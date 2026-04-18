package dev.share.bytecodelens.comments;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Central store for user comments on classes, methods and fields.
 *
 * <p>Keys use stable string form so they survive workspace save/restore:
 * <ul>
 *     <li>{@code CLASS:com.foo.Bar}</li>
 *     <li>{@code METHOD:com.foo.Bar:login:(Ljava/lang/String;)Z}</li>
 *     <li>{@code FIELD:com.foo.Bar:count:I}</li>
 * </ul>
 *
 * <p>Listeners are notified on every add/edit/remove so downstream views can update
 * without polling.</p>
 */
public final class CommentStore {

    private final Map<String, String> comments = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    public static String classKey(String fqn) {
        return "CLASS:" + fqn;
    }

    public static String methodKey(String fqn, String name, String desc) {
        return "METHOD:" + fqn + ":" + name + ":" + desc;
    }

    public static String fieldKey(String fqn, String name, String desc) {
        return "FIELD:" + fqn + ":" + name + ":" + desc;
    }

    public String get(String key) {
        return comments.get(key);
    }

    public void set(String key, String text) {
        if (text == null || text.isBlank()) {
            comments.remove(key);
        } else {
            comments.put(key, text);
        }
        notifyListeners(key);
    }

    public void remove(String key) {
        if (comments.remove(key) != null) {
            notifyListeners(key);
        }
    }

    public boolean has(String key) {
        return comments.containsKey(key);
    }

    public Map<String, String> all() {
        return Collections.unmodifiableMap(comments);
    }

    public int size() {
        return comments.size();
    }

    public void clear() {
        comments.clear();
        notifyListeners(null);
    }

    /** Replace the whole map atomically (used during workspace restore). */
    public void replaceAll(Map<String, String> newComments) {
        comments.clear();
        if (newComments != null) comments.putAll(newComments);
        notifyListeners(null);
    }

    /** Listener is invoked with the changed key, or null if the whole store changed. */
    public void addListener(Consumer<String> listener) {
        listeners.add(listener);
    }

    private void notifyListeners(String key) {
        for (Consumer<String> l : listeners) {
            try { l.accept(key); } catch (Exception ignored) { }
        }
    }
}
