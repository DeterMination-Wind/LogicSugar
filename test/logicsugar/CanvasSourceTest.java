package logicsugar;

/**
 * {@code SugarCanvas} 渲染路径上的源码钉子：无头测试看不见画布，只能钉住代码写法。
 *
 * <p>两条不变量：</p>
 * <ol>
 *   <li><b>{@code save()} 是纯文本读取</b>：它会被周期性调用（指令预算横幅每 24 帧一次、共存档里
 *       第三方编辑器每帧一次），一旦在前后 unfold/fold，正在编辑的 Expression 卡就会被拆掉重建。</li>
 *   <li><b>行号标签不在「语句下标」与「mlog 下标」之间来回跳</b>：顺序必须是先原版布局、再写
 *       文本、最后清 {@code needsLayout}。</li>
 * </ol>
 */
public final class CanvasSourceTest{
    private static int failures;

    private CanvasSourceTest(){}

    public static void main(String[] args){
        try{
            saveIsAPureTextRead();
            addressLabelsDoNotOscillate();
            dialogResizeAndMdtIsolationNails();
        }catch(java.io.IOException exception){
            check(false, "canvas sources could not be read: " + exception.getMessage());
        }

        if(failures > 0){
            System.out.println("FAILED: " + failures + " check(s)");
            System.exit(1);
        }
        System.out.println("CanvasSourceTest: all checks passed");
    }

    /**
     * {@code save()} 必须是纯文本读取：它会被周期性调用（指令预算横幅每 24 帧一次、共存档里
     * 第三方编辑器每帧一次），只要它在前后 unfold/fold，正在编辑的 Expression 卡就会被拆掉
     * 重建，文本框下一帧因元素脱离而失焦（2026-10 报告：“点进编辑区域马上丢焦点、卡片每帧
     * 抖动”）；而一旦某次 {@code foldAll} 因外部读取拒绝折回，卡片还会被永久退化成裸 op 积木。
     *
     * <p>展开改在文本层：{@code ExprHook.unfoldedText} 把多行卡展开成 op 行并把 jump/begin
     * 记的<b>画布</b>语句下标换算成<b>文本</b>语句下标（逐字等价与下标自洽由
     * {@code exprCardTest} 的 {@code unfoldedTextMatchesTheUnfoldedCanvas} 钉住）。</p>
     */
    private static void saveIsAPureTextRead() throws java.io.IOException{
        System.out.println("== save() 不改画布 ==");
        String canvas = SourceNails.readSource("src/mindustry/logic/SugarCanvas.java");
        String save = withoutComments(SourceNails.methodBody(canvas, "public String save(){"));
        check(save.contains("ExprHook.unfoldedText(this)"),
            "save() must hand over the text-layer unfolded program text");
        check(!save.contains("unfoldAll") && !save.contains("foldAll"),
            "save() must not unfold/fold the canvas: it is called periodically and every call would "
                + "rebuild the statement elements (the focused Expression field loses focus every time)");
        check(!save.contains("super.save()"),
            "super.save() would write the folded text: multi-line cards would shift every jump/begin "
                + "target written from a canvas statement index");

        // 卡片的“保不保留”判定只有一处，两条路径不能漂
        String haystack = SourceNails.readSource("src/logicsugar/assist/expr/ExprHook.java");
        String cardLines = withoutComments(SourceNails.methodBody(haystack, "private static List<ExprCompiler.Line> cardLines("));
        check(cardLines.contains("ExprStatement.functionChecker()"),
            "cardLines() must validate function names exactly like the editor and the save pre-check");
        int uses = haystack.split("cardLines\\(exprStmt", -1).length - 1;
        check(uses >= 1, "unfoldAll() must keep using the shared cardLines() decision");
    }

    /**
     * 行号标签不能每帧在“语句下标”与“mlog 下标”之间来回跳：宽度改变会让卡片头部左右抖动，
     * 还会让整个语句列表每帧重排一次（2026-10 报告：“expr 积木渲染抖动（向左抖动）”）。
     * 顺序必须先是原版布局、再写我们的文本、最后把 {@code needsLayout} 清掉。
     */
    private static void addressLabelsDoNotOscillate() throws java.io.IOException{
        System.out.println("== 行号标签不抖动 ==");
        String canvas = SourceNails.readSource("src/mindustry/logic/SugarCanvas.java");
        String body = withoutComments(SourceNails.methodBody(canvas, "private void updateMlogAddresses(){"));
        int layout = body.indexOf("statements.validate()");
        int write = body.indexOf("setLabelText(");
        int clear = body.indexOf("clearStatementLayoutFlag()");
        check(layout >= 0 && write > layout && clear > write,
            "updateMlogAddresses() must run the vanilla layout first, then write the mlog text, then "
                + "clear needsLayout (otherwise the next draw() re-runs layout and both texts alternate "
                + "every frame)");
        check(body.contains("if(!changed) return;"),
            "the idle path must not force a layout pass at all (it runs every frame for every card)");
    }

