package dev.share.bytecodelens.structure;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureDetectorTest {

    @Test
    void detectsNothingForEmptyOrNull() {
        assertNull(StructureDetector.detect(null));
        assertNull(StructureDetector.detect(new byte[0]));
        assertNull(StructureDetector.detect(new byte[]{0, 0, 0, 0}));
    }

    @Test
    void detectsClassFileByMagic() {
        byte[] header = buildMinimalClassBytes();
        String fmt = StructureDetector.detectFormatName(header);
        assertEquals("Java class file", fmt);
    }

    @Test
    void parsesClassFileMagicAndVersion() {
        byte[] bytes = buildMinimalClassBytes();
        StructureNode root = StructureDetector.detect(bytes);
        assertNotNull(root);
        boolean hasMagic = root.children().stream().anyMatch(n -> "magic".equals(n.label()));
        boolean hasVersion = root.children().stream().anyMatch(n -> "major_version".equals(n.label()));
        assertTrue(hasMagic, "no magic field");
        assertTrue(hasVersion, "no major_version field");
    }

    @Test
    void detectsZipByLocalFileHeader() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("hello.txt"));
            zos.write("hello".getBytes());
            zos.closeEntry();
        }
        byte[] zip = baos.toByteArray();
        StructureNode root = StructureDetector.detect(zip);
        assertNotNull(root);
        assertEquals("ZIP / JAR", root.label());
        // Expect at least LOC + Central Directory + EOCD containers.
        boolean hasLoc = root.children().stream().anyMatch(n -> n.label().startsWith("Local"));
        boolean hasEocd = root.children().stream().anyMatch(n -> n.label().contains("Central Directory")
                || n.label().contains("End of Central"));
        assertTrue(hasLoc);
        assertTrue(hasEocd);
    }

    @Test
    void detectsPngBySignature() {
        byte[] png = new byte[30];
        png[0] = (byte) 0x89;
        png[1] = 'P';
        png[2] = 'N';
        png[3] = 'G';
        png[4] = '\r';
        png[5] = '\n';
        png[6] = 0x1A;
        png[7] = '\n';
        // Put an "IEND" length=0, type=IEND, crc=0
        // offset 8..11: length = 0
        // offset 12..15: 'IEND'
        png[12] = 'I'; png[13] = 'E'; png[14] = 'N'; png[15] = 'D';
        // offset 16..19: crc = 0
        StructureNode root = StructureDetector.detect(png);
        assertNotNull(root);
        assertEquals("PNG", root.label());
        // signature node + chunks container
        assertEquals(2, root.children().size());
        assertEquals("signature", root.children().get(0).label());
    }

    @Test
    void nodesExposeByteRangesInsideFile() {
        byte[] bytes = buildMinimalClassBytes();
        StructureNode root = StructureDetector.detect(bytes);
        assertNotNull(root);
        StructureNode magic = root.children().get(0);
        assertEquals(0, magic.offset());
        assertEquals(4, magic.length());
        // leaves() walks the subtree; every leaf offset must stay within the file.
        for (StructureNode leaf : root.leaves()) {
            assertTrue(leaf.offset() >= 0);
            assertTrue(leaf.offset() + leaf.length() <= bytes.length,
                    "leaf " + leaf.label() + " out of range");
        }
    }

    @Test
    void parsesActualBytecodeLensOwnClass() throws Exception {
        // Use ASM to synthesise a real-but-tiny class; saves adding a test resource.
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC, "Foo", null,
                "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        byte[] bytes = cw.toByteArray();

        StructureNode root = StructureDetector.detect(bytes);
        assertNotNull(root);
        // Must parse without throwing and expose at least constant_pool + methods.
        List<String> topLabels = root.children().stream().map(StructureNode::label).toList();
        assertTrue(topLabels.contains("constant_pool"));
        assertTrue(topLabels.contains("methods"));
    }

    // ---- helpers ---------------------------------------------------------

    private static byte[] buildMinimalClassBytes() {
        // CAFEBABE + minor=0 + major=52 + cp_count=1 + access_flags + this + super
        // + interfaces_count=0 + fields_count=0 + methods_count=0 + attributes_count=0
        return new byte[] {
                (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE,
                0, 0,                // minor
                0, 52,               // major
                0, 1,                // cp_count = 1 (no actual entries)
                0, 0x21,             // access_flags
                0, 0,                // this_class
                0, 0,                // super_class
                0, 0,                // interfaces_count
                0, 0,                // fields_count
                0, 0,                // methods_count
                0, 0,                // attributes_count
        };
    }
}
