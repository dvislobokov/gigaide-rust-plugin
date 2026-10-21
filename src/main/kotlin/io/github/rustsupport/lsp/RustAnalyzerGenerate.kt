package io.github.rustsupport.lsp

import com.google.gson.JsonObject
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.customization.LspIntentionAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.CodeActionTriggerKind
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit

/**
 * The assists of rust-analyzer that generate code, as the Generate menu (Alt+Insert) lists them; pure, for the tests. An assist is
 * known by its id, which rust-analyzer puts into `data.id` of a code action as `generate_new:Generate:3:` when the client resolves
 * the edits later (the platform does; seen live with 1.99.1); without `data` its title is all there is, so the titles of the common
 * ones are known too.
 */
object RustAssists {
    /** An entry of the menu: the id of the assist and its text there. */
    private class Known(val id: String, val text: String)

    /** In the order of the menu. */
    private val KNOWN = listOf(
        Known("add_impl_missing_members", "Implement Missing Members"),
        Known("add_impl_default_members", "Implement Default Members"),
        Known("generate_new", "Constructor (new)"),
        Known("generate_getter", "Getter"),
        Known("generate_getter_mut", "Mutable Getter"),
        Known("generate_setter", "Setter"),
        Known("generate_impl", "impl Block"),
        Known("generate_trait_impl", "Trait impl Block"),
        Known("generate_default_from_new", "Default impl from new"),
        Known("generate_default_from_enum_variant", "Default impl from Variant"),
        Known("replace_derive_with_manual_impl", ""),
        Known("generate_derive", "#[derive]"),
        Known("generate_deref", ""),
        Known("generate_from_impl_for_enum", "From impl for Variant"),
        Known("generate_single_field_struct_from", "From impl"),
        Known("generate_documentation_template", "Documentation Template"),
        Known("generate_trait_from_impl", "Trait from impl"),
    )
    private val ORDER: Map<String, Int> = KNOWN.withIndex().associate { it.value.id to it.index }
    private val TEXTS: Map<String, String> = KNOWN.filter { it.text.isNotEmpty() }.associate { it.id to it.text }

    /** Assists named `generate_*` that create what a reference at the caret lacks: quick fixes of an error, not a choice of the menu. */
    private val FROM_USAGE = setOf("generate_function", "generate_constant", "generate_enum_variant", "generate_field", "generate_method")

    /** Titles of rust-analyzer 1.99.1 (seen live) for an answer without `data`. */
    private val TITLES = listOf(
        Regex("^Implement missing members$") to "add_impl_missing_members",
        Regex("^Implement default members$") to "add_impl_default_members",
        Regex("^Generate `new`$") to "generate_new",
        Regex("^Generate a getter method$") to "generate_getter",
        Regex("^Generate a mut getter method$") to "generate_getter_mut",
        Regex("^Generate a setter method$") to "generate_setter",
        Regex("^Generate impl for `") to "generate_impl",
        Regex("^Generate trait impl for `") to "generate_trait_impl",
        Regex("^Generate `Deref` impl") to "generate_deref",
        Regex("^Generate single field `From`$") to "generate_single_field_struct_from",
        Regex("^Convert to manual `impl ") to "replace_derive_with_manual_impl",
    )

    /** `generate_new` of `{"id": "generate_new:Generate:3:", ...}`. */
    fun idOfData(data: Any?): String? {
        val id = (data as? JsonObject)?.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: (data as? Map<*, *>)?.get("id") as? String
        return id?.substringBefore(':')?.takeIf { it.isNotEmpty() }
    }

    fun id(data: Any?, title: String?): String? = idOfData(data) ?: TITLES.firstOrNull { it.first.containsMatchIn(title.orEmpty()) }?.second

    /** Whether an assist belongs to the Generate menu. */
    fun isGenerate(id: String?): Boolean = id != null && (id in ORDER || id.startsWith("generate_") && id !in FROM_USAGE)

    /** The text in the menu: a short one for the known assists, the title of the server for the rest (it names the type or the trait). */
    fun text(id: String, title: String): String = TEXTS[id] ?: title

    /** An assist offered at the caret: [index] is its place in the answer of the server. */
    class Offered(val id: String, val title: String, val index: Int)

