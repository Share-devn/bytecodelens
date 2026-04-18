package dev.share.bytecodelens.service;

import dev.share.bytecodelens.model.LoadedJar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Defensive tests for jar loading edge cases: broken zips, corrupt class bytes, unusual entry names.
 * Purpose: prove the loader never hangs or crashes the process on hostile input. A graceful failure
 * (IOException propagated, or silently skipped bad entries) is acceptable — uncaught RuntimeException is not.
 */
class JarLoaderEdgeCasesTest {

    @Test
    void nonZipFileFailsGracefully(@TempDir Path dir) throws Exception {
        Path bogus = dir.resolve("not-a-jar.jar");
        Files.writeString(bogus, "this is not a zip file at all");

        JarLoader loader = new JarLoader();
        // Either IOException or other IO-level failure is expected — just not silent success.
        assertThrows(IOException.class, () -> loader.load(bogus, p -> {}));
    }

    @Test
    void zipWithCorruptClassBytesSkipsBadEntry(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("partial.jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            // One valid class
            addClass(zip, "ok/Valid");
            // One bogus "class" — 20 bytes of garbage
            zip.putNextEntry(new ZipEntry("bad/Corrupt.class"));
            zip.write(new byte[]{0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
                    0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
                    0x10, 0x11, 0x12, 0x13});
            zip.closeEntry();
            // Another valid class
            addClass(zip, "ok/AlsoValid");
        }

        JarLoader loader = new JarLoader();
        LoadedJar result = loader.load(jar, p -> {});

        // Corrupt entry is silently dropped, valid ones survive
        assertEquals(2, result.classCount());
        assertTrue(result.classes().stream().anyMatch(c -> c.name().equals("ok.Valid")));
        assertTrue(result.classes().stream().anyMatch(c -> c.name().equals("ok.AlsoValid")));
    }

    @Test
    void zeroByteClassEntryIsSkipped(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("empty-entry.jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            addClass(zip, "ok/Good");
            zip.putNextEntry(new ZipEntry("empty/Ghost.class"));
            // write nothing
            zip.closeEntry();
        }

        JarLoader loader = new JarLoader();
        LoadedJar result = loader.load(jar, p -> {});
        assertEquals(1, result.classCount());
    }

    @Test
    void unicodeClassNameRoundtrips(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("unicode.jar");
        // Unicode class names are legal in the JVM spec even if rare.
        String internal = "\u043f\u0430\u043a\u0435\u0442/\u041a\u043b\u0430\u0441\u0441"; // пакет/Класс
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
            cw.visitEnd();
            zip.putNextEntry(new ZipEntry(internal + ".class"));
            zip.write(cw.toByteArray());
            zip.closeEntry();
        }

        JarLoader loader = new JarLoader();
        LoadedJar result = loader.load(jar, p -> {});
        assertEquals(1, result.classCount());
        assertEquals(internal, result.classes().get(0).internalName());
        assertEquals("\u041a\u043b\u0430\u0441\u0441", result.classes().get(0).simpleName());
    }

    @Test
    void extremelyLongEntryNameDoesNotCrash(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("long.jar");
        // Build a 2kb-long internal class name.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("pkg").append(i).append('/');
        }
        sb.append("VeryLongName");
        String internal = sb.toString();

        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
            cw.visitEnd();
            zip.putNextEntry(new ZipEntry(internal + ".class"));
            zip.write(cw.toByteArray());
            zip.closeEntry();
        }

        JarLoader loader = new JarLoader();
        LoadedJar result = loader.load(jar, p -> {});
        assertEquals(1, result.classCount());
        assertEquals("VeryLongName", result.classes().get(0).simpleName());
    }

    @Test
    void emptyJarLoadsWithZeroClasses(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("empty.jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            // Not a single entry
        }

        JarLoader loader = new JarLoader();
        LoadedJar result = loader.load(jar, p -> {});
        assertEquals(0, result.classCount());
        assertEquals(0, result.versionedClassCount());
        assertEquals(0, result.resourceCount());
    }

    @Test
    void obfuscatorDetectorSurvivesEmptyJar(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("empty.jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            // intentionally empty
        }

        JarLoader loader = new JarLoader();
        LoadedJar result = loader.load(jar, p -> {});

        // Both detector paths must handle a 0-class jar without division-by-zero or NPE.
        var v1 = new ObfuscatorDetector().detect(result);
        assertEquals(0.0, v1.confidence(), 0.001);

        var v2 = new dev.share.bytecodelens.detector.ObfuscatorDetectorV2().analyze(result);
        assertTrue(v2.detections() != null);
    }

    @Test
    void jarWithOnlyResourcesLoads(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("resources-only.jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            zip.putNextEntry(new ZipEntry("config.properties"));
            zip.write("foo=bar\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("README.txt"));
            zip.write("hello".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        JarLoader loader = new JarLoader();
        LoadedJar result = loader.load(jar, p -> {});
        assertEquals(0, result.classCount());
        assertEquals(2, result.resourceCount());
    }

    @Test
    void controlCharsInResourceEntryNameDoNotCrash(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("ctl.jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            addClass(zip, "ok/Good");
            // Resource with control chars in its name
            zip.putNextEntry(new ZipEntry("weird/\u0001\u0002\u0003.txt"));
            zip.write("hello".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        JarLoader loader = new JarLoader();
        LoadedJar result = loader.load(jar, p -> {});
        assertEquals(1, result.classCount());
        assertEquals(1, result.resourceCount());
        // We don't require the control chars to be sanitised away — just that loading completes.
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
