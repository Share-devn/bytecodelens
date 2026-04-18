package dev.share.bytecodelens.pattern.ast;

public enum CountOp {
    GT, LT, GE, LE, EQ, NE;

    public boolean apply(int actual, int value) {
        return switch (this) {
            case GT -> actual > value;
            case LT -> actual < value;
            case GE -> actual >= value;
            case LE -> actual <= value;
            case EQ -> actual == value;
            case NE -> actual != value;
        };
    }
}
