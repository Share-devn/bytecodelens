package dev.share.bytecodelens.search;

import dev.share.bytecodelens.comments.CommentStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the streaming entry point of {@link SearchEngine} — incremental consumer
 * delivery and cooperative cancellation.
 */
class SearchEngineStreamingTest {

    /** Build an index populated with enough comments to exercise the scan loop. */
    private static SearchIndex indexWithComments(int howMany) {
        CommentStore store = new CommentStore();
        for (int i = 0; i < howMany; i++) {
            store.set(CommentStore.classKey("com.foo.Class" + i), "keyword body " + i);
        }
        SearchIndex idx = new SearchIndex(null);
        idx.setCommentStore(store);
        return idx;
    }

    @Test
    void streamingConsumerReceivesHitsIncrementally() {
        SearchIndex idx = indexWithComments(50);
        List<SearchResult> received = new ArrayList<>();
        new SearchEngine().search(idx,
                new SearchQuery("keyword", SearchMode.COMMENTS, false, false,
                        null, true, false),
                received::add, () -> false);
        assertEquals(50, received.size());
    }

    @Test
    void cancelFlagHaltsScanEarly() {
        SearchIndex idx = indexWithComments(500);
        AtomicBoolean cancel = new AtomicBoolean(false);
        List<SearchResult> received = new ArrayList<>();
        new SearchEngine().search(idx,
                new SearchQuery("keyword", SearchMode.COMMENTS, false, false,
                        null, true, false),
                r -> {
                    received.add(r);
                    // Ask to stop after we've seen the first 10 hits.
                    if (received.size() >= 10) cancel.set(true);
                },
                cancel::get);
        // We might overshoot slightly because the flag is checked at loop heads, not
        // after every hit. 10..50 is a reasonable window — the important thing is it
        // STOPPED far before 500.
        assertTrue(received.size() >= 10, "Should have received at least the trigger batch");
        assertTrue(received.size() < 500, "Cancel did not halt the scan");
    }

    @Test
    void backCompatListApiStillReturnsEverything() {
        SearchIndex idx = indexWithComments(30);
        List<SearchResult> list = new SearchEngine().search(idx,
                new SearchQuery("keyword", SearchMode.COMMENTS, false, false,
                        null, true, false));
        assertEquals(30, list.size());
    }

    @Test
    void nullConsumerNoOps() {
        SearchIndex idx = indexWithComments(10);
        // Passing a null consumer must not explode — it's a no-op.
        new SearchEngine().search(idx,
                new SearchQuery("keyword", SearchMode.COMMENTS, false, false,
                        null, true, false),
                null, () -> false);
    }
}
