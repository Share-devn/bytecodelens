package dev.share.bytecodelens.mapping;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses ProGuard / R8 {@code mapping.txt} files.
 *
 * <p>Input direction is {@code original -> obfuscated}; we invert it so callers get
 * {@code obfuscated -> original} — the practical direction for reverse engineering.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * com.foo.Bar -> a.b:
 *     int x -> a
 *     12:15:void doSomething() -> a
 *     void doOther(int,java.lang.String) -> b
 * }</pre>
 */
public final class ProGuardMappingParser {

    // "com.foo.Bar -> a.b:"
    private static final Pattern CLASS_LINE =
            Pattern.compile("^([\\w$.]+(?:\\[\\])*)\\s*->\\s*([\\w$.]+(?:\\[\\])*):$");
    // "    int x -> a"  or  "    12:15:void m() -> a"  or  "    void m(int,java.lang.String) -> b"
    private static final Pattern MEMBER_LINE =
            Pattern.compile("^\\s+(?:\\d+:\\d+:)?([\\w$.\\[\\]]+)\\s+([\\w$<>]+)(?:\\(([^)]*)\\))?\\s*(?::\\d+:\\d+)?\\s*->\\s*([\\w$<>]+)$");

    private ProGuardMappingParser() {}

    public static MappingModel parse(Reader in) throws IOException {
        MappingModel.Builder out = MappingModel.builder(MappingFormat.PROGUARD);
        String currentOrigInternal = null;
        String currentObfInternal = null;

        try (BufferedReader br = new BufferedReader(in)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;

                Matcher cm = CLASS_LINE.matcher(line);
                if (cm.matches()) {
                    String origDot = cm.group(1);
                    String obfDot = cm.group(2);
                    currentOrigInternal = origDot.replace('.', '/');
                    currentObfInternal = obfDot.replace('.', '/');
                    // obfuscated -> original
                    out.mapClass(currentObfInternal, currentOrigInternal);
                    continue;
                }

                Matcher mm = MEMBER_LINE.matcher(line);
                if (mm.matches() && currentObfInternal != null) {
                    String returnOrFieldType = mm.group(1); // "int", "java.lang.String[]", "void"
                    String origName = mm.group(2);
                    String paramList = mm.group(3); // null for fields, "" for no-arg method, "int,java.lang.String" otherwise
                    String obfName = mm.group(4);

                    if (paramList == null) {
                        // Field: "int x -> a"
                        String desc = javaTypeToDescriptor(returnOrFieldType);
                        out.mapField(currentObfInternal, obfName, desc, origName);
                    } else {
                        // Method: build descriptor (args)return
                        String args = buildParamDescriptors(paramList);
                        String retDesc = javaTypeToDescriptor(returnOrFieldType);
                        String desc = "(" + args + ")" + retDesc;
                        out.mapMethod(currentObfInternal, obfName, desc, origName);
                    }
                }
            }
        }
        return out.build();
    }

    // --- type descriptor construction -----------------------------------------

    /**
     * Converts a Java source-style type ({@code int}, {@code java.lang.String[]}, {@code void}) to
     * a JVMS descriptor ({@code I}, {@code [Ljava/lang/String;}, {@code V}).
     */
    static String javaTypeToDescriptor(String javaType) {
        int arrayDims = 0;
        String base = javaType;
        while (base.endsWith("[]")) {
            arrayDims++;
            base = base.substring(0, base.length() - 2);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arrayDims; i++) sb.append('[');
        sb.append(primitiveOrObject(base.trim()));
        return sb.toString();
    }

    private static String primitiveOrObject(String base) {
        return switch (base) {
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            case "void" -> "V";
            default -> "L" + base.replace('.', '/') + ";";
        };
    }

    /** "int,java.lang.String" -> "ILjava/lang/String;" */
    static String buildParamDescriptors(String paramList) {
        if (paramList == null || paramList.isEmpty()) return "";
        String[] parts = paramList.split(",");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            sb.append(javaTypeToDescriptor(p.trim()));
        }
        return sb.toString();
    }
}
