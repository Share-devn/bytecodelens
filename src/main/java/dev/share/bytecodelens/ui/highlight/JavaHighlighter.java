package dev.share.bytecodelens.ui.highlight;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaHighlighter {

    private static final String[] KEYWORDS = {
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new", "non-sealed",
            "package", "permits", "private", "protected", "public", "record", "return",
            "sealed", "short", "static", "strictfp", "super", "switch", "synchronized",
            "this", "throw", "throws", "transient", "try", "var", "void", "volatile",
            "while", "yield"
    };

    private static final String KEYWORD_PATTERN = "\\b(" + String.join("|", KEYWORDS) + ")\\b";
    private static final String ANNOTATION_PATTERN = "@[A-Za-z_][A-Za-z0-9_]*";
    private static final String STRING_PATTERN = "\"([^\"\\\\]|\\\\.)*\"";
    private static final String CHAR_PATTERN = "'([^'\\\\]|\\\\.)'";
    private static final String COMMENT_LINE = "//[^\\n]*";
    private static final String COMMENT_BLOCK = "/\\*[\\s\\S]*?\\*/";
    private static final String NUMBER_PATTERN = "\\b-?\\d+(?:\\.\\d+)?[FfLlDd]?\\b|\\b0x[0-9a-fA-F]+[Ll]?\\b";
    private static final String TYPE_PATTERN = "\\b[A-Z][A-Za-z0-9_]*\\b";

    private static final Pattern PATTERN = Pattern.compile(
            "(?<COMMENTBLOCK>" + COMMENT_BLOCK + ")"
                    + "|(?<COMMENTLINE>" + COMMENT_LINE + ")"
                    + "|(?<STRING>" + STRING_PATTERN + ")"
                    + "|(?<CHAR>" + CHAR_PATTERN + ")"
                    + "|(?<KEYWORD>" + KEYWORD_PATTERN + ")"
                    + "|(?<ANNOTATION>" + ANNOTATION_PATTERN + ")"
                    + "|(?<NUMBER>" + NUMBER_PATTERN + ")"
                    + "|(?<TYPE>" + TYPE_PATTERN + ")");

    private JavaHighlighter() {
    }

    public static StyleSpans<Collection<String>> compute(String text) {
        Matcher m = PATTERN.matcher(text);
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
        int last = 0;
        while (m.find()) {
            String style =
                    m.group("COMMENTBLOCK") != null ? "code-comment"
                            : m.group("COMMENTLINE") != null ? "code-comment"
                            : m.group("STRING") != null ? "code-string"
                            : m.group("CHAR") != null ? "code-string"
                            : m.group("KEYWORD") != null ? "code-keyword"
                            : m.group("ANNOTATION") != null ? "code-annotation"
                            : m.group("NUMBER") != null ? "code-number"
                            : m.group("TYPE") != null ? "code-type"
                            : null;
            if (style == null) continue;
            spans.add(Collections.emptyList(), m.start() - last);
            spans.add(Collections.singleton(style), m.end() - m.start());
            last = m.end();
        }
        spans.add(Collections.emptyList(), text.length() - last);
        return spans.create();
    }
}
