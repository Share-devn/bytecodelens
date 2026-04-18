package dev.share.bytecodelens.detector.detectors;

import dev.share.bytecodelens.detector.DetectorContext;
import dev.share.bytecodelens.detector.IDetector;
import dev.share.bytecodelens.detector.ObfuscatorSignature;

import java.util.ArrayList;
import java.util.List;

public final class ZkmDetector implements IDetector {

    @Override
    public String name() {
        return "ZKM (Zelix KlassMaster)";
    }

    @Override
    public ObfuscatorSignature.Family family() {
        return ObfuscatorSignature.Family.STRING_ENCRYPTION;
    }

    @Override
    public ObfuscatorSignature detect(DetectorContext ctx) {
        List<ObfuscatorSignature.Evidence> evidence = new ArrayList<>();
        double score = 0;

        double zkmRatio = ctx.ratio(ctx.zkmStyleClasses);
        if (zkmRatio > 0.15) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    String.format("%.0f%% of classes use I/l/1 confusable-char names", zkmRatio * 100),
                    zkmRatio));
            score += Math.min(0.55, zkmRatio * 1.3);
        }

        int decryptors = ctx.staticStringDecryptCandidates;
        if (decryptors >= 5) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    decryptors + " static String-decryptor methods matching ZKM shape",
                    Math.min(1.0, decryptors / 30.0)));
            score += Math.min(0.35, decryptors / 80.0);
        }

        int hugeSwitches = ctx.tableSwitchHeavyMethods;
        if (hugeSwitches > 0) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    hugeSwitches + " method(s) with tableswitch > 50 cases (control-flow flattening)",
                    Math.min(1.0, hugeSwitches / 10.0)));
            score += Math.min(0.25, hugeSwitches / 40.0);
        }

        if (ctx.avgStringEntropy > 5.5 && ctx.totalStringLiterals > 50) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    String.format("High average string entropy %.1f bits/char", ctx.avgStringEntropy),
                    0.5));
            score += 0.15;
        }

        return new ObfuscatorSignature(name(), family(), Math.min(1.0, score), evidence);
    }
}
