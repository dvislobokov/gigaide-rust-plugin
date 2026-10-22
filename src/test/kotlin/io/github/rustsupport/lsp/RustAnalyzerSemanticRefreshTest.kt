package io.github.rustsupport.lsp

import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture

class RustAnalyzerSemanticRefreshTest {
    private inline fun <reified T> fake(crossinline call: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> call(method.name) } as T

    @Test
    fun `the refresh of the server stays with the plugin, the rest goes on`() {
        val called = mutableListOf<String>()
        val platform = fake<LspServerNotificationsHandler> { called += it; CompletableFuture.completedFuture(null) }
        val handler = RustAnalyzerSemanticRefresh(fake<Project> { null }).handler(platform)

        assertTrue(handler.refreshSemanticTokens().isDone)
        assertEquals(emptyList<String>(), called) // the platform would clear its tokens: the blink
        handler.refreshInlayHints()
        assertEquals(listOf("refreshInlayHints"), called)
    }

    @Test
    fun `only the end of a load asks for the tokens again`() {
        assertTrue(RustAnalyzerSemanticRefresh.becameQuiescent(before = false, now = true))
        assertFalse(RustAnalyzerSemanticRefresh.becameQuiescent(before = true, now = true))
        assertFalse(RustAnalyzerSemanticRefresh.becameQuiescent(before = true, now = false))
        assertFalse(RustAnalyzerSemanticRefresh.becameQuiescent(before = false, now = false))
    }
}
