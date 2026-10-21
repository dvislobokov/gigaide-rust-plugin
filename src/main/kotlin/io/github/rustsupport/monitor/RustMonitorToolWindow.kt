package io.github.rustsupport.monitor

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.rustsupport.toolchain.RustProjectDetector
import java.awt.BorderLayout
import java.awt.Color
import java.awt.GridLayout
import java.nio.file.Path
import java.time.Duration
import javax.swing.DefaultComboBoxModel
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

class RustMonitorToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = RustMonitorPanel(project, toolWindow.disposable)
        toolWindow.contentManager.addContent(toolWindow.contentManager.factory.createContent(panel, "", false))
    }

    override fun shouldBeAvailable(project: Project): Boolean = RustProjectDetector.hasRust(project.basePath?.let { Path.of(it) })

    companion object {
        const val ID = "Rust Monitor"
    }
}

/**
 * Tools | Rust | Open Monitor, and the context menu of a Run console: the monitor, on the process of that Run tab when it is one of
 * our runs.
 */
class OpenRustMonitorAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        // in the Tools menu always (the tool window says what to do); in the popup of a console only for our runs
        val running = RunningRustProcesses.getInstance(project)
        val descriptor = e.getData(LangDataKeys.RUN_CONTENT_DESCRIPTOR)
        e.presentation.isEnabledAndVisible = !e.isFromContextMenu ||
            (if (descriptor != null) running.find(descriptor.processHandler) != null else running.targets().isNotEmpty())
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = RunningRustProcesses.getInstance(project).find(e.getData(LangDataKeys.RUN_CONTENT_DESCRIPTOR)?.processHandler)
        ToolWindowManager.getInstance(project).getToolWindow(RustMonitorToolWindowFactory.ID)?.activate({
            target?.let { RunningRustProcesses.getInstance(project).select(it) }
        }, true)
    }
}

/**
 * A narrow panel on the right, as the monitors of the Go and .NET siblings: the run on top, the charts one under another. Everything
 * comes from the operating system; the program is the child of cargo, followed when it restarts.
 */
class RustMonitorPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()), Disposable {
    private val processes = ComboBox<MonitorTarget>()
    private val header = JBLabel().apply { foreground = UIUtil.getLabelForeground() }
    private val status = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val history = MonitorHistory()
    private var session: RustMonitorSession? = null
    private var updatingList = false

    private val cpu = TimeSeriesChart("CPU", ChartFormats::percent, fixedMax = 100.0, "of ${Runtime.getRuntime().availableProcessors()} cores" to CPU_COLOR)
    private val memory = TimeSeriesChart("Memory", ChartFormats::bytes, null, "private" to MEMORY_COLOR, "working set" to WORKING_SET_COLOR).apply { scale = ChartFormats::niceMaxBytes }
    private val threads = TimeSeriesChart("Threads", ChartFormats::number, null, "threads" to CPU_COLOR)
    private val tree = TimeSeriesChart("Processes", ChartFormats::number, null, "measured" to TREE_COLOR)
    private val charts = listOf(cpu, memory, threads, tree)

    init {
        Disposer.register(parent, this)
        val actions = DefaultActionGroup(StopAction(), ExportCsvAction())
        val toolbar = ActionManager.getInstance().createActionToolbar("RustMonitor", actions, true).apply { targetComponent = this@RustMonitorPanel }
        val chooser = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            add(processes, BorderLayout.CENTER)
            add(toolbar.component, BorderLayout.EAST)
        }
        val top = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            border = JBUI.Borders.empty(6, 8, 2, 8)
            add(chooser, BorderLayout.NORTH)
            add(header, BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        }
        val column = JPanel(GridLayout(0, 1, 0, JBUI.scale(10))).apply {
            border = JBUI.Borders.empty(4, 8, 8, 8)
            charts.forEach { add(it) }
        }
        // NORTH: the charts keep their height instead of stretching over a tall panel
        val content = JPanel(BorderLayout()).apply { add(column, BorderLayout.NORTH) }
        add(top, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(content, true).apply { horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER }, BorderLayout.CENTER)

