package dev.share.bytecodelens.decompile;

/**
 * Outcome of a single {@link ClassDecompiler} attempt. Carries the text plus
 * success/reason metadata so higher layers (chain, UI) can decide whether to retry
 * with the next engine, annotate the class as partial, or report a timeout.
 *
 * <p>The text is always non-null — failures return the decompiler's best-effort
 * rendering (typically a {@code // decompile failed: ...} comment) so the user
 * never sees an empty tab. "Success" distinguishes silent failures that still
 * produced text from crashes that returned a stub.</p>
 *
 * @param text             rendered Java source (or error comment) — never null
 * @param success          whether the decompiler ran without throwing
 * @param reason           short diagnostic when {@code success} is false, else null
 * @param decompilerName   which engine produced this result
 * @param timedOut         whether the decompiler was killed by the chain's timeout
 * @param elapsedMs        wall-clock time spent in this engine
 */
public record DecompileResult(
        String text,
        boolean success,
        String reason,
        String decompilerName,
        boolean timedOut,
        long elapsedMs) {

    public static DecompileResult ok(String text, String name, long elapsedMs) {
        return new DecompileResult(text == null ? "" : text, true, null, name, false, elapsedMs);
    }

    public static DecompileResult failed(String text, String name, String reason, long elapsedMs) {
        return new DecompileResult(text == null ? "" : text, false, reason, name, false, elapsedMs);
    }

    public static DecompileResult timedOut(String name, long elapsedMs) {
        return new DecompileResult(
                "// " + name + " timed out after " + elapsedMs + "ms — retrying next engine\n",
                false, "timed out after " + elapsedMs + "ms", name, true, elapsedMs);
    }
}
