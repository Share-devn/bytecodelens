package dev.share.bytecodelens.mapping;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/**
 * Parses Recaf's "Simple" text mapping format. Whitespace-separated, one entry per line.
 *
 * <pre>{@code
 * a/b com/example/Foo
 * a/b.c newField
 * a/b.m()V newMethod
 * }</pre>
 *
 * <p>The shape of the first column tells us the row type:
 * <ul>
 *   <li>internal name only — class rename (target on the right)</li>
 *   <li>{@code owner.field} — field rename (no descriptor)</li>
 *   <li>{@code owner.method(desc)} — method rename (descriptor inline)</li>
 * </ul>
 * </p>
 */
public final class RecafMappingParser {

    private RecafMappingParser() {}

    public static MappingModel parse(Reader in) throws IOException {
        MappingModel.Builder out = MappingModel.builder(MappingFormat.RECAF);
        try (BufferedReader br = new BufferedReader(in)) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String[] parts = t.split("\\s+");
                if (parts.length != 2) continue;
                String left = parts[0];
                String right = parts[1];

                int paren = left.indexOf('(');
                int dot = left.lastIndexOf('.', paren < 0 ? left.length() - 1 : paren);
                if (paren > 0 && dot > 0 && dot < paren) {
                    String owner = left.substring(0, dot);
                    String name = left.substring(dot + 1, paren);
                    String desc = left.substring(paren);
                    out.mapMethod(owner, name, desc, right);
                } else if (dot > 0) {
                    String owner = left.substring(0, dot);
                    String name = left.substring(dot + 1);
                    out.mapField(owner, name, "", right);
                } else {
                    out.mapClass(left, right);
                }
            }
        }
        return out.build();
    }
}