    /**
     * 与 MDT 共存的四条界面不变量（无头测试没有 Scene，只能钉源码）：
     *
     * <ol>
     *   <li>压缩/竖屏尺寸变化后底栏必须回来：原版 {@code onResize} 会重跑 {@code setup()} 清空
     *       {@code buttons}，而 {@code shown} 不会再触发，所以构造函数必须挂 resize 钩子重跑幂等的
     *       {@code installSugarButtons()}。</li>
     *   <li>编译副本菜单只挂进**我们自己**打开的原版导出对话框：MDT 的 LogDialog 也有
     *       {@code copy.clipboard} 按钮，整场景按文本扫描会把菜单注入别人的窗口。</li>
     *   <li>Ctrl+Z / Delete 只在逻辑编辑器是最上层对话框时生效。</li>
     *   <li>原版 {@code @add} 按钮的禁用阈值必须钉在 {@link logicsugar.assist.InstructionBudget#vanillaLimit()}，
     *       不能跟着 MDT 抬高的 {@code LExecutor.maxInstructions} 走。</li>
     * </ol>
     */
    private static void dialogResizeAndMdtIsolationNails() throws java.io.IOException{
        System.out.println("== MDT 共存：底栏/菜单/快捷键/预算 ==");
        String dialog = SourceNails.readSource("src/mindustry/logic/SugarLogicDialog.java");

        String constructor = withoutComments(SourceNails.methodBody(dialog, "public SugarLogicDialog(){"));
        check(constructor.contains("onResize(this::installSugarButtons)"),
            "the constructor must re-install the Sugar buttons on resize: vanilla setup() clears the row "
                + "when compact/portrait flips, and shown() does not fire again");
        check(SourceNails.methodBody(dialog, "private void installSugarButtons()")
                .contains("layoutBottomButtons()"),
            "the resize hook must end in the packed layout, not only re-add buttons");

        String copy = withoutComments(SourceNails.methodBody(dialog, "private void installCompiledCopy(){"));
        check(!copy.contains("findCopyButton(Core.scene.root)"),
            "the copy-button scan must not search the whole scene: MDT's LogDialog has a button with "
                + "the same localized label and would receive LS's menu");
        check(copy.contains("findCopyButton(export)"),
            "the scan must be scoped to one export dialog, never the scene");
        check(copy.contains("pendingExportDialog"),
            "the export dialog captured from LS's own edit click must be preferred");
        check(copy.contains("SelectionClipboardUi.exportDialog()"),
            "when the click-time capture missed, the fallback must still be the title-checked export dialog");
        check(copy.contains("Core.scene.getDialog() != export"),
            "the dialog being scanned must still be the front dialog, or the scan is abandoned");
        String editHook = withoutComments(SourceNails.methodBody(dialog, "private void installEditHook(){"));
        check(editHook.contains("pendingExportDialog = SelectionClipboardUi.exportDialog()"),
            "the edit button's click must capture the export dialog it just opened");

        String shortcuts = withoutComments(SourceNails.methodBody(dialog, "private void pollEditorShortcuts(){"));
        check(shortcuts.contains("SelectionClipboardUi.isFront(this)"),
            "Ctrl+Z/Y must stop while a stacked dialog (MDT's Vars/Log/Profiler) is in front");

        String add = withoutComments(SourceNails.methodBody(dialog, "private void installAddButton(){"));
        check(add.contains("setDisabled"), "the claimed vanilla @add button must have its predicate replaced");
        check(add.contains("InstructionBudget.vanillaLimit()"),
            "the @add button must stop at the pinned vanilla limit, not LExecutor.maxInstructions");

        String clipboard = SourceNails.readSource("src/logicsugar/assist/SelectionClipboardUi.java");
        check(!clipboard.contains("public static TextButton findCopyButton(Element"),
            "the scene-wide public findCopyButton(Element) scan must be gone");
        String export = withoutComments(SourceNails.methodBody(clipboard, "public static Dialog exportDialog(){"));
        check(export.contains("Core.scene.getDialog()"),
            "only the front dialog may be treated as the export dialog");
        check(export.contains("isExportDialog"), "the front dialog must also match @editor.export");
        String exportCheck = withoutComments(SourceNails.methodBody(clipboard, "public static boolean isExportDialog(Dialog dialog){"));
        check(exportCheck.contains("\"editor.export\""),
            "the title check must use the game's @editor.export string");

        String box = SourceNails.readSource("src/logicsugar/assist/BoxSelect.java");
        check(box.contains("SelectionClipboardUi.isFront(dialog)"),
            "Delete/Backspace must require the logic editor to be the front dialog");
    }

    /** 一段源码去掉注释（整行与行尾），只留可执行文本：源码钉子应该钉代码，不该被注释里的
     *  反例写法误伤。 */
    private static String withoutComments(String source){
        StringBuilder out = new StringBuilder();
        for(String line : source.split("\n", -1)){
            String bare = line.trim();
            if(bare.startsWith("//") || bare.startsWith("*") || bare.startsWith("/*")) continue;
            int comment = line.indexOf("//");
            if(comment >= 0) line = line.substring(0, comment);
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static void check(boolean condition, String message){
        if(!condition){
            failures++;
            System.out.println("  FAIL: " + message);
        }
    }
}
