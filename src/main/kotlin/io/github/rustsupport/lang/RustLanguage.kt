package io.github.rustsupport.lang

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.psi.FileViewProvider
import io.github.rustsupport.RustIcons
import javax.swing.Icon

object RustLanguage : Language("Rust") {
    private fun readResolve(): Any = RustLanguage
    override fun getDisplayName(): String = "Rust"
}

/** `.rs`: a file type of the plugin's own (a TextMate file is one PSI leaf, which no Ctrl + mouse link or brace matcher can work with). */
object RustFileType : LanguageFileType(RustLanguage) {
    override fun getName(): String = "Rust"
    override fun getDescription(): String = "Rust"
    override fun getDefaultExtension(): String = "rs"
    override fun getIcon(): Icon = RustIcons.RUST
}

class RustFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, RustLanguage) {
    override fun getFileType(): FileType = RustFileType
    override fun toString(): String = "Rust file"
}
