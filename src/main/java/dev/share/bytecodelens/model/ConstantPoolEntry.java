package dev.share.bytecodelens.model;

public record ConstantPoolEntry(
        int index,
        String type,
        String value
) {
}
