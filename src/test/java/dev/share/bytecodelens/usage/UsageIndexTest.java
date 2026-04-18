package dev.share.bytecodelens.usage;

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

class UsageIndexTest {

    private final ClassAnalyzer analyzer = new ClassAnalyzer();

    @Test
    void findsMethodInvokers() {
        ClassEntry callee = analyzer.analyze(makeClassWithMethod("com/x/Callee", "bark", "()V"));
        ClassEntry caller = analyzer.analyze(makeCallerOf("com/x/Caller", "com/x/Callee", "bark", "()V"));
        LoadedJar jar = new LoadedJar(Path.of("t.jar"), List.of(callee, caller), List.of(), List.of(), 0, 0);

        UsageIndex idx = new UsageIndex(jar);
        idx.build();

        var hits = idx.findUsages(new UsageTarget.Method("com/x/Callee", "bark", "()V"));
        assertEquals(1, hits.size());
        assertEquals("com/x/Caller", hits.get(0).inClassFqn());
    }

    @Test
    void findsFieldAccessors() {
        ClassEntry owner = analyzer.analyze(makeClassWithStaticField("com/x/Config", "DEBUG"));
        ClassEntry reader = analyzer.analyze(makeFieldReaderOf("com/x/Reader", "com/x/Config", "DEBUG"));
        LoadedJar jar = new LoadedJar(Path.of("t.jar"), List.of(owner, reader), List.of(), List.of(), 0, 0);

        UsageIndex idx = new UsageIndex(jar);
        idx.build();

        var hits = idx.findUsages(new UsageTarget.Field("com/x/Config", "DEBUG", "Z"));
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).kind() == CallSite.Kind.GETSTATIC);
    }

    @Test
    void findsClassUses() {
        ClassEntry target = analyzer.analyze(makeClassWithMethod("com/x/Target", "m", "()V"));
        ClassEntry user = analyzer.analyze(makeNewOf("com/x/User", "com/x/Target"));
        LoadedJar jar = new LoadedJar(Path.of("t.jar"), List.of(target, user), List.of(), List.of(), 0, 0);

        UsageIndex idx = new UsageIndex(jar);
        idx.build();

        var hits = idx.findUsages(new UsageTarget.Class("com/x/Target"));
        assertTrue(hits.stream().anyMatch(cs -> cs.kind() == CallSite.Kind.NEW));
    }

    @Test
    void emptyForUnusedTargets() {
        ClassEntry callee = analyzer.analyze(makeClassWithMethod("com/x/Unused", "ghost", "()V"));
        LoadedJar jar = new LoadedJar(Path.of("t.jar"), List.of(callee), List.of(), List.of(), 0, 0);

        UsageIndex idx = new UsageIndex(jar);
        idx.build();

        var hits = idx.findUsages(new UsageTarget.Method("com/x/Unused", "ghost", "()V"));
        assertTrue(hits.isEmpty());
    }

    private static byte[] makeClassWithMethod(String name, String methodName, String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, desc, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] makeCallerOf(String name, String targetOwner, String targetName, String targetDesc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, targetOwner, targetName, targetDesc, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] makeClassWithStaticField(String name, String fieldName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, fieldName, "Z", null, 0).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] makeFieldReaderOf(String name, String ownerOwner, String fieldName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, ownerOwner, fieldName, "Z");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] makeNewOf(String name, String targetOwner) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, targetOwner);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
