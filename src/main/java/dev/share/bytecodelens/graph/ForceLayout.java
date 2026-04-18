package dev.share.bytecodelens.graph;

import java.util.Collection;

/**
 * Fruchterman-Reingold force-directed layout. Positions are written back to GraphNode.x/.y.
 * After running, nodes are fit inside [0..width, 0..height] with a margin.
 */
public final class ForceLayout {

    private static final int DEFAULT_ITERATIONS = 300;

    private final double width;
    private final double height;
    private final int iterations;

    public ForceLayout(double width, double height) {
        this(width, height, DEFAULT_ITERATIONS);
    }

    public ForceLayout(double width, double height, int iterations) {
        this.width = width;
        this.height = height;
        this.iterations = iterations;
    }

    public void apply(CallGraph graph) {
        Collection<GraphNode> nodes = graph.nodes();
        Collection<GraphEdge> edges = graph.edges();
        int n = nodes.size();
        if (n == 0) return;
        if (n == 1) {
            GraphNode only = nodes.iterator().next();
            only.x = width / 2;
            only.y = height / 2;
            return;
        }

        double area = width * height;
        double k = Math.sqrt(area / n);
        double temperature = Math.max(width, height) / 10.0;
        double cooling = temperature / iterations;

        // Initial random placement in a circle around the center
        double cx = width / 2.0;
        double cy = height / 2.0;
        double radius = Math.min(width, height) * 0.35;
        int i = 0;
        for (GraphNode node : nodes) {
            if (node.x == 0 && node.y == 0) {
                double angle = (2 * Math.PI * i) / n;
                node.x = cx + radius * Math.cos(angle);
                node.y = cy + radius * Math.sin(angle);
            }
            i++;
        }

        for (int iter = 0; iter < iterations; iter++) {
            // Repulsive forces
            for (GraphNode v : nodes) {
                v.vx = 0;
                v.vy = 0;
                for (GraphNode u : nodes) {
                    if (u == v) continue;
                    double dx = v.x - u.x;
                    double dy = v.y - u.y;
                    double dist = Math.sqrt(dx * dx + dy * dy);
                    if (dist < 0.01) dist = 0.01;
                    double repulsion = (k * k) / dist;
                    v.vx += (dx / dist) * repulsion;
                    v.vy += (dy / dist) * repulsion;
                }
            }

            // Attractive forces along edges
            for (GraphEdge e : edges) {
                GraphNode u = e.source();
                GraphNode v = e.target();
                double dx = v.x - u.x;
                double dy = v.y - u.y;
                double dist = Math.sqrt(dx * dx + dy * dy);
                if (dist < 0.01) dist = 0.01;
                double attraction = (dist * dist) / k;
                double fx = (dx / dist) * attraction;
                double fy = (dy / dist) * attraction;
                u.vx += fx;
                u.vy += fy;
                v.vx -= fx;
                v.vy -= fy;
            }

            // Apply displacement with cooling, clamp to canvas
            for (GraphNode v : nodes) {
                double disp = Math.sqrt(v.vx * v.vx + v.vy * v.vy);
                if (disp < 0.01) continue;
                double limit = Math.min(disp, temperature);
                v.x += (v.vx / disp) * limit;
                v.y += (v.vy / disp) * limit;
                v.x = Math.max(30, Math.min(width - 30, v.x));
                v.y = Math.max(30, Math.min(height - 30, v.y));
            }

            temperature -= cooling;
            if (temperature < 0.5) break;
        }
    }
}
