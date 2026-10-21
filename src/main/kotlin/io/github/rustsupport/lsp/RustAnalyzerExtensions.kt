package io.github.rustsupport.lsp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture

/**
 * The requests rust-analyzer has beyond the protocol (https://rust-analyzer.github.io/book/contributing/lsp-extensions.html). The platform
 * makes its proxy of the server out of this interface ([RustAnalyzerDescriptor.lsp4jServerClass]), so the platform connection carries them.
 * The answers are raw JSON, read by [RustAnalyzerProtocol]: several of them are one of a few shapes, which lsp4j would not tell apart.
 */
interface RustAnalyzerServer : LanguageServer {
    /** `{name, expansion}` or null when there is no macro call at the position. */
    @JsonRequest("rust-analyzer/expandMacro")
    fun expandMacro(params: TextDocumentPositionParams): CompletableFuture<JsonElement?>

    /** `Location[]` or `LocationLink[]` (seen live: a `mod` item for a module file, `Cargo.toml` for a crate root). */
    @JsonRequest("experimental/parentModule")
    fun parentModule(params: TextDocumentPositionParams): CompletableFuture<JsonElement?>

    /** `TextEdit[]`. */
    @JsonRequest("experimental/joinLines")
    fun joinLines(params: JoinLinesParams): CompletableFuture<JsonElement?>

    /** `SnippetTextEdit[]` with `$0` for the caret, or null when Enter is to do what it always does. */
    @JsonRequest("experimental/onEnter")
    fun onEnter(params: TextDocumentPositionParams): CompletableFuture<JsonElement?>

    /** A string of JSON: the tree of the file (seen live with 1.99.1; older servers answered with text). */
    @JsonRequest("rust-analyzer/viewSyntaxTree")
    fun viewSyntaxTree(params: TextDocumentParams): CompletableFuture<JsonElement?>

    @JsonRequest("rust-analyzer/viewHir")
    fun viewHir(params: TextDocumentPositionParams): CompletableFuture<JsonElement?>

    @JsonRequest("rust-analyzer/viewMir")
    fun viewMir(params: TextDocumentPositionParams): CompletableFuture<JsonElement?>

    @JsonRequest("rust-analyzer/viewItemTree")
    fun viewItemTree(params: TextDocumentParams): CompletableFuture<JsonElement?>

    /** Graphviz `dot`. */
    @JsonRequest("rust-analyzer/viewCrateGraph")
    fun viewCrateGraph(params: CrateGraphParams): CompletableFuture<JsonElement?>

    @JsonRequest("rust-analyzer/analyzerStatus")
    fun analyzerStatus(params: TextDocumentParams): CompletableFuture<JsonElement?>

    /** The answer is kept as JSON: it is read by [io.github.rustsupport.run.CargoRunnables], which the main part of the plugin owns. */
    @JsonRequest("experimental/runnables")
    fun runnables(params: RunnablesParams): CompletableFuture<JsonElement?>
}

/** The client capabilities rust-analyzer reads under `experimental`; pure, for the tests. */
object RustAnalyzerCapabilities {
    /** [experimental] of the platform (null today) with `serverStatusNotification: true` added, whatever it already holds kept. */
    fun withServerStatus(experimental: Any?): Any = when (experimental) {
        is JsonObject -> experimental.deepCopy().apply { addProperty("serverStatusNotification", true) }
        is Map<*, *> -> LinkedHashMap<Any?, Any?>(experimental).apply { put("serverStatusNotification", true) }
        else -> JsonObject().apply { addProperty("serverStatusNotification", true) }
    }
}

class JoinLinesParams(@JvmField val textDocument: TextDocumentIdentifier, @JvmField val ranges: List<Range>)
class TextDocumentParams(@JvmField val textDocument: TextDocumentIdentifier?)
class CrateGraphParams(@JvmField val full: Boolean)

/**
 * The client side of the connection, with what rust-analyzer tells beyond the protocol: `experimental/serverStatus` (asked for in
 * [RustAnalyzerDescriptor.clientCapabilities]). lsp4j finds the method on the class of the client, the platform takes its methods too.
 */
class RustAnalyzerLsp4jClient(handler: LspServerNotificationsHandler, private val status: (RustAnalyzerProtocol.ServerStatus) -> Unit) : Lsp4jClient(handler) {
    @JsonNotification("experimental/serverStatus")
    fun serverStatus(params: JsonObject?) {
        RustAnalyzerProtocol.serverStatus(params)?.let(status)
    }
}

