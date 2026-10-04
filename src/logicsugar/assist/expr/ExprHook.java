package logicsugar.assist.expr;

import arc.*;
import arc.func.*;
import arc.scene.*;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.logic.*;
import mindustry.logic.LCanvas.*;
import mindustry.logic.LStatements.*;
import mindustry.logic.SugarAsserts.AssertBoundsCard;
import mindustry.logic.ConditionOp;
import mindustry.logic.SugarAsserts.AssertionType;
import mindustry.logic.SugarStatements.BeginStatement;
import mindustry.logic.SugarStatements.FuncCallStatement;

import java.util.*;

/**
 * 表达式集成钩子：提供 op 链 ↔ 表达式的双向转换。
 *
 * 集成后：
 * - foldAll() 由 LogicCanvas.load()（以及粘贴片段之后）直接调用，零延迟
 * - save() 是**纯文本读取**：{@link #unfoldedText} 在文本层展开多行卡并换算 jump/begin 的
 *   语句下标，画布一个元素都不动（旧实现是 unfoldAll → super.save → foldAll，周期性调用会把
 *   正在编辑的 Expression 卡拆掉重建、让输入框失焦）。{@link #unfoldAll} 因此只剩“画布形态”
 *   这一条用途（两条路径共用 {@link #cardLines} 的判定，不会漂）
 * - 行号由 LogicCanvas.act() 更新
 * - LogicIO.allStatements 是 public static 字段，直接访问无需反射
 * - 跳转高度刷新通过 SugarCanvas 的兼容入口处理
 *
 * ------------------------------------------------------------
 * 致谢 / Acknowledgements
 * ------------------------------------------------------------
 * op 链折叠（foldAll）思路参考了 mindcode 项目的 MlogDecompiler：
 *   - 项目地址: https://github.com/cardillan/mindcode
 *   - 参考文件: compiler/src/main/java/info/teksol/mc/mindcode/decompiler/MlogDecompiler.java
 *   - 参考内容: collapseExpressions() 检测线性指令块并折叠为表达式子树，
 *     用 isLinear() 判断指令是否可参与折叠（非 jump、非 @counter 赋值）。
 *     本项目用 hasJumpInRange() 实现等价的跳转安全检查。
 */
public class ExprHook{

    private static boolean statementRegistered = false;

    public static void init(){
        registerStatement();
    }

    /** 将 ExprStatement 注入 LogicIO.allStatements，使其出现在编辑器的积木列表中。 */
    private static void registerStatement(){
        if(statementRegistered) return;
        for(Prov<LStatement> prov : LogicIO.allStatements){
            if(prov.get() instanceof ExprStatement) return;
        }
        LogicIO.allStatements.add(() -> new ExprStatement());
        statementRegistered = true;
        Log.info("[LogicAssist] ExprStatement registered to LogicIO.allStatements");
    }

    // ===== 折叠：op 链 → ExprStatement =====

    public static void foldAll(LCanvas canvas){
        if(canvas == null || canvas.statements == null) return;

        Seq<Element> children = canvas.statements.getChildren();
        if(children.isEmpty()) return;

        // 先把卡片当前 UI 值写回字段，再快照数组声明；否则刚编辑过的 base/size
        // 可能仍使用旧注册表，导致保存时地址换算与标红结果滞后一拍。
        saveUIAll(canvas);
        // 数组注册表快照：折叠全程用同一份宽松口径的画布注册表（画布内容在折叠过程中
        // 会变，逐次重查既不一致也浪费）；画布上没有任何数组卡时用空注册表，阻止
        // ArrayRegistry.active() 反复回退到画布探测。finally 恢复，异常不泄漏上下文。
        ArrayRegistry snapshot = ArrayRegistry.canvasRegistry(canvas);
        ArrayRegistry previousArrays = ArrayRegistry.enter(snapshot == null ? ArrayRegistry.empty() : snapshot);
        try{
            foldAllInContext(canvas, children);
        }finally{
            ArrayRegistry.restore(previousArrays);
        }
    }

    /** 折叠主体（调用方已进入数组注册表上下文）。 */
    private static void foldAllInContext(LCanvas canvas, Seq<Element> children){
        saveUIAll(canvas);

        boolean changed = false;
        List<LStatement> statements = statementList(children);
        int i = 0;
        while(i < statements.size()){
            LStatement first = statements.get(i);
            if(first == null || !isChainLine(first)){
                i++;
                continue;
            }

            Chain chain = collectChain(statements, i);
            List<ExprCompiler.Line> ops = chain.ops;
            int j = chain.end;
            int chainLen = chain.length();
            // 整条链只有自动断言卡（无指令行）时无事可做
            if(ops.isEmpty()){
                i = j;
                continue;
            }
            // 链首是注册表命中的 read/write 时单行也尝试折叠（unfold 后 x = buf[3]、
            // buf[2] = 5 各只有一行，fold 必须能还原，否则表达式卡保存一次就永久丢失）；
            // 普通 op 链保持 >= 2 的既有门槛。
            boolean arrayEdge = ops.get(0) instanceof ExprCompiler.ReadLine
                || ops.get(0) instanceof ExprCompiler.WriteLine;
            if(chainLen >= 2 || (chainLen == 1 && arrayEdge)){
                // jump 安全检查（画布专有）：若有 jump 指向链中间 [i+1, i+chainLen-1]，放弃折叠。
                // 场景：别人没装插件时写的 jump 指向 op 链中间，折叠会改变语义。
                // 指向链首 i 是允许的，折叠后仍指向 expr 积木。
                Plan plan = hasJumpInRange(canvas, i + 1, i + chainLen - 1)
                    ? null : foldPlan(statements, chain);
                if(plan != null){
                    ExprStatement exprStmt = new ExprStatement();
                    exprStmt.dest = plan.dest() == null ? "result" : plan.dest();
                    exprStmt.expr = plan.expr();
                    exprStmt.lastOps = ops;

                    for(int k = 0; k < chainLen; k++){
                        ((StatementElem)children.get(i)).remove();
                    }

                    canvas.addAt(i, exprStmt);

                    changed = true;
                    // 画布已变：链下标的基准一起重建（下标口径与 collectChain 保持同一份语句列表）
                    statements = statementList(children);
                }else{
                    // 链外读取 / rebuild 失败（如链不完整）/ 安全门拒绝：跳过整条链，
                    // 避免 i++ 后从链中间重新查找子链导致误折叠
                    i = j;
                    continue;
                }
            }
            i++;
        }

        if(changed){
            // Element references survive the remove/insert operations. Recalculate their
            // serialized indices here; shifting the old values again corrupts nested blocks.
            saveUIAll(canvas);
            setupUIAll(canvas);
            // 行号由 LogicDragLayout.layout() 自动更新，无需手动调用
            SugarCanvas.markJumpHeightsDirty(canvas);
            Log.debug("[LogicAssist] Expression chains folded");
        }
    }

