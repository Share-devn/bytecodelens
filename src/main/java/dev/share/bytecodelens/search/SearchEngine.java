package dev.share.bytecodelens.search;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Search engine with two entry points:
 * <ul>
 *   <li>{@link #search(SearchIndex, SearchQuery)} — collects everything into a list. Keeps
 *       the simple path for tests and the CLI.</li>
 *   <li>{@link #search(SearchIndex, SearchQuery, Consumer, BooleanSupplier)} — streams
 *       each hit to the consumer and polls the {@code cancelCheck} supplier between
 *       items. The UI uses this so results show up incrementally and {@code Cancel}
 *       actually stops the scan (not just throws away the final list).</li>
 * </ul>
 *
 * <p>Results are unbounded — earlier versions capped at 5000 which silently hid hits in
 * huge jars (Minecraft-mapping-obf). The caller is now responsible for bounding if it
 * cares (e.g. the UI can stop listening after N items).</p>
 */
public final class SearchEngine {

    /** Convenience wrapper: list-collecting, uncancellable, bounded only by memory. */
    public List<SearchResult> search(SearchIndex index, SearchQuery query) {
        List<SearchResult> out = new ArrayList<>();
        search(index, query, out::add, () -> false);
        return out;
    }

    /**
     * Streaming entry point.
     *
     * @param consumer    receives each match as soon as it's found
     * @param cancelCheck polled between class iterations; returning true halts the scan
     */
    public void search(SearchIndex index, SearchQuery query,
                       Consumer<SearchResult> consumer, BooleanSupplier cancelCheck) {
        if (index == null || query.isEmpty()) return;
        if (consumer == null) return;
        BooleanSupplier cancel = cancelCheck == null ? () -> false : cancelCheck;
        switch (query.mode()) {
            case STRINGS -> searchStrings(index, query, consumer, cancel);
            case NAMES -> searchNames(index, query, consumer, cancel);
            case BYTECODE -> searchBytecode(index, query, consumer, cancel);
            case REGEX -> searchRegex(index, query, consumer, cancel);
            case NUMBERS -> searchNumbers(index, query, consumer, cancel);
            case COMMENTS -> searchComments(index, query, consumer, cancel);
        }
    }

    /**
     * Scan every method body for numeric literals matching {@code query.text()}.
     * The query parses as int / long / float / double ({@code 42}, {@code 0xDEAD},
     * {@code 3.14f}, {@code 1L}). Matches include ICONST, BIPUSH, SIPUSH, LDC and IINC.
     */
    private void searchNumbers(SearchIndex index, SearchQuery q,
                               Consumer<SearchResult> out, BooleanSupplier cancel) {
        if (!q.searchClasses()) return;
        Number target = parseNumericQuery(q.text());
        if (target == null) return;

        for (var entry : index.jar().classes()) {
            if (cancel.getAsBoolean()) return;
            if (!packageMatches(entry.name(), q)) continue;
            scanClassForNumeric(entry, target, out);
        }
    }

    private static void scanClassForNumeric(
            dev.share.bytecodelens.model.ClassEntry entry, Number target, Consumer<SearchResult> out) {
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        try {
            new org.objectweb.asm.ClassReader(entry.bytes()).accept(node, org.objectweb.asm.ClassReader.SKIP_FRAMES);
        } catch (Throwable ex) { return; }
        if (node.methods == null) return;
        for (var m : node.methods) {
            if (m.instructions == null) continue;
            int line = 0;
            for (var insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof org.objectweb.asm.tree.LineNumberNode ln) { line = ln.line; continue; }
                Number value = literalValue(insn);
                if (value == null) continue;
                if (numberEquals(value, target)) {
                    String label = "method " + m.name + " — " + value;
                    out.accept(new SearchResult(SearchResult.TargetKind.CLASS_BYTECODE,
                            entry.name(), m.name, line, String.valueOf(value), 0,
                            String.valueOf(value).length(), label));
                }
            }
        }
    }

    /** Extract the numeric value from any instruction that pushes a literal number, else null. */
    private static Number literalValue(org.objectweb.asm.tree.AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op >= org.objectweb.asm.Opcodes.ICONST_M1 && op <= org.objectweb.asm.Opcodes.ICONST_5) {
            return op - org.objectweb.asm.Opcodes.ICONST_0;
        }
        if (op == org.objectweb.asm.Opcodes.LCONST_0) return 0L;
        if (op == org.objectweb.asm.Opcodes.LCONST_1) return 1L;
        if (op == org.objectweb.asm.Opcodes.FCONST_0) return 0f;
        if (op == org.objectweb.asm.Opcodes.FCONST_1) return 1f;
        if (op == org.objectweb.asm.Opcodes.FCONST_2) return 2f;
        if (op == org.objectweb.asm.Opcodes.DCONST_0) return 0d;
        if (op == org.objectweb.asm.Opcodes.DCONST_1) return 1d;
        if (insn instanceof org.objectweb.asm.tree.IntInsnNode i) return i.operand;
        if (insn instanceof org.objectweb.asm.tree.IincInsnNode i) return i.incr;
        if (insn instanceof org.objectweb.asm.tree.LdcInsnNode l && l.cst instanceof Number n) return n;
        return null;
    }

    /**
     * Tolerant numeric equality. Because ASM stores {@code ldc 0xDEADBEEF} as a (signed)
     * Integer while the user's query parses as a positive long, we also compare the low
     * 32 bits directly — this lets "0xDEADBEEF" match that LDC.
     */
    private static boolean numberEquals(Number a, Number b) {
        if (a instanceof Double || b instanceof Double || a instanceof Float || b instanceof Float) {
            return a.doubleValue() == b.doubleValue();
        }
        long la = a.longValue();
        long lb = b.longValue();
        if (la == lb) return true;
        // 32-bit two's-complement bridge — the sign-extended lookup vs hex-literal case.
        return (la & 0xFFFFFFFFL) == (lb & 0xFFFFFFFFL);
    }

    static Number parseNumericQuery(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.isEmpty()) return null;
        try {
            // Hex prefix wins: "0xDEADBEEF" must not be parsed as a float just because it
            // ends in 'F'.
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return Long.parseLong(s.substring(2), 16);
            }
            if (s.endsWith("L") || s.endsWith("l")) return Long.parseLong(s.substring(0, s.length() - 1));
            if (s.endsWith("F") || s.endsWith("f")) return Float.parseFloat(s.substring(0, s.length() - 1));
            if (s.endsWith("D") || s.endsWith("d")) return Double.parseDouble(s.substring(0, s.length() - 1));
            if (s.contains(".") || s.contains("e") || s.contains("E")) return Double.parseDouble(s);
            return Long.parseLong(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void searchStrings(SearchIndex index, SearchQuery q,
                               Consumer<SearchResult> out, BooleanSupplier cancel) {
        Pattern p = compileForText(q.text(), q);

        if (q.searchClasses()) {
            for (var cs : index.classStrings()) {
                if (cancel.getAsBoolean()) return;
                if (!packageMatches(cs.classFqn(), q)) continue;
                int line = 0;
                for (String s : cs.strings()) {
                    line++;
                    Matcher m = p.matcher(s);
                    if (m.find()) {
                        out.accept(new SearchResult(
                                SearchResult.TargetKind.CLASS_STRING,
                                cs.classFqn(), simpleName(cs.classFqn()),
                                line, s, m.start(), m.end(),
                                "CP string"));
                    }
                }
            }
        }

        if (q.searchResources()) {
            for (var rt : index.resourceTexts()) {
                if (cancel.getAsBoolean()) return;
                searchLinesIn(rt.path(), rt.path(), rt.text(), p,
                        SearchResult.TargetKind.RESOURCE_TEXT, out);
            }
        }
    }

    private void searchNames(SearchIndex index, SearchQuery q,
                             Consumer<SearchResult> out, BooleanSupplier cancel) {
        if (!q.searchClasses()) return;
        Pattern p = compileForText(q.text(), q);

        for (var cn : index.classNames()) {
            if (cancel.getAsBoolean()) return;
            if (!packageMatches(cn.classFqn(), q)) continue;

            Matcher m1 = p.matcher(cn.classFqn());
            if (m1.find()) {
                out.accept(new SearchResult(
                        SearchResult.TargetKind.CLASS_NAME,
                        cn.classFqn(), simpleName(cn.classFqn()),
                        0, cn.classFqn(), m1.start(), m1.end(), "class"));
            }
            int line = 0;
            for (String name : cn.methodNames()) {
                line++;
                Matcher m = p.matcher(name);
                if (m.find()) {
                    out.accept(new SearchResult(
                            SearchResult.TargetKind.CLASS_METHOD,
                            cn.classFqn(), simpleName(cn.classFqn()),
                            line, name, m.start(), m.end(), "method"));
                }
            }
            line = 0;
            for (String name : cn.fieldNames()) {
                line++;
                Matcher m = p.matcher(name);
                if (m.find()) {
                    out.accept(new SearchResult(
                            SearchResult.TargetKind.CLASS_FIELD,
                            cn.classFqn(), simpleName(cn.classFqn()),
                            line, name, m.start(), m.end(), "field"));
                }
            }
        }
    }

    private void searchBytecode(SearchIndex index, SearchQuery q,
                                Consumer<SearchResult> out, BooleanSupplier cancel) {
        if (!q.searchClasses()) return;
        Pattern p = compileForText(q.text(), q);

        for (var cs : index.classStrings()) {
            if (cancel.getAsBoolean()) return;
            if (!packageMatches(cs.classFqn(), q)) continue;
            String bc = index.bytecodeOf(cs.classFqn());
            if (bc.isEmpty()) continue;
            searchLinesIn(cs.classFqn(), simpleName(cs.classFqn()), bc, p,
                    SearchResult.TargetKind.CLASS_BYTECODE, out);
        }
    }

    private void searchRegex(SearchIndex index, SearchQuery q,
                             Consumer<SearchResult> out, BooleanSupplier cancel) {
        Pattern p;
        try {
            int flags = q.caseSensitive() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            p = Pattern.compile(q.text(), flags);
        } catch (PatternSyntaxException ex) {
            return;
        }

        if (q.searchClasses()) {
            for (var cs : index.classStrings()) {
                if (cancel.getAsBoolean()) return;
                if (!packageMatches(cs.classFqn(), q)) continue;
                int line = 0;
                for (String s : cs.strings()) {
                    line++;
                    Matcher m = p.matcher(s);
                    if (m.find()) {
                        out.accept(new SearchResult(
                                SearchResult.TargetKind.CLASS_STRING,
                                cs.classFqn(), simpleName(cs.classFqn()),
                                line, s, m.start(), m.end(), "CP string"));
                    }
                }
            }
        }

        if (q.searchResources()) {
            for (var rt : index.resourceTexts()) {
                if (cancel.getAsBoolean()) return;
                searchLinesIn(rt.path(), rt.path(), rt.text(), p,
                        SearchResult.TargetKind.RESOURCE_TEXT, out);
            }
        }
    }

    /**
     * Scan every user comment attached to the workspace. Classes filtered through the
     * same package include/exclude rules — so "show me all Kotlin coroutine hints in my
     * package" works when you typed them into comments.
     */
    private void searchComments(SearchIndex index, SearchQuery q,
                                Consumer<SearchResult> out, BooleanSupplier cancel) {
        Pattern p = compileForText(q.text(), q);
        for (var ce : index.comments()) {
            if (cancel.getAsBoolean()) return;
            if (!packageMatches(ce.classFqn(), q)) continue;
            Matcher m = p.matcher(ce.text());
            if (!m.find()) continue;
            String simple = simpleName(ce.classFqn());
            String context = switch (ce.kind()) {
                case "class" -> "class comment";
                case "method" -> "method " + ce.memberName() + "() comment";
                case "field" -> "field " + ce.memberName() + " comment";
                default -> "comment";
            };
            out.accept(new SearchResult(
                    SearchResult.TargetKind.COMMENT,
                    ce.classFqn(), simple,
                    0, ce.text(), m.start(), m.end(), context));
        }
    }

    private static void searchLinesIn(String path, String label, String text, Pattern p,
                                      SearchResult.TargetKind kind, Consumer<SearchResult> out) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher m = p.matcher(line);
            if (m.find()) {
                out.accept(new SearchResult(kind, path, label, i + 1,
                        line, m.start(), m.end(), ""));
            }
        }
    }

    private static Pattern compileForText(String text, SearchQuery q) {
        String regex = Pattern.quote(text);
        if (q.wholeWord()) {
            regex = "\\b" + regex + "\\b";
        }
        int flags = q.caseSensitive() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        return Pattern.compile(regex, flags);
    }

    /**
     * Decision: include this class FQN or skip it. Combines:
     * <ul>
     *   <li>positive package filter ({@link SearchQuery#packageFilter()})</li>
     *   <li>negative exclusion list ({@link SearchQuery#excludedPackagesSafe()})</li>
     * </ul>
     * The positive filter is the old prefix-with-trailing-* form; exclusions use
     * {@link #packageExcluded}.
     */
    static boolean packageMatches(String fqn, SearchQuery q) {
        String filter = q == null ? null : q.packageFilter();
        if (filter != null && !filter.isBlank()) {
            String f = filter.trim();
            if (f.endsWith("*")) f = f.substring(0, f.length() - 1);
            if (!fqn.startsWith(f)) return false;
        }
        if (q != null && packageExcluded(fqn, q.excludedPackagesSafe())) return false;
        return true;
    }

    /**
     * Glob-matching exclusion test. Each pattern is either a plain prefix
     * ({@code com.google}) or prefix-with-star ({@code org.jetbrains.*}). An entry
     * exactly equal to the FQN excludes the class itself; a pattern ending in {@code *}
     * also excludes sub-packages. Package-private for tests.
     */
    static boolean packageExcluded(String fqn, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return false;
        for (String raw : patterns) {
            if (raw == null) continue;
            String p = raw.trim();
            if (p.isEmpty()) continue;
            boolean star = p.endsWith("*");
            if (star) p = p.substring(0, p.length() - 1);
            // Strip trailing dot so "com.foo." and "com.foo" both behave sanely.
            if (p.endsWith(".")) p = p.substring(0, p.length() - 1);
            if (p.isEmpty()) continue;
            // Star mode: match FQN starting with p followed by '.' (sub-package) or equal.
            // Plain mode: match FQN that IS the class / is directly inside the package.
            if (fqn.equals(p)) return true;
            if (fqn.startsWith(p + ".")) return true;
            // Plain (no star) matches only exact class or direct children — same behaviour
            // as star for our use case. We keep both shapes accepted so users can type
            // either form.
            if (!star) continue;
        }
        return false;
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
