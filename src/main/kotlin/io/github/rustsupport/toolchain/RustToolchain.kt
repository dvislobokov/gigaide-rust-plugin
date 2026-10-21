package io.github.rustsupport.toolchain

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.util.SystemInfo
import io.github.rustsupport.settings.RustSettings
import java.io.File
import java.nio.file.Path

/** The tools of the Rust toolchain the plugin runs. */
enum class RustTool(val executable: String, val component: String?) {
    CARGO("cargo", null),
    RUSTC("rustc", null),
    RUSTUP("rustup", null),
    RUST_ANALYZER("rust-analyzer", "rust-analyzer"),
    RUSTFMT("rustfmt", "rustfmt"),
    CLIPPY("cargo-clippy", "clippy");

    /** The tool: the path in the settings, then PATH, then ~/.cargo/bin (where rustup puts it, missing from PATH of an IDE started before rustup was installed). */
    fun find(): File? = RustToolchain.find(this, configuredPath(), System.getenv("PATH"), RustToolchain.cargoHome())?.toFile()

    /** Where [find] takes the tool from. */
    fun locate(): RustToolLocation? = RustToolchain.locate(this, configuredPath(), System.getenv("PATH"), RustToolchain.cargoHome())

    internal fun configuredPath(): String? = when (this) {
        RUST_ANALYZER -> RustSettings.getInstance().rustAnalyzerPath
        CARGO -> RustSettings.getInstance().cargoPath
        else -> null
    }?.takeIf { it.isNotBlank() }
}

enum class RustToolSource(val text: String) { SETTINGS("settings"), PATH("PATH"), CARGO_HOME("~/.cargo/bin, not on PATH") }

data class RustToolLocation(val path: Path, val source: RustToolSource)

object RustToolchain {
    /** `CARGO_HOME`, or ~/.cargo. */
    fun cargoHome(): Path = System.getenv("CARGO_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"), ".cargo")

    /** The candidates of [tool] in order; pure but for the existence check, for the tests. */
    fun candidates(tool: RustTool, configured: String?, pathEnv: String?, cargoHome: Path, windows: Boolean = SystemInfo.isWindows): List<Path> {
        val name = if (windows) tool.executable + ".exe" else tool.executable
        return buildList {
            configured?.let { add(Path.of(it)) }
            pathEnv?.split(File.pathSeparatorChar)?.filter { it.isNotBlank() }?.forEach { add(Path.of(it.trim('"'), name)) }
            add(cargoHome.resolve("bin").resolve(name))
        }
    }

    fun find(tool: RustTool, configured: String?, pathEnv: String?, cargoHome: Path): Path? = locate(tool, configured, pathEnv, cargoHome)?.path

    /** The first candidate that exists, with where it comes from. */
    fun locate(tool: RustTool, configured: String?, pathEnv: String?, cargoHome: Path, windows: Boolean = SystemInfo.isWindows): RustToolLocation? {
        val name = if (windows) tool.executable + ".exe" else tool.executable
        configured?.let { Path.of(it) }?.takeIf { File(it.toString()).canExecute() }?.let { return RustToolLocation(it, RustToolSource.SETTINGS) }
        pathEnv?.split(File.pathSeparatorChar)?.filter { it.isNotBlank() }?.map { Path.of(it.trim('"'), name) }
            ?.firstOrNull { File(it.toString()).canExecute() }?.let { return RustToolLocation(it, RustToolSource.PATH) }
        return cargoHome.resolve("bin").resolve(name).takeIf { File(it.toString()).canExecute() }?.let { RustToolLocation(it, RustToolSource.CARGO_HOME) }
    }

    /** A command line of [executable] with ~/.cargo/bin on PATH: the rustup proxies find the toolchain through it. */
    fun commandLine(executable: File, workDirectory: String?, vararg args: String): GeneralCommandLine =
        GeneralCommandLine(executable.path, *args)
            .withWorkingDirectory(workDirectory?.let { Path.of(it) })
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withEnvironment("PATH", withCargoBin(System.getenv("PATH")))

    fun withCargoBin(path: String?): String {
        val bin = cargoHome().resolve("bin").toString()
        val parts = path?.split(File.pathSeparatorChar).orEmpty()
        return if (parts.any { it.equals(bin, ignoreCase = SystemInfo.isWindows) }) path.orEmpty() else (listOf(bin) + parts).joinToString(File.pathSeparator)
    }

    /** The first line of `<tool> --version`, or null; blocks, call off the EDT. */
    fun version(tool: RustTool): String? {
        val exe = tool.find() ?: return null
        return runCatching {
            val output = ExecUtil.execAndGetOutput(commandLine(exe, null, "--version"), 10_000)
            output.stdout.lineSequence().firstOrNull()?.trim()?.takeIf { output.exitCode == 0 && it.isNotEmpty() }
        }.getOrNull()
    }
}
