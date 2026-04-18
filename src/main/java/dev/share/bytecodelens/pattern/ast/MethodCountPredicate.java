package dev.share.bytecodelens.pattern.ast;

public record MethodCountPredicate(CountOp op, int value) implements Predicate {
}
