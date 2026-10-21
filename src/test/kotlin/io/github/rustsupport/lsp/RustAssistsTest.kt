package io.github.rustsupport.lsp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.rustsupport.settings.RustSettings
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.CreateFile
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RustAssistsTest {
    private fun data(id: String) = JsonParser.parseString("""{"id": "$id", "codeActionParams": {}, "version": 1}""")

    @Test
    fun `the id of an assist, from data as seen live or from the title`() {
        assertEquals("generate_new", RustAssists.id(data("generate_new:Generate:3:"), "Generate `new`"))
        assertEquals("add_impl_missing_members", RustAssists.id(data("add_impl_missing_members:QuickFix:0:"), "Implement missing members"))
        assertEquals("generate_getter", RustAssists.id(null, "Generate a getter method"))
        assertEquals("generate_getter_mut", RustAssists.id(null, "Generate a mut getter method"))
        assertEquals("generate_trait_impl", RustAssists.id(null, "Generate trait impl for `Circle`"))
        assertEquals("generate_impl", RustAssists.id(null, "Generate impl for `Circle`"))
        assertEquals("replace_derive_with_manual_impl", RustAssists.id(null, "Convert to manual `impl Debug for Point`"))
        assertEquals("generate_setter", RustAssists.id(mapOf("id" to "generate_setter:Generate:3:"), "whatever"))
        assertNull(RustAssists.id(null, "Convert to tuple struct"))
    }

    @Test
    fun `the menu has the generating assists, the known ones first in their order`() {
        // the answer at a field of a struct, seen live with 1.99.1
        val offered = listOf(
            RustAssists.Offered("change_visibility", "Change visibility to pub(crate)", 0),
            RustAssists.Offered("generate_getter", "Generate a getter method", 1),
            RustAssists.Offered("generate_getter_mut", "Generate a mut getter method", 2),
            RustAssists.Offered("generate_setter", "Generate a setter method", 3),
            RustAssists.Offered("generate_deref", "Generate `Deref` impl using `radius`", 4),
            RustAssists.Offered("generate_new", "Generate `new`", 5),
            RustAssists.Offered("generate_function", "Generate function `foo`", 6),
            RustAssists.Offered("generate_enum_is_method", "Generate an `is_` method for this enum variant", 7),
        )
        val menu = RustAssists.menu(offered)
        assertEquals(
            listOf("generate_new", "generate_getter", "generate_getter_mut", "generate_setter", "generate_deref", "generate_enum_is_method"),
            menu.map { it.id },
        )
        assertEquals(
            listOf("Constructor (new)", "Getter", "Mutable Getter", "Setter", "Generate `Deref` impl using `radius`", "Generate an `is_` method for this enum variant"),
            menu.map { RustAssists.text(it.id, it.title) },
        )
        assertTrue(RustAssists.isGenerate("add_impl_missing_members"))
        assertFalse(RustAssists.isGenerate("convert_named_struct_to_tuple_struct"))
        assertFalse(RustAssists.isGenerate(null))
    }

    private fun edit(line: Int, column: Int, endLine: Int, endColumn: Int, text: String) = RustAnalyzerProtocol.Edit(line, column, endLine, endColumn, text)

    private fun applyAll(text: String, applied: RustAnalyzerProtocol.Applied): String {
        val out = StringBuilder(text)
        for (e in RustAnalyzerProtocol.descending(applied.edits)) out.replace(e.start, e.end, e.text)
        return out.toString()
    }

    @Test
    fun `generate new, as resolved live, lands with the caret on the new fn`() {
        val text = "struct Circle {\n    radius: f64,\n}\n\nimpl Circle {\n    fn grow(&mut self) {}\n}\n"
        // `Generate new` at the struct inserts into the existing impl (seen live: 32:4-32:4)
        val applied = RustAnalyzerProtocol.apply(text, listOf(edit(5, 4, 5, 4, "pub fn new(radius: f64) -> Self {\n        Self { radius }\n    }\n\n    ")))
        val result = applyAll(text, applied)
        assertEquals(
            "struct Circle {\n    radius: f64,\n}\n\nimpl Circle {\n    pub fn new(radius: f64) -> Self {\n        Self { radius }\n    }\n\n    fn grow(&mut self) {}\n}\n",
            result,
        )
        val caret = RustAssists.caret(applied)!!
        assertTrue(result.startsWith("pub fn new", caret))
    }

    @Test
    fun `the caret skips the blank lines an edit starts with, and counts the edits before it`() {
        val text = "use a;\nstruct S { x: u8 }\nimpl S {\n}\n"
        val applied = RustAnalyzerProtocol.apply(text, listOf(
            edit(3, 0, 3, 0, "\n\n    pub fn x(&self) -> u8 {\n        self.x\n    }\n"),
            edit(0, 0, 0, 0, "use b;\n"),
        ))
        val result = applyAll(text, applied)
        val caret = RustAssists.caret(applied)!!
        assertTrue(result.substring(caret), result.startsWith("use b;", caret))
        // the first edit by position is the import here; with a delete before it the shift is negative
        val applied2 = RustAnalyzerProtocol.apply(text, listOf(edit(0, 0, 1, 0, ""), edit(3, 0, 3, 0, "    fn y() {}\n")))
        val result2 = applyAll(text, applied2)
        assertTrue(result2.startsWith("fn y()", RustAssists.caret(applied2)!!))
    }

    @Test
    fun `a snippet caret wins`() {
        val applied = RustAnalyzerProtocol.apply("x", listOf(edit(0, 1, 0, 1, " fn f() { $0 }")))
        assertEquals(11, RustAssists.caret(applied))
        assertNull(RustAssists.caret(RustAnalyzerProtocol.apply("x", listOf(edit(0, 0, 0, 1, "")))))
    }

    @Test
    fun `text edits of a workspace edit, by file`() {
        val documentEdit = TextDocumentEdit(VersionedTextDocumentIdentifier("file:///a.rs", 1), listOf(TextEdit(Range(Position(1, 2), Position(1, 3)), "x")))
        val edit = WorkspaceEdit(listOf(Either.forLeft(documentEdit)))
        edit.changes = mapOf("file:///b.rs" to listOf(TextEdit(Range(Position(0, 0), Position(0, 0)), "y")))
        val byUri = RustAssists.textEdits(edit)!!
        assertEquals(listOf("file:///a.rs", "file:///b.rs"), byUri.keys.toList())
        val a = byUri.getValue("file:///a.rs").single()
        assertEquals(listOf(1, 2, 1, 3), listOf(a.startLine, a.startCharacter, a.endLine, a.endCharacter))
        assertEquals("x", a.newText)
        assertNull(RustAssists.textEdits(WorkspaceEdit(listOf(Either.forRight(CreateFile("file:///c.rs"))))))
    }

    @Test
    fun `lens titles read as the IDE says them`() {
        assertEquals("3 usages", RustLenses.title("3 references"))
        assertEquals("1 usage", RustLenses.title("1 reference"))
        assertEquals("no usages", RustLenses.title("0 references"))
        assertEquals("2 implementations", RustLenses.title("2 implementations"))
        assertTrue(RustLenses.isImplementations("1 implementation"))
        assertFalse(RustLenses.isImplementations("3 references"))
        assertTrue(RustLenses.hidden("0 references", "main"))
        assertFalse(RustLenses.hidden("1 implementation", "main"))
        assertFalse(RustLenses.hidden("0 references", "area"))
    }

    @Test
    fun `the places of showReferences, seen live`() {
        val arguments = JsonParser.parseString(
            """["file:///c%3A/p/src/main.rs", {"line": 2, "character": 10},
               [{"uri": "file:///c%3A/p/src/main.rs", "range": {"start": {"line": 22, "character": 0}, "end": {"line": 29, "character": 1}}},
                {"uri": "file:///c%3A/p/src/main.rs", "range": {"start": {"line": 48, "character": 0}, "end": {"line": 48, "character": 23}}}]]""",
        ).asJsonArray.toList()
        val places = RustLenses.locations(arguments)
        assertEquals(listOf(22, 48), places.map { it.line })
        assertEquals("file:///c%3A/p/src/main.rs", places.first().uri)
        assertTrue(RustLenses.locations(listOf("x")).isEmpty())
        // lsp4j may hand over plain objects as well
        assertEquals(1, RustLenses.locations(listOf("u", null, listOf(mapOf("uri" to "file:///a.rs", "range" to mapOf("start" to mapOf("line" to 1, "character" to 0)))))).size)
    }

    @Test
    fun `lens commands are asked for, the status notification kept`() {
        val experimental = RustAnalyzerCapabilities.withLensCommands(RustAnalyzerCapabilities.withServerStatus(null)) as JsonObject
        assertTrue(experimental.get("serverStatusNotification").asBoolean)
        assertEquals(JsonArray().apply { add("rust-analyzer.showReferences") }, experimental.getAsJsonObject("commands").getAsJsonArray("commands"))
        val map = RustAnalyzerCapabilities.withLensCommands(mapOf("x" to 1)) as Map<*, *>
        assertEquals(1, map["x"])
        assertTrue(map.containsKey("commands"))
    }

    @Test
    fun `lenses follow the switch, runs stay in the gutter`() {
        val settings = RustSettings().apply { codeLens = false }
        val lens = RustAnalyzerOptions.build(settings)["lens"] as Map<*, *>
        assertEquals(false, lens["enable"])
        assertEquals(mapOf("enable" to false), (lens["references"] as Map<*, *>)["method"])
        assertEquals(mapOf("enable" to false), lens["run"])
        val on = RustAnalyzerOptions.build(RustSettings())["lens"] as Map<*, *>
        assertEquals(true, on["enable"])
        assertEquals(mapOf("enable" to true), on["implementations"])
        assertEquals(mapOf("enable" to false), on["debug"])
    }

    @Test
    fun `lsp4j sees the requests the markers and the menu send`() {
        val server = org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints.getSupportedMethods(RustAnalyzerServer::class.java)
        for (method in listOf("textDocument/implementation", "textDocument/declaration", "textDocument/documentSymbol", "textDocument/codeAction", "codeAction/resolve")) {
            assertTrue(method, method in server)
        }
    }

    private fun String.startsWith(prefix: String, at: Int) = regionMatches(at, prefix, 0, prefix.length)
}
