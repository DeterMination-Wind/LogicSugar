package mindustry.logic;

import arc.struct.Seq;
import logicsugar.LogicSugarMod;
import logicsugar.assist.expr.ArrayRegistry;
import logicsugar.assist.expr.ExprCompiler;
import logicsugar.assist.expr.ExprFoldHarness;
import logicsugar.assist.expr.ExprHook;
import logicsugar.assist.expr.ExprStatement;
import logicsugar.assist.expr.ExprTextImport;
import logicsugar.assist.expr.SpanAccess;
import mindustry.Vars;
import mindustry.gen.Building;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Multi-cell span. Headless (no link resolver, or a member that resolves nowhere) falls back to the
 * same name heuristic as the array side: {@code cellN} = 64, {@code bankN}/{@code worldN} = 512.
 * A linked build always uses {@code memoryCapacity} from the resolver; a world-cell that happens to
 * be named {@code cellN} is not treated as 64.
 *
 * <p>Also pins the reconstruction half (2026-09 review): an Expr card over a span must fold back
 * (the whole reason the v5 saved text carries the {@code idiv}/{@code select} expansion).</p>
 *
 * <p>Variable addressing is {@code N+3} instructions (5 when N=2). {@code idiv} is
 * {@code Math.floor}, so a negative address yields {@code q <= -1} and matches none of
 * the {@code equal q k} selects. The chain starts on numeric {@code 0}, which is the
 * null building for both {@code addr < 0} (including -64) and {@code addr >= N*C}.
 */
public final class SpanTest{
    private SpanTest(){}

    /** Two cellN members, written as an expression sum. Headless capacity is 64 each. */
    private static final String SPAN = "span big \"cell1 + cell2\"\n";

