package io.github.rustsupport.run

/** What a cargo command builds or runs of a package: `--bin name`, `--lib`, `--test name` and so on. */
enum class CargoTargetKind(val flag: String?, val hasName: Boolean, val title: String) {
    DEFAULT(null, false, "Default"),
    BIN("--bin", true, "Binary (--bin)"),
    EXAMPLE("--example", true, "Example (--example)"),
    LIB("--lib", false, "Library (--lib)"),
    TEST("--test", true, "Integration test (--test)"),
    BENCH("--bench", true, "Benchmark (--bench)"),
    DOC("--doc", false, "Doc tests (--doc)"),
    BINS("--bins", false, "All binaries (--bins)"),
    TESTS("--tests", false, "All tests (--tests)"),
    ALL_TARGETS("--all-targets", false, "All targets (--all-targets)");

    override fun toString(): String = title

    companion object {
        fun ofFlag(flag: String): CargoTargetKind? = entries.firstOrNull { it.flag == flag }

        /** The kind of a target of `cargo metadata` (`bin`, `lib`, `rlib`, `proc-macro`, `example`, `test`, `bench`). */
        fun ofMetadataKind(kind: String): CargoTargetKind? = when (kind) {
            "bin" -> BIN
            "example" -> EXAMPLE
            "test" -> TEST
            "bench" -> BENCH
            "lib", "rlib", "dylib", "cdylib", "staticlib", "proc-macro" -> LIB
            else -> null
        }
    }
}

/**
 * A cargo command as a configuration holds it, and the arguments it turns into. Pure, for the tests.
 *
 * @param command the subcommand and whatever words follow it as typed: `run`, `test`, `nextest run`.
 * @param cargoArguments the rest of the cargo flags (`--release`, `--target x86_64-pc-windows-gnu`), before `--`.
 * @param programArguments after `--`: the arguments of the program, or of the test harness (the name filter, `--exact`, `--nocapture`).
 */
