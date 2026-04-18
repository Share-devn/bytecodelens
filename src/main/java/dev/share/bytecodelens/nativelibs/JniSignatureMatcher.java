package dev.share.bytecodelens.nativelibs;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Decode JNI-mangled symbol names into Java class/method references.
 *
 * <p>The JNI name-mangling scheme is documented in the JNI spec §"Resolving Native
 * Method Names". Shape:</p>
 * <pre>
 *   Java_&lt;package with _ separators&gt;_&lt;class&gt;_&lt;method&gt;[__&lt;desc&gt;]
 * </pre>
 * <p>Trailing {@code __&lt;desc&gt;} appears for overloaded methods (mangled arg types).
 * Underscores inside original names are escaped: {@code _} → {@code _1}, {@code /} →
 * {@code _}, {@code ;} → {@code _2}, {@code [} → {@code _3}, Unicode → {@code _0XXXX}.</p>
 */
public final class JniSignatureMatcher {

    /**
     * Parsed pieces of a JNI name. {@code methodDescriptor} is {@code null} if the
     * symbol lacks an overload suffix — the JVM only appends one for overloaded natives.
     */
    public record Parsed(String classFqn, String methodName, String methodDescriptor) {}

    /**
     * Parse {@code Java_*} symbol into class+method. Returns {@code null} on malformed
     * input — the caller knows the symbol isn't a JNI native.
     */
    public static Parsed parse(String mangled) {
        if (mangled == null || !mangled.startsWith("Java_")) return null;
        String rest = mangled.substring(5);
        // Split off optional "__<descriptor>" overload suffix. The descriptor half
        // can't contain "__" unmodified (the mangling doubles it), so a single
        // "__" split finds it unambiguously.
        String desc = null;
        int suffix = rest.indexOf("__");
        if (suffix >= 0) {
            desc = unmangle(rest.substring(suffix + 2));
            rest = rest.substring(0, suffix);
        }
        String decoded = unmangle(rest);
        // The last "/" (was "_") separates method from class. Except if the class has
        // no package — then "/" might be absent and the last segment is still the
        // method. We split on the LAST slash.
        int slash = decoded.lastIndexOf('/');
        String classPart;
        String methodName;
        if (slash < 0) {
            // Unusual: method on the default package. After unmangle, the owner is just
            // the class name and the method is what follows the unescaped last "_",
            // but we can't recover that here — so we return whole string as class.
            return null;
        }
        classPart = decoded.substring(0, slash);
        methodName = decoded.substring(slash + 1);
        if (classPart.isEmpty() || methodName.isEmpty()) return null;
        // JNI uses "/" as package separator internally, but we want dotted form for
        // matching against ClassEntry.name().
        return new Parsed(classPart.replace('/', '.'), methodName, desc);
    }

    /**
     * Reverse the JNI underscore escape rules. Scans sequentially so overlapping
     * patterns (e.g. "_1" followed by "0XXXX") resolve in the intended order.
     */
    static String unmangle(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '_') {
                out.append(c);
                i++;
                continue;
            }
            if (i + 1 >= s.length()) {
                // Dangling "_" at the end — treat as "/".
                out.append('/');
                i++;
                continue;
            }
            char n = s.charAt(i + 1);
            switch (n) {
                case '1' -> { out.append('_'); i += 2; }
                case '2' -> { out.append(';'); i += 2; }
                case '3' -> { out.append('['); i += 2; }
                case '0' -> {
                    // 4-digit hex unicode escape. If the 4 digits aren't there, fall back
                    // to treating as "/" (unknown escape).
                    if (i + 6 <= s.length()) {
                        try {
                            int cp = Integer.parseInt(s.substring(i + 2, i + 6), 16);
                            out.append((char) cp);
                            i += 6;
                        } catch (NumberFormatException ex) {
                            out.append('/');
                            i++;
                        }
                    } else {
                        out.append('/');
                        i++;
                    }
                }
                default -> { out.append('/'); i++; }
            }
        }
        return out.toString();
    }

    /**
     * Filter {@code symbols} down to the ones that look like JNI natives, and optionally
     * cross-reference against {@code knownClasses} to mark which correspond to classes
     * in the loaded workspace.
     */
    public static List<JniHit> findJniSymbols(List<String> symbols, Set<String> knownClasses) {
        List<JniHit> hits = new ArrayList<>();
        if (symbols == null) return hits;
        for (String sym : symbols) {
            Parsed p = parse(sym);
            if (p == null) continue;
            boolean matchesWorkspace = knownClasses != null && knownClasses.contains(p.classFqn());
            hits.add(new JniHit(sym, p, matchesWorkspace));
        }
        return hits;
    }

    public record JniHit(String mangledSymbol, Parsed parsed, boolean matchesWorkspaceClass) {}

    private JniSignatureMatcher() {}
}
