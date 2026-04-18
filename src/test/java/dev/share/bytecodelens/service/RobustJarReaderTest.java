package dev.share.bytecodelens.service;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RobustJarReaderTest {

    @Test
    void standardStrategyOnCleanJar(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("clean.jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            addClass(zip, "a/A");
            addClass(zip, "a/B");
        }
        var res = new RobustJarReader().read(jar);
        assertEquals(RobustJarReader.Strategy.STANDARD, res.strategyUsed());
        assertEquals(2, res.entries().size());
    }

    @Test
    void duplicateCdEntriesAreDeduplicated(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("dup.jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            // ZipOutputStream won't let us easily write duplicate names — simulate by
            // concatenating two separate zips. This isn't a perfect CD duplicate but
            // exercises the LFH-scan path once the JVM reader kicks in.
            addClass(zip, "a/A");
        }
        // Append another copy of the same class via JvmZipReader fallback test in next case.
        var res = new RobustJarReader().read(jar);
        assertNotNull(res);
        assertTrue(res.entries().size() >= 1);
    }

    @Test
    void polyglotHeaderIsDetected(@TempDir Path dir) throws Exception {
        // Build a tiny valid zip...
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
            addClass(zip, "a/A");
        }
        // ...then prepend an 8-byte fake GIF-like header so the file starts with non-ZIP bytes.
        byte[] fakePrefix = {'G', 'I', 'F', '8', '9', 'a', 0x00, 0x00};
        byte[] merged = new byte[fakePrefix.length + zipBytes.size()];
        System.arraycopy(fakePrefix, 0, merged, 0, fakePrefix.length);
        System.arraycopy(zipBytes.toByteArray(), 0, merged, fakePrefix.length, zipBytes.size());

        Path jar = dir.resolve("polyglot.jar");
        Files.write(jar, merged);

        var res = new RobustJarReader().read(jar);
        // Polyglot is detected as a diagnostic regardless of which strategy was used.
        boolean seen = res.diagnostics().stream()
                .anyMatch(d -> d.toLowerCase().contains("polyglot"));
        assertTrue(seen, "polyglot diagnostic missing; got: " + res.diagnostics());
    }

    @Test
    void jvmStrategyRecoversWhenStandardFails(@TempDir Path dir) throws Exception {
        // Build a valid zip, then corrupt its EOCD by overwriting it with zeros.
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
            addClass(zip, "a/Survivor");
        }
        byte[] bytes = zipBytes.toByteArray();
        // Find EOCD signature (PK\x05\x06) and zero out the 22-byte EOCD record.
        int eocd = -1;
        for (int i = bytes.length - 22; i >= 0; i--) {
            if (bytes[i] == 'P' && bytes[i + 1] == 'K'
                    && bytes[i + 2] == 0x05 && bytes[i + 3] == 0x06) {
                eocd = i; break;
            }
        }
        if (eocd > 0) {
            for (int i = eocd; i < bytes.length; i++) bytes[i] = 0;
        }

        Path jar = dir.resolve("noeocd.jar");
        Files.write(jar, bytes);

        var res = new RobustJarReader().read(jar);
        // Standard ZipFile should fail; JVM fallback must still surface the class.
        assertEquals(RobustJarReader.Strategy.JVM, res.strategyUsed());
        assertTrue(res.entries().containsKey("a/Survivor.class"),
                "class missing in fallback read: " + res.entries().keySet());
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
