package dev.share.bytecodelens.mapping;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serialises a {@link MappingModel} back into any of the supported file formats.
 *
 * <p>Note on direction: the model stores {@code obfuscated -> original}. Formats that natively
 * use the opposite direction (ProGuard) are inverted during write.</p>
 */
public final class MappingWriter {

    private MappingWriter() {}

    public static void write(MappingModel model, MappingFormat format, Path dest) throws IOException {
        try (Writer w = Files.newBufferedWriter(dest)) {
            write(model, format, w);
        }
    }

    public static void write(MappingModel model, MappingFormat format, Writer out) throws IOException {
        switch (format) {
            case PROGUARD -> writeProGuard(model, out);
            case TINY_V1 -> writeTinyV1(model, out);
            case TINY_V2 -> writeTinyV2(model, out);
            case SRG -> writeSrg(model, out);
            case XSRG -> writeXsrg(model, out);
            case TSRG -> writeTsrg(model, out);
            case TSRG_V2 -> writeTsrgV2(model, out);
            case CSRG -> writeCsrg(model, out);
            case JOBF -> writeJobf(model, out);
            case ENIGMA -> writeEnigma(model, out);
            case RECAF -> writeRecaf(model, out);
        }
    }

    // --- Tiny v1 --------------------------------------------------------------

    private static void writeTinyV1(MappingModel model, Writer out) throws IOException {
        out.write("v1\tofficial\tnamed\n");
        var owners = new java.util.TreeSet<>(model.classMap().keySet());
        for (String owner : owners) {
            out.write("CLASS\t" + owner + "\t" + model.classMap().get(owner) + "\n");
        }
        for (var e : model.fieldMap().entrySet()) {
            String full = e.getKey();
            int dot = full.indexOf('.'); int colon = full.lastIndexOf(':');
            if (dot < 0 || colon < 0) continue;
            String owner = full.substring(0, dot);
            String name = full.substring(dot + 1, colon);
            String desc = full.substring(colon + 1);
            out.write("FIELD\t" + owner + "\t" + desc + "\t" + name + "\t" + e.getValue() + "\n");
        }
        for (var e : model.methodMap().entrySet()) {
            String full = e.getKey();
            int dot = full.indexOf('.'); int lparen = full.indexOf('(');
            if (dot < 0 || lparen < 0) continue;
            String owner = full.substring(0, dot);
            String name = full.substring(dot + 1, lparen);
            String desc = full.substring(lparen);
            out.write("METHOD\t" + owner + "\t" + desc + "\t" + name + "\t" + e.getValue() + "\n");
        }
    }

    // --- XSRG -----------------------------------------------------------------

    private static void writeXsrg(MappingModel model, Writer out) throws IOException {
        // Same as SRG but FD lines carry descriptors on both sides.
        for (var e : new java.util.TreeMap<>(model.classMap()).entrySet()) {
            out.write("CL: " + e.getKey() + " " + e.getValue() + "\n");
        }
        for (var e : model.fieldMap().entrySet()) {
            String full = e.getKey();
            int dot = full.indexOf('.'); int colon = full.lastIndexOf(':');
            if (dot < 0 || colon < 0) continue;
            String owner = full.substring(0, dot);
            String obfName = full.substring(dot + 1, colon);
            String desc = full.substring(colon + 1);
            String namedOwner = model.classMap().getOrDefault(owner, owner);
            out.write("FD: " + owner + "/" + obfName + " " + desc + " "
                    + namedOwner + "/" + e.getValue() + " " + desc + "\n");
        }
        for (var e : model.methodMap().entrySet()) {
            String full = e.getKey();
            int dot = full.indexOf('.'); int lparen = full.indexOf('(');
            if (dot < 0 || lparen < 0) continue;
            String owner = full.substring(0, dot);
            String obfName = full.substring(dot + 1, lparen);
            String desc = full.substring(lparen);
            String namedOwner = model.classMap().getOrDefault(owner, owner);
            out.write("MD: " + owner + "/" + obfName + " " + desc + " "
                    + namedOwner + "/" + e.getValue() + " " + desc + "\n");
        }
    }

    // --- TSRG v2 --------------------------------------------------------------

