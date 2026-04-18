package dev.share.bytecodelens.mapping;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/**
 * Parses SRG v1 (Searge Range Generator) mapping files used by older Forge/MCP.
 *
 * <pre>{@code
 * PK: net/minecraft/src net/minecraft/src
 * CL: a/b com/example/Foo
 * FD: a/b/c com/example/Foo/field
 * MD: a/b/m ()V com/example/Foo/method ()V
 * }</pre>
 *
 * <p>Last column of FD/MD is a path (owner/name) in the renamed namespace — we only keep
 * the name portion since owner renames are already captured by CL entries.</p>
 */
public final class SrgMappingParser {

    private SrgMappingParser() {}

    public static MappingModel parse(Reader in) throws IOException {
        MappingModel.Builder out = MappingModel.builder(MappingFormat.SRG);

        try (BufferedReader br = new BufferedReader(in)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2) continue;

                switch (parts[0]) {
                    case "CL:" -> {
                        if (parts.length >= 3) {
                            out.mapClass(parts[1], parts[2]);
                        }
                    }
                    case "FD:" -> {
                        if (parts.length >= 3) {
                            // obf path "a/b/c" -> "a/b" owner + "c" name
                            String obfPath = parts[1];
                            String namedPath = parts[2];
                            int obfSlash = obfPath.lastIndexOf('/');
                            int namedSlash = namedPath.lastIndexOf('/');
                            if (obfSlash > 0 && namedSlash > 0) {
                                String owner = obfPath.substring(0, obfSlash);
                                String obfName = obfPath.substring(obfSlash + 1);
                                String newName = namedPath.substring(namedSlash + 1);
                                // SRG v1 lacks descriptors on fields — use special marker.
                                // Apply will handle "field without desc" as best-effort.
                                out.mapField(owner, obfName, "", newName);
                            }
                        }
                    }
                    case "MD:" -> {
                        if (parts.length >= 5) {
                            String obfPath = parts[1];   // a/b/m
                            String obfDesc = parts[2];   // ()V
                            String namedPath = parts[3]; // com/example/Foo/method
                            int obfSlash = obfPath.lastIndexOf('/');
                            int namedSlash = namedPath.lastIndexOf('/');
                            if (obfSlash > 0 && namedSlash > 0) {
                                String owner = obfPath.substring(0, obfSlash);
                                String obfName = obfPath.substring(obfSlash + 1);
                                String newName = namedPath.substring(namedSlash + 1);
                                out.mapMethod(owner, obfName, obfDesc, newName);
                            }
                        }
                    }
                    default -> { /* PK: and unknown prefixes — skip */ }
                }
            }
        }
        return out.build();
    }
}
