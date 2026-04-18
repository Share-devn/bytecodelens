package dev.share.bytecodelens.graph;

public final class GraphEdge {

    private final GraphNode source;
    private final GraphNode target;
    private int weight;

    public GraphEdge(GraphNode source, GraphNode target) {
        this.source = source;
        this.target = target;
        this.weight = 1;
    }

    public GraphNode source() { return source; }
    public GraphNode target() { return target; }
    public int weight() { return weight; }

    public void incrementWeight() {
        weight++;
    }
}
