package io.github.rustsupport.run

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A zero-based position of the protocol: line and UTF-16 column. */
data class LspPosition(val line: Int, val character: Int)

/**
 * A runnable of rust-analyzer (`experimental/runnables`): `run app`, `test tests::it_works`, `test-mod tests`, `bench ...`, `doctest ...`.
 * [nameStart] is where its name starts (`targetSelectionRange`), [rangeStart]..[rangeEnd] the whole item (`targetRange`); no location
 * for the runnables of a whole package.
 */
data class CargoRunnable(
    val label: String,
    val uri: String?,
    val nameStart: LspPosition?,
    val rangeStart: LspPosition?,
    val rangeEnd: LspPosition?,
    val workspaceRoot: String?,
    val cwd: String?,
    val cargoArgs: List<String>,
    val executableArgs: List<String>,
    val environment: Map<String, String>,
) {
    /** The command as a configuration holds it. */
    fun invocation(): CargoInvocation = CargoInvocation.parse(cargoArgs, executableArgs)

    /** A name for the configuration: `Test it_works`, `Run app`, `Test tests` for a module. */
    fun configurationName(): String {
        val kind = label.substringBefore(' ')
        val target = label.substringAfter(' ', "").trim()
        val title = when (kind) {
            "run" -> "Run"
            "test" -> "Test"
            "test-mod" -> "Test"
            "bench" -> "Bench"
            "doctest" -> "Doctest"
            else -> return label
        }
        return if (target.isEmpty()) title else "$title ${target.substringAfterLast("::").ifEmpty { target }}"
    }
}

/** The answer of `experimental/runnables`, pure for the tests: only the runnables of cargo (`kind: "cargo"`). */
object CargoRunnables {
    fun parse(json: String?): List<CargoRunnable> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JsonParser.parseString(json) }.getOrNull()?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonObject)?.let(::runnable) }
    }

    private fun runnable(item: JsonObject): CargoRunnable? {
        val kind = item.string("kind")
        if (kind != null && kind != "cargo") return null
        val args = item.obj("args") ?: return null
        val location = item.obj("location")
        val cargoArgs = args.strings("cargoArgs") + args.strings("cargoExtraArgs")
        if (cargoArgs.isEmpty()) return null
        return CargoRunnable(
            label = item.string("label").orEmpty(),
            uri = location?.string("targetUri"),
            nameStart = (location?.obj("targetSelectionRange") ?: location?.obj("targetRange"))?.obj("start")?.let(::position),
            rangeStart = location?.obj("targetRange")?.obj("start")?.let(::position),
            rangeEnd = location?.obj("targetRange")?.obj("end")?.let(::position),
            workspaceRoot = args.string("workspaceRoot"),
            cwd = args.string("cwd") ?: args.string("workspaceRoot"),
            cargoArgs = cargoArgs,
            executableArgs = args.strings("executableArgs"),
            environment = args.obj("environment")?.entrySet()?.mapNotNull { (k, v) -> v.takeIf { it.isJsonPrimitive }?.let { k to it.asString } }?.toMap().orEmpty(),
        )
    }

    private fun position(json: JsonObject): LspPosition? {
        val line = json.get("line")?.takeIf { it.isJsonPrimitive }?.asInt ?: return null
        return LspPosition(line, json.get("character")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0)
    }

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.obj(name: String): JsonObject? = get(name) as? JsonObject
    private fun JsonObject.strings(name: String): List<String> = (get(name) as? JsonArray)?.mapNotNull { e: JsonElement -> e.takeIf { it.isJsonPrimitive }?.asString }.orEmpty()

    /** The offset of [position] in [document], clamped to its line. */
    fun offset(document: Document, position: LspPosition): Int {
        if (position.line >= document.lineCount) return document.textLength
        return (document.getLineStartOffset(position.line) + position.character).coerceAtMost(document.getLineEndOffset(position.line))
    }
}

