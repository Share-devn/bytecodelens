package dev.share.bytecodelens.ui.views;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-string interpretation of a byte slice as every primitive type a reverse engineer
 * usually needs at a glance. Staying agent-free + JavaFX-free so unit tests run on
 * the plain JVM and nothing here pulls UI deps.
 *
 * <p>All methods return {@code "—"} when there aren't enough bytes to interpret the
 * requested type — easier for the UI to render than sprinkling null-checks.</p>
 */
public final class DataInterpreter {

    /** Named field shown in the inspector panel, with both LE and BE renderings. */
    public record Field(String label, String littleEndian, String bigEndian) {}

    /**
     * Produce the whole list of fields in display order. Caller feeds the raw buffer
     * + the offset to inspect; we read up to 8 bytes for the widest type.
     */
    public static List<Field> interpret(byte[] bytes, int offset) {
        List<Field> out = new ArrayList<>();
        if (bytes == null || offset < 0 || offset >= bytes.length) return out;

        int available = bytes.length - offset;

        // Integer ladder — only include types whose bytes fit. 8-bit always fits.
        out.add(new Field("int8",    asInt8(bytes, offset, true),  asInt8(bytes, offset, true)));
        out.add(new Field("uint8",   asInt8(bytes, offset, false), asInt8(bytes, offset, false)));
        if (available >= 2) {
            out.add(new Field("int16",  asInt(bytes, offset, 2, true,  ByteOrder.LITTLE_ENDIAN),
                                         asInt(bytes, offset, 2, true,  ByteOrder.BIG_ENDIAN)));
            out.add(new Field("uint16", asInt(bytes, offset, 2, false, ByteOrder.LITTLE_ENDIAN),
                                         asInt(bytes, offset, 2, false, ByteOrder.BIG_ENDIAN)));
        }
        if (available >= 4) {
            out.add(new Field("int32",  asInt(bytes, offset, 4, true,  ByteOrder.LITTLE_ENDIAN),
                                         asInt(bytes, offset, 4, true,  ByteOrder.BIG_ENDIAN)));
            out.add(new Field("uint32", asInt(bytes, offset, 4, false, ByteOrder.LITTLE_ENDIAN),
                                         asInt(bytes, offset, 4, false, ByteOrder.BIG_ENDIAN)));
        }
        if (available >= 8) {
            out.add(new Field("int64",  asInt(bytes, offset, 8, true,  ByteOrder.LITTLE_ENDIAN),
                                         asInt(bytes, offset, 8, true,  ByteOrder.BIG_ENDIAN)));
            out.add(new Field("uint64", asInt(bytes, offset, 8, false, ByteOrder.LITTLE_ENDIAN),
                                         asInt(bytes, offset, 8, false, ByteOrder.BIG_ENDIAN)));
        }

        if (available >= 4) {
            out.add(new Field("float32",
                    asFloat32(bytes, offset, ByteOrder.LITTLE_ENDIAN),
                    asFloat32(bytes, offset, ByteOrder.BIG_ENDIAN)));
        }
        if (available >= 8) {
            out.add(new Field("float64",
                    asFloat64(bytes, offset, ByteOrder.LITTLE_ENDIAN),
                    asFloat64(bytes, offset, ByteOrder.BIG_ENDIAN)));
        }

        out.add(new Field("char (ASCII)",
                asAscii(bytes[offset]),
                asAscii(bytes[offset])));
        if (available >= 2) {
            out.add(new Field("UTF-16",
                    asUtf16(bytes, offset, ByteOrder.LITTLE_ENDIAN),
                    asUtf16(bytes, offset, ByteOrder.BIG_ENDIAN)));
        }
        // UTF-8 — up to 4 bytes. The widest code point that fits in however many
        // bytes we have is what we return; don't overread.
        out.add(new Field("UTF-8", asUtf8(bytes, offset, Math.min(available, 4)), ""));

        out.add(new Field("binary (8)", asBinary(bytes[offset]), asBinary(bytes[offset])));

        if (available >= 4) {
            out.add(new Field("Unix time (32)",
                    asUnixTime(asRawInt(bytes, offset, 4, ByteOrder.LITTLE_ENDIAN)),
                    asUnixTime(asRawInt(bytes, offset, 4, ByteOrder.BIG_ENDIAN))));
        }
        if (available >= 8) {
            out.add(new Field("Unix ms (64)",
                    asUnixMillis(asRawLong(bytes, offset, 8, ByteOrder.LITTLE_ENDIAN)),
                    asUnixMillis(asRawLong(bytes, offset, 8, ByteOrder.BIG_ENDIAN))));
            out.add(new Field("FILETIME",
                    asFileTime(asRawLong(bytes, offset, 8, ByteOrder.LITTLE_ENDIAN)),
                    asFileTime(asRawLong(bytes, offset, 8, ByteOrder.BIG_ENDIAN))));
        }

        return out;
    }

