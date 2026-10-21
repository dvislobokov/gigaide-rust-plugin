package io.github.rustsupport.debugger

import com.intellij.openapi.util.SystemInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CodeLldbTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `assets of the platforms`() {
        assertEquals(CodeLldbRelease.Asset.WIN32_X64, CodeLldbRelease.asset("windows", "amd64"))
        assertNull(CodeLldbRelease.asset("windows", "aarch64"))
        assertNull(CodeLldbRelease.asset("windows", "x86"))
        assertEquals(CodeLldbRelease.Asset.LINUX_X64, CodeLldbRelease.asset("linux", "amd64"))
        assertEquals(CodeLldbRelease.Asset.LINUX_ARM64, CodeLldbRelease.asset("linux", "aarch64"))
        assertEquals(CodeLldbRelease.Asset.LINUX_ARMHF, CodeLldbRelease.asset("linux", "arm"))
        assertEquals(CodeLldbRelease.Asset.DARWIN_X64, CodeLldbRelease.asset("mac", "x86_64"))
        assertEquals(CodeLldbRelease.Asset.DARWIN_ARM64, CodeLldbRelease.asset("mac", "aarch64"))
    }

    @Test
    fun `the download URL of a release asset`() {
        assertEquals(
            "https://github.com/vadimcn/codelldb/releases/download/v1.12.3/codelldb-win32-x64.vsix",
            CodeLldbRelease.downloadUrl(CodeLldbRelease.Asset.WIN32_X64, "1.12.3"),
        )
        assertEquals("codelldb-darwin-arm64.vsix", CodeLldbRelease.Asset.DARWIN_ARM64.fileName)
    }

    @Test
    fun `only the adapter, lldb and the language support are unpacked`() {
        assertEquals("adapter/codelldb.exe", CodeLldbRelease.unpackedPath("extension/adapter/codelldb.exe"))
        assertEquals("lldb/bin/liblldb.dll", CodeLldbRelease.unpackedPath("extension/lldb/bin/liblldb.dll"))
        assertEquals("lang_support/rust.py", CodeLldbRelease.unpackedPath("extension/lang_support/rust.py"))
        assertNull(CodeLldbRelease.unpackedPath("extension/extension.js"))
        assertNull(CodeLldbRelease.unpackedPath("extension.vsixmanifest"))
        assertNull(CodeLldbRelease.unpackedPath("extension/adapter/"))
        assertNull(CodeLldbRelease.unpackedPath("extension/adapter/../../evil"))
    }

    @Test
    fun `versions of extension folders`() {
        assertEquals("1.11.8", CodeLldbLocator.extensionVersion("vadimcn.vscode-lldb-1.11.8"))
        assertEquals("1.12.0", CodeLldbLocator.extensionVersion("vadimcn.vscode-lldb-1.12.0-win32-x64"))
        assertNull(CodeLldbLocator.extensionVersion("vadimcn.vscode-lldb-bootstrap"))
        assertNull(CodeLldbLocator.extensionVersion("rust-lang.rust-analyzer-0.3.2"))
        assertTrue(CodeLldbLocator.compareVersions("1.12.0", "1.11.10") > 0)
        assertTrue(CodeLldbLocator.compareVersions("1.9", "1.10.0") < 0)
    }

    private val exe = if (SystemInfo.isWindows) "codelldb.exe" else "codelldb"

    private fun extension(home: Path, editor: String, folder: String): Path {
        val adapter = home.resolve(editor).resolve("extensions").resolve(folder).resolve("adapter").resolve(exe)
        Files.createDirectories(adapter.parent)
        Files.writeString(adapter, "")
        return adapter
    }

    @Test
    fun `the newest extension of the editors, VS Code first`() {
        val home = temp.newFolder("home").toPath()
        val locator = CodeLldbLocator(home, temp.newFolder("downloads").toPath())
        assertNull(locator.find(null))
        val cursor = extension(home, ".cursor", "vadimcn.vscode-lldb-1.12.3")
        assertEquals(CodeLldbInstallation(cursor, "1.12.3", CodeLldbSource.CURSOR), locator.find(""))
        extension(home, ".vscode", "vadimcn.vscode-lldb-1.11.7")
        val newest = extension(home, ".vscode", "vadimcn.vscode-lldb-1.11.10")
        // a folder of the extension without an adapter (an install that broke) is passed over
        Files.createDirectories(home.resolve(".vscode/extensions/vadimcn.vscode-lldb-1.13.0"))
        val found = locator.find(null)!!
        assertEquals(CodeLldbInstallation(newest, "1.11.10", CodeLldbSource.VS_CODE), found)
        assertEquals("CodeLLDB 1.11.10 from VS Code extension", found.describe())
    }

    @Test
    fun `the path of the settings comes first, the downloaded one last`() {
        val home = temp.newFolder("home").toPath()
        val downloads = temp.newFolder("downloads").toPath()
        val locator = CodeLldbLocator(home, downloads)
        val downloaded = downloads.resolve(CodeLldbRelease.VERSION).resolve("adapter").resolve(exe)
        Files.createDirectories(downloaded.parent)
        Files.writeString(downloaded, "")
        assertNull("not complete without the marker", locator.find(null))
        Files.writeString(downloads.resolve(CodeLldbRelease.VERSION).resolve(CodeLldbLocator.INSTALLED_MARKER), "")
        assertEquals(CodeLldbSource.DOWNLOADED, locator.find(null)!!.source)
        val vscode = extension(home, ".vscode-oss", "vadimcn.vscode-lldb-1.10.0")
        assertEquals(CodeLldbInstallation(vscode, "1.10.0", CodeLldbSource.VSCODIUM), locator.find(null))

        val own = temp.newFolder("own", "extension", "adapter").toPath().resolve(exe)
        Files.writeString(own, "")
        assertEquals(CodeLldbInstallation(own, null, CodeLldbSource.SETTINGS), locator.find(own.toString()))
        // the folder of an extension, or of its adapter, is as good as the executable
        assertEquals(own, locator.find(own.parent.parent.toString())!!.adapter)
        assertEquals(own, locator.find(own.parent.toString())!!.adapter)
        // a path in the settings that is not there: the next places
        assertEquals(CodeLldbSource.VSCODIUM, locator.find(temp.root.resolve("missing").path)!!.source)
    }

    @Test
    fun `a VSIX is unpacked with the marker of a complete install`() {
        val vsix = temp.root.toPath().resolve("codelldb.vsix")
        ZipOutputStream(Files.newOutputStream(vsix)).use { zip ->
            for ((name, text) in listOf(
                "extension.vsixmanifest" to "<x/>",
                "extension/extension.js" to "js",
                "extension/adapter/" to "",
                "extension/adapter/codelldb" to "bin",
                "extension/lldb/bin/lldb-server" to "server",
                "extension/lang_support/rust.py" to "py",
            )) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        val target = temp.root.toPath().resolve("out")
        CodeLldbInstaller.unpack(vsix, target)
        assertEquals("bin", Files.readString(target.resolve("adapter/codelldb")))
        assertEquals("server", Files.readString(target.resolve("lldb/bin/lldb-server")))
        assertEquals("py", Files.readString(target.resolve("lang_support/rust.py")))
        assertFalse(Files.exists(target.resolve("extension.js")))
        assertTrue(Files.exists(target.resolve(CodeLldbLocator.INSTALLED_MARKER)))
    }
}
