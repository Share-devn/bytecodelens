package dev.share.bytecodelens.graph;

public final class GraphNode {

    public enum Kind { CLASS, METHOD, EXTERNAL }

    private final String id;
    private final String label;
    private final String fqn;
    private final String methodName;
    private final String methodDesc;
    private final Kind kind;

    // Extended metadata, filled in when building the graph (0 if unknown)
    public int access;
    public int methodCount;
    public int fieldCount;

    // Mutable layout state
    public double x;
    public double y;
    public double vx;
    public double vy;

    public GraphNode(String id, String label, String fqn, String methodName, String methodDesc, Kind kind) {
        this.id = id;
        this.label = label;
        this.fqn = fqn;
        this.methodName = methodName;
        this.methodDesc = methodDesc;
        this.kind = kind;
    }

    public String id() { return id; }
    public String label() { return label; }
    public String fqn() { return fqn; }
    public String methodName() { return methodName; }
    public String methodDesc() { return methodDesc; }
    public Kind kind() { return kind; }

    public String simpleClassName() {
        if (fqn == null) return label;
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    public String packageName() {
        if (fqn == null) return "";
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    public static String classId(String fqn) {
        return "C:" + fqn;
    }

    public static String methodId(String ownerFqn, String name, String desc) {
        return "M:" + ownerFqn + "#" + name + desc;
    }
}
