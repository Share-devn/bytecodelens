package dev.share.bytecodelens.structure.parsers;

import dev.share.bytecodelens.structure.StructureNode;
import dev.share.bytecodelens.structure.StructureParser;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Java {@code .class} file structure parser — walks the JVM 17 classfile layout:
 *
 * <pre>
 *   u4   magic = 0xCAFEBABE
 *   u2   minor_version
 *   u2   major_version
 *   u2   constant_pool_count
 *   cp_info constant_pool[constant_pool_count - 1]
 *   u2   access_flags
 *   u2   this_class
 *   u2   super_class
 *   u2   interfaces_count
 *   u2   interfaces[]
 *   u2   fields_count
 *   field_info fields[]
 *   u2   methods_count
 *   method_info methods[]
 *   u2   attributes_count
 *   attribute_info attributes[]
 * </pre>
 *
 * <p>We produce a container per section and a labelled leaf per constant-pool entry
 * with its decoded value where practical (UTF-8 strings, numeric constants, name/type
 * references). Field/method/attribute bodies are emitted as size-labelled containers
 * without further unpacking — enough for structural overlay, not a full decompile.</p>
 */
public final class ClassFileParser implements StructureParser {

    @Override public String formatName() { return "Java class file"; }

    @Override
    public boolean matches(byte[] bytes) {
        return bytes != null && bytes.length >= 10
                && (bytes[0] & 0xFF) == 0xCA
                && (bytes[1] & 0xFF) == 0xFE
                && (bytes[2] & 0xFF) == 0xBA
                && (bytes[3] & 0xFF) == 0xBE;
    }

    @Override
    public StructureNode parse(byte[] bytes) throws UnsupportedFormatException {
        Reader r = new Reader(bytes);
        try {
            List<StructureNode> top = new ArrayList<>();

            int start = 0;
            top.add(StructureNode.leaf("magic", "0xCAFEBABE",
                    r.pos(), 4, "magic"));
            r.skip(4);

            int minor = r.u2();
            top.add(StructureNode.leaf("minor_version", Integer.toString(minor),
                    r.pos() - 2, 2, "header"));

            int major = r.u2();
            top.add(StructureNode.leaf("major_version",
                    major + " (Java " + (major - 44) + ")",
                    r.pos() - 2, 2, "header"));

            int cpCount = r.u2();
            int cpCountOffset = r.pos() - 2;
            List<StructureNode> cpEntries = new ArrayList<>();
            int cpStart = r.pos();
            // Constant pool is 1-indexed; [0] is reserved.
            for (int i = 1; i < cpCount; i++) {
                int entryStart = r.pos();
                StructureNode entry = readCpEntry(r, i);
                cpEntries.add(entry);
                // Long/Double occupy two slots per the JVM spec (ancient design decision).
                int tag = entryTagAt(bytes, entryStart);
                if (tag == 5 || tag == 6) i++;
            }
            int cpLen = r.pos() - cpStart;
            StructureNode cpCountNode = StructureNode.leaf("constant_pool_count",
                    Integer.toString(cpCount), cpCountOffset, 2, "header");
            StructureNode cpContainer = StructureNode.container(
                    "constant_pool", (cpEntries.size()) + " entries",
                    cpStart, cpLen, "constant-pool", cpEntries);
            top.add(cpCountNode);
            top.add(cpContainer);

            int accessFlags = r.u2();
            top.add(StructureNode.leaf("access_flags",
                    String.format("0x%04X", accessFlags),
                    r.pos() - 2, 2, "header"));

            int thisClass = r.u2();
            top.add(StructureNode.leaf("this_class",
                    "#" + thisClass, r.pos() - 2, 2, "header"));

            int superClass = r.u2();
            top.add(StructureNode.leaf("super_class",
                    "#" + superClass, r.pos() - 2, 2, "header"));

            int ifaceCount = r.u2();
            int ifaceCountOff = r.pos() - 2;
            int ifaceStart = r.pos();
            List<StructureNode> ifaceNodes = new ArrayList<>();
            for (int i = 0; i < ifaceCount; i++) {
                int idx = r.u2();
                ifaceNodes.add(StructureNode.leaf("interface[" + i + "]",
                        "#" + idx, r.pos() - 2, 2, "table-entry"));
            }
            top.add(StructureNode.leaf("interfaces_count",
                    Integer.toString(ifaceCount), ifaceCountOff, 2, "header"));
            top.add(StructureNode.container("interfaces",
                    ifaceCount + " entries", ifaceStart, ifaceCount * 2,
                    "table", ifaceNodes));

            // Fields, methods, attributes share the same outer shape:
            //   u2 access_flags, u2 name_idx, u2 desc_idx, u2 attr_count, attribute_info[]
            top.addAll(readTable(r, "fields", "field", bytes));
            top.addAll(readTable(r, "methods", "method", bytes));
            top.addAll(readAttributeTable(r, "attributes", bytes));

            int totalLen = r.pos() - start;
            return StructureNode.container(formatName(),
                    bytes.length + " bytes", 0, totalLen, "root", top);

        } catch (IOException ex) {
            throw new UnsupportedFormatException("parse error: " + ex.getMessage());
        } catch (IndexOutOfBoundsException ex) {
            throw new UnsupportedFormatException("truncated at offset " + r.pos());
        }
    }

