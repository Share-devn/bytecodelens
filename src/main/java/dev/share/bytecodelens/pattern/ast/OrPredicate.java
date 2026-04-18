package dev.share.bytecodelens.pattern.ast;

import java.util.List;

public record OrPredicate(List<Predicate> alternatives) implements Predicate {
}
