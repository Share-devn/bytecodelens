package dev.share.bytecodelens.transform;

import org.objectweb.asm.tree.ClassNode;

/**
 * A single bytecode transformation — typically a deobfuscation pass.
 *
 * <p>Implementations mutate the given {@link ClassNode} in-place and report progress via
 * {@link TransformContext}. The runner handles read/write I/O and batch orchestration.</p>
 *
 * <p>Transformations must be idempotent: running the same transformation twice on the same
 * input should be a no-op on the second run. This lets users re-apply passes without worrying
 * about double-mutation.</p>
 */
public interface Transformation {

    /** Short stable identifier, e.g. {@code "dead-code-removal"}. Used in config & UI. */
    String id();

    /** Human-readable name shown in the picker. */
    String name();

    /** One-line description. */
    String description();

    /**
     * Apply this transformation to {@code node} in-place. Increment counters on {@code ctx}
     * as appropriate so the final report can tell the user what happened.
     */
    void transform(ClassNode node, TransformContext ctx);
}
