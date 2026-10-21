package io.github.rustsupport.run

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir

/** Starts a cargo command for the whole project from code, in a Run console of its own. EDT. */
object CargoLauncher {
    fun run(project: Project, name: String, command: String) {
        val settings = RunManager.getInstance(project).createConfiguration(name, CargoConfigurationType.instance.factory)
        (settings.configuration as CargoRunConfiguration).options.command = command
        settings.isActivateToolWindowBeforeRun = true
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
    }
}

/** Tools | Rust and Build: `cargo build`, `cargo check`, `cargo clippy` of the project (its workspace), the output linked to the sources. */
abstract class CargoCommandAction(private val command: String) : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        // the Build menu of a project without Rust stays as it was; a VFS look at two levels, cheap enough for an update
        val base = e.project?.guessProjectDir()
        e.presentation.isEnabledAndVisible = base != null && (base.findChild(MANIFEST) != null || base.children.any { it.isDirectory && it.findChild(MANIFEST) != null })
    }

    private companion object {
        const val MANIFEST = "Cargo.toml"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        CargoLauncher.run(project, "cargo $command", command)
    }
}

class CargoBuildAction : CargoCommandAction("build")
class CargoCheckAction : CargoCommandAction("check")
class CargoClippyAction : CargoCommandAction("clippy")
