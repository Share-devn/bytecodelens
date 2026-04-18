package dev.share.bytecodelens.mapping;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/**
 * Parses TSRG v2 — multi-namespace TSRG used by ParchmentMC and modern Forge tooling.
 *
 * <pre>{@code
 * tsrg2 obf srg
 * a/b com/example/Foo
 *     fldObf fldNamed
 *     mObf ()V mNamed
 *         static
 *         0 paramObf paramNamed
 * }</pre>
 *
 * <p>Header line names the source and target namespaces. We only consume the FIRST
 * extra namespace (column 2 after the obf one) since {@link MappingModel} is bilateral.
 * Indented method-body lines (parameters, {@code static} marker) are skipped.</p>
 *
 * <p>Method/field rows: 2-tab indent → method or field; deeper indent → param or static
 * marker. We detect indent level by counting leading tabs.</p>
 */
public final class TsrgV2MappingParser {

    private TsrgV2MappingParser() {}

    public static MappingModel parse(Reader in) throws IOException {
        MappingModel.Builder out = MappingModel.builder(MappingFormat.TSRG_V2);
        String currentObfClass = null;
        String currentObfMethod = null; // tracks whether deeper indent applies to a method
        try (BufferedReader br = new BufferedReader(in)) {
            String line;
            boolean headerSeen = false;
            while ((line = br.readLine()) != null) {
                if (line.isBlank() || line.trim().startsWith("#")) continue;
                if (!headerSeen) {
                    if (line.trim().startsWith("tsrg2")) {
                        headerSeen = true;
                        continue;
                    }
                    headerSeen = true; // accept missing header (some tools omit it)
                }
                int indent = leadingTabs(line);
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 0) continue;

                switch (indent) {
                    case 0 -> {
                        // Class: obf  named  (extra namespaces ignored)
                        if (parts.length >= 2) {
                            currentObfClass = parts[0];
                            currentObfMethod = null;
                            out.mapClass(parts[0], parts[1]);
                        }
                    }
                    case 1 -> {
                        if (currentObfClass == null) break;
                        if (parts.length >= 3 && parts[1].startsWith("(")) {
                            // Method: name  (desc)  newName  …
                            out.mapMethod(currentObfClass, parts[0], parts[1], parts[2]);
                            currentObfMethod = parts[0];
                        } else if (parts.length >= 2) {
                            // Field: name  newName
                            out.mapField(currentObfClass, parts[0], "", parts[1]);
                            currentObfMethod = null;
                        }
                    }
                    default -> {
                        // Method body lines (params, static marker) — ignore for our model.
                        if (currentObfMethod == null) break;
                    }
                }
            }
        }
        return out.build();
    }

    private static int leadingTabs(String s) {
        int n = 0;
        while (n < s.length() && s.charAt(n) == '\t') n++;
        return n;
    }
}
