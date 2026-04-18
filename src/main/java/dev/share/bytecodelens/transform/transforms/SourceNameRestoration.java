package dev.share.bytecodelens.transform.transforms;

import dev.share.bytecodelens.transform.TransformContext;
import dev.share.bytecodelens.transform.Transformation;
import org.objectweb.asm.tree.ClassNode;

/**
 * Sets a sensible {@code SourceFile} attribute on classes that have it stripped or
 * mangled by an obfuscator.
 *
 * <p>The attribute exists for IDEs/decompilers to map bytecode lines back to source.
 * Obfuscators routinely remove it to harden against reverse engineering, but the
 * top-level enclosing class name is the canonical default — recompiling with javac
 * always produces {@code SourceFile = "Foo.java"} for a top-level class {@code Foo}.</p>
 *
 * <p>For inner / nested / anonymous classes (name contains {@code $}) we use the OUTER
 * class simple name + {@code .java} — that's where the real source actually lives.
 * For top-level classes we use the simple name + {@code .java}.</p>
 *
 * <p>Skips classes that already have a non-empty {@code sourceFile} ending in
 * {@code .java} or {@code .kt}/{@code .scala}/{@code .groovy} — assume it's correct.</p>
 */
public final class SourceNameRestoration implements Transformation {

    @Override public String id() { return "source-name-restoration"; }
    @Override public String name() { return "Source Name Restoration"; }
    @Override public String description() {
        return "Restore SourceFile attribute to OuterClass.java for classes missing one.";
    }

    @Override
    public void transform(ClassNode node, TransformContext ctx) {
        if (node.name == null) return;
        if (looksValid(node.sourceFile)) return;
        String src = inferSourceFile(node.name);
        if (src == null) return;
        node.sourceFile = src;
        ctx.inc("source-files-set");
    }

    private static boolean looksValid(String s) {
        if (s == null || s.isEmpty()) return false;
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".kt")
                || lower.endsWith(".scala") || lower.endsWith(".groovy");
    }

    private static String inferSourceFile(String internalName) {
        int slash = internalName.lastIndexOf('/');
        String simple = slash < 0 ? internalName : internalName.substring(slash + 1);
        // Strip everything from the first $ onwards — gives us the top-level enclosing class.
        int dollar = simple.indexOf('$');
        String base = dollar < 0 ? simple : simple.substring(0, dollar);
        if (base.isEmpty()) return null;
        return base + ".java";
    }
}
