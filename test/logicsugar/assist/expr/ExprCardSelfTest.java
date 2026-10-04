package logicsugar.assist.expr;

import arc.struct.Seq;
import logicsugar.LogicSugarMod;
import mindustry.Vars;
import mindustry.logic.GlobalVars;
import mindustry.logic.LAssembler;
import mindustry.logic.LStatement;
import mindustry.logic.LStatements;
import mindustry.logic.LStatements.SetStatement;
import mindustry.logic.SugarCompiler;
import mindustry.logic.SugarStatements;

import java.util.ArrayList;
import java.util.List;

/**
 * Pinned coverage for the canvas side of an {@link ExprStatement} card: the palette entry
 * ("Expr" in the add-block dialog) must produce a card that survives
 * {@code SugarCanvas.save()} — which every palette insert triggers immediately
 * ({@code addAt → afterMutate → SugarLogicDialog.recordCanvasHistory → canvas.save()}) and
 * which runs {@code ExprHook.unfoldAll} followed by {@code ExprHook.foldAll}.
 *
 * <p>Regression this pins (report: "clicking Expr in the add-block dialog does nothing, no
 * block is added"): the palette default card is {@code result = 0}, which the v5 expression
 * API compiles to a single value-copy line ({@code set result 0},
 * {@link ExprCompiler.CopyLine}). {@code CopyLine extends RawLine}, and
 * {@code ExprHook.statementFor} mapped every non-assert {@code RawLine} to {@code null}
 * ("currently none exist"), while {@code unfoldAllInContext} had already removed the card —
 * so the unfold inserted nothing and the block vanished. The same hole silently degraded every
 * single-line expression card into a plain set/op block, and it removed the card before the
 * user could type into it.</p>
 *
 * <p>What is pinned here (headless: the canvas itself needs UI, so the pure
 * unfold-decision helpers are used):</p>
 * <ul>
 *   <li>every chain line has a canvas statement — value copies included;</li>
 *   <li>the palette default card is recognised as "keep the card", not "downgrade to set/op";</li>
 *   <li>an unknown {@code RawLine} is reported by {@code hasUnmappableLine} so the caller keeps
 *       the card instead of deleting work;</li>
 *   <li>unfolding (or skipping the unfold) never changes the text a card writes into the saved
 *       program — the product and every jump/block index stay identical.</li>
 * </ul>
 */
public class ExprCardSelfTest{
    public static void main(String[] args){
        // 完整注册（含数据子系统的声明卡/操作卡解析器），与游戏内 init 一致
        LogicSugarMod.registerStatements();
        Vars.logicVars = new GlobalVars();

        paletteDefaultCardIsKept();
        everyChainLineHasAStatement();
        unknownRawLineIsReported();
        singleLineCardsCarryTheMarker();
        markerRoundTripsThroughTextImport();
        markerSurvivesTextRewrites();
        carrierRoundTripKeepsTheCard();
        textIsIdenticalWithAndWithoutUnfold();
        counterConstantsAreFolded();
        unfoldedTextMatchesTheUnfoldedCanvas();
        duplicateChainsShareTempsWithoutBlockingEachOther();

        System.out.println("LogicSugar expression card self-test passed.");
    }

