package io.github.rustsupport.run

import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton

import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

/** The editor of a Cargo configuration; the packages and targets come from `cargo metadata`, read in the background. */
class CargoSettingsEditor(private val project: Project) : SettingsEditor<CargoRunConfiguration>() {
    private val command = ComboBox(CargoInvocation.COMMON.toTypedArray()).apply { isEditable = true }
    private val packageModel = DefaultComboBoxModel<String>()
    private val packageName = ComboBox(packageModel).apply { isEditable = true }
    private val targetKind = ComboBox(CargoTargetKind.entries.toTypedArray())
    private val targetModel = DefaultComboBoxModel<String>()
    private val targetName = ComboBox(targetModel).apply { isEditable = true }
    private val features = JBTextField()
    private val allFeatures = JBCheckBox("All features (--all-features)")
    private val noDefaultFeatures = JBCheckBox("No default features (--no-default-features)")
    private val cargoArguments = RawCommandLineEditor()
    private val programArguments = RawCommandLineEditor()
    private val workingDirectory = TextFieldWithBrowseButton()
    private val environment = EnvironmentVariablesComponent()

    @Volatile private var workspace: CargoWorkspace? = null
    private var loadedFor: String? = null
    @Volatile private var disposed = false

    override fun disposeEditor() {
        disposed = true
    }

    override fun createEditor(): JComponent {
        workingDirectory.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Working Directory"))
        environment.labelLocation = java.awt.BorderLayout.WEST
        packageName.addActionListener { fillTargets() }
        targetKind.addActionListener { fillTargets() }
        return panel {
            row("Command:") { cell(command).comment("A subcommand of cargo with its words: <code>run</code>, <code>test</code>, <code>clippy</code>, <code>nextest run</code>") }
            row("Package:") { cell(packageName).align(AlignX.FILL).comment("<code>--package</code>; empty is the package of the working directory, or the default members of the workspace") }
            row("Target:") {
                cell(targetKind)
                cell(targetName).align(AlignX.FILL)
            }.comment("<code>--bin</code>, <code>--example</code>, <code>--test</code>, <code>--bench</code> with a name; <code>--lib</code>, <code>--doc</code> without")
            row("Features:") { cell(features).align(AlignX.FILL).comment("<code>--features</code>, separated by commas or spaces") }
            row { cell(allFeatures); cell(noDefaultFeatures) }
            row("Cargo arguments:") { cell(cargoArguments).align(AlignX.FILL).comment("More flags of cargo: <code>--release</code>, <code>--target ...</code>") }
            row("Program arguments:") { cell(programArguments).align(AlignX.FILL).comment("After <code>--</code>: the arguments of the program; for tests the name filter, <code>--exact</code>, <code>--nocapture</code>") }
            row("Working directory:") { cell(workingDirectory).align(AlignX.FILL).comment("By default the project directory") }
            row { cell(environment).align(AlignX.FILL) }
        }
    }

    override fun resetEditorFrom(configuration: CargoRunConfiguration) {
        val options = configuration.options
        command.selectedItem = options.command ?: "run"
        packageName.selectedItem = options.packageName.orEmpty()
        targetKind.selectedItem = options.targetKind
        targetName.selectedItem = options.targetName.orEmpty()
        features.text = options.features.orEmpty()
        allFeatures.isSelected = options.allFeatures
        noDefaultFeatures.isSelected = options.noDefaultFeatures
        cargoArguments.text = options.cargoArguments.orEmpty()
        programArguments.text = options.programArguments.orEmpty()
        workingDirectory.text = options.workingDirectory.orEmpty()
        environment.envs = options.environment
        environment.isPassParentEnvs = options.passParentEnvironment
        loadMetadata(configuration.workingDirectory())
    }

    override fun applyEditorTo(configuration: CargoRunConfiguration) {
        val options = configuration.options
        options.command = text(command).ifEmpty { "run" }
        options.packageName = text(packageName).ifEmpty { null }
        options.targetKind = targetKind.selectedItem as? CargoTargetKind ?: CargoTargetKind.DEFAULT
        options.targetName = text(targetName).ifEmpty { null }
        options.features = features.text.trim().ifEmpty { null }
        options.allFeatures = allFeatures.isSelected
        options.noDefaultFeatures = noDefaultFeatures.isSelected
        options.cargoArguments = cargoArguments.text.trim().ifEmpty { null }
        options.programArguments = programArguments.text.trim().ifEmpty { null }
        options.workingDirectory = workingDirectory.text.trim().ifEmpty { null }
        options.environment = LinkedHashMap(environment.envs)
        options.passParentEnvironment = environment.isPassParentEnvs
    }

    /** The text of an editable combo box: what is typed, not only what is chosen. */
    private fun text(box: ComboBox<*>): String = ((box.editor?.item ?: box.selectedItem)?.toString() ?: "").trim()

    private fun loadMetadata(directory: String) {
        if (directory.isEmpty() || directory == loadedFor) return
        loadedFor = directory
        ApplicationManager.getApplication().executeOnPooledThread {
            val loaded = CargoMetadata.load(directory) ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                workspace = loaded
                val current = text(packageName)
                packageModel.removeAllElements()
                packageModel.addElement("")
                loaded.packages.map { it.name }.sorted().forEach(packageModel::addElement)
                packageName.selectedItem = current
                fillTargets()
            }, ModalityState.any())
        }
    }

    /** The targets of the chosen package (of every package when none is chosen) of the chosen kind. */
    private fun fillTargets() {
        val loaded = workspace ?: return
        val kind = targetKind.selectedItem as? CargoTargetKind ?: return
        val current = text(targetName)
        val chosen = text(packageName)
        val packages = loaded.packages.filter { chosen.isEmpty() || it.name == chosen }
        val names = packages.flatMap { it.targets }.filter { it.kind == kind }.map { it.name }.distinct().sorted()
        targetModel.removeAllElements()
        names.forEach(targetModel::addElement)
        targetName.selectedItem = current
        targetName.isEnabled = kind.hasName
    }
}
