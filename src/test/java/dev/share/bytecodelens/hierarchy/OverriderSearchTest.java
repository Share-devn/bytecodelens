package dev.share.bytecodelens.hierarchy;

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

class OverriderSearchTest {

    private final ClassAnalyzer analyzer = new ClassAnalyzer();

    @Test
    void findsDirectSubclassOverride() {
        ClassEntry base = analyzer.analyze(classWithVirtualMethod("p/Base", null, "java/lang/Object", "run"));
        ClassEntry sub = analyzer.analyze(classWithVirtualMethod("p/Sub", null, "p/Base", "run"));
        var hi = build(base, sub);

        var hits = OverriderSearch.findOverriders(hi, "p/Base", "run", "()V");
        assertEquals(1, hits.size());
        assertEquals("p/Sub", hits.get(0).ownerInternal());
    }

    @Test
    void findsTransitiveOverride() {
        ClassEntry base = analyzer.analyze(classWithVirtualMethod("p/Base", null, "java/lang/Object", "run"));
        ClassEntry mid = analyzer.analyze(classWithoutMethod("p/Mid", "p/Base"));
        ClassEntry leaf = analyzer.analyze(classWithVirtualMethod("p/Leaf", null, "p/Mid", "run"));
        var hi = build(base, mid, leaf);

        var hits = OverriderSearch.findOverriders(hi, "p/Base", "run", "()V");
        assertEquals(1, hits.size());
        assertEquals("p/Leaf", hits.get(0).ownerInternal());
    }

    @Test
    void findsInterfaceImplementations() {
        ClassEntry iface = analyzer.analyze(interfaceWithMethod("p/I", "act"));
        ClassEntry impl = analyzer.analyze(classWithVirtualMethod("p/Impl", "p/I", "java/lang/Object", "act"));
        var hi = build(iface, impl);

        var hits = OverriderSearch.findOverriders(hi, "p/I", "act", "()V");
        assertEquals(1, hits.size());
        assertEquals("p/Impl", hits.get(0).ownerInternal());
    }

    @Test
    void skipsClassesThatDontOverride() {
        ClassEntry base = analyzer.analyze(classWithVirtualMethod("p/Base", null, "java/lang/Object", "run"));
        ClassEntry sub = analyzer.analyze(classWithoutMethod("p/Sub", "p/Base"));
        var hi = build(base, sub);

        var hits = OverriderSearch.findOverriders(hi, "p/Base", "run", "()V");
        assertTrue(hits.isEmpty());
    }

    @Test
    void skipsConstructors() {
        ClassEntry base = analyzer.analyze(classWithVirtualMethod("p/Base", null, "java/lang/Object", "run"));
        ClassEntry sub = analyzer.analyze(classWithVirtualMethod("p/Sub", null, "p/Base", "run"));
        var hi = build(base, sub);
        assertTrue(OverriderSearch.findOverriders(hi, "p/Base", "<init>", "()V").isEmpty());
    }

    @Test
    void implementersListsAllSubtypes() {
        ClassEntry iface = analyzer.analyze(interfaceWithMethod("p/I", "act"));
        ClassEntry a = analyzer.analyze(classWithoutMethod("p/A", "java/lang/Object", "p/I"));
        ClassEntry b = analyzer.analyze(classWithoutMethod("p/B", "java/lang/Object", "p/I"));
        var hi = build(iface, a, b);

        var impls = OverriderSearch.findImplementers(hi, "p/I");
        assertEquals(2, impls.size());
        assertTrue(impls.contains("p/A"));
        assertTrue(impls.contains("p/B"));
    }

    @Test
    void nullArgsReturnEmpty() {
        var hi = build();
        assertTrue(OverriderSearch.findOverriders(null, "x", "y", "()V").isEmpty());
        assertTrue(OverriderSearch.findOverriders(hi, null, "y", "()V").isEmpty());
        assertTrue(OverriderSearch.findOverriders(hi, "x", null, "()V").isEmpty());
        assertTrue(OverriderSearch.findOverriders(hi, "x", "y", null).isEmpty());
        assertTrue(OverriderSearch.findImplementers(null, "x").isEmpty());
    }

    private static HierarchyIndex build(ClassEntry... entries) {
        LoadedJar jar = new LoadedJar(Path.of("t.jar"), List.of(entries), List.of(), List.of(), 0, 0);
        HierarchyIndex hi = new HierarchyIndex(jar);
        hi.build();
        return hi;
    }

    private static byte[] classWithVirtualMethod(String name, String iface, String superName, String methodName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        String[] interfaces = iface == null ? null : new String[]{iface};
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, superName, interfaces);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] classWithoutMethod(String name, String superName, String... interfaces) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        String[] ifs = interfaces.length == 0 ? null : interfaces;
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, superName, ifs);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] interfaceWithMethod(String name, String methodName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                name, null, "java/lang/Object", null);
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, methodName, "()V", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
