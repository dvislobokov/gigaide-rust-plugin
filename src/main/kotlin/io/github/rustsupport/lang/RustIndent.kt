package io.github.rustsupport.lang

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter
import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider
import com.intellij.psi.tree.IElementType
import io.github.rustsupport.lang.RustTokenTypes as T

/**
 * The indent of a line while typing, the way rustfmt would have it (the engine of the Go sibling over the tokens of [RustLexer]): a level
 * per line that has brackets still open (not per bracket: `foo(|x| {` opens two and indents one), a closing bracket at the level of the
 * line that opened it, a level more for a line that continues an expression. Whole files are formatted by rustfmt.
 */
object RustIndentEngine {
    /**
     * The number of indent levels of the line that starts at [lineStart]; null inside a raw string, a string or a block comment, which are as
     * they are. By the lexer over the text: the line is being typed.
     */
    fun levelOf(text: CharSequence, lineStart: Int): Int? {
        val stack = ArrayList<Int>()
        var line = 0
        var last: IElementType? = null
        var lastText = ""
        val lexer = RustLexer()
        lexer.start(text, 0, text.length, 0)
        while (true) {
            val type = lexer.tokenType ?: break
            val start = lexer.tokenStart
            val end = lexer.tokenEnd
            if (start >= lineStart) break
            // a token that spans the line start: the line is a part of it
            if (end > lineStart && type != T.WHITE_SPACE) return null
            for (k in start until end) if (text[k] == '\n') line++
            when (type) {
                T.WHITE_SPACE, T.LINE_COMMENT, T.BLOCK_COMMENT, T.DOC_COMMENT -> {}
                else -> {
                    when (type) {
                        T.LBRACE, T.LPAREN, T.LBRACKET -> stack += line
                        T.RBRACE, T.RPAREN, T.RBRACKET -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                    }
                    last = type
                    lastText = if (type == T.OPERATOR) text.subSequence(start, end).toString() else ""
                }
            }
            lexer.advance()
        }

        // what the line itself starts with
        var first = lineStart
        while (first < text.length && (text[first] == ' ' || text[first] == '\t')) first++
        var closers = 0
        var probe = first
        while (probe < text.length && text[probe] in "})]") {
            closers++
            probe++
        }
        val startsWithDot = closers == 0 && probe < text.length && text[probe] == '.' && text.getOrNull(probe + 1) != '.'

        val remaining = stack.dropLast(closers.coerceAtMost(stack.size))
        var level = remaining.distinct().size
        // the brackets a closer closes and the ones that stay may share a line: `})` of `foo(|x| {` closes that line as a whole
        if (closers > 0 && remaining.isNotEmpty() && stack.size > remaining.size && stack[remaining.size] == remaining.last()) level--
        if (closers == 0 && (continues(last, lastText) || startsWithDot && last != T.SEMICOLON && last != T.LBRACE && last != T.RBRACE)) level++
        return level.coerceAtLeast(0)
    }

    /**
     * A line whose last token cannot end a statement is continued by the next one: `let total =`, `a +`, `x.`, `Some(x) =>`; not `x?`,
     * not the `>` of `Vec<T>`, not a `,` `;` or bracket.
     */
    private fun continues(last: IElementType?, operator: String): Boolean = when (last) {
        T.DOT -> true
        T.OPERATOR -> operator != "?" && operator != ">" && operator != "::" && operator != ":" && operator != "!"
        else -> false
    }

    fun indentText(level: Int, options: CommonCodeStyleSettings.IndentOptions): String =
        if (options.USE_TAB_CHARACTER) "\t".repeat(level) else " ".repeat(level * options.INDENT_SIZE)
}

object RustIndenter {
    /** The indent for the line of [offset], as text; null when the line is to be left alone. */
    fun indentFor(project: Project, document: Document, offset: Int): String? {
        val file = PsiDocumentManager.getInstance(project).getCachedPsiFile(document)
        val options = file?.let { CodeStyle.getIndentOptions(it) } ?: CodeStyle.getSettings(project).getIndentOptions(RustFileType)
        val lineStart = document.getLineStartOffset(document.getLineNumber(offset.coerceIn(0, document.textLength)))
        return RustIndentEngine.levelOf(document.immutableCharSequence, lineStart)?.let { RustIndentEngine.indentText(it, options) }
    }
}

/** Enter, a typed `}`, and everything else of the platform that asks "what is the indent of this line" of a language without a formatter model. */
class RustLineIndentProvider : LineIndentProvider {
    override fun isSuitableFor(language: Language?): Boolean = language == RustLanguage

    override fun getLineIndent(project: Project, editor: Editor, language: Language?, offset: Int): String? =
        RustIndenter.indentFor(project, editor.document, offset) ?: LineIndentProvider.DO_NOT_ADJUST
}

/**
 * Enter between a pair of brackets, `{|}`, `(|)`, `[|]`: the pair opens into three lines with the caret on the indented middle one.
 * Done here because the platform does it through the formatter of the language, which there is none of.
 */
class RustEnterBetweenBracketsHandler : EnterHandlerDelegateAdapter() {
    override fun preprocessEnter(
        file: PsiFile, editor: Editor, caretOffset: Ref<Int>, caretAdvance: Ref<Int>, dataContext: DataContext, originalHandler: EditorActionHandler?,
    ): EnterHandlerDelegate.Result {
        if (file !is RustFile || editor.caretModel.caretCount > 1 || editor.selectionModel.hasSelection()) return EnterHandlerDelegate.Result.Continue
        val document = editor.document
        val text = document.immutableCharSequence
        val offset = caretOffset.get()
        if (offset < 1 || offset >= text.length || PAIRS[text[offset - 1]] != text[offset]) return EnterHandlerDelegate.Result.Continue

        document.insertString(offset, "\n\n")
        val closing = RustIndenter.indentFor(file.project, document, offset + 2).orEmpty()
        document.insertString(offset + 2, closing)
        val middle = RustIndenter.indentFor(file.project, document, offset + 1).orEmpty()
        document.insertString(offset + 1, middle)
        editor.caretModel.moveToOffset(offset + 1 + middle.length)
        editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
        return EnterHandlerDelegate.Result.Stop
    }

    private companion object {
        val PAIRS = mapOf('{' to '}', '(' to ')', '[' to ']')
    }
}
