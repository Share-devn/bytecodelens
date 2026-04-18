package dev.share.bytecodelens.workspace;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal hand-rolled JSON reader/writer for {@link WorkspaceState}.
 *
 * <p>We deliberately don't pull in Jackson/Gson for one feature — the state is a shallow
 * POJO with only strings/bools/numbers/lists/maps, which this serialiser handles fine.
 * Accepts comments and extra fields on read for forward-compat.</p>
 */
public final class WorkspaceJson {

    private WorkspaceJson() {}

    // --- Write -----------------------------------------------------------------

    public static void write(WorkspaceState state, Path dest) throws IOException {
        try (Writer w = Files.newBufferedWriter(dest, StandardCharsets.UTF_8)) {
            w.write(serialize(state));
        }
    }

    public static String serialize(WorkspaceState s) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\n");
        appendField(sb, "jarPath", s.jarPath, true);
        appendField(sb, "mappingFile", s.mappingFile, true);
        appendField(sb, "mappingFormat", s.mappingFormat, true);
        appendFieldRaw(sb, "activeTab", s.activeTab == null ? "null" : quote(s.activeTab), true);
        appendFieldRaw(sb, "darkTheme", String.valueOf(s.darkTheme), true);
        appendFieldRaw(sb, "mainSplit1", Double.toString(s.mainSplit1), true);
        appendFieldRaw(sb, "mainSplit2", Double.toString(s.mainSplit2), true);
        appendFieldRaw(sb, "centerSplit", Double.toString(s.centerSplit), true);
        appendFieldRaw(sb, "codeFontSize", Double.toString(s.codeFontSize), true);

        sb.append("  \"openTabs\": [");
        for (int i = 0; i < s.openTabs.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(quote(s.openTabs.get(i)));
        }
        sb.append("],\n");

        sb.append("  \"comments\": {");
        boolean first = true;
        for (var e : s.comments.entrySet()) {
            if (!first) sb.append(',');
            sb.append("\n    ").append(quote(e.getKey())).append(": ").append(quote(e.getValue()));
            first = false;
        }
        if (!s.comments.isEmpty()) sb.append('\n').append("  ");
        sb.append("},\n");

        sb.append("  \"excludedPackages\": [");
        for (int i = 0; i < s.excludedPackages.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(quote(s.excludedPackages.get(i)));
        }
        sb.append("]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static void appendField(StringBuilder sb, String name, String value, boolean comma) {
        sb.append("  ").append(quote(name)).append(": ")
                .append(value == null ? "null" : quote(value));
        if (comma) sb.append(",");
        sb.append("\n");
    }

