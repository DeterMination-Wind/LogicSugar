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
