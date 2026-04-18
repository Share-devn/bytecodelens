package dev.share.bytecodelens.asm;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsmAssemblerTest {

    @Test
    void assembleTrivialClassProducesValidBytecode() {
        String src = """
                .class public asm/Trivial
                .super java/lang/Object

                .method public static answer()I
                    bipush 42
                    ireturn
                .end method
                """;
        byte[] bytes = new AsmAssembler().assemble(src);

        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        assertEquals("asm/Trivial", node.name);
        assertEquals(1, node.methods.size());
        assertEquals("answer", node.methods.get(0).name);
    }

    @Test
    void assembledClassIsLoadableAndCallable() throws Exception {
        String src = """
                .class public asm/Answer
                .super java/lang/Object

                .method public static compute()I
                    iconst_2
                    iconst_3
                    iadd
                    ireturn
                .end method
                """;
        byte[] bytes = new AsmAssembler().assemble(src);

        ByteClassLoader cl = new ByteClassLoader();
        Class<?> cls = cl.define("asm.Answer", bytes);
        Method m = cls.getDeclaredMethod("compute");
        assertEquals(5, (int) m.invoke(null));
    }

    @Test
    void stringReturningMethodWorks() throws Exception {
        String src = """
                .class public asm/Hi
                .super java/lang/Object

                .method public static greet()Ljava/lang/String;
                    ldc "hello"
                    areturn
                .end method
                """;
        byte[] bytes = new AsmAssembler().assemble(src);
        ByteClassLoader cl = new ByteClassLoader();
        Class<?> cls = cl.define("asm.Hi", bytes);
        assertEquals("hello", cls.getDeclaredMethod("greet").invoke(null));
    }

    @Test
    void defaultConstructorAndFieldWork() throws Exception {
        String src = """
                .class public asm/WithField
                .super java/lang/Object

                .field public value I

                .method public <init>()V
                    aload_0
                    invokespecial java/lang/Object.<init>()V
                    aload_0
                    bipush 7
                    putfield asm/WithField.value:I
                    return
                .end method

                .method public getValue()I
                    aload_0
                    getfield asm/WithField.value:I
                    ireturn
                .end method
                """;
        byte[] bytes = new AsmAssembler().assemble(src);
        ByteClassLoader cl = new ByteClassLoader();
        Class<?> cls = cl.define("asm.WithField", bytes);
        Object inst = cls.getDeclaredConstructor().newInstance();
        assertEquals(7, (int) cls.getDeclaredMethod("getValue").invoke(inst));
    }

    @Test
    void parseErrorReportsLineNumber() {
        String src = """
                .class public asm/Broken
                .super java/lang/Object

                .method public static m()V
                    frobnicate   // unknown mnemonic
                    return
                .end method
                """;
        AsmAssembler.AsmException ex = assertThrows(AsmAssembler.AsmException.class,
                () -> new AsmAssembler().assemble(src));
        assertTrue(ex.line >= 4, "unexpected line " + ex.line + " for message: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("frobnicate"));
    }

    @Test
    void labelsAndGotoWork() throws Exception {
        String src = """
                .class public asm/Branch
                .super java/lang/Object

                .method public static max(II)I
                    iload_0
                    iload_1
                    if_icmpge first:
                    iload_1
                    ireturn
                first:
                    iload_0
                    ireturn
                .end method
                """;
        byte[] bytes = new AsmAssembler().assemble(src);
        ByteClassLoader cl = new ByteClassLoader();
        Class<?> cls = cl.define("asm.Branch", bytes);
        Method m = cls.getDeclaredMethod("max", int.class, int.class);
        assertEquals(5, (int) m.invoke(null, 5, 3));
        assertEquals(7, (int) m.invoke(null, 2, 7));
    }

    private static final class ByteClassLoader extends ClassLoader {
        Class<?> define(String dottedName, byte[] bytes) {
            return defineClass(dottedName, bytes, 0, bytes.length);
        }
    }
}
