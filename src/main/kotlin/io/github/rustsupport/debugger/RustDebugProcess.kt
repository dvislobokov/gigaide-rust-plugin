package io.github.rustsupport.debugger

import com.google.gson.JsonObject
import com.intellij.execution.ExecutionException
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.OSProcessUtil
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.XSuspendContext
import io.github.rustsupport.monitor.MonitorTarget
import io.github.rustsupport.monitor.RunningRustProcesses
import io.github.rustsupport.monitor.RustProcessStartListener
import io.github.rustsupport.run.RustDebugLaunchSpec
import io.github.rustsupport.toolchain.RustSources
import io.github.rustsupport.toolchain.RustTool
import io.github.rustsupport.toolchain.RustToolchain
import io.github.rustsupport.toolchain.RustToolchainInstaller
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

private val LOG = logger<RustDebugProcess>()

/**
 * A debug session of a Rust program under CodeLLDB or lldb-dap ([RustDebugAdapter]) on the XDebugger API. The start, off the EDT: `cargo <build> --message-format=json`
 * with its output in the console of the session, the executable from its `compiler-artifact` messages, then the adapter, `initialize`,
 * `launch`, and on the `initialized` event the breakpoints and `configurationDone`.
 */
class RustDebugProcess(
    session: XDebugSession,
    private val spec: RustDebugLaunchSpec,
    private val name: String,
    private val debugAdapter: RustDebugAdapter,
) : XDebugProcess(session), DapConnection.Listener {
    private val handler = DebuggeeProcessHandler()
    private val lineBreakpoints = RustLineBreakpointHandler(this)
    private val panicBreakpoints = RustPanicBreakpointHandler(this)
    private val editors = RustDebuggerEditorsProvider()

    @Volatile private var dap: DapConnection? = null
    @Volatile private var adapter: DebugAdapterProcess? = null
    @Volatile private var build: Process? = null
    @Volatile private var monitored: MonitorTarget? = null

    /** The connection; requests before the adapter is there end with [DapClosedException]. */
    val connection: DapConnection get() = dap ?: NOT_STARTED

    val adapterKind: RustDebugAdapterKind get() = debugAdapter.kind

    /** An LLDB command as `evaluate` in `repl` takes it: as it is for CodeLLDB, after the escape for lldb-dap. */
    fun lldbCommand(command: String): String = if (adapterKind == RustDebugAdapterKind.LLDB_DAP) LldbDapLaunch.command(command) else command

    @Volatile var configured: Boolean = false
        private set

    @Volatile var stoppedThreadId: Int? = null
        private set

    /** The file `rust_line_locations.py`, loaded into the adapter, answers in; null without it ([RustLineLocations]). */
    @Volatile var lineLocations: Path? = null
        private set
    /** The stop of `stopOnEntry` is still to come: the IDE does not show it, it sets the pieces of the line breakpoints and resumes. */
    private val entryStop = AtomicBoolean()

    @Volatile private var exitCode: Int? = null
    private val shutdown = AtomicBoolean()
    private val stopped = AsyncPromise<Any>()

    override fun getEditorsProvider(): XDebuggerEditorsProvider = editors
    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = arrayOf(lineBreakpoints, panicBreakpoints)
    override fun doGetProcessHandler(): ProcessHandler = handler

    /** The console of the session shows the build, the program and the messages of the adapter: all of them come through [handler]. */
    override fun createConsole(): ExecutionConsole = super.createConsole().also { (it as? ConsoleView)?.attachToProcess(handler) }

    override fun sessionInitialized() {
        handler.startNotify()
        AppExecutorUtil.getAppExecutorService().execute(::start)
    }

    private fun start() {
        try {
            val cargo = RustTool.CARGO.find() ?: throw ExecutionException("cargo is not found: set its path in Settings | Languages & Frameworks | Rust")
            val arguments = CargoBuild.arguments(spec.buildArguments)
            print("> cargo ${arguments.joinToString(" ")}\n", ProcessOutputTypes.SYSTEM)
            val commandLine = RustToolchain.commandLine(cargo, spec.workingDirectory, *arguments.toTypedArray()).withEnvironment(spec.environment)
            val result = CargoBuild.run(commandLine, { process -> build = process; if (shutdown.get()) OSProcessUtil.killProcessTree(process) }, ::print)
            build = null
            if (shutdown.get()) return
            if (!result.success) return startFailed("the build has failed (cargo exit code ${result.exitCode})", buildFailed = true)
            val choice = CargoBuild.choose(result.artifacts, CargoBuild.isTestBuild(spec.buildArguments))
            choice.warning?.let { print("$it\n", ProcessOutputTypes.SYSTEM) }
            val program = choice.executable ?: return startFailed(choice.error ?: "nothing to debug")

            val rustc = rustcInfo()
            val started = when (adapterKind) {
                RustDebugAdapterKind.CODELLDB -> DebugAdapterProcess.startCodeLldb(debugAdapter.path, CodeLldbLaunch.adapterSettings(rustc?.sysroot), spec.workingDirectory, ::print)
                RustDebugAdapterKind.LLDB_DAP -> DebugAdapterProcess.startLldbDap(debugAdapter.path, spec.workingDirectory, ::print)
            }
            adapter = started
            if (shutdown.get()) return started.stop(0)
            // the Rust Monitor follows the program under the adapter: its pid comes with the `process` event
            monitored = RunningRustProcesses.getInstance(session.project).startedDebug(name, handler, started.pid)
            if (monitored != null) RustProcessStartListener.openIfAsked(session.project)
            val connection = DapConnection(started.input, started.output, this, newProtocolTrace())
            dap = connection
            print("> ${debugAdapter.path} (pid ${started.pid})\n> $program ${spec.programArguments.joinToString(" ")}\n", ProcessOutputTypes.SYSTEM)
            if (started.programInput == null) print("${adapterKind.title} does not pass the input of the console to the program\n", ProcessOutputTypes.SYSTEM)
            val script = if (RustLineLocations.enabled) RustLineLocations.script() else null
            lineLocations = script?.let { runCatching { Files.createTempFile("rust-line-locations", ".txt") }.getOrNull() }
            val initCommands = script?.takeIf { lineLocations != null }?.let(RustLineLocations::initCommands).orEmpty()
            entryStop.set(initCommands.isNotEmpty())
            val launch = when (adapterKind) {
                RustDebugAdapterKind.CODELLDB -> CodeLldbLaunch.arguments(name, program, spec.programArguments, spec.workingDirectory, spec.environment, rustc, initCommands, entryStop.get())
                RustDebugAdapterKind.LLDB_DAP -> LldbDapLaunch.arguments(name, program, spec.programArguments, spec.workingDirectory, spec.environment, rustc, initCommands, entryStop.get())
            }
            connection.start(debugAdapter.path.fileName.toString())
            connection.request("initialize", CodeLldbLaunch.initializeArguments(if (adapterKind == RustDebugAdapterKind.LLDB_DAP) "lldb-dap" else "lldb"), REQUEST_TIMEOUT_MS)
                .thenCompose { connection.request("launch", launch) }
                .whenComplete { _, error -> if (error != null && !shutdown.get()) startFailed(errorText(error)) }
        } catch (e: Exception) {
            if (!shutdown.get()) startFailed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** The sysroot and the commit of the toolchain of the project directory (a `rust-toolchain.toml` there picks it). */
    private fun rustcInfo(): RustcInfo? {
        val rustc = RustTool.RUSTC.find() ?: return null
        fun run(vararg args: String): String? = runCatching {
            val output = CapturingProcessHandler(RustToolchain.commandLine(rustc, spec.workingDirectory, *args)).runProcess(15_000)
            output.stdout.takeIf { output.exitCode == 0 }
        }.getOrNull()
        val sysroot = run("--print", "sysroot")?.trim()?.takeIf { it.isNotEmpty() }
        return RustcInfo(sysroot, run("-vV")?.let(RustcInfo::commitHash), RustSources.configured()?.parent?.toString())
    }

    private fun startFailed(reason: String, buildFailed: Boolean = false) {
        exitCode = 1
        print("Cannot start debugging: $reason\n", ProcessOutputTypes.STDERR)
        if (!buildFailed) {
            NotificationGroupManager.getInstance().getNotificationGroup(RustToolchainInstaller.NOTIFICATION_GROUP)
                .createNotification("Debug has not started", "$name: $reason", NotificationType.ERROR)
                .notify(session.project)
        }
        session.stop()
    }

    // --- events of the adapter ---

    override fun event(event: String, body: JsonObject) {
        when (event) {
            "initialized" -> configure()
            "process" -> body.get("systemProcessId")?.takeIf { it.isJsonPrimitive }?.asLong?.let { monitored?.programPid = it }
            "stopped" -> {
                if (entryStop.compareAndSet(true, false)) return entryStopped(body)
                monitored?.paused = true
                stoppedEvent(body)
            }
            "continued" -> {
                monitored?.paused = false
                if (!session.isStopped) session.sessionResumed()
            }
            "output" -> output(body)
            "breakpoint" -> body.getAsJsonObject("breakpoint")?.let(lineBreakpoints::update)
            "exited" -> exitCode = body.int("exitCode")
            "terminated" -> AppExecutorUtil.getAppExecutorService().execute { shutdown(programGone = true) }
        }
    }

    override fun closed() {
        AppExecutorUtil.getAppExecutorService().execute { shutdown(programGone = true) }
    }

    private fun configure() {
        configured = true
        CompletableFuture.allOf(lineBreakpoints.sendAll(), panicBreakpoints.send())
            .handle { _, _ -> null }
            .thenCompose { connection.request("configurationDone") }
            .exceptionally { error -> LOG.info("configurationDone: ${errorText(error)}"); null }
    }

    /** The program is loaded (on Windows this stop comes as "exception"): the pieces of the line breakpoints, then on, unseen. */
    private fun entryStopped(body: JsonObject) {
        lineBreakpoints.locate()
            .handle { _, _ -> null }
            .thenCompose { connection.request("continue", json("threadId" to (body.int("threadId") ?: 0)), REQUEST_TIMEOUT_MS) }
            .exceptionally { error -> LOG.info("Cannot resume after the entry: ${errorText(error)}"); null }
    }

    private fun stoppedEvent(body: JsonObject) {
        val threadId = body.int("threadId")
        val reason = body.string("reason")
        stoppedThreadId = threadId
        val ranTo = lineBreakpoints.clearTemporary()
        val threads = connection.request("threads", null, REQUEST_TIMEOUT_MS).thenApply { answer ->
            answer.objects("threads").mapNotNull { thread -> thread.int("id")?.let { it to thread.string("name").orEmpty() } }
        }
        val top = if (threadId == null) CompletableFuture.completedFuture(emptyList()) else stackTrace(threadId, 0, FIRST_FRAMES)
        threads.thenCombine(top) { list, frames -> list to frames }.whenComplete { result, error ->
            if (error != null) return@whenComplete LOG.info("Cannot show the stop: ${errorText(error)}")
            val (list, frames) = result
            val active = threadId ?: list.firstOrNull()?.first
            val context = RustSuspendContext(this, list.ifEmpty { listOfNotNull(active?.let { it to "Thread $it" }) }, active, frames)
            if (reason == "exception" && threadId != null) printException(threadId)
            reached(context, body, frames.firstOrNull(), ranTo)
        }
    }

    /** A breakpoint of the IDE when the stop is one: the platform then logs, counts and decides whether to stay suspended. */
    private fun reached(context: RustSuspendContext, body: JsonObject, top: JsonObject?, ranTo: Boolean) {
        val reason = body.string("reason")
        val byId = body.getAsJsonArray("hitBreakpointIds")?.firstNotNullOfOrNull { id -> id.takeIf { it.isJsonPrimitive }?.asInt?.let(lineBreakpoints::find) }
        // no ids in the event: the breakpoint of the IDE at the line of the top frame
        val byLine = top?.takeIf { reason == "breakpoint" && !ranTo }?.let { frame ->
            frame.getAsJsonObject("source")?.string("path")?.let { lineBreakpoints.at(it, (frame.int("line") ?: 0) - 1) }
        }
        val breakpoint: XBreakpoint<*>? = byId ?: byLine ?: panicBreakpoints.first().takeIf { reason == "exception" || reason == "function breakpoint" }
        if (breakpoint == null) return session.positionReached(context)
        val expression = breakpoint.logExpressionObject?.expression?.takeIf { it.isNotBlank() }
        val logged = if (expression == null) CompletableFuture.completedFuture<String?>(null)
        else context.topFrameId?.let { frame -> evaluate(expression, frame, "watch").handle { answer, error -> answer?.string("result") ?: error?.let(::errorText) } }
            ?: CompletableFuture.completedFuture(null)
        logged.thenAccept { value -> if (!session.breakpointReached(breakpoint, value, context)) resume(context) }
    }

    /** A panic: CodeLLDB describes it in `exceptionInfo`. */
    private fun printException(threadId: Int) {
        connection.request("exceptionInfo", json("threadId" to threadId), REQUEST_TIMEOUT_MS).thenAccept { info ->
            val description = info.string("description") ?: info.string("exceptionId") ?: return@thenAccept
            print("Stopped at a panic: $description\n", ProcessOutputTypes.STDERR)
        }
    }

    private fun output(body: JsonObject) {
        val text = body.string("output") ?: return
        when (body.string("category")) {
            "telemetry" -> Unit
            "stderr" -> print(text, ProcessOutputTypes.STDERR)
            "stdout" -> print(text, ProcessOutputTypes.STDOUT)
            // the commands lldb-dap runs and their echo ("Running initCommands:", "(lldb) command source ..."): the log, unless an error
            "console" if adapterKind == RustDebugAdapterKind.LLDB_DAP && !text.contains("error", ignoreCase = true) -> LOG.info("lldb-dap: ${text.trim()}")
            // "Console is in 'commands' mode": about the debug console of VS Code, which the IDE does not have
            else -> if (!text.startsWith("Console is in '")) print(text, ProcessOutputTypes.SYSTEM)
        }
    }

    fun print(text: String, type: Key<*>) = handler.notifyTextAvailable(text, type)

    // --- requests the frames and values make ---

    fun stackTrace(threadId: Int, startFrame: Int, levels: Int): CompletableFuture<List<JsonObject>> =
        connection.request("stackTrace", json("threadId" to threadId, "startFrame" to startFrame, "levels" to levels), REQUEST_TIMEOUT_MS)
            .thenApply { it.objects("stackFrames") }

    /** [context]: `watch` for Evaluate and watches, `hover` for the editor; never `repl`, which the adapters may take as LLDB commands. */
    fun evaluate(expression: String, frameId: Int?, context: String): CompletableFuture<JsonObject> =
        connection.request("evaluate", json("expression" to expression, "frameId" to frameId, "context" to context), REQUEST_TIMEOUT_MS)

    // --- commands of the IDE ---

    private fun threadOf(context: XSuspendContext?): Int? = (context as? RustSuspendContext)?.activeThreadId ?: stoppedThreadId

    override fun resume(context: XSuspendContext?) {
        monitored?.paused = false
        connection.request("continue", json("threadId" to (threadOf(context) ?: 0)))
    }

    override fun startStepOver(context: XSuspendContext?) = step("next", context)
    override fun startStepInto(context: XSuspendContext?) = step("stepIn", context)
    override fun startStepOut(context: XSuspendContext?) = step("stepOut", context)

    private fun step(command: String, context: XSuspendContext?) {
        val threadId = threadOf(context) ?: return
        monitored?.paused = false
        connection.request(command, json("threadId" to threadId)).exceptionally { error ->
            session.reportMessage("${command.replaceFirstChar(Char::uppercase)} failed: ${errorText(error)}", MessageType.WARNING)
            null
        }
    }

    override fun startPausing() {
        connection.request("pause", json("threadId" to (stoppedThreadId ?: 0)))
    }

    /** Run to Cursor: DAP has no request of its own, so a breakpoint of one stop, sent with the others of its file. */
    override fun runToPosition(position: XSourcePosition, context: XSuspendContext?) {
        lineBreakpoints.runTo(position.file.path, position.line).thenRun { resume(context) }
    }

    override fun stopAsync(): Promise<Any> {
        AppExecutorUtil.getAppExecutorService().execute { shutdown(programGone = false) }
        return stopped
    }

    /** The end of the session, once, whoever asks: the build is killed, the program ended with `disconnect`, then the adapter stopped. */
    private fun shutdown(programGone: Boolean) {
        if (!shutdown.compareAndSet(false, true)) return
        try {
            build?.let { if (!OSProcessUtil.killProcessTree(it)) it.destroyForcibly() }
            val connection = dap
            if (!programGone && connection != null && !connection.isClosed) {
                runCatching { connection.request("disconnect", json("terminateDebuggee" to true), SHORT_TIMEOUT_MS).get() }
            }
        } finally {
            dap?.close()
            adapter?.stop()
            lineLocations?.let { runCatching { Files.deleteIfExists(it) } }
            handler.finish(exitCode)
            stopped.setResult(Unit)
        }
    }

    /** Stop of the IDE comes here through the process handler of the session; what is typed in the console goes to the program. */
    private inner class DebuggeeProcessHandler : ProcessHandler() {
        private val input = ProgramInput()

        override fun destroyProcessImpl() {
            AppExecutorUtil.getAppExecutorService().execute { shutdown(programGone = false) }
        }

        override fun detachProcessImpl() = destroyProcessImpl()
        override fun detachIsDefault(): Boolean = false
        override fun getProcessInput(): OutputStream = input

        fun finish(code: Int?) {
            if (!isProcessTerminated) notifyProcessTerminated(code ?: 0)
        }
    }

    /** The console takes the input stream once, when it is attached, before the adapter is there: kept until then. */
    private inner class ProgramInput : OutputStream() {
        private val buffer = ByteArrayOutputStream()

        override fun write(b: Int) = synchronized(buffer) { buffer.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) = synchronized(buffer) { buffer.write(b, off, len) }

        override fun flush() {
            val target = adapter?.programInput ?: return
            val bytes = synchronized(buffer) { buffer.toByteArray().also { buffer.reset() } }
            if (bytes.isEmpty()) return
            runCatching {
                target.write(bytes)
                target.flush()
            }
        }
    }

    companion object {
        const val REQUEST_TIMEOUT_MS = 30_000L
        private const val SHORT_TIMEOUT_MS = 3_000L
        const val FIRST_FRAMES = 20

        /** A connection that is closed from the start: what [connection] gives before the adapter has started. */
        private val NOT_STARTED = DapConnection(java.io.InputStream.nullInputStream(), OutputStream.nullOutputStream(), object : DapConnection.Listener {
            override fun event(event: String, body: JsonObject) {}
        }).apply { close() }

        fun errorText(error: Throwable): String {
            val cause = generateSequence(error) { it.cause }.firstOrNull { it is DapException || it is DapClosedException || it is TimeoutException } ?: error
            return when (cause) {
                is TimeoutException -> "The debugger has not answered in time"
                else -> cause.message ?: cause.javaClass.simpleName
            }
        }

        fun samePath(a: String, b: String): Boolean = FileUtil.pathsEqual(FileUtil.toSystemIndependentName(a), FileUtil.toSystemIndependentName(b))

        fun pathOf(fileUrl: String): String = FileUtil.toSystemDependentName(VfsUtilCore.urlToPath(fileUrl))

        /** Registry `rust.debugger.protocol.trace`: every DAP message of a session into `<logs>/rust-debugger/`. */
        fun newProtocolTrace(): Writer? {
            if (!runCatching { Registry.`is`("rust.debugger.protocol.trace") }.getOrDefault(false)) return null
            return runCatching {
                val dir = File(PathManager.getLogPath(), "rust-debugger").apply { mkdirs() }
                File(dir, "dap-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.log").bufferedWriter()
            }.getOrNull()
        }
    }
}