/**
 * Where the runnables come from: rust-analyzer, in the content module of the LSP client (`io.github.rustsupport.lsp`), which the main
 * part of the plugin must not touch. None registered (an IDE without the LSP client): the gutter falls back to `fn main` of the lexer.
 */
interface RustRunnableSource {
    /** A server is up and can be asked. */
    fun isReady(project: Project): Boolean

    /** The JSON array of `experimental/runnables` for [file]; null when the server failed to answer. Blocks: call off the EDT. */
    fun fetchRunnables(project: Project, file: VirtualFile): String?

    companion object {
        val EP: ExtensionPointName<RustRunnableSource> = ExtensionPointName.create("io.github.rustsupport.runnableSource")

        fun get(): RustRunnableSource? = EP.extensionList.firstOrNull()
    }
}

/**
 * The runnables of a file per modification stamp of its document. Never blocks: a file not asked for yet is asked in the background,
 * and the gutter is redrawn when the answer comes. rust-analyzer answers nothing while it loads the workspace: an empty answer is asked
 * again a few times.
 */
@Service(Service.Level.PROJECT)
class CargoRunnablesCache(private val project: Project) {
    private class Entry(val stamp: Long, val runnables: List<CargoRunnable>?, val attempts: Int)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** The runnables of [file] as known for the current text, or null when they are not known (yet). */
    fun runnables(file: VirtualFile, document: Document): List<CargoRunnable>? {
        val source = RustRunnableSource.get() ?: return null
        val entry = entries[file.url]
        if (entry != null && entry.stamp == document.modificationStamp && entry.runnables != null) return entry.runnables
        if (source.isReady(project)) request(source, file, document.modificationStamp, entry?.takeIf { it.stamp == document.modificationStamp }?.attempts ?: 0, 0)
        // the answer for an older text: better than nothing, the positions move little while typing
        return entry?.runnables
    }

    /** The last runnables known for [file], whatever text they were for. */
    fun lastKnown(file: VirtualFile): List<CargoRunnable>? = entries[file.url]?.runnables

    private fun request(source: RustRunnableSource, file: VirtualFile, stamp: Long, attempts: Int, delaySeconds: Long) {
        if (!inFlight.add(file.url)) return
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            var retry = false
            try {
                if (!project.isDisposed) {
                    val document = runReadAction { FileDocumentManager.getInstance().getDocument(file) }
                    if (document != null && document.modificationStamp == stamp) {
                        val json = source.fetchRunnables(project, file)
                        val runnables = CargoRunnables.parse(json)
                        val previous = entries[file.url]?.runnables
                        entries[file.url] = Entry(stamp, runnables.takeIf { json != null }, attempts + 1)
                        if (runnables != previous) restartDaemon(file)
                        retry = runnables.isEmpty() && attempts < MAX_ATTEMPTS && runReadAction { mayHaveRunnables(document) }
                    }
                }
            } catch (e: Exception) {
                if (e !is com.intellij.openapi.progress.ProcessCanceledException) LOG.info("runnables of ${file.path}: $e")
            } finally {
                inFlight.remove(file.url)
            }
            if (retry) request(source, file, stamp, attempts + 1, RETRY_SECONDS)
        }, delaySeconds, TimeUnit.SECONDS)
    }

    private fun mayHaveRunnables(document: Document): Boolean {
        val text = document.immutableCharSequence
        return text.contains("fn main") || text.contains("#[test]") || text.contains("#[bench]") || text.contains("```")
    }

    private fun restartDaemon(file: VirtualFile) {
        ApplicationManager.getApplication().invokeLater({
            val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@invokeLater
            DaemonCodeAnalyzer.getInstance(project).restart(psiFile, "the runnables of rust-analyzer have changed")
        }, project.disposed)
    }

    companion object {
        private val LOG = logger<CargoRunnablesCache>()
        private const val MAX_ATTEMPTS = 10
        private const val RETRY_SECONDS = 3L

        fun getInstance(project: Project): CargoRunnablesCache = project.service()
    }
}
