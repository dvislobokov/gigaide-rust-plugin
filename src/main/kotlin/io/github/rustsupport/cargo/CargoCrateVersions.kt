package io.github.rustsupport.cargo

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.execution.util.ExecUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import io.github.rustsupport.toolchain.RustProjectDetector
import io.github.rustsupport.toolchain.RustTool
import io.github.rustsupport.toolchain.RustToolchain
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The newest versions of crates learned in this session, only ever by an action of the user: `cargo info <crate>` or
 * `cargo update --dry-run --verbose`. cargo goes to the network itself, through its own proxy and registry-mirror configuration
 * (`[http] proxy`, `[source.crates-io] replace-with`, `HTTPS_PROXY`/`CARGO_HTTP_PROXY`); the plugin never talks to a registry.
 */
@Service(Service.Level.APP)
class CargoCrateVersions {
    private val latest = ConcurrentHashMap<String, String>()
    private val infos = ConcurrentHashMap<String, CargoInfo>()

    fun latest(crate: String): String? = latest[crate.lowercase()]

    fun info(crate: String): CargoInfo? = infos[crate.lowercase()]

    fun remember(crate: String, version: String) {
        val key = crate.lowercase()
        latest.merge(key, version) { old, new -> if ((SemVersion.parse(new) ?: return@merge old) > (SemVersion.parse(old) ?: return@merge new)) new else old }
    }

    fun remember(info: CargoInfo) {
        infos[info.name.lowercase()] = info
        remember(info.name, info.latest)
    }

    companion object {
        fun getInstance(): CargoCrateVersions = ApplicationManager.getApplication().getService(CargoCrateVersions::class.java)
        private const val TIMEOUT_MS = 180_000

        /** `cargo info <crate>` in [directory] (its registry configuration applies), in a cancellable background task. */
        fun checkCrate(project: Project, crate: String, directory: String?, onDone: (CargoInfo?) -> Unit = {}) {
            object : Task.Backgroundable(project, "cargo info $crate", true) {
                private var info: CargoInfo? = null
                private var error: String? = null

                override fun run(indicator: ProgressIndicator) {
                    val cargo = RustTool.CARGO.find() ?: run { error = "cargo is not found"; return }
                    val output = ExecUtil.execAndGetOutput(RustToolchain.commandLine(cargo, directory, "info", crate), TIMEOUT_MS)
                    info = CargoInfoParser.parse(output.stdout).takeIf { output.exitCode == 0 }
                    if (info == null) error = output.stderr.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("error") } ?: "cargo info $crate has failed (exit code ${output.exitCode})"
                }

                override fun onFinished() {
                    val found = info
                    if (found != null) {
                        getInstance().remember(found)
                        val text = if (found.latest != found.version) "${found.name}: ${found.latest} is the newest (the workspace has ${found.version})" else "${found.name}: ${found.latest} is the newest"
                        notify(project, text, NotificationType.INFORMATION)
                    } else notify(project, error ?: "cargo info $crate has failed", NotificationType.WARNING)
                    DaemonCodeAnalyzer.getInstance(project).restart("Cargo crate versions updated")
                    onDone(found)
                }
            }.queue()
        }

        /** `cargo update --dry-run --verbose` of the workspace in [directory]: the newest version of every crate of the lock file. */
        fun checkWorkspace(project: Project, directory: String) {
            object : Task.Backgroundable(project, "Checking dependency updates (cargo update --dry-run)", true) {
                private var entries: List<CargoUpdateEntry>? = null
                private var error: String? = null

                override fun run(indicator: ProgressIndicator) {
                    val cargo = RustTool.CARGO.find() ?: run { error = "cargo is not found"; return }
                    val output = ExecUtil.execAndGetOutput(RustToolchain.commandLine(cargo, directory, "update", "--dry-run", "--verbose", "--color", "never"), TIMEOUT_MS)
                    val parsed = CargoUpdateParser.parse(output.stderr + "\n" + output.stdout)
                    if (output.exitCode == 0 || parsed.isNotEmpty()) entries = parsed
                    else error = output.stderr.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("error") } ?: "cargo update --dry-run has failed (exit code ${output.exitCode})"
                }

                override fun onFinished() {
                    val found = entries
                    if (found == null) {
                        notify(project, error ?: "cargo update --dry-run has failed", NotificationType.WARNING)
                        return
                    }
                    found.forEach { getInstance().remember(it.name, it.latest) }
                    val newer = found.filter { it.latest != it.current }
                    val text = if (newer.isEmpty()) "Every dependency is at its newest version"
                    else "${newer.size} ${StringUtil.pluralize("crate", newer.size)} with newer versions: " +
                        newer.take(8).joinToString(", ") { "${it.name} ${it.current} → ${it.latest}" } + (if (newer.size > 8) ", …" else "") +
                        ". Cargo.toml highlights the requirements a newer version does not satisfy."
                    notify(project, text, NotificationType.INFORMATION)
                    DaemonCodeAnalyzer.getInstance(project).restart("Cargo crate versions updated")
                }
            }.queue()
        }

        private fun notify(project: Project, text: String, type: NotificationType) {
            if (project.isDisposed) return
            NotificationGroupManager.getInstance().getNotificationGroup("Rust").createNotification("Cargo", text, type).notify(project)
        }
    }
}

/**
 * Tools | Rust | Check Dependency Updates: `cargo update --dry-run --verbose` of the workspace (nothing is written), through cargo and
 * its proxy and mirror settings. The newer versions then show in Cargo.toml.
 */
class CheckDependencyUpdatesAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.basePath != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val state = CargoProjects.getInstance(project).state
        val directory = state?.workspaces?.firstOrNull()?.root
        if (directory != null) return CargoCrateVersions.checkWorkspace(project, directory)
        ApplicationManager.getApplication().executeOnPooledThread {
            val base = project.basePath ?: return@executeOnPooledThread
            val root = RustProjectDetector.targetDirectories(Path.of(base)).map { it.parent.toString() }.minByOrNull { it.length } ?: base
            CargoCrateVersions.checkWorkspace(project, root)
        }
    }
}
