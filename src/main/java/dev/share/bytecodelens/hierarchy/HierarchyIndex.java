package dev.share.bytecodelens.hierarchy;

import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.LoadedJar;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class HierarchyIndex {

    private static final Logger log = LoggerFactory.getLogger(HierarchyIndex.class);

    private final LoadedJar jar;
    private final Map<String, String> superByClass = new HashMap<>();
    private final Map<String, List<String>> interfacesByClass = new HashMap<>();
    private final Map<String, List<String>> subclassesBySuper = new HashMap<>();
    private final Map<String, List<String>> implementersByInterface = new HashMap<>();
    private final Map<String, ClassEntry> byInternalName = new HashMap<>();

    public HierarchyIndex(LoadedJar jar) {
        this.jar = jar;
    }

    public void build() {
        long start = System.currentTimeMillis();
        for (ClassEntry c : jar.classes()) {
            byInternalName.put(c.internalName(), c);
            if (c.superName() != null) {
                superByClass.put(c.internalName(), c.superName());
                subclassesBySuper.computeIfAbsent(c.superName(), k -> new ArrayList<>())
                        .add(c.internalName());
            }
            if (!c.interfaces().isEmpty()) {
                interfacesByClass.put(c.internalName(), c.interfaces());
                for (String iface : c.interfaces()) {
                    implementersByInterface.computeIfAbsent(iface, k -> new ArrayList<>())
                            .add(c.internalName());
                }
            }
        }
        log.info("Hierarchy index built in {}ms: {} classes indexed",
                System.currentTimeMillis() - start, byInternalName.size());
    }

    /** Build the chain of ancestors from Object down to the target class. */
    public HierarchyNode buildAncestorChain(String internalName) {
        List<String> chain = new ArrayList<>();
        chain.add(internalName);
        String current = internalName;
        Set<String> visited = new HashSet<>();
        visited.add(current);
        while (true) {
            String sup = superByClass.get(current);
            if (sup == null) break;
            if (!visited.add(sup)) break;
            chain.add(sup);
            current = sup;
            // Stop if we reached external (not in jar)
            if (!byInternalName.containsKey(sup)) break;
        }
        // Reverse so Object is at top
        Collections.reverse(chain);
        HierarchyNode root = null;
        HierarchyNode parent = null;
        for (String name : chain) {
            var children = new ArrayList<HierarchyNode>();
            HierarchyNode node = new HierarchyNode(name, kindOf(name), children);
            if (root == null) root = node;
            if (parent != null) parent.children().add(node);
            parent = node;
        }
        return root == null
                ? new HierarchyNode(internalName, kindOf(internalName), new ArrayList<>())
                : root;
    }

    /** Build the tree of subtypes rooted at the target class. */
    public HierarchyNode buildSubtypeTree(String internalName) {
        Set<String> visited = new HashSet<>();
        return buildSubtypeTreeRec(internalName, visited);
    }

    private HierarchyNode buildSubtypeTreeRec(String internalName, Set<String> visited) {
        List<HierarchyNode> children = new ArrayList<>();
        if (visited.add(internalName)) {
            // Direct subclasses via extends
            List<String> subs = subclassesBySuper.getOrDefault(internalName, List.of());
            List<String> impls = implementersByInterface.getOrDefault(internalName, List.of());
            Set<String> allChildren = new java.util.TreeSet<>();
            allChildren.addAll(subs);
            allChildren.addAll(impls);
            for (String child : allChildren) {
                children.add(buildSubtypeTreeRec(child, visited));
            }
        }
        return new HierarchyNode(internalName, kindOf(internalName), children);
    }

    public List<String> directImplementers(String interfaceName) {
        return implementersByInterface.getOrDefault(interfaceName, List.of());
    }

    public List<String> directInterfaces(String internalName) {
        return interfacesByClass.getOrDefault(internalName, List.of());
    }

    public ClassEntry resolveEntry(String internalName) {
        return byInternalName.get(internalName);
    }

    public boolean isInJar(String internalName) {
        return byInternalName.containsKey(internalName);
    }

    private HierarchyNode.Kind kindOf(String internalName) {
        ClassEntry entry = byInternalName.get(internalName);
        if (entry == null) return HierarchyNode.Kind.EXTERNAL;
        int acc = entry.access();
        if ((acc & Opcodes.ACC_ANNOTATION) != 0) return HierarchyNode.Kind.ANNOTATION;
        if ((acc & Opcodes.ACC_INTERFACE) != 0) return HierarchyNode.Kind.INTERFACE;
        if ((acc & Opcodes.ACC_ENUM) != 0) return HierarchyNode.Kind.ENUM;
        if ((acc & Opcodes.ACC_RECORD) != 0) return HierarchyNode.Kind.RECORD;
        if ((acc & Opcodes.ACC_ABSTRACT) != 0) return HierarchyNode.Kind.ABSTRACT_CLASS;
        return HierarchyNode.Kind.CLASS;
    }
}