    /** Peek the tag byte at the given offset — we already advanced past it in the reader. */
    private static int entryTagAt(byte[] bytes, int offset) {
        return bytes[offset] & 0xFF;
    }

    // ========================================================================
    // Constant pool entries
    // ========================================================================

    private StructureNode readCpEntry(Reader r, int index) throws IOException {
        int entryStart = r.pos();
        int tag = r.u1();
        String label;
        String detail;
        int length;
        String tagName = cpTagName(tag);
        switch (tag) {
            case 1 -> { // Utf8
                int len = r.u2();
                byte[] raw = r.bytes(len);
                String s = new String(raw, StandardCharsets.UTF_8);
                label = "#" + index + " " + tagName;
                detail = abbreviate(s, 64);
                length = 1 + 2 + len;
            }
            case 3, 4 -> { // Integer / Float
                long v = r.u4();
                label = "#" + index + " " + tagName;
                detail = tag == 3 ? Integer.toString((int) v)
                                  : formatFloat(Float.intBitsToFloat((int) v));
                length = 1 + 4;
            }
            case 5, 6 -> { // Long / Double
                long high = r.u4();
                long low = r.u4();
                long raw = (high << 32) | (low & 0xFFFFFFFFL);
                label = "#" + index + " " + tagName + " (takes 2 slots)";
                detail = tag == 5 ? Long.toString(raw) : formatFloat(Double.longBitsToDouble(raw));
                length = 1 + 8;
            }
            case 7, 8, 19, 20 -> { // Class / String / Module / Package
                int idx = r.u2();
                label = "#" + index + " " + tagName;
                detail = "-> #" + idx;
                length = 1 + 2;
            }
            case 9, 10, 11 -> { // Fieldref / Methodref / InterfaceMethodref
                int classIdx = r.u2();
                int nameTypeIdx = r.u2();
                label = "#" + index + " " + tagName;
                detail = "class #" + classIdx + ", name&type #" + nameTypeIdx;
                length = 1 + 4;
            }
            case 12 -> { // NameAndType
                int nameIdx = r.u2();
                int descIdx = r.u2();
                label = "#" + index + " NameAndType";
                detail = "name #" + nameIdx + ", desc #" + descIdx;
                length = 1 + 4;
            }
            case 15 -> { // MethodHandle
                int kind = r.u1();
                int refIdx = r.u2();
                label = "#" + index + " MethodHandle";
                detail = "kind " + kind + " -> #" + refIdx;
                length = 1 + 3;
            }
            case 16 -> { // MethodType
                int idx = r.u2();
                label = "#" + index + " MethodType";
                detail = "desc #" + idx;
                length = 1 + 2;
            }
            case 17, 18 -> { // Dynamic / InvokeDynamic
                int bootstrap = r.u2();
                int nameType = r.u2();
                label = "#" + index + " " + tagName;
                detail = "bootstrap #" + bootstrap + ", name&type #" + nameType;
                length = 1 + 4;
            }
            default -> {
                label = "#" + index + " unknown tag " + tag;
                detail = "skipping — format error";
                length = 1;
            }
        }
        return StructureNode.leaf(label, detail, entryStart, length, "constant-pool-entry");
    }

    private static String cpTagName(int tag) {
        return switch (tag) {
            case 1 -> "Utf8";
            case 3 -> "Integer";
            case 4 -> "Float";
            case 5 -> "Long";
            case 6 -> "Double";
            case 7 -> "Class";
            case 8 -> "String";
            case 9 -> "Fieldref";
            case 10 -> "Methodref";
            case 11 -> "InterfaceMethodref";
            case 12 -> "NameAndType";
            case 15 -> "MethodHandle";
            case 16 -> "MethodType";
            case 17 -> "Dynamic";
            case 18 -> "InvokeDynamic";
            case 19 -> "Module";
            case 20 -> "Package";
            default -> "Unknown(" + tag + ")";
        };
    }

