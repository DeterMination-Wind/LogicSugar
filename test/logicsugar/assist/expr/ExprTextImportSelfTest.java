package logicsugar.assist.expr;

import arc.struct.Seq;
import mindustry.Vars;
import mindustry.logic.GlobalVars;
import mindustry.logic.LAssembler;
import mindustry.logic.LStatement;
import mindustry.logic.LStatements.InvalidStatement;
import mindustry.logic.LStatements.JumpStatement;
import mindustry.logic.LStatements.SetStatement;
import mindustry.logic.SugarCompiler;
import mindustry.logic.SugarFunctions;
import mindustry.logic.SugarStatements;

/**
 * Pinned coverage for {@link ExprTextImport}: the text-import form of one-line expression
 * statements (`x = buf[3]`, `result = (a + b) * 2`, `buf[i] = 5`).
 *
 * <p>Before this, pasting such a line made the vanilla {@code LParser} build an
 * {@link InvalidStatement} (registered as {@code noop}, emitting {@code NoopI}) because
 * statement dispatch only looks at {@code tokens[0]}. The import now rewrites those lines into
 * unique sentinel {@code set} statements, keeps the statement count identical (jump labels /
 * indices untouched) and swaps the sentinels for {@link ExprStatement} cards after the parse -
 * i.e. exactly the state a manually placed Expr card produces.</p>
 *
 * <p>What is pinned here (all headless; the canvas swap itself needs UI and is covered by the
 * same addAt/remove pattern as {@code ExprHook.foldAll}):</p>
 * <ul>
 *   <li>shape detection and the conservative skip list (vanilla tokens, comparisons, comments,
 *       strings, one-line multi-statements, reserved sentinel names);</li>
 *   <li>statement-count / jump-index preservation across the sentinel rewrite;</li>
 *   <li>the issue-#12 case lowers to the documented {@code read x cell1 3} and carries no
 *       {@code noop}; carrier round trip stays byte-identical and passes {@code verifyRestore};</li>
 *   <li>{@code buf[i] = 5} and plain expressions ({@code x = (a + b) * 2});</li>
 *   <li>a malformed expression stays a card and fails loudly instead of silently emitting mlog.</li>
 * </ul>
 */
public class ExprTextImportSelfTest{
    public static void main(String[] args){
        SugarStatements.installParsers();
        Vars.logicVars = new GlobalVars();

        detectsAssignments();
        conservativeSkips();
        statementIndicesPreserved();
        arraySubscriptImport();
        indexedWriteImport();
        plainExpressionImport();
        invalidExpressionStaysCard();
        vanillaTextPassesThrough();

        System.out.println("LogicSugar expression text-import self-test passed.");
    }

    /** 形状识别：赋值行 → 哨兵，dest / expr 原样保留（含无空格、下标、成员形式）。 */
    private static void detectsAssignments(){
        ExprTextImport.Plan plan = ExprTextImport.plan("array buf cell1 0 8\nx = buf[3]");
        check(!plan.isEmpty(), "assignment line was not detected");
        check(plan.text().equals("array buf cell1 0 8\nset " + ExprTextImport.sentinelPrefix + "1 0"),
            "sentinel rewrite mismatch:\n" + plan.text());

        checkSingle("x=5", "x", "5");
        checkSingle("buf[i] = 5", "buf[i]", "5");
        checkSingle("m[i][j] = v", "m[i][j]", "v");
        checkSingle("p.hp = 3", "p.hp", "3");
        checkSingle("x = (a + b) * 2", "x", "(a + b) * 2");
        checkSingle("x =-5", "x", "-5");
        checkSingle("buf[i + 1] = 5", "buf[i + 1]", "5");

        // 尾部 `;` 仍是一条语句；多个赋值行各拿一个独立哨兵。
        check(!ExprTextImport.plan("x = 5;").isEmpty(), "trailing semicolon should still import");
        ExprTextImport.Plan two = ExprTextImport.plan("x = 1\ny = x + 1");
        check(two.text().equals("set " + ExprTextImport.sentinelPrefix + "1 0\nset "
            + ExprTextImport.sentinelPrefix + "2 0"), "two-sentinel rewrite mismatch:\n" + two.text());
        Seq<LStatement> statements = LAssembler.read(two.text(), true);
        check(ExprTextImport.applyToStatements(statements, two) == 2, "sentinels were not replaced");
        check(exprAt(statements, 0).dest.equals("x") && exprAt(statements, 1).dest.equals("y"),
            "sentinel order drifted: " + exprAt(statements, 0).dest + " / " + exprAt(statements, 1).dest);
    }