/** Sending the requests of [RustAnalyzerServer]: the answer or null, never an exception out of a server that is busy, gone or failing. */
object RustAnalyzerRequests {
    fun send(client: LspClient, method: String, timeoutMs: Int, call: (RustAnalyzerServer) -> CompletableFuture<JsonElement?>): JsonElement? = try {
        client.sendRequestSync(timeoutMs) { server ->
            if (server is RustAnalyzerServer) call(server)
            else CompletableFuture.failedFuture(IllegalStateException("the connection has no rust-analyzer requests (${server.javaClass.name})"))
        }
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Exception) {
        RustAnalyzerLog.getInstance(client.project).error("$method has failed: ${e.message ?: e.javaClass.simpleName}")
        null
    }
}

/** What the answers of rust-analyzer say; pure, for the tests. */
object RustAnalyzerProtocol {
    class Edit(val startLine: Int, val startCharacter: Int, val endLine: Int, val endCharacter: Int, val newText: String)

    class Target(val uri: String, val line: Int, val character: Int)

    class ExpandedMacro(val name: String, val expansion: String)

    class ServerStatus(val health: String, val quiescent: Boolean, val message: String?)

    /** An edit as offsets of the text: a replacement of [start, end) with [text]. */
    class OffsetEdit(val start: Int, val end: Int, val text: String)

    /** The edits as offsets of the original text, the caret (where `$0` was) as an offset of the text after them, if any. */
    class Applied(val edits: List<OffsetEdit>, val caret: Int?)

    private fun JsonElement?.obj(): JsonObject? = this?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.int(name: String): Int? = get(name)?.takeIf { it.isJsonPrimitive }?.asInt

    fun expandedMacro(answer: JsonElement?): ExpandedMacro? {
        val o = answer.obj() ?: return null
        return ExpandedMacro(o.string("name") ?: return null, o.string("expansion") ?: return null)
    }

    /** The text of the tab: a heading as VS Code has it, the lines of the expansion without the `\r` of Windows (seen live). */
    fun expansionText(macro: ExpandedMacro): String {
        val name = macro.name.removeSuffix("!")
        val heading = "// Recursive expansion of $name! macro"
        return heading + "\n// " + "=".repeat(heading.length - 3) + "\n\n" + normalize(macro.expansion).trim('\n') + "\n"
    }

