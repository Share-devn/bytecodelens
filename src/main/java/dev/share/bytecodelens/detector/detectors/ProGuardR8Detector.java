package dev.share.bytecodelens.detector.detectors;

import dev.share.bytecodelens.detector.DetectorContext;
import dev.share.bytecodelens.detector.IDetector;
import dev.share.bytecodelens.detector.ObfuscatorSignature;

import java.util.ArrayList;
import java.util.List;

public final class ProGuardR8Detector implements IDetector {

    @Override
    public String name() {
        return "ProGuard / R8";
    }

    @Override
    public ObfuscatorSignature.Family family() {
        return ObfuscatorSignature.Family.NAME_STRIPPER;
    }

    @Override
    public ObfuscatorSignature detect(DetectorContext ctx) {
        List<ObfuscatorSignature.Evidence> evidence = new ArrayList<>();
        double ratio = ctx.ratio(ctx.shortNamedClasses);
        double score = 0;
        if (ratio > 0.25) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    String.format("%.0f%% of classes have 1-2 char names", ratio * 100), ratio));
            score += Math.min(0.6, ratio);
        }
        if (ctx.hasResourceMatching("META-INF/proguard")
                || ctx.hasResourceMatching("proguard.txt")
                || ctx.hasResourceMatching("proguard.cfg")) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    "Found ProGuard configuration in META-INF/", 0.9));
            score += 0.35;
        }
        if (ctx.hasResourceMatching("META-INF/android.arsc")
                || ctx.hasResourceMatching("classes.dex")) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    "Android-style resources suggest R8", 0.3));
            score += 0.1;
        }
        // "Mapping file" signature
        if (ctx.hasResourceMatching("mapping.txt") || ctx.hasResourceMatching("seeds.txt")) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    "ProGuard mapping/seeds file present", 0.7));
            score += 0.2;
        }
        return new ObfuscatorSignature(name(), family(), Math.min(1.0, score), evidence);
    }
}
