package dev.share.bytecodelens.service;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookSnippetGeneratorTest {

    private final HookSnippetGenerator gen = new HookSnippetGenerator();

    @Test
    void fridaInstanceMethodWithStringArg() {
        String js = gen.frida("com/example/Auth", "login",
                "(Ljava/lang/String;Ljava/lang/String;)Z", Opcodes.ACC_PUBLIC);

        assertTrue(js.contains("Java.use('com.example.Auth')"));
        assertTrue(js.contains(".login.overload('java.lang.String', 'java.lang.String')"));
        // instance method -> call on `this`
        assertTrue(js.contains("this.login(arg0, arg1)"));
        // boolean return -> result is captured and logged
        assertTrue(js.contains("var result = "));
        assertTrue(js.contains("return result;"));
    }

    @Test
    void fridaVoidMethodNoReturn() {
        String js = gen.frida("a/B", "run", "()V", Opcodes.ACC_PUBLIC);
        assertFalse(js.contains("var result ="));
        assertFalse(js.contains("return result"));
        assertTrue(js.contains(".run.overload()"));
    }

    @Test
    void fridaStaticMethodUsesClassReceiver() {
        String js = gen.frida("a/Util", "hash", "(I)Ljava/lang/String;", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        // static -> call on class variable, not on this
        assertTrue(js.contains("Util.hash(arg0)"));
        assertFalse(js.contains("this.hash"));
    }

    @Test
    void fridaConstructorMapsTo$init() {
        String js = gen.frida("a/B", "<init>", "(I)V", Opcodes.ACC_PUBLIC);
        assertTrue(js.contains(".$init.overload('int')"));
    }

    @Test
    void fridaArrayArgUsesJniStyleDescriptor() {
        String js = gen.frida("a/B", "m", "([Ljava/lang/String;)V", Opcodes.ACC_PUBLIC);
        assertTrue(js.contains(".overload('[Ljava.lang.String;')"));
    }

    @Test
    void xposedEmitsFindAndHookMethodWithStringArg() {
        String x = gen.xposed("com/example/Auth", "login",
                "(Ljava/lang/String;)Z", Opcodes.ACC_PUBLIC);
        assertTrue(x.contains("findAndHookMethod"));
        assertTrue(x.contains("\"com.example.Auth\""));
        assertTrue(x.contains("\"login\""));
        assertTrue(x.contains("\"java.lang.String\""));
    }

    @Test
    void xposedConstructorUsesFindAndHookConstructor() {
        String x = gen.xposed("a/B", "<init>", "(I)V", Opcodes.ACC_PUBLIC);
        assertTrue(x.contains("findAndHookConstructor"));
        assertFalse(x.contains("findAndHookMethod"));
        assertTrue(x.contains("int.class"));
    }

    @Test
    void xposedPrimitivesUseDotClass() {
        String x = gen.xposed("a/B", "m", "(IJDF)V", Opcodes.ACC_PUBLIC);
        assertTrue(x.contains("int.class"));
        assertTrue(x.contains("long.class"));
        assertTrue(x.contains("double.class"));
        assertTrue(x.contains("float.class"));
    }
}
