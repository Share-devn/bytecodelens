package dev.share.bytecodelens.pattern.parser;

import dev.share.bytecodelens.pattern.ast.AccessPredicate;
import dev.share.bytecodelens.pattern.ast.AnnotationPredicate;
import dev.share.bytecodelens.pattern.ast.ClassPattern;
import dev.share.bytecodelens.pattern.ast.ContainsPredicate;
import dev.share.bytecodelens.pattern.ast.CountOp;
import dev.share.bytecodelens.pattern.ast.DescPredicate;
import dev.share.bytecodelens.pattern.ast.ExtendsPredicate;
import dev.share.bytecodelens.pattern.ast.FieldCountPredicate;
import dev.share.bytecodelens.pattern.ast.FieldPattern;
import dev.share.bytecodelens.pattern.ast.ImplementsPredicate;
import dev.share.bytecodelens.pattern.ast.InstructionCountPredicate;
import dev.share.bytecodelens.pattern.ast.InstructionMatcher;
import dev.share.bytecodelens.pattern.ast.MatchSpec;
import dev.share.bytecodelens.pattern.ast.MethodCountPredicate;
import dev.share.bytecodelens.pattern.ast.MethodPattern;
import dev.share.bytecodelens.pattern.ast.NamePredicate;
import dev.share.bytecodelens.pattern.ast.NestedPattern;
import dev.share.bytecodelens.pattern.ast.NotPredicate;
import dev.share.bytecodelens.pattern.ast.OrPredicate;
import dev.share.bytecodelens.pattern.ast.Pattern;
import dev.share.bytecodelens.pattern.ast.Predicate;
import dev.share.bytecodelens.pattern.ast.Quantifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class Parser {

    private static final Set<String> CLASS_ACCESS = Set.of(
            "public", "private", "protected", "final", "abstract",
            "interface", "annotation", "enum", "record", "synthetic", "super", "module"
    );
    private static final Set<String> METHOD_ACCESS = Set.of(
            "public", "private", "protected", "static", "final", "synchronized",
            "abstract", "native", "bridge", "varargs", "strict", "synthetic"
    );
    private static final Set<String> FIELD_ACCESS = Set.of(
            "public", "private", "protected", "static", "final",
            "volatile", "transient", "synthetic", "enum"
    );
    private static final Set<String> OPCODE_NAMES = Set.of(
            "athrow", "monitorenter", "monitorexit", "arraylength", "return",
            "areturn", "ireturn", "lreturn", "freturn", "dreturn",
            "checkcast", "instanceof", "tableswitch", "lookupswitch"
    );

    private final List<Token> tokens;
    private int pos;

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public static Pattern parse(String source) {
        var tokens = new Lexer(source).tokenize();
        return new Parser(tokens).parsePattern();
    }

    public Pattern parsePattern() {
        Pattern result = parseBlock();
        if (current().kind() != Token.Kind.EOF) {
            throw err("Unexpected token after top-level pattern: " + current().text());
        }
        return result;
    }

    private Pattern parseBlock() {
        Token kind = expect(Token.Kind.IDENT);
        String kw = kind.text();
        expect(Token.Kind.LBRACE);
        List<Predicate> predicates = parsePredicateList(kw);
        expect(Token.Kind.RBRACE);
        return switch (kw) {
            case "class" -> new ClassPattern(predicates);
            case "method" -> new MethodPattern(predicates);
            case "field" -> new FieldPattern(predicates);
            default -> throw err("Expected class/method/field, got '" + kw + "'");
        };
    }

    private List<Predicate> parsePredicateList(String blockKw) {
        List<Predicate> out = new ArrayList<>();
        while (current().kind() != Token.Kind.RBRACE && current().kind() != Token.Kind.EOF) {
            Predicate p = parsePredicate(blockKw);
            if (current().kind() == Token.Kind.PIPE) {
                List<Predicate> alts = new ArrayList<>();
                alts.add(p);
                while (current().kind() == Token.Kind.PIPE) {
                    consume();
                    alts.add(parsePredicate(blockKw));
                }
                out.add(new OrPredicate(alts));
            } else {
                out.add(p);
            }
        }
        return out;
    }

    private Predicate parsePredicate(String blockKw) {
        if (current().kind() == Token.Kind.BANG) {
            consume();
            return new NotPredicate(parsePredicate(blockKw));
        }
        if (current().kind() != Token.Kind.IDENT) {
            throw err("Expected predicate name, got " + current().kind());
        }
        String word = current().text();
        if (word.equals("any") || word.equals("all") || word.equals("none")) {
            Quantifier q = switch (word) {
                case "any" -> Quantifier.ANY;
                case "all" -> Quantifier.ALL;
                default -> Quantifier.NONE;
            };
            consume();
            Pattern inner = parseBlock();
            if (!(inner instanceof MethodPattern || inner instanceof FieldPattern)) {
                throw err("Nested pattern must be method { } or field { }");
            }
            return new NestedPattern(q, inner);
        }
        return switch (word) {
            case "name" -> { consume(); yield new NamePredicate(parseMatchSpec()); }
            case "desc" -> { consume(); yield new DescPredicate(parseMatchSpec()); }
            case "extends" -> { consume(); yield new ExtendsPredicate(parseMatchSpec()); }
            case "implements" -> { consume(); yield new ImplementsPredicate(parseMatchSpec()); }
            case "annotation" -> { consume(); yield new AnnotationPredicate(parseMatchSpec()); }
            case "access" -> parseAccess(blockKw);
            case "instructions" -> { consume(); yield parseCount(InstructionKind.INSTRUCTIONS); }
            case "methods" -> { consume(); yield parseCount(InstructionKind.METHODS); }
            case "fields" -> { consume(); yield parseCount(InstructionKind.FIELDS); }
            case "contains" -> { consume(); yield new ContainsPredicate(parseInstructionMatcher()); }
            default -> throw err("Unknown predicate: '" + word + "'");
        };
    }

    private enum InstructionKind { INSTRUCTIONS, METHODS, FIELDS }

    private Predicate parseCount(InstructionKind kind) {
        CountOp op = parseCountOp();
        int value = Integer.parseInt(expect(Token.Kind.NUMBER).text());
        return switch (kind) {
            case INSTRUCTIONS -> new InstructionCountPredicate(op, value);
            case METHODS -> new MethodCountPredicate(op, value);
            case FIELDS -> new FieldCountPredicate(op, value);
        };
    }

    private CountOp parseCountOp() {
        Token t = current();
        consume();
        return switch (t.kind()) {
            case GT -> CountOp.GT;
            case LT -> CountOp.LT;
            case GE -> CountOp.GE;
            case LE -> CountOp.LE;
            case EQ -> CountOp.EQ;
            case NE -> CountOp.NE;
            default -> throw err("Expected comparison operator, got " + t.text());
        };
    }

    private Predicate parseAccess(String blockKw) {
        consume();
        String flag = expect(Token.Kind.IDENT).text();
        Set<String> allowed = switch (blockKw) {
            case "class" -> CLASS_ACCESS;
            case "method" -> METHOD_ACCESS;
            case "field" -> FIELD_ACCESS;
            default -> Set.of();
        };
        if (!allowed.contains(flag)) {
            throw err("Access flag '" + flag + "' not applicable to " + blockKw);
        }
        return new AccessPredicate(flag);
    }

    private MatchSpec parseMatchSpec() {
        Token t = current();
        if (t.kind() == Token.Kind.TILDE) {
            consume();
            Token rx = expect(Token.Kind.REGEX);
            return new MatchSpec.Regex(compileRegex(rx), rx.text());
        }
        if (t.kind() == Token.Kind.EQ) {
            consume();
            return literalSpec();
        }
        return literalSpec();
    }

    private java.util.regex.Pattern compileRegex(Token rx) {
        try {
            return java.util.regex.Pattern.compile(rx.text());
        } catch (java.util.regex.PatternSyntaxException ex) {
            throw new PatternParseException("Invalid regex: " + ex.getDescription(), rx.line(), rx.col());
        }
    }

    private MatchSpec literalSpec() {
        Token t = current();
        if (t.kind() == Token.Kind.STAR) {
            consume();
            return new MatchSpec.Wildcard();
        }
        if (t.kind() == Token.Kind.STRING) {
            consume();
            return new MatchSpec.Literal(t.text());
        }
        if (t.kind() == Token.Kind.REGEX) {
            consume();
            return new MatchSpec.Regex(compileRegex(t), t.text());
        }
        if (t.kind() == Token.Kind.IDENT) {
            consume();
            return new MatchSpec.Literal(t.text());
        }
        throw err("Expected string, regex or wildcard, got " + t.kind());
    }

    private InstructionMatcher parseInstructionMatcher() {
        String op = expect(Token.Kind.IDENT).text();
        return switch (op) {
            case "ldc" -> new InstructionMatcher.Ldc(parseMatchSpec());
            case "invoke" -> parseInvoke();
            case "getfield", "putfield", "getstatic", "putstatic" -> parseFieldAccess(op);
            case "new" -> new InstructionMatcher.NewInstance(parseMatchSpec());
            case "opcode" -> new InstructionMatcher.Opcode(expect(Token.Kind.IDENT).text());
            default -> {
                if (OPCODE_NAMES.contains(op)) yield new InstructionMatcher.Opcode(op);
                throw err("Unknown instruction matcher: '" + op + "'");
            }
        };
    }

    private InstructionMatcher.Invoke parseInvoke() {
        MatchSpec owner;
        MatchSpec name;
        if (current().kind() == Token.Kind.STAR) {
            consume();
            owner = new MatchSpec.Wildcard();
            expect(Token.Kind.DOT);
            name = literalSpec();
        } else if (current().kind() == Token.Kind.REGEX || current().kind() == Token.Kind.STRING || current().kind() == Token.Kind.IDENT) {
            MatchSpec first = literalSpec();
            if (current().kind() == Token.Kind.DOT) {
                consume();
                owner = first;
                name = literalSpec();
            } else {
                owner = new MatchSpec.Wildcard();
                name = first;
            }
        } else if (current().kind() == Token.Kind.TILDE) {
            MatchSpec first = parseMatchSpec();
            if (current().kind() == Token.Kind.DOT) {
                consume();
                owner = first;
                name = literalSpec();
            } else {
                owner = new MatchSpec.Wildcard();
                name = first;
            }
        } else {
            throw err("Expected invoke target");
        }
        MatchSpec desc = new MatchSpec.Wildcard();
        return new InstructionMatcher.Invoke(owner, name, desc);
    }

    private InstructionMatcher.FieldAccess parseFieldAccess(String op) {
        InstructionMatcher.FieldOp fo = switch (op) {
            case "getfield" -> InstructionMatcher.FieldOp.GETFIELD;
            case "putfield" -> InstructionMatcher.FieldOp.PUTFIELD;
            case "getstatic" -> InstructionMatcher.FieldOp.GETSTATIC;
            case "putstatic" -> InstructionMatcher.FieldOp.PUTSTATIC;
            default -> throw err("?");
        };
        MatchSpec owner;
        MatchSpec name;
        if (current().kind() == Token.Kind.STAR) {
            consume();
            owner = new MatchSpec.Wildcard();
            expect(Token.Kind.DOT);
            name = literalSpec();
        } else {
            MatchSpec first = literalSpec();
            if (current().kind() == Token.Kind.DOT) {
                consume();
                owner = first;
                name = literalSpec();
            } else {
                owner = new MatchSpec.Wildcard();
                name = first;
            }
        }
        return new InstructionMatcher.FieldAccess(fo, owner, name);
    }

    private Token current() {
        return tokens.get(pos);
    }

    private void consume() {
        pos++;
    }

    private Token expect(Token.Kind kind) {
        Token t = current();
        if (t.kind() != kind) {
            throw err("Expected " + kind + " but got " + t.kind() + " ('" + t.text() + "')");
        }
        pos++;
        return t;
    }

    private PatternParseException err(String msg) {
        Token t = current();
        return new PatternParseException(msg, t.line(), t.col());
    }
}
