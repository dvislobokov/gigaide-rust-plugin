package io.github.rustsupport.lang

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import com.intellij.lang.BracePair
import com.intellij.lang.CodeDocumentationAwareCommenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as D
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import io.github.rustsupport.RustIcons
import javax.swing.Icon
import io.github.rustsupport.lang.RustTokenTypes as T

/**
 * The colours of Rust code. The lexer gives the first ones at once; the semantic tokens of rust-analyzer come on top of them a moment
 * later ([semantic]), with what only resolve can tell: a type from a function, a mutable binding, a trait.
 */
object RustColors {
    val KEYWORD = createTextAttributesKey("RUST_KEYWORD", D.KEYWORD)
    val SELF = createTextAttributesKey("RUST_SELF", D.KEYWORD)
    val IDENTIFIER = createTextAttributesKey("RUST_IDENTIFIER", D.IDENTIFIER)
    val NUMBER = createTextAttributesKey("RUST_NUMBER", D.NUMBER)
    val STRING = createTextAttributesKey("RUST_STRING", D.STRING)
    val CHAR = createTextAttributesKey("RUST_CHAR", D.STRING)
    val ESCAPE = createTextAttributesKey("RUST_ESCAPE", D.VALID_STRING_ESCAPE)
    val FORMAT_SPECIFIER = createTextAttributesKey("RUST_FORMAT_SPECIFIER", D.VALID_STRING_ESCAPE)
    val LIFETIME = createTextAttributesKey("RUST_LIFETIME", D.METADATA)
    val LINE_COMMENT = createTextAttributesKey("RUST_LINE_COMMENT", D.LINE_COMMENT)
    val BLOCK_COMMENT = createTextAttributesKey("RUST_BLOCK_COMMENT", D.BLOCK_COMMENT)
    val DOC_COMMENT = createTextAttributesKey("RUST_DOC_COMMENT", D.DOC_COMMENT)
    val PARENTHESES = createTextAttributesKey("RUST_PARENTHESES", D.PARENTHESES)
    val BRACKETS = createTextAttributesKey("RUST_BRACKETS", D.BRACKETS)
    val BRACES = createTextAttributesKey("RUST_BRACES", D.BRACES)
    val SEMICOLON = createTextAttributesKey("RUST_SEMICOLON", D.SEMICOLON)
    val COMMA = createTextAttributesKey("RUST_COMMA", D.COMMA)
    val DOT = createTextAttributesKey("RUST_DOT", D.DOT)
    val OPERATOR = createTextAttributesKey("RUST_OPERATOR", D.OPERATION_SIGN)
    val ATTRIBUTE = createTextAttributesKey("RUST_ATTRIBUTE", D.METADATA)
    val MACRO = createTextAttributesKey("RUST_MACRO", D.STATIC_METHOD)
    val BAD_CHARACTER = createTextAttributesKey("RUST_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)

    // from rust-analyzer only
    val MODULE = createTextAttributesKey("RUST_MODULE", D.IDENTIFIER)
    val CRATE = createTextAttributesKey("RUST_CRATE", D.IDENTIFIER)
    val STRUCT = createTextAttributesKey("RUST_STRUCT", D.CLASS_NAME)
    val ENUM = createTextAttributesKey("RUST_ENUM", D.CLASS_NAME)
    val UNION = createTextAttributesKey("RUST_UNION", D.CLASS_NAME)
    val TRAIT = createTextAttributesKey("RUST_TRAIT", D.INTERFACE_NAME)
    val TYPE_ALIAS = createTextAttributesKey("RUST_TYPE_ALIAS", D.CLASS_REFERENCE)
    val BUILTIN_TYPE = createTextAttributesKey("RUST_BUILTIN_TYPE", D.KEYWORD)
    val TYPE_PARAMETER = createTextAttributesKey("RUST_TYPE_PARAMETER", D.CLASS_REFERENCE)
    val ENUM_VARIANT = createTextAttributesKey("RUST_ENUM_VARIANT", D.STATIC_FIELD)
    val FIELD = createTextAttributesKey("RUST_FIELD", D.INSTANCE_FIELD)
    val FUNCTION = createTextAttributesKey("RUST_FUNCTION", D.FUNCTION_DECLARATION)
    val FUNCTION_CALL = createTextAttributesKey("RUST_FUNCTION_CALL", D.FUNCTION_CALL)
    val METHOD = createTextAttributesKey("RUST_METHOD", D.INSTANCE_METHOD)
    val ASSOCIATED_FUNCTION = createTextAttributesKey("RUST_ASSOCIATED_FUNCTION", D.STATIC_METHOD)
    val CONSTANT = createTextAttributesKey("RUST_CONSTANT", D.CONSTANT)
    val STATIC = createTextAttributesKey("RUST_STATIC", D.STATIC_FIELD)
    val PARAMETER = createTextAttributesKey("RUST_PARAMETER", D.PARAMETER)
    val LOCAL = createTextAttributesKey("RUST_LOCAL", D.LOCAL_VARIABLE)
    val MUTABLE = createTextAttributesKey("RUST_MUTABLE", D.REASSIGNED_LOCAL_VARIABLE)
    val LABEL = createTextAttributesKey("RUST_LABEL", D.LABEL)
    val UNSAFE = createTextAttributesKey("RUST_UNSAFE", D.KEYWORD)
    val UNRESOLVED = createTextAttributesKey("RUST_UNRESOLVED", HighlighterColors.TEXT)

    /**
     * The colour of a semantic token of rust-analyzer (https://rust-analyzer.github.io/book/features.html#semantic-syntax-highlighting):
     * its standard types and its own, refined by modifiers. Null leaves the lexer colour.
     */
    fun semantic(type: String, modifiers: List<String>): TextAttributesKey? = when (type) {
        "namespace" -> if ("crateRoot" in modifiers) CRATE else MODULE
        "struct", "class" -> STRUCT
        "enum" -> ENUM
        "union" -> UNION
        "interface" -> TRAIT
        "typeAlias", "type" -> TYPE_ALIAS
        "builtinType" -> BUILTIN_TYPE
        "typeParameter", "constParameter" -> TYPE_PARAMETER
        "enumMember" -> ENUM_VARIANT
        "property" -> FIELD
        "function" -> when {
            "associated" in modifiers || "static" in modifiers -> ASSOCIATED_FUNCTION
            "declaration" in modifiers -> FUNCTION
            else -> FUNCTION_CALL
        }
        "method" -> if ("static" in modifiers) ASSOCIATED_FUNCTION else METHOD
        "macro", "macroBang" -> MACRO
        "attribute", "builtinAttribute", "derive", "deriveHelper", "decorator", "attributeBracket", "toolModule" -> ATTRIBUTE
        "const" -> CONSTANT
        "static" -> STATIC
        "parameter" -> if ("mutable" in modifiers) MUTABLE else PARAMETER
        "variable" -> if ("mutable" in modifiers) MUTABLE else LOCAL
        "selfKeyword", "selfTypeKeyword" -> SELF
        "lifetime" -> LIFETIME
        "label" -> LABEL
        "escapeSequence" -> ESCAPE
        "formatSpecifier" -> FORMAT_SPECIFIER
        "keyword" -> if ("unsafe" in modifiers) UNSAFE else KEYWORD
        "unresolvedReference" -> UNRESOLVED
        else -> null
    }
}

class RustSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = RustLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = pack(
        when (tokenType) {
            T.KEYWORD -> RustColors.KEYWORD
            T.IDENTIFIER -> RustColors.IDENTIFIER
            T.MACRO -> RustColors.MACRO
            T.LIFETIME -> RustColors.LIFETIME
            T.NUMBER -> RustColors.NUMBER
            T.STRING, T.RAW_STRING -> RustColors.STRING
            T.CHAR -> RustColors.CHAR
            T.LINE_COMMENT -> RustColors.LINE_COMMENT
            T.BLOCK_COMMENT -> RustColors.BLOCK_COMMENT
            T.DOC_COMMENT -> RustColors.DOC_COMMENT
            T.LPAREN, T.RPAREN -> RustColors.PARENTHESES
            T.LBRACKET, T.RBRACKET -> RustColors.BRACKETS
            T.LBRACE, T.RBRACE -> RustColors.BRACES
            T.SEMICOLON -> RustColors.SEMICOLON
            T.COMMA -> RustColors.COMMA
            T.DOT -> RustColors.DOT
            T.HASH -> RustColors.ATTRIBUTE
            T.OPERATOR -> RustColors.OPERATOR
            T.BAD_CHARACTER -> RustColors.BAD_CHARACTER
            else -> null
        },
    )
}

class RustSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = RustSyntaxHighlighter()
}

class RustBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> = PAIRS
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true
    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset

    private companion object {
        val PAIRS = arrayOf(BracePair(T.LBRACE, T.RBRACE, true), BracePair(T.LPAREN, T.RPAREN, false), BracePair(T.LBRACKET, T.RBRACKET, false))
    }
}

class RustCommenter : CodeDocumentationAwareCommenter {
    override fun getLineCommentPrefix(): String = "//"
    override fun getBlockCommentPrefix(): String = "/*"
    override fun getBlockCommentSuffix(): String = "*/"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
    override fun getLineCommentTokenType(): IElementType = T.LINE_COMMENT
    override fun getBlockCommentTokenType(): IElementType = T.BLOCK_COMMENT
    override fun getDocumentationCommentTokenType(): IElementType = T.DOC_COMMENT
    override fun getDocumentationCommentPrefix(): String = "///"
    override fun getDocumentationCommentLinePrefix(): String = "///"
    override fun getDocumentationCommentSuffix(): String? = null
    override fun isDocumentationComment(element: PsiComment?): Boolean = element?.tokenType == T.DOC_COMMENT
}

class RustQuoteHandler : SimpleTokenSetQuoteHandler(T.STRING)

/** Settings | Editor | Color Scheme | Rust. */
class RustColorSettingsPage : ColorSettingsPage {
    override fun getIcon(): Icon = RustIcons.RUST
    override fun getHighlighter(): SyntaxHighlighter = RustSyntaxHighlighter()
    override fun getDisplayName(): String = "Rust"
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = TAGS

