package io.github.rustsupport.debugger

import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import io.github.rustsupport.lang.RustFileType
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

// --- line breakpoints ---

/** Nothing beyond what the platform keeps (condition, log message, suspend policy); a class of its own for the serializer. */
class RustLineBreakpointProperties : XBreakpointProperties<RustLineBreakpointProperties>() {
    override fun getState(): RustLineBreakpointProperties = this
    override fun loadState(state: RustLineBreakpointProperties) {}
}

typealias RustLineBreakpoint = XLineBreakpoint<RustLineBreakpointProperties>

/** Line breakpoints in `.rs` files. The id is the one of the breakpoints users have saved: it must not change. */
class RustLineBreakpointType : XLineBreakpointType<RustLineBreakpointProperties>("rust-line", "Rust Line Breakpoints") {
    override fun createBreakpointProperties(file: VirtualFile, line: Int): RustLineBreakpointProperties = RustLineBreakpointProperties()
    override fun createProperties(): RustLineBreakpointProperties = RustLineBreakpointProperties()

    /** With an editor for expressions the platform shows "Condition" for the breakpoint; the condition goes to CodeLLDB with it. */
    override fun getEditorsProvider(breakpoint: RustLineBreakpoint, project: Project): XDebuggerEditorsProvider = RustDebuggerEditorsProvider()

    override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean {
        if (file.fileType != RustFileType) return false
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return true
        if (line < 0 || line >= document.lineCount) return false
        return RustBreakpointLines.canStopAt(document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))))
    }
}

/** Without a parser the lines are judged by their text: no breakpoint on a blank line, a comment, a lone brace (LLDB moves those anyway). */
object RustBreakpointLines {
    fun canStopAt(lineText: String): Boolean {
        val text = lineText.trim()
        if (text.isEmpty()) return false
        if (text.startsWith("//") || text.startsWith("/*") || text.startsWith("*")) return false
        if (text.startsWith("#[") || text.startsWith("#![")) return false
        return text != "{" && text != "(" && text != "["
    }
}

/**
 * The line breakpoints of a session: `setBreakpoints` is per file and replaces the whole list of the file, so every change sends the file
 * again, with the condition of each breakpoint. CodeLLDB answers in the order it was sent: that is how its ids are matched.
 */
class RustLineBreakpointHandler(private val process: RustDebugProcess) : XBreakpointHandler<RustLineBreakpoint>(RustLineBreakpointType::class.java) {
    private val byFile = ConcurrentHashMap<String, MutableSet<RustLineBreakpoint>>()
    private val byId = ConcurrentHashMap<Int, RustLineBreakpoint>()
    /** Run to Cursor: file -> the 0-based line of a breakpoint of one stop. */
    private val temporary = ConcurrentHashMap<String, Int>()

    override fun registerBreakpoint(breakpoint: RustLineBreakpoint) {
        val path = RustDebugProcess.pathOf(breakpoint.fileUrl)
        byFile.computeIfAbsent(path) { ConcurrentHashMap.newKeySet() }.add(breakpoint)
        if (process.configured) send(path)
    }

    override fun unregisterBreakpoint(breakpoint: RustLineBreakpoint, temporary: Boolean) {
        val path = RustDebugProcess.pathOf(breakpoint.fileUrl)
        byFile[path]?.remove(breakpoint)
        if (process.configured) send(path)
    }

    fun sendAll(): CompletableFuture<*> = CompletableFuture.allOf(*(byFile.keys + temporary.keys).distinct().map(::send).toTypedArray())

    fun find(id: Int): RustLineBreakpoint? = byId[id]

    /** The enabled breakpoint of the IDE at [line] (0-based) of [path]. */
    fun at(path: String, line: Int): RustLineBreakpoint? =
        byFile.entries.firstOrNull { RustDebugProcess.samePath(it.key, path) }?.value?.firstOrNull { it.isEnabled && it.line == line }

    fun runTo(path: String, line: Int): CompletableFuture<*> {
        val key = FileUtil.toSystemDependentName(path)
        temporary[key] = line
        return send(key)
    }

    /** At every stop: the breakpoint of Run to Cursor has done its job. True when there was one. */
    fun clearTemporary(): Boolean {
        val paths = temporary.keys.toList()
        temporary.clear()
        paths.forEach(::send)
        return paths.isNotEmpty()
    }

    /** `breakpoint` event: CodeLLDB has resolved (or moved) a breakpoint after the fact, when a library is loaded. */
    fun update(breakpoint: JsonObject) {
        val ide = breakpoint.int("id")?.let(byId::get) ?: return
        mark(ide, breakpoint)
    }

