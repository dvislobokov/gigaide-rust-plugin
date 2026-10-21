package io.github.rustsupport.lsp

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.BaseProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.util.Key
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lang.lsWidget.LanguageServiceWidgetItem
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspDocumentHighlightsCustomizer
import com.intellij.platform.lsp.api.customization.LspDocumentHighlightsSupport
import com.intellij.platform.lsp.api.customization.LspFormattingCustomizer
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.intellij.platform.lsp.api.customization.LspGoToDefinitionCustomizer
import com.intellij.platform.lsp.api.customization.LspGoToDefinitionDisabled
import com.intellij.platform.lsp.api.customization.LspSelectionRangeCustomizer
import com.intellij.platform.lsp.api.customization.LspSelectionRangeSupport
import com.intellij.platform.lsp.api.customization.LspSemanticTokensCustomizer
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem
import com.intellij.psi.PsiFile
import io.github.rustsupport.cargo.CargoFeatureSettings
import io.github.rustsupport.lang.RustColors
import io.github.rustsupport.lang.RustFile
import io.github.rustsupport.lang.RustFileType
import io.github.rustsupport.settings.RustSettings
import io.github.rustsupport.settings.RustSettingsListener
import io.github.rustsupport.toolchain.RustProjectDetector
import io.github.rustsupport.toolchain.RustTool
import io.github.rustsupport.toolchain.RustToolchain
import io.github.rustsupport.toolchain.RustToolchainInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.WorkDoneProgressBegin
import org.eclipse.lsp4j.WorkDoneProgressEnd
import org.eclipse.lsp4j.WorkDoneProgressReport
import org.eclipse.lsp4j.services.LanguageServer
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Starts rust-analyzer for the project when it is opened with Rust in it, or when a Rust file is opened. */
class RustAnalyzerIntegrationProvider : LspIntegrationProvider {
    override fun fileOpened(project: Project, file: VirtualFile, clientStarter: LspIntegrationProvider.LspClientStarter) {
        if (!isRustFile(file) || !RustSettings.getInstance().languageServerEnabled) return
        if (RustTool.RUST_ANALYZER.find() == null) return RustToolchainInstaller.offerRustAnalyzer(project)
        clientStarter.ensureClientStarted(RustAnalyzerDescriptor(project))
    }

    /** No row in the widget of language services: the server has a widget of its own in the status bar ([RustAnalyzerStatusWidget]). */
    override fun createWidgetItems(project: Project, currentFile: VirtualFile?): List<LanguageServiceWidgetItem> = emptyList()

    override fun createWidgetItem(lspClient: LspClient, currentFile: VirtualFile?): LspClientWidgetItem? = null

    companion object {
        fun isRustFile(file: VirtualFile): Boolean = file.fileType == RustFileType || file.extension == "rs"

        /** A running server starts anew; with none (rust-analyzer was just installed), one starts for a Rust project even with no file open. */
        fun restart(project: Project) {
            val manager = LspClientManager.getInstance(project)
            manager.stopAndRestartClientsIfNeeded(RustAnalyzerIntegrationProvider::class.java)
            manager.startClientsIfNeeded(RustAnalyzerIntegrationProvider::class.java)
            ApplicationManager.getApplication().executeOnPooledThread { startForProject(project) }
        }

        /**
         * Starts rust-analyzer without waiting for a Rust file to be opened, when the project has Rust in it: loading a workspace takes
         * rust-analyzer a while, and it is done by the time the first file is. Reads the disk: call off the EDT.
         */
        fun startForProject(project: Project) {
            if (project.isDisposed || !RustSettings.getInstance().languageServerEnabled) return
            if (!RustProjectDetector.hasRust(project.basePath?.let { Path.of(it) })) return
            if (LspClientManager.getInstance(project).getClients(RustAnalyzerIntegrationProvider::class.java).isNotEmpty()) return
            if (RustTool.RUST_ANALYZER.find() == null) return RustToolchainInstaller.offerRustAnalyzer(project)
            LspClientManager.getInstance(project).ensureClientStarted(RustAnalyzerIntegrationProvider::class.java, RustAnalyzerDescriptor(project))
        }
    }
}

class RustAnalyzerDescriptor(project: Project) : ProjectWideLspClientDescriptor(project, "rust-analyzer") {
    // a file on disk only: the tabs of the plugin (a macro expansion, HIR) are Rust files in memory that the server knows nothing of
    override fun isSupportedFile(file: VirtualFile): Boolean = file.isInLocalFileSystem && RustAnalyzerIntegrationProvider.isRustFile(file)

    override fun createCommandLine(): GeneralCommandLine {
        val exe = RustTool.RUST_ANALYZER.find() ?: throw ExecutionException("rust-analyzer is not found")
        val commandLine = RustToolchain.commandLine(exe, project.basePath)
        // rust-analyzer runs the cargo of the CARGO variable when it is set
        RustSettings.getInstance().cargoPath.takeIf { it.isNotBlank() }?.let { commandLine.withEnvironment("CARGO", it) }
        return commandLine
    }

