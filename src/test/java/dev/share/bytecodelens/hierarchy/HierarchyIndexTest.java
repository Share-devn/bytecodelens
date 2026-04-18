package dev.share.bytecodelens.hierarchy;

import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.LoadedJar;
import dev.share.bytecodelens.service.ClassAnalyzer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HierarchyIndexTest {

    private final ClassAnalyzer analyzer = new ClassAnalyzer();

    @Test
    void buildsAncestorChainForSimpleClass() {
        LoadedJar jar = jar(
                klass("com/x/Parent", "java/lang/Object"),
                klass("com/x/Child", "com/x/Parent"));
        HierarchyIndex idx = new HierarchyIndex(jar);
        idx.build();

        HierarchyNode root = idx.buildAncestorChain("com/x/Child");
        List<String> walk = walk(root);
        assertEquals(List.of("java/lang/Object", "com/x/Parent", "com/x/Child"), walk);
    }

    @Test
    void findsDirectSubclasses() {
        LoadedJar jar = jar(
                klass("com/x/Base", "java/lang/Object"),
                klass("com/x/A", "com/x/Base"),
                klass("com/x/B", "com/x/Base"),
                klass("com/x/C", "com/x/A"));
        HierarchyIndex idx = new HierarchyIndex(jar);
        idx.build();

        HierarchyNode subs = idx.buildSubtypeTree("com/x/Base");
        assertEquals("com/x/Base", subs.internalName());
        assertEquals(2, subs.children().size());
        // At least one child should have C as grandchild
        HierarchyNode a = subs.children().stream()
                .filter(n -> n.internalName().equals("com/x/A"))
                .findFirst().orElseThrow();
        assertEquals(1, a.children().size());
        assertEquals("com/x/C", a.children().get(0).internalName());
    }

    @Test
    void findsInterfaceImplementers() {
        LoadedJar jar = jar(
                iface("com/x/Runnable2"),
                klassWithInterface("com/x/Impl", "java/lang/Object", "com/x/Runnable2"));
        HierarchyIndex idx = new HierarchyIndex(jar);
        idx.build();

        HierarchyNode subs = idx.buildSubtypeTree("com/x/Runnable2");
        assertEquals(1, subs.children().size());
        assertEquals("com/x/Impl", subs.children().get(0).internalName());
    }

    @Test
    void handlesMissingSuperGracefully() {
        LoadedJar jar = jar(
                klass("com/x/External", "com/external/NotInJar"));
        HierarchyIndex idx = new HierarchyIndex(jar);
        idx.build();

        HierarchyNode chain = idx.buildAncestorChain("com/x/External");
        List<String> walk = walk(chain);
        assertTrue(walk.contains("com/x/External"));
        assertTrue(walk.contains("com/external/NotInJar"));
    }

    private LoadedJar jar(byte[]... classes) {
        List<ClassEntry> entries = new ArrayList<>();
        for (byte[] b : classes) entries.add(analyzer.analyze(b));
        return new LoadedJar(Path.of("t.jar"), entries, List.of(), List.of(), 0, 0);
    }

    private static byte[] klass(String name, String superName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, superName, null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] klassWithInterface(String name, String superName, String iface) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, superName, new String[]{iface});
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] iface(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> walk(HierarchyNode node) {
        List<String> out = new ArrayList<>();
        collect(node, out);
        return out;
    }

    private static void collect(HierarchyNode node, List<String> out) {
        out.add(node.internalName());
        for (HierarchyNode child : node.children()) collect(child, out);
    }
}
