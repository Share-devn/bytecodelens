package dev.share.bytecodelens.mapping;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/**
 * Parses TSRG v1 (two-column SRG) files used by more recent Forge tooling.
 *
 * <pre>{@code
 * a/b com/example/Foo
 * 	fieldObf fieldNew
 * 	methodObf ()V methodNew
 * another/cls com/example/Another
 * }</pre>
 *
 * <p>Class lines start at column 0 with {@code obf named}. Member lines are tab-indented:
 * either {@code name name} (field) or {@code name desc name} (method).</p>
 */
public final class TsrgMappingParser {

    private TsrgMappingParser() {}

    public static MappingModel parse(Reader in) throws IOException {
        MappingModel.Builder out = MappingModel.builder(MappingFormat.TSRG);
        String currentObfClass = null;

        try (BufferedReader br = new BufferedReader(in)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;

                boolean indented = line.startsWith("\t") || line.startsWith(" ");
                String[] parts = line.trim().split("\\s+");

                if (!indented) {
                    // Class line: "obf named" (or "tsrg2" header — ignore)
                    if (parts.length >= 2 && !parts[0].equals("tsrg2")) {
                        currentObfClass = parts[0];
                        out.mapClass(parts[0], parts[1]);
                    }
                } else {
                    if (currentObfClass == null) continue;
                    if (parts.length == 2) {
                        // Field: "obfName newName" (SRG v1 tsrg has no field descriptors)
                        out.mapField(currentObfClass, parts[0], "", parts[1]);
                    } else if (parts.length >= 3) {
                        // Method: "obfName (desc) newName"
                        out.mapMethod(currentObfClass, parts[0], parts[1], parts[2]);
                    }
                }
            }
        }
        return out.build();
    }
}
