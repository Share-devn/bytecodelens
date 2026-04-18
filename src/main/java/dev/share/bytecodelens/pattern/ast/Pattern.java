package dev.share.bytecodelens.pattern.ast;

import java.util.List;

public sealed interface Pattern permits ClassPattern, MethodPattern, FieldPattern {

    List<Predicate> predicates();

    enum Target { CLASS, METHOD, FIELD }

    Target target();
}
