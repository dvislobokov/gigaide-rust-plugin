package io.github.rustsupport.lsp

import io.github.rustsupport.lsp.RustImplMarkers.Item
import io.github.rustsupport.lsp.RustImplMarkers.Kind
import io.github.rustsupport.lsp.RustImplMarkers.Symbol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RustImplMarkersTest {
    private val text = """
        use std::fmt;

        pub trait Shape {
            fn area(&self) -> f64;
            fn name(&self) -> String {
                "shape".to_string()
            }
        }

        pub struct Circle {
            radius: f64,
        }

        impl Shape for Circle {
            fn area(&self) -> f64 {
                3.14 * self.radius * self.radius
            }
        }

        impl Circle {
            fn grow(&mut self) {}
        }

        impl fmt::Display for Circle {
            fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                write!(f, "{}", self.radius)
            }
        }
    """.trimIndent() + "\n"

    private val index = RustImplMarkers.LineIndex(text)
    private fun at(line: Int, column: Int) = index.offset(line, column)

    /** The answer of rust-analyzer 1.99.1 to `textDocument/documentSymbol` for this text (the shape seen live: kinds 11, 23, 19, 6). */
    private val symbols = listOf(
        Symbol("Shape", 11, at(2, 0), at(2, 10), listOf(Symbol("area", 6, at(3, 4), at(3, 7)), Symbol("name", 6, at(4, 4), at(4, 7)))),
        Symbol("Circle", 23, at(9, 0), at(9, 11), listOf(Symbol("radius", 8, at(10, 4), at(10, 4)))),
        Symbol("impl Shape for Circle", 19, at(13, 0), at(13, 15), listOf(Symbol("area", 6, at(14, 4), at(14, 7)))),
        Symbol("impl Circle", 19, at(19, 0), at(19, 5), listOf(Symbol("grow", 6, at(20, 4), at(20, 7)))),
        Symbol("impl fmt::Display for Circle", 19, at(23, 0), at(23, 22), listOf(Symbol("fmt", 6, at(24, 4), at(24, 7)))),
    )

    @Test
    fun `traits, trait impls and their items get icons, inherent impls none`() {
        val items = RustImplMarkers.items(symbols, text)
        assertEquals(
            listOf(
                Item(Kind.TRAIT, "Shape", at(2, 10), null),
                Item(Kind.TRAIT_MEMBER, "area", at(3, 7), "Shape"),
                Item(Kind.TRAIT_MEMBER, "name", at(4, 7), "Shape"),
                Item(Kind.TRAIT_IMPL, "Shape", at(13, 5), "Shape"),
                Item(Kind.IMPL_MEMBER, "area", at(14, 7), "Shape"),
                Item(Kind.TRAIT_IMPL, "Display", at(23, 10), "Display"),
                Item(Kind.IMPL_MEMBER, "fmt", at(24, 7), "Display"),
            ),
            items,
        )
        for (item in items) assertTrue(item.toString(), text.startsWith(item.name, item.offset))
        assertFalse(RustImplMarkers.misplaced(symbols, text))
    }

    @Test
    fun `an answer about an older text puts no icon on the wrong token`() {
        // a line typed above the trait: the server still speaks of the old positions
        val newer = "// a comment\n$text"
        val items = RustImplMarkers.items(symbols, newer)
        assertTrue(items.none { it.kind == Kind.TRAIT || it.kind == Kind.TRAIT_MEMBER || it.kind == Kind.IMPL_MEMBER })
        assertTrue(RustImplMarkers.misplaced(symbols, newer))
    }

    @Test
    fun `items inside a module are found`() {
        val module = Symbol("shapes", 2, 0, 4, symbols)
        assertEquals(7, RustImplMarkers.items(listOf(module), text).size)
    }

    private fun trait(header: String): String? {
        val typeStart = header.lastIndexOf(" for ").let { if (it < 0) -1 else it + 5 }
        return RustImplMarkers.traitOfImpl(header, 0, typeStart)?.let { (offset, name) ->
            assertEquals(name, header.substring(offset, offset + name.length))
            name
        }
    }

    @Test
    fun `the trait of an impl header`() {
        assertEquals("Shape", trait("impl Shape for Circle {"))
        assertEquals("Display", trait("impl fmt::Display for Circle {"))
        assertEquals("Debug", trait("impl<T: Display + Clone> std::fmt::Debug for Wrapper<T> {"))
        assertEquals("From", trait("impl From<Vec<u8>> for Bytes {"))
        assertEquals("Iterator", trait("unsafe impl<'a, T> Iterator for Iter<'a, T> where T: 'a {"))
        assertEquals("Send", trait("impl !Send for Handle {}"))
        assertEquals("Fn", trait("#[allow(dead_code)]\n/// docs\nimpl<F> Fn<(u8,)> for F {"))
        assertEquals("Callback", trait("impl Callback for for<'a> fn(&'a u8) {"))
        assertEquals("Trait", trait("impl<T> Trait for &mut T {"))
    }

    @Test
    fun `an inherent impl has no trait`() {
        assertNull(RustImplMarkers.traitOfImpl("impl Circle {\n fn f() { for x in y {} }\n}", 0, 5))
        assertNull(RustImplMarkers.traitOfImpl("impl<T> Wrapper<T> where T: for<'a> Fn(&'a u8) {", 0, 8))
        assertNull(RustImplMarkers.traitOfImpl("impl<T> Wrapper<T> {", 0, -1))
    }

    @Test
    fun `a name is where the answer says only as a whole word`() {
        assertTrue(RustImplMarkers.nameAt("fn area(", 3, "area"))
        assertFalse(RustImplMarkers.nameAt("fn areas(", 3, "area"))
        assertFalse(RustImplMarkers.nameAt("fn are", 3, "area"))
        assertTrue(RustImplMarkers.nameAt("fn r#type(", 3, "r#type"))
        assertTrue(RustImplMarkers.nameAt("fn r#type(", 3, "type"))
    }

    @Test
    fun `lines and columns`() {
        val index = RustImplMarkers.LineIndex("ab\ncd\n\nef")
        assertEquals(0, index.offset(0, 0))
        assertEquals(4, index.offset(1, 1))
        assertEquals(5, index.offset(1, 99))
        assertEquals(7, index.offset(3, 0))
        assertEquals(9, index.offset(9, 0))
        assertEquals(1 to 1, index.position(4))
        assertEquals(2 to 0, index.position(6))
        assertEquals(3 to 2, index.position(9))
        assertEquals(0 to 0, index.position(-5))
    }

    @Test
    fun tooltips() {
        assertEquals("Implemented in 3 impls", RustImplMarkers.tooltip(Item(Kind.TRAIT, "Shape", 0, null), 3))
        assertEquals("Implemented in 1 impl", RustImplMarkers.tooltip(Item(Kind.TRAIT_MEMBER, "area", 0, "Shape"), 1))
        assertEquals("Implements trait Shape", RustImplMarkers.tooltip(Item(Kind.TRAIT_IMPL, "Shape", 0, "Shape"), null))
        assertEquals("Implements Shape::area", RustImplMarkers.tooltip(Item(Kind.IMPL_MEMBER, "area", 0, "Shape"), null))
        assertTrue(RustImplMarkers.countsImplementations(Kind.TRAIT))
        assertFalse(RustImplMarkers.countsImplementations(Kind.IMPL_MEMBER))
    }

    @Test
    fun `the impls of a derive are told apart by their trait`() {
        val line = "#[derive(Debug, Clone, serde::Deserialize)]"
        assertEquals("#[derive(Debug)]", RustTargets.derived(line, line.indexOf("Debug")))
        assertEquals("#[derive(Clone)]", RustTargets.derived(line, line.indexOf("Clone")))
        assertEquals("#[derive(serde::Deserialize)]", RustTargets.derived(line, line.indexOf("Deserialize")))
        assertEquals("#[derive(serde::Deserialize)]", RustTargets.derived("    $line", 4 + line.indexOf("serde")))
        // a proc macro derive: the whole item from its `#`, told by the derives that are not builtin
        assertEquals("#[derive(serde::Deserialize)]", RustTargets.derived(line, 0))
        assertEquals("#[derive(Serialize)]", RustTargets.derived("    #[derive(Debug, Serialize)]", 4))
        assertEquals("#[derive(Serialize, Deserialize)]", RustTargets.derived("#[derive(Clone, Serialize, Deserialize)]", 0))
        assertNull(RustTargets.derived("#[derive(Debug, Clone)]", 0))
        // another place of the attribute, another attribute, an impl: the line as it is
        assertNull(RustTargets.derived(line, 2))
        assertNull(RustTargets.derived("#[serde(default)]", 8))
        assertNull(RustTargets.derived("impl Debug for Config {", 5))
    }

    private fun String.startsWith(prefix: String, at: Int) = regionMatches(at, prefix, 0, prefix.length)
}
