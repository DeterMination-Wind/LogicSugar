package logicsugar;

import mindustry.logic.SugarCompiler;
import mindustry.logic.SugarFunctions;
import mindustry.logic.SugarStatements;

/**
 * 编译期来源通道（{@code @counter} 指示线的地基）的回归测试。
 *
 * <p>两条不变量：</p>
 * <ol>
 *   <li><b>产物逐字节不变</b>：{@link SugarCompiler#compileRecorded} 与
 *       {@link SugarCompiler#compile} 的产物必须完全相同 —— 记录是旁路，不能碰正文。</li>
 *   <li><b>来源对得上</b>：每一条指令的来源语句下标都要指向真正发射它的那张卡；编译器自己
 *       产生的指令（入口 skip、hoist 前导跳、函数体、返回跳板）一律是
 *       {@link SugarFunctions#syntheticOrigin}，绝不能指到某张画布积木上。</li>
 * </ol>
 */
public class OriginRecordingTest{
    private static int failures;

    public static void main(String[] args){
        SugarStatements.installParsers();

        compileIsByteIdentical();
        straightLineOriginsAreExact();
        vanillaProgramIsOneToOne();
        sugarBlockOriginsFollowTheirCards();
        functionBodiesAreSynthetic();
        entrySkipIsSynthetic();
        recordedOriginsAlignWithStrippedStream();
        counterTargetsResolveThroughProvenance();
        anchorsAreLocalCoordinates();
        try{
            overlayUsesReadonlySnapshot();
            overlayRailsFollowLanes();
        }catch(java.io.IOException exception){
            check(false, "overlay sources could not be read: " + exception.getMessage());
        }

        if(failures > 0){
            System.out.println("FAILED: " + failures + " check(s)");
            System.exit(1);
        }
        System.out.println("OriginRecordingTest: all checks passed");
    }

    // ===== 1. 产物逐字节不变 ==============================================================

    private static void compileIsByteIdentical(){
        System.out.println("== 产物逐字节不变 ==");
        for(String source : fixtures()){
            for(SugarCompiler.FuncMode mode : SugarCompiler.FuncMode.values()){
                String plain = SugarCompiler.compile(source, mode);
                SugarCompiler.CompileProvenance recorded = record(source, mode);
                check(recorded != null, "compileRecorded returned null for: " + firstLine(source) + " / " + mode);
                if(recorded == null) continue;
                check(plain.equals(recorded.code),
                    "产物被记录改动 (" + firstLine(source) + " / " + mode + "):\n--- plain ---\n"
                        + plain + "--- recorded ---\n" + recorded.code);
            }
        }
    }

    /** 覆盖直线、纯原版、for、while、if、switch、函数调用与 @counter 写法。
     *  结构头的最后一个数字是 destIndex（jump 注释），编译期由 recomputeBlockDests 修正，
     *  这里给一个明显不可能的占位值即可。 */
    private static String[] fixtures(){
        return new String[]{
            "set x 1\nop add x x 2\nprint x\n",
            "set @counter 3\nprint 1\nprint 2\nprint 3\n",
            "op add @counter @counter 1\nprint 1\nprint 2\n",
            "forbegin i 0 1 lessThan 10 999\nprint i\nblockend\n",
            "whilebegin x lessThan 10 999\nop add x x 1\nblockend\n",
            "ifbegin x greaterThan 1 999\nprint x\nblockend\n",
            "switchbegin x 999\ncase 1\nprint one\nbreak\ncase 2\nprint two\nbreak\nblockend\n",
            "funcdef f a 999\nreturn \"a + 1\"\nblockend\nset x 3\nfunccall f \"x\" out\nprint out\n",
        };
    }

    // ===== 2. 结构化程序：直线语句逐条对应 ==============================================

    private static void straightLineOriginsAreExact(){
        System.out.println("== 直线语句 ==");
        String source = "forbegin i 0 1 lessThan 10 999\nprint i\nblockend\n";
        SugarCompiler.CompileProvenance recorded = record(source, SugarCompiler.FuncMode.normal);
        check(recorded != null, "no provenance");
        if(recorded == null) return;
        String[] lines = SugarCompiler.stripMarkers(recorded.code).split("\n", -1);

        // 语句下标：0 = forbegin，1 = print，2 = blockend
        checkOwner(recorded, lines, "set i", 0, "loop init");
        checkOwner(recorded, lines, "print i", 1, "loop body");
        checkOwner(recorded, lines, "op add i i", 2, "loop step (emitted by the blockend card)");
        // 回跳而不是条件跳转：条件跳转属于 for 头
        checkOwner(recorded, lines, "jump __ls_for_check_", 2, "loop-back jump (emitted by the blockend card)");
        checkOwner(recorded, lines, "jump __ls_for_body_", 0, "loop condition jump (emitted by the for head)");
    }