    private fun send(path: String): CompletableFuture<*> {
        val breakpoints = byFile[path].orEmpty().filter { it.isEnabled }.sortedBy { it.line }
        val extra = temporary[path]?.takeIf { line -> breakpoints.none { it.line == line } }
        val list = breakpoints.map { breakpointJson(it.line, it.conditionExpression?.expression) } + listOfNotNull(extra?.let { breakpointJson(it, null) })
        val source = json("path" to path, "name" to path.substringAfterLast('/').substringAfterLast('\\'))
        return process.connection.request("setBreakpoints", json("source" to source, "breakpoints" to list), RustDebugProcess.REQUEST_TIMEOUT_MS)
            .thenAccept { answer ->
                answer.objects("breakpoints").zip(breakpoints).forEach { (dap, ide) ->
                    dap.int("id")?.let { byId[it] = ide }
                    mark(ide, dap)
                }
            }
            .exceptionally { error -> breakpoints.forEach { process.session.setBreakpointInvalid(it, RustDebugProcess.errorText(error)) }; null }
    }

    private fun mark(ide: RustLineBreakpoint, dap: JsonObject) {
        if (dap.bool("verified") == true) process.session.setBreakpointVerified(ide)
        else process.session.setBreakpointInvalid(ide, dap.string("message") ?: "The debugger has not resolved the breakpoint (yet)")
    }

    companion object {
        /** One breakpoint of `setBreakpoints`: the line of the protocol is 1-based; an empty condition is left out. */
        fun breakpointJson(line: Int, condition: String?): Map<String, Any> = buildMap {
            put("line", line + 1)
            condition?.trim()?.takeIf { it.isNotEmpty() }?.let { put("condition", it) }
        }
    }
}

// --- panics ---

class RustPanicBreakpointProperties : XBreakpointProperties<RustPanicBreakpointProperties>() {
    override fun getState(): RustPanicBreakpointProperties = this
    override fun loadState(state: RustPanicBreakpointProperties) {}
}

typealias RustPanicBreakpoint = XBreakpoint<RustPanicBreakpointProperties>

/** A stop where a panic starts: the `rust_panic` exception filter of CodeLLDB (a breakpoint on `rust_panic` of std). */
class RustPanicBreakpointType : XBreakpointType<RustPanicBreakpoint, RustPanicBreakpointProperties>("rust-panic", "Rust Panic Breakpoints") {
    override fun getDisplayText(breakpoint: RustPanicBreakpoint): String = "Rust: on panic"
    override fun createProperties(): RustPanicBreakpointProperties = RustPanicBreakpointProperties()
    override fun getEnabledIcon(): Icon = AllIcons.Debugger.Db_exception_breakpoint
    override fun getDisabledIcon(): Icon = AllIcons.Debugger.Db_disabled_exception_breakpoint

    /** The platform makes default breakpoints disabled; this one is what stops at a panic, as CodeLLDB does by default, so it is on. */
    override fun createDefaultBreakpoint(creator: XBreakpointCreator<RustPanicBreakpointProperties>): RustPanicBreakpoint =
        creator.createBreakpoint(createProperties()).apply { isEnabled = true }
}

/** `setExceptionBreakpoints` with `rust_panic` or with nothing: sent even when off, or CodeLLDB applies its default (on). */
class RustPanicBreakpointHandler(private val process: RustDebugProcess) : XBreakpointHandler<RustPanicBreakpoint>(RustPanicBreakpointType::class.java) {
    private val registered = ConcurrentHashMap.newKeySet<RustPanicBreakpoint>()

    override fun registerBreakpoint(breakpoint: RustPanicBreakpoint) {
        registered.add(breakpoint)
        if (process.configured) send()
    }

    override fun unregisterBreakpoint(breakpoint: RustPanicBreakpoint, temporary: Boolean) {
        registered.remove(breakpoint)
        if (process.configured) send()
    }

    fun first(): RustPanicBreakpoint? = registered.firstOrNull()

    fun send(): CompletableFuture<*> =
        process.connection.request("setExceptionBreakpoints", arguments(registered.isNotEmpty()), RustDebugProcess.REQUEST_TIMEOUT_MS).exceptionally { null }

    companion object {
        const val PANIC_FILTER = "rust_panic"

        fun arguments(onPanic: Boolean): JsonObject = json("filters" to if (onPanic) listOf(PANIC_FILTER) else emptyList())
    }
}
