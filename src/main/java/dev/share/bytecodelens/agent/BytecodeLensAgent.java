package dev.share.bytecodelens.agent;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Java agent loaded into a target JVM via {@link com.sun.tools.attach.VirtualMachine#loadAgent}.
 *
 * <p>After {@link #agentmain} is invoked by the target, the agent opens a TCP socket
 * (localhost) and serves a tiny binary protocol so the BytecodeLens UI can:</p>
 * <ul>
 *     <li>{@code LIST} — enumerate all loaded classes</li>
 *     <li>{@code GET} — fetch the bytes of a single class</li>
 *     <li>{@code REDEF} — redefine one class via {@link Instrumentation#redefineClasses}</li>
 *     <li>{@code BYE} — shut the listener down (agent stays loaded)</li>
 * </ul>
 *
 * <p>Protocol is deliberately minimal: 4-byte length-prefixed frames over a single TCP
 * connection. No TLS (localhost only), no auth (user must attach manually).</p>
 */
public final class BytecodeLensAgent {

    // Op codes — kept short on the wire to match the framing cost.
    public static final byte OP_LIST = 1;
    public static final byte OP_GET = 2;
    public static final byte OP_REDEF = 3;
    public static final byte OP_BYE = 4;
    /** Stream every loadable class as a sequence of (name, bytes) pairs, terminated by empty name. */
    public static final byte OP_DUMP_ALL = 5;
    /**
     * Snapshot of JVM state through ManagementFactory MXBeans — properties, runtime info,
     * class loading, compilation, GC, memory, threads, deadlocks. Returned as a single
     * JSON string so the UI can parse fields as it evolves without bumping the wire
     * format. Clients should expect forward-compatible JSON (extra fields).
     */
    public static final byte OP_JVM_STATE = 6;

    /** Status codes returned in response frames. */
    public static final byte STATUS_OK = 0;
    public static final byte STATUS_ERROR = 1;
    public static final byte STATUS_NOT_FOUND = 2;

    private static volatile Instrumentation inst;
    private static volatile ServerSocket serverSocket;

    private static java.io.PrintWriter agentLog;

    public static void agentmain(String args, Instrumentation instrumentation) throws Exception {
        inst = instrumentation;
        int port = parsePort(args);
        serverSocket = new ServerSocket(port, 1, java.net.InetAddress.getLoopbackAddress());
        writeDiscovery(serverSocket.getLocalPort());
        openLog();

        agentLog("agent started, pid=" + ProcessHandle.current().pid()
                + " port=" + serverSocket.getLocalPort());

        Thread t = new Thread(BytecodeLensAgent::runServer, "bytecodelens-agent-listener");
        t.setDaemon(true);
        t.start();
    }

    private static void openLog() {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(
                    System.getProperty("user.home"), ".bytecodelens", "agents");
            java.nio.file.Files.createDirectories(dir);
            long pid = ProcessHandle.current().pid();
            agentLog = new java.io.PrintWriter(new java.io.FileWriter(
                    dir.resolve(pid + ".log").toFile(), false), true);
        } catch (IOException ignored) {}
    }

    private static void agentLog(String msg) {
        if (agentLog != null) {
            agentLog.println(System.currentTimeMillis() + " " + msg);
            agentLog.flush();
        }
    }

    /** Optional {@code premain} so the agent can also be loaded via {@code -javaagent:}. */
    public static void premain(String args, Instrumentation instrumentation) throws Exception {
        agentmain(args, instrumentation);
    }

    private static int parsePort(String args) {
        if (args == null) return 0;
        try {
            for (String kv : args.split(",")) {
                if (kv.startsWith("port=")) return Integer.parseInt(kv.substring(5));
            }
        } catch (NumberFormatException ignored) {}
        return 0;
    }

    private static void writeDiscovery(int port) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(
                    System.getProperty("user.home"), ".bytecodelens", "agents");
            java.nio.file.Files.createDirectories(dir);
            long pid = ProcessHandle.current().pid();
            java.nio.file.Files.writeString(dir.resolve(pid + ".port"), Integer.toString(port));
        } catch (IOException ignored) {}
    }

    private static void runServer() {
        while (!serverSocket.isClosed()) {
            try (Socket sock = serverSocket.accept();
                 DataInputStream in = new DataInputStream(sock.getInputStream());
                 DataOutputStream out = new DataOutputStream(sock.getOutputStream())) {
                handleSession(in, out);
            } catch (IOException ex) {
                if (!serverSocket.isClosed()) {
                    // Log to stderr of the target process so the user can see issues if they
                    // care; agent-side slf4j isn't available here.
                    ex.printStackTrace(System.err);
                }
            }
        }
    }

    private static void handleSession(DataInputStream in, DataOutputStream out) throws IOException {
        while (true) {
            int op;
            try {
                op = in.readUnsignedByte();
            } catch (java.io.EOFException eof) {
                return;
            }
            switch (op) {
                case OP_LIST -> handleList(out);
                case OP_GET -> handleGet(in, out);
                case OP_REDEF -> handleRedef(in, out);
                case OP_DUMP_ALL -> handleDumpAll(out);
                case OP_JVM_STATE -> handleJvmState(out);
                case OP_BYE -> {
                    out.writeByte(STATUS_OK);
                    out.flush();
                    return;
                }
                default -> {
                    out.writeByte(STATUS_ERROR);
                    out.writeUTF("unknown op " + op);
                    out.flush();
                    return;
                }
            }
        }
    }

    private static void handleList(DataOutputStream out) throws IOException {
        long t0 = System.currentTimeMillis();
        java.util.Map<String, Class<?>> idx = buildClassIndex();
        classIndex.set(idx);
        agentLog("LIST built index of " + idx.size() + " classes in "
                + (System.currentTimeMillis() - t0) + "ms");

        out.writeByte(STATUS_OK);
        out.writeInt(idx.size());
        // Writing thousands of small UTFs into a 64KB socket buffer eventually blocks until
        // the client drains. The framing is fine — just be aware that the client must be
        // reading in parallel or with a large receive buffer, which DataInputStream is.
        for (String n : idx.keySet()) out.writeUTF(n);
        out.flush();
        agentLog("LIST wrote response");
    }

    /** Session-local map populated once per LIST and reused across subsequent GETs. */
    private static final ThreadLocal<java.util.Map<String, Class<?>>> classIndex = new ThreadLocal<>();

    private static void handleGet(DataInputStream in, DataOutputStream out) throws IOException {
        String dotted = in.readUTF();
        long t0 = System.currentTimeMillis();
        agentLog("GET " + dotted + " start");

        java.util.Map<String, Class<?>> idx = classIndex.get();
        if (idx == null) {
            idx = buildClassIndex();
            classIndex.set(idx);
        }

        Class<?> found = idx.get(dotted);
        if (found == null) {
            out.writeByte(STATUS_NOT_FOUND);
            out.writeUTF("class not loaded: " + dotted);
            out.flush();
            return;
        }

        byte[] bytes;
        try {
            bytes = fetchClassBytes(found);
        } catch (Throwable ex) {
            out.writeByte(STATUS_ERROR);
            out.writeUTF(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            out.flush();
            return;
        }
        if (bytes == null) {
            out.writeByte(STATUS_NOT_FOUND);
            out.writeUTF("no source for " + dotted);
            out.flush();
            return;
        }
        out.writeByte(STATUS_OK);
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
        long took = System.currentTimeMillis() - t0;
        if (took > 50) agentLog("GET " + dotted + " OK " + bytes.length + "b in " + took + "ms");
    }

    /**
     * Stream every loadable class as (name, len, bytes). Empty name marks end.
     *
     * <p>Strategy: parallel {@code getResourceAsStream} lookups with a per-class timeout.
     * Custom classloaders (Forge's LaunchClassLoader) hold locks on init that deadlock a
     * straight-line loop, so each lookup runs on a worker thread and times out after
     * {@link #PER_CLASS_TIMEOUT_MS}. Classes that can't be fetched in that window are
     * skipped — better to ship 80% of classes quickly than to hang forever.</p>
     */
    private static final long PER_CLASS_TIMEOUT_MS = 500;

    private static void handleDumpAll(DataOutputStream out) throws IOException {
        Class<?>[] all = inst.getAllLoadedClasses();
        java.util.List<Class<?>> modifiable = new java.util.ArrayList<>(all.length);
        for (Class<?> c : all) {
            if (c.isPrimitive() || c.isArray()) continue;
            if (!inst.isModifiableClass(c)) continue;
            // Skip JDK internals — they waste retransform time and user doesn't care.
            String n = c.getName();
            if (n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("jdk.")
                    || n.startsWith("sun.") || n.startsWith("com.sun.")) continue;
            if (n.contains("$$Lambda/") || n.contains("$$Lambda$")) continue; // synthetic lambda
            modifiable.add(c);
        }
        agentLog("DUMP_ALL selected " + modifiable.size() + " modifiable classes from "
                + all.length + " total");

        out.writeByte(STATUS_OK);

        int sent = 0;
        int timedOut = 0;
        int failed = 0;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                4, r -> {
                    Thread t = new Thread(r, "bytecodelens-agent-fetcher");
                    t.setDaemon(true);
                    return t;
                });
        try {
            for (int i = 0; i < modifiable.size(); i++) {
                Class<?> cls = modifiable.get(i);
                String name;
                try {
                    name = cls.getName();
                } catch (Throwable nameEx) {
                    failed++;
                    continue;
                }
                final Class<?> captured = cls;
                java.util.concurrent.Future<byte[]> fut = pool.submit(() -> {
                    String resource = captured.getName().replace('.', '/') + ".class";
                    ClassLoader cl = captured.getClassLoader();
                    if (cl == null) return null;
                    try (var is = cl.getResourceAsStream(resource)) {
                        return is == null ? null : is.readAllBytes();
                    }
                });
                byte[] bytes;
                try {
                    bytes = fut.get(PER_CLASS_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException te) {
                    fut.cancel(true);
                    timedOut++;
                    if (timedOut <= 10 || timedOut % 100 == 0) {
                        agentLog("TIMEOUT " + name);
                    }
                    continue;
                } catch (Throwable ex) {
                    failed++;
                    continue;
                }
                if (bytes == null || bytes.length == 0) {
                    failed++;
                    continue;
                }
                try {
                    out.writeUTF(name);
                    out.writeInt(bytes.length);
                    out.write(bytes);
                    sent++;
                } catch (IOException ioe) {
                    agentLog("socket write failed at " + name + ": " + ioe);
                    throw ioe;
                }
                if (sent % 500 == 0) {
                    out.flush();
                    agentLog("DUMP_ALL progress: i=" + (i + 1) + "/" + modifiable.size()
                            + " sent=" + sent + " timedOut=" + timedOut + " failed=" + failed);
                }
            }
            out.writeUTF("");
            out.writeInt(0);
            out.flush();
            agentLog("DUMP_ALL done: sent=" + sent + " timedOut=" + timedOut + " failed=" + failed);
        } catch (Throwable fatal) {
            agentLog("DUMP_ALL FATAL after sent=" + sent + ", timedOut=" + timedOut + ": " + fatal);
            java.io.StringWriter sw = new java.io.StringWriter();
            fatal.printStackTrace(new java.io.PrintWriter(sw));
            agentLog(sw.toString());
            try {
                out.writeUTF("");
                out.writeInt(0);
                out.flush();
            } catch (Throwable ignored) {}
            if (fatal instanceof IOException io) throw io;
            if (fatal instanceof RuntimeException rt) throw rt;
            throw new IOException(fatal);
        } finally {
            pool.shutdownNow();
        }
    }

    private static java.util.Map<String, Class<?>> buildClassIndex() {
        Class<?>[] all = inst.getAllLoadedClasses();
        java.util.Map<String, Class<?>> idx = new java.util.HashMap<>(all.length);
        for (Class<?> c : all) {
            if (c.isPrimitive() || c.isArray()) continue;
            idx.put(c.getName(), c);
        }
        return idx;
    }

    /**
     * Resolve a class's current bytecode via the classloader's resource stream.
     *
     * <p>We deliberately do <em>not</em> fall back to a retransform-trick for bootstrap /
     * system classes, because retransforming even a single {@code java.lang.*} class can
     * take hundreds of milliseconds and kills the classloader's JIT state. For a live
     * session with thousands of classes that becomes a 10-minute stall. Callers should
     * filter bootstrap classes out on their side instead.</p>
     */
    private static byte[] fetchClassBytes(Class<?> cls) throws Exception {
        String resource = cls.getName().replace('.', '/') + ".class";
        ClassLoader cl = cls.getClassLoader();
        if (cl == null) return null; // bootstrap — not fetchable without retransform (too slow)
        try (var is = cl.getResourceAsStream(resource)) {
            if (is == null) return null;
            return is.readAllBytes();
        }
    }

    private static void handleRedef(DataInputStream in, DataOutputStream out) throws IOException {
        String dotted = in.readUTF();
        int len = in.readInt();
        byte[] newBytes = in.readNBytes(len);
        Class<?> target = null;
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.getName().equals(dotted)) { target = c; break; }
        }
        if (target == null) {
            out.writeByte(STATUS_NOT_FOUND);
            out.flush();
            return;
        }
        try {
            inst.redefineClasses(new ClassDefinition(target, newBytes));
            out.writeByte(STATUS_OK);
        } catch (UnmodifiableClassException | ClassNotFoundException | LinkageError ex) {
            out.writeByte(STATUS_ERROR);
            out.writeUTF(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        out.flush();
    }

    // ========================================================================
    // OP_JVM_STATE — serialise every interesting MXBean into a single JSON string.
    // We hand-roll JSON because the agent JAR must stay dependency-free (no Jackson,
    // no Gson) and the value set is small + flat. Client-side parser in
    // AttachClient.fetchJvmState handles the reverse.
    // ========================================================================

    private static void handleJvmState(DataOutputStream out) throws IOException {
        String json;
        try {
            json = buildJvmStateJson();
        } catch (Throwable t) {
            agentLog("JVM_STATE failed: " + t);
            out.writeByte(STATUS_ERROR);
            out.writeUTF(t.getClass().getSimpleName() + ": " + t.getMessage());
            out.flush();
            return;
        }
        out.writeByte(STATUS_OK);
        // Use writeUTF would cap at 64KB — many JVMs produce >64KB of JSON (lots of
        // threads). Write length-prefixed bytes as UTF-8 so there's no cap.
        byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
    }

    /** Build the one big JSON document describing current JVM state. */
    private static String buildJvmStateJson() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append('{');
        appendRuntime(sb);
        sb.append(',');
        appendClassLoading(sb);
        sb.append(',');
        appendCompilation(sb);
        sb.append(',');
        appendOperatingSystem(sb);
        sb.append(',');
        appendMemory(sb);
        sb.append(',');
        appendMemoryPools(sb);
        sb.append(',');
        appendGc(sb);
        sb.append(',');
        appendThreads(sb);
        sb.append(',');
        appendProperties(sb);
        sb.append('}');
        return sb.toString();
    }

    private static void appendRuntime(StringBuilder sb) {
        java.lang.management.RuntimeMXBean r = java.lang.management.ManagementFactory.getRuntimeMXBean();
        sb.append("\"runtime\":{");
        kv(sb, "name", r.getName()); sb.append(',');
        kv(sb, "vmName", r.getVmName()); sb.append(',');
        kv(sb, "vmVendor", r.getVmVendor()); sb.append(',');
        kv(sb, "vmVersion", r.getVmVersion()); sb.append(',');
        kv(sb, "specName", r.getSpecName()); sb.append(',');
        kv(sb, "specVersion", r.getSpecVersion()); sb.append(',');
        kv(sb, "specVendor", r.getSpecVendor()); sb.append(',');
        kv(sb, "classPath", r.getClassPath()); sb.append(',');
        kv(sb, "libraryPath", r.getLibraryPath()); sb.append(',');
        kv(sb, "bootClassPath", safeBoot(r)); sb.append(',');
        kvRaw(sb, "bootClassPathSupported", r.isBootClassPathSupported()); sb.append(',');
        kvRaw(sb, "startTime", r.getStartTime()); sb.append(',');
        kvRaw(sb, "uptime", r.getUptime()); sb.append(',');
        kvRaw(sb, "pid", ProcessHandle.current().pid()); sb.append(',');
        sb.append("\"inputArguments\":[");
        List<String> args = r.getInputArguments();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(quote(args.get(i)));
        }
        sb.append("]}");
    }

    private static String safeBoot(java.lang.management.RuntimeMXBean r) {
        try { return r.getBootClassPath(); } catch (Throwable t) { return "?"; }
    }

    private static void appendClassLoading(StringBuilder sb) {
        java.lang.management.ClassLoadingMXBean c =
                java.lang.management.ManagementFactory.getClassLoadingMXBean();
        sb.append("\"classLoading\":{");
        kvRaw(sb, "loadedClassCount", c.getLoadedClassCount()); sb.append(',');
        kvRaw(sb, "totalLoadedClassCount", c.getTotalLoadedClassCount()); sb.append(',');
        kvRaw(sb, "unloadedClassCount", c.getUnloadedClassCount()); sb.append(',');
        kvRaw(sb, "verbose", c.isVerbose());
        sb.append('}');
    }

    private static void appendCompilation(StringBuilder sb) {
        java.lang.management.CompilationMXBean c =
                java.lang.management.ManagementFactory.getCompilationMXBean();
        sb.append("\"compilation\":{");
        if (c == null) { sb.append("\"available\":false}"); return; }
        kvRaw(sb, "available", true); sb.append(',');
        kv(sb, "name", c.getName()); sb.append(',');
        boolean supported = c.isCompilationTimeMonitoringSupported();
        kvRaw(sb, "timeMonitoringSupported", supported); sb.append(',');
        kvRaw(sb, "totalCompilationTimeMs", supported ? c.getTotalCompilationTime() : -1);
        sb.append('}');
    }

    private static void appendOperatingSystem(StringBuilder sb) {
        java.lang.management.OperatingSystemMXBean os =
                java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        sb.append("\"os\":{");
        kv(sb, "name", os.getName()); sb.append(',');
        kv(sb, "arch", os.getArch()); sb.append(',');
        kv(sb, "version", os.getVersion()); sb.append(',');
        kvRaw(sb, "availableProcessors", os.getAvailableProcessors()); sb.append(',');
        kvRaw(sb, "systemLoadAverage", os.getSystemLoadAverage());
        // Try com.sun.management.OperatingSystemMXBean for detailed memory metrics —
        // it's an internal API but present on HotSpot/OpenJ9 everywhere.
        try {
            com.sun.management.OperatingSystemMXBean sun =
                    (com.sun.management.OperatingSystemMXBean) os;
            sb.append(',');
            kvRaw(sb, "freeMemorySize", sun.getFreeMemorySize()); sb.append(',');
            kvRaw(sb, "totalMemorySize", sun.getTotalMemorySize()); sb.append(',');
            kvRaw(sb, "committedVirtualMemorySize", sun.getCommittedVirtualMemorySize()); sb.append(',');
            kvRaw(sb, "freeSwapSpaceSize", sun.getFreeSwapSpaceSize()); sb.append(',');
            kvRaw(sb, "totalSwapSpaceSize", sun.getTotalSwapSpaceSize()); sb.append(',');
            kvRaw(sb, "processCpuTime", sun.getProcessCpuTime()); sb.append(',');
            kvRaw(sb, "cpuLoad", sun.getCpuLoad()); sb.append(',');
            kvRaw(sb, "processCpuLoad", sun.getProcessCpuLoad());
        } catch (Throwable ignored) {}
        sb.append('}');
    }

    private static void appendMemory(StringBuilder sb) {
        java.lang.management.MemoryMXBean m =
                java.lang.management.ManagementFactory.getMemoryMXBean();
        sb.append("\"memory\":{");
        java.lang.management.MemoryUsage heap = m.getHeapMemoryUsage();
        java.lang.management.MemoryUsage nonHeap = m.getNonHeapMemoryUsage();
        sb.append("\"heap\":"); appendMemoryUsage(sb, heap); sb.append(',');
        sb.append("\"nonHeap\":"); appendMemoryUsage(sb, nonHeap); sb.append(',');
        kvRaw(sb, "objectPendingFinalizationCount", m.getObjectPendingFinalizationCount());
        sb.append('}');
    }

    private static void appendMemoryUsage(StringBuilder sb, java.lang.management.MemoryUsage u) {
        sb.append('{');
        kvRaw(sb, "init", u.getInit()); sb.append(',');
        kvRaw(sb, "used", u.getUsed()); sb.append(',');
        kvRaw(sb, "committed", u.getCommitted()); sb.append(',');
        kvRaw(sb, "max", u.getMax());
        sb.append('}');
    }

    private static void appendMemoryPools(StringBuilder sb) {
        List<java.lang.management.MemoryPoolMXBean> pools =
                java.lang.management.ManagementFactory.getMemoryPoolMXBeans();
        sb.append("\"memoryPools\":[");
        for (int i = 0; i < pools.size(); i++) {
            if (i > 0) sb.append(',');
            java.lang.management.MemoryPoolMXBean p = pools.get(i);
            sb.append('{');
            kv(sb, "name", p.getName()); sb.append(',');
            kv(sb, "type", p.getType().name()); sb.append(',');
            sb.append("\"usage\":"); appendMemoryUsage(sb, p.getUsage());
            java.lang.management.MemoryUsage peak = p.getPeakUsage();
            if (peak != null) { sb.append(','); sb.append("\"peak\":"); appendMemoryUsage(sb, peak); }
            sb.append('}');
        }
        sb.append(']');
    }

    private static void appendGc(StringBuilder sb) {
        List<java.lang.management.GarbageCollectorMXBean> gcs =
                java.lang.management.ManagementFactory.getGarbageCollectorMXBeans();
        sb.append("\"gc\":[");
        for (int i = 0; i < gcs.size(); i++) {
            if (i > 0) sb.append(',');
            java.lang.management.GarbageCollectorMXBean g = gcs.get(i);
            sb.append('{');
            kv(sb, "name", g.getName()); sb.append(',');
            kvRaw(sb, "collectionCount", g.getCollectionCount()); sb.append(',');
            kvRaw(sb, "collectionTimeMs", g.getCollectionTime());
            sb.append('}');
        }
        sb.append(']');
    }

    private static void appendThreads(StringBuilder sb) {
        java.lang.management.ThreadMXBean t =
                java.lang.management.ManagementFactory.getThreadMXBean();
        sb.append("\"threads\":{");
        kvRaw(sb, "threadCount", t.getThreadCount()); sb.append(',');
        kvRaw(sb, "peakThreadCount", t.getPeakThreadCount()); sb.append(',');
        kvRaw(sb, "daemonThreadCount", t.getDaemonThreadCount()); sb.append(',');
        kvRaw(sb, "totalStartedThreadCount", t.getTotalStartedThreadCount());
        // Deadlock detection — surfaces a list of thread ids that are deadlocked.
        long[] deadlocked = t.findDeadlockedThreads();
        sb.append(",\"deadlocked\":[");
        if (deadlocked != null) {
            for (int i = 0; i < deadlocked.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(deadlocked[i]);
            }
        }
        sb.append("]");
        // Stack traces — cap at 50 frames per thread to bound payload size. Even very
        // busy apps produce JSON under a few MB at this cap.
        java.lang.management.ThreadInfo[] infos = t.dumpAllThreads(false, false);
        sb.append(",\"list\":[");
        for (int i = 0; i < infos.length; i++) {
            java.lang.management.ThreadInfo ti = infos[i];
            if (i > 0) sb.append(',');
            sb.append('{');
            kvRaw(sb, "id", ti.getThreadId()); sb.append(',');
            kv(sb, "name", ti.getThreadName()); sb.append(',');
            kv(sb, "state", ti.getThreadState().name()); sb.append(',');
            kvRaw(sb, "blockedCount", ti.getBlockedCount()); sb.append(',');
            kvRaw(sb, "waitedCount", ti.getWaitedCount()); sb.append(',');
            sb.append("\"stack\":[");
            StackTraceElement[] stack = ti.getStackTrace();
            int max = Math.min(stack.length, 50);
            for (int j = 0; j < max; j++) {
                if (j > 0) sb.append(',');
                sb.append(quote(stack[j].toString()));
            }
            sb.append("]}");
        }
        sb.append("]}");
    }

    private static void appendProperties(StringBuilder sb) {
        java.util.Properties p = System.getProperties();
        sb.append("\"systemProperties\":{");
        java.util.List<String> keys = new java.util.ArrayList<>(p.stringPropertyNames());
        java.util.Collections.sort(keys);
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(',');
            kv(sb, keys.get(i), p.getProperty(keys.get(i)));
        }
        sb.append('}');
    }

    // JSON primitives — no dependency on Jackson so the agent JAR stays tiny.
    private static void kv(StringBuilder sb, String key, String value) {
        sb.append(quote(key)).append(':').append(quote(value == null ? "" : value));
    }

    private static void kvRaw(StringBuilder sb, String key, Object value) {
        sb.append(quote(key)).append(':').append(value);
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
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
