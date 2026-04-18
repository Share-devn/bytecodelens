package dev.share.bytecodelens.service;

import dev.share.bytecodelens.model.ClassEntry;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassAnalyzerTest {

    @Test
    void analyzesGeneratedClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                "com/example/Hello", null, "java/lang/Object",
                new String[]{"java/lang/Runnable"});
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "COUNT", "I", null, 42).visitEnd();
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();
        cw.visitEnd();
        byte[] bytes = cw.toByteArray();

        ClassAnalyzer analyzer = new ClassAnalyzer();
        ClassEntry entry = analyzer.analyze(bytes);

        assertEquals("com/example/Hello", entry.internalName());
        assertEquals("com.example.Hello", entry.name());
        assertEquals("com.example", entry.packageName());
        assertEquals("Hello", entry.simpleName());
        assertEquals("java/lang/Object", entry.superName());
        assertTrue(entry.interfaces().contains("java/lang/Runnable"));
        assertEquals(1, entry.methodCount());
        assertEquals(1, entry.fieldCount());
        assertEquals(65, entry.majorVersion());
    }
}