    /**
     * 端到端：单行卡 → 保存文本 → 编译 → 载体 → 重开。标记必须只活在载体/注释里，
     * 产物仍是纯原版 mlog，且重开能把那一行还原成同一张卡（这是"保存一次不再丢卡"的证据）。
     */
    private static void carrierRoundTripKeepsTheCard(){
        // 空 dest 之外的常规形状；带一条真实 sugar 语句，确保走载体路径（纯 op 程序按
        // "no sugar" 原样返回，那条路径由 markerRoundTripsThroughTextImport 覆盖）
        String sugar = "stack s cell1 0 4\n"
            + "datacall stack_push pushed \"s, 7\"\n"
            + writeOf("result", "a + b") + "\n";
        for(SugarCompiler.FuncMode mode : SugarCompiler.FuncMode.values()){
            String compiled = SugarCompiler.compile(sugar, mode, null, null);
            String product = SugarCompiler.stripMarkers(compiled);
            check(product.contains("op add result a b"),
                "the flattened card line must stay in the product (" + mode + "):\n" + product);
            check(!product.contains(ExprStatement.cardMarkerPrefix),
                "the marker must not leak into the executable product (" + mode + "):\n" + product);
            check(SugarCompiler.verifyRestore(compiled, sugar),
                "the marked text must still pass the restore gate in " + mode);

            String restored = SugarCompiler.restore(compiled);
            check(restored.contains(ExprStatement.cardMarkerPrefix),
                "the carrier must keep the marker in " + mode + ":\n" + restored);

            ExprTextImport.Plan plan = ExprTextImport.plan(restored);
            Seq<LStatement> statements = LAssembler.read(plan.text(), true);
            int applied = ExprTextImport.applyToStatements(statements, plan);
            check(applied == 1, "reopening restored " + applied + " card(s) in " + mode);
            LStatement last = statements.get(statements.size - 1);
            check(last instanceof ExprStatement reopened && reopened.expr.equals("a + b")
                    && reopened.dest.equals("result"),
                "reopening did not restore the card in " + mode + ": " + last.getClass().getSimpleName());
        }

        // 只有表达式卡的程序（没有其它 sugar 语句）：compile 按既有 "no sugar" 路径原样返回
        // 文本，标记作为注释留在代码里，重开仍由 plan 还原成卡片
        String bare = writeOf("x", "a + b") + "\n";
        String passthrough = SugarCompiler.compile(bare, SugarCompiler.FuncMode.normal, null, null);
        check(passthrough.equals(bare),
            "a program without sugar statements must pass through unchanged:\n" + passthrough);
        ExprTextImport.Plan barePlan = ExprTextImport.plan(passthrough);
        check(!barePlan.isEmpty(), "the marker must survive the pass-through path:\n" + passthrough);
        Seq<LStatement> bareStatements = LAssembler.read(barePlan.text(), true);
        ExprTextImport.applyToStatements(bareStatements, barePlan);
        check(bareStatements.get(0) instanceof ExprStatement bareCard && bareCard.expr.equals("a + b"),
            "the pass-through path did not restore the card");
    }

    /** 用户从调色板拖出的默认卡（result = 0）：必须保留卡片，不能被展开成 set/op 积木。 */
    private static void paletteDefaultCardIsKept(){
        ExprStatement card = new ExprStatement();
        check(card.dest.equals("result") && card.expr.equals("0"),
            "the palette default card changed: " + card.dest + " = " + card.expr);

        List<ExprCompiler.Line> ops = compile(card);
        check(ops.size() == 1 && ops.get(0) instanceof ExprCompiler.CopyLine,
            "the default card must compile to one value-copy line: " + text(ops));
        check(ExprHook.keepsCard(ops),
            "a single-line card must keep the card instead of being unfolded (the block would vanish/downgrade)");
        check(!ExprHook.hasUnmappableLine(ops), "a value copy must not count as an unmappable line");

        List<LStatement> statements = ExprHook.toStatements(ops, null, false);
        check(statements.size() == 1 && statements.get(0) instanceof SetStatement,
            "a value copy must unfold to a set card: " + statements.size() + " statement(s)");
        SetStatement set = (SetStatement)statements.get(0);
        check(set.to.equals("result") && set.from.equals("0"),
            "set card fields lost the copy: " + set.to + " = " + set.from);
    }

