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
