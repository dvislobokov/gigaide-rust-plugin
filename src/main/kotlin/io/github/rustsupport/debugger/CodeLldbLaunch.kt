package io.github.rustsupport.debugger

import com.google.gson.JsonObject
import java.io.File

/** What `rustc -vV` and `rustc --print sysroot` tell the debugger: where the Rust formatters and the sources of std are. */
data class RustcInfo(val sysroot: String?, val commitHash: String?) {
    /** `<sysroot>/lib/rustlib/src/rust`: the sources of std, when the `rust-src` component is installed. */
    fun stdSources(): String? = sysroot?.let { File(it, "lib/rustlib/src/rust") }?.takeIf { it.isDirectory }?.path

    companion object {
        /** The `commit-hash:` line of `rustc -vV`. */
        fun commitHash(verbose: String): String? =
            verbose.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("commit-hash:") }?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() && it != "unknown" }
    }
}

/**
 * The requests that start CodeLLDB. Two parts of the configuration go to two places: what the adapter loads at its start (the
 * languages: `sourceLanguages: ["rust"]` makes it run `lang_support/rust.py`, which loads the formatters of the toolchain) is
 * `--settings` of its command line, as the VS Code extension passes it (it moves `sourceLanguages` from the launch configuration there);
 * the rest is the `launch` request.
 */
object CodeLldbLaunch {
    /**
     * `--settings` of the adapter. The sysroot is given (`scriptConfig.lang.rust.sysroot`, read by `rust.py`) because the adapter would
     * run `rustc --print=sysroot` itself with its own PATH, where rustc may be missing (seen with a probe: "Could not locate Rust toolchain").
     * Forward slashes only: the adapter pastes these settings into Python source as a string literal, which turns `\\` into `\`, and
     * `json.loads` then fails on `\U` of `C:\Users` (seen live: "Invalid \escape", and none of the settings applied).
     */
    fun adapterSettings(sysroot: String?): JsonObject = json(
        "sourceLanguages" to listOf("rust"),
        "evaluateForHovers" to true,
        "scriptConfig" to sysroot?.let { mapOf("lang" to mapOf("rust" to mapOf("sysroot" to it.replace('\\', '/')))) },
    )

    /**
     * `launch` of CodeLLDB. No `stdio`: with `terminal: console` the program inherits the standard streams of the adapter (seen with a
     * probe), which the plugin reads into the console of the session, the input included; the protocol goes over a socket for that.
     * `sourceMap` maps `/rustc/<commit>` of the debug info of std onto the `rust-src` sources, so frames of std open in the editor.
     * [initCommands]: LLDB commands before the target is made; [stopOnEntry]: a stop once the program is loaded ([RustLineLocations]).
     */
    fun arguments(
        name: String, program: String, args: List<String>, cwd: String, env: Map<String, String>, rustc: RustcInfo?,
        initCommands: List<String> = emptyList(), stopOnEntry: Boolean = false,
    ): JsonObject {
        val sources = rustc?.stdSources()
        val sourceMap = rustc?.commitHash?.takeIf { sources != null }?.let { mapOf("/rustc/$it" to sources) }
        return json(
            "name" to name,
            "type" to "lldb",
            "request" to "launch",
            "program" to program,
            "args" to args,
            "cwd" to cwd,
            "env" to env.takeIf { it.isNotEmpty() },
            "terminal" to "console",
            "stopOnEntry" to stopOnEntry,
            "sourceLanguages" to listOf("rust"),
            "sourceMap" to sourceMap,
            "initCommands" to initCommands.takeIf { it.isNotEmpty() },
        )
    }

    /** The answer of the IDE to `initialize`: lines and columns from 1, plain paths, no `runInTerminal` (the console is ours). */
    fun initializeArguments(adapterId: String = "lldb"): JsonObject = json(
        "clientID" to "intellij", "clientName" to "IntelliJ Platform", "adapterID" to adapterId, "locale" to "en-us",
        "pathFormat" to "path", "linesStartAt1" to true, "columnsStartAt1" to true,
        "supportsVariableType" to true, "supportsVariablePaging" to true, "supportsRunInTerminalRequest" to false,
    )
}
