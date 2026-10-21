package io.github.rustsupport.toolchain

import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import io.github.rustsupport.settings.RustSettings
import io.github.rustsupport.settings.RustSettingsConfigurable
import java.util.concurrent.atomic.AtomicBoolean

/** The balloons about a missing toolchain, with the buttons that fix it. */
object RustToolchainInstaller {
    const val NOTIFICATION_GROUP = "Rust"
    private const val RUSTUP_URL = "https://rustup.rs"
    private val offered = AtomicBoolean()

    /** Once per session: rust-analyzer is missing. Installs it (with the std sources it navigates into) by rustup when there is one. */
    fun offerRustAnalyzer(project: Project) {
        if (!offered.compareAndSet(false, true)) return
        val group = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
        val notification = if (RustTool.RUSTUP.find() != null) {
            group.createNotification("rust-analyzer is not installed", "Completion, navigation and errors in Rust files need it.", NotificationType.WARNING)
                .addAction(NotificationAction.createSimpleExpiring("Install with rustup") { installComponents(project, "rust-analyzer", "rust-src") })
        } else {
            group.createNotification("Rust toolchain is not found", "Install Rust with rustup, then press Retry.", NotificationType.WARNING)
                .addAction(NotificationAction.createSimple("Open rustup.rs") { BrowserUtil.browse(RUSTUP_URL) })
                .addAction(NotificationAction.createSimpleExpiring("Retry") { offered.set(false); RustSettings.fireChanged() })
        }
        notification
            .addAction(NotificationAction.createSimple("Settings...") { ShowSettingsUtil.getInstance().showSettingsDialog(project, RustSettingsConfigurable::class.java) })
            .notify(project)
    }

    private val linkerOffered = AtomicBoolean()

    /**
     * Once per session: rustc could not link a build script or a proc macro with `link.exe`. On Windows that is a Visual Studio install
     * without the C++ workload or the Windows SDK, after which rustc takes whatever `link.exe` is on PATH (Git's coreutils one, seen live).
     */
    fun offerMsvcLinker(project: Project) {
        if (!linkerOffered.compareAndSet(false, true)) return
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "Build scripts and proc macros cannot be built",
                "rustc cannot link with the MSVC linker: install the <b>Desktop development with C++</b> workload (MSVC and the Windows SDK) " +
                    "in Visual Studio Installer. Until then rust-analyzer does not expand derives and other proc macros.",
                NotificationType.ERROR,
            )
            .apply { Msvc.installerAction()?.let(::addAction) }
            .addAction(NotificationAction.createSimple("Build Tools Web Page") { BrowserUtil.browse(BUILD_TOOLS_URL) })
            .notify(project)
    }

    /** The toolchain check has told about rust-analyzer or MSVC in its own balloon: these are not offered a second time this session. */
    fun markRustAnalyzerOffered() = offered.set(true)
    fun markMsvcOffered() = linkerOffered.set(true)

    private const val BUILD_TOOLS_URL = "https://visualstudio.microsoft.com/visual-cpp-build-tools/"

    /** `rustup component add <components>` in the background, then the language server restarts. */
    fun installComponents(project: Project, vararg components: String) {
        val rustup = RustTool.RUSTUP.find() ?: return BrowserUtil.browse(RUSTUP_URL)
        object : Task.Backgroundable(project, "Installing ${components.joinToString()}", true) {
            override fun run(indicator: ProgressIndicator) {
                val output = CapturingProcessHandler(RustToolchain.commandLine(rustup, null, "component", "add", *components))
                    .runProcessWithProgressIndicator(indicator)
                val group = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                if (output.exitCode == 0) {
                    group.createNotification("Installed: ${components.joinToString()}", NotificationType.INFORMATION).notify(project)
                    offered.set(false)
                    RustSettings.fireChanged()
                } else {
                    group.createNotification("rustup component add failed", output.stderr.ifBlank { output.stdout }.take(2000), NotificationType.ERROR)
                        .notify(project)
                }
            }
        }.queue()
    }
}
