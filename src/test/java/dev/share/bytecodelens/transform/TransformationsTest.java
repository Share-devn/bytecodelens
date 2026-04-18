package dev.share.bytecodelens.transform;

import dev.share.bytecodelens.model.LoadedJar;
import dev.share.bytecodelens.service.ClassAnalyzer;
import dev.share.bytecodelens.service.JarLoader;
import dev.share.bytecodelens.transform.transforms.DeadCodeRemoval;
import dev.share.bytecodelens.transform.transforms.IllegalNameMapping;
import dev.share.bytecodelens.transform.transforms.OpaquePredicateSimplification;
import dev.share.bytecodelens.transform.transforms.StaticValueInlining;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransformationsTest {

    // --- Dead Code Removal ---------------------------------------------------

    @Test
    void deadCodeRemovalStripsUnreachableInstructions(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("dc.jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "a/A", null, "java/lang/Object", null);
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "m", "()I", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.IRETURN);
            // Everything below is unreachable
            mv.visitInsn(Opcodes.ICONST_2);
            mv.visitInsn(Opcodes.ICONST_3);
            mv.visitInsn(Opcodes.IADD);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitMaxs(2, 0);
            mv.visitEnd();
            cw.visitEnd();
            zip.putNextEntry(new ZipEntry("a/A.class"));
            zip.write(cw.toByteArray());
            zip.closeEntry();
        }
        LoadedJar loaded = new JarLoader().load(jar, p -> {});
        var res = new TransformationRunner().run(loaded, List.of(), List.of(new DeadCodeRemoval()));

        // We expect at least one instruction removed.
        assertNotNull(res.context().counters().get("dead-code-removal"));
        assertTrue(res.context().counters().get("dead-code-removal")
                .getOrDefault("instructions-removed", 0) >= 1);
    }

    // --- Opaque Predicate Simplification -------------------------------------

    @Test
    void opaquePredicateDropsAlwaysFalseBranch(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("op.jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "a/A", null, "java/lang/Object", null);
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "m", "()I", null, null);
            mv.visitCode();
            Label skipped = new Label();
            // ICONST_1 + IFEQ skipped  ->  never jumps, both insns should be stripped
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitJumpInsn(Opcodes.IFEQ, skipped);
            mv.visitInsn(Opcodes.ICONST_2);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitLabel(skipped);
            mv.visitInsn(Opcodes.ICONST_3);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitMaxs(1, 0);
            mv.visitEnd();
            cw.visitEnd();
            zip.putNextEntry(new ZipEntry("a/A.class"));
            zip.write(cw.toByteArray());
            zip.closeEntry();
        }
        LoadedJar loaded = new JarLoader().load(jar, p -> {});
        var res = new TransformationRunner().run(loaded, List.of(),
                List.of(new OpaquePredicateSimplification()));

        assertNotNull(res.context().counters().get("opaque-predicate-simplification"));
        assertEquals(1, res.context().counters().get("opaque-predicate-simplification")
                .getOrDefault("branches-dropped", 0));
    }

    // --- Illegal Name Mapping -----------------------------------------------

    @Test
    void illegalNameMappingRenamesKeywordClass(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("kw.jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            // "true" is a Java keyword — JVM allows it as an identifier, javac does not.
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "pkg/true", null, "java/lang/Object", null);
            cw.visitEnd();
            zip.putNextEntry(new ZipEntry("pkg/true.class"));
            zip.write(cw.toByteArray());
            zip.closeEntry();
        }
        LoadedJar loaded = new JarLoader().load(jar, p -> {});
        var res = new TransformationRunner().run(loaded, List.of(new IllegalNameMapping()), List.of());
        assertEquals(1, res.transformedJar().classCount());
        String newName = res.transformedJar().classes().get(0).simpleName();
        assertFalse(newName.equals("true"),
                "keyword class should have been renamed, was: " + newName);
        assertTrue(newName.startsWith("C_"));
    }

    // --- Static Value Inlining ----------------------------------------------

    @Test
    void staticValueInliningReplacesGetstaticWithConstant(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("sv.jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            // class K { public static final int X = 42; static int use() { return X; } }
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "a/K", null, "java/lang/Object", null);
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                    "X", "I", null, 42).visitEnd();
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "use", "()I", null, null);
            mv.visitCode();
            mv.visitFieldInsn(Opcodes.GETSTATIC, "a/K", "X", "I");
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitMaxs(1, 0);
            mv.visitEnd();
            cw.visitEnd();
            zip.putNextEntry(new ZipEntry("a/K.class"));
            zip.write(cw.toByteArray());
            zip.closeEntry();
        }
        LoadedJar loaded = new JarLoader().load(jar, p -> {});
        var res = new TransformationRunner().run(loaded,
                List.of(new StaticValueInlining()), List.of());
        assertNotNull(res.context().counters().get("static-value-inlining"));
        assertEquals(1, res.context().counters().get("static-value-inlining")
                .getOrDefault("constants-inlined", 0));
    }

    // --- Runner composition --------------------------------------------------

    @Test
    void runnerHandlesMultiplePassesInOrder(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("multi.jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar.toFile()))) {
            addSimpleClass(zip, "a/A");
            addSimpleClass(zip, "b/B");
        }
        LoadedJar loaded = new JarLoader().load(jar, p -> {});

        var res = new TransformationRunner().run(loaded,
                List.of(new IllegalNameMapping(), new StaticValueInlining()),
                List.of(new DeadCodeRemoval(), new OpaquePredicateSimplification()));
        // Nothing transformable here — but runner must complete without errors.
        assertEquals(2, res.transformedJar().classCount());
        assertEquals(0, res.classesFailed());
    }

    private static void addSimpleClass(ZipOutputStream zip, String internalName) throws Exception {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        zip.putNextEntry(new ZipEntry(internalName + ".class"));
        zip.write(cw.toByteArray());
        zip.closeEntry();
    }

    // Silence unused-import warnings for ClassAnalyzer — it's pulled in by the assertion shape above.
    @SuppressWarnings("unused")
    private static final ClassAnalyzer UNUSED = new ClassAnalyzer();
}
