package dev.share.bytecodelens.pattern.ast;

public record InstructionCountPredicate(CountOp op, int value) implements Predicate {
}
