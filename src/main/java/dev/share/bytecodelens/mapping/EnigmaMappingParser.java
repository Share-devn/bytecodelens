package dev.share.bytecodelens.mapping;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Parses Enigma {@code .mapping} / {@code .mappings} files.
 *
 * <p>Enigma is the only indent-sensitive format we handle. A CLASS line at indent {@code N}
 * is owner of following FIELD/METHOD lines at indent {@code N+1}. Nested CLASS lines under
 * a CLASS are inner classes — we concatenate them with {@code $}.</p>
 *
 * <pre>{@code
 * CLASS a/b com/foo/Bar
 *     FIELD c old_name I
 *     METHOD m old_method ()V
 *         ARG 0 argName
 *     CLASS inner com/foo/Bar$Inner
 * }</pre>
 */
public final class EnigmaMappingParser {

    private EnigmaMappingParser() {}

    public static MappingModel parse(Reader in) throws IOException {
        MappingModel.Builder out = MappingModel.builder(MappingFormat.ENIGMA);

        // Stack of currently-open CLASS scopes with their obfuscated internal names, one per
        // nesting level. Deepest element is the direct parent of any indented member.
        Deque<String> classStack = new ArrayDeque<>();
        Deque<Integer> indentStack = new ArrayDeque<>();

        try (BufferedReader br = new BufferedReader(in)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank() || line.trim().startsWith("#")) continue;

                int indent = countLeadingTabs(line);
                // Pop scopes whose indent is >= current — they're done
                while (!indentStack.isEmpty() && indentStack.peek() >= indent) {
                    classStack.pop();
                    indentStack.pop();
                }

                String[] parts = line.trim().split("\\s+");
                String kind = parts[0];

                switch (kind) {
                    case "CLASS" -> {
                        if (parts.length < 2) continue;
                        String obf = parts[1];
                        String named = parts.length >= 3 ? parts[2] : null;
                        // Child CLASS under another CLASS is an inner class — compose with $
                        String fullObf;
                        if (!classStack.isEmpty()) {
                            fullObf = classStack.peek() + "$" + obf;
                        } else {
                            fullObf = obf;
                        }
                        if (named != null && !named.equals(fullObf)) {
                            String fullNamed;
                            if (!classStack.isEmpty() && !named.contains("/")) {
                                // Inner class uses a bare name; inherit outer package
                                String outer = out.build().mapClass(classStack.peek());
                                fullNamed = outer + "$" + named;
                            } else {
                                fullNamed = named;
                            }
                            out.mapClass(fullObf, fullNamed);
                        }
                        classStack.push(fullObf);
                        indentStack.push(indent);
                    }
                    case "FIELD" -> {
                        if (parts.length < 4 || classStack.isEmpty()) continue;
                        String obf = parts[1];
                        // Enigma omits the named part if it equals obf; in that case parts has 3 tokens.
                        // Most real files keep all 4: FIELD <obf> <named> <desc>
                        String named = parts[2];
                        String desc = parts[3];
                        if (!named.equals(obf)) {
                            out.mapField(classStack.peek(), obf, desc, named);
                        }
                    }
                    case "METHOD" -> {
                        if (parts.length < 4 || classStack.isEmpty()) continue;
                        String obf = parts[1];
                        String named = parts[2];
                        String desc = parts[3];
                        if (!named.equals(obf)) {
                            out.mapMethod(classStack.peek(), obf, desc, named);
                        }
                    }
                    default -> { /* ARG, COMMENT, etc. — ignored */ }
                }
            }
        }
        return out.build();
    }

    private static int countLeadingTabs(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == '\t') n++;
        return n;
    }
}
