package io.github.rustsupport.cargo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {
    private fun v(text: String) = SemVersion.parse(text)!!
    private fun req(text: String) = VersionReq.parse(text)!!

    @Test
    fun `versions parse leniently and order as SemVer`() {
        assertEquals(SemVersion(1, 2, 0), v("1.2"))
        assertEquals(SemVersion(1, 0, 0, "beta.2"), v("v1.0.0-beta.2+build.5"))
        assertNull(SemVersion.parse("latest"))
        val sorted = listOf("1.0.0", "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-beta", "0.9.12", "1.0.0-alpha.beta", "1.0.0-rc.1", "1.0.10").map(::v).sorted()
        assertEquals(listOf("0.9.12", "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-rc.1", "1.0.0", "1.0.10"), sorted.map { it.toString() })
    }

    @Test
    fun `caret is the default requirement`() {
        assertTrue(req("1.2.3").matches(v("1.9.0")))
        assertFalse(req("1.2.3").matches(v("2.0.0")))
        assertFalse(req("1.2.3").matches(v("1.2.2")))
        assertTrue(req("0.2.3").matches(v("0.2.9")))
        assertFalse(req("0.2.3").matches(v("0.3.0")))
        assertTrue(req("^0.0.3").matches(v("0.0.3")))
        assertFalse(req("^0.0.3").matches(v("0.0.4")))
        assertTrue(req("0").matches(v("0.99.0")))
        assertTrue(req("0.0").matches(v("0.0.7")))
        assertFalse(req("0.0").matches(v("0.1.0")))
    }

    @Test
    fun `tilde, exact, wildcard and ranges`() {
        assertTrue(req("~1.2.3").matches(v("1.2.9")))
        assertFalse(req("~1.2.3").matches(v("1.3.0")))
        assertTrue(req("~1").matches(v("1.7.0")))
        assertTrue(req("=1.2.3").matches(v("1.2.3")))
        assertFalse(req("=1.2.3").matches(v("1.2.4")))
        assertTrue(req("=1.2").matches(v("1.2.8")))
        assertTrue(req("1.*").matches(v("1.5.0")))
        assertFalse(req("1.*").matches(v("2.0.0")))
        assertTrue(req("*").matches(v("42.0.0")))
        assertTrue(req(">=1.2, <1.5").matches(v("1.4.9")))
        assertFalse(req(">=1.2, <1.5").matches(v("1.5.0")))
        assertTrue(req("<=1.2").matches(v("1.2.7")))
        assertFalse(req(">1.2").matches(v("1.2.7")))
        assertNull(VersionReq.parse("not a version"))
        assertNull(VersionReq.parse(""))
    }

    @Test
    fun `pre-releases only for a requirement that names one`() {
        assertFalse(req("1.0").matches(v("1.1.0-beta.1")))
        assertTrue(req("1.0.0-beta.1").matches(v("1.0.0-beta.2")))
    }

    @Test
    fun `outdated means the newest release is out of the requirement`() {
        assertTrue(req("0.8").isOutdatedBy(v("0.10.2")))
        assertTrue(req("1.0.104").isOutdatedBy(v("2.0.0")))
        assertFalse(req("1.0").isOutdatedBy(v("1.0.229")))
        assertFalse(req("1").isOutdatedBy(v("1.53.1")))
        assertFalse(req("2").isOutdatedBy(v("1.9.0")))
        assertFalse(req("1").isOutdatedBy(v("2.0.0-rc.1")))
        assertTrue(req("=1.2.3").isOutdatedBy(v("1.2.4")))
        assertFalse(req("*").isOutdatedBy(v("3.0.0")))
    }
}
