package io.github.rustsupport.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RustPostfixExpressionsTest {
    /** The expression before the dot of `|` (the caret right after the key): its text, or null. */
    private fun expr(code: String): String? {
        val offset = code.indexOf('|')
        val text = code.removeRange(offset, offset + 1)
        return RustPostfixExpressions.expressionBefore(text, offset)?.substring(text)
    }

    @Test fun name() = assertEquals("x", expr("let a = x.if|"))
    @Test fun keyRemoved() = assertEquals("x", expr("let a = x.|"))
    @Test fun fieldChain() = assertEquals("self.items.len()", expr("    self.items.len().if|"))
    @Test fun path() = assertEquals("std::env::args()", expr("let a = std::env::args().for|"))
    @Test fun turbofish() = assertEquals("Vec::<Vec<u8>>::new()", expr("Vec::<Vec<u8>>::new().let|"))
    @Test fun genericCall() = assertEquals("parse::<i32>(s)", expr("parse::<i32>(s).unwrap|"))
    @Test fun qualifiedPath() = assertEquals("<T as Default>::default()", expr("<T as Default>::default().let|"))
    @Test fun indexing() = assertEquals("xs[i + 1]", expr("foo(xs[i + 1].ref|"))
    @Test fun questionMark() = assertEquals("file.read(&mut buf)?", expr("file.read(&mut buf)?.match|"))
    @Test fun awaitChain() = assertEquals("client.get(url).await", expr("client.get(url).await.ok|"))
    @Test fun macroCall() = assertEquals("vec![1, 2, 3]", expr("vec![1, 2, 3].iter|"))
    @Test fun parenthesized() = assertEquals("(a + b)", expr("(a + b).dbg|"))
    @Test fun tupleField() = assertEquals("pair.0", expr("pair.0.clone|"))
    @Test fun stringLiteral() = assertEquals("\"text\"", expr("\"text\".to_string|"))
    @Test fun number() {
        assertEquals("42", expr("let a = 42.some|"))
        assertEquals("42", expr("let a = 42.|"))
        assertEquals("pair.0", expr("pair.0.|\n"))
        assertEquals("1.5", expr("1.5.into|"))
    }
    @Test fun selfKeyword() = assertEquals("self", expr("self.clone|"))
    @Test fun multilineChain() = assertEquals("items\n        .iter()\n        .map(f)", expr("items\n        .iter()\n        .map(f)\n        .for|"))

    @Test fun prefixOperatorsStop() {
        assertEquals("x", expr("&x.ref|"))
        assertEquals("y", expr("*y.deref|"))
        assertEquals("ok", expr("!ok.not|"))
        assertEquals("b", expr("a + b.par|"))
        assertEquals("n", expr("x as u8 + n.into|"))
    }

    @Test fun keywordsStop() {
        assertEquals("v", expr("return v.ok|"))
        assertEquals("(x)", expr("if (x).not|"))
        assertEquals("a", expr("let b = a.clone|"))
    }

    @Test fun comparisonIsNotGenerics() = assertEquals("d", expr("a < b && c > d.not|"))

    @Test fun nothing() {
        assertNull(expr(".if|"))
        assertNull(expr("x = &.if|"))
        assertNull(expr("match x { _ => {} }.if|"))
        assertNull(expr("if|"))
    }

    @Test fun notInStringsOrComments() {
        assertNull(expr("let s = \"abc.if|"))
        assertNull(expr("// x.if|"))
    }

    @Test fun indentOfTheTemplateLines() = assertEquals(
        "if \$EXPR\$ {\n        \$END\$\n    }",
        RustPostfixText.indent("if \$EXPR\$ {\n\t\$END\$\n}", "    ", "    "),
    )

    @Test fun everyKindHasTheExpression() {
        for (kind in RustPostfixKinds.ALL) org.junit.Assert.assertTrue(kind.key, "\$EXPR\$" in kind.text && "\$END\$" in kind.text)
        assertEquals(RustPostfixKinds.ALL.size, RustPostfixKinds.ALL.map { it.key }.toSet().size)
    }

    @Test fun rangeIsNotADot()= assertEquals("n", expr("for i in 0..n.for|"))
}
