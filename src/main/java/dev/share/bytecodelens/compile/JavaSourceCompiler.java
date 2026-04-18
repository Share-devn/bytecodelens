package dev.share.bytecodelens.compile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal in-memory Java source compiler for stage 7a.
 *
 * <p>Takes a single .java source string plus a user-chosen target version, compiles it via
 * the system javac (must be run on a JDK, not a JRE) and returns bytecode for every
 * class the compiler emitted — top-level + nested. Classpath for references is the current
 * process classpath; widening it to cover a LoadedJar's classes comes in stage 7b.</p>
 *
 * <p>A compile failure returns {@code success=false} and the diagnostics; no exception is
 * thrown for user-visible errors.</p>
 */
public final class JavaSourceCompiler {

    private static final Logger log = LoggerFactory.getLogger(JavaSourceCompiler.class);

    public enum Level { ERROR, WARNING, NOTE }

    /** A single compiler diagnostic. Line/column are 1-based; {@code -1} means unknown. */
    public record CompileDiagnostic(
            Level level, long line, long column, String code, String message) {}

    /**
     * @param outputClasses map from JVM internal name ("com/foo/Bar") to compiled .class bytes.
     *                      Empty when compilation fails.
     * @param diagnostics   every diagnostic the compiler emitted, in encounter order
     * @param success       true iff the compiler reported no ERROR-level diagnostics
     */
    public record CompileResult(
            Map<String, byte[]> outputClasses,
            List<CompileDiagnostic> diagnostics,
            boolean success) {}

    /** Fallback release if the caller passes 0 or a negative number. */
    public static final int DEFAULT_RELEASE = 21;

    public CompileResult compile(String fileName, String source) {
        return compile(fileName, source, DEFAULT_RELEASE, null);
    }

    public CompileResult compile(String fileName, String source, int releaseVersion) {
        return compile(fileName, source, releaseVersion, null);
    }

    /**
     * Compile {@code source} with the given LoadedJar on the classpath, letting references
     * to other classes from that jar resolve. {@code contextJar} may be null for a
     * stdlib-only compile.
     *
     * <p>Automatically generates phantom stub classes for any unresolved references and
     * retries up to {@link #PHANTOM_MAX_PASSES} times. This lets the caller compile
     * decompiled-then-edited source that references classes the compiler can't see
     * (e.g. ones not present in {@code contextJar} or on the runtime classpath).</p>
     */
    public CompileResult compile(String fileName, String source, int releaseVersion,
                                 dev.share.bytecodelens.model.LoadedJar contextJar) {
        return compile(fileName, source, releaseVersion, contextJar,
                new java.util.HashMap<>(), 0);
    }

    /** Maximum rounds of phantom generation before we give up and return diagnostics. */
    public static final int PHANTOM_MAX_PASSES = 5;

