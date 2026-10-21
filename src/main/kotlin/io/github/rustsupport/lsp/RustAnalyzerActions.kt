package io.github.rustsupport.lsp

import com.google.gson.JsonElement
import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.testFramework.LightVirtualFile
import io.github.rustsupport.lang.RustFileType
import io.github.rustsupport.lang.RustLexer
import io.github.rustsupport.lang.RustTokenTypes
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentPositionParams
import java.util.concurrent.CompletableFuture

/** The Rust file of an editor (on disk: the server knows only those), with the server that is running for it. */
internal class RustEditorContext(val project: Project, val editor: Editor, val file: VirtualFile, val client: LspClient) {
    val document: Document get() = editor.document

    fun position(): Position = RustAnalyzer.position(document, editor.caretModel.offset)

    fun positionParams(): TextDocumentPositionParams = TextDocumentPositionParams(client.getDocumentIdentifier(file), position())

    fun documentParams(): TextDocumentParams = TextDocumentParams(client.getDocumentIdentifier(file))

    companion object {
        fun rustFile(editor: Editor): VirtualFile? =
            FileDocumentManager.getInstance().getFile(editor.document)?.takeIf { it.isInLocalFileSystem && RustAnalyzerIntegrationProvider.isRustFile(it) }

        fun of(e: AnActionEvent): RustEditorContext? {
            val project = e.project ?: return null
            val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
            val file = rustFile(editor) ?: return null
            val client = RustAnalyzer.client(project) ?: return null
            return RustEditorContext(project, editor, file, client)
        }

        fun of(editor: Editor): RustEditorContext? {
            val project = editor.project ?: return null
            val file = rustFile(editor) ?: return null
            val client = RustAnalyzer.client(project) ?: return null
            return RustEditorContext(project, editor, file, client)
        }

        /** For `update`: a Rust editor is there; the server may still be starting, the action says so when it is invoked. */
        fun isRustEditor(e: AnActionEvent): Boolean = e.project != null && e.getData(CommonDataKeys.EDITOR)?.let(::rustFile) != null
    }
}

/** The tabs of the plugin: text in memory, read-only, highlighted as the given type. */
object RustAnalyzerTabs {
    fun open(project: Project, name: String, text: String, fileType: FileType) {
        val file = LightVirtualFile(name, fileType, text).apply { isWritable = false }
        FileEditorManager.getInstance(project).openFile(file, true)
    }
}

/** Asks the server off the UI thread, behind a progress that can be cancelled; the answer is handled back on the UI thread. */
private fun askInBackground(context: RustEditorContext, title: String, ask: () -> JsonElement?, done: (JsonElement?) -> Unit) {
    ProgressManager.getInstance().run(object : Task.Backgroundable(context.project, title, true) {
        private var answer: JsonElement? = null
        override fun run(indicator: ProgressIndicator) {
            answer = ask()
        }

        override fun onSuccess() {
            if (!context.project.isDisposed) done(answer)
        }
    })
}

private fun hint(editor: Editor, text: String) {
    if (!editor.isDisposed) HintManager.getInstance().showInformationHint(editor, text)
}

private const val VIEW_TIMEOUT_MS = 10_000

/** Base of the actions on the caret of a Rust editor. */
abstract class RustEditorAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = RustEditorContext.isRustEditor(e)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val context = RustEditorContext.of(e) ?: return hint(editor, "rust-analyzer is not running")
        perform(context)
    }

    internal abstract fun perform(context: RustEditorContext)
}

/** Expand Macro Recursively: what the macro call under the caret becomes, in a tab of its own (`rust-analyzer/expandMacro`). */
class RustExpandMacroAction : RustEditorAction() {
    override fun perform(context: RustEditorContext) {
        val (project, editor, client) = Triple(context.project, context.editor, context.client)
        val params = context.positionParams()
        askInBackground(context, "Expanding macro", { RustAnalyzerRequests.send(client, "rust-analyzer/expandMacro", VIEW_TIMEOUT_MS) { it.expandMacro(params) } }) { answer ->
            val macro = RustAnalyzerProtocol.expandedMacro(answer) ?: return@askInBackground hint(editor, "No macro call at the caret")
            RustAnalyzerTabs.open(project, "${macro.name.removeSuffix("!")}! expansion.rs", RustAnalyzerProtocol.expansionText(macro), RustFileType)
        }
    }
}

/** Go to Parent Module (`experimental/parentModule`): the `mod` item of the file, or Cargo.toml for the root of a crate (seen live). */
class RustGoToParentModuleAction : RustEditorAction() {
    override fun perform(context: RustEditorContext) {
        val (project, editor, client) = Triple(context.project, context.editor, context.client)
        val params = context.positionParams()
        askInBackground(context, "Finding parent module", { RustAnalyzerRequests.send(client, "experimental/parentModule", VIEW_TIMEOUT_MS) { it.parentModule(params) } }) { answer ->
            val targets = RustAnalyzerProtocol.targets(answer).mapNotNull { target ->
                client.descriptor.findFileByUri(target.uri)?.let { OpenFileDescriptor(project, it, target.line, target.character) }
            }
            when (targets.size) {
                0 -> hint(editor, "No parent module")
                1 -> targets.single().navigate(true)
                else -> JBPopupFactory.getInstance().createListPopup(object : BaseListPopupStep<OpenFileDescriptor>("Parent Modules", targets) {
                    override fun getTextFor(value: OpenFileDescriptor): String = "${value.file.presentableUrl}:${value.line + 1}"
                    override fun onChosen(selectedValue: OpenFileDescriptor, finalChoice: Boolean): PopupStep<*>? {
                        doFinalStep { selectedValue.navigate(true) }
                        return FINAL_CHOICE
                    }
                }).showInBestPositionFor(editor)
            }
        }
    }
}