    // ========================================================================
    // Individual formatters — each signature returns a ready-for-display string.
    // Package-private so unit tests can hit them directly.
    // ========================================================================

    static String asInt8(byte[] bytes, int offset, boolean signed) {
        int b = bytes[offset] & 0xFF;
        long v = signed ? (byte) b : b;
        return formatIntBoth(v, signed);
    }

    static String asInt(byte[] bytes, int offset, int width, boolean signed, ByteOrder order) {
        long raw = asRawLong(bytes, offset, width, order);
        // Sign-extend only if signed; otherwise mask to unsigned width.
        long v;
        if (signed) {
            int shift = 64 - width * 8;
            v = (raw << shift) >> shift;
        } else {
            long mask = width == 8 ? ~0L : ((1L << (width * 8)) - 1);
            v = raw & mask;
        }
        // uint64 can exceed Long.MAX_VALUE — render as unsigned string.
        if (!signed && width == 8) {
            return formatIntBothUnsigned64(v);
        }
        return formatIntBoth(v, signed);
    }

    static String asFloat32(byte[] bytes, int offset, ByteOrder order) {
        int raw = (int) asRawLong(bytes, offset, 4, order);
        return formatFloat(Float.intBitsToFloat(raw));
    }

    static String asFloat64(byte[] bytes, int offset, ByteOrder order) {
        long raw = asRawLong(bytes, offset, 8, order);
        return formatFloat(Double.longBitsToDouble(raw));
    }

    static String asAscii(byte b) {
        int v = b & 0xFF;
        if (v >= 0x20 && v < 0x7F) return "'" + (char) v + "'";
        return "." + String.format("(0x%02X)", v);
    }

    static String asUtf16(byte[] bytes, int offset, ByteOrder order) {
        int b0 = bytes[offset] & 0xFF;
        int b1 = bytes[offset + 1] & 0xFF;
        int cp = order == ByteOrder.LITTLE_ENDIAN ? (b1 << 8) | b0 : (b0 << 8) | b1;
        if (cp >= 0xD800 && cp <= 0xDFFF) {
            // Lone surrogate — not a valid standalone character.
            return "surrogate U+" + String.format("%04X", cp);
        }
        if (cp < 0x20 || cp == 0x7F) return "control U+" + String.format("%04X", cp);
        return "'" + new String(Character.toChars(cp)) + "' U+" + String.format("%04X", cp);
    }

    static String asUtf8(byte[] bytes, int offset, int maxLen) {
        if (maxLen <= 0) return "—";
        // Decode up to maxLen bytes; stop at the first complete code point.
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        // Try growing windows — UTF-8 code points can span 1–4 bytes.
        for (int len = 1; len <= maxLen; len++) {
            try {
                var buf = ByteBuffer.wrap(bytes, offset, len);
                String s = decoder.decode(buf).toString();
                if (!s.isEmpty() && buf.remaining() == 0) {
                    int cp = s.codePointAt(0);
                    if (cp < 0x20 || cp == 0x7F) {
                        return "control U+" + String.format("%04X", cp) + " (" + len + " byte"
                                + (len == 1 ? "" : "s") + ")";
                    }
                    return "'" + s + "' U+" + String.format("%04X", cp)
                            + " (" + len + " byte" + (len == 1 ? "" : "s") + ")";
                }
            } catch (CharacterCodingException ignored) {
                decoder.reset();
            }
        }
        return "invalid";
    }

