package io.github.rustsupport.lsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.intellij.psi.PsiManager
import java.util.concurrent.CompletableFuture

/**
 * `workspace/semanticTokens/refresh` without the blink. The platform answers it by clearing its cache of semantic tokens and asking
 * again (`LspClientImpl.refreshSemanticTokens`): until the answer comes the editor has the colours of the lexer only. rust-analyzer
 * sends it every time it becomes quiescent after a change, a keystroke included, so the colours blinked after every analysis (seen
 * by a user). An edit needs no refresh: the PSI has changed, and the platform asks for the tokens again keeping the old ones on screen
 * meanwhile. What the server learns by itself does need one (the workspace, build scripts or proc macros loaded: `quiescent` of
 * `experimental/serverStatus` turning true); then the PSI modification count is moved, which makes the platform ask the same gentle way.
 */
class RustAnalyzerSemanticRefresh(private val project: Project) {
    @Volatile private var quiescent = true

    /** [LspServerNotificationsHandler] of [delegate] that takes the refresh request of the server and does nothing with it. */
    fun handler(delegate: LspServerNotificationsHandler): LspServerNotificationsHandler = object : LspServerNotificationsHandler by delegate {
        override fun refreshSemanticTokens(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)
    }

    /** `quiescent` of `experimental/serverStatus`: the end of a load asks for the tokens of the open files again. */
    fun serverStatus(quiescent: Boolean) {
        val before = this.quiescent
        this.quiescent = quiescent
        if (becameQuiescent(before, quiescent)) {
            // dropPsiCaches moves the PSI modification count (its `propUnloadedPsi` event) and restarts highlighting; EDT only
            ApplicationManager.getApplication().invokeLater({ PsiManager.getInstance(project).dropPsiCaches() }, project.disposed)
        }
    }

    companion object {
        fun becameQuiescent(before: Boolean, now: Boolean): Boolean = now && !before
    }
}
