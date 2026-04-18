package dev.share.bytecodelens.detector.detectors;

import dev.share.bytecodelens.detector.DetectorContext;
import dev.share.bytecodelens.detector.IDetector;
import dev.share.bytecodelens.detector.ObfuscatorSignature;

import java.util.ArrayList;
import java.util.List;

public final class AllatoriDetector implements IDetector {

    @Override
    public String name() {
        return "Allatori";
    }

    @Override
    public ObfuscatorSignature.Family family() {
        return ObfuscatorSignature.Family.NAME_STRIPPER;
    }

    @Override
    public ObfuscatorSignature detect(DetectorContext ctx) {
        List<ObfuscatorSignature.Evidence> evidence = new ArrayList<>();
        double score = 0;

        double ratio = ctx.ratio(ctx.allatoriStyleClasses);
        if (ratio > 0.1) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    String.format("%.0f%% of classes have long I/l/1/O/0 confusable names", ratio * 100),
                    ratio));
            score += Math.min(0.6, ratio * 1.5);
        }

        // Allatori often leaves its watermark in META-INF
        if (ctx.hasResourceMatching("ALLATORI") || ctx.hasResourceMatching("allatori")) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    "Found 'allatori' reference in resources", 0.95));
            score += 0.4;
        }

        double unicodeRatio = ctx.ratio(ctx.unicodeNamedClasses);
        if (unicodeRatio > 0.15 && unicodeRatio < 0.9) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    String.format("%.0f%% of classes have non-ASCII chars in names", unicodeRatio * 100),
                    unicodeRatio));
            score += 0.2;
        }
        return new ObfuscatorSignature(name(), family(), Math.min(1.0, score), evidence);
    }
}
