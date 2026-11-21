package mindustry.logic;

import arc.Core;
import arc.func.Cons;
import arc.func.Func;
import arc.func.Prov;
import arc.input.KeyCode;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.style.Drawable;
import arc.graphics.Color;
import arc.scene.ui.Button;
import arc.scene.ui.Dialog;
import arc.scene.ui.Label;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.gen.Icon;
import mindustry.gen.LogicIO;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.logic.LExecutor;
import mindustry.ui.Fonts;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.blocks.logic.LogicBlock;
import logicsugar.FunctionLibrary;
import logicsugar.FunctionLibraryDialog;
import logicsugar.assist.BottomBarLayout;
import logicsugar.assist.EditHistory;
import logicsugar.assist.InstructionBudget;
import logicsugar.assist.SelectionClipboardUi;
import logicsugar.assist.SugarTooltip;
import logicsugar.assist.VarClipboard;
import logicsugar.assist.expr.ExprCompiler;
import logicsugar.assist.expr.ExprStatement;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

public class SugarLogicDialog extends LogicDialog{
    private static final String compiledCopyName = "logicsugar-copy-compiled";
    private static final String originalViewName = "logicsugar-view-original";
    private static final Field consumerField = field(LogicDialog.class, "consumer");
    /** LogicDialog.privileged is package-private and lives in the MindustryX mod class loader at
     *  runtime, so it must be read reflectively (cross-loader package access throws
     *  IllegalAccessError). Both this and consumer keep the hard field(): they are load-bearing
     *  for the dialog (there is no degraded mode), unlike SugarCanvas's optional feature fields. */
    private static final Field privilegedField = field(LogicDialog.class, "privileged");
    /** Mirrors LogicBlock.maxCompressedLen (private upstream); read reflectively so the limit
     *  tracks upstream instead of drifting silently when the game adjusts it. */
    private static final int maxCompressedBytes = compressedLimit();
    private final Map<Object, String> drafts = new IdentityHashMap<>();
    /** Cached copy-button scan results (see {@link #installCompiledCopy}); cleared on hide. */
    private TextButton cachedCopyButton;
    private Table cachedCopyMenu;
    private Dialog cachedCopyDialog;
    /** Set by the edit button's click (see {@link #installEditHook}): the export dialog instance
     *  we may mount menus into was opened by <em>our</em> button. A reference rather than a flag:
     *  a stale flag could latch onto a later foreign dialog, while an instance can be re-checked. */
    private Dialog pendingExportDialog;
    private TextButton originalViewButton;
    private Table originalViewMenu;
    private Dialog originalViewDialog;
    /** Raw code and recovered source for the optional original/Sugar view toggle. */
    private String originalCode;
    private String recoveredSugar;
    private boolean showingOriginal;
    public LExecutor executor;

    /** 原版「变量」按钮由本类保留：它打开原版变量表/内置变量对话框。
     *  本类不再替换它（Vars/Memory/Properties 界面属于 Mlog Dev Tools 的重叠功能，已移除）。 */
    /** When true, a failed compile during a close is passed back to the caller as raw sugar
     *  instead of being dropped. Used by the function library editing session (executor == null),
     *  so processor edits are never affected. */
    public boolean passThroughSugarOnError;
    /** The stored code as it was when the dialog opened (stale-close protection). */
    private String openedCode = "";
    /** The sugar (or compiled fallback) the canvas was loaded with. */
    private String editable = "";
    /** Embedded plus local library snapshot for this editing session. */
    private SugarCompiler.EffectiveLibrary effectiveLibrary = SugarCompiler.effectiveLibrary("", null, "");
    /** Content hash of the library file when the dialog opened; used to refresh the stale
     *  session snapshot when the library is edited while the processor editor stays open. */
    private int libraryHashAtOpen;
    private boolean editingPrivileged;
    /** Shown only during library-file editing sessions: closes without saving. */
    private Button discardButton;
    private Element editButton;
    private float menuScanTimer;
    /** Ctrl+Z/Y 的长按去抖：正在按住的键；物理松开后清空（见 {@link #pollEditorShortcuts}）。 */
    private KeyCode heldEditorShortcut;
    /** 跨逻辑复制的菜单与 Ctrl+C/V。共存档在对方画布上另挂一份，见 {@link SelectionClipboardUi}。 */
    private final SelectionClipboardUi selectionClipboard = new SelectionClipboardUi();
    /** Live compiled-size banner; rebuilt with the vanilla button row. */
    private Label budgetLabel;
    private float budgetTimer;
    private boolean budgetToastShown;
    /** One-time notice that another mod raised {@code LExecutor.maxInstructions}; LogicSugar
     *  keeps enforcing the vanilla 1000-instruction product limit regardless. */
    private boolean budgetPinNoticeShown;
    /** One-shot: the over-budget escape chose "discard changes and close", so the close-time
     *  submit must not write sugar (the processor keeps the program it opened with). */
    private boolean closingWithoutSave;
    /** The over-budget confirm is modal; this keeps a repeated close attempt from stacking copies. */
    private boolean overBudgetConfirmOpen;
    private InstructionBudget.Snapshot lastBudget;
    private final EditHistory history = new EditHistory();
    private String lastHistorySnap = "";
    private float historyTimer;
    private float historyIdle;
    private Button undoButton;
    private Button redoButton;
    /** Width used by the last desktop bottom-bar layout; changed after the dialog gets a real size. */
    private float bottomButtonsWidth = -1f;

    /** Fixed size of one bottom-bar button cell; vanilla setup() uses the same 160x64.
     *  <p>These are <b>declared</b> units: arc multiplies every Cell size/pad/margin by
     *  {@code Scl.scl}, so the live layout calls them 400px wide on a 2.5x phone. Anything
     *  compared against a measured width must go through {@code Scl.scl} too — see
     *  {@link #layoutBottomButtons()} and {@link BottomBarLayout#scaledWidths}.</p> */
    private static final float barButtonWidth = 160f;
    private static final float barButtonHeight = 64f;
    /** Side padding of every bottom-bar cell, and of every packed row's own edges. */
    private static final float barCellPad = 8f;
    /** Instruction-budget label cell: 180px content plus its side pads. */
    private static final float barBudgetWidth = 180f + 2f * barCellPad;
    /** Horizontal padding reserved by every bottom-bar row. */
    private static final float barRowPad = 2f * barCellPad;

    public SugarLogicDialog(){
        super();
        clearChildren();
        canvas = new SugarCanvas();
        if(canvas instanceof SugarCanvas sugarCanvas){
            sugarCanvas.afterMutate = this::recordCanvasHistory;
        }
        add(canvas).grow().name("canvas");
        row();
        add(buttons).growX().name("buttons");
        // direct entry to the global function library, next to the other editor actions
        buttons.button("@logicsugar.funclib.open", Icon.book, () -> new FunctionLibraryDialog().show()).name("funclib");
        // library-file editing sessions (executor == null) offer a discard escape so a user
        // who cannot or does not want to fix the library is not trapped in the reopen loop
        discardButton = buttons.button("@logicsugar.funclib.discard", Icon.cancel, this::discardLibraryChanges).get();
        discardButton.name = "funclib-discard";
        discardButton.visible = false;
        // vanilla LogicDialog registers shown(setup), and setup() rebuilds the button row
        // (clearChildren) on EVERY show — wiping anything added outside it. Re-append the
        // Sugar-owned buttons after each rebuild; find() guards make it idempotent.
        shown(this::installSugarButtons);
        // Vanilla's onResize re-runs setup() — which clears `buttons` — whenever LCanvas.isCompact()
        // or the portrait flag flips (window resize, phone rotation). `shown` does not fire again,
        // so without this hook the Sugar buttons stay gone until the editor is reopened. The
        // installer is idempotent (find() guards) and ends in layoutBottomButtons(), so repeating
        // it on a resize neither duplicates listeners nor double-adds a control.
        onResize(this::installSugarButtons);
        update(() -> {
            installEditHook();
            // Device-independent on purpose: mobile and narrow desktop windows take the same
            // width-driven path. Gating this on Vars.mobile/isPortrait() is what left the phone
            // bar on vanilla's fixed-width row, which cannot be compressed to the stage width and
            // so leaves its first and last controls off screen. The width check is what keeps
            // this from re-parenting half the bar every single frame.
            float width = buttons.getWidth();
            if(width > 0f && Math.abs(width - bottomButtonsWidth) > 1f){
                // The shown callback can run before the parent table has measured this
                // row. Re-run once its actual width is available (and after a resize).
                layoutBottomButtons();
            }
            menuScanTimer += Time.delta;
            if(menuScanTimer >= 6f){
                menuScanTimer = 0f;
                installCompiledCopy();
                installOriginalView();
                installInspectionCopy();
            }
            selectionClipboard.tick(canvas, this);
            budgetTimer += Time.delta;
            if(budgetTimer >= 24f){
                budgetTimer = 0f;
                refreshInstructionBudget();
            }
            historyTimer += Time.delta;
            if(historyTimer >= 8f){
                historyTimer = 0f;
                pollCanvasHistory();
            }
            pollEditorShortcuts();
        });
    }

