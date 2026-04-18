package dev.share.bytecodelens.transform.transforms;

import dev.share.bytecodelens.transform.TransformContext;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;

import static org.junit.jupiter.api.Assertions.*;

class SourceNameRestorationTest {

    @Test
    void setsSourceFileForTopLevelClass() {
        ClassNode n = new ClassNode();
        n.name = "p/Foo";
        n.sourceFile = null;
        TransformContext ctx = new TransformContext();
        ctx.enterPass("source-name-restoration");
        new SourceNameRestoration().transform(n, ctx);
        ctx.exitPass();
        assertEquals("Foo.java", n.sourceFile);
        assertEquals(1, ctx.totalFor("source-name-restoration"));
    }

    @Test
    void usesOuterClassForInnerClass() {
        ClassNode n = new ClassNode();
        n.name = "p/Outer$Inner";
        n.sourceFile = null;
        new SourceNameRestoration().transform(n, new TransformContext());
        assertEquals("Outer.java", n.sourceFile);
    }

    @Test
    void usesOuterForAnonymousClass() {
        ClassNode n = new ClassNode();
        n.name = "p/Outer$1";
        n.sourceFile = null;
        new SourceNameRestoration().transform(n, new TransformContext());
        assertEquals("Outer.java", n.sourceFile);
    }

    @Test
    void leavesValidJavaSourceFileAlone() {
        ClassNode n = new ClassNode();
        n.name = "p/Foo";
        n.sourceFile = "Foo.java";
        TransformContext ctx = new TransformContext();
        ctx.enterPass("source-name-restoration");
        new SourceNameRestoration().transform(n, ctx);
        ctx.exitPass();
        assertEquals("Foo.java", n.sourceFile);
        assertEquals(0, ctx.totalFor("source-name-restoration"));
    }

    @Test
    void leavesKotlinSourceFileAlone() {
        ClassNode n = new ClassNode();
        n.name = "p/Foo";
        n.sourceFile = "Foo.kt";
        new SourceNameRestoration().transform(n, new TransformContext());
        assertEquals("Foo.kt", n.sourceFile);
    }

    @Test
    void overwritesEmptySourceFile() {
        ClassNode n = new ClassNode();
        n.name = "p/Foo";
        n.sourceFile = "";
        new SourceNameRestoration().transform(n, new TransformContext());
        assertEquals("Foo.java", n.sourceFile);
    }

    @Test
    void overwritesGarbageSourceFile() {
        // Obfuscator-set garbage that doesn't end in a known source extension.
        ClassNode n = new ClassNode();
        n.name = "p/Foo";
        n.sourceFile = "X";
        new SourceNameRestoration().transform(n, new TransformContext());
        assertEquals("Foo.java", n.sourceFile);
    }

    @Test
    void handlesDefaultPackage() {
        ClassNode n = new ClassNode();
        n.name = "Foo";
        n.sourceFile = null;
        new SourceNameRestoration().transform(n, new TransformContext());
        assertEquals("Foo.java", n.sourceFile);
    }
}
