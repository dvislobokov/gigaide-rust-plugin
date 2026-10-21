package io.github.rustsupport.debugger

import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueGroup

/** All threads of the program at a stop; the active one is the thread the `stopped` event named, with its top frames already known. */
class RustSuspendContext(
    private val process: RustDebugProcess, threads: List<Pair<Int, String>>, val activeThreadId: Int?, topFrames: List<JsonObject>,
) : XSuspendContext() {
    private val stacks: List<RustExecutionStack> = threads.map { (id, name) ->
        RustExecutionStack(process, id, name, if (id == activeThreadId) topFrames else null)
    }
    private val active = stacks.firstOrNull { it.threadId == activeThreadId } ?: stacks.firstOrNull()

    val topFrameId: Int? get() = (active?.topFrame as? RustStackFrame)?.id

    override fun getActiveExecutionStack(): XExecutionStack? = active
    override fun getExecutionStacks(): Array<XExecutionStack> = (listOfNotNull(active) + stacks.filter { it !== active }).toTypedArray()
}

/** One thread. Frames come in pages (`stackTrace` with `startFrame` / `levels`): a deep recursion is not read whole at every stop. */
class RustExecutionStack(
    private val process: RustDebugProcess, val threadId: Int, name: String, private val known: List<JsonObject>?,
) : XExecutionStack(name.ifBlank { "Thread $threadId" }, AllIcons.Debugger.ThreadSuspended) {
    private val top: XStackFrame? = known?.firstOrNull()?.let { RustStackFrame(process, it) }

    override fun getTopFrame(): XStackFrame? = top

    override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer) {
        val ready = known?.drop(firstFrameIndex)?.map { RustStackFrame(process, it) }.orEmpty()
        val done = known != null && known.size < RustDebugProcess.FIRST_FRAMES
        if (ready.isNotEmpty() || done) container.addStackFrames(ready, done)
        if (!done) page(firstFrameIndex + ready.size, container)
    }

    private fun page(start: Int, container: XStackFrameContainer) {
        if (start >= MAX_FRAMES) return container.addStackFrames(emptyList(), true)
        process.stackTrace(threadId, start, PAGE).whenComplete { frames, error ->
            if (container.isObsolete) return@whenComplete
            if (error != null) return@whenComplete container.errorOccurred(RustDebugProcess.errorText(error))
            val last = frames.size < PAGE
            container.addStackFrames(frames.map { RustStackFrame(process, it) }, last)
            if (!last) page(start + frames.size, container)
        }
    }

    private companion object {
        const val PAGE = 50
        const val MAX_FRAMES = 2000
    }
}

/**
 * A frame of the protocol. Without a source file (std without `rust-src`, system libraries: CodeLLDB gives those as disassembly) it has
 * no position and is gray. The variables are the scopes of CodeLLDB: the first (the locals) is shown open, the others (statics, globals,
 * registers) as groups.
 */
class RustStackFrame(private val process: RustDebugProcess, frame: JsonObject) : XStackFrame() {
    val id: Int = frame.int("id") ?: 0
    private val name = frame.string("name").orEmpty()
    private val path = frame.getAsJsonObject("source")?.string("path")?.takeIf { it.isNotBlank() }
    private val line = (frame.int("line") ?: 1) - 1
    private val subtle = frame.string("presentationHint") in setOf("subtle", "label") || path == null

    private val position: XSourcePosition? by lazy {
        val file = path?.let { LocalFileSystem.getInstance().findFileByPath(it.replace('\\', '/')) } ?: return@lazy null
        XDebuggerUtil.getInstance().createPosition(file, line.coerceAtLeast(0))
    }

    override fun getSourcePosition(): XSourcePosition? = position

    // the ids of frames are new at every stop: the selection is kept by what the frame is
    override fun getEqualityObject(): Any = "$name|$path"

    override fun getEvaluator(): XDebuggerEvaluator = RustEvaluator(process, id)

    override fun customizePresentation(component: ColoredTextContainer) {
        component.append(name, if (subtle) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
        if (path != null) component.append("  ${path.substringAfterLast('/').substringAfterLast('\\')}:${line + 1}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        component.setIcon(AllIcons.Debugger.Frame)
    }

    override fun computeChildren(node: XCompositeNode) {
        process.connection.request("scopes", json("frameId" to id), RustDebugProcess.REQUEST_TIMEOUT_MS).whenComplete { answer, error ->
            if (node.isObsolete) return@whenComplete
            if (error != null) return@whenComplete node.setErrorMessage(RustDebugProcess.errorText(error))
            val scopes = answer.objects("scopes")
            val first = scopes.firstOrNull() ?: return@whenComplete node.addChildren(XValueChildrenList.EMPTY, true)
            val groups = scopes.drop(1).map { scope -> ScopeGroup(process, scope, id) }
            RustValueChildren(process, first.int("variablesReference") ?: 0, id).load(node, 0, groups)
        }
    }
}

private class ScopeGroup(private val process: RustDebugProcess, private val scope: JsonObject, private val frameId: Int) : XValueGroup(scope.string("name").orEmpty()) {
    override fun isAutoExpand(): Boolean = false
    override fun computeChildren(node: XCompositeNode) = RustValueChildren(process, scope.int("variablesReference") ?: 0, frameId).load(node, 0)
}

/** Evaluate, watches, the hover in the editor: `evaluate` in the frame. The expression under the mouse is found on the text, see [RustHoverExpression]. */
class RustEvaluator(private val process: RustDebugProcess, private val frameId: Int) : XDebuggerEvaluator() {
    override fun evaluate(expression: String, callback: XEvaluationCallback, expressionPosition: XSourcePosition?) {
        process.evaluate(expression, frameId, "watch").whenComplete { answer, error ->
            if (error != null) callback.errorOccurred(RustDebugProcess.errorText(error))
            else callback.evaluated(RustValue(process, expression, answer.string("result").orEmpty(), answer.string("type"),
                answer.int("variablesReference") ?: 0, expression, frameId, null))
        }
    }

    override fun getExpressionRangeAtOffset(project: Project, document: Document, offset: Int, sideEffectsAllowed: Boolean): TextRange? =
        RustHoverExpression.rangeAt(document.immutableCharSequence, offset)?.let { TextRange(it.first, it.last + 1) }
}
