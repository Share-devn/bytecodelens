package dev.share.bytecodelens.compile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the multi-pass phantom-class generator turns previously-unresolvable source
 * into compilable code. Without phantoms, references to unknown types like {@code com.unknown.X}
 * are a hard error; with phantoms, we stub them so javac proceeds.
 */
@EnabledIf("javacAvailable")
class PhantomClassesTest {

    static boolean javacAvailable() {
        return ToolProvider.getSystemJavaCompiler() != null;
    }

    private final JavaSourceCompiler compiler = new JavaSourceCompiler();

    @Test
    void stubsSingleMissingTypeAndCompiles() {
        String src = """
                package demo;
                public class Uses {
                    public com.unknown.Thing thing;
                }
                """;
        var res = compiler.compile("demo/Uses.java", src);
        assertTrue(res.success(),
                "should compile via phantom; diagnostics: " + res.diagnostics());
        // "Uses" ends up in outputs. The phantom itself is not an output — it's just a
        // classpath resolution aid — so we don't assert on it.
        assertTrue(res.outputClasses().containsKey("demo/Uses"));
    }

    @Test
    void stubsMultipleMissingTypesAcrossSeveralPasses() {
        String src = """
                package demo;
                public class Chain {
                    public com.a.One one;
                    public com.b.Two two;
                    public com.c.Three three;
                }
                """;
        var res = compiler.compile("demo/Chain.java", src);
        assertTrue(res.success(),
                "three-way phantom should resolve; diagnostics: " + res.diagnostics());
    }

    @Test
    void stubsBarePackageLocalTypeQualifiedToOwnerPackage() {
        String src = """
                package demo;
                public class UsesLocal {
                    public Sibling sibling;  // unqualified -> demo.Sibling
                }
                """;
        var res = compiler.compile("demo/UsesLocal.java", src);
        assertTrue(res.success(),
                "bare type should be phantomed into owner package; diagnostics: "
                        + res.diagnostics());
    }

    @Test
    void doesNotStubJdkTypes() {
        // JDK types are real — an "incompatible types" error should NOT be silenced by
        // phantoming java.lang.String. Phantom generator must refuse java.* prefixes.
        String src = """
                public class Strict {
                    public String s = new Object();
                }
                """;
        var res = compiler.compile("Strict.java", src);
        assertFalse(res.success(), "real type mismatch should still be flagged");
        assertTrue(res.diagnostics().stream()
                        .anyMatch(d -> d.level() == JavaSourceCompiler.Level.ERROR),
                "should have at least one error");
    }

    @Test
    void doesNotLoopForeverOnUnresolvableMismatch() {
        // "Object cannot be converted to String" is not a phantom case; generator should
        // not keep producing new phantoms forever. Just checking this terminates is enough.
        String src = """
                public class Mismatch {
                    public String s = (Object) new Object();
                }
                """;
        var res = compiler.compile("Mismatch.java", src);
        // Either fails cleanly or succeeds via unrelated route — must not hang.
        assertFalse(res.success());
    }
}
