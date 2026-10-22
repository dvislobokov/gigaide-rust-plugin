package io.github.rustsupport.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import io.github.rustsupport.toolchain.RustSources
import io.github.rustsupport.toolchain.RustTool
import io.github.rustsupport.toolchain.RustToolchainCheck
import java.io.File
import java.nio.file.Path
import javax.swing.JCheckBox
import kotlin.reflect.KMutableProperty0

/** Settings | Languages & Frameworks | Rust. Apply restarts rust-analyzer. */
class RustSettingsConfigurable : BoundConfigurable("Rust") {
    private val settings = RustSettings.getInstance()
    private val detected = JBLabel("Looking for the toolchain...")
    /** Read and set by the row of Settings | Tools | Actions on Save ([io.github.rustsupport.toolchain.RustfmtOnSaveInfoProvider]). */
    lateinit var rustfmtOnSave: JCheckBox

    override fun createPanel(): DialogPanel = panel {
        group("Toolchain") {
            toolRow(RustTool.CARGO, settings::cargoPath, "Empty: found on PATH or in ~/.cargo/bin")
            toolRow(RustTool.RUSTC, settings::rustcPath, "Set: cargo and rust-analyzer build with it (RUSTC), over rust-toolchain.toml")
            toolRow(RustTool.RUSTFMT, settings::rustfmtPath, "Set: formatting, cargo fmt and rust-analyzer use it (RUSTFMT)")
            toolRow(RustTool.CLIPPY, settings::clippyPath, "cargo-clippy, with clippy-driver next to it; its directory goes first on PATH of cargo")
            toolRow(RustTool.RUSTUP, settings::rustupPath, "Installs the missing components")
            row("rust-src:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("rust-src"))
                    .bindText(settings::rustSrcPath).align(AlignX.FILL)
                    .comment("Sources of std: a sysroot, its lib/rustlib/src/rust or library. Empty: the sysroot of rustc (RUST_SRC_PATH)")
                    .validationOnInput { field ->
                        val path = field.text.trim()
                        if (path.isNotEmpty() && runCatching { RustSources.library(Path.of(path)) }.getOrNull() == null) {
                            warning("No library/std/src/lib.rs there: the sources of the sysroot of rustc are used")
                        } else null
                    }
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
            toolRow(RustTool.RUST_ANALYZER, settings::rustAnalyzerPath, "Empty: the rustup component", "Path:")
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
        group("Formatting") {
            row {
                rustfmtOnSave = checkBox("Reformat code with rustfmt on save").bindSelected(settings::rustfmtOnSave)
                    .comment("Rust files of the project; the edition from Cargo.toml, rustfmt.toml from the directory of the file up. Also in Settings | Tools | Actions on Save")
                    .component
            }
        }
        group("Monitor") {
            row {
                checkBox("Open Rust Monitor when a Cargo run starts").bindSelected(settings::openMonitorOnRun)
                    .comment("CPU, memory and threads of the program cargo runs, in the Rust Monitor tool window")
            }
        }
    }.also { refreshDetected() }

    /** A path to [tool]: empty means found on PATH; a file that does not run is told at once, as the tool would be looked for elsewhere. */
    private fun Panel.toolRow(tool: RustTool, property: KMutableProperty0<String>, comment: String, label: String = "${tool.executable}:") {
        row(label) {
            textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle(tool.executable))
                .bindText(property).align(AlignX.FILL)
                .comment(comment)
                .validationOnInput { field ->
                    val path = field.text.trim()
                    if (path.isNotEmpty() && !File(path).canExecute()) warning("Not an executable file: ${tool.executable} is looked for on PATH instead") else null
                }
        }
    }

    /** rust-analyzer restarts only when what it is started with or told has changed: not for the monitor switch. */
    override fun apply() {
        val before = settings.serverState()
        super.apply()
        if (settings.serverState() != before) RustSettings.fireChanged()
    }

    companion object {
        /** The id of the page in plugin.xml. */
        const val ID = "io.github.rustsupport.settings"
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
