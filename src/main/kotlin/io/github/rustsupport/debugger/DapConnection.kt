package io.github.rustsupport.debugger

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import java.time.LocalTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val LOG = logger<DapConnection>()

/** The adapter answered a request with `success: false`; [message] is its text as it is ("Cannot convert 'abc' to int"). */
class DapException(val command: String, override val message: String) : Exception(message)

/** The connection is gone (the adapter has exited or been stopped): every request still waiting ends with this. */
class DapClosedException(message: String) : IOException(message)

/**
 * The Debug Adapter Protocol over the streams of the adapter: `Content-Length` framing, requests matched to their responses by
 * `request_seq` (answers need not come in order), events, the requests of the adapter itself. The plugin's own client, as in the Go and
 * .NET siblings, in place of the one of the platform: the DAP module of the platform (2026.1+) is absent from GIGA IDE and other forks.
 *
 * JSON is Gson and the messages are built by the plugin only.
 */
class DapConnection(
    private val input: InputStream,
    private val output: OutputStream,
    private val listener: Listener,
    /** Every message both ways, for Trace Debugger Protocol; null when off. */
    private val trace: Writer? = null,
) {
    interface Listener {
        fun event(event: String, body: JsonObject)

        /** A request of the adapter (`runInTerminal`, `startDebugging`): the body of the answer, or null to refuse it. */
        fun request(command: String, arguments: JsonObject): JsonObject? = null

        /** The input has ended or broke: the adapter is gone. */
        fun closed() {}
    }

    private val seq = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, Pair<String, CompletableFuture<JsonObject>>>()
    private val closed = AtomicBoolean()
    private val writeLock = Any()

    fun start(name: String = "codelldb") {
        Thread({ readLoop() }, "DAP reader: $name").apply { isDaemon = true }.start()
    }

    /**
     * Sends [command]; the future ends with the `body` of the response (an empty object when it has none) or with [DapException].
     * A request after [close], or one the adapter never answers before it goes away, ends with [DapClosedException].
     */
    fun request(command: String, arguments: JsonElement? = null): CompletableFuture<JsonObject> {
        val future = CompletableFuture<JsonObject>()
        if (closed.get()) return future.apply { completeExceptionally(DapClosedException("The debug adapter is gone ($command)")) }
        val id = seq.getAndIncrement()
        pending[id] = command to future
        val message = JsonObject().apply {
            addProperty("seq", id)
            addProperty("type", "request")
            addProperty("command", command)
            if (arguments != null) add("arguments", arguments)
        }
        try {
            send(message)
        } catch (e: IOException) {
            pending.remove(id)
            future.completeExceptionally(DapClosedException("Cannot send $command: ${e.message}"))
        }
        return future
    }

    /** [request] that gives up after [timeoutMs]: a busy adapter answers nothing at all, `disconnect` included. */
    fun request(command: String, arguments: JsonElement?, timeoutMs: Long): CompletableFuture<JsonObject> =
        request(command, arguments).orTimeout(timeoutMs, TimeUnit.MILLISECONDS)

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        val gone = DapClosedException("The debug adapter is gone")
        pending.values.forEach { (_, future) -> future.completeExceptionally(gone) }
        pending.clear()
        runCatching { trace?.close() }
    }

    val isClosed: Boolean get() = closed.get()

    private fun send(message: JsonObject) {
        val body = GSON.toJson(message).toByteArray(Charsets.UTF_8)
        synchronized(writeLock) {
            output.write(DapFraming.header(body.size))
            output.write(body)
            output.flush()
        }
        trace("->", message)
    }

    private fun readLoop() {
        try {
            while (!closed.get()) {
                val text = DapFraming.read(input) ?: break
                val message = runCatching { JsonParser.parseString(text).asJsonObject }.getOrElse {
                    LOG.warn("Not a DAP message: ${text.take(200)}")
                    continue
                }
                trace("<-", message)
                dispatch(message)
            }
        } catch (e: IOException) {
            if (!closed.get()) LOG.info("The debug adapter has closed the connection: ${e.message}")
        } finally {
            close()
            listener.closed()
        }
    }

    private fun dispatch(message: JsonObject) {
        when (message.string("type")) {
            "response" -> {
                val (command, future) = pending.remove(message.int("request_seq") ?: return) ?: return
                if (message.get("success")?.asBoolean == true) future.complete(message.getAsJsonObject("body") ?: JsonObject())
                else future.completeExceptionally(DapException(command, errorMessage(message)))
            }
            "event" -> runCatching { listener.event(message.string("event").orEmpty(), message.getAsJsonObject("body") ?: JsonObject()) }
                .onFailure { LOG.error("DAP event ${message.string("event")}", it) }
            "request" -> {
                val command = message.string("command").orEmpty()
                val body = runCatching { listener.request(command, message.getAsJsonObject("arguments") ?: JsonObject()) }.getOrNull()
                val response = JsonObject().apply {
                    addProperty("seq", seq.getAndIncrement())
                    addProperty("type", "response")
                    addProperty("request_seq", message.int("seq") ?: 0)
                    addProperty("command", command)
                    addProperty("success", body != null)
                    if (body != null) add("body", body) else addProperty("message", "$command is not supported by this client")
                }
                runCatching { send(response) }
            }
        }
    }

    private fun trace(direction: String, message: JsonObject) {
        val writer = trace ?: return
        runCatching {
            synchronized(writer) {
                writer.write("${LocalTime.now()} $direction ${GSON.toJson(message)}\n")
                writer.flush()
            }
        }
    }

    companion object {
        val GSON: Gson = GsonBuilder().disableHtmlEscaping().create()

        /** `body.error.format` with its `{variables}` filled in, else `message`: what the adapter says went wrong. */
        fun errorMessage(response: JsonObject): String {
            val error = response.getAsJsonObject("body")?.getAsJsonObject("error")
            val format = error?.string("format")
            if (format != null) {
                val variables = error.getAsJsonObject("variables")
                return Regex("\\{(\\w+)}").replace(format) { variables?.string(it.groupValues[1]) ?: it.value }
            }
            return response.string("message")?.takeIf { it.isNotBlank() } ?: "Request failed"
        }
    }
}

