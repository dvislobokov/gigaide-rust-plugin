package io.github.rustsupport.toolchain

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.testFramework.LightVirtualFile
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/** The error codes of rustc (`E0308`): from what the user types, from a diagnostic of rust-analyzer, and the text of `rustc --explain`. */
object RustErrorCodes {
    private val INPUT = Regex("""^[Ee]?0*(\d{1,4})$""")
    private val CODE = Regex("""^E\d{4}$""")
    private val IN_LINK = Regex("""\bE\d{4}\b""")

    /** `E0308`, `e0308`, `0308`, `308` → `E0308`; null for anything else. */
    fun normalize(input: String): String? {
        val match = INPUT.matchEntire(input.trim()) ?: return null
        val number = match.groupValues[1].toInt()
        return if (number == 0) null else "E%04d".format(number)
    }

    /**
     * The rustc error code of a diagnostic: its `code` (`E0308` from rustc through `cargo check`, and from the native diagnostics of
     * rust-analyzer that have one), else the code in its `codeDescription.href` (`…/error_codes/E0308.html`, `…/error-index.html#E0308`).
     * Lints (`unused_variables`, `clippy::needless_return`) have none: `rustc --explain` knows only the error codes.
     */
    fun fromDiagnostic(code: String?, href: String?): String? {
        code?.trim()?.takeIf { CODE.matches(it) }?.let { return it }
        if (href == null || !(href.contains("rust-lang.org") && (href.contains("error") || href.contains("E0")))) return null
        return IN_LINK.findAll(href).lastOrNull()?.value
    }

    /**
     * The output of `rustc --explain` as a Markdown page: a title, and the code blocks marked as Rust (rustdoc's are Rust unless said
     * otherwise: ```` ``` ````, ```` ```compile_fail,E0308 ````), so that a Markdown view highlights them.
     */
    fun markdown(code: String, explanation: String): String {
        val out = StringBuilder("# $code\n\n")
        var inFence = false
        for (line in explanation.lineSequence()) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```")) {
                if (!inFence) {
                    val info = trimmed.removePrefix("```").trim()
                    val attributes = info.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    val rust = attributes.isEmpty() || attributes.all { it == "rust" || it in RUSTDOC_ATTRIBUTES || it.matches(Regex("E\\d{4}|edition\\d{4}")) }
                    out.append(line.substring(0, line.length - trimmed.length)).append(if (rust) "```rust" else trimmed).append('\n')
                } else {
                    out.append(line).append('\n')
                }
                inFence = !inFence
            } else {
                out.append(line).append('\n')
            }
        }
        return out.toString().trimEnd() + "\n"
    }

    private val RUSTDOC_ATTRIBUTES = setOf("compile_fail", "should_panic", "no_run", "ignore", "edition2015", "edition2018", "edition2021", "edition2024")
}

/** What `rustc --explain` said. */
sealed interface RustExplanation {
    /** The Markdown of the explanation. */
    data class Text(val markdown: String) : RustExplanation
    /** rustc knows no such code: `error: E9999 is not a valid error code`. */
    data class Invalid(val message: String) : RustExplanation
    /** rustc is missing or did not run. */
    data class Failed(val message: String) : RustExplanation
}

/** The explanations by code: an explanation of a toolchain does not change, a failure (no rustc yet) is tried again next time. */
class RustExplanationCache(private val run: (String) -> RustExplanation) {
    private val explanations = ConcurrentHashMap<String, RustExplanation>()

    fun get(code: String): RustExplanation {
        explanations[code]?.let { return it }
        val explanation = run(code)
        if (explanation !is RustExplanation.Failed) explanations[code] = explanation
        return explanation
    }

    fun clear() = explanations.clear()
}

object RustErrorExplainer {
    private const val TIMEOUT_MS = 15_000
    private val EXPLANATION = Key.create<String>("rust.explanation.code")

    val cache = RustExplanationCache(::runRustc)