    /**
     * 编辑器级快捷键：Ctrl+Z / Ctrl+Y 撤销重做。Ctrl+C / Ctrl+V 在 {@link SelectionClipboardUi}。
     *
     * <p>轮询而不是事件驱动，因为事件派发有两个坑，而轮询对两者都不敏感：</p>
     * <ul>
     *   <li><b>焦点。</b>{@code Scene.keyDown} 只把事件交给当前键盘焦点，再沿父链冒泡。对话框
     *       显示时焦点是自己，但用户一旦编辑过某个输入框、随后点了别处，Mindustry 的
     *       {@code UI.update} 会把焦点清成 null —— 此后事件从 root 开始，挂在对话框上的
     *       capture 监听器根本收不到（撤销因此看起来"有时不灵"）。反过来，<b>arc 的
     *       {@code handle()} 只标记 handled、不停止冒泡</b>，所以输入框也没法靠"消费"按键
     *       保护自己（在 Expr 输入框里按 Ctrl+Z 会撤销画布而不是文本）。两件事都靠
     *       {@link arc.scene.Scene#hasField()} 一次问清。</li>
     *   <li><b>生命周期。</b>冲突设置一切换，编辑器实例就被换掉；轮询随实例来去，不必注册和
     *       注销场景监听器，也就没有重复挂载或悬空引用的问题。</li>
     * </ul>
     *
     * <p>帧序上是安全的：按键状态由 {@code input.postUpdate()} 在整帧最后清除，而本回调跑在
     * {@code ApplicationListener.update()} 里、早于它 —— 与 Mindustry 自己的快捷键
     * （{@code Vars.control}）读的是同一份状态。</p>
     */
    private void pollEditorShortcuts(){
        // 松手用物理状态判断，不用 key-up 事件：窗口失焦时事件可能丢，一旦丢了按键就会卡成
        // "一直按着"，之后所有按键都失效。这同时也是唯一需要的去抖 —— keyTap 在系统连发期间
        // 持续为真，不挡住的话按住不放会一直撤销、一直粘贴。
        if(heldEditorShortcut != null && !Core.input.keyDown(heldEditorShortcut)) heldEditorShortcut = null;
        if(heldEditorShortcut != null || Vars.mobile || !isShown()) return;
        // 盖在上面的对话框（MDT 的 Vars / Log / Profiler、函数库编辑器等）拥有快捷键。本对话框
        // 仍然 isShown()，但底下的画布不该再撤销/重做——那会和上面那张各做一次。
        if(!SelectionClipboardUi.isFront(this)) return;
        // 输入框优先。Mindustry 在点击非输入框处就会清掉焦点，所以这一问等价于"用户此刻在打字"。
        if(Core.scene != null && Core.scene.hasField()) return;
        if(!Core.input.ctrl() || Core.input.alt()) return;

        if(Core.input.keyTap(KeyCode.z)){
            heldEditorShortcut = KeyCode.z;
            performUndo();
        }else if(Core.input.keyTap(KeyCode.y)){
            heldEditorShortcut = KeyCode.y;
            performRedo();
        }
    }

    /** Re-appends the Sugar-owned buttons after vanilla {@code setup()} has rebuilt the row. */
    private void installSugarButtons(){
        if(buttons.find("funclib") == null){
            buttons.button("@logicsugar.funclib.open", Icon.book, () -> new FunctionLibraryDialog().show()).name("funclib");
        }
        if(buttons.find("funclib-discard") == null){
            discardButton = buttons.button("@logicsugar.funclib.discard", Icon.cancel, this::discardLibraryChanges).get();
            discardButton.name = "funclib-discard";
        }
        // show() sets this before super.show() fires the shown callbacks, so re-apply it here
        discardButton.visible = executor == null;
        // The processor-inspection copies (variables dump + print buffer) are mounted into the
        // edit menu by installInspectionCopy(), not here: the bar is width-bound, and two more
        // fixed-width cells are what pushed it off narrow windows.
        installBudgetLabel();
        installHistoryButtons();
        bottomButtonsWidth = -1f;
        layoutBottomButtons();
    }

    /**
     * Keeps the vanilla {@code @add} control in the packed action group: claims it by shape (see
     * {@link BottomBarLayout#claimAddButton}) and, when the row has nothing left to claim — a fork
     * that named or replaced that button — rebuilds the same action under our own name instead of
     * silently dropping it.
     *
     * <p>2026-10 报告（底栏「添加积木」直接消失）的成因是这次声明跑得太晚且靠位置认人：
     * 那次改动先把原版变量按钮摘掉、再把替身接到行尾，位置猜测因此落在函数库
     * 按钮上，真正的 Add 随整行一起被 {@link #layoutBottomButtons} 清掉。按形状认人不看位置，但仍
     * 必须发生在清行之前，所以调用点就在 {@code layoutBottomButtons} 开头。</p>
     * <p>（变量按钮的替身已随 MDT 重叠功能移除，原版按钮现在原样保留；这段历史说明仍适用：
     * 认按钮只按形状，不按位置。）</p>
     */
    private void installAddButton(){
        Element claimed = BottomBarLayout.claimAddButton(buttons);
        if(claimed instanceof Button button){
            // Vanilla disables @add at LExecutor.maxInstructions, which another mod may raise
            // (MDT's slider). The saved product is still capped at the vanilla limit, so replace
            // the predicate with the pinned budget: the control stops where the save would.
            button.setDisabled(() -> canvas.statements.getChildren().size >= InstructionBudget.vanillaLimit());
            return;
        }
        buttons.button("@add", Icon.add, this::showAddDialog).name("add")
            .disabled(t -> canvas.statements.getChildren().size >= InstructionBudget.vanillaLimit());
    }

