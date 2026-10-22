package io.github.rustsupport.toolchain

import io.github.rustsupport.settings.RustSettings
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The sources of std (the `rust-src` component): the `library` directory rust-analyzer reads std, core and alloc from. Normally
 * `<sysroot>/lib/rustlib/src/rust/library`, but a rustc copied out of its toolchain (into ~/.local/bin, a tool bundle of a VS Code
 * plugin) has a sysroot without them, so the path can be set in the settings.
 */
object RustSources {
    private const val MARKER = "std/src/lib.rs"

    /** The `library` directory for [path] as people give it: the sysroot, `lib/rustlib/src/rust`, or `library` itself; null when none has std. */
    fun library(path: Path): Path? =
        listOf(path, path.resolve("library"), path.resolve("rust/library"), path.resolve("src/rust/library"), path.resolve("lib/rustlib/src/rust/library"))
            .firstOrNull { Files.isRegularFile(it.resolve(MARKER)) }

    /** The path in the settings, or null. */
    fun configuredPath(): String? = RustSettings.getInstance().rustSrcPath.trim().takeIf { it.isNotEmpty() }

    /** The `library` directory of the path in the settings, when there are sources there. */
    fun configured(): Path? = configuredPath()?.let { runCatching { library(Path.of(it)) }.getOrNull() }

    /** `RUSTUP_HOME`, or ~/.rustup. */
    fun rustupHome(): Path = System.getenv("RUSTUP_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"), ".rustup")

    /**
     * The sources of a rustup toolchain of [version] (`1.97.1` gives `toolchains/1.97.1-<host>`): where they are when rustc is not
     * the one of rustup. Only a toolchain of the same version: the sources of std must match the compiler.
     */
    fun inRustup(rustupHome: Path, version: String?): Path? {
        version ?: return null
        val toolchains = File(rustupHome.toFile(), "toolchains").listFiles().orEmpty()
        return toolchains.filter { it.name.startsWith("$version-") }.sortedBy { it.name }.firstNotNullOfOrNull { library(it.toPath()) }
    }

    /** The version of `rustc --version` (`rustc 1.97.1 (8bab26f4f 2026-07-14)` gives `1.97.1`). */
    fun version(rustcVersion: String?): String? = rustcVersion?.let { Regex("""^rustc (\d+\.\d+\.\d+)""").find(it.trim())?.groupValues?.get(1) }
}
