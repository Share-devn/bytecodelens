package dev.share.bytecodelens.detector.detectors;

import dev.share.bytecodelens.detector.DetectorContext;
import dev.share.bytecodelens.detector.IDetector;
import dev.share.bytecodelens.detector.ObfuscatorSignature;

import java.util.ArrayList;
import java.util.List;

public final class DashODetector implements IDetector {

    @Override
    public String name() {
        return "DashO";
    }

    @Override
    public ObfuscatorSignature.Family family() {
        return ObfuscatorSignature.Family.NAME_STRIPPER;
    }

    @Override
    public ObfuscatorSignature detect(DetectorContext ctx) {
        List<ObfuscatorSignature.Evidence> evidence = new ArrayList<>();
        double score = 0;

        if (ctx.hasResourceMatching("dasho.txt")
                || ctx.hasResourceMatching("DashO")
                || ctx.hasResourceMatching("META-INF/DASHO")) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    "Found DashO artifacts in resources", 0.9));
            score += 0.7;
        }

        long dashMarkers = ctx.ldcStrings.stream()
                .filter(s -> s.contains("DashO") || s.contains("PreEmptive")).count();
        if (dashMarkers > 0) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    "LDC strings reference DashO / PreEmptive", 0.8));
            score += 0.3;
        }

        return new ObfuscatorSignature(name(), family(), Math.min(1.0, score), evidence);
    }
}
