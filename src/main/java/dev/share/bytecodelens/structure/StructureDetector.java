package dev.share.bytecodelens.structure;

import dev.share.bytecodelens.structure.parsers.ClassFileParser;
import dev.share.bytecodelens.structure.parsers.PngParser;
import dev.share.bytecodelens.structure.parsers.ZipParser;

import java.util.List;

/**
 * Tries every registered parser in order and returns the first structure tree that
 * matches. Order matters: more specific formats (e.g. .class) before generic ones
 * (e.g. ZIP — would happily parse a jar's outer container, but a .class starts with
 * CAFEBABE which ZipParser wouldn't recognise anyway).
 */
public final class StructureDetector {

    private static final List<StructureParser> PARSERS = List.of(
            new ClassFileParser(),
            new ZipParser(),
            new PngParser());

    public static StructureNode detect(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        for (StructureParser p : PARSERS) {
            if (!p.matches(bytes)) continue;
            try {
                return p.parse(bytes);
            } catch (StructureParser.UnsupportedFormatException ex) {
                // Matching magic but malformed body — try next parser, then fall through.
            } catch (Throwable ex) {
                // Any parser bug mustn't crash the UI. Log-less for now.
            }
        }
        return null;
    }

    public static String detectFormatName(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        for (StructureParser p : PARSERS) {
            if (p.matches(bytes)) return p.formatName();
        }
        return null;
    }

    private StructureDetector() {}
}
