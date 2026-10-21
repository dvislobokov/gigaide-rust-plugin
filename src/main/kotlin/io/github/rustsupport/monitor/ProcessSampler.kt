package io.github.rustsupport.monitor

import com.intellij.openapi.util.SystemInfo
import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

/** What the operating system knows about a process tree at one moment. */
/** [privateBytes]: what the process has committed; pages committed and not touched are in it and not in the working set. */
class ProcessUsage(val cpuTime: Duration, val workingSetBytes: Long, val privateBytes: Long, val threads: Int?, val processes: Int)

/**
 * CPU and memory of a process and its children, from the operating system: rust-analyzer starts cargo, build scripts and proc-macro
 * servers as its children, so the whole tree is measured. CPU time comes from the JVM; memory is asked from the system (the JVM does not report it):
 * the Win32 API, `/proc` on Linux, `ps` on macOS. The same code as in the Go and .NET siblings of this plugin.
 */
object ProcessSampler {
    fun tree(rootPid: Long): List<ProcessHandle> {
        val root = ProcessHandle.of(rootPid).orElse(null) ?: return emptyList()
        return (listOf(root) + root.descendants().toList()).filter { it.isAlive }
    }

    fun sample(processes: List<ProcessHandle>): ProcessUsage {
        var cpu = Duration.ZERO
        var memory = 0L
        var committed = 0L
        var threads: Int? = null
        for (process in processes) {
            cpu += process.info().totalCpuDuration().orElse(Duration.ZERO)
            val (bytes, threadCount) = memoryAndThreads(process.pid())
            memory += bytes.first
            committed += bytes.second
            if (threadCount != null) threads = (threads ?: 0) + threadCount
        }
        return ProcessUsage(cpu, memory, committed, threads, processes.size)
    }

    /** Share of the whole machine (0..100) used between two samples taken [elapsedNanos] apart. */
    fun cpuPercent(previous: Duration, current: Duration, elapsedNanos: Long, processors: Int = Runtime.getRuntime().availableProcessors()): Double {
        if (elapsedNanos <= 0) return 0.0
        // the sum goes down when a child exits
        val used = (current - previous).toNanos().coerceAtLeast(0)
        return (used * 100.0 / elapsedNanos / processors.coerceAtLeast(1)).coerceIn(0.0, 100.0)
    }

    /** (resident, committed) bytes and the number of threads. */
    private fun memoryAndThreads(pid: Long): Pair<Pair<Long, Long>, Int?> = try {
        when {
            SystemInfo.isWindows -> windowsMemory(pid) to null
            SystemInfo.isLinux -> parseProcStatus(File("/proc/$pid/status").readText())
            else -> parsePsRss(run("ps", "-o", "rss=", "-p", pid.toString())).let { it to it } to null
        }
    } catch (_: Throwable) {
        (0L to 0L) to null // the process has just exited, or the system does not let us look
    }

    private fun windowsMemory(pid: Long): Pair<Long, Long> {
        val handle = Kernel32.INSTANCE.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid.toInt()) ?: return 0L to 0L
        try {
            val counters = ProcessMemoryCounters()
            return if (PSAPI.GetProcessMemoryInfo(handle, counters, counters.size())) counters.WorkingSetSize.toLong() to counters.PagefileUsage.toLong() else 0L to 0L
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle)
        }
    }

    /** `VmRSS:     81234 kB`, `VmData:   120000 kB` and `Threads:        17` of `/proc/<pid>/status`. */
    fun parseProcStatus(status: String): Pair<Pair<Long, Long>, Int?> {
        fun field(name: String) = status.lineSequence().firstOrNull { it.startsWith("$name:") }?.substringAfter(':')?.trim()
        fun kb(name: String) = (field(name)?.substringBefore(' ')?.toLongOrNull() ?: 0) * 1024
        return (kb("VmRSS") to kb("VmData")) to field("Threads")?.toIntOrNull()
    }

    /** `ps -o rss=` prints kilobytes. */
    fun parsePsRss(output: String): Long = (output.trim().lineSequence().firstOrNull()?.trim()?.toLongOrNull() ?: 0) * 1024

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(2, TimeUnit.SECONDS)
        return output
    }

    private const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000

    // The JNA bundled with the platform maps psapi.dll without GetProcessMemoryInfo.
    private val PSAPI: PsapiMemory by lazy { Native.load("psapi", PsapiMemory::class.java, W32APIOptions.DEFAULT_OPTIONS) }
}

private interface PsapiMemory : StdCallLibrary {
    fun GetProcessMemoryInfo(process: WinNT.HANDLE, counters: ProcessMemoryCounters, size: Int): Boolean
}

/** PROCESS_MEMORY_COUNTERS of the Win32 API. */
@Structure.FieldOrder(
    "cb", "PageFaultCount", "PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage",
    "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage",
)
class ProcessMemoryCounters : Structure() {
    @JvmField var cb: Int = 0
    @JvmField var PageFaultCount: Int = 0
    @JvmField var PeakWorkingSetSize: BaseTSD.SIZE_T = BaseTSD.SIZE_T()
    @JvmField var WorkingSetSize: BaseTSD.SIZE_T = BaseTSD.SIZE_T()
    @JvmField var QuotaPeakPagedPoolUsage: BaseTSD.SIZE_T = BaseTSD.SIZE_T()
    @JvmField var QuotaPagedPoolUsage: BaseTSD.SIZE_T = BaseTSD.SIZE_T()
    @JvmField var QuotaPeakNonPagedPoolUsage: BaseTSD.SIZE_T = BaseTSD.SIZE_T()
    @JvmField var QuotaNonPagedPoolUsage: BaseTSD.SIZE_T = BaseTSD.SIZE_T()
    @JvmField var PagefileUsage: BaseTSD.SIZE_T = BaseTSD.SIZE_T()
    @JvmField var PeakPagefileUsage: BaseTSD.SIZE_T = BaseTSD.SIZE_T()
}
