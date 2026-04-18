package dev.share.bytecodelens.detector;

public interface IDetector {

    /** Display name — e.g. "ProGuard / R8", "ZKM". */
    String name();

    ObfuscatorSignature.Family family();

    /** Run detection against pre-computed context; return signature. */
    ObfuscatorSignature detect(DetectorContext ctx);
}
