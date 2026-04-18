package dev.share.bytecodelens.service;

import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.JarResource;
import dev.share.bytecodelens.model.LoadedJar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.function.DoubleConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class JarLoader {

    private static final Logger log = LoggerFactory.getLogger(JarLoader.class);

    /** META-INF/versions/N/... — MR-jar versioned entry. Group 1 = version, group 2 = class internal path. */
    private static final Pattern MR_PATH = Pattern.compile("^META-INF/versions/(\\d+)/(.+)$");

    private final ClassAnalyzer analyzer = new ClassAnalyzer();

    /**
     * Fallback path for tampered jars: when {@link #load} fails due to corrupt zip structure,
     * the caller can try this loader which parses local file headers directly via
     * {@link RobustJarReader}.
     */
    public LoadedJar loadResilient(Path path, java.util.function.DoubleConsumer progress)
            throws IOException {
        long start = System.currentTimeMillis();
        var robust = new RobustJarReader().read(path);

        List<ClassEntry> classes = new ArrayList<>();
        List<ClassEntry> versionedClasses = new ArrayList<>();
        List<dev.share.bytecodelens.model.JarResource> resources = new ArrayList<>();
        long totalBytes = 0;

        int n = robust.entries().size();
        int done = 0;
        for (var e : robust.entries().entrySet()) {
            String name = e.getKey();
            byte[] bytes = e.getValue();
            if (name.endsWith(".class")) {
                int runtimeVersion = 0;
                Matcher m = MR_PATH.matcher(name);
                if (m.matches() && m.group(2).endsWith(".class")) {
                    try { runtimeVersion = Integer.parseInt(m.group(1)); }
                    catch (NumberFormatException ignored) {}
                }
                try {
                    ClassEntry ce = analyzer.analyze(bytes, runtimeVersion);
                    if (ce.isVersioned()) versionedClasses.add(ce);
                    else classes.add(ce);
                    totalBytes += ce.size();
                } catch (Exception ex) {
                    log.debug("Resilient: skip corrupt class {}", name);
                }
            } else {
                int slash = name.lastIndexOf('/');
                String simple = slash < 0 ? name : name.substring(slash + 1);
                resources.add(new dev.share.bytecodelens.model.JarResource(
                        name, simple, bytes.length,
                        dev.share.bytecodelens.model.JarResource.detect(name)));
            }
            done++;
            progress.accept((double) done / Math.max(1, n));
        }

        classes.sort((a, b) -> a.name().compareTo(b.name()));
        versionedClasses.sort((a, b) -> {
            int cmp = Integer.compare(a.runtimeVersion(), b.runtimeVersion());
            return cmp != 0 ? cmp : a.name().compareTo(b.name());
        });
        resources.sort((a, b) -> a.path().compareTo(b.path()));
        long elapsed = System.currentTimeMillis() - start;
        log.info("Resilient loader: {} classes ({} versioned) + {} resources via {}, diagnostics: {}",
                classes.size(), versionedClasses.size(), resources.size(),
                robust.strategyUsed(), robust.diagnostics());
        return new LoadedJar(path, List.copyOf(classes), List.copyOf(versionedClasses),
                List.copyOf(resources), totalBytes, elapsed);
    }

    public LoadedJar load(Path path, DoubleConsumer progress) throws IOException {
        long start = System.currentTimeMillis();
        if (!Files.isRegularFile(path)) {
            throw new IOException("Not a regular file: " + path);
        }

        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".class")) {
            byte[] bytes = Files.readAllBytes(path);
            ClassEntry entry = analyzer.analyze(bytes);
            progress.accept(1.0);
            return new LoadedJar(path, List.of(entry), List.of(), List.of(), bytes.length, System.currentTimeMillis() - start);
        }

        // JDK .jmod — a ZIP prefixed by a 4-byte "JM" header. Extract to a temp ZIP and
        // strip the "classes/" prefix that jmods use on all class entries.
        Path zipPath = path;
        boolean jmodMode = false;
        if (JmodSupport.isJmod(path)) {
            zipPath = JmodSupport.extractToTempZip(path);
            jmodMode = true;
            log.info("Loading .jmod — extracted to temp zip: {}", zipPath);
        }

        List<ClassEntry> classes = new ArrayList<>();
        List<ClassEntry> versionedClasses = new ArrayList<>();
        List<JarResource> resources = new ArrayList<>();
        long totalBytes = 0;

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            final boolean isJmod = jmodMode;
            record PendingClass(ZipEntry entry, int runtimeVersion, String logicalName) {}
            List<PendingClass> classEntries = new ArrayList<>();
            List<ZipEntry> resourceEntries = new ArrayList<>();
            java.util.Map<ZipEntry, String> logicalNames = new java.util.HashMap<>();

            for (var it = zip.entries(); it.hasMoreElements(); ) {
                ZipEntry e = it.nextElement();
                if (e.isDirectory()) continue;
                String p = e.getName();
                if (isJmod) {
                    // Jmod payloads lay class files under classes/, native libs under lib/, etc.
                    // Only the "classes/" prefix is relevant for our tree — skip lib/conf/bin.
                    if (p.startsWith("classes/")) {
                        p = p.substring("classes/".length());
                    } else {
                        // Keep as a resource but strip the top-level directory so paths stay short.
                        int slash = p.indexOf('/');
                        if (slash > 0) p = p.substring(slash + 1);
                    }
                }
                logicalNames.put(e, p);
                if (p.endsWith(".class")) {
                    Matcher m = MR_PATH.matcher(p);
                    if (m.matches() && m.group(2).endsWith(".class")) {
                        int version;
                        try {
                            version = Integer.parseInt(m.group(1));
                        } catch (NumberFormatException ex) {
                            resourceEntries.add(e);
                            continue;
                        }
                        classEntries.add(new PendingClass(e, version, p));
                    } else {
                        classEntries.add(new PendingClass(e, 0, p));
                    }
                } else {
                    resourceEntries.add(e);
                }
            }

            for (ZipEntry e : resourceEntries) {
                String p = logicalNames.getOrDefault(e, e.getName());
                int slash = p.lastIndexOf('/');
                String simple = slash < 0 ? p : p.substring(slash + 1);
                JarResource.ResourceKind kind = JarResource.detect(p);
                // When extension-based detection fails, peek at the magic bytes. Read at most
                // the first 256 bytes — enough for every format we recognise.
                if (kind == JarResource.ResourceKind.OTHER) {
                    try (var in = zip.getInputStream(e)) {
                        byte[] head = in.readNBytes(256);
                        JarResource.ResourceKind guess = JarResource.detectByContent(head);
                        if (guess != null) kind = guess;
                    } catch (Exception ignored) {
                        // Unreadable entry — leave OTHER.
                    }
                }
                resources.add(new JarResource(p, simple, e.getSize(), kind));
            }

            if (!classEntries.isEmpty()) {
                ForkJoinPool pool = new ForkJoinPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
                try {
                    List<ClassEntry> parsed = pool.submit(() -> classEntries.parallelStream()
                            .map(pc -> {
                                try {
                                    byte[] bytes = zip.getInputStream(pc.entry()).readAllBytes();
                                    return analyzer.analyze(bytes, pc.runtimeVersion());
                                } catch (Exception ex) {
                                    log.warn("Failed to parse {}: {}", pc.entry().getName(), ex.getMessage());
                                    return null;
                                }
                            })
                            .filter(c -> c != null)
                            .toList()).get();

                    for (ClassEntry c : parsed) {
                        totalBytes += c.size();
                        if (c.isVersioned()) {
                            versionedClasses.add(c);
                        } else {
                            classes.add(c);
                        }
                    }
                    progress.accept(1.0);
                } catch (Exception ex) {
                    throw new IOException("Parallel class parsing failed: " + ex.getMessage(), ex);
                } finally {
                    pool.shutdown();
                }
            } else {
                progress.accept(1.0);
            }
        }

        classes.sort((a, b) -> a.name().compareTo(b.name()));
        versionedClasses.sort((a, b) -> {
            int cmp = Integer.compare(a.runtimeVersion(), b.runtimeVersion());
            return cmp != 0 ? cmp : a.name().compareTo(b.name());
        });
        resources.sort((a, b) -> a.path().compareTo(b.path()));
        long elapsed = System.currentTimeMillis() - start;
        log.info("Loaded {} classes ({} versioned) + {} resources from {} in {}ms",
                classes.size(), versionedClasses.size(), resources.size(), path.getFileName(), elapsed);
        return new LoadedJar(path, List.copyOf(classes), List.copyOf(versionedClasses),
                List.copyOf(resources), totalBytes, elapsed);
    }
}