    /** 一条链折回卡片的结果：目标文本（{@code dest}）与表达式文本（{@code expr}）。 */
    public record Plan(String dest, String expr){}

    /**
     * 一条已收集的链能不能折回卡片：链外读取判定 + 逆向重建（{@code rebuild} /
     * {@code rebuildAssignment}）+ 重新编译比对安全门。画布版 {@code foldAllInContext} 与自测
     * 共用这一处，因此“哪些链能折回”不会在两边漂（链收集同样共用 {@link #collectChain}）。
     *
     * <p>画布专有的 jump 安全检查由调用方先做（{@code foldAllInContext} 的
     * {@code hasJumpInRange}）。链内的自动断言卡属于链范围，不参与链外读取判定。</p>
     *
     * @return {@code null} = 保持原样（链外读取 / 重建失败 / 安全门拒绝）
     */
    public static Plan foldPlan(List<LStatement> statements, Chain chain){
        List<ExprCompiler.Line> ops = chain.ops;
        if(ops.isEmpty()) return null;
        // 链内临时变量被链外语句读取时放弃折叠（折叠会删除这些变量，值也会变）
        if(hasExternalReads(statements, chain.start, chain.end, ops)) return null;

        String dest;
        String expr;
        ExprCompiler.Line last = ops.get(ops.size() - 1);
        if(last instanceof ExprCompiler.WriteLine){
            // 下标赋值链：write <value> <memory> <address> 结尾 → dest=buf[i], expr=value
            String[] pair = ExprCompiler.rebuildAssignment(ops);
            if(pair == null) return null;
            dest = pair[0];
            expr = pair[1];
        }else{
            expr = ExprCompiler.rebuild(ops);
            if(expr == null) return null;
            dest = ExprCompiler.lineDest(last);
        }
        // 折回安全门：数组/矩阵/span/数据模块反向层（容器 getter 等）的折回结果重新编译后
        // 必须与原链指令流逐行一致，否则保持原样（宁可少折回也不能折错；链内没有任何折回时该门恒通过）。
        if(!ExprCompiler.verifyArrayFold(ops, dest, expr, ExprStatement.functionChecker())) return null;
        return new Plan(dest, expr);
    }

    /** 画布元素 → 语句列表（null 表示非语句元素）。链收集与链外读取判定共用同一份下标口径。 */
    private static List<LStatement> statementList(Seq<Element> children){
        List<LStatement> statements = new ArrayList<>(children.size);
        for(Element child : children){
            statements.add(child instanceof StatementElem elem ? elem.st : null);
        }
        return statements;
    }

    /** 折叠链：语句下标区间 {@code [start, end)} 加它折出的指令链。 */
    public static final class Chain{
        public final int start, end;
        public final List<ExprCompiler.Line> ops;

        Chain(int start, int end, List<ExprCompiler.Line> ops){
            this.start = start;
            this.end = end;
            this.ops = ops;
        }

        public int length(){
            return end - start;
        }
    }

    /**
     * 从 {@code statements[start]} 开始收集折叠链的判定结果（无画布版）。
     *
     * <p>画布折叠（{@link #foldAll}）与自测走同一条收集路径：一条语句能不能入链、入链后链
     * 是否到此结束，只在 {@link #appendChainLine} 一处。这是“重开时卡片能不能折回”的第一道
     * 判据，而它在无头环境里看不见——自测因此用真正在跑的那一份，而不是自己再写一遍。</p>
     */
    public static Chain collectChain(List<LStatement> statements, int start){
        List<ExprCompiler.Line> ops = new ArrayList<>();
        int j = start;
        while(j < statements.size()){
            LStatement st = statements.get(j);
            if(st == null) break;
            if(st instanceof AssertBoundsCard && isAutoAssert(st)){
                // 自动断言卡随链生成（位于下标计算之后、read/write 之前）：作为链内
                // 透明元素跳过——不进入 ops，但折叠时随链一起移除，下次展开按折回后的
                // 表达式重建。用户手写断言卡不是链元素（链在此断开）。
                j++;
                continue;
            }
            ChainStep step = appendChainLine(ops, st);
            if(step == ChainStep.NONE) break;
            j++;
            if(step == ChainStep.TAIL) break;
        }
        return new Chain(start, j, ops);
    }

    /** 链收集的单步：{@code NONE} = 该语句不属于链（链在此断开）、{@code MORE} = 已入链且链继续、
     *  {@code TAIL} = 已入链且链到此结束（该行写的是非临时目标）。 */
    private enum ChainStep{ NONE, MORE, TAIL }

