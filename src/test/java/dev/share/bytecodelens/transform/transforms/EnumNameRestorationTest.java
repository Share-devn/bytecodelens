package dev.share.bytecodelens.transform.transforms;

import dev.share.bytecodelens.transform.TransformContext;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import static org.junit.jupiter.api.Assertions.*;

class EnumNameRestorationTest {

    @Test
    void renamesObfuscatedFieldToLiteralFromClinit() {
        // Synthesize an enum equivalent to:
        //   enum E { RED("RED",0), BLUE("BLUE",1) }   <- but with field names "a", "b"
        byte[] bytes = enumClass("p/E",
                new String[]{"a", "b"},
                new String[]{"RED", "BLUE"});
        ClassNode node = parse(bytes);

        TransformContext ctx = new TransformContext();
        ctx.enterPass("enum-name-restoration");
        new EnumNameRestoration().transform(node, ctx);
        ctx.exitPass();

        // Fields should now be RED + BLUE (plus any synthetic $VALUES).
        assertNotNull(field(node, "RED"));
        assertNotNull(field(node, "BLUE"));
        assertNull(field(node, "a"));
        assertNull(field(node, "b"));
        assertEquals(2, ctx.totalFor("enum-name-restoration"));
    }

    @Test
    void leavesNonEnumClassesAlone() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "p/Plain", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "a", "I", null, null).visitEnd();
        cw.visitEnd();
        ClassNode n = parse(cw.toByteArray());
        new EnumNameRestoration().transform(n, new TransformContext());
        assertNotNull(field(n, "a"));
    }

    @Test
    void leavesAlreadyMatchingNamesAlone() {
        // field name == literal already → no-op.
        byte[] bytes = enumClass("p/E", new String[]{"RED"}, new String[]{"RED"});
        ClassNode node = parse(bytes);
        TransformContext ctx = new TransformContext();
        ctx.enterPass("enum-name-restoration");
        new EnumNameRestoration().transform(node, ctx);
        ctx.exitPass();
        assertEquals(0, ctx.totalFor("enum-name-restoration"));
    }

    @Test
    void rejectsInvalidIdentifierLiterals() {
        // Literal contains a space — not valid as a Java identifier; must NOT rename.
        byte[] bytes = enumClass("p/E", new String[]{"a"}, new String[]{"with space"});
        ClassNode node = parse(bytes);
        new EnumNameRestoration().transform(node, new TransformContext());
        assertNotNull(field(node, "a"));
    }

    private static ClassNode parse(byte[] b) {
        ClassNode n = new ClassNode();
        new ClassReader(b).accept(n, 0);
        return n;
    }

    private static FieldNode field(ClassNode n, String name) {
        for (FieldNode f : n.fields) if (f.name.equals(name)) return f;
        return null;
    }

    /** Build an enum-like class with the given field names and constructor literal names. */
    private static byte[] enumClass(String name, String[] fieldNames, String[] literals) {
        if (fieldNames.length != literals.length) throw new IllegalArgumentException();
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM | Opcodes.ACC_SUPER,
                name, null, "java/lang/Enum", null);

        // Static fields for each constant.
        for (String fn : fieldNames) {
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
                    fn, "L" + name + ";", null, null).visitEnd();
        }

        // Mandatory enum ctor (String, int) -> super.
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PRIVATE,
                "<init>", "(Ljava/lang/String;I)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitVarInsn(Opcodes.ILOAD, 2);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Enum",
                "<init>", "(Ljava/lang/String;I)V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        // <clinit>: emit canonical pattern per constant.
        MethodVisitor cl = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        cl.visitCode();
        for (int i = 0; i < fieldNames.length; i++) {
            cl.visitTypeInsn(Opcodes.NEW, name);
            cl.visitInsn(Opcodes.DUP);
            cl.visitLdcInsn(literals[i]);
            cl.visitIntInsn(Opcodes.BIPUSH, i);
            cl.visitMethodInsn(Opcodes.INVOKESPECIAL, name, "<init>", "(Ljava/lang/String;I)V", false);
            cl.visitFieldInsn(Opcodes.PUTSTATIC, name, fieldNames[i], "L" + name + ";");
        }
        cl.visitInsn(Opcodes.RETURN);
        cl.visitMaxs(0, 0);
        cl.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
