package dev.share.bytecodelens.diff;

import java.nio.file.Path;
import java.util.List;

public record JarDiffResult(
        Path jarA,
        Path jarB,
        List<ClassDiff> classes,
        List<ResourceDiff> resources,
        long durationMs
) {
    public Stats stats() {
        int addedC = 0, removedC = 0, modifiedC = 0, unchangedC = 0;
        for (ClassDiff c : classes) {
            switch (c.change()) {
                case ADDED -> addedC++;
                case REMOVED -> removedC++;
                case MODIFIED -> modifiedC++;
                case UNCHANGED -> unchangedC++;
            }
        }
        int addedR = 0, removedR = 0, modifiedR = 0, unchangedR = 0;
        for (ResourceDiff r : resources) {
            switch (r.change()) {
                case ADDED -> addedR++;
                case REMOVED -> removedR++;
                case MODIFIED -> modifiedR++;
                case UNCHANGED -> unchangedR++;
            }
        }
        return new Stats(addedC, removedC, modifiedC, unchangedC, addedR, removedR, modifiedR, unchangedR);
    }

    public record Stats(
            int addedClasses, int removedClasses, int modifiedClasses, int unchangedClasses,
            int addedResources, int removedResources, int modifiedResources, int unchangedResources) {
    }
}
