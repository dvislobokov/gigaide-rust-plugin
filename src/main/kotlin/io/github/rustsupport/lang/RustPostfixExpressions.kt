package io.github.rustsupport.lang

import com.intellij.openapi.util.TextRange
import com.intellij.psi.tree.IElementType
import io.github.rustsupport.lang.RustTokenTypes as T

/**
 * The expression a postfix key applies to, by the tokens of [RustLexer]: there is no parser, so it is the longest run of tokens before
 * the dot that reads as one postfix expression: a name or a path (`a::b::C`, `Vec::<u8>::new`), a literal, a macro call (`vec![1]`),
 * a parenthesized group or an array, followed by calls `(…)`, indexing `[…]`, `?`, and `.field` / `.method(…)` / `.0` / `.await` chains.
 * Prefix operators (`&`, `*`, `!`, `-`), binary operators, `as`, keywords and blocks (`}`) end it: `&x.ref` wraps `x`, not `&x`.
 */
object RustPostfixExpressions {
    private class Token(val type: IElementType, val start: Int, val end: Int, val text: String)

    /** Keywords that are values or path segments: `self.field`, `Self::new()`, `crate::x`, `true.not`, `fut.await`. */
    private val PRIMARY_KEYWORDS = setOf("self", "Self", "super", "crate", "true", "false", "await")

    /**
     * The range of the expression that ends before the dot of a postfix key; [offset] is right after that dot (`x.|`) or right after the
     * key (`x.if|`), the key is then skipped. Null when there is no dot there, the dot is in a string or a comment, or no expression ends at it.
     */
    fun expressionBefore(text: CharSequence, offset: Int): TextRange? {
        if (offset <= 0 || offset > text.length) return null
        var dot = offset - 1
        while (dot >= 0 && (text[dot].isLetterOrDigit() || text[dot] == '_')) dot--
        if (dot < 0 || text[dot] != '.') return null
        val tokens = codeTokens(text, dot + 1).toMutableList()
        val number = tokens.lastOrNull()
        // `42.` and `pair.0.` read as floats when nothing follows the dot (the key is gone): the dot is the key's
        if (number != null && number.type == T.NUMBER && number.end == dot + 1 && number.end - number.start > 1) {
            tokens[tokens.lastIndex] = Token(T.NUMBER, number.start, dot, "")
            tokens += Token(T.DOT, dot, dot + 1, "")
        }
        val last = tokens.lastOrNull() ?: return null
        // the dot is a token of its own: not a part of a string, a comment, `..` or a float
        if (last.type != T.DOT || last.start != dot) return null
        val start = startOfExpression(tokens, tokens.size - 2) ?: return null
        return TextRange(tokens[start].start, tokens[tokens.size - 2].end)
    }

