package dev.share.bytecodelens.compile;

import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.LoadedJar;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link JavaFileManager} that forwards everything to the standard filer except
 * {@code getJavaFileForOutput} — compiled bytecode is captured in memory instead of
 * being written to {@code .class} files on disk.
 *
 * <p>7a scope: source classpath resolution still uses the standard filer
 * (i.e. whatever's on the JVM classpath at run time). Widening it to see classes inside
 * a LoadedJar is the job of stage 7b.</p>
 */
final class InMemoryFileManager
        extends ForwardingJavaFileManager<StandardJavaFileManager> {

    /** className -> compiled bytecode. Populated by javac via {@link #getJavaFileForOutput}. */
    private final Map<String, ByteArrayOutputStream> outputs = new LinkedHashMap<>();

    /** package-name -> list of {@link JarClassFile}s visible on the CLASS_PATH. Never null. */
    private final Map<String, List<JarClassFile>> jarByPackage = new HashMap<>();

    /** Fast lookup for {@link #inferBinaryName}. Never null. */
    private final Map<JavaFileObject, String> jarBinaryNames = new HashMap<>();

    InMemoryFileManager(StandardJavaFileManager delegate) {
        this(delegate, null, null);
    }

    InMemoryFileManager(StandardJavaFileManager delegate, LoadedJar jarClasspath) {
        this(delegate, jarClasspath, null);
    }

    /**
     * @param phantoms map of internal name -> .class bytes for auto-generated stub classes;
     *                 these sit on CLASS_PATH alongside the real jar entries. May be null.
     */
    InMemoryFileManager(StandardJavaFileManager delegate, LoadedJar jarClasspath,
                        Map<String, byte[]> phantoms) {
        super(delegate);
        if (jarClasspath != null) {
            for (ClassEntry c : jarClasspath.classes()) {
                indexClass(c.internalName(), c.bytes(), c.name());
            }
        }
        if (phantoms != null) {
            for (var e : phantoms.entrySet()) {
                String internal = e.getKey();
                String dotted = internal.replace('/', '.');
                indexClass(internal, e.getValue(), dotted);
            }
        }
    }

    private void indexClass(String internalName, byte[] bytes, String binaryName) {
        int slash = internalName.lastIndexOf('/');
        String pkgDot = slash < 0 ? "" : internalName.substring(0, slash).replace('/', '.');
        JarClassFile f = new JarClassFile(internalName, bytes);
        jarByPackage.computeIfAbsent(pkgDot, k -> new ArrayList<>()).add(f);
        jarBinaryNames.put(f, binaryName);
    }

    @Override
    public JavaFileObject getJavaFileForOutput(Location location, String className,
                                               JavaFileObject.Kind kind, FileObject sibling)
            throws IOException {
        if (kind != JavaFileObject.Kind.CLASS) {
            return super.getJavaFileForOutput(location, className, kind, sibling);
        }
        return new InMemoryClassOutput(className);
    }

    @Override
    public Iterable<JavaFileObject> list(Location location, String packageName,
                                         Set<JavaFileObject.Kind> kinds, boolean recurse)
            throws IOException {
        Iterable<JavaFileObject> base = super.list(location, packageName, kinds, recurse);
        if (jarByPackage.isEmpty()) return base;
        if (location != StandardLocationRef.CLASS_PATH) return base;
        if (!kinds.contains(JavaFileObject.Kind.CLASS)) return base;

        // Put jar classes first so if a runtime classpath entry has the same binary name,
        // the jar version wins. javac iterates in order and takes the first hit.
        List<JavaFileObject> merged = new ArrayList<>();
        if (recurse) {
            for (var entry : jarByPackage.entrySet()) {
                if (entry.getKey().equals(packageName)
                        || entry.getKey().startsWith(packageName + ".")) {
                    merged.addAll(entry.getValue());
                }
            }
        } else {
            List<JarClassFile> pkg = jarByPackage.get(packageName);
            if (pkg != null) merged.addAll(pkg);
        }
        // Append everything the standard filer knows about — runtime classpath + JDK stdlib.
        // Jar entries above take precedence for duplicate names because javac picks the first match.
        base.forEach(merged::add);
        return merged;
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        if (jarBinaryNames.containsKey(file)) {
            return jarBinaryNames.get(file);
        }
        return super.inferBinaryName(location, file);
    }

    @Override
    public boolean hasLocation(Location location) {
        if (!jarByPackage.isEmpty() && location == StandardLocationRef.CLASS_PATH) return true;
        return super.hasLocation(location);
    }

    /** Snapshot of all emitted classes. Keys use slash-form internal names. */
    Map<String, byte[]> output() {
        Map<String, byte[]> map = new LinkedHashMap<>();
        for (var e : outputs.entrySet()) {
            map.put(e.getKey().replace('.', '/'), e.getValue().toByteArray());
        }
        return map;
    }

    /** Alias so we can compare Location identity without the full javax.tools.StandardLocation import. */
    private static final javax.tools.JavaFileManager.Location StandardLocationRef_CLASS_PATH =
            javax.tools.StandardLocation.CLASS_PATH;

    private static final class StandardLocationRef {
        static final javax.tools.JavaFileManager.Location CLASS_PATH = StandardLocationRef_CLASS_PATH;
    }

    /** In-memory JavaFileObject wrapping a class's raw bytes from a LoadedJar. */
    private static final class JarClassFile extends SimpleJavaFileObject {
        private final byte[] bytes;

        JarClassFile(String internalName, byte[] bytes) {
            super(URI.create("jar:///" + internalName + ".class"), Kind.CLASS);
            this.bytes = bytes;
        }

        @Override public InputStream openInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override public long getLastModified() { return 0L; }
    }

    private final class InMemoryClassOutput extends SimpleJavaFileObject {
        private final String className;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        InMemoryClassOutput(String className) {
            super(URI.create("mem:///" + className.replace('.', '/') + ".class"), Kind.CLASS);
            this.className = className;
        }

        @Override public OutputStream openOutputStream() {
            outputs.put(className, buffer);
            return buffer;
        }
    }
}