/** The debug views of rust-analyzer (Tools | Rust | Debug): its text in a tab. */
abstract class RustAnalyzerViewAction(private val what: String, private val needsEditor: Boolean = true) : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = if (needsEditor) RustEditorContext.isRustEditor(e) else e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val client = RustAnalyzer.client(project)
        if (client == null) {
            editor?.let { hint(it, "rust-analyzer is not running") }
            return RustAnalyzerLog.getInstance(project).error("$what: rust-analyzer is not running")
        }
        val context = editor?.let { RustEditorContext.of(it) }
        if (needsEditor && context == null) return
        val ask = request(context) ?: return
        val document = context?.document
        val fileName = context?.file?.name
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, what, true) {
            private var text: String? = null
            override fun run(indicator: ProgressIndicator) {
                text = RustAnalyzerProtocol.text(RustAnalyzerRequests.send(client, method, VIEW_TIMEOUT_MS, ask))
                    ?.let { answer -> document?.let { render(answer, it.immutableCharSequence) } ?: answer }
            }

            override fun onSuccess() {
                val shown = text
                if (project.isDisposed) return
                if (shown == null) editor?.let { hint(it, "rust-analyzer has no answer, see its log") }
                else RustAnalyzerTabs.open(project, listOfNotNull(what, fileName).joinToString(" of ") + extension, shown, fileType)
            }
        })
    }

    protected abstract val method: String
    protected open val fileType: FileType get() = PlainTextFileType.INSTANCE
    protected open val extension: String get() = ".txt"

    /** The request of the view; null when it cannot be asked without an editor. */
    internal abstract fun request(context: RustEditorContext?): ((RustAnalyzerServer) -> CompletableFuture<JsonElement?>)?

    protected open fun render(answer: String, fileText: CharSequence): String = answer
}

class RustViewSyntaxTreeAction : RustAnalyzerViewAction("Syntax Tree") {
    override val method = "rust-analyzer/viewSyntaxTree"
    override fun request(context: RustEditorContext?) = context?.let { c -> { s: RustAnalyzerServer -> s.viewSyntaxTree(c.documentParams()) } }
    override fun render(answer: String, fileText: CharSequence): String = RustAnalyzerProtocol.syntaxTree(answer, fileText)
}

class RustViewHirAction : RustAnalyzerViewAction("HIR") {
    override val method = "rust-analyzer/viewHir"
    override val fileType: FileType get() = RustFileType
    override val extension: String get() = ".rs"
    override fun request(context: RustEditorContext?) = context?.let { c -> { s: RustAnalyzerServer -> s.viewHir(c.positionParams()) } }
}

class RustViewMirAction : RustAnalyzerViewAction("MIR") {
    override val method = "rust-analyzer/viewMir"
    override fun request(context: RustEditorContext?) = context?.let { c -> { s: RustAnalyzerServer -> s.viewMir(c.positionParams()) } }
}

class RustViewItemTreeAction : RustAnalyzerViewAction("Item Tree") {
    override val method = "rust-analyzer/viewItemTree"
    override val fileType: FileType get() = RustFileType
    override val extension: String get() = ".rs"
    override fun request(context: RustEditorContext?) = context?.let { c -> { s: RustAnalyzerServer -> s.viewItemTree(c.documentParams()) } }
}

class RustViewCrateGraphAction : RustAnalyzerViewAction("Crate Graph", needsEditor = false) {
    override val method = "rust-analyzer/viewCrateGraph"
    override val extension: String get() = ".dot"
    override fun request(context: RustEditorContext?) = { s: RustAnalyzerServer -> s.viewCrateGraph(CrateGraphParams(false)) }
}

class RustAnalyzerStatusAction : RustAnalyzerViewAction("Analyzer Status", needsEditor = false) {
    override val method = "rust-analyzer/analyzerStatus"
    override fun request(context: RustEditorContext?) = { s: RustAnalyzerServer -> s.analyzerStatus(context?.documentParams() ?: TextDocumentParams(null)) }
}

/**
 * Enter right after a `{` whose block goes on in the same line (`fn f() { a + b }`): rust-analyzer's `experimental/onEnter` opens the
 * block around its contents. Asked only there ([RustOnEnterGate]), with a short timeout; any other Enter, or no answer in time, is the
 * Enter of the IDE (with the comments continued by [io.github.rustsupport.lang.RustCommentEnterHandler] and `{|}` opened by
 * [io.github.rustsupport.lang.RustEnterBetweenBracketsHandler]). A wrapper of the Enter action rather than an [com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate]:
 * the delegates run inside the write action, where waiting for the server would hold every other thread of the IDE.
 */
