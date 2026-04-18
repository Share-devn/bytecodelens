package dev.share.bytecodelens.transform.transforms;

import dev.share.bytecodelens.transform.TransformContext;
import dev.share.bytecodelens.transform.Transformation;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.Frame;

/**
 * Removes instructions that are unreachable by control-flow analysis.
 *
 * <p>Uses ASM's {@link Analyzer} to compute stack frames for every instruction; any
 * instruction whose frame is {@code null} after analysis is unreachable and gets stripped.
 * {@link LabelNode}s and {@link LineNumberNode}s are preserved (labels may still be
 * referenced by exception handlers, line numbers are harmless debug info).</p>
 */
public final class DeadCodeRemoval implements Transformation {

    @Override public String id() { return "dead-code-removal"; }
    @Override public String name() { return "Dead Code Removal"; }
    @Override public String description() {
        return "Strip instructions unreachable by control-flow analysis.";
    }

    @Override
    public void transform(ClassNode node, TransformContext ctx) {
        if (node.methods == null) return;
        for (MethodNode m : node.methods) {
            if (m.instructions == null || m.instructions.size() == 0) continue;
            // Abstract/native methods have no bodies.
            if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            removeDeadInstructions(node.name, m, ctx);
        }
    }

    private static void removeDeadInstructions(String owner, MethodNode m, TransformContext ctx) {
        Analyzer<?> analyzer = new Analyzer<>(new BasicInterpreter());
        Frame<?>[] frames;
        try {
            frames = analyzer.analyze(owner, m);
        } catch (AnalyzerException ex) {
            // Malformed method — leave it untouched.
            return;
        }
        AbstractInsnNode[] insns = m.instructions.toArray();
        int removed = 0;
        for (int i = 0; i < insns.length; i++) {
            AbstractInsnNode insn = insns[i];
            if (frames[i] != null) continue;
            // Keep labels (may be referenced by try-catch) and line numbers.
            if (insn instanceof LabelNode || insn instanceof LineNumberNode) continue;
            m.instructions.remove(insn);
            removed++;
        }
        if (removed > 0) {
            ctx.inc("instructions-removed", removed);
        }
    }
}
