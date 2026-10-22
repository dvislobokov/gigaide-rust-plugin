package io.github.rustsupport.toolchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class RustToolchainCheckTest {
    private val ra = RustToolLocation(Path.of("C:/u/.cargo/bin/rust-analyzer.exe"), RustToolSource.CARGO_HOME)

    @Test
    fun `a working tool is ok with its version and where it comes from`() {
        val item = RustToolchainCheck.toolItem(RustTool.RUST_ANALYZER, true, false, ra, RustProbe(0, "rust-analyzer 1.99.1 (58cf8f9c 2026-10-13)\n", ""))
        assertEquals(RustCheckLevel.OK, item.level)
        assertEquals("rust-analyzer 1.99.1 (58cf8f9c 2026-10-13)", item.summary)
        assertTrue(item.detail!!.contains("not on PATH"))
    }

    @Test
    fun `a rustup proxy without its component asks for the component`() {
        val stderr = "error: the 'rust-analyzer' component which provides the command 'rust-analyzer.exe' is not available for the 'stable-x86_64-pc-windows-msvc' toolchain"
        val item = RustToolchainCheck.toolItem(RustTool.RUST_ANALYZER, true, false, ra, RustProbe(1, "", stderr))
        assertEquals(RustCheckLevel.ERROR, item.level)
        assertEquals("rustup proxy without the component", item.summary)
        assertEquals("rust-analyzer", item.component)
        assertTrue(RustToolchainCheck.isMissingComponent("error: Unknown binary 'rustfmt.exe' in official toolchain"))
        assertFalse(RustToolchainCheck.isMissingComponent("error: could not compile"))
    }

    @Test
    fun `missing tools by need`() {
        assertEquals(RustCheckLevel.ERROR, RustToolchainCheck.toolItem(RustTool.CARGO, true, false, null, null).level)
        assertEquals(RustCheckLevel.WARNING, RustToolchainCheck.toolItem(RustTool.CLIPPY, false, false, null, null).level)
        assertEquals("clippy", RustToolchainCheck.toolItem(RustTool.CLIPPY, false, false, null, null).component)
        assertEquals(RustCheckLevel.INFO, RustToolchainCheck.toolItem(RustTool.RUSTUP, false, true, null, null).level)
    }

    @Test
    fun `rust-src by the std sources in the sysroot`() {
        val sysroot = Files.createTempDirectory("sysroot")
        try {
            assertEquals(RustCheckLevel.WARNING, RustToolchainCheck.rustSrcItem(sysroot).level)
            assertEquals("rust-src", RustToolchainCheck.rustSrcItem(null).component)
            val lib = sysroot.resolve("lib/rustlib/src/rust/library/std/src/lib.rs").toFile()
            lib.parentFile.mkdirs()
            lib.writeText("")
            assertEquals(RustCheckLevel.OK, RustToolchainCheck.rustSrcItem(sysroot).level)
        } finally {
            sysroot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rust-src from the settings, else a rustup toolchain of the same version is pointed at`() {
        val root = Files.createTempDirectory("rustup")
        try {
            val copied = root.resolve("local") // rustc copied into ~/.local/bin: its sysroot is ~/.local
            val toolchain = root.resolve("toolchains/1.97.1-aarch64-apple-darwin")
            val library = toolchain.resolve("lib/rustlib/src/rust/library")
            library.resolve("std/src/lib.rs").toFile().apply { parentFile.mkdirs(); writeText("") }
            root.resolve("toolchains/1.96.0-aarch64-apple-darwin/lib/rustlib/src/rust/library/std/src/lib.rs").toFile().apply { parentFile.mkdirs(); writeText("") }

            // the path as people give it: the sysroot, lib/rustlib/src/rust, library
            for (given in listOf(toolchain, toolchain.resolve("lib/rustlib/src/rust"), library)) assertEquals(library, RustSources.library(given))
            assertNull(RustSources.library(copied))

            assertEquals("1.97.1", RustSources.version("rustc 1.97.1 (8bab26f4f 2026-07-14)\n"))
            assertEquals(library, RustSources.inRustup(root, "1.97.1"))
            assertNull(RustSources.inRustup(root, "1.98.0"))

            val hint = RustToolchainCheck.rustSrcItem(copied, null, RustSources.inRustup(root, "1.97.1"))
            assertEquals(RustCheckLevel.WARNING, hint.level)
            assertNull(hint.component) // rustup says "up to date": installing again is no fix
            assertTrue(hint.detail!!, hint.detail!!.contains(library.toString()) && hint.detail!!.contains(copied.toString()))

            assertEquals(RustCheckLevel.OK, RustToolchainCheck.rustSrcItem(copied, toolchain.toString()).level)
            assertEquals(RustCheckLevel.WARNING, RustToolchainCheck.rustSrcItem(toolchain, copied.toString()).level)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `msvc without the sdk is an error that names Git's linker`() {
        val incomplete = Msvc.State(vcTools = true, complete = false, windowsSdk = false, installation = "C:/VS/BuildTools")
        val item = RustToolchainCheck.msvcItem(incomplete, Path.of("C:/Program Files/Git/usr/bin/link.exe"))
        assertEquals(RustCheckLevel.ERROR, item.level)
        assertEquals("installation incomplete, no Windows SDK", item.summary)
        assertTrue(item.needsMsvc)
        assertTrue(item.detail!!.contains("Git"))
        val fine = RustToolchainCheck.msvcItem(Msvc.State(true, true, true, "C:/VS"), null)
        assertEquals(RustCheckLevel.OK, fine.level)
    }

    @Test
    fun `windows sdk by its x64 import libraries`() {
        val root = Files.createTempDirectory("kits").toFile()
        try {
            assertFalse(Msvc.hasWindowsSdk(root))
            File(root, "10.0.26100.0/um/x64").mkdirs()
            assertFalse(Msvc.hasWindowsSdk(root))
            File(root, "10.0.26100.0/um/x64/kernel32.lib").writeText("")
            assertTrue(Msvc.hasWindowsSdk(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `first link on path`() {
        val dir = Files.createTempDirectory("bin")
        try {
            assertNull(Msvc.firstLinkOnPath(dir.toString()))
            dir.resolve("link.exe").toFile().writeText("")
            assertEquals(dir.resolve("link.exe"), Msvc.firstLinkOnPath("C:/nowhere" + File.pathSeparator + dir))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `html escapes`() {
        val html = RustToolchainCheck.html(listOf(RustCheckItem("cargo", RustCheckLevel.OK, "cargo 1.99 <x>", "a&b")))
        assertTrue(html.contains("&lt;x&gt;"))
        assertTrue(html.contains("a&amp;b"))
    }
}
