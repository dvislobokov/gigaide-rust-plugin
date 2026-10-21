package io.github.rustsupport.lsp

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import io.github.rustsupport.RustIcons
import io.github.rustsupport.monitor.ProcessSampler
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.Timer

/**
 * The running rust-analyzer of a project: its process, its version, the work it reports and what it costs. The cost is measured every few
 * seconds for as long as the process lives, off the UI thread: the status bar shows it without a click.
 */
@Service(Service.Level.PROJECT)
class RustAnalyzerServerState(private val project: Project) : Disposable {
    @Volatile var process: ProcessHandle? = null
        private set

    /** `serverInfo.version`: `1.99.1 (58cf8f9c 2026-10-13)`. */
    @Volatile var version: String? = null

    /** What the server is busy with, from its `$/progress`: `Indexing 120/436`, `cargo check`; null when idle. */
    @Volatile var activity: String? = null

    @Volatile var sample: RustAnalyzerUsage.Sample? = null
        private set

    /** The health of the server from its `experimental/serverStatus`: `ok`, `warning` or `error`, with what it says; null until told. */
    class Health(val health: String, val message: String?)

    @Volatile var health: Health? = null
        private set

    private val usage = RustAnalyzerUsage()
    private var measuring: ScheduledFuture<*>? = null

    val isRunning: Boolean get() = process?.isAlive == true

    @Synchronized
    fun started(handle: ProcessHandle) {
        process = handle
        version = null
        activity = null
        sample = null
        health = null
        measuring?.cancel(false)
        measuring = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ measure() }, 0, PERIOD_MS, TimeUnit.MILLISECONDS)
        RustAnalyzerStatusWidgetFactory.refresh(project)
    }

    /** On a restart the new server may be there before the old one is gone: only the end of the current process counts. */
    @Synchronized
    fun stopped(handle: ProcessHandle) {
        if (process != handle) return
        measuring?.cancel(false)
        measuring = null
        process = null
        sample = null
        activity = null
        RustAnalyzerStatusWidgetFactory.refresh(project)
    }

    fun healthChanged(health: String, message: String?) {
        this.health = Health(health, message?.trim()?.takeIf { it.isNotEmpty() })
        RustAnalyzerStatusWidgetFactory.repaint(project)
    }

    fun activityChanged(text: String?) {
        activity = text
        RustAnalyzerStatusWidgetFactory.repaint(project)
    }

    private fun measure() {
        // an exception would end the schedule
        sample = runCatching { usage.sample(process) }.getOrNull()
        RustAnalyzerStatusWidgetFactory.repaint(project)
    }

    override fun dispose() {
        measuring?.cancel(false)
    }

    companion object {
        const val PERIOD_MS = 3_000L

        fun getInstance(project: Project): RustAnalyzerServerState = project.service()
    }
}

/**
 * rust-analyzer in the status bar for as long as its process lives, with its memory and CPU next to the icon, as the Go and .NET siblings
 * show their servers. The row of the platform in the widget of language services is switched off ([RustAnalyzerIntegrationProvider]).
 */
class RustAnalyzerStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID
    override fun getDisplayName(): String = "Rust Language Server (rust-analyzer)"
    override fun isAvailable(project: Project): Boolean = RustAnalyzerServerState.getInstance(project).isRunning
    override fun createWidget(project: Project): StatusBarWidget = RustAnalyzerStatusWidget(project)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    companion object {
        const val ID = "Rust.RustAnalyzer.Status"

        /** The server has started or stopped: the widget comes or goes. */
        fun refresh(project: Project) {
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                project.service<StatusBarWidgetsManager>().updateWidget(RustAnalyzerStatusWidgetFactory::class.java)
                WindowManager.getInstance().getStatusBar(project)?.updateWidget(ID)
            }, ModalityState.any())
        }

        /** New numbers: the text of the widget changes. */
        fun repaint(project: Project) {
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed) WindowManager.getInstance().getStatusBar(project)?.updateWidget(ID)
            }, ModalityState.any())
        }
    }
}

class RustAnalyzerStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.MultipleTextValuesPresentation {
    private val state get() = RustAnalyzerServerState.getInstance(project)

