package dev.share.bytecodelens.pattern.ast;

import java.util.List;

public record ClassPattern(List<Predicate> predicates) implements Pattern {
    @Override
    public Target target() {
        return Target.CLASS;
    }
}
