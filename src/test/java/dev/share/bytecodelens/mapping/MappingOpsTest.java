package dev.share.bytecodelens.mapping;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MappingOpsTest {

    @Test
    void diffDetectsAdditions() {
        var base = MappingModel.builder(MappingFormat.PROGUARD).mapClass("a", "Foo").build();
        var upd = MappingModel.builder(MappingFormat.PROGUARD)
                .mapClass("a", "Foo").mapClass("b", "Bar").build();
        var d = MappingOps.diff(base, upd);
        assertEquals(1, d.classesAdded().size());
        assertEquals("b", d.classesAdded().get(0).key());
        assertEquals("Bar", d.classesAdded().get(0).newTarget());
        assertEquals(0, d.classesRemoved().size());
        assertEquals(0, d.classesRenamed().size());
    }

    @Test
    void diffDetectsRemovals() {
        var base = MappingModel.builder(MappingFormat.PROGUARD)
                .mapClass("a", "Foo").mapClass("b", "Bar").build();
        var upd = MappingModel.builder(MappingFormat.PROGUARD).mapClass("a", "Foo").build();
        var d = MappingOps.diff(base, upd);
        assertEquals(1, d.classesRemoved().size());
        assertEquals("b", d.classesRemoved().get(0).key());
    }

    @Test
    void diffDetectsRenames() {
        var base = MappingModel.builder(MappingFormat.PROGUARD).mapClass("a", "Foo").build();
        var upd = MappingModel.builder(MappingFormat.PROGUARD).mapClass("a", "Renamed").build();
        var d = MappingOps.diff(base, upd);
        assertEquals(1, d.classesRenamed().size());
        assertEquals("Foo", d.classesRenamed().get(0).oldTarget());
        assertEquals("Renamed", d.classesRenamed().get(0).newTarget());
    }

    @Test
    void diffCountsAcrossKinds() {
        var base = MappingModel.builder(MappingFormat.PROGUARD)
                .mapClass("a", "Foo")
                .mapField("a", "x", "I", "fieldX")
                .mapMethod("a", "m", "()V", "methodM")
                .build();
        var upd = MappingModel.builder(MappingFormat.PROGUARD)
                .mapClass("a", "Foo")
                .mapField("a", "x", "I", "fieldXrenamed")
                .mapField("a", "y", "I", "fieldY")
                .build();
        var d = MappingOps.diff(base, upd);
        assertEquals(1, d.fieldsRenamed().size());
        assertEquals(1, d.fieldsAdded().size());
        assertEquals(1, d.methodsRemoved().size());
        assertEquals(3, d.totalChanges());
    }

    @Test
    void composeChainsClassRenames() {
        // first: a -> Foo, second: Foo -> CleanFoo  =>  composed: a -> CleanFoo
        var first = MappingModel.builder(MappingFormat.PROGUARD).mapClass("a", "Foo").build();
        var second = MappingModel.builder(MappingFormat.PROGUARD).mapClass("Foo", "CleanFoo").build();
        var composed = MappingOps.compose(first, second);
        assertEquals("CleanFoo", composed.classMap().get("a"));
    }

    @Test
    void composeChainsFieldRenames() {
        var first = MappingModel.builder(MappingFormat.PROGUARD)
                .mapClass("a", "Foo")
                .mapField("a", "x", "I", "renamedX")
                .build();
        var second = MappingModel.builder(MappingFormat.PROGUARD)
                .mapField("Foo", "renamedX", "I", "finalX")
                .build();
        var composed = MappingOps.compose(first, second);
        assertEquals("finalX", composed.fieldMap().get("a.x:I"));
    }

    @Test
    void composeKeepsUnmatchedFromFirst() {
        var first = MappingModel.builder(MappingFormat.PROGUARD).mapClass("a", "Foo").build();
        var second = MappingModel.builder(MappingFormat.PROGUARD).build();
        var composed = MappingOps.compose(first, second);
        assertEquals("Foo", composed.classMap().get("a"));
    }

    @Test
    void invertSwapsClassMap() {
        var orig = MappingModel.builder(MappingFormat.PROGUARD).mapClass("a", "Foo").build();
        var inv = MappingOps.invert(orig);
        assertEquals("a", inv.classMap().get("Foo"));
    }

    @Test
    void invertDropsAmbiguousEntries() {
        // Two obfuscated names map to the same target -> no unique inverse for "Foo".
        var orig = MappingModel.builder(MappingFormat.PROGUARD)
                .mapClass("a", "Foo").mapClass("b", "Foo").build();
        var inv = MappingOps.invert(orig);
        assertNull(inv.classMap().get("Foo"));
    }

    @Test
    void nullInputsThrow() {
        assertThrows(IllegalArgumentException.class, () -> MappingOps.diff(null, null));
        assertThrows(IllegalArgumentException.class, () -> MappingOps.compose(null, null));
        assertThrows(IllegalArgumentException.class, () -> MappingOps.invert(null));
    }
}
