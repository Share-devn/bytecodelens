package dev.share.bytecodelens.model;

public record MethodEntry(
        String name,
        String descriptor,
        int access,
        int instructionCount,
        int maxStack,
        int maxLocals
) {
}