    private static String abbreviate(String s, int max) {
        String clean = s.replaceAll("[\\s]+", " ");
        if (clean.length() <= max) return "\"" + clean + "\"";
        return "\"" + clean.substring(0, max) + "\"…";
    }

    private static String formatFloat(double d) {
        if (Double.isNaN(d)) return "NaN";
        if (Double.isInfinite(d)) return d > 0 ? "+Inf" : "-Inf";
        return String.format(java.util.Locale.ROOT, "%.7g", d);
    }

    // ========================================================================
    // Fields + methods — same layout
    // ========================================================================

    private List<StructureNode> readTable(Reader r, String sectionName, String entryPrefix, byte[] bytes)
            throws IOException {
        int countOff = r.pos();
        int count = r.u2();
        int start = r.pos();
        List<StructureNode> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int entryStart = r.pos();
            int flags = r.u2();
            int nameIdx = r.u2();
            int descIdx = r.u2();
            int attrCount = r.u2();
            int attrStart = r.pos();
            List<StructureNode> attrs = new ArrayList<>();
            for (int a = 0; a < attrCount; a++) {
                attrs.add(readAttribute(r, bytes));
            }
            int entryLen = r.pos() - entryStart;
            StructureNode body = StructureNode.container(
                    entryPrefix + "[" + i + "]",
                    String.format("flags=0x%04X name=#%d desc=#%d  (%d attr%s)",
                            flags, nameIdx, descIdx, attrCount, attrCount == 1 ? "" : "s"),
                    entryStart, entryLen, "member", attrs);
            entries.add(body);
        }
        int sectionLen = r.pos() - start;
        List<StructureNode> out = new ArrayList<>();
        out.add(StructureNode.leaf(sectionName + "_count",
                Integer.toString(count), countOff, 2, "header"));
        out.add(StructureNode.container(sectionName,
                count + " entries", start, sectionLen, "table", entries));
        return out;
    }

    private List<StructureNode> readAttributeTable(Reader r, String sectionName, byte[] bytes)
            throws IOException {
        int countOff = r.pos();
        int count = r.u2();
        int start = r.pos();
        List<StructureNode> attrs = new ArrayList<>();
        for (int a = 0; a < count; a++) {
            attrs.add(readAttribute(r, bytes));
        }
        int sectionLen = r.pos() - start;
        List<StructureNode> out = new ArrayList<>();
        out.add(StructureNode.leaf(sectionName + "_count",
                Integer.toString(count), countOff, 2, "header"));
        out.add(StructureNode.container(sectionName,
                count + " entries", start, sectionLen, "table", attrs));
        return out;
    }

    private StructureNode readAttribute(Reader r, byte[] bytes) throws IOException {
        int start = r.pos();
        int nameIdx = r.u2();
        long length = r.u4();
        if (length < 0 || length > bytes.length) length = 0;
        r.skip((int) length);
        return StructureNode.leaf("attribute #" + nameIdx,
                length + " bytes", start, 6 + (int) length, "attribute");
    }

    // ========================================================================
    // Tiny helper that tracks cursor position separately from DataInputStream.
    // ========================================================================

    private static final class Reader {
        private final byte[] bytes;
        private int pos = 0;

        Reader(byte[] bytes) { this.bytes = bytes; }

        int pos() { return pos; }

        void skip(int n) { pos = Math.min(bytes.length, pos + n); }

        int u1() throws IOException {
            if (pos >= bytes.length) throw new IOException("EOF at " + pos);
            return bytes[pos++] & 0xFF;
        }
        int u2() throws IOException {
            if (pos + 2 > bytes.length) throw new IOException("EOF at " + pos);
            int v = ((bytes[pos] & 0xFF) << 8) | (bytes[pos + 1] & 0xFF);
            pos += 2;
            return v;
        }
        long u4() throws IOException {
            if (pos + 4 > bytes.length) throw new IOException("EOF at " + pos);
            long v = ((long)(bytes[pos]     & 0xFF) << 24)
                   | ((long)(bytes[pos + 1] & 0xFF) << 16)
                   | ((long)(bytes[pos + 2] & 0xFF) << 8)
                   |  (long)(bytes[pos + 3] & 0xFF);
            pos += 4;
            return v;
        }
        byte[] bytes(int n) throws IOException {
            if (pos + n > bytes.length) throw new IOException("EOF at " + pos + " need " + n);
            byte[] out = new byte[n];
            System.arraycopy(bytes, pos, out, 0, n);
            pos += n;
            return out;
        }
    }
}
