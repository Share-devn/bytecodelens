package dev.share.bytecodelens.ui.views;

import org.junit.jupiter.api.Test;

import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for every individual type decoder in {@link DataInterpreter}. Keep them
 * strict — a bug here renders wrong numbers in a forensic tool, which is worse than
 * useless. Values were double-checked against Python ({@code struct} module).
 */
class DataInterpreterTest {

    @Test
    void int8SignedReadsNegative() {
        byte[] b = {(byte) 0xFF};
        assertTrue(DataInterpreter.asInt8(b, 0, true).startsWith("-1"));
    }

    @Test
    void uint8ReadsFullRange() {
        byte[] b = {(byte) 0xFF};
        assertTrue(DataInterpreter.asInt8(b, 0, false).startsWith("255"));
    }

    @Test
    void int16LittleEndianSignedPositive() {
        byte[] b = {0x78, 0x56};  // 0x5678 LE
        assertTrue(DataInterpreter.asInt(b, 0, 2, true, ByteOrder.LITTLE_ENDIAN).startsWith("22136"));
    }

    @Test
    void int16BigEndianNegative() {
        byte[] b = {(byte) 0xFF, (byte) 0xFF};
        assertTrue(DataInterpreter.asInt(b, 0, 2, true, ByteOrder.BIG_ENDIAN).startsWith("-1"));
    }

    @Test
    void int32LittleEndianClassFileMagic() {
        byte[] b = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};
        // As BE uint32 this is 0xCAFEBABE = 3405691582.
        assertTrue(DataInterpreter.asInt(b, 0, 4, false, ByteOrder.BIG_ENDIAN).startsWith("3405691582"));
    }

    @Test
    void int64BigEndianUnsignedBeyondSignedMax() {
        byte[] b = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                    (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        // uint64 max = 18446744073709551615.
        String out = DataInterpreter.asInt(b, 0, 8, false, ByteOrder.BIG_ENDIAN);
        assertTrue(out.startsWith("18446744073709551615"), "Got: " + out);
    }

    @Test
    void float32LittleEndianOne() {
        // 1.0f in LE is 00 00 80 3F. After trailing-zero trim we expect bare "1".
        byte[] b = {0x00, 0x00, (byte) 0x80, 0x3F};
        assertEquals("1", DataInterpreter.asFloat32(b, 0, ByteOrder.LITTLE_ENDIAN));
    }

    @Test
    void float64BigEndianPi() {
        // π double BE: 40 09 21 FB 54 44 2D 18. With %.7g we get 7 sig digits,
        // which rounds to 3.141593 — close enough to recognise as π.
        byte[] b = {0x40, 0x09, 0x21, (byte) 0xFB, 0x54, 0x44, 0x2D, 0x18};
        String out = DataInterpreter.asFloat64(b, 0, ByteOrder.BIG_ENDIAN);
        assertTrue(out.startsWith("3.14159"), "Got: " + out);
    }

    @Test
    void float32NaNDisplaysAsNaN() {
        byte[] b = {0x7F, (byte) 0xC0, 0x00, 0x00};  // BE NaN
        assertEquals("NaN", DataInterpreter.asFloat32(b, 0, ByteOrder.BIG_ENDIAN));
    }

    @Test
    void asciiPrintableReturnsQuotedChar() {
        assertEquals("'A'", DataInterpreter.asAscii((byte) 'A'));
    }

    @Test
    void asciiNonPrintableReturnsHex() {
        String out = DataInterpreter.asAscii((byte) 0x01);
        assertTrue(out.contains("0x01"));
    }

    @Test
    void utf16LittleEndianReadsCyrillic() {
        // 'А' (U+0410) in LE = 10 04
        byte[] b = {0x10, 0x04};
        assertTrue(DataInterpreter.asUtf16(b, 0, ByteOrder.LITTLE_ENDIAN).contains("U+0410"));
    }

    @Test
    void utf8SingleByteAscii() {
        byte[] b = {'a'};
        assertTrue(DataInterpreter.asUtf8(b, 0, 1).contains("'a'"));
    }

    @Test
    void utf8MultiByteCyrillic() {
        // 'А' (U+0410) in UTF-8 = 0xD0 0x90
        byte[] b = {(byte) 0xD0, (byte) 0x90};
        String out = DataInterpreter.asUtf8(b, 0, 2);
        assertTrue(out.contains("U+0410"), "Got: " + out);
        assertTrue(out.contains("2 bytes"));
    }

    @Test
    void binaryEightBitsWithSpace() {
        assertEquals("1010 1010", DataInterpreter.asBinary((byte) 0xAA));
        assertEquals("0000 0000", DataInterpreter.asBinary((byte) 0));
        assertEquals("1111 1111", DataInterpreter.asBinary((byte) 0xFF));
    }

    @Test
    void unixTimeRendersKnownDate() {
        // 2024-01-01 00:00:00 UTC = 1704067200
        assertTrue(DataInterpreter.asUnixTime(1_704_067_200L).startsWith("2024-01-01"));
    }

    @Test
    void unixTimeOutOfRangeReturnsDash() {
        assertEquals("—", DataInterpreter.asUnixTime(-1));
        assertEquals("—", DataInterpreter.asUnixTime(999_999_999_999L));
    }

    @Test
    void filetimeRendersKnownDate() {
        // FILETIME of 1970-01-01 00:00:00 = 11644473600 * 10_000_000 = 116444736000000000
        long ft = 11_644_473_600L * 10_000_000L;
        assertTrue(DataInterpreter.asFileTime(ft).startsWith("1970-01-01"));
    }

    @Test
    void interpretReturnsAllFieldsForEightBytes() {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) b[i] = (byte) (i + 1);
        List<DataInterpreter.Field> fields = DataInterpreter.interpret(b, 0);
        // Expect every type present: int8 uint8 int16 uint16 int32 uint32 int64 uint64
        // float32 float64 char UTF-16 UTF-8 binary Unix(32) Unix-ms(64) FILETIME = 17.
        assertEquals(17, fields.size());
    }

    @Test
    void interpretShrinksForShortBuffers() {
        byte[] b = {1, 2};
        List<DataInterpreter.Field> fields = DataInterpreter.interpret(b, 0);
        // Only int8/uint8/int16/uint16/char/UTF-16/UTF-8/binary → 8 entries, no int32+.
        assertFalse(fields.stream().anyMatch(f -> f.label().equals("int32")));
        assertFalse(fields.stream().anyMatch(f -> f.label().equals("float32")));
        assertTrue(fields.stream().anyMatch(f -> f.label().equals("int8")));
    }

    @Test
    void interpretReturnsEmptyForOutOfBoundsOffset() {
        byte[] b = {0};
        assertTrue(DataInterpreter.interpret(b, 5).isEmpty());
        assertTrue(DataInterpreter.interpret(b, -1).isEmpty());
        assertTrue(DataInterpreter.interpret(null, 0).isEmpty());
    }
}
