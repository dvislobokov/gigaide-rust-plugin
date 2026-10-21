package io.github.rustsupport.lang

import io.github.rustsupport.lang.RustTemplatePlaces.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RustTemplatePlacesTest {
    /** The place of the abbreviation that ends at `|`. */
    private fun place(code: String): Place? {
        val offset = code.indexOf('|')
        return RustTemplatePlaces.placeAt(code.removeRange(offset, offset + 1), offset)
    }

    @Test fun topLevel() {
        assertEquals(Place.ITEM, place("fn|"))
        assertEquals(Place.ITEM, place("use std::io;\n\nst|"))
        assertEquals(Place.ITEM, place("fn a() {}\nfn b(x: i32) -> u8 { 1 }\ntest|"))
    }

    @Test fun functionBody() {
        assertEquals(Place.CODE, place("fn main() {\n    p|"))
        assertEquals(Place.CODE, place("fn main() {\n    let x = 1;\n    if x > 0 {\n        pd|"))
        assertEquals(Place.CODE, place("fn f() -> impl Iterator<Item = u8> {\n    m|"))
        assertEquals(Place.CODE, place("fn f(xs: [u8; 4]) {\n    for|"))
        assertEquals(Place.CODE, place("fn f() {\n    let s = S { a: 1, b: c|"))
        assertEquals(Place.CODE, place("fn f() {\n    foo(|x| {\n        d|"))
        assertEquals(Place.CODE, place("fn f() {\n    call(a, todo|"))
    }

    @Test fun itemContainers() {
        assertEquals(Place.ITEM, place("impl Foo {\n    pfn|"))
        assertEquals(Place.ITEM, place("impl Display for Foo {\n    fn|"))
        assertEquals(Place.ITEM, place("#[cfg(test)]\nmod tests {\n    use super::*;\n\n    test|"))
        assertEquals(Place.ITEM, place("trait T {\n    fn a();\n    fn|"))
        assertEquals(Place.ITEM, place("fn main() {\n    impl X {\n        fn|"))
        assertEquals(Place.ITEM, place("mod a {\n    fn b() {}\n    st|"))
    }

    @Test fun methodBodyInImpl() = assertEquals(Place.CODE, place("impl Foo {\n    fn new() -> Self {\n        p|"))

    @Test fun neitherInStructsEnumsAndSignatures() {
        assertEquals(Place.OTHER, place("struct S {\n    a: u8,\n    b|"))
        assertEquals(Place.OTHER, place("enum E {\n    A = 1,\n    B|"))
        assertEquals(Place.OTHER, place("fn f(a: u8, b|"))
        assertEquals(Place.OTHER, place("macro_rules! m {\n    () => {\n        p|"))
    }

    @Test fun attributeArgumentsDoNotCount() = assertEquals(Place.ITEM, place("#[path = \"x.rs\"]\nmod m {\n    fn|"))

    @Test fun afterAClosedItemBackToTop() = assertEquals(Place.ITEM, place("struct S {\n    a: u8,\n}\n\nimpl S {}\n\nfn|"))

    @Test fun stringsAndComments() {
        assertNull(place("fn main() {\n    let s = \"p|"))
        assertNull(place("fn main() {\n    // p|"))
        assertNull(place("/// docs p|"))
        assertNull(place("fn main() {\n    let s = r#\"p|"))
        assertEquals(Place.CODE, place("fn main() {\n    let s = \"x\"; p|"))
        assertEquals(Place.CODE, place("fn main() {\n    /* c */ p|"))
    }

    @Test fun literalOrCommentAtOffset() {
        assertTrue(RustTemplatePlaces.isInLiteralOrComment("x = \"a", 6))
        assertTrue(RustTemplatePlaces.isInLiteralOrComment("x = '\\n", 7))
        assertFalse(RustTemplatePlaces.isInLiteralOrComment("x = 'a'", 7))
        assertFalse(RustTemplatePlaces.isInLiteralOrComment("fn f<'a>", 7))
    }
}
