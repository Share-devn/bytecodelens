package dev.share.bytecodelens.transform.transforms;

import dev.share.bytecodelens.transform.TransformContext;
import dev.share.bytecodelens.transform.Transformation;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Strips StackMapTable frame nodes from every method body.
 *
 * <p>StackMapTable is required by the verifier on class file version &gt;= 50 (Java 6),
 * but obfuscators sometimes inject malformed frames to crash decompilers/disassemblers.
 * Removing them and letting ASM recompute on write (the runner uses
 * {@code ClassWriter.COMPUTE_FRAMES}) yields a clean, verifier-correct class.</p>
 *
 * <p>Idempotent: a class with no frames left is a no-op on a second run.</p>
 */
public final class StackFrameRemoval implements Transformation {

    @Override public String id() { return "stack-frame-removal"; }
    @Override public String name() { return "Stack Frame Removal"; }
    @Override public String description() {
        return "Drop StackMapTable frames; ASM will recompute on write.";
    }

    @Override
    public void transform(ClassNode node, TransformContext ctx) {
        if (node.methods == null) return;
        for (MethodNode m : node.methods) {
            if (m.instructions == null || m.instructions.size() == 0) continue;
            removeFrames(m, ctx);
        }
    }

    private static void removeFrames(MethodNode m, TransformContext ctx) {
        AbstractInsnNode insn = m.instructions.getFirst();
        int removed = 0;
        while (insn != null) {
            AbstractInsnNode next = insn.getNext();
            if (insn instanceof FrameNode) {
                m.instructions.remove(insn);
                removed++;
            }
            insn = next;
        }
        if (removed > 0) ctx.inc("frames-removed", removed);
    }
}
