package io.github.rustsupport.cargo

import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import com.intellij.util.messages.Topic
import io.github.rustsupport.toolchain.RustProjectDetector
import io.github.rustsupport.toolchain.RustTool
import io.github.rustsupport.toolchain.RustToolchain
import java.io.File
import java.nio.file.Path

/** The workspaces of a project as `cargo metadata` gave them; [message] says why something is missing (no deps offline, no cargo). */
data class CargoProjectsState(val workspaces: List<CargoProjectData>, val message: String?) {
    /** The package of a manifest in any of the workspaces. */
    fun packageOfManifest(manifestPath: String): Pair<CargoProjectData, CargoMetaPackage>? =
        workspaces.firstNotNullOfOrNull { ws -> ws.packageOfManifest(manifestPath)?.let { ws to it } }

    /** The workspace [manifestPath] belongs to: as a package, or as the root manifest of a virtual workspace. */
    fun workspaceOf(manifestPath: String): CargoProjectData? = packageOfManifest(manifestPath)?.first
        ?: workspaces.firstOrNull { CargoProjectData.samePath(File(it.root, "Cargo.toml").path, manifestPath) }
}

fun interface CargoProjectsListener {
    fun cargoProjectsChanged()
}

/**
 * The Cargo workspaces of the project: `cargo metadata` off the EDT, again when a Cargo.toml or Cargo.lock of the project changes.
 * Nothing runs until something asks ([ensureLoaded]): the tool window, or Cargo.toml support wanting the packages.
 */
@Service(Service.Level.PROJECT)
class CargoProjects(private val project: Project) : Disposable {
    @Volatile var state: CargoProjectsState? = null
        private set
    @Volatile var isLoading: Boolean = false
        private set
    @Volatile private var requested = false
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val lock = Any()

    init {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (requested && events.any { isManifestOfProject(it.path) }) refresh(DEBOUNCE_MS)
            }
        })
    }

    /** Loads the workspaces once, in the background; the [TOPIC] tells when they are there. */
    fun ensureLoaded() {
        if (!requested) refresh()
    }

    /** `cargo metadata` again after [delayMs]; a refresh asked in the meantime replaces this one. */
    fun refresh(delayMs: Int = 0) {
        requested = true
        if (project.isDisposed) return
        alarm.cancelAllRequests()
        alarm.addRequest({ load() }, delayMs)
    }

    private fun load() {
        synchronized(lock) {
            if (project.isDisposed) return
            isLoading = true
            publish()
            try {
                state = loadState(project.basePath)
            } catch (e: Exception) {
                LOG.warn("cargo metadata has failed", e)
                state = CargoProjectsState(emptyList(), "cargo metadata has failed: ${e.message}")
            } finally {
                isLoading = false
            }
            publish()
        }
    }

    private fun publish() {
        if (!project.isDisposed) project.messageBus.syncPublisher(TOPIC).cargoProjectsChanged()
    }

    private fun isManifestOfProject(path: String): Boolean {
        val name = path.substringAfterLast('/')
        if (name != "Cargo.toml" && name != "Cargo.lock") return false
        val base = project.basePath?.replace('\\', '/') ?: return false
        return path.replace('\\', '/').startsWith("$base/") && "/target/" !in path.removePrefix(base)
    }

    override fun dispose() {}

    companion object {
        private val LOG = logger<CargoProjects>()
        private const val DEBOUNCE_MS = 1500
        private const val MAX_WORKSPACES = 8

        @JvmField val TOPIC: Topic<CargoProjectsListener> = Topic.create("Cargo projects", CargoProjectsListener::class.java)

        fun getInstance(project: Project): CargoProjects = project.getService(CargoProjects::class.java)

        /** Every workspace under [basePath] (the first manifest of each, top down). Blocks: call off the EDT. */
        fun loadState(basePath: String?): CargoProjectsState {
            val root = basePath?.let { Path.of(it) } ?: return CargoProjectsState(emptyList(), null)
            if (RustTool.CARGO.find() == null) return CargoProjectsState(emptyList(), "cargo is not found: install Rust with rustup, or set its path in Settings | Languages & Frameworks | Rust")
            val directories = RustProjectDetector.targetDirectories(root).map { it.parent.toString() }.sortedBy { it.length }
            val workspaces = ArrayList<CargoProjectData>()
            val messages = LinkedHashSet<String>()
            for (directory in directories) {
                if (workspaces.size >= MAX_WORKSPACES) break
                val manifest = File(directory, "Cargo.toml").path
                if (workspaces.any { it.packageOfManifest(manifest) != null || CargoProjectData.samePath(it.root, directory) }) continue
                val (data, message) = CargoMetadataLoader.load(directory)
                data?.let { workspaces += it }
                message?.let { messages += it }
            }
            return CargoProjectsState(workspaces, messages.joinToString("\n").ifEmpty { null })
        }
    }
}

/**
 * `cargo metadata` of one directory, never reaching the network: `--offline` with the dependencies, then only those of the host
 * platform (cargo resolves every platform and fails offline on a crate of another one that was never downloaded), then `--no-deps`.
 */
object CargoMetadataLoader {
    private val LOG = logger<CargoMetadataLoader>()
    private const val TIMEOUT_MS = 120_000

    fun load(directory: String): Pair<CargoProjectData?, String?> {
        val cargo = RustTool.CARGO.find() ?: return null to "cargo is not found"
        val base = listOf("metadata", "--format-version", "1", "--offline")
        val first = run(cargo, directory, base)
        first.first?.let { return it to null }
        hostTriple()?.let { host -> run(cargo, directory, base + listOf("--filter-platform", host)).first?.let { return it to null } }
        val noDeps = run(cargo, directory, base + "--no-deps")
        val reason = first.second.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("error") }?.removePrefix("error:")?.trim()
        return if (noDeps.first != null) {
            noDeps.first to "Dependencies are not available offline${reason?.let { " ($it)" } ?: ""}: Fetch Dependencies (cargo fetch) downloads them through the network"
        } else {
            null to "cargo metadata in $directory has failed: ${noDeps.second.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "no output"}"
        }
    }

    private fun run(cargo: File, directory: String, args: List<String>): Pair<CargoProjectData?, String> = try {
        val output = ExecUtil.execAndGetOutput(RustToolchain.commandLine(cargo, directory, *args.toTypedArray()), TIMEOUT_MS)
        if (output.exitCode == 0) CargoProjectData.parse(output.stdout) to output.stderr
        else {
            LOG.info("cargo ${args.joinToString(" ")} in $directory: ${output.stderr.take(500)}")
            null to output.stderr
        }
    } catch (e: Exception) {
        LOG.info("cargo metadata in $directory has failed: $e")
        null to e.message.orEmpty()
    }

    @Volatile private var host: String? = null

    /** `host:` of `rustc -vV`: `x86_64-pc-windows-msvc`. */
    private fun hostTriple(): String? {
        host?.let { return it }
        val rustc = RustTool.RUSTC.find() ?: return null
        return runCatching {
            ExecUtil.execAndGetOutput(RustToolchain.commandLine(rustc, null, "-vV"), 10_000).stdout.lineSequence()
                .firstOrNull { it.startsWith("host:") }?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()?.also { host = it }
    }
}