    public static void main(String[] args){
        LogicSugarMod.registerStatements();
        Vars.logicVars = new GlobalVars();

        List<String> constant0 = instructions(SPAN + "read x big 0\n");
        List<String> constant64 = instructions(SPAN + "read x big 64\n");
        List<String> variable = instructions(SPAN + "read x big i\n");
        List<String> variableWrite = instructions(SPAN + "write v big i\n");

        check(constant0.equals(List.of("read x cell1 0")), "constant index 0\n" + constant0);
        check(constant64.equals(List.of("read x cell2 0")), "constant index 64\n" + constant64);
        check(variable.size() == 5, "N=2 variable read is " + variable.size() + " instructions, expected 5\n" + variable);
        check(variableWrite.size() == 5, "N=2 variable write is " + variableWrite.size() + " instructions\n" + variableWrite);
        check(variable.equals(List.of(
            "op idiv __ls_span_q i 64",
            "op mod __ls_span_r i 64",
            "select __ls_span_b equal __ls_span_q 0 cell1 0",
            "select __ls_span_b equal __ls_span_q 1 cell2 __ls_span_b",
            "read x __ls_span_b __ls_span_r"
        )), "variable read shape\n" + variable);
        check(variableWrite.get(4).equals("write v __ls_span_b __ls_span_r"), "variable write payload\n" + variableWrite);
        for(String line : variable){
            check(!line.contains("span ") && !line.contains("funcdef") && !line.contains("funccall")
                && !line.startsWith("array ") && !line.startsWith("datacall"),
                "sugar token in variable product: " + line);
        }

        check(instructions(SPAN + "read x big -1\n").equals(List.of("read x 0 0")), "negative constant must be the null building");
        check(instructions(SPAN + "read x big -64\n").equals(List.of("read x 0 0")), "negative multiple of C must not read cell2");
        check(instructions(SPAN + "read x big 128\n").equals(List.of("read x 0 0")), "address N*C is out of range");
        check(instructions(SPAN + "read y cell1 3\n").equals(List.of("read y cell1 3")), "a real cell1 link must stay one read");

        String arraySugar = SPAN + "array buf big 0 128\nset x 1\n";
        String restored = SugarCompiler.restore(compile(arraySugar));
        check(restored.contains("span big") && restored.contains("cell1 + cell2"),
            "carrier dropped the span expression:\n" + restored);
        check(restored.contains("array buf big 0 128"), "carrier dropped the array:\n" + restored);
        ArrayRegistry registry = ArrayRegistry.compileRegistry(LAssembler.read(restored, true), Collections.emptySet());
        check(registry.span("big").logicalCapacity == 128, "span logical capacity");
        check(registry.span("big").cellCapacity == 64, "headless cellN capacity is 64");
        check(registry.span("cell1") == null, "cell1 must not itself be a span");
        ArrayRegistry previous = ArrayRegistry.enter(registry);
        try{
            check(ArrayRegistry.capacityOf("big") == 128, "capacityOf(span) during an entered registry");
            check(ArrayRegistry.capacityOf("cell1") == 64, "capacityOf(cell1) stays the cell, not N*C");
            check(textOf(ExprCompiler.compile("x", "buf[0]")).equals("read x cell1 0"), "array index 0");
            check(textOf(ExprCompiler.compile("x", "buf[64]")).equals("read x cell2 0"), "array index 64");
            List<String> indexed = linesOf(ExprCompiler.compile("x", "buf[i]"));
            check(indexed.size() == 5, "array variable index instructions: " + indexed.size() + "\n" + indexed);
            check(indexed.get(4).equals("read x __ls_span_b __ls_span_r"), "array variable index read\n" + indexed);
        }finally{
            ArrayRegistry.restore(previous);
        }
        expectFail(SPAN + "array buf big 0 129\nset x 1\n", "past N*C");

        List<String> filled = instructions(SPAN + "array buf big 64 4\narrayinit buf 7 ~ ~ ~ ~ ~ ~ ~\n");
        check(filled.equals(List.of("write 7 cell2 0")), "arrayinit past the first cell\n" + filled);

        expectFail("span big \"cell1\"\nset x 1\n", "one cell");
        expectFail("span big \"cell1 + + cell2\"\nset x 1\n", "empty term");
        expectFail("span big \"cell1 + 5\"\nset x 1\n", "not a name");
        // 无链接上下文时回落与数组侧同一口径的名字启发式（cellN=64 / bankN|worldN=512）。
        // 2026-09 复核前这里只接受 cellN：bank span 在共享/换图后编译失败，而编译失败会让
        // 编辑器回落 vanilla 视图，用户下次保存就把只存在于载体里的 span/array 卡丢掉了。
        check(instructions("span b \"bank1 + bank2\"\nset x 1\n").equals(List.of("set x 1")),
            "headless bank span must compile with the inferred 512 slots");
        check(ArrayRegistry.compileRegistry(LAssembler.read("span b \"bank1 + bank2\"\nset x 1\n", true),
            Collections.emptySet()).span("b").logicalCapacity == 1024, "bank span logical capacity");
        check(ArrayRegistry.memoryCapacity("world3") == 512, "worldN inference");
        expectFail("span b \"cell1 + bank1\"\nset x 1\n", "mixed inferred capacities");
        expectFail("span b \"mem1 + mem2\"\nset x 1\n", "unknown link names");

        expressionCardFoldRoundTrip();
        variableReadFoldsOnReopen();
        foldChainWiring();
        spanBuiltinsStayConsistent();
        try{
            compile(SPAN + "array buf big 0 8\ndatacall array_sum r \"buf\"\n");
            check(false, "array_sum on a span should fail");
        }catch(RuntimeException e){
            String message = String.valueOf(e.getMessage());
            check(message.contains("first cell") || message.contains("span"),
                "array_sum error should name the span, got: " + message);
        }

        String inferred = SugarDecompiler.openingSource(executable(compile(SPAN + "read x big i\n")), false, false).source;
        check(inferred == null || !inferred.contains("span "), "idiv/select was inferred back into a span card:\n" + inferred);

        withLinks(Links.of("cell1", 512, "cell2", 512), () -> {
            ArrayRegistry linked = ArrayRegistry.compileRegistry(LAssembler.read(SPAN + "set x 1\n", true), Collections.emptySet());
            check(linked.span("big").cellCapacity == 512, "linked cellN must use memoryCapacity 512, not the name guess 64");
            check(linked.span("big").logicalCapacity == 1024, "linked logical capacity");
            compile(SPAN + "array buf big 0 200\nset x 1\n");
        });
        withLinks(Links.of("cell1", 64, "cell2", 512), () -> expectFail(SPAN + "set x 1\n", "mixed"));
        withLinks(Links.privileged("cell1", "cell2"), () -> expectFail(SPAN + "set x 1\n", "privileged"));

        String condition = executable(compile(SPAN + "array buf big 0 128\nifbegin expr \"buf[i] > 0\" 3\nset x 1\nblockend\n"));
        check(condition.contains("op idiv __ls_span_q i 64"), "condition read did not divide the address\n" + condition);
        check(condition.contains("select __ls_span_b equal __ls_span_q 0 cell1 0"),
            "condition read did not null the failed cell\n" + condition);
        check(!condition.contains("lessThan i 0"), "condition read still clamps before idiv\n" + condition);

        List<String> ordered = instructions("span _cell1 \"cell1 + cell3 + cell2\"\nread x _cell1 64\n");
        check(ordered.equals(List.of("read x cell3 0")),
            "the second term is address C, got\n" + ordered);

        List<String> three = instructions("span wide \"cell1 + cell2 + cell3\"\nread x wide i\n");
        check(three.size() == 6, "N=3 variable read is " + three.size() + " instructions, expected N+3\n" + three);
        check(three.get(2).equals("select __ls_span_b equal __ls_span_q 0 cell1 0"), "N=3 null fallthrough\n" + three);
        check(three.get(4).equals("select __ls_span_b equal __ls_span_q 2 cell3 __ls_span_b"), "N=3 last cell\n" + three);

        System.out.println("spanTest passed; N=2 variable instructions=" + variable.size()
            + "; constant index 0 instructions=" + constant0.size()
            + "; constant index 64 instructions=" + constant64.size());
    }

