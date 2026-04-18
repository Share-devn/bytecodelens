package dev.share.bytecodelens.hierarchy;

import dev.share.bytecodelens.model.ClassEntry;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds all classes that override a given method by walking the subtype tree
 * (subclasses + interface implementers) of the declaring class and inspecting
 * each subtype's declared methods for an exact name+desc match.
 *
 * <p>Pure / stateless — caller supplies the {@link HierarchyIndex} for subtype
 * lookup. Returns owners as JVM-internal names ("p/Sub").</p>
 *
 * <p>Static methods, private methods, constructors and the class initializer are
 * skipped — none of those participate in dynamic dispatch in the JVM, so the
 * notion of "override" doesn't apply.</p>
 */
public final class OverriderSearch {

    private OverriderSearch() {}

    public record Overrider(String ownerInternal, int access) {
        public boolean isAbstract() { return (access & Opcodes.ACC_ABSTRACT) != 0; }
    }

    /**
     * Find every subtype of {@code declaringClassInternal} that declares a method
     * with the exact same {@code name+desc}. Excludes the declaring class itself.
     *
     * @return ordered list of overriders (deterministic by internal name)
     */
    public static List<Overrider> findOverriders(
            HierarchyIndex hierarchy, String declaringClassInternal,
            String methodName, String methodDesc) {
        if (hierarchy == null || declaringClassInternal == null
                || methodName == null || methodDesc == null) return List.of();
        if (isUndispatched(methodName)) return List.of();

        // Collect every subtype using BFS over the existing subtype tree.
        HierarchyNode root = hierarchy.buildSubtypeTree(declaringClassInternal);
        Set<String> subtypes = new HashSet<>();
        collect(root, subtypes);
        subtypes.remove(declaringClassInternal);

        List<Overrider> out = new ArrayList<>();
        java.util.TreeSet<String> sorted = new java.util.TreeSet<>(subtypes);
        for (String name : sorted) {
            ClassEntry entry = hierarchy.resolveEntry(name);
            if (entry == null) continue; // external class — can't inspect its methods
            ClassNode node;
            try {
                node = new ClassNode();
                new ClassReader(entry.bytes()).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            } catch (Exception ex) {
                continue;
            }
            if (node.methods == null) continue;
            for (MethodNode m : node.methods) {
                if (!m.name.equals(methodName)) continue;
                if (!m.desc.equals(methodDesc)) continue;
                if ((m.access & Opcodes.ACC_STATIC) != 0) continue;
                if ((m.access & Opcodes.ACC_PRIVATE) != 0) continue;
                out.add(new Overrider(name, m.access));
                break;
            }
        }
        return out;
    }

    /**
     * Find every subtype of {@code interfaceInternal} (subinterfaces + implementing
     * classes), regardless of whether they re-declare a particular method. Useful
     * for "implementers of this interface" right-click action on a class node.
     */
    public static List<String> findImplementers(HierarchyIndex hierarchy, String interfaceInternal) {
        if (hierarchy == null || interfaceInternal == null) return List.of();
        HierarchyNode root = hierarchy.buildSubtypeTree(interfaceInternal);
        Set<String> all = new HashSet<>();
        collect(root, all);
        all.remove(interfaceInternal);
        return new ArrayList<>(new java.util.TreeSet<>(all));
    }

    private static void collect(HierarchyNode n, Set<String> sink) {
        if (n == null) return;
        sink.add(n.internalName());
        if (n.children() != null) {
            for (HierarchyNode c : n.children()) collect(c, sink);
        }
    }

    private static boolean isUndispatched(String name) {
        // <init> and <clinit> aren't virtually dispatched; skip them outright.
        return "<init>".equals(name) || "<clinit>".equals(name);
    }
}