    /** The menu out of the answer: the generating assists in the order of [KNOWN], the rest after them, an assist once. */
    fun menu(offered: List<Offered>): List<Offered> = offered.filter { isGenerate(it.id) }.distinctBy { it.id to it.title }
        .sortedWith(compareBy<Offered> { ORDER[it.id] ?: Int.MAX_VALUE }.thenBy { it.index })

    /**
     * Where the caret goes after the edits ([applied] of [RustAnalyzerProtocol.apply] on the original text): to `$0` when the server put
     * one, else to the first character that is not a blank of the first edit that inserts something, in the text after the edits. Null
     * when nothing is inserted.
     */
    fun caret(applied: RustAnalyzerProtocol.Applied): Int? {
        applied.caret?.let { return it }
        val edits = applied.edits
        val target = edits.filter { it.text.isNotBlank() }.minWithOrNull(compareBy<RustAnalyzerProtocol.OffsetEdit> { it.start }.thenBy { it.end }) ?: return null
        val shift = edits.filter { it !== target && it.start < target.start }.sumOf { it.text.length - (it.end - it.start) }
        return target.start + shift + target.text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
    }

    /**
     * The text edits of a workspace edit by the uri of their file, in the shape [RustAnalyzerProtocol.apply] takes; null when the edit
     * does more than change text (creates, renames or deletes a file), which is left to the platform.
     */
    fun textEdits(edit: WorkspaceEdit): Map<String, List<RustAnalyzerProtocol.Edit>>? {
        val out = LinkedHashMap<String, MutableList<RustAnalyzerProtocol.Edit>>()
        for (change in edit.documentChanges.orEmpty()) {
            if (!change.isLeft) return null
            val documentEdit = change.left
            out.getOrPut(documentEdit.textDocument.uri) { ArrayList() } += documentEdit.edits.orEmpty().map(::edit)
        }
        for ((uri, edits) in edit.changes.orEmpty()) out.getOrPut(uri) { ArrayList() } += edits.orEmpty().map(::edit)
        return out
    }

    private fun edit(edit: TextEdit): RustAnalyzerProtocol.Edit =
        RustAnalyzerProtocol.Edit(edit.range.start.line, edit.range.start.character, edit.range.end.line, edit.range.end.character, edit.newText.orEmpty())
}

/**
 * Generate (Alt+Insert) in a Rust file: the assists of rust-analyzer that generate code at the caret (Implement Missing Members,
 * Constructor, Getter, Setter, impl blocks, Default, derives...), only those the server offers there. The server is asked while the
 * menu is built, with a short timeout, once per text and caret.
 */
class RustGenerateGroup : ActionGroup(), DumbAware {
    private class Asked(val document: Document, val stamp: Long, val offset: Int, val actions: Array<AnAction>)

    @Volatile private var last: Asked? = null

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val event = e ?: return EMPTY_ARRAY
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return EMPTY_ARRAY
        if (RustEditorContext.rustFile(editor) == null) return EMPTY_ARRAY
        val document = editor.document
        val offset = editor.caretModel.offset
        last?.takeIf { it.document == document && it.stamp == document.modificationStamp && it.offset == offset }?.let { return it.actions }
        val context = RustEditorContext.of(editor) ?: return EMPTY_ARRAY
        val actions = RustAssistRequests.codeActions(context, TIMEOUT_MS)
        val offered = actions.mapIndexedNotNull { index, action -> RustAssists.id(action.data, action.title)?.let { RustAssists.Offered(it, action.title.orEmpty(), index) } }
        val children: Array<AnAction> = RustAssists.menu(offered).map { RustAssistAction(RustAssists.text(it.id, it.title), it.id, it.title) }.toTypedArray()
        last = Asked(document, document.modificationStamp, offset, children)
        return children
    }

    private companion object {
        // the menu waits for it; the server answers in milliseconds once it has loaded the workspace (seen live)
        const val TIMEOUT_MS = 1_500
    }
}

