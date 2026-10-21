package io.github.rustsupport.lang

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

/** No grammar: the file is its tokens, one leaf each. Errors, structure and resolve are rust-analyzer's. */
class RustParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = RustLexer()

    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val file = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        file.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType(): IFileElementType = RustTokenTypes.FILE
    override fun getWhitespaceTokens(): TokenSet = RustTokenTypes.WHITE_SPACES
    override fun getCommentTokens(): TokenSet = RustTokenTypes.COMMENTS
    override fun getStringLiteralElements(): TokenSet = RustTokenTypes.STRINGS
    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = RustFile(viewProvider)
}
