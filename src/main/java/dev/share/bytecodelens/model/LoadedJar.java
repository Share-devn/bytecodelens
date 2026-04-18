package dev.share.bytecodelens.model;

import java.nio.file.Path;
import java.util.List;

public record LoadedJar(
        Path source,
        List<ClassEntry> classes,
        List<ClassEntry> versionedClasses,
        List<JarResource> resources,
        long totalBytes,
        long loadTimeMs
) {
    public int classCount() {
        return classes.size();
    }

    public int resourceCount() {
        return resources.size();
    }

    public int versionedClassCount() {
        return versionedClasses.size();
    }

    public boolean isMultiRelease() {
        return !versionedClasses.isEmpty();
    }
}
