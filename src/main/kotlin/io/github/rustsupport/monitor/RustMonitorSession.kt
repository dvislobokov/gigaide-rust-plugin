package io.github.rustsupport.monitor

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.BaseProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Tlhelp32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import io.github.rustsupport.run.CargoRunConfiguration
import io.github.rustsupport.settings.RustSettings
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** A run of our Cargo configurations: [pid] is the process the IDE started (cargo, mostly), [handler] stops it. */
class MonitorTarget(val pid: Long, val name: String, val handler: ProcessHandler, val startedMillis: Long = System.currentTimeMillis()) {
    val title: String get() = "$name ($pid)"
    override fun toString(): String = title
    override fun equals(other: Any?): Boolean = other is MonitorTarget && other.pid == pid
    override fun hashCode(): Int = pid.hashCode()
}

/**
 * Samples one [target] every second until its tree is gone or the session is disposed. The program behind cargo is picked again on
 * every tick ([RustProcessTree.pick]): a program that restarts, or the next test binary of `cargo test`, is followed. Callbacks run on
 * a pooled thread.
 */
class RustMonitorSession(val target: MonitorTarget, private val onSample: (MonitorSample) -> Unit, private val onEnd: () -> Unit) : Disposable {
    private var task: ScheduledFuture<*>? = null
    private var previousCpu: Duration? = null
    private var previousTime = 0L
    private var previousProgram: Long? = null
    @Volatile private var disposed = false

    fun start() {
        task = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
            try {
                tick()
            } catch (e: Exception) {
                LOG.debug("Rust Monitor sample of ${target.title}", e)
            }
        }, 0, 1, TimeUnit.SECONDS)
    }

    private fun tick() {
        if (disposed) return
        val tree = ProcessSampler.tree(target.pid)
        if (tree.isEmpty()) {
            dispose()
            onEnd()
            return
        }
        val entries = tree.map { process ->
            val info = process.info()
            ProcessEntry(
                process.pid(), process.parent().map { it.pid() }.orElse(null), info.command().orElse(""),
                info.commandLine().orElse(""), info.startInstant().map { it.toEpochMilli() }.orElse(0L),
            )
        }
        val picked = RustProcessTree.pick(target.pid, entries)
        val measured = tree.filter { it.pid() in picked.pids }.ifEmpty { tree }
        val usage = ProcessSampler.sample(measured)
        val programPid = picked.program?.pid ?: target.pid
        // a new program: its CPU time starts from zero, the difference to the old one means nothing
        if (programPid != previousProgram) previousCpu = null
        previousProgram = programPid
        val now = System.nanoTime()
        val cpu = previousCpu?.let { ProcessSampler.cpuPercent(it, usage.cpuTime, now - previousTime) } ?: 0.0
        previousCpu = usage.cpuTime
        previousTime = now
        val threads = if (SystemInfo.isWindows) WindowsThreads.count(measured.map { it.pid() }.toSet()) else usage.threads
        val program = picked.program ?: entries.first { it.pid == target.pid }
        if (disposed) return
        onSample(MonitorSample(
            System.currentTimeMillis(), programPid, RustProcessTree.executableName(program.name).ifEmpty { target.name }, cpu,
            usage.workingSetBytes, usage.privateBytes, threads, usage.processes, program.startMillis, picked.isWholeTree,
        ))
    }

    override fun dispose() {
        disposed = true
        task?.cancel(false)
    }

    private companion object {
        val LOG = logger<RustMonitorSession>()
    }
}

/**
 * Threads of processes on Windows: the JVM does not report them and the memory counters of psapi have none; the Toolhelp snapshot of
 * processes has `cntThreads` for each. Only the entries of [pids] (our run) are read; nothing else of the snapshot is kept.
 */
internal object WindowsThreads {
    fun count(pids: Set<Long>): Int? = try {
        val snapshot = Kernel32.INSTANCE.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPPROCESS, WinDef.DWORD(0))
        if (snapshot == null || snapshot == WinNT.INVALID_HANDLE_VALUE) null else try {
            val entry = Tlhelp32.PROCESSENTRY32.ByReference()
            var total = 0
            var found = false
            var more = Kernel32.INSTANCE.Process32First(snapshot, entry)
            while (more) {
                if (entry.th32ProcessID.toLong() in pids) {
                    total += entry.cntThreads.toInt()
                    found = true
                }
                more = Kernel32.INSTANCE.Process32Next(snapshot, entry)
            }
            if (found) total else null
        } finally {
            Kernel32.INSTANCE.CloseHandle(snapshot)
        }
    } catch (_: Throwable) {
        null
    }
}

/** The processes of our run configurations that are alive, the newest first. */
@Service(Service.Level.PROJECT)
class RunningRustProcesses {
    private val targets = CopyOnWriteArrayList<MonitorTarget>()
    private val listeners = CopyOnWriteArrayList<(MonitorTarget?) -> Unit>()

    fun targets(): List<MonitorTarget> = targets.toList()

    fun find(handler: ProcessHandler?): MonitorTarget? = handler?.let { h -> targets.firstOrNull { it.handler === h } }

    /** [listener] gets a started (or asked for) process, or null when one has exited. */
    fun subscribe(parent: Disposable, listener: (MonitorTarget?) -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    /** Open Monitor from a Run tab: the monitor switches to [target]. */
    fun select(target: MonitorTarget) = listeners.forEach { it(target) }

    fun started(name: String, handler: ProcessHandler) {
        // the debugger's handler stands for CodeLLDB and has no process of its own: only plain runs are monitored
        val pid = runCatching { (handler as? BaseProcessHandler<*>)?.process?.pid() }.getOrNull() ?: return
        if (handler.isProcessTerminated) return
        val target = MonitorTarget(pid, name, handler)
        targets.add(0, target)
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                targets.remove(target)
                listeners.forEach { it(null) }
            }
        })
        listeners.forEach { it(target) }
    }

    companion object {
        fun getInstance(project: Project): RunningRustProcesses = project.service()
    }
}

/** Registers the processes of Cargo run configurations; opens the monitor when the setting asks for it. */
class RustProcessStartListener(private val project: Project) : ExecutionListener {
    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        val configuration = env.runProfile as? CargoRunConfiguration ?: return
        RunningRustProcesses.getInstance(project).started(configuration.name, handler)
        if (RustSettings.getInstance().openMonitorOnRun && RunningRustProcesses.getInstance(project).find(handler) != null) {
            ApplicationManager.getApplication().invokeLater({
                ToolWindowManager.getInstance(project).getToolWindow(RustMonitorToolWindowFactory.ID)?.show()
            }, project.disposed)
        }
    }
}
