package dev.share.bytecodelens.decompile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a sequence of {@link ClassDecompiler}s until one produces a "successful"
 * rendering, returning each attempt's result for observability.
 *
 * <p>The chain protects the caller from three failure modes users see elsewhere:</p>
 * <ul>
 *   <li><b>Throws from the engine</b> — wrapped into a failed DecompileResult, chain
 *       moves on to the next one. Recaf-style "Failed to patch class" will never leave
 *       the user with a blank tab.</li>
 *   <li><b>Infinite hang</b> — per-engine timeout (default 15s) cancels the Future.
 *       The Task worker thread doesn't leak because decompilers are short-lived
 *       compared to the timeout.</li>
 *   <li><b>Unknown opcodes / malformed bytecode</b> — the chain always terminates
 *       with {@link FallbackDecompiler}, which renders ASM-level metadata and never
 *       throws, so even SC2-obfuscated classes with dead {@code push/pop/throw}
 *       sequences get something readable.</li>
 * </ul>
 */
public final class DecompileChain {

    private static final Logger log = LoggerFactory.getLogger(DecompileChain.class);

    /** Default per-engine timeout. JADX-parity value; big obfuscated classes fit. */
    public static final long DEFAULT_TIMEOUT_MS = 15_000;

    private final List<ClassDecompiler> engines;
    private final long timeoutMs;

    public DecompileChain(List<ClassDecompiler> engines) {
        this(engines, DEFAULT_TIMEOUT_MS);
    }

    public DecompileChain(List<ClassDecompiler> engines, long timeoutMs) {
        this.engines = List.copyOf(engines);
        this.timeoutMs = timeoutMs;
    }

    /**
     * Decompile {@code classBytes} through the chain. Returns every attempt's result
     * in order, so callers can display diagnostics like "Vineflower succeeded after
     * CFR timed out". The final entry is always {@code Fallback} when all real
     * engines failed — Fallback is expected to be the last engine in the list.
     *
     * @param internalName JVM-style class name for logging
     * @param classBytes   raw class file bytes
     * @return list of results; last one is what the UI should render
     */
    public List<DecompileResult> run(String internalName, byte[] classBytes) {
        List<DecompileResult> trace = new ArrayList<>();
        for (ClassDecompiler engine : engines) {
            DecompileResult r = runOne(engine, internalName, classBytes);
            trace.add(r);
            if (r.success()) return trace;  // first success short-circuits the chain
        }
        return trace;
    }

    /**
     * Run a single engine with timeout protection. Exceptions, InterruptedException,
     * and TimeoutException all produce a DecompileResult rather than propagating.
     */
    private DecompileResult runOne(ClassDecompiler engine, String internalName, byte[] bytes) {
        long started = System.currentTimeMillis();
        // Each run gets its own executor — decompilers are seconds-long, thread pool
        // reuse doesn't help and makes cancel semantics muddy. SingleThreadExecutor
        // lets us cancel by interrupting the one worker.
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "decompile-" + engine.name());
            t.setDaemon(true);
            return t;
        });
        // Callable signature allows arbitrary Exception — the engine throws what it throws,
        // we let ExecutionException's getCause() pass the real thing back up.
        Callable<String> work = () -> engine.decompile(internalName, bytes);
        Future<String> future = exec.submit(work);
        try {
            String text = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - started;
            return DecompileResult.ok(text, engine.name(), elapsed);
        } catch (TimeoutException ex) {
            future.cancel(true);
            long elapsed = System.currentTimeMillis() - started;
            log.debug("{} timed out on {}", engine.name(), internalName);
            return DecompileResult.timedOut(engine.name(), elapsed);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            long elapsed = System.currentTimeMillis() - started;
            return DecompileResult.failed("// interrupted", engine.name(), "interrupted", elapsed);
        } catch (Exception ex) {
            long elapsed = System.currentTimeMillis() - started;
            // ExecutionException.getCause() is the real exception thrown by the engine.
            Throwable real = ex.getCause() != null ? ex.getCause() : ex;
            String msg = real.getMessage();
            if (msg == null) msg = real.getClass().getSimpleName();
            log.debug("{} failed on {}: {}", engine.name(), internalName, msg);
            return DecompileResult.failed(
                    "// " + engine.name() + " failed: " + msg + "\n",
                    engine.name(), msg, elapsed);
        } finally {
            exec.shutdownNow();
        }
    }

    /** Available engines — exposed for UI dropdowns that let users pick manually. */
    public List<ClassDecompiler> engines() { return engines; }

    /** Find engine by display name (case-sensitive). Returns null if absent. */
    public ClassDecompiler findEngine(String name) {
        for (ClassDecompiler d : engines) {
            if (d.name().equals(name)) return d;
        }
        return null;
    }
}
