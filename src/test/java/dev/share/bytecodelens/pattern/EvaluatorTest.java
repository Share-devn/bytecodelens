package dev.share.bytecodelens.pattern;

import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.LoadedJar;
import dev.share.bytecodelens.pattern.eval.Evaluator;
import dev.share.bytecodelens.pattern.eval.PatternResult;
import dev.share.bytecodelens.pattern.parser.Parser;
import dev.share.bytecodelens.service.ClassAnalyzer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluatorTest {

    private final ClassAnalyzer analyzer = new ClassAnalyzer();
    private final Evaluator evaluator = new Evaluator();

    @Test
    void findsMethodWithLdc() {
        byte[] bytes = makeClassWithLdc("com/example/Foo", "bar", "secret");
        LoadedJar jar = new LoadedJar(Path.of("fake.jar"),
                List.of(analyzer.analyze(bytes)), List.of(), List.of(), 0, 0);

        var pattern = Parser.parse("method { contains ldc \"secret\" }");
        List<PatternResult> results = evaluator.evaluate(jar, pattern);
        assertEquals(1, results.size());
        assertEquals(PatternResult.Kind.METHOD, results.get(0).kind());
        assertEquals("bar", results.get(0).memberName());
    }

    @Test
    void findsClassByName() {
        List<ClassEntry> classes = List.of(
                analyzer.analyze(makeEmpty("com/foo/A")),
                analyzer.analyze(makeEmpty("com/foo/B")),
                analyzer.analyze(makeEmpty("com/bar/C")));
        LoadedJar jar = new LoadedJar(Path.of("fake.jar"), classes, List.of(), List.of(), 0, 0);

        var pattern = Parser.parse("class { name ~ /com\\.foo\\..*/ }");
        List<PatternResult> results = evaluator.evaluate(jar, pattern);
        assertEquals(2, results.size());
    }

    @Test
    void findsMethodInvocation() {
        byte[] bytes = makeClassWithInvoke("com/example/Auth", "login",
                "java/lang/System", "exit", "(I)V");
        LoadedJar jar = new LoadedJar(Path.of("fake.jar"),
                List.of(analyzer.analyze(bytes)), List.of(), List.of(), 0, 0);

        var pattern = Parser.parse("method { contains invoke java/lang/System.exit }");
        List<PatternResult> results = evaluator.evaluate(jar, pattern);
        assertEquals(1, results.size());
    }

    @Test
    void nestedAnyMethodMatchesClass() {
        byte[] bytes = makeClassWithLdc("com/example/Bomb", "detonate", "boom");
        LoadedJar jar = new LoadedJar(Path.of("fake.jar"),
                List.of(analyzer.analyze(bytes)), List.of(), List.of(), 0, 0);

        var pattern = Parser.parse("""
                class {
                  any method { contains ldc "boom" }
                }
                """);
        List<PatternResult> results = evaluator.evaluate(jar, pattern);
        assertEquals(1, results.size());
        assertEquals(PatternResult.Kind.CLASS, results.get(0).kind());
    }

    @Test
    void orMatchesAnyAlternative() {
        byte[] bytes = makeClassWithInvoke("com/x/X", "m",
                "java/lang/Runtime", "exec", "(Ljava/lang/String;)Ljava/lang/Process;");
        LoadedJar jar = new LoadedJar(Path.of("fake.jar"),
                List.of(analyzer.analyze(bytes)), List.of(), List.of(), 0, 0);

        var pattern = Parser.parse("""
                method {
                  contains invoke java/lang/System.exit
                  | contains invoke java/lang/Runtime.exec
                }
                """);
        List<PatternResult> results = evaluator.evaluate(jar, pattern);
        assertEquals(1, results.size());
    }

    @Test
    void accessFlagFilter() {
        byte[] bytes = makeClassWithLdc("com/x/X", "m", "hello");
        LoadedJar jar = new LoadedJar(Path.of("fake.jar"),
                List.of(analyzer.analyze(bytes)), List.of(), List.of(), 0, 0);

        var pattern = Parser.parse("method { access public contains ldc \"hello\" }");
        List<PatternResult> results = evaluator.evaluate(jar, pattern);
        assertEquals(1, results.size());
    }

    @Test
    void doesNotMatchWhenAbsent() {
        byte[] bytes = makeEmpty("com/x/X");
        LoadedJar jar = new LoadedJar(Path.of("fake.jar"),
                List.of(analyzer.analyze(bytes)), List.of(), List.of(), 0, 0);

        var pattern = Parser.parse("method { contains ldc \"nothing\" }");
        List<PatternResult> results = evaluator.evaluate(jar, pattern);
        assertTrue(results.isEmpty());
    }

    private static byte[] makeEmpty(String internalName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] makeClassWithLdc(String internalName, String methodName, String value) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()V", null, null);
        mv.visitCode();
        mv.visitLdcInsn(value);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] makeClassWithInvoke(String internalName, String methodName,
                                              String targetOwner, String targetName, String targetDesc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, targetOwner, targetName, targetDesc, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