    /**
     * Rebuilds the bottom bar so that no control is ever squeezed or painted over.
     *
     * <p>Vanilla {@code setup()} leaves {@code buttons.defaults().size(160f, 64f)} on this
     * row. That default sets a positive <em>maximum</em> width as well as a minimum, so every
     * container cell added here used to be clamped to a single button; the fixed 160px
     * children of that container then overflowed it and the inspection controls painted
     * straight over the action controls (buttons-overlapping report, 2026-09). The inherited
     * maximum is cleared before any container is added — a non-positive maximum means
     * "unbounded" in Table's layout math.</p>
     *
     * <p>The width logic runs on <b>every</b> device, mobile included. It used to return early
     * for {@code Vars.mobile || isPortrait()} and keep vanilla's single fixed-width row there,
     * which is the report this method now answers: {@code TextButton} pins its label's minimum
     * width to the text width, so on a phone that row cannot be compressed down to the screen.
     * A row wider than the stage gets pushed out of the visible area once
     * {@code Element.keepInStage()} pulls its overflowing edge back onto the stage, which is how
     * the first and last controls — the back button and the function-library button — end up cut
     * off with no way to press them. Wrapping onto rows that really fit is the only layout that
     * keeps every control on screen at a usable size.</p>
     *
     * <p>Talking about "the width" is only meaningful once the units are fixed. The cells
     * declared here are scaled by {@code Scl.scl} when arc stores them, so on a phone (scale
     * 2.5) one button really occupies 400 scene units. The packer must therefore be handed the
     * scaled widths together with the measured bar width: feeding it the declared 160s against
     * the real bar is what let seven cells share one phone row that truly needed 2000 scene
     * units, pushing the two end buttons off screen (2026-09 phone report).</p>
     */
    private void layoutBottomButtons(){
        // The vanilla @add control is anonymous and this is the pass that consumes (and clears) the
        // row, so it has to be claimed here, before anything is reparented — see installAddButton.
        installAddButton();

        // Add closes the action group: it is the control used most often while editing (a
        // statement lands right where the pointer already is), and keeping it last also puts it
        // next to the inspection readout instead of buried between the view controls.
        Element[] centered = {
            buttons.find("back"),
            buttons.find("edit"),
            buttons.find("variables"),
            buttons.find("funclib"),
            buttons.find("funclib-discard"),
            buttons.find("logicsugar-undo"),
            buttons.find("logicsugar-redo"),
            buttons.find("add")
        };
        // Only the readout is a bar control now; the two clipboard actions live in the edit menu
        // (see installInspectionCopy), which has room for them and no width budget to blow.
        Element[] debug = {
            buttons.find("instruction-budget")
        };

        buttons.clearChildren();
        // See the method comment: vanilla's 160px default maximum would clamp the containers
        // below to a single button and let their fixed-size children overlap each other.
        buttons.defaults().maxWidth(0f);

        Table centeredTable = new Table();
        centeredTable.defaults().size(barButtonWidth, barButtonHeight);
        centeredTable.center();
        for(Element element : centered){
            // Hidden optional actions (for example the library-only discard button) must not
            // reserve an invisible slot, otherwise the visible action group is off-center.
            if(element != null && element.visible) centeredTable.add(element);
        }

        Table debugTable = new Table();
        debugTable.defaults().size(barButtonWidth, barButtonHeight);
        debugTable.right().marginRight(12f);
        for(Element element : debug){
            if(element == null || !element.visible) continue;
            addBarCell(debugTable, element);
        }

        float available = buttons.getWidth();
        // Before the first layout the row has no measured width yet; the screen is an upper
        // bound for it (dialog windows fill their parent), so that first pass is only ever
        // corrected towards a narrower bar by the update() re-check installed above.
        if(available <= 0f) available = Core.graphics.getWidth();
        bottomButtonsWidth = buttons.getWidth();

        // Every Cell size/pad/margin below is multiplied by this scale when arc stores it, so a
        // "160px" button is 160 * uiScale scene units wide (400px on a 2.5x phone). The widths
        // measured off the live layout — available, getPrefWidth() — are already in those scene
        // units, so every declared number used in the comparisons must be scaled the same way.
        // Comparing the declared 160 against the real bar is what packed all seven phone cells
        // into one row that was 2.5x wider than the screen (2026-09 phone report).
        float uiScale = Scl.scl(1f);

        // 1) Everything on one row: actions centered, inspection controls anchored to the
        //    right edge.  Both groups are full-width layers of a stack, so they may only share
        //    the row while their footprints cannot touch.  This is the shape every wide window
        //    uses; the packing below is the fallback for everything narrower.
        float sideWidth = debugTable.getPrefWidth() + Scl.scl(12f);
        float wideEnough = centeredTable.getPrefWidth() + 2f * sideWidth + Scl.scl(barRowPad);
        if(available >= wideEnough){
            Table debugRegion = new Table();
            debugRegion.right();
            debugRegion.add(debugTable).right();
            buttons.stack(centeredTable, debugRegion).growX().height(barButtonHeight).padLeft(barCellPad).padRight(barCellPad);
            buttons.invalidateHierarchy();
            return;
        }

        // 2) Narrow (every phone, and any window too small for the centered stack): pack the
        //    individual cells into rows that really fit, so nothing is squeezed into its
        //    neighbour.  A cell never shares a row unless that row can hold it; at worst a
        //    single cell keeps a row to itself rather than being dropped or overlapped.
        float rowSpace = available - Scl.scl(barRowPad);
        // The instruction-budget label is not just the widest cell (196px), it is also the only
        // cell that may be given up: on a bar barely wider than the screen it costs a whole extra
        // row of height for a readout the over-budget toast already reports.  A control the user
        // cannot press is a real loss, a readout is not, so every pressable cell stays on the bar
        // whatever the width.  A row too narrow for the label itself never shows it.
        boolean budgetLabelFits = rowSpace >= Scl.scl(barBudgetWidth);
        ArrayList<Element> packed = new ArrayList<>();
        for(Element element : centered){
            if(element != null && element.visible) packed.add(element);
        }
        for(Element element : debug){
            if(element == null || !element.visible) continue;
            if(element == budgetLabel && !budgetLabelFits) continue;
            packed.add(element);
        }
        float[] widths = new float[packed.size()];
        for(int i = 0; i < widths.length; i++){
            widths[i] = packed.get(i) == budgetLabel ? barBudgetWidth : barButtonWidth;
        }

        // Declared units -> scene units; see uiScale above and addBarCell below (the label's cell
        // is declared as 180 + 8 + 8, i.e. barBudgetWidth, so the two stay in step).
        widths = BottomBarLayout.scaledWidths(uiScale, widths);

        int[] rows = BottomBarLayout.packRows(rowSpace, widths);
        int index = 0;
        for(int row = 0; row < rows.length; row++){
            Table rowTable = new Table();
            rowTable.defaults().size(barButtonWidth, barButtonHeight);
            rowTable.center();
            for(int cell = 0; cell < rows[row]; cell++){
                addBarCell(rowTable, packed.get(index++));
            }
            buttons.add(rowTable).growX().height(barButtonHeight).padLeft(barCellPad).padRight(barCellPad);
            if(row < rows.length - 1) buttons.row();
        }
        buttons.invalidateHierarchy();
    }

    /**
     * Adds one bottom-bar cell. The instruction-budget label keeps its wider cell — that cell is
     * where {@link #barBudgetWidth} comes from, so the two must stay in step or the row packing
     * measures a cell the layout does not actually produce.
     */
    private void addBarCell(Table row, Element element){
        if(element == budgetLabel){
            row.add(element).width(barBudgetWidth - 2f * barCellPad).height(barButtonHeight)
                .padLeft(barCellPad).padRight(barCellPad);
        }else{
            row.add(element);
        }
    }

    private void installHistoryButtons(){
        if(!Vars.mobile) return;
        if(buttons.find("logicsugar-undo") == null){
            undoButton = buttons.button("@logicsugar.undo", Icon.left, this::performUndo).get();
            undoButton.name = "logicsugar-undo";
        }else if(buttons.find("logicsugar-undo") instanceof Button button){
            undoButton = button;
        }
        if(buttons.find("logicsugar-redo") == null){
            redoButton = buttons.button("@logicsugar.redo", Icon.rightOpen, this::performRedo).get();
            redoButton.name = "logicsugar-redo";
        }else if(buttons.find("logicsugar-redo") instanceof Button button){
            redoButton = button;
        }
        refreshHistoryButtons();
    }

