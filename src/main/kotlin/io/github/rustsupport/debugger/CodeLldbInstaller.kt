package io.github.rustsupport.debugger

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
    /** Debug was started and no CodeLLDB is found: a balloon with the two ways out; [onReady] starts the debug again. */
    fun notifyMissing(project: Project, onReady: () -> Unit) {
        val asset = CodeLldbRelease.currentAsset()
        val text = if (asset != null) "Debugging Rust needs the CodeLLDB debug adapter. It was not found in the settings, in VS Code, Cursor or VSCodium extensions, or among the downloads."
        else "Debugging Rust needs the CodeLLDB debug adapter, and its releases have no build for this platform: choose an existing codelldb."
        val notification = group().createNotification("CodeLLDB is not found", text, NotificationType.WARNING)
        if (asset != null) notification.addAction(NotificationAction.createSimpleExpiring("Download CodeLLDB...") { offerDownload(project, onReady) })
        notification
            .addAction(NotificationAction.createSimpleExpiring("Choose existing codelldb...") { chooseExisting(project, onReady) })
            .addAction(NotificationAction.createSimple("Settings...") { ShowSettingsUtil.getInstance().showSettingsDialog(project, RustDebuggerConfigurable::class.java) })
            .notify(project)
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
    fun unpack(vsix: Path, target: Path, indicator: ProgressIndicator? = null) {
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
        Files.writeString(target.resolve(CodeLldbLocator.INSTALLED_MARKER), CodeLldbRelease.VERSION)
    }

    private fun group() = NotificationGroupManager.getInstance().getNotificationGroup(RustToolchainInstaller.NOTIFICATION_GROUP)
}

/** Tools | Rust | Download CodeLLDB...: the same confirmation as the button in Settings. */
class DownloadCodeLldbAction : DumbAwareAction("Download CodeLLDB...", "Download the CodeLLDB debug adapter from GitHub releases", AllIcons.Actions.Download) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) = CodeLldbInstaller.offerDownload(e.project, null)
}
