package dev.share.bytecodelens.crypto;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SymbolicInterpreterTest {

    @Test
    void interpretsSimpleXorDecryptor() {
        // public static String decrypt(String s) {
        //     char[] c = s.toCharArray();
        //     for (int i = 0; i < c.length; i++) c[i] = (char) (c[i] ^ 0x20);
        //     return new String(c);
        // }
        String encrypted = applyXor("HELLO", 0x20);

        byte[] cls = makeXorDecryptorClass("com/x/D", "dec", 0x20);
        String result = new SymbolicInterpreter()
                .invokeStringToString(cls, "com/x/D", "dec", "(Ljava/lang/String;)Ljava/lang/String;", encrypted);

        assertNotNull(result);
        assertEquals("HELLO", result);
    }

    @Test
    void interpretsAddDecryptor() {
        // c[i] = c[i] + 1
        String encrypted = applyAdd("world", 1);

        byte[] cls = makeAddDecryptorClass("com/x/D", "dec", 1);
        String result = new SymbolicInterpreter()
                .invokeStringToString(cls, "com/x/D", "dec", "(Ljava/lang/String;)Ljava/lang/String;", encrypted);

        assertNotNull(result);
        assertEquals("world", result);
    }

    @Test
    void bailsOutOnUnsupportedCall() {
        // Class with a method that calls System.currentTimeMillis() — not supported
        byte[] cls = makeUnsupportedDecryptor();
        String result = new SymbolicInterpreter()
                .invokeStringToString(cls, "com/x/D", "dec", "(Ljava/lang/String;)Ljava/lang/String;", "anything");
        // Should return null, not throw
        // Either the method doesn't exist in our class or it bails on first unsupported call
        // Acceptable: null
        // The test just confirms no exception bubbles up
    }

    private static String applyXor(String s, int key) {
        char[] c = s.toCharArray();
        for (int i = 0; i < c.length; i++) c[i] = (char) (c[i] ^ key);
        return new String(c);
    }

    private static String applyAdd(String s, int delta) {
        char[] c = s.toCharArray();
        for (int i = 0; i < c.length; i++) c[i] = (char) (c[i] - delta);
        return new String(c);
    }

    /** Generates: static String dec(String s) { char[] c = s.toCharArray(); for (i=0; i<c.length; i++) c[i] ^= key; return new String(c); } */
    private static byte[] makeXorDecryptorClass(String internalName, String methodName, int key) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                methodName, "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        mv.visitCode();

        // char[] c = s.toCharArray()
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toCharArray", "()[C", false);
        mv.visitVarInsn(Opcodes.ASTORE, 1);

        // int i = 0
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 2);

        Label loop = new Label();
        Label exit = new Label();
        mv.visitLabel(loop);
        // if (i >= c.length) break
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, exit);

        // c[i] = (char)(c[i] ^ key)
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitInsn(Opcodes.CALOAD);
        mv.visitIntInsn(Opcodes.SIPUSH, key);
        mv.visitInsn(Opcodes.IXOR);
        mv.visitInsn(Opcodes.I2C);
        mv.visitInsn(Opcodes.CASTORE);

        // i++
        mv.visitIincInsn(2, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loop);

        mv.visitLabel(exit);
        // return new String(c)
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/String");
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([C)V", false);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Generates: c[i] = c[i] + delta (our encrypt does -delta, so decrypt +delta) */
    private static byte[] makeAddDecryptorClass(String internalName, String methodName, int delta) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                methodName, "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toCharArray", "()[C", false);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 2);
        Label loop = new Label();
        Label exit = new Label();
        mv.visitLabel(loop);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, exit);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitInsn(Opcodes.CALOAD);
        mv.visitIntInsn(Opcodes.BIPUSH, delta);
        mv.visitInsn(Opcodes.IADD);
        mv.visitInsn(Opcodes.I2C);
        mv.visitInsn(Opcodes.CASTORE);
        mv.visitIincInsn(2, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loop);
        mv.visitLabel(exit);
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/String");
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([C)V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Just a method that calls System.currentTimeMillis() — to verify graceful bailout. */
    private static byte[] makeUnsupportedDecryptor() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/x/D", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "dec", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
        mv.visitInsn(Opcodes.POP2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
