package dev.share.bytecodelens.decompile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the chain-of-responsibility logic in {@link DecompileChain}. Uses simple
 * stubs that either succeed, throw, or hang so we cover the main failure modes
 * without touching real decompilers.
 */
class DecompileChainTest {

    private static ClassDecompiler ok(String name, String out) {
        return new ClassDecompiler() {
            @Override public String name() { return name; }
            @Override public String decompile(String internalName, byte[] classBytes) { return out; }
        };
    }

    private static ClassDecompiler thrower(String name) {
        return new ClassDecompiler() {
            @Override public String name() { return name; }
            @Override public String decompile(String internalName, byte[] classBytes) {
                throw new RuntimeException("boom");
            }
        };
    }

    private static ClassDecompiler hanger(String name) {
        return new ClassDecompiler() {
            @Override public String name() { return name; }
            @Override public String decompile(String internalName, byte[] classBytes) {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                return "never";
            }
        };
    }

    @Test
    void firstSuccessShortCircuits() {
        DecompileChain chain = new DecompileChain(List.of(ok("A", "// a"), ok("B", "// b")));
        List<DecompileResult> trace = chain.run("Foo", new byte[0]);
        assertEquals(1, trace.size());
        assertEquals("A", trace.get(0).decompilerName());
        assertTrue(trace.get(0).success());
    }

    @Test
    void exceptionFallsThroughToNext() {
        DecompileChain chain = new DecompileChain(List.of(thrower("A"), ok("B", "// b")));
        List<DecompileResult> trace = chain.run("Foo", new byte[0]);
        assertEquals(2, trace.size());
        assertFalse(trace.get(0).success());
        assertEquals("boom", trace.get(0).reason());
        assertTrue(trace.get(1).success());
    }

    @Test
    void allFailureReturnsTraceWithLastAsFallback() {
        DecompileChain chain = new DecompileChain(List.of(thrower("A"), thrower("B"), thrower("C")));
        List<DecompileResult> trace = chain.run("Foo", new byte[0]);
        assertEquals(3, trace.size());
        for (DecompileResult r : trace) assertFalse(r.success());
        // Last entry should still have non-null text (the error comment).
        assertFalse(trace.get(2).text().isEmpty());
    }

    @Test
    void timeoutInterruptsHang() {
        // 200ms timeout; our hanger sleeps for 30s, so we MUST time out.
        DecompileChain chain = new DecompileChain(List.of(hanger("Slow"), ok("Fast", "// fast")), 200);
        long started = System.currentTimeMillis();
        List<DecompileResult> trace = chain.run("Foo", new byte[0]);
        long elapsed = System.currentTimeMillis() - started;
        assertEquals(2, trace.size());
        assertTrue(trace.get(0).timedOut());
        assertTrue(trace.get(1).success());
        // Sanity: we stopped in well under 30 seconds.
        assertTrue(elapsed < 5_000, "Chain took too long: " + elapsed + "ms");
    }

    @Test
    void findEngineByName() {
        DecompileChain chain = new DecompileChain(List.of(ok("CFR", "x"), ok("Vineflower", "y")));
        assertEquals("Vineflower", chain.findEngine("Vineflower").name());
    }

    @Test
    void autoDecompilerDelegatesToChain() {
        AutoDecompiler auto = new AutoDecompiler(List.of(thrower("CFR"), ok("Vineflower", "// vf output")));
        String result = auto.decompile("Foo", new byte[0]);
        // Auto prefixes its output with a tiny "X failed, Y succeeded" note when >=2 engines tried.
        assertTrue(result.contains("CFR failed"));
        assertTrue(result.contains("Vineflower succeeded"));
        assertTrue(result.contains("// vf output"));
    }

    @Test
    void autoDecompilerFirstSuccessNoHeader() {
        AutoDecompiler auto = new AutoDecompiler(List.of(ok("CFR", "// first")));
        String result = auto.decompile("Foo", new byte[0]);
        // Only one engine ran; no preamble expected.
        assertFalse(result.startsWith("// Auto:"));
        assertEquals("// first", result);
    }

    @Test
    void autoDecompilerAllFailAggregatesErrors() {
        AutoDecompiler auto = new AutoDecompiler(List.of(thrower("A"), thrower("B")));
        String result = auto.decompile("Foo", new byte[0]);
        assertTrue(result.contains("every engine failed"));
        assertTrue(result.contains("A: boom"));
        assertTrue(result.contains("B: boom"));
    }
}
