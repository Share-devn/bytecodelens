package dev.share.bytecodelens.mapping;

/**
 * Mapping file formats recognised by BytecodeLens.
 *
 * <ul>
 *     <li>{@link #PROGUARD} — ProGuard/R8 {@code mapping.txt} ({@code com.foo.Bar -> a.b:})</li>
 *     <li>{@link #TINY_V1} — Fabric Tiny v1 (TAB-separated, header {@code v1\tofficial\tnamed})</li>
 *     <li>{@link #TINY_V2} — Fabric Tiny v2 ({@code tiny\t2\t0\tofficial\tnamed})</li>
 *     <li>{@link #SRG} — Forge SRG v1 ({@code CL: a/b com/example/Foo})</li>
 *     <li>{@link #XSRG} — SRG variant with field descriptors ({@code FD: a/b/c desc com/example/Foo/field desc})</li>
 *     <li>{@link #TSRG} — Forge TSRG v1 (two-column, indented members)</li>
 *     <li>{@link #TSRG_V2} — TSRG v2 (header {@code tsrg2 source target …}, optional method param/static markers)</li>
 *     <li>{@link #CSRG} — Compact SRG (single-line entries, space-separated)</li>
 *     <li>{@link #JOBF} — JOBF (Java OBFuscator) text format</li>
 *     <li>{@link #ENIGMA} — Enigma directory tree of {@code .mapping} files
 *         (we accept a single concatenated {@code .mappings} file too)</li>
 *     <li>{@link #RECAF} — Recaf simple text format ({@code TYPE oldName newName} per line)</li>
 * </ul>
 */
public enum MappingFormat {
    PROGUARD,
    TINY_V1,
    TINY_V2,
    SRG,
    XSRG,
    TSRG,
    TSRG_V2,
    CSRG,
    JOBF,
    ENIGMA,
    RECAF
}
