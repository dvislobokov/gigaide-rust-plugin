package io.github.rustsupport.toolchain

import com.intellij.codeInsight.actions.onSave.FormatOnSaveOptions
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.ide.actionsOnSave.ActionOnSaveBackedByOwnConfigurable
import com.intellij.ide.actionsOnSave.ActionOnSaveContext
import com.intellij.ide.actionsOnSave.ActionOnSaveInfo
import com.intellij.ide.actionsOnSave.ActionOnSaveInfoProvider
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import io.github.rustsupport.lang.RustFileType
import io.github.rustsupport.settings.RustSettings
import io.github.rustsupport.settings.RustSettingsConfigurable
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

/**
 * The edition of a crate by its `Cargo.toml` (rustfmt formats `async`, `let … else`, `gen` and the like by the edition; rust-analyzer passes
 * it the edition of the crate in the same way). A line-based reading of the TOML, enough for the keys involved: `[package] edition = "2021"`,
 * `edition.workspace = true` / `edition = { workspace = true }` and `[workspace.package] edition = "2021"` of the workspace root.
 */
object CargoEdition {
    class Manifest(val isPackage: Boolean, val edition: String?, val inheritsEdition: Boolean, val workspaceEdition: String?)

    private val HEADER = Regex("""^\[\[?\s*([^\[\]]+?)\s*]]?""")
    private val KEY_VALUE = Regex("""^([A-Za-z0-9_.\-" ]+?)\s*=\s*(.+)$""")
    private val STRING = Regex("""^["']([^"']*)["']""")
    private val INHERITED = Regex("""^\{.*\bworkspace\s*=\s*true\b.*}""")

    fun parse(text: String): Manifest {
        var table = ""
        var isPackage = false
        var edition: String? = null
        var inherits = false
        var workspaceEdition: String? = null
        for (raw in text.lineSequence()) {
            val line = stripComment(raw).trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[")) {
                table = HEADER.find(line)?.groupValues?.get(1)?.replace(" ", "")?.replace("\"", "") ?: ""
                if (table == "package") isPackage = true
                continue
            }
            val match = KEY_VALUE.find(line) ?: continue
            val key = (if (table.isEmpty()) "" else "$table.") + match.groupValues[1].replace(" ", "").replace("\"", "")
            val value = match.groupValues[2].trim()
            when (key) {
                "package.edition" -> {
                    isPackage = true
                    STRING.find(value)?.let { edition = it.groupValues[1] }
                    if (INHERITED.matches(value)) inherits = true
                }
                "package.edition.workspace" -> {
                    isPackage = true
                    if (value == "true") inherits = true
                }
                "workspace.package.edition" -> STRING.find(value)?.let { workspaceEdition = it.groupValues[1] }
            }
        }
        return Manifest(isPackage, edition, inherits, workspaceEdition)
    }

    /**
     * The edition of the package of a file, from the manifests [nearestFirst] above it (the texts of `Cargo.toml` from its directory up):
     * the nearest package decides; an inherited edition comes from the nearest workspace root above it (the package's own manifest
     * included); a package without one is 2015, as Cargo has it. Null outside a package: rustfmt takes its own default.
     */
    fun resolve(nearestFirst: List<String>): String? {
        val manifests = nearestFirst.map(::parse)
        val index = manifests.indexOfFirst { it.isPackage }
        if (index < 0) return null
        val manifest = manifests[index]
        if (manifest.edition != null) return manifest.edition
        if (manifest.inheritsEdition) return manifests.drop(index).firstNotNullOfOrNull { it.workspaceEdition }
        return "2015"
    }

    /** `#` outside a string starts a comment. */
    private fun stripComment(line: String): String {
        var quote: Char? = null
        for ((i, c) in line.withIndex()) {
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '#' -> return line.substring(0, i)
            }
        }
        return line
    }

    /** The edition for a file in [directory], by the `Cargo.toml` files from there up. Reads the disk: off the EDT. */
    fun forDirectory(directory: Path): String? {
        val texts = ArrayList<String>()
        var dir: Path? = directory
        while (dir != null) {
            val manifest = dir.resolve("Cargo.toml")
            if (Files.isRegularFile(manifest)) runCatching { Files.readString(manifest) }.getOrNull()?.let { texts += it }
            dir = dir.parent
        }
        return resolve(texts)
    }
}

/** The smallest replacement that turns one text into another: carets, folds and breakpoints at the unchanged ends stay where they are. */
object RustTextDiff {
    class Replacement(val start: Int, val end: Int, val text: String)

    fun minimal(old: CharSequence, new: CharSequence): Replacement? {
        if (old.length == new.length && old.toString() == new.toString()) return null
        var prefix = 0
        val max = minOf(old.length, new.length)
        while (prefix < max && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < max - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
        return Replacement(prefix, old.length - suffix, new.subSequence(prefix, new.length - suffix).toString())
    }
}

object Rustfmt {
    private const val TIMEOUT_MS = 10_000
    private val LOG = logger<Rustfmt>()

    /** The arguments of rustfmt for stdin: the edition of the crate when there is one; rustfmt.toml is found from the working directory up. */
    fun arguments(edition: String?): List<String> = buildList {
        if (edition != null) {
            add("--edition")
            add(edition)
        }
        add("--emit")
        add("stdout")
    }

