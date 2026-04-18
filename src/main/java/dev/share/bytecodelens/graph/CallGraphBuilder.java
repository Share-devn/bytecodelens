package dev.share.bytecodelens.graph;

import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.LoadedJar;
import dev.share.bytecodelens.usage.CallSite;
import dev.share.bytecodelens.usage.UsageIndex;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class CallGraphBuilder {

    private final UsageIndex usageIndex;
    private final Map<String, ClassEntry> byInternalName = new HashMap<>();

    public CallGraphBuilder(UsageIndex usageIndex) {
        this.usageIndex = usageIndex;
        LoadedJar jar = usageIndex == null ? null : usageIndex.jar();
        if (jar != null) {
            for (ClassEntry c : jar.classes()) {
                byInternalName.put(c.internalName(), c);
            }
        }
    }

    private void enrichNode(GraphNode n) {
        if (n.fqn() == null) return;
        ClassEntry entry = byInternalName.get(n.fqn().replace('.', '/'));
        if (entry == null) return;
        n.access = entry.access();
        n.methodCount = entry.methodCount();
        n.fieldCount = entry.fieldCount();
    }

    public CallGraph buildAround(String rootId, String rootLabel,
                                 CallGraph.Mode mode, CallGraph.Direction direction, int depth,
                                 String packageFilter) {
        CallGraph graph = new CallGraph(mode);

        GraphNode root = createNodeFromId(rootId, rootLabel, mode);
        enrichNode(root);
        graph.addNodeIfAbsent(root);

        // BFS by depth
        Deque<LevelEntry> frontier = new ArrayDeque<>();
        frontier.add(new LevelEntry(root, 0));
        Set<String> visited = new HashSet<>();
        visited.add(root.id());

        while (!frontier.isEmpty()) {
            LevelEntry cur = frontier.poll();
            if (cur.level >= depth) continue;

            if (direction == CallGraph.Direction.OUT || direction == CallGraph.Direction.BOTH) {
                for (CallSite cs : outgoingFrom(cur.node, mode)) {
                    GraphNode target = nodeForCallSiteTarget(cs, mode);
                    if (target == null) continue;
                    if (!passesPackageFilter(target, packageFilter)) continue;
                    enrichNode(target);
                    target = graph.addNodeIfAbsent(target);
                    graph.addOrIncrementEdge(cur.node, target);
                    if (visited.add(target.id())) {
                        frontier.add(new LevelEntry(target, cur.level + 1));
                    }
                }
            }
            if (direction == CallGraph.Direction.IN || direction == CallGraph.Direction.BOTH) {
                for (CallSite cs : incomingTo(cur.node, mode)) {
                    GraphNode caller = nodeForCallSiteSource(cs, mode);
                    if (caller == null) continue;
                    if (!passesPackageFilter(caller, packageFilter)) continue;
                    enrichNode(caller);
                    caller = graph.addNodeIfAbsent(caller);
                    graph.addOrIncrementEdge(caller, cur.node);
                    if (visited.add(caller.id())) {
                        frontier.add(new LevelEntry(caller, cur.level + 1));
                    }
                }
            }
        }
        return graph;
    }

    private Iterable<CallSite> outgoingFrom(GraphNode node, CallGraph.Mode mode) {
        if (mode == CallGraph.Mode.CLASS) {
            String fqn = node.fqn();
            if (fqn == null) return java.util.List.of();
            String internal = fqn.replace('.', '/');
            return usageIndex.allMethodCalls()
                    .filter(cs -> cs.inClassFqn().equals(internal))
                    .toList();
        } else {
            String internal = node.fqn() == null ? "" : node.fqn().replace('.', '/');
            return usageIndex.allMethodCalls()
                    .filter(cs -> cs.inClassFqn().equals(internal)
                            && node.methodName().equals(cs.inMethodName())
                            && node.methodDesc().equals(cs.inMethodDesc()))
                    .toList();
        }
    }

    private Iterable<CallSite> incomingTo(GraphNode node, CallGraph.Mode mode) {
        if (mode == CallGraph.Mode.CLASS) {
            String fqn = node.fqn();
            if (fqn == null) return java.util.List.of();
            String internal = fqn.replace('.', '/');
            return usageIndex.allMethodCalls()
                    .filter(cs -> cs.targetOwner().equals(internal))
                    .toList();
        } else {
            String internal = node.fqn() == null ? "" : node.fqn().replace('.', '/');
            return usageIndex.allMethodCalls()
                    .filter(cs -> cs.targetOwner().equals(internal)
                            && node.methodName().equals(cs.targetName())
                            && node.methodDesc().equals(cs.targetDesc()))
                    .toList();
        }
    }

    private GraphNode nodeForCallSiteTarget(CallSite cs, CallGraph.Mode mode) {
        String ownerInternal = cs.targetOwner();
        if (ownerInternal == null || ownerInternal.isEmpty()) return null;
        String ownerFqn = ownerInternal.replace('/', '.');
        if (mode == CallGraph.Mode.CLASS) {
            return new GraphNode(GraphNode.classId(ownerFqn),
                    simpleName(ownerFqn), ownerFqn, null, null, GraphNode.Kind.CLASS);
        }
        String label = simpleName(ownerFqn) + "#" + cs.targetName();
        return new GraphNode(GraphNode.methodId(ownerFqn, cs.targetName(), cs.targetDesc()),
                label, ownerFqn, cs.targetName(), cs.targetDesc(), GraphNode.Kind.METHOD);
    }

    private GraphNode nodeForCallSiteSource(CallSite cs, CallGraph.Mode mode) {
        String ownerInternal = cs.inClassFqn();
        String ownerFqn = ownerInternal.replace('/', '.');
        if (mode == CallGraph.Mode.CLASS) {
            return new GraphNode(GraphNode.classId(ownerFqn),
                    simpleName(ownerFqn), ownerFqn, null, null, GraphNode.Kind.CLASS);
        }
        String label = simpleName(ownerFqn) + "#" + cs.inMethodName();
        return new GraphNode(GraphNode.methodId(ownerFqn, cs.inMethodName(), cs.inMethodDesc()),
                label, ownerFqn, cs.inMethodName(), cs.inMethodDesc(), GraphNode.Kind.METHOD);
    }

    private GraphNode createNodeFromId(String id, String label, CallGraph.Mode mode) {
        if (mode == CallGraph.Mode.CLASS) {
            String fqn = id.startsWith("C:") ? id.substring(2) : id;
            return new GraphNode(GraphNode.classId(fqn),
                    simpleName(fqn), fqn, null, null, GraphNode.Kind.CLASS);
        }
        // M:fqn#name(desc)
        String body = id.startsWith("M:") ? id.substring(2) : id;
        int hash = body.indexOf('#');
        if (hash < 0) {
            return new GraphNode(id, label, body, null, null, GraphNode.Kind.METHOD);
        }
        String fqn = body.substring(0, hash);
        String rest = body.substring(hash + 1);
        int paren = rest.indexOf('(');
        String name = paren < 0 ? rest : rest.substring(0, paren);
        String desc = paren < 0 ? "" : rest.substring(paren);
        return new GraphNode(id, simpleName(fqn) + "#" + name, fqn, name, desc, GraphNode.Kind.METHOD);
    }

    private static boolean passesPackageFilter(GraphNode n, String filter) {
        if (filter == null || filter.isBlank()) return true;
        String fqn = n.fqn();
        if (fqn == null) return false;
        String trimmed = filter.trim();
        if (trimmed.endsWith("*")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return fqn.startsWith(trimmed);
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    private record LevelEntry(GraphNode node, int level) {
    }
}
