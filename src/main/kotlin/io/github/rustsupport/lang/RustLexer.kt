package io.github.rustsupport.lang

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType
import io.github.rustsupport.lang.RustTokenTypes as T

/**
 * The tokens of Rust after the lexical structure of the Reference (https://doc.rust-lang.org/reference/tokens.html), as `rustc_lexer`
 * splits them. Stateless between tokens: a block comment or a raw string is one token however long, so relexing from any token start
 * gives the same result and the state is always 0.
 */
class RustLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var end = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        end = endOffset
        tokenEnd = startOffset
        advance()
    }

    override fun getState(): Int = 0
    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = end

    override fun advance() {
        tokenStart = tokenEnd
        if (tokenStart >= end) {
            tokenType = null
            return
        }
        tokenType = scan(tokenStart)
    }

    private fun at(i: Int): Char = if (i < end) buffer[i] else '\u0000'

    /** Sets [tokenEnd] and returns the type of the token at [p]. */
    private fun scan(p: Int): IElementType {
        val c = at(p)
        when {
            c.isWhitespace() -> {
                var i = p
                while (i < end && at(i).isWhitespace()) i++
                return token(i, T.WHITE_SPACE)
            }
            c == '/' && at(p + 1) == '/' -> return lineComment(p)
            c == '/' && at(p + 1) == '*' -> return blockComment(p)
            c == '"' -> return token(quoted(p + 1, '"'), T.STRING)
            c == '\'' -> return charOrLifetime(p)
            c.isDigit() -> return token(number(p), T.NUMBER)
            isIdentStart(c) -> return identifierOrPrefixed(p)
        }
        return when (c) {
            '(' -> token(p + 1, T.LPAREN)
            ')' -> token(p + 1, T.RPAREN)
            '[' -> token(p + 1, T.LBRACKET)
            ']' -> token(p + 1, T.RBRACKET)
            '{' -> token(p + 1, T.LBRACE)
            '}' -> token(p + 1, T.RBRACE)
            ';' -> token(p + 1, T.SEMICOLON)
            ',' -> token(p + 1, T.COMMA)
            '#' -> token(p + 1, T.HASH)
            '.' -> if (at(p + 1) == '.') token(if (at(p + 2) == '.' || at(p + 2) == '=') p + 3 else p + 2, T.OPERATOR) else token(p + 1, T.DOT)
            ':' -> token(if (at(p + 1) == ':') p + 2 else p + 1, T.OPERATOR)
            '+', '-', '*', '/', '%', '^', '!', '&', '|', '=', '<', '>', '@', '$', '?', '~' -> token(operator(p), T.OPERATOR)
            else -> token(p + Character.charCount(Character.codePointAt(buffer, p)), T.BAD_CHARACTER)
        }
    }

    private fun token(endOffset: Int, type: IElementType): IElementType {
        tokenEnd = endOffset.coerceAtMost(end)
        return type
    }

    /** `->`, `=>`, `==`, `!=`, `<=`, `>=`, `&&`, `||`, `<<`, `>>`, `+=` and the other compound assignments as one token each. */
    private fun operator(p: Int): Int {
        val c = at(p)
        val n = at(p + 1)
        if ((c == '<' && n == '<' || c == '>' && n == '>') && at(p + 2) == '=') return p + 3
        val twoChar = when (c) {
            '-' -> n == '>' || n == '='
            '=' -> n == '>' || n == '='
            '&' -> n == '&' || n == '='
            '|' -> n == '|' || n == '='
            '<' -> n == '<' || n == '='
            '>' -> n == '>' || n == '='
            '+', '*', '/', '%', '^', '!' -> n == '='
            else -> false
        }
        return if (twoChar) p + 2 else p + 1
    }

    private fun lineComment(p: Int): IElementType {
        var i = p
        while (i < end && at(i) != '\n') i++
        // `///` and `//!` document; `////` is a plain comment again
        val doc = at(p + 2) == '/' && at(p + 3) != '/' || at(p + 2) == '!'
        return token(i, if (doc) T.DOC_COMMENT else T.LINE_COMMENT)
    }

    /** Block comments nest. An unterminated one runs to the end of the file. */
    private fun blockComment(p: Int): IElementType {
        var depth = 0
        var i = p
        while (i < end) {
            if (at(i) == '/' && at(i + 1) == '*') {
                depth++
                i += 2
            } else if (at(i) == '*' && at(i + 1) == '/') {
                depth--
                i += 2
                if (depth == 0) break
            } else {
                i++
            }
        }
        // `/** */` and `/*! */` document; `/**/` and `/***` are plain
        val doc = at(p + 2) == '*' && at(p + 3) != '*' && at(p + 3) != '/' || at(p + 2) == '!'
        return token(i, if (doc) T.DOC_COMMENT else T.BLOCK_COMMENT)
    }

    /** The end of a literal opened before [from] and closed by [quote], with backslash escapes; unterminated, the end of the file. */
    private fun quoted(from: Int, quote: Char): Int {
        var i = from
        while (i < end) {
            when (at(i)) {
                '\\' -> i += 2
                quote -> return suffix(i + 1)
                else -> i++
            }
        }
        return end
    }

    /** `r"…"`, `r#"…"#` with any number of `#`, after a prefix that ends at [afterR]; null when this is not a raw string. */
    private fun rawString(afterR: Int): Int? {
        var i = afterR
        while (at(i) == '#') i++
        if (at(i) != '"') return null
        val hashes = i - afterR
        i++
        while (i < end) {
            if (at(i) == '"') {
                var h = 0
                while (h < hashes && at(i + 1 + h) == '#') h++
                if (h == hashes) return suffix(i + 1 + hashes)
            }
            i++
        }
        return end
    }

    /** A literal suffix: `1u8`, `"x"suffix` (reserved, still one token). */
    private fun suffix(from: Int): Int {
        var i = from
        if (!isIdentStart(at(i))) return i
        while (i < end && isIdentPart(at(i))) i++
        return i
    }

    /** `'a'` and `'\n'` are characters, `'a` and `'static` lifetimes or labels, `'r#a` a raw lifetime. */
    private fun charOrLifetime(p: Int): IElementType {
        val c1 = at(p + 1)
        if (c1 == '\\') return token(quoted(p + 1, '\''), T.CHAR)
        if (c1 == 'r' && at(p + 2) == '#' && isIdentStart(at(p + 3))) return token(identEnd(p + 3), T.LIFETIME)
        if (isIdentStart(c1)) {
            val identEnd = identEnd(p + 1)
            val single = identEnd - (p + 1) == Character.charCount(Character.codePointAt(buffer, p + 1))
            return if (single && at(identEnd) == '\'') token(suffix(identEnd + 1), T.CHAR) else token(identEnd, T.LIFETIME)
        }
        if (p + 1 >= end || c1 == '\n') return token(p + 1, T.CHAR)
        val afterChar = p + 1 + Character.charCount(Character.codePointAt(buffer, p + 1))
        return token(if (at(afterChar) == '\'') suffix(afterChar + 1) else afterChar, T.CHAR)
    }

    /** Integers and floats in every base, with `_` separators, exponents and suffixes; `1..2` and `1.max(2)` keep their dots. */
    private fun number(p: Int): Int {
        var i = p
        if (at(i) == '0' && at(i + 1) in "xXoObB") {
            i += 2
            while (i < end && (at(i).isLetterOrDigit() || at(i) == '_') && (at(i).isDigit() || at(i) == '_' || at(i).lowercaseChar() in 'a'..'f')) i++
            return suffix(i)
        }
        while (i < end && (at(i).isDigit() || at(i) == '_')) i++
        if (at(i) == '.' && at(i + 1) != '.' && !isIdentStart(at(i + 1))) {
            i++
            while (i < end && (at(i).isDigit() || at(i) == '_')) i++
        }
        if (at(i) == 'e' || at(i) == 'E') {
            var j = i + 1
            if (at(j) == '+' || at(j) == '-') j++
            if (at(j).isDigit() || at(j) == '_') {
                i = j
                while (i < end && (at(i).isDigit() || at(i) == '_')) i++
            }
        }
        return suffix(i)
    }

    private fun identifierOrPrefixed(p: Int): IElementType {
        val c = at(p)
        val n = at(p + 1)
        // raw identifier r#name
        if (c == 'r' && n == '#' && isIdentStart(at(p + 2))) return token(identEnd(p + 2), T.IDENTIFIER)
        // r"…", br"…", cr"…" and the hashed forms
        if (c == 'r') rawString(p + 1)?.let { return token(it, T.RAW_STRING) }
        if ((c == 'b' || c == 'c') && n == 'r') rawString(p + 2)?.let { return token(it, T.RAW_STRING) }
        // b"…", c"…", b'…'
        if ((c == 'b' || c == 'c') && n == '"') return token(quoted(p + 2, '"'), T.STRING)
        if (c == 'b' && n == '\'') return token(quoted(p + 2, '\''), T.CHAR)
        val identEnd = identEnd(p)
        val text = buffer.subSequence(p, identEnd).toString()
        if (text in T.KEYWORDS) return token(identEnd, T.KEYWORD)
        // a macro call: `name!(`, `name![`, `name! {`; `a != b` is an operator
        if (at(identEnd) == '!' && at(identEnd + 1) != '=') return token(identEnd, T.MACRO)
        return token(identEnd, T.IDENTIFIER)
    }

    private fun identEnd(from: Int): Int {
        var i = from
        while (i < end) {
            val cp = Character.codePointAt(buffer, i)
            if (!(cp == '_'.code || Character.isUnicodeIdentifierPart(cp)) || Character.isIdentifierIgnorable(cp)) break
            i += Character.charCount(cp)
        }
        return i
    }

    private fun isIdentStart(c: Char): Boolean = c == '_' || c.isLetter()
    private fun isIdentPart(c: Char): Boolean = c == '_' || c.isLetterOrDigit()
}
