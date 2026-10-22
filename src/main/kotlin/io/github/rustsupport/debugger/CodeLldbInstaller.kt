package io.github.rustsupport.debugger

import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.NioFiles
import com.intellij.util.io.HttpRequests
import io.github.rustsupport.toolchain.RustToolchainInstaller
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

private val LOG = logger<CodeLldbInstaller>()

/**
 * CodeLLDB is never downloaded on its own: Debug without it offers the download or the choice of an existing codelldb, and the download
 * starts only from that button (or the one in Settings), after a confirmation that names the version, the URL and the size. Through
 * [HttpRequests] (the proxy settings of the IDE), in a task that can be cancelled; the VSIX is unpacked here, by `java.util.zip`.
 */
object CodeLldbInstaller {
    /** Debug was started and no adapter is found: a balloon with the ways out; [onReady] starts the debug again. */
    fun notifyMissing(project: Project, onReady: () -> Unit) {
        val settings = ShowSettingsUtil.getInstance()
        if (RustDebuggerSettings.getInstance().adapter == RustDebugAdapterChoice.LLDB_DAP) {
            group().createNotification("lldb-dap is not found", "The debugger is set to lldb-dap, and it is not at the path of the settings, on PATH or in Xcode or LLVM.", NotificationType.WARNING)
                .addAction(NotificationAction.createSimpleExpiring("Choose lldb-dap...") { chooseLldbDap(project, onReady) })
                .addAction(NotificationAction.createSimple("Settings...") { settings.showSettingsDialog(project, RustDebuggerConfigurable::class.java) })
                .notify(project)
            return
        }
        val asset = CodeLldbRelease.currentAsset()
        val text = if (asset != null) "Debugging Rust needs the CodeLLDB debug adapter or lldb-dap of LLVM. Neither was found in the settings, in VS Code, Cursor or VSCodium extensions, among the downloads, on PATH or in Xcode or LLVM."
        else "Debugging Rust needs a debug adapter, and the releases of CodeLLDB have no build for this platform: choose an existing codelldb or lldb-dap."
        val notification = group().createNotification("No debug adapter is found", text, NotificationType.WARNING)
        if (asset != null) notification.addAction(NotificationAction.createSimpleExpiring("Download CodeLLDB...") { offerDownload(project, onReady) })
        notification
            .addAction(NotificationAction.createSimpleExpiring("Install CodeLLDB from VSIX...") { installFromVsix(project, onReady) })
            .addAction(NotificationAction.createSimpleExpiring("Choose existing codelldb...") { chooseExisting(project, onReady) })
            .addAction(NotificationAction.createSimpleExpiring("Choose lldb-dap...") { chooseLldbDap(project, onReady) })
            .addAction(NotificationAction.createSimple("Settings...") { settings.showSettingsDialog(project, RustDebuggerConfigurable::class.java) })
            .notify(project)
    }

    /** An lldb-dap chosen by hand; a debugger set to CodeLLDB is switched to it, as that is what the user has just picked. */
    fun chooseLldbDap(project: Project?, onReady: (() -> Unit)?) {
        val descriptor = FileChooserDescriptorFactory.singleFileOrDir().withTitle("Choose lldb-dap")
            .withDescription("The lldb-dap executable of Xcode or LLVM, or its folder")
        FileChooser.chooseFile(descriptor, project, null) { file ->
            val location = LldbDapLocator.default().fromSettings(file.path)
            if (location == null) {
                Messages.showErrorDialog(project, "No lldb-dap executable in ${file.presentableUrl}", "Choose lldb-dap")
                return@chooseFile
            }
            val settings = RustDebuggerSettings.getInstance()
            settings.lldbDapPath = location.path.toString()
            if (settings.adapter == RustDebugAdapterChoice.CODELLDB) settings.adapter = RustDebugAdapterChoice.LLDB_DAP
            onReady?.invoke()
        }
    }

