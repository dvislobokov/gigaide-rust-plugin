package io.github.rustsupport.debugger

import com.intellij.execution.process.ProcessOutputTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CargoBuildTest {
    private val binArtifact = """{"reason":"compiler-artifact","package_id":"path+file:///C:/x/app#0.1.0","manifest_path":"C:\\x\\app\\Cargo.toml","target":{"kind":["bin"],"crate_types":["bin"],"name":"app","src_path":"C:\\x\\app\\src\\main.rs","edition":"2021","doc":true,"doctest":false,"test":true},"profile":{"opt_level":"0","debuginfo":2,"debug_assertions":true,"overflow_checks":true,"test":false},"features":[],"filenames":["C:\\x\\app\\target\\debug\\app.exe","C:\\x\\app\\target\\debug\\app.pdb"],"executable":"C:\\x\\app\\target\\debug\\app.exe","fresh":false}"""
    private val libArtifact = """{"reason":"compiler-artifact","target":{"kind":["lib"],"name":"app"},"profile":{"test":false},"filenames":["C:\\x\\app\\target\\debug\\libapp.rlib"],"executable":null,"fresh":true}"""
    private val testArtifact = """{"reason":"compiler-artifact","target":{"kind":["lib"],"name":"app"},"profile":{"test":true},"executable":"/x/app/target/debug/deps/app-0f3c.exe","fresh":false}"""
    private val buildScript = """{"reason":"compiler-artifact","target":{"kind":["custom-build"],"name":"build-script-build"},"profile":{"test":false},"executable":null}"""

    @Test
    fun `message format is added once and before the arguments of the program`() {
        assertEquals(listOf("build", "--bin", "app", "--message-format=json"), CargoBuild.arguments(listOf("build", "--bin", "app")))
        assertEquals(listOf("test", "--no-run", "--message-format=json", "--", "x"), CargoBuild.arguments(listOf("test", "--no-run", "--", "x")))
        assertEquals(listOf("build", "--message-format=json-render-diagnostics"), CargoBuild.arguments(listOf("build", "--message-format=json-render-diagnostics")))
    }

    @Test
    fun `test builds are told by the subcommand`() {
        assertTrue(CargoBuild.isTestBuild(listOf("test", "--package", "app", "--lib", "--no-run")))
        assertTrue(CargoBuild.isTestBuild(listOf("+nightly", "bench", "--no-run")))
        assertFalse(CargoBuild.isTestBuild(listOf("build", "--package", "test")))
    }

    @Test
    fun `artifacts with an executable are parsed`() {
        val message = CargoBuild.parse(binArtifact) as CargoMessage.Artifact
        assertEquals(CargoArtifact("C:\\x\\app\\target\\debug\\app.exe", "app", listOf("bin"), false), message.artifact)
        assertEquals(CargoMessage.Ignored, CargoBuild.parse(libArtifact))
        assertTrue((CargoBuild.parse(testArtifact) as CargoMessage.Artifact).artifact.test)
    }

    @Test
    fun `diagnostics, the end of the build and plain text`() {
        val error = CargoBuild.parse("""{"reason":"compiler-message","message":{"rendered":"error[E0308]: mismatched types\n","level":"error","message":"mismatched types"}}""")
        assertEquals(CargoMessage.Diagnostic("error[E0308]: mismatched types\n", true), error)
        val warning = CargoBuild.parse("""{"reason":"compiler-message","message":{"rendered":"warning: unused","level":"warning"}}""") as CargoMessage.Diagnostic
        assertFalse(warning.error)
        assertEquals(CargoMessage.Finished(false), CargoBuild.parse("""{"reason":"build-finished","success":false}"""))
        assertEquals(CargoMessage.Text("hello"), CargoBuild.parse("hello"))
        assertEquals(CargoMessage.Text("{not json"), CargoBuild.parse("{not json"))
        assertEquals(CargoMessage.Ignored, CargoBuild.parse("""{"reason":"build-script-executed"}"""))
    }

    @Test
    fun `the executable of a binary build`() {
        val artifacts = listOf(buildScript, libArtifact, binArtifact).mapNotNull { (CargoBuild.parse(it) as? CargoMessage.Artifact)?.artifact }
        val choice = CargoBuild.choose(artifacts, testBuild = false)
        assertEquals("C:\\x\\app\\target\\debug\\app.exe", choice.executable)
        assertNull(choice.warning)
    }

    @Test
    fun `the executable of a test build is the harness`() {
        val artifacts = listOf(binArtifact, testArtifact).mapNotNull { (CargoBuild.parse(it) as? CargoMessage.Artifact)?.artifact }
        assertEquals("/x/app/target/debug/deps/app-0f3c.exe", CargoBuild.choose(artifacts, testBuild = true).executable)
    }

    @Test
    fun `nothing to debug and several executables`() {
        val none = CargoBuild.choose(emptyList(), testBuild = false)
        assertNull(none.executable)
        assertNotNull(none.error)
        val two = CargoBuild.choose(listOf(CargoArtifact("/a", "a", listOf("test"), true), CargoArtifact("/b", "b", listOf("test"), true)), testBuild = true)
        assertEquals("/a", two.executable)
        assertTrue(two.warning!!.contains("2 executables"))
    }

    @Test
    fun `progress of cargo is gray, its errors red`() {
        assertEquals(ProcessOutputTypes.SYSTEM, CargoBuild.stderrType("   Compiling app v0.1.0 (C:\\x\\app)"))
        assertEquals(ProcessOutputTypes.SYSTEM, CargoBuild.stderrType("    Finished `dev` profile [unoptimized + debuginfo] target(s) in 0.52s"))
        assertEquals(ProcessOutputTypes.STDERR, CargoBuild.stderrType("error: could not compile `app` (bin \"app\") due to 1 previous error"))
    }
}
