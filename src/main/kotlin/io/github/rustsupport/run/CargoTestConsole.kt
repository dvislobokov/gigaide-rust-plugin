package io.github.rustsupport.run

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComponentContainer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.execution.ParametersListUtil
import jetbrains.buildServer.messages.serviceMessages.ServiceMessageVisitor

const val CARGO_TEST_FRAMEWORK = "CargoTest"
private const val LOCATION_PROTOCOL = "cargotest"

/** `cargo test` / `cargo bench` with the test tree of the platform, which grows while the tests run. */
class CargoTestRunState(private val configuration: CargoRunConfiguration, environment: ExecutionEnvironment) : CommandLineState(environment) {
    override fun startProcess(): ProcessHandler = KillableColoredProcessHandler(configuration.commandLine()).also { ProcessTerminatedListener.attach(it) }

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val handler = startProcess()
        val properties = CargoTestConsoleProperties(configuration, executor)
        val console = SMTestRunnerConnectionUtil.createAndAttachConsole(CARGO_TEST_FRAMEWORK, handler, properties)
        val rerunFailed = properties.createRerunFailedTestsAction(console)
        rerunFailed.setModelProvider { (console as SMTRunnerConsoleView).resultsViewer }
        return DefaultExecutionResult(console, handler, *createActions(console, handler, executor)).apply { setRestartActions(rerunFailed) }
    }
}

class CargoTestConsoleProperties(private val configuration: CargoRunConfiguration, executor: Executor) :
    SMTRunnerConsoleProperties(configuration, CARGO_TEST_FRAMEWORK, executor), SMCustomMessagesParsing {

    init {
        isIdBasedTestTree = true
        // the platform hides passed tests until "Show Passed" is pressed: a green run would look like an empty tree
        setIfUndefined(TestConsoleProperties.HIDE_PASSED_TESTS, false)
        setIfUndefined(TestConsoleProperties.HIDE_IGNORED_TEST, false)
    }

    override fun createTestEventsConverter(testFrameworkName: String, consoleProperties: TestConsoleProperties): OutputToGeneralTestEventsConverter {
        val root = configuration.workingDirectory()
        return CargoTestEventsConverter(testFrameworkName, CargoTestEvents { source, path -> locationHint(root, source, path) })
    }

    override fun getTestLocator(): SMTestLocator = CargoTestLocator

    override fun createRerunFailedTestsAction(consoleView: ConsoleView): AbstractRerunFailedTestsAction = RerunFailedCargoTestsAction(consoleView as ComponentContainer, this, configuration)

    companion object {
        fun locationHint(root: String, source: String, path: String): String = "$LOCATION_PROTOCOL://${root.replace('\\', '/')}|$source|$path"
    }
}

/** Every complete line of the output through [CargoTestEvents]; what is not an event goes to the console as it came. */
class CargoTestEventsConverter(testFrameworkName: String, private val events: CargoTestEvents) :
    OutputToGeneralTestEventsConverter(testFrameworkName, true, false, false) {

    override fun processServiceMessages(text: String, outputType: Key<*>, visitor: ServiceMessageVisitor): Boolean {
        val output = synchronized(events) { events.convert(text) }
        for (message in output.messages) super.processServiceMessages(message, outputType, visitor)
        // false for a line that is no service message: the platform prints it
        return if (output.toConsole) super.processServiceMessages(text, outputType, visitor) else true
    }

    override fun flushBufferOnProcessTermination(exitCode: Int) {
        super.flushBufferOnProcessTermination(exitCode)
        val messages = synchronized(events) { events.finish() }
        for (message in messages) processConsistentText(message + "\n", ProcessOutputTypes.STDOUT)
    }
}

/**
 * Double click on a test: a doctest (`src/lib.rs - add (line 5)`) leads to its line; a test to `fn name` in the source file of its
 * binary, else in the `.rs` files next to it and below; a module to `mod name`.
 */
