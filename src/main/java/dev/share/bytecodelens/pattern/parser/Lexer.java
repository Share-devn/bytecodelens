package dev.share.bytecodelens.pattern.parser;

import java.util.ArrayList;
import java.util.List;

public final class Lexer {

    private final String src;
    private int pos;
    private int line = 1;
    private int col = 1;

    public Lexer(String src) {
        this.src = src;
    }

    public List<Token> tokenize() {
        List<Token> out = new ArrayList<>();
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\n') {
                line++;
                col = 1;
                pos++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                pos++;
                col++;
                continue;
            }
            if (c == '/' && peek(1) == '/') {
                while (pos < src.length() && src.charAt(pos) != '\n') {
                    pos++;
                }
                continue;
            }
            int startLine = line;
            int startCol = col;
            switch (c) {
                case '{' -> { out.add(new Token(Token.Kind.LBRACE, "{", startLine, startCol)); advance(); }
                case '}' -> { out.add(new Token(Token.Kind.RBRACE, "}", startLine, startCol)); advance(); }
                case '(' -> { out.add(new Token(Token.Kind.LPAREN, "(", startLine, startCol)); advance(); }
                case ')' -> { out.add(new Token(Token.Kind.RPAREN, ")", startLine, startCol)); advance(); }
                case '.' -> { out.add(new Token(Token.Kind.DOT, ".", startLine, startCol)); advance(); }
                case '*' -> { out.add(new Token(Token.Kind.STAR, "*", startLine, startCol)); advance(); }
                case '|' -> { out.add(new Token(Token.Kind.PIPE, "|", startLine, startCol)); advance(); }
                case '~' -> { out.add(new Token(Token.Kind.TILDE, "~", startLine, startCol)); advance(); }
                case '!' -> {
                    if (peek(1) == '=') {
                        out.add(new Token(Token.Kind.NE, "!=", startLine, startCol));
                        advance();
                        advance();
                    } else {
                        out.add(new Token(Token.Kind.BANG, "!", startLine, startCol));
                        advance();
                    }
                }
                case '=' -> { out.add(new Token(Token.Kind.EQ, "=", startLine, startCol)); advance(); }
                case '<' -> {
                    if (peek(1) == '=') {
                        out.add(new Token(Token.Kind.LE, "<=", startLine, startCol));
                        advance();
                        advance();
                    } else {
                        out.add(new Token(Token.Kind.LT, "<", startLine, startCol));
                        advance();
                    }
                }
                case '>' -> {
                    if (peek(1) == '=') {
                        out.add(new Token(Token.Kind.GE, ">=", startLine, startCol));
                        advance();
                        advance();
                    } else {
                        out.add(new Token(Token.Kind.GT, ">", startLine, startCol));
                        advance();
                    }
                }
                case '"' -> out.add(readString(startLine, startCol));
                case '/' -> out.add(readRegex(startLine, startCol));
                default -> {
                    if (Character.isDigit(c)) {
                        out.add(readNumber(startLine, startCol));
                    } else if (isIdentStart(c)) {
                        out.add(readIdent(startLine, startCol));
                    } else {
                        throw new PatternParseException("Unexpected character '" + c + "'", line, col);
                    }
                }
            }
        }
        out.add(new Token(Token.Kind.EOF, "", line, col));
        return out;
    }

    private Token readString(int startLine, int startCol) {
        advance();
        StringBuilder sb = new StringBuilder();
        while (pos < src.length() && src.charAt(pos) != '"') {
            char c = src.charAt(pos);
            if (c == '\\' && pos + 1 < src.length()) {
                char next = src.charAt(pos + 1);
                sb.append(switch (next) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    case '\\' -> '\\';
                    case '"' -> '"';
                    default -> next;
                });
                advance();
                advance();
            } else {
                sb.append(c);
                advance();
            }
        }
        if (pos >= src.length()) {
            throw new PatternParseException("Unterminated string", startLine, startCol);
        }
        advance();
        return new Token(Token.Kind.STRING, sb.toString(), startLine, startCol);
    }

    private Token readRegex(int startLine, int startCol) {
        advance();
        StringBuilder sb = new StringBuilder();
        while (pos < src.length() && src.charAt(pos) != '/') {
            char c = src.charAt(pos);
            if (c == '\\' && pos + 1 < src.length()) {
                sb.append(c);
                advance();
                sb.append(src.charAt(pos));
                advance();
            } else if (c == '\n') {
                throw new PatternParseException("Unterminated regex", startLine, startCol);
            } else {
                sb.append(c);
                advance();
            }
        }
        if (pos >= src.length()) {
            throw new PatternParseException("Unterminated regex", startLine, startCol);
        }
        advance();
        StringBuilder flags = new StringBuilder();
        while (pos < src.length() && Character.isLetter(src.charAt(pos))) {
            flags.append(src.charAt(pos));
            advance();
        }
        String source = sb.toString();
        if (flags.length() > 0) source = "(?" + flags + ")" + source;
        return new Token(Token.Kind.REGEX, source, startLine, startCol);
    }

    private Token readNumber(int startLine, int startCol) {
        StringBuilder sb = new StringBuilder();
        while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
            sb.append(src.charAt(pos));
            advance();
        }
        return new Token(Token.Kind.NUMBER, sb.toString(), startLine, startCol);
    }

    private Token readIdent(int startLine, int startCol) {
        StringBuilder sb = new StringBuilder();
        while (pos < src.length() && isIdentPart(src.charAt(pos))) {
            sb.append(src.charAt(pos));
            advance();
        }
        return new Token(Token.Kind.IDENT, sb.toString(), startLine, startCol);
    }

    private void advance() {
        if (pos < src.length()) {
            if (src.charAt(pos) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
            pos++;
        }
    }

    private char peek(int offset) {
        int p = pos + offset;
        return p < src.length() ? src.charAt(p) : '\0';
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '<' || c == '$';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '<' || c == '>'
                || c == '/' || c == '-';
    }
}
