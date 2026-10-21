package io.github.rustsupport.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CargoRunnablesTest {
    // the shape of `experimental/runnables` of rust-analyzer (lsp_ext.rs: Runnable, CargoRunnableArgs)
    private val json = """
        [
          {"label":"run app","location":{"targetUri":"file:///c%3A/w/src/main.rs",
              "targetRange":{"start":{"line":2,"character":0},"end":{"line":4,"character":1}},
              "targetSelectionRange":{"start":{"line":2,"character":3},"end":{"line":2,"character":7}}},
           "kind":"cargo","args":{"environment":{"RUST_BACKTRACE":"short"},"cwd":"C:\\w","overrideCargo":null,"workspaceRoot":"C:\\w",
              "cargoArgs":["run","--package","app","--bin","app"],"executableArgs":[]}},
          {"label":"test tests::it_works","location":{"targetUri":"file:///c%3A/w/src/main.rs",
              "targetRange":{"start":{"line":8,"character":4},"end":{"line":11,"character":5}},
              "targetSelectionRange":{"start":{"line":9,"character":7},"end":{"line":9,"character":15}}},
           "kind":"cargo","args":{"cwd":"C:\\w","workspaceRoot":"C:\\w",
              "cargoArgs":["test","--package","app","--bin","app"],"executableArgs":["tests::it_works","--exact","--show-output"]}},
          {"label":"cargo test -p app --all-targets","kind":"cargo","args":{"workspaceRoot":"C:\\w","cargoArgs":["test","--package","app","--all-targets"],"executableArgs":[]}},
          {"label":"make","kind":"shell","args":{"program":"make","args":[],"cwd":"C:\\w"}}
        ]
    """.trimIndent()

    @Test
    fun `cargo runnables with their places`() {
        val runnables = CargoRunnables.parse(json)
        assertEquals(listOf("run app", "test tests::it_works", "cargo test -p app --all-targets"), runnables.map { it.label })
        val main = runnables[0]
        assertEquals(LspPosition(2, 3), main.nameStart)
        assertEquals(LspPosition(2, 0), main.rangeStart)
        assertEquals(LspPosition(4, 1), main.rangeEnd)
        assertEquals("C:\\w", main.cwd)
        assertEquals(mapOf("RUST_BACKTRACE" to "short"), main.environment)
        assertEquals(CargoInvocation(listOf("run"), packageName = "app", targetKind = CargoTargetKind.BIN, targetName = "app"), main.invocation())
        val test = runnables[1]
        assertEquals(listOf("tests::it_works", "--exact", "--show-output"), test.invocation().programArguments)
        assertNull(runnables[2].nameStart)
        assertEquals("C:\\w", runnables[2].cwd)
    }

    @Test
    fun `cargoExtraArgs of older servers`() {
        val old = """[{"label":"test a","kind":"cargo","args":{"workspaceRoot":"/w","cargoArgs":["test","--package","x","--lib"],"cargoExtraArgs":["--release"],"executableArgs":["a","--exact"]}}]"""
        assertEquals(listOf("--release"), CargoRunnables.parse(old).single().invocation().cargoArguments)
    }

    @Test
    fun `broken answers`() {
        assertEquals(emptyList<CargoRunnable>(), CargoRunnables.parse(null))
        assertEquals(emptyList<CargoRunnable>(), CargoRunnables.parse("null"))
        assertEquals(emptyList<CargoRunnable>(), CargoRunnables.parse("{\"x\":1}"))
    }

    @Test
    fun `names of configurations`() {
        val runnable = CargoRunnables.parse(json)[1]
        assertEquals("Test it_works", runnable.configurationName())
        assertEquals("Run app", CargoRunnables.parse(json)[0].configurationName())
        assertEquals("Test tests", runnable.copy(label = "test-mod tests").configurationName())
        assertEquals("cargo test -p app --all-targets", CargoRunnables.parse(json)[2].configurationName())
    }
}