object CargoTestLocator : SMTestLocator {
    private val DOCTEST = Regex("""^(.+?) - .*\(line (\d+)\)$""")

    override fun getLocation(protocol: String, path: String, project: Project, scope: GlobalSearchScope): List<Location<*>> {
        if (protocol != LOCATION_PROTOCOL) return emptyList()
        val parts = path.split('|')
        if (parts.size != 3) return emptyList()
        val (root, source, test) = parts
        val fileSystem = LocalFileSystem.getInstance()
        val rootDirectory = fileSystem.findFileByPath(root) ?: return emptyList()
        val psiManager = PsiManager.getInstance(project)
        DOCTEST.matchEntire(test)?.let { match ->
            val file = rootDirectory.findFileByRelativePath(match.groupValues[1].replace('\\', '/')) ?: return emptyList()
            val psiFile = psiManager.findFile(file) ?: return emptyList()
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return emptyList()
            val line = (match.groupValues[2].toInt() - 1).coerceIn(0, (document.lineCount - 1).coerceAtLeast(0))
            return listOfNotNull(psiFile.findElementAt(document.getLineStartOffset(line))?.let { PsiLocation(it) })
        }
        val sourceFile = source.takeIf { it.isNotEmpty() }?.let { rootDirectory.findFileByRelativePath(it) }
        if (test.isEmpty()) return listOfNotNull(sourceFile?.let(psiManager::findFile)?.let { PsiLocation(it) })
        val name = test.substringAfterLast("::")
        val files = candidateFiles(sourceFile, rootDirectory)
        for (keyword in listOf("fn", "mod")) {
            val pattern = Regex("""\b$keyword\s+(${Regex.escape(name)})\b""")
            for (file in files) {
                val document = FileDocumentManager.getInstance().getDocument(file) ?: continue
                val match = pattern.find(document.immutableCharSequence) ?: continue
                val element = psiManager.findFile(file)?.findElementAt(match.groups[1]!!.range.first) ?: continue
                return listOf(PsiLocation(element))
            }
        }
        return emptyList()
    }

    /** The source file of the binary first, then the Rust files of its directory and below, at most a few hundred. */
    private fun candidateFiles(sourceFile: VirtualFile?, root: VirtualFile): List<VirtualFile> {
        val directory = sourceFile?.parent ?: root
        val files = ArrayList<VirtualFile>()
        sourceFile?.let(files::add)
        VfsUtilCore.visitChildrenRecursively(directory, object : VirtualFileVisitor<Unit>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (files.size >= 500) return false
                if (file.isDirectory) return file == directory || file.name != "target" && !file.name.startsWith(".")
                if (file.extension == "rs" && file != sourceFile) files += file
                return true
            }
        })
        return files
    }
}

/** Runs again only the tests that failed: their full paths as the filters of the harness, with `--exact`. */
class RerunFailedCargoTestsAction(container: ComponentContainer, properties: CargoTestConsoleProperties, private val configuration: CargoRunConfiguration) :
    AbstractRerunFailedTestsAction(container) {

    init {
        init(properties)
    }

    override fun getRunProfile(environment: ExecutionEnvironment): MyRunProfile = object : MyRunProfile(configuration) {
        override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
            val rerun = configuration.clone() as CargoRunConfiguration
            val previous = ParametersListUtil.parse(configuration.options.programArguments.orEmpty())
            rerun.options.programArguments = ParametersListUtil.join(CargoTestArguments.rerun(previous, failedTestNames(getFailedTests(configuration.project))))
            return rerun.getState(executor, environment)
        }
    }

    companion object {
        fun failedTestNames(failed: List<AbstractTestProxy>): List<String> =
            failed.filter { it.isLeaf }.mapNotNull { it.locationUrl?.substringAfter("://", "")?.split('|')?.getOrNull(2)?.takeIf(String::isNotEmpty) }.distinct()
    }
}