/** The framing of DAP: `Content-Length: N` and a blank line before every message. */
object DapFraming {
    fun header(length: Int): ByteArray = "Content-Length: $length\r\n\r\n".toByteArray(Charsets.US_ASCII)

    fun frame(body: String): ByteArray = body.toByteArray(Charsets.UTF_8).let { header(it.size) + it }

    /** The next message, or null when the stream has ended between messages. */
    fun read(input: InputStream): String? {
        var length = -1
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) {
                if (length >= 0) break
                continue
            }
            val (name, value) = line.split(':', limit = 2).takeIf { it.size == 2 } ?: continue
            if (name.trim().equals("Content-Length", ignoreCase = true)) length = value.trim().toIntOrNull() ?: throw IOException("Bad header: $line")
        }
        val body = input.readNBytes(length)
        if (body.size < length) throw IOException("The stream ended in the middle of a message")
        return String(body, Charsets.UTF_8)
    }

    /** A header line without its CR LF; null at the end of the stream before any byte of it. */
    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else throw IOException("The stream ended in a header")
            if (b == '\n'.code) break
            bytes.write(b)
        }
        return bytes.toString(Charsets.US_ASCII).trimEnd('\r')
    }
}

fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
fun JsonObject.int(name: String): Int? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
fun JsonObject.bool(name: String): Boolean? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
fun JsonObject.objects(name: String): List<JsonObject> = getAsJsonArray(name)?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }.orEmpty()

/** A JSON object from Kotlin values: maps, lists, strings, numbers, booleans; nulls are left out. */
fun json(vararg pairs: Pair<String, Any?>): JsonObject = DapConnection.GSON.toJsonTree(pairs.filter { it.second != null }.toMap()).asJsonObject