    private static String compile(String sugar){
        return SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
    }

    /**
     * 表达式卡重建（2026-09 复核）：span 上的数组/矩阵下标必须能折回，否则用户写的
     * {@code x = buf[i]} 保存一次就永久退化成前导段 + 一条 read 积木（载体里存的就是展开后的
     * 文本）。无头环境搭不出 LCanvas，所以用 {@code ExprHook.foldAll} 实际调用的同一对函数
     * （compile / rebuild + 安全门 {@code verifyArrayFold}）钉住——与 arrayTest 的折层口径一致。
     */
    private static void expressionCardFoldRoundTrip(){
        withSpanRegistry(SPAN + "array buf big 0 128\n", () -> {
            // 变量地址：前导段 + read 折回 buf[i]
            List<ExprCompiler.Line> variable = ExprCompiler.compile("x", "buf[i]", name -> false, false);
            check(variable.size() == 5, "span expression chain shape: " + textOf(variable));
            check("x = buf[i]".equals(spanFoldBack(textOf(variable))),
                "variable span read did not fold: " + spanFoldBack(textOf(variable)));
            check(ExprCompiler.verifyArrayFold(variable, "x", "buf[i]", name -> false),
                "variable span fold must pass the recompile gate");

            // 常量地址：编译器把下标折成指向选中成员的一条 read
            List<ExprCompiler.Line> constant = ExprCompiler.compile("x", "buf[64]", name -> false, false);
            check(textOf(constant).equals("read x cell2 0"), "constant span read shape: " + textOf(constant));
            // 反解层仍能把这条 read 解释成 buf[64]（verifyArrayFold 通过），但折叠层不再让孤立的
            // read 行折回——声明只说明 cell 归属，不能说明这一行是下标访问。卡片的身份由它自己
            // 写下的标记给出，重开时由文本导入直接还原。
            check("x = buf[64]".equals(spanFoldBack(textOf(constant))),
                "constant span read did not rebuild: " + spanFoldBack(textOf(constant)));
            check(ExprCompiler.verifyArrayFold(constant, "x", "buf[64]", name -> false),
                "constant span fold must pass the recompile gate");
            ExprStatement constantCard = new ExprStatement();
            constantCard.dest = "x";
            constantCard.expr = "buf[64]";
            StringBuilder constantText = new StringBuilder();
            constantCard.write(constantText);
            check(constantText.toString().equals("read x cell2 0\n"
                    + ExprStatement.cardMarkerPrefix + "x \"buf[64]\""),
                "a single-line span read card must write its marker:\n" + constantText);
            ExprTextImport.Plan constantPlan = ExprTextImport.plan(constantText.toString());
            check(!constantPlan.isEmpty(), "the span card marker was not recognised");
            Seq<LStatement> constantStatements = LAssembler.read(constantPlan.text(), true);
            ExprTextImport.applyToStatements(constantStatements, constantPlan);
            check(constantStatements.get(0) instanceof ExprStatement spanCard
                    && spanCard.dest.equals("x") && spanCard.expr.equals("buf[64]"),
                "the constant span read card did not round-trip through the marker");

            // 赋值链：前导段 + write 折回 buf[i] = 7
            List<ExprCompiler.Line> assignment = ExprCompiler.compile("buf[i]", "7", name -> false, false);
            check("buf[i] = 7".equals(spanFoldBack(textOf(assignment))), "span assignment did not fold");
            check(ExprCompiler.verifyArrayFold(assignment, "buf[i]", "7", name -> false),
                "span assignment must pass the recompile gate");
            // 常量赋值：反解层同样能把这条单行 write 解释成 buf[64] = 7（折叠层不再让孤立的
            // write 行折回，卡片靠自描述标记还原）
            check("buf[64] = 7".equals(spanFoldBack(textOf(ExprCompiler.compile("buf[64]", "7", name -> false, false)))),
                "constant span assignment did not fold");

            // 含临时量的复合表达式：read 的临时量靠同一张 folds 表替换掉
            check("x = buf[i]+1".equals(spanFoldBack(textOf(ExprCompiler.compile("x", "buf[i] + 1", name -> false, false)))),
                "span read inside a larger expression did not fold");
            check("x = buf[i]*2".equals(spanFoldBack(textOf(ExprCompiler.compile("x", "buf[i] * 2", name -> false, false)))),
                "span read with a tail op did not fold");
            // 一张卡里两个 span（读+写）：两条链都要按 span 视角归属，再走 rebuildAssignment
            check("buf[i] = buf[j]+1".equals(spanFoldBack(
                    textOf(ExprCompiler.compile("buf[i]", "buf[j] + 1", name -> false, false)))),
                "a card with two span accesses did not fold");
        });
        // 矩阵走同一条反解（逻辑地址 → m[i][j]）
        withSpanRegistry(SPAN + "matrix m big 0 2 2\n", () -> {
            check("x = m[i][j]".equals(spanFoldBack(textOf(ExprCompiler.compile("x", "m[i][j]", name -> false, false)))),
                "span matrix fold");
            check("x = m[0][1]".equals(spanFoldBack(textOf(ExprCompiler.compile("x", "m[0][1]", name -> false, false)))),
                "constant span matrix fold");
        });
        // 成员上没有数组/矩阵时保持原样（纯 read 卡不受影响）
        withSpanRegistry(SPAN, () -> {
            check(spanFoldBack("read x cell1 3") == null, "a member read without an array over the span must not fold");
            check(spanFoldBack("read x cell1 i") == null,
                "a variable local address must not fold: buf[i] could reach into another cell");
        });
        // 逻辑地址越出所有数组区间时不折（折回去会让下次编译报错）
        withSpanRegistry(SPAN + "array buf big 0 8\n", () -> {
            check(spanFoldBack("read x cell2 0") == null, "an out-of-range logical address must not fold");
        });
        // 手写的假前导段（成员序列对不上任何 span 形状）不折
        withSpanRegistry(SPAN + "array buf big 0 128\n", () -> {
            String lookalike = "op idiv __ls_span_q i 64\nop mod __ls_span_r i 64\n"
                + "select __ls_span_b equal __ls_span_q 0 cell9 0\n"
                + "select __ls_span_b equal __ls_span_q 1 cell1 __ls_span_b\n"
                + "read x __ls_span_b __ls_span_r";
            check(spanFoldBack(lookalike) == null, "a hand-written idiv/select lookalike must not fold");
        });
    }

