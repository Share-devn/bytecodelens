package dev.share.bytecodelens.detector.detectors;

import dev.share.bytecodelens.detector.DetectorContext;
import dev.share.bytecodelens.detector.IDetector;
import dev.share.bytecodelens.detector.ObfuscatorSignature;

import java.util.ArrayList;
import java.util.List;

public final class SkidfuscatorDetector implements IDetector {

    @Override
    public String name() {
        return "Skidfuscator";
    }

    @Override
    public ObfuscatorSignature.Family family() {
        return ObfuscatorSignature.Family.FLOW_OBFUSCATION;
    }

    @Override
    public ObfuscatorSignature detect(DetectorContext ctx) {
        List<ObfuscatorSignature.Evidence> evidence = new ArrayList<>();
        double score = 0;

        long skidMarkers = ctx.classInternalNames.stream()
                .filter(n -> n.contains("Skidfuscator") || n.contains("$SKID")
                        || n.matches(".*\\$skid\\$.*") || n.contains("skidfuscator/"))
                .count();
        if (skidMarkers > 0) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    "Found " + skidMarkers + " Skidfuscator-marked class(es)", 0.98));
            score += 0.7;
        }

        long skidStrings = ctx.ldcStrings.stream()
                .filter(s -> s.toLowerCase().contains("skidfuscator")
                        || s.contains("Skidded by"))
                .count();
        if (skidStrings > 0) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    "LDC strings reference Skidfuscator (" + skidStrings + " occurrences)", 0.98));
            score += 0.35;
        }

        // Skidfuscator uses a lot of predicate-based flow flattening
        if (ctx.tableSwitchHeavyMethods > 10 && ctx.invokedynamicSites > 30) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    "Heavy tableswitch + invokedynamic pattern (possible Skidfuscator flow)", 0.4));
            score += 0.15;
        }

        return new ObfuscatorSignature(name(), family(), Math.min(1.0, score), evidence);
    }
}
