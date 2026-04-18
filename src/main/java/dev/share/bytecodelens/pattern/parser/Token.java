package dev.share.bytecodelens.pattern.parser;

public record Token(Kind kind, String text, int line, int col) {

    public enum Kind {
        IDENT,
        STRING,
        REGEX,
        NUMBER,
        LBRACE,
        RBRACE,
        LPAREN,
        RPAREN,
        DOT,
        STAR,
        PIPE,
        BANG,
        TILDE,
        EQ,
        LT,
        GT,
        LE,
        GE,
        NE,
        EOF
    }
}
