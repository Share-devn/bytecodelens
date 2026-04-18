package dev.share.bytecodelens.transform.transforms;

import dev.share.bytecodelens.transform.TransformContext;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

class CallResultInliningTest {

    @Test
    void inlinesIntConstantGetter() {
        // class C {
        //   static int X() { return 42; }
        //   static int use() { return X(); }   <- INVOKESTATIC C.X()I should become BIPUSH 42
        // }
        ClassNode node = parse(buildClass(cw -> {
            method(cw, "X", "()I", mv -> {
                mv.visitIntInsn(Opcodes.BIPUSH, 42);
                mv.visitInsn(Opcodes.IRETURN);
            });
            method(cw, "use", "()I", mv -> {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "p/C", "X", "()I", false);
                mv.visitInsn(Opcodes.IRETURN);
            });
        }));

        TransformContext ctx = new TransformContext();
        ctx.enterPass("call-result-inlining");
        new CallResultInlining().transform(node, ctx);
        ctx.exitPass();

        MethodNode use = methodNamed(node, "use");
        // First non-frame insn should be BIPUSH 42, not INVOKESTATIC.
        boolean foundCall = false;
        for (AbstractInsnNode ins = use.instructions.getFirst(); ins != null; ins = ins.getNext()) {
            if (ins instanceof MethodInsnNode) foundCall = true;
        }
        assertFalse(foundCall);
        assertEquals(1, ctx.totalFor("call-result-inlining"));
    }

    @Test
    void inlinesStringConstant() {
        ClassNode node = parse(buildClass(cw -> {
            method(cw, "url", "()Ljava/lang/String;", mv -> {
                mv.visitLdcInsn("https://example.com");
                mv.visitInsn(Opcodes.ARETURN);
            });
            method(cw, "use", "()Ljava/lang/String;", mv -> {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "p/C", "url", "()Ljava/lang/String;", false);
                mv.visitInsn(Opcodes.ARETURN);
            });
        }));

        new CallResultInlining().transform(node, new TransformContext());

        MethodNode use = methodNamed(node, "use");
        AbstractInsnNode first = firstReal(use);
        assertTrue(first instanceof LdcInsnNode);
        assertEquals("https://example.com", ((LdcInsnNode) first).cst);
    }

    @Test
    void inlinesNull() {
        ClassNode node = parse(buildClass(cw -> {
            method(cw, "n", "()Ljava/lang/String;", mv -> {
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);
            });
            method(cw, "use", "()Ljava/lang/String;", mv -> {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "p/C", "n", "()Ljava/lang/String;", false);
                mv.visitInsn(Opcodes.ARETURN);
            });
        }));

        new CallResultInlining().transform(node, new TransformContext());

        MethodNode use = methodNamed(node, "use");
        assertEquals(Opcodes.ACONST_NULL, firstReal(use).getOpcode());
    }

    @Test
    void leavesNonConstantBodyAlone() {
        // X() does Math.random() — not a constant; must NOT be inlined.
        ClassNode node = parse(buildClass(cw -> {
            method(cw, "X", "()D", mv -> {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "random", "()D", false);
                mv.visitInsn(Opcodes.DRETURN);
            });
            method(cw, "use", "()D", mv -> {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "p/C", "X", "()D", false);
                mv.visitInsn(Opcodes.DRETURN);
            });
        }));

        new CallResultInlining().transform(node, new TransformContext());
        MethodNode use = methodNamed(node, "use");
        assertTrue(firstReal(use) instanceof MethodInsnNode);
    }

    @Test
    void leavesNonStaticAlone() {
        ClassNode node = parse(buildClass(cw -> {
            // virtual method — must NOT qualify even though body is constant.
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "X", "()I", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            method(cw, "use", "()I", mvUse -> {
                mvUse.visitVarInsn(Opcodes.ALOAD, 0);
                mvUse.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "p/C", "X", "()I", false);
                mvUse.visitInsn(Opcodes.IRETURN);
            });
        }));

        new CallResultInlining().transform(node, new TransformContext());
        MethodNode use = methodNamed(node, "use");
        boolean stillCall = false;
        for (AbstractInsnNode ins = use.instructions.getFirst(); ins != null; ins = ins.getNext()) {
            if (ins instanceof MethodInsnNode) stillCall = true;
        }
        assertTrue(stillCall);
    }

    @Test
    void leavesArgTakingMethodAlone() {
        ClassNode node = parse(buildClass(cw -> {
            method(cw, "X", "(I)I", mv -> {
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IRETURN);
            });
            method(cw, "use", "()I", mv -> {
                mv.visitInsn(Opcodes.ICONST_5);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "p/C", "X", "(I)I", false);
                mv.visitInsn(Opcodes.IRETURN);
            });
        }));

        new CallResultInlining().transform(node, new TransformContext());
        MethodNode use = methodNamed(node, "use");
        boolean stillCall = false;
        for (AbstractInsnNode ins = use.instructions.getFirst(); ins != null; ins = ins.getNext()) {
            if (ins instanceof MethodInsnNode) stillCall = true;
        }
        assertTrue(stillCall);
    }

    @Test
    void leavesCrossClassCallsAlone() {
        // INVOKESTATIC against a different owner — we don't have its body, so leave it.
        ClassNode node = parse(buildClass(cw -> {
            method(cw, "X", "()I", mv -> {
                mv.visitInsn(Opcodes.ICONST_3);
                mv.visitInsn(Opcodes.IRETURN);
            });
            method(cw, "use", "()I", mv -> {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "p/Other", "X", "()I", false);
                mv.visitInsn(Opcodes.IRETURN);
            });
        }));

        new CallResultInlining().transform(node, new TransformContext());
        MethodNode use = methodNamed(node, "use");
        assertTrue(firstReal(use) instanceof MethodInsnNode);
    }

    // -- helpers ---------------------------------------------------------

    private static byte[] buildClass(java.util.function.Consumer<ClassWriter> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "p/C", null, "java/lang/Object", null);
        body.accept(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void method(ClassWriter cw, String name, String desc,
                               java.util.function.Consumer<MethodVisitor> body) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static ClassNode parse(byte[] bytes) {
        ClassNode n = new ClassNode();
        new ClassReader(bytes).accept(n, 0);
        return n;
    }

    private static MethodNode methodNamed(ClassNode n, String name) {
        for (MethodNode m : n.methods) if (m.name.equals(name)) return m;
        throw new IllegalStateException("no method " + name);
    }

    private static AbstractInsnNode firstReal(MethodNode m) {
        for (AbstractInsnNode ins = m.instructions.getFirst(); ins != null; ins = ins.getNext()) {
            if (ins.getOpcode() != -1) return ins;
        }
        throw new IllegalStateException("no real insn");
    }
}
