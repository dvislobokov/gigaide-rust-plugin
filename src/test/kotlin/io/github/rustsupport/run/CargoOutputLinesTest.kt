package io.github.rustsupport.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CargoOutputLinesTest {
    private fun List<List<CargoOutputLines.Piece<String>>>.shown() = map { line -> line.map { it.type + ":" + it.text } }

    @Test
    fun `a line in pieces of different colors is one line`() {
        val lines = CargoOutputLines<String>()
        assertTrue(lines.add("stderr", "     ", "plain").isEmpty())
        assertTrue(lines.add("stderr", "Running", "green").isEmpty())
        assertTrue(lines.add("stderr", " tests\\api.rs (target\\debug\\deps\\api-7dc8.exe)", "plain").isEmpty())
        val done = lines.add("stderr", "\n", "plain")
        assertEquals(listOf("     Running tests\\api.rs (target\\debug\\deps\\api-7dc8.exe)\n"), done.map { CargoOutputLines.text(it) })
        assertEquals(listOf(listOf("plain:     ", "green:Running", "plain: tests\\api.rs (target\\debug\\deps\\api-7dc8.exe)", "plain:\n")), done.shown())
    }

    @Test
    fun `streams are put together apart and the rest comes at the end`() {
        val lines = CargoOutputLines<String>()
        assertTrue(lines.add("stderr", "   Compiling", "green").isEmpty())
        assertEquals(listOf("test a ... ok\n", "test b ... ok\n"), lines.add("stdout", "test a ... ok\ntest b ... ok\ntest c", "plain").map { CargoOutputLines.text(it) })
        assertEquals(listOf("   Compiling app\n"), lines.add("stderr", " app\n", "plain").map { CargoOutputLines.text(it) })
        assertEquals(listOf("test c"), lines.rest().map { CargoOutputLines.text(it) })
        assertTrue(lines.rest().isEmpty())
    }
}
