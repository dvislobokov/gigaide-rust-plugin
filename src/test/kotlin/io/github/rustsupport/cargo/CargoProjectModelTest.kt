package io.github.rustsupport.cargo

import io.github.rustsupport.run.CargoTargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CargoProjectModelTest {
    // the shape of `cargo metadata --format-version 1` (with the resolve graph) of a small workspace
    private val json = """
        {"packages":[
          {"name":"app","version":"0.1.0","id":"path+file:///C:/w/app#0.1.0","source":null,
           "dependencies":[
             {"name":"serde","source":"registry+https://github.com/rust-lang/crates.io-index","req":"^1.0","kind":null,"rename":null,"optional":false,"uses_default_features":true,"features":["derive"],"target":null,"registry":null},
             {"name":"rand","source":"registry+https://github.com/rust-lang/crates.io-index","req":"^0.8","kind":null,"rename":"random","optional":true,"uses_default_features":true,"features":[],"target":null,"registry":null},
             {"name":"core_lib","source":null,"req":"*","kind":null,"rename":null,"optional":false,"uses_default_features":true,"features":[],"target":null,"registry":null,"path":"C:\\w\\core"},
             {"name":"tempfile","source":"registry+https://github.com/rust-lang/crates.io-index","req":"^3","kind":"dev","rename":null,"optional":false,"uses_default_features":true,"features":[],"target":null,"registry":null},
             {"name":"cc","source":"registry+https://github.com/rust-lang/crates.io-index","req":"^1","kind":"build","rename":null,"optional":false,"uses_default_features":true,"features":[],"target":null,"registry":null}
           ],
           "targets":[{"kind":["bin"],"crate_types":["bin"],"name":"app","src_path":"C:\\w\\app\\src\\main.rs"},
                      {"kind":["example"],"crate_types":["bin"],"name":"demo","src_path":"C:\\w\\app\\examples\\demo.rs"}],
           "features":{"fast":["random"],"default":["fast"],"random":["dep:random"]},"manifest_path":"C:\\w\\app\\Cargo.toml"},
          {"name":"core_lib","version":"0.2.0","id":"path+file:///C:/w/core#core_lib@0.2.0","source":null,"dependencies":[],
           "targets":[{"kind":["lib"],"crate_types":["lib"],"name":"core_lib","src_path":"C:\\w\\core\\src\\lib.rs"}],
           "features":{},"manifest_path":"C:\\w\\core\\Cargo.toml"},
          {"name":"serde","version":"1.0.229","id":"registry+https://github.com/rust-lang/crates.io-index#serde@1.0.229","source":"registry+https://github.com/rust-lang/crates.io-index",
           "dependencies":[],"targets":[],"features":{"std":[],"derive":["serde_derive"],"default":["std"]},"manifest_path":"C:\\h\\serde-1.0.229\\Cargo.toml"},
          {"name":"serde","version":"0.9.0","id":"registry+https://github.com/rust-lang/crates.io-index#serde@0.9.0","source":"registry+https://github.com/rust-lang/crates.io-index",
           "dependencies":[],"targets":[],"features":{},"manifest_path":"C:\\h\\serde-0.9.0\\Cargo.toml"},
          {"name":"rand","version":"0.8.5","id":"registry+https://github.com/rust-lang/crates.io-index#rand@0.8.5","source":"registry+https://github.com/rust-lang/crates.io-index",
           "dependencies":[],"targets":[],"features":{"std":[]},"manifest_path":"C:\\h\\rand-0.8.5\\Cargo.toml"},
          {"name":"tempfile","version":"3.10.0","id":"registry+https://github.com/rust-lang/crates.io-index#tempfile@3.10.0","source":"registry+https://github.com/rust-lang/crates.io-index",
           "dependencies":[],"targets":[],"features":{},"manifest_path":"C:\\h\\tempfile-3.10.0\\Cargo.toml"},
          {"name":"cc","version":"1.2.0","id":"registry+https://github.com/rust-lang/crates.io-index#cc@1.2.0","source":"registry+https://github.com/rust-lang/crates.io-index",
           "dependencies":[],"targets":[],"features":{},"manifest_path":"C:\\h\\cc-1.2.0\\Cargo.toml"}
        ],
        "workspace_members":["path+file:///C:/w/app#0.1.0","path+file:///C:/w/core#core_lib@0.2.0"],
        "resolve":{"nodes":[
          {"id":"path+file:///C:/w/app#0.1.0","dependencies":[],"deps":[
             {"name":"serde","pkg":"registry+https://github.com/rust-lang/crates.io-index#serde@1.0.229","dep_kinds":[{"kind":null,"target":null}]},
             {"name":"random","pkg":"registry+https://github.com/rust-lang/crates.io-index#rand@0.8.5","dep_kinds":[{"kind":null,"target":null}]},
             {"name":"core_lib","pkg":"path+file:///C:/w/core#core_lib@0.2.0","dep_kinds":[{"kind":null,"target":null}]},
             {"name":"tempfile","pkg":"registry+https://github.com/rust-lang/crates.io-index#tempfile@3.10.0","dep_kinds":[{"kind":"dev","target":null}]},
             {"name":"cc","pkg":"registry+https://github.com/rust-lang/crates.io-index#cc@1.2.0","dep_kinds":[{"kind":"build","target":null},{"kind":null,"target":"cfg(windows)"}]}
          ],"features":[]},
          {"id":"path+file:///C:/w/core#core_lib@0.2.0","dependencies":[],"deps":[
             {"name":"serde","pkg":"registry+https://github.com/rust-lang/crates.io-index#serde@0.9.0","dep_kinds":[{"kind":null,"target":null}]}
          ],"features":[]}
        ],"root":null},
        "target_directory":"C:\\w\\target","version":1,"workspace_root":"C:\\w","metadata":null}
    """.trimIndent()

    @Test
    fun `members, targets and features`() {
        val data = CargoProjectData.parse(json)!!
        assertEquals("C:\\w", data.root)
        assertEquals(listOf("app", "core_lib"), data.members.map { it.name })
        assertTrue(data.hasDependencies)
        val app = data.members.first()
        assertEquals(listOf(CargoTargetKind.BIN, CargoTargetKind.EXAMPLE), app.targets.map { it.kind })
        assertEquals(listOf("default", "fast", "random"), app.features.keys.toList())
        assertEquals(listOf("dep:random"), app.features["random"])
        assertEquals("random", app.dependencies.first { it.name == "rand" }.key)
        assertTrue(app.dependencies.first { it.name == "rand" }.optional)
        assertEquals(CargoDependencyKind.DEV, app.dependencies.first { it.name == "tempfile" }.kind)
    }

    @Test
    fun `resolved dependencies with versions and kinds, normal ones first`() {
        val data = CargoProjectData.parse(json)!!
        val deps = data.dependencies("path+file:///C:/w/app#0.1.0")
        assertEquals(listOf("cc", "core_lib", "rand", "serde", "tempfile"), deps.map { it.name })
        assertEquals(setOf(CargoDependencyKind.BUILD, CargoDependencyKind.NORMAL), deps.first { it.name == "cc" }.kinds)
        assertEquals("1.0.229", deps.first { it.name == "serde" }.version)
        assertTrue(deps.all { it.resolved })
        assertEquals(listOf("serde"), data.dependencies("path+file:///C:/w/core#core_lib@0.2.0").map { it.name })
        assertEquals("0.9.0", data.dependencies("path+file:///C:/w/core#core_lib@0.2.0").single().version)
    }

    @Test
    fun `the package a manifest means by a dependency key`() {
        val data = CargoProjectData.parse(json)!!
        assertEquals("1.0.229", data.dependencyPackage("C:/w/app/Cargo.toml", "serde")?.version)
        assertEquals("0.9.0", data.dependencyPackage("c:\\w\\core\\Cargo.toml", "serde")?.version)
        assertEquals("rand", data.dependencyPackage("C:\\w\\app\\Cargo.toml", "random")?.name)
        // [workspace.dependencies] of the root manifest: the newest package of the name
        assertEquals("1.0.229", data.dependencyPackage("C:\\w\\Cargo.toml", "serde")?.version)
        assertNull(data.dependencyPackage("C:\\w\\Cargo.toml", "nothing"))
        assertEquals("core_lib", data.packageOfManifest("C:/W/core/Cargo.toml")?.name)
    }

    @Test
    fun `without the resolve graph the declarations are the dependencies`() {
        val noDeps = """
            {"packages":[{"name":"app","version":"0.1.0","id":"path+file:///C:/w#app@0.1.0","source":null,
              "dependencies":[{"name":"serde","req":"^1.0","kind":null,"rename":null,"optional":false,"features":[]},
                              {"name":"serde","req":"^1.0","kind":"dev","rename":null,"optional":false,"features":[]}],
              "targets":[{"kind":["lib"],"name":"app","src_path":"C:\\w\\src\\lib.rs"}],"features":{},"manifest_path":"C:\\w\\Cargo.toml"}],
             "workspace_members":["path+file:///C:/w#app@0.1.0"],"resolve":null,"workspace_root":"C:\\w","version":1}
        """.trimIndent()
        val data = CargoProjectData.parse(noDeps)!!
        assertFalse(data.hasDependencies)
        val deps = data.dependencies("path+file:///C:/w#app@0.1.0")
        assertEquals(1, deps.size)
        assertEquals("^1.0", deps.single().version)
        assertEquals(setOf(CargoDependencyKind.NORMAL, CargoDependencyKind.DEV), deps.single().kinds)
        assertFalse(deps.single().resolved)
        assertNull(CargoProjectData.parse("error: could not find `Cargo.toml`"))
    }

    @Test
    fun `dependency tables of Cargo toml`() {
        assertTrue(CargoTomlSections.isDependencyTable(listOf("dependencies")))
        assertTrue(CargoTomlSections.isDependencyTable(listOf("dev-dependencies")))
        assertTrue(CargoTomlSections.isDependencyTable(listOf("workspace", "dependencies")))
        assertTrue(CargoTomlSections.isDependencyTable(listOf("target", "cfg(windows)", "build-dependencies")))
        assertFalse(CargoTomlSections.isDependencyTable(listOf("package")))
        assertFalse(CargoTomlSections.isDependencyTable(listOf("features")))
        assertEquals("serde", CargoTomlSections.dependencyOfTable(listOf("dependencies", "serde")))
        assertEquals("winapi", CargoTomlSections.dependencyOfTable(listOf("target", "cfg(windows)", "dependencies", "winapi")))
        assertNull(CargoTomlSections.dependencyOfTable(listOf("package", "metadata")))
        assertNull(CargoTomlSections.dependencyOfTable(listOf("dependencies")))
    }

    @Test
    fun `features of new run configurations`() {
        val selected = listOf("app/fast", "app/random", "core_lib/std")
        assertEquals("fast,random", CargoFeatureDefaults.forRun(selected, "app"))
        assertNull(CargoFeatureDefaults.forRun(selected, "other"))
        assertEquals("app/fast,app/random,core_lib/std", CargoFeatureDefaults.forRun(selected, null))
        assertNull(CargoFeatureDefaults.forRun(emptyList(), null))
    }
}
