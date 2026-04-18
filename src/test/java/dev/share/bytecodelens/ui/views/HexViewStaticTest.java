package dev.share.bytecodelens.ui.views;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the HexView's pure-string helpers. They're package-private static so
 * the tests can hit them without spinning up JavaFX. Covers:
 * <ul>
 *     <li>Needle parsing across Auto/Hex/ASCII/UTF-16 modes</li>
 *     <li>Offset parsing with hex / decimal / negative-from-end</li>
 *     <li>Copy formatters (hex / C-array)</li>
 * </ul>
 */
class HexViewStaticTest {

    // ---- parseNeedle -------------------------------------------------------

    @Test
    void parseNeedleAutoRecognisesHex() {
        byte[] r = HexView.parseNeedle("DE AD BE EF", "Auto");
        assertArrayEquals(new byte[]{(byte)0xDE, (byte)0xAD, (byte)0xBE, (byte)0xEF}, r);
    }

    @Test
    void parseNeedleAutoRecognisesCompactHex() {
        byte[] r = HexView.parseNeedle("cafebabe", "Auto");
        assertArrayEquals(new byte[]{(byte)0xCA, (byte)0xFE, (byte)0xBA, (byte)0xBE}, r);
    }

    @Test
    void parseNeedleAutoFallsBackToAsciiOnNonHex() {
        byte[] r = HexView.parseNeedle("hello", "Auto");
        assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII), r);
    }

    @Test
    void parseNeedleAutoPrefersAsciiWhenLengthOdd() {
        // "abc" looks hex-ish but is odd-length → ASCII per design.
        byte[] r = HexView.parseNeedle("abc", "Auto");
        assertArrayEquals(new byte[]{'a', 'b', 'c'}, r);
    }

    @Test
    void parseNeedleStrictAsciiSkipsHexRecognition() {
        byte[] r = HexView.parseNeedle("DE AD", "ASCII");
        assertArrayEquals("DE AD".getBytes(StandardCharsets.US_ASCII), r);
    }

    @Test
    void parseNeedleHexStripsPrefixAndWhitespace() {
        byte[] r = HexView.parseNeedle("0xDE,AD 0xBEEF", "Hex");
        assertArrayEquals(new byte[]{(byte)0xDE, (byte)0xAD, (byte)0xBE, (byte)0xEF}, r);
    }

    @Test
    void parseNeedleHexRejectsOddOrInvalid() {
        assertNull(HexView.parseNeedle("DE A", "Hex"));
        assertNull(HexView.parseNeedle("ZZ", "Hex"));
    }

    @Test
    void parseNeedleUtf16ProducesPairsOfBytes() {
        byte[] r = HexView.parseNeedle("AB", "UTF-16");
        // UTF-16LE: 'A' = 41 00, 'B' = 42 00
        assertArrayEquals(new byte[]{0x41, 0x00, 0x42, 0x00}, r);
    }

    @Test
    void parseNeedleEmptyReturnsNull() {
        assertNull(HexView.parseNeedle("", "Auto"));
        assertNull(HexView.parseNeedle(null, "Auto"));
    }

    // ---- parseOffset -------------------------------------------------------

    @Test
    void parseOffsetAcceptsHexPrefix() {
        assertEquals(0xDEAD, HexView.parseOffset("0xDEAD", 0x10000));
        assertEquals(16, HexView.parseOffset("0x10", 100));
    }

    @Test
    void parseOffsetAcceptsDecimal() {
        assertEquals(1024, HexView.parseOffset("1024", 2048));
    }

    @Test
    void parseOffsetNegativeCountsFromEnd() {
        // -16 on a 1024-byte file points at offset 1024 - 16 = 1008.
        assertEquals(1008, HexView.parseOffset("-16", 1024));
    }

    @Test
    void parseOffsetRejectsOutOfRange() {
        assertNull(HexView.parseOffset("999999", 100));
        assertNull(HexView.parseOffset("-999999", 100));
    }

    @Test
    void parseOffsetRejectsGarbage() {
        assertNull(HexView.parseOffset("nope", 100));
        assertNull(HexView.parseOffset("", 100));
        assertNull(HexView.parseOffset(null, 100));
    }

    // ---- formatters --------------------------------------------------------

    @Test
    void formatHexUppercaseSpaceSeparated() {
        byte[] slice = {0, 1, (byte)0xFF, 'A'};
        assertEquals("00 01 FF 41", HexView.formatHex(slice));
    }

    @Test
    void formatHexEmpty() {
        assertEquals("", HexView.formatHex(new byte[0]));
    }

    @Test
    void formatCArrayUsesHexLiterals() {
        byte[] slice = {(byte)0xDE, (byte)0xAD};
        String c = HexView.formatC(slice);
        assertTrue(c.contains("0xDE"));
        assertTrue(c.contains("0xAD"));
        assertTrue(c.startsWith("{"));
        assertTrue(c.trim().endsWith("}"));
    }

    @Test
    void formatCArrayWrapsEvery16Bytes() {
        byte[] slice = new byte[32];
        for (int i = 0; i < 32; i++) slice[i] = (byte) i;
        String c = HexView.formatC(slice);
        // Two chunks of 16 separated by a newline within the braces.
        long newlines = c.chars().filter(ch -> ch == '\n').count();
        assertTrue(newlines >= 2, "Expected wrap newlines, got: " + c);
    }

    // ---- tryParseHex (via parseNeedle Hex-mode) ----------------------------

    @Test
    void hexParseRoundTrip() {
        byte[] original = {0x01, 0x23, 0x45, 0x67, (byte)0x89, (byte)0xAB, (byte)0xCD, (byte)0xEF};
        String text = HexView.formatHex(original);
        byte[] parsed = HexView.parseNeedle(text, "Hex");
        assertNotNull(parsed);
        assertArrayEquals(original, parsed);
    }
}