    private static void writeTsrgV2(MappingModel model, Writer out) throws IOException {
        out.write("tsrg2 obf named\n");
        var owners = new java.util.TreeSet<String>();
        owners.addAll(model.classMap().keySet());
        Map<String, List<String>> members = new LinkedHashMap<>();
        for (var e : model.methodMap().entrySet()) {
            String full = e.getKey();
            int dot = full.indexOf('.'); int lparen = full.indexOf('(');
            if (dot < 0 || lparen < 0) continue;
            String owner = full.substring(0, dot);
            members.computeIfAbsent(owner, k -> new ArrayList<>())
                    .add("\t" + full.substring(dot + 1, lparen) + " "
                            + full.substring(lparen) + " " + e.getValue() + "\n");
            owners.add(owner);
        }
        for (var e : model.fieldMap().entrySet()) {
            String full = e.getKey();
            int dot = full.indexOf('.'); int colon = full.lastIndexOf(':');
            if (dot < 0 || colon < 0) continue;
            String owner = full.substring(0, dot);
            members.computeIfAbsent(owner, k -> new ArrayList<>())
                    .add("\t" + full.substring(dot + 1, colon) + " " + e.getValue() + "\n");
            owners.add(owner);
        }
        for (String owner : owners) {
            out.write(owner + " " + model.classMap().getOrDefault(owner, owner) + "\n");
            List<String> ml = members.get(owner);
            if (ml != null) for (String l : ml) out.write(l);
        }
    }

    // --- CSRG -----------------------------------------------------------------

    private static void writeCsrg(MappingModel model, Writer out) throws IOException {
        for (var e : new java.util.TreeMap<>(model.classMap()).entrySet()) {
            out.write(e.getKey() + " " + e.getValue() + "\n");
        }
        for (var e : model.fieldMap().entrySet()) {
            String full = e.getKey();
            int dot = full.indexOf('.'); int colon = full.lastIndexOf(':');
            if (dot < 0 || colon < 0) continue;
            out.write(full.substring(0, dot) + " " + full.substring(dot + 1, colon)
                    + " " + e.getValue() + "\n");
        }
        for (var e : model.methodMap().entrySet()) {
            String full = e.getKey();
            int dot = full.indexOf('.'); int lparen = full.indexOf('(');
            if (dot < 0 || lparen < 0) continue;
            out.write(full.substring(0, dot) + " " + full.substring(dot + 1, lparen)
                    + " " + full.substring(lparen) + " " + e.getValue() + "\n");
        }
    }

    // --- JOBF -----------------------------------------------------------------

    private static void writeJobf(MappingModel model, Writer out) throws IOException {
        for (var e : new java.util.TreeMap<>(model.classMap()).entrySet()) {
            out.write(".class_map " + e.getKey().replace('/', '.') + " " + e.getValue() + "\n");
        }
        for (var e : model.fieldMap().entrySet()) {
            String full = e.getKey();
            int dot = full.indexOf('.'); int colon = full.lastIndexOf(':');
            if (dot < 0 || colon < 0) continue;
            String owner = full.substring(0, dot).replace('/', '.');
            String name = full.substring(dot + 1, colon);
            out.write(".field_map " + owner + "." + name + " " + e.getValue() + "\n");
        }
        for (var e : model.methodMap().entrySet()) {
            String full = e.getKey();
            int dot = full.indexOf('.'); int lparen = full.indexOf('(');
            if (dot < 0 || lparen < 0) continue;
            String owner = full.substring(0, dot).replace('/', '.');
            String name = full.substring(dot + 1, lparen);
            String desc = full.substring(lparen);
            out.write(".method_map " + owner + "." + name + desc + " " + e.getValue() + "\n");
        }
    }

    // --- Recaf simple ---------------------------------------------------------

