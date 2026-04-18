package dev.share.bytecodelens.ui.views;

public record HighlightRequest(Mode mode, String query, int line) {

    /**
     * Highlight matching mode.
     * <ul>
     *     <li>{@link #LITERAL} — substring match. Good for user-typed search ({@code Ctrl+F}).</li>
     *     <li>{@link #LITERAL_WORD} — substring + both sides must NOT be Java-identifier
     *         parts. Good for "jump to method/field" — prevents {@code main} matching inside
     *         {@code domain}/{@code remainder}/{@code mainActivity}.</li>
     *     <li>{@link #REGEX} — full regex. Case-insensitive by default.</li>
     *     <li>{@link #NONE} — no highlight; used to open a class without extra search.</li>
     * </ul>
     */
    public enum Mode { LITERAL, LITERAL_WORD, REGEX, NONE }

    public static HighlightRequest none() {
        return new HighlightRequest(Mode.NONE, "", -1);
    }

    public static HighlightRequest literal(String text, int line) {
        return new HighlightRequest(Mode.LITERAL, text, line);
    }

    /** Whole-word literal — callers that navigate to a member (not user search) want this. */
    public static HighlightRequest literalWord(String text, int line) {
        return new HighlightRequest(Mode.LITERAL_WORD, text, line);
    }

    public static HighlightRequest regex(String pattern, int line) {
        return new HighlightRequest(Mode.REGEX, pattern, line);
    }

    public boolean isEmpty() {
        return mode == Mode.NONE || query == null || query.isEmpty();
    }
}