    /** 保守边界：任何可能改变既有行为的文本都必须原样通过。 */
    private static void conservativeSkips(){
        String[] untouched = {
            "",
            "array buf cell1 0 8\nread x cell1 3",
            "read x cell1 3\nop add y x 1",
            "set x 5",
            "noop",
            "# x = 5",
            "print \"a = b\"",
            "print \"hello x = 5\"",
            "x == 5",
            "x != 5",
            "x <= 5",
            "x >= 5",
            "a = 1; b = 2",
            "set x 1 # y = 2",
            "array = 5",
            "print = 5",
            "set __ls_import_1 0\nx = 5",
        };
        for(String text : untouched){
            ExprTextImport.Plan plan = ExprTextImport.plan(text);
            check(plan.isEmpty() && plan.text().equals(text),
                "text should pass through untouched: " + text.replace("\n", "\\n")
                    + " -> " + plan.text().replace("\n", "\\n"));
        }
    }

    /** 哨兵一对一替换：语句条数不变，因此标签解析 / jump destIndex 不变。 */
    private static void statementIndicesPreserved(){
        String text = "start:\nx = 1\njump start\n";
        ExprTextImport.Plan plan = ExprTextImport.plan(text);
        check(!plan.isEmpty(), "assignment after a label was not detected");

        Seq<LStatement> before = LAssembler.read(text, true);
        Seq<LStatement> after = LAssembler.read(plan.text(), true);
        check(before.size == after.size,
            "sentinel rewrite changed the statement count: " + before.size + " -> " + after.size);
        check(before.get(0) instanceof InvalidStatement, "original assignment line was not a noop");
        check(after.get(0) instanceof SetStatement, "sentinel not in place");
        JumpStatement beforeJump = (JumpStatement)before.get(before.size - 1);
        JumpStatement afterJump = (JumpStatement)after.get(after.size - 1);
        check(beforeJump.destIndex == 0 && afterJump.destIndex == 0,
            "jump label index changed: " + beforeJump.destIndex + " -> " + afterJump.destIndex);
    }

    /** issue #12 原始复现：`array buf cell1 0 8` + `x = buf[3]` 必须落成 read，且不再有 noop。 */
    private static void arraySubscriptImport(){
        String text = "array buf cell1 0 8\nx = buf[3]";
        Seq<LStatement> statements = imported(text);
        ExprStatement card = exprAt(statements, 1);
        check(card.dest.equals("x") && card.expr.equals("buf[3]"),
            "card fields drifted: " + card.dest + " / " + card.expr);

        String lowered = unfold(statements);
        check(lowered.equals("array buf cell1 0 8\nread x cell1 3"),
            "text import did not lower to the documented mlog:\n" + lowered);

        String compiled = compile(lowered);
        String mlog = SugarCompiler.stripMarkers(compiled);
        check(mlog.contains("read x cell1 3"), "compiled product lost the read:\n" + mlog);
        check(!mlog.contains("noop"), "imported program still compiles to noop:\n" + mlog);

        // 载体重建（AGENTS.md reconstruction gate）：载体保存降级后的源码，重编译逐行一致。
        String restored = SugarCompiler.restore(compiled);
        check(restored.equals(lowered), "carrier did not preserve the lowered source:\n" + restored);
        check(SugarCompiler.verifyRestore(compiled, restored), "verifyRestore rejected the import product");
        check(SugarCompiler.matchesStoredStream(compile(restored), compiled),
            "recompiled import did not match the stored stream");

        // 重开时 read x cell1 3 要能被 foldAll 折回 buf[3]：目标内存必须命中数组注册表。
        ArrayRegistry registry = ArrayRegistry.compileRegistry(LAssembler.read(restored, true), null);
        check(!registry.isEmpty() && !registry.byMemory("cell1").isEmpty(),
            "reopened read cannot fold back to x = buf[3]");
    }

    /** 下标写：`buf[i] = 5` 落成 op add + write，与手写等价表达式一致。 */
    private static void indexedWriteImport(){
        Seq<LStatement> statements = imported("array buf cell1 10 8\nbuf[i] = 5");
        ExprStatement card = exprAt(statements, 1);
        check(card.dest.equals("buf[i]") && card.expr.equals("5"),
            "subscript card fields drifted: " + card.dest + " / " + card.expr);

        String lowered = unfold(statements);
        check(lowered.equals("array buf cell1 10 8\nop add _0 10 i\nwrite 5 cell1 _0"),
            "subscript write lowering mismatch:\n" + lowered);
        String mlog = SugarCompiler.stripMarkers(compile(lowered));
        check(mlog.contains("write 5 cell1 _0"), "subscript write lost in product:\n" + mlog);
    }

