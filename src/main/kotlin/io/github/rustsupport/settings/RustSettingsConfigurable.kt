package io.github.rustsupport.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import io.github.rustsupport.toolchain.RustToolchainCheck

/** Settings | Languages & Frameworks | Rust. Apply restarts rust-analyzer. */
class RustSettingsConfigurable : BoundConfigurable("Rust") {
    private val settings = RustSettings.getInstance()
    private val detected = JBLabel("Looking for the toolchain...")

    override fun createPanel(): DialogPanel = panel {
        group("Toolchain") {
            row("cargo:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle("cargo"))
                    .bindText(settings::cargoPath).align(AlignX.FILL)
                    .comment("Empty: found on PATH or in ~/.cargo/bin")
            }
            row { cell(detected).align(AlignX.FILL) }
            row {
                button("Check Again") { refreshDetected() }
                button("Install or Update Components") { RustComponents.installAll(null) }
                    .comment("rustup component add rust-analyzer rust-src rustfmt clippy")
            }
        }
        group("rust-analyzer") {
            row { checkBox("Enable rust-analyzer").bindSelected(settings::languageServerEnabled) }
            row("Path:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle("rust-analyzer"))
                    .bindText(settings::rustAnalyzerPath).align(AlignX.FILL)
                    .comment("Empty: the rustup component")
            }
            row("Check on save:") {
                comboBox(listOf("clippy", "check")).bindItem(settings::checkCommand.toNullableProperty())
            }
            row { checkBox("Expand procedural macros").bindSelected(settings::procMacros) }
            row { checkBox("Run build scripts (build.rs)").bindSelected(settings::buildScripts) }
            row { checkBox("Inlay hints: types, parameter names, chaining").bindSelected(settings::inlayHints) }
            row { checkBox("Code vision: usages and implementations above items").bindSelected(settings::codeLens) }
        }
        group("Memory") {
            row {
                checkBox("Index all dependencies at startup").bindSelected(settings::primeCaches)
                    .comment("Faster first completion in a large workspace, more memory for crates never opened")
            }
            row("Parse trees kept in memory:") {
                intTextField(16..1024).bindIntText(settings::lruCapacity)
                    .comment("rust-analyzer default: 128")
            }
        }
        group("Monitor") {
            row {
                checkBox("Open Rust Monitor when a Cargo run starts").bindSelected(settings::openMonitorOnRun)
                    .comment("CPU, memory and threads of the program cargo runs, in the Rust Monitor tool window")
            }
        }
    }.also { refreshDetected() }

    /** rust-analyzer restarts only when what it is started with or told has changed: not for the monitor switch. */
    override fun apply() {
        val before = settings.serverState()
        super.apply()
        if (settings.serverState() != before) RustSettings.fireChanged()
    }

    /** The toolchain check (the tools, where they come from, whether they run, rust-src, MSVC), run off the EDT. */
    private fun refreshDetected() {
        detected.text = "Checking the toolchain..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val html = RustToolchainCheck.html(RustToolchainCheck.run())
            ApplicationManager.getApplication().invokeLater({ detected.text = html }, ModalityState.any())
        }
    }
}
