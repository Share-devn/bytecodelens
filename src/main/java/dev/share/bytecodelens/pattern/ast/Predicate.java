package dev.share.bytecodelens.pattern.ast;

public sealed interface Predicate permits
        NamePredicate, AccessPredicate, ExtendsPredicate, ImplementsPredicate,
        AnnotationPredicate, DescPredicate, InstructionCountPredicate,
        FieldCountPredicate, MethodCountPredicate,
        ContainsPredicate, NestedPattern, OrPredicate, NotPredicate {
}
