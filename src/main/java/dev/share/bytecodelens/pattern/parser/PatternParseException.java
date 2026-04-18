package dev.share.bytecodelens.pattern.parser;

public final class PatternParseException extends RuntimeException {

    private final int line;
    private final int col;

    public PatternParseException(String message, int line, int col) {
        super("[line " + line + ", col " + col + "] " + message);
        this.line = line;
        this.col = col;
    }

    public int line() {
        return line;
    }

    public int col() {
        return col;
    }
}
