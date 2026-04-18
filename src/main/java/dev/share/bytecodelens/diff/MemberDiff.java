package dev.share.bytecodelens.diff;

public record MemberDiff(
        Kind kind,
        ChangeType change,
        String name,
        String descriptor,
        int accessA,
        int accessB,
        String detail
) {
    public enum Kind { METHOD, FIELD }

    public String label() {
        return name + descriptor;
    }
}
