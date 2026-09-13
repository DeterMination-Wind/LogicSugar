package logicsugar.assist;

import arc.Application;
import arc.Core;
import arc.scene.Element;
import arc.scene.ui.layout.Stack;
import arc.scene.ui.layout.Table;

import java.lang.reflect.Proxy;
import java.util.Arrays;

/**
 * Self-test for the logic editor's bottom bar: {@link BottomBarLayout} row packing plus the
 * arc layout facts the dialog depends on.
 *
 * <p>The second half runs the real {@code arc.scene.ui.layout} engine headlessly and pins the
 * bug from the 2026-09 overlap report: vanilla {@code setup()} leaves a fixed
 * {@code size(160f, 64f)} default cell on the button row, which also caps the <em>maximum</em>
 * width of every container cell added to that row. A clamped container is one button wide, so
 * its fixed-width children spill past it and the inspection group paints over the action
 * group. {@code SugarLogicDialog.layoutBottomButtons()} relaxes that inherited maximum before
 * adding its containers; this test fails if either half regresses.</p>
 *
 * <p>No fonts or OpenGL are involved, so this stays a headless JavaExec task.</p>
 */
public class BottomBarLayoutTest{
    private static final float button = 160f;
    private static final float budgetLabel = 196f;

    public static void main(String[] args){
        singleRowWhenEverythingFits();
        exactFitDoesNotWrap();
        wrapsAtCapacity();
        budgetLabelCountsAsItsWiderCell();
        oversizedCellKeepsItsOwnRow();
        neverExceedsRowWidthWithoutBeingAlone();
        keepsEveryCellInOrder();
        emptyInput();
        fitsOneRowMirrorsPacking();

        installHeadlessApp();
        vanillaDefaultClampsTheGroupContainer();
        relaxedDefaultSpansTheBar();
        wideBarKeepsGroupsApart();
        narrowBarWouldOverlapSoItMustWrap();

        System.out.println("LogicSugar BottomBarLayout self-test passed.");
    }

    // ---------- pure row packing ----------

    private static void singleRowWhenEverythingFits(){
        int[] rows = BottomBarLayout.packRows(1000f, new float[]{button, button, budgetLabel});
        check(rows.length == 1 && rows[0] == 3, "three small cells should share one row, got " + Arrays.toString(rows));
    }

    private static void exactFitDoesNotWrap(){
        float[] widths = new float[]{button, button, button, button, button};
        int[] rows = BottomBarLayout.packRows(5f * button, widths);
        check(rows.length == 1 && rows[0] == 5, "a row that fits exactly must not wrap, got " + Arrays.toString(rows));
    }

    private static void wrapsAtCapacity(){
        float[] widths = new float[]{button, button, button, button, button, button, button, button};
        // 8 buttons (1280px total) into a 800px bar: five fit, the rest follow on the next row
        int[] rows = BottomBarLayout.packRows(800f, widths);
        check(rows.length == 2, "eight 160px buttons need two rows at 800px, got " + Arrays.toString(rows));
        check(rows[0] == 5 && rows[1] == 3, "800px holds exactly five buttons, got " + Arrays.toString(rows));
    }

    private static void budgetLabelCountsAsItsWiderCell(){
        float[] widths = new float[]{button, button, budgetLabel};
        // 160 + 160 + 196 = 516 does not fit 500, so the label moves to row two
        int[] rows = BottomBarLayout.packRows(500f, widths);
        check(rows.length == 2 && rows[0] == 2 && rows[1] == 1, "budget label must use 196px, got " + Arrays.toString(rows));
    }

    private static void oversizedCellKeepsItsOwnRow(){
        float[] widths = new float[]{800f, button, button};
        int[] rows = BottomBarLayout.packRows(300f, widths);
        check(rows.length == 3, "an oversized cell must not swallow its neighbours, got " + Arrays.toString(rows));
        check(rows[0] == 1 && rows[1] == 1 && rows[2] == 1, "each oversized row holds one cell, got " + Arrays.toString(rows));
    }

    private static void neverExceedsRowWidthWithoutBeingAlone(){
        float[] widths = new float[]{button, button, button, button, budgetLabel, button, budgetLabel, button};
        float available = 480f;
        int[] rows = BottomBarLayout.packRows(available, widths);
        int index = 0;
        for(int count : rows){
            float width = BottomBarLayout.rowWidth(widths, index, count);
            check(width <= available || count == 1,
                "row of " + count + " cells is " + width + "px wide (available " + available + ")");
            index += count;
        }
    }

    private static void keepsEveryCellInOrder(){
        float[] widths = new float[]{button, budgetLabel, button, button, budgetLabel};
        int[] rows = BottomBarLayout.packRows(340f, widths);
        int placed = 0;
        for(int count : rows){
            check(count > 0, "empty rows must not be produced");
            placed += count;
        }
        check(placed == widths.length, "every cell must be placed exactly once, placed " + placed);
    }

