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
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.xmlb.XmlSerializerUtil

/** Settings | Languages & Frameworks | Rust | Debugger. A storage of its own: the page of the toolchain is edited elsewhere. */
@Service(Service.Level.APP)
@State(name = "RustDebuggerSettings", storages = [Storage("rust-support-debugger.xml")])
class RustDebuggerSettings : PersistentStateComponent<RustDebuggerSettings> {
    /** An existing codelldb (the executable or the folder of the extension); empty: VS Code-like editors, then the downloaded one. */
    var codelldbPath: String = ""

    override fun getState(): RustDebuggerSettings = this
    override fun loadState(state: RustDebuggerSettings) = XmlSerializerUtil.copyBean(state, this)

    companion object {
        fun getInstance(): RustDebuggerSettings = ApplicationManager.getApplication().getService(RustDebuggerSettings::class.java)
    }
}

class RustDebuggerConfigurable : BoundConfigurable("Debugger") {
    private val settings = RustDebuggerSettings.getInstance()
    private val found = JBLabel("Looking for CodeLLDB...")

    override fun createPanel(): DialogPanel = panel {
        group("CodeLLDB") {
            row("codelldb:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFileOrDir().withTitle("codelldb"))
                    .bindText(settings::codelldbPath).align(AlignX.FILL)
                    .comment("Empty: the CodeLLDB extension of VS Code, Cursor or VSCodium, else the one downloaded here")
            }
            row { cell(found) }
            row {
                button("Download CodeLLDB ${CodeLldbRelease.VERSION}...") { CodeLldbInstaller.offerDownload(null) { refresh() } }
                    .comment("From <a href=\"${CodeLldbRelease.REPOSITORY_URL}\">GitHub releases of vadimcn/codelldb</a>, after a confirmation; only when you press it")
            }
        }
    }.also { refresh() }

    override fun apply() {
        super.apply()
        refresh()
    }

    private fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val installation = runCatching { CodeLldbLocator.current() }.getOrNull()
            val text = installation?.let { "<html>Using ${it.describe()}:<br>${it.adapter}</html>" }
                ?: "Not found: Debug offers to download it or to choose an existing one"
            ApplicationManager.getApplication().invokeLater({ found.text = text }, ModalityState.any())
        }
    }
}
