package dev.share.bytecodelens.cli;

import dev.share.bytecodelens.decompile.AutoDecompiler;
import dev.share.bytecodelens.decompile.CfrDecompiler;
import dev.share.bytecodelens.decompile.ClassDecompiler;
import dev.share.bytecodelens.decompile.FallbackDecompiler;
import dev.share.bytecodelens.decompile.ProcyonDecompiler;
import dev.share.bytecodelens.decompile.VineflowerDecompiler;
import dev.share.bytecodelens.mapping.MappingFormat;
import dev.share.bytecodelens.mapping.MappingLoader;
import dev.share.bytecodelens.mapping.MappingModel;
import dev.share.bytecodelens.mapping.MappingOps;
import dev.share.bytecodelens.mapping.MappingWriter;
import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.LoadedJar;
import dev.share.bytecodelens.service.JarLoader;
import dev.share.bytecodelens.usage.UsageIndex;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Headless CLI for BytecodeLens. Parses argv, dispatches to one of:
 *
 * <ul>
 *   <li>{@code decompile <jar> -o <dir> [--engine cfr|vineflower|procyon|fallback|auto]}</li>
 *   <li>{@code analyze <jar> [--report-json out.json]}</li>
 *   <li>{@code mappings convert <input> --to <format> -o <output>}</li>
 *   <li>{@code mappings diff <a> <b> [--report-json out.json]}</li>
 * </ul>
 *
 * <p>Pure: every command takes inputs and writes to user-specified outputs (or stdout
 * for JSON). No JavaFX, no shared state. Designed to be unit-testable by passing in
 * a custom {@code PrintStream} for {@code out}/{@code err}.</p>
 */
public final class Cli {

    private final PrintStream out;
    private final PrintStream err;

    public Cli(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public Cli() { this(System.out, System.err); }

    /** Returns process exit code: 0 success, non-zero failure. */
    public int run(String[] args) {
        if (args.length == 0) {
            usage();
            return 0;
        }
        String cmd = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        try {
            return switch (cmd) {
                case "decompile" -> cmdDecompile(rest);
                case "analyze" -> cmdAnalyze(rest);
                case "mappings" -> cmdMappings(rest);
                case "help", "-h", "--help" -> { usage(); yield 0; }
                default -> {
                    err.println("Unknown command: " + cmd);
                    usage();
                    yield 2;
                }
            };
        } catch (CliException ex) {
            err.println("error: " + ex.getMessage());
            return 1;
        } catch (Throwable ex) {
            err.println("internal error: " + ex);
            return 3;
        }
    }

    // ============================================================
    // decompile
    // ============================================================

    private int cmdDecompile(String[] args) throws IOException {
        Path jarPath = null;
        Path outDir = null;
        String engineName = "auto";
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("-o") || a.equals("--out")) {
                outDir = Path.of(requireNext(args, i++, "--out"));
            } else if (a.equals("--engine")) {
                engineName = requireNext(args, i++, "--engine").toLowerCase(Locale.ROOT);
            } else if (!a.startsWith("-") && jarPath == null) {
                jarPath = Path.of(a);
            } else {
                throw new CliException("unexpected token: " + a);
            }
        }
        if (jarPath == null) throw new CliException("missing input jar");
        if (outDir == null) throw new CliException("missing -o/--out output directory");
        ClassDecompiler engine = pickEngine(engineName);