    private void performUndo(){
        applyHistory(history.undo(canvasSnapshot()));
    }

    private void performRedo(){
        applyHistory(history.redo(canvasSnapshot()));
    }

    /**
     * 只读画布文本（历史/签名比较专用）。
     *
     * <p>{@code LogicDialog.canvas} 的静态类型是原版 {@link LCanvas}，所以这里做一次判定；
     * 本对话框的字段装的始终是 {@link SugarCanvas}，非本实现的回退分支只为不抛异常。</p>
     */
    private String readonlyCanvasText(){
        if(canvas == null) return "";
        // 历史快照必须是能重新解析的**程序文本**（展开态）：{@code save()} 现在就是纯文本读取，
        // 一个积木元素都不动。折叠态文本里多行表达式卡占多条语句，而 jump/begin 记的是画布语句
        // 下标，{@code canvas.load()} 回灌时会静默改掉跳转目标。
        return canvas.save();
    }

    /** 历史快照一律走纯文本读取：{@code save()} 不再 unfold/fold（不会重建积木元素），
     *  下面几条路径（尤其每帧轮询）可以放心调。 */
    private String canvasSnapshot(){
        try{
            return readonlyCanvasText();
        }catch(Throwable ignored){
            return lastHistorySnap;
        }
    }

    private void recordCanvasHistory(){
        try{
            String now = readonlyCanvasText();
            history.record(now);
            lastHistorySnap = now;
            historyIdle = 0f;
            refreshHistoryButtons();
        }catch(Throwable ignored){
        }
    }

    private void pollCanvasHistory(){
        if(history.isRestoring()) return;
        try{
            String now = readonlyCanvasText();
            if(!now.equals(lastHistorySnap)){
                lastHistorySnap = now;
                historyIdle = 0f;
            }else{
                historyIdle += 8f;
                if(historyIdle >= 24f){
                    history.record(now);
                    historyIdle = 0f;
                    refreshHistoryButtons();
                }
            }
        }catch(Throwable ignored){
        }
    }

    private void applyHistory(String sugar){
        if(sugar == null) return;
        canvas.load(sugar);
        try{
            String actual = readonlyCanvasText();
            history.applied(actual);
            lastHistorySnap = actual;
        }catch(Throwable ignored){
            history.applied(sugar);
            lastHistorySnap = sugar;
        }
        historyIdle = 0f;
        refreshHistoryButtons();
    }

    private void resetEditHistory(){
        try{
            String snap = readonlyCanvasText();
            history.reset(snap);
            lastHistorySnap = snap;
        }catch(Throwable ignored){
            history.reset("");
            lastHistorySnap = "";
        }
        historyIdle = 0f;
        refreshHistoryButtons();
    }

    private void refreshHistoryButtons(){
        if(undoButton != null) undoButton.setDisabled(!history.canUndo());
        if(redoButton != null) redoButton.setDisabled(!history.canRedo());
    }

    private void installBudgetLabel(){
        if(buttons.find("instruction-budget") != null){
            Element found = buttons.find("instruction-budget");
            if(found instanceof Label label) budgetLabel = label;
            return;
        }
        budgetLabel = new Label("");
        budgetLabel.name = "instruction-budget";
        budgetLabel.setAlignment(arc.util.Align.left);
        budgetLabel.setWrap(true);
        buttons.add(budgetLabel).name("instruction-budget").left().growX().padLeft(8f).minWidth(160f).height(40f);
        refreshInstructionBudget();
    }

    private void installEditHook(){
        Element candidate = buttons.find("edit");
        if(candidate == editButton || !(candidate instanceof Button button)) return;
        editButton = candidate;
        button.clicked(() -> Core.app.post(() -> {
            // Keep the dialog instance this click just opened. The scan may only use that exact
            // window, and the instance can be re-validated (still shown / still front) on retry.
            pendingExportDialog = SelectionClipboardUi.exportDialog();
            installCompiledCopy();
        }));
    }

    private static int compressedLimit(){
        try{
            Field field = LogicBlock.class.getDeclaredField("maxCompressedLen");
            field.setAccessible(true);
            return field.getInt(null);
        }catch(Exception exception){
            // upstream renamed/removed the constant; fall back to the known value
            return 16_000;
        }
    }

    /**
     * Mounts the compiled-copy (and later the other menu) actions into the game's export dialog.
     *
     * <p>The scan is deliberately narrow: only the export dialog instance that our own edit
     * button opened ({@link #pendingExportDialog}) is considered, and only while it is still
     * shown and still the front dialog. Scanning the scene by the localized
     * {@code copy.clipboard} text used to latch onto <em>any</em> dialog with such a button —
     * MDT's LogDialog has one — and inject LS's menu into another mod's window.</p>
     */
    private void installCompiledCopy(){
        if(cachedCopyButton != null && (!inSceneTree(cachedCopyButton) || cachedCopyDialog == null
            || !cachedCopyDialog.isShown())){
            // the menu (and its dialog) was closed and detached; rescan from scratch.
            // A bare parent check is not enough: an old edit dialog's menu object may
            // survive hidden in the scene tree, so the stale button keeps matching it.
            clearCompiledCopyCache();
        }
        Dialog export = pendingExportDialog;
        if(export != null && (!export.isShown() || Core.scene == null
            || Core.scene.getDialog() != export)){
            // The captured instance was closed or covered by another dialog; it is no longer
            // ours to mount into.
            pendingExportDialog = null;
            export = null;
        }
        if(export == null && cachedCopyButton == null){
            // Fallback when the click-time capture missed (the export dialog may not have had
            // focus yet): only the game's own export dialog passes the title check, so this
            // still cannot mount into another mod's window such as MDT's LogDialog.
            export = SelectionClipboardUi.exportDialog();
        }
        if(export != null && cachedCopyButton == null){
            TextButton found = findCopyButton(export);
            if(found != null){
                cachedCopyButton = found;
                cachedCopyMenu = found.parent instanceof Table table ? table : null;
                cachedCopyDialog = parentDialog(found);
                pendingExportDialog = null;
            }
        }
        if(cachedCopyButton == null || cachedCopyMenu == null || cachedCopyDialog == null) return;
        if(cachedCopyMenu.find(compiledCopyName) != null) return;

        Dialog dialog = cachedCopyDialog;
        installMenuButton(cachedCopyMenu, compiledCopyName, "@logicsugar.copy.compiled", Icon.copy, () -> {
            try{
                // copy with the session's effective library, so the embedded functions survive
                Core.app.setClipboardText(SugarCompiler.compile(canvas.save(), SugarCompiler.currentMode(),
                    effectiveLibrary.index, effectiveLibrary.text, SugarCompiler.currentStrategy(),
                    SugarCompiler.currentAssertEmit(), editingPrivileged));
                dialog.hide();
                Vars.ui.showInfoFade("@logicsugar.copy.compiled.done");
            }catch(IllegalArgumentException exception){
                dialog.hide();
                showCompileError(exception, false);
            }
        });
    }

    /**
     * Mounts the processor-inspection clipboard actions (variables dump and print buffer) into the
     * edit menu.
     *
     * <p>They used to be bottom-bar buttons. The bar's cells are fixed-width and the row is the
     * scarce dimension — seven of them overflow a narrow window, which is exactly why the two
     * least-used ones moved here, where a 280x60 row costs nothing. Library-file sessions have no
     * processor to inspect, so they simply do not get the entries.</p>
     */
    private void installInspectionCopy(){
        if(cachedCopyMenu == null || cachedCopyDialog == null) return;
        if(executor == null) return;
        if(cachedCopyMenu.find(VarClipboard.copyVarsButtonName) == null){
            installMenuButton(cachedCopyMenu, VarClipboard.copyVarsButtonName, "@logicsugar.copyvars",
                Icon.copy, () -> copyInspection(VarClipboard::variablesToText));
        }
        if(cachedCopyMenu.find(VarClipboard.copyBufferButtonName) == null){
            installMenuButton(cachedCopyMenu, VarClipboard.copyBufferButtonName, "@logicsugar.copybuffer",
                Icon.copy, () -> copyInspection(VarClipboard::bufferToText));
        }
    }

