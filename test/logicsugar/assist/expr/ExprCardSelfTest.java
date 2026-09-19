package logicsugar.assist.expr;

import arc.struct.Seq;
import logicsugar.LogicSugarMod;
import mindustry.Vars;
import mindustry.logic.GlobalVars;
import mindustry.logic.LAssembler;
import mindustry.logic.LStatement;
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
        carrierRoundTripKeepsTheCard();
        textIsIdenticalWithAndWithoutUnfold();

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

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
