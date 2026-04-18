package dev.share.bytecodelens.mapping;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/**
 * Parses FabricMC Tiny v2 mapping files.
 *
 * <p>Format header: {@code tiny\t2\t0\t<namespace0>\t<namespace1>[\t<ns2>...]}.
 * We take namespace 0 as the "from" (obfuscated) and namespace 1 as the "to" (named).</p>
 *
 * <p>Entries use tab indentation: {@code c} / {@code f} / {@code m} at column 0 is a class,
 * indented {@code f}/{@code m} under it is a member. Descriptors in tiny v2 are already
 * in JVMS form on the source namespace side.</p>
 *
 * <pre>{@code
 * tiny	2	0	official	named
 * c	com/foo/Bar	com/example/Renamed
 * 	f	I	old	new
 * 	m	()V	oldMethod	newMethod
 * }</pre>
 */
public final class TinyV2MappingParser {

    private TinyV2MappingParser() {}

    public static MappingModel parse(Reader in) throws IOException {
        MappingModel.Builder out = MappingModel.builder(MappingFormat.TINY_V2);
        String currentObfClass = null;

        try (BufferedReader br = new BufferedReader(in)) {
            String line;
            boolean headerSeen = false;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\t", -1);

                if (!headerSeen) {
                    // Expected: tiny 2 <minor> <ns0> <ns1> [...]
                    if (parts.length >= 5 && "tiny".equals(parts[0]) && "2".equals(parts[1])) {
                        headerSeen = true;
                        continue;
                    } else {
                        throw new IOException("Not a Tiny v2 file: header missing");
                    }
                }

                // Class: "c"  <src>  <dst>
                if (parts.length >= 3 && "c".equals(parts[0])) {
                    currentObfClass = parts[1];
                    if (!parts[2].isEmpty() && !parts[2].equals(parts[1])) {
                        out.mapClass(parts[1], parts[2]);
                    }
                    continue;
                }

                // Member: leading-empty + f/m + desc + src + dst
                if (parts.length >= 5 && parts[0].isEmpty() && currentObfClass != null) {
                    String kind = parts[1];
                    String desc = parts[2];
                    String src = parts[3];
                    String dst = parts[4];
                    if (dst.isEmpty() || dst.equals(src)) continue;
                    if ("f".equals(kind)) {
                        out.mapField(currentObfClass, src, desc, dst);
                    } else if ("m".equals(kind)) {
                        out.mapMethod(currentObfClass, src, desc, dst);
                    }
                    // other kinds (c = inner, p = param, v = local) — ignored for now
                }
            }
        }
        return out.build();
    }
}
