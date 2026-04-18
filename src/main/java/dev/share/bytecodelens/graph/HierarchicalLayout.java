package dev.share.bytecodelens.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Top-down hierarchical layout (Sugiyama-lite).
 * Root at top, children below. Nodes with many siblings wrap into
 * multiple rows within the same conceptual level to prevent a one-line mess.
 */
public final class HierarchicalLayout {

    private static final double HSPACING = 290;
    private static final double VSPACING = 170;
    private static final double MARGIN = 100;
    private static final int MAX_PER_ROW = 8;

    public Bounds apply(CallGraph graph, String rootId) {
        Collection<GraphNode> nodes = graph.nodes();
        if (nodes.isEmpty()) return new Bounds(0, 0);

        GraphNode root = graph.node(rootId);
        Map<GraphNode, Integer> levels = new HashMap<>();
        if (root != null) {
            bfs(root, graph, levels);
        }
        int maxReached = levels.values().stream().max(Integer::compare).orElse(0);
        for (GraphNode n : nodes) {
            if (!levels.containsKey(n)) {
                levels.put(n, maxReached + 1);
            }
        }

        Map<Integer, List<GraphNode>> byLevel = new LinkedHashMap<>();
        for (GraphNode n : nodes) {
            byLevel.computeIfAbsent(levels.get(n), k -> new ArrayList<>()).add(n);
        }
        for (List<GraphNode> level : byLevel.values()) {
            level.sort((a, b) -> {
                int p = a.packageName().compareTo(b.packageName());
                return p != 0 ? p : a.label().compareTo(b.label());
            });
        }

        double width = MARGIN * 2 + Math.max(MAX_PER_ROW, 1) * HSPACING;
        double currentY = MARGIN;
        List<Integer> sortedLevels = new ArrayList<>(byLevel.keySet());
        java.util.Collections.sort(sortedLevels);

        for (int level : sortedLevels) {
            List<GraphNode> group = byLevel.get(level);
            int rows = (int) Math.ceil(group.size() / (double) MAX_PER_ROW);
            for (int row = 0; row < rows; row++) {
                int fromIdx = row * MAX_PER_ROW;
                int toIdx = Math.min(fromIdx + MAX_PER_ROW, group.size());
                int count = toIdx - fromIdx;
                double rowWidth = count * HSPACING;
                double startX = (width - rowWidth) / 2 + HSPACING / 2;
                for (int i = 0; i < count; i++) {
                    GraphNode n = group.get(fromIdx + i);
                    n.x = startX + i * HSPACING;
                    n.y = currentY + row * VSPACING;
                }
            }
            currentY += rows * VSPACING;
        }

        double height = currentY + MARGIN;
        return new Bounds(width, height);
    }

    private void bfs(GraphNode root, CallGraph graph, Map<GraphNode, Integer> levels) {
        levels.put(root, 0);
        java.util.Deque<GraphNode> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        Set<GraphNode> visited = new HashSet<>();
        visited.add(root);
        while (!queue.isEmpty()) {
            GraphNode cur = queue.poll();
            int curLevel = levels.get(cur);
            for (GraphEdge e : graph.outgoing(cur)) {
                GraphNode child = e.target();
                if (visited.add(child)) {
                    levels.put(child, curLevel + 1);
                    queue.add(child);
                }
            }
            for (GraphEdge e : graph.incoming(cur)) {
                GraphNode parent = e.source();
                if (visited.add(parent)) {
                    levels.put(parent, curLevel - 1);
                    queue.add(parent);
                }
            }
        }
        int min = levels.values().stream().min(Integer::compare).orElse(0);
        if (min < 0) {
            for (var e : levels.entrySet()) {
                e.setValue(e.getValue() - min);
            }
        }
    }

    public record Bounds(double width, double height) {
    }
}