        LoadedJar jar = new JarLoader().load(jarPath, p -> {});
        Files.createDirectories(outDir);
        int total = jar.classes().size();
        int done = 0;
        int failed = 0;
        for (ClassEntry c : jar.classes()) {
            String javaPath = c.internalName() + ".java";
            Path target = outDir.resolve(javaPath);
            Files.createDirectories(target.getParent());
            String src;
            try {
                src = engine.decompile(c.internalName(), c.bytes());
            } catch (Throwable ex) {
                src = "// decompile failed: " + ex.getMessage();
                failed++;
            }
            Files.writeString(target, src == null ? "" : src, StandardCharsets.UTF_8);
            done++;
        }
        out.println("Decompiled " + done + "/" + total + " classes (" + failed + " failures) to " + outDir);
        return failed == 0 ? 0 : 4;
    }

    private ClassDecompiler pickEngine(String name) {
        return switch (name) {
            case "cfr" -> new CfrDecompiler();
            case "vineflower", "vf" -> new VineflowerDecompiler();
            case "procyon" -> new ProcyonDecompiler();
            case "fallback" -> new FallbackDecompiler();
            case "auto" -> new AutoDecompiler(List.of(
                    new CfrDecompiler(), new VineflowerDecompiler(),
                    new ProcyonDecompiler(), new FallbackDecompiler()));
            default -> throw new CliException("unknown engine: " + name);
        };
    }

    // ============================================================
    // analyze
    // ============================================================

    private int cmdAnalyze(String[] args) throws IOException {
        Path jarPath = null;
        Path reportJson = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--report-json")) {
                reportJson = Path.of(requireNext(args, i++, "--report-json"));
            } else if (!a.startsWith("-") && jarPath == null) {
                jarPath = Path.of(a);
            } else {
                throw new CliException("unexpected token: " + a);
            }
        }
        if (jarPath == null) throw new CliException("missing input jar");

        LoadedJar jar = new JarLoader().load(jarPath, p -> {});
        UsageIndex usage = new UsageIndex(jar);
        usage.build();

        // Collect summary counters into a deterministic map for JSON output.
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("source", jarPath.toString());
        report.put("classCount", jar.classCount());
        report.put("versionedClassCount", jar.versionedClassCount());
        report.put("resourceCount", jar.resourceCount());
        report.put("totalBytes", jar.totalBytes());
        report.put("loadTimeMs", jar.loadTimeMs());
        report.put("methodCallCount", usage.allMethodCalls().count());
        report.put("fieldAccessCount", usage.allFieldAccesses().count());
        report.put("classUseCount", usage.allClassUses().count());

        if (reportJson != null) {
            Files.writeString(reportJson, toJson(report), StandardCharsets.UTF_8);
            out.println("Wrote " + reportJson);
        } else {
            out.println(toHumanReadable(report));
        }
        return 0;
    }

    // ============================================================
    // mappings
    // ============================================================

    private int cmdMappings(String[] args) throws IOException {
        if (args.length == 0) throw new CliException("mappings: missing subcommand (convert|diff)");
        String sub = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        return switch (sub) {
            case "convert" -> mappingsConvert(rest);
            case "diff" -> mappingsDiff(rest);
            default -> throw new CliException("mappings: unknown subcommand " + sub);
        };
    }

    private int mappingsConvert(String[] args) throws IOException {
        Path inPath = null;
        Path outPath = null;
        String toFormat = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--to")) {
                toFormat = requireNext(args, i++, "--to").toUpperCase(Locale.ROOT);
            } else if (a.equals("-o") || a.equals("--out")) {
                outPath = Path.of(requireNext(args, i++, "--out"));
            } else if (!a.startsWith("-") && inPath == null) {
                inPath = Path.of(a);
            } else {
                throw new CliException("unexpected token: " + a);
            }
        }
        if (inPath == null) throw new CliException("missing input file");
        if (outPath == null) throw new CliException("missing -o/--out");
        if (toFormat == null) throw new CliException("missing --to <format>");
        MappingModel m = MappingLoader.load(inPath);
        MappingFormat target;
        try { target = MappingFormat.valueOf(toFormat); }
        catch (IllegalArgumentException e) { throw new CliException("unknown target format: " + toFormat); }
        MappingWriter.write(m, target, outPath);
        out.println("Converted " + m.sourceFormat() + " (" + m.classCount() + " classes) -> "
                + target + " at " + outPath);
        return 0;
    }

    private int mappingsDiff(String[] args) throws IOException {
        Path a = null, b = null;
        Path reportJson = null;
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String t = args[i];
            if (t.equals("--report-json")) {
                reportJson = Path.of(requireNext(args, i++, "--report-json"));
            } else if (!t.startsWith("-")) {
                positional.add(t);
            } else throw new CliException("unexpected token: " + t);
        }
        if (positional.size() < 2) throw new CliException("diff requires two mapping files");
        a = Path.of(positional.get(0));
        b = Path.of(positional.get(1));
        var diff = MappingOps.diff(MappingLoader.load(a), MappingLoader.load(b));

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("baseFile", a.toString());
        r.put("updatedFile", b.toString());
        r.put("classesAdded", diff.classesAdded().size());
        r.put("classesRemoved", diff.classesRemoved().size());
        r.put("classesRenamed", diff.classesRenamed().size());
        r.put("fieldsAdded", diff.fieldsAdded().size());
        r.put("fieldsRemoved", diff.fieldsRemoved().size());
        r.put("fieldsRenamed", diff.fieldsRenamed().size());
        r.put("methodsAdded", diff.methodsAdded().size());
        r.put("methodsRemoved", diff.methodsRemoved().size());
        r.put("methodsRenamed", diff.methodsRenamed().size());
        r.put("totalChanges", diff.totalChanges());

        if (reportJson != null) {
            Files.writeString(reportJson, toJson(r), StandardCharsets.UTF_8);
            out.println("Wrote " + reportJson);
        } else {
            out.println(toHumanReadable(r));
        }
        return 0;
    }

    // ============================================================
    // helpers
    // ============================================================

    private static String requireNext(String[] args, int i, String flag) {
        if (i + 1 >= args.length) throw new CliException("flag " + flag + " requires a value");
        return args[i + 1];
    }

    /**
     * Tiny JSON serializer for the flat {@code Map<String, Object>} reports. Strings get
     * quoted with minimal escaping; numbers render as-is. Avoids pulling in a JSON dep.
     */
    static String toJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : m.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v == null) sb.append("null");
            else if (v instanceof Number || v instanceof Boolean) sb.append(v);
            else sb.append('"').append(escape(v.toString())).append('"');
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
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
        return sb.toString();
    }

    private static String toHumanReadable(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder();
        int maxKey = m.keySet().stream().mapToInt(String::length).max().orElse(0);
        for (var e : m.entrySet()) {
            sb.append(String.format("%-" + maxKey + "s : %s%n", e.getKey(), e.getValue()));
        }
        return sb.toString();
    }

    private void usage() {
        out.println("BytecodeLens CLI");
        out.println();
        out.println("Usage:");
        out.println("  bytecodelens decompile <jar> -o <dir> [--engine cfr|vineflower|procyon|fallback|auto]");
        out.println("  bytecodelens analyze <jar> [--report-json <out.json>]");
        out.println("  bytecodelens mappings convert <input> --to <FORMAT> -o <output>");
        out.println("  bytecodelens mappings diff <a> <b> [--report-json <out.json>]");
        out.println();
        out.println("Mapping formats: PROGUARD TINY_V1 TINY_V2 SRG XSRG TSRG TSRG_V2 CSRG JOBF ENIGMA RECAF");
        out.println("Without arguments, the GUI is launched.");
    }

    public static final class CliException extends RuntimeException {
        public CliException(String msg) { super(msg); }
    }
}
