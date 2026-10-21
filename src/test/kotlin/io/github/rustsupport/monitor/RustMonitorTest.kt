package io.github.rustsupport.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class RustProcessTreeTest {
    private fun p(pid: Long, parent: Long?, name: String, start: Long = 0) = ProcessEntry(pid, parent, name, name, start)

    @Test fun `cargo run on Windows - the program, not cargo, rustc or conhost`() {
        val entries = listOf(
            p(10, 1, "C:\\Users\\me\\.cargo\\bin\\cargo.exe", 100),
            p(11, 10, "C:\\Windows\\System32\\conhost.exe", 101),
            p(12, 10, "C:\\Users\\me\\.rustup\\toolchains\\stable\\bin\\cargo.exe", 102),
            p(13, 12, "C:\\proj\\target\\debug\\server.exe", 200),
            p(14, 13, "C:\\Windows\\System32\\conhost.exe", 201),
            p(15, 13, "C:\\proj\\target\\debug\\worker.exe", 202),
        )
        val picked = RustProcessTree.pick(10, entries)
        assertEquals(13L, picked.program?.pid)
        assertEquals(listOf(13L, 15L), picked.pids)
        assertFalse(picked.isWholeTree)
    }

    @Test fun `still building - the whole tree`() {
        val entries = listOf(
            p(10, 1, "/home/me/.cargo/bin/cargo"),
            p(11, 10, "/home/me/.rustup/toolchains/stable/bin/rustc"),
            p(12, 10, "/proj/target/debug/build/foo-1234/build-script-build"),
            p(13, 11, "cc"),
        )
        val picked = RustProcessTree.pick(10, entries)
        assertNull(picked.program)
        assertTrue(picked.isWholeTree)
        assertEquals(setOf(10L, 11L, 12L, 13L), picked.pids.toSet())
    }

    @Test fun `a child of a build script is not the program`() {
        val entries = listOf(p(10, 1, "cargo"), p(12, 10, "build-script-build"), p(13, 12, "cmake"))
        assertNull(RustProcessTree.pick(10, entries).program)
    }

    @Test fun `cargo test - the newest test binary, a restart is followed`() {
        val entries = listOf(p(10, 1, "cargo"), p(20, 10, "mylib-ab12cd.exe", 500), p(21, 10, "integration-ff00.exe", 900))
        assertEquals(21L, RustProcessTree.pick(10, entries).program?.pid)
        // same start time: the later entry
        val equal = listOf(p(10, 1, "cargo"), p(30, 10, "a"), p(31, 10, "b"))
        assertEquals(31L, RustProcessTree.pick(10, equal).program?.pid)
    }

    @Test fun `an executable run directly is the program itself`() {
        val entries = listOf(p(10, 1, "/proj/target/release/app"), p(11, 10, "/bin/sh"))
        val picked = RustProcessTree.pick(10, entries)
        assertEquals(10L, picked.program?.pid)
        assertEquals(listOf(10L, 11L), picked.pids)
    }

    @Test fun `cargo subcommands and wrappers are tools`() {
        assertTrue(RustProcessTree.isTool(p(1, null, "cargo-nextest.exe")))
        assertTrue(RustProcessTree.isTool(p(1, null, "/usr/bin/sccache")))
        assertTrue(RustProcessTree.isTool(p(1, null, "build_script_build-0f1e2d.exe")))
        assertFalse(RustProcessTree.isTool(p(1, null, "my-cargo-app")))
    }

    @Test fun `no root - nothing, and a cycle does not hang`() {
        assertTrue(RustProcessTree.pick(99, listOf(p(10, 1, "cargo"))).pids.isEmpty())
        val cycle = listOf(p(10, 11, "cargo"), p(11, 10, "app"))
        assertEquals(listOf(11L), RustProcessTree.descendants(10, cycle).map { it.pid })
    }

    @Test fun `executable names`() {
        assertEquals("cargo", RustProcessTree.executableName("C:\\x\\Cargo.EXE"))
        assertEquals("rustc", RustProcessTree.executableName("/usr/bin/rustc"))
    }
}

class MonitorHistoryTest {
    private fun sample(time: Long, ws: Long, threads: Int? = 4, name: String = "app") =
        MonitorSample(time, 42, name, 12.345, ws, ws * 2, threads, 2, 0, false)

    @Test fun `bounded history keeps the newest and the peak of the session`() {
        val history = MonitorHistory(capacity = 3)
        listOf(5L, 50L, 7L, 8L, 9L).forEachIndexed { i, ws -> history.add(sample(i.toLong(), ws)) }
        assertEquals(listOf(2L, 3L, 4L), history.samples().map { it.timeMillis })
        assertEquals(50L, history.peakWorkingSetBytes)
        history.clear()
        assertEquals(0L, history.peakWorkingSetBytes)
        assertTrue(history.samples().isEmpty())
    }

    @Test fun `csv rows`() {
        val csv = MonitorCsv.format(listOf(sample(0, 1024), sample(1000, 2048, threads = null, name = "a,\"b\"")))
        assertEquals(
            MonitorCsv.HEADER + "\n" +
                "1970-01-01T00:00:00Z,42,app,12.3,1024,2048,4,2\n" +
                "1970-01-01T00:00:01Z,42,\"a,\"\"b\"\"\",12.3,2048,4096,,2\n",
            csv,
        )
    }

    @Test fun `uptime`() {
        assertEquals("0:42", MonitorFormats.uptime(Duration.ofSeconds(42)))
        assertEquals("12:05", MonitorFormats.uptime(Duration.ofSeconds(725)))
        assertEquals("1:02:03", MonitorFormats.uptime(Duration.ofSeconds(3723)))
        assertEquals("0:00", MonitorFormats.uptime(Duration.ofSeconds(-5)))
    }
}
