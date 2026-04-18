package dev.share.bytecodelens.ui.highlight;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BytecodeHighlighter {

    private static final Set<String> OPCODES = Set.of(
            "nop", "aconst_null", "iconst_m1", "iconst_0", "iconst_1", "iconst_2", "iconst_3",
            "iconst_4", "iconst_5", "lconst_0", "lconst_1", "fconst_0", "fconst_1", "fconst_2",
            "dconst_0", "dconst_1", "bipush", "sipush", "ldc", "ldc_w", "ldc2_w",
            "iload", "lload", "fload", "dload", "aload",
            "iload_0", "iload_1", "iload_2", "iload_3", "lload_0", "lload_1", "lload_2", "lload_3",
            "fload_0", "fload_1", "fload_2", "fload_3", "dload_0", "dload_1", "dload_2", "dload_3",
            "aload_0", "aload_1", "aload_2", "aload_3",
            "iaload", "laload", "faload", "daload", "aaload", "baload", "caload", "saload",
            "istore", "lstore", "fstore", "dstore", "astore",
            "istore_0", "istore_1", "istore_2", "istore_3", "lstore_0", "lstore_1", "lstore_2", "lstore_3",
            "fstore_0", "fstore_1", "fstore_2", "fstore_3", "dstore_0", "dstore_1", "dstore_2", "dstore_3",
            "astore_0", "astore_1", "astore_2", "astore_3",
            "iastore", "lastore", "fastore", "dastore", "aastore", "bastore", "castore", "sastore",
            "pop", "pop2", "dup", "dup_x1", "dup_x2", "dup2", "dup2_x1", "dup2_x2", "swap",
            "iadd", "ladd", "fadd", "dadd", "isub", "lsub", "fsub", "dsub",
            "imul", "lmul", "fmul", "dmul", "idiv", "ldiv", "fdiv", "ddiv",
            "irem", "lrem", "frem", "drem", "ineg", "lneg", "fneg", "dneg",
            "ishl", "lshl", "ishr", "lshr", "iushr", "lushr",
            "iand", "land", "ior", "lor", "ixor", "lxor", "iinc",
            "i2l", "i2f", "i2d", "l2i", "l2f", "l2d", "f2i", "f2l", "f2d", "d2i", "d2l", "d2f",
            "i2b", "i2c", "i2s", "lcmp", "fcmpl", "fcmpg", "dcmpl", "dcmpg",
            "ifeq", "ifne", "iflt", "ifge", "ifgt", "ifle",
            "if_icmpeq", "if_icmpne", "if_icmplt", "if_icmpge", "if_icmpgt", "if_icmple",
            "if_acmpeq", "if_acmpne",
            "goto", "jsr", "ret", "tableswitch", "lookupswitch",
            "ireturn", "lreturn", "freturn", "dreturn", "areturn", "return",
            "getstatic", "putstatic", "getfield", "putfield",
            "invokevirtual", "invokespecial", "invokestatic", "invokeinterface", "invokedynamic",
            "new", "newarray", "anewarray", "arraylength", "athrow",
            "checkcast", "instanceof", "monitorenter", "monitorexit",
            "wide", "multianewarray", "ifnull", "ifnonnull", "goto_w", "jsr_w"
    );

    private static final Set<String> KEYWORDS = Set.of(
            "public", "private", "protected", "static", "final", "abstract", "synchronized",
            "native", "strictfp", "transient", "volatile", "class", "interface", "enum",
            "extends", "implements", "throws", "super", "synthetic", "bridge", "varargs",
            "ACC_PUBLIC", "ACC_PRIVATE", "ACC_PROTECTED", "ACC_STATIC", "ACC_FINAL",
            "ACC_SUPER", "ACC_INTERFACE", "ACC_ABSTRACT", "ACC_SYNTHETIC", "ACC_ANNOTATION",
            "ACC_ENUM", "ACC_RECORD", "ACC_BRIDGE", "ACC_VARARGS", "ACC_NATIVE", "ACC_STRICT",
            "ACC_VOLATILE", "ACC_TRANSIENT", "ACC_SYNCHRONIZED", "MAXSTACK", "MAXLOCALS",
            "LOCALVARIABLE", "LINENUMBER", "TRYCATCHBLOCK", "FRAME", "LABEL",
            "boolean", "byte", "short", "int", "long", "float", "double", "char", "void"
    );

    private static final Pattern LABEL = Pattern.compile("^\\s*(L\\d+|T\\d+)\\b");
    private static final Pattern NUMBER = Pattern.compile("\\b-?\\d+(\\.\\d+)?\\b");
    private static final Pattern COMMENT = Pattern.compile("//[^\\n]*");
    private static final Pattern STRING = Pattern.compile("\"([^\"\\\\]|\\\\.)*\"");
    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private BytecodeHighlighter() {
    }

    public static StyleSpans<Collection<String>> compute(String text) {
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
        int lastEnd = 0;

        Matcher comment = COMMENT.matcher(text);
        Matcher string = STRING.matcher(text);
        Matcher label = LABEL.matcher(text);
        Matcher number = NUMBER.matcher(text);
        Matcher word = WORD.matcher(text);

        record Span(int start, int end, String klass) {
        }

        List<Span> all = new java.util.ArrayList<>();
        while (comment.find()) all.add(new Span(comment.start(), comment.end(), "code-comment"));
        while (string.find()) all.add(new Span(string.start(), string.end(), "code-string"));
        while (label.find()) all.add(new Span(label.start(1), label.end(1), "code-label"));
        while (number.find()) all.add(new Span(number.start(), number.end(), "code-number"));
        while (word.find()) {
            String w = word.group();
            if (OPCODES.contains(w.toLowerCase())) {
                all.add(new Span(word.start(), word.end(), "code-opcode"));
            } else if (KEYWORDS.contains(w)) {
                all.add(new Span(word.start(), word.end(), "code-keyword"));
            }
        }

        all.sort((a, b) -> Integer.compare(a.start(), b.start()));
        // dedupe overlaps: prefer earlier, skip any span starting before lastEnd
        List<Span> merged = new java.util.ArrayList<>();
        int cursor = 0;
        for (Span s : all) {
            if (s.start() < cursor) continue;
            merged.add(s);
            cursor = s.end();
        }

        for (Span s : merged) {
            if (s.start() > lastEnd) {
                spans.add(Collections.emptyList(), s.start() - lastEnd);
            }
            spans.add(Collections.singleton(s.klass()), s.end() - s.start());
            lastEnd = s.end();
        }
        if (lastEnd < text.length()) {
            spans.add(Collections.emptyList(), text.length() - lastEnd);
        }
        return spans.create();
    }
}
