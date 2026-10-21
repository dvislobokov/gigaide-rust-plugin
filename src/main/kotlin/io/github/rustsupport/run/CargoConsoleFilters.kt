package io.github.rustsupport.run

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.io.File

/** A place in Rust code named by a line of output: [start] and [end] are offsets in the line, [line] and [column] one-based. */
data class RustOutputLocation(val start: Int, val end: Int, val path: String, val line: Int, val column: Int)

/**
 * `file.rs:10:5` wherever the toolchain prints it: `--> src/main.rs:10:5` of the compiler, `panicked at src/main.rs:10:5:` of a panic
 * (`panicked at 'msg', src/main.rs:10:5` before Rust 1.73), `at ./src/main.rs:4:5` of a backtrace, absolute paths of the registry.
 * Paths with backslashes too: rustc on Windows prints `src\main.rs`.
 */
object RustOutputLocations {
    private val LOCATION = Regex("""((?:[A-Za-z]:)?[^\s:"'`()\[\]<>,]+\.rs):(\d+)(?::(\d+))?""")

    fun find(line: String): List<RustOutputLocation> = LOCATION.findAll(line).map { match ->
        RustOutputLocation(match.range.first, match.range.last + 1, match.groupValues[1], match.groupValues[2].toInt(), match.groupValues[3].toIntOrNull() ?: 1)
    }.toList()

    /** Where a relative path may be: the [directories] in order (the run directory, the project), as `/`-separated absolute paths. */
    fun candidates(path: String, directories: List<String>): List<String> {
        val normalized = path.replace('\\', '/').removePrefix("./")
        if (File(path).isAbsolute || normalized.startsWith("/")) return listOf(normalized)
        return directories.map { it.replace('\\', '/').trimEnd('/') + "/" + normalized }.distinct()
    }
}

class CargoConsoleFilterProvider : ConsoleFilterProvider {
    override fun getDefaultFilters(project: Project): Array<Filter> = arrayOf(CargoConsoleFilter(project))
}

/**
 * Links to Rust sources in any console of the project. A relative path is looked for under [directories] (the directory of the run,
 * when known), the project directory, and else among the files of the project whose path ends with it (a member of a workspace that
 * is not the project directory).
 */
class CargoConsoleFilter(private val project: Project, private val directories: List<String> = emptyList()) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        if (".rs:" !in line) return null
        val lineStart = entireLength - line.length
        val items = RustOutputLocations.find(line).mapNotNull { location ->
            val file = resolve(location.path) ?: return@mapNotNull null
            Filter.ResultItem(lineStart + location.start, lineStart + location.end, OpenFileHyperlinkInfo(project, file, location.line - 1, location.column - 1))
        }
        return if (items.isEmpty()) null else Filter.Result(items)
    }

    private fun resolve(path: String): VirtualFile? {
        val fileSystem = LocalFileSystem.getInstance()
        val bases = directories + listOfNotNull(project.basePath)
        RustOutputLocations.candidates(path, bases).firstNotNullOfOrNull { fileSystem.findFileByPath(it) }?.let { return it }
        if (File(path).isAbsolute) return null
        val suffix = "/" + path.replace('\\', '/').removePrefix("./")
        return runReadAction {
            if (project.isDisposed || DumbService.isDumb(project)) return@runReadAction null
            FilenameIndex.getVirtualFilesByName(suffix.substringAfterLast('/'), GlobalSearchScope.projectScope(project))
                .filter { it.path.endsWith(suffix) }.singleOrNull()
        }
    }
}