    private static void emptyInput(){
        check(BottomBarLayout.packRows(500f, new float[0]).length == 0, "an empty bar has no rows");
    }

    private static void fitsOneRowMirrorsPacking(){
        float[] oneRow = new float[]{button, button};
        float[] twoRows = new float[]{button, button, button};
        check(BottomBarLayout.fitsOneRow(320f, oneRow), "two buttons fit into 320px");
        check(!BottomBarLayout.fitsOneRow(320f, twoRows), "three buttons do not fit into 320px");
    }

    // ---------- real arc layout geometry ----------

    /** {@code Scl.scl} consults {@code Core.app} to pick the UI scale, so a headless run needs
     *  a desktop-shaped application. Nothing else in the layout path touches the game. */
    private static void installHeadlessApp(){
        Core.app = (Application)Proxy.newProxyInstance(BottomBarLayoutTest.class.getClassLoader(),
            new Class<?>[]{Application.class},
            (proxy, method, args) -> switch(method.getName()){
                case "isDesktop" -> Boolean.TRUE;
                case "isWeb", "isMobile" -> Boolean.FALSE;
                default -> null;
            });
    }

    /** Vanilla's inherited fixed default cell is the trap this fix has to disarm. */
    private static void vanillaDefaultClampsTheGroupContainer(){
        Stack container = buildBar(false, 1920f);
        check(Math.abs(container.getWidth() - button) < 0.01f,
            "vanilla's size(160, 64) default must clamp a container to one button, got " + container.getWidth());
    }

    /** After relaxing the inherited maximum the container spans the whole row. */
    private static void relaxedDefaultSpansTheBar(){
        Stack container = buildBar(true, 1920f);
        check(Math.abs(container.getWidth() - (1920f - 16f)) < 0.01f,
            "container must span the bar minus its 8px side pads, got " + container.getWidth());
    }

    /** The reported bug: the centred action group and the right-anchored inspection group
     *  must not share pixels on a wide bar. */
    private static void wideBarKeepsGroupsApart(){
        Stack container = buildBar(true, 1920f);
        Bounds centered = centeredContent(container);
        Bounds debug = debugContent(container);
        check(centered.right <= debug.left,
            "action group ends at " + centered.right + " but inspection controls start at " + debug.left);
    }

    /** Below the single-row threshold the groups do overlap, which is exactly why the dialog is
     *  required to wrap instead of stacking there — this documents the threshold's value. */
    private static void narrowBarWouldOverlapSoItMustWrap(){
        Stack container = buildBar(true, 1280f);
        Bounds centered = centeredContent(container);
        Bounds debug = debugContent(container);
        check(centered.right > debug.left, "this fixture must reproduce the overlap the wrap exists for");
    }

    /**
     * Builds the same structure {@code SugarLogicDialog.layoutBottomButtons()} builds for its
     * single-row branch: actions centred on one layer, inspection controls anchored right on
     * the other. Cell widths here mirror barButtonWidth / barBudgetWidth / barRowPad.
     */
    private static Stack buildBar(boolean relaxInheritedMaximum, float barWidth){
        Table bar = new Table();
        bar.defaults().size(button, 64f);
        if(relaxInheritedMaximum) bar.defaults().maxWidth(0f);

        Table centeredTable = new Table();
        centeredTable.defaults().size(button, 64f);
        centeredTable.center();
        for(int i = 0; i < 5; i++) centeredTable.add(new Element());

        Table debugTable = new Table();
        debugTable.defaults().size(button, 64f);
        debugTable.right().marginRight(12f);
        for(int i = 0; i < 2; i++) debugTable.add(new Element());
        // instruction-budget label: 180px content plus its 8px side pads
        debugTable.add(new Element()).width(180f).height(64f).padLeft(8f).padRight(8f);

        Table debugRegion = new Table();
        debugRegion.right();
        debugRegion.add(debugTable).right();

        bar.stack(centeredTable, debugRegion).growX().height(64f).padLeft(8f).padRight(8f);
        bar.setSize(barWidth, 64f);
        bar.validate();
        return (Stack)bar.getChildren().peek();
    }

    private static Bounds centeredContent(Stack container){
        Table centered = (Table)container.getChildren().get(0);
        Element last = centered.getChildren().peek();
        return new Bounds(centered.x, centered.x + last.x + last.getWidth());
    }

    private static Bounds debugContent(Stack container){
        Table debugRegion = (Table)container.getChildren().get(1);
        Table debugTable = (Table)debugRegion.getChildren().get(0);
        Element first = debugTable.getChildren().first();
        Element last = debugTable.getChildren().peek();
        return new Bounds(debugTable.x + first.x, debugTable.x + last.x + last.getWidth());
    }

    private static final class Bounds{
        final float left, right;

        Bounds(float left, float right){
            this.left = left;
            this.right = right;
        }
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError("BottomBarLayout: " + message);
    }
}