    /** Recursive variant — {@code pass} tracks how many phantom rounds we've already done. */
    private CompileResult compile(String fileName, String source, int releaseVersion,
                                  dev.share.bytecodelens.model.LoadedJar contextJar,
                                  Map<String, byte[]> phantoms, int pass) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new CompileResult(Map.of(), List.of(new CompileDiagnostic(
                    Level.ERROR, -1, -1, "no-javac",
                    "Running under a JRE — no javac is available. Launch BytecodeLens on a JDK.")),
                    false);
        }

        DiagnosticCollector<JavaFileObject> diagnosticCollector = new DiagnosticCollector<>();
        InMemoryFileManager fileManager;
        StandardJavaFileManager std;
        try {
            std = compiler.getStandardFileManager(
                    diagnosticCollector, Locale.ENGLISH, StandardCharsets.UTF_8);
            fileManager = new InMemoryFileManager(std, contextJar, phantoms);
        } catch (Exception ex) {
            log.warn("Failed to create file manager", ex);
            return new CompileResult(Map.of(), List.of(new CompileDiagnostic(
                    Level.ERROR, -1, -1, "fm-init", ex.toString())), false);
        }

        // Attach the current process classpath so third-party libs present at BytecodeLens
        // runtime (JavaFX, RichTextFX, ASM, ...) are visible when the user recompiles a class
        // that mentions them. Without this, editing a UI class fails with
        // "package javafx.application does not exist".
        //
        // Harvest from THREE sources: java.class.path (flat jars), jdk.module.path (JavaFX
        // is delivered via --module-path by the Gradle JavaFX plugin), and already-resolved
        // modules in the boot ModuleLayer — the last one is the fallback that always works.
        try {
            java.util.LinkedHashSet<java.io.File> cpEntries = new java.util.LinkedHashSet<>();
            collectPathProperty("java.class.path", cpEntries);
            collectPathProperty("jdk.module.path", cpEntries);
            // All boot-layer resolved modules — usually catches user modules resolved from
            // --module-path, but won't catch unnamed-module loaded libs.
            for (java.lang.module.ResolvedModule m :
                    ModuleLayer.boot().configuration().modules()) {
                m.reference().location().ifPresent(uri -> {
                    try {
                        java.io.File f = new java.io.File(uri);
                        if (f.exists()) cpEntries.add(f);
                    } catch (IllegalArgumentException ignored) {}
                });
            }
            // Boot layer missed something? Probe a few well-known classes we ship with. Each
            // class that is actually loaded reveals its CodeSource, which points at the exact
            // jar on disk — this catches JavaFX (which lives in user ModuleLayer, not boot)
            // and any other dependency that slips through property-based discovery.
            String[] probeClasses = {
                    "javafx.application.Application",
                    "javafx.scene.Scene",
                    "javafx.scene.image.Image",
                    "javafx.scene.control.Button",
                    "javafx.fxml.FXMLLoader",
                    "org.fxmisc.richtext.CodeArea",
                    "org.kordamp.ikonli.javafx.FontIcon",
                    "atlantafx.base.theme.PrimerDark",
                    "org.controlsfx.control.PopOver",
                    "org.objectweb.asm.ClassWriter",
                    "org.benf.cfr.reader.api.CfrDriver",
                    "org.vineflower.decompiler.VineflowerDecompiler",
                    "com.strobel.decompiler.Decompiler"
            };
            for (String cname : probeClasses) {
                java.io.File f = jarOfClass(cname);
                if (f != null) cpEntries.add(f);
            }
            if (!cpEntries.isEmpty()) {
                std.setLocation(javax.tools.StandardLocation.CLASS_PATH,
                        new java.util.ArrayList<>(cpEntries));
                log.info("Compile classpath seeded with {} entries (first few: {})",
                        cpEntries.size(),
                        cpEntries.stream().limit(5).map(java.io.File::getName).toList());
            } else {
                log.warn("Compile classpath is empty — third-party refs will not resolve");
            }
        } catch (Exception ex) {
            log.warn("Could not seed runtime classpath: {}", ex.toString());
        }

        String safeName = fileName == null || fileName.isBlank() ? "Source.java" : fileName;
        SourceObject src = new SourceObject(safeName, source == null ? "" : source);
        // Use source/target instead of --release so non-stdlib classpath entries remain
        // visible (JavaFX, RichTextFX, etc). Release is source-level guarantee we don't need
        // for in-place edits of an already-loaded jar.
        int level = releaseVersion <= 0 ? DEFAULT_RELEASE : releaseVersion;
        List<String> options = new ArrayList<>(Arrays.asList(
                "-source", Integer.toString(level),
                "-target", Integer.toString(level),
                "-Xlint:none",
                "-proc:none"));

        boolean ok;
        try {
            ok = compiler.getTask(null, fileManager, diagnosticCollector, options, null,
                    List.of(src)).call();
        } catch (Throwable ex) {
            log.warn("Compilation threw", ex);
            return new CompileResult(fileManager.output(), convert(diagnosticCollector), false);
        } finally {
            try { fileManager.close(); } catch (IOException ignored) {}
        }

        List<CompileDiagnostic> converted = convert(diagnosticCollector);
        boolean success = ok && converted.stream().noneMatch(d -> d.level() == Level.ERROR);
        CompileResult current = new CompileResult(fileManager.output(), converted, success);

        // If we still have ERROR-level diagnostics and haven't exhausted phantom budget,
        // synthesize stubs for any unresolved references and try again.
        if (!success && pass < PHANTOM_MAX_PASSES) {
            String ownerPkg = extractPackage(source);
            Map<String, byte[]> newPhantoms =
                    new PhantomClassGenerator().generateFor(current, ownerPkg, source);
            // Only actually new ones — otherwise we loop forever if a phantom still fails.
            boolean progress = false;
            for (var e : newPhantoms.entrySet()) {
                if (!phantoms.containsKey(e.getKey())) {
                    phantoms.put(e.getKey(), e.getValue());
                    progress = true;
                }
            }
            if (progress) {
                log.info("Phantom pass {}: added {} new phantoms, retrying",
                        pass + 1, newPhantoms.size());
                return compile(fileName, source, releaseVersion, contextJar, phantoms, pass + 1);
            }
        }
        return current;
    }

    /** Grab the {@code package foo.bar;} declaration from source if any, else empty. */
    private static String extractPackage(String source) {
        if (source == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(source);
        return m.find() ? m.group(1) : "";
    }

    private static List<CompileDiagnostic> convert(DiagnosticCollector<JavaFileObject> dc) {
        List<CompileDiagnostic> out = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : dc.getDiagnostics()) {
            Level level = switch (d.getKind()) {
                case ERROR -> Level.ERROR;
                case WARNING, MANDATORY_WARNING -> Level.WARNING;
                default -> Level.NOTE;
            };
            out.add(new CompileDiagnostic(
                    level,
                    d.getLineNumber(),
                    d.getColumnNumber(),
                    d.getCode() == null ? "" : d.getCode(),
                    d.getMessage(Locale.ENGLISH)));
        }
        return out;
    }

    /** Locate the jar (or classes dir) that contains {@code dottedClassName} on the live classpath. */
    private static java.io.File jarOfClass(String dottedClassName) {
        try {
            Class<?> cls = Class.forName(dottedClassName, false,
                    JavaSourceCompiler.class.getClassLoader());
            var cs = cls.getProtectionDomain().getCodeSource();
            if (cs == null) return null;
            var loc = cs.getLocation();
            if (loc == null) return null;
            java.io.File f = new java.io.File(loc.toURI());
            return f.exists() ? f : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void collectPathProperty(String propName, java.util.Set<java.io.File> out) {
        String v = System.getProperty(propName);
        if (v == null || v.isEmpty()) return;
        for (String part : v.split(java.io.File.pathSeparator)) {
            java.io.File f = new java.io.File(part);
            if (f.exists()) out.add(f);
        }
    }

    /**
     * A JavaFileObject backed by an in-memory string — this is how we hand source to javac
     * without writing it to disk.
     */
    private static final class SourceObject extends javax.tools.SimpleJavaFileObject {
        private final String content;

        SourceObject(String name, String content) {
            super(URI.create("mem:///" + name), Kind.SOURCE);
            this.content = content;
        }

        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }
}
