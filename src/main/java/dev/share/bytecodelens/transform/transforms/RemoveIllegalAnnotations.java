package dev.share.bytecodelens.transform.transforms;

import dev.share.bytecodelens.transform.TransformContext;
import dev.share.bytecodelens.transform.Transformation;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Iterator;
import java.util.List;

/**
 * Anti-tamper fix: removes annotations whose descriptor is not a valid JVM type descriptor.
 * Obfuscators sometimes emit {@code @""} / {@code @null} / broken descriptors to crash
 * decompilers; strict parsers handle them but output becomes unreadable.
 */
public final class RemoveIllegalAnnotations implements Transformation {

    @Override public String id() { return "remove-illegal-annotations"; }
    @Override public String name() { return "Remove illegal annotations"; }
    @Override public String description() {
        return "Strip annotations whose descriptor is empty, non-ASCII or malformed.";
    }

    @Override
    public void transform(ClassNode node, TransformContext ctx) {
        ctx.inc("_", filter(node.visibleAnnotations, ctx));
        ctx.inc("_", filter(node.invisibleAnnotations, ctx));
        ctx.inc("_", filter(node.visibleTypeAnnotations, ctx));
        ctx.inc("_", filter(node.invisibleTypeAnnotations, ctx));
        if (node.fields != null) {
            for (FieldNode f : node.fields) {
                filter(f.visibleAnnotations, ctx);
                filter(f.invisibleAnnotations, ctx);
                filter(f.visibleTypeAnnotations, ctx);
                filter(f.invisibleTypeAnnotations, ctx);
            }
        }
        if (node.methods != null) {
            for (MethodNode m : node.methods) {
                filter(m.visibleAnnotations, ctx);
                filter(m.invisibleAnnotations, ctx);
                filter(m.visibleTypeAnnotations, ctx);
                filter(m.invisibleTypeAnnotations, ctx);
            }
        }
    }

    private static int filter(List<? extends AnnotationNode> list, TransformContext ctx) {
        if (list == null) return 0;
        int removed = 0;
        Iterator<? extends AnnotationNode> it = list.iterator();
        while (it.hasNext()) {
            AnnotationNode a = it.next();
            if (a == null || !isValidDescriptor(a.desc)) {
                it.remove();
                ctx.inc("annotations-removed");
                removed++;
            }
        }
        return removed;
    }

    /** Accepts L...; form only — the valid descriptor for an annotation. */
    static boolean isValidDescriptor(String desc) {
        if (desc == null || desc.length() < 3) return false;
        if (desc.charAt(0) != 'L' || desc.charAt(desc.length() - 1) != ';') return false;
        for (int i = 1; i < desc.length() - 1; i++) {
            char c = desc.charAt(i);
            if (c == '.' || c == ';' || c == '[' || c == '<' || c == '>') return false;
            if (c < 0x20) return false;
        }
        return true;
    }
}
