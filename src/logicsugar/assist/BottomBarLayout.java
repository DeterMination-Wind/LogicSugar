package logicsugar.assist;

import arc.scene.Element;
import arc.scene.ui.Button;
import arc.scene.ui.layout.Table;

import java.util.Arrays;

/**
 * Pure, headless-testable row packing for the logic editor's bottom bar, plus the one piece of
 * vanilla-row knowledge that packing depends on ({@link #claimAddButton}).
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
 * pinning with a self-test, and they must stay runnable in a headless JVM. That is also why the
 * claim helper lives here instead of in {@code mindustry.logic.SugarLogicDialog}: this package
 * cannot reach a package-private game member even by accident (javac rejects it), while a class
 * sitting in a game package compiles such an access and only fails at runtime across loaders.</p>
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

    /**
     * Claims vanilla's anonymous {@code @add} control of a bottom-bar row, so the packed action
     * group can keep that exact element.
     *
     * <p>Vanilla's {@code setup()} names {@code back} / {@code edit} / {@code variables} but builds
     * Add anonymously and rebuilds the whole row on every show, so shape is the only handle: at
     * that point Add is the row's only unnamed button child. A positional guess ("the fourth
     * vanilla child") is what broke in 2026-10: {@code SugarLogicDialog.installVarsButton()} runs
     * before the row is packed, removes the variables button and re-appends its Sugar replacement
     * at the end, so the fourth child was the function-library button — Add was renamed, left out of
     * the action group and cleared with the rest of the row (「添加积木」disappeared). Claiming by
     * shape does not care about that reordering; what still matters is claiming <em>before</em> the
     * row is cleared, which is why the dialog calls this at the top of its layout pass.</p>
     *
     * <p>An ambiguous row (a fork that added an anonymous button of its own next to Add) claims
     * nothing: guessing between them could rename a foreign control and drop the real one, while
     * returning null makes the caller rebuild the action — the unnamed children would be dropped by
     * the packed layout anyway.</p>
     *
     * @param row the dialog's button row, still holding its vanilla children
     * @return the element to use as the Add control (now named {@code add}), or {@code null} when
     *         the row has none to claim — the caller then rebuilds the control instead of losing it
     */
    public static Element claimAddButton(Table row){
        Element add = row.find("add");
        if(add != null) return add;

        Element unnamed = null;
        for(Element child : row.getChildren()){
            if(!(child instanceof Button button) || button.name != null) continue;
            if(unnamed != null) return null; // ambiguous: more than one candidate
            unnamed = child;
        }
        if(unnamed != null) unnamed.name = "add";
        return unnamed;
    }
}
