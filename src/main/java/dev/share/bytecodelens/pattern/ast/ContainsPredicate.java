package dev.share.bytecodelens.pattern.ast;

public record ContainsPredicate(InstructionMatcher matcher) implements Predicate {
}
