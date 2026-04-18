package dev.share.bytecodelens.transform.transforms;

import dev.share.bytecodelens.transform.TransformContext;
import dev.share.bytecodelens.transform.Transformation;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.util.HashSet;
import java.util.Set;

/**
 * Strip instructions that sit between a control-flow terminator (RETURN / ATHROW /
 * unconditional GOTO) and the next jump-target label — classic anti-disassembly
 * pattern found in StarCraft-era native obfuscators and similar tooling, where
 * sequences like {@code push; pop; ret; throw} jam decompilers that try to follow
 * the fall-through after a {@code ret}.
 *
 * <p>Why this is safe while {@link DeadCodeRemoval} alone isn't: ASM's
 * {@code Analyzer} marks an instruction unreachable only if no data-flow path ever
 * reaches it. The terminator+garbage pattern looks reachable to the analyser (the
 * garbage sits inline after the return), but the JVM never executes it. Knowing
 * the specific shape lets us strip it without a full-bore analysis and without
 * relying on {@link DeadCodeRemoval} succeeding on a poisoned class.</p>
 *
 * <p>The walk collects every instruction between a terminator and the next
 * referenced label (target of any jump, switch, or try-catch handler). Non-terminator
 * labels and line numbers are preserved to keep debug info intact.</p>
 */
public final class UnreachableAfterTerminator implements Transformation {

    @Override public String id() { return "unreachable-after-terminator"; }
    @Override public String name() { return "Unreachable After Terminator"; }
    @Override public String description() {
        return "Remove instructions between a return/throw/goto and the next jump target. "
                + "Targets anti-disassembly patterns like push/pop/ret/throw.";
    }

    @Override
    public void transform(ClassNode node, TransformContext ctx) {
        if (node.methods == null) return;
        for (MethodNode m : node.methods) {
            if (m.instructions == null || m.instructions.size() == 0) continue;
            if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            int removed = stripAfterTerminators(m);
            if (removed > 0) ctx.inc("instructions-removed", removed);
        }
    }

    /**
     * Strip instructions between a terminator and the next referenced label.
     * Package-private so tests can exercise it directly on a built MethodNode.
     */
    static int stripAfterTerminators(MethodNode m) {
        Set<LabelNode> referenced = collectReferencedLabels(m);
        int removed = 0;
        AbstractInsnNode insn = m.instructions.getFirst();
        boolean insideDead = false;
        while (insn != null) {
            AbstractInsnNode next = insn.getNext();
            if (insideDead) {
                // Stop on a referenced label — normal control flow can resume there.
                if (insn instanceof LabelNode label && referenced.contains(label)) {
                    insideDead = false;
                } else if (insn instanceof LineNumberNode) {
                    // Line numbers before the next real target are orphaned; keep them to
                    // preserve debug continuity.
                } else if (insn instanceof LabelNode) {
                    // Unreferenced label — drop. We checked referenced membership above.
                    m.instructions.remove(insn);
                    removed++;
                } else {
                    m.instructions.remove(insn);
                    removed++;
                }
            } else if (isTerminator(insn)) {
                insideDead = true;
            }
            insn = next;
        }
        return removed;
    }

    private static boolean isTerminator(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op == -1) return false;
        // Unconditional returns/throws terminate the basic block.
        if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) return true;
        if (op == Opcodes.ATHROW) return true;
        if (op == Opcodes.GOTO) return true;
        // TABLESWITCH / LOOKUPSWITCH also don't fall through — every case is a jump.
        if (insn instanceof TableSwitchInsnNode) return true;
        if (insn instanceof LookupSwitchInsnNode) return true;
        return false;
    }

    private static Set<LabelNode> collectReferencedLabels(MethodNode m) {
        Set<LabelNode> out = new HashSet<>();
        if (m.tryCatchBlocks != null) {
            for (TryCatchBlockNode tc : m.tryCatchBlocks) {
                if (tc.start != null) out.add(tc.start);
                if (tc.end != null) out.add(tc.end);
                if (tc.handler != null) out.add(tc.handler);
            }
        }
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in instanceof JumpInsnNode j && j.label != null) out.add(j.label);
            else if (in instanceof TableSwitchInsnNode t) {
                if (t.dflt != null) out.add(t.dflt);
                if (t.labels != null) out.addAll(t.labels);
            } else if (in instanceof LookupSwitchInsnNode l) {
                if (l.dflt != null) out.add(l.dflt);
                if (l.labels != null) out.addAll(l.labels);
            }
        }
        // Also keep the very first label — some methods start with a label that's
        // technically unreferenced but needed as the function entry.
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in instanceof LabelNode label) { out.add(label); break; }
        }
        return out;
    }
}
