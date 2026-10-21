package io.github.rustsupport.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.util.execution.ParametersListUtil
import io.github.rustsupport.RustIcons
import io.github.rustsupport.toolchain.RustTool
import io.github.rustsupport.toolchain.RustToolchain
import java.io.File

class CargoConfigurationType : ConfigurationTypeBase(ID, "Cargo", "Run a cargo command: run, test, build, check, clippy, bench", NotNullLazyValue.createValue { RustIcons.CARGO }) {
    val factory: ConfigurationFactory = object : ConfigurationFactory(this) {
        override fun getId(): String = "Cargo"
        override fun createTemplateConfiguration(project: Project): RunConfiguration = CargoRunConfiguration(project, this, "Cargo")
        override fun getOptionsClass(): Class<out BaseState> = CargoRunConfigurationOptions::class.java
    }

    init {
        addFactory(factory)
    }

    companion object {
        const val ID = "CargoCommandRunConfiguration"

        val instance: CargoConfigurationType get() = ConfigurationTypeUtil.findConfigurationType(CargoConfigurationType::class.java)
    }
}

class CargoRunConfigurationOptions : LocatableRunConfigurationOptions() {
    /** The subcommand and the words after it: `run`, `test`, `nextest run`. */
    var command by string("run")
    var packageName by string()
    var targetKind by enum(CargoTargetKind.DEFAULT)
    var targetName by string()
    var features by string()
    var allFeatures by property(false)
    var noDefaultFeatures by property(false)

    /** More flags of cargo: `--release`, `--target ...`. */
    var cargoArguments by string()

    /** After `--`: the arguments of the program, or of the test harness (`tests::it_works --exact`). */
    var programArguments by string()

    /** Where cargo and the program run: the root of the workspace by default. */
    var workingDirectory by string()
    var environment by map<String, String>()
    var passParentEnvironment by property(true)
}

class CargoRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<CargoRunConfigurationOptions>(project, factory, name), RustDebuggableConfiguration {

    public override fun getOptions(): CargoRunConfigurationOptions = super.getOptions() as CargoRunConfigurationOptions

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = CargoSettingsEditor(project)

    fun invocation(): CargoInvocation = CargoInvocation(
        command = ParametersListUtil.parse(options.command.orEmpty()).ifEmpty { listOf("run") },
        packageName = options.packageName,
        targetKind = options.targetKind,
        targetName = options.targetName,
        features = options.features,
        allFeatures = options.allFeatures,
        noDefaultFeatures = options.noDefaultFeatures,
        cargoArguments = ParametersListUtil.parse(options.cargoArguments.orEmpty()),
        programArguments = ParametersListUtil.parse(options.programArguments.orEmpty()),
    )

    /** The working directory of the configuration, else the project directory (where the workspace is, as a rule). */
    fun workingDirectory(): String = options.workingDirectory?.takeIf { it.isNotBlank() } ?: project.basePath.orEmpty()

    override fun checkConfiguration() {
        if (RustTool.CARGO.find() == null) throw RuntimeConfigurationError("cargo is not found: install Rust with rustup, or set the path in Settings | Languages & Frameworks | Rust")
        if (options.command.isNullOrBlank()) throw RuntimeConfigurationError("The cargo command is not specified")
        val directory = workingDirectory()
        if (directory.isEmpty() || !File(directory).isDirectory) throw RuntimeConfigurationError("The working directory does not exist: $directory")
        if (options.targetKind.hasName && options.targetName.isNullOrBlank()) throw RuntimeConfigurationError("The name of the target (${options.targetKind.flag}) is not specified")
    }

    fun commandLine(): GeneralCommandLine {
        val cargo = RustTool.CARGO.find() ?: throw ExecutionException("cargo is not found")
        return RustToolchain.commandLine(cargo, workingDirectory(), *invocation().arguments().toTypedArray())
            .withEnvironment(options.environment)
            .withParentEnvironmentType(if (options.passParentEnvironment) GeneralCommandLine.ParentEnvironmentType.CONSOLE else GeneralCommandLine.ParentEnvironmentType.NONE)
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        if (invocation().isTestHarness) CargoTestRunState(this, environment) else CargoRunState(this, environment)

    override fun debugLaunchSpec(): RustDebugLaunchSpec? {
        val invocation = invocation()
        val build = invocation.debugBuildArguments() ?: return null
        return RustDebugLaunchSpec(build, invocation.programArguments, workingDirectory(), options.environment.toMap())
    }

    override fun suggestedName(): String? = invocation().let { invocation ->
        listOfNotNull(invocation.command.joinToString(" "), invocation.targetName?.takeIf { it.isNotBlank() } ?: invocation.packageName?.takeIf { it.isNotBlank() }).joinToString(" ")
    }
}

/** A cargo command in a console: the output colored by cargo, the places in Rust files linked ([CargoConsoleFilterProvider]). */
class CargoRunState(private val configuration: CargoRunConfiguration, environment: ExecutionEnvironment) : CommandLineState(environment) {
    override fun startProcess(): ProcessHandler = KillableColoredProcessHandler(configuration.commandLine()).also { ProcessTerminatedListener.attach(it) }
}
