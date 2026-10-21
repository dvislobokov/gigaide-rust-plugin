package io.github.rustsupport.lsp

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.BaseProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.intellij.ui.content.ContentFactory
import io.github.rustsupport.toolchain.RustProjectDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import java.awt.BorderLayout
import java.nio.file.Path
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.swing.JPanel

/**
 * The log window of rust-analyzer (tool window `rust-analyzer`): what the server writes to stderr (its own log, `RA_LOG`), its
 * `window/logMessage` and `window/showMessage`, its health from `experimental/serverStatus`, the starts and stops of its process, and the
 * requests of the plugin that have failed. The console is made when the window is first shown; what comes before is kept until then.
 */
@Service(Service.Level.PROJECT)
class RustAnalyzerLog(private val project: Project) : Disposable {
    private val pending = StringBuilder()
    @Volatile private var console: ConsoleView? = null

    /** What the plugin does with the server; also in idea.log at the info level. */
    fun info(text: String) {
        LOG.info(text)
        append(RustAnalyzerLogLines.stamped(LocalTime.now(), text), ConsoleViewContentType.SYSTEM_OUTPUT)
    }

    fun error(text: String) {
        LOG.info(text)
        append(RustAnalyzerLogLines.stamped(LocalTime.now(), text), ConsoleViewContentType.ERROR_OUTPUT)
    }

    /** A message of the server (`window/logMessage`, `window/showMessage`). */
    fun message(params: MessageParams, prefix: String = "") {
        val text = prefix + params.message.orEmpty().trimEnd()
        val type = when (params.type) {
            MessageType.Error -> ConsoleViewContentType.ERROR_OUTPUT
            MessageType.Warning -> ConsoleViewContentType.LOG_WARNING_OUTPUT
            else -> ConsoleViewContentType.NORMAL_OUTPUT
        }
        append(RustAnalyzerLogLines.stamped(LocalTime.now(), text), type)
    }

    /** A line of stderr of the server: its own time is shortened, its level gives the colour. */
    fun stderr(line: String) {
        val type = when (RustAnalyzerLogLines.level(line)) {
            "ERROR" -> ConsoleViewContentType.ERROR_OUTPUT
            "WARN" -> ConsoleViewContentType.LOG_WARNING_OUTPUT
            else -> ConsoleViewContentType.NORMAL_OUTPUT
        }
        append(RustAnalyzerLogLines.stderr(line) + "\n", type)
    }

    @Synchronized
    private fun append(text: String, type: ConsoleViewContentType) {
        val view = console
        if (view != null) view.print(text, type)
        else {
            pending.append(text)
            // nobody has looked for a long time: the oldest lines go
            if (pending.length > PENDING_LIMIT) pending.delete(0, pending.length - PENDING_LIMIT / 2)
        }
    }

    @Synchronized
    fun console(): ConsoleView = console ?: TextConsoleBuilderFactory.getInstance().createBuilder(project).console.also {
        Disposer.register(this, it)
        it.print(pending.toString(), ConsoleViewContentType.NORMAL_OUTPUT)
        pending.setLength(0)
        console = it
    }

    /** The stderr of a started server, line by line, and the end of its process. */
    fun attach(handler: BaseProcessHandler<*>, commandLine: String) {
        info("Starting $commandLine")
        val lines = RustAnalyzerLogLines.Splitter()
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                // stdout is the protocol itself
                if (ProcessOutputType.isStderr(outputType)) lines.feed(event.text).forEach(::stderr)
            }

            override fun processTerminated(event: ProcessEvent) {
                lines.rest()?.let(::stderr)
                if (!project.isDisposed) info("rust-analyzer has stopped, exit code ${event.exitCode}")
            }
        })
    }

    /** `experimental/serverStatus`: to the status widget; a change of health to the log, an error to a balloon. */
    fun serverStatus(status: RustAnalyzerProtocol.ServerStatus) {
        val state = RustAnalyzerServerState.getInstance(project)
        val before = state.health
        state.healthChanged(status.health, status.message)
        if (before?.health == status.health && before.message == status.message) return
        val text = RustAnalyzerLogLines.health(status.health, status.message)
        if (status.health == "ok") info(text) else error(text)
        if (status.health == "error" && !status.message.isNullOrBlank()) {
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                NotificationGroupManager.getInstance().getNotificationGroup("Rust")
                    .createNotification("rust-analyzer", RustAnalyzerLogLines.html(status.message), NotificationType.ERROR)
                    .addAction(DumbAwareAction.create("Show Log") { ShowRustAnalyzerLogAction.show(project) })
                    .notify(project)
            }
        }
    }

    override fun dispose() {}

    companion object {
        private val LOG = logger<RustAnalyzerLog>()
        private const val PENDING_LIMIT = 1_000_000

        fun getInstance(project: Project): RustAnalyzerLog = project.service()
    }
}

