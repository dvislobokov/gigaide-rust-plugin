package io.github.rustsupport.lsp

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.elementType
import io.github.rustsupport.lang.RustFile
import io.github.rustsupport.lang.RustTokenTypes
import org.eclipse.lsp4j.DeclarationParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.ImplementationParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture

/** Requests to the rust-analyzer the platform has started, for what its LSP client does not do; the connection stays the platform's. */
object RustAnalyzer {
    private val LOG = logger<RustAnalyzer>()

    /** A place in a file, as offsets. */
    class Place(val file: VirtualFile, val startOffset: Int, val endOffset: Int)

    fun client(project: Project): LspClient? =
        LspClientManager.getInstance(project).getClients(RustAnalyzerIntegrationProvider::class.java).firstOrNull { it.state == LspServerState.Running }

    /** Columns of the protocol are UTF-16 code units, as the offsets of a Java string are. */
    fun position(document: Document, offset: Int): Position {
        val line = document.getLineNumber(offset.coerceIn(0, document.textLength))
        return Position(line, offset - document.getLineStartOffset(line))
    }

    fun definition(client: LspClient, file: VirtualFile, position: Position, timeoutMs: Int): List<Place> =
        locations(client, request(client, timeoutMs) { it.textDocumentService.definition(DefinitionParams(client.getDocumentIdentifier(file), position)) })

    /** The impl blocks of a trait or a type, the items of the impls that define a trait item (seen live with 1.99.1). */
    fun implementation(client: LspClient, file: VirtualFile, position: Position, timeoutMs: Int): List<Place> =
        locations(client, request(client, timeoutMs) { it.textDocumentService.implementation(ImplementationParams(client.getDocumentIdentifier(file), position)) })

    /** For an item of a trait impl: the item of the trait it implements (seen live with 1.99.1); nothing for an inherent one. */
    fun declaration(client: LspClient, file: VirtualFile, position: Position, timeoutMs: Int): List<Place> =
        locations(client, request(client, timeoutMs) { it.textDocumentService.declaration(DeclarationParams(client.getDocumentIdentifier(file), position)) })

    /** The answer, or nothing: a server that is busy, gone or answers with an error must not break navigation of the IDE. */
    private fun <T> request(client: LspClient, timeoutMs: Int, call: (LanguageServer) -> CompletableFuture<T>): T? = try {
        client.sendRequestSync(timeoutMs, call)
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Exception) {
        LOG.info("rust-analyzer request has failed: $e")
        RustAnalyzerLog.getInstance(client.project).error("A request of navigation has failed: ${e.message ?: e.javaClass.simpleName}")
        null
    }

    private fun locations(client: LspClient, answer: Either<out List<Location>, out List<LocationLink>>?): List<Place> = when {
        answer == null -> emptyList()
        answer.isLeft -> answer.left.orEmpty().mapNotNull { place(client, it.uri, it.range.start, it.range.end) }
        else -> answer.right.orEmpty().mapNotNull { place(client, it.targetUri, it.targetSelectionRange.start, it.targetSelectionRange.end) }
    }

    private fun place(client: LspClient, uri: String, start: Position, end: Position): Place? = ReadAction.compute<Place?, RuntimeException> {
        val file = client.descriptor.findFileByUri(uri) ?: return@compute null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return@compute null
        fun offset(position: Position): Int {
            if (position.line >= document.lineCount) return document.textLength
            return (document.getLineStartOffset(position.line) + position.character).coerceAtMost(document.getLineEndOffset(position.line))
        }
        Place(file, offset(start), offset(end))
    }

    /** The token a place starts at: the name of the declaration, which the platform underlines and navigates to. */
    fun element(project: Project, place: Place): PsiElement? =
        PsiManager.getInstance(project).findFile(place.file)?.findElementAt(place.startOffset)
}

/**
 * Go to Declaration through PSI elements (the tokens of [io.github.rustsupport.lang.RustLexer]) instead of the symbols of the platform
 * LSP client, whose own go-to-definition is switched off ([RustAnalyzerDescriptor]), or every target would be offered twice. With PSI
 * targets the platform underlines the name under Ctrl + mouse, changes the cursor to a hand and shows what it leads to.
 */
class RustAnalyzerGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
        val element = sourceElement?.takeIf { it.containingFile is RustFile && it.elementType in RustTokenTypes.NAVIGABLE } ?: return null
        if (element.elementType == RustTokenTypes.KEYWORD && element.text !in RustTokenTypes.NAVIGABLE_KEYWORDS) return null
        val project = element.project
        val file = element.containingFile.virtualFile ?: return null
        val client = RustAnalyzer.client(project) ?: return null
        val start = element.textRange.startOffset
        // the document of the file of the element, not of the editor: they differ when the element comes from a popup
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val places = RustAnalyzer.definition(client, file, RustAnalyzer.position(document, start), TIMEOUT_MS)
        // the name of a declaration leads to itself: nothing to go to, the platform offers the usages then
        val targets = places.filter { !(it.file == file && it.startOffset == start) }.mapNotNull { RustAnalyzer.element(project, it) }
        return targets.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    private companion object {
        // asked while the mouse moves: better no link than a frozen read action
        const val TIMEOUT_MS = 1500
    }
}
