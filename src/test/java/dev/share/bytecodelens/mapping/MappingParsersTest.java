package dev.share.bytecodelens.mapping;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingParsersTest {

    // --- ProGuard -------------------------------------------------------------

    @Test
    void proguardParsesClassAndMembers() throws Exception {
        String in = """
                # A comment
                com.example.Auth -> a.b:
                    int tries -> a
                    12:14:java.lang.String login(java.lang.String,int) -> a
                    void logout() -> b
                """;
        MappingModel m = MappingLoader.loadString(in);

        assertEquals(MappingFormat.PROGUARD, m.sourceFormat());
        assertEquals("com/example/Auth", m.mapClass("a/b"));
        assertEquals("tries", m.fieldMap().get("a/b.a:I"));
        assertEquals("login", m.methodMap().get("a/b.a(Ljava/lang/String;I)Ljava/lang/String;"));
        assertEquals("logout", m.methodMap().get("a/b.b()V"));
    }

    @Test
    void proguardHandlesArrayTypesInMethodSig() throws Exception {
        String in = """
                com.example.Foo -> a:
                    void bar(int[],java.lang.String[][]) -> b
                """;
        MappingModel m = MappingLoader.loadString(in);
        assertEquals("bar", m.methodMap().get("a.b([I[[Ljava/lang/String;)V"));
    }

    // --- Tiny v2 --------------------------------------------------------------

    @Test
    void tinyV2ParsesClassAndMembers() throws Exception {
        String in = "tiny\t2\t0\tofficial\tnamed\n"
                + "c\ta/b\tcom/example/Foo\n"
                + "\tf\tI\ta\trenamedField\n"
                + "\tm\t()V\tm1\trenamedMethod\n";
        MappingModel m = MappingLoader.loadString(in);

        assertEquals(MappingFormat.TINY_V2, m.sourceFormat());
        assertEquals("com/example/Foo", m.mapClass("a/b"));
        assertEquals("renamedField", m.fieldMap().get("a/b.a:I"));
        assertEquals("renamedMethod", m.methodMap().get("a/b.m1()V"));
    }

    @Test
    void tinyV2IgnoresUnchangedMembers() throws Exception {
        String in = "tiny\t2\t0\tofficial\tnamed\n"
                + "c\ta/b\ta/b\n"
                + "\tf\tI\ttheField\ttheField\n";
        MappingModel m = MappingLoader.loadString(in);
        // Class unchanged -> not in map. Field unchanged -> not in map.
        assertEquals(0, m.classCount());
        assertEquals(0, m.fieldCount());
    }

    // --- SRG v1 ---------------------------------------------------------------

    @Test
    void srgParsesAllEntryKinds() throws Exception {
        String in = """
                PK: . net/minecraft
                CL: a/b com/example/Foo
                FD: a/b/c com/example/Foo/renamedField
                MD: a/b/m ()V com/example/Foo/renamedMethod ()V
                """;
        MappingModel m = MappingLoader.loadString(in);

        assertEquals(MappingFormat.SRG, m.sourceFormat());
        assertEquals("com/example/Foo", m.mapClass("a/b"));
        assertEquals("renamedField", m.fieldMap().get("a/b.c:"));
        assertEquals("renamedMethod", m.methodMap().get("a/b.m()V"));
    }

    // --- TSRG -----------------------------------------------------------------

    @Test
    void tsrgParsesTwoColumnFormat() throws Exception {
        String in = "a/b com/example/Foo\n"
                + "\ttheField renamedField\n"
                + "\ttheMethod ()V renamedMethod\n"
                + "another/cls com/example/Another\n";
        MappingModel m = MappingLoader.loadString(in);

        assertEquals(MappingFormat.TSRG, m.sourceFormat());
        assertEquals("com/example/Foo", m.mapClass("a/b"));
        assertEquals("com/example/Another", m.mapClass("another/cls"));
        assertEquals("renamedField", m.fieldMap().get("a/b.theField:"));
        assertEquals("renamedMethod", m.methodMap().get("a/b.theMethod()V"));
    }

    // --- Enigma ---------------------------------------------------------------

    @Test
    void enigmaParsesClassFieldMethod() throws Exception {
        String in = """
                CLASS a/b com/example/Foo
                \tFIELD c renamedField I
                \tMETHOD m renamedMethod ()V
                """;
        MappingModel m = MappingLoader.loadString(in);

        assertEquals(MappingFormat.ENIGMA, m.sourceFormat());
        assertEquals("com/example/Foo", m.mapClass("a/b"));
        assertEquals("renamedField", m.fieldMap().get("a/b.c:I"));
        assertEquals("renamedMethod", m.methodMap().get("a/b.m()V"));
    }

    @Test
    void enigmaHandlesNestedInnerClasses() throws Exception {
        String in = """
                CLASS a/b com/example/Foo
                \tCLASS inner RenamedInner
                \t\tFIELD c x I
                """;
        MappingModel m = MappingLoader.loadString(in);

        assertEquals("com/example/Foo", m.mapClass("a/b"));
        // Inner class obf = "a/b$inner", named = parent + "$" + simple
        assertTrue(m.classMap().containsKey("a/b$inner"),
                "inner class mapping missing: " + m.classMap());
        assertEquals("x", m.fieldMap().get("a/b$inner.c:I"));
    }

    // --- Autodetection --------------------------------------------------------

    @Test
    void detectFormatFromContent() throws Exception {
        // Each detection is identified purely by content sniff
        assertEquals(MappingFormat.PROGUARD, MappingLoader.detectFormat("com.foo -> a:\n"));
        assertEquals(MappingFormat.TINY_V2, MappingLoader.detectFormat("tiny\t2\t0\tofficial\tnamed\n"));
        assertEquals(MappingFormat.SRG, MappingLoader.detectFormat("CL: a/b com/foo/Bar\n"));
        assertEquals(MappingFormat.ENIGMA, MappingLoader.detectFormat("CLASS a/b com/foo/Bar\n"));
        assertEquals(MappingFormat.TSRG, MappingLoader.detectFormat("a/b com/foo/Bar\n\tmember named\n"));
    }
}
