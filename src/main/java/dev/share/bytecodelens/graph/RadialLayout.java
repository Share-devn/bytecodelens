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
 * Radial layout — root in the center, next hops in concentric rings around.
 * Good for exploring "what touches this node" in a single glance.
 */
public final class RadialLayout {

    private static final double BASE_RADIUS = 260;
    private static final double RING_STEP = 220;
    private static final double MARGIN = 80;

    public HierarchicalLayout.Bounds apply(CallGraph graph, String rootId) {
        Collection<GraphNode> nodes = graph.nodes();
        if (nodes.isEmpty()) return new HierarchicalLayout.Bounds(0, 0);

        GraphNode root = graph.node(rootId);
        Map<GraphNode, Integer> levels = new HashMap<>();
        if (root != null) {
            bfs(root, graph, levels);
        }
        int maxLevel = levels.values().stream().max(Integer::compare).orElse(0);
        for (GraphNode n : nodes) {
            if (!levels.containsKey(n)) {
                levels.put(n, maxLevel + 1);
            }
        }
        maxLevel = levels.values().stream().max(Integer::compare).orElse(0);

        Map<Integer, List<GraphNode>> byLevel = new LinkedHashMap<>();
        for (GraphNode n : nodes) {
            byLevel.computeIfAbsent(levels.get(n), k -> new ArrayList<>()).add(n);
        }
        for (List<GraphNode> level : byLevel.values()) {
            level.sort((a, b) -> a.label().compareTo(b.label()));
        }

        double canvasSize = (BASE_RADIUS + RING_STEP * (maxLevel + 1)) * 2 + MARGIN * 2;
        double cx = canvasSize / 2;
        double cy = canvasSize / 2;

        for (var entry : byLevel.entrySet()) {
            int level = entry.getKey();
            List<GraphNode> group = entry.getValue();
            if (level == 0) {
                for (GraphNode n : group) {
                    n.x = cx;
                    n.y = cy;
                }
                continue;
            }
            double radius = BASE_RADIUS + RING_STEP * (level - 1);
            int count = group.size();
            for (int i = 0; i < count; i++) {
                double angle = (2 * Math.PI * i) / count - Math.PI / 2;
                GraphNode n = group.get(i);
                n.x = cx + radius * Math.cos(angle);
                n.y = cy + radius * Math.sin(angle);
            }
        }

        return new HierarchicalLayout.Bounds(canvasSize, canvasSize);
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
                    levels.put(e.source(), curLevel + 1);
                    queue.add(e.source());
                }
            }
        }
    }
}
