package dev.share.bytecodelens.transform.transforms;

import dev.share.bytecodelens.transform.TransformContext;
import dev.share.bytecodelens.transform.Transformation;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Simplifies trivially-decidable conditional branches (opaque predicates) where the
 * condition is a {@code ICONST_0}/{@code ICONST_1} literal pushed immediately before.
 *
 * <p>Patterns handled:</p>
 * <ul>
 *     <li>{@code ICONST_1; IFEQ L} — never jumps → strip both insns</li>
 *     <li>{@code ICONST_0; IFNE L} — never jumps → strip both insns</li>
 *     <li>{@code ICONST_0; IFEQ L} — always jumps → replace with GOTO L</li>
 *     <li>{@code ICONST_1; IFNE L} — always jumps → replace with GOTO L</li>
 * </ul>
 *
 * <p>More elaborate opaque predicates (e.g. {@code x*x >= 0}) are out of scope — they
 * require symbolic analysis of the full stack.</p>
 */
public final class OpaquePredicateSimplification implements Transformation {

    @Override public String id() { return "opaque-predicate-simplification"; }
    @Override public String name() { return "Opaque Predicate Simplification"; }
    @Override public String description() {
        return "Rewrite literal ICONST_0/1 + IFEQ/IFNE branches to GOTO or a no-op.";
    }

    @Override
    public void transform(ClassNode node, TransformContext ctx) {
        if (node.methods == null) return;
        for (MethodNode m : node.methods) {
            if (m.instructions == null || m.instructions.size() < 2) continue;
            if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            simplify(m, ctx);
        }
    }

    private static void simplify(MethodNode m, TransformContext ctx) {
        AbstractInsnNode insn = m.instructions.getFirst();
        while (insn != null) {
            AbstractInsnNode next = insn.getNext();
            if (insn instanceof JumpInsnNode jump) {
                int prevLiteral = literalIntBefore(insn);
                if (prevLiteral != Integer.MIN_VALUE) {
                    Boolean taken = decideBoxed(jump.getOpcode(), prevLiteral);
                    if (taken != null) {
                        AbstractInsnNode prev = insn.getPrevious();
                        if (prev != null) m.instructions.remove(prev);
                        if (taken) {
                            m.instructions.set(jump, new JumpInsnNode(Opcodes.GOTO, jump.label));
                            ctx.inc("branches-forced-taken");
                        } else {
                            m.instructions.remove(jump);
                            ctx.inc("branches-dropped");
                        }
                    }
                }
            }
            insn = next;
        }
    }

    /** Returns the integer literal pushed immediately before {@code insn}, or MIN_VALUE if not a constant push. */
    private static int literalIntBefore(AbstractInsnNode insn) {
        AbstractInsnNode prev = insn.getPrevious();
        while (prev != null && (prev.getOpcode() < 0)) {
            // Skip labels / line numbers / frame nodes
            prev = prev.getPrevious();
        }
        if (prev == null) return Integer.MIN_VALUE;
        if (prev instanceof InsnNode in) {
            int op = in.getOpcode();
            if (op == Opcodes.ICONST_0) return 0;
            if (op == Opcodes.ICONST_1) return 1;
        }
        return Integer.MIN_VALUE;
    }

    /** @return true  = always jumps; false = never jumps; null = can't decide with just this literal. */
    private static Boolean decideBoxed(int opcode, int literal) {
        return switch (opcode) {
            case Opcodes.IFEQ -> literal == 0;
            case Opcodes.IFNE -> literal != 0;
            // Other comparisons (IFLT/IFLE/IFGT/IFGE) are also decidable with one operand,
            // but then the operand must satisfy the comparison unconditionally —
            // for 0/1 literals, just handle the two above.
            default -> null;
        };
    }

}
