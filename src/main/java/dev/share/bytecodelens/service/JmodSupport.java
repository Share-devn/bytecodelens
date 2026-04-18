package dev.share.bytecodelens.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Support for JDK {@code .jmod} files.
 *
 * <p>A {@code .jmod} is a ZIP prefixed with a 4-byte header {@code "JM\x01\x00"}. Strip
 * the header and the rest is a normal ZIP. We also rewrite entry paths on load so
 * {@code classes/java/lang/Object.class} appears as just {@code java/lang/Object.class},
 * matching the {@code JarLoader} conventions.</p>
 */
public final class JmodSupport {

    /** {@code JM\x01\x00} — little-endian marker at byte 0 of every jmod. */
    private static final byte[] MAGIC = {'J', 'M', 0x01, 0x00};

    private JmodSupport() {}

    /** Cheap check: does {@code path} start with the jmod magic? */
    public static boolean isJmod(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            byte[] head = is.readNBytes(MAGIC.length);
            if (head.length != MAGIC.length) return false;
            for (int i = 0; i < MAGIC.length; i++) {
                if (head[i] != MAGIC[i]) return false;
            }
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    /**
     * Extract the payload (jmod minus its 4-byte header) to a temp file. The temp file is
     * marked {@link java.io.File#deleteOnExit()} so it's cleaned up when the JVM quits.
     */
    public static Path extractToTempZip(Path jmod) throws IOException {
        Path tmp = Files.createTempFile("bytecodelens-jmod-", ".zip");
        tmp.toFile().deleteOnExit();
        try (InputStream is = Files.newInputStream(jmod);
             var out = Files.newOutputStream(tmp, StandardOpenOption.WRITE)) {
            byte[] head = is.readNBytes(MAGIC.length);
            if (head.length != MAGIC.length) throw new IOException("jmod header truncated");
            is.transferTo(out);
        }
        return tmp;
    }

    /** Normalise an entry name coming out of a jmod payload: drop the leading {@code classes/}. */
    public static String normaliseEntryName(String name) {
        if (name.startsWith("classes/")) return name.substring("classes/".length());
        return name;
    }
}
