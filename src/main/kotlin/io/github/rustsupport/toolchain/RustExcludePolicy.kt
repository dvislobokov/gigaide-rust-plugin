package io.github.rustsupport.toolchain

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy
import com.intellij.openapi.vfs.VfsUtilCore
import java.nio.file.Path

/**
 * The build output of every Cargo package and workspace of the project (`target/`, or `CARGO_TARGET_DIR`) is excluded: thousands of
 * generated files the IDE would index and search for nothing, rust-analyzer reads what it needs from there itself. Excluded whether it
 * exists yet or not, so that the first `cargo build` does not start an indexing.
 */
class RustExcludePolicy(private val project: Project) : DirectoryIndexExcludePolicy {
    override fun getExcludeUrlsForProject(): Array<String> {
        val root = project.basePath?.let { Path.of(it) } ?: return emptyArray()
        return RustProjectDetector.targetDirectories(root, System.getenv("CARGO_TARGET_DIR"))
            .map { VfsUtilCore.pathToUrl(it.toString()) }.toTypedArray()
    }
}
