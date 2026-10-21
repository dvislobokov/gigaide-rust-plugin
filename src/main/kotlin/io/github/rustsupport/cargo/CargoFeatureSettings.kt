package io.github.rustsupport.cargo

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.annotations.XCollection

/** The features of the workspace were checked or unchecked: rust-analyzer restarts with them (the content module listens). */
fun interface CargoFeaturesListener {
    fun cargoFeaturesChanged()
}

/**
 * The features checked in the Cargo tool window, per project (the workspace file: they are a choice of this checkout). They go to
 * rust-analyzer as `cargo.features` and to new Cargo run configurations as `--features`.
 */
@Service(Service.Level.PROJECT)
@State(name = "CargoFeatures", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class CargoFeatureSettings(private val project: Project) : PersistentStateComponent<CargoFeatureSettings.FeaturesState>, Disposable {
    class FeaturesState {
        /** `package/feature`. */
        @XCollection(style = XCollection.Style.v2)
        var selected: MutableList<String> = ArrayList()
    }

    private var state = FeaturesState()
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    override fun getState(): FeaturesState = state
    override fun loadState(state: FeaturesState) {
        this.state = state
    }

    @Synchronized fun isEnabled(packageName: String, feature: String): Boolean = "$packageName/$feature" in state.selected

    @Synchronized fun featuresOf(packageName: String): List<String> = state.selected.filter { it.startsWith("$packageName/") }.map { it.substringAfter('/') }

    /** Every checked feature as `package/feature`, sorted. */
    @Synchronized fun all(): List<String> = state.selected.sorted()

    /** Checks or unchecks a feature; rust-analyzer is told once the clicks stop for a moment. */
    fun set(packageName: String, feature: String, enabled: Boolean) {
        val changed = synchronized(this) {
            val key = "$packageName/$feature"
            if (enabled) !state.selected.contains(key) && state.selected.add(key) else state.selected.remove(key)
        }
        if (changed && !project.isDisposed) {
            alarm.cancelAllRequests()
            alarm.addRequest({ if (!project.isDisposed) project.messageBus.syncPublisher(TOPIC).cargoFeaturesChanged() }, RESTART_DELAY_MS)
        }
    }

    /** `cargo.features` of rust-analyzer: cargo takes `package/feature` for the members of a workspace. */
    fun rustAnalyzerFeatures(): List<String> = all()

    /** `--features` of a new run configuration of [packageName]; of the whole workspace when it names no package. */
    fun runConfigurationFeatures(packageName: String?): String? =
        CargoFeatureDefaults.forRun(all(), packageName?.takeIf { it.isNotBlank() })

    override fun dispose() {}

    companion object {
        private const val RESTART_DELAY_MS = 1200

        @JvmField val TOPIC: Topic<CargoFeaturesListener> = Topic.create("Cargo features", CargoFeaturesListener::class.java)

        fun getInstance(project: Project): CargoFeatureSettings = project.getService(CargoFeatureSettings::class.java)
    }
}

/** Pure: what `--features` a run of a package gets from the checked `package/feature` entries. */
object CargoFeatureDefaults {
    fun forRun(selected: List<String>, packageName: String?): String? {
        // without --package: `member/feature`, which cargo takes for any member, in a virtual workspace as well
        val features = if (packageName != null) selected.filter { it.startsWith("$packageName/") }.map { it.substringAfter('/') } else selected
        return features.joinToString(",").ifEmpty { null }
    }
}
