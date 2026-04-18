package dev.share.bytecodelens.settings;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Hand-rolled JSON reader/writer for {@link AppSettings}. Deliberately minimal: the
 * state is a flat-ish POJO and pulling in Jackson/Gson for one file is overkill.
 *
 * <p>Write path is <b>atomic</b> — we write to {@code settings.json.tmp} and rename
 * on success. A failure mid-write leaves the previous settings intact. Read path is
 * tolerant: missing fields fall back to defaults, unknown fields are ignored, and
 * malformed JSON returns a defaulted {@link AppSettings} rather than throwing.</p>
 */
public final class AppSettingsJson {

    private AppSettingsJson() {}

    // --- Write ---------------------------------------------------------------

    public static void writeAtomic(AppSettings s, Path dest) throws IOException {
        Path tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
        if (dest.getParent() != null) Files.createDirectories(dest.getParent());
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            w.write(serialize(s));
        }
        // Atomic move where supported, fallback to replace on Windows.
        try {
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static String serialize(AppSettings s) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("{\n");
        kv(sb, "version", String.valueOf(s.version), true, 1);

        section(sb, "appearance", 1, () -> {
            kv(sb, "uiTheme", quote(s.appearance.uiTheme.name()), true, 2);
            kv(sb, "syntaxThemeId", quote(s.appearance.syntaxThemeId), true, 2);
            kv(sb, "uiFontFamily", quote(s.appearance.uiFontFamily), true, 2);
            kv(sb, "uiFontSize", Double.toString(s.appearance.uiFontSize), true, 2);
            kv(sb, "showLineNumbers", Boolean.toString(s.appearance.showLineNumbers), true, 2);
            kv(sb, "showCaretLineHighlight", Boolean.toString(s.appearance.showCaretLineHighlight), true, 2);
            kv(sb, "showFocusedLinePulse", Boolean.toString(s.appearance.showFocusedLinePulse), true, 2);
            kv(sb, "hoverHighlightIdentifiers", Boolean.toString(s.appearance.hoverHighlightIdentifiers), true, 2);
            kv(sb, "ctrlUnderlineOnHover", Boolean.toString(s.appearance.ctrlUnderlineOnHover), false, 2);
        });
        sb.append(",\n");

        section(sb, "editor", 1, () -> {
            kv(sb, "codeFontSize", Double.toString(s.editor.codeFontSize), true, 2);
            kv(sb, "tabSize", Integer.toString(s.editor.tabSize), true, 2);
            kv(sb, "showWhitespace", Boolean.toString(s.editor.showWhitespace), true, 2);
            kv(sb, "autoCollapseComments", Boolean.toString(s.editor.autoCollapseComments), true, 2);
            kv(sb, "wrapLongLines", Boolean.toString(s.editor.wrapLongLines), false, 2);
        });
        sb.append(",\n");

        section(sb, "decompiler", 1, () -> {
            kv(sb, "defaultEngine", quote(s.decompiler.defaultEngine.name()), true, 2);
            kv(sb, "perEngineTimeoutMs", Integer.toString(s.decompiler.perEngineTimeoutMs), true, 2);
            kv(sb, "autoFallbackOnFail", Boolean.toString(s.decompiler.autoFallbackOnFail), true, 2);
            kv(sb, "cacheEnabled", Boolean.toString(s.decompiler.cacheEnabled), true, 2);
            kv(sb, "cacheCapacity", Integer.toString(s.decompiler.cacheCapacity), true, 2);
            kv(sb, "backgroundWarmupEnabled", Boolean.toString(s.decompiler.backgroundWarmupEnabled), true, 2);
            kv(sb, "warmupNeighborhoodSize", Integer.toString(s.decompiler.warmupNeighborhoodSize), true, 2);
            kv(sb, "warmupThreadPriority", quote(s.decompiler.warmupThreadPriority.name()), false, 2);
        });
        sb.append(",\n");

        section(sb, "xref", 1, () -> {
            kv(sb, "showCodeSnippetPreview", Boolean.toString(s.xref.showCodeSnippetPreview), true, 2);
            kv(sb, "includeOverridersInUsages", Boolean.toString(s.xref.includeOverridersInUsages), true, 2);
            kv(sb, "recursiveCallersMaxDepth", Integer.toString(s.xref.recursiveCallersMaxDepth), true, 2);
            kv(sb, "recursiveCallersMaxPerNode", Integer.toString(s.xref.recursiveCallersMaxPerNode), true, 2);
            kv(sb, "stringLiteralIndexEnabled", Boolean.toString(s.xref.stringLiteralIndexEnabled), false, 2);
        });
        sb.append(",\n");

        section(sb, "search", 1, () -> {
            kv(sb, "defaultSearchMode", quote(s.search.defaultSearchMode.name()), true, 2);
            kv(sb, "streamingThreshold", Integer.toString(s.search.streamingThreshold), true, 2);
            kv(sb, "caseSensitiveDefault", Boolean.toString(s.search.caseSensitiveDefault), true, 2);
            kv(sb, "persistExcludedPackagesAcrossJars",
                    Boolean.toString(s.search.persistExcludedPackagesAcrossJars), false, 2);
        });
        sb.append(",\n");

        section(sb, "tree", 1, () -> {
            kv(sb, "showDecompileStatusBadges", Boolean.toString(s.tree.showDecompileStatusBadges), true, 2);
            kv(sb, "expandableClassTreeDefault", Boolean.toString(s.tree.expandableClassTreeDefault), true, 2);
            kv(sb, "openPreviewOnSingleClick", Boolean.toString(s.tree.openPreviewOnSingleClick), true, 2);
            kv(sb, "promoteOnDoubleClick", Boolean.toString(s.tree.promoteOnDoubleClick), true, 2);
            kv(sb, "syncWithEditorOnOpen", Boolean.toString(s.tree.syncWithEditorOnOpen), false, 2);
        });
        sb.append(",\n");

        section(sb, "hex", 1, () -> {
            kv(sb, "defaultRowWidth", Integer.toString(s.hex.defaultRowWidth), true, 2);
            kv(sb, "offsetBase", quote(s.hex.offsetBase.name()), true, 2);
            kv(sb, "showStructureTabByDefault", Boolean.toString(s.hex.showStructureTabByDefault), true, 2);
            kv(sb, "defaultInspectorEndianness", quote(s.hex.defaultInspectorEndianness.name()), false, 2);
        });
        sb.append(",\n");

        section(sb, "jvm", 1, () -> {
            kv(sb, "autoRefreshIntervalMs", Integer.toString(s.jvm.autoRefreshIntervalMs), true, 2);
            kv(sb, "importClassesOnAttachByDefault",
                    Boolean.toString(s.jvm.importClassesOnAttachByDefault), false, 2);
        });
        sb.append(",\n");

        section(sb, "transformations", 1, () -> {
            sb.append("    \"defaultSelectedPasses\": [");
            List<String> passes = new ArrayList<>(s.transformations.defaultSelectedPasses);
            for (int i = 0; i < passes.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(quote(passes.get(i)));
            }
            sb.append("]\n");
        });
        sb.append(",\n");

        section(sb, "language", 1, () -> {
            kv(sb, "locale", quote(s.language.locale), true, 2);
            kv(sb, "fallbackToEnglish", Boolean.toString(s.language.fallbackToEnglish), false, 2);
        });
        sb.append(",\n");

        section(sb, "paths", 1, () -> {
            kv(sb, "agentDir", quote(s.paths.agentDir), true, 2);
            kv(sb, "recentLimit", Integer.toString(s.paths.recentLimit), true, 2);
            kv(sb, "pinnedLimit", Integer.toString(s.paths.pinnedLimit), false, 2);
        });
        sb.append(",\n");

        section(sb, "advanced", 1, () -> {
            kv(sb, "gcLogLevel", quote(s.advanced.gcLogLevel.name()), true, 2);
            kv(sb, "javaHomeOverride", quote(s.advanced.javaHomeOverride), true, 2);
            kv(sb, "maxClassParseThreads", Integer.toString(s.advanced.maxClassParseThreads), false, 2);
        });
        sb.append("\n}\n");

        return sb.toString();
    }