        processes.addActionListener { if (!updatingList) (processes.selectedItem as? MonitorTarget)?.let(::monitor) }
        RunningRustProcesses.getInstance(project).subscribe(this) { started ->
            ApplicationManager.getApplication().invokeLater({ reload(select = started) }, project.disposed)
        }
        reload(select = RunningRustProcesses.getInstance(project).targets().firstOrNull())
    }

    /** Our live runs, the newest first; [select] when a run started or was asked for, else the one shown stays. */
    private fun reload(select: MonitorTarget?) {
        val own = RunningRustProcesses.getInstance(project).targets()
        val current = select?.takeIf { it in own } ?: session?.target?.takeIf { it in own } ?: own.firstOrNull().takeIf { session == null }
        updatingList = true
        processes.model = DefaultComboBoxModel(own.toTypedArray())
        processes.selectedItem = current ?: session?.target
        updatingList = false
        if (current != null) monitor(current)
        else if (session == null) {
            header.text = ""
            status.text = if (history.samples().isEmpty()) "Run a Cargo configuration to see its program here" else status.text
        }
    }

    private fun monitor(target: MonitorTarget) {
        if (session?.target == target) return
        session?.dispose()
        history.clear()
        charts.forEach { it.clear() }
        header.text = target.title
        status.text = "Waiting for the first sample..."
        val started = RustMonitorSession(
            target,
            onSample = { sample -> ApplicationManager.getApplication().invokeLater({ show(target, sample) }, project.disposed) },
            onEnd = {
                ApplicationManager.getApplication().invokeLater({
                    if (session?.target == target) {
                        status.text = "${target.title} has exited; the samples stay until another run"
                        session = null
                    }
                }, project.disposed)
            },
        )
        session = started
        started.start()
    }

    private fun show(target: MonitorTarget, sample: MonitorSample) {
        if (session?.target != target) return
        history.add(sample)
        cpu.add(sample.cpuPercent)
        memory.add(sample.privateBytes.toDouble(), sample.workingSetBytes.toDouble())
        threads.add(sample.threads?.toDouble())
        tree.add(sample.processes.toDouble())
        val uptime = sample.programStartMillis.takeIf { it > 0 }?.let { MonitorFormats.uptime(Duration.ofMillis(sample.timeMillis - it)) }
        header.text = listOfNotNull(
            "${sample.programName}  PID ${sample.pid}",
            uptime?.let { "up $it" },
            "peak ${ChartFormats.bytes(history.peakWorkingSetBytes.toDouble())}",
        ).joinToString("  ·  ")
        status.text = MonitorFormats.status(target.name, target.debug, sample)
    }

    override fun dispose() {
        session?.dispose()
        session = null
    }

    private inner class StopAction : AnAction("Stop Process", "Stop the selected run (cargo and the program it started)", AllIcons.Actions.Suspend), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = (processes.selectedItem as? MonitorTarget)?.handler?.isProcessTerminated == false
        }
        override fun actionPerformed(e: AnActionEvent) {
            (processes.selectedItem as? MonitorTarget)?.handler?.destroyProcess()
        }
    }

    private inner class ExportCsvAction : AnAction("Export Samples to CSV...", "The samples of the last ten minutes, one row a second", AllIcons.ToolbarDecorator.Export), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = history.samples().isNotEmpty()
        }
        override fun actionPerformed(e: AnActionEvent) {
            val samples = history.samples().ifEmpty { return }
            val descriptor = FileSaverDescriptor("Export Samples", "CPU, memory, threads and processes, one row a second", "csv")
            val name = samples.last().programName.ifEmpty { "rust" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(null as VirtualFile?, "$name-monitor.csv") ?: return
            try {
                wrapper.file.writeText(MonitorCsv.format(samples))
            } catch (ex: Exception) {
                Messages.showErrorDialog(project, ex.message ?: ex.javaClass.simpleName, "Export Samples")
            }
        }
    }

    private companion object {
        val CPU_COLOR = JBColor(Color(0x3574F0), Color(0x548AF7))
        val MEMORY_COLOR = JBColor(Color(0x208A3C), Color(0x5FAD65))
        val WORKING_SET_COLOR = JBColor(Color(0x6C707E), Color(0x9DA0A8))
        val TREE_COLOR = JBColor(Color(0xC9643B), Color(0xE07B4F))
    }
}
