package dev.share.bytecodelens.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Persistent list of pinned jar paths, shown at the top of the Start Page.
 * Same on-disk format as {@link RecentFiles} (plain text, one path per line), in
 * {@code ~/.bytecodelens/pinned.txt}. No max — user chooses what to pin.
 *
 * <p>Ordering: most-recently pinned first. Pinning an already-pinned path moves it
 * to the top (same as macOS Dock).</p>
 */
public final class PinnedFiles {

    private static final Logger log = LoggerFactory.getLogger(PinnedFiles.class);

    private final Path storage;

    public PinnedFiles() {
        this(defaultStorage());
    }

    PinnedFiles(Path storage) {
        this.storage = storage;
    }

    public List<Path> load() {
        if (!Files.exists(storage)) return List.of();
        try {
            List<String> lines = Files.readAllLines(storage);
            List<Path> out = new ArrayList<>();
            for (String l : lines) {
                if (l == null || l.isBlank()) continue;
                Path p = Paths.get(l.trim());
                if (Files.exists(p)) out.add(p);
            }
            return out;
        } catch (IOException ex) {
            log.debug("Failed to load pinned files: {}", ex.getMessage());
            return List.of();
        }
    }

    public void pin(Path path) {
        if (path == null) return;
        Path abs = path.toAbsolutePath().normalize();
        Set<String> ordered = new LinkedHashSet<>();
        ordered.add(abs.toString());
        for (Path p : load()) {
            Path existing = p.toAbsolutePath().normalize();
            if (!existing.equals(abs)) ordered.add(existing.toString());
        }
        write(new ArrayList<>(ordered));
    }

    public void unpin(Path path) {
        if (path == null) return;
        Path abs = path.toAbsolutePath().normalize();
        List<String> remaining = new ArrayList<>();
        for (Path p : load()) {
            Path existing = p.toAbsolutePath().normalize();
            if (!existing.equals(abs)) remaining.add(existing.toString());
        }
        write(remaining);
    }

    public boolean isPinned(Path path) {
        if (path == null) return false;
        Path abs = path.toAbsolutePath().normalize();
        for (Path p : load()) {
            if (p.toAbsolutePath().normalize().equals(abs)) return true;
        }
        return false;
    }

    private void write(List<String> lines) {
        try {
            Files.createDirectories(storage.getParent());
            Files.write(storage, lines);
        } catch (IOException ex) {
            log.debug("Failed to save pinned files: {}", ex.getMessage());
        }
    }

    private static Path defaultStorage() {
        String home = System.getProperty("user.home");
        return Paths.get(home, ".bytecodelens", "pinned.txt");
    }
}
