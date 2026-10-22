package io.github.rustsupport.cargo

import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.facet.ui.ValidationResult
import com.intellij.ide.util.projectWizard.SettingsStep
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.DirectoryProjectGeneratorBase
import com.intellij.platform.ProjectGeneratorPeer
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.PlatformUtils
import io.github.rustsupport.RustIcons
import io.github.rustsupport.settings.RustSettings
import io.github.rustsupport.toolchain.RustTool
import io.github.rustsupport.toolchain.RustToolchain
import io.github.rustsupport.toolchain.RustToolchainInstaller
import java.io.File
import java.nio.charset.StandardCharsets
import javax.swing.Icon
import javax.swing.JComponent

/** What `cargo init` of the New Project dialog is asked for; pure, for the tests. */
object CargoNewProject {
    enum class Template(val text: String, val flag: String, val entry: String) {
        BINARY("Binary (src/main.rs)", "--bin", "src/main.rs"),
        LIBRARY("Library (src/lib.rs)", "--lib", "src/lib.rs"),
    }

    /** "" is the default edition of the cargo at hand: 2024 needs cargo 1.85, which an older toolchain would refuse. */
    val EDITIONS = listOf("", "2024", "2021", "2018")

    private val KEYWORDS = setOf(
        "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum", "extern", "false", "fn", "for", "gen", "if",
        "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return", "self", "static", "struct", "super", "trait",
        "true", "try", "type", "unsafe", "use", "where", "while", "abstract", "become", "box", "do", "final", "macro", "override", "priv",
        "typeof", "unsized", "virtual", "yield",
    )

    /** Names cargo refuses: the libraries of the toolchain, and the directories of `target/<profile>` a binary would clash with. */
    private val RESERVED = setOf("std", "core", "alloc", "test", "proc_macro", "proc-macro", "deps", "examples", "build", "incremental")

    /** Why cargo would refuse [name], or null. */
    fun nameProblem(name: String): String? = when {
        name.isEmpty() -> "The package name is empty"
        !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' } -> "Only letters, digits, '-' and '_'"
        name.first().isDigit() -> "The package name cannot start with a digit"
        name in KEYWORDS -> "'$name' is a Rust keyword"
        name in RESERVED -> "'$name' is taken by the toolchain"
        else -> null
    }

    /** The package name a project name gives: lower case, `-` for the rest, never a name cargo refuses. */
    fun packageName(projectName: String): String {
        val name = projectName.trim().lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-') it else '-' }.joinToString("")
            .replace(Regex("-{2,}"), "-").trim('-', '_')
        return when {
            name.isEmpty() -> "app"
            name.first().isDigit() -> "rust-$name"
            nameProblem(name) != null -> "$name-app"
            else -> name
        }
    }

    /** `cargo init` in the directory the dialog has made (`cargo new` refuses an existing one). */
    fun arguments(name: String, template: Template, edition: String, git: Boolean): List<String> = buildList {
        add("init")
        add("--name"); add(name)
        add(template.flag)
        if (edition.isNotEmpty()) { add("--edition"); add(edition) }
        add("--vcs"); add(if (git) "git" else "none")
    }
}

class CargoNewProjectSettings(
    val cargo: String, val packageName: String, val template: CargoNewProject.Template, val edition: String, val git: Boolean,
)

/**
 * "Rust" in the New Project dialog of IntelliJ IDEA (and GIGA IDE), which lists generator wizards: name and location come from the base
 * step, cargo, the package and its kind below. Finish runs `cargo init` in the new directory, as the Go plugin runs `go mod init`.
 */
class CargoNewProjectWizard : GeneratorNewProjectWizard {
    // not "Rust": a fork may hide the wizard id of a plugin it advertises (seen with "Go" of the sibling plugin in GIGA IDE)
    override val id: String get() = "io.github.rustsupport.newProject"
    override val name: String get() = "Rust"
    override val icon: Icon get() = RustIcons.RUST
    override val description: String get() = "A Cargo package: Cargo.toml and src/main.rs or src/lib.rs"

    // the other IDEs show the directory generator; both at once would be two "Rust" entries
    override fun isEnabled(): Boolean = PlatformUtils.isIntelliJ()

    override fun createStep(context: WizardContext): NewProjectWizardStep =
        RootNewProjectWizardStep(context)
            .nextStep(::NewProjectWizardBaseStep)
            .nextStep(::Step)

    private class Step(private val base: NewProjectWizardBaseStep) : AbstractNewProjectWizardStep(base) {
        init {
            // new projects default under ~/GigaIdeProjects, as in the Go plugin, not the platform's ~/<Product>Projects
            base.path = File(System.getProperty("user.home"), "GigaIdeProjects").path
        }

        private val rows = CargoProjectPanel(context.project) { base.name }

        override fun setupUI(builder: Panel) = rows.addRows(builder)

        override fun setupProject(project: Project) {
            CargoProjectCreator.create(project, project.basePath ?: return, rows.settings)
        }
    }
}

/** "Rust" of the New Project dialog in the IDEs that use directory-based generators. */
class CargoProjectGenerator : DirectoryProjectGeneratorBase<CargoNewProjectSettings>() {
    override fun getName(): String = "Rust"
    override fun getLogo(): Icon = RustIcons.RUST
    override fun createPeer(): ProjectGeneratorPeer<CargoNewProjectSettings> = Peer()

    override fun validate(baseDirPath: String): ValidationResult =
        if (RustTool.CARGO.find() == null) ValidationResult("cargo is not found. Install Rust, or set the path to cargo in Settings | Languages & Frameworks | Rust.")
        else ValidationResult.OK

