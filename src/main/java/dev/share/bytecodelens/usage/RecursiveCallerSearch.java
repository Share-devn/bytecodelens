package dev.share.bytecodelens.usage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a callers-of-callers tree: starting from a target method, walk
 * {@link UsageIndex#findUsages} backwards N levels and return a structured tree.
 *
 * <p>Cycles are broken by tracking visited methods — a method that appears more
 * than once in a path becomes a leaf with {@link Node#cyclic} = true. Default
 * depth cap is 5, which keeps even densely-connected codebases from producing
 * trees the user can't navigate.</p>
 */
public final class RecursiveCallerSearch {

    public static final int DEFAULT_MAX_DEPTH = 5;
    public static final int DEFAULT_MAX_CALLERS_PER_NODE = 50;

    private final UsageIndex index;
    private final int maxDepth;
    private final int maxCallersPerNode;

    public RecursiveCallerSearch(UsageIndex index) {
        this(index, DEFAULT_MAX_DEPTH, DEFAULT_MAX_CALLERS_PER_NODE);
    }

    public RecursiveCallerSearch(UsageIndex index, int maxDepth, int maxCallersPerNode) {
        if (index == null) throw new IllegalArgumentException("index is null");
        if (maxDepth <= 0) throw new IllegalArgumentException("maxDepth must be > 0");
        this.index = index;
        this.maxDepth = maxDepth;
        this.maxCallersPerNode = maxCallersPerNode;
    }

    /** Tree node — methodKey identifies the method, callers are the parents above it. */
    public static final class Node {
        public final String methodKey;       // owner#name+desc
        public final String ownerInternal;
        public final String name;
        public final String desc;
        public final int line;               // line at which this node calls its child (0 for root)
        public final boolean cyclic;
        public final List<Node> callers = new ArrayList<>();

        Node(String methodKey, String owner, String name, String desc, int line, boolean cyclic) {
            this.methodKey = methodKey;
            this.ownerInternal = owner;
            this.name = name;
            this.desc = desc;
            this.line = line;
            this.cyclic = cyclic;
        }
    }

    public Node build(String ownerInternal, String name, String desc) {
        if (ownerInternal == null || name == null || desc == null) return null;
        String rootKey = key(ownerInternal, name, desc);
        Node root = new Node(rootKey, ownerInternal, name, desc, 0, false);
        Set<String> path = new HashSet<>();
        path.add(rootKey);
        expand(root, ownerInternal, name, desc, path, 0);
        return root;
    }

    private void expand(Node parent, String ownerInternal, String name, String desc,
                        Set<String> path, int depth) {
        if (depth >= maxDepth) return;
        var callers = index.findUsages(new UsageTarget.Method(ownerInternal, name, desc));
        // Distinct callers (one node per (callerOwner, callerName, callerDesc) — we collapse
        // multiple call sites of the same caller into one row; the line number kept is the
        // first one for navigation.
        Map<String, CallSite> byCallerKey = new LinkedHashMap<>();
        for (CallSite cs : callers) {
            String k = key(cs.inClassFqn(), cs.inMethodName(), cs.inMethodDesc());
            byCallerKey.putIfAbsent(k, cs);
        }
        int added = 0;
        for (var e : byCallerKey.entrySet()) {
            if (added >= maxCallersPerNode) break;
            CallSite cs = e.getValue();
            String childKey = e.getKey();
            boolean cyclic = !path.add(childKey);
            Node child = new Node(childKey, cs.inClassFqn(), cs.inMethodName(),
                    cs.inMethodDesc(), cs.lineNumber(), cyclic);
            parent.callers.add(child);
            added++;
            if (!cyclic) {
                expand(child, cs.inClassFqn(), cs.inMethodName(), cs.inMethodDesc(),
                        path, depth + 1);
                path.remove(childKey);
            }
        }
    }

    private static String key(String owner, String name, String desc) {
        return owner + "#" + name + desc;
    }
}
