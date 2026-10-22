package io.github.rustsupport.debugger

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.AsyncProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import io.github.rustsupport.run.RustDebuggableConfiguration
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise

private val LOG = logger<RustDebugRunner>()

/**
 * Debug of a configuration that can be debugged ([RustDebuggableConfiguration]): the session builds the program with cargo and
 * starts it under CodeLLDB or lldb-dap ([RustDebugAdapters]), see [RustDebugProcess]. Without an adapter nothing starts: a balloon offers
 * the download of CodeLLDB or an existing adapter, and starts the debug again once there is one.
 */
class RustDebugRunner : AsyncProgramRunner<RunnerSettings>() {
    override fun getRunnerId(): String = "RustDebugRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == DefaultDebugExecutor.EXECUTOR_ID && profile is RustDebuggableConfiguration &&
            runCatching { profile.debugLaunchSpec() != null }.getOrDefault(false)

    override fun execute(environment: ExecutionEnvironment, state: RunProfileState): Promise<RunContentDescriptor?> {
        val result = AsyncPromise<RunContentDescriptor?>()
        val project = environment.project
        val configuration = environment.runProfile as? RustDebuggableConfiguration
            ?: return result.apply { setError(ExecutionException("${environment.runProfile.name} cannot be debugged")) }
        val spec = configuration.debugLaunchSpec()
            ?: return result.apply { setError(ExecutionException("${configuration.name} cannot be debugged: it does not build a program")) }
        ApplicationManager.getApplication().executeOnPooledThread {
            val adapter = runCatching { RustDebugAdapters.current() }.getOrNull()
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater result.setResult(null)
                if (adapter == null) {
                    // nothing is downloaded by itself: the user chooses, then the debug starts again
                    CodeLldbInstaller.notifyMissing(project) { if (!project.isDisposed) ExecutionUtil.restart(environment) }
                    return@invokeLater result.setResult(null)
                }
                try {
                    LOG.info("Debug of ${configuration.name} with ${adapter.description} (${adapter.path})")
                    val starter = object : XDebugProcessStarter() {
                        override fun start(session: XDebugSession): XDebugProcess = RustDebugProcess(session, spec, configuration.name, adapter)
                    }
                    // The session builder hands out the descriptor in both modes: XDebugSession.getRunContentDescriptor logs a SEVERE in the
                    // split debugger, and GIGA IDE, whose split frontend builds no UI, needs the descriptor to show the session at all.
                    val started = XDebuggerManager.getInstance(project).newSessionBuilder(starter).environment(environment).startSession()
                    result.setResult(started.runContentDescriptor)
                } catch (e: Exception) {
                    result.setError(e)
                }
            }, ModalityState.nonModal())
        }
        return result
    }
}
