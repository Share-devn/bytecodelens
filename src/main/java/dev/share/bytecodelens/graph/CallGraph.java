package dev.share.bytecodelens.graph;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CallGraph {

    public enum Mode { CLASS, METHOD }

    public enum Direction { OUT, IN, BOTH }

    private final Map<String, GraphNode> nodes = new LinkedHashMap<>();
    private final Map<String, GraphEdge> edges = new LinkedHashMap<>();
    private final Mode mode;

    public CallGraph(Mode mode) {
        this.mode = mode;
    }

    public Mode mode() {
        return mode;
    }

    public GraphNode addNodeIfAbsent(GraphNode node) {
        return nodes.computeIfAbsent(node.id(), k -> node);
    }

    public void addOrIncrementEdge(GraphNode source, GraphNode target) {
        if (source == null || target == null || source == target) return;
        String key = source.id() + "->" + target.id();
        GraphEdge existing = edges.get(key);
        if (existing != null) {
            existing.incrementWeight();
        } else {
            edges.put(key, new GraphEdge(source, target));
        }
    }

    public GraphNode node(String id) {
        return nodes.get(id);
    }

    public Collection<GraphNode> nodes() {
        return nodes.values();
    }

    public Collection<GraphEdge> edges() {
        return edges.values();
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edges.size();
    }

    public List<GraphEdge> outgoing(GraphNode n) {
        return edges.values().stream().filter(e -> e.source() == n).toList();
    }

    public List<GraphEdge> incoming(GraphNode n) {
        return edges.values().stream().filter(e -> e.target() == n).toList();
    }
}
