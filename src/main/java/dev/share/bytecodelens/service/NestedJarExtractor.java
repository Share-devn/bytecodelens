package dev.share.bytecodelens.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class NestedJarExtractor {

    private static final Path TEMP_DIR = Paths.get(System.getProperty("java.io.tmpdir"), "bytecodelens-nested");

    public Path extract(Path parentJar, String entryPath) throws IOException {
        Files.createDirectories(TEMP_DIR);
        String safeName = entryPath.replaceAll("[\\\\/:]+", "_");
        Path target = TEMP_DIR.resolve(System.currentTimeMillis() + "_" + safeName);

        try (ZipFile zip = new ZipFile(parentJar.toFile())) {
            ZipEntry entry = zip.getEntry(entryPath);
            if (entry == null) {
                throw new IOException("Nested entry not found: " + entryPath);
            }
            try (var in = zip.getInputStream(entry)) {
                Files.copy(in, target);
            }
        }

        target.toFile().deleteOnExit();
        return target;
    }
}