    /** The confirmation, then the download in the background. On the EDT. */
    fun offerDownload(project: Project?, onReady: (() -> Unit)?) {
        val asset = CodeLldbRelease.currentAsset() ?: run {
            Messages.showErrorDialog(project, "CodeLLDB has no release for this platform (${SystemInfo.OS_NAME} ${System.getProperty("os.arch")}).", "Download CodeLLDB")
            return
        }
        val url = CodeLldbRelease.downloadUrl(asset)
        val target = CodeLldbLocator.downloadRoot().resolve(CodeLldbRelease.VERSION)
        val answer = Messages.showOkCancelDialog(
            project,
            "Download CodeLLDB ${CodeLldbRelease.VERSION} (${asset.id}, about ${asset.sizeMb} MB) from GitHub releases:\n$url\n\nIt is unpacked into\n$target",
            "Download CodeLLDB", "Download", Messages.getCancelButton(), Messages.getQuestionIcon(),
        )
        if (answer == Messages.OK) download(project, asset, onReady)
    }

    fun chooseExisting(project: Project?, onReady: (() -> Unit)?) {
        val descriptor = FileChooserDescriptorFactory.singleFileOrDir().withTitle("Choose codelldb")
            .withDescription("The codelldb executable, or the folder of the CodeLLDB extension")
        FileChooser.chooseFile(descriptor, project, null) { file ->
            val installation = CodeLldbLocator.default().fromSettings(file.path)
            if (installation == null) {
                Messages.showErrorDialog(project, "No codelldb executable in ${file.presentableUrl}", "Choose codelldb")
                return@chooseFile
            }
            RustDebuggerSettings.getInstance().codelldbPath = installation.adapter.toString()
            onReady?.invoke()
        }
    }

    /**
     * A VSIX the user has (the Marketplace, Open VSX, a mirror of the releases inside a closed network): checked, then unpacked as a
     * download is, with no network at all. A version other than [CodeLldbRelease.VERSION] goes into the settings, as only that one is
     * looked for among the downloads.
     */
    fun installFromVsix(project: Project?, onReady: (() -> Unit)?) {
        val descriptor = FileChooserDescriptorFactory.singleFile().withExtensionFilter("VSIX", "vsix").withTitle("Install CodeLLDB from VSIX")
            .withDescription("The VSIX of this platform: ${CodeLldbRelease.currentAsset()?.fileName ?: "CodeLLDB has no release for it"}")
        FileChooser.chooseFile(descriptor, project, null) { file ->
            val vsix = Path.of(file.path)
            object : Task.Backgroundable(project, "Installing CodeLLDB from ${file.name}", true) {
                override fun run(indicator: ProgressIndicator) {
                    val root = CodeLldbLocator.downloadRoot()
                    var staging: Path? = null
                    try {
                        val contents = inspectVsix(vsix)
                        require(contents.hasAdapter) { "${file.name} has no extension/adapter/${CodeLldbLocator.adapterName()}: a VSIX of another platform, or not CodeLLDB" }
                        val version = contents.version ?: "vsix"
                        staging = root.resolve("$version.tmp")
                        Files.createDirectories(root)
                        NioFiles.deleteRecursively(staging)
                        unpack(vsix, staging, indicator, version)
                        val target = root.resolve(version)
                        NioFiles.deleteRecursively(target)
                        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
                        LOG.info("CodeLLDB $version installed from $vsix into $target")
                        val adapter = target.resolve("adapter").resolve(CodeLldbLocator.adapterName())
                        ApplicationManager.getApplication().invokeLater({
                            if (version != CodeLldbRelease.VERSION) RustDebuggerSettings.getInstance().codelldbPath = adapter.toString()
                            onReady?.invoke()
                        }, ModalityState.any())
                        group().createNotification("CodeLLDB $version is installed", target.toString(), NotificationType.INFORMATION).notify(project)
                    } catch (e: ProcessCanceledException) {
                        throw e
                    } catch (e: Exception) {
                        LOG.warn("Cannot install CodeLLDB from $vsix", e)
                        group().createNotification("CodeLLDB has not been installed", (e.message ?: e.javaClass.simpleName).take(500), NotificationType.ERROR).notify(project)
                    } finally {
                        staging?.let { runCatching { NioFiles.deleteRecursively(it) } }
                    }
                }
            }.queue()
        }
    }

    data class VsixContents(val version: String?, val hasAdapter: Boolean)

