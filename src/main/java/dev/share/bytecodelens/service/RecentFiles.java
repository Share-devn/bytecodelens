package dev.share.bytecodelens.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Persistent "Recently opened jars" list. Stored as plain text, one path per line,
 * in ~/.bytecodelens/recent.txt. Keeps up to {@link #MAX} entries, most recent first.
 */
public final class RecentFiles {

    private static final Logger log = LoggerFactory.getLogger(RecentFiles.class);
    private static final int MAX = 8;

    private final Path storage;

    public RecentFiles() {
        this(defaultStorage());
    }

    RecentFiles(Path storage) {
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
            log.debug("Failed to load recent files: {}", ex.getMessage());
            return List.of();
        }
    }

    public void add(Path path) {
        if (path == null) return;
        Path abs = path.toAbsolutePath().normalize();
        Set<String> ordered = new LinkedHashSet<>();
        ordered.add(abs.toString());
        for (Path p : load()) {
            Path existing = p.toAbsolutePath().normalize();
            if (!existing.equals(abs)) ordered.add(existing.toString());
        }
        List<String> trimmed = new ArrayList<>(ordered);
        if (trimmed.size() > MAX) trimmed = trimmed.subList(0, MAX);
        try {
            Files.createDirectories(storage.getParent());
            Files.write(storage, trimmed);
        } catch (IOException ex) {
            log.debug("Failed to save recent files: {}", ex.getMessage());
        }
    }

    public void clear() {
        try {
            Files.deleteIfExists(storage);
        } catch (IOException ignored) {
        }
    }

    private static Path defaultStorage() {
        String home = System.getProperty("user.home");
        return Paths.get(home, ".bytecodelens", "recent.txt");
    }
}
