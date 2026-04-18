package dev.share.bytecodelens.mapping;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/**
 * Parses JOBF (Java OBFuscator) text mapping format — used by some commercial Java
 * obfuscators that ship a sidecar mapping for support purposes.
 *
 * <pre>{@code
 * .class_map a.b com/example/Foo
 * .field_map a.b.c newField
 * .method_map a.b.m()V newMethod
 * }</pre>
 *
 * <p>Names use dot notation in the source paths and slash notation in renamed targets;
 * we normalise dots to slashes in classMap entries and parse {@code owner.member}
 * pairs so internal-name lookups work uniformly.</p>
 *
 * <p>Tolerant of whitespace variations; comment lines start with {@code #} or {@code //}.</p>
 */
public final class JobfMappingParser {

    private JobfMappingParser() {}

    public static MappingModel parse(Reader in) throws IOException {
        MappingModel.Builder out = MappingModel.builder(MappingFormat.JOBF);
        try (BufferedReader br = new BufferedReader(in)) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) continue;
                String[] parts = t.split("\\s+");
                if (parts.length < 3) continue;
                switch (parts[0]) {
                    case ".class_map" -> {
                        out.mapClass(dotToSlash(parts[1]), dotToSlash(parts[2]));
                    }
                    case ".field_map" -> {
                        // .field_map  owner.fieldName  newName
                        String full = parts[1];
                        int dot = full.lastIndexOf('.');
                        if (dot > 0) {
                            String owner = dotToSlash(full.substring(0, dot));
                            String fieldName = full.substring(dot + 1);
                            out.mapField(owner, fieldName, "", parts[2]);
                        }
                    }
                    case ".method_map" -> {
                        // .method_map  owner.method(desc)  newName
                        String full = parts[1];
                        int paren = full.indexOf('(');
                        if (paren < 0) break;
                        String desc = full.substring(paren);
                        String prefix = full.substring(0, paren);
                        int dot = prefix.lastIndexOf('.');
                        if (dot > 0) {
                            String owner = dotToSlash(prefix.substring(0, dot));
                            String name = prefix.substring(dot + 1);
                            out.mapMethod(owner, name, desc, parts[2]);
                        }
                    }
                    default -> { /* unknown directive — ignore */ }
                }
            }
        }
        return out.build();
    }

    private static String dotToSlash(String s) { return s.replace('.', '/'); }
}
