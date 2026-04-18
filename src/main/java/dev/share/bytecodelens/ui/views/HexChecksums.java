package dev.share.bytecodelens.ui.views;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Compute common cryptographic / checksum hashes of a byte slice. Pure function,
 * no UI deps — easy to unit-test and reuse in CLI later.
 *
 * <p>We deliberately include <b>MD5 and SHA-1</b> alongside the SHA-2 family: file-
 * identification workflows still use them (every malware DB keys on MD5 + SHA-1,
 * CDN integrity checks often use MD5). This is a viewer, not a security tool —
 * we're not advocating for using these as crypto primitives.</p>
 */
public final class HexChecksums {

    /** Result map key in insertion order: CRC32, MD5, SHA-1, SHA-256, SHA-512. */
    public static Map<String, String> compute(byte[] bytes) {
        Map<String, String> out = new LinkedHashMap<>();
        if (bytes == null) bytes = new byte[0];

        CRC32 crc = new CRC32();
        crc.update(bytes);
        out.put("CRC32", String.format("%08X", crc.getValue()));

        for (String alg : new String[]{"MD5", "SHA-1", "SHA-256", "SHA-512"}) {
            out.put(alg, tryHash(alg, bytes));
        }
        return out;
    }

    private static String tryHash(String algorithm, byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            // Every JRE ships the ones we ask for — but a hardened deployment might strip MD5.
            return "(unavailable)";
        }
    }

    private HexChecksums() {}
}
