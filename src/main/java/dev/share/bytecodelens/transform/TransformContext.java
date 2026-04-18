package dev.share.bytecodelens.transform;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mutable context threaded through transformation passes. Each pass increments its own
 * counters here; after the run, the runner uses these to build a user-facing summary.
 */
public final class TransformContext {

    /** counters[transformationId] -> {counterName: count}. */
    private final Map<String, Map<String, Integer>> counters = new LinkedHashMap<>();
    private String currentPassId;

    /** Called by the runner before invoking each pass on a class node. */
    public void enterPass(String transformationId) {
        this.currentPassId = transformationId;
        counters.computeIfAbsent(transformationId, k -> new LinkedHashMap<>());
    }

    public void exitPass() {
        this.currentPassId = null;
    }

    /** Increment a named counter on the currently running pass. */
    public void inc(String counter) {
        inc(counter, 1);
    }

    public void inc(String counter, int amount) {
        if (currentPassId == null) return;
        counters.get(currentPassId).merge(counter, amount, Integer::sum);
    }

    public Map<String, Map<String, Integer>> counters() {
        return counters;
    }

    public int totalFor(String passId) {
        Map<String, Integer> map = counters.get(passId);
        if (map == null) return 0;
        return map.values().stream().mapToInt(Integer::intValue).sum();
    }
}