    /**
     * 变量下标重开（2026-10 复核）：上一条只把展开文本交给 {@code ExprCompiler.rebuild}，
     * 看不见链在哪里断——而真实重开时先过链收集。span 变量寻址的两端都会被旧口径挡在链外：
     * 前导段以固定 scratch 名（{@code __ls_span_q/r}，不是临时变量形态）收尾，read 又落在
     * building scratch 上（不是 span 成员块，{@code isArrayMemory} 认不出来），于是链在
     * {@code idiv} 那一行就断掉（它的目标不是 {@code _n} 临时变量），read 永远留在链外，
     * 载体里的展开文本重开后折不回卡片（只有常量下标那种单行 read 能折）。
     * 这里用生产折叠的那一份判定（{@code ExprFoldHarness}）跑「声明卡 + 展开文本」的重开。
     */
    private static void variableReadFoldsOnReopen(){
        withSpanRegistry(SPAN + "array buf big 0 128\n", () -> {
            check("x = buf[i]".equals(foldBody("x", "buf[i]")), "variable span read must fold on reopen");
            check("x = buf[i]+1".equals(foldBody("x", "buf[i] + 1")),
                "a span read inside a larger expression must fold on reopen");
            check("buf[i] = 7".equals(foldBody("buf[i]", "7")), "span assignment must fold on reopen");
            // 常量下标是单行：折叠层不再让孤立的 read 行折回，卡片靠自描述标记还原；
            // ≤5.7.1 存档没有标记，这一行会显示成原版 read 积木（产物不变）
            check("read x cell2 0".equals(foldBody("x", "buf[64]")),
                "a constant span read must stay a vanilla read line on reopen: " + foldBody("x", "buf[64]"));
            for(LStatement statement : LAssembler.read(textOf(ExprCompiler.compile("x", "buf[i]", name -> false, false)), true)){
                if(statement instanceof LStatements.ReadStatement read){
                    check(ExprHook.foldsMemoryLine(read),
                        "a span scratch read must be a fold chain line: " + read.target + " " + read.address);
                }
            }
        });
        withSpanRegistry(SPAN + "matrix m big 0 2 2\n", () -> {
            check("x = m[i][j]".equals(foldBody("x", "m[i][j]")), "span matrix read must fold on reopen");
        });
        // 没有 span 声明时 scratch 名不是任何结构的内存块：链不入、也不会被误折
        ArrayRegistry emptyRegistry = ArrayRegistry.compileRegistry(
            LAssembler.read("set x 1\n", true), Collections.emptySet());
        ArrayRegistry previousEmpty = ArrayRegistry.enter(emptyRegistry);
        try{
            check(!ExprHook.foldsMemoryLine((LStatements.ReadStatement)LAssembler.read(
                    "read x __ls_span_b __ls_span_r", true).first()),
                "without a span declaration the scratch read must stay out of the fold chain");
            String prologue = "op idiv __ls_span_q i 64\nop mod __ls_span_r i 64\n"
                + "select __ls_span_b equal __ls_span_q 0 cell1 0\n"
                + "select __ls_span_b equal __ls_span_q 1 cell2 __ls_span_b\n"
                + "read x __ls_span_b __ls_span_r";
            check(ExprFoldHarness.bodyOf(prologue).equals(prologue),
                "a span prologue without a span declaration must stay vanilla:\n" + ExprFoldHarness.bodyOf(prologue));
        }finally{
            ArrayRegistry.restore(previousEmpty);
        }
    }

