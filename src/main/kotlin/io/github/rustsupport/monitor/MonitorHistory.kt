package io.github.rustsupport.monitor

import java.time.Duration
import java.time.Instant
import java.util.Locale

/** One second of a run: [pid] is the program measured (or the root of the run while no program is found). */
class MonitorSample(
    val timeMillis: Long,
    val pid: Long,
    val programName: String,
    val cpuPercent: Double,
    val workingSetBytes: Long,
    val privateBytes: Long,
    /** Null where the operating system does not tell (macOS through `ps`). */
    val threads: Int?,
    val processes: Int,
    /** When the program started, 0 when unknown: the uptime of the header. */
    val programStartMillis: Long,
    val wholeTree: Boolean,
    /** Stopped at a breakpoint or a step of the debugger: its CPU is not the program's to judge. */
    val paused: Boolean = false,
)

/** The last [capacity] samples (ten minutes at one a second) and the peak of the working set over the whole session. */
class MonitorHistory(private val capacity: Int = CAPACITY) {
    private val samples = ArrayDeque<MonitorSample>()

    var peakWorkingSetBytes: Long = 0
        private set

    @Synchronized fun add(sample: MonitorSample) {
        samples.addLast(sample)
        while (samples.size > capacity) samples.removeFirst()
        peakWorkingSetBytes = maxOf(peakWorkingSetBytes, sample.workingSetBytes)
    }

    @Synchronized fun samples(): List<MonitorSample> = samples.toList()

    @Synchronized fun clear() {
        samples.clear()
        peakWorkingSetBytes = 0
    }

    companion object {
        const val CAPACITY = 600
    }
}

/** Export Samples to CSV: one row a second, numbers in plain units (bytes, percent with a dot) for a spreadsheet or a script. */
object MonitorCsv {
    const val HEADER = "time,pid,program,cpu_percent,working_set_bytes,private_bytes,threads,processes"

    fun format(samples: List<MonitorSample>): String = buildString {
        append(HEADER).append('\n')
        for (sample in samples) {
            append(Instant.ofEpochMilli(sample.timeMillis)).append(',')
            append(sample.pid).append(',')
            append(escape(sample.programName)).append(',')
            append(String.format(Locale.ROOT, "%.1f", sample.cpuPercent)).append(',')
            append(sample.workingSetBytes).append(',')
            append(sample.privateBytes).append(',')
            append(sample.threads?.toString().orEmpty()).append(',')
            append(sample.processes).append('\n')
        }
    }

    /** RFC 4180: a value with a comma, a quote or a line break goes in quotes, its quotes doubled. */
    fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
}

object MonitorFormats {
    /** The line under the header: what is measured, and for a debug session whether the program runs or is suspended. */
    fun status(name: String, debug: Boolean, sample: MonitorSample): String = when {
        debug && sample.wholeTree -> "$name: starting under the debugger, the whole tree is measured"
        debug && sample.paused -> "$name: suspended in the debugger"
        debug -> "$name: running under the debugger, the program and its children"
        sample.wholeTree -> "$name: cargo is building, the whole tree is measured"
        else -> "$name: the program and its children"
    }

    /** `0:42`, `12:05`, `1:02:03`. */
    fun uptime(duration: Duration): String {
        val seconds = duration.seconds.coerceAtLeast(0)
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }
}
