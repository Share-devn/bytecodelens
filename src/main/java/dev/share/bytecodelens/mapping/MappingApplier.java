package dev.share.bytecodelens.mapping;

import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.LoadedJar;
import dev.share.bytecodelens.service.ClassAnalyzer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites every class in a {@link LoadedJar} through ASM's {@link ClassRemapper} using the
 * supplied {@link MappingModel}. Returns a new {@link LoadedJar} whose classes have updated
 * internal names, descriptors and byte arrays — enough that downstream SearchIndex /
 * UsageIndex / HierarchyIndex rebuilds will see only the deobfuscated view.
 *
 * <p>Descriptor-less field mappings (SRG v1 / TSRG — no type info) are applied by matching
 * on owner + name only.</p>
 */
public final class MappingApplier {

    private static final Logger log = LoggerFactory.getLogger(MappingApplier.class);

    private final ClassAnalyzer analyzer = new ClassAnalyzer();

    public LoadedJar apply(LoadedJar jar, MappingModel model) {
        long start = System.currentTimeMillis();
        BytecodeLensRemapper remapper = new BytecodeLensRemapper(model);

        List<ClassEntry> remapped = new ArrayList<>(jar.classes().size());
        for (ClassEntry entry : jar.classes()) {
            remapped.add(remap(entry, remapper));
        }
        List<ClassEntry> remappedVersioned = new ArrayList<>(jar.versionedClasses().size());
        for (ClassEntry entry : jar.versionedClasses()) {
            remappedVersioned.add(remap(entry, remapper));
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("Mapping applied in {}ms: {} classes, {} fields, {} methods from {}",
                elapsed, model.classCount(), model.fieldCount(), model.methodCount(),
                model.sourceFormat());

        return new LoadedJar(jar.source(), List.copyOf(remapped), List.copyOf(remappedVersioned),
                jar.resources(), jar.totalBytes(), jar.loadTimeMs());
    }

    private ClassEntry remap(ClassEntry entry, BytecodeLensRemapper remapper) {
        try {
            ClassReader reader = new ClassReader(entry.bytes());
            ClassWriter writer = new ClassWriter(0);
            ClassRemapper cr = new ClassRemapper(writer, remapper);
            reader.accept(cr, 0);
            byte[] newBytes = writer.toByteArray();
            // Re-analyse to rebuild ClassEntry with updated name/interfaces/methodCount etc.
            return analyzer.analyze(newBytes, entry.runtimeVersion());
        } catch (Exception ex) {
            log.warn("Failed to remap {}: {}", entry.name(), ex.getMessage());
            return entry; // keep original
        }
    }

    /**
     * Wraps a {@link MappingModel} as an ASM {@link Remapper}. Falls back to original name
     * when no mapping exists, and tolerates descriptor-less field mappings.
     */
    private static final class BytecodeLensRemapper extends Remapper {
        private final MappingModel model;

        BytecodeLensRemapper(MappingModel model) { this.model = model; }

        @Override
        public String map(String internalName) {
            return model.mapClass(internalName);
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            String keyed = MappingModel.fieldKey(owner, name, descriptor);
            String hit = model.fieldMap().get(keyed);
            if (hit != null) return hit;
            // Fallback: mapping format had no descriptor (SRG v1 / TSRG). Try desc-less key.
            String descless = MappingModel.fieldKey(owner, name, "");
            String fallback = model.fieldMap().get(descless);
            return fallback != null ? fallback : name;
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            String keyed = MappingModel.methodKey(owner, name, descriptor);
            String hit = model.methodMap().get(keyed);
            return hit != null ? hit : name;
        }
    }
}
