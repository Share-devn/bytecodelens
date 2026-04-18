package dev.share.bytecodelens.compile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests against the real system javac. Skipped on JRE installations (no javac),
 * which is fine since CI/dev both run on JDKs.
 */
@EnabledIf("javacAvailable")
class JavaSourceCompilerTest {

    static boolean javacAvailable() {
        return ToolProvider.getSystemJavaCompiler() != null;
    }

    private final JavaSourceCompiler compiler = new JavaSourceCompiler();

    @Test
    void helloWorldCompiles() {
        String src = """
                package demo;
                public class Hello {
                    public static String greet() { return "hi"; }
                }
                """;
        var res = compiler.compile("demo/Hello.java", src);
        assertTrue(res.success(), "expected success, diagnostics: " + res.diagnostics());
        assertTrue(res.outputClasses().containsKey("demo/Hello"));

        byte[] bytes = res.outputClasses().get("demo/Hello");
        assertNotNull(bytes);

        // Sanity: bytes are a valid class with the expected name.
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        assertEquals("demo/Hello", node.name);
    }

    @Test
    void nestedClassAlsoProducesOutput() {
        String src = """
                public class Outer {
                    public static class Inner { public int x = 1; }
                    Inner i = new Inner();
                }
                """;
        var res = compiler.compile("Outer.java", src);
        assertTrue(res.success(), "diagnostics: " + res.diagnostics());
        assertTrue(res.outputClasses().containsKey("Outer"));
        assertTrue(res.outputClasses().containsKey("Outer$Inner"),
                "nested class missing: " + res.outputClasses().keySet());
    }

    @Test
    void syntaxErrorReportsDiagnostic() {
        String src = "public class Broken { void m() { return }";  // missing ';' and '}'
        var res = compiler.compile("Broken.java", src);
        assertFalse(res.success());
        assertFalse(res.diagnostics().isEmpty());
        assertTrue(res.diagnostics().stream()
                .anyMatch(d -> d.level() == JavaSourceCompiler.Level.ERROR));
    }

    @Test
    void unresolvedReferenceIsNowAutoPhantomed() {
        // Behaviour changed after phantom generation was added: a reference to a class that
        // doesn't exist on the classpath is auto-stubbed by PhantomClassGenerator, so the
        // compile succeeds even though com.unknown.X was never on the real classpath.
        String src = "public class Uses { com.unknown.X x; }";
        var res = compiler.compile("Uses.java", src);
        assertTrue(res.success(),
                "phantom should satisfy the reference; diagnostics: " + res.diagnostics());
    }

    @Test
    void realTypeErrorStillFailsEvenWithPhantoms() {
        // Phantom generator must NOT swallow real semantic errors — only genuine "unresolved
        // type" problems. An incompatible assignment between real JDK types still errors.
        String src = "public class Bad { public String s = new Object(); }";
        var res = compiler.compile("Bad.java", src);
        assertFalse(res.success(),
                "phantom must not mask real type mismatches; diagnostics: " + res.diagnostics());
    }

    @Test
    void jdkStdlibReferenceWorks() {
        // 7a classpath includes JDK stdlib — java.util.List must resolve.
        String src = """
                import java.util.List;
                public class U { List<String> xs = List.of("a", "b"); }
                """;
        var res = compiler.compile("U.java", src);
        assertTrue(res.success(), "diagnostics: " + res.diagnostics());
        assertTrue(res.outputClasses().containsKey("U"));
    }

    @Test
    void resolvesReferenceFromContextJar(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // Build a jar with a single helper class.
        java.nio.file.Path jar = dir.resolve("deps.jar");
        try (var zip = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(jar.toFile()))) {
            org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
            cw.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC,
                    "demo/Helper", null, "java/lang/Object", null);
            var mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                    "constant", "()I", null, null);
            mv.visitCode();
            mv.visitIntInsn(org.objectweb.asm.Opcodes.BIPUSH, 42);
            mv.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
            mv.visitMaxs(1, 0);
            mv.visitEnd();
            cw.visitEnd();
            zip.putNextEntry(new java.util.zip.ZipEntry("demo/Helper.class"));
            zip.write(cw.toByteArray());
            zip.closeEntry();
        }
        var loaded = new dev.share.bytecodelens.service.JarLoader().load(jar, p -> {});

        // Now compile a source that uses Helper.constant() — must resolve via context jar.
        String src = """
                package demo;
                public class Use {
                    public int get() { return Helper.constant(); }
                }
                """;
        var res = compiler.compile("demo/Use.java", src, JavaSourceCompiler.DEFAULT_RELEASE, loaded);
        assertTrue(res.success(), "diagnostics: " + res.diagnostics());
        assertTrue(res.outputClasses().containsKey("demo/Use"));
    }

    @Test
    void editAndRecompileProducesDifferentBytecode(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // Start with a simple class — then recompile with a changed method body and verify
        // the new bytecode differs. This simulates the edit-in-place workflow.
        String src1 = """
                public class Foo {
                    public int answer() { return 1; }
                }
                """;
        var res1 = compiler.compile("Foo.java", src1);
        assertTrue(res1.success());
        byte[] bytes1 = res1.outputClasses().get("Foo");

        String src2 = """
                public class Foo {
                    public int answer() { return 42; }
                }
                """;
        var res2 = compiler.compile("Foo.java", src2);
        assertTrue(res2.success());
        byte[] bytes2 = res2.outputClasses().get("Foo");

        // Different method body -> different bytecode
        assertNotNull(bytes2);
        assertFalse(java.util.Arrays.equals(bytes1, bytes2),
                "recompiled bytes should differ from original");
    }

    @Test
    void runtimeClasspathIsVisibleSoJavaFXResolves() {
        // Regression: "Compile failed — package javafx.application does not exist" — earlier
        // we used --release which hides non-stdlib classpath entries. Here the class references
        // org.objectweb.asm.Opcodes, which is always on BytecodeLens' own runtime classpath.
        String src = """
                public class NeedsAsm {
                    int v = org.objectweb.asm.Opcodes.ACC_PUBLIC;
                }
                """;
        var res = compiler.compile("NeedsAsm.java", src);
        assertTrue(res.success(), "third-party runtime class should be visible; diagnostics: "
                + res.diagnostics());
    }

    @Test
    void diagnosticsHaveLineNumbers() {
        String src = """
                public class LineTest {
                    void m() {
                        return 42;   // error: incompatible return type
                    }
                }
                """;
        var res = compiler.compile("LineTest.java", src);
        assertFalse(res.success());
        var err = res.diagnostics().stream()
                .filter(d -> d.level() == JavaSourceCompiler.Level.ERROR)
                .findFirst().orElseThrow();
        assertTrue(err.line() >= 1, "expected positive line number, got " + err.line());
    }
}
