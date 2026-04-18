package dev.share.bytecodelens.search;

public record SearchResult(
        TargetKind targetKind,
        String targetPath,
        String targetLabel,
        int lineNumber,
        String lineText,
        int matchStart,
        int matchEnd,
        String context
) {
    public enum TargetKind {
        CLASS_STRING,
        CLASS_NAME,
        CLASS_METHOD,
        CLASS_FIELD,
        CLASS_BYTECODE,
        RESOURCE_TEXT,
        /**
         * A user-authored comment matched by text. {@code targetPath} carries the owning
         * class FQN; {@code context} reveals what kind of member it's attached to
         * ({@code "class"} / {@code "method"} / {@code "field"}) plus the member name.
         */
        COMMENT
    }
}
