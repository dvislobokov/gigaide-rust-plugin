package io.github.rustsupport.debugger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LldbDapTest {
    @get:Rule val temp = TemporaryFolder()

    private fun executable(dir: File, name: String): Path = File(dir, name).apply { parentFile.mkdirs(); writeText(""); setExecutable(true) }.toPath()

    @Test
    fun `lldb-dap from the settings, then PATH, then the installs of LLVM, the old name last in each`() {
        val onPath = temp.newFolder("path")
        val xcode = temp.newFolder("xcode")
        val locator = LldbDapLocator(onPath.path, listOf(xcode.toPath()), windows = false)
        assertNull(locator.find(null))

        val inXcode = executable(xcode, "lldb-dap")
        assertEquals(LldbDapLocation(inXcode, LldbDapSource.LLVM), locator.find(null))
        val old = executable(onPath, "lldb-vscode")
        assertEquals(LldbDapLocation(old, LldbDapSource.PATH), locator.find(""))
        val current = executable(onPath, "lldb-dap")
        assertEquals(LldbDapLocation(current, LldbDapSource.PATH), locator.find(null))

        // the folder in the settings is as good as the executable; a path that is not there: the other places
        assertEquals(LldbDapLocation(inXcode, LldbDapSource.SETTINGS), locator.find(xcode.path))
        assertEquals(LldbDapLocation(inXcode, LldbDapSource.SETTINGS), locator.find(inXcode.toString()))
        assertEquals(LldbDapSource.PATH, locator.find(temp.root.resolve("missing").path)!!.source)
    }

    @Test
    fun `the installs of LLVM of each OS`() {
        assertEquals(Path.of("/Applications/Xcode.app/Contents/Developer/usr/bin"), LldbDapLocator.knownDirectories("mac").first())
        assertEquals(
            listOf(Path.of("/usr/lib/llvm-19/bin"), Path.of("/usr/local/bin")),
            LldbDapLocator.knownDirectories("linux") { listOf(it.resolve("llvm-19/bin")) },
        )
    }

    @Test
    fun `launch of lldb-dap loads the formatters of the toolchain`() {
        val sysroot = temp.newFolder("sysroot")
        val etc = File(sysroot, "lib/rustlib/etc").apply { mkdirs() }
        File(etc, "lldb_lookup.py").writeText("")
        File(etc, "lldb_commands").writeText("")
        File(sysroot, "lib/rustlib/src/rust").mkdirs()
        val root = sysroot.path.replace('\\', '/')
        val launch = LldbDapLaunch.arguments(
            "app", "/p/app", listOf("-v"), "/p", mapOf("RUST_LOG" to "debug"), RustcInfo(sysroot.path, "abc"), listOf("command script import \"/x.py\""), stopOnEntry = true,
        )
        assertEquals("lldb-dap", launch.string("type"))
        assertEquals(listOf("RUST_LOG=debug"), launch.getAsJsonArray("env").map { it.asString })
        assertEquals(true, launch.bool("stopOnEntry"))
        assertEquals(
            listOf("command script import \"$root/lib/rustlib/etc/lldb_lookup.py\"", "command source -s 1 \"$root/lib/rustlib/etc/lldb_commands\"", "command script import \"/x.py\""),
            launch.getAsJsonArray("initCommands").map { it.asString },
        )
        val pair = launch.getAsJsonArray("sourceMap")[0].asJsonArray
        assertEquals("/rustc/abc", pair[0].asString)
        assertEquals(File(sysroot, "lib/rustlib/src/rust").path, pair[1].asString)

        // no sysroot, no environment: neither formatters nor the keys
        val bare = LldbDapLaunch.arguments("app", "/p/app", emptyList(), "/p", emptyMap(), null)
        assertNull(bare.get("initCommands"))
        assertNull(bare.get("env"))
        assertNull(bare.get("sourceMap"))
    }

    @Test
    fun `LLDB commands go to lldb-dap after its escape`() {
        assertEquals("`rust-line-locations \"/o.txt\" 1:3", LldbDapLaunch.command("rust-line-locations \"/o.txt\" 1:3"))
    }

    @Test
    fun `a panic breakpoint of lldb-dap is a function breakpoint on rust_panic`() {
        assertEquals("""{"breakpoints":[{"name":"rust_panic"}]}""", RustPanicBreakpointHandler.functionArguments(true).toString())
        assertEquals("""{"breakpoints":[]}""", RustPanicBreakpointHandler.functionArguments(false).toString())
    }

    @Test
    fun `a VSIX tells its version and whether the adapter of the OS is in it`() {
        val vsix = temp.root.toPath().resolve("linux.vsix")
        ZipOutputStream(Files.newOutputStream(vsix)).use { zip ->
            for ((name, text) in listOf("extension/package.json" to """{"name": "vscode-lldb", "version": "1.11.5"}""", "extension/adapter/codelldb" to "bin")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        assertEquals(CodeLldbInstaller.VsixContents("1.11.5", true), CodeLldbInstaller.inspectVsix(vsix, windows = false))
        assertEquals(CodeLldbInstaller.VsixContents("1.11.5", false), CodeLldbInstaller.inspectVsix(vsix, windows = true))
    }
}
