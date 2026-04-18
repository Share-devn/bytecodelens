package dev.share.bytecodelens.transform.transforms;

import dev.share.bytecodelens.transform.TransformContext;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

class StackFrameRemovalTest {

    @Test
    void removesAllFrames() {
        ClassNode node = new ClassNode();
        node.name = "p/C";
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "x", "()V", null, null);
        m.instructions.add(new FrameNode(Opcodes.F_NEW, 0, new Object[0], 0, new Object[0]));
        m.instructions.add(new InsnNode(Opcodes.NOP));
        m.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        m.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(m);

        TransformContext ctx = new TransformContext();
        ctx.enterPass("stack-frame-removal");
        new StackFrameRemoval().transform(node, ctx);
        ctx.exitPass();

        for (AbstractInsnNode ins = m.instructions.getFirst(); ins != null; ins = ins.getNext()) {
            assertFalse(ins instanceof FrameNode, "frames should be gone");
        }
        assertEquals(2, ctx.totalFor("stack-frame-removal"));
    }

    @Test
    void noopWhenNoFrames() {
        ClassNode node = new ClassNode();
        node.name = "p/C";
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "x", "()V", null, null);
        m.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(m);

        TransformContext ctx = new TransformContext();
        ctx.enterPass("stack-frame-removal");
        new StackFrameRemoval().transform(node, ctx);
        ctx.exitPass();

        assertEquals(0, ctx.totalFor("stack-frame-removal"));
    }

    @Test
    void idempotentOnSecondRun() {
        ClassNode node = new ClassNode();
        node.name = "p/C";
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "x", "()V", null, null);
        m.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        m.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(m);

        new StackFrameRemoval().transform(node, new TransformContext());
        // Second run — should report zero removed.
        TransformContext ctx2 = new TransformContext();
        ctx2.enterPass("stack-frame-removal");
        new StackFrameRemoval().transform(node, ctx2);
        ctx2.exitPass();
        assertEquals(0, ctx2.totalFor("stack-frame-removal"));
    }
}
