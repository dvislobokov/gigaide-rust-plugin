package io.github.rustsupport.toolchain

import com.intellij.execution.util.ExecUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.StringUtil
import io.github.rustsupport.settings.RustSettingsConfigurable
import java.io.File
import java.nio.file.Path

enum class RustCheckLevel { OK, INFO, WARNING, ERROR }

/**
 * One line of the toolchain check: a tool or a part of the toolchain, how it is, and what fixes it.
 * @param component the rustup component that installs what is missing.
 * @param needsMsvc the fix is the C++ workload of Visual Studio.
 */
data class RustCheckItem(
    val name: String,
    val level: RustCheckLevel,
    val summary: String,
    val detail: String? = null,
    val component: String? = null,
    val needsMsvc: Boolean = false,
)

/** What `<tool> --version` answered. */
data class RustProbe(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Everything the plugin needs from the machine, looked at as the plugin would use it: the tools where the plugin finds them (settings,
 * PATH, ~/.cargo/bin) and whether they run - a rustup proxy without its component is there and fails - the std sources rust-analyzer
 * navigates into, and on Windows the MSVC linker rustc needs for build scripts and proc macros.
 */
object RustToolchainCheck {
    private enum class Need { REQUIRED, RECOMMENDED, OPTIONAL }

    private val TOOLS = listOf(
        RustTool.CARGO to Need.REQUIRED,
        RustTool.RUSTC to Need.REQUIRED,
        RustTool.RUST_ANALYZER to Need.REQUIRED,
        RustTool.RUSTFMT to Need.RECOMMENDED,
        RustTool.CLIPPY to Need.RECOMMENDED,
        RustTool.RUSTUP to Need.OPTIONAL,
    )

    /** Runs the tools: blocks for a few seconds, call off the EDT. */
    fun run(): List<RustCheckItem> = buildList {
        for ((tool, need) in TOOLS) {
            val location = tool.locate()
            add(toolItem(tool, need == Need.REQUIRED, need == Need.OPTIONAL, location, location?.let { probe(it.path, "--version") }))
        }
        val rustc = RustTool.RUSTC.find()
        val sysroot = rustc?.let { probe(it.toPath(), "--print", "sysroot") }?.takeIf { it.exitCode == 0 }?.stdout?.trim()
        if (rustc != null) add(rustSrcItem(sysroot?.let { Path.of(it) }))
        val host = rustc?.let { probe(it.toPath(), "-vV") }?.stdout?.lineSequence()?.firstOrNull { it.startsWith("host:") }?.substringAfter(':')?.trim()
        if (SystemInfo.isWindows && host?.endsWith("-msvc") == true) add(msvcItem(Msvc.detect(), Msvc.firstLinkOnPath(RustToolchain.pathEnv())))
    }

    fun toolItem(tool: RustTool, required: Boolean, optional: Boolean, location: RustToolLocation?, probe: RustProbe?): RustCheckItem {
        val name = tool.executable
        if (location == null || probe == null) {
            val level = when {
                required -> RustCheckLevel.ERROR
                optional -> RustCheckLevel.INFO
                else -> RustCheckLevel.WARNING
            }
            val fix = tool.component?.let { "rustup component add $it" } ?: if (tool == RustTool.RUSTUP) "https://rustup.rs" else "install Rust with rustup"
            return RustCheckItem(name, level, "not found", "Not in the settings, on PATH or in ~/.cargo/bin; $fix", tool.component)
        }
        if (probe.exitCode != 0) {
            val missingComponent = isMissingComponent(probe.stderr)
            return RustCheckItem(
                name, if (optional) RustCheckLevel.INFO else if (required) RustCheckLevel.ERROR else RustCheckLevel.WARNING,
                if (missingComponent) "rustup proxy without the component" else "does not run (exit code ${probe.exitCode})",
                StringUtil.shortenTextWithEllipsis(probe.stderr.trim().lineSequence().firstOrNull().orEmpty(), 200, 0) + " (${location.path})",
                tool.component,
            )
        }
        val version = probe.stdout.lineSequence().firstOrNull()?.trim().orEmpty()
        val where = "${location.path} (${location.source.text})"
        return RustCheckItem(name, RustCheckLevel.OK, version, where)
    }

    /** What a rustup proxy prints for a tool whose component is not installed: "the 'x' component ... is not available/installed", "Unknown binary". */
    fun isMissingComponent(stderr: String): Boolean =
        stderr.contains("component", ignoreCase = true) && (stderr.contains("not available") || stderr.contains("not installed")) ||
            stderr.contains("Unknown binary", ignoreCase = true)

    fun rustSrcItem(sysroot: Path?): RustCheckItem {
        val lib = sysroot?.resolve("lib/rustlib/src/rust/library/std/src/lib.rs")
        return if (lib != null && File(lib.toString()).isFile) {
            RustCheckItem("rust-src", RustCheckLevel.OK, "installed", "Sources of std for navigation and completion")
        } else {
            RustCheckItem("rust-src", RustCheckLevel.WARNING, "not installed", "rust-analyzer cannot go into std, core and alloc; rustup component add rust-src", "rust-src")
        }
    }

    fun msvcItem(msvc: Msvc.State, linkOnPath: Path?): RustCheckItem {
        val foreignLinker = linkOnPath?.takeIf { !it.toString().contains("Microsoft Visual Studio", ignoreCase = true) }
        val shadow = foreignLinker?.let { " A link.exe that is not Microsoft's comes first on PATH ($it): rustc takes it when MSVC is incomplete." }.orEmpty()
        return when {
            msvc.vcTools && msvc.complete && msvc.windowsSdk ->
                RustCheckItem("MSVC linker", RustCheckLevel.OK, "Visual Studio C++ tools and Windows SDK", msvc.installation)
            else -> RustCheckItem(
                "MSVC linker", RustCheckLevel.ERROR,
                listOfNotNull(
                    "no C++ build tools".takeIf { !msvc.vcTools },
                    "installation incomplete".takeIf { msvc.vcTools && !msvc.complete },
                    "no Windows SDK".takeIf { !msvc.windowsSdk },
                ).joinToString(", "),
                "Build scripts and proc macros cannot be linked: install \"Desktop development with C++\" in Visual Studio Installer.$shadow",
                needsMsvc = true,
            )
        }
    }

    private fun probe(executable: Path, vararg args: String): RustProbe? = runCatching {
        val output = ExecUtil.execAndGetOutput(RustToolchain.commandLine(executable.toFile(), null, *args), 15_000)
        RustProbe(output.exitCode, output.stdout, output.stderr)
    }.getOrNull()

    /** The problems as one balloon, with the buttons that fix them; nothing when all is well, unless [always]. */
    fun notify(project: Project, items: List<RustCheckItem>, always: Boolean) {
        val problems = items.filter { it.level >= RustCheckLevel.WARNING }
        val group = NotificationGroupManager.getInstance().getNotificationGroup(RustToolchainInstaller.NOTIFICATION_GROUP)
        if (problems.isEmpty()) {
            if (always) group.createNotification("Rust toolchain is fine", items.joinToString("<br>") { "${it.name}: ${it.summary}" }, NotificationType.INFORMATION).notify(project)
            return
        }
        val type = if (problems.any { it.level == RustCheckLevel.ERROR }) NotificationType.ERROR else NotificationType.WARNING
        val text = problems.joinToString("<br>") { "<b>${it.name}</b>: ${it.summary}" }
        val components = problems.mapNotNull { it.component }.distinct()
        val notification = group.createNotification("Rust toolchain: ${problems.size} " + StringUtil.pluralize("problem", problems.size), text, type)
        if (components.isNotEmpty() && RustTool.RUSTUP.find() != null) {
            notification.addAction(NotificationAction.createSimpleExpiring("Install ${components.joinToString()}") {
                RustToolchainInstaller.installComponents(project, *components.toTypedArray())
            })
        }
        if (problems.any { it.needsMsvc }) {
            Msvc.installerAction()?.let(notification::addAction)
            RustToolchainInstaller.markMsvcOffered()
        }
        notification.addAction(NotificationAction.createSimple("Details...") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, RustSettingsConfigurable::class.java)
        })
        // the balloon names rust-analyzer when it is missing: the language server does not offer it a second time
        if (problems.any { it.name == RustTool.RUST_ANALYZER.executable }) RustToolchainInstaller.markRustAnalyzerOffered()
        notification.notify(project)
    }

    /** The items as rows of an HTML table for the settings page. */
    fun html(items: List<RustCheckItem>): String = buildString {
        append("<html><table>")
        for (item in items) {
            val (mark, color) = when (item.level) {
                RustCheckLevel.OK -> "✓" to "#4CAF50"
                RustCheckLevel.INFO -> "•" to "#8C8C8C"
                RustCheckLevel.WARNING -> "!" to "#E0A030"
                RustCheckLevel.ERROR -> "✗" to "#E05555"
            }
            append("<tr><td><font color='$color'><b>$mark</b></font></td><td><b>").append(StringUtil.escapeXmlEntities(item.name)).append("</b></td><td>")
                .append(StringUtil.escapeXmlEntities(item.summary))
            item.detail?.let { append("<br><font color='#8C8C8C'>").append(StringUtil.escapeXmlEntities(it)).append("</font>") }
            append("</td></tr>")
        }
        append("</table></html>")
    }
}