    /** Reads the live processor at click time (the session may have been re-shown in between),
     *  copies the dump, then closes the menu like the compiled-copy action above it.
     *
     *  <p>Uses {@link Func} rather than {@code java.util.function.Function}: the latter is API 24
     *  and is not backported by D8 here (no core-library desugaring), so it would throw
     *  {@code NoSuchMethodError}/{@code NoClassDefFoundError} on API 21-23 devices.</p> */
    private void copyInspection(Func<LExecutor, String> dump){
        LExecutor current = executor;
        if(current == null) return;
        Core.app.setClipboardText(dump.get(current));
        if(cachedCopyDialog != null) cachedCopyDialog.hide();
        Vars.ui.showInfoFade("@logicsugar.copied");
    }

    /** Adds a menu action for switching between an inferred Sugar view and the stored mlog. */
    private void installOriginalView(){
        if(originalCode == null || recoveredSugar == null) return;
        if(originalViewButton != null && !inSceneTree(originalViewButton)) clearOriginalViewCache();
        if(originalViewButton == null){
            TextButton candidate = findTextButton(Core.scene.root, originalViewName);
            if(candidate != null){
                originalViewButton = candidate;
                originalViewMenu = candidate.parent instanceof Table table ? table : null;
                originalViewDialog = parentDialog(candidate);
            }
        }
        if(originalViewButton != null) return;
        // anchor next to the compiled-copy action, which runs first in the periodic scan
        if(cachedCopyButton == null || cachedCopyMenu == null || cachedCopyDialog == null) return;
        originalViewButton = installMenuButton(cachedCopyMenu, originalViewName,
            "@logicsugar.view.original", Icon.edit, this::toggleOriginalView);
        originalViewMenu = cachedCopyMenu;
        originalViewDialog = cachedCopyDialog;
    }

    /** Mounts one named action into a copy-dialog menu and returns its button. Shared by the
     *  compiled-copy and view-toggle features so their layout stays in lockstep. */
    private static TextButton installMenuButton(Table menu, String name, String labelKey,
                                                Drawable icon, Runnable handler){
        menu.row();
        TextButton result = menu.button(labelKey, icon, Styles.flatt, handler)
            .size(280f, 60f).left().marginLeft(12f).get();
        result.name = name;
        menu.invalidateHierarchy();
        return result;
    }

    private void clearCompiledCopyCache(){
        cachedCopyButton = null;
        cachedCopyMenu = null;
        cachedCopyDialog = null;
    }

    private void clearOriginalViewCache(){
        originalViewButton = null;
        originalViewMenu = null;
        originalViewDialog = null;
    }

    private TextButton findTextButton(Element element, String name){
        if(element instanceof TextButton button && name.equals(button.name)) return button;
        if(element instanceof Group group){
            for(Element child : group.getChildren()){
                TextButton found = findTextButton(child, name);
                if(found != null) return found;
            }
        }
        return null;
    }

    private void toggleOriginalView(){
        if(originalCode == null || recoveredSugar == null) return;
        String target = showingOriginal ? recoveredSugar : originalCode;
        String snapshot = showingOriginal ? originalCode : recoveredSugar;
        String current = safeCanvasSave();
        if(current == null || !current.equals(snapshot)){
            // the current view was edited: loading the other snapshot would drop those
            // edits silently, so switching requires explicit confirmation. A failed
            // save() (uncompilable expression) counts as edited too: an untouched,
            // verified canvas always serializes successfully.
            Vars.ui.showConfirm(
                Core.bundle.get("logicsugar.view.confirm", "Unsaved Changes"),
                Core.bundle.get("logicsugar.view.confirm.text",
                    "The current view has unsaved edits. Switching views discards them.\nContinue?"),
                () -> applyViewSwitch(target));
            return;
        }
        applyViewSwitch(target);
    }

    /** Canvas serialization for change detection. {@code save()} throws on uncompilable
     *  expressions; the caller then treats the view as edited, since an untouched,
     *  verified canvas always serializes successfully. */
    private String safeCanvasSave(){
        try{
            return canvas.save();
        }catch(Throwable exception){
            return null;
        }
    }

    private void applyViewSwitch(String target){
        try{
            canvas.load(target);
            editable = target;
            showingOriginal = !showingOriginal;
            updateOriginalViewButton();
            if(originalViewDialog != null) originalViewDialog.hide();
        }catch(Throwable exception){
            showCompileError(new IllegalArgumentException("Cannot switch logic view: " + exception.getMessage()), false);
        }
    }

    private void updateOriginalViewButton(){
        if(originalViewButton == null) return;
        originalViewButton.setText(showingOriginal ? "@logicsugar.view.sugar" : "@logicsugar.view.original");
    }

    private Dialog parentDialog(Element element){
        return SelectionClipboardUi.parentDialog(element);
    }

    /** True when the element is still attached under the scene root (a closed dialog's
     *  children are detached from the tree, so a cached button there is stale). */
    private static boolean inSceneTree(Element element){
        return SelectionClipboardUi.inSceneTree(element);
    }

    private TextButton findCopyButton(Dialog dialog){
        return SelectionClipboardUi.findCopyButton(dialog);
    }

    @Override
    public void showAddDialog(int position){
        BaseDialog dialog = new BaseDialog("@add");
        boolean priv;
        try{
            priv = (boolean)privilegedField.get(this);
        }catch(ReflectiveOperationException exception){
            throw new RuntimeException(exception);
        }
        dialog.cont.table(table -> {
            String[] searchText = {""};
            Prov[] matched = {null};
            Runnable[] rebuild = {() -> {}};

            table.background(Tex.button);

            table.table(s -> {
                s.image(Icon.zoom).padRight(8);
                var search = s.field(null, text -> {
                    searchText[0] = text;
                    rebuild[0].run();
                }).growX().get();
                search.setMessageText("@players.search");

                if(!Vars.mobile){
                    Core.app.post(search::requestKeyboard);

                    search.keyDown(KeyCode.enter, () -> {
                        if(!searchText[0].isEmpty() && matched[0] != null){
                            canvas.addAt(position == -1 ? canvas.statements.getChildren().size : position, (LStatement)matched[0].get());
                            dialog.hide();
                        }
                    });
                }
            }).growX().padBottom(4).row();

            table.pane(t -> {
                rebuild[0] = () -> {
                    t.clear();

                    var text = searchText[0].toLowerCase();

                    matched[0] = null;

                    for(Prov<LStatement> prov : LogicIO.allStatements){
                        LStatement example = prov.get();
                        String displayName = statementDisplayName(example);
                        // Cards that cover several features (data-operation cards) contribute their
                        // own keywords, so searching a specific operation still finds the family card.
                        String searchTerms = example instanceof SugarStatements.SugarStatement sugar
                            ? sugar.searchTerms() : null;
                        if(example instanceof LStatements.InvalidStatement || example.hidden() || (example.privileged() && !priv) || (example.nonPrivileged() && priv) ||
                            (!text.isEmpty() && !displayName.toLowerCase(Locale.ROOT).contains(text)
                                && !example.name().toLowerCase(Locale.ROOT).contains(text)
                                && !example.typeName().toLowerCase(Locale.ROOT).contains(text)
                                && (searchTerms == null || !searchTerms.toLowerCase(Locale.ROOT).contains(text))) ||
                            (!priv && !Vars.state.rules.logicUnitControl && example.category() == LCategory.unit)) continue;

                        if(matched[0] == null){
                            matched[0] = prov;
                        }

                        LCategory category = example.category();
                        Table cat = t.find(category.name);
                        if(cat == null){
                            t.table(s -> {
                                if(category.icon != null){
                                    s.image(category.icon, Pal.darkishGray).left().size(15f).padRight(10f);
                                }
                                s.add(category.localized()).color(Pal.darkishGray).left()
                                    .self(c -> SugarTooltip.attach(c, category.description()));
                                s.image(Tex.whiteui, Pal.darkishGray).left().height(5f).growX().padLeft(10f);
                            }).growX().pad(5f).padTop(10f);

                            t.row();

                            cat = t.table(c -> {
                                c.top().left();
                            }).name(category.name).top().left().growX().fillY().get();
                            t.row();
                        }

                        TextButtonStyle style = new TextButtonStyle(Styles.flatt);
                        style.fontColor = category.color;
                        style.font = Fonts.outline;

                        cat.button(displayName, style, () -> {
                            canvas.addAt(position == -1 ? canvas.statements.getChildren().size : position, prov.get());
                            dialog.hide();
                        }).size(130f, 50f).self(c -> {
                            configurePaletteButton(c.get());
                            // LogicSugar statements use dedicated hint keys; vanilla ones keep the game's own lookup
                            SugarTooltip.hint(c, paletteHintKey(example));
                        }).top().left();

                        if(cat.getChildren().size % 3 == 0) cat.row();
                    }
                };

                rebuild[0].run();
            }).grow();
        }).fill().maxHeight(Core.graphics.getHeight() * 0.8f);
        dialog.addCloseButton();
        dialog.show();
    }

