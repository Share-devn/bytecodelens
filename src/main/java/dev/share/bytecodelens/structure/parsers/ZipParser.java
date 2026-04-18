package dev.share.bytecodelens.structure.parsers;

import dev.share.bytecodelens.structure.StructureNode;
import dev.share.bytecodelens.structure.StructureParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * ZIP/JAR structural overlay. We walk the LOC (local file header) chain at the head
 * and the CEN (central directory) at the tail, pointing out EOCD for sanity. This
 * doesn't replace unzipping — it shows the user which bytes are which.
 *
 * <p>We intentionally don't dive into every entry's compressed payload — listing
 * tens of thousands of entries in the UI would be useless. The Central Directory
 * entry count caps the display via {@link #MAX_ENTRIES_DISPLAYED}.</p>
 */
public final class ZipParser implements StructureParser {

    private static final int LOC_MAGIC = 0x04034B50;
    private static final int CEN_MAGIC = 0x02014B50;
    private static final int EOCD_MAGIC = 0x06054B50;
    private static final int MAX_ENTRIES_DISPLAYED = 256;

    @Override public String formatName() { return "ZIP / JAR"; }

    @Override
    public boolean matches(byte[] bytes) {
        return bytes != null && bytes.length >= 4
                && readLeInt(bytes, 0) == LOC_MAGIC;
    }

    @Override
    public StructureNode parse(byte[] bytes) throws UnsupportedFormatException {
        List<StructureNode> children = new ArrayList<>();

        // Local file headers — walk forward chain until we hit a non-LOC magic.
        List<StructureNode> localEntries = new ArrayList<>();
        int offset = 0;
        int entryIdx = 0;
        while (offset + 4 <= bytes.length && readLeInt(bytes, offset) == LOC_MAGIC) {
            int sig = readLeInt(bytes, offset);
            if (offset + 30 > bytes.length) break;
            int fnameLen = readLeShort(bytes, offset + 26);
            int extraLen = readLeShort(bytes, offset + 28);
            int compressed = readLeInt(bytes, offset + 18);
            String name = fnameLen > 0 && offset + 30 + fnameLen <= bytes.length
                    ? new String(bytes, offset + 30, fnameLen, StandardCharsets.UTF_8)
                    : "(unnamed)";
            int totalLen = 30 + fnameLen + extraLen + compressed;
            if (entryIdx < MAX_ENTRIES_DISPLAYED) {
                localEntries.add(StructureNode.leaf(
                        "LOC[" + entryIdx + "] " + abbreviate(name, 48),
                        totalLen + " bytes",
                        offset, totalLen, "table-entry"));
            }
            if (compressed < 0) break;
            offset += totalLen;
            entryIdx++;
            if (entryIdx > 2 * MAX_ENTRIES_DISPLAYED) break;  // sanity — don't loop forever on bad data
        }
        int locLen = offset;
        if (!localEntries.isEmpty()) {
            children.add(StructureNode.container(
                    "Local file headers",
                    entryIdx + " entries" + (entryIdx > localEntries.size()
                            ? " (first " + localEntries.size() + " shown)" : ""),
                    0, locLen, "header", localEntries));
        }

        // EOCD is at the very end (within the last 64KB after trailing comment).
        int eocdOffset = findEocd(bytes);
        if (eocdOffset >= 0) {
            int totalCenEntries = readLeShort(bytes, eocdOffset + 10);
            int cenSize = readLeInt(bytes, eocdOffset + 12);
            int cenOffset = readLeInt(bytes, eocdOffset + 16);
            int commentLen = readLeShort(bytes, eocdOffset + 20);

            // Central directory entries
            List<StructureNode> centralEntries = new ArrayList<>();
            int cursor = cenOffset;
            int shown = 0;
            while (cursor + 4 <= bytes.length
                    && readLeInt(bytes, cursor) == CEN_MAGIC
                    && shown < MAX_ENTRIES_DISPLAYED) {
                if (cursor + 46 > bytes.length) break;
                int fnameLen = readLeShort(bytes, cursor + 28);
                int extraLen = readLeShort(bytes, cursor + 30);
                int commentEntryLen = readLeShort(bytes, cursor + 32);
                String name = fnameLen > 0 && cursor + 46 + fnameLen <= bytes.length
                        ? new String(bytes, cursor + 46, fnameLen, StandardCharsets.UTF_8)
                        : "(unnamed)";
                int totalLen = 46 + fnameLen + extraLen + commentEntryLen;
                centralEntries.add(StructureNode.leaf(
                        "CEN[" + shown + "] " + abbreviate(name, 48),
                        totalLen + " bytes",
                        cursor, totalLen, "table-entry"));
                cursor += totalLen;
                shown++;
            }
            children.add(StructureNode.container(
                    "Central directory",
                    totalCenEntries + " entries" + (totalCenEntries > centralEntries.size()
                            ? " (first " + centralEntries.size() + " shown)" : ""),
                    cenOffset, cenSize, "body", centralEntries));

            children.add(StructureNode.leaf(
                    "End of Central Directory",
                    "comment " + commentLen + "B",
                    eocdOffset, 22 + commentLen, "footer"));
        }

        return StructureNode.container(formatName(),
                bytes.length + " bytes", 0, bytes.length, "root", children);
    }

    /** Locate the 22-byte EOCD magic. It can be followed by up to 65535 bytes of comment. */
    private static int findEocd(byte[] bytes) {
        int scanStart = Math.max(0, bytes.length - 65557);  // 22 + 65535
        for (int i = bytes.length - 22; i >= scanStart; i--) {
            if (readLeInt(bytes, i) == EOCD_MAGIC) return i;
        }
        return -1;
    }

    private static int readLeInt(byte[] b, int off) {
        if (off < 0 || off + 4 > b.length) return 0;
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    private static int readLeShort(byte[] b, int off) {
        if (off < 0 || off + 2 > b.length) return 0;
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
