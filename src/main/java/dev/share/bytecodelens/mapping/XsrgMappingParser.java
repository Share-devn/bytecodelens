package dev.share.bytecodelens.mapping;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/**
 * Parses XSRG ("eXtended SRG"), the SRG variant that adds field descriptors so the
 * applier can disambiguate overloaded fields.
 *
 * <pre>{@code
 * CL: a/b com/example/Foo
 * FD: a/b/c I com/example/Foo/field I
 * MD: a/b/m ()V com/example/Foo/method ()V
 * }</pre>
 *
 * <p>FD lines now carry a descriptor between the obf path and the new path. Other rows
 * match SRG v1 exactly.</p>
 */
public final class XsrgMappingParser {

    private XsrgMappingParser() {}

    public static MappingModel parse(Reader in) throws IOException {
        MappingModel.Builder out = MappingModel.builder(MappingFormat.XSRG);
        try (BufferedReader br = new BufferedReader(in)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2) continue;
                switch (parts[0]) {
                    case "CL:" -> {
                        if (parts.length >= 3) out.mapClass(parts[1], parts[2]);
                    }
                    case "FD:" -> {
                        // FD: obfPath obfDesc namedPath namedDesc
                        if (parts.length >= 5) {
                            String obfPath = parts[1];
                            String obfDesc = parts[2];
                            String namedPath = parts[3];
                            int obfSlash = obfPath.lastIndexOf('/');
                            int namedSlash = namedPath.lastIndexOf('/');
                            if (obfSlash > 0 && namedSlash > 0) {
                                out.mapField(obfPath.substring(0, obfSlash),
                                        obfPath.substring(obfSlash + 1),
                                        obfDesc,
                                        namedPath.substring(namedSlash + 1));
                            }
                        }
                    }
                    case "MD:" -> {
                        if (parts.length >= 5) {
                            String obfPath = parts[1];
                            String obfDesc = parts[2];
                            String namedPath = parts[3];
                            int obfSlash = obfPath.lastIndexOf('/');
                            int namedSlash = namedPath.lastIndexOf('/');
                            if (obfSlash > 0 && namedSlash > 0) {
                                out.mapMethod(obfPath.substring(0, obfSlash),
                                        obfPath.substring(obfSlash + 1),
                                        obfDesc,
                                        namedPath.substring(namedSlash + 1));
                            }
                        }
                    }
                    default -> { /* PK: + unknown — skip */ }
                }
            }
        }
        return out.build();
    }
}
