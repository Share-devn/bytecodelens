package dev.share.bytecodelens.detector.detectors;

import dev.share.bytecodelens.detector.DetectorContext;
import dev.share.bytecodelens.detector.IDetector;
import dev.share.bytecodelens.detector.ObfuscatorSignature;
import dev.share.bytecodelens.model.JarResource;

import java.util.ArrayList;
import java.util.List;

public final class NativeObfuscatorDetector implements IDetector {

    @Override
    public String name() {
        return "native-obfuscator (radioegor146)";
    }

    @Override
    public ObfuscatorSignature.Family family() {
        return ObfuscatorSignature.Family.NATIVE;
    }

    @Override
    public ObfuscatorSignature detect(DetectorContext ctx) {
        List<ObfuscatorSignature.Evidence> evidence = new ArrayList<>();
        double score = 0;
        boolean hasStrongSignature = false;

        List<JarResource> dlls = ctx.resourcesByKind(JarResource.ResourceKind.NATIVE_DLL);
        List<JarResource> sos = ctx.resourcesByKind(JarResource.ResourceKind.NATIVE_SO);
        List<JarResource> dylibs = ctx.resourcesByKind(JarResource.ResourceKind.NATIVE_DYLIB);
        int nativeCount = dlls.size() + sos.size() + dylibs.size();

        // Soft signal: bundled native libraries. Legitimate JNI apps (Kotlin/Native, LWJGL,
        // JNA, OpenCV, ...) also ship many .dll/.so/.dylib, so this alone must not push
        // the verdict past MEDIUM — we gate HIGH on a hard signature below.
        if (nativeCount > 0) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    "Found " + nativeCount + " native libraries bundled (" + dlls.size()
                            + " .dll, " + sos.size() + " .so, " + dylibs.size() + " .dylib)",
                    Math.min(1.0, nativeCount / 4.0)));
            score += Math.min(0.25, nativeCount / 12.0);
        }

        // Hard signature: the classic radioegor146 layout — a 'natives/' directory
        // containing native0.so / native0.dll files, one per target class.
        if (ctx.hasResourceMatching("natives/") || ctx.hasResourceMatching("native0.so")
                || ctx.hasResourceMatching("native0.dll")) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    "Found 'natives/' directory with native0.* libs (radioegor146 pattern)", 0.95));
            score += 0.5;
            hasStrongSignature = true;
        }

        // Soft signal: many classes with native methods. Same false-positive risk as
        // bundled libs — capped low unless combined with a strong signature.
        if (ctx.classesWithNativeMethods > 0) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    ctx.classesWithNativeMethods + " classes declare native methods",
                    Math.min(1.0, ctx.classesWithNativeMethods / 10.0)));
            score += Math.min(0.15, ctx.classesWithNativeMethods / 60.0);
        }

        // Hard signature: LDC references the library name directly.
        if (ctx.ldcStrings.stream().anyMatch(s -> s.contains("native-obfuscator")
                || s.contains("native_obfuscator") || s.startsWith("native0"))) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    "LDC strings reference native-obfuscator library names", 0.85));
            score += 0.35;
            hasStrongSignature = true;
        }

        // Negative signal — Kotlin/Native and Kotlin compiler jars ship native
        // backends for legitimate reasons; don't flag the whole Kotlin stack.
        long kotlinClasses = ctx.classInternalNames.stream()
                .filter(n -> n.startsWith("kotlin/") || n.startsWith("org/jetbrains/kotlin/"))
                .count();
        if (kotlinClasses > 100 && !hasStrongSignature) {
            evidence.add(new ObfuscatorSignature.Evidence(
                    "Likely legitimate JNI — jar contains " + kotlinClasses
                            + " Kotlin/Kotlin-compiler classes and no radioegor146 signature",
                    -0.6));
            score -= 0.6;
        }

        // Without at least one hard signature, cap the verdict to MEDIUM so we don't
        // claim HIGH confidence purely from the presence of JNI.
        if (!hasStrongSignature) {
            score = Math.min(score, 0.4);
        }

        return new ObfuscatorSignature(name(), family(), Math.max(0.0, Math.min(1.0, score)), evidence);
    }
}
