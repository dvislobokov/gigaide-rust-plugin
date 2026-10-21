package io.github.rustsupport.lang

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class RustTokenType(debugName: String) : IElementType(debugName, RustLanguage)

/** The tokens of [RustLexer]. There is no grammar: a Rust file is a flat list of them, the structure comes from rust-analyzer. */
object RustTokenTypes {
    @JvmField val FILE = IFileElementType(RustLanguage)

    @JvmField val WHITE_SPACE: IElementType = TokenType.WHITE_SPACE
    @JvmField val BAD_CHARACTER: IElementType = TokenType.BAD_CHARACTER

    @JvmField val IDENTIFIER = RustTokenType("IDENTIFIER")
    /** A name followed by `!`: `println!`, `vec!`, `macro_rules!`. */
    @JvmField val MACRO = RustTokenType("MACRO")
    @JvmField val KEYWORD = RustTokenType("KEYWORD")
    @JvmField val LIFETIME = RustTokenType("LIFETIME")

    @JvmField val NUMBER = RustTokenType("NUMBER")
    /** `"…"`, `b"…"`, `c"…"`. */
    @JvmField val STRING = RustTokenType("STRING")
    /** `r#"…"#`, `br"…"`, `cr"…"`. */
    @JvmField val RAW_STRING = RustTokenType("RAW_STRING")
    /** `'a'`, `b'a'`, `'\n'`. */
    @JvmField val CHAR = RustTokenType("CHAR")

    @JvmField val LINE_COMMENT = RustTokenType("LINE_COMMENT")
    @JvmField val BLOCK_COMMENT = RustTokenType("BLOCK_COMMENT")
    /** `///`, `//!`, `/** */`, `/*! */`. */
    @JvmField val DOC_COMMENT = RustTokenType("DOC_COMMENT")

    @JvmField val LPAREN = RustTokenType("(")
    @JvmField val RPAREN = RustTokenType(")")
    @JvmField val LBRACKET = RustTokenType("[")
    @JvmField val RBRACKET = RustTokenType("]")
    @JvmField val LBRACE = RustTokenType("{")
    @JvmField val RBRACE = RustTokenType("}")
    @JvmField val SEMICOLON = RustTokenType(";")
    @JvmField val COMMA = RustTokenType(",")
    @JvmField val DOT = RustTokenType(".")
    /** `#` of an attribute. */
    @JvmField val HASH = RustTokenType("#")
    @JvmField val OPERATOR = RustTokenType("OPERATOR")

    @JvmField val COMMENTS = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT, DOC_COMMENT)
    @JvmField val STRINGS = TokenSet.create(STRING, RAW_STRING, CHAR)
    @JvmField val WHITE_SPACES = TokenSet.create(WHITE_SPACE)

    /** What a Ctrl + mouse link and Go to Declaration start from. */
    @JvmField val NAVIGABLE = TokenSet.create(IDENTIFIER, MACRO, KEYWORD, LIFETIME)

    /** Strict and reserved keywords of the 2024 edition; the weak ones (`union`, `macro_rules`, `raw`, `safe`) are names to the lexer. */
    val KEYWORDS: Set<String> = setOf(
        "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum", "extern", "false", "fn", "for", "if", "impl",
        "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return", "self", "Self", "static", "struct", "super", "trait",
        "true", "type", "unsafe", "use", "where", "while",
        "abstract", "become", "box", "do", "final", "gen", "macro", "override", "priv", "try", "typeof", "unsized", "virtual", "yield",
    )

    /** Keywords that name something rust-analyzer can go to: the current type, module and crate. */
    val NAVIGABLE_KEYWORDS: Set<String> = setOf("self", "Self", "super", "crate")
}
