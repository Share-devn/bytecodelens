package dev.share.bytecodelens.model;

public record FieldEntry(
        String name,
        String descriptor,
        int access,
        Object constantValue
) {
}