    private static void kv(StringBuilder sb, String key, String rawValue, boolean comma, int indent) {
        sb.append("  ".repeat(indent)).append(quote(key)).append(": ").append(rawValue);
        if (comma) sb.append(",");
        sb.append("\n");
    }

    private static void section(StringBuilder sb, String name, int indent, Runnable body) {
        sb.append("  ".repeat(indent)).append(quote(name)).append(": {\n");
        body.run();
        sb.append("  ".repeat(indent)).append("}");
    }

    // --- Read ----------------------------------------------------------------

    /** Reads the file, returning a defaulted {@link AppSettings} on any error. */
    public static AppSettings readOrDefaults(Path src) {
        if (!Files.isRegularFile(src)) return AppSettings.defaults();
        try {
            return parse(Files.readString(src, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            return AppSettings.defaults();
        }
    }

    public static AppSettings parse(String json) {
        Map<String, Object> root = JsonMini.parseObject(json);
        if (root == null) return AppSettings.defaults();
        AppSettings out = new AppSettings();

        Number v = numberAt(root, "version");
        if (v != null) out.version = v.intValue();

        Map<String, Object> ap = objectAt(root, "appearance");
        if (ap != null) {
            out.appearance.uiTheme = enumAt(ap, "uiTheme", AppSettings.UiTheme.class, out.appearance.uiTheme);
            out.appearance.syntaxThemeId = stringAt(ap, "syntaxThemeId", out.appearance.syntaxThemeId);
            out.appearance.uiFontFamily = stringAt(ap, "uiFontFamily", out.appearance.uiFontFamily);
            out.appearance.uiFontSize = doubleAt(ap, "uiFontSize", out.appearance.uiFontSize);
            out.appearance.showLineNumbers = boolAt(ap, "showLineNumbers", out.appearance.showLineNumbers);
            out.appearance.showCaretLineHighlight = boolAt(ap, "showCaretLineHighlight", out.appearance.showCaretLineHighlight);
            out.appearance.showFocusedLinePulse = boolAt(ap, "showFocusedLinePulse", out.appearance.showFocusedLinePulse);
            out.appearance.hoverHighlightIdentifiers = boolAt(ap, "hoverHighlightIdentifiers", out.appearance.hoverHighlightIdentifiers);
            out.appearance.ctrlUnderlineOnHover = boolAt(ap, "ctrlUnderlineOnHover", out.appearance.ctrlUnderlineOnHover);
        }
        Map<String, Object> ed = objectAt(root, "editor");
        if (ed != null) {
            out.editor.codeFontSize = doubleAt(ed, "codeFontSize", out.editor.codeFontSize);
            out.editor.tabSize = intAt(ed, "tabSize", out.editor.tabSize);
            out.editor.showWhitespace = boolAt(ed, "showWhitespace", out.editor.showWhitespace);
            out.editor.autoCollapseComments = boolAt(ed, "autoCollapseComments", out.editor.autoCollapseComments);
            out.editor.wrapLongLines = boolAt(ed, "wrapLongLines", out.editor.wrapLongLines);
        }
        Map<String, Object> dc = objectAt(root, "decompiler");
        if (dc != null) {
            out.decompiler.defaultEngine = enumAt(dc, "defaultEngine", AppSettings.DecompilerEngine.class, out.decompiler.defaultEngine);
            out.decompiler.perEngineTimeoutMs = intAt(dc, "perEngineTimeoutMs", out.decompiler.perEngineTimeoutMs);
            out.decompiler.autoFallbackOnFail = boolAt(dc, "autoFallbackOnFail", out.decompiler.autoFallbackOnFail);
            out.decompiler.cacheEnabled = boolAt(dc, "cacheEnabled", out.decompiler.cacheEnabled);
            out.decompiler.cacheCapacity = intAt(dc, "cacheCapacity", out.decompiler.cacheCapacity);
            out.decompiler.backgroundWarmupEnabled = boolAt(dc, "backgroundWarmupEnabled", out.decompiler.backgroundWarmupEnabled);
            out.decompiler.warmupNeighborhoodSize = intAt(dc, "warmupNeighborhoodSize", out.decompiler.warmupNeighborhoodSize);
            out.decompiler.warmupThreadPriority = enumAt(dc, "warmupThreadPriority", AppSettings.WarmupPriority.class, out.decompiler.warmupThreadPriority);
        }
        Map<String, Object> xr = objectAt(root, "xref");
        if (xr != null) {
            out.xref.showCodeSnippetPreview = boolAt(xr, "showCodeSnippetPreview", out.xref.showCodeSnippetPreview);
            out.xref.includeOverridersInUsages = boolAt(xr, "includeOverridersInUsages", out.xref.includeOverridersInUsages);
            out.xref.recursiveCallersMaxDepth = intAt(xr, "recursiveCallersMaxDepth", out.xref.recursiveCallersMaxDepth);
            out.xref.recursiveCallersMaxPerNode = intAt(xr, "recursiveCallersMaxPerNode", out.xref.recursiveCallersMaxPerNode);
            out.xref.stringLiteralIndexEnabled = boolAt(xr, "stringLiteralIndexEnabled", out.xref.stringLiteralIndexEnabled);
        }
        Map<String, Object> sr = objectAt(root, "search");
        if (sr != null) {
            out.search.defaultSearchMode = enumAt(sr, "defaultSearchMode", AppSettings.SearchMode.class, out.search.defaultSearchMode);
            out.search.streamingThreshold = intAt(sr, "streamingThreshold", out.search.streamingThreshold);
            out.search.caseSensitiveDefault = boolAt(sr, "caseSensitiveDefault", out.search.caseSensitiveDefault);
            out.search.persistExcludedPackagesAcrossJars = boolAt(sr, "persistExcludedPackagesAcrossJars", out.search.persistExcludedPackagesAcrossJars);
        }
        Map<String, Object> tr = objectAt(root, "tree");
        if (tr != null) {
            out.tree.showDecompileStatusBadges = boolAt(tr, "showDecompileStatusBadges", out.tree.showDecompileStatusBadges);
            out.tree.expandableClassTreeDefault = boolAt(tr, "expandableClassTreeDefault", out.tree.expandableClassTreeDefault);
            out.tree.openPreviewOnSingleClick = boolAt(tr, "openPreviewOnSingleClick", out.tree.openPreviewOnSingleClick);
            out.tree.promoteOnDoubleClick = boolAt(tr, "promoteOnDoubleClick", out.tree.promoteOnDoubleClick);
            out.tree.syncWithEditorOnOpen = boolAt(tr, "syncWithEditorOnOpen", out.tree.syncWithEditorOnOpen);
        }
        Map<String, Object> hx = objectAt(root, "hex");
        if (hx != null) {
            out.hex.defaultRowWidth = intAt(hx, "defaultRowWidth", out.hex.defaultRowWidth);
            out.hex.offsetBase = enumAt(hx, "offsetBase", AppSettings.HexBase.class, out.hex.offsetBase);
            out.hex.showStructureTabByDefault = boolAt(hx, "showStructureTabByDefault", out.hex.showStructureTabByDefault);
            out.hex.defaultInspectorEndianness = enumAt(hx, "defaultInspectorEndianness", AppSettings.Endianness.class, out.hex.defaultInspectorEndianness);
        }
        Map<String, Object> jv = objectAt(root, "jvm");
        if (jv != null) {
            out.jvm.autoRefreshIntervalMs = intAt(jv, "autoRefreshIntervalMs", out.jvm.autoRefreshIntervalMs);
            out.jvm.importClassesOnAttachByDefault = boolAt(jv, "importClassesOnAttachByDefault", out.jvm.importClassesOnAttachByDefault);
        }
        Map<String, Object> tf = objectAt(root, "transformations");
        if (tf != null) {
            List<Object> passes = arrayAt(tf, "defaultSelectedPasses");
            if (passes != null) {
                out.transformations.defaultSelectedPasses = new LinkedHashSet<>();
                for (Object o : passes) {
                    if (o instanceof String s) out.transformations.defaultSelectedPasses.add(s);
                }
            }
        }
        Map<String, Object> ln = objectAt(root, "language");
        if (ln != null) {
            out.language.locale = stringAt(ln, "locale", out.language.locale);
            out.language.fallbackToEnglish = boolAt(ln, "fallbackToEnglish", out.language.fallbackToEnglish);
        }
        Map<String, Object> ps = objectAt(root, "paths");
        if (ps != null) {
            out.paths.agentDir = stringAt(ps, "agentDir", out.paths.agentDir);
            out.paths.recentLimit = intAt(ps, "recentLimit", out.paths.recentLimit);
            out.paths.pinnedLimit = intAt(ps, "pinnedLimit", out.paths.pinnedLimit);
        }
        Map<String, Object> ad = objectAt(root, "advanced");
        if (ad != null) {
            out.advanced.gcLogLevel = enumAt(ad, "gcLogLevel", AppSettings.LogLevel.class, out.advanced.gcLogLevel);
            out.advanced.javaHomeOverride = stringAt(ad, "javaHomeOverride", out.advanced.javaHomeOverride);
            out.advanced.maxClassParseThreads = intAt(ad, "maxClassParseThreads", out.advanced.maxClassParseThreads);
        }

        return out;
    }

    // --- Helpers -------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectAt(Map<String, Object> m, String key) {
        Object o = m.get(key);
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> arrayAt(Map<String, Object> m, String key) {
        Object o = m.get(key);
        return o instanceof List ? (List<Object>) o : null;
    }

    private static Number numberAt(Map<String, Object> m, String key) {
        Object o = m.get(key);
        return o instanceof Number n ? n : null;
    }

    private static int intAt(Map<String, Object> m, String key, int def) {
        Number n = numberAt(m, key);
        return n == null ? def : n.intValue();
    }

    private static double doubleAt(Map<String, Object> m, String key, double def) {
        Number n = numberAt(m, key);
        return n == null ? def : n.doubleValue();
    }

    private static boolean boolAt(Map<String, Object> m, String key, boolean def) {
        Object o = m.get(key);
        return o instanceof Boolean b ? b : def;
    }

    private static String stringAt(Map<String, Object> m, String key, String def) {
        Object o = m.get(key);
        return o instanceof String s ? s : def;
    }

    private static <E extends Enum<E>> E enumAt(Map<String, Object> m, String key, Class<E> cls, E def) {
        Object o = m.get(key);
        if (!(o instanceof String s)) return def;
        try { return Enum.valueOf(cls, s); }
        catch (IllegalArgumentException ex) { return def; }
    }

    private static String quote(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 2);
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

    /**
     * Mini JSON parser — enough for our flat structure (objects, arrays, strings, numbers,
     * booleans, nulls). Doesn't try to be conforming; returns {@code null} on any error so
     * the caller falls back to defaults.
     */
    static final class JsonMini {
        private final String src;
        private int pos;

        private JsonMini(String src) { this.src = src; }

        static Map<String, Object> parseObject(String src) {
            try {
                JsonMini p = new JsonMini(src);
                p.skipWs();
                Object o = p.value();
                return o instanceof Map<?, ?> m ? castMap(m) : null;
            } catch (Exception ex) {
                return null;
            }
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> castMap(Map<?, ?> m) { return (Map<String, Object>) m; }

        private Object value() {
            skipWs();
            if (pos >= src.length()) throw new IllegalStateException("eof");
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> parseObj();
                case '[' -> parseArr();
                case '"' -> parseStr();
                case 't', 'f' -> parseBool();
                case 'n' -> parseNull();
                default -> parseNum();
            };
        }

        private Map<String, Object> parseObj() {
            expect('{');
            Map<String, Object> out = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { pos++; return out; }
            while (true) {
                skipWs();
                String key = parseStr();
                skipWs();
                expect(':');
                Object v = value();
                out.put(key, v);
                skipWs();
                if (peek() == ',') { pos++; continue; }
                if (peek() == '}') { pos++; return out; }
                throw new IllegalStateException("expected , or }");
            }
        }

        private List<Object> parseArr() {
            expect('[');
            List<Object> out = new ArrayList<>();
            skipWs();
            if (peek() == ']') { pos++; return out; }
            while (true) {
                out.add(value());
                skipWs();
                if (peek() == ',') { pos++; continue; }
                if (peek() == ']') { pos++; return out; }
                throw new IllegalStateException("expected , or ]");
            }
        }

        private String parseStr() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\' && pos < src.length()) {
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            String hex = src.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> sb.append(esc);
                    }
                } else sb.append(c);
            }
            throw new IllegalStateException("unterminated string");
        }

        private Boolean parseBool() {
            if (src.startsWith("true", pos)) { pos += 4; return true; }
            if (src.startsWith("false", pos)) { pos += 5; return false; }
            throw new IllegalStateException("bad bool");
        }

        private Object parseNull() {
            if (src.startsWith("null", pos)) { pos += 4; return null; }
            throw new IllegalStateException("bad null");
        }

        private Number parseNum() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') pos++;
                else break;
            }
            String s = src.substring(start, pos);
            if (s.contains(".") || s.contains("e") || s.contains("E")) return Double.parseDouble(s);
            return Long.parseLong(s);
        }

        private void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }

        private char peek() { return pos < src.length() ? src.charAt(pos) : 0; }

        private void expect(char c) {
            if (peek() != c) throw new IllegalStateException("expected " + c + " at " + pos);
            pos++;
        }
    }
}
