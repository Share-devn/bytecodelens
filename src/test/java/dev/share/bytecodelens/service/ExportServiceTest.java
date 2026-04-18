package dev.share.bytecodelens.service;

import dev.share.bytecodelens.decompile.CfrDecompiler;
import dev.share.bytecodelens.model.LoadedJar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportServiceTest {

    @Test
    void exportJarCopiesBytesExactly(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src.jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(src.toFile()))) {
            addClass(zip, "a/A");
        }
        LoadedJar jar = new JarLoader().load(src, p -> {});

        Path dest = dir.resolve("dest.jar");
        new ExportService().exportJar(jar, dest);

        assertTrue(Files.exists(dest));
        assertEquals(Files.size(src), Files.size(dest));
    }

    @Test
    void exportSourcesProducesJavaFilePerClass(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src.jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(src.toFile()))) {
            addClass(zip, "com/example/Foo");
            addClass(zip, "com/example/Bar");
            addClass(zip, "other/Baz");
        }
        LoadedJar jar = new JarLoader().load(src, p -> {});

        Path out = dir.resolve("out");
        ExportService.ExportSummary summary = new ExportService().exportSources(
                jar, new CfrDecompiler(), out, (c, t, m) -> {}, new AtomicBoolean());

        assertEquals(3, summary.exported());
        assertEquals(0, summary.failed());
        assertTrue(Files.exists(out.resolve("com/example/Foo.java")));
        assertTrue(Files.exists(out.resolve("com/example/Bar.java")));
        assertTrue(Files.exists(out.resolve("other/Baz.java")));
        // Produced file should actually contain decompiled content
        String content = Files.readString(out.resolve("com/example/Foo.java"));
        assertTrue(content.contains("Foo"), "decompiled source should reference the class: " + content);
    }

    @Test
    void exportBytecodeProducesTxtPerClass(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src.jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(src.toFile()))) {
            addClass(zip, "a/A");
        }
        LoadedJar jar = new JarLoader().load(src, p -> {});

        Path out = dir.resolve("bc");
        ExportService.ExportSummary summary = new ExportService().exportBytecode(
                jar, new BytecodePrinter(), out, (c, t, m) -> {}, new AtomicBoolean());

        assertEquals(1, summary.exported());
        assertTrue(Files.exists(out.resolve("a/A.txt")));
        String content = Files.readString(out.resolve("a/A.txt"));
        // ASM Textifier output always has a `class` declaration header.
        assertTrue(content.contains("class a/A"), "bytecode listing should contain the class header");
    }

    @Test
    void exportSourcesPlacesVersionedClassesUnderMetaInf(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("mr.jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(src.toFile()))) {
            addClassAt(zip, "a/A", "a/A.class");
            addClassAt(zip, "a/A", "META-INF/versions/17/a/A.class");
        }
        LoadedJar jar = new JarLoader().load(src, p -> {});

        Path out = dir.resolve("out");
        new ExportService().exportSources(jar, new CfrDecompiler(), out,
                (c, t, m) -> {}, new AtomicBoolean());

        assertTrue(Files.exists(out.resolve("a/A.java")));
        assertTrue(Files.exists(out.resolve("META-INF/versions/17/a/A.java")));
    }

    @Test
    void cancelFlagStopsBatch(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("big.jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(src.toFile()))) {
            for (int i = 0; i < 50; i++) addClass(zip, "a/C" + i);
        }
        LoadedJar jar = new JarLoader().load(src, p -> {});

        AtomicBoolean cancel = new AtomicBoolean();
        Path out = dir.resolve("out");
        ExportService.ExportSummary summary = new ExportService().exportSources(
                jar, new CfrDecompiler(), out,
                (c, t, m) -> {
                    if (c >= 3) cancel.set(true); // request cancel after a few
                },
                cancel);

        // Some were written, some remained.
        assertNotEquals(0, summary.exported() + summary.failed());
        assertTrue(summary.cancelled() > 0, "cancel path should report remaining work");
        assertTrue(summary.total() == 50);
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
