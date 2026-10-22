package io.github.rustsupport.debugger

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path

private val LOG = logger<RustLineLocations>()

/**
 * Line breakpoints in async code on Windows. LLDB keeps one location of a file:line breakpoint per function; with the PDB of an MSVC
 * build the line of an async block lies in several pieces of the function of its state machine, and the dispatch of the states is marked
 * with lines of the block. Seen live: no stop on `let listener = TcpListener::bind(..).await` in `#[tokio::main]`, a stop on the first
 * line of the block at every poll, two stops on a line with an inlined `unwrap()`.
 *
 * `rust_line_locations.py`, imported into CodeLLDB by `initCommands`, puts that right once the program is loaded (the session starts with
 * `stopOnEntry` for it, a stop the IDE does not show): it disables the locations that only route to a line or repeat a piece, and lists
 * the pieces left without one, which the IDE sets as instruction breakpoints with the condition of the breakpoint of the IDE. It is
 * asked by a `repl` evaluation (an LLDB command for CodeLLDB) and answers in a file: the output of commands does not come back.
 */
object RustLineLocations {
    private const val RESOURCE = "/debugger/rust_line_locations.py"

    /** DWARF builds keep the pieces apart by `is_stmt`; the PDB has no such flag, and only MSVC builds have shown the problem. */
    val enabled: Boolean get() = SystemInfo.isWindows

    /** `<system>/rust-support/lldb/rust_line_locations.py`, written from the plugin when it differs; null when it cannot be. */
    fun script(): Path? = runCatching {
        val bytes = RustLineLocations::class.java.getResourceAsStream(RESOURCE)!!.use { it.readAllBytes() }
        val file = Path.of(PathManager.getSystemPath(), "rust-support", "lldb", "rust_line_locations.py")
        if (!Files.isRegularFile(file) || !Files.readAllBytes(file).contentEquals(bytes)) {
            Files.createDirectories(file.parent)
            Files.write(file, bytes)
        }
        file
    }.onFailure { LOG.info("Cannot write the LLDB script of line breakpoints: ${it.message}") }.getOrNull()

    /** `initCommands` of `launch`: forward slashes, as LLDB takes the path apart like a shell. */
    fun initCommands(script: Path): List<String> = listOf("command script import ${quote(script.toString())}")

    /** The command: [lines] are the ids of `setBreakpoints` with their 1-based lines; the answer goes into [out]. */
    fun command(out: Path, lines: List<Pair<Int, Int>>): String =
        (listOf("rust-line-locations", quote(out.toString())) + lines.map { (id, line) -> "$id:$line" }).joinToString(" ")

    /** The answer: `<id> <load address>` lines, the address as `instructionReference` takes it. */
    fun parse(text: String): List<Pair<Int, String>> = text.lineSequence().mapNotNull { line ->
        val parts = line.trim().split(' ')
        val id = parts.firstOrNull()?.toIntOrNull()
        val address = parts.getOrNull(1)?.takeIf { it.startsWith("0x") }
        if (parts.size == 2 && id != null && address != null) id to address else null
    }.toList()

    private fun quote(path: String): String = "\"" + path.replace('\\', '/').replace("\"", "\\\"") + "\""
}
