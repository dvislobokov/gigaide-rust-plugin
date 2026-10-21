package io.github.rustsupport.lang

import com.intellij.psi.TokenType
import io.github.rustsupport.lsp.RustAnalyzerStatusText
import org.junit.Assert.assertEquals
import org.junit.Test

class RustLexerTest {
    /** `TYPE:text` per token, whitespace left out. */
    private fun tokens(text: String): List<String> {
        val lexer = RustLexer()
        lexer.start(text, 0, text.length, 0)
        val result = ArrayList<String>()
        while (lexer.tokenType != null) {
            if (lexer.tokenType != TokenType.WHITE_SPACE) result += "${lexer.tokenType}:${text.substring(lexer.tokenStart, lexer.tokenEnd)}"
            lexer.advance()
        }
        return result
    }

    @Test
    fun `keywords, names, macros`() {
        assertEquals(
            listOf("KEYWORD:pub", "KEYWORD:fn", "IDENTIFIER:main", "(:(", "):)", "{:{", "MACRO:println", "OPERATOR:!", "(:(", "):)", "}:}"),
            tokens("pub fn main() { println!() }"),
        )
        assertEquals(listOf("IDENTIFIER:a", "OPERATOR:!=", "IDENTIFIER:b"), tokens("a!=b"))
        assertEquals(listOf("IDENTIFIER:r#type", "IDENTIFIER:union"), tokens("r#type union"))
    }

    @Test
    fun `chars and lifetimes`() {
        assertEquals(listOf("CHAR:'a'", "LIFETIME:'a", "LIFETIME:'static", "CHAR:'\\n'", "CHAR:'\\''", "LIFETIME:'r#a", "CHAR:b'x'", "CHAR:'1'"),
            tokens("'a' 'a 'static '\\n' '\\'' 'r#a b'x' '1'"))
        assertEquals(listOf("OPERATOR:&", "LIFETIME:'a", "IDENTIFIER:str"), tokens("&'a str"))
    }

    @Test
    fun `strings of every kind`() {
        assertEquals(listOf("STRING:\"a\\\"b\"", "STRING:b\"x\"", "STRING:c\"y\""), tokens("\"a\\\"b\" b\"x\" c\"y\""))
        assertEquals(listOf("RAW_STRING:r#\"a \"quoted\" b\"#", "RAW_STRING:br\"z\"", "RAW_STRING:cr##\"w\"##"), tokens("r#\"a \"quoted\" b\"# br\"z\" cr##\"w\"##"))
        assertEquals(listOf("STRING:\"multi\nline\""), tokens("\"multi\nline\""))
    }

    @Test
    fun `numbers keep ranges and method calls apart`() {
        assertEquals(listOf("NUMBER:1", "OPERATOR:..", "NUMBER:2"), tokens("1..2"))
        assertEquals(listOf("NUMBER:1", ".:.", "IDENTIFIER:max"), tokens("1.max"))
        assertEquals(listOf("NUMBER:1.5e-3f64", "NUMBER:0xFF_u8", "NUMBER:0b1010", "NUMBER:1_000", "NUMBER:2."), tokens("1.5e-3f64 0xFF_u8 0b1010 1_000 2."))
    }

    @Test
    fun `comments nest and document`() {
        assertEquals(listOf("BLOCK_COMMENT:/* a /* b */ c */", "IDENTIFIER:x"), tokens("/* a /* b */ c */ x"))
        assertEquals(listOf("DOC_COMMENT:/// doc", "DOC_COMMENT://! inner", "LINE_COMMENT://// plain", "LINE_COMMENT:// c"),
            tokens("/// doc\n//! inner\n//// plain\n// c"))
        assertEquals(listOf("DOC_COMMENT:/** d */", "BLOCK_COMMENT:/**/"), tokens("/** d */ /**/"))
    }

    @Test
    fun `attributes and paths`() {
        assertEquals(listOf("#:#", "[:[", "IDENTIFIER:derive", "(:(", "IDENTIFIER:Debug", "):)", "]:]"), tokens("#[derive(Debug)]"))
        assertEquals(listOf("IDENTIFIER:std", "OPERATOR:::", "IDENTIFIER:io", "OPERATOR:->", "OPERATOR:=>"), tokens("std::io -> =>"))
    }

    @Test
    fun `the lexer covers the text without gaps`() {
        val text = "fn f<'a>(x: &'a [u8]) -> Option<char> { let c = 'x'; let s = r#\"\"#; x.get(0).map(|b| *b as char) } é ✓"
        val lexer = RustLexer()
        lexer.start(text, 0, text.length, 0)
        var expected = 0
        while (lexer.tokenType != null) {
            assertEquals(expected, lexer.tokenStart)
            expected = lexer.tokenEnd
            lexer.advance()
        }
        assertEquals(text.length, expected)
    }

    private fun level(text: String): Int? {
        val caret = text.indexOf("<caret>")
        return RustIndentEngine.levelOf(text.replace("<caret>", ""), caret)
    }

    @Test
    fun `indents follow open brackets and close at their line`() {
        assertEquals(1, level("mod m {\n<caret>"))
        assertEquals(2, level("mod m {\n    struct S {\n<caret>"))
        assertEquals(1, level("mod m {\n    struct S {\n        a: u8,\n<caret>}"))
        assertEquals(0, level("mod m {\n    struct S {\n    }\n<caret>}"))
        assertEquals(1, level("fn f() {\n    foo(|x| {\n        x\n<caret>})"))
        assertEquals(2, level("fn f() {\n    let x = a\n<caret>.b()"))
        assertEquals(2, level("fn f() {\n    let total =\n<caret>"))
        assertEquals(1, level("fn f() {\n    let v: Vec<u8>\n<caret>"))
        assertEquals(null, level("fn f() {\n    let s = \"a\n<caret>b\";"))
    }

    @Test
    fun `status texts`() {
        assertEquals("rust-analyzer 1.99.1", RustAnalyzerStatusText.title("1.99.1 (58cf8f9c 2026-10-13)"))
        assertEquals("58cf8f9c 2026-10-13", RustAnalyzerStatusText.build("1.99.1 (58cf8f9c 2026-10-13)"))
        assertEquals("rust-analyzer", RustAnalyzerStatusText.title(null))
        assertEquals("1.50 GB", RustAnalyzerStatusText.memory(1536L * 1024 * 1024))
    }
}
