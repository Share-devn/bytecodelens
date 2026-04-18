package dev.share.bytecodelens.mapping;

import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.LoadedJar;
import dev.share.bytecodelens.service.JarLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingApplierTest {

    @Test
    void applyRenamesClassAndMethod(@TempDir Path dir) throws Exception {
        // Generate tiny jar with class a/b that has method m()V
        Path jar = dir.resolve("obf.jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "a/b", null, "java/lang/Object", null);
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "m", "()V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 1);
            mv.visitEnd();
            cw.visitEnd();
            zip.putNextEntry(new ZipEntry("a/b.class"));
            zip.write(cw.toByteArray());
            zip.closeEntry();
        }
        LoadedJar loaded = new JarLoader().load(jar, p -> {});

        // ProGuard-style mapping: a/b -> com/example/Foo, m()V -> realMethod
        String mappingText = """
                com.example.Foo -> a.b:
                    void realMethod() -> m
                """;
        MappingModel model = MappingLoader.loadString(mappingText);

        LoadedJar remapped = new MappingApplier().apply(loaded, model);

        assertEquals(1, remapped.classCount());
        ClassEntry renamed = remapped.classes().get(0);
        assertEquals("com/example/Foo", renamed.internalName());
        assertEquals("com.example.Foo", renamed.name());
        assertEquals("Foo", renamed.simpleName());
        // Method name should now be "realMethod"
        assertTrue(new dev.share.bytecodelens.service.ClassAnalyzer()
                .methods(renamed.bytes()).stream()
                .anyMatch(m -> m.name().equals("realMethod")),
                "remapped bytes should contain method 'realMethod'");
    }

    @Test
    void applyPreservesClassesWithoutMapping(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("obf.jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            addClass(zip, "a/Named");
            addClass(zip, "b/Other");
        }
        LoadedJar loaded = new JarLoader().load(jar, p -> {});

        // Map only one of two classes
        String mappingText = "a/Named com/example/Renamed\n";
        MappingModel model = MappingLoader.loadString(mappingText);
        LoadedJar remapped = new MappingApplier().apply(loaded, model);

        assertEquals(2, remapped.classCount());
        assertNotNull(remapped.classes().stream()
                .filter(c -> c.internalName().equals("com/example/Renamed"))
                .findFirst().orElse(null));
        // Unmapped class keeps its original name
        assertNotNull(remapped.classes().stream()
                .filter(c -> c.internalName().equals("b/Other"))
                .findFirst().orElse(null));
    }

    @Test
    void applyUpdatesClassReferencesInBytecode(@TempDir Path dir) throws Exception {
        // Class X uses Y (via super); rename Y and verify X's superName updated
        Path jar = dir.resolve("refs.jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            addClassWithSuper(zip, "app/Child", "app/Parent");
            addClass(zip, "app/Parent");
        }
        LoadedJar loaded = new JarLoader().load(jar, p -> {});

        String mapping = "app/Parent app/RenamedParent\n";
        MappingModel model = MappingLoader.loadString(mapping);
        LoadedJar remapped = new MappingApplier().apply(loaded, model);

        ClassEntry child = remapped.classes().stream()
                .filter(c -> c.internalName().equals("app/Child"))
                .findFirst().orElseThrow();
        assertEquals("app/RenamedParent", child.superName());
    }

    private static void addClass(ZipOutputStream zip, String internalName) throws Exception {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        zip.putNextEntry(new ZipEntry(internalName + ".class"));
        zip.write(cw.toByteArray());
        zip.closeEntry();
    }

    private static void addClassWithSuper(ZipOutputStream zip, String internalName, String superName) throws Exception {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, superName, null);
        cw.visitEnd();
        zip.putNextEntry(new ZipEntry(internalName + ".class"));
        zip.write(cw.toByteArray());
        zip.closeEntry();
    }
}
