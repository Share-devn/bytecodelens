package dev.share.bytecodelens.diff;

import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.LoadedJar;
import dev.share.bytecodelens.service.ClassAnalyzer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarDifferTest {

    private final ClassAnalyzer analyzer = new ClassAnalyzer();
    private final JarDiffer differ = new JarDiffer();

    @Test
    void detectsAddedClass() {
        LoadedJar a = jar();
        LoadedJar b = jar(classOf("com/x/Foo"));
        var result = differ.diff(a, b);
        assertEquals(1, result.stats().addedClasses());
        assertEquals(0, result.stats().removedClasses());
    }

    @Test
    void detectsRemovedClass() {
        LoadedJar a = jar(classOf("com/x/Foo"));
        LoadedJar b = jar();
        var result = differ.diff(a, b);
        assertEquals(0, result.stats().addedClasses());
        assertEquals(1, result.stats().removedClasses());
    }

    @Test
    void detectsUnchangedIdenticalClass() {
        byte[] bytes = classOf("com/x/Foo");
        LoadedJar a = jar(bytes);
        LoadedJar b = jar(bytes);
        var result = differ.diff(a, b);
        assertEquals(1, result.stats().unchangedClasses());
        assertEquals(0, result.stats().modifiedClasses());
    }

    @Test
    void detectsModifiedMethodBytecode() {
        LoadedJar a = jar(classWithMethod("com/x/Foo", "bar", 1));
        LoadedJar b = jar(classWithMethod("com/x/Foo", "bar", 2));
        var result = differ.diff(a, b);
        assertEquals(1, result.stats().modifiedClasses());
        var classDiff = result.classes().stream()
                .filter(c -> c.change() == ChangeType.MODIFIED)
                .findFirst().orElseThrow();
        assertTrue(classDiff.methods().stream()
                .anyMatch(m -> m.change() == ChangeType.MODIFIED && m.name().equals("bar")));
    }

    @Test
    void detectsAddedMethodInClass() {
        LoadedJar a = jar(classOf("com/x/Foo"));
        LoadedJar b = jar(classWithMethod("com/x/Foo", "newMethod", 1));
        var result = differ.diff(a, b);
        var classDiff = result.classes().stream()
                .filter(c -> c.change() == ChangeType.MODIFIED)
                .findFirst().orElseThrow();
        assertTrue(classDiff.methods().stream()
                .anyMatch(m -> m.change() == ChangeType.ADDED && m.name().equals("newMethod")));
    }

    private LoadedJar jar(byte[]... classes) {
        List<ClassEntry> entries = java.util.Arrays.stream(classes)
                .map(analyzer::analyze)
                .toList();
        return new LoadedJar(Path.of("t.jar"), entries, List.of(), List.of(), 0, 0);
    }

    private static byte[] classOf(String internalName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] classWithMethod(String internalName, String methodName, int constantValue) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()I", null, null);
        mv.visitCode();
        if (constantValue == 1) {
            mv.visitInsn(Opcodes.ICONST_1);
        } else {
            mv.visitInsn(Opcodes.ICONST_2);
        }
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