    /** README 宣传的一行表达式语句：`result = (a + b) * 2`。 */
    private static void plainExpressionImport(){
        Seq<LStatement> statements = imported("x = (a + b) * 2");
        String lowered = unfold(statements);
        check(lowered.equals(textOf(ExprCompiler.compile("x", "(a + b) * 2"))),
            "plain expression lowering mismatch:\n" + lowered);

        String compiled = compile(lowered);
        String restored = SugarCompiler.restore(compiled);
        check(restored.equals(lowered), "carrier did not preserve the plain expression:\n" + restored);
        check(SugarCompiler.verifyRestore(compiled, lowered), "verifyRestore rejected the plain expression import");
    }

    /** 非法表达式不再静默：卡片仍在（会标红），展开/保存明确失败。 */
    private static void invalidExpressionStaysCard(){
        Seq<LStatement> statements = imported("array buf cell1 0 8\nx = (a +");
        exprAt(statements, 1);
        checkThrows(() -> unfold(statements),
            "uncompilable import expression must not silently produce mlog");
    }

    /** 纯原版 / 已注册 token 的文本零差异；混合文本只动赋值行。 */
    private static void vanillaTextPassesThrough(){
        String text = "array buf cell1 0 8\nread x cell1 3\nop add y x 1";
        ExprTextImport.Plan plan = ExprTextImport.plan(text);
        check(plan.isEmpty() && plan.text().equals(text),
            "sugar-token program must pass through untouched:\n" + plan.text());

        ExprTextImport.Plan mixed = ExprTextImport.plan("read x cell1 3\nx = buf[3]");
        check(mixed.text().equals("read x cell1 3\nset " + ExprTextImport.sentinelPrefix + "1 0"),
            "mixed text only rewrote the wrong line:\n" + mixed.text());
    }

    // ===== helpers =====

    /** 文本导入的下半段：plan → LAssembler.read → 哨兵换卡（SugarCanvas.load 的同序子集）。 */
    private static Seq<LStatement> imported(String text){
        ExprTextImport.Plan plan = ExprTextImport.plan(text);
        check(!plan.isEmpty(), "no expression statement detected in: " + text.replace("\n", "\\n"));
        Seq<LStatement> statements = LAssembler.read(plan.text(), true);
        check(ExprTextImport.applyToStatements(statements, plan) > 0,
            "sentinels were not replaced in: " + text.replace("\n", "\\n"));
        return statements;
    }

    private static void checkSingle(String text, String dest, String expr){
        ExprStatement card = exprAt(imported(text), 0);
        check(card.dest.equals(dest) && card.expr.equals(expr),
            "assignment fields drifted for `" + text + "`: " + card.dest + " / " + card.expr);
    }

    /**
     * 无头版 {@code ExprHook.unfoldAll}：进入语句自身的数组注册表后，按
     * {@link ExprStatement#write} 铺开成 mlog（画布版用 canvasRegistry，语句列表版用
     * compileRegistry，口径一致）。
     */
    private static String unfold(Seq<LStatement> statements){
        ArrayRegistry previous = ArrayRegistry.enter(ArrayRegistry.compileRegistry(statements, null));
        try{
            StringBuilder out = new StringBuilder();
            for(int i = 0; i < statements.size; i++){
                if(i > 0) out.append('\n');
                statements.get(i).write(out);
            }
            return out.toString();
        }finally{
            ArrayRegistry.restore(previous);
        }
    }

    private static String compile(String sugar){
        return SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
    }

    private static String textOf(java.util.List<ExprCompiler.Line> ops){
        StringBuilder out = new StringBuilder();
        for(int i = 0; i < ops.size(); i++){
            if(i > 0) out.append('\n');
            out.append(ops.get(i).toText());
        }
        return out.toString();
    }

    private static ExprStatement exprAt(Seq<LStatement> statements, int index){
        check(index < statements.size && statements.get(index) instanceof ExprStatement,
            "expected an ExprStatement at index " + index + ", got "
                + (index < statements.size ? statements.get(index).getClass().getSimpleName() : "<missing>"));
        return (ExprStatement)statements.get(index);
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new RuntimeException("ExprTextImport self-test failed: " + message);
    }

    private static void checkThrows(Runnable body, String message){
        try{
            body.run();
        }catch(Throwable ignored){
            return;
        }
        throw new RuntimeException("ExprTextImport self-test failed (no throw): " + message);
    }
}