    private static void appendFieldRaw(StringBuilder sb, String name, String rawValue, boolean comma) {
        sb.append("  ").append(quote(name)).append(": ").append(rawValue);
        if (comma) sb.append(",");
        sb.append("\n");
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    // --- Read ------------------------------------------------------------------

    public static WorkspaceState read(Path src) throws IOException {
        return parse(Files.readString(src, StandardCharsets.UTF_8));
    }

    public static WorkspaceState parse(String text) throws IOException {
        Lexer lx = new Lexer(new StringReader(text));
        Object parsed = lx.value();
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IOException("Workspace JSON must be an object");
        }
        WorkspaceState s = new WorkspaceState();
        s.jarPath = str(map, "jarPath");
        s.mappingFile = str(map, "mappingFile");
        s.mappingFormat = str(map, "mappingFormat");
        s.activeTab = str(map, "activeTab");
        Boolean dark = bool(map, "darkTheme");
        if (dark != null) s.darkTheme = dark;
        Double d1 = num(map, "mainSplit1");
        if (d1 != null) s.mainSplit1 = d1;
        Double d2 = num(map, "mainSplit2");
        if (d2 != null) s.mainSplit2 = d2;
        Double dc = num(map, "centerSplit");
        if (dc != null) s.centerSplit = dc;
        Double fs = num(map, "codeFontSize");
        if (fs != null) s.codeFontSize = fs;
        if (map.get("openTabs") instanceof List<?> list) {
            for (Object o : list) if (o != null) s.openTabs.add(o.toString());
        }
        if (map.get("comments") instanceof Map<?, ?> cm) {
            for (var e : cm.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    s.comments.put(e.getKey().toString(), e.getValue().toString());
                }
            }
        }
        if (map.get("excludedPackages") instanceof List<?> list) {
            for (Object o : list) if (o != null) s.excludedPackages.add(o.toString());
        }
        return s;
    }

    private static String str(Map<?, ?> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }
    private static Boolean bool(Map<?, ?> m, String k) {
        Object v = m.get(k);
        return v instanceof Boolean b ? b : null;
    }
    private static Double num(Map<?, ?> m, String k) {
        Object v = m.get(k);
        return v instanceof Number n ? n.doubleValue() : null;
    }

    // --- Tiny JSON lexer -------------------------------------------------------

    private static final class Lexer {
        private final Reader r;
        private int peek;

        Lexer(Reader r) throws IOException { this.r = r; peek = r.read(); }

        Object value() throws IOException {
            skipWs();
            if (peek == -1) throw new IOException("Unexpected EOF");
            char c = (char) peek;
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            if (c == 't' || c == 'f') return bool();
            if (c == 'n') { lit("null"); return null; }
            return num();
        }

        Map<String, Object> obj() throws IOException {
            Map<String, Object> out = new LinkedHashMap<>();
            next(); // {
            skipWs();
            if (peek == '}') { next(); return out; }
            while (true) {
                skipWs();
                String key = str();
                skipWs();
                if (peek != ':') throw new IOException("Expected ':', got " + (char) peek);
                next();
                Object v = value();
                out.put(key, v);
                skipWs();
                if (peek == ',') { next(); continue; }
                if (peek == '}') { next(); return out; }
                throw new IOException("Expected ',' or '}', got " + (char) peek);
            }
        }

        List<Object> arr() throws IOException {
            List<Object> out = new ArrayList<>();
            next(); // [
            skipWs();
            if (peek == ']') { next(); return out; }
            while (true) {
                out.add(value());
                skipWs();
                if (peek == ',') { next(); continue; }
                if (peek == ']') { next(); return out; }
                throw new IOException("Expected ',' or ']'");
            }
        }

        String str() throws IOException {
            if (peek != '"') throw new IOException("Expected string");
            next();
            StringBuilder sb = new StringBuilder();
            while (peek != -1 && peek != '"') {
                if (peek == '\\') {
                    next();
                    switch (peek) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            char[] hex = new char[4];
                            for (int i = 0; i < 4; i++) { next(); hex[i] = (char) peek; }
                            sb.append((char) Integer.parseInt(new String(hex), 16));
                        }
                        default -> throw new IOException("Bad escape \\" + (char) peek);
                    }
                    next();
                } else {
                    sb.append((char) peek);
                    next();
                }
            }
            if (peek != '"') throw new IOException("Unterminated string");
            next();
            return sb.toString();
        }

        Boolean bool() throws IOException {
            if (peek == 't') { lit("true"); return Boolean.TRUE; }
            lit("false");
            return Boolean.FALSE;
        }

        void lit(String s) throws IOException {
            for (int i = 0; i < s.length(); i++) {
                if (peek != s.charAt(i)) throw new IOException("Expected literal " + s);
                next();
            }
        }

        Number num() throws IOException {
            StringBuilder sb = new StringBuilder();
            while (peek != -1 && "-+0123456789.eE".indexOf((char) peek) >= 0) {
                sb.append((char) peek); next();
            }
            String s = sb.toString();
            if (s.contains(".") || s.contains("e") || s.contains("E")) return Double.parseDouble(s);
            return Long.parseLong(s);
        }

        void skipWs() throws IOException {
            while (peek != -1 && Character.isWhitespace(peek)) next();
        }

        void next() throws IOException { peek = r.read(); }
    }
}
