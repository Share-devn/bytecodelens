package dev.share.bytecodelens.ui.views;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the shared font-size broadcast used by Ctrl+wheel / Ctrl+plus/minus / Ctrl+0.
 * The static {@code SHARED_FONT_SIZE} property drives every open CodeView, so checking
 * clamp + set semantics is enough — instance-level {@code fontSize} just mirrors it.
 *
 * <p>{@code SimpleDoubleProperty} doesn't require the JavaFX runtime toolkit for
 * value-only operations (no listeners on Scene graph), so this test does not need
 * TestFX.</p>
 */
class CodeViewFontSizeTest {

    /** Always restore the default so tests don't leak font state across the suite. */
    @AfterEach
    void reset() {
        CodeView.setSharedFontSize(CodeView.DEFAULT_FONT_SIZE);
    }

    @Test
    void setSharedFontSizeStoresExactValueWithinRange() {
        CodeView.setSharedFontSize(16.5);
        assertEquals(16.5, CodeView.getSharedFontSize(), 0.0001);
    }

    @Test
    void setSharedFontSizeClampsBelowMin() {
        CodeView.setSharedFontSize(1.0);
        assertEquals(CodeView.MIN_FONT_SIZE, CodeView.getSharedFontSize(), 0.0001);
    }

    @Test
    void setSharedFontSizeClampsAboveMax() {
        CodeView.setSharedFontSize(10_000);
        assertEquals(CodeView.MAX_FONT_SIZE, CodeView.getSharedFontSize(), 0.0001);
    }

    @Test
    void defaultFontSizeIsReasonable() {
        assertEquals(13.0, CodeView.DEFAULT_FONT_SIZE, 0.0001);
    }

    @Test
    void minLessThanDefaultLessThanMax() {
        // Sanity: constants mustn't be reordered by a sloppy refactor.
        assert CodeView.MIN_FONT_SIZE < CodeView.DEFAULT_FONT_SIZE;
        assert CodeView.DEFAULT_FONT_SIZE < CodeView.MAX_FONT_SIZE;
    }
}
