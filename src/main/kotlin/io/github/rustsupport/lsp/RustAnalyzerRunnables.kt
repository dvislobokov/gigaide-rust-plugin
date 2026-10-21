package io.github.rustsupport.lsp

import com.google.gson.JsonElement
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.rustsupport.run.RustRunnableSource
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture

/**
 * The extensions of rust-analyzer to the protocol (https://rust-analyzer.github.io/book/contributing/lsp-extensions.html) the plugin
 * sends: the platform builds its proxy of the server from this interface ([RustAnalyzerDescriptor.lsp4jServerClass]).
 */
interface RustAnalyzerServer : LanguageServer {
    /** The answer is kept as JSON: it is read by [io.github.rustsupport.run.CargoRunnables], which the main part of the plugin owns. */
    @JsonRequest("experimental/runnables")
    fun runnables(params: RunnablesParams): CompletableFuture<JsonElement?>
}

/** No [position]: every runnable of the file, and those of its package. */
class RunnablesParams(val textDocument: TextDocumentIdentifier, val position: Position? = null)

/** The runnables of a file for the gutter and the run configurations of the main part of the plugin. */
class RustAnalyzerRunnableSource : RustRunnableSource {
    override fun isReady(project: Project): Boolean = RustAnalyzer.client(project) != null

    override fun fetchRunnables(project: Project, file: VirtualFile): String? {
        val client = RustAnalyzer.client(project) ?: return null
        return try {
            client.sendRequestSync<JsonElement?>(TIMEOUT_MS) { server ->
                (server as? RustAnalyzerServer)?.runnables(RunnablesParams(client.getDocumentIdentifier(file))) ?: CompletableFuture.completedFuture<JsonElement?>(null)
            }?.toString()
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.info("experimental/runnables has failed: $e")
            null
        }
    }

    private companion object {
        val LOG = logger<RustAnalyzerRunnableSource>()
        const val TIMEOUT_MS = 10_000
    }
}
