package dev.share.bytecodelens.nativelibs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Smoke tests for the native lib parser. Instead of checking in real binaries we build
 * synthetic minimal ELF/PE/Mach-O headers just enough to exercise the detection paths.
 * Full symbol-table decoding is exercised at integration level (via the UI tab).
 */
class NativeLibParserTest {

    @Test
    void recognisesElfMagic() {
        byte[] hdr = new byte[64];
        hdr[0] = 0x7F; hdr[1] = 'E'; hdr[2] = 'L'; hdr[3] = 'F';
        hdr[4] = 2;   // 64-bit
        hdr[5] = 1;   // little endian
        hdr[7] = 3;   // Linux
        // e_machine at offset 18 — 0x3E = x86_64 (LE)
        hdr[18] = 0x3E; hdr[19] = 0x00;
        NativeLibInfo info = new NativeLibParser().parse(hdr);
        assertEquals(NativeLibInfo.Format.ELF, info.format());
        assertEquals(64, info.bitness());
        assertEquals("little", info.endianness());
        assertEquals("Linux", info.osAbi());
        assertEquals("x86_64", info.architecture());
    }

    @Test
    void recognisesElf32BigEndianArm() {
        byte[] hdr = new byte[64];
        hdr[0] = 0x7F; hdr[1] = 'E'; hdr[2] = 'L'; hdr[3] = 'F';
        hdr[4] = 1;   // 32-bit
        hdr[5] = 2;   // big endian
        // Big-endian read of e_machine at 18: 0x00 0x28 -> 0x0028 -> ARM
        hdr[18] = 0x00; hdr[19] = 0x28;
        NativeLibInfo info = new NativeLibParser().parse(hdr);
        assertEquals(NativeLibInfo.Format.ELF, info.format());
        assertEquals(32, info.bitness());
        assertEquals("big", info.endianness());
        assertEquals("ARM", info.architecture());
    }

    @Test
    void recognisesPeMagic() {
        // Minimal DOS + PE header:
        //   0..2   : "MZ"
        //   0x3C   : int pointer to PE header (we write 0x40 so PE signature at 0x40)
        //   0x40.. : "PE\0\0" + coff header (machine short at +4)
        byte[] hdr = new byte[512];
        hdr[0] = 'M'; hdr[1] = 'Z';
        hdr[0x3C] = 0x40; hdr[0x3D] = 0x00; hdr[0x3E] = 0x00; hdr[0x3F] = 0x00;
        hdr[0x40] = 'P'; hdr[0x41] = 'E'; hdr[0x42] = 0; hdr[0x43] = 0;
        // machine at 0x44 — LE 0x8664 -> x86_64
        hdr[0x44] = 0x64; hdr[0x45] = (byte) 0x86;
        // optional header size at 0x54 must fit ("20 bytes" lets the pe64 check read optMagic)
        hdr[0x54] = 20; hdr[0x55] = 0;
        // optMagic at optOff = 0x40 + 24 = 0x58 — 0x20B -> pe64
        hdr[0x58] = 0x0B; hdr[0x59] = 0x02;
        NativeLibInfo info = new NativeLibParser().parse(hdr);
        assertEquals(NativeLibInfo.Format.PE, info.format());
        assertEquals("x86_64", info.architecture());
        assertEquals("Windows", info.osAbi());
        assertEquals(64, info.bitness());
    }

    @Test
    void recognisesMachO64LittleEndian() {
        // Magic 0xFEEDFACF in LE is bytes CF FA ED FE
        byte[] hdr = new byte[64];
        hdr[0] = (byte) 0xCF; hdr[1] = (byte) 0xFA; hdr[2] = (byte) 0xED; hdr[3] = (byte) 0xFE;
        // cpu_type at +4: 0x01000007 -> x86_64
        hdr[4] = 0x07; hdr[5] = 0x00; hdr[6] = 0x00; hdr[7] = 0x01;
        // ncmds at +16 = 0 (we won't walk commands in this test)
        NativeLibInfo info = new NativeLibParser().parse(hdr);
        assertEquals(NativeLibInfo.Format.MACH_O, info.format());
        assertEquals(64, info.bitness());
        assertEquals("macOS", info.osAbi());
        assertEquals("x86_64", info.architecture());
    }

    @Test
    void recognisesMachOFatAsFat() {
        byte[] hdr = new byte[] {
                (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE,
                0, 0, 0, 2  // 2 fat arch entries (we don't parse them)
        };
        NativeLibInfo info = new NativeLibParser().parse(hdr);
        assertEquals(NativeLibInfo.Format.MACH_O, info.format());
        assertEquals("fat (multiple slices)", info.architecture());
    }

    @Test
    void unknownBytesReturnUnknownFormat() {
        NativeLibInfo info = new NativeLibParser().parse(new byte[]{0, 1, 2, 3, 4, 5});
        assertEquals(NativeLibInfo.Format.UNKNOWN, info.format());
        assertNotNull(info.diagnostics());
    }

    @Test
    void tooShortReturnsUnknown() {
        NativeLibInfo info = new NativeLibParser().parse(new byte[]{0x7F, 'E'});
        assertEquals(NativeLibInfo.Format.UNKNOWN, info.format());
    }

    @Test
    void nullReturnsUnknown() {
        NativeLibInfo info = new NativeLibParser().parse(null);
        assertEquals(NativeLibInfo.Format.UNKNOWN, info.format());
    }

    @Test
    void readCStringStopsAtNullByte() {
        byte[] data = new byte[] { 'h', 'e', 'l', 'l', 'o', 0, 'x', 'x' };
        assertEquals("hello", NativeLibParser.readCString(data, 0));
    }

    @Test
    void readCStringHandlesOutOfRange() {
        byte[] data = new byte[] { 'a' };
        assertEquals("", NativeLibParser.readCString(data, 10));
        assertEquals("", NativeLibParser.readCString(data, -1));
    }
}
