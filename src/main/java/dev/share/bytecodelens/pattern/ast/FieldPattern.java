package dev.share.bytecodelens.pattern.ast;

import java.util.List;

public record FieldPattern(List<Predicate> predicates) implements Pattern {
    @Override
    public Target target() {
        return Target.FIELD;
    }
}
