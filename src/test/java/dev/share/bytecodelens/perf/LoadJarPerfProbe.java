package dev.share.bytecodelens.perf;

import dev.share.bytecodelens.detector.ObfuscatorDetectorV2;
import dev.share.bytecodelens.hierarchy.HierarchyIndex;
import dev.share.bytecodelens.model.LoadedJar;
import dev.share.bytecodelens.search.SearchIndex;
import dev.share.bytecodelens.service.JarLoader;
import dev.share.bytecodelens.service.ObfuscatorDetector;
import dev.share.bytecodelens.usage.UsageIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;

/**
 * Profiling harness for C2 (large-jar performance). Disabled by default.
 *
 * <p>Run with: {@code ./gradlew.bat test --tests LoadJarPerfProbe -Dperf=1 -Dperf.jar=PATH}
 * (optionally {@code -Dperf.runs=3}).</p>
 *
 * <p>Measures wall time for each stage that {@code MainController.onJarLoaded} triggers,
 * so we can see where a large jar actually spends time before the UI would be responsive.</p>
 */
@EnabledIfSystemProperty(named = "perf", matches = "1")
final class LoadJarPerfProbe {

    @Test
    void profileLoad() throws Exception {
        String jarPath = System.getProperty("perf.jar");
        if (jarPath == null) {
            throw new IllegalStateException("Pass -Dperf.jar=<path-to-jar>");
        }
        int runs = Integer.parseInt(System.getProperty("perf.runs", "1"));

        Path jar = Path.of(jarPath);
        System.out.println("== LoadJarPerfProbe ==");
        System.out.println("jar: " + jar);
        System.out.println("runs: " + runs);
        System.out.println();

        for (int i = 1; i <= runs; i++) {
            System.out.println("--- run " + i + " ---");
            long t0 = System.nanoTime();
            JarLoader loader = new JarLoader();
            LoadedJar loaded = loader.load(jar, p -> {});
            long tLoad = System.nanoTime();
            System.out.printf("load (parse+zip):         %6d ms   %d classes, %d versioned, %d resources, %.1f MB%n",
                    ms(t0, tLoad),
                    loaded.classCount(), loaded.versionedClassCount(), loaded.resourceCount(),
                    loaded.totalBytes() / 1024.0 / 1024.0);

            long t1 = System.nanoTime();
            SearchIndex si = new SearchIndex(loaded);
            si.build();
            long tSearch = System.nanoTime();
            System.out.printf("search index build:       %6d ms%n", ms(t1, tSearch));

            long t2 = System.nanoTime();
            UsageIndex ui = new UsageIndex(loaded);
            ui.build();
            long tUsage = System.nanoTime();
            System.out.printf("usage index build:        %6d ms%n", ms(t2, tUsage));

            long t3 = System.nanoTime();
            HierarchyIndex hi = new HierarchyIndex(loaded);
            hi.build();
            long tHier = System.nanoTime();
            System.out.printf("hierarchy index build:    %6d ms%n", ms(t3, tHier));

            long t4 = System.nanoTime();
            new ObfuscatorDetector().detect(loaded);
            long tDet1 = System.nanoTime();
            System.out.printf("obfuscator detector v1:   %6d ms%n", ms(t4, tDet1));

            long t5 = System.nanoTime();
            new ObfuscatorDetectorV2().analyze(loaded);
            long tDet2 = System.nanoTime();
            System.out.printf("obfuscator detector v2:   %6d ms%n", ms(t5, tDet2));

            long total = tDet2 - t0;
            System.out.printf("TOTAL (main-thread cost): %6d ms%n", total / 1_000_000);
            System.out.println();
        }
    }

    private static long ms(long from, long to) {
        return (to - from) / 1_000_000;
    }
}
