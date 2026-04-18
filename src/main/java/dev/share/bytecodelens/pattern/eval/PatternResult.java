package dev.share.bytecodelens.pattern.eval;

public record PatternResult(
        Kind kind,
        String classFqn,
        String memberName,
        String memberDesc,
        String reason
) {
    public enum Kind { CLASS, METHOD, FIELD }

    public String label() {
        return switch (kind) {
            case CLASS -> classFqn;
            case METHOD -> classFqn + "#" + memberName + memberDesc;
            case FIELD -> classFqn + "." + memberName + " : " + memberDesc;
        };
    }
}
