package io.github.rustsupport.lsp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class RustAnalyzerProtocolTest {
    private fun json(text: String) = JsonParser.parseString(text)

    private fun applyAll(text: String, applied: RustAnalyzerProtocol.Applied): String {
        val out = StringBuilder(text)
        for (edit in RustAnalyzerProtocol.descending(applied.edits)) out.replace(edit.start, edit.end, edit.text)
        return out.toString()
    }

    /** The platform builds its JSON handler out of these: the methods of the server interface and of the class of the client. */
    @Test
    fun `lsp4j sees the extensions`() {
        val server = org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints.getSupportedMethods(RustAnalyzerServer::class.java)
        for (method in listOf("rust-analyzer/expandMacro", "experimental/parentModule", "experimental/joinLines", "experimental/onEnter", "rust-analyzer/viewSyntaxTree", "textDocument/definition")) {
            assertTrue(method, method in server)
        }
        val client = org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints.getSupportedMethods(RustAnalyzerLsp4jClient::class.java)
        assertTrue("experimental/serverStatus" in client)
        assertTrue("window/logMessage" in client)
    }

    @Test
    fun `expand macro answer, seen live`() {
        val macro = RustAnalyzerProtocol.expandedMacro(json("""{"name": "vec!", "expansion": "\r\nalloc::vec::from_elem(\r\n    0, 3,\r\n)\r\n"}"""))!!
        assertEquals("vec!", macro.name)
        assertEquals(
            "// Recursive expansion of vec! macro\n// " + "=".repeat(33) + "\n\nalloc::vec::from_elem(\n    0, 3,\n)\n",
            RustAnalyzerProtocol.expansionText(macro),
        )
        assertNull(RustAnalyzerProtocol.expandedMacro(json("null")))
        assertNull(RustAnalyzerProtocol.expandedMacro(null))
    }

    @Test
    fun `parent module as locations and as links, seen live`() {
        val locations = RustAnalyzerProtocol.targets(json("""[{"uri": "file:///c:/p/src/main.rs", "range": {"start": {"line": 0, "character": 4}, "end": {"line": 0, "character": 8}}}]"""))
        assertEquals(listOf("file:///c:/p/src/main.rs:0:4"), locations.map { "${it.uri}:${it.line}:${it.character}" })
        val links = RustAnalyzerProtocol.targets(json(
            """[{"targetUri": "file:///c:/p/Cargo.toml", "targetRange": {"start": {"line": 3, "character": 0}, "end": {"line": 4, "character": 0}},
                "targetSelectionRange": {"start": {"line": 3, "character": 2}, "end": {"line": 3, "character": 5}}}]""",
        ))
        assertEquals(listOf("file:///c:/p/Cargo.toml:3:2"), links.map { "${it.uri}:${it.line}:${it.character}" })
        val single = RustAnalyzerProtocol.targets(json("""{"uri": "file:///a.rs", "range": {"start": {"line": 1, "character": 1}, "end": {"line": 1, "character": 1}}}"""))
        assertEquals(1, single.size)
        assertTrue(RustAnalyzerProtocol.targets(json("null")).isEmpty())
    }

    @Test
    fun `join lines edits, seen live`() {
        val text = "fn main() {\n    let x = foo(1,\n        2);\n}\n"
        val edits = RustAnalyzerProtocol.edits(json("""[{"range": {"start": {"line": 1, "character": 18}, "end": {"line": 2, "character": 8}}, "newText": " "}]"""))!!
        val applied = RustAnalyzerProtocol.apply(text, edits)
        assertNull(applied.caret)
        assertEquals("fn main() {\n    let x = foo(1, 2);\n}\n", applyAll(text, applied))
        assertNull(RustAnalyzerProtocol.edits(json("null")))
    }

    @Test
    fun `on enter snippet, the caret at $0, seen live`() {
        val text = "fn foo(a: i32, b: i32) -> i32 { a + b }\n"
        val edits = RustAnalyzerProtocol.edits(json(
            """[{"range": {"start": {"line": 0, "character": 31}, "end": {"line": 0, "character": 38}}, "newText": "\n    ${'$'}0a + b\n", "insertTextFormat": 2}]""",
        ))!!
        val applied = RustAnalyzerProtocol.apply(text, edits)
        val result = applyAll(text, applied)
        assertEquals("fn foo(a: i32, b: i32) -> i32 {\n    a + b\n}\n", result)
        assertEquals(result.indexOf("a + b"), applied.caret)
    }

    @Test
    fun `the caret counts the edits before it, the code in a snippet is not unescaped`() {
        val text = "ab\ncd\n"
        val edits = listOf(
            RustAnalyzerProtocol.Edit(1, 1, 1, 1, "\\\\\${0}"),
            RustAnalyzerProtocol.Edit(0, 0, 0, 1, "XYZ"),
        )
        val applied = RustAnalyzerProtocol.apply(text, edits)
        val result = applyAll(text, applied)
        assertEquals("XYZb\nc\\\\d\n", result)
        assertEquals(result.indexOf("d"), applied.caret)
    }

    @Test
    fun `offsets of protocol positions`() {
        val text = "ab\n\ncdef"
        assertEquals(0, RustAnalyzerProtocol.offset(text, 0, 0))
        assertEquals(2, RustAnalyzerProtocol.offset(text, 0, 10))
        assertEquals(3, RustAnalyzerProtocol.offset(text, 1, 0))
        assertEquals(6, RustAnalyzerProtocol.offset(text, 2, 2))
        assertEquals(text.length, RustAnalyzerProtocol.offset(text, 7, 0))
    }

    @Test
    fun `syntax tree from JSON, seen live`() {
        val text = "mod util;\n"
        val tree = """{"type":"Node","kind":"SOURCE_FILE","start":[0,0,0],"end":[10,1,0],"children":[
            {"type":"Node","kind":"MODULE","start":[0,0,0],"end":[9,0,9],"children":[
              {"type":"Token","kind":"MOD_KW","start":[0,0,0],"end":[3,0,3]},
              {"type":"Token","kind":"WHITESPACE","start":[3,0,3],"end":[4,0,4]},
              {"type":"Node","kind":"NAME","start":[4,0,4],"end":[8,0,8],"children":[{"type":"Token","kind":"IDENT","start":[4,0,4],"end":[8,0,8]}]},
              {"type":"Token","kind":"SEMICOLON","start":[8,0,8],"end":[9,0,9]}]},
            {"type":"Token","kind":"WHITESPACE","start":[9,0,9],"end":[10,1,0]}]}"""
        assertEquals(
            """
            SOURCE_FILE@0..10
              MODULE@0..9
                MOD_KW@0..3 "mod"
                WHITESPACE@3..4 " "
                NAME@4..8
                  IDENT@4..8 "util"
                SEMICOLON@8..9 ";"
              WHITESPACE@9..10 "\n"
            """.trimIndent() + "\n",
            RustAnalyzerProtocol.syntaxTree(tree, text),
        )
        assertEquals("SOURCE_FILE@0..1\n", RustAnalyzerProtocol.syntaxTree("SOURCE_FILE@0..1\n", text))
    }

    @Test
    fun `server status and the experimental capabilities`() {
        val status = RustAnalyzerProtocol.serverStatus(json("""{"health": "warning", "quiescent": true, "message": "Failed to run build scripts"}""").asJsonObject)!!
        assertEquals("warning", status.health)
        assertTrue(status.quiescent)
        assertEquals("Failed to run build scripts", status.message)
        assertEquals("ok", RustAnalyzerProtocol.serverStatus(JsonObject())!!.health)

        val fresh = RustAnalyzerCapabilities.withServerStatus(null) as JsonObject
        assertTrue(fresh.get("serverStatusNotification").asBoolean)
        val kept = RustAnalyzerCapabilities.withServerStatus(mapOf("other" to 1)) as Map<*, *>
        assertEquals(1, kept["other"])
        assertEquals(true, kept["serverStatusNotification"])
    }

    @Test
    fun `health in the status popup`() {
        assertEquals("unknown", RustAnalyzerStatusText.health(null, null))
        assertEquals("ok", RustAnalyzerStatusText.health("ok", null))
        assertEquals("error: Failed to load workspaces", RustAnalyzerStatusText.health("error", "\nFailed to load workspaces\nmore"))
        assertTrue(RustAnalyzerStatusText.health("warning", "x".repeat(200)).length < 100)
    }

    @Test
    fun `log lines`() {
        assertEquals("[09:05:01] started\n", RustAnalyzerLogLines.stamped(LocalTime.of(9, 5, 1), "started"))
        val line = "2026-10-21T19:48:25.1648538+03:00  WARN notify error: Input watch path is neither a file nor a directory."
        assertEquals("[19:48:25] WARN notify error: Input watch path is neither a file nor a directory.", RustAnalyzerLogLines.stderr(line))
        assertEquals("WARN", RustAnalyzerLogLines.level(line))
        assertEquals("ERROR", RustAnalyzerLogLines.level("2026-10-21T19:48:28Z ERROR tried rendering"))
        assertNull(RustAnalyzerLogLines.level("thread 'main' panicked"))
        assertEquals("plain", RustAnalyzerLogLines.stderr("plain"))
        assertEquals("Server health: ok", RustAnalyzerLogLines.health("ok", null))
        assertEquals("Server health: error: no Cargo.toml", RustAnalyzerLogLines.health("error", " no Cargo.toml\n"))
        assertEquals("a &lt;b&gt;<br>c", RustAnalyzerLogLines.html("a <b>\r\nc"))
    }

    @Test
    fun `stderr comes in pieces, the log takes lines`() {
        val splitter = RustAnalyzerLogLines.Splitter()
        assertEquals(emptyList<String>(), splitter.feed("first pa"))
        assertEquals(listOf("first part", "second"), splitter.feed("rt\r\nsecond\nthi"))
        assertEquals("thi", splitter.rest())
        assertNull(splitter.rest())
    }

    @Test
    fun `enter asks the server only right after a brace with code after it`() {
        val text = "fn f() { a + b }\nfn g() {}\nfn h() {\n}\nlet s = \"{ x\";\n"
        assertTrue(RustOnEnterGate.asksServer(text, text.indexOf("{ a") + 1))
        assertFalse(RustOnEnterGate.asksServer(text, text.indexOf("{}") + 1))
        assertFalse(RustOnEnterGate.asksServer(text, text.indexOf("h() {") + 5))
        assertFalse(RustOnEnterGate.asksServer(text, text.indexOf("{ x") + 1))
        assertFalse(RustOnEnterGate.asksServer(text, text.indexOf("a +") + 1))
    }
}
