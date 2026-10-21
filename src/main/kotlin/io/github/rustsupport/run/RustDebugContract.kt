package io.github.rustsupport.run

import com.intellij.execution.configurations.RunProfile

/**
 * The contract between the run configurations of the plugin and its debugger: a configuration that can be debugged tells how to build
 * the program and how to start it, the debugger builds it (`cargo <buildArguments> --message-format=json`, the executable taken from
 * the `compiler-artifact` messages) and starts it under the debug adapter.
 */
interface RustDebuggableConfiguration : RunProfile {
    /** Null when this configuration cannot be debugged as it is set up (`cargo check`, `cargo clippy`). */
    fun debugLaunchSpec(): RustDebugLaunchSpec?
}

/**
 * @param buildArguments the cargo command that builds the program without running it: `build --package app --bin app` for a binary,
 *   `test --package app --lib --no-run` for tests; the debugger adds `--message-format=json`.
 * @param programArguments the arguments of the program itself: for tests the test name filter, `--exact`, `--nocapture`.
 */
data class RustDebugLaunchSpec(
    val buildArguments: List<String>,
    val programArguments: List<String>,
    val workingDirectory: String,
    val environment: Map<String, String> = emptyMap(),
)
