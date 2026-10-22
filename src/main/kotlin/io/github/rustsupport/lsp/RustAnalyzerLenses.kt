package io.github.rustsupport.lsp

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.customization.LspCodeLensSupport
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.ui.awt.RelativePoint
import io.github.rustsupport.settings.RustSettings
import org.eclipse.lsp4j.CodeLens
import org.eclipse.lsp4j.Command
import java.awt.event.MouseEvent

/**
 * "3 usages" and "2 implementations" above the items: the code lenses of rust-analyzer (`lens.references.*`, `lens.implementations`),
 * shown by the code vision of the platform. rust-analyzer gives them only to a client that runs `rust-analyzer.showReferences` itself
 * (seen live with 1.99.1: no lens at all without it in `experimental.commands`), which is done here: a click opens the places in a chooser.
 * Its run and debug lenses stay off: the gutter has the runnables already.
 */
class RustAnalyzerLenses : LspCodeLensSupport() {
    override fun shouldAskServerForCodeLenses(file: VirtualFile): Boolean =
        RustAnalyzerIntegrationProvider.isRustFile(file) && RustSettings.getInstance().codeLens

    override fun shouldDisplayCodeLens(file: VirtualFile, codeLens: CodeLens): Boolean {
        val command = codeLens.command ?: return true
        val name = FileDocumentManager.getInstance().getCachedDocument(file)?.let { document ->
            val range = codeLens.range ?: return@let null
            val index = RustImplMarkers.LineIndex(document.immutableCharSequence)
            val start = index.offset(range.start.line, range.start.character)
            val end = index.offset(range.end.line, range.end.character)
            if (start <= end) document.immutableCharSequence.subSequence(start, end).toString() else null
        }
        if (RustLenses.hidden(command.title, name)) return false
        command.title = RustLenses.title(command.title)
        return true
    }

    override fun codeLensClicked(lspClient: LspClient, contextFile: VirtualFile, command: Command, event: MouseEvent?) {
        if (command.command != RustLenses.SHOW_REFERENCES) return super.codeLensClicked(lspClient, contextFile, command, event)
        val project = lspClient.project
        val targets = ReadAction.compute<List<PsiElement>, RuntimeException> {
            RustTargets.onePerLine(RustLenses.locations(command.arguments.orEmpty()).mapNotNull { target -> element(lspClient, target) })
        }
        if (targets.isEmpty()) return
        val title = if (RustLenses.isImplementations(command.title)) "Implementations" else "Usages"
        val popup = RustTargets.popup(project, title, targets)
        if (event != null) popup.show(RelativePoint(event)) else popup.showInFocusCenter()
    }

    private fun element(client: LspClient, target: RustAnalyzerProtocol.Target): PsiElement? = ReadAction.compute<PsiElement?, RuntimeException> {
        val file = client.descriptor.findFileByUri(target.uri) ?: return@compute null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return@compute null
        val offset = RustAnalyzerProtocol.offset(document.immutableCharSequence, target.line, target.character)
        PsiManager.getInstance(client.project).findFile(file)?.findElementAt(offset)
    }
}

/** What the lenses of rust-analyzer say and lead to; pure, for the tests. */
object RustLenses {
    const val SHOW_REFERENCES = "rust-analyzer.showReferences"

    private val REFERENCES = Regex("""^(\d+) references?$""")
    private val IMPLEMENTATIONS = Regex("""^\d+ implementations?$""")

    /** `3 references` reads as the IDE says it: `3 usages`, `no usages`; the implementations stay as they are. */
    fun title(title: String?): String {
        val text = title.orEmpty()
        val count = REFERENCES.matchEntire(text)?.groupValues?.get(1)?.toIntOrNull() ?: return text
        return when (count) {
            0 -> "no usages"
            1 -> "1 usage"
            else -> "$count usages"
        }
    }

    fun isImplementations(title: String?): Boolean = IMPLEMENTATIONS.matches(title.orEmpty())

    /** `main` is called by the runtime: "no usages" above it is noise. */
    fun hidden(title: String?, name: String?): Boolean = name == "main" && REFERENCES.matches(title.orEmpty())

    /** The places of `rust-analyzer.showReferences`: its arguments are the uri, the position and the `Location[]` (seen live). */
    fun locations(arguments: List<Any?>): List<RustAnalyzerProtocol.Target> {
        val places = arguments.getOrNull(2) ?: return emptyList()
        val json: JsonElement = places as? JsonElement ?: runCatching { Gson().toJsonTree(places) }.getOrNull() ?: return emptyList()
        return if (json is JsonArray) RustAnalyzerProtocol.targets(json) else emptyList()
    }
}
