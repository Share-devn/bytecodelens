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

import static org.junit.jupiter.api.Assertions.*;

class RecursiveCallerSearchTest {

    private final ClassAnalyzer analyzer = new ClassAnalyzer();

    @Test
    void findsTwoLevelChain() {
        // a() -> b() -> target()
        ClassEntry t = analyzer.analyze(emptyMethod("p/T", "target"));
        ClassEntry b = analyzer.analyze(callerOf("p/B", "b", "p/T", "target"));
        ClassEntry a = analyzer.analyze(callerOf("p/A", "a", "p/B", "b"));
        var idx = build(t, a, b);

        var search = new RecursiveCallerSearch(idx);
        var root = search.build("p/T", "target", "()V");
        assertEquals(1, root.callers.size());
        var bNode = root.callers.get(0);
        assertEquals("p/B", bNode.ownerInternal);
        assertEquals(1, bNode.callers.size());
        assertEquals("p/A", bNode.callers.get(0).ownerInternal);
    }

    @Test
    void cyclesAreMarkedAndDontExpand() {
        // a() -> b() -> a() (cycle)
        ClassEntry a = analyzer.analyze(callerOf("p/A", "a", "p/B", "b"));
        ClassEntry b = analyzer.analyze(callerOf("p/B", "b", "p/A", "a"));
        var idx = build(a, b);

        var search = new RecursiveCallerSearch(idx);
        var root = search.build("p/A", "a", "()V");
        // root callers -> b (not cyclic itself, since path was just root)
        assertEquals(1, root.callers.size());
        var bNode = root.callers.get(0);
        assertEquals("p/B", bNode.ownerInternal);
        // b's callers include a, which IS in path now -> marked cyclic and not expanded.
        assertEquals(1, bNode.callers.size());
        var aNode = bNode.callers.get(0);
        assertTrue(aNode.cyclic);
        assertTrue(aNode.callers.isEmpty());
    }

    @Test
    void depthCapStopsExpansion() {
        // chain of 4: a -> b -> c -> target
        ClassEntry t = analyzer.analyze(emptyMethod("p/T", "target"));
        ClassEntry c = analyzer.analyze(callerOf("p/C", "c", "p/T", "target"));
        ClassEntry b = analyzer.analyze(callerOf("p/B", "b", "p/C", "c"));
        ClassEntry a = analyzer.analyze(callerOf("p/A", "a", "p/B", "b"));
        var idx = build(t, c, b, a);

        // depth=2 means: root + 2 levels of callers, so we should see c and b but NOT a.
        var search = new RecursiveCallerSearch(idx, 2, 50);
        var root = search.build("p/T", "target", "()V");
        var cNode = root.callers.get(0);
        assertEquals("p/C", cNode.ownerInternal);
        var bNode = cNode.callers.get(0);
        assertEquals("p/B", bNode.ownerInternal);
        // b's callers shouldn't be expanded (depth cap hit).
        assertTrue(bNode.callers.isEmpty());
    }

    @Test
    void duplicateCallersInSameMethodCollapseToOne() {
        // a() calls target() twice
        ClassEntry t = analyzer.analyze(emptyMethod("p/T", "target"));
        ClassEntry a = analyzer.analyze(twoCallerOf("p/A", "a", "p/T", "target"));
        var idx = build(t, a);

        var search = new RecursiveCallerSearch(idx);
        var root = search.build("p/T", "target", "()V");
        assertEquals(1, root.callers.size()); // collapsed
    }

    @Test
    void nullArgsReturnNull() {
        var idx = build();
        var search = new RecursiveCallerSearch(idx);
        assertNull(search.build(null, "x", "()V"));
        assertNull(search.build("x", null, "()V"));
        assertNull(search.build("x", "y", null));
    }

    @Test
    void invalidConstructorArgs() {
        var idx = build();
        assertThrows(IllegalArgumentException.class, () -> new RecursiveCallerSearch(null));
        assertThrows(IllegalArgumentException.class, () -> new RecursiveCallerSearch(idx, 0, 10));
    }

    private static UsageIndex build(ClassEntry... entries) {
        LoadedJar jar = new LoadedJar(Path.of("t.jar"), List.of(entries), List.of(), List.of(), 0, 0);
        UsageIndex idx = new UsageIndex(jar);
        idx.build();
        return idx;
    }

    private static byte[] emptyMethod(String name, String methodName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                methodName, "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] callerOf(String name, String myMethod, String targetOwner, String targetName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                myMethod, "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, targetOwner, targetName, "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] twoCallerOf(String name, String myMethod, String targetOwner, String targetName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                myMethod, "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, targetOwner, targetName, "()V", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, targetOwner, targetName, "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
