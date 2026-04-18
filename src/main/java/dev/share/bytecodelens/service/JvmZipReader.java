package dev.share.bytecodelens.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.InflaterInputStream;

/**
 * Hand-rolled ZIP reader that scans Local File Headers directly and ignores the Central
 * Directory. This mirrors how real JVMs resolve class entries — ZIP tampering that breaks
 * the Central Directory will still work under this reader because it doesn't trust CD.
 *
 * <p>Supports STORED (method 0) and DEFLATE (method 8) — the only two compression methods
 * that show up in practice for {@code .jar}s.</p>
 *
 * <p>This is a <em>recovery</em> reader, not a general ZIP library. It trades strict
 * compliance for robustness against hostile inputs.</p>
 */
public final class JvmZipReader {

    private static final int LOCAL_FILE_HEADER_SIG = 0x04034b50; // "PK\x03\x04"
    private static final int CENTRAL_DIR_SIG = 0x02014b50;       // "PK\x01\x02"
    private static final int END_OF_CD_SIG = 0x06054b50;          // "PK\x05\x06"

    public record ZipLocalEntry(String name, long uncompressedSize, int method, byte[] data) {}

    private JvmZipReader() {}

    public static List<ZipLocalEntry> readAll(byte[] zipBytes) throws IOException {
        List<ZipLocalEntry> out = new ArrayList<>();
        ByteBuffer bb = ByteBuffer.wrap(zipBytes).order(ByteOrder.LITTLE_ENDIAN);
        int offset = 0;
        while (offset < zipBytes.length - 4) {
            bb.position(offset);
            int sig = bb.getInt(offset);
            if (sig == CENTRAL_DIR_SIG || sig == END_OF_CD_SIG) break;
            if (sig != LOCAL_FILE_HEADER_SIG) {
                // Not a local file header — scan forward for one.
                offset = findNextSignature(zipBytes, offset + 1, LOCAL_FILE_HEADER_SIG);
                if (offset < 0) break;
                continue;
            }
            try {
                ZipLocalEntry entry = readLocalHeader(zipBytes, bb, offset);
                if (entry != null) out.add(entry);
                // Advance past this entry's compressed data.
                offset = nextOffsetAfter(zipBytes, bb, offset);
            } catch (Exception ex) {
                // Corrupt entry — skip past the signature and keep scanning.
                offset = findNextSignature(zipBytes, offset + 1, LOCAL_FILE_HEADER_SIG);
                if (offset < 0) break;
            }
        }
        return out;
    }

    private static ZipLocalEntry readLocalHeader(byte[] zip, ByteBuffer bb, int off) throws IOException {
        bb.position(off + 4);
        bb.getShort();                          // version
        int gpFlag = bb.getShort() & 0xFFFF;
        int method = bb.getShort() & 0xFFFF;
        bb.getShort();                          // last mod time
        bb.getShort();                          // last mod date
        bb.getInt();                            // CRC-32
        long compressedSize = bb.getInt() & 0xFFFFFFFFL;
        long uncompressedSize = bb.getInt() & 0xFFFFFFFFL;
        int nameLen = bb.getShort() & 0xFFFF;
        int extraLen = bb.getShort() & 0xFFFF;

        int nameOff = off + 30;
        if (nameOff + nameLen > zip.length) return null;
        String name = new String(zip, nameOff, nameLen,
                (gpFlag & 0x800) != 0 ? java.nio.charset.StandardCharsets.UTF_8
                                      : java.nio.charset.StandardCharsets.ISO_8859_1);
        int dataOff = nameOff + nameLen + extraLen;

        // GP bit 3 (0x08) = data descriptor follows — compressed size isn't known from LFH.
        // In that case we stream-decompress to EOF for DEFLATE, or bail for other methods.
        boolean hasDataDescriptor = (gpFlag & 0x08) != 0;
        byte[] decompressed;
        if (method == 8 && (hasDataDescriptor || compressedSize == 0)) {
            // Decompress from dataOff until Inflater reports end-of-stream; this naturally
            // stops at the right boundary for DEFLATE streams.
            try (java.util.zip.InflaterInputStream iis = new java.util.zip.InflaterInputStream(
                    new ByteArrayInputStream(zip, dataOff, zip.length - dataOff),
                    new java.util.zip.Inflater(true))) {
                decompressed = iis.readAllBytes();
            }
        } else {
            if (dataOff + compressedSize > zip.length) {
                compressedSize = Math.max(0, findNextSignature(zip, dataOff,
                        LOCAL_FILE_HEADER_SIG) - dataOff);
                if (compressedSize < 0) compressedSize = 0;
            }
            byte[] compressed = new byte[(int) compressedSize];
            if (compressedSize > 0) System.arraycopy(zip, dataOff, compressed, 0, (int) compressedSize);
            decompressed = decompress(method, compressed);
        }
        return new ZipLocalEntry(name, uncompressedSize, method, decompressed);
    }

    /** Return byte offset just past the end of the entry at {@code off}. */
    private static int nextOffsetAfter(byte[] zip, ByteBuffer bb, int off) {
        bb.position(off + 4);
        bb.getShort();                          // version
        int gpFlag = bb.getShort() & 0xFFFF;
        bb.getShort();                          // method
        bb.getShort();
        bb.getShort();
        bb.getInt();
        long compressedSize = bb.getInt() & 0xFFFFFFFFL;
        bb.getInt();                            // uncompressed
        int nameLen = bb.getShort() & 0xFFFF;
        int extraLen = bb.getShort() & 0xFFFF;

        boolean hasDataDescriptor = (gpFlag & 0x08) != 0;
        if (hasDataDescriptor || compressedSize == 0) {
            // Walk forward to the next Local File Header (or CD start) — safer than trusting
            // compressed size which was zero in the LFH.
            int next = findNextSignature(zip, off + 30 + nameLen + extraLen + 1,
                    LOCAL_FILE_HEADER_SIG);
            if (next < 0) {
                int cd = findNextSignature(zip, off + 30 + nameLen + extraLen + 1, CENTRAL_DIR_SIG);
                next = cd < 0 ? zip.length : cd;
            }
            return next;
        }
        long next = (long) off + 30 + nameLen + extraLen + compressedSize;
        return next > zip.length ? zip.length : (int) next;
    }

    private static byte[] decompress(int method, byte[] compressed) throws IOException {
        if (method == 0) return compressed; // STORED
        if (method == 8) {                 // DEFLATE
            try (InputStream in = new InflaterInputStream(
                    new ByteArrayInputStream(compressed),
                    new java.util.zip.Inflater(true))) {
                return in.readAllBytes();
            }
        }
        throw new IOException("Unsupported compression method: " + method);
    }

    /** Find next occurrence of a 4-byte little-endian signature starting at {@code from}. */
    private static int findNextSignature(byte[] zip, int from, int sig) {
        byte b0 = (byte) (sig & 0xFF);
        byte b1 = (byte) ((sig >> 8) & 0xFF);
        byte b2 = (byte) ((sig >> 16) & 0xFF);
        byte b3 = (byte) ((sig >> 24) & 0xFF);
        for (int i = from; i < zip.length - 3; i++) {
            if (zip[i] == b0 && zip[i + 1] == b1 && zip[i + 2] == b2 && zip[i + 3] == b3) return i;
        }
        return -1;
    }
}