    /** 一条语句并入折叠链（{@link #collectChain} 逐条调用它）。 */
    private static ChainStep appendChainLine(List<ExprCompiler.Line> ops, LStatement st){
        if(st instanceof OperationStatement opStmt){
            ops.add(new ExprCompiler.OpLine(
                opStmt.op.name(), opStmt.dest, opStmt.a, opStmt.b));
            // span 前导段的两个固定 scratch 名（__ls_span_q/r）不是临时变量形态，但它们同样是
            // 展开的内部寄存器（不是用户表达式的一部分）：链不能在这里结束，否则变量下标的
            // span 寻址永远凑不齐（前导段 + 它自己的 read/write），卡片折不回来。
            return ExprCompiler.isTemp(opStmt.dest) || SpanAccess.scratchNames().contains(opStmt.dest)
                ? ChainStep.MORE : ChainStep.TAIL;
        }
        if(st instanceof SensorStatement sensor){
            // sensor 语句也可入链：sensor _0 unit @health + op mul x _0 2
            // → unit.health * 2。仅折叠 type 为 @LAccess 常量的 sensor
            // （变量 type 是动态属性传感，语义上不等价于成员访问）。
            if(!sensor.type.startsWith("@") || ExprCompiler.resolveMember(sensor.type) == null){
                return ChainStep.NONE;
            }
            ops.add(new ExprCompiler.SensorLine(sensor.to, sensor.from, sensor.type));
            return ExprCompiler.isTemp(sensor.to) ? ChainStep.MORE : ChainStep.TAIL;
        }
        if(st instanceof FuncCallStatement call){
            // funccall 入链：call foo(a) _1 + op mul x _1 2 → foo(a) * 2
            // 仅折叠实参为纯值（temp/变量/数字）的调用——带嵌套表达式的实参
            // 无法无损重建（实参文本需要重新解析），保持原样积木。
            if(!isFoldableCall(call)) return ChainStep.NONE;
            ops.add(new ExprCompiler.CallLine(call.name, call.args, call.result));
            return ExprCompiler.isTemp(call.result) ? ChainStep.MORE : ChainStep.TAIL;
        }
        if(st instanceof ReadStatement read){
            // 注册表命中的 read 行入链（数组/矩阵/span 下标读，或容器 getter 展开末尾那条
            // 落在容器内存块上的 read）：经 opToNode 折回 buf[i] / s.top()。用户手写的普通
            // read（memory 未命中任何注册表）不受影响；read 的 dest 非 temp 时它是链的最后一行。
            if(!foldsMemoryLine(read)) return ChainStep.NONE;
            ops.add(new ExprCompiler.ReadLine(read.output, read.target, read.address));
            return ExprCompiler.isTemp(read.output) ? ChainStep.MORE : ChainStep.TAIL;
        }
        if(st instanceof SelectStatement sel){
            // span 展开的前导行（select building slot）：不是积木，但必须留在链里。
            // 它永远不结束链：这些 scratch 不是用户表达式的一部分，折叠时由
            // ExprCompiler 作为 consumed 行消费掉。
            if(!foldsSpanPrologue(sel)) return ChainStep.NONE;
            ops.add(new ExprCompiler.SelectLine(sel.result, sel.op.name(), sel.comp0, sel.comp1, sel.a, sel.b));
            return ChainStep.MORE;
        }
        if(st instanceof WriteStatement write){
            // 注册表命中的 write 行：下标赋值的终结行（没有 dest），链到此为止。
            // 整条链（含地址计算 op add）交给 rebuildAssignment 折回 buf[i] = value。
            if(!foldsMemoryLine(write)) return ChainStep.NONE;
            ops.add(new ExprCompiler.WriteLine(write.input, write.target, write.address));
            return ChainStep.TAIL;
        }
        return ChainStep.NONE;
    }

    // ===== 展开：ExprStatement → op 链 =====

    /** 语句能否作为表达式链的节点：op 语句、type 为 @LAccess 常量的 sensor 语句、
     *  实参为纯值的 funccall 语句、memory 命中数组/span/结构注册表的 read/write 语句、
     *  span 前导段 select，以及展开时随链生成的自动越界断言卡（链内透明元素，折叠时一并移除）。
     *
     *  <p>链收集（{@link #collectChain}）与自测共用这一道判据：它决定“哪些行会被折进卡片”，
     *  也决定了链在哪里断掉——而链在外面断掉的行会被 {@link #hasExternalReads} 当成“链外读取”，
     *  正是 getter 类展开以前折不回来的原因。</p> */
    public static boolean isChainLine(LStatement st){
        if(st instanceof OperationStatement) return true;
        if(st instanceof SensorStatement sensor){
            return sensor.type.startsWith("@") && ExprCompiler.resolveMember(sensor.type) != null;
        }
        if(st instanceof FuncCallStatement call) return isFoldableCall(call);
        // 自动断言卡随链生成：作为链元素参与折叠（不产出 ops，但会被移除并重建）。
        // 用户手写的断言卡不是链元素，链在它之前断开。
        if(st instanceof AssertBoundsCard) return isAutoAssert(st);
        // read/write 行只有在注册表把 memory 解析到已声明数组时才入链：
        // 用户手写的普通 read/write 与纯原版 mlog（无声明卡）不受影响
        if(st instanceof ReadStatement || st instanceof WriteStatement) return foldsMemoryLine(st);
        // span 展开的前导行（select building slot）：不是积木，但必须参与折叠，
        // 否则 `x = buf[i]` 保存一次就永久退化成前导段 + 一条原版 read 积木
        if(st instanceof SelectStatement sel) return foldsSpanPrologue(sel);
        return false;
    }

    /**
     * {@code read}/{@code write} 行能否作为折叠链的元素：memory 命中数组/矩阵/span 注册表，
     * 或落在某个数据结构声明的内存块上（{@link ExprIntrinsics#declaresMemory}）。
     * 链收集（{@link #isChainLine}）与自测共用这一处判定。
     *
     * <p>为什么要管结构内存块：容器 getter（{@code s.top()} / {@code q.front()} / {@code d.back()}）
     * 的多行展开以一条落在容器内存块上的 {@code read} 结尾。read 不入链时链在它前面断掉，
     * 而链外读取判定又把地址临时变量当成外部读取，整条链永远折不回来；多行卡片在保存文本里
     * 不带自描述标记，于是 getter 卡保存一次就永久退化成裸指令（2026-10 报告：Expr 里
     * {@code stack.top()} 这类 getter 重建不出来，画布上留下一条 {@code _1 = 8+head-(...)}
     * 的地址运算卡）。入链后由数据模块的反向层（{@link ExprIntrinsics.Provider#foldAt}）
     * 把整段折回 getter，重新编译比对门仍然是唯一出口。</p>
     */
    public static boolean foldsMemoryLine(LStatement statement){
        if(statement instanceof ReadStatement read){
            return isArrayMemory(read.target) || isSpanScratch(read.target, read.address)
                || ExprIntrinsics.declaresMemory(read.target);
        }
        // write 行只有数组/矩阵/span 下标赋值这一种形态：容器/记录的写都在注入函数体里，
        // 展开链上不会出现落在结构内存块上的 write
        if(statement instanceof WriteStatement write){
            return isArrayMemory(write.target) || isSpanScratch(write.target, write.address);
        }
        return false;
    }

    /**
     * span 变量逻辑地址的寻址行（{@code read/write x __ls_span_b __ls_span_r}）：前导段用的是
     * 程序级固定 scratch，内存名不是 span 成员块，{@link #isArrayMemory} 认不出来。它同样必须
     * 入链——否则 {@code ExprCompiler} 的 span 视角永远凑不齐（前导段 select 在链内、这条
     * read 在链外），变量下标的表达式卡（{@code x = buf[i]}）重开后折不回卡片。只声明确实有
     * span 时才放行（这两个名字是保留的，手写程序不会用到）。
     */
    private static boolean isSpanScratch(String memory, String address){
        if(!SpanAccess.BUILDING.equals(memory) || !SpanAccess.SLOT.equals(address)) return false;
        ArrayRegistry registry = ArrayRegistry.active();
        return registry != null && registry.hasSpans();
    }

