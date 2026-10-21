package io.github.rustsupport.run

import com.intellij.execution.testframework.sm.ServiceMessageBuilder

/**
 * The text output of `cargo test` (libtest has no JSON on stable Rust) as TeamCity service messages for the test tree of the platform.
 *
 * A test binary (`Running unittests src/lib.rs (target/debug/deps/app-1f2e.exe)`, `Doc-tests app`) is a suite, the modules of a test
 * path (`tests::nested::it_works`) are suites under it. libtest reports a test only when it is over (`test a::b ... ok`), so a test
 * starts and ends at once; a failed one stays open until its section `---- a::b stdout ----` is read, whose text becomes its output
 * and the panic message the message of the failure. Lines that are no events (the build, the program printing with `--nocapture`)
 * are left to the console ([Output.toConsole]).
 */
class CargoTestEvents(
    /** The location hint of a node: the source file of the binary (relative, as cargo prints it, may be empty) and the test path. */
    private val locationHint: (source: String, path: String) -> String? = { _, _ -> null },
) {
    private var suite: Suite? = null
    private var suiteCount = 0
    private var section = Section.NONE
    private var failureName: String? = null
    private val failureOutput = StringBuilder()

    /** A test whose `test x ... ` came without the result: it printed in between (`--nocapture`). */
    private var awaitingResult: String? = null

    private class Suite(val id: String, val source: String, val doc: Boolean) {
        val modules = LinkedHashSet<String>()
        val pendingFailures = LinkedHashSet<String>()
    }

    private enum class Section { NONE, FAILURES, FAILURE_LIST, SUCCESSES }

    /** The messages for a line; [toConsole]: the line is shown in the console as well, after them. */
    class Output(val messages: List<String>, val toConsole: Boolean)

    /** The messages for one line (without its line break, ANSI colors allowed). */
    fun convert(rawLine: String): Output {
        val line = ANSI.replace(rawLine, "").trimEnd('\r', '\n')
        val result = ArrayList<String>()
        RUNNING.matchEntire(line.trim())?.let { match ->
            val source = match.groupValues[1]
            val name = if (source.isNotEmpty()) line.trim().removePrefix("Running ").substringBeforeLast(" (") else match.groupValues[3]
            startSuite(name, source, doc = false, result)
            return Output(result, toConsole = false)
        }
        DOC_TESTS.matchEntire(line.trim())?.let { match ->
            startSuite("Doc-tests ${match.groupValues[1]}", "", doc = true, result)
            return Output(result, toConsole = false)
        }
        val current = suite ?: return Output(result, toConsole = true)
        if (section == Section.FAILURES) {
            FAILURE_HEADER.matchEntire(line)?.let { match ->
                finishFailure(current, result)
                failureName = match.groupValues[1]
                return Output(result, toConsole = false)
            }
            if (line == "failures:") {
                finishFailure(current, result)
                section = Section.FAILURE_LIST
                return Output(result, toConsole = false)
            }
            if (failureName != null) {
                failureOutput.append(rawLine.trimEnd('\r', '\n')).append('\n')
                return Output(result, toConsole = false)
            }
        }
        if (section == Section.SUCCESSES && line == "failures:") {
            section = Section.FAILURES
            return Output(result, toConsole = false)
        }
        when {
            line.startsWith("running ") && RUNNING_TESTS.matches(line) -> return Output(result, toConsole = false)
            line == "failures:" -> { section = Section.FAILURES; return Output(result, toConsole = false) }
            line == "successes:" -> { section = Section.SUCCESSES; return Output(result, toConsole = true) }
            line.startsWith("test result: ") -> {
                finishFailure(current, result)
                finishSuite(current, result)
                return Output(result, toConsole = true)
            }
        }
        TEST_LINE.matchEntire(line)?.let { match ->
            val name = testName(match.groupValues[1])
            val rest = match.groupValues[2]
            val status = STATUS.matchEntire(rest.trim())
            if (status != null) {
                report(current, name, status.groupValues[1], rest.trim(), result)
            } else {
                // `test a ... ` and what the test printed on the same line: the result comes on a line of its own later
                start(current, name, result)
                awaitingResult = name
                if (rest.isNotBlank()) result += ServiceMessageBuilder.testStdOut(leaf(current, name)).addAttribute("nodeId", nodeId(current, name)).addAttribute("out", rest + "\n").toString()
            }
            return Output(result, toConsole = false)
        }
        awaitingResult?.let { name ->
            val status = STATUS.matchEntire(line.trim())
            if (status != null) {
                awaitingResult = null
                finishStarted(current, name, status.groupValues[1], line.trim(), result)
                return Output(result, toConsole = false)
            }
        }
        return Output(result, toConsole = true)
    }

    /** The process has ended: whatever is still open ends with it (a test binary that crashed, a run that was stopped). */
    fun finish(): List<String> {
        val result = ArrayList<String>()
        suite?.let { current ->
            finishFailure(current, result)
            finishSuite(current, result)
        }
        return result
    }

    private fun startSuite(name: String, source: String, doc: Boolean, result: MutableList<String>) {
        suite?.let { previous ->
            finishFailure(previous, result)
            finishSuite(previous, result)
        }
        val id = "s${++suiteCount}"
        val created = Suite(id, source.replace('\\', '/'), doc)
        suite = created
        section = Section.NONE
        result += ServiceMessageBuilder.testSuiteStarted(name).id(id, ROOT).hint(locationHint(created.source, "")).toString()
    }

    private fun finishSuite(current: Suite, result: MutableList<String>) {
        if (suite !== current) return
        awaitingResult?.let { finishStarted(current, it, "FAILED", "", result) }
        awaitingResult = null
        for (name in current.pendingFailures.toList()) failed(current, name, "Failed", "", result)
        current.pendingFailures.clear()
        for (module in current.modules.reversed()) result += ServiceMessageBuilder.testSuiteFinished(module.substringAfterLast("::")).addAttribute("nodeId", "${current.id}|$module").toString()
        result += ServiceMessageBuilder.testSuiteFinished(current.id).addAttribute("nodeId", current.id).toString()
        suite = null
        section = Section.NONE
    }

    private fun report(current: Suite, name: String, status: String, text: String, result: MutableList<String>) {
        start(current, name, result)
        finishStarted(current, name, status, text, result)
    }

    private fun finishStarted(current: Suite, name: String, status: String, text: String, result: MutableList<String>) {
        val id = nodeId(current, name)
        val leaf = leaf(current, name)
        when {
            status == "FAILED" -> current.pendingFailures += name
            status.startsWith("ignored") -> {
                result += ServiceMessageBuilder.testIgnored(leaf).addAttribute("nodeId", id).addAttribute("message", text.substringAfter("ignored, ", "")).toString()
                result += ServiceMessageBuilder.testFinished(leaf).addAttribute("nodeId", id).toString()
            }
            status == "bench" -> {
                result += ServiceMessageBuilder.testStdOut(leaf).addAttribute("nodeId", id).addAttribute("out", text + "\n").toString()
                result += ServiceMessageBuilder.testFinished(leaf).addAttribute("nodeId", id).toString()
            }
            else -> result += ServiceMessageBuilder.testFinished(leaf).addAttribute("nodeId", id).toString()
        }
    }

    private fun start(current: Suite, name: String, result: MutableList<String>) {
        val parent = if (current.doc) current.id else ensureModules(current, name, result)
        result += ServiceMessageBuilder.testStarted(leaf(current, name)).id(nodeId(current, name), parent).hint(locationHint(current.source, name)).toString()
    }

    /** The suites of the modules of a test path, started once; the id of the innermost one, or of the binary. */
    private fun ensureModules(current: Suite, name: String, result: MutableList<String>): String {
        val segments = name.split("::")
        var parent = current.id
        for (i in 1 until segments.size) {
            val module = segments.subList(0, i).joinToString("::")
            val id = "${current.id}|$module"
            if (current.modules.add(module)) {
                result += ServiceMessageBuilder.testSuiteStarted(segments[i - 1]).id(id, parent).hint(locationHint(current.source, module)).toString()
            }
            parent = id
        }
        return parent
    }

    private fun finishFailure(current: Suite, result: MutableList<String>) {
        val name = failureName ?: return
        failureName = null
        val output = failureOutput.toString()
        failureOutput.setLength(0)
        if (name !in current.pendingFailures) return
        current.pendingFailures -= name
        failed(current, name, panicMessage(output) ?: "Failed", output, result)
    }

    private fun failed(current: Suite, name: String, message: String, output: String, result: MutableList<String>) {
        val id = nodeId(current, name)
        val leaf = leaf(current, name)
        val trimmed = output.trim('\n')
        if (trimmed.isNotEmpty()) result += ServiceMessageBuilder.testStdOut(leaf).addAttribute("nodeId", id).addAttribute("out", trimmed + "\n").toString()
        result += ServiceMessageBuilder.testFailed(leaf).addAttribute("nodeId", id).addAttribute("message", message).toString()
        result += ServiceMessageBuilder.testFinished(leaf).addAttribute("nodeId", id).toString()
    }

    private fun nodeId(current: Suite, name: String): String = "${current.id}|$name"

    private fun leaf(current: Suite, name: String): String = if (current.doc) name else name.substringAfterLast("::")

    private fun ServiceMessageBuilder.id(id: String, parent: String): ServiceMessageBuilder = addAttribute("nodeId", id).addAttribute("parentNodeId", parent)
    private fun ServiceMessageBuilder.hint(hint: String?): ServiceMessageBuilder = if (hint == null) this else addAttribute("locationHint", hint)

    companion object {
        const val ROOT = "0"
        private val ANSI = Regex("""\u001B\[[0-9;?]*[A-Za-z]""")

        /** `Running unittests src/lib.rs (target/debug/deps/app-1f2e.exe)`, `Running tests/it.rs (...)`, `Running target/debug/deps/app-1f2e` (old cargo). */
        private val RUNNING = Regex("""Running (?:unittests )?(.+?) \((.+)\)|Running (\S+)""")
        private val DOC_TESTS = Regex("""Doc-tests (\S+)""")
        private val RUNNING_TESTS = Regex("""running \d+ tests?""")
        private val TEST_LINE = Regex("""test (.+?) \.\.\. ?(.*)""")
        private val STATUS = Regex("""(ok|FAILED|ignored|bench)(?:\b.*)?""")
        private val FAILURE_HEADER = Regex("""---- (.+?) stdout ----""")
        private val SHOULD_PANIC = Regex(""" - should panic(?: with .*)?$""")
        private val PANIC_OLD = Regex("""panicked at '(.*)', \S+$""")

        /** `a::b - should panic` is reported by libtest with the suffix; the test is `a::b`. */
        fun testName(reported: String): String = SHOULD_PANIC.replace(reported.trim(), "")

        /**
         * The message of a panic in the output of a failed test: the lines after `thread 'x' panicked at src/lib.rs:10:9:` up to the
         * first blank or `note:` one (Rust 1.73+), or the quoted message of the old `panicked at 'msg', src/lib.rs:10:9`.
         */
        fun panicMessage(output: String): String? {
            val lines = output.lines()
            val index = lines.indexOfFirst { "panicked at " in it }
            if (index < 0) return null
            val panic = lines[index]
            PANIC_OLD.find(panic)?.let { return it.groupValues[1] }
            if (!panic.trimEnd().endsWith(":")) return null
            val message = lines.drop(index + 1).takeWhile { it.isNotBlank() && !it.startsWith("note: ") && !it.startsWith("stack backtrace:") }
            return message.joinToString("\n").takeIf { it.isNotBlank() }
        }
    }
}