    static String asBinary(byte b) {
        int v = b & 0xFF;
        StringBuilder sb = new StringBuilder(9);
        for (int i = 7; i >= 0; i--) {
            sb.append(((v >> i) & 1) == 1 ? '1' : '0');
            if (i == 4) sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * Unix time (seconds since 1970-01-01). Returns ISO-8601 or {@code "—"} for
     * obviously-out-of-range values so we don't splatter the UI with year 10000 AD.
     */
    static String asUnixTime(long seconds) {
        // Clamp to reasonable real-world-ish window: 1970..2100.
        if (seconds < 0 || seconds > 4_102_444_800L) return "—";
        try {
            return DateTimeFormatter.ISO_LOCAL_DATE_TIME
                    .format(Instant.ofEpochSecond(seconds).atZone(ZoneId.of("UTC")).toLocalDateTime())
                    + "Z";
        } catch (Exception ex) {
            return "—";
        }
    }

    static String asUnixMillis(long millis) {
        if (millis < 0 || millis > 4_102_444_800_000L) return "—";
        try {
            return DateTimeFormatter.ISO_LOCAL_DATE_TIME
                    .format(Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDateTime())
                    + "Z";
        } catch (Exception ex) {
            return "—";
        }
    }

    /**
     * Windows FILETIME: 100-nanosecond intervals since 1601-01-01 UTC. Converting to
     * Instant needs offset 11_644_473_600 seconds.
     */
    static String asFileTime(long raw) {
        if (raw <= 0) return "—";
        long seconds = raw / 10_000_000L - 11_644_473_600L;
        if (seconds < 0 || seconds > 4_102_444_800L) return "—";
        try {
            return DateTimeFormatter.ISO_LOCAL_DATE_TIME
                    .format(Instant.ofEpochSecond(seconds).atZone(ZoneId.of("UTC")).toLocalDateTime())
                    + "Z";
        } catch (Exception ex) {
            return "—";
        }
    }

    // ========================================================================
    // Low-level raw readers
    // ========================================================================

    static long asRawLong(byte[] bytes, int offset, int width, ByteOrder order) {
        long v = 0;
        if (order == ByteOrder.LITTLE_ENDIAN) {
            for (int i = 0; i < width; i++) {
                v |= ((long) (bytes[offset + i] & 0xFF)) << (8 * i);
            }
        } else {
            for (int i = 0; i < width; i++) {
                v |= ((long) (bytes[offset + i] & 0xFF)) << (8 * (width - 1 - i));
            }
        }
        return v;
    }

    static int asRawInt(byte[] bytes, int offset, int width, ByteOrder order) {
        return (int) asRawLong(bytes, offset, width, order);
    }

    // ========================================================================
    // Rendering helpers
    // ========================================================================

    /** Render a (possibly negative) integer as {@code "decimal (0xHEX)"}. */
    static String formatIntBoth(long v, boolean signed) {
        String dec = Long.toString(v);
        String hex = signed && v < 0
                ? "-0x" + Long.toHexString(-v).toUpperCase()
                : "0x" + Long.toHexString(v).toUpperCase();
        return dec + "  (" + hex + ")";
    }

    static String formatIntBothUnsigned64(long v) {
        // Long.toUnsignedString is jdk-8+ safe and handles all 64 bits.
        String dec = Long.toUnsignedString(v);
        String hex = "0x" + Long.toUnsignedString(v, 16).toUpperCase();
        return dec + "  (" + hex + ")";
    }

    static String formatFloat(double d) {
        if (Double.isNaN(d)) return "NaN";
        if (Double.isInfinite(d)) return d > 0 ? "+Inf" : "-Inf";
        // Use ROOT locale — on ru_* default, %.7g would print "1,000000" (comma) and
        // break both downstream parsers and our own test fixtures.
        String s = String.format(java.util.Locale.ROOT, "%.7g", d);
        if (s.contains(".") && !s.contains("e")) {
            int cut = s.length();
            while (cut > 1 && s.charAt(cut - 1) == '0') cut--;
            if (cut > 1 && s.charAt(cut - 1) == '.') cut--;
            s = s.substring(0, cut);
        }
        return s;
    }

    private DataInterpreter() {}
}
