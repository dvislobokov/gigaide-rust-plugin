package io.github.rustsupport.toolchain

import java.io.File
import java.nio.file.Path

/**
 * Whether a project has Rust in it, read off the disk (the IDE may still be indexing): a Cargo manifest, a `rust-project.json` or a
 * `.rs` file in the root or a few levels below it. Build output and dependency folders are not looked into.
 */
object RustProjectDetector {
    private const val MAX_DEPTH = 3
    private val SKIPPED = setOf("target", "node_modules", ".git", ".idea", ".gradle", "build", "dist", "out", ".venv")
    private val JVM_MARKERS = setOf("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "build.xml")

    fun hasRust(root: Path?): Boolean = root != null && any(root.toFile(), 0) { isRustMarker(it.name) }

    /** Whether there is Java or Kotlin to build: a JVM build file, or a `.java` / `.kt` source. */
    fun hasJvmCode(root: Path?): Boolean = root != null && any(root.toFile(), 0) { it.name in JVM_MARKERS || it.name.endsWith(".java") || it.name.endsWith(".kt") }

    /** The `target/` next to every `Cargo.toml` of the project, and the shared [cargoTargetDir] when it is inside the project. */
    fun targetDirectories(root: Path, cargoTargetDir: String? = null): List<Path> = buildList {
        collectManifestDirs(root.toFile(), 0) { add(it.toPath().resolve("target")) }
        cargoTargetDir?.takeIf { it.isNotBlank() }?.let { Path.of(it) }?.let { dir ->
            val absolute = if (dir.isAbsolute) dir else root.resolve(dir)
            if (absolute.normalize().startsWith(root.normalize())) add(absolute.normalize())
        }
    }.distinct()

    fun isRustMarker(name: String): Boolean = name == "Cargo.toml" || name == "rust-project.json" || name.endsWith(".rs")

    private fun any(dir: File, depth: Int, test: (File) -> Boolean): Boolean {
        val children = dir.listFiles() ?: return false
        if (children.any { it.isFile && test(it) }) return true
        if (depth >= MAX_DEPTH) return false
        return children.any { it.isDirectory && descends(it) && any(it, depth + 1, test) }
    }

    private fun collectManifestDirs(dir: File, depth: Int, found: (File) -> Unit) {
        val children = dir.listFiles() ?: return
        if (children.any { it.isFile && it.name == "Cargo.toml" }) found(dir)
        if (depth >= MAX_DEPTH) return
        children.filter { it.isDirectory && descends(it) }.forEach { collectManifestDirs(it, depth + 1, found) }
    }

    private fun descends(dir: File): Boolean = dir.name !in SKIPPED && !dir.name.startsWith(".")
}
