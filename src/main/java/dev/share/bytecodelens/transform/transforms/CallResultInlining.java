package dev.share.bytecodelens.transform.transforms;

import dev.share.bytecodelens.transform.TransformContext;
import dev.share.bytecodelens.transform.Transformation;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Replaces calls to side-effect-free static methods that return a constant with the
 * constant itself. Recaf's "Call Result Inlining" — useful against obfuscators that
 * wrap every constant in a getter to defeat naive constant folders.
 *
 * <p>A method qualifies if it is {@code static}, takes zero arguments and consists of
 * exactly: optional labels/line-numbers, one constant-pushing instruction (ICONST/BIPUSH/
 * SIPUSH/LDC of int/long/float/double/String), and the matching return opcode. We deliberately
 * do not chase getfield/getstatic — those depend on class init state that may diverge.</p>
 *
 * <p>Both passes (collect candidates, rewrite call sites) work entirely on the {@link ClassNode}
 * given to {@link #transform}; we don't reach across classes. That keeps the transform
 * obviously-safe at the cost of missing cross-class inlining (a limitation we accept; full
 * cross-class inlining would need a jar-level transformation, not a per-class one).</p>
 */
public final class CallResultInlining implements Transformation {

    @Override public String id() { return "call-result-inlining"; }
    @Override public String name() { return "Call Result Inlining"; }
    @Override public String description() {
        return "Inline static no-arg methods that return a constant.";
    }

    /** key = name + desc → constant value (Integer/Long/Float/Double/String). */
    private record Constant(Object value, int returnOpcode) {}

    @Override
    public void transform(ClassNode node, TransformContext ctx) {
        if (node.methods == null) return;
        Map<String, Constant> table = collectInlineableMethods(node);
        if (table.isEmpty()) return;
        rewriteCallSites(node, table, ctx);
    }

    private Map<String, Constant> collectInlineableMethods(ClassNode node) {
        Map<String, Constant> out = new HashMap<>();
        for (MethodNode m : node.methods) {
            if ((m.access & Opcodes.ACC_STATIC) == 0) continue;
            if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            // Zero-arg only.
            if (Type.getArgumentTypes(m.desc).length != 0) continue;
            if (m.instructions == null || m.instructions.size() == 0) continue;
            Constant c = analyseConstantBody(m);
            if (c != null) {
                out.put(m.name + m.desc, c);
            }
        }
        return out;
    }

    /** Returns the Constant if {@code m} is exactly "push X; return X" (ignoring labels/line-nums). */
    private static Constant analyseConstantBody(MethodNode m) {
        Object value = null;
        int returnOp = -1;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode || insn instanceof LineNumberNode) continue;
            int op = insn.getOpcode();
            if (op == -1) continue;
            if (value == null) {
                value = constantValueOf(insn);
                if (value == null) return null; // not a constant push — abandon
            } else if (returnOp == -1) {
                if (op >= Opcodes.IRETURN && op <= Opcodes.ARETURN) {
                    returnOp = op;
                } else {
                    return null; // something else after the constant — give up
                }
            } else {
                return null; // extra instructions after return — odd, give up
            }
        }
        if (value == null || returnOp == -1) return null;
        if (!isConsistentReturn(returnOp, value)) return null;
        return new Constant(value, returnOp);
    }

    private static Object constantValueOf(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) {
            return op - Opcodes.ICONST_0; // ICONST_M1 == 2 → -1; ICONST_0 == 3 → 0; …
        }
        if (op == Opcodes.LCONST_0) return 0L;
        if (op == Opcodes.LCONST_1) return 1L;
        if (op == Opcodes.FCONST_0) return 0f;
        if (op == Opcodes.FCONST_1) return 1f;
        if (op == Opcodes.FCONST_2) return 2f;
        if (op == Opcodes.DCONST_0) return 0d;
        if (op == Opcodes.DCONST_1) return 1d;
        if (op == Opcodes.ACONST_NULL) return Constants.NULL;
        if (insn instanceof IntInsnNode i) {
            // BIPUSH/SIPUSH push an int; NEWARRAY isn't a constant.
            if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) return i.operand;
            return null;
        }
        if (insn instanceof LdcInsnNode ldc) {
            Object cst = ldc.cst;
            // Allow Integer/Long/Float/Double/String. Type/Handle/ConstantDynamic are NOT
            // safe to inline because they have semantics (class-init, dynamic resolution).
            if (cst instanceof Integer || cst instanceof Long
                    || cst instanceof Float || cst instanceof Double
                    || cst instanceof String) return cst;
            return null;
        }
        return null;
    }

    private static boolean isConsistentReturn(int returnOp, Object value) {
        return switch (returnOp) {
            case Opcodes.IRETURN -> value instanceof Integer;
            case Opcodes.LRETURN -> value instanceof Long;
            case Opcodes.FRETURN -> value instanceof Float;
            case Opcodes.DRETURN -> value instanceof Double;
            case Opcodes.ARETURN -> value instanceof String || value == Constants.NULL;
            default -> false;
        };
    }

    private void rewriteCallSites(ClassNode node, Map<String, Constant> table, TransformContext ctx) {
        for (MethodNode m : node.methods) {
            if (m.instructions == null || m.instructions.size() == 0) continue;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; ) {
                AbstractInsnNode next = insn.getNext();
                if (insn instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC
                        && mi.owner.equals(node.name)) {
                    Constant c = table.get(mi.name + mi.desc);
                    if (c != null) {
                        AbstractInsnNode replacement = pushInsnFor(c.value());
                        if (replacement != null) {
                            m.instructions.set(insn, replacement);
                            ctx.inc("calls-inlined");
                        }
                    }
                }
                insn = next;
            }
        }
    }

    private static AbstractInsnNode pushInsnFor(Object value) {
        if (value == Constants.NULL) return new InsnNode(Opcodes.ACONST_NULL);
        if (value instanceof Integer i) return integerPush(i);
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

    private static AbstractInsnNode integerPush(int v) {
        if (v >= -1 && v <= 5) return new InsnNode(Opcodes.ICONST_0 + v);
        if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) return new IntInsnNode(Opcodes.BIPUSH, v);
        if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) return new IntInsnNode(Opcodes.SIPUSH, v);
        return new LdcInsnNode(v);
    }

    /** Sentinel for ACONST_NULL — distinguishes "null" from "no value" in maps. */
    private static final class Constants { static final Object NULL = new Object(); }
}
