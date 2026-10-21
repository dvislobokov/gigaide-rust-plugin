package io.github.rustsupport.lang

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiFile
import io.github.rustsupport.lang.RustTokenTypes as T

/**
 * Enter in a line comment goes on with it, as rust-analyzer's `experimental/onEnter` does: `///` and `//!` always, `//` only when Enter
 * splits its text (going on with a plain comment after its end is a nuisance, rust-analyzer says so too). Done here by the lexer, not by
 * the server: Enter is typed all the time, a round trip on the UI thread for each would be felt, and a busy server would not answer at all.
 */
object RustCommentContinuation {
    /** A replacement of [start, end) with [text], the caret after it. */
    class Edit(val start: Int, val end: Int, val text: String)

    fun onEnter(text: CharSequence, offset: Int): Edit? {
        if (offset <= 0 || offset > text.length) return null
        val lexer = RustLexer()
        lexer.start(text, 0, text.length, 0)
        while (lexer.tokenType != null && lexer.tokenEnd < offset) lexer.advance()
        // the caret at the very end of a comment is at the end of its token, inside it otherwise
        val type = lexer.tokenType ?: return null
        val start = lexer.tokenStart
        val end = lexer.tokenEnd
        if (type != T.LINE_COMMENT && type != T.DOC_COMMENT || offset <= start || offset > end) return null
        val comment = text.subSequence(start, end)
        if (!comment.startsWith("//")) return null // a block doc comment
        // `////` is a plain comment to the lexer, as to rustc
        val prefix = if (type == T.DOC_COMMENT) comment.substring(0, 3) else "//"
        if (offset < start + prefix.length) return null
        if (prefix == "//" && text.subSequence(offset, end).isBlank()) return null

        // a comment after code (`let x = 1; // why`) is left to Enter: the next line belongs to the code
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        val indent = text.subSequence(lineStart, start)
        if (indent.isNotBlank()) return null

        // the text that moves to the new line starts right after the prefix and its space
        var rest = offset
        while (rest < end && text[rest] == ' ') rest++
        // and no spaces are left at the end of the old line
        var cut = offset
        while (cut > start + prefix.length && text[cut - 1] == ' ') cut--
        return Edit(cut, rest,"\n$indent$prefix ")
    }

    private fun CharSequence.lastIndexOf(c: Char, from: Int): Int {
        var i = from
        while (i >= 0) {
            if (this[i] == c) return i
            i--
        }
        return -1
    }
}

class RustCommentEnterHandler : EnterHandlerDelegateAdapter() {
    override fun preprocessEnter(
        file: PsiFile, editor: Editor, caretOffset: Ref<Int>, caretAdvance: Ref<Int>, dataContext: DataContext, originalHandler: EditorActionHandler?,
    ): EnterHandlerDelegate.Result {
        if (file !is RustFile || editor.caretModel.caretCount > 1 || editor.selectionModel.hasSelection()) return EnterHandlerDelegate.Result.Continue
        val document = editor.document
        val edit = RustCommentContinuation.onEnter(document.immutableCharSequence, caretOffset.get()) ?: return EnterHandlerDelegate.Result.Continue
        document.replaceString(edit.start, edit.end, edit.text)
        editor.caretModel.moveToOffset(edit.start + edit.text.length)
        editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
        return EnterHandlerDelegate.Result.Stop
    }
}
