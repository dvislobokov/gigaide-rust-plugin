package io.github.rustsupport.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CargoMetadataTest {
    // shortened from `cargo metadata --format-version 1 --no-deps` of a real workspace
    private val json = """
        {"packages":[
          {"name":"komodo_cli","version":"0.0.0","id":"path+file:///C:/w/bin/cli#komodo_cli@0.0.0","dependencies":[],
           "targets":[{"kind":["bin"],"crate_types":["bin"],"name":"km","src_path":"C:\\w\\bin\\cli\\src\\main.rs","edition":"2024","doc":true,"doctest":false,"test":true}],
           "features":{},"manifest_path":"C:\\w\\bin\\cli\\Cargo.toml"},
          {"name":"database","version":"0.0.0","id":"path+file:///C:/w/lib/database#0.0.0","dependencies":[],
           "targets":[{"kind":["lib"],"crate_types":["lib"],"name":"database","src_path":"C:\\w\\lib\\database\\src\\lib.rs"},
                      {"kind":["test"],"crate_types":["bin"],"name":"it","src_path":"C:\\w\\lib\\database\\tests\\it.rs"},
                      {"kind":["custom-build"],"crate_types":["bin"],"name":"build-script-build","src_path":"C:\\w\\lib\\database\\build.rs"}],
           "features":{"default":["mongo"],"mongo":[]},"manifest_path":"C:\\w\\lib\\database\\Cargo.toml"}
        ],"workspace_members":[],"resolve":null,"target_directory":"C:\\w\\target","version":1,"workspace_root":"C:\\w","metadata":null}
    """.trimIndent()

    @Test
    fun `packages, targets and features`() {
        val workspace = CargoMetadata.parse(json)!!
        assertEquals("C:\\w", workspace.root)
        assertEquals(listOf("komodo_cli", "database"), workspace.packages.map { it.name })
        val cli = workspace.packages[0]
        assertEquals(listOf(CargoTarget("km", listOf("bin"), "C:\\w\\bin\\cli\\src\\main.rs")), cli.targets)
        assertEquals(CargoTargetKind.BIN, cli.targets[0].kind)
        val database = workspace.packages[1]
        assertEquals(listOf(CargoTargetKind.LIB, CargoTargetKind.TEST, null), database.targets.map { it.kind })
        assertEquals(listOf("default", "mongo"), database.features)
    }

    @Test
    fun `not metadata`() {
        assertNull(CargoMetadata.parse("error: could not find `Cargo.toml`"))
        assertNull(CargoMetadata.parse("[]"))
    }

    @Test
    fun `package name of a manifest`() {
        val manifest = """
            [workspace]
            members = ["a"]

            [package]
            name = "my-app" # the name
            version = "0.1.0"

            [dependencies]
            name = "not this one"
        """.trimIndent()
        assertEquals("my-app", CargoManifest.packageName(manifest))
        assertTrue(CargoManifest.isWorkspace(manifest))
        assertNull(CargoManifest.packageName("[workspace]\nmembers = []"))
        assertFalse(CargoManifest.isWorkspace("[package]\nname = 'x'\n[workspace.dependencies]"))
    }

    @Test
    fun `targets of files by the conventions of cargo`() {
        assertEquals(CargoTargetKind.BIN to "app", CargoMainTargets.targetOf("src/main.rs", "app"))
        assertEquals(CargoTargetKind.BIN to "tool", CargoMainTargets.targetOf("src\\bin\\tool.rs", "app"))
        assertEquals(CargoTargetKind.BIN to "tool", CargoMainTargets.targetOf("src/bin/tool/main.rs", "app"))
        assertEquals(CargoTargetKind.EXAMPLE to "demo", CargoMainTargets.targetOf("examples/demo.rs", "app"))
        assertNull(CargoMainTargets.targetOf("src/lib.rs", "app"))
        assertNull(CargoMainTargets.targetOf("src/bin/tool/helper.rs", "app"))
    }
}
