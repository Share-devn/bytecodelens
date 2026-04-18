package dev.share.bytecodelens.usage;

/**
 * Pulls a single source line out of a previously-decompiled class for display in
 * the xref panel. Pure / trivially testable — input is text + line number, output
 * is a trimmed string (or null if the line is missing or out of range).
 *
 * <p>Designed for {@code String} bodies up to a few hundred KB; for huge classes
 * it walks newline-by-newline rather than splitting (avoids allocating the full
 * String[] array just to grab one line).</p>
 */
public final class XrefSnippetExtractor {

    /** Maximum snippet length returned — rest is replaced with an ellipsis. */
    public static final int MAX_LEN = 120;

    private XrefSnippetExtractor() {}

    /**
     * Extract line {@code lineNumber} (1-based, JVM convention) from {@code source}.
     * Returns {@code null} if {@code source} is null/empty or line is out of range.
     * Trailing whitespace is removed; long lines are truncated with an ellipsis.
     */
    public static String extract(String source, int lineNumber) {
        if (source == null || source.isEmpty() || lineNumber <= 0) return null;
        int line = 1;
        int start = 0;
        int len = source.length();
        for (int i = 0; i < len; i++) {
            char c = source.charAt(i);
            if (c == '\n') {
                if (line == lineNumber) {
                    return clean(source.substring(start, i));
                }
                line++;
                start = i + 1;
            }
        }
        // Last line (no trailing newline).
        if (line == lineNumber && start < len) {
            return clean(source.substring(start));
        }
        return null;
    }

    private static String clean(String s) {
        // Strip trailing whitespace + truncate.
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\t'
                || s.charAt(end - 1) == '\r')) end--;
        // Also strip leading indent so the snippet starts at first non-whitespace.
        int begin = 0;
        while (begin < end && (s.charAt(begin) == ' ' || s.charAt(begin) == '\t')) begin++;
        if (begin >= end) return null;
        String trimmed = s.substring(begin, end);
        if (trimmed.length() > MAX_LEN) {
            return trimmed.substring(0, MAX_LEN - 1) + "\u2026";
        }
        return trimmed;
    }
}
