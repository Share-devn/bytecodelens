package dev.share.bytecodelens.ui.views;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the fuzzy matcher driving the Start Page search. Verifies the VS Code /
 * IntelliJ Ctrl+P semantics: every needle char must appear in order. Rejected inputs
 * return -1 (falsy for the UI filter).
 */
class StartPageFuzzyTest {

    private static int call(String haystack, String needle) throws Exception {
        Method m = StartPage.class.getDeclaredMethod("fuzzyScore", String.class, String.class);
        m.setAccessible(true);
        return (int) m.invoke(null, haystack, needle);
    }

    @Test
    void exactMatchScoresLow() throws Exception {
        // "exact match" means 0 gaps -> score of 1 (non-negative).
        assertTrue(call("library.jar", "library") >= 0);
    }

    @Test
    void subsequenceMatchesEvenWithGaps() throws Exception {
        // "lbr" matches "library"
        assertTrue(call("library.jar", "lbr") >= 0);
    }

    @Test
    void emptyNeedleMatchesEverything() throws Exception {
        assertEquals(0, call("anything.jar", ""));
        assertEquals(0, call("x", "   "));
    }

    @Test
    void needleLongerThanHaystackRejects() throws Exception {
        assertTrue(call("abc", "abcd") < 0);
    }

    @Test
    void outOfOrderCharsReject() throws Exception {
        // 'ba' not a subsequence of 'abc'
        assertTrue(call("abc", "ba") < 0);
    }

    @Test
    void caseInsensitive() throws Exception {
        assertTrue(call("Library.JAR", "LIBRARY") >= 0);
        assertTrue(call("Library.JAR", "library") >= 0);
    }

    @Test
    void moreGapsProducesLargerScoreForSorting() throws Exception {
        int compact = call("lib.jar", "lj");     // gap between b and j
        int spread = call("library.jar", "lj");  // bigger gap
        assertTrue(compact >= 0);
        assertTrue(spread >= 0);
        assertTrue(spread > compact,
                "spread matches should sort after compact matches (larger gap = worse)");
    }
}
