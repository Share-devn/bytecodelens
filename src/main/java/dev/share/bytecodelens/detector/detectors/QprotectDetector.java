package dev.share.bytecodelens.detector.detectors;

import dev.share.bytecodelens.detector.DetectorContext;
import dev.share.bytecodelens.detector.IDetector;
import dev.share.bytecodelens.detector.ObfuscatorSignature;

import java.util.ArrayList;
import java.util.List;

public final class QprotectDetector implements IDetector {

    @Override
    public String name() {
        return "QProtect";
    }

    @Override
    public ObfuscatorSignature.Family family() {
        return ObfuscatorSignature.Family.OTHER;
    }

    @Override
    public ObfuscatorSignature detect(DetectorContext ctx) {
        List<ObfuscatorSignature.Evidence> evidence = new ArrayList<>();
        double score = 0;

        long markers = ctx.classInternalNames.stream()
                .filter(n -> n.contains("QProtect") || n.contains("qprotect"))
                .count();
        if (markers > 0) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    markers + " QProtect-marked class(es)", 0.95));
            score += 0.7;
        }

        long bootstraps = ctx.invokeDynamicBootstrapOwners.stream()
                .filter(n -> n.toLowerCase().contains("qprotect")).count();
        if (bootstraps > 0) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    bootstraps + " InvokeDynamic bootstrap(s) owned by QProtect", 0.95));
            score += 0.35;
        }

        return new ObfuscatorSignature(name(), family(), Math.min(1.0, score), evidence);
    }
}
