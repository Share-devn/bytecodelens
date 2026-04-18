package dev.share.bytecodelens.detector.detectors;

import dev.share.bytecodelens.detector.DetectorContext;
import dev.share.bytecodelens.detector.IDetector;
import dev.share.bytecodelens.detector.ObfuscatorSignature;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

public final class ParamorphismDetector implements IDetector {

    @Override
    public String name() {
        return "Paramorphism";
    }

    @Override
    public ObfuscatorSignature.Family family() {
        return ObfuscatorSignature.Family.ANTI_ANALYSIS;
    }

    @Override
    public ObfuscatorSignature detect(DetectorContext ctx) {
        List<ObfuscatorSignature.Evidence> evidence = new ArrayList<>();
        double score = 0;

        int argNNames = 0;
        int scanned = 0;
        for (ClassNode node : ctx.classNodes()) {
            if (node.methods == null) continue;
            for (MethodNode m : node.methods) {
                if (m.localVariables == null) continue;
                for (var lv : m.localVariables) {
                    scanned++;
                    if (lv.name != null && lv.name.matches("arg\\d+")) argNNames++;
                }
            }
        }
        if (scanned > 50) {
            double ratio = argNNames / (double) scanned;
            if (ratio > 0.4) {
                evidence.add(new ObfuscatorSignature.Evidence(
                        String.format("%.0f%% of local variables renamed to argN pattern", ratio * 100), ratio));
                score += Math.min(0.7, ratio);
            }
        }

        long paramMarkers = ctx.classInternalNames.stream()
                .filter(n -> n.contains("paramorphism") || n.contains("Paramorphism"))
                .count();
        if (paramMarkers > 0) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    "Found Paramorphism-marked class(es)", 0.95));
            score += 0.4;
        }

        return new ObfuscatorSignature(name(), family(), Math.min(1.0, score), evidence);
    }
}
