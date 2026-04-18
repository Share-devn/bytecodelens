package dev.share.bytecodelens.search;

public enum SearchMode {
    STRINGS,
    NAMES,
    BYTECODE,
    REGEX,
    NUMBERS,
    /**
     * Scans user-authored comments (class / method / field) stored in the workspace's
     * {@link dev.share.bytecodelens.comments.CommentStore}. A workspace with no comments
     * produces zero results — the mode is harmless to leave selected.
     */
    COMMENTS
}
