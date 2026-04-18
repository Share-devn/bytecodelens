package dev.share.bytecodelens.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Anti-tamper ZIP reader that falls back through progressively more permissive parsing
 * strategies. Also reports which anti-tamper patterns were found so the UI can tell the
 * user what's suspicious about a jar.
 *
 * <p>Parsing order:</p>
 * <ol>
 *     <li><b>Standard</b> — {@link ZipFile}. Fails on missing/corrupt Central Directory.</li>
 *     <li><b>JVM</b> — {@link JvmZipReader}, ignores CD, scans Local File Headers.</li>
 * </ol>
 *
 * <p>The "Naïve" strategy from roadmap would only add value if both above fail and the file
 * has been stripped of all signatures; in practice the JVM reader already handles that.</p>
 */
public final class RobustJarReader {

    private static final Logger log = LoggerFactory.getLogger(RobustJarReader.class);

    public enum Strategy { STANDARD, JVM }

    /**
     * Result of a robust read: the entries as a map, plus a list of diagnostics explaining
     * what anti-tamper patterns were observed.
     */
    public record Result(Map<String, byte[]> entries, Strategy strategyUsed, List<String> diagnostics) {}

    public Result read(Path jar) throws IOException {
        List<String> diagnostics = new ArrayList<>();
        byte[] bytes = Files.readAllBytes(jar);
        checkPolyglotHeader(bytes, diagnostics);
        checkEocd(bytes, diagnostics);

        // Attempt 1 — standard ZipFile
        try {
            Map<String, byte[]> entries = readWithZipFile(jar, diagnostics);
            return new Result(entries, Strategy.STANDARD, diagnostics);
        } catch (IOException ex) {
            log.info("Standard ZipFile read failed ({}), falling back to JVM strategy", ex.getMessage());
            diagnostics.add("Standard ZIP parser rejected the file: " + ex.getMessage());
        }

        // Attempt 2 — JVM local-file-header scan
        var raw = JvmZipReader.readAll(bytes);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        int duplicates = 0;
        for (var e : raw) {
            if (!seen.add(e.name())) {
                duplicates++;
                continue; // keep first occurrence, dedupe duplicates
            }
            entries.put(e.name(), e.data());
        }
        if (duplicates > 0) {
            diagnostics.add("Deduplicated " + duplicates + " repeated entries (JVM strategy).");
        }
        return new Result(entries, Strategy.JVM, diagnostics);
    }

    /** Read with standard ZipFile but watch for duplicates in the Central Directory. */
    private Map<String, byte[]> readWithZipFile(Path jar, List<String> diagnostics) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        int duplicates = 0;
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            for (var it = zf.entries(); it.hasMoreElements(); ) {
                ZipEntry e = it.nextElement();
                if (e.isDirectory()) continue;
                if (out.containsKey(e.getName())) {
                    duplicates++;
                    continue;
                }
                try (var in = zf.getInputStream(e)) {
                    out.put(e.getName(), in.readAllBytes());
                }
            }
        }
        if (duplicates > 0) {
            diagnostics.add("Deduplicated " + duplicates + " duplicate CD entries.");
        }
        return out;
    }

    /**
     * Polyglot files start with a non-ZIP header (image, PE, ELF) then embed the ZIP
     * somewhere later. We detect by looking for the ZIP signature not being at offset 0.
     */
    private static void checkPolyglotHeader(byte[] bytes, List<String> diagnostics) {
        if (bytes.length < 4) return;
        boolean startsWithZip = bytes[0] == 'P' && bytes[1] == 'K'
                && bytes[2] == 0x03 && bytes[3] == 0x04;
        if (!startsWithZip) {
            int sig = findSig(bytes, 0, 0x04034b50);
            if (sig > 0) {
                diagnostics.add("Polyglot file: ZIP begins at offset " + sig
                        + " (first 4 bytes: " + hexPrefix(bytes) + ").");
            }
        }
    }

    /**
     * EOCD record should be within the last ~64K bytes. Its absence is a red flag —
     * some obfuscators strip it, expecting tools to give up.
     */
    private static void checkEocd(byte[] bytes, List<String> diagnostics) {
        int searchFrom = Math.max(0, bytes.length - 65557);
        int idx = findSig(bytes, searchFrom, 0x06054b50);
        if (idx < 0) {
            diagnostics.add("End-of-Central-Directory record missing or unreachable.");
        }
    }

    private static int findSig(byte[] bytes, int from, int sig) {
        byte b0 = (byte) (sig & 0xFF);
        byte b1 = (byte) ((sig >> 8) & 0xFF);
        byte b2 = (byte) ((sig >> 16) & 0xFF);
        byte b3 = (byte) ((sig >> 24) & 0xFF);
        for (int i = from; i < bytes.length - 3; i++) {
            if (bytes[i] == b0 && bytes[i + 1] == b1
                    && bytes[i + 2] == b2 && bytes[i + 3] == b3) return i;
        }
        return -1;
    }

    private static String hexPrefix(byte[] b) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(4, b.length);
        for (int i = 0; i < n; i++) sb.append(String.format("%02x ", b[i] & 0xFF));
        return sb.toString().trim();
    }
}
