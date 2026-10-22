package io.github.rustsupport.debugger

import com.google.gson.JsonObject
import com.intellij.openapi.util.SystemInfo
import io.github.rustsupport.toolchain.RustToolchain
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** The adapters the debugger can run: CodeLLDB, or `lldb-dap` of LLVM (Xcode, the LLVM packages), for machines without CodeLLDB. */
enum class RustDebugAdapterKind(val title: String) { CODELLDB("CodeLLDB"), LLDB_DAP("lldb-dap") }

/** Settings | Languages & Frameworks | Rust | Debugger: which adapter. */
enum class RustDebugAdapterChoice(val text: String) {
    AUTO("Auto: the path set here, else CodeLLDB, else lldb-dap"),
    CODELLDB("CodeLLDB"),
    LLDB_DAP("lldb-dap");

    override fun toString(): String = text
}

/** An adapter found on this machine, with where it comes from for Settings. */
data class RustDebugAdapter(val kind: RustDebugAdapterKind, val path: Path, val description: String)

object RustDebugAdapters {
    /**
     * The adapter of the next session, or null: then the debug offers to get one. Auto: a path set by hand first (CodeLLDB, then
     * lldb-dap), then CodeLLDB found by itself, then lldb-dap found by itself. Touches the file system only.
     */
    fun current(settings: RustDebuggerSettings = RustDebuggerSettings.getInstance()): RustDebugAdapter? {
        val codeLldb = CodeLldbLocator.default()
        val lldbDap = LldbDapLocator.default()
        fun codeLldb(installation: CodeLldbInstallation?) = installation?.let { RustDebugAdapter(RustDebugAdapterKind.CODELLDB, it.adapter, it.describe()) }
        fun lldbDap(location: LldbDapLocation?) = location?.let { RustDebugAdapter(RustDebugAdapterKind.LLDB_DAP, it.path, "lldb-dap from ${it.source.text}") }
        return when (settings.adapter) {
            RustDebugAdapterChoice.CODELLDB -> codeLldb(codeLldb.find(settings.codelldbPath))
            RustDebugAdapterChoice.LLDB_DAP -> lldbDap(lldbDap.find(settings.lldbDapPath))
            RustDebugAdapterChoice.AUTO ->
                codeLldb(settings.codelldbPath.takeIf { it.isNotBlank() }?.let(codeLldb::fromSettings))
                    ?: lldbDap(settings.lldbDapPath.takeIf { it.isNotBlank() }?.let(lldbDap::fromSettings))
                    ?: codeLldb(codeLldb.find(null))
                    ?: lldbDap(lldbDap.find(null))
        }
    }
}

enum class LldbDapSource(val text: String) { SETTINGS("the path in Settings"), PATH("PATH"), LLVM("an install of Xcode or LLVM") }

data class LldbDapLocation(val path: Path, val source: LldbDapSource)

/**
 * Finds `lldb-dap` (`lldb-vscode` before LLVM 18): the path of the settings (the executable or its folder), PATH, then where Xcode,
 * its Command Line Tools, Homebrew's LLVM, the LLVM packages of Linux and the LLVM installer of Windows put it. Pure but for the
 * existence check, for the tests.
 */
class LldbDapLocator(private val pathEnv: String?, private val directories: List<Path>, private val windows: Boolean = SystemInfo.isWindows) {
    private val names = listOf("lldb-dap", "lldb-vscode").map { if (windows) "$it.exe" else it }

    fun find(configured: String?): LldbDapLocation? =
        configured?.takeIf { it.isNotBlank() }?.let(::fromSettings) ?: candidates().firstOrNull { isExecutable(it.path) }

    fun fromSettings(configured: String): LldbDapLocation? {
        val path = Path.of(configured.trim().trim('"'))
        return (listOf(path) + names.map { path.resolve(it) }).firstOrNull(::isExecutable)?.let { LldbDapLocation(it, LldbDapSource.SETTINGS) }
    }

    /** Every place in order, the first name before the old one in each. */
    fun candidates(): List<LldbDapLocation> {
        val onPath = pathEnv?.split(File.pathSeparatorChar).orEmpty().filter { it.isNotBlank() }.map { Path.of(it.trim('"')) }
        return onPath.flatMap { dir -> names.map { LldbDapLocation(dir.resolve(it), LldbDapSource.PATH) } } +
            directories.flatMap { dir -> names.map { LldbDapLocation(dir.resolve(it), LldbDapSource.LLVM) } }
    }

