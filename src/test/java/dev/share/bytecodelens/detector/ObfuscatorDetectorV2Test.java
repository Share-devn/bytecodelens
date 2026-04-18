package dev.share.bytecodelens.detector;

import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.LoadedJar;
import dev.share.bytecodelens.service.ClassAnalyzer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObfuscatorDetectorV2Test {

    private final ClassAnalyzer analyzer = new ClassAnalyzer();
    private final ObfuscatorDetectorV2 v2 = new ObfuscatorDetectorV2();

    @Test
    void detectsProGuardStyleShortNames() {
        List<ClassEntry> classes = new ArrayList<>();
        for (char c = 'a'; c <= 'z'; c++) classes.add(mkClass("com/x/" + c));
        LoadedJar jar = new LoadedJar(Path.of("t.jar"), classes, List.of(), List.of(), 0, 0);

        DetectionReport report = v2.analyze(jar);
        assertTrue(report.detections().stream().anyMatch(s -> s.name().contains("ProGuard")));
    }

    @Test
    void detectsZkmStyleConfusableNames() {
        List<ClassEntry> classes = new ArrayList<>();
        String[] names = {"IllII", "lIIlI", "IIllI", "lIlIl", "IllIl", "lIIIl"};
        for (String n : names) classes.add(mkClass("a/" + n));
        LoadedJar jar = new LoadedJar(Path.of("t.jar"), classes, List.of(), List.of(), 0, 0);

        DetectionReport report = v2.analyze(jar);
        assertTrue(report.detections().stream().anyMatch(s -> s.name().contains("ZKM")));
    }

    @Test
    void reportsNoneForCleanClasses() {
        List<ClassEntry> classes = List.of(
                mkClass("com/app/UserService"),
                mkClass("com/app/OrderController"),
                mkClass("com/app/ProductRepository"));
        LoadedJar jar = new LoadedJar(Path.of("t.jar"), classes, List.of(), List.of(), 0, 0);

        DetectionReport report = v2.analyze(jar);
        assertFalse(report.notDetected().isEmpty());
        // At least ProGuard and ZKM should NOT be detected
        boolean proguardDetected = report.detections().stream()
                .anyMatch(s -> s.name().contains("ProGuard") && s.confidence() > 0.3);
        assertFalse(proguardDetected);
    }

    private ClassEntry mkClass(String internal) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
        cw.visitEnd();
        return analyzer.analyze(cw.toByteArray());
    }
}
