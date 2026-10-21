package io.github.rustsupport

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** The icons of the plugin; the `_dark` variants next to them are picked by the IDE theme. */
object RustIcons {
    @JvmField val RUST: Icon = IconLoader.getIcon("/icons/rust.svg", RustIcons::class.java)
    @JvmField val CARGO: Icon = IconLoader.getIcon("/icons/cargo.svg", RustIcons::class.java)
    @JvmField val TOOL_WINDOW: Icon = IconLoader.getIcon("/icons/rustToolWindow.svg", RustIcons::class.java)
}
