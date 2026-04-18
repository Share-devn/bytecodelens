package dev.share.bytecodelens.decompile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DecompileCacheTest {

    @Test
    void putThenGetReturnsSameText() {
        DecompileCache c = new DecompileCache(8);
        byte[] bytes = {1, 2, 3};
        c.put("Foo", "CFR", bytes, "// foo");
        assertEquals("// foo", c.get("Foo", "CFR", bytes));
        assertEquals(1, c.hits());
        assertEquals(0, c.misses());
    }

    @Test
    void differentEnginesAreSeparateEntries() {
        DecompileCache c = new DecompileCache(8);
        byte[] bytes = {1, 2, 3};
        c.put("Foo", "CFR", bytes, "// cfr");
        c.put("Foo", "Vineflower", bytes, "// vine");
        assertEquals("// cfr", c.get("Foo", "CFR", bytes));
        assertEquals("// vine", c.get("Foo", "Vineflower", bytes));
        assertEquals(2, c.size());
    }

    @Test
    void contentChangeInvalidates() {
        DecompileCache c = new DecompileCache(8);
        c.put("Foo", "CFR", new byte[]{1, 2, 3}, "// old");
        // Hot reload: same internal name + engine but different bytes.
        assertNull(c.get("Foo", "CFR", new byte[]{1, 2, 3, 4}));
        assertEquals("// old", c.get("Foo", "CFR", new byte[]{1, 2, 3}));
    }

    @Test
    void evictsLeastRecentlyUsedWhenOverCapacity() {
        DecompileCache c = new DecompileCache(2);
        c.put("A", "CFR", new byte[]{1}, "a");
        c.put("B", "CFR", new byte[]{2}, "b");
        // Touch A so B becomes LRU.
        assertEquals("a", c.get("A", "CFR", new byte[]{1}));
        c.put("C", "CFR", new byte[]{3}, "c");
        assertEquals("a", c.get("A", "CFR", new byte[]{1}));
        assertEquals("c", c.get("C", "CFR", new byte[]{3}));
        assertNull(c.get("B", "CFR", new byte[]{2}));
    }

    @Test
    void missesAreCounted() {
        DecompileCache c = new DecompileCache(4);
        assertNull(c.get("Nope", "CFR", new byte[]{0}));
        assertNull(c.get("Nope", "CFR", new byte[]{0}));
        assertEquals(0, c.hits());
        assertEquals(2, c.misses());
    }

    @Test
    void clearResetsState() {
        DecompileCache c = new DecompileCache(4);
        c.put("A", "CFR", new byte[]{1}, "a");
        c.get("A", "CFR", new byte[]{1});
        c.clear();
        assertEquals(0, c.size());
        assertEquals(0, c.hits());
        assertEquals(0, c.misses());
    }

    @Test
    void putNullTextIsNoop() {
        DecompileCache c = new DecompileCache(4);
        c.put("A", "CFR", new byte[]{1}, null);
        assertEquals(0, c.size());
    }

    @Test
    void capacityValidation() {
        assertThrows(IllegalArgumentException.class, () -> new DecompileCache(0));
        assertThrows(IllegalArgumentException.class, () -> new DecompileCache(-1));
    }

    @Test
    void keyIsDeterministic() {
        byte[] b = {1, 2, 3};
        assertEquals(DecompileCache.key("Foo", "CFR", b), DecompileCache.key("Foo", "CFR", b));
        assertNotEquals(DecompileCache.key("Foo", "CFR", b),
                        DecompileCache.key("Foo", "CFR", new byte[]{1, 2, 4}));
    }

    @Test
    void nullBytesDoNotCrash() {
        DecompileCache c = new DecompileCache(4);
        c.put("A", "CFR", null, "a");
        assertEquals("a", c.get("A", "CFR", null));
    }
}