/** An assist of the menu: asks the server again at the caret (the answer of the menu may be gone), resolves its edit and applies it. */
class RustAssistAction(text: String, private val id: String, private val title: String) : AnAction(text, title, null), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val context = RustEditorContext.of(editor) ?: return hint(editor, "rust-analyzer is not running")
        val project = context.project
        val resolved = ProgressManager.getInstance().runProcessWithProgressSynchronously<CodeAction?, RuntimeException>({
            val offered = RustAssistRequests.codeActions(context, TIMEOUT_MS).filter { RustAssists.id(it.data, it.title) == id }
            val action = offered.firstOrNull { it.title == title } ?: offered.firstOrNull()
            action?.let { if (it.edit == null) RustAssistRequests.resolve(context.client, it, TIMEOUT_MS) else it }
        }, "Asking rust-analyzer for \"$title\"", true, project)
        val edit = resolved?.edit ?: return hint(editor, "rust-analyzer offers no \"$title\" here")
        val byUri = RustAssists.textEdits(edit)
        if (byUri == null) return RustAssistRequests.applyByPlatform(project, editor, context.client, resolved)
        apply(project, editor, context.client, byUri)
    }

    private fun apply(project: Project, editor: Editor, client: LspClient, byUri: Map<String, List<RustAnalyzerProtocol.Edit>>) {
        val documents = byUri.mapNotNull { (uri, edits) ->
            val file = client.descriptor.findFileByUri(uri) ?: return@mapNotNull null
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@mapNotNull null
            document to RustAnalyzerProtocol.apply(document.immutableCharSequence, edits)
        }
        if (documents.isEmpty() || documents.any { !it.first.isWritable }) return hint(editor, "The edit of \"$title\" cannot be applied")
        val psiFiles = documents.mapNotNull { PsiDocumentManager.getInstance(project).getPsiFile(it.first) }.toTypedArray<PsiFile>()
        WriteCommandAction.writeCommandAction(project, *psiFiles).withName(title).run<RuntimeException> {
            for ((document, applied) in documents) {
                for (edit in RustAnalyzerProtocol.descending(applied.edits)) document.replaceString(edit.start, edit.end, edit.text)
            }
            documents.firstOrNull { it.first == editor.document }?.let { (document, applied) ->
                RustAssists.caret(applied)?.let { editor.caretModel.moveToOffset(it.coerceIn(0, document.textLength)) }
                editor.selectionModel.removeSelection()
            }
        }
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
    }

    private fun hint(editor: Editor, text: String) {
        if (!editor.isDisposed) HintManager.getInstance().showInformationHint(editor, text)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
    }
}

/** Code actions of rust-analyzer at the caret: the answer or nothing, never an exception out of a server that is busy or gone. */
internal object RustAssistRequests {
    fun codeActions(context: RustEditorContext, timeoutMs: Int): List<CodeAction> {
        val (document, editor) = context.document to context.editor
        val (start, end) = ReadAction.compute<Pair<Int, Int>, RuntimeException> {
            val selection = editor.selectionModel
            if (selection.hasSelection()) selection.selectionStart to selection.selectionEnd else editor.caretModel.offset to editor.caretModel.offset
        }
        val range = Range(RustAnalyzer.position(document, start), RustAnalyzer.position(document, end))
        val params = CodeActionParams(context.client.getDocumentIdentifier(context.file), range, CodeActionContext(emptyList()).apply { triggerKind = CodeActionTriggerKind.Invoked })
        return send(context.client, "textDocument/codeAction", timeoutMs) { it.textDocumentService.codeAction(params) }
            .orEmpty().mapNotNull { if (it.isRight) it.right else null }
    }

    fun resolve(client: LspClient, action: CodeAction, timeoutMs: Int): CodeAction? =
        send(client, "codeAction/resolve", timeoutMs) { it.textDocumentService.resolveCodeAction(action) }

    /** An edit that creates or renames files: the platform applies it (asked for its edit first, or its `invoke` does nothing; see the Go plugin). */
    fun applyByPlatform(project: Project, editor: Editor, client: LspClient, action: CodeAction) {
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        val prepared = ProgressManager.getInstance().runProcessWithProgressSynchronously<LspIntentionAction?, RuntimeException>({
            LspIntentionAction(client, action).takeIf { intention -> ReadAction.compute<Boolean, RuntimeException> { intention.isAvailable(project, editor, file) } }
        }, "Asking rust-analyzer for the edit", true, project)
        prepared?.invoke(project, editor, file)
    }

    private fun <T> send(client: LspClient, method: String, timeoutMs: Int, call: (org.eclipse.lsp4j.services.LanguageServer) -> java.util.concurrent.CompletableFuture<T>): T? = try {
        client.sendRequestSync(timeoutMs, call)
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Exception) {
        RustAnalyzerLog.getInstance(client.project).error("$method has failed: ${e.message ?: e.javaClass.simpleName}")
        null
    }
}
