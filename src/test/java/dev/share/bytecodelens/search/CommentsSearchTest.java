package dev.share.bytecodelens.search;

import dev.share.bytecodelens.comments.CommentStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for SearchMode.COMMENTS — exercises SearchIndex decoding of comment
 * keys and SearchEngine's scan loop end-to-end without needing a loaded jar.
 */
class CommentsSearchTest {

    @Test
    void decodesAllThreeKeyShapes() {
        CommentStore store = new CommentStore();
        store.set(CommentStore.classKey("com.foo.Bar"), "class note");
        store.set(CommentStore.methodKey("com.foo.Bar", "login", "(Ljava/lang/String;)Z"),
                "method note");
        store.set(CommentStore.fieldKey("com.foo.Bar", "count", "I"), "field note");

        SearchIndex idx = new SearchIndex(null);  // no jar needed for comments scan
        idx.setCommentStore(store);
        List<SearchIndex.CommentEntry> entries = idx.comments();
        assertEquals(3, entries.size());
        // Verify decoded shapes — fqn, kind, memberName, memberDesc.
        var byKind = entries.stream()
                .collect(java.util.stream.Collectors.toMap(SearchIndex.CommentEntry::kind, e -> e));
        assertEquals("com.foo.Bar", byKind.get("class").classFqn());
        assertEquals("login", byKind.get("method").memberName());
        assertEquals("(Ljava/lang/String;)Z", byKind.get("method").memberDesc());
        assertEquals("count", byKind.get("field").memberName());
        assertEquals("I", byKind.get("field").memberDesc());
    }

    @Test
    void findsMatchingCommentIgnoresNonMatching() {
        CommentStore store = new CommentStore();
        store.set(CommentStore.classKey("com.foo.Bar"), "This is auth-related");
        store.set(CommentStore.methodKey("com.foo.Bar", "login", "()V"), "entry point");
        store.set(CommentStore.classKey("com.other.Unrelated"), "parser helper");

        SearchIndex idx = new SearchIndex(null);
        idx.setCommentStore(store);

        SearchQuery q = new SearchQuery("auth", SearchMode.COMMENTS,
                false, false, null, true, false);
        List<SearchResult> results = new SearchEngine().search(idx, q);
        assertEquals(1, results.size());
        assertEquals(SearchResult.TargetKind.COMMENT, results.get(0).targetKind());
        assertEquals("com.foo.Bar", results.get(0).targetPath());
    }

    @Test
    void returnsEmptyWhenNoCommentStoreWired() {
        SearchIndex idx = new SearchIndex(null);
        // No setCommentStore() call — comment search should be harmless.
        SearchQuery q = new SearchQuery("x", SearchMode.COMMENTS,
                false, false, null, true, false);
        List<SearchResult> results = new SearchEngine().search(idx, q);
        assertTrue(results.isEmpty());
    }

    @Test
    void respectsExcludedPackages() {
        CommentStore store = new CommentStore();
        store.set(CommentStore.classKey("com.foo.Bar"), "keyword");
        store.set(CommentStore.classKey("com.noise.A"), "keyword");
        store.set(CommentStore.classKey("com.noise.sub.B"), "keyword");

        SearchIndex idx = new SearchIndex(null);
        idx.setCommentStore(store);

        SearchQuery q = new SearchQuery("keyword", SearchMode.COMMENTS,
                false, false, null, true, false, List.of("com.noise.*"));
        List<SearchResult> results = new SearchEngine().search(idx, q);
        assertEquals(1, results.size());
        assertEquals("com.foo.Bar", results.get(0).targetPath());
    }

    @Test
    void contextLabelReflectsMemberKind() {
        CommentStore store = new CommentStore();
        store.set(CommentStore.methodKey("com.foo.Bar", "login", "()V"), "xyz");
        store.set(CommentStore.fieldKey("com.foo.Bar", "count", "I"), "xyz");
        store.set(CommentStore.classKey("com.foo.Bar"), "xyz");

        SearchIndex idx = new SearchIndex(null);
        idx.setCommentStore(store);

        var all = new SearchEngine().search(idx,
                new SearchQuery("xyz", SearchMode.COMMENTS,
                        false, false, null, true, false));
        assertEquals(3, all.size());
        assertTrue(all.stream().anyMatch(r -> r.context().equals("class comment")));
        assertTrue(all.stream().anyMatch(r -> r.context().equals("method login() comment")));
        assertTrue(all.stream().anyMatch(r -> r.context().equals("field count comment")));
    }
}
