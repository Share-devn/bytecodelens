package dev.share.bytecodelens.compile;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks at javac diagnostics, extracts unresolved references, and generates empty "phantom"
 * classes on the fly so the next compile pass stops complaining about them.
 *
 * <p>This mirrors what Recaf does to make decompiled-then-recompiled source actually build
 * even when the source references other classes we don't have at hand (library classes,
 * classes from a different jar, etc).</p>
 *
 * <p>Phantoms are deliberately minimal: an empty public class body. They satisfy the
 * compiler for resolution purposes without affecting runtime behaviour of the edited
 * class — the original bytecode references unchanged symbols, and JVM resolves them at
 * load time, not at our compile time.</p>
 */
public final class PhantomClassGenerator {

    private static final Logger log = LoggerFactory.getLogger(PhantomClassGenerator.class);

    // "cannot find symbol" with "symbol: class Foo" — bare class name from javac.
    private static final Pattern SYMBOL_CLASS = Pattern.compile(
            "symbol:\\s+class\\s+([\\w.$]+)");
    // "package foo.bar does not exist" — capture the package so we can stub a type in it.
    private static final Pattern MISSING_PACKAGE = Pattern.compile(
            "package ([\\w.]+) does not exist");
    // "symbol: variable Foo" — sometimes an unresolved class shows up here because
    // Foo.STATIC_FIELD looks like a variable reference to the parser.
    private static final Pattern SYMBOL_VARIABLE = Pattern.compile(
            "symbol:\\s+variable\\s+([\\w.$]+)");
    // Dotted type reference — catches "com.unknown.Thing" in any part of the diagnostic
    // message even when the "symbol:" line reports only the bare last segment.
    // Package parts must start with lowercase; last segment with upper-case (class convention).
    private static final Pattern DOTTED_TYPE = Pattern.compile(
            "\\b([a-z][\\w]*(?:\\.[a-z][\\w]*)+\\.[A-Z][\\w$]*)");

    /**
     * Inspect {@code result.diagnostics()} and return a map of internal-name -> phantom .class bytes
     * for every unresolved class we can reasonably stub. Caller adds these to the compile context
     * and retries.
     */
    public Map<String, byte[]> generateFor(JavaSourceCompiler.CompileResult result,
                                            String ownerPackage) {
        return generateFor(result, ownerPackage, "");
    }

    /**
     * Same as {@link #generateFor(JavaSourceCompiler.CompileResult, String)} but with the
     * original source text — lets us extract fully-qualified type names when javac reports
     * only the package part ({@code "package com.unknown does not exist"}).
     */
    public Map<String, byte[]> generateFor(JavaSourceCompiler.CompileResult result,
                                           String ownerPackage, String source) {
        Map<String, byte[]> phantoms = new LinkedHashMap<>();
        java.util.LinkedHashSet<String> missing = new java.util.LinkedHashSet<>();

        for (var d : result.diagnostics()) {
            if (d.level() != JavaSourceCompiler.Level.ERROR) continue;
            String msg = d.message();
            if (msg == null) continue;

            // Dotted form first — most specific (covers com.foo.Bar in any part of the msg).
            Matcher m0 = DOTTED_TYPE.matcher(msg);
            while (m0.find()) addMissing(missing, m0.group(1), ownerPackage);

            Matcher m1 = SYMBOL_CLASS.matcher(msg);
            while (m1.find()) addMissing(missing, m1.group(1), ownerPackage);

            Matcher m2 = SYMBOL_VARIABLE.matcher(msg);
            while (m2.find()) {
                String v = m2.group(1);
                if (v.length() > 0 && Character.isUpperCase(v.charAt(0))) {
                    addMissing(missing, v, ownerPackage);
                }
            }

            // "package com.unknown does not exist" — find all `com.unknown.<Type>` patterns
            // in the source and stub each one. javac only complains about the package once
            // even when many distinct types under it are used.
            Matcher m3 = MISSING_PACKAGE.matcher(msg);
            while (m3.find()) {
                String pkg = m3.group(1);
                if (source != null && !source.isEmpty()) {
                    Matcher pkgTypes = Pattern.compile(
                            "\\b" + Pattern.quote(pkg) + "\\.([A-Z][\\w$]*)").matcher(source);
                    while (pkgTypes.find()) {
                        addMissing(missing, pkg + "." + pkgTypes.group(1), ownerPackage);
                    }
                }
            }
        }

        for (String internal : missing) {
            byte[] bytes = buildPhantom(internal);
            phantoms.put(internal, bytes);
        }
        if (!phantoms.isEmpty()) {
            log.info("Generated {} phantom classes: {}", phantoms.size(), phantoms.keySet());
        }
        return phantoms;
    }

    private static void addMissing(java.util.Set<String> out, String name, String ownerPackage) {
        if (name == null || name.isEmpty()) return;
        String dotted = name.replace('$', '.');
        // Qualify bare names to the owner's package so javac resolves them the same way a user would.
        String internal;
        if (dotted.contains(".")) {
            internal = dotted.replace('.', '/');
        } else if (ownerPackage == null || ownerPackage.isEmpty()) {
            internal = dotted;
        } else {
            internal = ownerPackage.replace('.', '/') + "/" + dotted;
        }
        // Never stub JDK bootstrap or well-known runtime packages. If javac can't see
        // JavaFX or similar, that's a compile classpath problem, not something phantoms
        // should paper over — a real phantom of javafx.scene.image.Image would silently
        // compile code that breaks the moment the JVM tries to actually use the real class.
        if (internal.startsWith("java/") || internal.startsWith("javax/")
                || internal.startsWith("jdk/") || internal.startsWith("sun/")
                || internal.startsWith("com/sun/") || internal.startsWith("javafx/")
                || internal.startsWith("org/fxmisc/")
                || internal.startsWith("atlantafx/")
                || internal.startsWith("org/kordamp/ikonli/")
                || internal.startsWith("org/controlsfx/")
                || internal.startsWith("org/objectweb/asm/")
                || internal.startsWith("org/benf/cfr/")
                || internal.startsWith("org/vineflower/")
                || internal.startsWith("com/strobel/")
                || internal.startsWith("org/slf4j/")
                || internal.startsWith("ch/qos/logback/")
                || internal.startsWith("kotlin/")) return;
        out.add(internal);
    }

    /** Minimal public class with no body. */
    private static byte[] buildPhantom(String internalName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        // Default public no-arg constructor so `new Phantom()` compiles.
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