    /** `rustc --explain <code>`: local, the texts ship with the compiler. Blocks: off the EDT. */
    private fun runRustc(code: String): RustExplanation {
        val rustc = RustTool.RUSTC.find() ?: return RustExplanation.Failed("rustc is not found: install Rust with rustup")
        return try {
            val output = com.intellij.execution.process.CapturingProcessHandler(
                RustToolchain.commandLine(rustc, null, "--explain", code).withCharset(StandardCharsets.UTF_8),
            ).runProcess(TIMEOUT_MS)
            when {
                output.isTimeout -> RustExplanation.Failed("rustc --explain $code takes too long")
                output.exitCode == 0 && output.stdout.isNotBlank() -> RustExplanation.Text(RustErrorCodes.markdown(code, output.stdout.replace("\r\n", "\n")))
                output.stderr.contains("not a valid error code") -> RustExplanation.Invalid("$code is not an error code of rustc")
                else -> RustExplanation.Failed(output.stderr.lineSequence().firstOrNull { it.isNotBlank() } ?: "rustc --explain $code has failed")
            }
        } catch (e: Exception) {
            RustExplanation.Failed("rustc --explain $code has failed: ${e.message}")
        }
    }

    /** The explanation of [code] in a read-only editor tab (Markdown when the IDE has it), rustc run in the background. */
    fun show(project: Project, code: String) {
        open(project, code)?.let { return }
        object : Task.Backgroundable(project, "rustc --explain $code", true) {
            override fun run(indicator: ProgressIndicator) {
                val explanation = cache.get(code)
                ApplicationManager.getApplication().invokeLater({
                    when (explanation) {
                        is RustExplanation.Text -> open(project, code, explanation.markdown)
                        is RustExplanation.Invalid -> notify(project, explanation.message, NotificationType.WARNING)
                        is RustExplanation.Failed -> notify(project, explanation.message, NotificationType.ERROR)
                    }
                }, project.disposed)
            }
        }.queue()
    }

    /** Selects the tab of [code] when it is open; with [markdown], opens one. Returns the file or null. EDT. */
    private fun open(project: Project, code: String, markdown: String? = null): LightVirtualFile? {
        val editors = FileEditorManager.getInstance(project)
        editors.openFiles.filterIsInstance<LightVirtualFile>().firstOrNull { it.getUserData(EXPLANATION) == code }?.let {
            editors.openFile(it, true)
            return it
        }
        if (markdown == null) return null
        val type = FileTypeManager.getInstance().getFileTypeByExtension("md").takeIf { it !is UnknownFileType } ?: PlainTextFileType.INSTANCE
        val file = LightVirtualFile("$code.md", type, markdown).apply {
            isWritable = false
            putUserData(EXPLANATION, code)
        }
        editors.openFile(file, true)
        return file
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Rust").createNotification("Explain Rust error", message, type).notify(project)
    }
}

/** Tools | Rust | Explain Rust Error Code...: `rustc --explain` of a code typed in (the word at the caret, when it is one, is offered). */
class ExplainRustErrorCodeAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val initial = editor?.let {
            val selected = it.selectionModel.selectedText
            selected?.let(RustErrorCodes::normalize) ?: wordAt(it.document.immutableCharSequence, it.caretModel.offset)?.takeIf { w -> w.first() in "Ee" }?.let(RustErrorCodes::normalize)
        } ?: "E0"
        val input = Messages.showInputDialog(project, "Error code of rustc, such as E0308:", "Explain Rust Error Code", null, initial, object : InputValidator {
            override fun checkInput(inputString: String?): Boolean = inputString != null && RustErrorCodes.normalize(inputString) != null
            override fun canClose(inputString: String?): Boolean = checkInput(inputString)
        }) ?: return
        RustErrorCodes.normalize(input)?.let { RustErrorExplainer.show(project, it) }
    }

    private fun wordAt(text: CharSequence, offset: Int): String? {
        var start = offset.coerceIn(0, text.length)
        var end = start
        while (start > 0 && text[start - 1].isLetterOrDigit()) start--
        while (end < text.length && text[end].isLetterOrDigit()) end++
        return text.subSequence(start, end).toString().takeIf { it.isNotEmpty() }
    }
}
