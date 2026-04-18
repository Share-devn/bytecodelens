package dev.share.bytecodelens.transform;

import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.LoadedJar;
import dev.share.bytecodelens.service.ClassAnalyzer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Applies a sequence of transformations to every class in a {@link LoadedJar}.
 *
 * <p>Order: all {@link JarLevelTransformation}s run first (they may rewrite bytes wholesale,
 * e.g. renaming classes jar-wide); then all per-class {@link Transformation}s run as a single
 * pipeline so every class is read-transform-write only once.</p>
 */
public final class TransformationRunner {

    private static final Logger log = LoggerFactory.getLogger(TransformationRunner.class);

    private final ClassAnalyzer analyzer = new ClassAnalyzer();

    public TransformationResult run(LoadedJar jar,
                                    List<JarLevelTransformation> jarLevel,
                                    List<Transformation> perClass) {
        TransformContext ctx = new TransformContext();

        LoadedJar current = jar;
        for (JarLevelTransformation t : jarLevel) {
            ctx.enterPass(t.id());
            try {
                current = t.apply(current, ctx);
            } catch (Throwable ex) {
                log.warn("Jar-level pass {} failed: {}", t.id(), ex.toString());
            } finally {
                ctx.exitPass();
            }
        }

        AtomicInteger changed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<ClassEntry> newRoot = transformEntries(current.classes(), perClass, ctx, changed, failed);
        List<ClassEntry> newVersioned = transformEntries(current.versionedClasses(), perClass, ctx, changed, failed);

        LoadedJar newJar = new LoadedJar(current.source(), newRoot, newVersioned, current.resources(),
                current.totalBytes(), current.loadTimeMs());
        log.info("Transformations applied: {} classes changed, {} failed, jar-level={} per-class={}",
                changed.get(), failed.get(),
                jarLevel.stream().map(JarLevelTransformation::id).toList(),
                perClass.stream().map(Transformation::id).toList());
        return new TransformationResult(newJar, changed.get(), failed.get(), ctx);
    }

    private List<ClassEntry> transformEntries(List<ClassEntry> entries,
                                              List<Transformation> transformations,
                                              TransformContext ctx,
                                              AtomicInteger changed, AtomicInteger failed) {
        if (transformations.isEmpty()) return entries;
        ClassEntry[] out = new ClassEntry[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            ClassEntry entry = entries.get(i);
            try {
                ClassReader reader = new ClassReader(entry.bytes());
                ClassNode node = new ClassNode();
                reader.accept(node, 0);
                for (Transformation t : transformations) {
                    ctx.enterPass(t.id());
                    try {
                        t.transform(node, ctx);
                    } finally {
                        ctx.exitPass();
                    }
                }
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                node.accept(writer);
                byte[] newBytes = writer.toByteArray();
                if (!Arrays.equals(newBytes, entry.bytes())) {
                    out[i] = analyzer.analyze(newBytes, entry.runtimeVersion());
                    changed.incrementAndGet();
                } else {
                    out[i] = entry;
                }
            } catch (Throwable ex) {
                log.warn("Failed to transform {}: {}", entry.name(), ex.toString());
                out[i] = entry;
                failed.incrementAndGet();
            }
        }
        return List.of(out);
    }
}