    /** The tokens of the text up to [end] without spaces and comments. */
    private fun codeTokens(text: CharSequence, end: Int): List<Token> {
        val result = ArrayList<Token>()
        val lexer = RustLexer()
        lexer.start(text, 0, end, 0)
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != T.WHITE_SPACE && type !in T.COMMENTS) {
                result += Token(type, lexer.tokenStart, lexer.tokenEnd, if (type == T.OPERATOR || type == T.KEYWORD || type == T.MACRO) text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString() else "")
            }
            lexer.advance()
        }
        return result
    }

    private enum class State { END, AFTER_GROUP, AFTER_PRIMARY }

    /** The index of the first token of the expression whose last token is [last], read right to left; null when there is none. */
    private fun startOfExpression(tokens: List<Token>, last: Int): Int? {
        var i = last
        var start: Int? = null
        var state = State.END
        while (i >= 0) {
            val t = tokens[i]
            when (state) {
                State.END -> when {
                    t.type == T.OPERATOR && t.text == "?" -> i--
                    t.type == T.RPAREN || t.type == T.RBRACKET -> {
                        val open = matchingOpen(tokens, i) ?: return start
                        start = open
                        i = open - 1
                        state = State.AFTER_GROUP
                    }
                    isPrimary(t) -> {
                        start = i
                        i--
                        state = State.AFTER_PRIMARY
                    }
                    else -> return start
                }
                State.AFTER_GROUP -> when {
                    // a call or an indexing of a name: `f(x)`, `xs[0]`, `self(…)` is nothing but harmless
                    t.type == T.IDENTIFIER || t.type == T.KEYWORD && t.text in PRIMARY_KEYWORDS -> state = State.END
                    // `vec![…]`, `format!(…)`
                    t.type == T.OPERATOR && t.text == "!" && i > 0 && tokens[i - 1].type == T.MACRO -> {
                        start = i - 1
                        i -= 2
                        state = State.AFTER_PRIMARY
                    }
                    t.type == T.RPAREN || t.type == T.RBRACKET || t.type == T.OPERATOR && t.text == "?" -> state = State.END
                    // `f::<T>(x)`
                    isClosingAngle(t) -> {
                        val angle = turbofish(tokens, i) ?: return start
                        i = angle - 1
                        state = State.END
                    }
                    // the group itself is the primary: `(a + b)`, `[1, 2]`, `(a, b)`
                    else -> state = State.AFTER_PRIMARY
                }
                State.AFTER_PRIMARY -> when {
                    t.type == T.DOT -> {
                        i--
                        state = State.END
                    }
                    t.type == T.OPERATOR && t.text == "::" -> {
                        i--
                        if (i >= 0 && isClosingAngle(tokens[i])) {
                            val open = matchingAngle(tokens, i) ?: return start
                            if (open > 0 && tokens[open - 1].type == T.OPERATOR && tokens[open - 1].text == "::") {
                                // `Vec::<u8>::new`
                                i = open - 2
                                state = State.END
                            } else if (open > 0 && tokens[open - 1].type == T.IDENTIFIER) {
                                // `Vec<u8>::new` (a type in a path)
                                i = open - 1
                                state = State.END
                            } else {
                                // `<T as Trait>::f`: the qualified path starts at `<`
                                return open
                            }
                        } else {
                            state = State.END
                        }
                    }
                    else -> return start
                }
            }
        }
        return start
    }

    private fun isPrimary(t: Token): Boolean = when (t.type) {
        T.IDENTIFIER, T.NUMBER, T.STRING, T.RAW_STRING, T.CHAR -> true
        T.KEYWORD -> t.text in PRIMARY_KEYWORDS
        else -> false
    }

    private fun isClosingAngle(t: Token): Boolean = t.type == T.OPERATOR && (t.text == ">" || t.text == ">>")

    /** `::<…>` of a turbofish ending with the angle at [close]: the index of `::`, or null. */
    private fun turbofish(tokens: List<Token>, close: Int): Int? {
        val open = matchingAngle(tokens, close) ?: return null
        return (open - 1).takeIf { it >= 0 && tokens[it].type == T.OPERATOR && tokens[it].text == "::" }
    }

    /** The `(` or `[` that the closing bracket at [close] closes. */
    private fun matchingOpen(tokens: List<Token>, close: Int): Int? {
        var depth = 0
        for (i in close downTo 0) {
            when (tokens[i].type) {
                T.RPAREN, T.RBRACKET, T.RBRACE -> depth++
                T.LPAREN, T.LBRACKET, T.LBRACE -> if (--depth == 0) return i
            }
        }
        return null
    }

    /** The `<` of generics closed at [close]: `>` and `>>` close one or two, `<` opens one; brackets in between are skipped. */
    private fun matchingAngle(tokens: List<Token>, close: Int): Int? {
        var depth = 0
        var i = close
        while (i >= 0) {
            val t = tokens[i]
            when {
                t.type == T.OPERATOR && t.text.all { it == '>' } -> depth += t.text.length
                t.type == T.OPERATOR && t.text.all { it == '<' } -> {
                    depth -= t.text.length
                    if (depth <= 0) return i
                }
                t.type == T.RPAREN || t.type == T.RBRACKET -> i = matchingOpen(tokens, i) ?: return null
                // a statement or a block between: not generics
                t.type == T.SEMICOLON || t.type == T.LBRACE || t.type == T.RBRACE -> return null
            }
            i--
        }
        return null
    }
}
