package dev.share.bytecodelens.service;

import dev.share.bytecodelens.detector.DetectionReport;
import dev.share.bytecodelens.detector.ObfuscatorDetectorV2;
import dev.share.bytecodelens.detector.ObfuscatorSignature;
import dev.share.bytecodelens.model.LoadedJar;

/**
 * Backward-compatible facade over {@link ObfuscatorDetectorV2}.
 * Existing callers (status bar, quick-detect menu) use the legacy single-result
 * Detection record. New callers should use {@link ObfuscatorDetectorV2} directly
 * to get the full multi-detector report.
 */
public final class ObfuscatorDetector {

    public record Detection(String name, double confidence, String reason) {
    }

    private final ObfuscatorDetectorV2 v2 = new ObfuscatorDetectorV2();

    public Detection detect(LoadedJar jar) {
        if (jar == null || jar.classes().isEmpty()) {
            return new Detection("None", 0.0, "No classes loaded");
        }
        DetectionReport report = v2.analyze(jar);
        ObfuscatorSignature top = report.top();
        if (top == null) {
            return new Detection("None / Unknown", 0.0, "No strong obfuscation patterns detected");
        }
        String reason = top.evidence().isEmpty()
                ? "Detected by heuristics"
                : top.evidence().get(0).description();
        return new Detection(top.name(), top.confidence(), reason);
    }

    public DetectionReport fullReport(LoadedJar jar) {
        if (jar == null) return new DetectionReport(java.util.List.of(), java.util.List.of(), 0, 0, 0);
        return v2.analyze(jar);
    }
}
