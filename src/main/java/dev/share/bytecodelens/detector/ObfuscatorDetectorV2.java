package dev.share.bytecodelens.detector;

import dev.share.bytecodelens.detector.detectors.AllatoriDetector;
import dev.share.bytecodelens.detector.detectors.BinscureDetector;
import dev.share.bytecodelens.detector.detectors.CaesiumDetector;
import dev.share.bytecodelens.detector.detectors.DashODetector;
import dev.share.bytecodelens.detector.detectors.NativeObfuscatorDetector;
import dev.share.bytecodelens.detector.detectors.ParamorphismDetector;
import dev.share.bytecodelens.detector.detectors.ProGuardR8Detector;
import dev.share.bytecodelens.detector.detectors.QprotectDetector;
import dev.share.bytecodelens.detector.detectors.SkidfuscatorDetector;
import dev.share.bytecodelens.detector.detectors.StringerDetector;
import dev.share.bytecodelens.detector.detectors.YGuardDetector;
import dev.share.bytecodelens.detector.detectors.ZkmDetector;
import dev.share.bytecodelens.model.LoadedJar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class ObfuscatorDetectorV2 {

    private static final Logger log = LoggerFactory.getLogger(ObfuscatorDetectorV2.class);

    private final List<IDetector> detectors = List.of(
            new ProGuardR8Detector(),
            new ZkmDetector(),
            new AllatoriDetector(),
            new NativeObfuscatorDetector(),
            new StringerDetector(),
            new SkidfuscatorDetector(),
            new ParamorphismDetector(),
            new BinscureDetector(),
            new CaesiumDetector(),
            new QprotectDetector(),
            new DashODetector(),
            new YGuardDetector()
    );

    public DetectionReport analyze(LoadedJar jar) {
        long start = System.currentTimeMillis();
        DetectorContext ctx = new DetectorContext(jar);
        ctx.build();

        List<ObfuscatorSignature> detections = new ArrayList<>();
        List<String> notDetected = new ArrayList<>();
        for (IDetector det : detectors) {
            try {
                ObfuscatorSignature sig = det.detect(ctx);
                if (sig.level() == ObfuscatorSignature.Level.NONE) {
                    notDetected.add(det.name());
                } else {
                    detections.add(sig);
                }
            } catch (Exception ex) {
                log.warn("Detector {} failed: {}", det.name(), ex.getMessage());
                notDetected.add(det.name());
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        return new DetectionReport(detections, notDetected,
                ctx.classCount, jar.resourceCount(), elapsed);
    }
}
