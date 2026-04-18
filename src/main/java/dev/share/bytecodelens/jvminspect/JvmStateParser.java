package dev.share.bytecodelens.jvminspect;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decode the JSON snapshot produced by {@link dev.share.bytecodelens.agent.BytecodeLensAgent#handleJvmState}
 * into a typed {@link JvmStateSnapshot}. Hand-rolled to keep the agent jar self-contained
 * (no Jackson leak into the client path) and to stay forward-compatible with unknown
 * fields — unknown keys are simply ignored.
 */
public final class JvmStateParser {

    public static JvmStateSnapshot parse(String json) throws IOException {
        Object parsed = new Lexer(new StringReader(json)).value();
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new IOException("JVM state JSON must be an object");
        }
        return new JvmStateSnapshot(
                System.currentTimeMillis(),
                parseRuntime(asMap(root.get("runtime"))),
                parseClassLoading(asMap(root.get("classLoading"))),
                parseCompilation(asMap(root.get("compilation"))),
                parseOs(asMap(root.get("os"))),
                parseMemory(asMap(root.get("memory"))),
                parseMemoryPools(asList(root.get("memoryPools"))),
                parseGc(asList(root.get("gc"))),
                parseThreads(asMap(root.get("threads"))),
                parseProps(asMap(root.get("systemProperties"))));
    }

    // --- per-section decoders ------------------------------------------------

    private static JvmStateSnapshot.Runtime parseRuntime(Map<String, Object> m) {
        if (m == null) return null;
        return new JvmStateSnapshot.Runtime(
                str(m, "name"), str(m, "vmName"), str(m, "vmVendor"), str(m, "vmVersion"),
                str(m, "specName"), str(m, "specVersion"), str(m, "specVendor"),
                str(m, "classPath"), str(m, "libraryPath"), str(m, "bootClassPath"),
                bool(m, "bootClassPathSupported", false),
                lng(m, "startTime", 0), lng(m, "uptime", 0), lng(m, "pid", 0),
                strList(asList(m.get("inputArguments"))));
    }

    private static JvmStateSnapshot.ClassLoading parseClassLoading(Map<String, Object> m) {
        if (m == null) return null;
        return new JvmStateSnapshot.ClassLoading(
                lng(m, "loadedClassCount", 0),
                lng(m, "totalLoadedClassCount", 0),
                lng(m, "unloadedClassCount", 0),
                bool(m, "verbose", false));
    }

    private static JvmStateSnapshot.Compilation parseCompilation(Map<String, Object> m) {
        if (m == null) return null;
        return new JvmStateSnapshot.Compilation(
                bool(m, "available", false),
                str(m, "name"),
                bool(m, "timeMonitoringSupported", false),
                lng(m, "totalCompilationTimeMs", 0));
    }

    private static JvmStateSnapshot.OperatingSystem parseOs(Map<String, Object> m) {
        if (m == null) return null;
        return new JvmStateSnapshot.OperatingSystem(
                str(m, "name"), str(m, "arch"), str(m, "version"),
                (int) lng(m, "availableProcessors", 0),
                dbl(m, "systemLoadAverage", -1),
                lng(m, "freeMemorySize", -1), lng(m, "totalMemorySize", -1),
                lng(m, "committedVirtualMemorySize", -1),
                lng(m, "freeSwapSpaceSize", -1), lng(m, "totalSwapSpaceSize", -1),
                lng(m, "processCpuTime", -1),
                dbl(m, "cpuLoad", -1), dbl(m, "processCpuLoad", -1));
    }

    private static JvmStateSnapshot.Memory parseMemory(Map<String, Object> m) {
        if (m == null) return null;
        return new JvmStateSnapshot.Memory(
                parseMemoryUsage(asMap(m.get("heap"))),
                parseMemoryUsage(asMap(m.get("nonHeap"))),
                lng(m, "objectPendingFinalizationCount", 0));
    }

    private static JvmStateSnapshot.MemoryUsage parseMemoryUsage(Map<String, Object> m) {
        if (m == null) return null;
        return new JvmStateSnapshot.MemoryUsage(
                lng(m, "init", 0), lng(m, "used", 0),
                lng(m, "committed", 0), lng(m, "max", -1));
    }

    private static List<JvmStateSnapshot.MemoryPool> parseMemoryPools(List<Object> list) {
        List<JvmStateSnapshot.MemoryPool> out = new ArrayList<>();
        if (list == null) return out;
        for (Object o : list) {
            Map<String, Object> m = asMap(o);
            if (m == null) continue;
            out.add(new JvmStateSnapshot.MemoryPool(
                    str(m, "name"), str(m, "type"),
                    parseMemoryUsage(asMap(m.get("usage"))),
                    parseMemoryUsage(asMap(m.get("peak")))));
        }
        return out;
    }

    private static List<JvmStateSnapshot.GcStats> parseGc(List<Object> list) {
        List<JvmStateSnapshot.GcStats> out = new ArrayList<>();
        if (list == null) return out;
        for (Object o : list) {
            Map<String, Object> m = asMap(o);
            if (m == null) continue;
            out.add(new JvmStateSnapshot.GcStats(
                    str(m, "name"),
                    lng(m, "collectionCount", 0),
                    lng(m, "collectionTimeMs", 0)));
        }
        return out;
    }

    private static JvmStateSnapshot.Threads parseThreads(Map<String, Object> m) {
        if (m == null) return null;
        List<Long> deadlocks = new ArrayList<>();
        if (m.get("deadlocked") instanceof List<?> dl) {
            for (Object o : dl) if (o instanceof Number n) deadlocks.add(n.longValue());
        }
        List<JvmStateSnapshot.ThreadInfo> threads = new ArrayList<>();
        if (m.get("list") instanceof List<?> tl) {
            for (Object o : tl) {
                Map<String, Object> tm = asMap(o);
                if (tm == null) continue;
                threads.add(new JvmStateSnapshot.ThreadInfo(
                        lng(tm, "id", 0),
                        str(tm, "name"),
                        str(tm, "state"),
                        lng(tm, "blockedCount", 0),
                        lng(tm, "waitedCount", 0),
                        strList(asList(tm.get("stack")))));
            }
        }
        return new JvmStateSnapshot.Threads(
                (int) lng(m, "threadCount", 0),
                (int) lng(m, "peakThreadCount", 0),
                (int) lng(m, "daemonThreadCount", 0),
                lng(m, "totalStartedThreadCount", 0),
                deadlocks, threads);
    }

    private static Map<String, String> parseProps(Map<String, Object> m) {
        Map<String, String> out = new LinkedHashMap<>();
        if (m == null) return out;
        for (var e : m.entrySet()) {
            out.put(e.getKey(), e.getValue() == null ? "" : e.getValue().toString());
        }
        return out;
    }

    // --- accessors -----------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    private static long lng(Map<String, Object> m, String k, long fallback) {
        Object v = m.get(k);
        return v instanceof Number n ? n.longValue() : fallback;
    }

    private static double dbl(Map<String, Object> m, String k, double fallback) {
        Object v = m.get(k);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    private static boolean bool(Map<String, Object> m, String k, boolean fallback) {
        Object v = m.get(k);
        return v instanceof Boolean b ? b : fallback;
    }

    private static List<String> strList(List<Object> list) {
        List<String> out = new ArrayList<>();
        if (list == null) return out;
        for (Object o : list) if (o != null) out.add(o.toString());
        return out;
    }

    // --- tiny JSON lexer -----------------------------------------------------

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
            next();
            skipWs();
            if (peek == '}') { next(); return out; }
            while (true) {
                skipWs();
                String key = str();
                skipWs();
                if (peek != ':') throw new IOException("Expected ':'");
                next();
                out.put(key, value());
                skipWs();
                if (peek == ',') { next(); continue; }
                if (peek == '}') { next(); return out; }
                throw new IOException("Expected ',' or '}'");
            }
        }

        List<Object> arr() throws IOException {
            List<Object> out = new ArrayList<>();
            next();
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
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            char[] hex = new char[4];
                            for (int i = 0; i < 4; i++) { next(); hex[i] = (char) peek; }
                            sb.append((char) Integer.parseInt(new String(hex), 16));
                        }
                        default -> throw new IOException("Bad escape \\" + (char) peek);
                    }
                    next();
                } else { sb.append((char) peek); next(); }
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
                if (peek != s.charAt(i)) throw new IOException("Expected " + s);
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

    private JvmStateParser() {}
}