    override fun ID(): String = RustAnalyzerStatusWidgetFactory.ID
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun install(statusBar: StatusBar) = Unit
    override fun dispose() = Unit

    override fun getIcon(): Icon = RustIcons.RUST
    override fun getSelectedValue(): String = RustAnalyzerStatusText.widget(state.sample, state.activity)
    override fun getTooltipText(): String = RustAnalyzerStatusText.tooltip(state.version, state.sample, state.activity)
    override fun getPopup(): JBPopup = RustAnalyzerStatusPopup(project).create()
}

/** What a click on the widget shows: the server in words, its numbers (updated while the popup is open) and what can be done with it. */
class RustAnalyzerStatusPopup(private val project: Project) {
    private val state get() = RustAnalyzerServerState.getInstance(project)
    private val status = JBLabel()
    private val health = JBLabel()
    private val cpu = JBLabel(RustAnalyzerStatusText.MEASURING)
    private val memory = JBLabel(RustAnalyzerStatusText.MEASURING)
    private val process = JBLabel()

    fun create(): JBPopup {
        var popup: JBPopup? = null
        fun action(id: String) {
            popup?.cancel()
            val action = ActionManager.getInstance().getAction(id) ?: return
            ActionUtil.invokeAction(action, SimpleDataContext.getProjectContext(project), ActionPlaces.STATUS_BAR_PLACE, null, null)
        }
        val content: JComponent = panel {
            row { icon(RustIcons.RUST); label(RustAnalyzerStatusText.title(state.version)).bold() }
            RustAnalyzerStatusText.build(state.version)?.let { row("Build:") { label(it) } }
            row("Status:") { cell(status) }
            row("Health:") { cell(health) }
            row("CPU:") { cell(cpu) }
            row("Memory:") { cell(memory) }
            row("Process:") { cell(process) }
            separator()
            row {
                link("Restart") { action("Rust.RestartLanguageServer") }
                link("Log") { action("Rust.ShowLog") }
                link("Install or Update Components") { action("Rust.InstallComponents") }
                link("Settings...") { action("Rust.Settings") }
            }
        }.apply { border = JBUI.Borders.empty(10, 14) }

        val created = JBPopupFactory.getInstance().createComponentPopupBuilder(content, null)
            .setRequestFocus(true).setCancelOnClickOutside(true).setCancelOnWindowDeactivation(true).createPopup()
        popup = created
        refresh()
        val timer = Timer(REFRESH_MS) { refresh() }.apply { start() }
        Disposer.register(created) { timer.stop() }
        return created
    }

    /** The numbers are the ones the state has measured for the status bar: the popup reads, it does not measure. */
    private fun refresh() {
        val sample = state.sample
        val running = state.isRunning
        status.text = if (running) state.activity ?: "ready" else RustAnalyzerStatusText.STOPPED
        health.text = if (running) RustAnalyzerStatusText.health(state.health?.health, state.health?.message) else RustAnalyzerStatusText.STOPPED
        health.toolTipText = state.health?.message
        cpu.text = if (sample == null) (if (running) RustAnalyzerStatusText.MEASURING else RustAnalyzerStatusText.STOPPED) else RustAnalyzerStatusText.cpu(sample.cpuPercent)
        memory.text = sample?.let { RustAnalyzerStatusText.memoryWithCommitted(it.memoryBytes, it.committedBytes) }
            ?: if (running) RustAnalyzerStatusText.MEASURING else RustAnalyzerStatusText.STOPPED
        process.text = sample?.let { RustAnalyzerStatusText.process(it.pid, it.processes, it.uptime) }
            ?: if (running) RustAnalyzerStatusText.MEASURING else RustAnalyzerStatusText.STOPPED
    }

    private companion object {
        const val REFRESH_MS = 1_000
    }
}

/** The CPU and the memory of the server and of what it has started (cargo, build scripts, the proc-macro server): the tree of its process. */
class RustAnalyzerUsage {
    class Sample(
        val pid: Long, val processes: Int, val memoryBytes: Long, val committedBytes: Long,
        /** Null for the first sample: a share needs two. */
        val cpuPercent: Double?, val uptime: Duration?,
    )

