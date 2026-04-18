package dev.share.bytecodelens.decompile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecompilersSmokeTest {

    @Test
    void cfrProducesOutput() {
        String out = new CfrDecompiler().decompile("com/x/Hello", makeHelloClass());
        assertNotNull(out);
        assertTrue(out.contains("Hello") || out.contains("class"));
    }

    @Test
    void vineflowerProducesOutput() {
        String out = new VineflowerDecompiler().decompile("com/x/Hello", makeHelloClass());
        assertNotNull(out);
        assertTrue(!out.isBlank(), "Vineflower output should not be blank: " + out);
    }

    @Test
    void procyonProducesOutput() {
        String out = new ProcyonDecompiler().decompile("com/x/Hello", makeHelloClass());
        assertNotNull(out);
        assertTrue(!out.isBlank(), "Procyon output should not be blank: " + out);
    }

    private static byte[] makeHelloClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/x/Hello", null, "java/lang/Object", null);

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "greet", "()Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitLdcInsn("Hello world");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