class RustAnalyzerEnterHandler(private val original: EditorActionHandler) : EditorActionHandler() {
    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean = original.isEnabled(editor, caret, dataContext)

    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        if (!byServer(editor)) original.execute(editor, caret, dataContext)
    }

    private fun byServer(editor: Editor): Boolean {
        val app = ApplicationManager.getApplication()
        if (app.isWriteAccessAllowed || editor.isViewer || editor.caretModel.caretCount > 1 || editor.selectionModel.hasSelection()) return false
        if (LookupManager.getActiveLookup(editor) != null) return false
        val offset = editor.caretModel.offset
        if (!RustOnEnterGate.asksServer(editor.document.immutableCharSequence, offset)) return false
        val context = RustEditorContext.of(editor) ?: return false
        val params = context.positionParams()
        val answer = RustAnalyzerRequests.send(context.client, "experimental/onEnter", TIMEOUT_MS) { it.onEnter(params) }
        val edits = RustAnalyzerProtocol.edits(answer)?.takeIf { it.isNotEmpty() } ?: return false
        val document = editor.document
        val applied = RustAnalyzerProtocol.apply(document.immutableCharSequence, edits)
        if (!document.isWritable) return false
        app.runWriteAction {
            for (edit in RustAnalyzerProtocol.descending(applied.edits)) document.replaceString(edit.start, edit.end, edit.text)
        }
        applied.caret?.let { editor.caretModel.moveToOffset(it.coerceIn(0, document.textLength)) }
        editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
        return true
    }

    private companion object {
        // on the UI thread, at every such Enter: a server that has not answered by then is not waited for
        const val TIMEOUT_MS = 300
    }
}

/** When Enter is worth a question to the server: right after a real `{` (not in a string or a comment) with code after the caret in its line. */
object RustOnEnterGate {
    fun asksServer(text: CharSequence, offset: Int): Boolean {
        if (offset < 1 || offset > text.length || text[offset - 1] != '{') return false
        // `{|}` and `{|` at the end of a line are the Enter of the IDE
        var i = offset
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        if (i >= text.length || text[i] == '\n' || text[i] == '\r' || text[i] == '}') return false
        val lexer = RustLexer()
        lexer.start(text, 0, text.length, 0)
        while (lexer.tokenType != null && lexer.tokenEnd < offset) lexer.advance()
        return lexer.tokenType == RustTokenTypes.LBRACE && lexer.tokenEnd == offset
    }
}

/**
 * Join Lines (Ctrl+Shift+J) in a Rust file by rust-analyzer (`experimental/joinLines`): it knows the syntax, so `foo(1,\n 2)` becomes
 * `foo(1, 2)`, a block of one expression loses its braces, a `use` list merges. With no server or no answer, the Join Lines of the IDE.
 */
class RustAnalyzerJoinLinesHandler(private val original: EditorActionHandler) : EditorActionHandler() {
    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean = original.isEnabled(editor, caret, dataContext)

    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        if (!byServer(editor)) original.execute(editor, caret, dataContext)
    }

    private fun byServer(editor: Editor): Boolean {
        val app = ApplicationManager.getApplication()
        if (app.isWriteAccessAllowed || editor.isViewer || editor.caretModel.caretCount > 1) return false
        val context = RustEditorContext.of(editor) ?: return false
        val document = editor.document
        val selection = editor.selectionModel
        val range = if (selection.hasSelection()) Range(RustAnalyzer.position(document, selection.selectionStart), RustAnalyzer.position(document, selection.selectionEnd))
        else context.position().let { Range(it, it) }
        val params = JoinLinesParams(context.client.getDocumentIdentifier(context.file), listOf(range))
        val answer = RustAnalyzerRequests.send(context.client, "experimental/joinLines", TIMEOUT_MS) { it.joinLines(params) }
        val edits = RustAnalyzerProtocol.edits(answer)?.takeIf { it.isNotEmpty() } ?: return false
        if (!document.isWritable) return false
        val applied = RustAnalyzerProtocol.apply(document.immutableCharSequence, edits)
        val selected = if (selection.hasSelection()) document.createRangeMarker(selection.selectionStart, selection.selectionEnd) else null
        app.runWriteAction {
            for (edit in RustAnalyzerProtocol.descending(applied.edits)) document.replaceString(edit.start, edit.end, edit.text)
        }
        if (selected != null) {
            if (selected.isValid) selection.setSelection(selected.startOffset, selected.endOffset)
            selected.dispose()
        } else {
            // where the lines have met, as the Join Lines of the IDE leaves it: the edits are applied from the end, the first keeps its offset
            applied.edits.minOfOrNull { it.start }?.let { editor.caretModel.moveToOffset(it.coerceIn(0, document.textLength)) }
        }
        editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
        return true
    }

    private companion object {
        // the user has asked and waits for it; the server answers in milliseconds (seen live)
        const val TIMEOUT_MS = 1_000
    }
}
