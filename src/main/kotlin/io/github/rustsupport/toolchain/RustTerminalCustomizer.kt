package io.github.rustsupport.toolchain

import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.startup.MutableShellExecOptions
import org.jetbrains.plugins.terminal.startup.ShellExecOptionsCustomizer
import java.nio.file.Files
import java.nio.file.Path

/**
 * The terminal of the IDE in a Rust project runs the tools the plugin runs: the directories of the tools set in the settings go first
 * on PATH, then `RUSTC`, `RUSTFMT` and `RUST_SRC_PATH` of the settings, as for the processes of the plugin ([RustToolchain.environment]).
 * ~/.cargo/bin goes last, for a PATH of an IDE started before rustup was installed. The terminal keeps the prepended entries first
 * even when the rc files of the shell rebuild PATH. Registered in `rust-terminal.xml`, loaded only with the terminal plugin.
 */
class RustTerminalCustomizer : ShellExecOptionsCustomizer {
    override fun customizeExecOptions(project: Project, shellExecOptions: MutableShellExecOptions) {
        if (!RustProjectDetector.hasRust(project.basePath?.let { Path.of(it) })) return
        // prepended one by one: the first of the settings ends up first
        RustToolchain.configuredDirectories().asReversed().forEach { shellExecOptions.prependEntryToPATH(Path.of(it)) }
        RustToolchain.cargoHome().resolve("bin").takeIf { Files.isDirectory(it) }?.let(shellExecOptions::appendEntryToPATH)
        RustToolchain.configuredVariables().forEach { (name, value) -> shellExecOptions.setEnvironmentVariable(name, value) }
    }
}
