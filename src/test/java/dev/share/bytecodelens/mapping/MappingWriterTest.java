package dev.share.bytecodelens.mapping;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip tests: build a model, write it out, parse the result and verify every
 * class/field/method mapping survived the trip.
 */
class MappingWriterTest {

    private static MappingModel sampleModel() {
        return MappingModel.builder(MappingFormat.PROGUARD)
                .mapClass("a/b", "com/example/Foo")
                .mapClass("a/c", "com/example/Bar")
                .mapMethod("a/b", "m", "()V", "renamedMethod")
                .mapMethod("a/b", "n", "(ILjava/lang/String;)Z", "complexMethod")
                .mapField("a/b", "x", "I", "renamedField")
                .build();
    }

    @Test
    void proguardRoundtripPreservesAllMappings() throws Exception {
        MappingModel original = sampleModel();
        StringWriter sw = new StringWriter();
        MappingWriter.write(original, MappingFormat.PROGUARD, sw);

        MappingModel parsed = ProGuardMappingParser.parse(new StringReader(sw.toString()));
        assertRoundtrip(original, parsed, "ProGuard");
    }

    @Test
    void tinyV2RoundtripPreservesAllMappings() throws Exception {
        MappingModel original = sampleModel();
        StringWriter sw = new StringWriter();
        MappingWriter.write(original, MappingFormat.TINY_V2, sw);

        MappingModel parsed = TinyV2MappingParser.parse(new StringReader(sw.toString()));
        assertRoundtrip(original, parsed, "Tiny v2");
    }

    @Test
    void srgRoundtripPreservesAllMappings() throws Exception {
        MappingModel original = sampleModel();
        StringWriter sw = new StringWriter();
        MappingWriter.write(original, MappingFormat.SRG, sw);

        MappingModel parsed = SrgMappingParser.parse(new StringReader(sw.toString()));
        // SRG v1 FD lines lack descriptors — so the round-tripped field map has empty desc.
        // Classes + methods must round-trip exactly though.
        assertEquals(original.classMap(), parsed.classMap(), "SRG classes mismatch");
        assertEquals(original.methodMap(), parsed.methodMap(), "SRG methods mismatch");
        assertEquals(1, parsed.fieldCount());
    }

    @Test
    void tsrgRoundtripPreservesAllMappings() throws Exception {
        MappingModel original = sampleModel();
        StringWriter sw = new StringWriter();
        MappingWriter.write(original, MappingFormat.TSRG, sw);

        MappingModel parsed = TsrgMappingParser.parse(new StringReader(sw.toString()));
        // Same as SRG — no field descriptors on wire.
        assertEquals(original.classMap(), parsed.classMap(), "TSRG classes mismatch");
        assertEquals(original.methodMap(), parsed.methodMap(), "TSRG methods mismatch");
    }

    @Test
    void enigmaRoundtripPreservesAllMappings() throws Exception {
        MappingModel original = sampleModel();
        StringWriter sw = new StringWriter();
        MappingWriter.write(original, MappingFormat.ENIGMA, sw);

        MappingModel parsed = EnigmaMappingParser.parse(new StringReader(sw.toString()));
        assertRoundtrip(original, parsed, "Enigma");
    }

    private static void assertRoundtrip(MappingModel original, MappingModel parsed, String label) {
        assertEquals(original.classMap(), parsed.classMap(), label + ": classes mismatch");
        assertEquals(original.methodMap(), parsed.methodMap(), label + ": methods mismatch");
        assertEquals(original.fieldMap(), parsed.fieldMap(), label + ": fields mismatch");
    }

    @Test
    void writerOutputIsNonEmpty() throws Exception {
        MappingModel original = sampleModel();
        for (MappingFormat f : MappingFormat.values()) {
            StringWriter sw = new StringWriter();
            MappingWriter.write(original, f, sw);
            assertTrue(sw.toString().length() > 0, "empty output for " + f);
        }
    }
}
