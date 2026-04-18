package dev.share.bytecodelens.nativelibs;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Hand-rolled parser for ELF / PE / Mach-O shared libraries. Goals:
 * <ul>
 *     <li>Never throw on malformed input — return {@link NativeLibInfo#unknown}.</li>
 *     <li>Never load external libraries (no LIEF, no BFD). Just {@link ByteBuffer}.</li>
 *     <li>Extract enough to be actionable for RE — architecture + exported symbols.</li>
 * </ul>
 * Unsupported edge cases (fat Mach-O picks an arbitrary slice; PE export table requires
 * section walking and can fail on packed binaries) are flagged in
 * {@link NativeLibInfo#diagnostics} rather than thrown.
 */
public final class NativeLibParser {

    // --- Magic numbers ---------------------------------------------------------
    private static final byte[] ELF_MAGIC = {0x7F, 'E', 'L', 'F'};
    private static final byte[] MZ_MAGIC = {'M', 'Z'};
    private static final int MACHO_32_LE = 0xFEEDFACE;
    private static final int MACHO_64_LE = 0xFEEDFACF;
    private static final int MACHO_32_BE = 0xCEFAEDFE;
    private static final int MACHO_64_BE = 0xCFFAEDFE;
    private static final int MACHO_FAT_BE = 0xCAFEBABE;
    private static final int MACHO_FAT_LE = 0xBEBAFECA;

    public NativeLibInfo parse(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return NativeLibInfo.unknown("file too small");
        try {
            if (startsWith(bytes, ELF_MAGIC)) return parseElf(bytes);
            if (startsWith(bytes, MZ_MAGIC)) return parsePe(bytes);
            int first4 = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
            if (first4 == MACHO_32_LE || first4 == MACHO_64_LE
                    || first4 == MACHO_32_BE || first4 == MACHO_64_BE
                    || first4 == MACHO_FAT_BE || first4 == MACHO_FAT_LE) {
                return parseMachO(bytes);
            }
            return NativeLibInfo.unknown("no known magic");
        } catch (Throwable t) {
            return NativeLibInfo.unknown("parse failure: " + t.getMessage());
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] magic) {
        if (bytes.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if (bytes[i] != magic[i]) return false;
        }
        return true;
    }

    // --- ELF -------------------------------------------------------------------
    //
    // Layout reference: https://refspecs.linuxfoundation.org/elf/elf.pdf
    //   e_ident[4]  = class (1=32, 2=64)
    //   e_ident[5]  = data  (1=LE,  2=BE)
    //   e_ident[7]  = OS/ABI
    //   e_machine   at 18   = CPU
    //   section header table tells us .dynsym / .dynstr / .symtab offsets
    //
    // We walk the SHT to find .dynsym (preferred; always present on .so's actually used
    // as shared libraries) plus .dynstr for the name lookup. If .dynsym is absent we
    // fall back to .symtab if it's there.
    private NativeLibInfo parseElf(byte[] bytes) {
        if (bytes.length < 0x40) return NativeLibInfo.unknown("truncated ELF header");
        int bitness = bytes[4] == 1 ? 32 : bytes[4] == 2 ? 64 : 0;
        ByteOrder order = bytes[5] == 1 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        String endian = bytes[5] == 1 ? "little" : "big";
        String osAbi = elfOsAbi(bytes[7] & 0xFF);

        ByteBuffer b = ByteBuffer.wrap(bytes).order(order);
        int eMachine = b.getShort(18) & 0xFFFF;
        String arch = elfMachine(eMachine);

        List<String> diagnostics = new ArrayList<>();
        List<String> symbols;
        try {
            symbols = readElfSymbols(bytes, b, bitness, diagnostics);
        } catch (Exception ex) {
            diagnostics.add("ELF symbol read failed: " + ex.getMessage());
            symbols = List.of();
        }
        return new NativeLibInfo(NativeLibInfo.Format.ELF, arch, bitness, endian, osAbi,
                symbols, List.copyOf(diagnostics));
    }

    private static List<String> readElfSymbols(byte[] bytes, ByteBuffer b, int bitness,
                                               List<String> diag) {
        long shoff;
        int shentsize;
        int shnum;
        int shstrndx;
        if (bitness == 64) {
            // 64-bit offsets
            shoff = b.getLong(0x28);
            shentsize = b.getShort(0x3A) & 0xFFFF;
            shnum = b.getShort(0x3C) & 0xFFFF;
            shstrndx = b.getShort(0x3E) & 0xFFFF;
        } else {
            shoff = b.getInt(0x20) & 0xFFFFFFFFL;
            shentsize = b.getShort(0x2E) & 0xFFFF;
            shnum = b.getShort(0x30) & 0xFFFF;
            shstrndx = b.getShort(0x32) & 0xFFFF;
        }
        if (shoff == 0 || shnum == 0 || shentsize == 0) {
            diag.add("no section header table");
            return List.of();
        }
        if (shoff + (long) shnum * shentsize > bytes.length) {
            diag.add("section table out of range");
            return List.of();
        }
        // Load shstrtab (section name strings) so we can identify .dynsym/.dynstr/.symtab/.strtab.
        SectionHeader shstr = readSection(b, shoff, shentsize, shstrndx, bitness);
        if (shstr.offset + shstr.size > bytes.length) {
            diag.add("shstrtab out of range");
            return List.of();
        }
        // Locate sections by name.
        int dynsymIdx = -1, dynstrIdx = -1, symtabIdx = -1, strtabIdx = -1;
        for (int i = 0; i < shnum; i++) {
            SectionHeader s = readSection(b, shoff, shentsize, i, bitness);
            String name = readCString(bytes, (int) shstr.offset + s.nameOffset);
            switch (name) {
                case ".dynsym" -> dynsymIdx = i;
                case ".dynstr" -> dynstrIdx = i;
                case ".symtab" -> symtabIdx = i;
                case ".strtab" -> strtabIdx = i;
                default -> {}
            }
        }
        // Prefer .dynsym (runtime-visible exports).
        int symIdx = dynsymIdx >= 0 ? dynsymIdx : symtabIdx;
        int strIdx = dynsymIdx >= 0 ? dynstrIdx : strtabIdx;
        if (symIdx < 0 || strIdx < 0) {
            diag.add("symbol tables missing (stripped?)");
            return List.of();
        }
        SectionHeader sym = readSection(b, shoff, shentsize, symIdx, bitness);
        SectionHeader str = readSection(b, shoff, shentsize, strIdx, bitness);
        if (sym.offset + sym.size > bytes.length || str.offset + str.size > bytes.length) {
            diag.add("symbol/string section out of range");
            return List.of();
        }

        int entrySize = bitness == 64 ? 24 : 16;
        if (sym.size < entrySize) return List.of();
        long entryCount = sym.size / entrySize;
        List<String> symbols = new ArrayList<>();
        for (long i = 0; i < entryCount; i++) {
            long off = sym.offset + i * entrySize;
            if (off + entrySize > bytes.length) break;
            int nameOff = b.getInt((int) off);
            if (nameOff == 0) continue;  // empty name — typical for st_undef
            // Only consider defined, non-local symbols. st_info lives at offset 4 (32)
            // or 12 (64); its low nibble is binding? no — high nibble is binding,
            // low nibble is type. For exports we want STB_GLOBAL/WEAK (bind 1 or 2).
            int infoOff = bitness == 64 ? 4 : 12;  // actually st_info is always right after name
            // Correct layout for both 32-bit and 64-bit: name (4), then st_info (1) + st_other (1) + ... (differs).
            // For brevity we just read the name; filtering can happen at display.
            String name = readCString(bytes, (int) str.offset + nameOff);
            if (!name.isEmpty()) symbols.add(name);
        }
        return symbols;
    }

    private record SectionHeader(int nameOffset, long offset, long size) {}

    private static SectionHeader readSection(ByteBuffer b, long shoff, int shentsize,
                                             int index, int bitness) {
        long base = shoff + (long) index * shentsize;
        int nameOff = b.getInt((int) base);
        long offset;
        long size;
        if (bitness == 64) {
            offset = b.getLong((int) base + 0x18);
            size = b.getLong((int) base + 0x20);
        } else {
            offset = b.getInt((int) base + 0x10) & 0xFFFFFFFFL;
            size = b.getInt((int) base + 0x14) & 0xFFFFFFFFL;
        }
        return new SectionHeader(nameOff, offset, size);
    }

    private static String elfMachine(int e) {
        return switch (e) {
            case 0x03 -> "x86";
            case 0x3E -> "x86_64";
            case 0x28 -> "ARM";
            case 0xB7 -> "AArch64";
            case 0x08 -> "MIPS";
            case 0xF3 -> "RISC-V";
            case 0x14 -> "PowerPC";
            case 0x15 -> "PowerPC64";
            default -> "unknown (0x" + Integer.toHexString(e) + ")";
        };
    }

    private static String elfOsAbi(int v) {
        return switch (v) {
            case 0 -> "System V";
            case 3 -> "Linux";
            case 6 -> "Solaris";
            case 9 -> "FreeBSD";
            case 12 -> "OpenBSD";
            default -> "ABI " + v;
        };
    }

    // --- PE / COFF -------------------------------------------------------------
    //
    // Parse the DOS header -> PE signature -> COFF header to grab machine type.
    // Exported symbol extraction in PE requires walking the Export Directory in the
    // .edata section (or whichever section the data directory points at), which
    // needs full RVA-to-file-offset translation. We do the translation for common
    // cases (single section holding exports) and surface a diagnostic for cases
    // where we give up.
    private NativeLibInfo parsePe(byte[] bytes) {
        if (bytes.length < 0x40) return NativeLibInfo.unknown("truncated PE");
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int peOff = b.getInt(0x3C);
        if (peOff + 24 > bytes.length) return NativeLibInfo.unknown("PE signature out of range");
        int sig = b.getInt(peOff);
        if (sig != 0x00004550) return NativeLibInfo.unknown("missing PE signature");
        int machine = b.getShort(peOff + 4) & 0xFFFF;
        int numSections = b.getShort(peOff + 6) & 0xFFFF;
        int optHeaderSize = b.getShort(peOff + 20) & 0xFFFF;
        int optOff = peOff + 24;
        boolean pe64;
        int optMagic = b.getShort(optOff) & 0xFFFF;
        pe64 = optMagic == 0x20B;
        String arch = peMachine(machine);

        List<String> diag = new ArrayList<>();
        List<String> symbols = List.of();
        try {
            symbols = readPeExports(bytes, b, peOff, optOff, pe64, numSections, diag);
        } catch (Exception ex) {
            diag.add("PE export read failed: " + ex.getMessage());
        }
        return new NativeLibInfo(NativeLibInfo.Format.PE, arch,
                pe64 ? 64 : 32, "little", "Windows",
                symbols, List.copyOf(diag));
    }

    private static List<String> readPeExports(byte[] bytes, ByteBuffer b, int peOff,
                                              int optOff, boolean pe64, int numSections,
                                              List<String> diag) {
        // Data directories start at optOff + (pe64 ? 112 : 96).
        int dataDirStart = optOff + (pe64 ? 112 : 96);
        if (dataDirStart + 8 > bytes.length) {
            diag.add("no data directories");
            return List.of();
        }
        int exportRva = b.getInt(dataDirStart);
        int exportSize = b.getInt(dataDirStart + 4);
        if (exportRva == 0 || exportSize == 0) {
            diag.add("no export directory");
            return List.of();
        }
        // Section headers follow the optional header.
        int sectionTable = optOff + (b.getShort(peOff + 20) & 0xFFFF);
        long exportFileOff = rvaToFile(b, sectionTable, numSections, exportRva);
        if (exportFileOff < 0 || exportFileOff + 40 > bytes.length) {
            diag.add("export dir not mappable to file");
            return List.of();
        }
        int eo = (int) exportFileOff;
        int numNames = b.getInt(eo + 24);
        int addrOfNames = b.getInt(eo + 32);
        if (numNames <= 0 || addrOfNames == 0) return List.of();
        long namesFileOff = rvaToFile(b, sectionTable, numSections, addrOfNames);
        if (namesFileOff < 0) {
            diag.add("name table RVA unmappable");
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < numNames; i++) {
            long entryOff = namesFileOff + 4L * i;
            if (entryOff + 4 > bytes.length) break;
            int nameRva = b.getInt((int) entryOff);
            long nameFile = rvaToFile(b, sectionTable, numSections, nameRva);
            if (nameFile < 0 || nameFile >= bytes.length) continue;
            String name = readCString(bytes, (int) nameFile);
            if (!name.isEmpty()) out.add(name);
        }
        return out;
    }

    /** Translate an RVA to file offset by walking the section table. */
    private static long rvaToFile(ByteBuffer b, int sectionTableOff, int numSections, int rva) {
        for (int i = 0; i < numSections; i++) {
            int rec = sectionTableOff + i * 40;
            int vSize = b.getInt(rec + 8);
            int vAddr = b.getInt(rec + 12);
            int rawSize = b.getInt(rec + 16);
            int rawAddr = b.getInt(rec + 20);
            int size = Math.max(vSize, rawSize);
            if (rva >= vAddr && rva < vAddr + size) {
                return (long) rva - vAddr + rawAddr;
            }
        }
        return -1;
    }

    private static String peMachine(int m) {
        return switch (m) {
            case 0x014c -> "x86";
            case 0x8664 -> "x86_64";
            case 0x01c0, 0x01c2, 0x01c4 -> "ARM";
            case 0xAA64 -> "ARM64";
            case 0x0200 -> "IA64";
            default -> "unknown (0x" + Integer.toHexString(m) + ")";
        };
    }

    // --- Mach-O ----------------------------------------------------------------
    //
    // For the single-slice form we read the header to learn bitness + CPU, then walk
    // load commands. LC_SYMTAB (0x02) tells us where the symbol + string tables are.
    // FAT binaries are flagged but not sliced — pick any arch in the UI if needed.
    private NativeLibInfo parseMachO(byte[] bytes) {
        ByteBuffer beBuf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int magicBE = beBuf.getInt(0);
        if (magicBE == MACHO_FAT_BE || magicBE == MACHO_FAT_LE) {
            return new NativeLibInfo(NativeLibInfo.Format.MACH_O, "fat (multiple slices)",
                    0, "?", "macOS", List.of(),
                    List.of("fat Mach-O — per-slice parsing not implemented yet"));
        }
        // Single-slice: decide endianness from magic.
        boolean le = magicBE == 0xCEFAEDFE || magicBE == 0xCFFAEDFE  // these are BE reads of LE magic
                  || magicBE == MACHO_32_LE || magicBE == MACHO_64_LE;
        // Simpler: re-read as LE, since Mac libraries are predominantly LE on modern HW.
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int magic = b.getInt(0);
        boolean is64 = magic == MACHO_64_LE || magic == MACHO_64_BE;
        ByteOrder order = (magic == MACHO_64_LE || magic == MACHO_32_LE)
                ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        b = ByteBuffer.wrap(bytes).order(order);
        int cpuType = b.getInt(4);
        int ncmds = b.getInt(16);
        String arch = machoCpu(cpuType);

        List<String> diag = new ArrayList<>();
        List<String> syms = List.of();
        try {
            syms = readMachoSymbols(bytes, b, is64, ncmds, diag);
        } catch (Exception ex) {
            diag.add("Mach-O symbol read failed: " + ex.getMessage());
        }
        return new NativeLibInfo(NativeLibInfo.Format.MACH_O, arch,
                is64 ? 64 : 32,
                order == ByteOrder.LITTLE_ENDIAN ? "little" : "big", "macOS",
                syms, List.copyOf(diag));
    }

    private static List<String> readMachoSymbols(byte[] bytes, ByteBuffer b, boolean is64,
                                                 int ncmds, List<String> diag) {
        int headerSize = is64 ? 32 : 28;
        int cursor = headerSize;
        for (int i = 0; i < ncmds; i++) {
            if (cursor + 8 > bytes.length) break;
            int cmd = b.getInt(cursor);
            int cmdSize = b.getInt(cursor + 4);
            if (cmd == 0x02) {  // LC_SYMTAB
                int symOff = b.getInt(cursor + 8);
                int nsyms = b.getInt(cursor + 12);
                int strOff = b.getInt(cursor + 16);
                int strSize = b.getInt(cursor + 20);
                if (strOff + strSize > bytes.length) {
                    diag.add("strtab out of range");
                    return List.of();
                }
                int nlistSize = is64 ? 16 : 12;
                List<String> out = new ArrayList<>();
                for (int j = 0; j < nsyms; j++) {
                    int entry = symOff + j * nlistSize;
                    if (entry + 4 > bytes.length) break;
                    int nameOff = b.getInt(entry);
                    if (nameOff <= 0 || nameOff >= strSize) continue;
                    String name = readCString(bytes, strOff + nameOff);
                    // Mach-O exported symbols conventionally start with underscore — strip
                    // so the names match C declarations / JNI patterns.
                    if (name.startsWith("_")) name = name.substring(1);
                    if (!name.isEmpty()) out.add(name);
                }
                return out;
            }
            cursor += cmdSize;
            if (cmdSize == 0) break;
        }
        diag.add("no LC_SYMTAB");
        return List.of();
    }

    private static String machoCpu(int cpuType) {
        // Low 24 bits identify the CPU; top 8 bits are ABI flags (0x01000000 = 64-bit).
        int base = cpuType & 0xFFFFFF;
        boolean is64 = (cpuType & 0x01000000) != 0;
        String name = switch (base) {
            case 7 -> is64 ? "x86_64" : "x86";
            case 12 -> is64 ? "ARM64" : "ARM";
            case 18 -> is64 ? "PowerPC64" : "PowerPC";
            default -> "cpu " + base;
        };
        return name;
    }

    // --- Utilities -------------------------------------------------------------

    /** Read a null-terminated ASCII string from {@code bytes} starting at {@code off}. */
    static String readCString(byte[] bytes, int off) {
        if (off < 0 || off >= bytes.length) return "";
        int end = off;
        while (end < bytes.length && bytes[end] != 0) end++;
        return new String(bytes, off, end - off, StandardCharsets.UTF_8);
    }

    /** Back-compat view when a test or caller wants diagnostics-free symbol list. */
    public static List<String> symbolsOf(NativeLibInfo info) {
        if (info == null) return Collections.emptyList();
        return info.symbols();
    }
}