    /**
     * Arc's {@link TextButton} enables word wrapping by default.  In the v160 fallback-font
     * layout a CJK title that lands exactly on the 130px palette-button boundary can leave the
     * glyph and advance arrays out of sync and crash in {@code GlyphLayout.setText}; the six
     * character Chinese title for {@code blockend} reproduced {@code 6 >= 6}.  Palette entries
     * are single-line names, so truncate unusually long translations instead of wrapping them.
     */
    static void configurePaletteButton(TextButton button){
        if(button == null) return;
        button.getLabel().setWrap(false);
        button.getLabel().setEllipsis(true);
        button.getLabelCell().minWidth(0f);
    }

    /**
     * Uses v160's canonical statement localization while preserving Sugar's own bundle keys.
     * {@code localizedName()} exists on MindustryX (and on some later v160 cores) but not on
     * vanilla v159, and Neon compiles this file against a vanilla classpath, so the optional
     * accessor is resolved reflectively and degrades to {@link LStatement#name()}.
     */
    private static String statementDisplayName(LStatement statement){
        if(statement instanceof SugarStatements.SugarStatement) return statement.name();
        if(!Core.settings.getBool("logiclocalization", true)) return statement.name();
        Object localized = optionalStatementString(statement, "localizedName");
        return localized instanceof String ? (String)localized : statement.name();
    }

    /**
     * Palette button hint key for {@code example}, or null when it has no description entry.
     *
     * <p>Two namespaces meet in the plus menu, and mis-resolving either one is silent: the button
     * simply shows no hint. LogicSugar's cards describe themselves under
     * {@code logicsugar.lst.<typeName>}; a vanilla statement's description is the game's
     * {@code lst.*} entry, which vanilla builds as {@code "lst." + statementKey()} on cores that
     * expose v160's accessor and as {@code "lst." + name()} on older ones.</p>
     *
     * <p>The reflective fallback used to return the bare {@code statementKey()} — without the
     * {@code "lst."} prefix — so every vanilla statement asked the bundle for {@code sensor}
     * instead of {@code lst.sensor} and the palette showed no hint at all (issue #24, 2026-10).
     * Falling back to {@code name()} when the accessor is absent is what keeps pre-v160 cores,
     * where the palette used to throw {@code NoSuchMethodError}, working instead.</p>
     *
     * <p>A LogicSugar card never falls back into the game's namespace: a card without its own
     * translation must show no hint rather than the description of an unrelated vanilla statement
     * that happens to share its name — the {@code Set} declaration card is vanilla's
     * {@code lst.set}.</p>
     */
    static String paletteHintKey(LStatement example){
        boolean modOwned = example instanceof SugarStatements.SugarStatement;
        Object statementKey = modOwned ? null : optionalStatementString(example, "statementKey");
        return paletteHintKey(example.typeName(),
            statementKey instanceof String ? (String)statementKey : null, example.name(), modOwned);
    }

    /**
     * The rule behind {@link #paletteHintKey(LStatement)}, split out so the version matrix is
     * testable on a core that always exposes {@code statementKey()}: {@code sugarKey} comes from
     * {@code typeName()}, {@code statementKey} is the v160 accessor's value (null on older cores)
     * and {@code name} is {@link LStatement#name()} — what vanilla used before that accessor
     * existed. Returns the key the bundle really has ({@link SugarTooltip#resolveHint} normalizes
     * it), or null when nothing matches.
     */
    static String paletteHintKey(String typeName, String statementKey, String name, boolean modOwned){
        if(modOwned) return SugarTooltip.resolveHint("logicsugar.lst." + typeName.toLowerCase(Locale.ROOT));
        return SugarTooltip.resolveHint("lst." + (statementKey != null ? statementKey : name));
    }

    private static Object optionalStatementString(LStatement statement, String method){
        try{
            return statement.getClass().getMethod(method).invoke(statement);
        }catch(ReflectiveOperationException | RuntimeException ignored){
            return null;
        }
    }

    @Override
    public void show(String code, LExecutor executor, boolean privileged, Cons<String> modified){
        this.executor = executor;
        // Function-library sessions (executor == null) parse with the raised library limit,
        // including the vanilla LCanvas.load inside super.show below.
        if(canvas instanceof SugarCanvas sugarCanvas) sugarCanvas.librarySession = executor == null;
        this.editingPrivileged = privileged;
        discardButton.visible = executor == null;
        this.openedCode = code;
        this.originalCode = null;
        this.recoveredSugar = null;
        this.showingOriginal = false;
        this.lastBudget = null;
        this.budgetToastShown = false;
        this.budgetTimer = 24f;
        this.overBudgetConfirmOpen = false;
        this.closingWithoutSave = false;
        clearOriginalViewCache();
        // drafts are keyed by Building; drop entries whose processor is gone so the map
        // cannot grow without bound over a session
        drafts.keySet().removeIf(key -> key instanceof Building build && !build.isValid());
        Object key = draftKey(executor);
        boolean fromDraft = key != null && drafts.containsKey(key);
        SugarDecompiler.OpeningMode announceMode = SugarDecompiler.OpeningMode.stored;
        if(fromDraft){
            // a failed compile kept the user's work; trust it over any stored code
            editable = drafts.get(key);
        }else{
            // What to open with is decided in SugarDecompiler.openingSource (headless-testable):
            // trusted stored sugar as-is, a verified inference from vanilla mlog, or the code.
            SugarDecompiler.Opening opening = SugarDecompiler.openingSource(code, privileged, executor == null);
            editable = opening.source;
            announceMode = opening.mode;
            if(opening.mode == SugarDecompiler.OpeningMode.inferred){
                originalCode = code;
                recoveredSugar = opening.source;
            }
        }
        effectiveLibrary = SugarCompiler.effectiveLibrary(code, SugarFunctions.library(), FunctionLibrary.loadText());
        libraryHashAtOpen = FunctionLibrary.hash();

        // Never open the editor with code it cannot parse: LogicDialog's load fallback
        // (canvas.load("")) would present an empty canvas, and the stale-close guard treats
        // an untouched empty canvas as "edited", so closing would submit an empty program and
        // silently wipe the processor. The decision is shared with the headless tests
        // (SugarDecompiler.openableSource): parse what we intended to open, and fall back to the
        // stored program when only that is readable, so an unreadable draft cannot lock the editor.
        SugarDecompiler.OpenDecision decision = SugarDecompiler.openableSource(editable, code, privileged, executor == null);
        if(decision.source == null){
            hide();
            showCompileError(new IllegalArgumentException("Cannot open the logic editor: " + decision.failure), false);
            return;
        }
        if(decision.fallback){
            final String reason = decision.failure;
            if(fromDraft) drafts.remove(key);
            editable = decision.source;
            originalCode = null;
            recoveredSugar = null;
            showingOriginal = false;
            clearOriginalViewCache();
            announceMode = null;
            Log.warn("[LogicSugar] the sugar source could not be parsed, opening the stored program: @", reason);
            Core.app.post(() -> Vars.ui.showInfoFade(Core.bundle.format(fromDraft
                ? "logicsugar.open.draftDropped" : "logicsugar.open.fallback", reason)));
        }else if(announceMode == SugarDecompiler.OpeningMode.inferred){
            Core.app.post(() -> Vars.ui.showInfoFade("@logicsugar.recovered"));
        }else if(announceMode == SugarDecompiler.OpeningMode.raw){
            Core.app.post(() -> Vars.ui.showInfoFade("@logicsugar.external.edit"));
        }

        Cons<String> submit = sugar -> submit(sugar, executor, modified, key, false);
        super.show(editable, executor, privileged, submit);

        // LogicDialog normally suppresses equal results. Sugar must always win the
        // close race against remote processor edits, so replace that consumer.
        setConsumer(sugar -> submit(sugar, executor, modified, key, true));
        resetEditHistory();
    }