    private static void writeRecaf(MappingModel model, Writer out) throws IOException {
        for (var e : new java.util.TreeMap<>(model.classMap()).entrySet()) {
            out.write(e.getKey() + " " + e.getValue() + "\n");
        }
        for (var e : model.fieldMap().entrySet()) {
            String full = e.getKey();
            int dot = full.indexOf('.'); int colon = full.lastIndexOf(':');
            if (dot < 0 || colon < 0) continue;
            out.write(full.substring(0, dot) + "." + full.substring(dot + 1, colon)
                    + " " + e.getValue() + "\n");
        }
        for (var e : model.methodMap().entrySet()) {
            String full = e.getKey();
            int dot = full.indexOf('.'); int lparen = full.indexOf('(');
            if (dot < 0 || lparen < 0) continue;
            out.write(full.substring(0, dot) + "." + full.substring(dot + 1, lparen)
                    + full.substring(lparen) + " " + e.getValue() + "\n");
        }
    }

    // --- ProGuard -------------------------------------------------------------

    private static void writeProGuard(MappingModel model, Writer out) throws IOException {
        // ProGuard is "original -> obfuscated". Our model is the other way round; we emit
        // "<original> -> <obfuscated>" by inverting classMap.
        // For fields/methods we need the original field type/descriptor — we don't have it
        // reliably for non-ProGuard sources, so descriptors are printed as-is (internal form
        // post-conversion) where possible, or dropped.
        Map<String, List<String>> classMembers = groupMembersByObfuscatedOwner(model);

        // Iterate by class, sorted for determinism.
        List<Map.Entry<String, String>> classEntries = new ArrayList<>(model.classMap().entrySet());
        classEntries.sort(Map.Entry.comparingByValue()); // by original name
        for (var e : classEntries) {
            String obfInternal = e.getKey();
            String origInternal = e.getValue();
            out.write(origInternal.replace('/', '.'));
            out.write(" -> ");
            out.write(obfInternal.replace('/', '.'));
            out.write(":\n");

            List<String> members = classMembers.get(obfInternal);
            if (members == null) continue;
            for (String m : members) out.write(m);
        }
    }

    /** Build "    <type> <origName>[(params)] -> <obfName>" lines per owner class. */
    private static Map<String, List<String>> groupMembersByObfuscatedOwner(MappingModel model) {
        Map<String, List<String>> byOwner = new LinkedHashMap<>();
        for (var e : model.methodMap().entrySet()) {
            // key format: owner.name(args)return
            String full = e.getKey();
            int dot = full.indexOf('.');
            if (dot < 0) continue;
            String owner = full.substring(0, dot);
            String rest = full.substring(dot + 1);
            int lparen = rest.indexOf('(');
            if (lparen < 0) continue;
            String obfName = rest.substring(0, lparen);
            String desc = rest.substring(lparen);
            // Parse args + return from desc. If malformed, fallback to "void m() -> obf".
            String line = "    " + descriptorToProGuardMember(desc) + " "
                    + e.getValue() + stripArgs(desc) + " -> " + obfName + "\n";
            byOwner.computeIfAbsent(owner, k -> new ArrayList<>()).add(line);
        }
        for (var e : model.fieldMap().entrySet()) {
            // key format: owner.name:desc
            String full = e.getKey();
            int dot = full.indexOf('.');
            int colon = full.lastIndexOf(':');
            if (dot < 0 || colon < 0 || colon <= dot) continue;
            String owner = full.substring(0, dot);
            String obfName = full.substring(dot + 1, colon);
            String desc = full.substring(colon + 1);
            String line = "    " + descriptorToJavaType(desc) + " " + e.getValue()
                    + " -> " + obfName + "\n";
            byOwner.computeIfAbsent(owner, k -> new ArrayList<>()).add(line);
        }
        return byOwner;
    }

    /** Return type portion of "(...)ret" -> "ret". */
    private static String descriptorToProGuardMember(String desc) {
        int close = desc.lastIndexOf(')');
        if (close < 0) return "void";
        return descriptorToJavaType(desc.substring(close + 1));
    }

    /** "(II)V" -> "(int,int)"; returns empty for no-args / malformed. */
    private static String stripArgs(String desc) {
        int close = desc.lastIndexOf(')');
        if (close < 0) return "";
        String args = desc.substring(1, close);
        if (args.isEmpty()) return "()";
        StringBuilder sb = new StringBuilder("(");
        int i = 0;
        boolean first = true;
        while (i < args.length()) {
            int start = i;
            while (i < args.length() && args.charAt(i) == '[') i++;
            char c = args.charAt(i);
            if (c == 'L') {
                int semi = args.indexOf(';', i);
                i = semi + 1;
            } else {
                i++;
            }
            if (!first) sb.append(',');
            sb.append(descriptorToJavaType(args.substring(start, i)));
            first = false;
        }
        sb.append(')');
        return sb.toString();
    }

