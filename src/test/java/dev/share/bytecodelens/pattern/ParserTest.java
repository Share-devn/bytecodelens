package dev.share.bytecodelens.pattern;

import dev.share.bytecodelens.pattern.ast.ClassPattern;
import dev.share.bytecodelens.pattern.ast.MethodPattern;
import dev.share.bytecodelens.pattern.ast.NestedPattern;
import dev.share.bytecodelens.pattern.ast.Pattern;
import dev.share.bytecodelens.pattern.parser.Parser;
import dev.share.bytecodelens.pattern.parser.PatternParseException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParserTest {

    @Test
    void parsesMinimalMethodBlock() {
        Pattern p = Parser.parse("method { contains ldc \"hello\" }");
        assertInstanceOf(MethodPattern.class, p);
        assertEquals(1, p.predicates().size());
    }

    @Test
    void parsesClassWithNestedMethod() {
        String src = """
                class {
                  any method {
                    contains invoke java/lang/Runtime.exec
                  }
                }
                """;
        Pattern p = Parser.parse(src);
        assertInstanceOf(ClassPattern.class, p);
        assertInstanceOf(NestedPattern.class, p.predicates().get(0));
    }

    @Test
    void parsesRegexMatchSpec() {
        Pattern p = Parser.parse("class { name ~ /com\\.foo\\..*/ }");
        assertEquals(1, p.predicates().size());
    }

    @Test
    void parsesOrAlternatives() {
        String src = """
                method {
                  contains invoke java/lang/System.exit
                  | contains invoke java/lang/Runtime.exit
                }
                """;
        Pattern p = Parser.parse(src);
        assertEquals(1, p.predicates().size());
    }

    @Test
    void errorsOnMissingBrace() {
        assertThrows(PatternParseException.class,
                () -> Parser.parse("method { contains ldc \"x\""));
    }

    @Test
    void errorsOnUnknownPredicate() {
        var ex = assertThrows(PatternParseException.class,
                () -> Parser.parse("method { foobar \"x\" }"));
        assertTrue(ex.getMessage().contains("foobar"));
    }

    @Test
    void parsesAccessWithCountPredicate() {
        Pattern p = Parser.parse("method { access static instructions > 10 }");
        assertEquals(2, p.predicates().size());
    }

    @Test
    void parsesLineComments() {
        String src = """
                // This is a comment
                method {
                  // Another comment
                  contains ldc "x"
                }
                """;
        Pattern p = Parser.parse(src);
        assertInstanceOf(MethodPattern.class, p);
    }
}
