package io.github.rustsupport.toolchain

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
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

    /**
     * The tool: the path in the settings, then PATH, then ~/.cargo/bin (where rustup puts it, missing from PATH of an IDE started before
     * rustup was installed), then the directories of package managers and installers ([RustToolchain.systemDirectories]).
     */
    fun find(): File? = RustToolchain.find(this, configuredPath(), RustToolchain.pathEnv(), RustToolchain.cargoHome())?.toFile()

    /** Where [find] takes the tool from. */
    fun locate(): RustToolLocation? = RustToolchain.locate(this, configuredPath(), RustToolchain.pathEnv(), RustToolchain.cargoHome())

    internal fun configuredPath(): String? = when (this) {
        RUST_ANALYZER -> RustSettings.getInstance().rustAnalyzerPath
        CARGO -> RustSettings.getInstance().cargoPath
        else -> null
    }?.takeIf { it.isNotBlank() }
}

enum class RustToolSource(val text: String) {
    SETTINGS("settings"), PATH("PATH"), CARGO_HOME("~/.cargo/bin, not on PATH"), SYSTEM("a standard directory, not on PATH")
}

data class RustToolLocation(val path: Path, val source: RustToolSource)

object RustToolchain {
    /** `CARGO_HOME`, or ~/.cargo. */
    fun cargoHome(): Path = System.getenv("CARGO_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"), ".cargo")

    /**
     * PATH of the login shell, as the platform loads it: an IDE started from the Dock or Finder on macOS gets the PATH of launchd
     * (`/usr/bin:/bin:/usr/sbin:/sbin`) in its own environment, without `/usr/local/bin` of the Rust installer and Homebrew.
     */
    fun pathEnv(): String? = runCatching { EnvironmentUtil.getValue("PATH") }.getOrNull() ?: System.getenv("PATH")

    /**
     * Where package managers and installers put the tools off Windows: Homebrew (Apple silicon, then Intel and the `.pkg` installer of
     * rust-lang.org), MacPorts, Homebrew on Linux. Looked at last, for a PATH that misses them (seen live: cargo in `/usr/local/bin`
     * not found on macOS).
     */
    fun systemDirectories(windows: Boolean = SystemInfo.isWindows): List<Path> =
        if (windows) emptyList() else listOf("/opt/homebrew/bin", "/usr/local/bin", "/opt/local/bin", "/home/linuxbrew/.linuxbrew/bin").map { Path.of(it) }

    private fun sources(tool: RustTool, configured: String?, pathEnv: String?, cargoHome: Path, windows: Boolean): List<RustToolLocation> {
        val name = if (windows) tool.executable + ".exe" else tool.executable
        return buildList {
            configured?.let { add(RustToolLocation(Path.of(it), RustToolSource.SETTINGS)) }
            pathEnv?.split(File.pathSeparatorChar)?.filter { it.isNotBlank() }?.forEach { add(RustToolLocation(Path.of(it.trim('"'), name), RustToolSource.PATH)) }
            add(RustToolLocation(cargoHome.resolve("bin").resolve(name), RustToolSource.CARGO_HOME))
            systemDirectories(windows).forEach { add(RustToolLocation(it.resolve(name), RustToolSource.SYSTEM)) }
        }
    }

    /** The candidates of [tool] in order; pure but for the existence check, for the tests. */
    fun candidates(tool: RustTool, configured: String?, pathEnv: String?, cargoHome: Path, windows: Boolean = SystemInfo.isWindows): List<Path> =
        sources(tool, configured, pathEnv, cargoHome, windows).map { it.path }

    fun find(tool: RustTool, configured: String?, pathEnv: String?, cargoHome: Path): Path? = locate(tool, configured, pathEnv, cargoHome)?.path

    /** The first candidate that exists, with where it comes from. */
    fun locate(tool: RustTool, configured: String?, pathEnv: String?, cargoHome: Path, windows: Boolean = SystemInfo.isWindows): RustToolLocation? =
        sources(tool, configured, pathEnv, cargoHome, windows).firstOrNull { File(it.path.toString()).canExecute() }

    /**
     * A command line of [executable] with ~/.cargo/bin on PATH (the rustup proxies find the toolchain through it) and the directory of
     * [executable] (cargo runs `rustc` from PATH, and a cargo found in [systemDirectories] has its rustc next to it).
     */
    fun commandLine(executable: File, workDirectory: String?, vararg args: String): GeneralCommandLine =
        GeneralCommandLine(executable.path, *args)
            .withWorkingDirectory(workDirectory?.let { Path.of(it) })
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withEnvironment("PATH", withDirectories(pathEnv(), listOfNotNull(executable.absoluteFile.parent)))

    /** [path] with ~/.cargo/bin first, then [after] at the end; nothing twice. */
    fun withDirectories(path: String?, after: List<String> = emptyList()): String {
        val parts = path?.split(File.pathSeparatorChar).orEmpty().filter { it.isNotEmpty() }
        fun has(list: List<String>, dir: String) = list.any { it.equals(dir, ignoreCase = SystemInfo.isWindows) }
        val bin = cargoHome().resolve("bin").toString()
        val result = (if (has(parts, bin)) parts else listOf(bin) + parts).toMutableList()
        after.forEach { if (!has(result, it)) result += it }
        return result.joinToString(File.pathSeparator)
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