    /** 产物里第一条以 {@code prefix} 开头的指令，其来源必须是 {@code expected}。 */
    private static void checkOwner(SugarCompiler.CompileProvenance recorded, String[] lines,
                                   String prefix, int expected, String what){
        int line = indexOfPrefix(lines, prefix);
        check(line >= 0, "no instruction starting with '" + prefix + "':\n" + SugarCompiler.stripMarkers(recorded.code));
        if(line < 0) return;
        int owner = recorded.originOf(instructionIndexOf(lines, line));
        check(owner == expected, what + " should belong to statement " + expected + ", got " + owner
            + " (line: " + lines[line].trim() + ")");
    }

    // ===== 3. 纯原版程序逐行对应 ========================================================

    private static void vanillaProgramIsOneToOne(){
        System.out.println("== 纯原版程序 ==");
        String source = "set @counter 2\nprint 1\nprint 2\n";
        SugarCompiler.CompileProvenance recorded = record(source, SugarCompiler.FuncMode.normal);
        check(recorded != null, "no provenance for a vanilla program");
        if(recorded == null) return;
        // 纯原版程序没有载体，compile() 原样返回源码：3 条语句 = 3 条指令，逐行 1:1
        check(recorded.instructions() == 3,
            "expected 3 instructions (the source is returned unchanged), got " + recorded.instructions());
        for(int i = 0; i < 3; i++){
            check(recorded.originOf(i) == i, "instruction " + i + " should belong to statement " + i
                + ", got " + recorded.originOf(i));
        }
    }

    // ===== 4. 语句下标跟随画布顺序 ======================================================

    private static void sugarBlockOriginsFollowTheirCards(){
        System.out.println("== if/while 积木 ==");
        String source = "ifbegin x greaterThan 1 999\nwhilebegin y lessThan 3 999\nprint y\nblockend\nblockend\n";
        SugarCompiler.CompileProvenance recorded = record(source, SugarCompiler.FuncMode.normal);
        check(recorded != null, "no provenance");
        if(recorded == null) return;
        String[] lines = SugarCompiler.stripMarkers(recorded.code).split("\n", -1);
        int printLine = indexOfPrefix(lines, "print");
        check(printLine >= 0, "no print instruction");
        if(printLine >= 0){
            int owner = recorded.originOf(instructionIndexOf(lines, printLine));
            check(owner == 2, "print belongs to statement 2, got " + owner);
        }
    }

    // ===== 5. 函数体（normal 模式 hoist）全段 synthetic ==================================

    private static void functionBodiesAreSynthetic(){
        System.out.println("== 函数体 hoist ==");
        String source = "funcdef f a 999\nreturn \"a + 1\"\nblockend\nset x 3\nfunccall f \"x\" out\nprint out\n";
        SugarCompiler.CompileProvenance recorded = record(source, SugarCompiler.FuncMode.normal);
        check(recorded != null, "no provenance");
        if(recorded == null) return;
        String lowered = SugarCompiler.stripMarkers(recorded.code);

        int prelude = lowered.indexOf("jump __ls_end always x false");
        check(prelude >= 0, "normal mode did not emit the hoist prelude:\n" + lowered);
        if(prelude >= 0){
            String[] lines = lowered.split("\n", -1);
            int preludeLine = 0;
            int seen = 0;
            for(int i = 0; i < lines.length; i++){
                if(seen == prelude) preludeLine = i;
                seen += lines[i].length() + 1;
            }
            for(int instruction = instructionIndexOf(lines, preludeLine); instruction < recorded.instructions(); instruction++){
                check(recorded.originOf(instruction) == SugarFunctions.syntheticOrigin,
                    "hoisted region instruction " + instruction + " must be synthetic, got "
                        + recorded.originOf(instruction) + "\n" + lowered);
            }
        }

        // 内联模式：函数体复制到调用点，调用方积木的下标仍然有效
        SugarCompiler.CompileProvenance inline = record(source, SugarCompiler.FuncMode.inline);
        check(inline != null, "no provenance (inline)");
        if(inline != null){
            check(SugarCompiler.compile(source, SugarCompiler.FuncMode.inline).equals(inline.code),
                "inline product changed under recording");
        }
    }

