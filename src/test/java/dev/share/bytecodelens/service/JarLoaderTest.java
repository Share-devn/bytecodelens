package dev.share.bytecodelens.service;

import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.LoadedJar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarLoaderTest {

    @Test
    void loadsSmallJar(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("tiny.jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            addClass(zip, "a/A");
            addClass(zip, "a/B");
            addClass(zip, "c/D");
        }

        JarLoader loader = new JarLoader();
        LoadedJar result = loader.load(jar, p -> {});

        assertEquals(3, result.classCount());
        assertEquals(0, result.versionedClassCount());
        assertFalse(result.isMultiRelease());
    }

    @Test
    void multiReleaseJarSeparatesVersionedClasses(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("mr.jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            // Root version
            addClassAt(zip, "a/A", "a/A.class");
            addClassAt(zip, "a/B", "a/B.class");
            // Java 11 versioned copy of A
            addClassAt(zip, "a/A", "META-INF/versions/11/a/A.class");
            // Java 17 versioned copy of A
            addClassAt(zip, "a/A", "META-INF/versions/17/a/A.class");
        }

        JarLoader loader = new JarLoader();
        LoadedJar result = loader.load(jar, p -> {});

        // Root: a/A and a/B
        assertEquals(2, result.classCount());
        assertTrue(result.classes().stream().allMatch(c -> !c.isVersioned()));

        // Versioned: a/A x2 (Java 11 + Java 17)
        assertEquals(2, result.versionedClassCount());
        assertTrue(result.versionedClasses().stream().allMatch(ClassEntry::isVersioned));
        assertTrue(result.versionedClasses().stream().anyMatch(c -> c.runtimeVersion() == 11));
        assertTrue(result.versionedClasses().stream().anyMatch(c -> c.runtimeVersion() == 17));

        // No duplicate fqns in root classes()
        long distinctRootNames = result.classes().stream().map(ClassEntry::name).distinct().count();
        assertEquals(result.classCount(), distinctRootNames);

        assertTrue(result.isMultiRelease());
    }

    @Test
    void loadsModuleInfoClass(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("mod.jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            addClass(zip, "a/A");
            // module-info.class with name "test.module", requires java.base, exports a.
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V21, Opcodes.ACC_MODULE, "module-info", null, null, null);
            ModuleVisitor mv = cw.visitModule("test.module", 0, null);
            mv.visitRequire("java.base", Opcodes.ACC_MANDATED, null);
            mv.visitExport("a", 0);
            mv.visitEnd();
            cw.visitEnd();
            zip.putNextEntry(new ZipEntry("module-info.class"));
            zip.write(cw.toByteArray());
            zip.closeEntry();
        }

        JarLoader loader = new JarLoader();
        LoadedJar result = loader.load(jar, p -> {});

        assertEquals(2, result.classCount());
        ClassEntry moduleEntry = result.classes().stream()
                .filter(ClassEntry::isModule)
                .findFirst()
                .orElse(null);
        assertNotNull(moduleEntry, "module-info entry should be present");
        assertNotNull(moduleEntry.moduleInfo());
        assertEquals("test.module", moduleEntry.moduleInfo().name());
        assertEquals(1, moduleEntry.moduleInfo().requires().size());
        assertEquals("java.base", moduleEntry.moduleInfo().requires().get(0).module());
        assertEquals(1, moduleEntry.moduleInfo().exports().size());
        assertEquals("a", moduleEntry.moduleInfo().exports().get(0).packageName());
    }

    private static void addClass(ZipOutputStream zip, String internalName) throws Exception {
        addClassAt(zip, internalName, internalName + ".class");
    }

    private static void addClassAt(ZipOutputStream zip, String internalName, String entryPath) throws Exception {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        zip.putNextEntry(new ZipEntry(entryPath));
        zip.write(cw.toByteArray());
        zip.closeEntry();
    }
}
