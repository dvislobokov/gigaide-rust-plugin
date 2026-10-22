package io.github.rustsupport.debugger

import com.google.gson.JsonObject
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessUtil
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Key
import io.github.rustsupport.toolchain.RustToolchain
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private val LOG = logger<CodeLldbAdapter>()

/**
 * The process of `codelldb` and the protocol connection to it. The adapter connects back to a port of the IDE (`--connect <port>`,
 * as the VS Code extension starts it), not over its standard streams: the program it launches with `terminal: console` inherits
 * the stdout/stderr of the adapter (seen with a probe: the output of the program broke the framing of the protocol over stdio). So
 * stdout of the adapter is the program's, its stderr the program's mixed with the errors the adapter logs (rare: `RUST_LOG` is
 * unset, so only errors); both go to the console. Its stdin is the program's input, typed in the console.
 */
class CodeLldbAdapter private constructor(private val process: Process, private val socket: Socket) {
    val input: InputStream get() = socket.getInputStream()
    val output: OutputStream get() = socket.getOutputStream()

    /** The input of the program (it inherits the stdin of the adapter). */
    val programInput: OutputStream get() = process.outputStream

    val pid: Long get() = process.pid()

    /** A moment to exit by itself after `disconnect`, then the whole process tree (the program with it). */
    fun stop(graceMs: Long = 1500) {
        runCatching { socket.close() }
        if (process.waitFor(graceMs, TimeUnit.MILLISECONDS)) return
        LOG.info("codelldb (pid ${process.pid()}) has not exited by itself, killing the process tree")
        if (!OSProcessUtil.killProcessTree(process)) process.destroyForcibly()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000

        fun commandLine(adapter: Path, port: Int, settings: JsonObject, workDirectory: String?): GeneralCommandLine =
            GeneralCommandLine(adapter.toString(), "--connect", port.toString(), "--settings", DapConnection.GSON.toJson(settings))
                .withWorkingDirectory(workDirectory?.let { Path.of(it) })
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                // rustc and cargo for `lang_support/rust.py` when the sysroot was not known
                .withEnvironment("PATH", RustToolchain.withDirectories(RustToolchain.pathEnv()))

        /** Starts the adapter and waits for it to connect; [print] gets what it and the program print. */
        fun start(adapter: Path, settings: JsonObject, workDirectory: String?, print: (String, Key<*>) -> Unit): CodeLldbAdapter {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                server.soTimeout = CONNECT_TIMEOUT_MS
                val commandLine = commandLine(adapter, server.localPort, settings, workDirectory)
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
                return CodeLldbAdapter(process, socket)
            }
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