    private var previousCpu: Duration? = null
    private var previousAt = 0L
    private var previousPid = -1L

    @Synchronized
    fun sample(root: ProcessHandle?): Sample? {
        if (root == null || !root.isAlive) return null
        val tree = ProcessSampler.tree(root.pid())
        if (tree.isEmpty()) return null
        val now = System.nanoTime()
        val usage = ProcessSampler.sample(tree)
        val before = previousCpu.takeIf { previousPid == root.pid() }
        val percent = before?.let { ProcessSampler.cpuPercent(it, usage.cpuTime, now - previousAt) }
        previousCpu = usage.cpuTime
        previousAt = now
        previousPid = root.pid()
        val started = root.info().startInstant().orElse(null)
        return Sample(root.pid(), usage.processes, usage.workingSetBytes, usage.privateBytes, percent, started?.let { Duration.between(it, Instant.now()) })
    }
}

/** The words of the widget and of its popup; pure, for the tests. */
object RustAnalyzerStatusText {
    const val MEASURING = "measuring..."
    const val STOPPED = "not running"

    /** Next to the icon in the status bar: `1.2 GB · 3%`, or what the server is busy with; whole percents, so that the width does not jump. */
    fun widget(sample: RustAnalyzerUsage.Sample?, activity: String? = null): String {
        if (sample == null) return "rust-analyzer"
        val numbers = memory(sample.memoryBytes) + (sample.cpuPercent?.let { " · " + String.format(Locale.ROOT, "%.0f%%", it) } ?: "")
        return if (activity != null) "$numbers · ${activity.take(40)}" else numbers
    }

    fun tooltip(version: String?, sample: RustAnalyzerUsage.Sample?, activity: String? = null): String = listOfNotNull(
        title(version),
        activity,
        sample?.let { "memory " + memory(it.memoryBytes) },
        sample?.cpuPercent?.let { "CPU " + cpu(it) },
        sample?.uptime?.let { "running " + uptime(it) },
    ).joinToString(", ")

    /** `rust-analyzer 1.99.1`, out of `1.99.1 (58cf8f9c 2026-10-13)`. */
    /** `ok`, or `warning: Failed to run build scripts...` cut to a line of the popup; `unknown` before the server has told. */
    fun health(health: String?, message: String?): String {
        if (health == null) return "unknown"
        val first = message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
        return if (first == null) health else "$health: " + if (first.length > 80) first.take(79) + "…" else first
    }

    fun title(version: String?): String = listOf("rust-analyzer", version?.substringBefore(' ').orEmpty()).filter { it.isNotEmpty() }.joinToString(" ")

    /** `58cf8f9c 2026-10-13`: the commit and the date of the build, when the version names them. */
    fun build(version: String?): String? = version?.substringAfter('(', "")?.substringBefore(')')?.takeIf { it.isNotBlank() }

    /** A share of the whole machine, as the task manager of the system shows it. */
    fun cpu(percent: Double?): String = if (percent == null) MEASURING else String.format(Locale.ROOT, "%.1f %%", percent)

    fun memory(bytes: Long): String {
        val megabytes = bytes / (1024.0 * 1024.0)
        return if (megabytes < 1024) String.format(Locale.ROOT, "%.0f MB", megabytes) else String.format(Locale.ROOT, "%.2f GB", megabytes / 1024)
    }

    fun memoryWithCommitted(bytes: Long, committed: Long): String = memory(bytes) + if (committed > 0 && committed != bytes) " (committed ${memory(committed)})" else ""

    fun uptime(uptime: Duration): String {
        val seconds = uptime.seconds.coerceAtLeast(0)
        return when {
            seconds < 60 -> "$seconds s"
            seconds < 3600 -> "${seconds / 60} min"
            else -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
        }
    }

    fun process(pid: Long, processes: Int, uptime: Duration?): String = listOfNotNull(
        "PID $pid",
        if (processes > 1) "$processes processes" else null,
        uptime?.let { "running " + uptime(it) },
    ).joinToString(", ")
}
