package io.github.rustsupport.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RustCommentEnterTest {
    /** The text after Enter at `|`, with `|` where the caret goes; null when Enter is left to the IDE. */
    private fun enter(textWithCaret: String): String? {
        val offset = textWithCaret.indexOf('|')
        val text = textWithCaret.removeRange(offset, offset + 1)
        val edit = RustCommentContinuation.onEnter(text, offset) ?: return null
        val result = text.replaceRange(edit.start, edit.end, edit.text)
        val caret = edit.start + edit.text.length
        return result.substring(0, caret) + "|" + result.substring(caret)
    }

    @Test
    fun `doc comments go on`() {
        assertEquals("/// Docs\n/// |\nfn main() {}", enter("/// Docs|\nfn main() {}"))
        assertEquals("    /// Docs\n    /// |here\n", enter("    /// Docs |here\n"))
        assertEquals("//! Crate\n//! |", enter("//! Crate|"))
        assertEquals("///\n/// |", enter("///|"))
    }

    @Test
    fun `a plain comment goes on only when its text is split`() {
        assertNull(enter("// note|\nfn main() {}"))
        assertEquals("// split\n// |here", enter("// split |here"))
        assertNull(enter("//// not a doc comment|"))
    }

    @Test
    fun `not in the prefix, not after code, not in a string`() {
        assertNull(enter("/|// Docs"))
        assertNull(enter("let x = 1; /// odd|"))
        assertNull(enter("let s = \"/// no|\";"))
        assertNull(enter("fn main() {|}"))
        assertNull(enter("/** block|*/"))
    }
}