    override fun getDemoText(): String = """
        //! Crate documentation
        use std::collections::<struct>HashMap</struct>;

        /// A server of the fleet.
        <attr>#[derive(Debug, Clone)]</attr>
        pub struct <struct>Server</struct><'a, <tp>T</tp>: <trait>Display</trait>> {
            <field>name</field>: &'a <builtin>str</builtin>,
            <field>state</field>: <enum>State</enum>,
            <field>payload</field>: <tp>T</tp>,
        }

        pub enum <enum>State</enum> { <variant>Up</variant>, <variant>Down</variant>(<builtin>u16</builtin>) }

        const <const>LIMIT</const>: <builtin>usize</builtin> = 0x_FF;
        static <static>NAME</static>: &<builtin>str</builtin> = "komodo\n";

        impl<'a, <tp>T</tp>: <trait>Display</trait>> <struct>Server</struct><'a, <tp>T</tp>> {
            pub fn <fn>new</fn>(<param>name</param>: &'a <builtin>str</builtin>, <param>payload</param>: <tp>T</tp>) -> <self>Self</self> {
                <self>Self</self> { <field>name</field>, <field>state</field>: <enum>State</enum>::<variant>Up</variant>, <field>payload</field> }
            }

            fn <fn>report</fn>(&self) -> <builtin>bool</builtin> {
                let mut <mut>count</mut> = 1.5e3 as <builtin>usize</builtin>;
                let <local>tags</local> = <macro>vec!</macro>[r#"raw "text""#, 'x'.<method>to_string</method>()];
                /* a /* nested */ comment */
                <label>'outer</label>: for <local>tag</local> in &<local>tags</local> {
                    <mut>count</mut> += <local>tag</local>.<method>len</method>();
                    if <mut>count</mut> > <const>LIMIT</const> { break <label>'outer</label>; }
                }
                <macro>println!</macro>("{} has {<fmt>:>8</fmt>} tags", self.<field>name</field>, <mut>count</mut>);
                <fncall>matches!</fncall>(self.<field>state</field>, <enum>State</enum>::<variant>Up</variant>) && <unresolved>missing</unresolved>
            }
        }
    """.trimIndent()