    // ===== 6. 入口 skip 是 synthetic =====================================================

    private static void entrySkipIsSynthetic(){
        System.out.println("== 入口 skip ==");
        String source = "forbegin i 0 1 lessThan 3 999\nprint i\nblockend\n";
        SugarCompiler.CompileProvenance recorded = record(source, SugarCompiler.FuncMode.normal);
        check(recorded != null, "no provenance");
        if(recorded == null) return;
        check(recorded.originOf(recorded.instructions() - 1) == SugarFunctions.syntheticOrigin,
            "entry skip must be synthetic, got " + recorded.originOf(recorded.instructions() - 1));
    }

    // ===== 7. 来源数组与 stripMarkers 的指令流逐条对齐 ====================================

    private static void recordedOriginsAlignWithStrippedStream(){
        System.out.println("== 与正文指令流对齐 ==");
        for(String source : fixtures()){
            SugarCompiler.CompileProvenance recorded = record(source, SugarCompiler.FuncMode.normal);
            if(recorded == null){
                check(false, "no provenance for " + firstLine(source));
                continue;
            }
            // 口径必须与 CompileProvenance 一致：去标记块、去载体行，标签行占行号不占指令下标。
            // 注意不能用 SugarCompiler.emittedInstructionCount —— 它保留载体行（预算横幅要算上
            // 它们），而来源通道只覆盖正文。
            int body = bodyInstructionCount(recorded.code);
            check(body == recorded.instructions(),
                "body counts " + body + " instructions but provenance has "
                    + recorded.instructions() + " (" + firstLine(source) + ")\n"
                    + "origins=" + java.util.Arrays.toString(recorded.origins) + "\n--- product ---\n"
                    + SugarCompiler.stripMarkers(recorded.code));
        }
    }

    /** 正文指令条数：跳过标签行、载体行、空行与注释行。 */
    private static int bodyInstructionCount(String code){
        int count = 0;
        for(String line : SugarCompiler.stripMarkers(code).split("\n", -1)){
            String bare = line.trim();
            if(bare.isEmpty() || bare.startsWith("#") || isCarrier(bare)) continue;
            if(bare.length() >= 2 && bare.endsWith(":") && !bare.contains(" ")) continue;
            count++;
        }
        return count;
    }

    /**
     * {@code @counter = N} 的 N 是<b>指令下标</b>，不是语句下标：要通过来源通道换算才能落到积木上。
     *
     * <p>2026-09 的错位报告就是混用了这两个索引空间 —— 把 N 直接当成语句下标去取元素，于是
     * {@code set @counter 3} 画到了第 4 张卡上，而真正该指向的是指令 3 所在的第 2 张卡。这里钉住
     * 编辑器依赖的那条换算链：{@code originOf(N)} 给出 N 所属的语句。</p>
     */
    private static void counterTargetsResolveThroughProvenance(){
        System.out.println("== @counter 目标换算到语句下标 ==");
        // 与报告里的程序同构：set @counter 3 / wait / wait / set @counter 1 / wait
        String source = "set @counter 3\nwait 0.5\nwait 0.5\nset @counter 1\nwait 0.5\n";
        SugarCompiler.CompileProvenance recorded = record(source, SugarCompiler.FuncMode.normal);
        check(recorded != null, "no provenance");
        if(recorded == null) return;

        // 纯原版程序逐行对应：指令 0..4 = 语句 0..4
        for(int i = 0; i < 5; i++){
            check(recorded.originOf(i) == i,
                "instruction " + i + " should belong to statement " + i + ", got " + recorded.originOf(i));
        }
        // set @counter 3 的目标是指令 3，它属于语句 3（而不是"语句 3"这种巧合之外的任何猜测）
        check(recorded.originOf(3) == 3, "instruction 3 belongs to statement 3, got " + recorded.originOf(3));

        // 结构化程序里两者不再相等：这条才是真正会踩坑的形状
        String sugar = "forbegin i 0 1 lessThan 3 999\nprint i\nblockend\n";
        SugarCompiler.CompileProvenance loop = record(sugar, SugarCompiler.FuncMode.normal);
        check(loop != null, "no provenance (loop)");
        if(loop == null) return;
        // for 的指令区间是 [0,6)：指令 0..2 归 for 头、3 归 print、4..5 归 blockend
        check(loop.originOf(0) == 0 && loop.originOf(3) == 1 && loop.originOf(4) == 2,
            "loop instruction origins are wrong: " + java.util.Arrays.toString(loop.origins));
        check(loop.instructions() != 3,
            "the loop compiles to more instructions than statements - the two index spaces differ,"
                + " which is exactly why a target must be resolved through provenance");
    }

