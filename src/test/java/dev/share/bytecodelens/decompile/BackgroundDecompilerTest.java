package dev.share.bytecodelens.decompile;

import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.ModuleInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BackgroundDecompilerTest {

    private static ClassEntry entry(String internal, byte[] bytes) {
        return new ClassEntry(internal, internal.replace('/', '.'), "", internal, "java/lang/Object",
                List.of(), 0, 52, 0, 0, 0, 0, null, bytes, (ModuleInfo) null, 0);
    }

    private static ClassDecompiler counting(AtomicInteger calls, String text) {
        return new ClassDecompiler() {
            @Override public String name() { return "Test"; }
            @Override public String decompile(String internalName, byte[] classBytes) {
                calls.incrementAndGet();
                return text + ":" + internalName;
            }
        };
    }

    @Test
    void warmsCacheForRequestedClasses() throws Exception {
        DecompileCache cache = new DecompileCache(16);
        AtomicInteger calls = new AtomicInteger();
        BackgroundDecompiler bg = new BackgroundDecompiler(cache, counting(calls, "ok"));

        ClassEntry a = entry("p/A", new byte[]{1});
        ClassEntry b = entry("p/B", new byte[]{2});
        bg.warm(List.of(a, b));

        // Wait for the LIFO worker to drain — generous deadline.
        long deadline = System.currentTimeMillis() + 5_000;
        while (cache.size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        bg.shutdown();

        assertEquals("ok:p/A", cache.get("p/A", "Test", new byte[]{1}));
        assertEquals("ok:p/B", cache.get("p/B", "Test", new byte[]{2}));
    }

    @Test
    void doesNotReDecompileAlreadyCached() throws Exception {
        DecompileCache cache = new DecompileCache(16);
        AtomicInteger calls = new AtomicInteger();
        BackgroundDecompiler bg = new BackgroundDecompiler(cache, counting(calls, "ok"));

        ClassEntry a = entry("p/A", new byte[]{1});
        cache.put("p/A", "Test", new byte[]{1}, "// pre");
        bg.warm(List.of(a));
        Thread.sleep(100);
        bg.shutdown();

        assertEquals(0, calls.get());
        assertEquals("// pre", cache.get("p/A", "Test", new byte[]{1}));
    }

    @Test
    void shutdownStopsAcceptingWork() throws Exception {
        DecompileCache cache = new DecompileCache(16);
        AtomicInteger calls = new AtomicInteger();
        BackgroundDecompiler bg = new BackgroundDecompiler(cache, counting(calls, "ok"));
        bg.shutdown();
        bg.warm(List.of(entry("p/A", new byte[]{1})));
        Thread.sleep(50);
        assertEquals(0, calls.get());
    }
}