    /** 「声明卡 + 一张表达式卡的展开文本」重开后的正文（声明卡不计）。 */
    private static String foldBody(String dest, String expr){
        return ExprFoldHarness.bodyOf(SPAN + "array buf big 0 128\n"
            + textOf(ExprCompiler.compile(dest, expr, name -> false, false)));
    }

    /**
     * 折叠接线（两边必须同时成立，否则“保存一次就丢卡”）：
     * {@code foldAll} 要把前导段 select 收进链（{@link ExprHook#foldsSpanPrologue}），
     * 而 {@code unfoldAll} 仍要认为它没有对应的原版积木，从而保留表达式卡；链外读取检查
     * 要忽略固定 scratch，否则画布上两张相邻的 span 表达式卡会互相判成外部读取。
     */
    private static void foldChainWiring(){
        List<ExprCompiler.Line> ops = inSpanRegistry(SPAN + "array buf big 0 128\n",
            () -> ExprCompiler.compile("x", "buf[i]", name -> false, false));
        check(ExprHook.hasUnmappableLine(ops), "span prologue lines must keep the Expr card on unfold");
        check(!ExprHook.keepsCard(ops), "a span chain is multi-line and must not be treated as a single-line card");
        check(SpanAccess.scratchNames().contains(SpanAccess.BUILDING), "scratch names must include the building slot");

        Seq<LStatement> prologue = LAssembler.read("select __ls_span_b equal __ls_span_q 0 cell1 0\n", true);
        check(ExprHook.foldsSpanPrologue(prologue.get(0)), "span prologue select must be a fold chain line");
        Seq<LStatement> userSelect = LAssembler.read("select out equal a b 1 2\n", true);
        check(!ExprHook.foldsSpanPrologue(userSelect.get(0)), "a user select must not enter the fold chain");

        // 两张相邻的 span 表达式卡（载体文本重开后的画布形态）。两张卡的文本必须在注册表
        // 上下文里生成，否则 compile 会按“没有数组声明”退化成逻辑 read（read x buf i），
        // 那正是这条检查要防的**展开后的**文本，测不到任何东西（2026-09 复核自己踩过）。
        String twoCards = inSpanRegistry(SPAN + "array buf big 0 128\n", () -> SPAN + "array buf big 0 128\n"
            + textOf(ExprCompiler.compile("x", "buf[i]", name -> false, false)) + "\n"
            + textOf(ExprCompiler.compile("y", "buf[j]", name -> false, false)) + "\n");
        Seq<LStatement> loaded = LAssembler.read(twoCards, true);
        List<LStatement> statements = new ArrayList<>();
        for(int i = 0; i < loaded.size; i++) statements.add(loaded.get(i));
        check(statements.size() == 12, "the two-card fixture must parse into 12 statements, got " + statements.size());
        check(!ExprHook.hasExternalReads(statements, 2, 7, ops),
            "another span card's scratch must not count as an external read");
        // 普通临时变量的链外读取仍然要拦住
        List<LStatement> external = new ArrayList<>();
        for(LStatement st : LAssembler.read("array buf cell1 0 8\nread _0 cell1 i\nop mul y _0 2\nset z _0\n", true)){
            external.add(st);
        }
        check(external.size() == 4, "the external-reader fixture must parse into 4 statements");
        List<ExprCompiler.Line> chain = new ArrayList<>();
        chain.add(new ExprCompiler.ReadLine("_0", "cell1", "i"));
        chain.add(new ExprCompiler.OpLine("mul", "y", "_0", "2"));
        check(ExprHook.hasExternalReads(external, 1, 3, chain),
            "a real temp read outside the chain must still block the fold");
    }

