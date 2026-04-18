package dev.share.bytecodelens.ui;

import dev.share.bytecodelens.ui.views.HighlightRequest;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@code highlightForMember} helper that maps bytecode member names
 * ({@code <clinit>}, {@code <init>}, regular names) to a search query that will actually
 * hit the declaration in decompiled source. Using reflection because the helper is
 * package-private-static on the (quite large) MainController.
 */
class MainControllerHighlightTest {

    private static HighlightRequest call(String member, String simple) throws Exception {
        var m = MainController.class.getDeclaredMethod(
                "highlightForMember", String.class, String.class);
        m.setAccessible(true);
        return (HighlightRequest) m.invoke(null, member, simple);
    }

    @Test
    void regularMethodUsesNameFollowedByParenRegex() throws Exception {
        HighlightRequest req = call("loadFonts", "Launcher");
        assertEquals(HighlightRequest.Mode.REGEX, req.mode());
        // Should match the declaration and call site, but NOT the method name appearing
        // inside a longer identifier like "loadFontsAndStuff".
        var p = Pattern.compile(req.query());
        assertTrue(p.matcher("public static void loadFonts() {}").find(),
                "Declaration should match");
        assertTrue(p.matcher("Foo.loadFonts();").find(),
                "Call site should match");
        assertTrue(!p.matcher("loadFontsAndStuff();").find(),
                "Substring inside longer identifier must NOT match");
        assertTrue(!p.matcher("String loadFonts = \"x\";").find(),
                "Variable reference (no parens) must NOT match");
    }

    @Test
    void mainMethodDoesNotMatchSubstringInDomain() throws Exception {
        HighlightRequest req = call("main", "App");
        var p = Pattern.compile(req.query());
        assertTrue(p.matcher("public static void main(String[] args) {}").find(),
                "Declaration should match");
        assertTrue(!p.matcher("String domain = \"x\";").find(),
                "'main' inside 'domain' must NOT match");
        assertTrue(!p.matcher("int remainder = a % b;").find(),
                "'main' inside 'remainder' must NOT match");
        assertTrue(!p.matcher("MainActivity mainActivity = null;").find(),
                "'main' as prefix of camelCase var must NOT match");
    }

    @Test
    void fieldUsesLiteralWordSearch() throws Exception {
        var m = MainController.class.getDeclaredMethod(
                "highlightForMember", String.class, String.class, MainController.NodeKind.class);
        m.setAccessible(true);
        HighlightRequest req = (HighlightRequest) m.invoke(null, "log", "App",
                MainController.NodeKind.FIELD);
        assertEquals(HighlightRequest.Mode.LITERAL_WORD, req.mode());
        assertEquals("log", req.query());
    }

    @Test
    void clinitSearchHitsStaticBlockInSampleSource() throws Exception {
        HighlightRequest req = call("<clinit>", "Launcher");
        assertEquals(HighlightRequest.Mode.REGEX, req.mode());
        // The regex we generated must actually match the canonical decompiler output.
        String sample = "public class Launcher {\n" +
                "    static {\n" +
                "        System.loadLibrary(\"foo\");\n" +
                "    }\n" +
                "    public static final int X = 1;\n" +  // "static " here must NOT be matched alone
                "}";
        var p = Pattern.compile(req.query());
        var m = p.matcher(sample);
        assertTrue(m.find(), "clinit regex should find 'static {'");
        // ... and the match must be the block start, not the field modifier.
        assertEquals("static {", sample.substring(m.start(), m.end()));
    }

    @Test
    void clinitRegexDoesNotMatchStaticFieldModifier() throws Exception {
        HighlightRequest req = call("<clinit>", "Foo");
        String fieldOnly = "public static final int X = 1;";
        var p = Pattern.compile(req.query());
        assertTrue(!p.matcher(fieldOnly).find(),
                "Static field modifier must not be confused with static initializer block");
    }

    @Test
    void initSearchFindsConstructorDeclaration() throws Exception {
        HighlightRequest req = call("<init>", "Launcher");
        assertEquals(HighlightRequest.Mode.REGEX, req.mode());
        String sample = "public class Launcher {\n" +
                "    public Launcher(String s) {\n" +
                "        super();\n" +
                "    }\n" +
                "    public Launcher() {}\n" +
                "}";
        var p = Pattern.compile(req.query());
        var m = p.matcher(sample);
        assertTrue(m.find(), "init regex should find constructor declaration");
    }

    @Test
    void initSearchMatchesCallSitesToo() throws Exception {
        HighlightRequest req = call("<init>", "Launcher");
        // new Launcher(...) at a call site should also highlight — good UX.
        String sample = "Object x = new Launcher(\"hi\");";
        var p = Pattern.compile(req.query());
        assertTrue(p.matcher(sample).find());
    }

    @Test
    void initWithInnerDollarNameIsProperlyEscaped() throws Exception {
        HighlightRequest req = call("<init>", "Outer$Inner");
        // The $ in the class name must be a literal match, not regex end-of-line.
        String sample = "Outer$Inner x = new Outer$Inner();";
        var p = Pattern.compile(req.query());
        assertTrue(p.matcher(sample).find());
    }

    @Test
    void initWithoutSimpleNameReturnsNoneSoCallerFallsBack() throws Exception {
        HighlightRequest req = call("<init>", null);
        assertEquals(HighlightRequest.Mode.NONE, req.mode());
    }
}
