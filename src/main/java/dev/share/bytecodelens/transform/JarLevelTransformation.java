package dev.share.bytecodelens.transform;

import dev.share.bytecodelens.model.LoadedJar;

/**
 * Transformation that needs a jar-wide view (e.g. renaming classes requires rewriting
 * every reference across the whole jar) rather than operating on a single class at a time.
 *
 * <p>Jar-level passes run <em>before</em> per-class passes so that by the time per-class
 * transformations see the {@link dev.share.bytecodelens.model.ClassEntry}s, they're already
 * in the post-rename form.</p>
 */
public interface JarLevelTransformation {

    String id();
    String name();
    String description();

    /** Apply the pass and return the (possibly new) jar. */
    LoadedJar apply(LoadedJar jar, TransformContext ctx);
}