    /** "I" -> "int", "Ljava/lang/String;" -> "java.lang.String", "[I" -> "int[]". */
    static String descriptorToJavaType(String d) {
        int arr = 0;
        while (arr < d.length() && d.charAt(arr) == '[') arr++;
        String base = switch (d.charAt(arr)) {
            case 'Z' -> "boolean";
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'S' -> "short";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'F' -> "float";
            case 'D' -> "double";
            case 'V' -> "void";
            case 'L' -> d.substring(arr + 1, d.length() - 1).replace('/', '.');
            default -> "?";
        };
        return base + "[]".repeat(arr);
    }

    // --- Tiny v2 --------------------------------------------------------------

    private static void writeTinyV2(MappingModel model, Writer out) throws IOException {
        out.write("tiny\t2\t0\tofficial\tnamed\n");
        // Group members by obfuscated owner, then emit "c\t<obf>\t<named>" followed by members.
        Map<String, List<String>> methodLines = new LinkedHashMap<>();
        for (var e : model.methodMap().entrySet()) {
            String full = e.getKey();
            int dot = full.indexOf('.');
            if (dot < 0) continue;
            String owner = full.substring(0, dot);
            String rest = full.substring(dot + 1);
            int lparen = rest.indexOf('(');
            if (lparen < 0) continue;
            String obfName = rest.substring(0, lparen);
            String desc = rest.substring(lparen);
            methodLines.computeIfAbsent(owner, k -> new ArrayList<>())
                    .add("\tm\t" + desc + "\t" + obfName + "\t" + e.getValue() + "\n");
        }
        Map<String, List<String>> fieldLines = new LinkedHashMap<>();
        for (var e : model.fieldMap().entrySet()) {
            String full = e.getKey();
            int dot = full.indexOf('.');
            int colon = full.lastIndexOf(':');
            if (dot < 0 || colon < 0) continue;
            String owner = full.substring(0, dot);
            String obfName = full.substring(dot + 1, colon);
            String desc = full.substring(colon + 1);
            fieldLines.computeIfAbsent(owner, k -> new ArrayList<>())
                    .add("\tf\t" + desc + "\t" + obfName + "\t" + e.getValue() + "\n");
        }

        // Union of all known owners (may include classes with no member mappings).
        var owners = new java.util.TreeSet<String>();
        owners.addAll(model.classMap().keySet());
        owners.addAll(methodLines.keySet());
        owners.addAll(fieldLines.keySet());
        for (String owner : owners) {
            String named = model.classMap().getOrDefault(owner, owner);
            out.write("c\t" + owner + "\t" + named + "\n");
            List<String> mlines = methodLines.get(owner);
            if (mlines != null) for (String l : mlines) out.write(l);
            List<String> flines = fieldLines.get(owner);
            if (flines != null) for (String l : flines) out.write(l);
        }
    }

    // --- SRG ------------------------------------------------------------------

    private static void writeSrg(MappingModel model, Writer out) throws IOException {
        List<Map.Entry<String, String>> classes = new ArrayList<>(model.classMap().entrySet());
        classes.sort(Map.Entry.comparingByKey());
        for (var e : classes) {
            out.write("CL: " + e.getKey() + " " + e.getValue() + "\n");
        }
        for (var e : model.fieldMap().entrySet()) {
            String full = e.getKey();
            int dot = full.indexOf('.');
            int colon = full.lastIndexOf(':');
            if (dot < 0 || colon < 0) continue;
            String owner = full.substring(0, dot);
            String obfName = full.substring(dot + 1, colon);
            String namedOwner = model.classMap().getOrDefault(owner, owner);
            out.write("FD: " + owner + "/" + obfName + " "
                    + namedOwner + "/" + e.getValue() + "\n");
        }
        for (var e : model.methodMap().entrySet()) {
            String full = e.getKey();
            int dot = full.indexOf('.');
            if (dot < 0) continue;
            String owner = full.substring(0, dot);
            String rest = full.substring(dot + 1);
            int lparen = rest.indexOf('(');
            if (lparen < 0) continue;
            String obfName = rest.substring(0, lparen);
            String desc = rest.substring(lparen);
            String namedOwner = model.classMap().getOrDefault(owner, owner);
            out.write("MD: " + owner + "/" + obfName + " " + desc + " "
                    + namedOwner + "/" + e.getValue() + " " + desc + "\n");
        }
    }