    private void submit(String sugar, LExecutor executor, Cons<String> modified, Object key, boolean closing){
        if(closing && closingWithoutSave){
            // Over-budget escape (see dismissOverBudget…/discardOverBudgetChanges): the processor
            // keeps its stored program; never write sugar for this close.
            closingWithoutSave = false;
            if(key != null) drafts.remove(key);
            return;
        }
        // Stale-close protection: an untouched canvas must not clobber a save that happened
        // while the dialog was open; an edited canvas always submits (last writer wins).
        if(executor != null && !SugarCompiler.shouldSubmit(sugar, editable, openedCode, currentCode(executor))){
            return;
        }
        if(executor != null){
            // the library file may have been edited while the processor editor stayed open;
            // refresh the session snapshot so calls resolve against the current library
            int hash = FunctionLibrary.hash();
            if(hash != libraryHashAtOpen){
                libraryHashAtOpen = hash;
                effectiveLibrary = SugarCompiler.effectiveLibrary(openedCode, SugarFunctions.library(), FunctionLibrary.loadText());
            }
        }
        try{
            String compiled = SugarCompiler.compile(sugar, SugarCompiler.currentMode(), effectiveLibrary.index,
                effectiveLibrary.text, SugarCompiler.currentStrategy(), SugarCompiler.currentAssertEmit(), editingPrivileged,
                executor == null);
            if(executor != null && executor.build != null && !executor.build.isValid()){
                drafts.remove(key);
                return;
            }
            if(executor != null && executor.build != null){
                compiled = enforceStorageLimit(compiled, executor);
            }
            drafts.remove(key);
            modified.get(compiled);
        }catch(IllegalArgumentException exception){
            if(closing && key != null) drafts.put(key, sugar);
            if(closing && executor == null && passThroughSugarOnError){
                // Function library session: hand the raw sugar back so the caller can keep
                // the user's work and reopen the editor instead of dropping it.
                modified.get(sugar);
                return;
            }
            Core.app.post(() -> showCompileError(exception, closing));
        }
    }

    /** The stored code as of right now (the build may have been reconfigured while open). */
    private String currentCode(LExecutor executor){
        return executor.build != null ? executor.build.code : openedCode;
    }

    @Override
    public void hide(){
        // 关闭保存路径（hidden -> canvas.save()）没有 try/catch：ExprStatement.write() 对
        // 从未成功编译的表达式抛 IllegalArgumentException 会冒泡成引擎级报错。这里在
        // hide 之前预检，命中则提示并阻止关闭——画布原样保留（标红可见），不破坏任何状态。
        // 仅处理器会话执行预检：函数库会话（executor == null）有自己的错误处理与
        // "放弃修改"路径（discardLibraryChanges），不应被拦截。
        if(executor != null && !passThroughSugarOnError && hasUncompilableExpression()){
            Core.app.post(() -> showCompileError(
                new IllegalArgumentException(uncompilableExpressionMessage()), true));
            return;
        }
        if(executor != null && !passThroughSugarOnError && overProcessorBudget()){
            // Refusal stays the normal outcome; the confirm is the only supported escape, and it
            // never offers a way to save more than the pinned budget (see discardOverBudgetChanges).
            Core.app.post(this::showOverBudgetClosePrompt);
            return;
        }
        clearCompiledCopyCache();
        clearOriginalViewCache();
        pendingExportDialog = null;
        super.hide();
    }

    /** 画布上是否存在从未成功编译的表达式（ExprStatement.write 会因此抛错）。 */
    private boolean hasUncompilableExpression(){
        return uncompilableExpressionMessage() != null;
    }

    /**
     * The only supported escape from an over-budget program: <b>discard changes and close</b>.
     *
     * <p>The pinned 1000-instruction product limit means a program authored elsewhere at, say,
     * 1200 instructions cannot be saved here — without this prompt the editor could never be
     * closed again. There is deliberately no "save anyway": the processor keeps the program it
     * had when the session opened, and this session's edits are dropped.</p>
     *
     * <p>Reachable only from the over-budget branch of {@link #hide()}; the ordinary refusal
     * (including every close that fits the budget) is unchanged.</p>
     */
    private void showOverBudgetClosePrompt(){
        if(overBudgetConfirmOpen || !isShown()) return;
        overBudgetConfirmOpen = true;
        String message = Core.bundle.format("logicsugar.budget.escape.text",
            budgetCloseError().getMessage(), InstructionBudget.vanillaLimit());
        BaseDialog dialog = new BaseDialog(Core.bundle.get("logicsugar.budget.escape.title"));
        dialog.cont.add(message).width(Vars.mobile ? 400f : 500f).wrap().pad(4f)
            .get().setAlignment(Align.center, Align.center);
        dialog.buttons.defaults().size(220f, 54f).pad(2f);
        dialog.buttons.button("@cancel", Icon.cancel, () -> {
            overBudgetConfirmOpen = false;
            dialog.hide();
        });
        dialog.buttons.button(Core.bundle.get("logicsugar.budget.escape.confirm"), Icon.trash, () -> {
            overBudgetConfirmOpen = false;
            dialog.hide();
            discardOverBudgetChanges();
        });
        // Esc / any other dismissal must clear the guard too, or a later close attempt would
        // silently do nothing.
        dialog.hidden(() -> overBudgetConfirmOpen = false);
        dialog.show();
    }

    /** Closes without writing sugar: the close-time submit is short-circuited and the retained
     *  draft for this processor is dropped, so reopening shows the program that was stored before
     *  this session (the user's over-budget edits are gone, by explicit choice). */
    private void discardOverBudgetChanges(){
        Object key = draftKey(executor);
        if(key != null) drafts.remove(key);
        closingWithoutSave = true;
        // Bypass this class's own close guards; the confirm already replaced them.
        super.hide();
    }

