package dev.share.bytecodelens.pattern.ast;

public record FieldCountPredicate(CountOp op, int value) implements Predicate {
}