    private companion object {
        val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Keywords//Keyword", RustColors.KEYWORD),
            AttributesDescriptor("Keywords//self and Self", RustColors.SELF),
            AttributesDescriptor("Keywords//unsafe", RustColors.UNSAFE),
            AttributesDescriptor("Identifiers//Default", RustColors.IDENTIFIER),
            AttributesDescriptor("Identifiers//Local variable", RustColors.LOCAL),
            AttributesDescriptor("Identifiers//Mutable binding", RustColors.MUTABLE),
            AttributesDescriptor("Identifiers//Parameter", RustColors.PARAMETER),
            AttributesDescriptor("Identifiers//Field", RustColors.FIELD),
            AttributesDescriptor("Identifiers//Constant", RustColors.CONSTANT),
            AttributesDescriptor("Identifiers//Static", RustColors.STATIC),
            AttributesDescriptor("Identifiers//Lifetime", RustColors.LIFETIME),
            AttributesDescriptor("Identifiers//Label", RustColors.LABEL),
            AttributesDescriptor("Identifiers//Unresolved reference", RustColors.UNRESOLVED),
            AttributesDescriptor("Functions//Function declaration", RustColors.FUNCTION),
            AttributesDescriptor("Functions//Function call", RustColors.FUNCTION_CALL),
            AttributesDescriptor("Functions//Method", RustColors.METHOD),
            AttributesDescriptor("Functions//Associated function", RustColors.ASSOCIATED_FUNCTION),
            AttributesDescriptor("Functions//Macro", RustColors.MACRO),
            AttributesDescriptor("Types//Struct", RustColors.STRUCT),
            AttributesDescriptor("Types//Enum", RustColors.ENUM),
            AttributesDescriptor("Types//Enum variant", RustColors.ENUM_VARIANT),
            AttributesDescriptor("Types//Union", RustColors.UNION),
            AttributesDescriptor("Types//Trait", RustColors.TRAIT),
            AttributesDescriptor("Types//Type alias", RustColors.TYPE_ALIAS),
            AttributesDescriptor("Types//Primitive type", RustColors.BUILTIN_TYPE),
            AttributesDescriptor("Types//Type parameter", RustColors.TYPE_PARAMETER),
            AttributesDescriptor("Modules//Module", RustColors.MODULE),
            AttributesDescriptor("Modules//Crate", RustColors.CRATE),
            AttributesDescriptor("Literals//Number", RustColors.NUMBER),
            AttributesDescriptor("Literals//String", RustColors.STRING),
            AttributesDescriptor("Literals//Character", RustColors.CHAR),
            AttributesDescriptor("Literals//Escape sequence", RustColors.ESCAPE),
            AttributesDescriptor("Literals//Format specifier", RustColors.FORMAT_SPECIFIER),
            AttributesDescriptor("Comments//Line comment", RustColors.LINE_COMMENT),
            AttributesDescriptor("Comments//Block comment", RustColors.BLOCK_COMMENT),
            AttributesDescriptor("Comments//Documentation", RustColors.DOC_COMMENT),
            AttributesDescriptor("Attribute", RustColors.ATTRIBUTE),
            AttributesDescriptor("Braces and Operators//Parentheses", RustColors.PARENTHESES),
            AttributesDescriptor("Braces and Operators//Brackets", RustColors.BRACKETS),
            AttributesDescriptor("Braces and Operators//Braces", RustColors.BRACES),
            AttributesDescriptor("Braces and Operators//Operator", RustColors.OPERATOR),
            AttributesDescriptor("Braces and Operators//Semicolon", RustColors.SEMICOLON),
            AttributesDescriptor("Braces and Operators//Comma", RustColors.COMMA),
            AttributesDescriptor("Braces and Operators//Dot", RustColors.DOT),
            AttributesDescriptor("Bad character", RustColors.BAD_CHARACTER),
        )

        val TAGS = mapOf(
            "struct" to RustColors.STRUCT, "enum" to RustColors.ENUM, "variant" to RustColors.ENUM_VARIANT, "trait" to RustColors.TRAIT,
            "tp" to RustColors.TYPE_PARAMETER, "builtin" to RustColors.BUILTIN_TYPE, "field" to RustColors.FIELD, "const" to RustColors.CONSTANT,
            "static" to RustColors.STATIC, "fn" to RustColors.FUNCTION, "fncall" to RustColors.MACRO, "method" to RustColors.METHOD,
            "param" to RustColors.PARAMETER, "local" to RustColors.LOCAL, "mut" to RustColors.MUTABLE, "self" to RustColors.SELF,
            "macro" to RustColors.MACRO, "attr" to RustColors.ATTRIBUTE, "label" to RustColors.LABEL, "fmt" to RustColors.FORMAT_SPECIFIER,
            "unresolved" to RustColors.UNRESOLVED,
        )
    }
}
