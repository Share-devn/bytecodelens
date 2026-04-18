package dev.share.bytecodelens.diff;

public record ResourceDiff(
        ChangeType change,
        String path,
        long sizeA,
        long sizeB
) {
}
