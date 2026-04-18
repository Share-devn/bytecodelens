package dev.share.bytecodelens.ui.views;

import java.util.ArrayList;
import java.util.List;

/**
 * Byte-level binary diff. Produces a list of {@link Region}s marking where the two
 * byte arrays differ. We deliberately skip Myers/Hirschberg edit-distance: those
 * are needed when users think in lines, but byte diffs are used for patch analysis
 * where positional alignment matters (what changed in offset 0x100..0x200).
 *
 * <p>For two buffers of different sizes we pair-align up to the shorter length and
 * tag the rest as "only in {left|right}". A future enhancement could add a shift-
 * detect heuristic, but that'd already belong in Session 5+.</p>
 */
public final class HexDiff {

    public enum Side { SAME, CHANGED, ONLY_LEFT, ONLY_RIGHT }

    /** One byte range classified by diff outcome. */
    public record Region(int offset, int length, Side side) {}

    /**
     * Compute a run-length-encoded diff. Adjacent bytes with the same status are
     * merged into a single Region — makes rendering 1000×-diff regions cheap.
     */
    public static List<Region> diff(byte[] left, byte[] right) {
        List<Region> out = new ArrayList<>();
        int la = left == null ? 0 : left.length;
        int rb = right == null ? 0 : right.length;
        int common = Math.min(la, rb);

        // Walk the common prefix, accumulating same/changed runs.
        Side run = null;
        int runStart = 0;
        for (int i = 0; i < common; i++) {
            Side s = left[i] == right[i] ? Side.SAME : Side.CHANGED;
            if (run == null) { run = s; runStart = i; }
            else if (s != run) {
                out.add(new Region(runStart, i - runStart, run));
                run = s;
                runStart = i;
            }
        }
        if (run != null) out.add(new Region(runStart, common - runStart, run));

        // Tail: whichever side is longer gets tagged as "only in ..."
        if (la > rb) out.add(new Region(common, la - rb, Side.ONLY_LEFT));
        else if (rb > la) out.add(new Region(common, rb - la, Side.ONLY_RIGHT));

        return out;
    }

    /** Sum lengths of regions with the given status — quick stat for the UI. */
    public static int bytesWith(List<Region> regions, Side side) {
        int sum = 0;
        for (Region r : regions) if (r.side() == side) sum += r.length();
        return sum;
    }

    private HexDiff() {}
}
