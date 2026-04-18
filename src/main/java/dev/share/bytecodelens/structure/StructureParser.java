package dev.share.bytecodelens.structure;

/**
 * Format-specific parsers produce a {@link StructureNode} tree from raw bytes.
 * Implementations must be side-effect-free and throw {@link UnsupportedFormatException}
 * when the bytes don't match their expected format (the dispatcher then moves on).
 */
public interface StructureParser {

    /** Short display name — shown in the structure panel header. */
    String formatName();

    /** Cheap magic-number check. Return true only if {@link #parse} is worth calling. */
    boolean matches(byte[] bytes);

    /** Parse. Implementations may throw on malformed-but-matching input. */
    StructureNode parse(byte[] bytes) throws UnsupportedFormatException;

    class UnsupportedFormatException extends Exception {
        public UnsupportedFormatException(String msg) { super(msg); }
    }
}
