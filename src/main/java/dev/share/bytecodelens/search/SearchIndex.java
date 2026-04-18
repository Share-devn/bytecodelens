package dev.share.bytecodelens.search;

import dev.share.bytecodelens.comments.CommentStore;
import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.JarResource;
import dev.share.bytecodelens.model.LoadedJar;
import dev.share.bytecodelens.service.BytecodePrinter;
import dev.share.bytecodelens.service.ResourceReader;
import org.objectweb.asm.ClassReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SearchIndex {

    private static final Logger log = LoggerFactory.getLogger(SearchIndex.class);

    public record ClassStringEntries(String classFqn, List<String> strings) {
    }

    public record ClassNameEntries(String classFqn, List<String> methodNames, List<String> fieldNames) {
    }

    public record ResourceTextEntry(String path, String text) {
    }

    /**
     * A single comment snapshot returned by {@link #comments()}. We decode the stored
     * key form (CLASS:fqn / METHOD:fqn:name:desc / FIELD:fqn:name:desc) here so the
     * search engine doesn't have to.
     */
    public record CommentEntry(String classFqn, String kind, String memberName,
                               String memberDesc, String text) {
    }

    private final LoadedJar jar;
    /**
     * Optional: when non-null, {@link #comments()} walks this store. Wiring is optional so
     * tests that don't need comment search (the majority) don't have to construct one.
     */
    private CommentStore commentStore;
    private final Map<String, byte[]> classBytes = new ConcurrentHashMap<>();
    private final List<ClassStringEntries> classStrings = Collections.synchronizedList(new ArrayList<>());
    private final List<ClassNameEntries> classNames = Collections.synchronizedList(new ArrayList<>());
    private final List<ResourceTextEntry> resourceTexts = Collections.synchronizedList(new ArrayList<>());
    private final BytecodePrinter printer = new BytecodePrinter();
    private final ResourceReader resourceReader = new ResourceReader();

    public SearchIndex(LoadedJar jar) {
        this.jar = jar;
    }

    public void build() {
        long start = System.currentTimeMillis();

        jar.classes().parallelStream().forEach(c -> {
            classBytes.put(c.name(), c.bytes());
            extractStrings(c);
            extractNames(c);
        });

        jar.resources().parallelStream().forEach(r -> {
            if (isTextual(r.kind())) {
                try {
                    byte[] bytes = resourceReader.read(jar.source(), r.path());
                    resourceTexts.add(new ResourceTextEntry(r.path(), new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
                } catch (Exception ex) {
                    log.debug("Failed to read resource {}: {}", r.path(), ex.getMessage());
                }
            }
        });

        log.info("Search index built in {}ms: {} classes, {} resources",
                System.currentTimeMillis() - start, classStrings.size(), resourceTexts.size());
    }

    public LoadedJar jar() {
        return jar;
    }

    public List<ClassStringEntries> classStrings() {
        return List.copyOf(classStrings);
    }

    public List<ClassNameEntries> classNames() {
        return List.copyOf(classNames);
    }

    public List<ResourceTextEntry> resourceTexts() {
        return List.copyOf(resourceTexts);
    }

    /** Plug in the workspace's CommentStore so comment search can walk it. */
    public void setCommentStore(CommentStore store) {
        this.commentStore = store;
    }

    /**
     * Snapshot of every comment in the workspace, decoded from the key form stored by
     * {@link CommentStore}. Returns an empty list if no store is wired. Not cached — the
     * comment set is tiny by construction (user-authored) and always-fresh results beat
     * cache invalidation for a trivial walk.
     */
    public List<CommentEntry> comments() {
        if (commentStore == null) return List.of();
        Map<String, String> all = commentStore.all();
        List<CommentEntry> out = new ArrayList<>(all.size());
        for (Map.Entry<String, String> e : all.entrySet()) {
            CommentEntry ce = decodeCommentKey(e.getKey(), e.getValue());
            if (ce != null) out.add(ce);
        }
        return out;
    }

    private static CommentEntry decodeCommentKey(String key, String text) {
        if (key == null || text == null) return null;
        int first = key.indexOf(':');
        if (first < 0) return null;
        String kind = key.substring(0, first);
        String rest = key.substring(first + 1);
        switch (kind) {
            case "CLASS" -> {
                return new CommentEntry(rest, "class", null, null, text);
            }
            case "METHOD", "FIELD" -> {
                // fqn : name : desc  — split from the RIGHT so fqn keeps its dots intact.
                int lastColon = rest.lastIndexOf(':');
                if (lastColon < 0) return null;
                String desc = rest.substring(lastColon + 1);
                String head = rest.substring(0, lastColon);
                int nameColon = head.lastIndexOf(':');
                if (nameColon < 0) return null;
                String fqn = head.substring(0, nameColon);
                String name = head.substring(nameColon + 1);
                return new CommentEntry(fqn, kind.toLowerCase(), name, desc, text);
            }
            default -> {
                return null;
            }
        }
    }

    public byte[] classBytesOf(String fqn) {
        return classBytes.get(fqn);
    }

    public String bytecodeOf(String fqn) {
        byte[] b = classBytes.get(fqn);
        if (b == null) return "";
        try {
            return printer.print(b);
        } catch (Exception ex) {
            return "";
        }
    }

    private void extractStrings(ClassEntry c) {
        List<String> strings = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(c.bytes()))) {
            int magic = in.readInt();
            if (magic != 0xCAFEBABE) return;
            in.readUnsignedShort();
            in.readUnsignedShort();
            int cpCount = in.readUnsignedShort();
            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> strings.add(in.readUTF());
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> {
                        in.readLong();
                        i++;
                    }
                    case 7, 8, 16, 19, 20 -> in.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> {
                        in.readUnsignedShort();
                        in.readUnsignedShort();
                    }
                    case 15 -> {
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                    }
                    default -> { return; }
                }
            }
        } catch (Exception ex) {
            return;
        }
        if (!strings.isEmpty()) {
            classStrings.add(new ClassStringEntries(c.name(), strings));
        }
    }

    private void extractNames(ClassEntry c) {
        List<String> methods = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        try {
            ClassReader reader = new ClassReader(c.bytes());
            var node = new org.objectweb.asm.tree.ClassNode();
            reader.accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (node.methods != null) for (var m : node.methods) methods.add(m.name);
            if (node.fields != null) for (var f : node.fields) fields.add(f.name);
        } catch (Exception ignored) {
        }
        classNames.add(new ClassNameEntries(c.name(), methods, fields));
    }

    private static boolean isTextual(JarResource.ResourceKind kind) {
        if (kind == null) return false;
        return switch (kind) {
            case MANIFEST, SERVICE, PROPERTIES, XML, JSON, YAML, TEXT, SQL, SCRIPT -> true;
            default -> false;
        };
    }
}
