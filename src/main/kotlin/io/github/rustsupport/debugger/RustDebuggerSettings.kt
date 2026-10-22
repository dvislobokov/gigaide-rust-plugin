package io.github.rustsupport.debugger

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.ui.layout.ValidationInfoBuilder
import com.intellij.util.xmlb.XmlSerializerUtil

/** Settings | Languages & Frameworks | Rust | Debugger. A storage of its own: the page of the toolchain is edited elsewhere. */
@Service(Service.Level.APP)
@State(name = "RustDebuggerSettings", storages = [Storage("rust-support-debugger.xml")])
class RustDebuggerSettings : PersistentStateComponent<RustDebuggerSettings> {
    var adapter: RustDebugAdapterChoice = RustDebugAdapterChoice.AUTO
    /** An existing codelldb (the executable or the folder of the extension); empty: VS Code-like editors, then the downloaded one. */
    var codelldbPath: String = ""
    /** An existing lldb-dap (the executable or its folder); empty: PATH, then Xcode and the installs of LLVM. */
    var lldbDapPath: String = ""

    override fun getState(): RustDebuggerSettings = this
    override fun loadState(state: RustDebuggerSettings) = XmlSerializerUtil.copyBean(state, this)

    companion object {
        fun getInstance(): RustDebuggerSettings = ApplicationManager.getApplication().getService(RustDebuggerSettings::class.java)
    }
}

class RustDebuggerConfigurable : BoundConfigurable("Debugger") {
    private val settings = RustDebuggerSettings.getInstance()
    private val found = JBLabel("Looking for a debug adapter...")

    override fun createPanel(): DialogPanel = panel {
        group("Debug Adapter") {
            row("Adapter:") {
                comboBox(RustDebugAdapterChoice.entries).bindItem(settings::adapter.toNullableProperty())
            }
            row { cell(found) }
        }
        group("CodeLLDB") {
            row("codelldb:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFileOrDir().withTitle("codelldb"))
                    .bindText(settings::codelldbPath).align(AlignX.FILL)
                    .comment("Empty: the CodeLLDB extension of VS Code, Cursor or VSCodium, else the one downloaded or installed here")
                    .validationOnInput { missing(it, "codelldb") { path -> CodeLldbLocator.default().fromSettings(path) != null } }
            }
            row {
                button("Download CodeLLDB ${CodeLldbRelease.VERSION}...") { CodeLldbInstaller.offerDownload(null) { refresh() } }
                    .comment("From <a href=\"${CodeLldbRelease.REPOSITORY_URL}\">GitHub releases of vadimcn/codelldb</a>, after a confirmation; only when you press it")
            }
            row {
                button("Install from VSIX...") { CodeLldbInstaller.installFromVsix(null) { reset(); refresh() } }
                    .comment("No network: a codelldb-&lt;platform&gt;.vsix from the Marketplace, Open VSX or a mirror of the releases")
            }
        }
        group("lldb-dap") {
            row("lldb-dap:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFileOrDir().withTitle("lldb-dap"))
                    .bindText(settings::lldbDapPath).align(AlignX.FILL)
                    .comment("The DAP adapter of LLVM. Empty: PATH, then Xcode and its Command Line Tools, Homebrew's llvm, /usr/lib/llvm-*, C:\\Program Files\\LLVM")
                    .validationOnInput { missing(it, "lldb-dap") { path -> LldbDapLocator.default().fromSettings(path) != null } }
            }
        }
    }.also { refresh() }

    /** A path that is not there is passed over in silence by the lookup, and another adapter would be used: told at once. */
    private fun ValidationInfoBuilder.missing(field: TextFieldWithBrowseButton, name: String, exists: (String) -> Boolean): ValidationInfo? {
        val path = field.text.trim()
        return if (path.isNotEmpty() && !exists(path)) warning("No $name here: it is looked for elsewhere") else null
    }

    override fun apply() {
        super.apply()
        refresh()
    }

    private fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val adapter = runCatching { RustDebugAdapters.current() }.getOrNull()
            val text = adapter?.let { "<html>Using ${it.description}:<br>${it.path}</html>" }
                ?: "Not found: Debug offers to download CodeLLDB or to choose an existing adapter"
            ApplicationManager.getApplication().invokeLater({ found.text = text }, ModalityState.any())
        }
    }
}
