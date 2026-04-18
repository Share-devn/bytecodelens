package dev.share.bytecodelens.transform.transforms;

import dev.share.bytecodelens.transform.TransformContext;
import dev.share.bytecodelens.transform.Transformation;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.util.Iterator;

/**
 * Anti-tamper fix: a field carrying a {@code Code} attribute (or any other attribute that
 * only belongs on methods) crashes strict parsers. The JVM just ignores it; some
 * obfuscators attach garbage method-only attributes to fields to break analysis tools.
 *
 * <p>This pass walks every {@link FieldNode#attrs} collection and drops anything named
 * {@code Code} / {@code LineNumberTable} / {@code LocalVariableTable} — attributes that
 * make no sense on a field.</p>
 */
public final class StripCodeOnField implements Transformation {

    @Override public String id() { return "strip-code-on-field"; }
    @Override public String name() { return "Strip method-only attributes from fields"; }
    @Override public String description() {
        return "Remove Code / LineNumberTable / LocalVariableTable attributes attached to fields.";
    }

    @Override
    public void transform(ClassNode node, TransformContext ctx) {
        if (node.fields == null) return;
        for (FieldNode f : node.fields) {
            if (f.attrs == null) continue;
            Iterator<Attribute> it = f.attrs.iterator();
            while (it.hasNext()) {
                Attribute a = it.next();
                if (a == null) continue;
                String t = a.type;
                if ("Code".equals(t) || "LineNumberTable".equals(t)
                        || "LocalVariableTable".equals(t) || "LocalVariableTypeTable".equals(t)
                        || "StackMapTable".equals(t) || "Exceptions".equals(t)) {
                    it.remove();
                    ctx.inc("attributes-stripped");
                }
            }
        }
    }
}