    /**
     * span 展开前导段里的 select 行（{@code result} 是固定的 building scratch）。
     * {@code foldAll} 据此把它收进折叠链；{@code unfoldAll} 反过来必须仍然认为它
     * “没有对应的原版积木”（{@link #statementFor} 不映射它）——否则展开会删掉卡片却
     * 不补出这几条指令，保存文本也会变。判定与折叠共用本方法，避免两边漂移。
     */
    public static boolean foldsSpanPrologue(LStatement st){
        return st instanceof SelectStatement sel && SpanAccess.BUILDING.equals(sel.result);
    }

    /** memory 变量名是否承载了当前注册表中的数组（宽松口径画布注册表）。 */
    private static boolean isArrayMemory(String memory){
        if(memory == null || memory.isEmpty()) return false;
        ArrayRegistry registry = ArrayRegistry.active();
        if(registry == null || registry.isEmpty()) return false;
        if(!registry.byMemory(memory).isEmpty()) return true;
        // span 成员：数组/矩阵登记在 span 别名上，而保存文本里的物理读落在成员块上
        // （常量下标被编译成 `read x cell1 3`），这些行同样要能参与折回。
        for(ArrayRegistry.SpanInfo span : registry.spansByMember(memory)){
            if(!registry.byMemory(span.name).isEmpty() || !registry.matricesByMemory(span.name).isEmpty()){
                return true;
            }
        }
        return false;
    }

    /** funccall 的实参必须是纯值（temp/变量/数字，无逗号无括号），否则无法无损重建表达式。 */
    private static boolean isFoldableCall(FuncCallStatement call){
        if(call.result == null || call.result.isEmpty()) return false;
        String args = call.args.trim();
        if(args.isEmpty()) return true;
        if(!ExprCompiler.collectCalls(args).isEmpty()) return false; // 实参里含函数调用
        try{
            for(String arg : args.split(",")){
                String value = arg.trim();
                // 纯值：temp / 变量 / 数字（操作符、括号、空格都拒绝）；
                // '-' 仅对负数字面量放行，否则 a-b 折叠进 foo(a-b) 会静默变成减法
                if(value.isEmpty()) return false;
                boolean negativeNumber = value.matches("-\\d+(\\.\\d+)?");
                for(int i = 0; i < value.length(); i++){
                    char c = value.charAt(i);
                    if(!Character.isLetterOrDigit(c) && c != '_' && c != '@' && c != '.'
                        && !(c == '-' && negativeNumber)) return false;
                }
            }
            return true;
        }catch(Exception e){
            return false;
        }
    }

    // ===== 文本：展开态（画布一个元素都不动） =====================================================

    /**
     * 画布当前内容的<b>展开态程序文本</b>：语义等于旧的
     * {@code unfoldAll() → super.save() → foldAll()} 取到的那段文本，但不改动画布。
     *
     * <p>为什么要有这一份"不碰画布的 save()"：{@code unfoldAll} 会 remove/addAt 积木元素、重建
     * 每张卡的 {@code StatementElem}。只要 {@code save()} 被周期性调用（自身的指令预算横幅每 24
     * 帧一次、共存档里第三方编辑器每帧一次），正在编辑的 Expression 卡就会被拆掉重建，文本框在
     * 下一次 {@code Scene.act} 里因为元素已脱离而失焦——用户看到的是"点进编辑区域马上就丢焦点"
     * （2026-10 报告）。取文本本来就是纯读取，展开只需要发生在文本层。</p>
     *
     * <p>展开态的意义是下标口径：{@code LAssembler.write} 一行一条语句，而
     * {@code JumpStatement.destIndex} / {@code BeginStatement.destIndex} 记的是<b>画布语句
     * 下标</b>。多行表达式卡在文本里是 N 条语句，所以这些下标必须换算到展开后的下标，否则重新
     * 解析时目标会整体偏移（旧实现靠"先把画布也展开"保证两者相等，代价就是重建积木）。</p>
     */
    public static String unfoldedText(LCanvas canvas){
        if(canvas == null || canvas.statements == null) return "";

        // 先把跳转/结构卡的目标落到 destIndex：与 LCanvas.save() 一样从 UI 侧取值
        saveUIAll(canvas);
        // 与 foldAll/unfoldAll 同一个数组/span 注册表快照：地址换算与越界检查要对着同一份声明表
        ArrayRegistry snapshot = ArrayRegistry.canvasRegistry(canvas);
        ArrayRegistry previousArrays = ArrayRegistry.enter(snapshot == null ? ArrayRegistry.empty() : snapshot);
        try{
            List<LStatement> statements = new ArrayList<>();
            for(Element child : canvas.statements.getChildren()){
                if(child instanceof StatementElem elem && elem.st != null) statements.add(elem.st);
            }
            return unfoldedText(statements, assertEmitEnabled());
        }finally{
            ArrayRegistry.restore(previousArrays);
        }
    }

    /** 越界断言只在 emit 调试构建（单机）下成为真实语句；strip 模式与联机恒不发射。 */
    private static boolean assertEmitEnabled(){
        return SugarCompiler.currentAssertEmit() == SugarCompiler.AssertEmit.emit;
    }

    /**
     * 语句列表口径（无画布，可单测）：{@code statements} 的下标就是画布语句下标。
     *
     * <p>画布上下文（数组/span 注册表）由调用方负责；画布侧入口是
     * {@link #unfoldedText(LCanvas)}。</p>
     */
    public static String unfoldedText(List<LStatement> statements, boolean emitAsserts){
        int size = statements.size();
        if(size == 0) return "";

        // 第一遍：展开计划 + "画布语句下标 -> 文本语句下标"
        int[] textIndex = new int[size + 1];
        List<TextPlan> plan = new ArrayList<>(size);
        int emitted = 0;
        for(int i = 0; i < size; i++){
            textIndex[i] = emitted;
            TextPlan text = planStatement(statements, i, plan, emitAsserts);
            plan.add(text);
            emitted += text.statements;
        }
        textIndex[size] = emitted;

        // 第二遍：写文本（目标下标换算在写之前完成）
        StringBuilder out = new StringBuilder();
        for(int i = 0; i < size; i++){
            for(LStatement line : plan.get(i).lines){
                appendStatement(out, line, textIndex);
            }
        }
        return out.toString();
    }

