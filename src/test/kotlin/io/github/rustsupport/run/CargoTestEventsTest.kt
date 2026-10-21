package io.github.rustsupport.run

import jetbrains.buildServer.messages.serviceMessages.ServiceMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CargoTestEventsTest {
    private class Run(val events: List<String>, val console: List<String>)

    /** Every line through the converter: the events in the short form `testStarted s1|a::b`, the lines left to the console. */
    private fun run(output: String, finish: Boolean = true): Run {
        val converter = CargoTestEvents { source, path -> "cargotest://C:/w|$source|$path" }
        val events = ArrayList<String>()
        val console = ArrayList<String>()
        for (line in output.lines()) {
            val converted = converter.convert(line)
            events += converted.messages.map(::short)
            if (converted.toConsole) console += line
        }
        if (finish) events += converter.finish().map(::short)
        return Run(events, console)
    }

    private fun short(text: String): String {
        val message = ServiceMessage.parse(text)!!
        val attributes = message.attributes
        val extra = when (message.messageName) {
            "testFailed" -> " message=" + attributes["message"]
            "testIgnored" -> " message=" + attributes["message"]
            "testStdOut" -> " out=" + attributes["out"]!!.trimEnd()
            "testStarted", "testSuiteStarted" -> " name=" + attributes["name"] + " parent=" + attributes["parentNodeId"]
            else -> ""
        }
        return message.messageName + " " + attributes["nodeId"] + extra
    }

    private val passing = """
           Compiling app v0.1.0 (C:\w)
            Finished `test` profile [unoptimized + debuginfo] target(s) in 0.61s
             Running unittests src\lib.rs (target\debug\deps\app-1f2e3d4c5b6a7980.exe)

        running 3 tests
        test tests::nested::deep ... ok
        test top_level ... ok
        test tests::slow ... ignored, needs the network

        test result: ok. 2 passed; 0 failed; 1 ignored; 0 measured; 0 filtered out; finished in 0.00s

    """.trimIndent()

    @Test
    fun `a passing run makes a tree of modules`() {
        val run = run(passing)
        assertEquals(
            listOf(
                "testSuiteStarted s1 name=unittests src\\lib.rs parent=0",
                "testSuiteStarted s1|tests name=tests parent=s1",
                "testSuiteStarted s1|tests::nested name=nested parent=s1|tests",
                "testStarted s1|tests::nested::deep name=deep parent=s1|tests::nested",
                "testFinished s1|tests::nested::deep",
                "testStarted s1|top_level name=top_level parent=s1",
                "testFinished s1|top_level",
                "testStarted s1|tests::slow name=slow parent=s1|tests",
                "testIgnored s1|tests::slow message=needs the network",
                "testFinished s1|tests::slow",
                "testSuiteFinished s1|tests::nested",
                "testSuiteFinished s1|tests",
                "testSuiteFinished s1",
            ),
            run.events,
        )
        assertTrue(run.console.any { it.contains("Compiling app") })
        assertTrue(run.console.any { it.startsWith("test result: ok.") })
    }

    @Test
    fun `location hints carry the source of the binary`() {
        val converter = CargoTestEvents { source, path -> "cargotest://C:/w|$source|$path" }
        converter.convert("     Running unittests src\\lib.rs (target\\debug\\deps\\app-1f2e.exe)")
        val started = converter.convert("test tests::a ... ok").messages.map { ServiceMessage.parse(it)!! }.first { it.messageName == "testStarted" }
        assertEquals("cargotest://C:/w|src/lib.rs|tests::a", started.attributes["locationHint"])
    }

    private val failing = """
             Running tests\it.rs (target\debug\deps\it-0a1b2c3d4e5f6789.exe)

        running 3 tests
        test adds ... ok
        test fails_assert ... FAILED
        test should_panic_but_not - should panic ... FAILED

        failures:

        ---- fails_assert stdout ----

        thread 'fails_assert' panicked at tests\it.rs:9:5:
        assertion `left == right` failed
          left: 1
         right: 2
        note: run with `RUST_BACKTRACE=1` environment variable to display a backtrace

        ---- should_panic_but_not stdout ----
        note: test did not panic as expected at tests\it.rs:13:4

        failures:
            fails_assert
            should_panic_but_not

        test result: FAILED. 1 passed; 2 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s

        error: test failed, to rerun pass `--test it`
    """.trimIndent()

    @Test
    fun `failures get the output of their section and the panic message`() {
        val run = run(failing)
        assertEquals(
            listOf(
                "testSuiteStarted s1 name=tests\\it.rs parent=0",
                "testStarted s1|adds name=adds parent=s1",
                "testFinished s1|adds",
                "testStarted s1|fails_assert name=fails_assert parent=s1",
                "testStarted s1|should_panic_but_not name=should_panic_but_not parent=s1",
                "testStdOut s1|fails_assert out=thread 'fails_assert' panicked at tests\\it.rs:9:5:\nassertion `left == right` failed\n  left: 1\n right: 2\nnote: run with `RUST_BACKTRACE=1` environment variable to display a backtrace",
                "testFailed s1|fails_assert message=assertion `left == right` failed\n  left: 1\n right: 2",
                "testFinished s1|fails_assert",
                "testStdOut s1|should_panic_but_not out=note: test did not panic as expected at tests\\it.rs:13:4",
                "testFailed s1|should_panic_but_not message=Failed",
                "testFinished s1|should_panic_but_not",
                "testSuiteFinished s1",
            ),
            run.events,
        )
        assertTrue(run.console.contains("    fails_assert"))
        assertTrue(run.console.contains("error: test failed, to rerun pass `--test it`"))
    }

    @Test
    fun `several binaries and doc tests`() {
        val output = """
                 Running unittests src/lib.rs (target/debug/deps/app-1)
            running 1 test
            test a ... ok
            test result: ok. 1 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s
               Doc-tests app
            running 1 test
            test src/lib.rs - add (line 5) ... ok
            test result: ok. 1 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.20s
        """.trimIndent()
        val events = run(output).events
        assertTrue("testSuiteStarted s2 name=Doc-tests app parent=0" in events)
        assertTrue("testStarted s2|src/lib.rs - add (line 5) name=src/lib.rs - add (line 5) parent=s2" in events)
        assertEquals(2, events.count { it.startsWith("testSuiteFinished s") && '|' !in it })
    }

    @Test
    fun `output of a test with nocapture between its name and its result`() {
        val output = """
                 Running unittests src/main.rs (target/debug/deps/app-1)
            running 1 test
            test prints ... hello from the test
            second line
            ok
            test result: ok. 1 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s
        """.trimIndent()
        val run = run(output)
        assertTrue("testStdOut s1|prints out=hello from the test" in run.events)
        assertTrue("testFinished s1|prints" in run.events)
        assertTrue("second line" in run.console)
    }

    @Test
    fun `a crashed binary ends its open tests as failed`() {
        val output = """
                 Running unittests src/lib.rs (target/debug/deps/app-1)
            running 2 tests
            test overflow ... FAILED
            error: test failed, to rerun pass `--lib`
        """.trimIndent()
        val events = run(output).events
        assertEquals(listOf("testFailed s1|overflow message=Failed", "testFinished s1|overflow", "testSuiteFinished s1"), events.takeLast(3))
    }

    @Test
    fun `benchmarks report their numbers`() {
        val output = """
                 Running benches/speed.rs (target/release/deps/speed-1)
            running 1 test
            test bench_add ... bench:          12.50 ns/iter (+/- 0.30)
            test result: ok. 0 passed; 0 failed; 0 ignored; 1 measured; 0 filtered out; finished in 1.20s
        """.trimIndent()
        val events = run(output).events
        assertTrue(events.any { it.startsWith("testStdOut s1|bench_add out=bench:") })
        assertTrue("testFinished s1|bench_add" in events)
    }

    @Test
    fun `colored lines and lines outside of a binary`() {
        val converter = CargoTestEvents()
        assertTrue(converter.convert("\u001B[1m\u001B[32m   Compiling\u001B[0m app v0.1.0").toConsole)
        assertTrue(converter.convert("\u001B[1m\u001B[32m     Running\u001B[0m unittests src/lib.rs (target/debug/deps/app-1)").messages.isNotEmpty())
        assertTrue(converter.convert("not an event").toConsole)
    }

    @Test
    fun `panic messages`() {
        assertEquals("boom", CargoTestEvents.panicMessage("thread 'x' panicked at src/lib.rs:1:1:\nboom\n\n"))
        assertEquals("old style", CargoTestEvents.panicMessage("thread 'x' panicked at 'old style', src/lib.rs:1:1"))
        assertNull(CargoTestEvents.panicMessage("no panic here"))
        assertEquals("a::b", CargoTestEvents.testName("a::b - should panic with 'x'"))
    }
}
