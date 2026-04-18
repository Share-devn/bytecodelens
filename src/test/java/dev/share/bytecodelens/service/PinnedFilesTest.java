package dev.share.bytecodelens.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinnedFilesTest {

    /** Build a PinnedFiles bound to a test-local storage path. */
    private static PinnedFiles build(Path storageFile) throws Exception {
        Constructor<PinnedFiles> c = PinnedFiles.class.getDeclaredConstructor(Path.class);
        c.setAccessible(true);
        return c.newInstance(storageFile);
    }

    @Test
    void emptyStorageLoadsEmptyList(@TempDir Path dir) throws Exception {
        PinnedFiles pinned = build(dir.resolve("pinned.txt"));
        assertTrue(pinned.load().isEmpty());
    }

    @Test
    void pinAddsAndPersists(@TempDir Path dir) throws Exception {
        Path jar = Files.createFile(dir.resolve("a.jar"));
        PinnedFiles pinned = build(dir.resolve("pinned.txt"));
        pinned.pin(jar);
        List<Path> loaded = pinned.load();
        assertEquals(1, loaded.size());
        assertEquals(jar.toAbsolutePath().normalize(),
                loaded.get(0).toAbsolutePath().normalize());
    }

    @Test
    void pinSameFileTwiceKeepsSingleEntry(@TempDir Path dir) throws Exception {
        Path jar = Files.createFile(dir.resolve("a.jar"));
        PinnedFiles pinned = build(dir.resolve("pinned.txt"));
        pinned.pin(jar);
        pinned.pin(jar);
        assertEquals(1, pinned.load().size());
    }

    @Test
    void pinReordersMostRecentFirst(@TempDir Path dir) throws Exception {
        Path a = Files.createFile(dir.resolve("a.jar"));
        Path b = Files.createFile(dir.resolve("b.jar"));
        PinnedFiles pinned = build(dir.resolve("pinned.txt"));
        pinned.pin(a);
        pinned.pin(b);
        // Now re-pin a — it should move to the top.
        pinned.pin(a);
        List<Path> loaded = pinned.load();
        assertEquals(2, loaded.size());
        assertEquals(a.getFileName(), loaded.get(0).getFileName());
    }

    @Test
    void unpinRemovesEntry(@TempDir Path dir) throws Exception {
        Path a = Files.createFile(dir.resolve("a.jar"));
        Path b = Files.createFile(dir.resolve("b.jar"));
        PinnedFiles pinned = build(dir.resolve("pinned.txt"));
        pinned.pin(a);
        pinned.pin(b);
        pinned.unpin(a);
        assertEquals(1, pinned.load().size());
        assertFalse(pinned.isPinned(a));
        assertTrue(pinned.isPinned(b));
    }

    @Test
    void deletedPathsAreDroppedFromLoad(@TempDir Path dir) throws Exception {
        Path a = Files.createFile(dir.resolve("a.jar"));
        PinnedFiles pinned = build(dir.resolve("pinned.txt"));
        pinned.pin(a);
        Files.delete(a);
        // load() filters out non-existent files.
        assertTrue(pinned.load().isEmpty());
    }
}
