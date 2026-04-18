package dev.share.bytecodelens.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ResourceReader {

    public byte[] read(Path source, String entryPath) throws IOException {
        String lower = source.getFileName().toString().toLowerCase();
        if (lower.endsWith(".class")) {
            return Files.readAllBytes(source);
        }
        try (ZipFile zip = new ZipFile(source.toFile())) {
            ZipEntry entry = zip.getEntry(entryPath);
            if (entry == null) {
                throw new IOException("Resource not found in archive: " + entryPath);
            }
            try (var in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }
}
