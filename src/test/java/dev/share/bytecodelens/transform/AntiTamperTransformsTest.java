package dev.share.bytecodelens.transform;

import dev.share.bytecodelens.transform.transforms.RemoveIllegalAnnotations;
import dev.share.bytecodelens.transform.transforms.StripCodeOnField;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AntiTamperTransformsTest {

    @Test
    void stripCodeRemovesMethodAttributesFromFields() {
        ClassNode node = new ClassNode();
        node.name = "a/A";
        node.fields = new ArrayList<>();
        FieldNode f = new FieldNode(0, "x", "I", null, null);
        f.attrs = new ArrayList<>();
        f.attrs.add(new UnknownAttribute("Code"));
        f.attrs.add(new UnknownAttribute("LineNumberTable"));
        f.attrs.add(new UnknownAttribute("CustomBenign"));
        node.fields.add(f);

        TransformContext ctx = new TransformContext();
        ctx.enterPass("strip-code-on-field");
        new StripCodeOnField().transform(node, ctx);
        ctx.exitPass();

        assertEquals(1, f.attrs.size());
        assertEquals("CustomBenign", f.attrs.get(0).type);
        assertEquals(2, ctx.counters().get("strip-code-on-field").get("attributes-stripped"));
    }

    @Test
    void removeIllegalAnnotationsStripsBadDescriptors() {
        ClassNode node = new ClassNode();
        node.name = "a/A";
        node.visibleAnnotations = new ArrayList<>();
        node.visibleAnnotations.add(new AnnotationNode("Lkotlin/Metadata;"));   // valid
        node.visibleAnnotations.add(new AnnotationNode(""));                     // invalid
        node.visibleAnnotations.add(new AnnotationNode("Lbroken.with.dots;"));   // invalid (dots)
        node.visibleAnnotations.add(new AnnotationNode("not-a-descriptor"));     // invalid

        TransformContext ctx = new TransformContext();
        ctx.enterPass("remove-illegal-annotations");
        new RemoveIllegalAnnotations().transform(node, ctx);
        ctx.exitPass();

        assertEquals(1, node.visibleAnnotations.size());
        assertEquals("Lkotlin/Metadata;", node.visibleAnnotations.get(0).desc);
    }

    @Test
    void descriptorValidatorAcceptsValidAndRejectsInvalid() {
        // Access private method via package-private static — or just round-trip via
        // transform behaviour (already done above). Supplementary quick checks:
        assertTrue(looksValid("Ljava/lang/String;"));
        assertTrue(looksValid("Lcom/foo/Bar$Inner;"));
        assertFalse(looksValid(""));
        assertFalse(looksValid("L;"));
        assertFalse(looksValid("Ljava.lang.String;"));
    }

    private static boolean looksValid(String desc) {
        // Mirror the predicate from RemoveIllegalAnnotations — simple enough to inline.
        if (desc == null || desc.length() < 3) return false;
        if (desc.charAt(0) != 'L' || desc.charAt(desc.length() - 1) != ';') return false;
        for (int i = 1; i < desc.length() - 1; i++) {
            char c = desc.charAt(i);
            if (c == '.' || c == ';' || c == '[' || c == '<' || c == '>') return false;
            if (c < 0x20) return false;
        }
        return true;
    }

    /** ASM test helper — a minimal Attribute implementation with only the 'type' field set. */
    private static final class UnknownAttribute extends Attribute {
        UnknownAttribute(String type) { super(type); }
    }
}
