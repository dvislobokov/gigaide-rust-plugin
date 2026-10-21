package io.github.rustsupport.lang

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.psi.tree.IElementType
import io.github.rustsupport.lang.RustTokenTypes as T

/**
 * Where the live templates of `liveTemplates/Rust.xml` apply: the code of a Rust file, not its strings and comments (`p` + Tab inside
 * a string literal would write a `println!` into the string). The places within it are the sub-contexts below.
 */
class RustTemplateContext : TemplateContextType("Rust") {
    override fun isInContext(templateActionContext: TemplateActionContext): Boolean {
        val file = templateActionContext.file as? RustFile ?: return false
        return !RustTemplatePlaces.isInLiteralOrComment(file.viewProvider.contents, templateActionContext.startOffset)
    }
}

/** A place in Rust code: registered with `baseContextId="RUST"`, so a template of the whole language applies in each of them too. */
abstract class RustPlaceTemplateContext(presentableName: String, private val place: RustTemplatePlaces.Place) : TemplateContextType(presentableName) {
    override fun isInContext(templateActionContext: TemplateActionContext): Boolean {
        val file = templateActionContext.file as? RustFile ?: return false
        return RustTemplatePlaces.placeAt(file.viewProvider.contents, templateActionContext.startOffset) == place
    }
}

/** Where an item may be declared: the top of a file, the body of a `mod`, an `impl`, a `trait`: `fn`, `st`, `test`, `tmod`, … */
class RustItemTemplateContext : RustPlaceTemplateContext("Rust: item level", RustTemplatePlaces.Place.ITEM)

/** The code of a function body (statements and expressions): `p`, `m`, `ifl`, `todo`, … */
class RustCodeTemplateContext : RustPlaceTemplateContext("Rust: statement or expression", RustTemplatePlaces.Place.CODE)

/**
 * The place of a template abbreviation by the tokens of [RustLexer] before it: there is no parser, the open brackets say where it is.
 * Each `{` is classified by the keywords of the header in front of it (the tokens since the last `;`, `{` or `}` at its depth): a header with
 * `fn` or `=` opens code, `mod` / `impl` / `trait` / `extern` open items, anything else (`struct`, `enum`, `macro_rules!`) opens something
 * that is neither (fields, variants, macro rules). Inside code every `{` opens code again (`if`, `match`, a closure, a struct literal), except
 * an item declared there (`impl` in a function body). `(` and `[` keep code as code and make the rest neither (parameters, attributes).
 */
object RustTemplatePlaces {
    enum class Place { ITEM, CODE, OTHER }

    /** The place of the word that ends at [offset] (the abbreviation being expanded); null in a string, a character or a comment. */
    fun placeAt(text: CharSequence, offset: Int): Place? {
        if (offset < 0 || offset > text.length) return null
        if (isInLiteralOrComment(text, offset)) return null
        var start = offset
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
        return placeBefore(text, start)
    }

    /** The place of a token that would start at [start], by the brackets opened before it. */
    fun placeBefore(text: CharSequence, start: Int): Place {
        // each open bracket with the place it opens
        val open = ArrayList<Place>()
        // the header belongs to the depth it started at: what is inside its brackets (`fn f(x: impl T)`, `#[path = "x"]`) does not count
        var header = Header(0)
        val lexer = RustLexer()
        lexer.start(text, 0, start, 0)
        while (true) {
            val type = lexer.tokenType ?: break
            if (type == T.WHITE_SPACE || type in T.COMMENTS) {
                lexer.advance()
                continue
            }
            val current = open.lastOrNull() ?: Place.ITEM
            when (type) {
                T.LBRACE -> {
                    open += header.opens(current)
                    header = Header(open.size)
                }
                // the parentheses of `fn f(…)` are a part of its header: what follows them is still the `fn`
                T.LPAREN, T.LBRACKET -> open += if (current == Place.CODE) Place.CODE else Place.OTHER
                T.RBRACE -> {
                    if (open.isNotEmpty()) open.removeAt(open.lastIndex)
                    header = Header(open.size)
                }
                T.RPAREN, T.RBRACKET -> if (open.isNotEmpty()) open.removeAt(open.lastIndex)
                T.SEMICOLON -> if (open.size <= header.depth) header = Header(open.size)
                else -> if (open.size == header.depth) header.add(type, text.subSequence(lexer.tokenStart, lexer.tokenEnd))
            }
            lexer.advance()
        }
        return open.lastOrNull() ?: Place.ITEM
    }

    /** The keywords of the header of a `{` that matter for what it opens. */
    private class Header(val depth: Int) {
        var fn = false
        var assign = false
        var items = false
        var other = false

        fun add(type: IElementType, text: CharSequence) {
            when (type) {
                T.KEYWORD -> when (text.toString()) {
                    "fn" -> fn = true
                    "mod", "impl", "trait", "extern" -> items = true
                    "struct", "enum" -> other = true
                }
                T.MACRO -> if (text.toString() == "macro_rules") other = true
                T.IDENTIFIER -> if (text.toString() == "union") other = true
                T.OPERATOR -> if (text.toString() == "=") assign = true
            }
        }

        fun opens(outer: Place): Place = when {
            // `fn f() -> impl Trait {`: the function wins
            fn || assign -> Place.CODE
            items -> Place.ITEM
            other -> Place.OTHER
            outer == Place.CODE -> Place.CODE
            // `{` with no telling header at item level: a macro call `thread_local! {`, a block of an attribute; nothing to expand there
            else -> Place.OTHER
        }
    }

    /** Whether [offset] is inside a string, a character or a comment; the token that ends at [offset] counts while it is not closed. */
    fun isInLiteralOrComment(text: CharSequence, offset: Int): Boolean {
        val lexer = RustLexer()
        lexer.start(text, 0, text.length, 0)
        while (true) {
            val type = lexer.tokenType ?: return false
            val start = lexer.tokenStart
            val end = lexer.tokenEnd
            if (start >= offset) return false
            if (type in T.STRINGS || type in T.COMMENTS) {
                if (offset < end || offset == end && !isClosed(text, start, end, type)) return true
            }
            lexer.advance()
        }
    }

    /** A literal with its closing quote, a block comment with its `*` + `/`; a line comment is open until the line ends. */
    private fun isClosed(text: CharSequence, start: Int, end: Int, type: IElementType): Boolean = when (type) {
        T.LINE_COMMENT -> false
        T.DOC_COMMENT -> text[start + 1] == '*' && end - start >= 4 && text[end - 1] == '/' && text[end - 2] == '*'
        T.BLOCK_COMMENT -> end - start >= 4 && text[end - 1] == '/' && text[end - 2] == '*'
        else -> {
            // a literal may have a suffix (`1u8`, `"x"suffix`): closed when a quote precedes the end, past the opening one
            val quote = text.subSequence(start, end).indexOfFirst { it == '"' || it == '\'' }
            val last = text.subSequence(start, end).indexOfLast { it == '"' || it == '\'' || it == '#' }
            quote >= 0 && last > quote
        }
    }
}
