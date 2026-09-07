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
import mindustry.logic.SugarStatements.BeginStatement;
import mindustry.logic.SugarStatements.FuncCallStatement;

import java.util.*;

/**
 * 表达式集成钩子：提供 op 链 ↔ 表达式的双向转换。
 *
 * 集成后：
 * - foldAll() 由 LogicCanvas.load() 直接调用，零延迟
 * - save() 由 LogicCanvas.save() 调用：unfoldAll → super.save → foldAll
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
        int i = 0;
        while(i < children.size){
            if(!(children.get(i) instanceof StatementElem) ||
               !isChainLine(((StatementElem)children.get(i)).st)){
                i++;
                continue;
            }

            List<ExprCompiler.Line> ops = new ArrayList<>();
            int j = i;
            while(j < children.size){
                if(!(children.get(j) instanceof StatementElem)) break;
                StatementElem elem = (StatementElem)children.get(j);
                LStatement st = elem.st;
                if(st instanceof OperationStatement opStmt){
                    ops.add(new ExprCompiler.OpLine(
                        opStmt.op.name(), opStmt.dest, opStmt.a, opStmt.b));
                    if(!ExprCompiler.isTemp(opStmt.dest)){
                        j++;
                        break;
                    }
                }else if(st instanceof SensorStatement sensor){
                    // sensor 语句也可入链：sensor _0 unit @health + op mul x _0 2
                    // → unit.health * 2。仅折叠 type 为 @LAccess 常量的 sensor
                    // （变量 type 是动态属性传感，语义上不等价于成员访问）。
                    if(!sensor.type.startsWith("@") || ExprCompiler.resolveMember(sensor.type) == null){
                        break;
                    }
                    ops.add(new ExprCompiler.SensorLine(sensor.to, sensor.from, sensor.type));
                    if(!ExprCompiler.isTemp(sensor.to)){
                        j++;
                        break;
                    }
                }else if(st instanceof FuncCallStatement call && isFoldableCall(call)){
                    // funccall 入链：call foo(a) _1 + op mul x _1 2 → foo(a) * 2
                    // 仅折叠实参为纯值（temp/变量/数字）的调用——带嵌套表达式的实参
                    // 无法无损重建（实参文本需要重新解析），保持原样积木。
                    ops.add(new ExprCompiler.CallLine(call.name, call.args, call.result));
                    if(!ExprCompiler.isTemp(call.result)){
                        j++;
                        break;
                    }
                }else if(st instanceof ReadStatement read && isArrayMemory(read.target)){
                    // 注册表命中的 read 行入链（数组下标读）：read _0 cell1 i 参与折叠，
                    // 经 opToNode 折回 buf[i]。用户手写的普通 read（memory 未命中注册表）
                    // 不受影响；read 的 dest 非 temp 时它是链的最后一行。
                    ops.add(new ExprCompiler.ReadLine(read.output, read.target, read.address));
                    if(!ExprCompiler.isTemp(read.output)){
                        j++;
                        break;
                    }
                }else if(st instanceof WriteStatement write && isArrayMemory(write.target)){
                    // 注册表命中的 write 行：下标赋值的终结行（没有 dest），链到此为止。
                    // 整条链（含地址计算 op add）交给 rebuildAssignment 折回 buf[i] = value。
                    ops.add(new ExprCompiler.WriteLine(write.input, write.target, write.address));
                    j++;
                    break;
                }else{
                    break;
                }
                j++;
            }

            int chainLen = j - i;
            // 链首是注册表命中的 read/write 时单行也尝试折叠（unfold 后 x = buf[3]、
            // buf[2] = 5 各只有一行，fold 必须能还原，否则表达式卡保存一次就永久丢失）；
            // 普通 op 链保持 >= 2 的既有门槛。
            boolean arrayEdge = ops.get(0) instanceof ExprCompiler.ReadLine
                || ops.get(0) instanceof ExprCompiler.WriteLine;
            if(chainLen >= 2 || (chainLen == 1 && arrayEdge)){
                // 安全检查：若有 jump 指向链中间 [i+1, i+chainLen-1]，放弃折叠。
                // 场景：别人没装插件时写的 jump 指向 op 链中间，折叠会改变语义。
                // 指向链首 i 是允许的，折叠后仍指向 expr 积木。
                // 链内临时变量被链外语句读取时同样放弃（折叠会删除这些变量，值也会变）。
                if(hasJumpInRange(canvas, i + 1, i + chainLen - 1) || hasExternalReads(children, i, j, ops)){
                    i = j; // 跳过整条链，不折叠
                    continue;
                }
                String expr = null;
                String dest = null;
                ExprCompiler.Line last = ops.get(ops.size() - 1);
                if(last instanceof ExprCompiler.WriteLine){
                    // 下标赋值链：write <value> <memory> <address> 结尾 → dest=buf[i], expr=value
                    String[] pair = ExprCompiler.rebuildAssignment(ops);
                    if(pair != null){
                        dest = pair[0];
                        expr = pair[1];
                    }
                }else{
                    expr = ExprCompiler.rebuild(ops);
                    if(expr != null){
                        dest = ExprCompiler.lineDest(last);
                    }
                }
                if(expr != null){
                    ExprStatement exprStmt = new ExprStatement();
                    exprStmt.dest = dest == null ? "result" : dest;
                    exprStmt.expr = expr;
                    exprStmt.lastOps = ops;

                    for(int k = 0; k < chainLen; k++){
                        ((StatementElem)children.get(i)).remove();
                    }

                    canvas.addAt(i, exprStmt);

                    changed = true;
                }else{
                    // rebuild 失败（如链不完整），跳过整条链，
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

    // ===== 展开：ExprStatement → op 链 =====

    /** 语句能否作为表达式链的节点：op 语句、type 为 @LAccess 常量的 sensor 语句、
     *  实参为纯值的 funccall 语句、memory 命中数组注册表的 read/write 语句（链首）。 */
    private static boolean isChainLine(LStatement st){
        if(st instanceof OperationStatement) return true;
        if(st instanceof SensorStatement sensor){
            return sensor.type.startsWith("@") && ExprCompiler.resolveMember(sensor.type) != null;
        }
        if(st instanceof FuncCallStatement call) return isFoldableCall(call);
        // read/write 行只有在注册表把 memory 解析到已声明数组时才入链：
        // 用户手写的普通 read/write 与纯原版 mlog（无声明卡）不受影响
        if(st instanceof ReadStatement read) return isArrayMemory(read.target);
        if(st instanceof WriteStatement write) return isArrayMemory(write.target);
        return false;
    }

    /** memory 变量名是否承载了当前注册表中的数组（宽松口径画布注册表）。 */
    private static boolean isArrayMemory(String memory){
        if(memory == null || memory.isEmpty()) return false;
        ArrayRegistry registry = ArrayRegistry.active();
        return registry != null && !registry.isEmpty() && !registry.byMemory(memory).isEmpty();
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

        boolean changed = false;
        for(int i = 0; i < children.size; i++){
            if(!(children.get(i) instanceof StatementElem)) continue;
            StatementElem elem = (StatementElem)children.get(i);
            if(!(elem.st instanceof ExprStatement)) continue;

            ExprStatement exprStmt = (ExprStatement)elem.st;

            List<ExprCompiler.Line> ops;
            try{
                // 与 ExprStatement.write()/SugarLogicDialog 预检同口径：使用 functionChecker
                // 校验函数名，否则未定义函数会被展开成 will-fail 的 funccall（编译时才报错），
                // 与编辑期标红、保存拦截的行为不一致。
                ops = ExprCompiler.compile(exprStmt.dest, exprStmt.expr, ExprStatement.functionChecker());
            }catch(Exception e){
                // 编译失败：保留 ExprStatement 不展开，write() 会输出 lastOps
                // 避免 unfold→fold 循环用 lastOps 重建 ExprStatement 覆盖错误的 expr
                continue;
            }

            int chainLen = ops.size();

            elem.remove();

            for(int k = 0; k < chainLen; k++){
                ExprCompiler.Line line = ops.get(k);
                if(line instanceof ExprCompiler.SensorLine sensor){
                    SensorStatement st = new SensorStatement();
                    st.to = sensor.dest;
                    st.from = sensor.a;
                    st.type = sensor.b;
                    canvas.addAt(i + k, st);
                }else if(line instanceof ExprCompiler.CallLine call){
                    // 函数调用展开为 funccall 语句（result 绑定临时变量），
                    // 编译管线（analyze/expandCall）对 funccall 已有完整支持
                    FuncCallStatement st = new FuncCallStatement();
                    st.name = call.name;
                    st.args = call.args;
                    st.result = call.dest;
                    canvas.addAt(i + k, st);
                }else if(line instanceof ExprCompiler.ReadLine read){
                    // 数组下标读展开为原版 read 卡（read <output> <target> <address>），
                    // 保存的文本是纯原版指令
                    ReadStatement st = new ReadStatement();
                    st.output = read.dest;
                    st.target = read.a;
                    st.address = read.b;
                    canvas.addAt(i + k, st);
                }else if(line instanceof ExprCompiler.WriteLine write){
                    // 下标赋值展开为原版 write 卡（write <input> <target> <address>）
                    WriteStatement st = new WriteStatement();
                    st.input = write.value;
                    st.target = write.memory;
                    st.address = write.address;
                    canvas.addAt(i + k, st);
                }else{
                    ExprCompiler.OpLine op = (ExprCompiler.OpLine)line;
                    OperationStatement st = new OperationStatement();
                    st.op = LogicOp.valueOf(op.op);
                    st.dest = op.dest;
                    st.a = op.a;
                    st.b = op.b;
                    canvas.addAt(i + k, st);
                }
            }

            changed = true;
            i += chainLen - 1;
        }

        if(changed){
            // See foldAll(): the destination elements have already moved with the layout.
            saveUIAll(canvas);
            setupUIAll(canvas);
            SugarCanvas.markJumpHeightsDirty(canvas);
            Log.debug("[LogicAssist] Expression statements unfolded");
        }
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

    /** 检查链外语句是否读取了链内临时变量（折叠会删除这些临时变量）。
     *  保守实现：用序列化文本做标识符边界匹配，宁可少折叠也不改变语义。 */
    private static boolean hasExternalReads(Seq<Element> children, int chainStart, int chainEnd, List<ExprCompiler.Line> ops){
        Set<String> temps = new HashSet<>();
        for(int k = 0; k < ops.size() - 1; k++){ // 链内被后续 op 消费的临时变量
            String dest = ExprCompiler.lineDest(ops.get(k));
            if(dest != null) temps.add(dest);
        }
        if(temps.isEmpty()) return false;
        for(int idx = 0; idx < children.size; idx++){
            if(idx >= chainStart && idx < chainEnd) continue;
            if(!(children.get(idx) instanceof StatementElem)) continue;
            LStatement st = ((StatementElem)children.get(idx)).st;
            StringBuilder text = new StringBuilder();
            st.write(text);
            for(String temp : temps){
                if(containsIdentifier(text, temp)) return true;
            }
        }
        return false;
    }

    /** 字符串是否包含独立成词的标识符（避免 "_1" 误匹配 "_10"）。 */
    private static boolean containsIdentifier(StringBuilder text, String identifier){
        int from = 0;
        while(true){
            int at = text.indexOf(identifier, from);
            if(at < 0) return false;
            boolean boundaryBefore = at == 0 || !isIdentifierChar(text.charAt(at - 1));
            int after = at + identifier.length();
            boolean boundaryAfter = after == text.length() || !isIdentifierChar(text.charAt(after));
            if(boundaryBefore && boundaryAfter) return true;
            from = at + 1;
        }
    }

    private static boolean isIdentifierChar(char c){
        return Character.isLetterOrDigit(c) || c == '_';
    }

    // ===== 工具方法 =====

    private static void saveUIAll(LCanvas canvas){
        for(Element child : canvas.statements.getChildren()){
            if(child instanceof StatementElem){
                ((StatementElem)child).st.saveUI();
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
