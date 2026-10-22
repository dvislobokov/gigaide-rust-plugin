package io.github.rustsupport.debugger

import com.google.gson.JsonObject
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessUtil
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Key
import io.github.rustsupport.toolchain.RustToolchain
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private val LOG = logger<DebugAdapterProcess>()

/**
 * The process of a debug adapter and the protocol streams to it, as each adapter takes them.
 *
 * CodeLLDB connects back to a port of the IDE (`--connect <port>`, as the VS Code extension starts it), not over its standard streams:
 * the program it launches with `terminal: console` inherits the stdout/stderr of the adapter (seen with a probe: the output of the
 * program broke the framing of the protocol over stdio). So stdout of the adapter is the program's, its stderr the program's mixed with
 * the errors the adapter logs (rare: `RUST_LOG` is unset, so only errors); both go to the console. Its stdin is the program's input.
 *
 * lldb-dap speaks over its standard streams (its default mode, in every LLVM release that has it): it reads the output of the program
 * itself and sends it as `output` events, and has no way to the input of the program but `runInTerminal`, which the IDE does not offer.
 */
class DebugAdapterProcess private constructor(
    private val process: Process,
    val input: InputStream,
    val output: OutputStream,
    /** The input of the program, or null when the adapter keeps it to itself. */
    val programInput: OutputStream?,
    private val connection: Closeable?,
) {
    val pid: Long get() = process.pid()

    /** A moment to exit by itself after `disconnect`, then the whole process tree (the program with it). */
    fun stop(graceMs: Long = 1500) {
        runCatching { connection?.close() }
        if (process.waitFor(graceMs, TimeUnit.MILLISECONDS)) return
        LOG.info("The debug adapter (pid ${process.pid()}) has not exited by itself, killing the process tree")
        if (!OSProcessUtil.killProcessTree(process)) process.destroyForcibly()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000

        private fun commandLine(adapter: Path, workDirectory: String?, vararg args: String): GeneralCommandLine =
            GeneralCommandLine(adapter.toString(), *args)
                .withWorkingDirectory(workDirectory?.let { Path.of(it) })
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                // rustc and cargo for `lang_support/rust.py` of CodeLLDB when the sysroot was not known
                .withEnvironment(RustToolchain.environment())

        fun codeLldbCommandLine(adapter: Path, port: Int, settings: JsonObject, workDirectory: String?): GeneralCommandLine =
            commandLine(adapter, workDirectory, "--connect", port.toString(), "--settings", DapConnection.GSON.toJson(settings))

        /** Starts CodeLLDB and waits for it to connect; [print] gets what it and the program print. */
        fun startCodeLldb(adapter: Path, settings: JsonObject, workDirectory: String?, print: (String, Key<*>) -> Unit): DebugAdapterProcess {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                server.soTimeout = CONNECT_TIMEOUT_MS
                val commandLine = codeLldbCommandLine(adapter, server.localPort, settings, workDirectory)
                LOG.info("Starting ${commandLine.commandLineString}")
                val process = commandLine.createProcess()
                pump(process.inputStream, "codelldb stdout") { print(it, ProcessOutputTypes.STDOUT) }
                pump(process.errorStream, "codelldb stderr") { print(it, ProcessOutputTypes.STDERR) }
                val socket = try {
                    server.accept().apply { tcpNoDelay = true }
                } catch (e: SocketTimeoutException) {
                    if (!OSProcessUtil.killProcessTree(process)) process.destroyForcibly()
                    throw ExecutionException("codelldb has not connected in ${CONNECT_TIMEOUT_MS / 1000} s" + (if (process.isAlive) "" else " (it has exited with ${process.exitValue()})"))
                }
                return DebugAdapterProcess(process, socket.getInputStream(), socket.getOutputStream(), process.outputStream, socket)
            }
        }

        /** Starts lldb-dap over its standard streams; its stderr (its own errors) goes to the console. */
        fun startLldbDap(adapter: Path, workDirectory: String?, print: (String, Key<*>) -> Unit): DebugAdapterProcess {
            val commandLine = commandLine(adapter, workDirectory)
            LOG.info("Starting ${commandLine.commandLineString}")
            val process = commandLine.createProcess()
            pump(process.errorStream, "lldb-dap stderr") { print(it, ProcessOutputTypes.STDERR) }
            return DebugAdapterProcess(process, process.inputStream, process.outputStream, null, null)
        }

        /** Text as it comes, not by lines: a prompt without a line end is shown at once. The program writes UTF-8, as Rust does. */
        private fun pump(stream: InputStream, name: String, consumer: (String) -> Unit) {
            Thread({
                runCatching {
                    InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                        val buffer = CharArray(8192)
                        while (true) {
                            val n = reader.read(buffer)
                            if (n < 0) break
                            if (n > 0) consumer(String(buffer, 0, n))
                        }
                    }
                }
            }, name).apply { isDaemon = true }.start()
        }
    }
}
