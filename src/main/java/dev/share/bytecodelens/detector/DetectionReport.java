package dev.share.bytecodelens.detector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public record DetectionReport(
        List<ObfuscatorSignature> detections,
        List<String> notDetected,
        int classCount,
        int resourceCount,
        long durationMs
) {

    public List<ObfuscatorSignature> high() {
        return detections.stream()
                .filter(s -> s.level() == ObfuscatorSignature.Level.HIGH)
                .sorted(Comparator.comparingDouble(ObfuscatorSignature::confidence).reversed())
                .toList();
    }

    public List<ObfuscatorSignature> medium() {
        return detections.stream()
                .filter(s -> s.level() == ObfuscatorSignature.Level.MEDIUM)
                .sorted(Comparator.comparingDouble(ObfuscatorSignature::confidence).reversed())
                .toList();
    }

    public List<ObfuscatorSignature> low() {
        return detections.stream()
                .filter(s -> s.level() == ObfuscatorSignature.Level.LOW)
                .sorted(Comparator.comparingDouble(ObfuscatorSignature::confidence).reversed())
                .toList();
    }

    public ObfuscatorSignature top() {
        return detections.stream()
                .filter(s -> s.level() != ObfuscatorSignature.Level.NONE)
                .max(Comparator.comparingDouble(ObfuscatorSignature::confidence))
                .orElse(null);
    }

    public String asText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Obfuscator Detection Report\n");
        sb.append("Analyzed ").append(classCount).append(" classes, ")
                .append(resourceCount).append(" resources in ").append(durationMs).append("ms\n\n");

        List<ObfuscatorSignature> h = high(), m = medium(), l = low();
        if (!h.isEmpty()) {
            sb.append("=== HIGH confidence (> 70%) ===\n");
            for (var s : h) appendSig(sb, s);
        }
        if (!m.isEmpty()) {
            sb.append("\n=== MEDIUM confidence (30-70%) ===\n");
            for (var s : m) appendSig(sb, s);
        }
        if (!l.isEmpty()) {
            sb.append("\n=== LOW confidence (< 30%) ===\n");
            for (var s : l) appendSig(sb, s);
        }
        if (!notDetected.isEmpty()) {
            sb.append("\n=== NOT DETECTED ===\n");
            sb.append(String.join(", ", notDetected)).append("\n");
        }
        return sb.toString();
    }

    private static void appendSig(StringBuilder sb, ObfuscatorSignature s) {
        sb.append(String.format("\n  %s  %.0f%%\n", s.name(), s.confidence() * 100));
        for (var ev : s.evidence()) {
            sb.append("    - ").append(ev.description()).append('\n');
        }
    }
}
