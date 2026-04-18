package dev.share.bytecodelens.detector.detectors;

import dev.share.bytecodelens.detector.DetectorContext;
import dev.share.bytecodelens.detector.IDetector;
import dev.share.bytecodelens.detector.ObfuscatorSignature;

import java.util.ArrayList;
import java.util.List;

public final class YGuardDetector implements IDetector {

    @Override
    public String name() {
        return "yGuard";
    }

    @Override
    public ObfuscatorSignature.Family family() {
        return ObfuscatorSignature.Family.NAME_STRIPPER;
    }

    @Override
    public ObfuscatorSignature detect(DetectorContext ctx) {
        List<ObfuscatorSignature.Evidence> evidence = new ArrayList<>();
        double score = 0;

        if (ctx.hasResourceMatching("yguard") || ctx.hasResourceMatching("yGuard")) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    "Found yGuard reference in resources", 0.9));
            score += 0.7;
        }
        long markers = ctx.ldcStrings.stream()
                .filter(s -> s.toLowerCase().contains("yguard")).count();
        if (markers > 0) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    "LDC strings reference yGuard", 0.85));
            score += 0.3;
        }
        return new ObfuscatorSignature(name(), family(), Math.min(1.0, score), evidence);
    }
}
