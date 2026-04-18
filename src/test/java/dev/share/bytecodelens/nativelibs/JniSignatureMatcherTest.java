package dev.share.bytecodelens.nativelibs;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for JNI name demangling. Examples drawn from the JNI spec plus real .so's
 * I've decompiled (HotSpot, Leveldb, Sqlite JDBC).
 */
class JniSignatureMatcherTest {

    @Test
    void parsesPlainJniName() {
        var p = JniSignatureMatcher.parse("Java_com_foo_Bar_doThing");
        assertNotNull(p);
        assertEquals("com.foo.Bar", p.classFqn());
        assertEquals("doThing", p.methodName());
        assertNull(p.methodDescriptor());
    }

    @Test
    void parsesOverloadedJniNameWithDescriptor() {
        // Overloaded forms append __<desc>. The desc itself uses the same escape rules.
        var p = JniSignatureMatcher.parse(
                "Java_com_foo_Bar_send__Ljava_lang_String_2");
        assertNotNull(p);
        assertEquals("com.foo.Bar", p.classFqn());
        assertEquals("send", p.methodName());
        // _2 -> ; , regular _ -> /
        assertEquals("Ljava/lang/String;", p.methodDescriptor());
    }

    @Test
    void parsesUnderscoreInOriginalName() {
        // my_method becomes my_1method after JNI mangling.
        var p = JniSignatureMatcher.parse("Java_com_foo_Bar_my_1method");
        assertNotNull(p);
        assertEquals("my_method", p.methodName());
    }

    @Test
    void parsesUnicodeEscape() {
        // Unicode letter escapes as _0XXXX (4-hex).
        // 'z' is 0x007A — we'll use it just to exercise the path.
        var p = JniSignatureMatcher.parse("Java_com_foo_Bar_pre_0007Apost");
        assertNotNull(p);
        assertEquals("prezpost", p.methodName());
    }

    @Test
    void parsesArrayDescriptor() {
        // '[' escapes as _3
        var p = JniSignatureMatcher.parse("Java_com_foo_Bar_m___3I");
        assertNotNull(p);
        assertEquals("[I", p.methodDescriptor());
    }

    @Test
    void rejectsNonJniSymbols() {
        assertNull(JniSignatureMatcher.parse("malloc"));
        assertNull(JniSignatureMatcher.parse("_init"));
        assertNull(JniSignatureMatcher.parse(""));
        assertNull(JniSignatureMatcher.parse(null));
    }

    @Test
    void rejectsJavaPrefixWithoutClassMethod() {
        // "Java_" with nothing after has no class/method — should be rejected, not crash.
        assertNull(JniSignatureMatcher.parse("Java_"));
        assertNull(JniSignatureMatcher.parse("Java_foo"));
    }

    @Test
    void findJniSymbolsCrossReferencesWorkspaceClasses() {
        List<String> symbols = List.of(
                "malloc",
                "Java_com_foo_Bar_x",
                "Java_com_foo_Missing_y",
                "_PyObject_New");
        Set<String> known = Set.of("com.foo.Bar");
        var hits = JniSignatureMatcher.findJniSymbols(symbols, known);
        assertEquals(2, hits.size());
        var byClass = hits.stream().collect(java.util.stream.Collectors.toMap(
                h -> h.parsed().classFqn(), h -> h.matchesWorkspaceClass()));
        assertTrue(byClass.get("com.foo.Bar"));
        assertTrue(!byClass.get("com.foo.Missing"));
    }

    @Test
    void unmangleHandlesCombinedEscapes() {
        // "_1" followed by "_2" should decode as "_;"
        assertEquals("_;", JniSignatureMatcher.unmangle("_1_2"));
        // Trailing underscore → '/' to fail gracefully.
        assertEquals("a/", JniSignatureMatcher.unmangle("a_"));
    }
}
