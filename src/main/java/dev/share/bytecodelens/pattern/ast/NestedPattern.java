package dev.share.bytecodelens.pattern.ast;

public record NestedPattern(Quantifier quantifier, Pattern inner) implements Predicate {
}
