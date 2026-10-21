package io.github.rustsupport.cargo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class CargoRegistryTest {
    @get:Rule val temp = TemporaryFolder()

    /** A file of the index cache as cargo writes it: version 3, index format 2, the etag, then version/JSON pairs. */
    private fun cacheFile(vararg lines: Pair<String, String>): ByteArray = ByteArrayOutputStream().apply {
        write(3)
        write(byteArrayOf(2, 0, 0, 0))
        write("etag: \"80abafacb839\"".toByteArray()); write(0)
        for ((version, json) in lines) {
            write(version.toByteArray()); write(0)
            write(json.toByteArray()); write(0)
        }
    }.toByteArray()

    private val aes = arrayOf(
        "0.0.0" to """{"name":"aes","vers":"0.0.0","deps":[],"cksum":"f5","features":{},"yanked":true}""",
        "0.8.4" to """{"name":"aes","vers":"0.8.4","deps":[],"cksum":"b1","features":{"hazmat":[],"zeroize":["dep:zeroize"]},"features2":{"std":[]},"yanked":false,"rust_version":"1.56"}""",
        "0.7.5" to """{"name":"aes","vers":"0.7.5","deps":[],"cksum":"c2","features":{},"yanked":false}""",
    )

    @Test
    fun `a file of the index cache`() {
        val entries = CargoRegistry.parseCacheFile(cacheFile(*aes))
        assertEquals(listOf("0.0.0", "0.8.4", "0.7.5"), entries.map { it.version })
        assertTrue(entries[0].yanked)
        assertEquals(setOf("hazmat", "zeroize", "std"), entries[1].features.keys)
        assertEquals(listOf("dep:zeroize"), entries[1].features["zeroize"])
        assertEquals("1.56", entries[1].rustVersion)
        assertTrue(CargoRegistry.parseCacheFile(ByteArray(0)).isEmpty())
    }

    @Test
    fun `paths of the index and names of archives`() {
        assertEquals("1/a", CargoRegistry.indexPath("a"))
        assertEquals("2/cc", CargoRegistry.indexPath("cc"))
        assertEquals("3/a/aes", CargoRegistry.indexPath("aes"))
        assertEquals("se/rd/serde", CargoRegistry.indexPath("Serde"))
        assertEquals("aes-gcm" to "0.10.3", CargoRegistry.parseCrateFileName("aes-gcm-0.10.3.crate"))
        assertEquals("windows_x86_64_msvc" to "0.52.6", CargoRegistry.parseCrateFileName("windows_x86_64_msvc-0.52.6.crate"))
        assertEquals("foo-bar" to "1.0.0-rc.1", CargoRegistry.parseCrateFileName("foo-bar-1.0.0-rc.1.crate"))
        assertNull(CargoRegistry.parseCrateFileName("README.md"))
    }

    @Test
    fun `names, versions and sources of a cargo home`() {
        val home = temp.root.toPath()
        val index = File(temp.root, "registry/index/index.crates.io-1949cf8c6b5b557f/.cache")
        File(index, "3/a").mkdirs()
        File(index, "3/a/aes").writeBytes(cacheFile(*aes))
        File(index, "se/rd").mkdirs()
        File(index, "se/rd/serde").writeBytes(cacheFile("1.0.229" to """{"name":"serde","vers":"1.0.229","features":{"std":[]},"yanked":false}"""))
        val cache = File(temp.root, "registry/cache/index.crates.io-1949cf8c6b5b557f").apply { mkdirs() }
        File(cache, "aes-0.9.0.crate").writeText("")
        File(cache, "tokio-1.53.1.crate").writeText("")
        val src = File(temp.root, "registry/src/index.crates.io-1949cf8c6b5b557f")
        File(src, "aes-0.8.4").mkdirs()
        File(src, "aes-0.8.4/Cargo.toml").writeText("[package]")
        File(src, "aes-0.7.5").mkdirs()
        File(src, "aes-0.7.5/Cargo.toml").writeText("[package]")

        assertEquals(listOf("aes", "serde", "tokio"), CargoRegistry.crateNames(home).toList())
        assertEquals(listOf("0.9.0", "0.8.4", "0.7.5"), CargoRegistry.versions(home, "aes"))
        assertEquals(listOf("1.53.1"), CargoRegistry.versions(home, "tokio"))
        assertFalse(CargoRegistry.entries(home, "aes").first { it.version == "0.0.0" }.let { !it.yanked })
        assertEquals(listOf("0.8.4", "0.7.5"), CargoRegistry.sourceManifests(home, "aes").map { it.first })
        assertTrue(CargoRegistry.versions(home, "nothing").isEmpty())
    }
}
