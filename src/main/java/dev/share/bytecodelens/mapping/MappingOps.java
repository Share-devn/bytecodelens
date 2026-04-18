package dev.share.bytecodelens.mapping;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Diff and composition utilities for {@link MappingModel}.
 *
 * <ul>
 *   <li>{@link #diff} — returns a structured comparison of two models, listing entries
 *       added, removed, or whose target name changed. Useful for "what changed when I
 *       updated MappingsCommon.tiny?"</li>
 *   <li>{@link #compose} — chain two mappings A→B and B→C into a single A→C model.
 *       Useful for stacking refactor passes (vendor mapping + your manual renames).</li>
 *   <li>{@link #invert} — flip A→B into B→A, dropping entries that aren't bijective.</li>
 * </ul>
 *
 * <p>All operations are pure — inputs are immutable, outputs are new {@link MappingModel}s
 * or value-type {@link Diff} records.</p>
 */
public final class MappingOps {

    private MappingOps() {}

    public record Change(String key, String oldTarget, String newTarget) {}

    public record Diff(
            List<Change> classesAdded,
            List<Change> classesRemoved,
            List<Change> classesRenamed,
            List<Change> fieldsAdded,
            List<Change> fieldsRemoved,
            List<Change> fieldsRenamed,
            List<Change> methodsAdded,
            List<Change> methodsRemoved,
            List<Change> methodsRenamed
    ) {
        public int totalChanges() {
            return classesAdded.size() + classesRemoved.size() + classesRenamed.size()
                    + fieldsAdded.size() + fieldsRemoved.size() + fieldsRenamed.size()
                    + methodsAdded.size() + methodsRemoved.size() + methodsRenamed.size();
        }
    }

    public static Diff diff(MappingModel base, MappingModel updated) {
        if (base == null || updated == null) {
            throw new IllegalArgumentException("base and updated must not be null");
        }
        return new Diff(
                added(base.classMap(), updated.classMap()),
                removed(base.classMap(), updated.classMap()),
                renamed(base.classMap(), updated.classMap()),
                added(base.fieldMap(), updated.fieldMap()),
                removed(base.fieldMap(), updated.fieldMap()),
                renamed(base.fieldMap(), updated.fieldMap()),
                added(base.methodMap(), updated.methodMap()),
                removed(base.methodMap(), updated.methodMap()),
                renamed(base.methodMap(), updated.methodMap())
        );
    }

    private static List<Change> added(Map<String, String> base, Map<String, String> upd) {
        List<Change> out = new ArrayList<>();
        for (var k : new TreeSet<>(upd.keySet())) {
            if (!base.containsKey(k)) out.add(new Change(k, null, upd.get(k)));
        }
        return out;
    }

    private static List<Change> removed(Map<String, String> base, Map<String, String> upd) {
        List<Change> out = new ArrayList<>();
        for (var k : new TreeSet<>(base.keySet())) {
            if (!upd.containsKey(k)) out.add(new Change(k, base.get(k), null));
        }
        return out;
    }

    private static List<Change> renamed(Map<String, String> base, Map<String, String> upd) {
        List<Change> out = new ArrayList<>();
        for (var k : new TreeSet<>(base.keySet())) {
            String b = base.get(k);
            String u = upd.get(k);
            if (u != null && !u.equals(b)) out.add(new Change(k, b, u));
        }
        return out;
    }

    /**
     * Compose two mappings. Treat {@code first} as A→B and {@code second} as B→C;
     * returns A→C. Where {@code first} maps {@code a→b} and {@code second} maps {@code b→c},
     * we emit {@code a→c}. Entries in {@code first} whose target isn't in {@code second}
     * survive unchanged.
     */
    public static MappingModel compose(MappingModel first, MappingModel second) {
        if (first == null || second == null) {
            throw new IllegalArgumentException("both inputs must be non-null");
        }
        MappingModel.Builder out = MappingModel.builder(first.sourceFormat());

        // Classes — straightforward.
        for (var e : first.classMap().entrySet()) {
            String firstTarget = e.getValue();
            String chained = second.classMap().getOrDefault(firstTarget, firstTarget);
            out.mapClass(e.getKey(), chained);
        }
        // Also include classes that only appear in `second` (i.e. it knows about them
        // but the first mapping didn't touch them — keep them).
        for (var e : second.classMap().entrySet()) {
            if (!first.classMap().containsKey(e.getKey())
                    && !first.classMap().containsValue(e.getKey())) {
                out.mapClass(e.getKey(), e.getValue());
            }
        }

        // Fields — re-key into the second-namespace owner before lookup.
        for (var e : first.fieldMap().entrySet()) {
            String full = e.getKey();
            int dot = full.indexOf('.'); int colon = full.lastIndexOf(':');
            if (dot < 0 || colon < 0) continue;
            String owner = full.substring(0, dot);
            String name = full.substring(dot + 1, colon);
            String desc = full.substring(colon + 1);
            String firstTargetName = e.getValue();
            String namedOwner = first.classMap().getOrDefault(owner, owner);
            String secondKey = MappingModel.fieldKey(namedOwner, firstTargetName, desc);
            String chained = second.fieldMap().getOrDefault(secondKey, firstTargetName);
            out.mapField(owner, name, desc, chained);
        }
        // Methods — same pattern.
        for (var e : first.methodMap().entrySet()) {
            String full = e.getKey();
            int dot = full.indexOf('.'); int lparen = full.indexOf('(');
            if (dot < 0 || lparen < 0) continue;
            String owner = full.substring(0, dot);
            String name = full.substring(dot + 1, lparen);
            String desc = full.substring(lparen);
            String firstTargetName = e.getValue();
            String namedOwner = first.classMap().getOrDefault(owner, owner);
            String secondKey = MappingModel.methodKey(namedOwner, firstTargetName, desc);
            String chained = second.methodMap().getOrDefault(secondKey, firstTargetName);
            out.mapMethod(owner, name, desc, chained);
        }
        return out.build();
    }

    /**
     * Invert {@code A→B} into {@code B→A}. Entries where multiple keys point to the same
     * value are dropped (no unique inverse).
     */
    public static MappingModel invert(MappingModel m) {
        if (m == null) throw new IllegalArgumentException("model is null");
        MappingModel.Builder out = MappingModel.builder(m.sourceFormat());

        // Class map — bijection check.
        Map<String, String> reverseClass = new HashMap<>();
        Map<String, Integer> targetCounts = new HashMap<>();
        for (var e : m.classMap().entrySet()) {
            targetCounts.merge(e.getValue(), 1, Integer::sum);
        }
        for (var e : m.classMap().entrySet()) {
            if (targetCounts.get(e.getValue()) == 1) reverseClass.put(e.getValue(), e.getKey());
        }
        for (var e : reverseClass.entrySet()) out.mapClass(e.getKey(), e.getValue());

        // Field map — invert per (owner, name). After inversion, owner becomes the renamed
        // target's owner. Drop ambiguous entries.
        for (var e : m.fieldMap().entrySet()) {
            String full = e.getKey();
            int dot = full.indexOf('.'); int colon = full.lastIndexOf(':');
            if (dot < 0 || colon < 0) continue;
            String owner = full.substring(0, dot);
            String name = full.substring(dot + 1, colon);
            String desc = full.substring(colon + 1);
            String namedOwner = reverseClass.getOrDefault(m.classMap().getOrDefault(owner, owner), owner);
            // After inversion: namedOwner.<value> -> name
            out.mapField(namedOwner, e.getValue(), desc, name);
        }
        for (var e : m.methodMap().entrySet()) {
            String full = e.getKey();
            int dot = full.indexOf('.'); int lparen = full.indexOf('(');
            if (dot < 0 || lparen < 0) continue;
            String owner = full.substring(0, dot);
            String name = full.substring(dot + 1, lparen);
            String desc = full.substring(lparen);
            String namedOwner = reverseClass.getOrDefault(m.classMap().getOrDefault(owner, owner), owner);
            out.mapMethod(namedOwner, e.getValue(), desc, name);
        }
        return out.build();
    }
}
