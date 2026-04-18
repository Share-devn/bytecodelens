package dev.share.bytecodelens.transform.transforms;

import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.LoadedJar;
import dev.share.bytecodelens.transform.JarLevelTransformation;
import dev.share.bytecodelens.transform.TransformContext;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Inlines {@code static final} field references that have a compile-time constant value
 * (JVM {@code ConstantValue} attribute). Replaces {@code GETSTATIC Class.field} with the
 * literal constant instruction (ICONST / BIPUSH / SIPUSH / LDC).
 *
 * <p>Safe transformation: the JVM itself inlines these at load time if the declaring class
 * is loaded via direct reference, but some obfuscators intentionally emit the
 * {@code GETSTATIC} anyway to hurt decompiler output. This pass undoes that.</p>
 */
public final class StaticValueInlining implements JarLevelTransformation {

    private static final Logger log = LoggerFactory.getLogger(StaticValueInlining.class);

    @Override public String id() { return "static-value-inlining"; }
    @Override public String name() { return "Static Value Inlining"; }
    @Override public String description() {
        return "Inline GETSTATIC of static-final constants with their compile-time value.";
    }

    @Override
    public LoadedJar apply(LoadedJar jar, TransformContext ctx) {
        // Phase 1 — collect map of (owner.field:desc) -> ConstantValue.
        Map<String, Object> constants = new HashMap<>();
        for (ClassEntry entry : jar.classes()) {
            ClassNode node = readNode(entry.bytes());
            if (node == null || node.fields == null) continue;
            for (FieldNode f : node.fields) {
                if ((f.access & Opcodes.ACC_STATIC) == 0) continue;
                if ((f.access & Opcodes.ACC_FINAL) == 0) continue;
                if (f.value == null) continue;
                constants.put(node.name + "." + f.name + ":" + f.desc, f.value);
            }
        }
        if (constants.isEmpty()) return jar;

        // Phase 2 — rewrite every GETSTATIC that hits the map.
        List<ClassEntry> newRoot = new ArrayList<>(jar.classes().size());
        for (ClassEntry entry : jar.classes()) {
            newRoot.add(rewriteOne(entry, constants, ctx));
        }
        List<ClassEntry> newVer = new ArrayList<>(jar.versionedClasses().size());
        for (ClassEntry entry : jar.versionedClasses()) {
            newVer.add(rewriteOne(entry, constants, ctx));
        }
        return new LoadedJar(jar.source(), newRoot, newVer, jar.resources(),
                jar.totalBytes(), jar.loadTimeMs());
    }

    private ClassEntry rewriteOne(ClassEntry entry, Map<String, Object> constants, TransformContext ctx) {
        ClassNode node = readNode(entry.bytes());
        if (node == null || node.methods == null) return entry;
        boolean changed = false;
        for (MethodNode m : node.methods) {
            if (m.instructions == null) continue;
            AbstractInsnNode insn = m.instructions.getFirst();
            while (insn != null) {
                AbstractInsnNode next = insn.getNext();
                if (insn.getOpcode() == Opcodes.GETSTATIC && insn instanceof FieldInsnNode fi) {
                    String key = fi.owner + "." + fi.name + ":" + fi.desc;
                    Object value = constants.get(key);
                    if (value != null) {
                        AbstractInsnNode replacement = constantInstruction(value);
                        if (replacement != null) {
                            m.instructions.set(insn, replacement);
                            ctx.inc("constants-inlined");
                            changed = true;
                        }
                    }
                }
                insn = next;
            }
        }
        if (!changed) return entry;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        try {
            node.accept(cw);
        } catch (Throwable ex) {
            log.warn("Static-value-inlining write failed for {}: {}", entry.name(), ex.getMessage());
            return entry;
        }
        return new dev.share.bytecodelens.service.ClassAnalyzer().analyze(cw.toByteArray(), entry.runtimeVersion());
    }

    private static ClassNode readNode(byte[] bytes) {
        try {
            ClassNode n = new ClassNode();
            new ClassReader(bytes).accept(n, 0);
            return n;
        } catch (Throwable ex) {
            return null;
        }
    }

    /** Build the shortest bytecode instruction that pushes {@code value}. */
    static AbstractInsnNode constantInstruction(Object value) {
        if (value instanceof Integer i) {
            if (i >= -1 && i <= 5) return new InsnNode(Opcodes.ICONST_0 + i);
            if (i >= Byte.MIN_VALUE && i <= Byte.MAX_VALUE) return new IntInsnNode(Opcodes.BIPUSH, i);
            if (i >= Short.MIN_VALUE && i <= Short.MAX_VALUE) return new IntInsnNode(Opcodes.SIPUSH, i);
            return new LdcInsnNode(i);
        }
        if (value instanceof Long l) {
            if (l == 0L) return new InsnNode(Opcodes.LCONST_0);
            if (l == 1L) return new InsnNode(Opcodes.LCONST_1);
            return new LdcInsnNode(l);
        }
        if (value instanceof Float f) {
            if (f == 0f) return new InsnNode(Opcodes.FCONST_0);
            if (f == 1f) return new InsnNode(Opcodes.FCONST_1);
            if (f == 2f) return new InsnNode(Opcodes.FCONST_2);
            return new LdcInsnNode(f);
        }
        if (value instanceof Double d) {
            if (d == 0d) return new InsnNode(Opcodes.DCONST_0);
            if (d == 1d) return new InsnNode(Opcodes.DCONST_1);
            return new LdcInsnNode(d);
        }
        if (value instanceof String s) return new LdcInsnNode(s);
        return null;
    }
}