    /** The process of the platform, watched for the status bar: its memory and CPU while it lives. */
    override fun startServerProcess(): BaseProcessHandler<*> = super.startServerProcess().also { handler ->
        val state = RustAnalyzerServerState.getInstance(project)
        val process = handler.process.toHandle()
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                // a build script or a proc macro that rustc could not link: the C++ tools of Visual Studio are missing or incomplete
                if (ProcessOutputType.isStderr(outputType) && LINK_FAILURE in event.text) RustToolchainInstaller.offerMsvcLinker(project)
            }

            override fun processTerminated(event: ProcessEvent) {
                // the project may be closing: its services are gone then, and so is its status bar
                if (!project.isDisposed) state.stopped(process)
            }
        })
        state.started(process)
        // the log window: stderr of the server, its start and its end
        RustAnalyzerLog.getInstance(project).attach(handler, handler.commandLine)
    }

    /**
     * `$/progress` of the server goes to the status bar as well, on its way to the platform; its messages go to the log window
     * ([LoggingNotificationsHandler]); `experimental/serverStatus` is taken by [RustAnalyzerLsp4jClient].
     */
    override fun createLsp4jClient(handler: LspServerNotificationsHandler): Lsp4jClient {
        val log = RustAnalyzerLog.getInstance(project)
        return RustAnalyzerLsp4jClient(LoggingNotificationsHandler(ProgressTrackingHandler(handler, RustAnalyzerServerState.getInstance(project)), log), log::serverStatus)
    }

    /** The requests of rust-analyzer beyond the protocol (runnables, expand macro, parent module, join lines, on enter, views) on the same connection. */
    override val lsp4jServerClass: Class<out LanguageServer> = RustAnalyzerServer::class.java

    /** `experimental/serverStatus` is sent only to a client that asks for it: the health of the server for the status widget. */
    override val clientCapabilities: ClientCapabilities get() = super.clientCapabilities.apply {
        experimental = RustAnalyzerCapabilities.withServerStatus(experimental)
    }

    override val lspServerListener: LspServerListener = object : LspServerListener {
        override fun serverInitialized(params: InitializeResult) {
            RustAnalyzerServerState.getInstance(project).version = params.serverInfo?.version
            RustAnalyzerStatusWidgetFactory.repaint(project)
        }
    }

    override fun getLanguageId(file: VirtualFile): String = "rust"

    override fun createInitializationOptions(): Any = RustAnalyzerOptions.build(RustSettings.getInstance(), CargoFeatureSettings.getInstance(project).rustAnalyzerFeatures())

    /** rust-analyzer asks for the section `rust-analyzer` after `workspace/didChangeConfiguration`. */
    override fun getWorkspaceConfiguration(item: ConfigurationItem): Any? =
        if (item.section == "rust-analyzer") RustAnalyzerOptions.build(RustSettings.getInstance(), CargoFeatureSettings.getInstance(project).rustAnalyzerFeatures()) else null

    /**
     * A Rust file has a language of its own (for Ctrl + mouse, braces, indents): the platform then asks a server for colours, highlighted
     * usages, Extend Selection and formatting only when told so, which is done here.
     */
    override val lspCustomization: LspCustomization = object : LspCustomization() {
        /** Go to Declaration is [RustAnalyzerGotoDeclarationHandler]: with both, every target would be offered twice. */
        override val goToDefinitionCustomizer: LspGoToDefinitionCustomizer get() = LspGoToDefinitionDisabled

        override val semanticTokensCustomizer: LspSemanticTokensCustomizer = object : LspSemanticTokensSupport() {
            override fun shouldAskServerForSemanticTokens(psiFile: PsiFile): Boolean = psiFile is RustFile
            override val tokenTypes: List<String> get() = (super.tokenTypes + RUST_ANALYZER_TOKEN_TYPES).distinct()
            override val tokenModifiers: List<String> get() = (super.tokenModifiers + RUST_ANALYZER_TOKEN_MODIFIERS).distinct()
            override fun getTextAttributesKey(tokenType: String, modifiers: List<String>): TextAttributesKey? = RustColors.semantic(tokenType, modifiers)
        }

        override val documentHighlightsCustomizer: LspDocumentHighlightsCustomizer = object : LspDocumentHighlightsSupport() {
            override fun shouldAskServerForDocumentHighlights(psiFile: PsiFile): Boolean = psiFile is RustFile
        }

        override val selectionRangeCustomizer: LspSelectionRangeCustomizer = object : LspSelectionRangeSupport() {
            override fun shouldAskServerForSelectionRange(file: VirtualFile): Boolean = RustAnalyzerIntegrationProvider.isRustFile(file)
        }

        /** Reformat Code is rustfmt through rust-analyzer: the IDE has no formatter of Rust. */
        override val formattingCustomizer: LspFormattingCustomizer = object : LspFormattingSupport() {
            override fun shouldFormatThisFileExclusivelyByServer(file: VirtualFile, ideCanFormatThisFileItself: Boolean, serverExplicitlyEnabled: Boolean): Boolean =
                RustAnalyzerIntegrationProvider.isRustFile(file)
        }
    }

    private companion object {
        const val LINK_FAILURE = "linking with `link.exe` failed"

        /** The token types rust-analyzer adds to the standard ones (https://rust-analyzer.github.io/book/features.html). */
        val RUST_ANALYZER_TOKEN_TYPES = listOf(
            "angle", "arithmetic", "attribute", "attributeBracket", "bitwise", "boolean", "brace", "bracket", "builtinAttribute", "builtinType",
            "char", "colon", "comma", "comparison", "constParameter", "const", "derive", "deriveHelper", "dot", "escapeSequence",
            "formatSpecifier", "generic", "invalidEscapeSequence", "label", "lifetime", "logical", "macroBang", "parenthesis", "procMacro",
            "punctuation", "selfKeyword", "selfTypeKeyword", "semicolon", "static", "toolModule", "typeAlias", "union", "unresolvedReference",
        )
        val RUST_ANALYZER_TOKEN_MODIFIERS = listOf(
            "async", "attribute", "callable", "constant", "consuming", "controlFlow", "crateRoot", "injected", "intraDocLink", "library",
            "macro", "mutable", "procMacro", "public", "reference", "trait", "unsafe", "associated",
        )
    }
}

