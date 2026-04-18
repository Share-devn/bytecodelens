package dev.share.bytecodelens.crypto;

public record DecryptedString(
        String inClassFqn,
        String inMethodName,
        String inMethodDesc,
        String encrypted,
        String decrypted,
        String decryptOwner,
        String decryptName,
        String decryptDesc,
        Mode mode,
        int lineNumber
) {
    public enum Mode { SIMULATION, REFLECTION }
}
