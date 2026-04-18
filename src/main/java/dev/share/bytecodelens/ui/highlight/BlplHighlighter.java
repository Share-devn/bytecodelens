package dev.share.bytecodelens.ui.highlight;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BlplHighlighter {

    private static final String KEYWORDS = "\\b(class|method|field|contains|name|desc|access|"
            + "extends|implements|annotation|instructions|methods|fields|"
            + "any|all|none|ldc|invoke|new|opcode|getfield|putfield|getstatic|putstatic)\\b";

    private static final String ACCESS_KW = "\\b(public|private|protected|static|final|abstract|"
            + "synchronized|native|bridge|varargs|interface|enum|annotation|record|"
            + "volatile|transient|synthetic|super|module)\\b";

    private static final String OPCODES = "\\b(athrow|monitorenter|monitorexit|arraylength|return|"
            + "areturn|ireturn|lreturn|freturn|dreturn|checkcast|instanceof|tableswitch|lookupswitch)\\b";

    private static final String COMMENT = "//[^\\n]*";
    private static final String STRING = "\"([^\"\\\\]|\\\\.)*\"";
    private static final String REGEX = "/([^/\\\\\\n]|\\\\.)+/[a-zA-Z]*";
    private static final String NUMBER = "\\b\\d+\\b";
    private static final String OPERATOR = "[{}()<>=!~|*.]|<=|>=|!=";

    private static final Pattern PATTERN = Pattern.compile(
            "(?<COMMENT>" + COMMENT + ")"
                    + "|(?<STRING>" + STRING + ")"
                    + "|(?<REGEX>" + REGEX + ")"
                    + "|(?<KEYWORD>" + KEYWORDS + ")"
                    + "|(?<ACCESS>" + ACCESS_KW + ")"
                    + "|(?<OPCODE>" + OPCODES + ")"
                    + "|(?<NUMBER>" + NUMBER + ")"
                    + "|(?<OPERATOR>" + OPERATOR + ")");

    private BlplHighlighter() {
    }

    public static StyleSpans<Collection<String>> compute(String text) {
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
        if (text == null || text.isEmpty()) {
            spans.add(Collections.emptyList(), 0);
            return spans.create();
        }
        Matcher m = PATTERN.matcher(text);
        int last = 0;
        while (m.find()) {
            String style =
                    m.group("COMMENT") != null ? "code-comment"
                            : m.group("STRING") != null ? "code-string"
                            : m.group("REGEX") != null ? "code-regex"
                            : m.group("KEYWORD") != null ? "code-keyword"
                            : m.group("ACCESS") != null ? "code-type"
                            : m.group("OPCODE") != null ? "code-opcode"
                            : m.group("NUMBER") != null ? "code-number"
                            : m.group("OPERATOR") != null ? "code-operator"
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
