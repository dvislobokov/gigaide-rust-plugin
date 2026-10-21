package io.github.rustsupport.debugger

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** A `compiler-artifact` message of cargo with an executable: a binary, an example, a test or bench harness. */
data class CargoArtifact(
    val executable: String,
    val targetName: String,
    val kinds: List<String>,
    val test: Boolean,
)

/** One line of `cargo ... --message-format=json` on stdout. */
sealed interface CargoMessage {
    data class Artifact(val artifact: CargoArtifact) : CargoMessage
    /** A diagnostic of the compiler, rendered by it as it would print it without JSON. */
    data class Diagnostic(val rendered: String, val error: Boolean) : CargoMessage
    data class Finished(val success: Boolean) : CargoMessage
    /** Not JSON (a build script that prints to stdout through cargo?): shown as it is. */
    data class Text(val text: String) : CargoMessage
    /** JSON the debugger has no use for (`build-script-executed`, an artifact without an executable). */
    data object Ignored : CargoMessage
}

/** Pure parts of the build before a debug session: the command, the messages, the choice of the executable. */
object CargoBuild {
    /** `build --bin app` + `--message-format=json`, before a `--` of arguments for the program, and only once. */
    fun arguments(buildArguments: List<String>): List<String> {
        if (buildArguments.any { it == "--message-format" || it.startsWith("--message-format=") }) return buildArguments
        val separator = buildArguments.indexOf("--")
        return if (separator < 0) buildArguments + MESSAGE_FORMAT
        else buildArguments.take(separator) + MESSAGE_FORMAT + buildArguments.drop(separator)
    }

    private const val MESSAGE_FORMAT = "--message-format=json"

    /** `cargo test --no-run` and `cargo bench --no-run` build harnesses: the executable is the one of `profile.test`. */
    fun isTestBuild(buildArguments: List<String>): Boolean = buildArguments.firstOrNull { !it.startsWith("-") && !it.startsWith("+") } in setOf("test", "bench", "t")

    fun parse(line: String): CargoMessage {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return CargoMessage.Text(line)
        val json = runCatching { JsonParser.parseString(trimmed).asJsonObject }.getOrNull() ?: return CargoMessage.Text(line)
        return when (json.string("reason")) {
            "compiler-artifact" -> artifact(json)?.let(CargoMessage::Artifact) ?: CargoMessage.Ignored
            "compiler-message" -> {
                val message = json.getAsJsonObject("message") ?: return CargoMessage.Ignored
                val rendered = message.string("rendered") ?: message.string("message") ?: return CargoMessage.Ignored
                CargoMessage.Diagnostic(rendered, message.string("level") in setOf("error", "error: internal compiler error"))
            }
            "build-finished" -> CargoMessage.Finished(json.bool("success") == true)
            else -> CargoMessage.Ignored
        }
    }

    private fun artifact(json: JsonObject): CargoArtifact? {
        val executable = json.get("executable")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() } ?: return null
        val target = json.getAsJsonObject("target") ?: JsonObject()
        val kinds = target.getAsJsonArray("kind")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()
        val test = json.getAsJsonObject("profile")?.bool("test") == true
        return CargoArtifact(executable, target.string("name").orEmpty(), kinds, test)
    }

    /** The executable to debug, or why there is none; [warning] when several were built and the first is taken. */
    data class Choice(val executable: String?, val error: String? = null, val warning: String? = null)

    fun choose(artifacts: List<CargoArtifact>, testBuild: Boolean): Choice {
        val runnable = artifacts.filter { "custom-build" !in it.kinds }.distinctBy { it.executable }
        val candidates = if (testBuild) runnable.filter { it.test } else runnable.filter { !it.test && it.kinds.any { k -> k == "bin" || k == "example" } }
            .ifEmpty { runnable.filter { !it.test } }
        return when {
            candidates.isEmpty() -> Choice(null, error = if (testBuild) "cargo has built no test executable" else "cargo has built no executable (a library crate has nothing to run)")
            candidates.size == 1 -> Choice(candidates[0].executable)
            else -> Choice(
                candidates[0].executable,
                warning = "cargo has built ${candidates.size} executables (${candidates.joinToString { it.targetName.ifEmpty { it.executable } }}); debugging the first one, " +
                    "narrow the configuration down (--bin, --lib, --test) to choose",
            )
        }
    }

    private val PROGRESS = Regex("""^\s*(Compiling|Checking|Finished|Running|Fresh|Blocking|Updating|Downloading|Downloaded|Locking|Adding|Executable|Documenting|Packaging|Removing|Unpacking|Fetching)\b""")

    /** cargo's own lines on stderr: progress is gray, the rest (`error: could not compile`) red. */
    fun stderrType(line: String): Key<*> = if (PROGRESS.containsMatchIn(line)) ProcessOutputTypes.SYSTEM else ProcessOutputTypes.STDERR

    /** The outcome of a build: what it built, whether it passed. */
    class Result(val exitCode: Int, val artifacts: List<CargoArtifact>) {
        val success: Boolean get() = exitCode == 0
    }

    /**
     * Runs the build and prints its output through [print]; [started] gets the process, for a stop of the session in the middle of it.
     * Blocks: call off the EDT.
     */
    fun run(commandLine: GeneralCommandLine, started: (Process) -> Unit, print: (String, Key<*>) -> Unit): Result {
        val process = commandLine.withCharset(Charsets.UTF_8).createProcess()
        started(process)
        val stderr = Thread({ lines(process.errorStream) { print(it + "\n", stderrType(it)) } }, "cargo stderr").apply { isDaemon = true; start() }
        val artifacts = mutableListOf<CargoArtifact>()
        lines(process.inputStream) { line ->
            when (val message = parse(line)) {
                is CargoMessage.Artifact -> artifacts += message.artifact
                is CargoMessage.Diagnostic -> print(message.rendered.trimEnd() + "\n", if (message.error) ProcessOutputTypes.STDERR else ProcessOutputTypes.SYSTEM)
                is CargoMessage.Text -> print(line + "\n", ProcessOutputTypes.STDOUT)
                else -> Unit
            }
        }
        process.waitFor()
        stderr.join(TimeUnit.SECONDS.toMillis(5))
        return Result(process.exitValue(), artifacts)
    }

    private fun lines(input: InputStream, consumer: (String) -> Unit) {
        runCatching { input.bufferedReader(Charsets.UTF_8).forEachLine(consumer) }
    }
}
