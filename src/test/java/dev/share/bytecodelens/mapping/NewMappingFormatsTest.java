package dev.share.bytecodelens.mapping;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

class NewMappingFormatsTest {

    // ---------- Tiny v1 ----------
    @Test
    void tinyV1ParsesClassFieldMethod() throws IOException {
        String src = String.join("\n",
                "v1\tofficial\tnamed",
                "CLASS\ta/b\tcom/example/Foo",
                "FIELD\ta/b\tI\tobfFld\tnewFld",
                "METHOD\ta/b\t()V\tobfM\tnewM");
        MappingModel m = TinyV1MappingParser.parse(new StringReader(src));
        assertEquals("com/example/Foo", m.classMap().get("a/b"));
        assertEquals("newFld", m.fieldMap().get("a/b.obfFld:I"));
        assertEquals("newM", m.methodMap().get("a/b.obfM()V"));
    }

    @Test
    void tinyV1DetectedByLoader() throws IOException {
        String src = "v1\tofficial\tnamed\nCLASS\ta\tFoo";
        var m = MappingLoader.loadString(src);
        assertEquals(MappingFormat.TINY_V1, m.sourceFormat());
    }

    // ---------- TSRG v2 ----------
    @Test
    void tsrgV2ParsesClassAndMembers() throws IOException {
        String src = String.join("\n",
                "tsrg2 obf srg",
                "a/b com/example/Foo",
                "\tfldObf fldNamed",
                "\tmObf ()V mNamed",
                "\t\tstatic",
                "\t\t0 paramObf paramNamed");
        MappingModel m = TsrgV2MappingParser.parse(new StringReader(src));
        assertEquals("com/example/Foo", m.classMap().get("a/b"));
        assertEquals("fldNamed", m.fieldMap().get("a/b.fldObf:"));
        assertEquals("mNamed", m.methodMap().get("a/b.mObf()V"));
    }

    @Test
    void tsrgV2DetectedByLoader() throws IOException {
        String src = "tsrg2 obf srg\na/b com/example/Foo";
        assertEquals(MappingFormat.TSRG_V2, MappingLoader.loadString(src).sourceFormat());
    }

    // ---------- XSRG ----------
    @Test
    void xsrgParsesFieldsWithDescriptors() throws IOException {
        String src = String.join("\n",
                "CL: a/b com/example/Foo",
                "FD: a/b/c I com/example/Foo/field I",
                "MD: a/b/m ()V com/example/Foo/method ()V");
        MappingModel m = XsrgMappingParser.parse(new StringReader(src));
        assertEquals("com/example/Foo", m.classMap().get("a/b"));
        assertEquals("field", m.fieldMap().get("a/b.c:I"));
        assertEquals("method", m.methodMap().get("a/b.m()V"));
    }

    @Test
    void xsrgDetectedAsXsrgWhenFdHasFiveTokens() throws IOException {
        String src = "FD: a/b/c I com/example/Foo/field I\n";
        assertEquals(MappingFormat.XSRG, MappingLoader.loadString(src).sourceFormat());
    }

    @Test
    void srgDetectedAsSrgWhenFdHasOnlyThreeTokens() throws IOException {
        String src = "FD: a/b/c com/example/Foo/field\n";
        assertEquals(MappingFormat.SRG, MappingLoader.loadString(src).sourceFormat());
    }

    // ---------- CSRG ----------
    @Test
    void csrgParsesAllRowTypes() throws IOException {
        String src = String.join("\n",
                "a/b com/example/Foo",
                "a/b c newFld",
                "a/b m ()V newMth");
        MappingModel m = CsrgMappingParser.parse(new StringReader(src));
        assertEquals("com/example/Foo", m.classMap().get("a/b"));
        assertEquals("newFld", m.fieldMap().get("a/b.c:"));
        assertEquals("newMth", m.methodMap().get("a/b.m()V"));
    }

    // ---------- JOBF ----------
    @Test
    void jobfParsesAllRowTypes() throws IOException {
        String src = String.join("\n",
                ".class_map a.b com/example/Foo",
                ".field_map a.b.c newFld",
                ".method_map a.b.m()V newMth");
        MappingModel m = JobfMappingParser.parse(new StringReader(src));
        assertEquals("com/example/Foo", m.classMap().get("a/b"));
        assertEquals("newFld", m.fieldMap().get("a/b.c:"));
        assertEquals("newMth", m.methodMap().get("a/b.m()V"));
    }

    @Test
    void jobfDetectedByLoader() throws IOException {
        String src = ".class_map a com/Foo\n";
        assertEquals(MappingFormat.JOBF, MappingLoader.loadString(src).sourceFormat());
    }

    // ---------- Recaf simple ----------
    @Test
    void recafParsesAllRowTypes() throws IOException {
        String src = String.join("\n",
                "a/b com/example/Foo",
                "a/b.fld newFld",
                "a/b.m()V newMth");
        MappingModel m = RecafMappingParser.parse(new StringReader(src));
        // class line — without a dot in the left token; treated as class rename.
        assertEquals("com/example/Foo", m.classMap().get("a/b"));
        assertEquals("newFld", m.fieldMap().get("a/b.fld:"));
        assertEquals("newMth", m.methodMap().get("a/b.m()V"));
    }

    @Test
    void recafDetectedByLoader() throws IOException {
        String src = "a/b.fld newFld\n";
        assertEquals(MappingFormat.RECAF, MappingLoader.loadString(src).sourceFormat());
    }

    // ---------- Existing formats still detected ----------
    @Test
    void existingFormatsStillRecognised() throws IOException {
        assertEquals(MappingFormat.PROGUARD, MappingLoader.loadString("com.foo.Bar -> a.b:\n").sourceFormat());
        assertEquals(MappingFormat.SRG, MappingLoader.loadString("CL: a com/Foo\n").sourceFormat());
        assertEquals(MappingFormat.TSRG, MappingLoader.loadString("a/b com/Foo\n").sourceFormat());
        assertEquals(MappingFormat.ENIGMA, MappingLoader.loadString("CLASS a com/Foo\n").sourceFormat());
        assertEquals(MappingFormat.TINY_V2, MappingLoader.loadString("tiny\t2\t0\tofficial\tnamed\n").sourceFormat());
    }
}
