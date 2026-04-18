package dev.share.bytecodelens.comments;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommentStoreTest {

    @Test
    void keyFormatsAreStable() {
        assertEquals("CLASS:com.foo.Bar", CommentStore.classKey("com.foo.Bar"));
        assertEquals("METHOD:com.foo.Bar:login:(Ljava/lang/String;)Z",
                CommentStore.methodKey("com.foo.Bar", "login", "(Ljava/lang/String;)Z"));
        assertEquals("FIELD:com.foo.Bar:count:I",
                CommentStore.fieldKey("com.foo.Bar", "count", "I"));
    }

    @Test
    void setAndGet() {
        CommentStore store = new CommentStore();
        store.set("CLASS:a", "hello");
        assertEquals("hello", store.get("CLASS:a"));
        assertTrue(store.has("CLASS:a"));
        assertEquals(1, store.size());
    }

    @Test
    void settingBlankRemovesEntry() {
        CommentStore store = new CommentStore();
        store.set("CLASS:a", "comment");
        store.set("CLASS:a", "");
        assertNull(store.get("CLASS:a"));
        assertFalse(store.has("CLASS:a"));
    }

    @Test
    void settingNullRemovesEntry() {
        CommentStore store = new CommentStore();
        store.set("CLASS:a", "comment");
        store.set("CLASS:a", null);
        assertFalse(store.has("CLASS:a"));
    }

    @Test
    void replaceAllSwapsContentsAndNotifies() {
        CommentStore store = new CommentStore();
        AtomicInteger notifications = new AtomicInteger();
        store.addListener(k -> notifications.incrementAndGet());

        store.set("CLASS:a", "first");
        Map<String, String> incoming = new HashMap<>();
        incoming.put("CLASS:b", "loaded");
        incoming.put("METHOD:c:m:()V", "note");
        store.replaceAll(incoming);

        assertEquals(2, store.size());
        assertFalse(store.has("CLASS:a"));
        assertEquals("loaded", store.get("CLASS:b"));
        assertTrue(notifications.get() >= 2);
    }

    @Test
    void listenerReceivesChangedKey() {
        CommentStore store = new CommentStore();
        java.util.List<String> seen = new java.util.ArrayList<>();
        store.addListener(seen::add);
        store.set("CLASS:x", "v");
        store.remove("CLASS:x");
        assertEquals(java.util.List.of("CLASS:x", "CLASS:x"), seen);
    }
}