    /**
     * 两个注入函数（{@code spanread}/{@code spanwrite}）不参与内联发射，但会并进本次编译的
     * 函数库：编辑器侧的函数名校集必须与编译路径同口径，否则直接调用既会被标红又能编译通过。
     */
    private static void spanBuiltinsStayConsistent(){
        ExprCompiler.FunctionChecker checker = ExprStatement.functionChecker();
        check(checker.isFunction(SpanAccess.BUILTIN_READ), "span read builtin must be a known function in the editor");
        check(checker.isFunction(SpanAccess.BUILTIN_WRITE), "span write builtin must be a known function in the editor");
        check(SpanAccess.builtinFunctionNames().size() == 2, "span builtin name set");
        check(!SpanAccess.builtinFunctionNames().contains(SpanAccess.BUILDING), "scratch names must not be builtins");

        String product = executable(compile(SPAN
            + "funccall " + SpanAccess.BUILTIN_READ + " \"0, 64, 2, cell1, cell2, 0, 0, 0, 0, 0, 0\" out\n"
            + "printflush message1\n"));
        check(!product.contains("funccall"), "the builtin call must be lowered, not left as sugar:\n" + product);
        check(product.contains("op idiv") && product.contains("greaterThanEq"),
            "the span read builtin body must reach the product:\n" + product);
    }

    /** 在一个只声明了这些声明的注册表上下文里跑；进出均恢复，不泄漏到其它检查。 */
    private static <T> T inSpanRegistry(String declarations, java.util.function.Supplier<T> body){
        ArrayRegistry registry = ArrayRegistry.compileRegistry(LAssembler.read(declarations, true), Collections.emptySet());
        ArrayRegistry previous = ArrayRegistry.enter(registry);
        try{
            return body.get();
        }finally{
            ArrayRegistry.restore(previous);
        }
    }

    private static void withSpanRegistry(String declarations, Runnable body){
        inSpanRegistry(declarations, () -> {
            body.run();
            return null;
        });
    }

