package io.github.rustsupport.lang

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplate
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplateProvider
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.lang.ref.WeakReference

/**
 * `expr.if`, `opt.match`, `value.dbg`, `x.ok`: the postfix templates of Rust. The expression is found by the tokens
 * ([RustPostfixExpressions]), not by types: rust-analyzer offers type-aware postfix completions of its own in the completion list,
 * these are the keys that expand with Tab right away and that Settings | Editor | General | Postfix Completion lists and switches.
 */
class RustPostfixTemplateProvider : PostfixTemplateProvider {
    private val templates: Set<PostfixTemplate> = RustPostfixKinds.ALL.map { RustPostfixTemplate(it, this) }.toSet()

    override fun getTemplates(): Set<PostfixTemplate> = templates
    override fun isTerminalSymbol(currentChar: Char): Boolean = currentChar == '.'
    override fun preExpand(file: PsiFile, editor: Editor) {}
    override fun afterExpand(file: PsiFile, editor: Editor) {}
    override fun preCheck(copyFile: PsiFile, realEditor: Editor, currentOffset: Int): PsiFile = copyFile
    override fun getId(): String = "rust"
    override fun getPresentableName(): String = "Rust"

    /** The platform asks every template against the same copy of the document: the expression is found once per its state and offset. */
    private var cache: Triple<WeakReference<Document>, Pair<Long, Int>, TextRange?>? = null

    @Synchronized
    fun expression(document: Document, offset: Int): TextRange? {
        val key = document.modificationStamp to offset
        cache?.takeIf { it.first.get() === document && it.second == key }?.let { return it.third }
        return RustPostfixExpressions.expressionBefore(document.immutableCharSequence, offset).also { cache = Triple(WeakReference(document), key, it) }
    }
}

/**
 * One template: [text] is what replaces `expr.key`, with `$EXPR$` for the expression, `$END$` for the caret and the names of [stops]
 * (with their defaults) for the stops to type. A line of [text] after the first one is indented as the line of the expression, plus a
 * level for each leading tab.
 */
class RustPostfixKind(val key: String, val example: String, val text: String, val stops: List<Pair<String, String>> = emptyList(), val description: String = example) {
    val id: String get() = "rust.$key"
}

object RustPostfixKinds {
    private fun wrap(key: String, before: String, after: String, description: String) =
        RustPostfixKind(key, "$before" + "expr" + after, before + "\$EXPR$" + after + "\$END$", description = description)

    private fun method(key: String, call: String) =
        RustPostfixKind(key, "expr.$call", "\$EXPR$.$call\$END$", description = "Calls <code>.$call</code> on the expression")

