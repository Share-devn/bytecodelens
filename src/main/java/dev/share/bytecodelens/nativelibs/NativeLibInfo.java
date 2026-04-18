package dev.share.bytecodelens.nativelibs;

import java.util.List;

/**
 * Parsed metadata from a native shared library (.so / .dll / .dylib).
 * Always carries at least {@link #format} and {@link #architecture}; symbol list may be
 * empty if the binary is stripped.
 *
 * @param format         executable format of the file
 * @param architecture   CPU architecture (x86, x86_64, ARM, ARM64, …)
 * @param bitness        32 or 64, or 0 if indeterminate (e.g. fat Mach-O before picking a slice)
 * @param endianness     {@code "little"} / {@code "big"} / {@code "?"}
 * @param osAbi          loose OS/ABI string (Linux/Windows/macOS/…) parsed from headers
 * @param symbols        exported symbols — may include JNI {@code Java_*} names
 * @param diagnostics    parse warnings / notes, surfaced to the UI for transparency
 */
public record NativeLibInfo(
        Format format,
        String architecture,
        int bitness,
        String endianness,
        String osAbi,
        List<String> symbols,
        List<String> diagnostics) {

    public enum Format { ELF, PE, MACH_O, UNKNOWN }

    public static NativeLibInfo unknown(String reason) {
        return new NativeLibInfo(Format.UNKNOWN, "?", 0, "?", "?",
                List.of(), List.of(reason == null ? "unrecognised file" : reason));
    }
}
