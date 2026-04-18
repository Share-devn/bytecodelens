package dev.share.bytecodelens.mapping;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point for loading mapping files. Sniffs content to pick a parser rather than
 * relying on file extension — user might have {@code mapping.txt} that is actually TSRG,
 * or a {@code .mappings} file without newlines etc.
 */
public final class MappingLoader {

    public static MappingModel load(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        return loadString(content);
    }

    /** Detect format by first non-blank, non-comment line(s) and parse accordingly. */
    public static MappingModel loadString(String content) throws IOException {
        MappingFormat detected = detectFormat(content);
        StringReader r = new StringReader(content);
        return switch (detected) {
            case PROGUARD -> ProGuardMappingParser.parse(r);
            case TINY_V1 -> TinyV1MappingParser.parse(r);
            case TINY_V2 -> TinyV2MappingParser.parse(r);
            case SRG -> SrgMappingParser.parse(r);
            case XSRG -> XsrgMappingParser.parse(r);
            case TSRG -> TsrgMappingParser.parse(r);
            case TSRG_V2 -> TsrgV2MappingParser.parse(r);
            case CSRG -> CsrgMappingParser.parse(r);
            case JOBF -> JobfMappingParser.parse(r);
            case ENIGMA -> EnigmaMappingParser.parse(r);
            case RECAF -> RecafMappingParser.parse(r);
        };
    }

    static MappingFormat detectFormat(String content) throws IOException {
        try (BufferedReader br = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank() || line.trim().startsWith("#") || line.trim().startsWith("//")) continue;
                String trimmed = line.trim();

                // Tiny v2 header — unambiguous
                if (trimmed.startsWith("tiny\t2\t") || trimmed.startsWith("tiny 2 ")) return MappingFormat.TINY_V2;
                // Tiny v1 — header line "v1\tofficial\tnamed"
                if (trimmed.startsWith("v1\t")) return MappingFormat.TINY_V1;
                // TSRG v2 — explicit header
                if (trimmed.startsWith("tsrg2 ") || trimmed.startsWith("tsrg2\t")) return MappingFormat.TSRG_V2;

                // JOBF — directives prefixed with a dot
                if (trimmed.startsWith(".class_map") || trimmed.startsWith(".field_map")
                        || trimmed.startsWith(".method_map")) {
                    return MappingFormat.JOBF;
                }

                // SRG/XSRG — CL:/FD:/MD:/PK: prefix. XSRG distinguishes itself by FD: lines
                // having FIVE whitespace-separated parts (FD:, obfPath, obfDesc, namedPath, namedDesc)
                // rather than three. Sample more lines if needed.
                if (trimmed.startsWith("CL:") || trimmed.startsWith("FD:")
                        || trimmed.startsWith("MD:") || trimmed.startsWith("PK:")) {
                    return looksLikeXsrg(content) ? MappingFormat.XSRG : MappingFormat.SRG;
                }

                // Enigma — explicit CLASS keyword
                if (trimmed.startsWith("CLASS ")) return MappingFormat.ENIGMA;

                // ProGuard — " -> " + trailing colon on a class line
                if (trimmed.contains(" -> ") && trimmed.endsWith(":")) return MappingFormat.PROGUARD;

                // TSRG — two whitespace-separated internal-name tokens, no prefix, no colon
                if (!trimmed.contains("->") && !trimmed.contains(":")
                        && trimmed.matches("[\\w/$]+\\s+[\\w/$]+.*")) {
                    return MappingFormat.TSRG;
                }

                // CSRG — single line of 2/3/4 space-separated tokens with no descriptor on
                // first line (a.k.a. plain "obf named" class line, but no extra qualifier).
                // Falls through to unknown if previous heuristics didn't catch it.

                // Recaf simple — "owner.name(desc) newName" or "owner.name newName" or "owner newName"
                // We treat it as a fallback when we see a dot in the left token.
                if (trimmed.split("\\s+").length == 2 && trimmed.split("\\s+")[0].contains(".")) {
                    return MappingFormat.RECAF;
                }

                // First meaningful line doesn't match anything we know.
                throw new IOException("Cannot detect mapping format from line: " + line);
            }
        }
        throw new IOException("Mapping file is empty");
    }

    /** Heuristic: an XSRG file has at least one FD: line with 5+ tokens (extra desc column). */
    private static boolean looksLikeXsrg(String content) {
        for (String l : content.split("\\r?\\n")) {
            String t = l.trim();
            if (t.startsWith("FD:")) {
                String[] parts = t.split("\\s+");
                if (parts.length >= 5) return true;
            }
        }
        return false;
    }

    private MappingLoader() {}
}
