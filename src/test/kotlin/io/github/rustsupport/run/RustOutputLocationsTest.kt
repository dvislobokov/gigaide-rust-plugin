package io.github.rustsupport.run

import org.junit.Assert.assertEquals
import org.junit.Test

class RustOutputLocationsTest {
    @Test
    fun `location of a compiler diagnostic`() {
        val line = "  --> src/main.rs:10:5"
        assertEquals(listOf(RustOutputLocation(6, 22, "src/main.rs", 10, 5)), RustOutputLocations.find(line))
    }

    @Test
    fun `windows separators`() {
        assertEquals(listOf(RustOutputLocation(4, 24, "src\\bin\\tool.rs", 3, 14)), RustOutputLocations.find("--> src\\bin\\tool.rs:3:14"))
    }

    @Test
    fun `panic of Rust 1_73 and later`() {
        val found = RustOutputLocations.find("thread 'tests::fails' panicked at src/lib.rs:12:9:")
        assertEquals(listOf("src/lib.rs" to 12), found.map { it.path to it.line })
        assertEquals(9, found[0].column)
    }

    @Test
    fun `old panic with a quoted message`() {
        val found = RustOutputLocations.find("thread 'main' panicked at 'boom: x', src/main.rs:4:5")
        assertEquals(listOf("src/main.rs"), found.map { it.path })
    }

    @Test
    fun `absolute path of the registry`() {
        val line = "   1: core::panicking::panic_fmt at C:\\Users\\me\\.cargo\\registry\\src\\index.crates.io-1949cf8c6b5b557f\\tokio-1.40.0\\src\\lib.rs:72:14"
        val found = RustOutputLocations.find(line).single()
        assertEquals("C:\\Users\\me\\.cargo\\registry\\src\\index.crates.io-1949cf8c6b5b557f\\tokio-1.40.0\\src\\lib.rs", found.path)
        assertEquals(72, found.line)
        assertEquals(line.length, found.end)
    }

    @Test
    fun `backtrace frame and a line without a column`() {
        assertEquals(listOf("./src/main.rs" to 4), RustOutputLocations.find("             at ./src/main.rs:4:5").map { it.path to it.line })
        assertEquals(listOf(RustOutputLocation(0, 8, "lib.rs", 7, 1)), RustOutputLocations.find("lib.rs:7 is wrong"))
    }

    @Test
    fun `nothing in other text`() {
        assertEquals(emptyList<RustOutputLocation>(), RustOutputLocations.find("   Compiling app v0.1.0 (C:\\w\\app)"))
        assertEquals(emptyList<RustOutputLocation>(), RustOutputLocations.find("error: could not compile `app` (bin \"app\") due to 1 previous error"))
    }

    @Test
    fun `candidates of relative and absolute paths`() {
        assertEquals(listOf("C:/w/src/main.rs", "C:/p/src/main.rs"), RustOutputLocations.candidates("src\\main.rs", listOf("C:\\w", "C:/p/")))
        assertEquals(listOf("C:/w/src/main.rs"), RustOutputLocations.candidates("./src/main.rs", listOf("C:/w", "C:/w")))
        assertEquals(listOf("/home/me/src/lib.rs"), RustOutputLocations.candidates("/home/me/src/lib.rs", listOf("C:/w")))
    }
}
