package io.github.rustsupport.toolchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RustfmtTest {
    @Test fun packageEdition() = assertEquals("2021", CargoEdition.resolve(listOf("[package]\nname = \"a\"\nversion = \"0.1.0\"\nedition = \"2021\"\n")))

    @Test fun commentsAndSpaces() =
        assertEquals("2024", CargoEdition.resolve(listOf("# edition = \"2015\"\n[ package ]\nname = \"a#b\" # the name\nedition='2024' # latest\n")))

    @Test fun noEditionIs2015() = assertEquals("2015", CargoEdition.resolve(listOf("[package]\nname = \"a\"\n")))

    @Test fun editionOfAnotherTableDoesNotCount() =
        assertEquals("2015", CargoEdition.resolve(listOf("[package]\nname = \"a\"\n\n[dependencies]\nedition = \"2021\"\n")))

    @Test fun inheritedDotted() = assertEquals("2021", CargoEdition.resolve(listOf(
        "[package]\nname = \"member\"\nedition.workspace = true\n",
        "[workspace]\nmembers = [\"member\"]\n\n[workspace.package]\nedition = \"2021\"\n",
    )))

    @Test fun inheritedInline() = assertEquals("2024", CargoEdition.resolve(listOf(
        "[package]\nname = \"member\"\nedition = { workspace = true }\n",
        "[workspace.package]\nversion = \"1.0.0\"\nedition = \"2024\"\n",
    )))

    @Test fun inheritedFromTheSameManifest() =
        assertEquals("2021", CargoEdition.resolve(listOf("[workspace.package]\nedition = \"2021\"\n\n[package]\nname = \"root\"\nedition.workspace = true\n")))

    @Test fun nearestPackageWins() =
        assertEquals("2018", CargoEdition.resolve(listOf("[package]\nedition = \"2018\"\n", "[package]\nedition = \"2021\"\n")))

    @Test fun virtualManifestAboveIsSkipped() =
        assertEquals("2021", CargoEdition.resolve(listOf("[workspace]\nmembers = []\n", "[package]\nedition = \"2021\"\n")))

    @Test fun noPackage() {
        assertNull(CargoEdition.resolve(emptyList()))
        assertNull(CargoEdition.resolve(listOf("[workspace]\nmembers = [\"a\"]\n")))
    }

    @Test fun arguments() {
        assertEquals(listOf("--edition", "2021", "--emit", "stdout"), Rustfmt.arguments("2021"))
        assertEquals(listOf("--emit", "stdout"), Rustfmt.arguments(null))
    }

    @Test fun minimalReplacement() {
        val r = RustTextDiff.minimal("fn main(){x}", "fn main() { x }")!!
        assertEquals(9, r.start)
        assertEquals(11, r.end)
        assertEquals(" { x ", r.text)
        assertNull(RustTextDiff.minimal("same", "same"))
    }
}