    val ALL: List<RustPostfixKind> = listOf(
        RustPostfixKind("if", "if expr {}", "if \$EXPR$ {\n\t\$END$\n}", description = "Checks a boolean expression: <code>if expr { }</code>"),
        wrap("not", "!", "", "Negates a boolean expression: <code>!expr</code>"),
        RustPostfixKind("match", "match expr {}", "match \$EXPR$ {\n\t\$END$\n}", description = "Matches the expression: <code>match expr { }</code>"),
        RustPostfixKind("ifl", "if let Some(x) = expr {}", "if let Some(\$x$) = \$EXPR$ {\n\t\$END$\n}", listOf("x" to "x"),
            "Unwraps an <code>Option</code>: <code>if let Some(x) = expr { }</code>"),
        RustPostfixKind("iflet", "if let Some(x) = expr {}", "if let Some(\$x$) = \$EXPR$ {\n\t\$END$\n}", listOf("x" to "x"),
            "Unwraps an <code>Option</code>: <code>if let Some(x) = expr { }</code>"),
        RustPostfixKind("while", "while expr {}", "while \$EXPR$ {\n\t\$END$\n}", description = "A loop while the expression holds: <code>while expr { }</code>"),
        RustPostfixKind("let", "let name = expr;", "let \$name$ = \$EXPR$;\$END$", listOf("name" to "x"), "Binds the expression to a new variable"),
        RustPostfixKind("letm", "let mut name = expr;", "let mut \$name$ = \$EXPR$;\$END$", listOf("name" to "x"), "Binds the expression to a new mutable variable"),
        wrap("ref", "&", "", "Borrows the expression: <code>&amp;expr</code>"),
        wrap("refm", "&mut ", "", "Borrows the expression mutably: <code>&amp;mut expr</code>"),
        wrap("deref", "*", "", "Dereferences the expression: <code>*expr</code>"),
        wrap("dbg", "dbg!(", ")", "Prints the expression with its file and line to stderr and returns it: <code>dbg!(expr)</code>"),
        RustPostfixKind("println", "println!(\"{:?}\", expr);", "println!(\"{:?}\", \$EXPR$);\$END$", description = "Prints the expression with its Debug format"),
        wrap("ok", "Ok(", ")", "Wraps the expression into <code>Ok</code>"),
        wrap("err", "Err(", ")", "Wraps the expression into <code>Err</code>"),
        wrap("some", "Some(", ")", "Wraps the expression into <code>Some</code>"),
        wrap("box", "Box::new(", ")", "Puts the expression on the heap: <code>Box::new(expr)</code>"),
        wrap("rc", "Rc::new(", ")", "Wraps the expression into a reference-counted pointer: <code>Rc::new(expr)</code>"),
        wrap("arc", "Arc::new(", ")", "Wraps the expression into an atomically reference-counted pointer: <code>Arc::new(expr)</code>"),
        method("unwrap", "unwrap()"),
        RustPostfixKind("expect", "expr.expect(\"msg\")", "\$EXPR$.expect(\"\$msg$\")\$END$", listOf("msg" to ""),
            "Unwraps the expression with a message for the panic: <code>expr.expect(\"msg\")</code>"),
        RustPostfixKind("return", "return expr;", "return \$EXPR$;\$END$", description = "Returns the expression from the function"),
        wrap("par", "(", ")", "Parenthesizes the expression: <code>(expr)</code>"),
        RustPostfixKind("for", "for x in expr {}", "for \$x$ in \$EXPR$ {\n\t\$END$\n}", listOf("x" to "x"), "Iterates over the expression: <code>for x in expr { }</code>"),
        method("iter", "iter()"),
        method("into", "into()"),
        method("clone", "clone()"),
        method("to_string", "to_string()"),
    )
}

class RustPostfixTemplate(private val kind: RustPostfixKind, private val rustProvider: RustPostfixTemplateProvider) :
    PostfixTemplate(kind.id, kind.key, ".${kind.key}", kind.example, rustProvider) {

    // one class serves every key: the description is the kind's; postfixTemplates/RustPostfixTemplate/ holds only the common preview
    override fun calcDescription(): String = kind.description

    override fun isApplicable(context: PsiElement, copyDocument: Document, newOffset: Int): Boolean =
        context.containingFile is RustFile && rustProvider.expression(copyDocument, newOffset) != null

    override fun expand(context: PsiElement, editor: Editor) {
        val project = context.project
        val document = editor.document
        val offset = editor.caretModel.offset
        val range = RustPostfixExpressions.expressionBefore(document.immutableCharSequence, offset) ?: return
        val expression = document.getText(range)
        val lineStart = document.getLineStartOffset(document.getLineNumber(range.startOffset))
        val text = document.immutableCharSequence
        var indentEnd = lineStart
        while (indentEnd < text.length && (text[indentEnd] == ' ' || text[indentEnd] == '\t')) indentEnd++
        val indent = text.subSequence(lineStart, indentEnd).toString()
        val file = context.containingFile
        val unit = RustIndentEngine.indentText(1, file?.let { CodeStyle.getIndentOptions(it) } ?: CodeStyle.getSettings(project).getIndentOptions(RustFileType))

        document.deleteString(range.startOffset, offset)
        editor.caretModel.moveToOffset(range.startOffset)
        val manager = TemplateManager.getInstance(project)
        val template = manager.createTemplate(kind.id, "rust", RustPostfixText.indent(kind.text, indent, unit))
        template.isToReformat = false
        template.addVariable("EXPR", ConstantNode(expression), false)
        for ((name, default) in kind.stops) template.addVariable(name, ConstantNode(default), ConstantNode(default), true)
        manager.startTemplate(editor, template)
    }
}

object RustPostfixText {
    /** Every line of [text] but the first gets [indent], and [unit] for each of its leading tabs. */
    fun indent(text: String, indent: String, unit: String): String = text.split('\n').mapIndexed { index, line ->
        if (index == 0) line else {
            val tabs = line.takeWhile { it == '\t' }.length
            indent + unit.repeat(tabs) + line.substring(tabs)
        }
    }.joinToString("\n")
}
