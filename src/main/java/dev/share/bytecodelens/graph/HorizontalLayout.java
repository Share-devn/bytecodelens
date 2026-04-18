package dev.share.bytecodelens.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Left-to-right layered layout. Good when depth is small but each level has few nodes. */
public final class HorizontalLayout {

    private static final double HSPACING = 320;
    private static final double VSPACING = 110;
    private static final double MARGIN = 100;
    private static final int MAX_PER_COL = 10;

    public HierarchicalLayout.Bounds apply(CallGraph graph, String rootId) {
        Collection<GraphNode> nodes = graph.nodes();
        if (nodes.isEmpty()) return new HierarchicalLayout.Bounds(0, 0);

        GraphNode root = graph.node(rootId);
        Map<GraphNode, Integer> levels = new HashMap<>();
        if (root != null) bfs(root, graph, levels);
        int maxReached = levels.values().stream().max(Integer::compare).orElse(0);
        for (GraphNode n : nodes) {
            if (!levels.containsKey(n)) levels.put(n, maxReached + 1);
        }

        Map<Integer, List<GraphNode>> byLevel = new LinkedHashMap<>();
        for (GraphNode n : nodes) {
            byLevel.computeIfAbsent(levels.get(n), k -> new ArrayList<>()).add(n);
        }
        for (List<GraphNode> level : byLevel.values()) {
            level.sort((a, b) -> a.label().compareTo(b.label()));
        }

        double currentX = MARGIN;
        double maxY = 0;
        List<Integer> sortedLevels = new ArrayList<>(byLevel.keySet());
        java.util.Collections.sort(sortedLevels);

        for (int level : sortedLevels) {
            List<GraphNode> group = byLevel.get(level);
            int cols = (int) Math.ceil(group.size() / (double) MAX_PER_COL);
            double levelWidth = cols * HSPACING;
            for (int col = 0; col < cols; col++) {
                int fromIdx = col * MAX_PER_COL;
                int toIdx = Math.min(fromIdx + MAX_PER_COL, group.size());
                int count = toIdx - fromIdx;
                double colHeight = count * VSPACING;
                double startY = MARGIN + (Math.max(MAX_PER_COL, 1) * VSPACING - colHeight) / 2;
                for (int i = 0; i < count; i++) {
                    GraphNode n = group.get(fromIdx + i);
                    n.x = currentX + col * HSPACING;
                    n.y = startY + i * VSPACING;
                    if (n.y > maxY) maxY = n.y;
                }
            }
            currentX += levelWidth;
        }

        double width = currentX + MARGIN;
        double height = Math.max(maxY + MARGIN, MARGIN * 2 + MAX_PER_COL * VSPACING);
        return new HierarchicalLayout.Bounds(width, height);
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
                if (visited.add(e.target())) {
                    levels.put(e.target(), curLevel + 1);
                    queue.add(e.target());
                }
            }
            for (GraphEdge e : graph.incoming(cur)) {
                if (visited.add(e.source())) {
                    levels.put(e.source(), curLevel - 1);
                    queue.add(e.source());
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
}