    /**
     * [text] formatted by rustfmt run in [directory] (the directory of the file: rustfmt looks for `rustfmt.toml` / `.rustfmt.toml` from
     * its working directory up when it reads stdin, as it does by the path of a file); null when rustfmt is missing, fails (a syntax error
     * is the business of the editor, not of saving) or takes too long. Blocks: off the EDT.
     */
    fun format(text: String, directory: Path?): String? {
        val exe = RustTool.RUSTFMT.find() ?: return null
        val edition = directory?.let { CargoEdition.forDirectory(it) }
        return try {
            val commandLine = RustToolchain.commandLine(exe, directory?.toString(), *arguments(edition).toTypedArray()).withCharset(StandardCharsets.UTF_8)
            val handler = CapturingProcessHandler(commandLine)
            handler.processInput.use { it.write(text.toByteArray(StandardCharsets.UTF_8)) }
            val output = handler.runProcess(TIMEOUT_MS)
            if (output.exitCode == 0 && !output.isTimeout && output.stdout.isNotEmpty()) StringUtil.convertLineSeparators(output.stdout) else null
        } catch (e: Exception) {
            LOG.info("rustfmt has failed: ${e.message}")
            null
        }
    }
}

/**
 * rustfmt over the Rust files of the project being saved (Settings | Tools | Actions on Save, and Settings | Languages & Frameworks |
 * Rust). The save itself is not held back: the platform forbids waiting for a process on the EDT, so the file is saved as typed,
 * formatted in the background, and saved again if its text has not changed meanwhile. That second save finds the text as rustfmt left
 * it and is skipped by its modification stamp: no save → format → save loop. The replacement is a command: Undo takes it back.
 * rustfmt runs directly rather than through rust-analyzer: the same formatter with the same edition and rustfmt.toml, and no wait for a
 * server that may be loading the workspace or not running at all. A project whose "Reformat code" on save of the platform covers Rust
 * files (through rust-analyzer) is left to it.
 */
class RustfmtOnSave : ActionsOnSaveFileDocumentManagerListener.ActionOnSave() {
    override fun isEnabledForProject(project: Project): Boolean = RustSettings.getInstance().rustfmtOnSave && !platformCovers(project)

    override fun processDocuments(project: Project, documents: Array<Document>) {
        val files = FileDocumentManager.getInstance()
        val index = ProjectFileIndex.getInstance(project)
        for (document in documents) {
            val file = files.getFile(document) ?: continue
            if (file.fileType != RustFileType || !file.isInLocalFileSystem || !index.isInContent(file)) continue
            // the save that follows our own formatting
            if (document.getUserData(FORMATTED_STAMP) == document.modificationStamp) continue
            if (!inFlight.add(document)) continue
            val text = document.immutableCharSequence.toString()
            val stamp = document.modificationStamp
            val directory = file.parent?.path?.let { Path.of(it) }
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val formatted = Rustfmt.format(text, directory) ?: return@executeOnPooledThread
                    val replacement = RustTextDiff.minimal(text, formatted) ?: return@executeOnPooledThread
                    ApplicationManager.getApplication().invokeLater({
                        // typed on since: the next save formats what is there then
                        if (document.modificationStamp != stamp || !file.isValid) return@invokeLater
                        WriteCommandAction.runWriteCommandAction(project, "Reformat with rustfmt", null, {
                            document.replaceString(replacement.start, replacement.end, replacement.text)
                        })
                        document.putUserData(FORMATTED_STAMP, document.modificationStamp)
                        files.saveDocument(document)
                    }, project.disposed)
                } finally {
                    inFlight.remove(document)
                }
            }
        }
    }

    companion object {
        private val FORMATTED_STAMP = Key.create<Long>("rust.rustfmt.formattedStamp")
        private val inFlight: MutableSet<Document> = Collections.synchronizedSet(Collections.newSetFromMap(java.util.WeakHashMap()))

        fun platformCovers(project: Project): Boolean =
            FormatOnSaveOptions.getInstance(project).let { it.isRunOnSaveEnabled && it.isFileTypeSelected(RustFileType) }

        const val NAME = "Reformat Rust code with rustfmt"
    }
}

/** The row of Settings | Tools | Actions on Save: the checkbox of Settings | Languages & Frameworks | Rust, read and set through that page. */
class RustfmtOnSaveInfoProvider : ActionOnSaveInfoProvider() {
    override fun getActionOnSaveInfos(context: ActionOnSaveContext): Collection<ActionOnSaveInfo> = listOf(Info(context))

    override fun getSearchableOptions(): Collection<String> = listOf(RustfmtOnSave.NAME)

    private class Info(context: ActionOnSaveContext) :
        ActionOnSaveBackedByOwnConfigurable<RustSettingsConfigurable>(context, RustSettingsConfigurable.ID, RustSettingsConfigurable::class.java) {
        override fun getActionOnSaveName(): String = RustfmtOnSave.NAME

        override fun isActionOnSaveEnabledAccordingToStoredState(): Boolean = RustSettings.getInstance().rustfmtOnSave

        override fun isActionOnSaveEnabledAccordingToUiState(configurable: RustSettingsConfigurable): Boolean = configurable.rustfmtOnSave.isSelected

        override fun setActionOnSaveEnabled(configurable: RustSettingsConfigurable, enabled: Boolean) {
            configurable.rustfmtOnSave.isSelected = enabled
        }

        override fun getActionLinks() = listOf(createGoToPageInSettingsLink(RustSettingsConfigurable.ID))
    }
}