/** Visual Studio's C++ tools and the Windows SDK, as vswhere and the disk tell. */
object Msvc {
    data class State(val vcTools: Boolean, val complete: Boolean, val windowsSdk: Boolean, val installation: String?)

    private val programFilesX86: String get() = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"
    private val installerDir: File get() = File(programFilesX86, "Microsoft Visual Studio\\Installer")

    fun detect(): State {
        val vswhere = File(installerDir, "vswhere.exe")
        var installation: String? = null
        var complete = false
        if (vswhere.isFile) runCatching {
            val output = ExecUtil.execAndGetOutput(
                com.intellij.execution.configurations.GeneralCommandLine(
                    vswhere.path, "-products", "*", "-requires", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64", "-property", "installationPath",
                ), 10_000,
            )
            installation = output.stdout.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            if (installation != null) {
                val state = ExecUtil.execAndGetOutput(
                    com.intellij.execution.configurations.GeneralCommandLine(vswhere.path, "-path", installation, "-property", "isComplete"), 10_000,
                )
                complete = state.stdout.trim() == "1"
            }
        }
        return State(installation != null, complete, hasWindowsSdk(File(programFilesX86, "Windows Kits\\10\\Lib")), installation)
    }

    /** A Windows 10/11 SDK with the x64 import libraries rustc links against. */
    fun hasWindowsSdk(libRoot: File): Boolean = libRoot.listFiles()?.any { File(it, "um\\x64\\kernel32.lib").isFile } == true

    fun firstLinkOnPath(path: String?): Path? = path?.split(File.pathSeparatorChar)?.filter { it.isNotBlank() }
        ?.map { Path.of(it.trim('"'), "link.exe") }?.firstOrNull { File(it.toString()).isFile }

    fun installerAction(): NotificationAction? {
        val setup = File(installerDir, "setup.exe").takeIf { it.isFile } ?: return null
        return NotificationAction.createSimple("Open Visual Studio Installer") { runCatching { ProcessBuilder(setup.path).start() } }
    }
}

/** At the opening of a Rust project: the toolchain is looked at once, a balloon tells what is missing. */
class RustToolchainCheckActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!RustProjectDetector.hasRust(project.basePath?.let { Path.of(it) })) return
        RustToolchainCheck.notify(project, RustToolchainCheck.run(), always = false)
    }
}

/** Tools | Rust | Check Toolchain. */
class CheckRustToolchainAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        object : Task.Backgroundable(project, "Checking the Rust toolchain", false) {
            override fun run(indicator: ProgressIndicator) = RustToolchainCheck.notify(project, RustToolchainCheck.run(), always = true)
        }.queue()
    }
}
