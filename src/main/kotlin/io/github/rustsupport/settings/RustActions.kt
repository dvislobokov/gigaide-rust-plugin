package io.github.rustsupport.settings

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import io.github.rustsupport.toolchain.RustToolchainInstaller

object RustComponents {
    /** rust-analyzer, the std sources it navigates into, rustfmt and clippy; [project] null: the first open project hosts the progress. */
    fun installAll(project: Project?) {
        val host = project ?: ProjectManager.getInstance().openProjects.firstOrNull() ?: return
        RustToolchainInstaller.installComponents(host, "rust-analyzer", "rust-src", "rustfmt", "clippy")
    }
}

class OpenRustSettingsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) = ShowSettingsUtil.getInstance().showSettingsDialog(e.project, RustSettingsConfigurable::class.java)
}

/** Tools | Rust | Install or Update Components. */
class InstallRustComponentsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
    override fun actionPerformed(e: AnActionEvent) = RustComponents.installAll(e.project)
}
