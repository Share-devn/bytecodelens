package dev.share.bytecodelens.ui.views;

/**
 * Shannon entropy of a byte array, optionally sliced into windows so the UI can draw
 * a "where are the compressed / encrypted regions" graph.
 *
 * <p>Entropy of a byte stream is {@code -Σ p(x) log2 p(x)} over byte values 0..255,
 * maxing out at 8.0 for uniform random data. Heuristics that fall out:</p>
 * <ul>
 *   <li>&lt;4.0 — mostly repetitive / textual / structured</li>
 *   <li>~6–7 — normal binary with some headers</li>
 *   <li>&gt;7.5 — compressed or encrypted block</li>
 * </ul>
 */
public final class HexEntropy {

    /** Shannon entropy of the whole array. Returns 0 on empty/null. */
    public static double entropy(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return 0.0;
        return entropy(bytes, 0, bytes.length);
    }

    /** Entropy of a slice — [offset, offset+length). */
    public static double entropy(byte[] bytes, int offset, int length) {
        if (bytes == null || length <= 0) return 0.0;
        int[] hist = new int[256];
        int end = Math.min(bytes.length, offset + length);
        int n = 0;
        for (int i = offset; i < end; i++) {
            hist[bytes[i] & 0xFF]++;
            n++;
        }
        if (n == 0) return 0.0;
        double inv = 1.0 / n;
        double sum = 0.0;
        for (int count : hist) {
            if (count == 0) continue;
            double p = count * inv;
            sum -= p * (Math.log(p) / Math.log(2));
        }
        return sum;
    }

    /**
     * Compute entropy for each window of {@code windowSize} bytes. The last window
     * may be short; entropy is still well-defined there. Returns an array of doubles
     * in [0, 8]; length = ceil(bytes.length / windowSize).
     */
    public static double[] windowed(byte[] bytes, int windowSize) {
        if (bytes == null || bytes.length == 0 || windowSize <= 0) return new double[0];
        int count = (bytes.length + windowSize - 1) / windowSize;
        double[] out = new double[count];
        for (int i = 0; i < count; i++) {
            int from = i * windowSize;
            int len = Math.min(windowSize, bytes.length - from);
            out[i] = entropy(bytes, from, len);
        }
        return out;
    }

    /**
     * Pick a sensible window size targeting ~200 samples. Minimum 64 bytes so the
     * entropy estimate has some statistical weight; maximum 64KB so huge files don't
     * flatten into a single dot.
     */
    public static int suggestedWindowSize(int totalBytes) {
        if (totalBytes <= 0) return 64;
        int ideal = Math.max(64, totalBytes / 200);
        if (ideal > 65_536) ideal = 65_536;
        return ideal;
    }

    private HexEntropy() {}
}
