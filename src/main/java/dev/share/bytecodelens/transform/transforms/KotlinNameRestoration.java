package dev.share.bytecodelens.transform.transforms;

import dev.share.bytecodelens.transform.TransformContext;
import dev.share.bytecodelens.transform.Transformation;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

/**
 * Kotlin-aware pass. The full implementation requires reading {@code @kotlin.Metadata}
 * protobuf to recover source names — that pulls in kotlinx.metadata-jvm which is a sizable
 * dependency. For now we only <em>detect</em> Kotlin-compiled classes and count them, so
 * users can tell whether a full Kotlin pass would be worth wiring up.
 *
 * <p>TODO: add kotlinx-metadata-jvm dependency and parse {@code d1}/{@code d2} arrays to
 * rename functions/fields back to source names.</p>
 */
public final class KotlinNameRestoration implements Transformation {

    private static final String METADATA_DESC = "Lkotlin/Metadata;";

    @Override public String id() { return "kotlin-name-restoration"; }
    @Override public String name() { return "Kotlin Name Restoration (detect-only)"; }
    @Override public String description() {
        return "Detect Kotlin-compiled classes via @kotlin.Metadata. Full restore pending.";
    }

    @Override
    public void transform(ClassNode node, TransformContext ctx) {
        if (node.visibleAnnotations == null) return;
        for (AnnotationNode a : node.visibleAnnotations) {
            if (METADATA_DESC.equals(a.desc)) {
                ctx.inc("kotlin-classes-detected");
                return;
            }
        }
    }
}
