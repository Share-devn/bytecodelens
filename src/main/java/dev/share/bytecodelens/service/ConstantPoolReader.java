package dev.share.bytecodelens.service;

import dev.share.bytecodelens.model.ConstantPoolEntry;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public final class ConstantPoolReader {

    private static final int CONSTANT_Utf8 = 1;
    private static final int CONSTANT_Integer = 3;
    private static final int CONSTANT_Float = 4;
    private static final int CONSTANT_Long = 5;
    private static final int CONSTANT_Double = 6;
    private static final int CONSTANT_Class = 7;
    private static final int CONSTANT_String = 8;
    private static final int CONSTANT_Fieldref = 9;
    private static final int CONSTANT_Methodref = 10;
    private static final int CONSTANT_InterfaceMethodref = 11;
    private static final int CONSTANT_NameAndType = 12;
    private static final int CONSTANT_MethodHandle = 15;
    private static final int CONSTANT_MethodType = 16;
    private static final int CONSTANT_Dynamic = 17;
    private static final int CONSTANT_InvokeDynamic = 18;
    private static final int CONSTANT_Module = 19;
    private static final int CONSTANT_Package = 20;

    public List<ConstantPoolEntry> read(byte[] bytes) throws IOException {
        List<ConstantPoolEntry> result = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
            int magic = in.readInt();
            if (magic != 0xCAFEBABE) {
                throw new IOException("Not a class file (magic=" + Integer.toHexString(magic) + ")");
            }
            in.readUnsignedShort();
            in.readUnsignedShort();
            int cpCount = in.readUnsignedShort();

            Object[] raw = new Object[cpCount];
            int[] tags = new int[cpCount];

            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                tags[i] = tag;
                switch (tag) {
                    case CONSTANT_Utf8 -> raw[i] = in.readUTF();
                    case CONSTANT_Integer -> raw[i] = in.readInt();
                    case CONSTANT_Float -> raw[i] = in.readFloat();
                    case CONSTANT_Long -> {
                        raw[i] = in.readLong();
                        i++;
                    }
                    case CONSTANT_Double -> {
                        raw[i] = in.readDouble();
                        i++;
                    }
                    case CONSTANT_Class, CONSTANT_String, CONSTANT_MethodType, CONSTANT_Module, CONSTANT_Package ->
                            raw[i] = in.readUnsignedShort();
                    case CONSTANT_Fieldref, CONSTANT_Methodref, CONSTANT_InterfaceMethodref,
                         CONSTANT_NameAndType, CONSTANT_Dynamic, CONSTANT_InvokeDynamic ->
                            raw[i] = new int[]{in.readUnsignedShort(), in.readUnsignedShort()};
                    case CONSTANT_MethodHandle -> raw[i] = new int[]{in.readUnsignedByte(), in.readUnsignedShort()};
                    default -> throw new IOException("Unknown constant pool tag: " + tag + " at index " + i);
                }
            }

            for (int i = 1; i < cpCount; i++) {
                int tag = tags[i];
                if (tag == 0) continue;
                String type = tagName(tag);
                String value = formatValue(tag, raw[i], raw, tags);
                result.add(new ConstantPoolEntry(i, type, value));
            }
        }
        return result;
    }

    private static String tagName(int tag) {
        return switch (tag) {
            case CONSTANT_Utf8 -> "Utf8";
            case CONSTANT_Integer -> "Integer";
            case CONSTANT_Float -> "Float";
            case CONSTANT_Long -> "Long";
            case CONSTANT_Double -> "Double";
            case CONSTANT_Class -> "Class";
            case CONSTANT_String -> "String";
            case CONSTANT_Fieldref -> "Fieldref";
            case CONSTANT_Methodref -> "Methodref";
            case CONSTANT_InterfaceMethodref -> "InterfaceMethodref";
            case CONSTANT_NameAndType -> "NameAndType";
            case CONSTANT_MethodHandle -> "MethodHandle";
            case CONSTANT_MethodType -> "MethodType";
            case CONSTANT_Dynamic -> "Dynamic";
            case CONSTANT_InvokeDynamic -> "InvokeDynamic";
            case CONSTANT_Module -> "Module";
            case CONSTANT_Package -> "Package";
            default -> "Tag" + tag;
        };
    }

    private String formatValue(int tag, Object v, Object[] raw, int[] tags) {
        if (v == null) return "";
        return switch (tag) {
            case CONSTANT_Utf8 -> (String) v;
            case CONSTANT_Integer, CONSTANT_Float, CONSTANT_Long, CONSTANT_Double -> String.valueOf(v);
            case CONSTANT_Class, CONSTANT_String, CONSTANT_MethodType, CONSTANT_Module, CONSTANT_Package -> {
                int idx = (int) v;
                yield "#" + idx + " -> " + utf8At(raw, tags, idx);
            }
            case CONSTANT_Fieldref, CONSTANT_Methodref, CONSTANT_InterfaceMethodref,
                 CONSTANT_NameAndType, CONSTANT_Dynamic, CONSTANT_InvokeDynamic -> {
                int[] pair = (int[]) v;
                yield "#" + pair[0] + ", #" + pair[1];
            }
            case CONSTANT_MethodHandle -> {
                int[] pair = (int[]) v;
                yield "kind=" + pair[0] + ", #" + pair[1];
            }
            default -> v.toString();
        };
    }

    private String utf8At(Object[] raw, int[] tags, int idx) {
        if (idx <= 0 || idx >= raw.length) return "?";
        if (tags[idx] == CONSTANT_Utf8) return (String) raw[idx];
        return "?";
    }
}
