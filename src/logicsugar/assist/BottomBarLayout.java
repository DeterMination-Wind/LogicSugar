package logicsugar.assist;

import java.util.Arrays;

/**
 * Pure, headless-testable row packing for the logic editor's bottom bar.
 *
 * <p>Every bar cell has a fixed width (one button = 160px, the instruction-budget label =
 * 196px including its side pads), so deciding how many rows the bar needs is plain
 * arithmetic. {@code SugarLogicDialog} uses this instead of assuming the vanilla one-row
 * layout always fits: that assumption is what let fixed-width cells overflow their row and
 * paint over their neighbours on windows narrower than the controls' total width.</p>
 *
 * <p>Widths must always be in the same units as {@code available}: use {@link #scaledWidths} to
 * turn the declared (unscaled) widths the dialog writes into arc cells into the scene units the
 * measured bar width is expressed in. Mixing the two is what let a phone pack seven cells into a
 * single row and push its outermost buttons off screen.</p>
 *
 * <p>No game classes are touched here on purpose — the packing rules are the part worth
 * pinning with a self-test, and they must stay runnable in a headless JVM.</p>
 */
public final class BottomBarLayout{
    private BottomBarLayout(){}

    /**
     * Greedily packs the given cell widths into rows no wider than {@code available}.
     *
     * <p>A cell wider than {@code available} keeps a row of its own instead of being dropped
     * or merged: callers then still render every cell (clipped at worst, never overlapping
     * another one). A non-positive {@code available} therefore degrades to one cell per row,
     * which is the only honest layout for a bar that narrow.</p>
     *
     * @param available width usable by one row, already excluding the row's own padding
     * @param widths    cell widths in render order; must not be null
     * @return the number of cells on each row, in order; entries sum to {@code widths.length}
     */
    public static int[] packRows(float available, float[] widths){
        if(widths.length == 0) return new int[0];

        int[] counts = new int[widths.length];
        int rows = 0;
        int count = 0;
        float used = 0f;
        for(float width : widths){
            float cell = Math.max(0f, width);
            // a row that already holds something must not be widened past the bar; an empty
            // row always accepts the cell, so oversized cells only ever get a row to themselves
            if(count > 0 && used + cell > available){
                counts[rows++] = count;
                count = 0;
                used = 0f;
            }
            count++;
            used += cell;
        }
        counts[rows++] = count;
        return Arrays.copyOf(counts, rows);
    }

    /**
     * Converts declared cell widths into the scene units the layout really uses.
     *
     * <p>arc multiplies every {@code Cell} size, pad and margin by its UI scale ({@code Scl.scl}),
     * so a button written as {@code 160} is 400 scene units wide on a phone with a 2.5x scale.
     * Packing must compare the measured bar width against those <em>real</em> widths: the 2026-09
     * phone report packed the declared numbers against the real bar, believed seven cells shared
     * a 1260-unit phone row that truly needed 2800, and let the centred overflow push the first
     * and last buttons (back / add) off screen.</p>
     *
     * @param scale  current UI scale, i.e. {@code Scl.scl(1f)}
     * @param widths declared cell widths, in unscaled UI units
     * @return the same widths multiplied by {@code scale}
     */
    public static float[] scaledWidths(float scale, float[] widths){
        float factor = Math.max(0f, scale);
        float[] scaled = new float[widths.length];
        for(int i = 0; i < widths.length; i++){
            scaled[i] = Math.max(0f, widths[i]) * factor;
        }
        return scaled;
    }

    /** Total width of the {@code count} cells starting at {@code from} in {@code widths}. */
    public static float rowWidth(float[] widths, int from, int count){
        float total = 0f;
        for(int i = 0; i < count; i++){
            total += Math.max(0f, widths[from + i]);
        }
        return total;
    }

    /** True when every cell fits into a single row of {@code available}. */
    public static boolean fitsOneRow(float available, float[] widths){
        return rowWidth(widths, 0, widths.length) <= available;
    }
}
