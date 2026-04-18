package dev.share.bytecodelens.detector;

import java.util.List;

public record ObfuscatorSignature(
        String name,
        Family family,
        double confidence,
        List<Evidence> evidence
) {

    public enum Family {
        NAME_STRIPPER,
        STRING_ENCRYPTION,
        FLOW_OBFUSCATION,
        NATIVE,
        ANTI_ANALYSIS,
        OTHER
    }

    public record Evidence(String description, double weight) {
    }

    public Level level() {
        if (confidence >= 0.7) return Level.HIGH;
        if (confidence >= 0.3) return Level.MEDIUM;
        if (confidence > 0) return Level.LOW;
        return Level.NONE;
    }

    public enum Level { HIGH, MEDIUM, LOW, NONE }
}
