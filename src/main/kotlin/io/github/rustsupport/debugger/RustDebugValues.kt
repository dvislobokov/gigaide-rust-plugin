package io.github.rustsupport.debugger

import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.util.LocalTimeCounter
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProviderBase
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XNamedValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueGroup
import com.intellij.xdebugger.frame.XValueModifier
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XRegularValuePresentation
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl
import io.github.rustsupport.lang.RustFileType
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.resolvedPromise

/**
 * A variable, a field, an element or the result of an expression, as the Rust formatters of the toolchain show it (CodeLLDB loads them
 * for `sourceLanguages: ["rust"]`): `Vec`, `String`, `Option`, `HashMap` with their elements as children. Children come from `variables`
 * of its reference, lazily, in pages. Set Value is `setVariable` in the list the variable came from.
 */
class RustValue(
    private val process: RustDebugProcess,
    name: String,
    private val value: String,
    private val type: String?,
    private val reference: Int,
    private val evaluateName: String?,
    private val frameId: Int,
    /** The reference of the list this variable came from; null for the result of an expression. */
    private val container: Int?,
) : XNamedValue(name) {
    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        val icon = if (name.startsWith("[")) AllIcons.Debugger.Value else AllIcons.Nodes.Variable
        node.setPresentation(icon, XRegularValuePresentation(value, type?.takeIf { it.isNotBlank() }), reference > 0)
    }

    override fun computeChildren(node: XCompositeNode) = RustValueChildren(process, reference, frameId).load(node, 0)

    /** Add to Watches takes the expression of CodeLLDB (`v[0]`, `point.x`), not the name of the row. */
    override fun calculateEvaluationExpression(): Promise<XExpression> =
        resolvedPromise(XExpressionImpl.fromText(evaluateName?.takeIf { it.isNotBlank() } ?: name))

    /** Scalars only: a struct or a collection as a whole is not assigned. */
    override fun getModifier(): XValueModifier? = container?.takeIf { reference <= 0 }?.let(::Modifier)

    private inner class Modifier(private val container: Int) : XValueModifier() {
        override fun calculateInitialValueEditorText(callback: XInitialValueCallback) = callback.setValue(value)

        override fun setValue(newValue: XExpression, callback: XModificationCallback) {
            process.connection.request("setVariable", json("variablesReference" to container, "name" to name, "value" to newValue.expression.trim()), RustDebugProcess.REQUEST_TIMEOUT_MS)
                .whenComplete { _, error -> if (error == null) callback.valueModified() else callback.errorOccurred(RustDebugProcess.errorText(error)) }
        }
    }

    companion object {
        fun of(process: RustDebugProcess, variable: JsonObject, frameId: Int, container: Int) = RustValue(
            process, variable.string("name").orEmpty(), variable.string("value").orEmpty(), variable.string("type"),
            variable.int("variablesReference") ?: 0, variable.string("evaluateName"), frameId, container,
        )
    }
}

/** The children of a reference, a page at a time with `start` / `count`: a `Vec` of a million elements is not read whole. */
class RustValueChildren(private val process: RustDebugProcess, private val reference: Int, private val frameId: Int) {
    /** [groups] (the other scopes of a frame) go below the variables of the first page. */
    fun load(node: XCompositeNode, start: Int, groups: List<XValueGroup> = emptyList()) {
        if (reference <= 0) return node.addChildren(XValueChildrenList().also { list -> groups.forEach(list::addBottomGroup) }, true)
        process.connection.request("variables", json("variablesReference" to reference, "start" to start, "count" to PAGE), RustDebugProcess.REQUEST_TIMEOUT_MS)
            .whenComplete { answer, error ->
                if (node.isObsolete) return@whenComplete
                if (error != null) return@whenComplete node.setErrorMessage(RustDebugProcess.errorText(error))
                val variables = answer.objects("variables")
                val children = XValueChildrenList(variables.size)
                variables.forEach { children.add(RustValue.of(process, it, frameId, reference)) }
                groups.forEach(children::addBottomGroup)
                val more = variables.size >= PAGE
                node.addChildren(children, !more)
                if (more) node.tooManyChildren(PAGE) { load(node, start + variables.size) }
            }
    }

    companion object {
        const val PAGE = 100
    }
}

/** The editors of Evaluate, of a watch and of a breakpoint condition: Rust fragments, highlighted by the lexer of the plugin. */
class RustDebuggerEditorsProvider : XDebuggerEditorsProviderBase() {
    override fun getFileType(): FileType = RustFileType

    override fun createExpressionCodeFragment(project: Project, text: String, context: PsiElement?, isPhysical: Boolean): PsiFile =
        PsiFileFactory.getInstance(project).createFileFromText("fragment.rs", RustFileType, text, LocalTimeCounter.currentTime(), isPhysical)
}

/**
 * The expression under the mouse, from the text alone (there is no parser): a name with the fields before it, `self.items.len` up to
 * the name the mouse is on. Not a call or a macro (evaluating them would run code), not a keyword or a path segment (`std::mem`).
 */
object RustHoverExpression {
    private val KEYWORDS = setOf(
        "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum", "extern", "false", "fn", "for", "if", "impl", "in",
        "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return", "static", "struct", "super", "trait", "true", "type",
        "unsafe", "use", "where", "while", "Self",
    )

    /** The offsets (inclusive) of the expression at [offset], or null. */
    fun rangeAt(text: CharSequence, offset: Int): IntRange? {
        if (offset < 0 || offset > text.length) return null
        var start = offset
        while (start > 0 && isNamePart(text[start - 1])) start--
        var end = offset
        while (end < text.length && isNamePart(text[end])) end++
        if (start == end) return null
        // a number, unless it is the field of a tuple (`pair.0`)
        if (text[start].isDigit() && !(start >= 2 && text[start - 1] == '.' && isNamePart(text[start - 2]))) return null
        val name = text.substring(start, end)
        if (name in KEYWORDS) return null
        var after = end
        while (after < text.length && text[after] == ' ') after++
        if (after < text.length && (text[after] == '(' || text[after] == '!')) return null
        if (end + 1 < text.length && text[end] == ':' && text[end + 1] == ':') return null
        if (start >= 2 && text[start - 1] == ':' && text[start - 2] == ':') return null
        // the fields before it: `a.b.` (a tuple field `t.0` included)
        var chainStart = start
        while (chainStart >= 2 && text[chainStart - 1] == '.' && isNamePart(text[chainStart - 2])) {
            var s = chainStart - 1
            while (s > 0 && isNamePart(text[s - 1])) s--
            if (text.substring(s, chainStart - 1) in KEYWORDS - "self") break
            chainStart = s
        }
        if (text[chainStart].isDigit()) return null
        return chainStart until end
    }

    private fun isNamePart(c: Char): Boolean = c == '_' || c.isLetterOrDigit()
}
