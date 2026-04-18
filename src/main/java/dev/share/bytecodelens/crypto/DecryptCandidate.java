package dev.share.bytecodelens.crypto;

public record DecryptCandidate(
        String ownerInternal,
        String methodName,
        String methodDesc,
        byte[] classBytes,
        int instructionCount,
        int score
) {
    public String qualifiedName() {
        return ownerInternal + "." + methodName + methodDesc;
    }
}
