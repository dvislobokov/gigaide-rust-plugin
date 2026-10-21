package io.github.rustsupport

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/** `.rs` files are TextMate files of the IDE (its bundled Rust grammar): the icon is the plugin's, Cargo manifests get a crate. */
class RustFileIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? = iconOf(file.name, file.isDirectory)

    companion object {
        fun iconOf(name: String, isDirectory: Boolean): Icon? = when {
            isDirectory -> null
            name.endsWith(".rs") -> RustIcons.RUST
            name == "Cargo.toml" || name == "Cargo.lock" -> RustIcons.CARGO
            else -> null
        }
    }
}
