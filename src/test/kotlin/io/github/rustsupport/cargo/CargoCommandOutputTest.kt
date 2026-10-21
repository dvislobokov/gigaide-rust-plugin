package io.github.rustsupport.cargo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CargoCommandOutputTest {
    // the shape of `cargo info serde` of cargo 1.99 outside a workspace
    private val info = """
        serde #serde #serialization #no_std
        A generic serialization/deserialization framework
        version: 1.0.229
        license: MIT OR Apache-2.0
        rust-version: 1.56
        documentation: https://docs.rs/serde
        homepage: https://serde.rs
        repository: https://github.com/serde-rs/serde
        crates.io: https://crates.io/crates/serde/1.0.229
        features:
         +default      = [std]
          std          = [serde_core/std]
          derive       = [serde_derive]
          serde_derive = [dep:serde_derive]
        note: to see how you depend on serde, run `cargo tree --invert serde@1.0.229`
    """.trimIndent()

    @Test
    fun `cargo info of a crate`() {
        val parsed = CargoInfoParser.parse(info)!!
        assertEquals("serde", parsed.name)
        assertEquals("A generic serialization/deserialization framework", parsed.description)
        assertEquals("1.0.229", parsed.version)
        assertEquals("1.0.229", parsed.latest)
        assertEquals(listOf("default", "std", "derive", "serde_derive"), parsed.features.map { it.name })
        assertTrue(parsed.features.first().default)
        assertEquals(listOf("serde_core/std"), parsed.features[1].enables)
    }

    @Test
    fun `cargo info inside a workspace names the locked version and the latest one`() {
        val text = """
                Updating crates.io index
            rand #random #rng
            Random number generators and other randomness functionality.
            spanning two lines of description
            version: 0.8.5 (latest 0.10.2)
            license: MIT OR Apache-2.0
            features:
             +default = [std, std_rng]
              std     = []
        """.trimIndent()
        val parsed = CargoInfoParser.parse(text)!!
        assertEquals("rand", parsed.name)
        assertEquals("0.8.5", parsed.version)
        assertEquals("0.10.2", parsed.latest)
        assertEquals("Random number generators and other randomness functionality. spanning two lines of description", parsed.description)
        assertEquals(listOf("std", "std_rng"), parsed.features.first().enables)
    }

    @Test
    fun `cargo info with no version is not understood`() {
        assertNull(CargoInfoParser.parse("error: could not find `nosuchcrate` in registry `crates-io`"))
        assertNull(CargoInfoParser.parse(""))
    }

    @Test
    fun `cargo update dry run`() {
        val text = """
                Updating crates.io index
                 Locking 2 packages to latest compatible versions
                Updating anyhow v1.0.80 -> v1.0.104
                Updating rand v0.8.4 -> v0.8.5 (available: v0.10.2)
               Unchanged base64 v0.21.7 (available: v0.22.1)
             Downgrading foo v1.2.0 -> v1.1.0
                  Adding bar v0.1.0
                Removing baz v0.2.0
            note: to see how you depend on a package, run `cargo tree --invert <dep>@<ver>`
            warning: aborting update due to dry run
        """.trimIndent()
        val entries = CargoUpdateParser.parse(text)
        assertEquals(listOf("anyhow", "rand", "base64", "foo", "bar"), entries.map { it.name })
        assertEquals("1.0.104", entries[0].latest)
        assertEquals(CargoUpdateEntry("rand", "0.8.4", "0.8.5", "0.10.2"), entries[1])
        assertEquals("0.10.2", entries[1].latest)
        assertEquals("0.22.1", entries[2].latest)
        assertEquals("1.2.0", entries[3].latest)
        assertEquals("0.1.0", entries[4].latest)
    }
}