    override fun generateProject(project: Project, baseDir: VirtualFile, settings: CargoNewProjectSettings, module: Module) =
        CargoProjectCreator.create(project, baseDir.path, settings)

    private class Peer : ProjectGeneratorPeer<CargoNewProjectSettings> {
        private var location: TextFieldWithBrowseButton? = null
        private val rows = CargoProjectPanel(null) { location?.text?.let { File(it).name }.orEmpty() }
        private val settingsPanel: JComponent by lazy { panel { rows.addRows(this) } }

        override fun getComponent(myLocationField: TextFieldWithBrowseButton, checkValid: Runnable): JComponent {
            location = myLocationField
            return settingsPanel
        }

        override fun buildUI(settingsStep: SettingsStep) = settingsStep.addSettingsComponent(settingsPanel)
        override fun getSettings(): CargoNewProjectSettings = rows.settings
        override fun validate(): ValidationInfo? = rows.packageProblem()?.let { ValidationInfo(it) }
        override fun isBackgroundJobRunning(): Boolean = false
    }
}

/** The rows of both New Project dialogs; [projectName] gives the package name when its field is left empty. */
class CargoProjectPanel(project: Project?, private val projectName: () -> String) {
    private val cargo = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileOrExecutableAppDescriptor().withTitle("Cargo"))
        text = RustTool.CARGO.find()?.path.orEmpty()
    }
    private val packageName = JBTextField()
    private val template = ComboBox(CargoNewProject.Template.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.text }
    }
    private val edition = ComboBox(CargoNewProject.EDITIONS.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.ifEmpty { "Cargo's default" } }
    }
    private val git = JBCheckBox("Create Git repository", false)

    val settings: CargoNewProjectSettings
        get() = CargoNewProjectSettings(
            cargo.text.trim(), packageName(), template.item ?: CargoNewProject.Template.BINARY, edition.item.orEmpty(), git.isSelected,
        )

    private fun packageName(): String = packageName.text.trim().ifEmpty { CargoNewProject.packageName(projectName()) }

    /** The package name typed in, when cargo would refuse it; a derived one is always good. */
    fun packageProblem(): String? = packageName.text.trim().takeIf { it.isNotEmpty() }?.let { CargoNewProject.nameProblem(it) }

    fun addRows(builder: Panel) {
        with(builder) {
            // short comments: a comment wider than the dialog gives it a horizontal scroll bar and cuts the fields (seen live in Go's)
            row("Cargo:") { cell(cargo).align(AlignX.FILL).comment("Empty: PATH, ~/.cargo/bin and the standard directories", COMMENT_WIDTH) }
            row("Package name:") {
                cell(packageName).align(AlignX.FILL).comment("Empty: from the project name", COMMENT_WIDTH)
                    .validationOnApply { packageProblem()?.let { error(it) } }
            }
            row("Template:") { cell(template) }
            row("Edition:") { cell(edition) }
            row { cell(git).comment("cargo init --vcs git: a repository and .gitignore with /target", COMMENT_WIDTH) }
        }
    }

    private companion object {
        const val COMMENT_WIDTH = 50
    }
}

/** What Finish of both dialogs does: `cargo init`, then the entry point in the editor once the project is open. */
object CargoProjectCreator {
    private val LOG = logger<CargoProjectCreator>()
    private const val TIMEOUT_MS = 60_000

    fun create(project: Project, basePath: String, settings: CargoNewProjectSettings) {
        val cargo = rememberCargo(settings.cargo) ?: RustTool.CARGO.find()
        if (cargo == null) return notify(project, "cargo is not found: install Rust, or set the path to cargo in Settings | Languages & Frameworks | Rust.")
        val arguments = CargoNewProject.arguments(settings.packageName, settings.template, settings.edition, settings.git)
        // before the project opens, so that it opens as a Cargo project: rust-analyzer and the Cargo model look for Cargo.toml at the start
        val run = {
            runCatching {
                File(basePath).mkdirs()
                CapturingProcessHandler(RustToolchain.commandLine(cargo, basePath, *arguments.toTypedArray()).withCharset(StandardCharsets.UTF_8))
                    .runProcess(TIMEOUT_MS)
            }
        }
        val output = if (ApplicationManager.getApplication().isDispatchThread) {
            ProgressManager.getInstance().runProcessWithProgressSynchronously<Result<ProcessOutput>, RuntimeException>(
                { run() }, "cargo init", false, project,
            )
        } else run()
        val result = output.getOrElse { LOG.info("cargo init failed", it); return notify(project, "cargo init failed: ${it.message}") }
        if (result.exitCode != 0 || result.isTimeout) {
            val text = result.stderr.ifBlank { result.stdout }.trim().ifEmpty { if (result.isTimeout) "timed out" else "exit code ${result.exitCode}" }
            return notify(project, "cargo ${arguments.joinToString(" ")} failed:\n$text")
        }
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath) ?: return
        root.refresh(false, true)
        StartupManager.getInstance(project).runAfterOpened {
            ApplicationManager.getApplication().invokeLater({
                root.findFileByRelativePath(settings.template.entry)?.let { FileEditorManager.getInstance(project).openFile(it, true) }
            }, project.disposed)
        }
    }

    /** A cargo the user picked over the found one becomes the plugin's cargo. */
    private fun rememberCargo(path: String): File? {
        if (path.isEmpty()) return null
        val file = File(path).takeIf { it.isFile } ?: return null
        if (RustTool.CARGO.find()?.path != file.path) RustSettings.getInstance().cargoPath = file.path
        return file
    }

    private fun notify(project: Project, text: String) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance().getNotificationGroup(RustToolchainInstaller.NOTIFICATION_GROUP)
                .createNotification("New Rust project", text, NotificationType.ERROR).notify(project)
        }
    }
}
