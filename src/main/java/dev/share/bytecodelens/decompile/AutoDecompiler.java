package dev.share.bytecodelens.decompile;

import java.util.List;

/**
 * Pseudo-decompiler that delegates to a {@link DecompileChain}, trying each engine
 * until one succeeds. Appears in the engine dropdown as "Auto" so users who don't
 * care which engine rendered a given class can just have "the best available"
 * automatically — matches JADX's "it just works" feel.
 *
 * <p>On full failure (every engine threw or timed out) returns a concatenated
 * diagnostic of each attempt so the user knows what was tried.</p>
 */
public final class AutoDecompiler implements ClassDecompiler {

    private final DecompileChain chain;

    public AutoDecompiler(List<ClassDecompiler> engines) {
        this.chain = new DecompileChain(engines);
    }

    @Override
    public String name() { return "Auto"; }

    @Override
    public String decompile(String internalName, byte[] classBytes) {
        List<DecompileResult> trace = chain.run(internalName, classBytes);
        DecompileResult last = trace.get(trace.size() - 1);
        if (last.success()) {
            // Prefix the successful output with a tiny header telling the user which
            // engine won, unless it's the first engine (no interesting info there).
            if (trace.size() > 1) {
                StringBuilder header = new StringBuilder("// Auto: ");
                for (int i = 0; i < trace.size() - 1; i++) {
                    DecompileResult r = trace.get(i);
                    header.append(r.decompilerName())
                            .append(r.timedOut() ? " timed out, " : " failed, ");
                }
                header.append(last.decompilerName()).append(" succeeded (")
                        .append(last.elapsedMs()).append("ms)\n\n");
                return header + last.text();
            }
            return last.text();
        }
        // No engine succeeded — concat all error comments so the user sees everything tried.
        StringBuilder out = new StringBuilder("// Auto: every engine failed on this class.\n");
        for (DecompileResult r : trace) {
            out.append("//   ").append(r.decompilerName()).append(": ")
                    .append(r.reason() == null ? "unknown error" : r.reason())
                    .append(" (").append(r.elapsedMs()).append("ms)\n");
        }
        out.append('\n').append(last.text());
        return out.toString();
    }
}
