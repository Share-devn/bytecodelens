package dev.share.bytecodelens.detector.detectors;

import dev.share.bytecodelens.detector.DetectorContext;
import dev.share.bytecodelens.detector.IDetector;
import dev.share.bytecodelens.detector.ObfuscatorSignature;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

public final class BinscureDetector implements IDetector {

    @Override
    public String name() {
        return "Binscure";
    }

    @Override
    public ObfuscatorSignature.Family family() {
        return ObfuscatorSignature.Family.ANTI_ANALYSIS;
    }

    @Override
    public ObfuscatorSignature detect(DetectorContext ctx) {
        List<ObfuscatorSignature.Evidence> evidence = new ArrayList<>();
        double score = 0;

        long binscureMarkers = ctx.classInternalNames.stream()
                .filter(n -> n.contains("binscure") || n.contains("Binscure"))
                .count();
        if (binscureMarkers > 0) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    "Found Binscure-marked class(es)", 0.95));
            score += 0.55;
        }

        // Binscure's hallmark: crasher methods with broken stackmaps are placed as first method
        int crasherClasses = 0;
        for (ClassNode node : ctx.classNodes()) {
            if (node.methods == null || node.methods.isEmpty()) continue;
            MethodNode first = node.methods.get(0);
            if (first.instructions == null) continue;
            // Heuristic: first method is static, name is obfuscated, has unreachable code pattern
            if (first.name.length() <= 2 && first.instructions.size() > 100
                    && first.tryCatchBlocks != null && first.tryCatchBlocks.size() > 3) {
                crasherClasses++;
            }
        }
        if (crasherClasses > ctx.classCount * 0.3 && ctx.classCount > 20) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    crasherClasses + " classes match Binscure crasher pattern", 0.7));
            score += 0.35;
        }

        return new ObfuscatorSignature(name(), family(), Math.min(1.0, score), evidence);
    }
}