    /** The version of `extension/package.json`, and whether the adapter of this OS is inside (a VSIX of another platform has the other one). */
    fun inspectVsix(vsix: Path, windows: Boolean = SystemInfo.isWindows): VsixContents = ZipFile(vsix.toFile()).use { zip ->
        val version = zip.getEntry("extension/package.json")?.let { entry ->
            runCatching { JsonParser.parseString(zip.getInputStream(entry).use { String(it.readAllBytes(), Charsets.UTF_8) }).asJsonObject.get("version")?.asString }.getOrNull()
        }?.takeIf { VERSION_TEXT.matches(it) }
        VsixContents(version, zip.getEntry("extension/adapter/${CodeLldbLocator.adapterName(windows)}") != null)
    }

    private val VERSION_TEXT = Regex("""\d+(\.\d+)*""")

    private fun download(project: Project?, asset: CodeLldbRelease.Asset, onReady: (() -> Unit)?) {
        object : Task.Backgroundable(project, "Downloading CodeLLDB ${CodeLldbRelease.VERSION}", true) {
            override fun run(indicator: ProgressIndicator) {
                val root = CodeLldbLocator.downloadRoot()
                val vsix = root.resolve("codelldb-${CodeLldbRelease.VERSION}-${asset.id}.vsix.part")
                val staging = root.resolve("${CodeLldbRelease.VERSION}.tmp")
                try {
                    Files.createDirectories(root)
                    indicator.text = "Downloading ${asset.fileName}"
                    HttpRequests.request(CodeLldbRelease.downloadUrl(asset)).productNameAsUserAgent().saveToFile(vsix.toFile(), indicator)
                    indicator.checkCanceled()
                    indicator.text = "Unpacking CodeLLDB"
                    indicator.isIndeterminate = true
                    NioFiles.deleteRecursively(staging)
                    unpack(vsix, staging, indicator)
                    val target = root.resolve(CodeLldbRelease.VERSION)
                    NioFiles.deleteRecursively(target)
                    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
                    LOG.info("CodeLLDB ${CodeLldbRelease.VERSION} installed into $target")
                    group().createNotification("CodeLLDB ${CodeLldbRelease.VERSION} is installed", target.toString(), NotificationType.INFORMATION).notify(project)
                    if (onReady != null) ApplicationManager.getApplication().invokeLater(onReady, ModalityState.nonModal())
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("Cannot download CodeLLDB", e)
                    group().createNotification("CodeLLDB has not been downloaded", (e.message ?: e.javaClass.simpleName).take(500), NotificationType.ERROR)
                        .addAction(NotificationAction.createSimpleExpiring("Choose existing codelldb...") { chooseExisting(project, onReady) })
                        .addAction(NotificationAction.createSimple("Open GitHub Releases") { BrowserUtil.browse(CodeLldbRelease.REPOSITORY_URL) })
                        .notify(project)
                } finally {
                    runCatching { Files.deleteIfExists(vsix) }
                    runCatching { NioFiles.deleteRecursively(staging) }
                }
            }
        }.queue()
    }

    /**
     * The parts of the VSIX the adapter needs (see [CodeLldbRelease.unpackedPath]) into [target], with the marker of a complete install
     * last. A VSIX keeps no Unix permissions, so the binaries are made executable here.
     */
    fun unpack(vsix: Path, target: Path, indicator: ProgressIndicator? = null, version: String = CodeLldbRelease.VERSION) {
        ZipFile(vsix.toFile()).use { zip ->
            for (entry in zip.entries()) {
                indicator?.checkCanceled()
                if (entry.isDirectory) continue
                val relative = CodeLldbRelease.unpackedPath(entry.name) ?: continue
                val file = target.resolve(relative).normalize()
                if (!file.startsWith(target)) continue
                Files.createDirectories(file.parent)
                zip.getInputStream(entry).use { Files.copy(it, file, StandardCopyOption.REPLACE_EXISTING) }
                if (!SystemInfo.isWindows && (relative.startsWith("adapter/") || relative.startsWith("lldb/bin/"))) File(file.toString()).setExecutable(true)
            }
        }
        Files.writeString(target.resolve(CodeLldbLocator.INSTALLED_MARKER), version)
    }

    private fun group() = NotificationGroupManager.getInstance().getNotificationGroup(RustToolchainInstaller.NOTIFICATION_GROUP)
}

/** Tools | Rust | Download CodeLLDB...: the same confirmation as the button in Settings. */
class DownloadCodeLldbAction : DumbAwareAction("Download CodeLLDB...", "Download the CodeLLDB debug adapter from GitHub releases", AllIcons.Actions.Download) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) = CodeLldbInstaller.offerDownload(e.project, null)
}
