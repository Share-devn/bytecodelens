package dev.share.bytecodelens.detector.detectors;

import dev.share.bytecodelens.detector.DetectorContext;
import dev.share.bytecodelens.detector.IDetector;
import dev.share.bytecodelens.detector.ObfuscatorSignature;

import java.util.ArrayList;
import java.util.List;

public final class CaesiumDetector implements IDetector {

    @Override
    public String name() {
        return "Caesium";
    }

    @Override
    public ObfuscatorSignature.Family family() {
        return ObfuscatorSignature.Family.FLOW_OBFUSCATION;
    }

    @Override
    public ObfuscatorSignature detect(DetectorContext ctx) {
        List<ObfuscatorSignature.Evidence> evidence = new ArrayList<>();
        double score = 0;

        long markers = ctx.classInternalNames.stream()
                .filter(n -> n.contains("caesium") || n.contains("Caesium"))
                .count();
        if (markers > 0) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    "Found " + markers + " Caesium-marked class(es)", 0.95));
            score += 0.8;
        }
        long strings = ctx.ldcStrings.stream()
                .filter(s -> s.toLowerCase().contains("caesium")).count();
        if (strings > 0) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    "LDC strings mention Caesium", 0.8));
            score += 0.3;
        }
        return new ObfuscatorSignature(name(), family(), Math.min(1.0, score), evidence);
    }
}
