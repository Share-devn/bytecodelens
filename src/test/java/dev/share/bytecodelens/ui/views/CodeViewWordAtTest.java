package dev.share.bytecodelens.ui.views;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for the private {@code wordAt} helper used by Ctrl+Click symbol navigation.
 * We use reflection because the logic is package-private-static and moving it purely for
 * testability would bloat the public API.
 */
class CodeViewWordAtTest {

    private static String call(String text, int pos) throws Exception {
        Method m = CodeView.class.getDeclaredMethod("wordAt", String.class, int.class);
        m.setAccessible(true);
        return (String) m.invoke(null, text, pos);
    }

    @Test
    void extractsWordAtMiddleOfIdentifier() throws Exception {
        String src = "  foo.bar()";
        // positions 2..4 inside "foo"
        assertEquals("foo", call(src, 2));
        assertEquals("foo", call(src, 3));
        assertEquals("foo", call(src, 4));
    }

    @Test
    void extractsWordAtEdgeAfterIdentifier() throws Exception {
        String src = "Foo.bar";
        // caret right after 'o' (pos 3) — we step back to land in "Foo"
        assertEquals("Foo", call(src, 3));
    }

    @Test
    void clickImmediatelyAfterWordSelectsThatWord() throws Exception {
        // Clicking between 'a' and '.' — practical: user wanted the 'a'.
        String src = "a.b";
        assertEquals("a", call(src, 1));
    }

    @Test
    void clickOnPunctuationBetweenWhitespaceReturnsNull() throws Exception {
        // No identifier before OR at position.
        String src = "foo . bar";
        assertNull(call(src, 4)); // the '.'
    }

    @Test
    void returnsNullOnWhitespace() throws Exception {
        String src = "foo   bar";
        assertNull(call(src, 4));
    }

    @Test
    void handlesIdentifierAtEndOfText() throws Exception {
        String src = "method";
        assertEquals("method", call(src, 5));
        assertEquals("method", call(src, 6)); // caret at end
    }

    @Test
    void handlesIdentifierAtStart() throws Exception {
        String src = "Class foo";
        assertEquals("Class", call(src, 0));
        assertEquals("Class", call(src, 4));
    }

    @Test
    void handlesUnderscoreAndDigits() throws Exception {
        String src = "my_var2";
        assertEquals("my_var2", call(src, 3));
    }

    @Test
    void handlesDollarInIdentifier() throws Exception {
        String src = "Outer$Inner";
        // '$' is a valid Java identifier part
        assertEquals("Outer$Inner", call(src, 3));
        assertEquals("Outer$Inner", call(src, 7));
    }

    @Test
    void returnsNullOnNullOrOutOfBounds() throws Exception {
        assertNull(call(null, 0));
        assertNull(call("", 0));
        assertNull(call("abc", -1));
        assertNull(call("abc", 10));
    }
}