    /**
     * 指示线/角标的锚点必须是<b>元素局部坐标</b>。
     *
     * <p>几何没有无头测试能看见（见 docs/testing.md 手动清单），但坐标空间混用是可以钉住的：
     * {@code localToAscendantCoordinates} 自己会把元素在父表里的 {@code x}/{@code y} 加进去，
     * 锚点里再写一次就是重复计入。2026-09-25 的报告正是这一条 —— {@code anchorY} 写成
     * {@code elem.y + getHeight()/2}，而语句表顶对齐，越靠程序开头的卡片 {@code elem.y} 越大，
     * 于是同一条线两端抬升量不同：写卡片几乎不动，靠前的目标卡片被甩到屏幕外，看起来就是
     * "线飞出积木"。</p>
     */
    private static void anchorsAreLocalCoordinates(){
        System.out.println("== 指示线锚点是局部坐标 ==");
        String source;
        try{
            source = SourceNails.readSource("src/mindustry/logic/CounterJumpOverlay.java");
        }catch(java.io.IOException exception){
            check(false, "CounterJumpOverlay.java could not be read: " + exception.getMessage());
            return;
        }
        String anchorY = SourceNails.methodBody(source, "private static float anchorY(LCanvas.StatementElem elem){");
        // 只看代码：这段方法的注释正是用错误的写法来解释这个陷阱的。
        String anchorCode = withoutComments(anchorY);
        check(!anchorCode.contains("elem.y"),
            "anchorY must be a card-local coordinate: localToAscendantCoordinates adds elem.y itself, "
                + "and counting it twice throws the anchor off the cards (2026-09-25 report)");
        check(anchorCode.contains("getHeight() / 2f"),
            "anchorY must be the card's vertical middle in its own space");
        // 端点必须经过换算那一步，否则缩进与 scroll 都不生效。
        check(source.contains("source.localToAscendantCoordinates(common, from)")
                && source.contains("targetElem.localToAscendantCoordinates(common, to)"),
            "both endpoints must go through localToAscendantCoordinates");
    }

    /**
     * 每帧路径必须用只读快照，且回调不能把异常抛回渲染循环。
     *
     * <p>{@code SugarCanvas.save()} 会跑 {@code structure.refresh()} + {@code ExprHook.unfoldAll}
     * / {@code foldAll}（remove/addAt 积木元素、文本是展开态），而 {@code LCanvas.save()} 逐条
     * {@code saveUI()} 会在 jump 目标已脱离时抛 NPE。指示线的刷新是每帧回调：调 save() 等于每帧
     * 改画布，而任何一次抛出都会让这条回调静默死掉 —— 屏幕上只表现为"指示线整场不画，编辑一下
     * 闪一帧"（2026-09-25 报告）。</p>
     */
    private static void overlayUsesReadonlySnapshot() throws java.io.IOException{
        System.out.println("== 指示线每帧只读快照 ==");
        String overlay = SourceNails.readSource("src/mindustry/logic/CounterJumpOverlay.java");
        String refresh = SourceNails.methodBody(overlay, "private void refresh(){");
        String refreshCode = withoutComments(refresh);
        check(refreshCode.contains("canvas.readonlyText()"),
            "the per-frame refresh must read the read-only snapshot");
        check(!refreshCode.contains("canvas.save()"),
            "the per-frame refresh must never call canvas.save(): it unfolds/folds the canvas and its "
                + "text is the unfolded layout, while elementAt() indexes the folded one");
        check(refreshCode.contains("catch(Throwable"), 
            "the per-frame callback must contain its own failures: an escaping exception kills the "
                + "callback for the rest of the session and the line silently never comes back");

        String canvas = SourceNails.readSource("src/mindustry/logic/SugarCanvas.java");
        String snapshot = withoutComments(SourceNails.methodBody(canvas, "public String readonlyText(){"));
        check(!snapshot.contains("unfoldAll") && !snapshot.contains("foldAll"),
            "readonlyText() must not fold or unfold: it is called every frame and must describe the "
                + "canvas as it is on screen");
        check(snapshot.contains("normalizeJumpUI"),
            "readonlyText() must use the null-safe saveUI path (LCanvas.save() throws on a detached "
                + "jump target)");

        String dialog = SourceNails.readSource("src/mindustry/logic/SugarLogicDialog.java");
        String poll = withoutComments(SourceNails.methodBody(dialog, "private void pollCanvasHistory(){"));
        check(poll.contains("readonlyCanvasText()"),
            "the undo-history poll runs every few frames and must use the read-only snapshot too");
    }

