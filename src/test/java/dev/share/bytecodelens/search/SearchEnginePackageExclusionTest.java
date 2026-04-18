package dev.share.bytecodelens.search;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure-string {@code packageExcluded} helper powering the
 * right-click "Exclude from search" workflow. Uses reflection because the helper is
 * package-private-static.
 */
class SearchEnginePackageExclusionTest {

    @SuppressWarnings("unchecked")
    private static boolean call(String fqn, List<String> patterns) throws Exception {
        Method m = SearchEngine.class.getDeclaredMethod(
                "packageExcluded", String.class, List.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, fqn, patterns);
    }

    @Test
    void emptyPatternListExcludesNothing() throws Exception {
        assertFalse(call("com.foo.Bar", List.of()));
        assertFalse(call("com.foo.Bar", null));
    }

    @Test
    void exactFqnMatchExcludes() throws Exception {
        // Pattern "com.foo.Bar" matches exactly the class "com.foo.Bar".
        assertTrue(call("com.foo.Bar", List.of("com.foo.Bar")));
    }

    @Test
    void packageGlobExcludesAllSubclasses() throws Exception {
        // Pattern "com.google.*" excludes com.google.X, com.google.inner.Y, etc.
        assertTrue(call("com.google.common.collect.ImmutableList",
                List.of("com.google.*")));
        assertTrue(call("com.google.App", List.of("com.google.*")));
    }

    @Test
    void globDoesNotMatchSiblingPrefix() throws Exception {
        // "com.goo" should NOT match "com.google.X" — prefix-with-boundary required.
        assertFalse(call("com.google.App", List.of("com.goo.*")));
        assertFalse(call("com.googlelator.App", List.of("com.google.*")));
    }

    @Test
    void trailingDotNormalization() throws Exception {
        // "com.foo." and "com.foo" should behave the same way.
        assertTrue(call("com.foo.Bar", List.of("com.foo.")));
        assertTrue(call("com.foo.Bar", List.of("com.foo")));
    }

    @Test
    void plainPrefixAlsoMatchesSubpackages() throws Exception {
        // Even without the trailing star we accept "com.foo" as a package exclusion
        // — users type either form.
        assertTrue(call("com.foo.Bar", List.of("com.foo")));
    }

    @Test
    void blankEntriesAreIgnored() throws Exception {
        // Empty / whitespace entries don't accidentally exclude everything.
        assertFalse(call("com.foo.Bar", List.of("", "   ", null == null ? "" : null)));
    }

    @Test
    void multiplePatternsAreOrMatched() throws Exception {
        assertTrue(call("com.foo.Bar",
                List.of("com.baz.*", "com.foo.*", "org.other")));
    }

    @Test
    void packageMatchesCombinesPositiveFilterAndExclusion() throws Exception {
        // packageMatches respects BOTH positive filter ("com.*") and exclusion list.
        Method m = SearchEngine.class.getDeclaredMethod(
                "packageMatches", String.class, SearchQuery.class);
        m.setAccessible(true);
        SearchQuery q = new SearchQuery("x", SearchMode.STRINGS, false, false,
                "com.*", true, false, List.of("com.google.*"));
        assertTrue((boolean) m.invoke(null, "com.foo.Bar", q));
        assertFalse((boolean) m.invoke(null, "com.google.Whatever", q));
        assertFalse((boolean) m.invoke(null, "org.other.App", q),
                "positive filter com.* also rejects org.other");
    }
}
