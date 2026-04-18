package dev.share.bytecodelens.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JarResourceContentDetectTest {

    @Test
    void detectsJavaClassMagic() {
        byte[] bytes = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 65};
        assertEquals(JarResource.ResourceKind.JAVA_CLASS, JarResource.detectByContent(bytes));
    }

    @Test
    void detectsZipAndPngAndJpeg() {
        byte[] zip = {'P', 'K', 0x03, 0x04, 0, 0};
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A};
        byte[] jpg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        assertEquals(JarResource.ResourceKind.NESTED_JAR, JarResource.detectByContent(zip));
        assertEquals(JarResource.ResourceKind.IMAGE, JarResource.detectByContent(png));
        assertEquals(JarResource.ResourceKind.IMAGE, JarResource.detectByContent(jpg));
    }

    @Test
    void detectsNativeBinaries() {
        byte[] elf = {0x7F, 'E', 'L', 'F', 2, 1, 1, 0};
        byte[] pe = {'M', 'Z', (byte) 0x90, 0};
        byte[] machO = {(byte) 0xCF, (byte) 0xFA, (byte) 0xED, (byte) 0xFE};
        assertEquals(JarResource.ResourceKind.NATIVE_SO, JarResource.detectByContent(elf));
        assertEquals(JarResource.ResourceKind.NATIVE_DLL, JarResource.detectByContent(pe));
        assertEquals(JarResource.ResourceKind.NATIVE_DYLIB, JarResource.detectByContent(machO));
    }

    @Test
    void detectsXmlAndJsonFromAsciiPrefix() {
        byte[] xml = "<?xml version=\"1.0\"?><root/>".getBytes();
        byte[] json = "  {\"foo\": 1, \"bar\": [1, 2, 3]}".getBytes();
        byte[] shell = "#!/bin/bash\necho hi\n".getBytes();
        assertEquals(JarResource.ResourceKind.XML, JarResource.detectByContent(xml));
        assertEquals(JarResource.ResourceKind.JSON, JarResource.detectByContent(json));
        assertEquals(JarResource.ResourceKind.SCRIPT, JarResource.detectByContent(shell));
    }

    @Test
    void returnsNullForUnknownBinaryBlobs() {
        byte[] gibberish = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
                (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
        assertNull(JarResource.detectByContent(gibberish));
    }

    @Test
    void returnsNullOnInputsTooShort() {
        assertNull(JarResource.detectByContent(null));
        assertNull(JarResource.detectByContent(new byte[0]));
        assertNull(JarResource.detectByContent(new byte[]{1, 2, 3}));
    }
}
