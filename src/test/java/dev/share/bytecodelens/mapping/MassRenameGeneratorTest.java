package dev.share.bytecodelens.mapping;

import dev.share.bytecodelens.model.LoadedJar;
import dev.share.bytecodelens.service.JarLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MassRenameGeneratorTest {

    @Test
    void renamesClassSimpleNameWithCaptureGroup(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("rn.jar");
        try (var zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            addClass(zip, "pkg/C_5");
            addClass(zip, "pkg/C_42");
            addClass(zip, "pkg/UntouchedName");
        }
        LoadedJar loaded = new JarLoader().load(jar, p -> {});

        var rules = new MassRenameGenerator.Rules(
                Pattern.compile("^C_(\\d+)$"), "Class$1", true, false, false);
        MappingModel model = new MassRenameGenerator().generate(loaded, rules);

        assertEquals(2, model.classCount());
        assertEquals("pkg/Class5", model.classMap().get("pkg/C_5"));
        assertEquals("pkg/Class42", model.classMap().get("pkg/C_42"));
        assertFalse(model.classMap().containsKey("pkg/UntouchedName"));
    }

    @Test
    void skipsInitAndClinitWhenRenamingMethods(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("m.jar");
        try (var zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "a/A", null, "java/lang/Object", null);
            cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).visitEnd();
            cw.visitMethod(Opcodes.ACC_PUBLIC, "go", "()V", null, null).visitEnd();
            cw.visitEnd();
            zip.putNextEntry(new ZipEntry("a/A.class"));
            zip.write(cw.toByteArray());
            zip.closeEntry();
        }
        LoadedJar loaded = new JarLoader().load(jar, p -> {});

        // Greedy pattern that would match anything.
        var rules = new MassRenameGenerator.Rules(
                Pattern.compile(".+"), "renamed", false, true, false);
        MappingModel model = new MassRenameGenerator().generate(loaded, rules);

        // <init> is skipped; only "go" gets a mapping.
        assertEquals(1, model.methodCount());
        assertTrue(model.methodMap().keySet().stream().anyMatch(k -> k.contains(".go(")));
    }

    @Test
    void emptyModelWhenNothingMatches(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("none.jar");
        try (var zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            addClass(zip, "a/Real");
        }
        LoadedJar loaded = new JarLoader().load(jar, p -> {});

        var rules = new MassRenameGenerator.Rules(
                Pattern.compile("^NeverMatches$"), "X", true, true, true);
        MappingModel model = new MassRenameGenerator().generate(loaded, rules);

        assertEquals(0, model.classCount());
        assertEquals(0, model.methodCount());
        assertEquals(0, model.fieldCount());
    }

    private static void addClass(ZipOutputStream zip, String internalName) throws Exception {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        zip.putNextEntry(new ZipEntry(internalName + ".class"));
        zip.write(cw.toByteArray());
        zip.closeEntry();
    }
}