    // --- TSRG -----------------------------------------------------------------

    private static void writeTsrg(MappingModel model, Writer out) throws IOException {
        var owners = new java.util.TreeSet<String>();
        owners.addAll(model.classMap().keySet());
        Map<String, List<String>> members = new LinkedHashMap<>();
        for (var e : model.methodMap().entrySet()) {
            String full = e.getKey();
            int dot = full.indexOf('.');
            if (dot < 0) continue;
            String owner = full.substring(0, dot);
            String rest = full.substring(dot + 1);
            int lparen = rest.indexOf('(');
            if (lparen < 0) continue;
            members.computeIfAbsent(owner, k -> new ArrayList<>())
                    .add("\t" + rest.substring(0, lparen) + " "
                            + rest.substring(lparen) + " " + e.getValue() + "\n");
            owners.add(owner);
        }
        for (var e : model.fieldMap().entrySet()) {
            String full = e.getKey();
            int dot = full.indexOf('.');
            int colon = full.lastIndexOf(':');
            if (dot < 0 || colon < 0) continue;
            String owner = full.substring(0, dot);
            String obfName = full.substring(dot + 1, colon);
            members.computeIfAbsent(owner, k -> new ArrayList<>())
                    .add("\t" + obfName + " " + e.getValue() + "\n");
            owners.add(owner);
        }
        for (String owner : owners) {
            String named = model.classMap().getOrDefault(owner, owner);
            out.write(owner + " " + named + "\n");
            List<String> lines = members.get(owner);
            if (lines != null) for (String l : lines) out.write(l);
        }
    }

    // --- Enigma ---------------------------------------------------------------

    private static void writeEnigma(MappingModel model, Writer out) throws IOException {
        // Enigma is hierarchical. We emit a flat top-level list — inner classes get their own
        // CLASS line with the full "Outer$Inner" form rather than re-nesting.
        List<String> owners = new ArrayList<>(model.classMap().keySet());
        owners.sort(Comparator.naturalOrder());
        Map<String, List<String>> members = new LinkedHashMap<>();
        for (var e : model.methodMap().entrySet()) {
            String full = e.getKey();
            int dot = full.indexOf('.');
            if (dot < 0) continue;
            String owner = full.substring(0, dot);
            String rest = full.substring(dot + 1);
            int lparen = rest.indexOf('(');
            if (lparen < 0) continue;
            String obfName = rest.substring(0, lparen);
            String desc = rest.substring(lparen);
            members.computeIfAbsent(owner, k -> new ArrayList<>())
                    .add("\tMETHOD " + obfName + " " + e.getValue() + " " + desc + "\n");
        }
        for (var e : model.fieldMap().entrySet()) {
            String full = e.getKey();
            int dot = full.indexOf('.');
            int colon = full.lastIndexOf(':');
            if (dot < 0 || colon < 0) continue;
            String owner = full.substring(0, dot);
            String obfName = full.substring(dot + 1, colon);
            String desc = full.substring(colon + 1);
            members.computeIfAbsent(owner, k -> new ArrayList<>())
                    .add("\tFIELD " + obfName + " " + e.getValue() + " " + desc + "\n");
        }

        for (String owner : owners) {
            String named = model.classMap().getOrDefault(owner, owner);
            out.write("CLASS " + owner + " " + named + "\n");
            List<String> lines = members.get(owner);
            if (lines != null) for (String l : lines) out.write(l);
        }
        // Also emit CLASS lines for owners with members but no class rename (edge case).
        for (String owner : members.keySet()) {
            if (!model.classMap().containsKey(owner)) {
                out.write("CLASS " + owner + " " + owner + "\n");
                for (String l : members.get(owner)) out.write(l);
            }
        }
    }
}
