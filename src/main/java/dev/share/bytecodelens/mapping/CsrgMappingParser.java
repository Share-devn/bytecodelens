package dev.share.bytecodelens.mapping;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/**
 * Parses CSRG (Compact SRG) — Bukkit/Spigot's flat one-line-per-mapping format.
 *
 * <pre>{@code
 * a/b com/example/Foo
 * a/b c newField
 * a/b m ()V newMethod
 * }</pre>
 *
 * <p>Row arity decides type:
 * 2 columns → class rename;
 * 3 columns → field rename in owner;
 * 4 columns → method rename (3rd column is the descriptor).</p>
 */
public final class CsrgMappingParser {

    private CsrgMappingParser() {}

    public static MappingModel parse(Reader in) throws IOException {
        MappingModel.Builder out = MappingModel.builder(MappingFormat.CSRG);
        try (BufferedReader br = new BufferedReader(in)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.trim().split("\\s+");
                switch (parts.length) {
                    case 2 -> out.mapClass(parts[0], parts[1]);
                    case 3 -> out.mapField(parts[0], parts[1], "", parts[2]);
                    case 4 -> {
                        // owner  obfMethod  desc  newMethod
                        if (parts[2].startsWith("(")) {
                            out.mapMethod(parts[0], parts[1], parts[2], parts[3]);
                        }
                    }
                    default -> { /* unknown shape — ignore */ }
                }
            }
        }
        return out.build();
    }
}