data class CargoInvocation(
    val command: List<String>,
    val packageName: String? = null,
    val targetKind: CargoTargetKind = CargoTargetKind.DEFAULT,
    val targetName: String? = null,
    val features: String? = null,
    val allFeatures: Boolean = false,
    val noDefaultFeatures: Boolean = false,
    val cargoArguments: List<String> = emptyList(),
    val programArguments: List<String> = emptyList(),
) {
    val subcommand: String get() = command.firstOrNull().orEmpty()

    /** `cargo test` and `cargo bench`: libtest prints what they run, which becomes the test tree. */
    val isTestHarness: Boolean get() = subcommand == "test" || subcommand == "bench"

    /** The arguments after `cargo`; [color]: `--color=always` for the subcommands of cargo itself, which a console with ANSI support shows. */
    fun arguments(color: Boolean = true): List<String> = buildList {
        addAll(command)
        if (color && subcommand in COLORED) add("--color=always")
        addAll(selection())
        addAll(cargoArguments)
        if (programArguments.isNotEmpty()) {
            add("--")
            addAll(programArguments)
        }
    }

    /** Package, target and features: what a build for the debugger must keep. */
    fun selection(): List<String> = buildList {
        packageName?.trim()?.takeIf { it.isNotEmpty() }?.let { add("--package"); add(it) }
        targetKind.flag?.let { flag ->
            val name = targetName?.trim().orEmpty()
            if (!targetKind.hasName) add(flag) else if (name.isNotEmpty()) { add(flag); add(name) }
        }
        features?.trim()?.takeIf { it.isNotEmpty() }?.let { add("--features"); add(it) }
        if (allFeatures) add("--all-features")
        if (noDefaultFeatures) add("--no-default-features")
    }

    /**
     * How the debugger builds the program without running it: `run` is `build` of the same target, `test` and `bench` get `--no-run`;
     * null for the commands that run nothing (check, clippy, build, the commands of other tools).
     */
    fun debugBuildArguments(): List<String>? = when (subcommand) {
        "run" -> listOf("build") + selection() + cargoArguments
        "test", "bench" -> listOf(subcommand) + selection() + cargoArguments.filter { it != "--no-run" } + "--no-run"
        else -> null
    }

    companion object {
        /** The subcommands of cargo that take `--color`. */
        val COLORED = setOf("run", "test", "build", "check", "clippy", "bench", "doc", "fix", "rustc", "rustdoc", "install", "update", "fetch", "metadata", "tree", "clean")

        /** The commands offered in the editor of a configuration; anything else can be typed. */
        val COMMON = listOf("run", "test", "build", "check", "clippy", "bench")

        /** The flags of cargo that take a value as the next argument: they must not be taken for a positional one. */
        private val WITH_VALUE = setOf(
            "--package", "-p", "--bin", "--example", "--test", "--bench", "--features", "-F", "--target", "--target-dir", "--manifest-path",
            "--profile", "--jobs", "-j", "--exclude", "--config", "--message-format", "--color", "-Z",
        )

        /**
         * A command of rust-analyzer (`cargoArgs` of a runnable: `test --package app --lib`) taken apart into the fields of a
         * configuration; `--` and what follows go to the program, as do [executableArguments].
         */
        fun parse(cargoArgs: List<String>, executableArguments: List<String> = emptyList()): CargoInvocation {
            val separator = cargoArgs.indexOf("--")
            val own = if (separator >= 0) cargoArgs.subList(0, separator) else cargoArgs
            val afterSeparator = if (separator >= 0) cargoArgs.subList(separator + 1, cargoArgs.size) else emptyList()
            var packageName: String? = null
            var kind = CargoTargetKind.DEFAULT
            var targetName: String? = null
            val features = ArrayList<String>()
            var allFeatures = false
            var noDefaultFeatures = false
            val rest = ArrayList<String>()
            val words = own.takeWhile { !it.startsWith("-") }
            val command = words.ifEmpty { listOf("run") }
            var i = words.size
            while (i < own.size) {
                val arg = own[i]
                val next = own.getOrNull(i + 1)
                val (flag, inline) = if (arg.startsWith("--") && '=' in arg) arg.substringBefore('=') to arg.substringAfter('=') else arg to null
                val value = inline ?: next
                val consumed = if (inline != null) 1 else 2
                when {
                    (flag == "--package" || flag == "-p") && value != null && packageName == null -> { packageName = value; i += consumed; continue }
                    kind == CargoTargetKind.DEFAULT && CargoTargetKind.ofFlag(flag)?.hasName == true && value != null -> {
                        kind = CargoTargetKind.ofFlag(flag)!!; targetName = value; i += consumed; continue
                    }
                    kind == CargoTargetKind.DEFAULT && inline == null && CargoTargetKind.ofFlag(flag)?.hasName == false -> { kind = CargoTargetKind.ofFlag(flag)!!; i++; continue }
                    (flag == "--features" || flag == "-F") && value != null -> { features += value; i += consumed; continue }
                    flag == "--all-features" -> { allFeatures = true; i++; continue }
                    flag == "--no-default-features" -> { noDefaultFeatures = true; i++; continue }
                    flag in WITH_VALUE && inline == null && next != null -> { rest += arg; rest += next; i += 2; continue }
                    else -> { rest += arg; i++ }
                }
            }
            return CargoInvocation(
                command, packageName, kind, targetName, features.joinToString(",").ifEmpty { null }, allFeatures, noDefaultFeatures, rest,
                afterSeparator + executableArguments,
            )
        }
    }
}

/** The arguments of the test harness when only some tests run again. Pure. */
object CargoTestArguments {
    /** Flags of libtest that take a value. */
    private val WITH_VALUE = setOf("--skip", "--test-threads", "--format", "--color", "--logfile", "-Z", "--shuffle-seed", "--report-time")

    /**
     * The harness arguments of a previous run with its name filters replaced by [failed], matched exactly: `a::b c::d --exact`;
     * the other flags (`--nocapture`, `--test-threads 1`) stay.
     */
    fun rerun(previous: List<String>, failed: List<String>): List<String> {
        val flags = ArrayList<String>()
        var i = 0
        while (i < previous.size) {
            val arg = previous[i]
            when {
                arg.startsWith("-") && arg in WITH_VALUE && '=' !in arg && i + 1 < previous.size -> { flags += arg; flags += previous[i + 1]; i += 2; continue }
                arg.startsWith("-") -> if (arg != "--exact") flags += arg
            }
            i++
        }
        return failed + "--exact" + flags
    }
}