    /** 一条画布语句的文本形态：{@link #lines} 是要写的语句，{@link #statements} 是它们占用的
     *  文本语句条数。保留一张多行表达式卡时两者不同——卡只有一条语句，但文本是 N 条。 */
    private record TextPlan(List<LStatement> lines, int statements){}

    /** 展开一条画布语句的文本形态：多行表达式卡在文本层展开成它的 op 行（画布不动），
     *  其余语句原样一条。展开判定与 {@link #unfoldAll} 共用 {@link #cardLines}，两条路径不会漂。 */
    private static TextPlan planStatement(List<LStatement> statements, int index, List<TextPlan> plan, boolean emitAsserts){
        LStatement statement = statements.get(index);
        if(statement instanceof ExprStatement card){
            List<ExprCompiler.Line> ops = cardLines(card, emitAsserts);
            if(ops != null){
                if(!keepsCard(ops) && !hasUnmappableLine(ops)){
                    List<LStatement> expanded = toStatements(ops, previousLines(plan), emitAsserts);
                    return new TextPlan(expanded, expanded.size());
                }
                // 保留卡片（单行卡 / 链里有画布表达不了的行）：文本仍是 write() 写出的那串行，
                // 可能多行——span 前导段的 select 就没有对应的原版积木，条数按行数算。
                return new TextPlan(Collections.singletonList(statement), Math.max(1, ops.size()));
            }
        }
        return new TextPlan(Collections.singletonList(statement), 1);
    }

    /** 展开计划尾部连续的自动断言语句：{@link #toStatements} 的“重复展开不重复插卡”靠它，
     *  口径与画布侧的 {@code trailingAutoAsserts} 一致（可以跨多条画布语句往回看）。 */
    private static List<LStatement> previousLines(List<TextPlan> plan){
        List<LStatement> tail = new ArrayList<>();
        for(int i = plan.size() - 1; i >= 0; i--){
            List<LStatement> lines = plan.get(i).lines;
            for(int k = lines.size() - 1; k >= 0; k--){
                LStatement line = lines.get(k);
                if(!isAutoAssert(line)) return tail;
                tail.add(0, line);
            }
        }
        return tail;
    }

    /**
     * 写一条语句并补换行；jump / begin 卡里记的<b>画布</b>语句下标换算成<b>文本</b>语句下标。
     *
     * <p>{@code destIndex} 只是 {@code dest} 的 UI 镜像（{@code setupUI()} 按它重建），所以可以
     * 临时改写；写完在 {@code finally} 里还原，读文本的其它人（结构引导线、撤销快照）看到的仍是
     * 画布下标的原值。越界（目标已删除等）原样写出，编译器的报错口径与旧实现一致。</p>
     */
    private static void appendStatement(StringBuilder out, LStatement statement, int[] textIndex){
        int canvasIndex = statementIndex(statement);
        if(canvasIndex < 0 || canvasIndex >= textIndex.length){
            statement.write(out);
            out.append('\n');
            return;
        }
        setStatementIndex(statement, textIndex[canvasIndex]);
        try{
            statement.write(out);
        }finally{
            setStatementIndex(statement, canvasIndex);
        }
        out.append('\n');
    }

    /** 语句里记录的画布语句下标（没有则为 -1）：jump 的目标、begin 卡的块尾注释。 */
    private static int statementIndex(LStatement statement){
        if(statement instanceof JumpStatement jump) return jump.destIndex;
        if(statement instanceof BeginStatement begin) return begin.destIndex;
        return -1;
    }

    private static void setStatementIndex(LStatement statement, int value){
        if(statement instanceof JumpStatement jump) jump.destIndex = value;
        else if(statement instanceof BeginStatement begin) begin.destIndex = value;
    }

    public static void unfoldAll(LCanvas canvas){
        if(canvas == null || canvas.statements == null) return;

        Seq<Element> children = canvas.statements.getChildren();
        if(children.isEmpty()) return;

        // 先把卡片当前 UI 值写回字段，再快照数组声明；否则刚编辑过的 base/size
        // 可能仍使用旧注册表，导致展开时地址换算滞后一拍。
        saveUIAll(canvas);
        // 与 foldAll 相同的注册表快照：展开时 buf[i] 表达式的地址换算、越界检查都要
        // 对着同一份声明表（保存拦截的严格口径由 write()/compile 阶段负责）
        ArrayRegistry snapshot = ArrayRegistry.canvasRegistry(canvas);
        ArrayRegistry previousArrays = ArrayRegistry.enter(snapshot == null ? ArrayRegistry.empty() : snapshot);
        try{
            unfoldAllInContext(canvas, children);
        }finally{
            ArrayRegistry.restore(previousArrays);
        }
    }

