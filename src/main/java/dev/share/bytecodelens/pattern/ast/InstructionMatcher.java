package dev.share.bytecodelens.pattern.ast;

public sealed interface InstructionMatcher {

    record Ldc(MatchSpec value) implements InstructionMatcher {
    }

    record Invoke(MatchSpec owner, MatchSpec name, MatchSpec desc) implements InstructionMatcher {
    }

    record FieldAccess(FieldOp op, MatchSpec owner, MatchSpec name) implements InstructionMatcher {
    }

    record NewInstance(MatchSpec owner) implements InstructionMatcher {
    }

    record Opcode(String name) implements InstructionMatcher {
    }

    enum FieldOp { GETFIELD, PUTFIELD, GETSTATIC, PUTSTATIC }
}
