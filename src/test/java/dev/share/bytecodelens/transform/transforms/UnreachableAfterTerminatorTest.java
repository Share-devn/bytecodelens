package dev.share.bytecodelens.transform.transforms;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the SC2-style anti-disassembly stripper. We build synthetic
 * MethodNodes with the specific shapes we want to strip, run the static helper, and
 * assert the output is the expected clean sequence.
 */
class UnreachableAfterTerminatorTest {

    @Test
    void stripsGarbageAfterReturn() {
        // RETURN followed by ICONST_1, POP, RETURN — the trailing three are unreachable.
        MethodNode m = newMethod();
        m.instructions.add(new InsnNode(Opcodes.RETURN));
        m.instructions.add(new InsnNode(Opcodes.ICONST_1));
        m.instructions.add(new InsnNode(Opcodes.POP));
        m.instructions.add(new InsnNode(Opcodes.RETURN));
        int removed = UnreachableAfterTerminator.stripAfterTerminators(m);
        assertEquals(3, removed);
        assertEquals(1, m.instructions.size());
        assertEquals(Opcodes.RETURN, m.instructions.getFirst().getOpcode());
    }

    @Test
    void stripsSc2PushPopRetThrowPattern() {
        // The reported obfuscation: push/pop/ret/throw. We encode as ICONST_0 (push),
        // POP, RETURN, ATHROW — the RETURN is the terminator, POP before it is live,
        // and ATHROW is dead tail that should be removed.
        MethodNode m = newMethod();
        m.instructions.add(new InsnNode(Opcodes.ICONST_0));
        m.instructions.add(new InsnNode(Opcodes.POP));
        m.instructions.add(new InsnNode(Opcodes.RETURN));
        m.instructions.add(new InsnNode(Opcodes.ATHROW));
        int removed = UnreachableAfterTerminator.stripAfterTerminators(m);
        assertEquals(1, removed);
        assertEquals(3, m.instructions.size());
    }

    @Test
    void preservesInstructionsAfterReferencedLabel() {
        // IF_ICMPNE jumps to L1; after the RETURN there's L1 which is a valid target.
        // The instructions AFTER L1 must survive.
        LabelNode l1 = new LabelNode();
        MethodNode m = newMethod();
        m.instructions.add(new InsnNode(Opcodes.ICONST_1));
        m.instructions.add(new InsnNode(Opcodes.ICONST_2));
        m.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPNE, l1));
        m.instructions.add(new InsnNode(Opcodes.RETURN));
        // Garbage between RETURN and l1 — must be stripped.
        m.instructions.add(new InsnNode(Opcodes.ICONST_5));
        m.instructions.add(l1);
        m.instructions.add(new InsnNode(Opcodes.RETURN));
        int removed = UnreachableAfterTerminator.stripAfterTerminators(m);
        assertEquals(1, removed);
        // Remaining: ICONST_1, ICONST_2, IF_ICMPNE, RETURN, L1, RETURN = 6
        assertEquals(6, m.instructions.size());
    }

    @Test
    void preservesTryCatchHandlerLabels() {
        // try-catch handler label is "referenced" even without explicit jump to it,
        // so instructions after a terminator survive when the next label is a
        // try-block start/end/handler.
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        MethodNode m = newMethod();
        m.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
        m.instructions.add(start);
        m.instructions.add(new InsnNode(Opcodes.RETURN));
        // GARBAGE between RETURN and `end` — this is stripped.
        m.instructions.add(new InsnNode(Opcodes.NOP));
        m.instructions.add(end);
        m.instructions.add(handler);
        m.instructions.add(new InsnNode(Opcodes.ATHROW));
        int removed = UnreachableAfterTerminator.stripAfterTerminators(m);
        assertEquals(1, removed);
        // start, RETURN, end, handler, ATHROW = 5.
        assertEquals(5, m.instructions.size());
    }

    @Test
    void noopOnMethodWithoutTerminators() {
        // Method body that ends with a "normal" instruction — shouldn't strip anything.
        MethodNode m = newMethod();
        m.instructions.add(new InsnNode(Opcodes.ICONST_1));
        m.instructions.add(new InsnNode(Opcodes.ICONST_2));
        int before = m.instructions.size();
        int removed = UnreachableAfterTerminator.stripAfterTerminators(m);
        assertEquals(0, removed);
        assertEquals(before, m.instructions.size());
    }

    @Test
    void transformSkipsAbstractMethods() {
        // abstract methods have null/empty instructions — must not crash.
        UnreachableAfterTerminator x = new UnreachableAfterTerminator();
        ClassNode cn = new ClassNode();
        cn.name = "Foo";
        cn.version = Opcodes.V1_8;
        cn.access = Opcodes.ACC_ABSTRACT;
        cn.superName = "java/lang/Object";
        MethodNode abstractM = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "abs", "()V", null, null);
        cn.methods.add(abstractM);
        assertTrue(cn.methods.size() == 1);  // sanity
        // Should run without exception.
        x.transform(cn, new dev.share.bytecodelens.transform.TransformContext());
    }

    private static MethodNode newMethod() {
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "test", "()V", null, null);
        return m;
    }
}
