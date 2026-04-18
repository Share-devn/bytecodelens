package dev.share.bytecodelens.detector.detectors;

import dev.share.bytecodelens.detector.DetectorContext;
import dev.share.bytecodelens.detector.IDetector;
import dev.share.bytecodelens.detector.ObfuscatorSignature;

import java.util.ArrayList;
import java.util.List;

public final class StringerDetector implements IDetector {

    @Override
    public String name() {
        return "Stringer (Licel)";
    }

    @Override
    public ObfuscatorSignature.Family family() {
        return ObfuscatorSignature.Family.STRING_ENCRYPTION;
    }

    @Override
    public ObfuscatorSignature detect(DetectorContext ctx) {
        List<ObfuscatorSignature.Evidence> evidence = new ArrayList<>();
        double score = 0;

        long stringerHelpers = ctx.classInternalNames.stream()
                .filter(n -> n.contains("StringerHelper") || n.contains("StringerRT")
                        || n.contains("com/licel/stringer") || n.contains("stringer/"))
                .count();
        if (stringerHelpers > 0) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    "Found " + stringerHelpers + " Stringer helper class(es)", 0.95));
            score += 0.6;
        }

        long stringerBootstraps = ctx.invokeDynamicBootstrapOwners.stream()
                .filter(n -> n.contains("stringer") || n.contains("licel"))
                .count();
        if (stringerBootstraps > 0) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    stringerBootstraps + " InvokeDynamic bootstraps point to Stringer owner", 0.9));
            score += 0.4;
        }

        if (ctx.invokedynamicSites > 50 && ctx.encryptedStringLiterals > 20) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    ctx.invokedynamicSites + " invokedynamic sites + "
                            + ctx.encryptedStringLiterals + " encrypted-looking LDC strings", 0.6));
            score += 0.2;
        }

        return new ObfuscatorSignature(name(), family(), Math.min(1.0, score), evidence);
    }
}
