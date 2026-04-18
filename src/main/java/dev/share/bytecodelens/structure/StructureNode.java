package dev.share.bytecodelens.structure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One labelled byte range in a parsed binary format. Nodes compose into a tree so
 * containers (class → constant pool → entry) render as collapsible rows in the UI.
 *
 * <p>Every node spans bytes {@code [offset, offset + length)} exclusively. A node with
 * {@code length == 0} is a marker (rare; used for empty tables).</p>
 *
 * @param label       short human-readable name shown in the tree
 * @param detail      optional extra detail for the row (value, enum name, size)
 * @param offset      byte offset from the start of the file
 * @param length      number of bytes the field occupies
 * @param colorGroup  semantic group for colouring the hex overlay (e.g. "magic",
 *                    "header", "body", "table"). UI maps these to CSS classes.
 * @param children    nested fields
 */
public record StructureNode(
        String label,
        String detail,
        int offset,
        int length,
        String colorGroup,
        List<StructureNode> children) {

    public StructureNode {
        children = children == null ? List.of() : List.copyOf(children);
    }

    public static StructureNode leaf(String label, String detail, int offset, int length, String colorGroup) {
        return new StructureNode(label, detail, offset, length, colorGroup, List.of());
    }

    public static StructureNode container(String label, String detail, int offset, int length,
                                          String colorGroup, List<StructureNode> children) {
        return new StructureNode(label, detail, offset, length, colorGroup, children);
    }

    /** Flatten all leaf nodes (those with empty children list). Handy for the hex overlay map. */
    public List<StructureNode> leaves() {
        List<StructureNode> out = new ArrayList<>();
        collectLeaves(this, out);
        return Collections.unmodifiableList(out);
    }

    private static void collectLeaves(StructureNode n, List<StructureNode> out) {
        if (n.children.isEmpty()) { out.add(n); return; }
        for (StructureNode c : n.children) collectLeaves(c, out);
    }
}
