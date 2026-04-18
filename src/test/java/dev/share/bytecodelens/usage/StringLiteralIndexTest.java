package dev.share.bytecodelens.usage;

import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.LoadedJar;
import dev.share.bytecodelens.service.ClassAnalyzer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class StringLiteralIndexTest {

    private final ClassAnalyzer analyzer = new ClassAnalyzer();

    @Test
    void findsLdcLiteralsInMethodBody() {
        ClassEntry e = analyzer.analyze(classWithLiterals("p/A", "hello", "world"));
        var idx = build(e);

        assertEquals(2, idx.uniqueStringCount());
        assertEquals(2, idx.totalSiteCount());
        assertEquals(1, idx.findExact("hello").size());
        assertEquals(1, idx.findExact("world").size());
    }

    @Test
    void aggregatesIdenticalLiteralsAcrossClasses() {
        ClassEntry a = analyzer.analyze(classWithLiterals("p/A", "shared"));
        ClassEntry b = analyzer.analyze(classWithLiterals("p/B", "shared", "unique"));
        var idx = build(a, b);

        assertEquals(2, idx.findExact("shared").size());
        assertEquals(1, idx.findExact("unique").size());
        assertEquals(2, idx.uniqueStringCount());
    }

    @Test
    void capturesFieldConstantValue() {
        ClassEntry e = analyzer.analyze(classWithStringField("p/Cfg", "URL", "http://example.com"));
        var idx = build(e);

        var sites = idx.findExact("http://example.com");
        assertEquals(1, sites.size());
        assertEquals(StringLiteralIndex.Site.Source.FIELD_CONSTANT, sites.get(0).source());
        assertEquals("URL", sites.get(0).inMethodName());
    }

    @Test
    void substringSearchWorks() {
        var idx = build(
                analyzer.analyze(classWithLiterals("p/A", "http://a.com", "ftp://b.com")),
                analyzer.analyze(classWithLiterals("p/B", "https://c.com")));
        var hits = idx.findContaining("://");
        assertEquals(3, hits.size());
        var httpsOnly = idx.findContaining("https://");
        assertEquals(1, httpsOnly.size());
    }

    @Test
    void regexSearchWorks() {
        var idx = build(
                analyzer.analyze(classWithLiterals("p/A", "log_event_login", "log_event_logout", "other")));
        var hits = idx.findRegex(Pattern.compile("^log_event_log(in|out)$"));
        assertEquals(2, hits.size());
    }

    @Test
    void unknownLiteralReturnsEmpty() {
        var idx = build(analyzer.analyze(classWithLiterals("p/A", "real")));
        assertTrue(idx.findExact("nonexistent").isEmpty());
        assertTrue(idx.findExact(null).isEmpty());
        assertTrue(idx.findContaining(null).isEmpty());
        assertTrue(idx.findContaining("").isEmpty());
        assertTrue(idx.findRegex(null).isEmpty());
    }

    @Test
    void distributionReturnsCountPerString() {
        var idx = build(
                analyzer.analyze(classWithLiterals("p/A", "x", "x", "y")),
                analyzer.analyze(classWithLiterals("p/B", "x")));
        var dist = idx.distribution();
        assertEquals(3, dist.get("x"));
        assertEquals(1, dist.get("y"));
    }

    @Test
    void siteCarriesLineNumberWhenAvailable() {
        // ClassWriter without LineNumberTable produces line=0; that's expected and tested.
        var idx = build(analyzer.analyze(classWithLiterals("p/A", "z")));
        assertEquals(0, idx.findExact("z").get(0).lineNumber());
    }

    private static StringLiteralIndex build(ClassEntry... entries) {
        LoadedJar jar = new LoadedJar(Path.of("t.jar"), List.of(entries), List.of(), List.of(), 0, 0);
        StringLiteralIndex idx = new StringLiteralIndex(jar);
        idx.build();
        return idx;
    }

    private static byte[] classWithLiterals(String name, String... literals) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "m", "()V", null, null);
        mv.visitCode();
        for (String s : literals) {
            mv.visitLdcInsn(s);
            mv.visitInsn(Opcodes.POP);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] classWithStringField(String name, String fieldName, String value) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                fieldName, "Ljava/lang/String;", null, value).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