    private fun isExecutable(path: Path): Boolean = File(path.toString()).let { it.isFile && it.canExecute() }

    companion object {
        private val LLVM_DIR = Regex("""llvm-(\d+)""")

        fun default(): LldbDapLocator = LldbDapLocator(RustToolchain.pathEnv(), knownDirectories())

        /** Where installers put it; the versioned LLVM folders of Debian and Ubuntu newest first. */
        fun knownDirectories(os: String = CodeLldbRelease.currentOs(), llvmVersions: (Path) -> List<Path> = ::versionedLlvm): List<Path> = when (os) {
            "mac" -> listOf(
                "/Applications/Xcode.app/Contents/Developer/usr/bin",
                "/Library/Developer/CommandLineTools/usr/bin",
                "/opt/homebrew/opt/llvm/bin",
                "/usr/local/opt/llvm/bin",
            ).map { Path.of(it) }
            "windows" -> listOfNotNull(System.getenv("ProgramFiles")?.let { Path.of(it, "LLVM", "bin") }, Path.of("C:\\Program Files\\LLVM\\bin")).distinct()
            else -> llvmVersions(Path.of("/usr/lib")) + listOf(Path.of("/usr/local/bin"))
        }

        /** `/usr/lib/llvm-<n>/bin`, the highest `n` first. */
        private fun versionedLlvm(lib: Path): List<Path> = runCatching {
            Files.list(lib).use { stream -> stream.toList() }
                .mapNotNull { dir -> LLVM_DIR.matchEntire(dir.fileName.toString())?.let { it.groupValues[1].toInt() to dir } }
                .sortedByDescending { it.first }
                .map { it.second.resolve("bin") }
        }.getOrDefault(emptyList())
    }
}

/**
 * The `launch` of lldb-dap. Unlike CodeLLDB it loads no language support of its own: the Rust formatters of the toolchain are loaded by
 * `initCommands`, as `rust-lldb` does (`lldb_lookup.py`, then the type rules of `lldb_commands`). `env` as `KEY=VALUE` strings and
 * `sourceMap` as pairs: the forms every lldb-dap takes (the objects came later).
 */
object LldbDapLaunch {
    fun arguments(
        name: String, program: String, args: List<String>, cwd: String, env: Map<String, String>, rustc: RustcInfo?,
        initCommands: List<String> = emptyList(), stopOnEntry: Boolean = false,
    ): JsonObject {
        val sources = rustc?.stdSources()
        val sourceMap = rustc?.commitHash?.takeIf { sources != null }?.let { listOf(listOf("/rustc/$it", sources)) }
        return json(
            "name" to name,
            "type" to "lldb-dap",
            "request" to "launch",
            "program" to program,
            "args" to args,
            "cwd" to cwd,
            "env" to env.map { (key, value) -> "$key=$value" }.takeIf { it.isNotEmpty() },
            "stopOnEntry" to stopOnEntry,
            "sourceMap" to sourceMap,
            "initCommands" to (formatterCommands(rustc?.sysroot) + initCommands).takeIf { it.isNotEmpty() },
        )
    }

    /** The commands of `rust-lldb` for the formatters of [sysroot]; none when the toolchain does not have them. */
    fun formatterCommands(sysroot: String?): List<String> {
        val etc = sysroot?.let { File(it, "lib/rustlib/etc") } ?: return emptyList()
        val lookup = File(etc, "lldb_lookup.py").takeIf { it.isFile } ?: return emptyList()
        val commands = File(etc, "lldb_commands").takeIf { it.isFile }
        return listOfNotNull("command script import ${quote(lookup.path)}", commands?.let { "command source -s 1 ${quote(it.path)}" })
    }

    /** lldb-dap takes an LLDB command in `repl` when it starts with its escape, a backtick by default. */
    fun command(command: String): String = "`$command"

    private fun quote(path: String): String = "\"" + path.replace('\\', '/').replace("\"", "\\\"") + "\""
}
