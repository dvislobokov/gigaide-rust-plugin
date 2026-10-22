package io.github.rustsupport.toolchain

import io.github.rustsupport.RustFileIconProvider
import io.github.rustsupport.RustIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File
import java.nio.file.Path

class RustToolchainTest {
    @Test
    fun `candidates go settings, PATH, then cargo home`() {
        val path = listOf("C:\\a", "\"C:\\b\"", "").joinToString(File.pathSeparator)
        val candidates = RustToolchain.candidates(RustTool.RUST_ANALYZER, "D:\\ra.exe", path, Path.of("C:\\home\\.cargo"), windows = true)
        assertEquals(
            listOf(Path.of("D:\\ra.exe"), Path.of("C:\\a", "rust-analyzer.exe"), Path.of("C:\\b", "rust-analyzer.exe"), Path.of("C:\\home\\.cargo", "bin", "rust-analyzer.exe")),
            candidates,
        )
    }

    @Test
    fun `no exe suffix off Windows, and the directories of installers after cargo home`() {
        val candidates = RustToolchain.candidates(RustTool.CARGO, null, null, Path.of("/home/u/.cargo"), windows = false)
        assertEquals(
            listOf("/home/u/.cargo/bin", "/opt/homebrew/bin", "/usr/local/bin", "/opt/local/bin", "/home/linuxbrew/.linuxbrew/bin").map { Path.of(it, "cargo") },
            candidates,
        )
    }

    @Test
    fun `PATH of commands gets cargo home first and the directory of the tool last`() {
        val bin = RustToolchain.cargoHome().resolve("bin").toString()
        val sep = File.pathSeparator
        assertEquals(listOf(bin, "/a", "/usr/local/bin").joinToString(sep), RustToolchain.withDirectories("/a", listOf("/usr/local/bin")))
        assertEquals(listOf("/a", bin).joinToString(sep), RustToolchain.withDirectories(listOf("/a", bin).joinToString(sep), listOf("/a")))
        assertEquals(bin, RustToolchain.withDirectories(null))
    }

    @Test
    fun `Rust projects are told by manifests and sources, not by build output`() {
        val root = java.nio.file.Files.createTempDirectory("rust-detect")
        try {
            assertEquals(false, RustProjectDetector.hasRust(root))
            root.resolve("target/debug").toFile().mkdirs()
            root.resolve("target/debug/gen.rs").toFile().writeText("")
            assertEquals(false, RustProjectDetector.hasRust(root))
            root.resolve("crates/app").toFile().mkdirs()
            root.resolve("crates/app/Cargo.toml").toFile().writeText("[package]")
            assertEquals(true, RustProjectDetector.hasRust(root))
            assertEquals(false, RustProjectDetector.hasRust(null))
            assertEquals(false, RustProjectDetector.hasJvmCode(root))

            root.resolve("Cargo.toml").toFile().writeText("[workspace]")
            assertEquals(
                listOf(root.resolve("target"), root.resolve("crates/app/target"), root.resolve("out/cargo")).map { it.toString() }.sorted(),
                RustProjectDetector.targetDirectories(root, "out/cargo").map { it.toString() }.sorted(),
            )
            // a target directory outside the project is not the project's to exclude
            assertEquals(2, RustProjectDetector.targetDirectories(root, "D:/elsewhere").size)
            root.resolve("app/src").toFile().mkdirs()
            root.resolve("app/src/Main.java").toFile().writeText("")
            assertEquals(true, RustProjectDetector.hasJvmCode(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `icons of Rust files and Cargo manifests`() {
        assertSame(RustIcons.RUST, RustFileIconProvider.iconOf("main.rs", false))
        assertSame(RustIcons.CARGO, RustFileIconProvider.iconOf("Cargo.toml", false))
        assertSame(RustIcons.CARGO, RustFileIconProvider.iconOf("Cargo.lock", false))
        assertNull(RustFileIconProvider.iconOf("src.rs", true))
        assertNull(RustFileIconProvider.iconOf("build.toml", false))
    }
}
