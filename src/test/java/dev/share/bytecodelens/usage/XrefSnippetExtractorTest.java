package dev.share.bytecodelens.usage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class XrefSnippetExtractorTest {

    @Test
    void extractsRequestedLine() {
        String src = "line1\nline2\nline3";
        assertEquals("line1", XrefSnippetExtractor.extract(src, 1));
        assertEquals("line2", XrefSnippetExtractor.extract(src, 2));
        assertEquals("line3", XrefSnippetExtractor.extract(src, 3));
    }

    @Test
    void stripsLeadingIndentAndTrailingWhitespace() {
        String src = "    foo();   \n   bar();";
        assertEquals("foo();", XrefSnippetExtractor.extract(src, 1));
        assertEquals("bar();", XrefSnippetExtractor.extract(src, 2));
    }

    @Test
    void returnsNullForOutOfRange() {
        assertNull(XrefSnippetExtractor.extract("a\nb", 0));
        assertNull(XrefSnippetExtractor.extract("a\nb", 5));
        assertNull(XrefSnippetExtractor.extract("", 1));
        assertNull(XrefSnippetExtractor.extract(null, 1));
    }

    @Test
    void returnsNullForBlankLine() {
        assertNull(XrefSnippetExtractor.extract("a\n   \nb", 2));
    }

    @Test
    void truncatesLongLines() {
        String longLine = "x".repeat(500);
        String snippet = XrefSnippetExtractor.extract(longLine, 1);
        assertNotNull(snippet);
        assertTrue(snippet.length() <= XrefSnippetExtractor.MAX_LEN);
        assertTrue(snippet.endsWith("\u2026"));
    }

    @Test
    void handlesSingleLineWithoutNewline() {
        assertEquals("only", XrefSnippetExtractor.extract("only", 1));
    }

    @Test
    void handlesCrlf() {
        // \r is included in substring since splitter only consumes \n; clean() strips trailing \r.
        assertEquals("foo", XrefSnippetExtractor.extract("foo\r\nbar", 1));
    }
}
