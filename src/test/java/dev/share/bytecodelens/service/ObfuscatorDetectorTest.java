package dev.share.bytecodelens.service;

import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.LoadedJar;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObfuscatorDetectorTest {

    @Test
    void detectsProGuardShortNames() {
        List<ClassEntry> classes = new ArrayList<>();
        for (char c = 'a'; c <= 'z'; c++) {
            classes.add(fakeClass("com/app/" + c));
        }
        LoadedJar jar = new LoadedJar(Path.of("fake.jar"), classes, java.util.List.of(), java.util.List.of(), 0, 0);

        var result = new ObfuscatorDetector().detect(jar);
        assertTrue(result.name().toLowerCase().contains("proguard")
                || result.name().toLowerCase().contains("r8"));
        assertTrue(result.confidence() > 0.5);
    }

    @Test
    void reportsNoneForNormalClasses() {
        List<ClassEntry> classes = List.of(
                fakeClass("com/app/MyService"),
                fakeClass("com/app/UserController"),
                fakeClass("com/app/DataRepository"));
        LoadedJar jar = new LoadedJar(Path.of("fake.jar"), classes, java.util.List.of(), java.util.List.of(), 0, 0);

        var result = new ObfuscatorDetector().detect(jar);
        assertEquals(0.0, result.confidence(), 0.001);
    }

    private static ClassEntry fakeClass(String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        return new ClassAnalyzer().analyze(cw.toByteArray());
    }
}
