package dev.share.bytecodelens.service;

import dev.share.bytecodelens.model.LoadedJar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JmodSupportTest {

    @Test
    void isJmodRecognisesHeaderAndRejectsPlainZip(@TempDir Path dir) throws Exception {
        // Fabricate a valid-ish jmod by writing the 4-byte header + a real ZIP.
        Path jmod = dir.resolve("fake.jmod");
        Path zip = dir.resolve("plain.zip");

        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(zipBytes)) {
            addClass(zos, "classes/demo/Foo");
        }
        byte[] z = zipBytes.toByteArray();

        try (var out = new FileOutputStream(jmod.toFile())) {
            out.write(new byte[]{'J', 'M', 0x01, 0x00});
            out.write(z);
        }
        Files.write(zip, z);

        assertTrue(JmodSupport.isJmod(jmod), "should recognise JM\\1\\0 header");
        assertFalse(JmodSupport.isJmod(zip), "plain ZIP must not be flagged as jmod");
    }

    @Test
    void loadsClassFromJmod(@TempDir Path dir) throws Exception {
        Path jmod = dir.resolve("demo.jmod");
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(zipBytes)) {
            addClass(zos, "classes/demo/Foo");
            addClass(zos, "classes/demo/Bar");
        }
        try (var out = new FileOutputStream(jmod.toFile())) {
            out.write(new byte[]{'J', 'M', 0x01, 0x00});
            out.write(zipBytes.toByteArray());
        }

        LoadedJar loaded = new JarLoader().load(jmod, p -> {});
        assertEquals(2, loaded.classCount(), "both classes should surface");
        // Class names should not carry the "classes/" prefix.
        assertTrue(loaded.classes().stream().anyMatch(c -> c.name().equals("demo.Foo")));
        assertTrue(loaded.classes().stream().anyMatch(c -> c.name().equals("demo.Bar")));
    }

    @Test
    void plainZipStillLoadsAsJar(@TempDir Path dir) throws Exception {
        // Regression: after adding jmod detection we must not break regular jars.
        Path jar = dir.resolve("ok.jar");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            addClass(zos, "demo/Real");
        }
        LoadedJar loaded = new JarLoader().load(jar, p -> {});
        assertEquals(1, loaded.classCount());
    }

    private static void addClass(ZipOutputStream zip, String internalPath) throws Exception {
        // Strip classes/ prefix for the actual JVM-level name the class reports about itself.
        String internalName = internalPath.startsWith("classes/")
                ? internalPath.substring("classes/".length())
                : internalPath;
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        zip.putNextEntry(new ZipEntry(internalPath + ".class"));
        zip.write(cw.toByteArray());
        zip.closeEntry();
    }
}
