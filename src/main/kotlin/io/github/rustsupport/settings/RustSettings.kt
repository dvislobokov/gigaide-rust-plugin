package io.github.rustsupport.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil

/** Settings | Languages & Frameworks | Rust. Empty paths mean "find it": settings, then PATH, then ~/.cargo/bin. */
@Service(Service.Level.APP)
@State(name = "RustSettings", storages = [Storage("rust-support.xml")])
class RustSettings : PersistentStateComponent<RustSettings> {
    var languageServerEnabled: Boolean = true
    var rustAnalyzerPath: String = ""
    var cargoPath: String = ""
    /** What rust-analyzer runs on save: `clippy` or `check`. */
    var checkCommand: String = "clippy"
    var procMacros: Boolean = true
    var buildScripts: Boolean = true
    var inlayHints: Boolean = true
    /** rust-analyzer analyses every crate of the graph at start (`cachePriming.enable`); off, it does so on demand and keeps less in memory. */
    var primeCaches: Boolean = false
    /** Parse trees kept in memory (`lru.capacity`); rust-analyzer's default is 128. */
    var lruCapacity: Int = 64
    /** The Rust Monitor tool window shows itself when a Cargo run starts. */
    var openMonitorOnRun: Boolean = false

    override fun getState(): RustSettings = this
    override fun loadState(state: RustSettings) = XmlSerializerUtil.copyBean(state, this)

    companion object {
        fun getInstance(): RustSettings = ApplicationManager.getApplication().getService(RustSettings::class.java)

        /** Settings were applied or the toolchain changed (a component was installed): the language server restarts. */
        @JvmField val CHANGED: Topic<RustSettingsListener> = Topic.create("Rust settings", RustSettingsListener::class.java)

        fun fireChanged() = ApplicationManager.getApplication().messageBus.syncPublisher(CHANGED).settingsChanged()
    }
}

fun interface RustSettingsListener {
    fun settingsChanged()
}
