package io.github.rustsupport.debugger

import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import io.github.rustsupport.run.RustDebugLaunchSpec
import io.github.rustsupport.run.RustDebuggableConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.swing.Icon

class RustDebugLogicTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `adapter settings load the Rust formatters of the sysroot`() {
        assertEquals(
            """{"sourceLanguages":["rust"],"evaluateForHovers":true,"scriptConfig":{"lang":{"rust":{"sysroot":"C:/Users/r"}}}}""",
            DapConnection.GSON.toJson(CodeLldbLaunch.adapterSettings("C:\\Users\\r")),
        )
        assertEquals("""{"sourceLanguages":["rust"],"evaluateForHovers":true}""", DapConnection.GSON.toJson(CodeLldbLaunch.adapterSettings(null)))
    }

    @Test
    fun `launch arguments of CodeLLDB`() {
        val sysroot = temp.newFolder("sysroot")
        val sources = java.io.File(sysroot, "lib/rustlib/src/rust").apply { mkdirs() }
        val launch = CodeLldbLaunch.arguments("app", "/p/app", listOf("--x", "1"), "/p", mapOf("K" to "V"), RustcInfo(sysroot.path, "abc123"))
        assertEquals("launch", launch.string("request"))
        assertEquals("/p/app", launch.string("program"))
        assertEquals(listOf("--x", "1"), launch.getAsJsonArray("args").map { it.asString })
        assertEquals("/p", launch.string("cwd"))
        assertEquals("V", launch.getAsJsonObject("env").string("K"))
        assertEquals("console", launch.string("terminal"))
        assertEquals("rust", launch.getAsJsonArray("sourceLanguages")[0].asString)
        assertEquals(sources.path, launch.getAsJsonObject("sourceMap").string("/rustc/abc123"))
        assertNull("no stdio: the program inherits the streams of the adapter", launch.get("stdio"))

        val bare = CodeLldbLaunch.arguments("app", "/p/app", emptyList(), "/p", emptyMap(), null)
        assertNull(bare.get("env"))
        assertNull(bare.get("sourceMap"))
    }

    @Test
    fun `the commit of rustc`() {
        val verbose = "rustc 1.99.1 (58cf8f9c3 2026-10-13)\nbinary: rustc\ncommit-hash: 58cf8f9c3a1b\ncommit-date: 2026-10-13\nhost: x86_64-pc-windows-msvc\n"
        assertEquals("58cf8f9c3a1b", RustcInfo.commitHash(verbose))
        assertNull(RustcInfo.commitHash("commit-hash: unknown"))
    }

    @Test
    fun `breakpoints and the panic filter`() {
        assertEquals(mapOf("line" to 11), RustLineBreakpointHandler.breakpointJson(10, "  "))
        assertEquals(mapOf("line" to 1, "condition" to "i == 3"), RustLineBreakpointHandler.breakpointJson(0, " i == 3 "))
        assertEquals("""{"filters":["rust_panic"]}""", DapConnection.GSON.toJson(RustPanicBreakpointHandler.arguments(true)))
        assertEquals("""{"filters":[]}""", DapConnection.GSON.toJson(RustPanicBreakpointHandler.arguments(false)))
    }

    @Test
    fun `lines a breakpoint can be put at`() {
        assertTrue(RustBreakpointLines.canStopAt("    let x = 1;"))
        assertTrue(RustBreakpointLines.canStopAt("}"))
        assertFalse(RustBreakpointLines.canStopAt("   "))
        assertFalse(RustBreakpointLines.canStopAt("  // comment"))
        assertFalse(RustBreakpointLines.canStopAt("/// doc"))
        assertFalse(RustBreakpointLines.canStopAt("#[derive(Debug)]"))
    }

    private fun hover(text: String): String? {
        val offset = text.indexOf('|')
        val clean = text.removeRange(offset, offset + 1)
        return RustHoverExpression.rangeAt(clean, offset)?.let { clean.substring(it.first, it.last + 1) }
    }

    @Test
    fun `the expression under the mouse`() {
        assertEquals("count", hover("let x = cou|nt + 1;"))
        assertEquals("self.items", hover("self.ite|ms.len()"))
        assertEquals("self", hover("se|lf.items"))
        assertEquals("p.0", hover("p.|0"))
        assertEquals("order.lines.total", hover("order.lines.tot|al"))
        assertNull("a call runs code", hover("comp|ute(1)"))
        assertNull("a macro", hover("print|ln!(\"x\")"))
        assertNull("a keyword", hover("l|et x = 1;"))
        assertNull("a path", hover("std::m|em::swap"))
        assertNull("a path", hover("st|d::mem"))
        assertNull("a number", hover("1|00"))
    }

    private class FakeConfiguration(private val spec: RustDebugLaunchSpec?) : RustDebuggableConfiguration {
        override fun debugLaunchSpec(): RustDebugLaunchSpec? = spec
        override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? = null
        override fun getName(): String = "fake"
        override fun getIcon(): Icon? = null
    }

    @Test
    fun `the runner takes debuggable configurations for Debug only`() {
        val runner = RustDebugRunner()
        val spec = RustDebugLaunchSpec(listOf("build", "--bin", "app"), emptyList(), "/p")
        assertTrue(runner.canRun("Debug", FakeConfiguration(spec)))
        assertFalse(runner.canRun("Run", FakeConfiguration(spec)))
        assertFalse("cargo check cannot be debugged", runner.canRun("Debug", FakeConfiguration(null)))
    }

    @Test
    fun `line locations of async code are asked for with LLDB quoting`() {
        assertEquals(
            """rust-line-locations "C:/Users/My Name/AppData/Local/Temp/rust-line-locations1.txt" 3:28 5:30""",
            RustLineLocations.command(java.nio.file.Path.of("C:\\Users\\My Name\\AppData\\Local\\Temp\\rust-line-locations1.txt"), listOf(3 to 28, 5 to 30)),
        )
        assertEquals(
            listOf("""command script import "C:/Users/me/rust-support/lldb/rust_line_locations.py""""),
            RustLineLocations.initCommands(java.nio.file.Path.of("C:\\Users\\me\\rust-support\\lldb\\rust_line_locations.py")),
        )
    }

    @Test
    fun `answer of the line locations script`() {
        assertEquals(
            listOf(2 to "0x7ff605ac5b68", 4 to "0x7ff605ac5dc3"),
            RustLineLocations.parse("2 0x7ff605ac5b68\r\n4 0x7ff605ac5dc3\n\nnoise\n5 nothing\n"),
        )
        assertEquals(emptyList<Pair<Int, String>>(), RustLineLocations.parse(""))
    }

    @Test
    fun `init commands and the entry stop go into launch only when asked`() {
        val with = CodeLldbLaunch.arguments("n", "p.exe", emptyList(), "C:\\w", emptyMap(), null, listOf("command script import \"x.py\""), stopOnEntry = true)
        assertEquals("""["command script import \"x.py\""]""", with.get("initCommands").toString())
        assertTrue(with.get("stopOnEntry").asBoolean)
        val without = CodeLldbLaunch.arguments("n", "p.exe", emptyList(), "C:\\w", emptyMap(), null)
        assertNull(without.get("initCommands"))
        assertFalse(without.get("stopOnEntry").asBoolean)
    }
}
