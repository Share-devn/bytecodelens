package dev.share.bytecodelens.service;

import dev.share.bytecodelens.decompile.ClassDecompiler;
import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.LoadedJar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Batch export for a {@link LoadedJar}: save as jar, or decompile every class into a
 * directory tree of {@code .java} / {@code .txt} files.
 *
 * <p>All operations report per-class progress via a {@link ProgressListener} and respect
 * a cooperative {@link AtomicBoolean} cancel flag so the UI can interrupt long runs.</p>
 */
public final class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    /** Progress callback. {@code current} is 1-based, {@code total} is the work unit count. */
    public interface ProgressListener {
        void update(int current, int total, String message);
    }

    public record ExportSummary(int exported, int failed, int cancelled) {
        public int total() { return exported + failed + cancelled; }
    }

    /**
     * Copy the source jar bytes to {@code dest} without modification.
     * Cheapest form of "not read-only" since we currently can't edit.
     */
    public void exportJar(LoadedJar jar, Path dest) throws IOException {
        Path src = jar.source();
        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
        log.info("Exported jar: {} -> {}", src, dest);
    }

    /**
     * Decompile every class in the jar into {@code outDir}. Directory structure mirrors
     * package layout: {@code com/foo/Bar.class} -> {@code outDir/com/foo/Bar.java}.
     * Multi-release classes are placed under {@code META-INF/versions/<N>/}.
     */
    public ExportSummary exportSources(LoadedJar jar, ClassDecompiler decompiler,
                                       Path outDir, ProgressListener progress,
                                       AtomicBoolean cancel) throws IOException {
        Files.createDirectories(outDir);
        return batchExport(jar, outDir, "java", progress, cancel,
                (entry) -> decompiler.decompile(entry.internalName(), entry.bytes()));
    }

    /**
     * Dump ASM Textifier output for every class in the jar into {@code outDir}.
     * {@code com/foo/Bar.class} -> {@code outDir/com/foo/Bar.txt}.
     */
    public ExportSummary exportBytecode(LoadedJar jar, BytecodePrinter printer,
                                        Path outDir, ProgressListener progress,
                                        AtomicBoolean cancel) throws IOException {
        Files.createDirectories(outDir);
        return batchExport(jar, outDir, "txt", progress, cancel,
                (entry) -> printer.print(entry.bytes()));
    }

    private ExportSummary batchExport(LoadedJar jar, Path outDir, String extension,
                                      ProgressListener progress, AtomicBoolean cancel,
                                      Transformer transformer) throws IOException {
        int total = jar.classCount() + jar.versionedClassCount();
        AtomicInteger done = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        // Root classes
        for (ClassEntry entry : jar.classes()) {
            if (cancel.get()) {
                return new ExportSummary(done.get() - failed.get(), failed.get(),
                        total - done.get());
            }
            Path target = outDir.resolve(entry.internalName() + "." + extension);
            writeOne(entry, target, transformer, failed);
            int current = done.incrementAndGet();
            progress.update(current, total, entry.name());
        }
        // Versioned classes — mirror MR-jar layout
        for (ClassEntry entry : jar.versionedClasses()) {
            if (cancel.get()) {
                return new ExportSummary(done.get() - failed.get(), failed.get(),
                        total - done.get());
            }
            Path target = outDir.resolve("META-INF/versions/" + entry.runtimeVersion())
                    .resolve(entry.internalName() + "." + extension);
            writeOne(entry, target, transformer, failed);
            int current = done.incrementAndGet();
            progress.update(current, total, "Java " + entry.runtimeVersion() + " / " + entry.name());
        }

        int totalDone = done.get();
        int totalFailed = failed.get();
        log.info("Batch export finished: {} exported, {} failed, total {}",
                totalDone - totalFailed, totalFailed, total);
        return new ExportSummary(totalDone - totalFailed, totalFailed, 0);
    }

    private void writeOne(ClassEntry entry, Path target, Transformer transformer, AtomicInteger failed) {
        try {
            Files.createDirectories(target.getParent());
            String text;
            try {
                text = transformer.transform(entry);
            } catch (Throwable ex) {
                failed.incrementAndGet();
                text = "// BytecodeLens: failed to transform " + entry.name() + System.lineSeparator()
                        + "// " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
                log.debug("Transform failed for {}: {}", entry.name(), ex.getMessage());
            }
            Files.writeString(target, text, StandardCharsets.UTF_8);
        } catch (IOException ioEx) {
            failed.incrementAndGet();
            log.warn("Failed to write {}: {}", target, ioEx.getMessage());
        }
    }

    @FunctionalInterface
    private interface Transformer {
        String transform(ClassEntry entry) throws Exception;
    }
}