    /** 展开主体（调用方已进入数组注册表上下文）。 */
    private static void unfoldAllInContext(LCanvas canvas, Seq<Element> children){
        saveUIAll(canvas);

        // 越界断言只在 emit 调试构建（单机）下展开成 assertBounds 卡；strip 模式与联机
        // 恒不插入（currentAssertEmit() 已含联机门禁）。
        boolean emitAsserts = assertEmitEnabled();

        boolean changed = false;
        for(int i = 0; i < children.size; i++){
            if(!(children.get(i) instanceof StatementElem)) continue;
            StatementElem elem = (StatementElem)children.get(i);
            if(!(elem.st instanceof ExprStatement)) continue;

            ExprStatement exprStmt = (ExprStatement)elem.st;

            // 编译失败 / 单行卡 / 链里有画布表达不了的行都保留卡片，展开判定与文本层
            // （{@link #unfoldedText}）共用同一次调用，两条路径不会漂。
            List<ExprCompiler.Line> ops = cardLines(exprStmt, emitAsserts);
            if(ops == null){
                // 编译失败：保留 ExprStatement 不展开，write() 会输出 lastOps
                // 避免 unfold→fold 循环用 lastOps 重建 ExprStatement 覆盖错误的 expr
                continue;
            }

            // 单行表达式（x = 0 / x = a / x = a + b）在保存文本里本来就只占一条语句，
            // 展开没有任何结构收益；而 foldAll 的单行门槛只对数组 read/write 放行，展开后
            // 无法折回。若不跳过，用户刚拖出的表达式卡会在第一次 save()
            // （addAt → recordCanvasHistory）后变成普通 set/op 积木——展开与保留卡片产出的
            // 文本逐字相同（write() 输出同一份 Line 链），因此产物与语句下标都不变。
            if(keepsCard(ops)) continue;

            // 链里出现无法映射成原版语句的行（未知 RawLine）时保留卡片：宁可留着表达式卡，
            // 也不能删掉用户的积木。历史教训：CopyLine（现 set 拷贝）曾落到 RawLine 分支被
            // 静默丢弃，而调用方已经移除了卡片，"添加 Expr" 表现为毫无反应。
            if(hasUnmappableLine(ops)){
                Log.warn("[LogicAssist] expression '@ = @' has instructions without a canvas statement; keeping the Expr card",
                    exprStmt.dest, exprStmt.expr);
                continue;
            }

            // 插入点之前的连续自动断言卡（画布顺序）：重复展开时等价的断言行不再插入
            List<LStatement> preceding = new ArrayList<>();
            for(int k = i - 1; k >= 0; k--){
                if(!(children.get(k) instanceof StatementElem prev) || !isAutoAssert(prev.st)) break;
                preceding.add(0, prev.st);
            }
            List<LStatement> statements = toStatements(ops, preceding, emitAsserts);

            elem.remove();
            for(int k = 0; k < statements.size(); k++){
                canvas.addAt(i + k, statements.get(k));
            }

            changed = true;
            i += statements.size() - 1;
        }

        if(changed){
            // See foldAll(): the destination elements have already moved with the layout.
            saveUIAll(canvas);
            setupUIAll(canvas);
            SugarCanvas.markJumpHeightsDirty(canvas);
            Log.debug("[LogicAssist] Expression statements unfolded");
        }
    }

    // ===== 展开产物：Line 链 → 画布语句（含自动越界断言） =====

    /**
     * 表达式卡编译后的行为（{@code null} = 编译失败，调用方保留卡片、{@code write()} 走 lastOps
     * 回退）。画布展开（{@link #unfoldAll}）与文本展开（{@link #unfoldedText}）共用这一处，
     * 因此"什么卡不展开"的判定不会在两边漂。
     *
     * <p>与 {@code ExprStatement.write()}/{@code SugarLogicDialog} 预检同口径：使用
     * {@link ExprStatement#functionChecker()} 校验函数名，否则未定义函数会被展开成 will-fail 的
     * funccall（编译时才报错），与编辑期标红、保存拦截的行为不一致。</p>
     */
    private static List<ExprCompiler.Line> cardLines(ExprStatement card, boolean emitAsserts){
        try{
            return ExprCompiler.compile(card.dest, card.expr, ExprStatement.functionChecker(), emitAsserts);
        }catch(Exception e){
            return null;
        }
    }

    /**
     * 该链是否应保留表达式卡而不展开：只有一行、且这一行有对应的原版卡片
     * （值拷贝 set / op / sensor / read / write / funccall）。单行链在保存文本里占一条
     * 语句，与卡本身等价，展开只是把可编辑的表达式卡降级成 set/op 积木。
     */
    public static boolean keepsCard(List<ExprCompiler.Line> ops){
        return ops.size() == 1 && statementFor(ops.get(0)) != null;
    }

    /**
     * 单行 read/write 链：{@code foldAll} 的数组门槛（memory 命中数组注册表）本来就能把它折回
     * 卡片，不需要 {@link ExprStatement#cardMarkerPrefix} 自描述标记。
     */
    public static boolean foldsBackAlone(List<ExprCompiler.Line> ops){
        return ops.size() == 1
            && (ops.get(0) instanceof ExprCompiler.ReadLine || ops.get(0) instanceof ExprCompiler.WriteLine);
    }

    /**
     * 链里是否存在没有画布语句对应的行。{@link ExprCompiler.AssertBoundsLine} 按模式转换或
     * 丢弃，不算丢失；其它无法映射的行展开后会静默丢掉指令，调用方必须保留原卡片。
     */
    public static boolean hasUnmappableLine(List<ExprCompiler.Line> ops){
        for(ExprCompiler.Line line : ops){
            if(line instanceof ExprCompiler.AssertBoundsLine) continue;
            if(statementFor(line) == null) return true;
        }
        return false;
    }

    /** 自动插入的越界断言卡消息前缀。用于两处识别：展开时避免重复插入、折叠时清理
     *  由表达式生成的断言卡（用户手写的断言卡没有该前缀，永远不动）。 */
    public static final String AUTO_ASSERT_PREFIX = "ls-auto: ";

    /**
     * 把表达式链（{@link ExprCompiler#compile} 的产物）转成画布语句序列。
     *
     * <p>{@link ExprCompiler.AssertBoundsLine} 只在 {@code emit}（单机调试构建）下转成
     * {@link AssertBoundsCard}，线格式与 F1 的 lower 路径一致（消息带
     * {@link #AUTO_ASSERT_PREFIX} 前缀）；{@code emit=false}（strip 模式 / 联机）时断言行
     * 被丢弃。断言卡按原行位置插入，因此始终位于对应 read/write 之前。</p>
     *
     * <p>幂等：{@code preceding} 是插入点之前的画布语句（画布顺序，可为 null）。若其尾部
     * 已有的自动断言卡与链首连续的断言行逐一等价，则这些断言行不再重复插入——重复展开
     * （展开 → 折叠 → 再展开）不会产生重复卡片。</p>
     */
    public static List<LStatement> toStatements(List<ExprCompiler.Line> ops, List<LStatement> preceding, boolean emit){
        List<LStatement> result = new ArrayList<>(ops.size());
        List<LStatement> existing = emit ? trailingAutoAsserts(preceding) : Collections.<LStatement>emptyList();
        int existingIndex = 0;
        boolean leading = true; // 只有链首连续的断言行才可能与插入点之前的卡片对应
        for(ExprCompiler.Line line : ops){
            if(line instanceof ExprCompiler.AssertBoundsLine bounds){
                if(!emit) continue;
                if(leading && existingIndex < existing.size()
                    && equivalentAssert(existing.get(existingIndex), bounds)){
                    existingIndex++;
                    continue; // 画布上已有等价自动断言（上次展开插入）→ 不重复插入
                }
                result.add(autoAssertCard(bounds));
                continue;
            }
            leading = false;
            LStatement statement = statementFor(line);
            if(statement != null) result.add(statement);
        }
        return result;
    }

