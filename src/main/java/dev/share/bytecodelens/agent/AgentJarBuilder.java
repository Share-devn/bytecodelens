package dev.share.bytecodelens.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/**
 * Produces the self-contained {@code bytecodelens-agent.jar} that BytecodeLens attaches
 * into target JVMs. Pulls {@link BytecodeLensAgent} out of our own classloader and writes
 * a fresh jar with the required {@code Agent-Class}/{@code Premain-Class} manifest entries.
 *
 * <p>Cached at {@code ~/.bytecodelens/agent/bytecodelens-agent.jar} — rebuilt each BytecodeLens
 * launch to stay in sync with any agent code changes between releases.</p>
 */
public final class AgentJarBuilder {

    private AgentJarBuilder() {}

    public static Path build() throws IOException {
        Path dir = Path.of(System.getProperty("user.home"), ".bytecodelens", "agent");
        Files.createDirectories(dir);
        Path jar = dir.resolve("bytecodelens-agent.jar");

        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.putValue("Agent-Class", BytecodeLensAgent.class.getName());
        main.putValue("Premain-Class", BytecodeLensAgent.class.getName());
        main.putValue("Can-Redefine-Classes", "true");
        main.putValue("Can-Retransform-Classes", "true");
        main.putValue("Can-Set-Native-Method-Prefix", "false");

        try (var out = Files.newOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(out, manifest)) {
            // Copy the agent class bytes out of our own classloader into the jar.
            copyClass(jos, BytecodeLensAgent.class);
        }
        return jar;
    }

    private static void copyClass(JarOutputStream jos, Class<?> cls) throws IOException {
        String resource = cls.getName().replace('.', '/') + ".class";
        try (InputStream is = cls.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) throw new IOException("Cannot locate " + resource + " on classpath");
            jos.putNextEntry(new ZipEntry(resource));
            is.transferTo(jos);
            jos.closeEntry();
        }
    }
}