    /** 任何一行链都必须有画布语句：映射失败会让展开"删卡不补块"。 */
    private static void everyChainLineHasAStatement(){
        String[] expressions = {
            "0", "a", "a + b", "(a + b) * 2", "cos(a)", "cos(a) * 10 + x",
            "unit.health", "unit.health * 2", "buf[3]", "buf[i + 1] + 1",
            "foo(a)", "foo(a) + 1"
        };
        for(String expression : expressions){
            ExprStatement card = new ExprStatement();
            card.dest = "result";
            card.expr = expression;
            List<ExprCompiler.Line> ops = compile(card);

            check(!ExprHook.hasUnmappableLine(ops),
                "expression '" + expression + "' has instructions without a canvas statement: " + text(ops));

            int instructionLines = 0;
            for(ExprCompiler.Line line : ops){
                if(!(line instanceof ExprCompiler.AssertBoundsLine)) instructionLines++;
            }
            List<LStatement> statements = ExprHook.toStatements(ops, null, false);
            check(statements.size() == instructionLines,
                "expression '" + expression + "' dropped statements while unfolding: expected "
                    + instructionLines + ", got " + statements.size() + " from " + text(ops));
        }
    }

    /** 未知 RawLine 必须上报，调用方据此保留卡片（宁可留卡，不能丢指令）。 */
    private static void unknownRawLineIsReported(){
        List<ExprCompiler.Line> ops = new ArrayList<>();
        ops.add(new ExprCompiler.RawLine("mystery x y"));
        check(ExprHook.hasUnmappableLine(ops), "an unknown raw line was not reported as unmappable");
        check(!ExprHook.keepsCard(ops), "an unknown raw line must not be treated as a kept card");
        check(ExprHook.toStatements(ops, null, false).isEmpty(),
            "an unknown raw line must not produce a half-declared statement list");

        // 断言行按模式转换/丢弃，不算不可映射
        List<ExprCompiler.Line> asserts = new ArrayList<>();
        asserts.add(new ExprCompiler.AssertBoundsLine("integer", "~", "0", "lessThanEq", "buf", "lessThan", "8", "\"ls-auto: x\""));
        check(!ExprHook.hasUnmappableLine(asserts), "bounds asserts must stay mappable");
        check(ExprHook.toStatements(asserts, null, true).size() == 1,
            "an emitted bounds assert must unfold to one assert card");
    }

    /**
     * 单行卡在保存文本里与普通 op/set 积木逐字相同，foldAll 的单行门槛只对数组 read/write
     * 放行，因此单行卡必须自带 {@link ExprStatement#cardMarkerPrefix} 标记（注释行），重开与
     * 撤销才能把它还原成卡片。多行卡与数组 read/write 卡不加标记。
     */
    private static void singleLineCardsCarryTheMarker(){
        String marked = writeOf("result", "0");
        check(marked.equals("set result 0\n" + ExprStatement.cardMarkerPrefix + "result \"0\""),
            "a single-line card must write its flattened line plus a marker:\n" + marked);

        check(writeOf("x", "a + b").endsWith(ExprStatement.cardMarkerPrefix + "x \"a + b\""),
            "an arithmetic single-line card lost its marker:\n" + writeOf("x", "a + b"));
        check(writeOf("x", "cos(a)").endsWith(ExprStatement.cardMarkerPrefix + "x \"cos(a)\""),
            "a math-call single-line card lost its marker:\n" + writeOf("x", "cos(a)"));

        // 多行卡由 foldAll 的 >= 2 门槛折回，不需要（也不能）加标记：那会改变语句条数
        check(!writeOf("x", "(a + b) * 2").contains(ExprStatement.cardMarkerPrefix),
            "a multi-line card must not carry a marker:\n" + writeOf("x", "(a + b) * 2"));
        // 单行 read/write 由 foldAll 的数组门槛折回，同样不加标记
        check(!writeOf("x", "buf[3]").contains(ExprStatement.cardMarkerPrefix),
            "a single-line array read must not carry a marker (foldAll recovers it):\n" + writeOf("x", "buf[3]"));
        // 表达式含引号/空格也必须无损转义：先成功编译一次（建立 lastOps 回退），再改成
        // 无法编译的中间态文本——标记必须原样保住用户输入，重开时卡片照旧标红
        ExprStatement broken = new ExprStatement();
        broken.dest = "x";
        broken.expr = "1";
        writeOf(broken);
        broken.expr = "a \"b\" + 1";
        String brokenText = writeOf(broken);
        check(brokenText.contains(ExprStatement.cardMarkerPrefix),
            "a card falling back to lastOps lost its marker:\n" + brokenText);
        ExprTextImport.Plan brokenPlan = ExprTextImport.plan(brokenText);
        check(!brokenPlan.isEmpty(), "the quoted marker was not recognised");
        Seq<LStatement> brokenStatements = LAssembler.read(brokenPlan.text(), true);
        ExprTextImport.applyToStatements(brokenStatements, brokenPlan);
        check(brokenStatements.get(0) instanceof ExprStatement restored
                && restored.expr.equals("a \"b\" + 1"),
            "a quoted expression did not round-trip through the marker");
    }

