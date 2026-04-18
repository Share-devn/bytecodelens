package dev.share.bytecodelens.crypto;

import java.util.List;

public record DecryptionResult(
        List<DecryptedString> decrypted,
        List<DecryptCandidate> candidates,
        int callSitesFound,
        int simulationHits,
        int reflectionHits,
        int failures,
        long durationMs
) {
}
