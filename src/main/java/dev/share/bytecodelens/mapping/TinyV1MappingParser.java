package dev.share.bytecodelens.mapping;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/**
 * Parses Tiny v1 mappings — Fabric's predecessor to Tiny v2. Tab-separated.
 *
 * <pre>{@code
 * v1\tofficial\tnamed
 * CLASS\ta/b\tcom/example/Foo
 * FIELD\ta/b\tI\tobfFld\tnewFld
 * METHOD\ta/b\t()V\tobfM\tnewM
 * }</pre>
 *
 * <p>Field/method lines carry the descriptor explicitly (in the official namespace),
 * so unlike SRG v1 we don't need to drop it.</p>
 */
public final class TinyV1MappingParser {

    private TinyV1MappingParser() {}

    public static MappingModel parse(Reader in) throws IOException {
        MappingModel.Builder out = MappingModel.builder(MappingFormat.TINY_V1);
        try (BufferedReader br = new BufferedReader(in)) {
            String line;
            boolean headerSeen = false;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\t");
                if (!headerSeen) {
                    if (parts.length >= 3 && "v1".equals(parts[0])) {
                        headerSeen = true;
                        continue;
                    }
                    // Lenient — also accept files without header that otherwise look right.
                    headerSeen = true;
                }
                if (parts.length < 3) continue;
                switch (parts[0]) {
                    case "CLASS" -> {
                        // CLASS  obfInternal  newInternal
                        if (parts.length >= 3) out.mapClass(parts[1], parts[2]);
                    }
                    case "FIELD" -> {
                        // FIELD  ownerObf  desc  obfName  newName
                        if (parts.length >= 5) {
                            out.mapField(parts[1], parts[3], parts[2], parts[4]);
                        }
                    }
                    case "METHOD" -> {
                        // METHOD  ownerObf  desc  obfName  newName
                        if (parts.length >= 5) {
                            out.mapMethod(parts[1], parts[3], parts[2], parts[4]);
                        }
                    }
                    default -> { /* unknown row type — ignore */ }
                }
            }
        }
        return out.build();
    }
}
