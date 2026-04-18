package dev.share.bytecodelens.hierarchy;

import java.util.List;

public record HierarchyNode(
        String internalName,
        Kind kind,
        List<HierarchyNode> children
) {
    public enum Kind {
        CLASS,
        INTERFACE,
        ABSTRACT_CLASS,
        ENUM,
        ANNOTATION,
        RECORD,
        EXTERNAL
    }

    public String displayName() {
        return internalName.replace('/', '.');
    }

    public String simpleName() {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? internalName : internalName.substring(slash + 1);
    }
}