    /**
     * 横向距离必须来自"每线一条轨道"，不能是固定值。
     *
     * <p>原版 {@code JumpCurve} 的 {@code uiHeight} 是
     * {@code Scl.scl(40) + Scl.scl(10) * predHeight}，{@code predHeight} 由
     * {@code StatementsTable.setJumpHeights} 的区间着色算出 —— 这正是原版跳转线不互相穿插的原因。
     * 早期实现把这条轨道算法抄丢了，用固定 {@code bow = 18f}，于是所有 {@code @counter} 指示线
     * 挤在同一条竖线上（2026-09-25 报告）。这里钉住"接了轨道分配"，具体层号由
     * {@code counterJumpIndexTest} 的 {@code jumpLanesSeparateOverlappingCurves} 覆盖。</p>
     */
    private static void overlayRailsFollowLanes() throws java.io.IOException{
        System.out.println("== 指示线轨道分配 ==");
        String overlay = SourceNails.readSource("src/mindustry/logic/CounterJumpOverlay.java");
        check(overlay.contains("JumpLanes.assign("),
            "the overlay must allocate one rail per curve through JumpLanes (the mirror of the "
                + "vanilla setJumpHeights interval colouring)");
        String draw = withoutComments(SourceNails.methodBody(overlay, "void draw(){"));
        check(draw.contains("lane") && draw.contains("rail"),
            "the lateral distance must come from the curve's own lane");
        check(!draw.contains("bow"),
            "a fixed bow would put every line on the same rail and they would cross each other");
        String lanes = SourceNails.readSource("src/logicsugar/assist/JumpLanes.java");
        check(lanes.contains("nextClearBit") && lanes.contains("reprBefore") && lanes.contains("reprAfter"),
            "JumpLanes must stay a faithful mirror of getJumpHeight + the representative merge");

        // 箭头：原版是 draw(t.x + 0.75s, y, -s, s)（负宽度 = 跨在卡片右缘上 + 贴图翻向卡内）。
        // 镜像到左缘必须 x 偏移与宽度一起翻，只翻一个就会整枚漂到卡片外面、而且指向卡外。
        String arrow = draw.substring(draw.indexOf("Tex.logicNode.draw("));
        check(arrow.startsWith("Tex.logicNode.draw(tx - s * 0.75f, ty - s / 2f, s, s)"),
            "the target arrow must be the double mirror of vanilla's "
                + "draw(t.x + 0.75f * s, t.y - s / 2f, -s, s): x offset negative *and* width positive, "
                + "so it straddles the card's left edge pointing into the card; got " + arrow.lines().findFirst().orElse(""));
    }

    // ===== helpers ======================================================================

    private static SugarCompiler.CompileProvenance record(String source, SugarCompiler.FuncMode mode){
        return SugarCompiler.compileRecorded(source, mode, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip, true);
    }

    /** 行号 -> 指令下标：跳过标签行与载体行。 */
    private static int instructionIndexOf(String[] lines, int lineIndex){
        int instruction = 0;
        for(int i = 0; i < lineIndex && i < lines.length; i++){
            String bare = lines[i].trim();
            if(bare.isEmpty()) continue;
            if(bare.length() >= 2 && bare.endsWith(":") && !bare.contains(" ")) continue;
            if(isCarrier(bare)) continue;
            instruction++;
        }
        return instruction;
    }

    private static int indexOfPrefix(String[] lines, String prefix){
        for(int i = 0; i < lines.length; i++){
            if(lines[i].trim().startsWith(prefix)) return i;
        }
        return -1;
    }

    private static boolean isCarrier(String bare){
        String[] tokens = bare.split("\\s+");
        if(tokens.length < 2 || !tokens[0].equals("set")) return false;
        return tokens[1].startsWith("__ls_sugar") || tokens[1].startsWith("__ls_lib");
    }

    private static String firstLine(String source){
        int at = source.indexOf('\n');
        return at < 0 ? source : source.substring(0, at);
    }

    /** 一段源码去掉注释（整行与行尾），只留可执行文本：源码钉子应该钉代码，不该被注释里的
     *  反例写法误伤 —— {@code anchorY} 的注释正是用错误表达式解释它防的是什么。 */
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
