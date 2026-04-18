package dev.share.bytecodelens.structure.parsers;

import dev.share.bytecodelens.structure.StructureNode;
import dev.share.bytecodelens.structure.StructureParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * PNG chunk walker. PNG layout is just an 8-byte signature followed by a chain of
 * {length:u4, type:4-chars, data:length bytes, crc:u4} chunks until IEND. Dense enough
 * to walk safely even on corrupt images — we bound the loop to the byte array length.
 */
public final class PngParser implements StructureParser {

    private static final byte[] SIG = {(byte)0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    @Override public String formatName() { return "PNG"; }

    @Override
    public boolean matches(byte[] bytes) {
        if (bytes == null || bytes.length < SIG.length) return false;
        for (int i = 0; i < SIG.length; i++) {
            if (bytes[i] != SIG[i]) return false;
        }
        return true;
    }

    @Override
    public StructureNode parse(byte[] bytes) throws UnsupportedFormatException {
        List<StructureNode> children = new ArrayList<>();
        children.add(StructureNode.leaf("signature",
                "PNG (89 50 4E 47 0D 0A 1A 0A)", 0, 8, "magic"));

        int offset = 8;
        int chunkIdx = 0;
        List<StructureNode> chunks = new ArrayList<>();
        while (offset + 12 <= bytes.length) {
            long length = readBeInt(bytes, offset) & 0xFFFFFFFFL;
            if (length > bytes.length - offset - 12) {
                chunks.add(StructureNode.leaf(
                        "chunk[" + chunkIdx + "] truncated",
                        "declared length " + length + " out of range",
                        offset, bytes.length - offset, "table-entry"));
                break;
            }
            String type = new String(bytes, offset + 4, 4, StandardCharsets.US_ASCII);
            String detail = length + " bytes" + describeChunk(type);
            int totalLen = 12 + (int) length;
            String colour = switch (type) {
                case "IHDR" -> "header";
                case "IEND" -> "footer";
                case "IDAT" -> "body";
                default -> "table-entry";
            };
            chunks.add(StructureNode.leaf(
                    "chunk[" + chunkIdx + "] " + type,
                    detail, offset, totalLen, colour));
            offset += totalLen;
            chunkIdx++;
            if ("IEND".equals(type)) break;
        }
        children.add(StructureNode.container("chunks",
                chunkIdx + " chunks", 8, offset - 8, "body", chunks));
        return StructureNode.container(formatName(),
                bytes.length + " bytes", 0, bytes.length, "root", children);
    }

    private static int readBeInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                |  (b[off + 3] & 0xFF);
    }

    private static String describeChunk(String type) {
        return switch (type) {
            case "IHDR" -> " (image header)";
            case "IEND" -> " (end marker)";
            case "IDAT" -> " (image data)";
            case "PLTE" -> " (palette)";
            case "tEXt", "zTXt", "iTXt" -> " (text metadata)";
            case "tIME" -> " (last-modified time)";
            case "gAMA" -> " (gamma)";
            case "sRGB" -> " (sRGB colour space)";
            case "pHYs" -> " (pixel dimensions)";
            default -> "";
        };
    }
}
