package dev.share.bytecodelens.decompile;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DecompileStatusTrackerTest {

    @Test
    void unknownByDefault() {
        var t = new DecompileStatusTracker();
        assertEquals(DecompileStatusTracker.Status.UNKNOWN, t.statusOf("p/A"));
        assertNull(t.get("p/A"));
    }

    @Test
    void updateStoresAndExposes() {
        var t = new DecompileStatusTracker();
        t.update("p/A", new DecompileStatusTracker.Entry(
                DecompileStatusTracker.Status.SUCCESS, "CFR", null, 42));
        assertEquals(DecompileStatusTracker.Status.SUCCESS, t.statusOf("p/A"));
        assertEquals("CFR", t.get("p/A").engineUsed());
        assertEquals(42, t.get("p/A").elapsedMs());
    }

    @Test
    void listenerFiresOnStatusChangeOnly() {
        var t = new DecompileStatusTracker();
        AtomicInteger fires = new AtomicInteger();
        t.addListener(name -> fires.incrementAndGet());
        t.update("p/A", new DecompileStatusTracker.Entry(
                DecompileStatusTracker.Status.SUCCESS, "CFR", null, 1));
        assertEquals(1, fires.get());
        // Same status — no fire.
        t.update("p/A", new DecompileStatusTracker.Entry(
                DecompileStatusTracker.Status.SUCCESS, "CFR", null, 2));
        assertEquals(1, fires.get());
        // Status change — fires.
        t.update("p/A", new DecompileStatusTracker.Entry(
                DecompileStatusTracker.Status.FAILED, "CFR", "boom", 5));
        assertEquals(2, fires.get());
    }

    @Test
    void fromTraceSuccess() {
        var trace = List.of(
                DecompileResult.failed("// CFR fail", "CFR", "x", 1),
                DecompileResult.ok("class C {}", "Vineflower", 100));
        assertEquals(DecompileStatusTracker.Status.SUCCESS,
                DecompileStatusTracker.fromTrace(trace));
    }

    @Test
    void fromTraceFallbackOnly() {
        var trace = List.of(
                DecompileResult.failed("// CFR fail", "CFR", "x", 1),
                DecompileResult.failed("// VF fail", "Vineflower", "x", 2),
                DecompileResult.failed("// Pro fail", "Procyon", "x", 3),
                DecompileResult.ok("// skeleton", "Fallback", 5));
        assertEquals(DecompileStatusTracker.Status.FALLBACK_ONLY,
                DecompileStatusTracker.fromTrace(trace));
    }

    @Test
    void fromTraceAllFailed() {
        var trace = List.of(
                DecompileResult.failed("// CFR fail", "CFR", "x", 1),
                DecompileResult.failed("// VF fail", "Vineflower", "x", 2));
        assertEquals(DecompileStatusTracker.Status.FAILED,
                DecompileStatusTracker.fromTrace(trace));
    }

    @Test
    void fromEmptyOrNullTrace() {
        assertEquals(DecompileStatusTracker.Status.FAILED,
                DecompileStatusTracker.fromTrace(null));
        assertEquals(DecompileStatusTracker.Status.FAILED,
                DecompileStatusTracker.fromTrace(List.of()));
    }

    @Test
    void clearWipesEntries() {
        var t = new DecompileStatusTracker();
        t.update("p/A", new DecompileStatusTracker.Entry(
                DecompileStatusTracker.Status.SUCCESS, "CFR", null, 1));
        t.clear();
        assertEquals(DecompileStatusTracker.Status.UNKNOWN, t.statusOf("p/A"));
    }

    @Test
    void nullArgsIgnored() {
        var t = new DecompileStatusTracker();
        t.update(null, new DecompileStatusTracker.Entry(
                DecompileStatusTracker.Status.SUCCESS, "CFR", null, 1));
        t.update("p/A", null);
        assertEquals(0, 0); // didn't crash
    }
}
