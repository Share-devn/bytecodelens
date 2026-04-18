package dev.share.bytecodelens.decompile;

import dev.share.bytecodelens.model.ClassEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pre-warms {@link DecompileCache} for classes the user is likely to open next
 * (the package surrounding the currently viewed one). Runs on a single low-priority
 * daemon thread so it never competes with foreground work.
 *
 * <p>Submits are deduped by cache key — re-warming the same class+engine+bytes is a
 * no-op via the cache itself, but we also skip enqueueing if the cache already
 * contains the entry to avoid redundant decompiles in the queue.</p>
 *
 * <p>Lifecycle: instantiate once per loaded jar, call {@link #shutdown()} when the
 * jar closes.</p>
 */
public final class BackgroundDecompiler {

    private static final Logger log = LoggerFactory.getLogger(BackgroundDecompiler.class);

    private final DecompileCache cache;
    private final ClassDecompiler engine;
    private final ExecutorService exec;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicInteger pending = new AtomicInteger();

    public BackgroundDecompiler(DecompileCache cache, ClassDecompiler engine) {
        this.cache = cache;
        this.engine = engine;
        // Single low-priority daemon worker. FIFO ordering — for warmup the order
        // matters less than throughput, and JDK FixedThreadPool gives us correct
        // wakeup semantics out of the box (manual deque manipulation deadlocks workers).
        this.exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "bg-decompile");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    /**
     * Enqueue every class in {@code candidates} for warmup. Already-cached entries
     * are skipped.
     */
    public void warm(List<ClassEntry> candidates) {
        if (shutdown.get()) return;
        for (ClassEntry c : candidates) {
            if (c == null || c.bytes() == null) continue;
            // Skip if already cached — saves a decompile.
            if (cache.get(c.internalName(), engine.name(), c.bytes()) != null) continue;
            pending.incrementAndGet();
            try {
                exec.execute(() -> {
                    try { warmOne(c); } finally { pending.decrementAndGet(); }
                });
            } catch (java.util.concurrent.RejectedExecutionException ex) {
                // Shutdown raced with warm() — give the counter back and stop.
                pending.decrementAndGet();
                return;
            }
        }
    }

    private void warmOne(ClassEntry c) {
        if (shutdown.get()) return;
        try {
            // Re-check inside the worker — another thread may have populated meanwhile.
            if (cache.get(c.internalName(), engine.name(), c.bytes()) != null) return;
            String text = engine.decompile(c.internalName(), c.bytes());
            cache.put(c.internalName(), engine.name(), c.bytes(), text);
        } catch (Throwable t) {
            // Background warmup failures are silent — the foreground decompile will surface them.
            log.debug("Background warmup failed for {}: {}", c.internalName(), t.getMessage());
        }
    }

    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) return;
        exec.shutdownNow();
    }

    /** For tests — outstanding (queued + running) tasks. */
    public int pending() {
        return pending.get();
    }
}
