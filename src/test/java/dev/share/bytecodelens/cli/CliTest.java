package dev.share.bytecodelens.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class CliTest {

    @Test
    void usagePrintedWithNoArgs() {
        var run = run(new String[]{});
        assertEquals(0, run.code);
        assertTrue(run.out.contains("Usage"));
    }

    @Test
    void unknownCommandReturnsExitCode2() {
        var run = run(new String[]{"frobnicate"});
        assertEquals(2, run.code);
        assertTrue(run.err.contains("Unknown command"));
    }

    @Test
    void decompileWritesJavaFiles(@TempDir Path tmp) throws IOException {
        Path jar = tmp.resolve("in.jar");
        writeJar(jar, Map.of("p/Foo.class", classBytes("p/Foo")));
        Path out = tmp.resolve("out");

        var run = run(new String[]{"decompile", jar.toString(), "-o", out.toString(), "--engine", "fallback"});
        assertEquals(0, run.code);
        assertTrue(Files.exists(out.resolve("p/Foo.java")));
    }

    @Test
    void decompileFailsOnMissingJar() {
        var run = run(new String[]{"decompile", "/nonexistent/x.jar", "-o", "/tmp/out"});
        assertNotEquals(0, run.code);
    }

    @Test
    void analyzeHumanReadableContainsClassCount(@TempDir Path tmp) throws IOException {
        Path jar = tmp.resolve("in.jar");
        writeJar(jar, Map.of("p/Foo.class", classBytes("p/Foo")));
        var run = run(new String[]{"analyze", jar.toString()});
        assertEquals(0, run.code);
        assertTrue(run.out.contains("classCount"));
        assertTrue(run.out.contains("1"));
    }

    @Test
    void analyzeJsonOutputIsValid(@TempDir Path tmp) throws IOException {
        Path jar = tmp.resolve("in.jar");
        writeJar(jar, Map.of("p/Foo.class", classBytes("p/Foo")));
        Path report = tmp.resolve("r.json");
        var run = run(new String[]{"analyze", jar.toString(), "--report-json", report.toString()});
        assertEquals(0, run.code);
        String json = Files.readString(report, StandardCharsets.UTF_8);
        assertTrue(json.startsWith("{"));
        assertTrue(json.contains("\"classCount\":1"));
        assertTrue(json.endsWith("}"));
    }

    @Test
    void mappingsConvertSrgToProGuard(@TempDir Path tmp) throws IOException {
        Path in = tmp.resolve("a.srg");
        Files.writeString(in, "CL: a/b com/Foo\n", StandardCharsets.UTF_8);
        Path out = tmp.resolve("out.txt");
        var run = run(new String[]{"mappings", "convert", in.toString(),
                "--to", "PROGUARD", "-o", out.toString()});
        assertEquals(0, run.code);
        String text = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(text.contains("com.Foo -> a.b:"));
    }

    @Test
    void mappingsDiffJson(@TempDir Path tmp) throws IOException {
        Path a = tmp.resolve("a.srg"); Path b = tmp.resolve("b.srg");
        Files.writeString(a, "CL: a com/Foo\n", StandardCharsets.UTF_8);
        Files.writeString(b, "CL: a com/Foo\nCL: b com/Bar\n", StandardCharsets.UTF_8);
        Path report = tmp.resolve("d.json");
        var run = run(new String[]{"mappings", "diff", a.toString(), b.toString(),
                "--report-json", report.toString()});
        assertEquals(0, run.code);
        String json = Files.readString(report, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"classesAdded\":1"));
        assertTrue(json.contains("\"totalChanges\":1"));
    }

    @Test
    void toJsonEscapesSpecialCharacters() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", "a\"b\nc");
        String json = Cli.toJson(m);
        assertTrue(json.contains("a\\\"b\\nc"));
    }

    // --- helpers ---

    private static Result run(String[] args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = new Cli(new PrintStream(o), new PrintStream(e)).run(args);
        return new Result(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    private record Result(int code, String out, String err) {}

    private static byte[] classBytes(String internalName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void writeJar(Path path, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
    }
}