    /** 第一个不可编译表达式的错误消息；全部合法时返回 null。
     *  与 ExprStatement.write() 完全同口径：同样使用 functionChecker() 校验函数名，
     *  否则未定义用户函数（如 noSuch(a)）会在预检中被漏过、保存时才抛错。 */
    private String uncompilableExpressionMessage(){
        if(canvas == null || canvas.statements == null) return null;
        for(Element child : canvas.statements.getChildren()){
            if(child instanceof LCanvas.StatementElem elem && elem.st instanceof ExprStatement expr){
                try{
                    ExprCompiler.compile(expr.dest, expr.expr, ExprStatement.functionChecker());
                }catch(Exception e){
                    return e.getMessage();
                }
            }
        }
        return null;
    }

    /** Library-file editing sessions only: close the editor without saving, so a user who
     *  cannot (or does not want to) fix the library can leave instead of being forced to
     *  reopen until the content validates. The library file keeps its last saved content. */
    private void discardLibraryChanges(){
        if(executor != null) return; // processor sessions keep their normal close semantics
        passThroughSugarOnError = false;
        drafts.clear();
        hide();
    }

    /**
     * Pre-checks the 16KB compressed storage limit before the block's own consumer does.
     * Over the limit, the comment marker (redundant sugar source) is stripped and the
     * carrier-based restore is kept; still over, the compile fails loudly so the draft
     * retention path keeps the user's work instead of vanilla's silent drop.
     */
    private String enforceStorageLimit(String compiled, LExecutor executor){
        LogicBlock.LogicBuild build = (LogicBlock.LogicBuild)executor.build;
        Seq<LogicBlock.LogicLink> links = build.relativeConnections();
        byte[] bytes = LogicBlock.compress(compiled, links);
        if(bytes.length <= maxCompressedBytes) return compiled;
        String stripped = SugarCompiler.stripMarkers(compiled);
        byte[] retry = LogicBlock.compress(stripped, links);
        if(retry.length <= maxCompressedBytes) return stripped;
        throw new IllegalArgumentException("Compiled program is too large to store: " + retry.length
            + " compressed bytes (limit " + maxCompressedBytes + "), even without the comment marker.");
    }

    private Object draftKey(LExecutor executor){
        if(executor == null) return null;
        Building build = executor.build;
        return build != null ? build : executor;
    }

    private void setConsumer(Cons<String> consumer){
        try{
            consumerField.set(this, consumer);
        }catch(IllegalAccessException exception){
            throw new RuntimeException("Unable to configure Logic Sugar save behavior", exception);
        }
    }

    private static Field field(Class<?> type, String name){
        try{
            Field result = type.getDeclaredField(name);
            result.setAccessible(true);
            return result;
        }catch(ReflectiveOperationException exception){
            throw new ExceptionInInitializerError(exception);
        }
    }

    private void showCompileError(IllegalArgumentException exception, boolean draftKept){
        String key = draftKept ? "logicsugar.error.draft" : "logicsugar.error.compile";
        Vars.ui.showErrorMessage(Core.bundle.format(key, exception.getMessage()));
    }

    /** Recompiles the canvas and updates the live instruction-budget banner. */
    private void refreshInstructionBudget(){
        // Sampled from the existing 24-frame tick: a foreign raise of LExecutor.maxInstructions
        // does not change LogicSugar's enforcement (always the pinned vanilla 1000), so say it
        // once instead of letting the banner/limit look inconsistent. Never in headless runs
        // (Vars.ui is null there and the tick does not run anyway).
        noticePinnedBudget();
        if(!isShown() || budgetLabel == null || canvas == null) return;
        String sugar;
        try{
            sugar = canvas.save();
        }catch(Throwable ignored){
            return;
        }
        if(executor == null){
            // 函数库会话不是一个处理器程序：1000 条处理器保存上限不适用，函数库有独立上限。
            // 显示「库源码行数 / 函数库上限」，超限标红提示；仍然不写入 lastBudget，
            // 因为关闭路径只对处理器会话做超限拦截（函数库保存由 FunctionLibrary.save 把关）。

            lastBudget = null;
            int libraryLines = SugarCompiler.emittedInstructionCount(sugar);
            boolean libraryOver = libraryLines > SugarFunctions.libraryInstructionLimit;
            budgetLabel.setText(Core.bundle.format(
                libraryOver ? "logicsugar.budget.library.over" : "logicsugar.budget.library",
                libraryLines, SugarFunctions.libraryInstructionLimit));

            budgetLabel.setColor(libraryOver ? Pal.remove : Color.lightGray);
            return;
        }
        lastBudget = InstructionBudget.of(sugar, SugarCompiler.currentMode(),
            effectiveLibrary.index, effectiveLibrary.text);
        int storage = compressedSize(lastBudget);
        updateBudgetLabel(storage);
        boolean over = lastBudget.over() || storageOver(storage);
        if(over && !budgetToastShown){
            budgetToastShown = true;
            Vars.ui.showInfoFade(Core.bundle.format("logicsugar.budget.toast",
                lastBudget.displayCount(), lastBudget.instructionLimit));
        }
        if(!over) budgetToastShown = false;
    }

    /** One-time notice: {@code LExecutor.maxInstructions} no longer matches vanilla because
     *  another mod (MDT's slider) raised it. LogicSugar keeps enforcing the pinned 1000 limit,
     *  so tell the user instead of letting them believe the larger number applies. */
    private void noticePinnedBudget(){
        if(budgetPinNoticeShown) return;
        if(LExecutor.maxInstructions == InstructionBudget.VANILLA_LIMIT) return;
        if(Vars.ui == null) return; // headless / test runs never warn
        budgetPinNoticeShown = true;
        Vars.ui.showInfoFade(Core.bundle.format("logicsugar.budget.pinned",
            LExecutor.maxInstructions, InstructionBudget.VANILLA_LIMIT));
    }

    private void updateBudgetLabel(int storage){
        if(budgetLabel == null || lastBudget == null) return;
        boolean over = lastBudget.over() || storageOver(storage);
        String text = Core.bundle.format(over ? "logicsugar.budget.over" : "logicsugar.budget",
            lastBudget.displayCount(), lastBudget.instructionLimit);
        if(storage >= 0){
            text += "\n" + Core.bundle.format("logicsugar.budget.storage", storage, maxCompressedBytes);
        }
        budgetLabel.setText(text);
        budgetLabel.setColor(over ? Pal.remove : Color.lightGray);
    }

    private int compressedSize(InstructionBudget.Snapshot snapshot){
        if(snapshot == null || snapshot.compiled == null) return -1;
        if(executor == null || !(executor.build instanceof LogicBlock.LogicBuild)) return -1;
        try{
            LogicBlock.LogicBuild build = (LogicBlock.LogicBuild)executor.build;
            return LogicBlock.compress(snapshot.compiled, build.relativeConnections()).length;
        }catch(Throwable ignored){
            return -1;
        }
    }

    private boolean storageOver(int storage){
        return storage > maxCompressedBytes;
    }

    private boolean overProcessorBudget(){
        String sugar;
        try{
            sugar = canvas.save();
        }catch(Throwable ignored){
            return false;
        }
        lastBudget = InstructionBudget.of(sugar, SugarCompiler.currentMode(),
            effectiveLibrary.index, effectiveLibrary.text);
        int storage = compressedSize(lastBudget);
        updateBudgetLabel(storage);
        return lastBudget.over() || storageOver(storage);
    }

    private IllegalArgumentException budgetCloseError(){
        if(lastBudget != null && lastBudget.over()){
            String hint = SugarCompiler.currentMode() == SugarCompiler.FuncMode.inline
                ? Core.bundle.get("logicsugar.error.budget.inlineHint") : "";
            return new IllegalArgumentException(Core.bundle.format("logicsugar.error.budget",
                lastBudget.displayCount(), lastBudget.instructionLimit, hint));
        }
        int storage = compressedSize(lastBudget);
        return new IllegalArgumentException(Core.bundle.format("logicsugar.error.storage",
            storage, maxCompressedBytes));
    }
}
