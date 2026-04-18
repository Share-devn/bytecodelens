package dev.share.bytecodelens.ui.views;

import javafx.scene.Cursor;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CodeView extends BorderPane {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CodeView.class);

    private static final String MONO_FAMILY =
            "-fx-font-family: 'JetBrains Mono', 'Cascadia Code', 'Consolas', monospace;"
                    + "-fx-font-smoothing-type: lcd;";

    /** Default font size in points — mirrored with WorkspaceState#codeFontSize. */
    public static final double DEFAULT_FONT_SIZE = 13.0;
    public static final double MIN_FONT_SIZE = 8.0;
    public static final double MAX_FONT_SIZE = 40.0;

    /**
     * Broadcast of the active font size to all CodeView instances — one user-facing
     * change (Ctrl+wheel in any editor) updates every open tab at once without each
     * view polling a shared state holder.
     */
    private static final javafx.beans.property.DoubleProperty SHARED_FONT_SIZE =
            new javafx.beans.property.SimpleDoubleProperty(DEFAULT_FONT_SIZE);

    private static final String MATCH_CLASS = "match-highlight";
    private static final String MATCH_PRIMARY_CLASS = "match-highlight-primary";
    /** Secondary (all occurrences) hover highlight. */
    private static final String HOVER_CLASS = "hover-highlight";
    /** Primary (the word under the cursor) hover highlight. */
    private static final String HOVER_PRIMARY_CLASS = "hover-highlight-primary";
    /** Token under Ctrl/Cmd while hovering — rendered underlined to hint click-nav. */
    private static final String CTRL_UNDERLINE_CLASS = "ctrl-hover-underline";
    /**
     * Full-line tint for the paragraph containing the navigation target. Persistent until
     * the next jump / user dismissal, unlike the short {@code line-jump-flash} pulse.
     */
    private static final String FOCUSED_LINE_CLASS = "focused-line";
    /**
     * Subtle "current line" tint that follows the caret — same UX as VS/IntelliJ/JADX
     * where the paragraph containing the cursor is slightly darker than the rest so
     * your eye doesn't lose its place while reading.
     */
    private static final String CARET_LINE_CLASS = "caret-line";

    private final CodeArea area = new CodeArea();
    private final Function<String, StyleSpans<Collection<String>>> highlighter;
    private String currentText = "";
    private StyleSpans<Collection<String>> baseSpans;
    private final List<int[]> matches = new ArrayList<>();
    /** Occurrences of the word currently hovered — emptied as soon as the mouse leaves. */
    private final List<int[]> hoverOccurrences = new ArrayList<>();
    /** Index into {@link #hoverOccurrences} that marks the token right under the cursor. */
    private int hoverPrimaryIndex = -1;
    /** The word under the cursor while Ctrl is held — used to render the click-hint underline. */
    private int[] ctrlUnderlineRange;
    /** Pending show; cancelled if cursor moves off the identifier before the delay elapses. */
    private javafx.animation.PauseTransition hoverDelay;
    /** What hoverDelay will compute/display when it fires — captured at MOUSE_MOVED. */
    private String pendingHoverWord;
    /** Current font size in points. Starts from SHARED_FONT_SIZE so new tabs pick up user's zoom. */
    private double fontSize = SHARED_FONT_SIZE.get();
    /** Gutter display mode — cycled by clicking on a line number. */
    public enum GutterMode {
        LINE,      // ordinary 1-based line numbers (default)
        HIDDEN     // no gutter — maximises code width
    }
    private GutterMode gutterMode = GutterMode.LINE;
    /**
     * Paragraph index (0-based) that's treated as the "focused" line — rendered with a
     * permanent gutter-style highlight until the user clicks somewhere else. Mirrors
     * IntelliJ/JADX: go-to-definition lands you on a line and that line stays highlighted
     * so you don't lose it while reading.
     */
    private int focusedParagraph = -1;
    /**
     * Tracks which paragraph currently carries the pulse style, so we can clear it when
     * a new jump fires before the previous PauseTransition completed. Without this, each
     * new jump leaves a dangling pulse class on the old paragraph and they accumulate —
     * symptom: "several lines highlighted after clicking main/start rapidly".
     */
    private int pulseParagraph = -1;
    /**
     * Paragraph currently under the caret. Updated by a listener on
     * {@link org.fxmisc.richtext.CodeArea#currentParagraphProperty()} so we follow every
     * caret movement (click, arrow keys, Home/End, programmatic moveTo).
     */
    private int caretParagraph = -1;
    /**
     * The last scroll/line-flash PauseTransition — cancelled if a new jump starts so
     * stale restores from the previous jump don't clobber the current highlight.
     */
    private javafx.animation.PauseTransition pendingFlashReset;
    /** Handler invoked when the user Ctrl/Cmd-clicks on a word; receives that word. */
    private Consumer<String> onSymbolClick;
    /**
     * Handler invoked when the user double-clicks on a word. Unlike onSymbolClick (which
     * requires Ctrl held), this fires on a plain double-click — JADX-style navigation to
     * declaration. Shares the same receiver semantics: if null, we let RichTextFX select
     * the word as normal.
     */
    private Consumer<String> onSymbolDoubleClick;
    /** Handler invoked when the user asks for "go to definition" from the context menu. */
    private Consumer<String> onGoToSymbol;
    /**
     * Handler invoked for JADX-style "Find Usages" hotkey (default {@code X}) on the
     * identifier under the caret. Separate from {@link #onSymbolClick} so the same
     * CodeView can wire navigation + xref independently.
     */
    private Consumer<String> onFindUsages;
    /** Handler invoked when the user picks "Search" on a word from the context menu. */
    private Consumer<String> onSearchSymbol;
    /**
     * Builds the right-click context menu for a given word. Returning {@code null} (or no
     * handler installed) falls back to the generic Copy/Select all menu.
     */
    private java.util.function.Function<String, javafx.scene.control.ContextMenu> contextMenuBuilder;
    /** The menu currently on screen — we hide it before showing a new one to avoid stacking. */
    private javafx.scene.control.ContextMenu activeContextMenu;

    public CodeView(Function<String, StyleSpans<Collection<String>>> highlighter) {
        this.highlighter = highlighter;
        area.setEditable(false);
        // Force the caret to render even though the area is read-only. RichTextFX's
        // default CaretVisibility.AUTO only shows the caret when the area is both
        // editable AND focused — for a read-only code viewer (like Visual Studio /
        // IntelliJ / JADX) users still expect to see a blinking caret where they click
        // so they know the editor is responsive and has focus, and so keyboard-nav /
        // copy operations have a clear anchor point.
        try {
            area.setShowCaret(org.fxmisc.richtext.Caret.CaretVisibility.ON);
        } catch (Throwable ignored) {
            // Very old RichTextFX without this API — caret will just behave per default.
        }
        applyGutterMode();  // installs the paragraph graphic factory according to gutterMode
        area.getStyleClass().add("code-view");
        applyFontStyle();
        // Track the shared font size: any other CodeView (or controller restore) updates it
        // and every live view follows along without manual wiring.
        SHARED_FONT_SIZE.addListener((obs, oldV, newV) -> {
            double nv = newV.doubleValue();
            if (Math.abs(nv - fontSize) > 0.01) {
                fontSize = nv;
                applyFontStyle();
            }
        });
        // Caret-line highlight: whenever the caret moves to a new paragraph (click, arrow
        // keys, programmatic moveTo) we restyle the old + new paragraphs so the line under
        // the caret is subtly darker. Classic VS/IntelliJ/JADX cue — helps the eye keep
        // its place while scrolling.
        area.currentParagraphProperty().addListener((obs, oldP, newP) -> {
            if (newP == null) return;
            int prev = caretParagraph;
            caretParagraph = newP;
            // Restyle the old line to drop the caret class; restyle the new one to add it.
            // Both go through syncParagraphStyleClasses which also preserves any overlapping
            // focused-line / pulse classes.
            if (prev >= 0 && prev != caretParagraph) restyleParagraph(prev);
            restyleParagraph(caretParagraph);
        });
        setCenter(new VirtualizedScrollPane<>(area));

        // Click on identifier -> navigation hook.
        //  * Ctrl/Cmd+single-click : onSymbolClick (long-standing, survives double-click
        //    detection because JavaFX only sets clickCount=2 on the second click).
        //  * Plain double-click    : onSymbolDoubleClick — JADX parity. The user double-
        //    clicks a method name / field name in the decompiled source and we jump to
        //    the declaration. Without this, plain double-click just selects the word
        //    which isn't very useful.
        // We read the word at the click *location* (not the caret position) because the
        // caret may still be at an old spot if the user hasn't pressed primary yet.
        area.addEventHandler(MouseEvent.MOUSE_CLICKED, ev -> {
            if (ev.getButton() != MouseButton.PRIMARY) return;
            String word = wordAtScenePoint(ev.getX(), ev.getY());
            if (word == null || word.isEmpty()) return;
            if (ev.isShortcutDown() && onSymbolClick != null) {
                onSymbolClick.accept(word);
                ev.consume();
                return;
            }
            if (ev.getClickCount() == 2 && onSymbolDoubleClick != null && !ev.isShortcutDown()) {
                onSymbolDoubleClick.accept(word);
                ev.consume();
            }
        });
        // Show a hand cursor while Ctrl/Cmd is held, as a hint that click will navigate.
        // When Ctrl is released we clear our override so RichTextFX falls back to its own
        // text-I-beam cursor for reading + selecting.
        area.addEventHandler(MouseEvent.MOUSE_MOVED, ev -> {
            if (ev.isShortcutDown() && onSymbolClick != null) {
                area.setCursor(Cursor.HAND);
            } else {
                area.setCursor(null);
            }
        });
        // When Ctrl is released, KEY_RELEASED fires globally — make sure to reset cursor so
        // the hand-cursor doesn't linger after Ctrl up.
        area.addEventHandler(javafx.scene.input.KeyEvent.KEY_RELEASED, ev -> {
            if (!ev.isShortcutDown()) area.setCursor(null);
        });

        // Right-click: IDE-style context menu — rebuilt each time with the word under
        // cursor. If the controller has plugged in a smarter builder, use that; otherwise
        // fall back to the basic generic menu (Copy / Select all).
        //
        // JavaFX doesn't auto-close popups we created through ContextMenu#show (as opposed to
        // setContextMenu). We hide the previous one manually and clear the reference when the
        // user dismisses it, so rapid right-clicking can't stack menus.
        area.setOnContextMenuRequested(ev -> {
            if (activeContextMenu != null && activeContextMenu.isShowing()) {
                activeContextMenu.hide();
            }
            // IDE convention: right-click moves the caret to the click position first so
            // "word under cursor" lines up with what the user visually clicked on. Without
            // this, secondary-only clicks read the stale caret (from last primary click)
            // and build a menu for the wrong word — forcing the two-click dance.
            moveCaretToMouse(ev.getX(), ev.getY());
            String word = selectedOrWordUnderCaret();
            javafx.scene.control.ContextMenu built = null;
            if (contextMenuBuilder != null && word != null) {
                try {
                    built = contextMenuBuilder.apply(word);
                } catch (Exception ignored) {}
            }
            final javafx.scene.control.ContextMenu menu = built != null ? built : buildFallbackMenu();
            activeContextMenu = menu;
            menu.setOnHidden(h -> {
                if (activeContextMenu == menu) activeContextMenu = null;
            });
            menu.show(area, ev.getScreenX(), ev.getScreenY());
            ev.consume();
        });
        // Close any stray menu on a primary click or Esc. Prevents orphans if focus
        // transitions strangely (e.g. after a dialog).
        area.addEventFilter(MouseEvent.MOUSE_PRESSED, ev -> {
            if (activeContextMenu != null && activeContextMenu.isShowing()
                    && ev.getButton() != MouseButton.SECONDARY) {
                activeContextMenu.hide();
            }
        });

        // Ctrl+wheel — adjust font size. This is a filter so VirtualizedScrollPane
        // doesn't swallow the scroll before we see it. We consume the event so the
        // scroll doesn't also move the viewport.
        area.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, ev -> {
            if (!ev.isShortcutDown()) return;
            double dy = ev.getDeltaY();
            if (dy == 0) return;
            // Move in 1-point steps regardless of wheel tick size — smoother than raw delta.
            adjustFontSize(dy > 0 ? +1 : -1);
            ev.consume();
        });

        // Ctrl++ / Ctrl+- / Ctrl+0 keybindings matching JADX + IntelliJ conventions.
        // Listen via EVENT_FILTER on the CodeArea so we run before CodeArea's own
        // KEY_PRESSED handler (which may otherwise insert a literal '+' character
        // on some keyboard layouts).
        area.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, ev -> {
            if (!ev.isShortcutDown()) return;
            switch (ev.getCode()) {
                case PLUS, ADD, EQUALS -> { adjustFontSize(+1); ev.consume(); }
                case MINUS, SUBTRACT, UNDERSCORE -> { adjustFontSize(-1); ev.consume(); }
                case DIGIT0, NUMPAD0 -> { setFontSize(DEFAULT_FONT_SIZE); ev.consume(); }
                default -> { /* fall through to default editor handling */ }
            }
        });

        // JADX-style find-usages hotkey (default X, remappable via Preferences → Keymap).
        // We install as a KEY_PRESSED handler rather than event filter so the default
        // RichTextFX key handling runs first — prevents X from being swallowed when the
        // user is actually trying to type in an editable view, but our area is read-only
        // anyway so the filter is fine here.
        area.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, ev -> {
            if (onFindUsages == null || findUsagesCombo == null) return;
            if (!findUsagesCombo.match(ev)) return;
            String word = selectedOrWordUnderCaret();
            if (word == null || word.isEmpty()) return;
            onFindUsages.accept(word);
            ev.consume();
        });

        // Hover highlight: pick up the identifier under the pointer and mark every
        // other occurrence in the document. A short delay (180ms) avoids repaints on
        // every pixel of mouse movement; the delay is cancelled as soon as we move
        // to a different word or off identifiers entirely.
        area.addEventHandler(MouseEvent.MOUSE_MOVED, ev -> {
            // Already handled Ctrl-hand-cursor earlier; here we pile on the hover logic.
            String word = wordAtScenePoint(ev.getX(), ev.getY());
            // Ctrl+hover underline (separate from the hover-highlight below — shown
            // only while Ctrl is held, only on identifiers that have a click handler).
            if (ev.isShortcutDown() && word != null && onSymbolClick != null) {
                int[] range = rangeAtScenePoint(ev.getX(), ev.getY());
                if (range != null && !sameRange(range, ctrlUnderlineRange)) {
                    ctrlUnderlineRange = range;
                    repaintOverlays();
                }
            } else if (ctrlUnderlineRange != null) {
                ctrlUnderlineRange = null;
                repaintOverlays();
            }

            // Hover highlight — debounce so we don't scan the whole document 60×/sec
            // during a mouse drag. If the word hasn't changed, there's nothing to do.
            if (word == null) {
                if (!hoverOccurrences.isEmpty()) {
                    clearHoverHighlight();
                }
                pendingHoverWord = null;
                if (hoverDelay != null) hoverDelay.stop();
                return;
            }
            if (word.equals(pendingHoverWord)) return;
            pendingHoverWord = word;
            if (hoverDelay != null) hoverDelay.stop();
            hoverDelay = new javafx.animation.PauseTransition(javafx.util.Duration.millis(180));
            final String targetWord = word;
            final double evX = ev.getX(), evY = ev.getY();
            hoverDelay.setOnFinished(e -> {
                // Recompute the primary range at fire-time — the mouse may have moved
                // within the same word but the primary index can shift.
                int[] primary = rangeAtScenePoint(evX, evY);
                applyHoverHighlight(targetWord, primary);
            });
            hoverDelay.play();
        });

        // Moving off the control clears any lingering hover state.
        area.addEventHandler(MouseEvent.MOUSE_EXITED, ev -> {
            clearHoverHighlight();
            if (ctrlUnderlineRange != null) {
                ctrlUnderlineRange = null;
                repaintOverlays();
            }
            pendingHoverWord = null;
            if (hoverDelay != null) hoverDelay.stop();
        });
    }

    /** Applies the full font style (family + smoothing + size). */
    private void applyFontStyle() {
        // Format in ROOT locale — Double.toString on ru_RU returns "16,0" with a comma,
        // which JavaFX CSS rejects. Explicit ROOT locale gives us "16.0" across systems.
        area.setStyle(MONO_FAMILY + " -fx-font-size: "
                + String.format(java.util.Locale.ROOT, "%.1f", fontSize) + "px;");
    }

    /**
     * Install the paragraph graphic factory matching the current {@link #gutterMode}.
     * The stock {@code LineNumberFactory.get(area)} returns a plain-label node per
     * paragraph — we wrap it so the user can left-click it to cycle modes, as in
     * JADX v1.5.x (click line-number area toggles simple ↔ debug line numbers).
     */
    private void applyGutterMode() {
        if (gutterMode == GutterMode.HIDDEN) {
            area.setParagraphGraphicFactory(null);
            return;
        }
        java.util.function.IntFunction<javafx.scene.Node> base = LineNumberFactory.get(area);
        area.setParagraphGraphicFactory(paragraph -> {
            javafx.scene.Node node = base.apply(paragraph);
            if (node != null) {
                node.setOnMouseClicked(ev -> {
                    if (ev.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                        cycleGutterMode();
                    }
                });
                node.setCursor(javafx.scene.Cursor.HAND);
            }
            return node;
        });
    }

    /** Rotate through gutter modes. Currently LINE → HIDDEN → LINE. */
    public void cycleGutterMode() {
        gutterMode = gutterMode == GutterMode.LINE ? GutterMode.HIDDEN : GutterMode.LINE;
        applyGutterMode();
    }

    public GutterMode gutterMode() { return gutterMode; }

    public void setGutterMode(GutterMode mode) {
        if (mode == null || mode == gutterMode) return;
        gutterMode = mode;
        applyGutterMode();
    }

    /** Clamped absolute size setter — persists to the shared broadcast property. */
    public void setFontSize(double points) {
        double clamped = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, points));
        if (Math.abs(clamped - fontSize) < 0.01) return;
        fontSize = clamped;
        applyFontStyle();
        if (Math.abs(SHARED_FONT_SIZE.get() - clamped) > 0.01) {
            SHARED_FONT_SIZE.set(clamped);
        }
    }

    private void adjustFontSize(int deltaPoints) {
        setFontSize(fontSize + deltaPoints);
    }

    public double getFontSize() { return fontSize; }

    /** Global setter used on workspace restore — every open CodeView follows suit. */
    public static void setSharedFontSize(double points) {
        double clamped = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, points));
        SHARED_FONT_SIZE.set(clamped);
    }

    public static double getSharedFontSize() { return SHARED_FONT_SIZE.get(); }

    /** Returns the identifier at the given (x,y), or null if we're not over an identifier. */
    private String wordAtScenePoint(double localX, double localY) {
        int[] r = rangeAtScenePoint(localX, localY);
        if (r == null) return null;
        return currentText.substring(r[0], r[1]);
    }

    /** Returns the [start,end) range of the identifier under the given point, or null. */
    private int[] rangeAtScenePoint(double localX, double localY) {
        try {
            var hit = area.hit(localX, localY);
            if (hit == null) return null;
            int idx = hit.getInsertionIndex();
            if (idx < 0 || currentText.isEmpty()) return null;
            // Step to a character that's actually part of an identifier. hit() can return
            // an insertion index sitting between two chars, which fails the isIdPart test
            // even though the user is clearly hovering an identifier.
            int probe = Math.min(idx, currentText.length() - 1);
            if (!isIdPart(currentText.charAt(probe))) {
                if (probe > 0 && isIdPart(currentText.charAt(probe - 1))) probe--;
                else return null;
            }
            int start = probe, end = probe + 1;
            while (start > 0 && isIdPart(currentText.charAt(start - 1))) start--;
            while (end < currentText.length() && isIdPart(currentText.charAt(end))) end++;
            // Skip one-letter noise and pure numerics — highlighting `i` everywhere in
            // a method body is just noise.
            if (end - start < 2) return null;
            if (Character.isDigit(currentText.charAt(start))) return null;
            return new int[]{start, end};
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean sameRange(int[] a, int[] b) {
        if (a == null || b == null) return a == b;
        return a[0] == b[0] && a[1] == b[1];
    }

    /**
     * Whole-word scan for every occurrence of {@code word} in {@code haystack}. "Whole word"
     * means the char immediately before and after must not be an identifier part — so
     * "list" will not match inside "checklist". Stops after {@code cap} occurrences to bound
     * UI work on pathological documents. Exposed package-private for unit testing.
     */
    static List<int[]> findWholeWordOccurrences(String haystack, String word, int cap) {
        List<int[]> out = new ArrayList<>();
        if (haystack == null || word == null || word.isEmpty() || haystack.length() < word.length()) {
            return out;
        }
        int from = 0;
        int wl = word.length();
        while (from <= haystack.length() - wl) {
            int i = haystack.indexOf(word, from);
            if (i < 0) break;
            boolean leftOk = i == 0 || !isIdPart(haystack.charAt(i - 1));
            boolean rightOk = i + wl == haystack.length() || !isIdPart(haystack.charAt(i + wl));
            if (leftOk && rightOk) {
                out.add(new int[]{i, i + wl});
                if (out.size() >= cap) break;
            }
            from = i + wl;
        }
        return out;
    }

    /** Scan currentText for occurrences of the word and repaint with hover overlay. */
    private void applyHoverHighlight(String word, int[] primaryRange) {
        if (word == null || word.isEmpty() || currentText.isEmpty()) {
            clearHoverHighlight();
            return;
        }
        hoverOccurrences.clear();
        hoverOccurrences.addAll(findWholeWordOccurrences(currentText, word, 2000));
        hoverPrimaryIndex = -1;
        if (primaryRange != null) {
            for (int i = 0; i < hoverOccurrences.size(); i++) {
                int[] r = hoverOccurrences.get(i);
                if (r[0] == primaryRange[0] && r[1] == primaryRange[1]) {
                    hoverPrimaryIndex = i;
                    break;
                }
            }
        }
        // If primary wasn't in the list, mark the first so the user still sees something.
        if (hoverPrimaryIndex < 0 && !hoverOccurrences.isEmpty()) hoverPrimaryIndex = 0;
        repaintOverlays();
    }

    private void clearHoverHighlight() {
        if (hoverOccurrences.isEmpty() && ctrlUnderlineRange == null) return;
        hoverOccurrences.clear();
        hoverPrimaryIndex = -1;
        repaintOverlays();
    }

    /**
     * Apply paragraph-style state to match {@link #focusedParagraph}: clear the class
     * from whatever paragraph had it before, add it to the new one. Idempotent — calling
     * repeatedly with the same focused paragraph is a no-op.
     *
     * <p>This used to be a text overlay (via StyleSpans), but multi-paragraph selections
     * need to fall inside exactly one paragraph: text overlays only cover the characters
     * in that paragraph, not its full box, so short lines looked "cut off" and long lines
     * bled into the next paragraph when the \n was included. Paragraph-style sidesteps
     * all of that.</p>
     */
    private void syncFocusedParagraphStyle(int previousPara) {
        if (previousPara >= 0 && previousPara != focusedParagraph) {
            restyleParagraph(previousPara);
        }
        if (focusedParagraph >= 0) {
            restyleParagraph(focusedParagraph);
        }
    }

    /**
     * Recompute the paragraph CSS class list for a given paragraph by OR-ing every class
     * it currently qualifies for: {@link #CARET_LINE_CLASS} if the caret is here, plus
     * {@link #FOCUSED_LINE_CLASS} / pulse variant if this is the navigation target. A
     * single source-of-truth recomputation avoids the class-ordering bugs that plagued
     * earlier per-class mutations.
     */
    private void restyleParagraph(int paragraph) {
        if (paragraph < 0 || paragraph >= area.getParagraphs().size()) return;
        List<String> classes = new ArrayList<>(3);
        if (paragraph == caretParagraph) classes.add(CARET_LINE_CLASS);
        if (paragraph == focusedParagraph) classes.add(FOCUSED_LINE_CLASS);
        if (paragraph == pulseParagraph) classes.add(FOCUSED_LINE_CLASS + "-pulse");
        try {
            area.setParagraphStyle(paragraph, classes);
        } catch (Throwable ignored) {}
    }

    /** Public: set the focused line (1-based) and repaint so it highlights permanently. */
    public void setFocusedLine(int oneBasedLine) {
        int clamped = Math.max(0, oneBasedLine - 1);
        if (clamped >= area.getParagraphs().size()) return;
        int prev = focusedParagraph;
        focusedParagraph = clamped;
        syncFocusedParagraphStyle(prev);
    }

    /** Public: clear the focused-line highlight. */
    public void clearFocusedLine() {
        if (focusedParagraph < 0) return;
        int prev = focusedParagraph;
        focusedParagraph = -1;
        syncFocusedParagraphStyle(prev);
    }

    /**
     * Rebuilds the full overlay (matches + hover + Ctrl underline) and pushes it to
     * the CodeArea in one shot, so independent UI sources can't stomp on each other.
     */
    private void repaintOverlays() {
        if (currentText.isEmpty()) return;
        StyleSpans<Collection<String>> base = baseSpans != null ? baseSpans : emptySpans(currentText.length());

        // Collect all interval-classed spans, sort by start, then paint sequentially.
        // Order of classes on overlap matters: a match-highlight on top of hover-highlight
        // should still show the match colour, so we add match-classes last (CSS cascade).
        List<StyledInterval> intervals = new ArrayList<>(
                matches.size() + hoverOccurrences.size() + 1);
        // Note: focused-line is NOT a text overlay anymore. It's applied as a per-paragraph
        // style via CodeArea#setParagraphStyle in syncFocusedParagraphStyle(). That way it
        // paints the full paragraph background (including leading whitespace + trailing
        // empty space), not just the text glyphs, which is what users expect from an
        // IDE "current line" highlight.
        for (int i = 0; i < hoverOccurrences.size(); i++) {
            int[] r = hoverOccurrences.get(i);
            String cls = (i == hoverPrimaryIndex) ? HOVER_PRIMARY_CLASS : HOVER_CLASS;
            intervals.add(new StyledInterval(r[0], r[1], cls));
        }
        if (ctrlUnderlineRange != null) {
            intervals.add(new StyledInterval(
                    ctrlUnderlineRange[0], ctrlUnderlineRange[1], CTRL_UNDERLINE_CLASS));
        }
        for (int i = 0; i < matches.size(); i++) {
            int[] r = matches.get(i);
            String cls = (i == 0) ? MATCH_PRIMARY_CLASS : MATCH_CLASS;
            intervals.add(new StyledInterval(r[0], r[1], cls));
        }
        if (intervals.isEmpty()) {
            area.setStyleSpans(0, base);
            return;
        }
        // Merge intervals into a single continuous StyleSpans using the classed-overlay
        // approach: produce a flat array of [pos -> classes-active-here] and walk it.
        int[] events = new int[intervals.size() * 2];
        for (int i = 0; i < intervals.size(); i++) {
            events[2 * i] = intervals.get(i).start;
            events[2 * i + 1] = intervals.get(i).end;
        }
        java.util.Arrays.sort(events);
        StyleSpansBuilder<Collection<String>> ov = new StyleSpansBuilder<>();
        int cursor = 0;
        // Unique sorted boundaries.
        int[] bounds = new int[events.length + 2];
        bounds[0] = 0;
        int bi = 1;
        int prev = -1;
        for (int e : events) {
            if (e != prev && e > 0 && e <= currentText.length()) {
                bounds[bi++] = e;
                prev = e;
            }
        }
        if (bounds[bi - 1] != currentText.length()) bounds[bi++] = currentText.length();
        for (int b = 0; b < bi - 1; b++) {
            int a = bounds[b], c = bounds[b + 1];
            if (c <= a) continue;
            List<String> classes = new ArrayList<>(2);
            for (StyledInterval si : intervals) {
                if (si.start <= a && si.end >= c) classes.add(si.cssClass);
            }
            if (a > cursor) ov.add(Collections.emptyList(), a - cursor);
            ov.add(classes, c - a);
            cursor = c;
        }
        if (cursor < currentText.length()) {
            ov.add(Collections.emptyList(), currentText.length() - cursor);
        }
        StyleSpans<Collection<String>> overlay = ov.create();
        StyleSpans<Collection<String>> merged = base.overlay(overlay, (b, o) -> {
            if (o.isEmpty()) return b;
            List<String> combined = new ArrayList<>(b);
            combined.addAll(o);
            return combined;
        });
        area.setStyleSpans(0, merged);
    }

    private static final class StyledInterval {
        final int start, end;
        final String cssClass;
        StyledInterval(int s, int e, String c) { start = s; end = e; cssClass = c; }
    }

    /** Translate local pointer coordinates to a caret position inside the area. */
    private void moveCaretToMouse(double localX, double localY) {
        try {
            var hit = area.hit(localX, localY);
            if (hit != null) {
                int idx = hit.getInsertionIndex();
                // Don't clobber an existing selection — if the user has text selected,
                // the menu should act on that selection, not reposition the caret.
                String sel = area.getSelectedText();
                if (sel == null || sel.isEmpty()) {
                    area.moveTo(idx);
                }
            }
        } catch (Throwable ignored) {
            // RichTextFX hit() can throw on empty doc / weird coords — no-op fallback.
        }
    }

    public void setContextMenuBuilder(
            java.util.function.Function<String, javafx.scene.control.ContextMenu> builder) {
        this.contextMenuBuilder = builder;
    }

    private javafx.scene.control.ContextMenu buildFallbackMenu() {
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        javafx.scene.control.MenuItem copy = new javafx.scene.control.MenuItem("Copy");
        copy.setOnAction(e -> copySelectedToClipboard());
        copy.setDisable(area.getSelectedText() == null || area.getSelectedText().isEmpty());
        javafx.scene.control.MenuItem selectAll = new javafx.scene.control.MenuItem("Select all");
        selectAll.setOnAction(e -> area.selectAll());
        menu.getItems().addAll(copy, selectAll);
        return menu;
    }

    /** Public helper so controller-built menus can reuse our copy logic. */
    public void copySelectedToClipboard() {
        String sel = area.getSelectedText();
        if (sel != null && !sel.isEmpty()) {
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(
                    java.util.Map.of(javafx.scene.input.DataFormat.PLAIN_TEXT, sel));
        }
    }

    public String selectedText() { return area.getSelectedText(); }
    public int caretPosition() { return area.getCaretPosition(); }
    public String textSnapshot() { return currentText; }

    public void selectAllText() { area.selectAll(); }

    /** Returns the current selection, or the identifier at the caret, or null. */
    private String selectedOrWordUnderCaret() {
        String sel = area.getSelectedText();
        if (sel != null && !sel.isEmpty()) return sel;
        return wordAt(currentText, area.getCaretPosition());
    }

    public String wordUnderCaret() { return selectedOrWordUnderCaret(); }

    public void setOnGoToSymbol(Consumer<String> handler) { this.onGoToSymbol = handler; }
    /**
     * Install a "Find Usages" handler fired when the user presses X (default) or
     * whatever key is configured for {@code navigate.find.usages}. The keymap-driven
     * KeyCombination is looked up by the controller and passed in via
     * {@link #setFindUsagesCombination(javafx.scene.input.KeyCombination)}.
     */
    public void setOnFindUsages(Consumer<String> handler) { this.onFindUsages = handler; }

    /** Current KeyCombination that triggers find-usages. Defaults to a plain "X". */
    private javafx.scene.input.KeyCombination findUsagesCombo =
            new javafx.scene.input.KeyCodeCombination(javafx.scene.input.KeyCode.X);

    public void setFindUsagesCombination(javafx.scene.input.KeyCombination combo) {
        this.findUsagesCombo = combo;
    }
    public void setOnSearchSymbol(Consumer<String> handler) { this.onSearchSymbol = handler; }

    /**
     * Overlay a set of 1-based line numbers with an {@code .error-line} style, so the user
     * can see at a glance where javac barfed. Pass an empty list to clear.
     */
    public void markErrorLines(java.util.Collection<Integer> lines) {
        if (baseSpans == null || currentText.isEmpty()) return;
        StyleSpansBuilder<Collection<String>> ov = new StyleSpansBuilder<>();
        java.util.Set<Integer> errSet = new java.util.HashSet<>(lines);
        int cursor = 0;
        String[] paragraphs = currentText.split("\n", -1);
        for (int i = 0; i < paragraphs.length; i++) {
            int paraLen = paragraphs[i].length() + (i == paragraphs.length - 1 ? 0 : 1);
            if (errSet.contains(i + 1)) {
                ov.add(java.util.List.of("error-line"), paraLen);
            } else {
                ov.add(Collections.emptyList(), paraLen);
            }
            cursor += paraLen;
        }
        StyleSpans<Collection<String>> overlay = ov.create();
        StyleSpans<Collection<String>> merged = baseSpans.overlay(overlay, (b, o) -> {
            if (o.isEmpty()) return b;
            List<String> combined = new ArrayList<>(b);
            combined.addAll(o);
            return combined;
        });
        area.setStyleSpans(0, merged);
    }

    public void clearErrorLines() {
        if (baseSpans != null) area.setStyleSpans(0, baseSpans);
    }

    /** Register a handler for Ctrl/Cmd-click; pass {@code null} to disable navigation. */
    public void setOnSymbolClick(Consumer<String> handler) {
        this.onSymbolClick = handler;
    }

    /**
     * Register a handler for plain double-click on an identifier — JADX-style "jump to
     * declaration". Independent of {@link #setOnSymbolClick}; both can be installed. Pass
     * {@code null} to fall back to RichTextFX's default word-selection behaviour.
     */
    public void setOnSymbolDoubleClick(Consumer<String> handler) {
        this.onSymbolDoubleClick = handler;
    }

    /**
     * Extracts the Java identifier at the given caret position, or null if the caret
     * isn't inside an identifier (e.g. on whitespace / punctuation).
     */
    private static String wordAt(String text, int pos) {
        if (text == null || text.isEmpty() || pos < 0 || pos > text.length()) return null;
        int start = pos;
        int end = pos;
        // If caret is at end-of-text OR on a non-identifier char but the previous one is
        // part of an identifier, step back — click right after the word should still select it.
        boolean atEnd = start >= text.length();
        boolean onNonId = !atEnd && !isIdPart(text.charAt(start));
        if (start > 0 && (atEnd || onNonId) && isIdPart(text.charAt(start - 1))) {
            start--;
            end = start;
        }
        if (start >= text.length() || !isIdPart(text.charAt(start))) return null;
        while (start > 0 && isIdPart(text.charAt(start - 1))) start--;
        while (end < text.length() && isIdPart(text.charAt(end))) end++;
        return text.substring(start, end);
    }

    private static boolean isIdPart(char c) {
        return Character.isJavaIdentifierPart(c);
    }

    public void setText(String text) {
        currentText = text == null ? "" : text;
        // replaceText resets paragraph styles implicitly (the old paragraphs are gone),
        // so we don't need to clear focused/pulse paragraphs by hand beyond resetting
        // our tracking fields. But keep the stops on in-flight transitions to avoid
        // their callbacks firing against the new document.
        area.replaceText(currentText);
        baseSpans = null;
        matches.clear();
        hoverOccurrences.clear();
        hoverPrimaryIndex = -1;
        ctrlUnderlineRange = null;
        focusedParagraph = -1;
        pulseParagraph = -1;
        caretParagraph = -1;  // will be re-observed by the listener on the first caret move
        if (pendingFlashReset != null) {
            pendingFlashReset.stop();
            pendingFlashReset = null;
        }
        if (!currentText.isEmpty() && currentText.length() < 500_000) {
            try {
                baseSpans = highlighter.apply(currentText);
                area.setStyleSpans(0, baseSpans);
            } catch (Exception ignored) {
            }
        }
        area.moveTo(0);
        area.scrollToPixel(0, 0);
    }

    public String getText() {
        return area.getText();
    }

    public void setEditable(boolean editable) {
        area.setEditable(editable);
        if (editable) {
            area.textProperty().addListener((obs, old, now) -> {
                if (now == null || now.isEmpty() || now.length() > 500_000) return;
                try {
                    currentText = now;
                    baseSpans = highlighter.apply(now);
                    area.setStyleSpans(0, baseSpans);
                } catch (Exception ignored) {
                }
            });
        }
    }

    public int matchCount() {
        return matches.size();
    }

    public void clearHighlight() {
        matches.clear();
        repaintOverlays();
    }

    public void goToMatch(int index) {
        if (matches.isEmpty()) return;
        int i = ((index % matches.size()) + matches.size()) % matches.size();
        int[] iv = matches.get(i);
        area.moveTo(iv[0]);
        area.requestFollowCaret();
    }

    public CodeArea area() {
        return area;
    }

    public void applyHighlight(HighlightRequest request) {
        matches.clear();
        if (request == null || request.isEmpty() || currentText.isEmpty()) {
            repaintOverlays();
            return;
        }
        // LITERAL_WORD bypasses regex entirely — pure whole-word scan using the same helper
        // as hover-highlight. Regex \b doesn't fit: Pattern.quote('$') produces \Q$\E which
        // invalidates \b on either side, and Java's \b uses a different word-char set than
        // Java.isJavaIdentifierPart. Direct string scan is simpler and correct.
        if (request.mode() == HighlightRequest.Mode.LITERAL_WORD) {
            matches.addAll(findWholeWordOccurrences(currentText, request.query(), 5000));
            repaintOverlays();
            return;
        }
        Pattern pattern;
        try {
            if (request.mode() == HighlightRequest.Mode.REGEX) {
                pattern = Pattern.compile(request.query(), Pattern.CASE_INSENSITIVE);
            } else {
                pattern = Pattern.compile(Pattern.quote(request.query()), Pattern.CASE_INSENSITIVE);
            }
        } catch (Exception ex) {
            repaintOverlays();
            return;
        }

        Matcher m = pattern.matcher(currentText);
        while (m.find()) {
            if (m.end() > m.start()) {
                matches.add(new int[]{m.start(), m.end()});
            }
            if (matches.size() > 5000) break;
        }
        repaintOverlays();
    }

    public void goToLine(int line) {
        if (line <= 0) return;
        int paragraphs = area.getParagraphs().size();
        int clamped = Math.max(0, Math.min(paragraphs - 1, line - 1));
        // Caret position inside the paragraph — column 0 is fine for line-jump.
        int caretOffset;
        try {
            caretOffset = area.getAbsolutePosition(clamped, 0);
        } catch (Throwable ex) {
            caretOffset = -1;
        }
        performJumpTo(clamped, caretOffset);
    }

    /**
     * Shared jump-to-paragraph path used by both goToLine and goToFirstMatch.
     *
     * <p>Sets a PERSISTENT focused-line highlight (via {@link #focusedParagraph}) which
     * lives on top of {@code baseSpans} through the unified {@link #repaintOverlays}
     * pipeline — so it can't be erased by other paint sources (match-highlight, hover).
     * On top of that, a short {@code line-jump-flash} pulse draws attention for the first
     * ~1.5 s; when the pulse clears, the quieter persistent highlight remains.</p>
     *
     * <p>Also brings the caret to {@code caretOffset} (-1 = skip caret), requests focus on
     * the CodeArea so the caret is visible (otherwise the tree/side panel still has focus
     * and the caret is hidden), and performs the 0/80/200 ms retry dance needed during
     * tab switches.</p>
     */
    private void performJumpTo(int paragraph, int caretOffset) {
        if (paragraph < 0) return;
        // Cancel any in-flight flash from a previous jump and strip pulse class from the
        // paragraph that was pulsing, otherwise rapid successive jumps leave multiple
        // paragraphs glowing simultaneously.
        if (pendingFlashReset != null) {
            pendingFlashReset.stop();
            pendingFlashReset = null;
        }
        int prevPulse = pulseParagraph;
        int prevFocused = focusedParagraph;
        focusedParagraph = paragraph;
        pulseParagraph = paragraph;
        // Restyle affected paragraphs — restyleParagraph folds caret/focused/pulse into a
        // single consistent class list, so we don't need to special-case combinations.
        if (prevPulse >= 0 && prevPulse != paragraph) restyleParagraph(prevPulse);
        if (prevFocused >= 0 && prevFocused != paragraph) restyleParagraph(prevFocused);
        restyleParagraph(paragraph);

        pendingFlashReset = new javafx.animation.PauseTransition(
                javafx.util.Duration.millis(1500));
        final int pulseP = paragraph;
        pendingFlashReset.setOnFinished(e -> {
            if (pulseParagraph == pulseP) {
                pulseParagraph = -1;
                restyleParagraph(pulseP);
            }
            pendingFlashReset = null;
        });
        pendingFlashReset.play();

        // Schedule the caret + scroll. Three tries at 0/80/200 ms reliably land even
        // during tab switches when the viewport hasn't been sized yet.
        Runnable move = () -> {
            if (caretOffset >= 0) area.moveTo(caretOffset);
            scrollParagraphIntoView(paragraph);
            // Focus the code area so the caret actually renders. Without this, a jump
            // triggered from the tree leaves focus on the tree and the caret stays
            // invisible — user reported "нажимая на поле не появляется вот эта черточка".
            area.requestFocus();
        };
        javafx.application.Platform.runLater(move);
        javafx.animation.PauseTransition p80 = new javafx.animation.PauseTransition(
                javafx.util.Duration.millis(80));
        p80.setOnFinished(e -> move.run());
        p80.play();
        javafx.animation.PauseTransition p200 = new javafx.animation.PauseTransition(
                javafx.util.Duration.millis(200));
        p200.setOnFinished(e -> move.run());
        p200.play();
    }

    /**
     * Programmatic scroll so paragraph {@code idx} is visible. Uses
     * {@link org.fxmisc.richtext.CodeArea#showParagraphInViewport(int)} which actually moves
     * the scroll pane — {@code requestFollowCaret} only takes effect after the next caret
     * movement (it doesn't scroll on its own).
     */
    private void scrollParagraphIntoView(int paragraphIdx) {
        try {
            area.showParagraphInViewport(paragraphIdx);
            // After bringing it into view, center it vertically — showParagraphInViewport
            // only ensures visibility, not centered position, so long methods still have
            // the caret at the very top/bottom edge of the viewport.
            area.showParagraphAtCenter(paragraphIdx);
        } catch (Throwable ignored) {
            // Older RichTextFX without these methods — fall back to caret-follow.
            area.requestFollowCaret();
        }
    }

    public void goToFirstMatch() {
        if (matches.isEmpty()) return;
        int[] first = matches.get(0);
        int paragraph;
        try {
            paragraph = area.offsetToPosition(first[0],
                    org.fxmisc.richtext.model.TwoDimensional.Bias.Forward).getMajor();
        } catch (Throwable ex) {
            paragraph = -1;
        }
        if (paragraph < 0) return;
        performJumpTo(paragraph, first[0]);
    }

    private void restoreBase() {
        if (baseSpans != null) {
            area.setStyleSpans(0, baseSpans);
        }
    }

    private static StyleSpans<Collection<String>> emptySpans(int length) {
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        b.add(Collections.emptyList(), length);
        return b.create();
    }
}