/** The lines of the log; pure, for the tests. */
object RustAnalyzerLogLines {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** `2026-10-21T19:48:25.1648538+03:00  WARN notify error: ...` (seen live): the date and the zone are dropped. */
    private val STAMP = Regex("""^\d{4}-\d\d-\d\dT(\d\d:\d\d:\d\d)(?:\.\d+)?(?:Z|[+-]\d\d:\d\d)?\s+""")
    private val LEVEL = Regex("""^(?:\S+\s+)?(ERROR|WARN|INFO|DEBUG|TRACE)\b""")

    fun stamped(time: LocalTime, text: String): String = "[${TIME.format(time)}] $text\n"

    fun stderr(line: String): String = STAMP.find(line)?.let { "[${it.groupValues[1]}] " + line.substring(it.range.last + 1) } ?: line

    /** `ERROR`, `WARN`, ... of a line of stderr, null when it has none. */
    fun level(line: String): String? = LEVEL.find(line.trimStart())?.groupValues?.get(1)

    fun health(health: String, message: String?): String =
        "Server health: $health" + (message?.trim()?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: "")

    fun html(text: String): String = com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(text.trim()).replace("\r", "").replace("\n", "<br>")

    /** Text that comes in pieces, given out in whole lines (without their ends). */
    class Splitter {
        private val buffer = StringBuilder()

        @Synchronized
        fun feed(text: String): List<String> {
            buffer.append(text)
            val lines = ArrayList<String>()
            while (true) {
                val end = buffer.indexOf("\n")
                if (end < 0) break
                lines += buffer.substring(0, end).trimEnd('\r')
                buffer.delete(0, end + 1)
            }
            return lines
        }

        /** What is left after the last line end, at the end of the process. */
        @Synchronized
        fun rest(): String? = buffer.toString().trimEnd('\r').takeIf { it.isNotEmpty() }.also { buffer.setLength(0) }
    }
}

/** The messages of the server copied to the log, on their way to the platform (which shows an error of `logMessage` as a balloon). */
internal class LoggingNotificationsHandler(
    private val delegate: LspServerNotificationsHandler, private val log: RustAnalyzerLog,
) : LspServerNotificationsHandler by delegate {
    override fun logMessage(params: MessageParams) {
        log.message(params)
        delegate.logMessage(params)
    }

    override fun showMessage(params: MessageParams) {
        log.message(params, "showMessage: ")
        delegate.showMessage(params)
    }
}

class RustAnalyzerLogToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val console = RustAnalyzerLog.getInstance(project).console()
        val actions = DefaultActionGroup().apply {
            ActionManager.getInstance().getAction("Rust.RestartLanguageServer")?.let(::add)
            ActionManager.getInstance().getAction("Rust.Settings")?.let(::add)
            addSeparator()
            addAll(*console.createConsoleActions())
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("RustAnalyzerLog", actions, false)
        toolbar.targetComponent = console.component
        val panel = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(console.component, BorderLayout.CENTER)
        }
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
    }

    /** Only a project with Rust gets the stripe button; the disk is read off the UI thread. */
    override suspend fun isApplicableAsync(project: Project): Boolean =
        withContext(Dispatchers.IO) { RustProjectDetector.hasRust(project.basePath?.let { Path.of(it) }) }
}

class ShowRustAnalyzerLogAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        show(e.project ?: return)
    }

    companion object {
        const val TOOL_WINDOW_ID = "rust-analyzer"

        fun show(project: Project) {
            ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
        }
    }
}
