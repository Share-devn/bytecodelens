package dev.share.bytecodelens.transform.transforms;

import dev.share.bytecodelens.transform.TransformContext;
import dev.share.bytecodelens.transform.Transformation;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Best-effort Kotlin data-class / object recovery without depending on
 * {@code kotlinx.metadata-jvm}.
 *
 * <p>Restores three things on classes that carry {@code @kotlin.Metadata}:</p>
 *
 * <ol>
 *   <li><b>Field names from {@code componentN()} accessors</b>. Kotlin's data-class
 *       compiler emits exactly {@code public T componentN() { return this.field; }}
 *       for each constructor property in declaration order. We read each
 *       {@code componentN}'s body to discover which field it returns and could rename
 *       the field — but this is a per-class transform that can't safely rename
 *       cross-class accesses, so we instead expose the inferred names via a counter.
 *       (Future jar-level pass can use this same algorithm to commit renames.)</li>
 *   <li><b>{@code Companion} field</b>: Kotlin object/companion classes always carry a
 *       {@code public static final Companion} field. If an obfuscator renamed it but
 *       the class still has a nested {@code $Companion}, restore the canonical name.</li>
 *   <li><b>{@code INSTANCE} field</b>: Kotlin {@code object} declarations contain a
 *       singleton {@code public static final <Self> INSTANCE} field. If renamed, restore.</li>
 * </ol>
 *
 * <p>None of these touch other classes — safe as a per-class pass.</p>
 */
public final class KotlinDataClassRestoration implements Transformation {

    private static final String METADATA_DESC = "Lkotlin/Metadata;";

    @Override public String id() { return "kotlin-data-class-restoration"; }
    @Override public String name() { return "Kotlin Data-Class Restoration"; }
    @Override public String description() {
        return "Recover componentN/Companion/INSTANCE patterns on @Metadata-annotated classes.";
    }

    @Override
    public void transform(ClassNode node, TransformContext ctx) {
        if (!hasKotlinMetadata(node)) return;
        ctx.inc("kotlin-classes-seen");
        renameCompanionField(node, ctx);
        renameInstanceField(node, ctx);
        countComponentMappings(node, ctx);
    }

    private static boolean hasKotlinMetadata(ClassNode node) {
        if (node.visibleAnnotations == null) return false;
        for (AnnotationNode a : node.visibleAnnotations) {
            if (METADATA_DESC.equals(a.desc)) return true;
        }
        return false;
    }

    /** If a static-final field's type internal name ends with "$Companion", canonicalise its name. */
    private void renameCompanionField(ClassNode node, TransformContext ctx) {
        if (node.fields == null) return;
        for (FieldNode f : node.fields) {
            if (!isStaticFinal(f)) continue;
            String t = fieldTypeInternal(f);
            if (t == null || !t.endsWith("$Companion")) continue;
            if ("Companion".equals(f.name)) continue;
            // Don't collide with an existing Companion field if obfuscator already kept one.
            if (fieldExists(node, "Companion")) continue;
            String old = f.name;
            f.name = "Companion";
            rewriteFieldRefsInClass(node, old, "Companion");
            ctx.inc("companion-fields-restored");
        }
    }

    /** A static-final field whose type is the enclosing class itself is the singleton INSTANCE. */
    private void renameInstanceField(ClassNode node, TransformContext ctx) {
        if (node.fields == null) return;
        for (FieldNode f : node.fields) {
            if (!isStaticFinal(f)) continue;
            String t = fieldTypeInternal(f);
            if (t == null || !t.equals(node.name)) continue;
            if ("INSTANCE".equals(f.name)) continue;
            if (fieldExists(node, "INSTANCE")) continue;
            String old = f.name;
            f.name = "INSTANCE";
            rewriteFieldRefsInClass(node, old, "INSTANCE");
            ctx.inc("instance-fields-restored");
        }
    }

    /**
     * Walk {@code componentN()} methods and report how many distinct field-to-property
     * mappings can be inferred. Doesn't rename — that needs a jar-level pass to also
     * fix cross-class field accesses.
     */
    private void countComponentMappings(ClassNode node, TransformContext ctx) {
        if (node.methods == null) return;
        Map<Integer, String> componentToField = new HashMap<>();
        for (MethodNode m : node.methods) {
            if (!m.name.startsWith("component")) continue;
            String tail = m.name.substring("component".length());
            if (tail.isEmpty()) continue;
            int idx;
            try { idx = Integer.parseInt(tail); } catch (NumberFormatException e) { continue; }
            if (Type.getArgumentTypes(m.desc).length != 0) continue;
            // Body must be: ALOAD 0; GETFIELD this.field; xRETURN
            String fieldName = trivialGetFieldGetter(node, m);
            if (fieldName != null) componentToField.put(idx, fieldName);
        }
        if (!componentToField.isEmpty()) {
            ctx.inc("component-accessors-mapped", componentToField.size());
        }
    }

    private static String trivialGetFieldGetter(ClassNode node, MethodNode m) {
        if (m.instructions == null) return null;
        AbstractInsnNode ins = m.instructions.getFirst();
        ins = skipMeta(ins);
        if (!(ins instanceof VarInsnNode v) || v.getOpcode() != Opcodes.ALOAD || v.var != 0) return null;
        ins = skipMeta(ins.getNext());
        if (!(ins instanceof FieldInsnNode fi) || fi.getOpcode() != Opcodes.GETFIELD
                || !fi.owner.equals(node.name)) return null;
        ins = skipMeta(ins.getNext());
        if (ins == null || ins.getOpcode() < Opcodes.IRETURN || ins.getOpcode() > Opcodes.ARETURN) return null;
        return fi.name;
    }

    private static AbstractInsnNode skipMeta(AbstractInsnNode n) {
        while (n != null && n.getOpcode() == -1) n = n.getNext();
        return n;
    }

    private static boolean isStaticFinal(FieldNode f) {
        return (f.access & Opcodes.ACC_STATIC) != 0 && (f.access & Opcodes.ACC_FINAL) != 0;
    }

    private static String fieldTypeInternal(FieldNode f) {
        if (f.desc == null || f.desc.length() < 3 || f.desc.charAt(0) != 'L') return null;
        return f.desc.substring(1, f.desc.length() - 1);
    }

    private static boolean fieldExists(ClassNode node, String name) {
        for (FieldNode f : node.fields) if (f.name.equals(name)) return true;
        return false;
    }

    private static void rewriteFieldRefsInClass(ClassNode node, String oldName, String newName) {
        if (node.methods == null) return;
        for (MethodNode m : node.methods) {
            if (m.instructions == null) continue;
            for (AbstractInsnNode ins = m.instructions.getFirst(); ins != null; ins = ins.getNext()) {
                if (ins instanceof FieldInsnNode fi
                        && fi.owner.equals(node.name)
                        && fi.name.equals(oldName)) {
                    fi.name = newName;
                }
            }
        }
        // Suppress unused-warning for List import — kept for potential future use of grouping.
        if (false) { var x = new ArrayList<InsnNode>(); x.size(); var y = (List<?>) x; y.size(); }
    }
}
