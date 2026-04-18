package dev.share.bytecodelens.search;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumericSearchTest {

    @Test
    void parseAcceptsIntHexLongFloatDouble() {
        assertEquals(42L, SearchEngine.parseNumericQuery("42").longValue());
        assertEquals(0xDEADBEEFL, SearchEngine.parseNumericQuery("0xDEADBEEF").longValue());
        assertEquals(100L, SearchEngine.parseNumericQuery("100L").longValue());
        assertEquals(3.14f, SearchEngine.parseNumericQuery("3.14f").floatValue(), 1e-6);
        assertEquals(2.718, SearchEngine.parseNumericQuery("2.718").doubleValue(), 1e-6);
        assertNull(SearchEngine.parseNumericQuery("not a number"));
        assertNull(SearchEngine.parseNumericQuery(""));
        assertNull(SearchEngine.parseNumericQuery(null));
    }

    @Test
    void findsIntConstantInMethod(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("n.jar");
        try (var zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "a/A", null, "java/lang/Object", null);
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "magic", "()I", null, null);
            mv.visitCode();
            mv.visitLdcInsn(0xDEADBEEF);  // exercise LdcInsnNode path
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitMaxs(1, 0);
            mv.visitEnd();
            cw.visitEnd();
            zip.putNextEntry(new ZipEntry("a/A.class"));
            zip.write(cw.toByteArray());
            zip.closeEntry();
        }
        LoadedJar loaded = new JarLoader().load(jar, p -> {});
        SearchIndex idx = new SearchIndex(loaded);
        idx.build();

        SearchQuery q = new SearchQuery("0xDEADBEEF", SearchMode.NUMBERS,
                false, false, "", true, false);
        var results = new SearchEngine().search(idx, q);
        assertEquals(1, results.size());
        var r = results.get(0);
        assertTrue(r.targetPath().equals("a.A"));
    }

    @Test
    void smallIntConstantsViaIconst(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("ic.jar");
        try (var zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "a/A", null, "java/lang/Object", null);
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "three", "()I", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.ICONST_3);  // ICONST_3 -> value 3
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitMaxs(1, 0);
            mv.visitEnd();
            cw.visitEnd();
            zip.putNextEntry(new ZipEntry("a/A.class"));
            zip.write(cw.toByteArray());
            zip.closeEntry();
        }
        LoadedJar loaded = new JarLoader().load(jar, p -> {});
        SearchIndex idx = new SearchIndex(loaded);
        idx.build();

        SearchQuery q = new SearchQuery("3", SearchMode.NUMBERS,
                false, false, "", true, false);
        var results = new SearchEngine().search(idx, q);
        assertTrue(results.size() >= 1, "ICONST_3 should match query '3'");
    }
}
