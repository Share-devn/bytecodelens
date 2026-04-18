package dev.share.bytecodelens.usage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sanity tests for the helper predicates on {@link CallSite.Kind}. The Xref filter bar
 * uses these to decide whether to hide a given call site — one bug here hides the
 * wrong half of the results, so keep coverage dense.
 */
class CallSiteKindTest {

    @Test
    void getfieldAndGetstaticAreReads() {
        assertTrue(CallSite.Kind.GETFIELD.isFieldRead());
        assertTrue(CallSite.Kind.GETSTATIC.isFieldRead());
        assertFalse(CallSite.Kind.PUTFIELD.isFieldRead());
        assertFalse(CallSite.Kind.PUTSTATIC.isFieldRead());
    }

    @Test
    void putfieldAndPutstaticAreWrites() {
        assertTrue(CallSite.Kind.PUTFIELD.isFieldWrite());
        assertTrue(CallSite.Kind.PUTSTATIC.isFieldWrite());
        assertFalse(CallSite.Kind.GETFIELD.isFieldWrite());
    }

    @Test
    void everyInvokeOpcodeIsInvoke() {
        assertTrue(CallSite.Kind.INVOKE_VIRTUAL.isInvoke());
        assertTrue(CallSite.Kind.INVOKE_STATIC.isInvoke());
        assertTrue(CallSite.Kind.INVOKE_SPECIAL.isInvoke());
        assertTrue(CallSite.Kind.INVOKE_INTERFACE.isInvoke());
        assertTrue(CallSite.Kind.INVOKE_DYNAMIC.isInvoke());
    }

    @Test
    void fieldAndInvokeKindsAreDisjoint() {
        for (CallSite.Kind k : CallSite.Kind.values()) {
            // Exactly one of the four "groups" must be true, except TYPE_IN_SIGNATURE
            // which is a type-use only.
            boolean r = k.isFieldRead();
            boolean w = k.isFieldWrite();
            boolean i = k.isInvoke();
            boolean t = k.isTypeUse();
            int count = (r ? 1 : 0) + (w ? 1 : 0) + (i ? 1 : 0) + (t ? 1 : 0);
            assertTrue(count == 1, "Kind " + k + " matched " + count + " categories");
        }
    }

    @Test
    void typeUseCategory() {
        assertTrue(CallSite.Kind.NEW.isTypeUse());
        assertTrue(CallSite.Kind.CHECKCAST.isTypeUse());
        assertTrue(CallSite.Kind.INSTANCEOF.isTypeUse());
        assertTrue(CallSite.Kind.ANEWARRAY.isTypeUse());
        assertTrue(CallSite.Kind.TYPE_IN_SIGNATURE.isTypeUse());
    }
}