    /** 折层判定：与 {@code ExprHook.foldAll} 同口径（write 结尾走 rebuildAssignment，否则 rebuild）。 */
    private static String spanFoldBack(String chainText){
        List<ExprCompiler.Line> ops = new ArrayList<>();
        for(String raw : chainText.replace("\r\n", "\n").split("\n", -1)){
            String line = raw.trim();
            if(line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            if(line.startsWith("op ")){
                ops.add(new ExprCompiler.OpLine(parts[1], parts[2], parts[3], parts[4]));
            }else if(line.startsWith("select ") && parts.length == 7){
                ops.add(new ExprCompiler.SelectLine(parts[1], parts[2], parts[3], parts[4], parts[5], parts[6]));
            }else if(line.startsWith("read ") && parts.length == 4){
                ops.add(new ExprCompiler.ReadLine(parts[1], parts[2], parts[3]));
            }else if(line.startsWith("write ") && parts.length == 4){
                ops.add(new ExprCompiler.WriteLine(parts[1], parts[2], parts[3]));
            }else{
                check(false, "spanFoldBack cannot parse line: " + line);
            }
        }
        if(!ops.isEmpty() && ops.get(ops.size() - 1) instanceof ExprCompiler.WriteLine){
            String[] pair = ExprCompiler.rebuildAssignment(ops);
            return pair == null ? null : pair[0] + " = " + pair[1];
        }
        String expr = ExprCompiler.rebuild(ops);
        if(expr == null) return null;
        String[] lines = chainText.trim().replace("\r\n", "\n").split("\n", -1);
        String[] last = lines[lines.length - 1].trim().split("\\s+");
        String dest = last[0].equals("op") ? last[2] : last[1];
        return dest + " = " + expr;
    }

    /** Addressing and payload lines. Entry skip and carriers are not part of the count. */
    private static List<String> instructions(String sugar){
        List<String> lines = new ArrayList<>();
        for(String line : executable(compile(sugar)).split("\n", -1)){
            String trimmed = line.trim();
            if(trimmed.isEmpty() || trimmed.endsWith(":") || trimmed.startsWith("#")) continue;
            if(trimmed.startsWith("set __ls_sugar") || trimmed.startsWith("set __ls_lib")) continue;
            if(trimmed.equals("set @counter 0")) continue;
            lines.add(trimmed);
        }
        return lines;
    }

    private static String executable(String compiled){
        String stripped = SugarCompiler.stripMarkers(compiled);
        StringBuilder out = new StringBuilder();
        for(String line : stripped.replace("\r\n", "\n").split("\n", -1)){
            if(line.startsWith("set __ls_sugar") || line.startsWith("set __ls_lib")) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String textOf(List<ExprCompiler.Line> lines){
        return String.join("\n", linesOf(lines));
    }

    private static List<String> linesOf(List<ExprCompiler.Line> lines){
        List<String> text = new ArrayList<>();
        for(ExprCompiler.Line line : lines) text.add(line.toText());
        return text;
    }

    private static void expectFail(String sugar, String what){
        try{
            compile(sugar);
        }catch(RuntimeException e){
            check(e.getMessage() != null && !e.getMessage().isEmpty(), what + " failed without a message");
            return;
        }
        check(false, "compile should have failed (" + what + ")");
    }

    private static void withLinks(ArrayRegistry.LinkResolver resolver, Runnable body){
        ArrayRegistry.LinkResolver previous = ArrayRegistry.enterLinkResolver(resolver);
        try{
            body.run();
        }finally{
            ArrayRegistry.restoreLinkResolver(previous);
        }
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }

    /** Capacities by name. Unlisted names are unresolved (-1). */
    private static final class Links implements ArrayRegistry.LinkResolver{
        final Map<String, Integer> capacities = new HashMap<>();
        final java.util.Set<String> privileged = new HashSet<>();
        boolean processorPrivileged = true;

        static Links of(String a, int aCap, String b, int bCap){
            Links links = new Links();
            links.capacities.put(a, aCap);
            links.capacities.put(b, bCap);
            return links;
        }

        static Links privileged(String a, String b){
            Links links = of(a, 512, b, 512);
            links.privileged.add(a);
            links.privileged.add(b);
            links.processorPrivileged = false;
            return links;
        }

        @Override public Building linkedBuilding(String memory){ return null; }

        @Override public int capacity(String memory){
            Integer value = capacities.get(memory);
            return value == null ? -1 : value;
        }

        @Override public boolean privilegedMemory(String memory){
            return privileged.contains(memory);
        }

        @Override public boolean processorPrivileged(){
            return processorPrivileged;
        }
    }
}
