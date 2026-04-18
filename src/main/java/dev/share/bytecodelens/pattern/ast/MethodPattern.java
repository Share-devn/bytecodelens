package dev.share.bytecodelens.pattern.ast;

import java.util.List;

public record MethodPattern(List<Predicate> predicates) implements Pattern {
    @Override
    public Target target() {
        return Target.METHOD;
    }
}
