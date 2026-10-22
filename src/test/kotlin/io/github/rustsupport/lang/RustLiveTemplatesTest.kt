package io.github.rustsupport.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/** `liveTemplates/Rust.xml` as the platform reads it: a slip there shows up only as a template that never expands. */
class RustLiveTemplatesTest {
    private val templates: List<Element> = run {
        val stream = javaClass.getResourceAsStream("/liveTemplates/Rust.xml")!!
        val document = stream.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
        val nodes = document.getElementsByTagName("template")
        (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.children(tag: String): List<Element> =
        getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

    @Test
    fun `names are unique and every template has a Rust context`() {
        val names = templates.map { it.getAttribute("name") }
        assertEquals(names.groupBy { it }.filterValues { it.size > 1 }.keys, emptySet<String>())
        for (template in templates) {
            val contexts = template.children("option").filter { it.getAttribute("value") == "true" }.map { it.getAttribute("name") }
            assertTrue("${template.getAttribute("name")}: $contexts", contexts.isNotEmpty() && contexts.all { it in setOf("RUST", "RUST_ITEM", "RUST_CODE") })
        }
    }

    @Test
    fun `every variable of a template is declared`() {
        for (template in templates) {
            val used = Regex("""\$([A-Z_]+)\$""").findAll(template.getAttribute("value")).map { it.groupValues[1] }.toSet() - setOf("END", "SELECTION")
            val declared = template.children("variable").map { it.getAttribute("name") }.toSet()
            assertEquals(template.getAttribute("name"), used, declared)
        }
    }

    @Test
    fun `keywords expand to their whole construct`() {
        val byName = templates.associateBy { it.getAttribute("name") }
        assertEquals("struct \$NAME\$ {\n    \$END\$\n}", byName.getValue("struct").getAttribute("value"))
        for (keyword in listOf("struct", "enum", "trait", "impl", "mod", "fn", "if", "while", "match", "for", "loop", "let", "const", "static", "type", "unsafe")) {
            assertTrue(keyword, keyword in byName)
        }
    }
}
