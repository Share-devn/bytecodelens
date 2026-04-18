package dev.share.bytecodelens.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentJarBuilderTest {

    @Test
    void buildsAgentJarWithExpectedManifest() throws Exception {
        Path jar = AgentJarBuilder.build();
        assertTrue(Files.exists(jar), "agent jar should exist at " + jar);
        assertTrue(Files.size(jar) > 0);

        try (JarFile jf = new JarFile(jar.toFile())) {
            var manifest = jf.getManifest();
            assertNotNull(manifest);
            var attrs = manifest.getMainAttributes();
            assertEquals(BytecodeLensAgent.class.getName(), attrs.getValue("Agent-Class"));
            assertEquals(BytecodeLensAgent.class.getName(), attrs.getValue("Premain-Class"));
            assertEquals("true", attrs.getValue("Can-Redefine-Classes"));
            assertEquals("true", attrs.getValue("Can-Retransform-Classes"));

            var entry = jf.getEntry(
                    BytecodeLensAgent.class.getName().replace('.', '/') + ".class");
            assertNotNull(entry, "agent class missing from jar");
            assertTrue(entry.getSize() > 100, "agent class looks truncated");
        }
    }
}