    /** 标记 + 展开行必须还原成同一张卡（一对一：语句条数不变，jump 下标不动）。 */
    /**
     * 标记必须在<b>重写文本</b>的路径上活下来：单行卡的展开行与普通积木逐字相同，标记是唯一证据，
     * 而两处重写都按语句重新序列化（丢掉注释）——{@code SugarCompiler.rewriteStaleBlockDests}
     * （载体里 destIndex 过期时）与反编译器推断（编辑器侧由 {@code SugarDecompilerTest} 钉住）。
     *
     * <p>同时钉住标记的两种形态：存档产物里它只以注释标记块里的嵌套形态存在，而
     * {@code plan} 只认紧跟在语句下面的独立标记行，所以由 {@link ExprTextImport#attachCardMarkers}
     * 把它提到位——且只在「文本里确实存在该标记所展开成的那条语句」时采用，重复调用不会补第二份。</p>
     */
    private static void markerSurvivesTextRewrites(){
        String sugar = "stack s cell1 0 4\n"
            + writeOf("x", "0") + "\n"
            + "ifbegin a greaterThan 0 999\n"
            + "print x\n"
            + "blockend\n";
        String compiled = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal);
        check(compiled.contains(ExprStatement.cardMarkerPrefix),
            "the marker must reach the product (inside the comment marker block):\n" + compiled);

        // 载体里的 destIndex 是过期的 999：restore() 会按嵌套重写语句文本，标记必须留在原地
        String restored = SugarCompiler.restore(compiled);
        check(restored.contains("set x 0\n" + ExprStatement.cardMarkerPrefix + "x \"0\""),
            "the stale-dest rewrite dropped the marker:\n" + restored);
        check(SugarCompiler.verifyRestore(compiled, restored), "the restored carrier stopped verifying");

        // 产物里标记嵌在注释标记块里，plan 认不出：先提到语句下面，语句条数与下标都不变
        String hoisted = ExprTextImport.attachCardMarkers(compiled, compiled);
        check(hoisted.contains("set x 0\n" + ExprStatement.cardMarkerPrefix + "x \"0\""),
            "a nested marker was not hoisted next to its statement:\n" + hoisted);
        check(ExprTextImport.attachCardMarkers(hoisted, hoisted).equals(hoisted),
            "hoisting must be idempotent (no second marker below the same statement)");
        check(LAssembler.read(hoisted, true).size == LAssembler.read(compiled, true).size,
            "hoisting must not change the statement count (a marker is a comment)");
        check(!ExprTextImport.plan(hoisted).isEmpty(), "the hoisted marker was not recognised");

        // 过期标记（记录的表达式展开后对不上那条语句）不得被采用
        String mismatched = compiled.replace("# @ls-expr-card x \"0\"", "# @ls-expr-card x \"a + b\"");
        check(!mismatched.equals(compiled), "the fixture no longer contains the marker this test edits");
        String kept = ExprTextImport.attachCardMarkers(mismatched, mismatched);
        check(!kept.contains("set x 0\n" + ExprStatement.cardMarkerPrefix),
            "a stale marker was attached to a statement it does not unfold to:\n" + kept);

