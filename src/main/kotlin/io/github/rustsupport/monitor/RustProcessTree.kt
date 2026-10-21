package io.github.rustsupport.monitor

/** One process of the tree a run started, as the operating system describes it. [startMillis]: 0 when unknown. */
data class ProcessEntry(val pid: Long, val parentPid: Long?, val name: String, val commandLine: String = "", val startMillis: Long = 0)

/** What the monitor measures: the program and its children, or (no program found) the whole tree of the run. */
class MonitoredProcesses(val program: ProcessEntry?, val pids: List<Long>) {
    val isWholeTree: Boolean get() = program == null
}

/**
 * Which process of a run is "the program". `cargo run` / `cargo test` is only a launcher: it starts rustc, build scripts and the linker
 * while it builds, and then the program (or one test binary after another) as its child. Under rustup the `cargo` the IDE starts is a
 * proxy with the real cargo as its child, so the program is the newest process below the root whose ancestors (up to the root) are
 * all launchers. Pure logic over a process list, so that it can be tested without processes.
 */
object RustProcessTree {
    private val TOOLS = setOf(
        "cargo", "rustc", "rustup", "rustdoc", "clippy-driver", "sccache", "rustc_wrapper",
        // linkers and the compilers build scripts call (cc crate)
        "link", "lld", "lld-link", "rust-lld", "ld", "ld.lld", "cc", "c++", "gcc", "g++", "clang", "clang++", "cl", "mspdbsrv", "vctip",
        // the debugger: CodeLLDB starts the program, LLDB's helpers are not it
        "codelldb", "lldb", "lldb-server", "lldb-argdumper", "debugserver",
    )

    /** `C:\x\cargo.exe` → `cargo`; `/usr/bin/rustc` → `rustc`. */
    fun executableName(path: String): String =
        path.trim().substringAfterLast('\\').substringAfterLast('/').lowercase().removeSuffix(".exe")

    /** A console program on Windows has a `conhost.exe` child: never the program, and its 1 MB is not the program's. */
    fun isConsoleHost(entry: ProcessEntry): Boolean = executableName(entry.name) == "conhost"

    /** cargo and what it runs to build: rustc, cargo subcommands (`cargo-nextest`), build scripts, linkers. */
    fun isTool(entry: ProcessEntry): Boolean {
        val name = executableName(entry.name)
        return name in TOOLS || name.startsWith("cargo-") || name.startsWith("build-script-") || name.startsWith("build_script_") || isConsoleHost(entry)
    }

    /** What starts the program: cargo, its rustup proxy, a cargo subcommand. Not rustc or a build script: what they start is the build. */
    fun isLauncher(entry: ProcessEntry): Boolean =
        executableName(entry.name).let { it == "cargo" || it == "rustup" || it.startsWith("cargo-") || it == "codelldb" || it == "lldb-server" || it == "debugserver" }

    /** A program known by its pid (the debugger told it): it and its children, not the console host. */
    fun withChildren(programPid: Long, entries: List<ProcessEntry>): MonitoredProcesses {
        val program = entries.firstOrNull { it.pid == programPid } ?: return MonitoredProcesses(null, entries.map { it.pid })
        return MonitoredProcesses(program, listOf(programPid) + descendants(programPid, entries).filter { !isConsoleHost(it) }.map { it.pid })
    }

    /** The processes below [rootPid], parents before children; a pid seen twice (reuse, a cycle in stale data) is taken once. */
    fun descendants(rootPid: Long, entries: List<ProcessEntry>): List<ProcessEntry> {
        val children = entries.groupBy { it.parentPid }
        val seen = hashSetOf(rootPid)
        val result = ArrayList<ProcessEntry>()
        val queue = ArrayDeque(listOf(rootPid))
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            for (child in children[pid].orEmpty()) {
                if (!seen.add(child.pid)) continue
                result += child
                queue += child.pid
            }
        }
        return result
    }

    /**
     * The program of the run started as [rootPid]: the root itself when it is not cargo (an executable run directly), else the newest
     * non-tool process with only launchers (cargo, rustup, cargo-*) between it and the root. With it come its children (not the console host). No program
     * (still building, or a tool we do not know as such) — the whole tree.
     */
    fun pick(rootPid: Long, entries: List<ProcessEntry>): MonitoredProcesses {
        val root = entries.firstOrNull { it.pid == rootPid } ?: return MonitoredProcesses(null, emptyList())
        val below = descendants(rootPid, entries)
        val program = if (!isTool(root)) root else {
            val byPid = (below + root).associateBy { it.pid }
            fun onlyLaunchersAbove(entry: ProcessEntry): Boolean {
                var parent = entry.parentPid?.let(byPid::get)
                var steps = 0
                while (parent != null && parent.pid != rootPid && steps++ < 64) {
                    if (!isLauncher(parent)) return false
                    parent = parent.parentPid?.let(byPid::get)
                }
                return true
            }
            // the newest: a restarted program, the next test binary of `cargo test`; equal times keep the later one of the list
            below.withIndex()
                .filter { (_, entry) -> !isTool(entry) && onlyLaunchersAbove(entry) }
                .maxWithOrNull(compareBy({ it.value.startMillis }, { it.index }))?.value
        }
        if (program == null) return MonitoredProcesses(null, listOf(rootPid) + below.map { it.pid })
        val measured = listOf(program) + descendants(program.pid, entries).filter { !isConsoleHost(it) }
        return MonitoredProcesses(program, measured.map { it.pid })
    }
}