    /** 一条指令行 → 原版画布语句；未知 RawLine 返回 null（编辑期防御，不崩溃）。
     *  未知行必须让调用方放弃展开（{@link #hasUnmappableLine}），否则卡片会被删掉却没有替代积木。 */
    private static LStatement statementFor(ExprCompiler.Line line){
        if(line instanceof ExprCompiler.CopyLine copy){
            // 值拷贝（v5 起 x = 0 / x = a 的唯一产物）：必须映射成原版 set 卡。
            // CopyLine extends RawLine，放在 RawLine 分支之前才不会被当成未知行丢弃。
            SetStatement st = new SetStatement();
            st.to = copy.dest;
            st.from = copy.src;
            return st;
        }
        if(line instanceof ExprCompiler.SensorLine sensor){
            SensorStatement st = new SensorStatement();
            st.to = sensor.dest;
            st.from = sensor.a;
            st.type = sensor.b;
            return st;
        }
        if(line instanceof ExprCompiler.CallLine call){
            // 函数调用展开为 funccall 语句（result 绑定临时变量），
            // 编译管线（analyze/expandCall）对 funccall 已有完整支持
            FuncCallStatement st = new FuncCallStatement();
            st.name = call.name;
            st.args = call.args;
            st.result = call.dest;
            return st;
        }
        if(line instanceof ExprCompiler.ReadLine read){
            // 数组下标读展开为原版 read 卡（read <output> <target> <address>），
            // 保存的文本是纯原版指令
            ReadStatement st = new ReadStatement();
            st.output = read.dest;
            st.target = read.a;
            st.address = read.b;
            return st;
        }
        if(line instanceof ExprCompiler.WriteLine write){
            // 下标赋值展开为原版 write 卡（write <input> <target> <address>）
            WriteStatement st = new WriteStatement();
            st.input = write.value;
            st.target = write.memory;
            st.address = write.address;
            return st;
        }
        if(line instanceof ExprCompiler.RawLine){
            // 未知原始行（不是断言）：调用方按 hasUnmappableLine() 保留卡片，绝不静默丢弃
            return null;
        }
        ExprCompiler.OpLine op = (ExprCompiler.OpLine)line;
        OperationStatement st = new OperationStatement();
        st.op = LogicOp.valueOf(op.op);
        st.dest = op.dest;
        st.a = op.a;
        st.b = op.b;
        return st;
    }

    /** 断言行 → 自动断言卡（消息加固定前缀；枚举解析失败时退回默认值，绝不抛错）。 */
    private static AssertBoundsCard autoAssertCard(ExprCompiler.AssertBoundsLine line){
        AssertBoundsCard card = new AssertBoundsCard();
        card.type = parseAssertionType(line.type);
        card.multiple = optionalValue(line.multiple);
        card.min = optionalValue(line.min);
        card.opMin = parseBoundsOp(line.opMin);
        card.value = optionalValue(line.value);
        card.opMax = parseBoundsOp(line.opMax);
        card.max = optionalValue(line.max);
        card.message = autoMessage(line.message);
        return card;
    }

    private static AssertionType parseAssertionType(String token){
        try{
            return AssertionType.valueOf(token);
        }catch(Exception e){
            return AssertionType.integer;
        }
    }

    /** Bounds operators are the game's {@link ConditionOp} (the card stores that type), and
     *  only the two inequality forms are legal in a saved {@code assertBounds} line. */
    private static ConditionOp parseBoundsOp(String token){
        try{
            ConditionOp op = ConditionOp.valueOf(token);
            return op == ConditionOp.lessThan || op == ConditionOp.lessThanEq ? op : ConditionOp.lessThanEq;
        }catch(Exception e){
            return ConditionOp.lessThanEq;
        }
    }

    /** 自动断言的消息：在引号内加固定前缀（write/read 往返后仍可识别）。 */
    private static String autoMessage(String message){
        if(message == null || message.isEmpty()) return "\"" + AUTO_ASSERT_PREFIX.trim() + "\"";
        if(message.startsWith("\"")) return "\"" + AUTO_ASSERT_PREFIX + message.substring(1);
        return AUTO_ASSERT_PREFIX + message;
    }

    /** 卡片是否是本钩子自动插入的越界断言卡（消息带 {@link #AUTO_ASSERT_PREFIX}）。 */
    public static boolean isAutoAssert(LStatement statement){
        return statement instanceof AssertBoundsCard card
            && card.message != null
            && card.message.startsWith("\"" + AUTO_ASSERT_PREFIX);
    }

    /** 列表尾部连续的自动断言卡（画布顺序）。 */
    public static List<LStatement> trailingAutoAsserts(List<LStatement> statements){
        if(statements == null || statements.isEmpty()) return Collections.emptyList();
        int from = statements.size();
        while(from > 0 && isAutoAssert(statements.get(from - 1))) from--;
        return new ArrayList<>(statements.subList(from, statements.size()));
    }

    /** 画布上的自动断言卡与链首断言行是否等价（字段逐项比较，空槽 "~" 归一化）。 */
    private static boolean equivalentAssert(LStatement statement, ExprCompiler.AssertBoundsLine line){
        if(!(statement instanceof AssertBoundsCard card) || !isAutoAssert(card)) return false;
        return card.type.name().equals(line.type)
            && sameOptional(card.multiple, line.multiple)
            && sameOptional(card.min, line.min)
            && card.opMin.name().equals(line.opMin)
            && sameOptional(card.value, line.value)
            && card.opMax.name().equals(line.opMax)
            && sameOptional(card.max, line.max)
            && card.message.equals(autoMessage(line.message));
    }

    /** 空槽归一化比较：null/""/"~" 视为同一个"无值"槽（write/read 往返会丢掉 "~"）。 */
    private static boolean sameOptional(String a, String b){
        return optionalValue(a).equals(optionalValue(b));
    }

    private static String optionalValue(String value){
        return value == null || value.isEmpty() || value.equals("~") ? "" : value;
    }

    // ===== 目标索引调整 =====

    /**
     * Shifts jump/block targets after a statement range is replaced. Structured blocks use
     * the same index model as jumps, so both must be updated together.
     */
    public static void adjustStatementIndex(LStatement statement, int threshold, int delta){
        if(delta == 0) return;
        if(statement instanceof JumpStatement jump && jump.destIndex > threshold){
            jump.destIndex += delta;
        }else if(statement instanceof BeginStatement begin && begin.destIndex > threshold){
            begin.destIndex += delta;
        }
    }

