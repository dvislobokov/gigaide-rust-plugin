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

    /** The path in Settings | Languages & Frameworks | Rust, or null. */
    fun configuredPath(): String? = RustSettings.getInstance().let {
        when (this) {
            CARGO -> it.cargoPath
            RUSTC -> it.rustcPath
            RUSTUP -> it.rustupPath
            RUST_ANALYZER -> it.rustAnalyzerPath
            RUSTFMT -> it.rustfmtPath
            CLIPPY -> it.clippyPath
        }
    }.trim().takeIf { it.isNotEmpty() }
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
     * A command line of [executable] in the environment of [environment]: cargo, rust-analyzer and the tools they run find the tools of
     * the settings, not only the ones of PATH.
     */
    fun commandLine(executable: File, workDirectory: String?, vararg args: String): GeneralCommandLine =
        GeneralCommandLine(executable.path, *args)
            .withWorkingDirectory(workDirectory?.let { Path.of(it) })
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withEnvironment(environment(executable))

    /**
     * The variables of the toolchain for a process of [executable]. PATH: the directories of the tools set in the settings first (cargo
     * runs `rustc` and `cargo-clippy` from PATH, `cargo-clippy` its `clippy-driver` from its own directory), then ~/.cargo/bin (the
     * rustup proxies find the toolchain through it), then PATH, then the directory of [executable] (a cargo found in [systemDirectories]
     * has its rustc next to it). `RUSTC` and `RUSTFMT` of the settings: cargo, `cargo fmt` and rust-analyzer run those over PATH. `RUST_SRC_PATH`: the std sources of
     * the settings, which rust-analyzer takes over the sysroot.
     */
    fun environment(executable: File? = null): Map<String, String> = buildMap {
        val configured = RustTool.entries.mapNotNull { it.configuredPath() }.mapNotNull { File(it).absoluteFile.parent }
        put("PATH", withDirectories(pathEnv(), listOfNotNull(executable?.absoluteFile?.parent), before = configured))
        RustTool.RUSTC.configuredPath()?.let { put("RUSTC", it) }
        RustTool.RUSTFMT.configuredPath()?.let { put("RUSTFMT", it) }
        RustSources.configured()?.let { put("RUST_SRC_PATH", it.toString()) }
    }

    /** [path] with [before] first, then ~/.cargo/bin, then [after] at the end; nothing twice. */
    fun withDirectories(path: String?, after: List<String> = emptyList(), before: List<String> = emptyList()): String {
        val parts = path?.split(File.pathSeparatorChar).orEmpty().filter { it.isNotEmpty() }
        val result = mutableListOf<String>()
        fun add(dir: String) {
            if (result.none { it.equals(dir, ignoreCase = SystemInfo.isWindows) }) result += dir
        }
        before.forEach(::add)
        val bin = cargoHome().resolve("bin").toString()
        if (parts.none { it.equals(bin, ignoreCase = SystemInfo.isWindows) }) add(bin)
        parts.forEach(::add)
        after.forEach(::add)
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