        // 没有标记的纯原版文本完全不受影响
        String plain = "set x 5\nop add y a b\n";
        check(ExprTextImport.attachCardMarkers(plain, plain).equals(plain),
            "plain text must not be rewritten by the marker pass");
    }

    private static void markerRoundTripsThroughTextImport(){
        String asm = "set y 1\n" + writeOf("result", "a + b") + "\nprint x\n";
        ExprTextImport.Plan plan = ExprTextImport.plan(asm);
        check(!plan.isEmpty(), "the card marker was not recognised");

        // 展开行被换成哨兵，语句条数不变，标记行作为注释留在原位（它认领的是上一行）
        String expected = "set y 1\nset " + ExprTextImport.sentinelPrefix + "1 0\n"
            + ExprStatement.cardMarkerPrefix + "result \"a + b\"\nprint x\n";
        check(plan.text().equals(expected), "marker rewrite mismatch:\n" + plan.text());

        Seq<LStatement> statements = LAssembler.read(plan.text(), true);
        check(statements.size == 3, "the comment marker must not add a statement: " + statements.size);
        check(ExprTextImport.applyToStatements(statements, plan) == 1, "the sentinel was not swapped for a card");
        LStatement card = statements.get(1);
        check(card instanceof ExprStatement, "index 1 must hold the restored card, got " + card.getClass().getSimpleName());
        check(((ExprStatement)card).dest.equals("result") && ((ExprStatement)card).expr.equals("a + b"),
            "restored card fields lost their value: " + ((ExprStatement)card).dest + " = " + ((ExprStatement)card).expr);

        // 没有标记的普通 set / op 文本完全不受影响（保守边界不变）
        check(ExprTextImport.plan("set x 5\nop add y a b\n").isEmpty(),
            "plain vanilla text must not be rewritten by the marker pass");

        // 空 dest（卡片目标留空）也必须能还原：标记以引号开头，解析不能把它当成 dest
        String emptyDest = writeOf("", "a + b");
        check(emptyDest.contains(ExprStatement.cardMarkerPrefix),
            "an empty destination lost the marker:\n" + emptyDest);
        ExprTextImport.Plan emptyPlan = ExprTextImport.plan(emptyDest);
        Seq<LStatement> emptyStatements = LAssembler.read(emptyPlan.text(), true);
        ExprTextImport.applyToStatements(emptyStatements, emptyPlan);
        check(emptyStatements.get(0) instanceof ExprStatement noDest && noDest.dest.isEmpty()
                && noDest.expr.equals("a + b"),
            "an empty destination did not round-trip through the marker");
    }

    /**
     * 展开与保留卡片写出的文本必须逐字一致（标记行是元数据，不算指令行）：单行卡被跳过展开
     * 时，保存产物与所有 jump / blockend 下标都不能改变（这是"跳过展开"能成立的前提）。
     */
    private static void textIsIdenticalWithAndWithoutUnfold(){
        // write() 用编辑器口径的 functionChecker 校验函数名，这里只用无需用户函数的表达式
        //（funccall 链的映射由 everyChainLineHasAStatement 覆盖）
        String[] expressions = {"0", "a", "a + b", "(a + b) * 2", "cos(a)", "unit.health * 2"};
        for(String expression : expressions){
            ExprStatement card = new ExprStatement();
            card.dest = "result";
            card.expr = expression;

            String written = instructionText(writeOf(card.dest, expression));

            List<LStatement> statements = ExprHook.toStatements(compile(card), null, false);
            check(!statements.isEmpty(), "expression '" + expression + "' unfolded to nothing");

            Seq<LStatement> seq = new Seq<>(statements.size());
            for(LStatement statement : statements) seq.add(statement);
            String unfolded = LAssembler.write(seq);

            check(written.equals(unfolded.trim()),
                "expression '" + expression + "' writes different text when unfolded"
                    + "\n  card:     " + written.replace("\n", " | ")
                    + "\n  unfolded: " + unfolded.trim().replace("\n", " | "));
        }
    }

    /**
     * {@code @counter} 目标的纯常量折叠（2026-09 报告）：{@code @counter = 5*2} 必须只产出一条
     * {@code set @counter 10}。否则产物是 {@code op mul _0 5 2} + {@code set @counter _0}，
     * 跳转目标被藏进一个临时变量。
     *
     * <p>范围刻意收窄：只有 {@code @counter} 目标折叠。普通目标的产物逐字保持原样 ——
     * 折叠它们会改动既有存档的产物，让载体校验失败。</p>
     *
     * <p>注意断言的是"没有 op 行 + 值是字面量"，不是行数：表达式层本来就会把最后一条 op 的
     * 目标改成 dest、把简单值并成一条 {@code set}，所以行数在两种形态间都会变。</p>
     */
    private static void counterConstantsAreFolded(){
        // 折叠：产物里不再有 op，值直接是算出来的字面量
        String folded = text(compile(counterCard("5*2")));
        check(folded.equals("set @counter 10"),
            "@counter = 5*2 must fold to set @counter 10, got: " + folded);

        String nested = text(compile(counterCard("(1 + 2) * (3 + 1)")));
        check(nested.equals("set @counter 12"), "expected set @counter 12, got: " + nested);

        // 不折叠：含变量时 op 行必须留着，目标仍取决于运行期值
        String dynamic = text(compile(counterCard("a + 1")));
        check(dynamic.contains("op") && !dynamic.contains("set "),
            "a non-constant expression must stay a runtime op, got: " + dynamic);

        // 不折叠：非 @counter 目标一律保持原样（产物兼容性硬要求）
        String other = text(compile(newExprCard("result", "5*2")));
        check(other.contains("op mul"), "a non-counter destination must keep its op, got: " + other);
        check(!other.contains("set result 10"), "a non-counter destination must not be folded, got: " + other);

        // 不折叠：算不出有限值（除以零）时回落到运行时计算，而不是折叠成 Infinity
        String zero = text(compile(counterCard("1/0")));
        check(zero.contains("op"), "a non-finite constant must not fold, got: " + zero);
    }

    private static ExprStatement counterCard(String expr){
        return newExprCard("@counter", expr);
    }

    private static ExprStatement newExprCard(String dest, String expr){
        ExprStatement card = new ExprStatement();
        card.dest = dest;
        card.expr = expr;
        return card;
    }

    /** 去掉自描述标记行后的指令文本（标记是注释元数据，不属于可执行程序）。 */
    private static String instructionText(String written){
        StringBuilder out = new StringBuilder();
        for(String line : written.replace("\r\n", "\n").split("\n", -1)){
            if(line.startsWith(ExprStatement.cardMarkerPrefix)) continue;
            if(out.length() > 0) out.append('\n');
            out.append(line);
        }
        return out.toString().trim();
    }

    private static String writeOf(String dest, String expr){
        ExprStatement card = new ExprStatement();
        card.dest = dest;
        card.expr = expr;
        return writeOf(card);
    }

    private static String writeOf(ExprStatement card){
        StringBuilder out = new StringBuilder();
        card.write(out);
        return out.toString();
    }

    /** 与编辑器同口径的函数名校验：{@code foo} 视为用户函数（本地 funcdef / 库函数）。 */
    private static List<ExprCompiler.Line> compile(ExprStatement card){
        ExprCompiler.FunctionChecker checker = name -> "foo".equals(name);
        return ExprCompiler.compile(card.dest, card.expr, checker, false);
    }

    private static String text(List<ExprCompiler.Line> ops){
        StringBuilder out = new StringBuilder();
        for(ExprCompiler.Line line : ops){
            if(out.length() > 0) out.append(" | ");
            out.append(line.toText());
        }
        return out.toString();
    }

    /**
     * {@code save()} 的文本现在是<b>文本层展开</b>（{@link ExprHook#unfoldedText}），不再把
     * 画布 unfold/fold 一遍再重建积木元素——只要有人周期性调 save()（自身的指令预算横幅每 24 帧
     * 一次、共存档里第三方编辑器每帧一次），正在编辑的 Expression 卡就会被拆掉重建，文本框下一帧
     * 因为元素已脱离而失焦（2026-10 报告：“点进编辑区域马上丢焦点、卡片每帧抖动”）。
     *
     * <p>这里钉住两条，缺一不可：</p>
     * <ul>
     *   <li><b>逐字等于旧路径</b>：把画布展开后的语句列表直接 {@code LAssembler.write}，与
     *       文本层展开写出的内容一致（同一个 {@code toStatements} 展开、同一套单行卡保留判定）；</li>
     *   <li><b>下标仍然对得上</b>：多行卡在文本里是 N 条语句，jump / begin 记的是画布语句下标，
     *       必须换算到展开后的下标，否则重新解析时目标整体偏移。</li>
     * </ul>
     */
    private static void unfoldedTextMatchesTheUnfoldedCanvas(){
        // 多行卡 + 一条 op + 一条指向该 op 的 jump（画布下标 1）
        ExprStatement card = newExprCard("x", "ceil(rand(10))");
        LStatements.OperationStatement op = opLine("op add y 1 1");
        LStatements.JumpStatement jump = (LStatements.JumpStatement)LAssembler.read("jump 1 equal y 1", true).first();
        List<LStatement> canvas = new ArrayList<>();
        canvas.add(card);
        canvas.add(op);
        canvas.add(jump);

        String pure = ExprHook.unfoldedText(canvas, false);

        // 1) 与旧路径（画布展开后再写）逐字一致。旧路径里 jump 的 destIndex 会被 saveUI()
        //    写成展开后的下标，这里显式对齐同一个口径。
        List<LStatement> expanded = new ArrayList<>();
        expanded.addAll(ExprHook.toStatements(compile(card), null, false));
        int opIndex = expanded.size();
        expanded.add(op);
        LStatements.JumpStatement expandedJump = (LStatements.JumpStatement)LAssembler.read("jump 0 equal y 1", true).first();
        expandedJump.destIndex = opIndex;
        expanded.add(expandedJump);
        Seq<LStatement> seq = new Seq<>(expanded.size());
        for(LStatement statement : expanded) seq.add(statement);
        check(pure.equals(LAssembler.write(seq)),
            "text-layer unfolding must write exactly what the unfolded canvas wrote"
                + "\n  pure:     " + pure.replace("\n", " | ")
                + "\n  unfolded: " + LAssembler.write(seq).replace("\n", " | "));

        // 2) 重新解析后 jump 要仍然指着那条 op（旧实现把画布也展开了才不会偏移）
        Seq<LStatement> parsed = LAssembler.read(pure, true);
        LStatement last = parsed.peek();
        check(last instanceof LStatements.JumpStatement, "the fixture must end with a jump, got " + last.getClass().getSimpleName());
        int target = ((LStatements.JumpStatement)last).destIndex;
        check(target >= 0 && target < parsed.size, "the jump target did not land inside the parsed program: " + target);
        StringBuilder targetText = new StringBuilder();
        parsed.get(target).write(targetText);
        check(targetText.toString().equals("op add y 1 1"),
            "the parsed jump no longer targets the op block: " + targetText);

        // 3) begin 卡的块尾注释同样换算，而且改完要还原（不能污染画布上的字段）
        SugarStatements.IfBeginStatement begin = new SugarStatements.IfBeginStatement();
        begin.value = "y";
        begin.destIndex = 2;
        List<LStatement> block = new ArrayList<>();
        block.add(card);
        block.add(begin);
        block.add(new SugarStatements.BlockEndStatement());
        String blockText = ExprHook.unfoldedText(block, false);
        check(begin.destIndex == 2, "text-layer unfolding must not leave the canvas index rewritten");
        Seq<LStatement> parsedBlock = LAssembler.read(blockText, true);
        int endIndex = parsedBlock.size - 1;
        check(parsedBlock.get(endIndex - 1) instanceof SugarStatements.IfBeginStatement parsedBegin
                && parsedBegin.destIndex == endIndex,
            "the begin card's block-end index must be rewritten into text-statement space: " + blockText.replace("\n", " | "));

        // 4) 单行卡的标记行是注释、不占语句：其后的 jump 映射不变
        ExprStatement single = newExprCard("z", "a + b");
        LStatements.JumpStatement singleJump = (LStatements.JumpStatement)LAssembler.read("jump 1 equal a b", true).first();
        List<LStatement> markerCanvas = new ArrayList<>();
        markerCanvas.add(single);
        markerCanvas.add(opLine("op mul w 2 2"));
        markerCanvas.add(singleJump);
        String markerText = ExprHook.unfoldedText(markerCanvas, false);
        check(markerText.contains(ExprStatement.cardMarkerPrefix), "a single-line card must keep its marker: " + markerText);
        Seq<LStatement> parsedMarker = LAssembler.read(markerText, true);
        check(parsedMarker.size == 3, "a comment marker must not add a statement: " + parsedMarker.size);
        check(((LStatements.JumpStatement)parsedMarker.peek()).destIndex == 1,
            "the marker line must not shift the jump target: " + markerText.replace("\n", " | "));
    }

    /**
     * 复制粘贴出来的第二份链用同一批临时变量：旧口径“链外文本里出现过”会把两条链互判成
     * 外部读取，两条都折不回来 —— 卡片在保存后永久退化成裸 op 积木
     * （2026-10 报告：“复制 Expr 积木后，马上转为了编译后形态”）。
     * 判定改成“只有真正的读才算”，但真的链外读取仍然必须拦住。
     */
    private static void duplicateChainsShareTempsWithoutBlockingEachOther(){
        ExprStatement card = newExprCard("x", "ceil(rand(10))");
        List<ExprCompiler.Line> chain = compile(card);
        check(chain.size() == 2, "the fixture must be a two-line chain, got " + text(chain));
        String temp = ExprCompiler.lineDest(chain.get(0));
        check(temp != null && ExprCompiler.isTemp(temp), "the first chain line must write a temp, got " + temp);

        List<LStatement> duplicate = new ArrayList<>();
        duplicate.addAll(ExprHook.toStatements(chain, null, false));
        duplicate.addAll(ExprHook.toStatements(chain, null, false));
        check(duplicate.size() == 4, "the duplicate fixture must be two chains of two, got " + duplicate.size());
        check(!ExprHook.hasExternalReads(duplicate, 0, 2, chain),
            "the pasted second chain must not block the first chain's fold (its own first line defines " + temp + ")");
        check(!ExprHook.hasExternalReads(duplicate, 2, 4, chain),
            "and the same in the other direction");

        // 链外语句真的在链的定义之外读它 -> 仍然拦住
        List<LStatement> reader = new ArrayList<>(duplicate.subList(0, 2));
        reader.add(LAssembler.read("op mul z " + temp + " 3", true).first());
        check(ExprHook.hasExternalReads(reader, 0, 2, chain),
            "a statement reading the chain temp must still block the fold: " + temp);

        // 链外先写再读（另一条链的完整形态）不算外部读取
        List<LStatement> redefined = new ArrayList<>(duplicate.subList(0, 2));
        redefined.add(LAssembler.read("set " + temp + " 7", true).first());
        redefined.add(LAssembler.read("op mul z " + temp + " 3", true).first());
        check(!ExprHook.hasExternalReads(redefined, 0, 2, chain),
            "a value that comes from an outside definition is not a read of the folded chain");

        // 已经折回的卡片（画布上是 ExprStatement）不能把后面的链拦住：它的展开行里同样是
        // 那批临时变量，但那是卡片自己的 scratch；扫的必须是卡片源码（expr/dest）
        List<LStatement> withCard = new ArrayList<>();
        withCard.add(newExprCard("y", "ceil(rand(10))"));
        withCard.addAll(duplicate.subList(2, 4));
        check(!ExprHook.hasExternalReads(withCard, 1, 3, chain),
            "a folded card's own expansion must not count as an external read");
        // 但卡片源码里真的读了那个临时变量名时仍然要拦住（用户可能手写 _0）
        List<LStatement> cardReader = new ArrayList<>();
        cardReader.add(newExprCard("y", temp + " + 1"));
        cardReader.addAll(duplicate.subList(2, 4));
        check(ExprHook.hasExternalReads(cardReader, 1, 3, chain),
            "a card whose expression reads the chain temp must still block the fold");
    }

    private static LStatements.OperationStatement opLine(String text){
        return (LStatements.OperationStatement)LAssembler.read(text, true).first();
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
