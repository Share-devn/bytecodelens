package dev.share.bytecodelens.ui.views;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the whole-word occurrence scanner that powers hover-highlight. The scanner is
 * a pure string function, independent of JavaFX, so it can be exercised directly without
 * a headless toolkit.
 */
class CodeViewHoverTest {

    @Test
    void findsEveryStandaloneOccurrence() {
        String src = "foo bar foo baz foo";
        List<int[]> hits = CodeView.findWholeWordOccurrences(src, "foo", 100);
        assertEquals(3, hits.size());
        assertEquals(0, hits.get(0)[0]);
        assertEquals(8, hits.get(1)[0]);
        assertEquals(16, hits.get(2)[0]);
    }

    @Test
    void rejectsPartialMatchesInsideLongerIdentifier() {
        // "list" must not match inside "checklist" or "listener".
        String src = "list checklist listener list";
        List<int[]> hits = CodeView.findWholeWordOccurrences(src, "list", 100);
        assertEquals(2, hits.size());
        assertEquals(0, hits.get(0)[0]);
        assertEquals(24, hits.get(1)[0]);
    }

    @Test
    void treatsDollarAndUnderscoreAsIdentifierChars() {
        // "foo" inside "foo$bar" should NOT match — $ is a Java identifier part.
        String src = "foo foo$bar foo_baz foo";
        List<int[]> hits = CodeView.findWholeWordOccurrences(src, "foo", 100);
        assertEquals(2, hits.size());
        assertEquals(0, hits.get(0)[0]);
        assertEquals(20, hits.get(1)[0]);
    }

    @Test
    void respectsCap() {
        String src = "x x x x x x x x x x";
        List<int[]> hits = CodeView.findWholeWordOccurrences(src, "x", 3);
        assertEquals(3, hits.size());
    }

    @Test
    void returnsEmptyOnNullsAndTooShortHaystack() {
        assertTrue(CodeView.findWholeWordOccurrences(null, "x", 10).isEmpty());
        assertTrue(CodeView.findWholeWordOccurrences("abc", null, 10).isEmpty());
        assertTrue(CodeView.findWholeWordOccurrences("abc", "", 10).isEmpty());
        assertTrue(CodeView.findWholeWordOccurrences("ab", "abc", 10).isEmpty());
    }

    @Test
    void matchAtStartAndEnd() {
        String src = "foo middle foo";
        List<int[]> hits = CodeView.findWholeWordOccurrences(src, "foo", 100);
        assertEquals(2, hits.size());
        assertEquals(0, hits.get(0)[0]);
        assertEquals(3, hits.get(0)[1]);
        assertEquals(11, hits.get(1)[0]);
        assertEquals(14, hits.get(1)[1]);
    }

    @Test
    void overlappingInBareRepetitionButWholeWordBlocksInternal() {
        // "aaa" in "aaaa" would match at index 0, but "aaa" inside "aaaa" fails whole-word
        // (the char after index 3 is 'a' which is an id-part). So 0 matches.
        String src = "aaaa";
        List<int[]> hits = CodeView.findWholeWordOccurrences(src, "aaa", 100);
        assertEquals(0, hits.size());
    }
}
