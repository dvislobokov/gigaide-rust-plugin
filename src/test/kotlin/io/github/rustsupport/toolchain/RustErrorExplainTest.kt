package io.github.rustsupport.toolchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RustErrorExplainTest {
    @Test fun normalize() {
        assertEquals("E0308", RustErrorCodes.normalize("E0308"))
        assertEquals("E0308", RustErrorCodes.normalize(" e0308 "))
        assertEquals("E0308", RustErrorCodes.normalize("308"))
        assertEquals("E0001", RustErrorCodes.normalize("E1"))
        assertNull(RustErrorCodes.normalize("E"))
        assertNull(RustErrorCodes.normalize("E0000"))
        assertNull(RustErrorCodes.normalize("E12345"))
        assertNull(RustErrorCodes.normalize("unused_variables"))
    }

    @Test fun codeOfDiagnostic() {
        assertEquals("E0308", RustErrorCodes.fromDiagnostic("E0308", "https://doc.rust-lang.org/error-index.html#E0308"))
        assertEquals("E0425", RustErrorCodes.fromDiagnostic("unresolved-ident", "https://doc.rust-lang.org/stable/error_codes/E0425.html"))
        assertEquals("E0599", RustErrorCodes.fromDiagnostic(null, "https://doc.rust-lang.org/error-index.html#E0599"))
        assertNull(RustErrorCodes.fromDiagnostic("unused_variables", null))
        assertNull(RustErrorCodes.fromDiagnostic("clippy::needless_return", "https://rust-lang.github.io/rust-clippy/master/index.html#needless_return"))
        assertNull(RustErrorCodes.fromDiagnostic("inactive-code", "https://rust-analyzer.github.io/book/diagnostics.html#inactive-code"))
    }

    @Test fun markdownMarksRustFences() {
        val raw = "Expected type did not match.\n\nErroneous code example:\n\n```compile_fail,E0308\nlet x: i32 = \"a\";\n```\n\nShell:\n\n```text\n$ cargo\n```\n\n```\nfn ok() {}\n```\n"
        val md = RustErrorCodes.markdown("E0308", raw)
        assertEquals(
            "# E0308\n\nExpected type did not match.\n\nErroneous code example:\n\n```rust\nlet x: i32 = \"a\";\n```\n\nShell:\n\n```text\n\$ cargo\n```\n\n```rust\nfn ok() {}\n```\n",
            md,
        )
    }

    @Test fun cacheKeepsExplanationsButNotFailures() {
        var runs = 0
        var fail = true
        val cache = RustExplanationCache { code ->
            runs++
            when {
                code == "E9999" -> RustExplanation.Invalid("no")
                fail -> RustExplanation.Failed("no rustc")
                else -> RustExplanation.Text("# $code")
            }
        }
        assertEquals(RustExplanation.Failed("no rustc"), cache.get("E0308"))
        fail = false
        assertEquals(RustExplanation.Text("# E0308"), cache.get("E0308"))
        assertEquals(RustExplanation.Text("# E0308"), cache.get("E0308"))
        assertEquals(RustExplanation.Invalid("no"), cache.get("E9999"))
        assertEquals(RustExplanation.Invalid("no"), cache.get("E9999"))
        assertEquals(3, runs)
    }
}
