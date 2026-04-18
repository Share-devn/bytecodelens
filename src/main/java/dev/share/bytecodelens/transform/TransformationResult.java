package dev.share.bytecodelens.transform;

import dev.share.bytecodelens.model.LoadedJar;

/**
 * Outcome of a transformation run: the rewritten jar + counters for each pass.
 */
public record TransformationResult(
        LoadedJar transformedJar,
        int classesChanged,
        int classesFailed,
        TransformContext context
) {
}
