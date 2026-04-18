package dev.share.bytecodelens.decompile;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Per-class outcome of the most recent decompile attempt. Lets the class tree
 * paint a small status badge (clean / partial / failed) and lets right-click
 * actions know whether a "Recover with Fallback" option makes sense.
 *
 * <p>Status is keyed by internal name only — engine choice is intentionally not
 * part of the key, since the badge wants to show the <em>last attempt</em> the
 * user actually saw, not a per-engine matrix.</p>
 */
public final class DecompileStatusTracker {

    public enum Status {
        /** No attempt yet (or just opened the jar). */
        UNKNOWN,
        /** A real engine produced source. */
        SUCCESS,
        /** Every real engine failed/timed out — only Fallback skeleton was rendered. */
        FALLBACK_ONLY,
        /** Even the chain couldn't render anything — class is effectively unreadable. */
        FAILED
    }

    public record Entry(Status status, String engineUsed, String reason, long elapsedMs) {}

    private final Map<String, Entry> byInternal = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    /** Record the outcome for {@code internalName}. Notifies listeners on actual change. */
    public void update(String internalName, Entry entry) {
        if (internalName == null || entry == null) return;
        Entry prev = byInternal.put(internalName, entry);
        if (prev == null || prev.status() != entry.status()) {
            for (Consumer<String> l : listeners) {
                try { l.accept(internalName); } catch (Throwable ignored) {}
            }
        }
    }

    public Entry get(String internalName) {
        return byInternal.get(internalName);
    }

    public Status statusOf(String internalName) {
        Entry e = byInternal.get(internalName);
        return e == null ? Status.UNKNOWN : e.status();
    }

    /** Subscribe to per-class status changes. Listener fires on the calling thread. */
    public void addListener(Consumer<String> listener) {
        listeners.add(listener);
    }

    public void clear() {
        byInternal.clear();
    }

    /** Derive a Status from a chain trace — highest-quality outcome wins. */
    public static Status fromTrace(java.util.List<DecompileResult> trace) {
        if (trace == null || trace.isEmpty()) return Status.FAILED;
        DecompileResult last = trace.get(trace.size() - 1);
        if (last.success()) {
            // Fallback engine name is "Fallback" — if that's what won, mark partial.
            if ("Fallback".equals(last.decompilerName())) return Status.FALLBACK_ONLY;
            return Status.SUCCESS;
        }
        return Status.FAILED;
    }
}