/** What the server is busy with: the titles of its `$/progress` that have begun and not ended, the latest shown. */
private class ProgressTrackingHandler(
    private val delegate: LspServerNotificationsHandler, private val state: RustAnalyzerServerState,
) : LspServerNotificationsHandler by delegate {
    private val active = ConcurrentHashMap<String, String>()
    @Volatile private var latest: String? = null

    override fun notifyProgress(params: ProgressParams) {
        val token = params.token?.let { if (it.isLeft) it.left else it.right?.toString() }
        val progress = params.value?.takeIf { it.isLeft }?.left
        if (token != null) {
            when (progress) {
                is WorkDoneProgressBegin -> active[token] = text(progress.title, progress.message).also { latest = token }
                is WorkDoneProgressReport -> active[token]?.let { title -> active[token] = text(title.substringBefore(": "), progress.message); latest = token }
                is WorkDoneProgressEnd -> {
                    active.remove(token)
                    if (latest == token) latest = active.keys.firstOrNull()
                }
                else -> {}
            }
            state.activityChanged(latest?.let { active[it] })
        }
        delegate.notifyProgress(params)
    }

    private fun text(title: String?, message: String?): String = listOfNotNull(title, message?.takeIf { it.isNotBlank() }).joinToString(": ")
}

/** The settings of rust-analyzer (https://rust-analyzer.github.io/book/configuration) the settings page has switches for. */
object RustAnalyzerOptions {
    /** [features]: `cargo.features`, the features checked in the Cargo tool window (`package/feature`). */
    fun build(settings: RustSettings, features: List<String> = emptyList()): Map<String, Any> = mapOf(
        "check" to mapOf("command" to settings.checkCommand),
        "checkOnSave" to true,
        "cargo" to buildMap<String, Any> {
            put("buildScripts", mapOf("enable" to settings.buildScripts))
            if (features.isNotEmpty()) put("features", features)
        },
        "procMacro" to mapOf("enable" to settings.procMacros),
        // indexing every crate of the dependency graph at start: fast first completion, at the price of memory for crates never opened
        "cachePriming" to mapOf("enable" to settings.primeCaches),
        "lru" to mapOf("capacity" to settings.lruCapacity),
        "inlayHints" to mapOf(
            "typeHints" to mapOf("enable" to settings.inlayHints),
            "parameterHints" to mapOf("enable" to settings.inlayHints),
            "chainingHints" to mapOf("enable" to settings.inlayHints),
        ),
    )
}

/** A Rust project gets rust-analyzer as soon as it is opened. */
class RustAnalyzerStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) = withContext(Dispatchers.IO) { RustAnalyzerIntegrationProvider.startForProject(project) }
}

/** Applied settings or an installed component: rust-analyzer restarts in every open project. */
class RustAnalyzerSettingsListener : RustSettingsListener {
    override fun settingsChanged() {
        ApplicationManager.getApplication().invokeLater {
            ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }.forEach(RustAnalyzerIntegrationProvider::restart)
        }
    }
}

class RestartRustAnalyzerAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let(RustAnalyzerIntegrationProvider::restart)
    }
}
