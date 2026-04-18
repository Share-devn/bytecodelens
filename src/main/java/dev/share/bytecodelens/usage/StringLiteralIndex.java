package dev.share.bytecodelens.usage;

import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.LoadedJar;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Indexes every {@code String} constant referenced from a jar so we can answer
 * "where is this exact string used?" instantly. Distinguishes three sources:
 *
 * <ul>
 *   <li>{@link Site.Source#LDC} — {@code LDC} instructions in method bodies (the bulk).</li>
 *   <li>{@link Site.Source#FIELD_CONSTANT} — final-static String fields with a
 *       {@code ConstantValue} attribute. Inlined by javac at the use site, but the
 *       definition itself still appears in the constant pool of the declaring class.</li>
 *   <li>{@link Site.Source#INVOKEDYNAMIC_BSM} — invokedynamic call sites whose
 *       bootstrap arguments include a String constant (e.g. STR concatenation
 *       templates compiled with makeConcatWithConstants).</li>
 * </ul>
 *
 * <p>Build is parallel over classes; lookup is O(1) by exact string and O(n) for
 * substring/regex (intentional — strings are interned, so hash-bucketing is enough).</p>
 */
public final class StringLiteralIndex {

    private static final Logger log = LoggerFactory.getLogger(StringLiteralIndex.class);

    public record Site(
            String inClassInternal,
            String inMethodName,
            String inMethodDesc,
            int lineNumber,
            Source source,
            String value
    ) {
        public enum Source { LDC, FIELD_CONSTANT, INVOKEDYNAMIC_BSM }
    }

    private final LoadedJar jar;
    /** Exact-string → list of sites. Lists are synchronized for concurrent build. */
    private final Map<String, List<Site>> byExact = new ConcurrentHashMap<>();
    /** Total site count for stats. */
    private long totalSites;

    public StringLiteralIndex(LoadedJar jar) {
        this.jar = jar;
    }

    public void build() {
        long start = System.currentTimeMillis();
        jar.classes().parallelStream().forEach(this::scan);
        totalSites = byExact.values().stream().mapToInt(List::size).sum();
        log.info("StringLiteralIndex: {} unique strings, {} sites in {}ms",
                byExact.size(), totalSites, System.currentTimeMillis() - start);
    }

    /** All sites that reference exactly {@code s} (after interning). */
    public List<Site> findExact(String s) {
        if (s == null) return List.of();
        List<Site> list = byExact.get(s);
        return list == null ? List.of() : List.copyOf(list);
    }

    /**
     * Linear scan over the index for case-sensitive substring matches. Designed for
     * "find every string containing 'http://'" — exact lookup is preferred when the
     * value is known.
     */
    public List<Site> findContaining(String fragment) {
        if (fragment == null || fragment.isEmpty()) return List.of();
        List<Site> out = new ArrayList<>();
        for (var e : byExact.entrySet()) {
            if (e.getKey().contains(fragment)) out.addAll(e.getValue());
        }
        return out;
    }

    public List<Site> findRegex(Pattern pattern) {
        if (pattern == null) return List.of();
        List<Site> out = new ArrayList<>();
        for (var e : byExact.entrySet()) {
            if (pattern.matcher(e.getKey()).find()) out.addAll(e.getValue());
        }
        return out;
    }

    /** Snapshot of every (string, count) pair — useful for the search overlay's "string mode". */
    public Map<String, Integer> distribution() {
        Map<String, Integer> m = new HashMap<>(byExact.size());
        byExact.forEach((k, v) -> m.put(k, v.size()));
        return m;
    }

    public int uniqueStringCount() { return byExact.size(); }
    public long totalSiteCount() { return totalSites; }

    private void scan(ClassEntry entry) {
        ClassNode node;
        try {
            node = new ClassNode();
            new ClassReader(entry.bytes()).accept(node, ClassReader.SKIP_FRAMES);
        } catch (Exception ex) {
            log.debug("StringLiteralIndex: skip {}: {}", entry.name(), ex.getMessage());
            return;
        }
        String inClass = entry.internalName();

        // Field constants — `static final String FOO = "bar"` keeps the literal in the
        // declaring class even after javac inlines it at every use site.
        if (node.fields != null) {
            for (FieldNode f : node.fields) {
                if (f.value instanceof String s) {
                    record(s, new Site(inClass, f.name, f.desc, 0, Site.Source.FIELD_CONSTANT, s));
                }
            }
        }

        if (node.methods == null) return;
        for (MethodNode m : node.methods) {
            if (m.instructions == null) continue;
            int currentLine = 0;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LineNumberNode ln) {
                    currentLine = ln.line;
                    continue;
                }
                if (insn instanceof LdcInsnNode ldc) {
                    if (ldc.cst instanceof String s) {
                        record(s, new Site(inClass, m.name, m.desc, currentLine, Site.Source.LDC, s));
                    }
                    // LDC of Type/Handle/ConstantDynamic is not a String literal — skipped.
                } else if (insn instanceof InvokeDynamicInsnNode indy) {
                    if (indy.bsmArgs == null) continue;
                    for (Object arg : indy.bsmArgs) {
                        if (arg instanceof String s) {
                            record(s, new Site(inClass, m.name, m.desc, currentLine,
                                    Site.Source.INVOKEDYNAMIC_BSM, s));
                        }
                    }
                    // Defensive: makeConcatWithConstants stores the recipe as a String in
                    // bsmArgs[0]; that's already covered by the loop above.
                    // Suppress unused-import warning for Type — kept for future LDC-Type handling.
                    if (false) Type.getMethodType(indy.desc);
                }
            }
        }
    }

    private void record(String s, Site site) {
        byExact.computeIfAbsent(s, k -> Collections.synchronizedList(new ArrayList<>())).add(site);
    }
}
