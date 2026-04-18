package dev.share.bytecodelens.ui.views;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the pure engines behind the Hex Tools panel — checksums, entropy,
 * binary diff. UI assembly isn't tested here (requires JavaFX runtime); the
 * algorithms are what can regress silently.
 */
class HexToolsTest {

    // ---- Checksums ---------------------------------------------------------

    @Test
    void checksumsEmpty() {
        Map<String, String> out = HexChecksums.compute(new byte[0]);
        // Known empty-input digests.
        assertEquals("00000000", out.get("CRC32"));
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", out.get("MD5"));
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", out.get("SHA-1"));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                out.get("SHA-256"));
    }

    @Test
    void checksumsKnownVector() {
        // "abc" has well-known digests.
        Map<String, String> out = HexChecksums.compute("abc".getBytes());
        assertEquals("900150983cd24fb0d6963f7d28e17f72", out.get("MD5"));
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", out.get("SHA-1"));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                out.get("SHA-256"));
    }

    @Test
    void checksumsMapOrderStable() {
        Map<String, String> out = HexChecksums.compute(new byte[]{1, 2, 3});
        // LinkedHashMap preserves insertion order.
        List<String> keys = List.copyOf(out.keySet());
        assertEquals(List.of("CRC32", "MD5", "SHA-1", "SHA-256", "SHA-512"), keys);
    }

    @Test
    void checksumsNullSafe() {
        // Null treated as empty — shouldn't throw.
        Map<String, String> out = HexChecksums.compute(null);
        assertEquals("00000000", out.get("CRC32"));
    }

    // ---- Entropy -----------------------------------------------------------

    @Test
    void entropyOfZeros() {
        // All same byte → entropy 0.
        assertEquals(0.0, HexEntropy.entropy(new byte[100]), 1e-9);
    }

    @Test
    void entropyOfUniformRandomCloseToEight() {
        // Deterministic pseudo-random — equal distribution across all 256 byte values
        // produces entropy very close to 8.
        byte[] buf = new byte[256 * 256];
        for (int i = 0; i < buf.length; i++) buf[i] = (byte) (i % 256);
        double h = HexEntropy.entropy(buf);
        assertTrue(h > 7.95, "Expected ≈ 8, got " + h);
    }

    @Test
    void entropyRisesWithDiversity() {
        // Two-byte alphabet → max entropy 1. Four-byte alphabet → max 2. Check ordering.
        byte[] two = new byte[100];
        byte[] four = new byte[100];
        for (int i = 0; i < 100; i++) {
            two[i] = (byte) (i % 2);
            four[i] = (byte) (i % 4);
        }
        assertTrue(HexEntropy.entropy(four) > HexEntropy.entropy(two));
    }

    @Test
    void windowedReturnsCeilCountBuckets() {
        byte[] buf = new byte[200];
        double[] out = HexEntropy.windowed(buf, 64);
        assertEquals(4, out.length);  // 200 / 64 → 4 windows (64+64+64+8)
    }

    @Test
    void suggestedWindowSizeScales() {
        assertTrue(HexEntropy.suggestedWindowSize(100) >= 64);
        // 2,000,000 byte file → 2_000_000 / 200 = 10_000; below the 65_536 cap.
        assertEquals(10_000, HexEntropy.suggestedWindowSize(2_000_000));
        assertEquals(65_536, HexEntropy.suggestedWindowSize(50_000_000));
    }

    // ---- Diff --------------------------------------------------------------

    @Test
    void diffIdentical() {
        byte[] a = {1, 2, 3, 4, 5};
        List<HexDiff.Region> regions = HexDiff.diff(a, a);
        assertEquals(1, regions.size());
        assertEquals(HexDiff.Side.SAME, regions.get(0).side());
        assertEquals(5, regions.get(0).length());
    }

    @Test
    void diffFindsChangedRegion() {
        byte[] a = {1, 2, 3, 4, 5};
        byte[] b = {1, 2, 9, 4, 5};
        List<HexDiff.Region> regions = HexDiff.diff(a, b);
        assertEquals(3, regions.size());
        assertEquals(HexDiff.Side.SAME, regions.get(0).side());
        assertEquals(2, regions.get(0).length());
        assertEquals(HexDiff.Side.CHANGED, regions.get(1).side());
        assertEquals(1, regions.get(1).length());
        assertEquals(HexDiff.Side.SAME, regions.get(2).side());
    }

    @Test
    void diffDifferentLengthsTagsTail() {
        byte[] a = {1, 2, 3, 4};
        byte[] b = {1, 2};
        List<HexDiff.Region> regions = HexDiff.diff(a, b);
        // Same prefix 2 bytes, then 2 bytes only in left.
        assertEquals(HexDiff.Side.SAME, regions.get(0).side());
        assertEquals(HexDiff.Side.ONLY_LEFT, regions.get(regions.size() - 1).side());
        assertEquals(2, regions.get(regions.size() - 1).length());
    }

    @Test
    void diffRleMergesAdjacentSameStatus() {
        byte[] a = {1, 2, 3, 4};
        byte[] b = {9, 9, 9, 9};  // every byte differs
        List<HexDiff.Region> regions = HexDiff.diff(a, b);
        assertEquals(1, regions.size());
        assertEquals(HexDiff.Side.CHANGED, regions.get(0).side());
        assertEquals(4, regions.get(0).length());
    }

    @Test
    void diffCounter() {
        byte[] a = {1, 2, 3, 4};
        byte[] b = {1, 9, 3, 9};
        List<HexDiff.Region> regions = HexDiff.diff(a, b);
        assertEquals(2, HexDiff.bytesWith(regions, HexDiff.Side.SAME));
        assertEquals(2, HexDiff.bytesWith(regions, HexDiff.Side.CHANGED));
    }

    @Test
    void diffNullsSafe() {
        List<HexDiff.Region> regions = HexDiff.diff(null, new byte[]{1, 2, 3});
        assertEquals(HexDiff.Side.ONLY_RIGHT, regions.get(regions.size() - 1).side());
    }
}
