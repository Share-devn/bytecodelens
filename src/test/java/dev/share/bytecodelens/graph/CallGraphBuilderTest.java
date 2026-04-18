package dev.share.bytecodelens.graph;

import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.LoadedJar;
import dev.share.bytecodelens.service.ClassAnalyzer;
import dev.share.bytecodelens.usage.UsageIndex;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallGraphBuilderTest {

    private final ClassAnalyzer analyzer = new ClassAnalyzer();

    @Test
    void buildsOutgoingClassGraph() {
        LoadedJar jar = jar(
                callerOf("com/x/Caller", "com/x/Callee", "bar"),
                simpleClass("com/x/Callee", "bar"));
        UsageIndex usage = new UsageIndex(jar);
        usage.build();

        CallGraph g = new CallGraphBuilder(usage).buildAround(
                GraphNode.classId("com.x.Caller"), "com.x.Caller",
                CallGraph.Mode.CLASS, CallGraph.Direction.OUT, 2, "");

        assertTrue(g.nodeCount() >= 2);
        assertTrue(g.edges().stream().anyMatch(e ->
                "com.x.Caller".equals(e.source().fqn())
                        && "com.x.Callee".equals(e.target().fqn())));
    }

    @Test
    void buildsIncomingClassGraph() {
        LoadedJar jar = jar(
                callerOf("com/x/Caller", "com/x/Callee", "bar"),
                simpleClass("com/x/Callee", "bar"));
        UsageIndex usage = new UsageIndex(jar);
        usage.build();

        CallGraph g = new CallGraphBuilder(usage).buildAround(
                GraphNode.classId("com.x.Callee"), "com.x.Callee",
                CallGraph.Mode.CLASS, CallGraph.Direction.IN, 2, "");

        assertTrue(g.nodeCount() >= 2);
        assertTrue(g.edges().stream().anyMatch(e ->
                "com.x.Caller".equals(e.source().fqn())));
    }

    @Test
    void depthLimits() {
        LoadedJar jar = jar(
                callerOf("com/x/A", "com/x/B", "m"),
                callerOf("com/x/B", "com/x/C", "m"),
                callerOf("com/x/C", "com/x/D", "m"),
                simpleClass("com/x/D", "m"));
        UsageIndex usage = new UsageIndex(jar);
        usage.build();

        CallGraph d1 = new CallGraphBuilder(usage).buildAround(
                GraphNode.classId("com.x.A"), "com.x.A",
                CallGraph.Mode.CLASS, CallGraph.Direction.OUT, 1, "");
        CallGraph d3 = new CallGraphBuilder(usage).buildAround(
                GraphNode.classId("com.x.A"), "com.x.A",
                CallGraph.Mode.CLASS, CallGraph.Direction.OUT, 3, "");

        assertTrue(d1.nodeCount() <= d3.nodeCount());
        assertTrue(d3.nodeCount() >= 3);
    }

    @Test
    void methodLevelGraph() {
        LoadedJar jar = jar(
                callerOf("com/x/Caller", "com/x/Callee", "bar"),
                simpleClass("com/x/Callee", "bar"));
        UsageIndex usage = new UsageIndex(jar);
        usage.build();

        CallGraph g = new CallGraphBuilder(usage).buildAround(
                GraphNode.methodId("com.x.Caller", "call", "()V"), "com.x.Caller#call",
                CallGraph.Mode.METHOD, CallGraph.Direction.OUT, 2, "");

        assertTrue(g.nodeCount() >= 2);
        assertTrue(g.nodes().stream().anyMatch(n ->
                n.kind() == GraphNode.Kind.METHOD && "bar".equals(n.methodName())));
    }

    private LoadedJar jar(byte[]... bytes) {
        List<ClassEntry> entries = java.util.Arrays.stream(bytes)
                .map(analyzer::analyze).toList();
        return new LoadedJar(Path.of("t.jar"), entries, List.of(), List.of(), 0, 0);
    }

    private static byte[] simpleClass(String internalName, String methodName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                methodName, "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] callerOf(String internalName, String targetOwner, String targetMethod) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "call", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, targetOwner, targetMethod, "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