    fun normalize(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    fun serverStatus(params: JsonObject?): ServerStatus? {
        if (params == null) return null
        return ServerStatus(params.string("health") ?: "ok", params.get("quiescent")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true, params.string("message"))
    }

    private fun position(o: JsonObject?): Pair<Int, Int>? = o?.let { (it.int("line") ?: return null) to (it.int("character") ?: return null) }

    /** `Location`, `Location[]` or `LocationLink[]`: where they lead (a link leads to its selection range). */
    fun targets(answer: JsonElement?): List<Target> {
        val items: List<JsonElement> = when {
            answer == null || answer.isJsonNull -> emptyList()
            answer.isJsonArray -> answer.asJsonArray.toList()
            else -> listOf(answer)
        }
        return items.mapNotNull { item ->
            val o = item.obj() ?: return@mapNotNull null
            val uri = o.string("targetUri") ?: o.string("uri") ?: return@mapNotNull null
            val range = (o.get("targetSelectionRange") ?: o.get("targetRange") ?: o.get("range")).obj()
            val (line, character) = position(range?.get("start").obj()) ?: (0 to 0)
            Target(uri, line, character)
        }
    }

    /** `TextEdit[]` or `SnippetTextEdit[]`; null for an answer that is not a list (onEnter: "do what Enter does"). */
    fun edits(answer: JsonElement?): List<Edit>? {
        if (answer == null || !answer.isJsonArray) return null
        return answer.asJsonArray.mapNotNull { item ->
            val o = item.obj() ?: return@mapNotNull null
            val range = o.get("range").obj() ?: return@mapNotNull null
            val (sl, sc) = position(range.get("start").obj()) ?: return@mapNotNull null
            val (el, ec) = position(range.get("end").obj()) ?: return@mapNotNull null
            Edit(sl, sc, el, ec, o.string("newText") ?: "")
        }
    }

    /** The offset of a protocol position: columns are UTF-16 units, as Java strings count; out-of-range ones are clamped to the line. */
    fun offset(text: CharSequence, line: Int, character: Int): Int {
        var start = 0
        var current = 0
        while (current < line) {
            val next = text.indexOf('\n', start)
            if (next < 0) return text.length
            start = next + 1
            current++
        }
        val lineEnd = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        return (start + character).coerceAtMost(lineEnd)
    }

    private fun CharSequence.indexOf(c: Char, from: Int): Int {
        for (i in from until length) if (this[i] == c) return i
        return -1
    }

    /**
     * The edits as offsets, with the snippet caret taken out: `$0` or `${0}` (the first one) is where the caret goes. Nothing else of the
     * snippet syntax is touched: rust-analyzer puts the code it moves into the snippet as it is (seen live: `\n    $0a + b\n` for
     * `{ a + b }`), so an unescape would change a `\\` of the code.
     */
    fun apply(text: CharSequence, edits: List<Edit>): Applied {
        var caretEdit = -1
        var caretInText = -1
        val offsetEdits = edits.mapIndexed { index, edit ->
            var newText = normalize(edit.newText)
            if (caretEdit < 0) {
                val marker = CARET_MARKERS.firstNotNullOfOrNull { m -> newText.indexOf(m).takeIf { it >= 0 }?.let { it to m } }
                if (marker != null) {
                    caretEdit = index
                    caretInText = marker.first
                    newText = newText.removeRange(marker.first, marker.first + marker.second.length)
                }
            }
            OffsetEdit(offset(text, edit.startLine, edit.startCharacter), offset(text, edit.endLine, edit.endCharacter), newText)
        }
        val caret = if (caretEdit < 0) null else {
            val target = offsetEdits[caretEdit]
            // what the edits before it have added or taken away
            val shift = offsetEdits.filterIndexed { i, e -> i != caretEdit && e.start < target.start }.sumOf { it.text.length - (it.end - it.start) }
            target.start + shift + caretInText
        }
        return Applied(offsetEdits, caret)
    }

    private val CARET_MARKERS = listOf("\${0}", "$0")

    /** The edits in the order they are applied to a document: from the end, so that the offsets of the others hold. */
    fun descending(edits: List<OffsetEdit>): List<OffsetEdit> = edits.sortedWith(compareByDescending<OffsetEdit> { it.start }.thenByDescending { it.end })

    /**
     * The syntax tree as text, `KIND@start..end` per node and the text of a token after it, out of the JSON rust-analyzer answers with
     * (`{"type":"Node","kind":"FN","start":[offset,line,column],"end":[...],"children":[...]}`); an answer that is not JSON is shown as it is.
     */
    fun syntaxTree(answer: String, fileText: CharSequence): String {
        val root = runCatching { JsonParser.parseString(answer) }.getOrNull()?.obj() ?: return answer
        val out = StringBuilder()
        fun walk(node: JsonObject, depth: Int) {
            val start = node.get("start")?.takeIf { it.isJsonArray }?.asJsonArray
            val end = node.get("end")?.takeIf { it.isJsonArray }?.asJsonArray
            out.append("  ".repeat(depth)).append(node.string("kind") ?: "?")
            if (start != null && end != null && start.size() > 0 && end.size() > 0) out.append('@').append(start[0].asInt).append("..").append(end[0].asInt)
            if (node.string("type") == "Token" && start != null && end != null) tokenText(fileText, start, end)?.let { out.append(' ').append(quote(it)) }
            out.append('\n')
            node.get("children")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { child -> child.obj()?.let { walk(it, depth + 1) } }
        }
        walk(root, 0)
        return out.toString()
    }

    /** The text of a token by its line and column (the first number is in UTF-8 bytes, which a Java string does not count in). */
    private fun tokenText(text: CharSequence, start: JsonArray, end: JsonArray): String? {
        if (start.size() < 3 || end.size() < 3) return null
        val from = offset(text, start[1].asInt, start[2].asInt)
        val to = offset(text, end[1].asInt, end[2].asInt)
        return if (from in 0..to && to <= text.length) text.subSequence(from, to).toString() else null
    }

    private fun quote(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").replace("\"", "\\\"") + "\""

    /** A string answer (viewHir, viewMir, viewItemTree, viewCrateGraph, analyzerStatus). */
    fun text(answer: JsonElement?): String? = answer?.takeIf { it.isJsonPrimitive }?.asString?.let(::normalize)
}