    /** 检查是否有 JumpStatement 的 destIndex 落在 [lo, hi] 范围内 */
    private static boolean hasJumpInRange(LCanvas canvas, int lo, int hi){
        if(lo > hi) return false;
        Seq<Element> children = canvas.statements.getChildren();
        for(Element child : children){
            if(!(child instanceof StatementElem)) continue;
            StatementElem elem = (StatementElem)child;
            if(elem.st instanceof JumpStatement){
                JumpStatement jump = (JumpStatement)elem.st;
                if(jump.destIndex >= lo && jump.destIndex <= hi){
                    return true;
                }
            }
        }
        return false;
    }

    /** 链外读取检查（语句列表口径，画布遍历与自测共用；null 表示非语句元素）。
     *  链内临时变量被链外语句引用时折叠会删除这些变量、值也会变，必须放弃折叠。
     *
     *  <p>只有“读”才算数：一条语句自己写下的那个名字是<b>定义</b>。旧实现按“文本里出现过”
     *  判定，于是两条完全相同的表达式链（复制粘贴出来的第二份）会互相把对方的定义当成外部读取，
     *  两条链都折不回来——卡片在保存后永久退化成裸 op 积木（2026-10 报告：「复制 Expr 积木后，
     *  马上转为了编译后形态」）。现在的口径：某条语句里除了它自己定义的那一次出现，多出来的
     *  都算读；而读之前已有链外定义时（例如另一条链的第一行 {@code op rand _0 10}），读到的
     *  不是被折叠链的值，不算外部读取。</p>
     *
     *  <p><b>表达式卡按源码扫，不按展开文本扫</b>：卡片的展开行（{@code write()} 写出的那串
     *  {@code op}/{@code read}）是它自己的内部实现，那些临时变量是卡片自己的 scratch，与外层
     *  链的同名临时变量无关。按展开文本扫会把这一层混进去：先折回的那张卡（它现在就是一条
     *  {@code ExprStatement}）会把后面所有用同名 {@code _0/_1…} 的链全部判成“链外读取”，
     *  第二张卡永远折不回来。卡片与外界的接口只有源码里的目标变量（定义）和表达式里出现的
     *  名字（读），因此这里扫 {@code expr} 并拿 {@code dest} 当定义——用户真在表达式里写了
     *  {@code _0} 时依旧是“读”，保守方向不变。</p> */
    public static boolean hasExternalReads(List<LStatement> statements, int chainStart, int chainEnd,
                                           List<ExprCompiler.Line> ops){
        Set<String> temps = new HashSet<>();
        for(int k = 0; k < ops.size() - 1; k++){ // 链内被后续 op 消费的临时变量
            String dest = ExprCompiler.lineDest(ops.get(k));
            if(dest != null) temps.add(dest);
        }
        // span 展开的固定 scratch（__ls_span_q/r/b）不是“值”：任何一次展开都会在它的
        // read/write 之前立刻重写它们，所以链外出现同名文本只能说明那是另一次展开自己的
        // 副本（画布上两张 span 表达式卡就是这种情况），删掉本链不会改变它的行为。
        // 不排除的话两张卡会互相把对方判成“外部读取”，谁都折不回来。
        temps.removeAll(SpanAccess.scratchNames());
        if(temps.isEmpty()) return false;

        // 链外语句已经定义过的临时变量：此后读到的不是被折叠链的值。
        Set<String> defined = new HashSet<>();
        for(int idx = 0; idx < statements.size(); idx++){
            if(idx >= chainStart && idx < chainEnd) continue;
            LStatement st = statements.get(idx);
            if(st == null) continue;
            boolean card = st instanceof ExprStatement;
            StringBuilder text = new StringBuilder();
            if(card){
                ExprStatement exprCard = (ExprStatement)st;
                if(exprCard.expr != null) text.append(exprCard.expr);
            }else{
                st.write(text);
            }
            String writes = card ? ((ExprStatement)st).dest : definesVariable(st);
            // 这条语句自己写下的变量是定义（卡片的目标、op/read/funccall/set 的目标）：
            // 此后读到的不是被折叠链的值。
            if(writes != null && temps.contains(writes)) defined.add(writes);
            for(String temp : temps){
                int occurrences = countIdentifier(text, temp);
                if(occurrences == 0) continue;
                // 恰好一次且就是这条语句写入的变量 -> 那是定义，不是读取
                if(occurrences == 1 && temp.equals(writes)){
                    defined.add(temp);
                    continue;
                }
                if(!defined.contains(temp)) return true;
            }
        }
        return false;
    }

    /** 语句写进的变量名；没认出来的定义一律按读取处理（宁可少折叠，不可折错）。 */
    private static String definesVariable(LStatement statement){
        if(statement instanceof OperationStatement op) return op.dest;
        if(statement instanceof SensorStatement sensor) return sensor.to;
        if(statement instanceof ReadStatement read) return read.output;
        if(statement instanceof FuncCallStatement call) return call.result;
        if(statement instanceof SetStatement set) return set.to;
        if(statement instanceof SelectStatement select) return select.result;
        return null;
    }

    /** 字符串里独立成词的标识符出现次数（避免 "_1" 误匹配 "_10"）。 */
    private static int countIdentifier(StringBuilder text, String identifier){
        int count = 0;
        int from = 0;
        while(true){
            int at = text.indexOf(identifier, from);
            if(at < 0) return count;
            boolean boundaryBefore = at == 0 || !isIdentifierChar(text.charAt(at - 1));
            int after = at + identifier.length();
            boolean boundaryAfter = after == text.length() || !isIdentifierChar(text.charAt(after));
            if(boundaryBefore && boundaryAfter) count++;
            from = at + 1;
        }
    }

    /** 字符串是否包含独立成词的标识符（避免 "_1" 误匹配 "_10"）。 */
    private static boolean containsIdentifier(StringBuilder text, String identifier){
        return countIdentifier(text, identifier) > 0;
    }

    private static boolean isIdentifierChar(char c){
        return Character.isLetterOrDigit(c) || c == '_';
    }

    // ===== 工具方法 =====

    private static void saveUIAll(LCanvas canvas){
        for(Element child : canvas.statements.getChildren()){
            if(child instanceof StatementElem){
                SugarCanvas.normalizeJumpUI(((StatementElem)child).st);
            }
        }
    }

    private static void setupUIAll(LCanvas canvas){
        for(Element child : canvas.statements.getChildren()){
            if(child instanceof StatementElem){
                ((StatementElem)child).st.setupUI();
            }
        }
    }
}
