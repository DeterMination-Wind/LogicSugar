package logicsugar.assist.data;

import arc.struct.Seq;
import logicsugar.assist.expr.ContainerIntrinsics;
import logicsugar.assist.expr.ExprCompiler;
import mindustry.Vars;
import mindustry.logic.GlobalVars;
import mindustry.logic.LAssembler;
import mindustry.logic.LStatement;
import mindustry.logic.SugarCompiler;
import mindustry.logic.SugarFunctions;
import mindustry.logic.SugarStatements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 栈 + 队列自测（{@link ContainerModule} + {@link ContainerIntrinsics}）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li><b>精确指令序列</b>：spop/speek/ssize/sclear、qpop/qpeek/qsize/qclear 的逐行展开，
 *       spush/qpush 的 funccall 展开 + 注入函数体文本，含非零 base 的地址重定向；</li>
 *   <li><b>行为</b>：用一个小型 op/read/write/jump 解释器执行展开链（mlog 语义：越界读 = NaN、
 *       越界写 = 空操作、NaN/null 读取为 0；push 的注入函数体逐行解释），验证
 *       push/pop/peek/size/clear、满时数据不变、空时 NaN、队列 FIFO 与 head 回绕、tail 不变式；</li>
 *   <li><b>声明校验</b>：名字/内存/字面量/重复/与 array 或函数重名/容量/区间重叠的编译期报错；</li>
 *   <li><b>声明卡不产指令</b> + 载体往返（restore/verifyRestore/matchesStoredStream）；</li>
 *   <li><b>产物逐行纯原版</b>（strip 模式，白名单 opcode）+ 注入函数共享与未使用不入产物。</li>
 * </ul>
 */
public class ContainerTest{
    public static void main(String[] args){
        SugarStatements.installParsers();
        Vars.logicVars = new GlobalVars();
        DataModules.clearModules();
        DataModules.register(new ContainerModule());
        DataModules.registerParsers();
        loadBuiltins();

        stackSequences();
        queueSequences();
        builtinBodies();
        stackBehaviour();
        queueBehaviourAndWraparound();
        builtinInjectionAndSharing();
        unusedBuiltinStaysOut();
        declarationErrors();
        editorMarkInvalid();
        declarationCardProducesNoLine();
        outputIsPureVanilla();
        roundTripAndVerification();

        DataModules.clearModules();
        System.out.println("LogicSugar Container self-test passed.");
    }

    // ===== 精确指令序列 =====

    private static void stackSequences(){
        withRegistry("stack s cell1 0 8", () -> {
            checkLine("op add x __ls_stk_s_top 0", textOf(ExprCompiler.compile("x", "ssize(s)")));
            checkLine("op add __ls_stk_s_top 0 0\nop add x 0 0",
                textOf(ExprCompiler.compile("x", "sclear(s)")));
            checkLine("op sub _0 __ls_stk_s_top 1\n"
                + "op add _1 0 _0\n"
                + "op lessThanEq _2 __ls_stk_s_top 0\n"
                + "op mul _2 _2 0\n"
                + "op sub _1 _1 _2\n"
                + "read x cell1 _1",
                textOf(ExprCompiler.compile("x", "speek(s)")));
            checkLine("op sub _0 __ls_stk_s_top 1\n"
                + "op add _1 0 _0\n"
                + "op lessThanEq _2 __ls_stk_s_top 0\n"
                + "op mul _2 _2 0\n"
                + "op sub _1 _1 _2\n"
                + "op sub __ls_stk_s_top __ls_stk_s_top 1\n"
                + "op max __ls_stk_s_top __ls_stk_s_top 0\n"
                + "read x cell1 _1",
                textOf(ExprCompiler.compile("x", "spop(s)")));
            checkLine("funccall __ls_builtin_stkpush \"cell1, 0, 8, __ls_stk_s_top, 5\" __ls_stk_s_top\n"
                + "op add x __ls_stk_s_top 0",
                textOf(ExprCompiler.compile("x", "spush(s, 5)")));
            // 实参是表达式：先编译值（追加到链首），再发 funccall
            checkLine("op add _0 i 1\n"
                + "funccall __ls_builtin_stkpush \"cell1, 0, 8, __ls_stk_s_top, _0\" __ls_stk_s_top\n"
                + "op add x __ls_stk_s_top 0",
                textOf(ExprCompiler.compile("x", "spush(s, i + 1)")));
        });
        // 非零 base：空栈读地址 = base - base = -1（越界 → NaN）
        withRegistry("stack s bank1 10 4", () -> {
            checkLine("op sub _0 __ls_stk_s_top 1\n"
                + "op add _1 10 _0\n"
                + "op lessThanEq _2 __ls_stk_s_top 0\n"
                + "op mul _2 _2 10\n"
                + "op sub _1 _1 _2\n"
                + "read x bank1 _1",
                textOf(ExprCompiler.compile("x", "speek(s)")));
            checkLine("funccall __ls_builtin_stkpush \"bank1, 10, 4, __ls_stk_s_top, v\" __ls_stk_s_top\n"
                + "op add x __ls_stk_s_top 0",
                textOf(ExprCompiler.compile("x", "spush(s, v)")));
        });
    }

    private static void queueSequences(){
        withRegistry("queue q cell2 0 4", () -> {
            checkLine("op add x __ls_que_q_count 0", textOf(ExprCompiler.compile("x", "qsize(q)")));
            checkLine("op add __ls_que_q_head 0 0\n"
                + "op add __ls_que_q_tail 0 0\n"
                + "op add __ls_que_q_count 0 0\n"
                + "op add x 0 0",
                textOf(ExprCompiler.compile("x", "qclear(q)")));
            checkLine("op lessThanEq _0 __ls_que_q_count 0\n"
                + "op add _1 0 __ls_que_q_head\n"
                + "op add _2 _1 1\n"
                + "op mul _0 _0 _2\n"
                + "op sub _1 _1 _0\n"
                + "read x cell2 _1",
                textOf(ExprCompiler.compile("x", "qpeek(q)")));
            checkLine("op lessThanEq _0 __ls_que_q_count 0\n"
                + "op add _1 0 __ls_que_q_head\n"
                + "op add _2 _1 1\n"
                + "op mul _0 _0 _2\n"
                + "op sub _1 _1 _0\n"
                + "op min _3 __ls_que_q_count 1\n"
                + "op add _4 __ls_que_q_head _3\n"
                + "op mod __ls_que_q_head _4 4\n"
                + "op sub __ls_que_q_count __ls_que_q_count 1\n"
                + "op max __ls_que_q_count __ls_que_q_count 0\n"
                + "read x cell2 _1",
                textOf(ExprCompiler.compile("x", "qpop(q)")));
            checkLine("funccall __ls_builtin_quepush \"cell2, 0, 4, __ls_que_q_head, __ls_que_q_count, 7\" __ls_que_q_count\n"
                + "op add __ls_que_q_tail __ls_que_q_head __ls_que_q_count\n"
                + "op mod __ls_que_q_tail __ls_que_q_tail 4\n"
                + "op add x __ls_que_q_count 0",
                textOf(ExprCompiler.compile("x", "qpush(q, 7)")));
        });
        // 非零 base：地址 = base + pos，push 的 write 在函数体里
        withRegistry("queue q bank1 20 2", () -> {
            checkLine("funccall __ls_builtin_quepush \"bank1, 20, 2, __ls_que_q_head, __ls_que_q_count, 9\" __ls_que_q_count\n"
                + "op add __ls_que_q_tail __ls_que_q_head __ls_que_q_count\n"
                + "op mod __ls_que_q_tail __ls_que_q_tail 2\n"
                + "op add x __ls_que_q_count 0",
                textOf(ExprCompiler.compile("x", "qpush(q, 9)")));
        });
    }

    private static void builtinBodies(){
        List<String> bodies = ContainerIntrinsics.builtinSugar();
        check(bodies.size() == 2, "expected two injected push bodies");
        checkLine("funcdef __ls_builtin_stkpush mem,base,size,top,v 8\n"
            + "jump 6 greaterThanEq top size\n"
            + "op add __ls_ct_a base top\n"
            + "write v mem __ls_ct_a\n"
            + "op add __ls_ct_r top 1\n"
            + "jump 7 always x false\n"
            + "op add __ls_ct_r size 0\n"
            + "return \"__ls_ct_r\"\n"
            + "blockend\n", bodies.get(0));
        checkLine("funcdef __ls_builtin_quepush mem,base,size,head,count,v 10\n"
            + "jump 8 greaterThanEq count size\n"
            + "op add __ls_ct_p head count\n"
            + "op mod __ls_ct_p __ls_ct_p size\n"
            + "op add __ls_ct_a base __ls_ct_p\n"
            + "write v mem __ls_ct_a\n"
            + "op add __ls_ct_r count 1\n"
            + "jump 9 always x false\n"
            + "op add __ls_ct_r size 0\n"
            + "return \"__ls_ct_r\"\n"
            + "blockend\n", bodies.get(1));
        // 编辑器可见性：注入函数参数表与名字集合
        check(SugarFunctions.paramsOf(ContainerIntrinsics.BUILTIN_STACK_PUSH, null) != null
            && SugarFunctions.paramsOf(ContainerIntrinsics.BUILTIN_STACK_PUSH, null)
                .equals(Arrays.asList("mem", "base", "size", "top", "v")),
            "stack push params are not visible to paramsOf");
        check(DataModules.builtinFunctionNames().contains(ContainerIntrinsics.BUILTIN_QUEUE_PUSH),
            "queue push builtin name is not exposed");
    }

    // ===== 行为（解释执行展开链） =====

    private static void stackBehaviour(){
        withRegistry("stack s cell1 0 4", () -> {
            double[] memory = new double[64];
            Map<String, Double> vars = new HashMap<>();

            // 空栈：size = 0、peek/pop = NaN，pop 后 top 仍为 0
            exec("ssize(s)", vars, memory);
            check(num(vars, "x") == 0, "empty stack size must be 0");
            exec("speek(s)", vars, memory);
            check(Double.isNaN(raw(vars, "x")), "empty peek must return NaN");
            exec("spop(s)", vars, memory);
            check(Double.isNaN(raw(vars, "x")), "empty pop must return NaN");
            check(num(vars, "__ls_stk_s_top") == 0, "empty pop must keep top at 0");

            // push 11/22/33 → top = 3，槽位 0..2
            exec("spush(s, 11)", vars, memory);
            check(num(vars, "x") == 1, "first push must return 1");
            exec("spush(s, 22)", vars, memory);
            exec("spush(s, 33)", vars, memory);
            check(num(vars, "x") == 3, "third push must return 3");
            check(memory[0] == 11 && memory[1] == 22 && memory[2] == 33, "stack slots wrong: " + Arrays.toString(memory));

            // peek 不改状态
            exec("speek(s)", vars, memory);
            check(num(vars, "x") == 33, "peek must return the top element");
            check(num(vars, "__ls_stk_s_top") == 3, "peek must not change top");

            // 满栈 push：不写入、返回 size、top 不变
            exec("spush(s, 44)", vars, memory);
            check(num(vars, "x") == 4, "push into a full stack must return size");
            exec("spush(s, 99)", vars, memory);
            check(num(vars, "x") == 4, "push into a full stack must still return size");
            check(memory[3] == 44, "full push must not overwrite the last element");
            check(num(vars, "__ls_stk_s_top") == 4, "full push must not change top");

            // LIFO 弹出
            exec("spop(s)", vars, memory);
            check(num(vars, "x") == 44, "pop order broken (44)");
            exec("spop(s)", vars, memory);
            check(num(vars, "x") == 33, "pop order broken (33)");
            exec("spop(s)", vars, memory);
            check(num(vars, "x") == 22, "pop order broken (22)");
            exec("spop(s)", vars, memory);
            check(num(vars, "x") == 11, "pop order broken (11)");
            exec("spop(s)", vars, memory);
            check(Double.isNaN(raw(vars, "x")) && num(vars, "__ls_stk_s_top") == 0, "over-pop must return NaN and stay empty");

            // clear 后仍可复用
            exec("spush(s, 7)", vars, memory);
            exec("sclear(s)", vars, memory);
            check(num(vars, "x") == 0 && num(vars, "__ls_stk_s_top") == 0, "clear must return 0 and reset top");
            exec("ssize(s)", vars, memory);
            check(num(vars, "x") == 0, "size after clear must be 0");
        });
    }

    private static void queueBehaviourAndWraparound(){
        withRegistry("queue q cell2 0 4", () -> {
            double[] memory = new double[64];
            Map<String, Double> vars = new HashMap<>();

            exec("qsize(q)", vars, memory);
            check(num(vars, "x") == 0, "empty queue size must be 0");
            exec("qpeek(q)", vars, memory);
            check(Double.isNaN(raw(vars, "x")), "empty qpeek must return NaN");
            exec("qpop(q)", vars, memory);
            check(Double.isNaN(raw(vars, "x")), "empty qpop must return NaN");
            check(num(vars, "__ls_que_q_count") == 0 && num(vars, "__ls_que_q_head") == 0,
                "empty qpop must keep head/count at 0");

            for(int v = 1; v <= 4; v++){
                exec("qpush(q, " + v + ")", vars, memory);
            }
            check(num(vars, "x") == 4, "queue must report 4 elements");
            check(memory[0] == 1 && memory[1] == 2 && memory[2] == 3 && memory[3] == 4,
                "queue slots wrong: " + Arrays.toString(memory));
            check(tailOk(vars, "q", 4), "tail invariant broken after pushes");

            // 满队列 push：不写入并返回 size
            exec("qpush(q, 99)", vars, memory);
            check(num(vars, "x") == 4, "push into a full queue must return size");
            check(memory[0] == 1 && memory[1] == 2 && memory[2] == 3 && memory[3] == 4,
                "full qpush must not modify memory: " + Arrays.toString(memory));
            check(tailOk(vars, "q", 4), "tail invariant broken after full push");

            // FIFO：pop 全部后 head 回到 0，队列空
            for(int v = 1; v <= 4; v++){
                exec("qpop(q)", vars, memory);
                check(num(vars, "x") == v, "FIFO order broken: expected " + v);
                check(tailOk(vars, "q", 4), "tail invariant broken after pops");
            }
            check(num(vars, "__ls_que_q_count") == 0, "queue must be empty after popping everything");
            check(num(vars, "__ls_que_q_head") == 0, "head must wrap back to 0");

            // 回绕：head 前进 1 格后继续 push/pop
            exec("qpush(q, 10)", vars, memory);
            exec("qpush(q, 11)", vars, memory);
            exec("qpush(q, 12)", vars, memory);
            exec("qpop(q)", vars, memory);
            check(num(vars, "x") == 10 && num(vars, "__ls_que_q_head") == 1, "head must advance by 1");
            exec("qpush(q, 13)", vars, memory);
            exec("qpush(q, 14)", vars, memory);
            check(num(vars, "__ls_que_q_head") == 1 && num(vars, "__ls_que_q_count") == 4,
                "wraparound state wrong (head/count)");
            check(num(vars, "__ls_que_q_tail") == 1, "tail must wrap to head when full");
            // 队列内容 [11, 12, 13, 14]，14 写进回绕槽位 0
            check(memory[0] == 14, "wrapped write must land at slot 0, got " + memory[0]);
            exec("qpeek(q)", vars, memory);
            check(num(vars, "x") == 11, "peek after wraparound must return the new head");
            int[] expected = {11, 12, 13, 14};
            for(int i = 0; i < expected.length; i++){
                exec("qpop(q)", vars, memory);
                check(num(vars, "x") == expected[i], "FIFO after wraparound broken at " + i);
            }
            check(num(vars, "__ls_que_q_count") == 0, "queue must be empty after the wrap sequence");

            // clear
            exec("qpush(q, 5)", vars, memory);
            exec("qclear(q)", vars, memory);
            check(num(vars, "x") == 0, "qclear must return 0");
            check(num(vars, "__ls_que_q_head") == 0 && num(vars, "__ls_que_q_tail") == 0
                && num(vars, "__ls_que_q_count") == 0, "qclear must reset all state");
        });
    }

    private static boolean tailOk(Map<String, Double> vars, String name, int size){
        long head = (long)num(vars, "__ls_que_" + name + "_head");
        long count = (long)num(vars, "__ls_que_" + name + "_count");
        long tail = (long)num(vars, "__ls_que_" + name + "_tail");
        return tail == Math.floorMod(head + count, (long)size);
    }

    private static void exec(String expr, Map<String, Double> vars, double[] memory){
        run(ExprCompiler.compile("x", expr), vars, memory);
    }

    /**
     * 极简 mlog 解释器：执行本模块展开会产生的 op/read/write/funccall 行。
     * 语义对齐原版：越界读 → NaN，越界写 → 空操作，null/NaN 参与运算按 0（{@code LVar.num()}）。
     * funccall 的注入函数体（{@link #builtins}）逐行解释，跳转目标按函数文本下标解析。
     */
    private static void run(List<ExprCompiler.Line> lines, Map<String, Double> vars, double[] memory){
        for(ExprCompiler.Line line : lines){
            if(line instanceof ExprCompiler.ReadLine read){
                int address = (int)num(vars, read.b);
                vars.put(read.dest, address >= 0 && address < memory.length ? memory[address] : Double.NaN);
            }else if(line instanceof ExprCompiler.WriteLine write){
                int address = (int)num(vars, write.address);
                if(address >= 0 && address < memory.length){
                    memory[address] = num(vars, write.value);
                }
            }else if(line instanceof ExprCompiler.CallLine call){
                runBuiltin(call, vars, memory);
            }else if(line instanceof ExprCompiler.OpLine op){
                vars.put(op.dest, apply(op.op, num(vars, op.a), num(vars, op.b)));
            }else{
                throw new AssertionError("unexpected line in a container expansion: " + line.toText());
            }
        }
    }

    /** 执行注入函数体（{@code __ls_builtin_stkpush}/{@code __ls_builtin_quepush}）。 */
    private static void runBuiltin(ExprCompiler.CallLine call, Map<String, Double> vars, double[] memory){
        Builtin builtin = builtins.get(call.name);
        check(builtin != null, "unknown injected function in a container expansion: " + call.name);
        Map<String, Double> local = new HashMap<>();
        String[] args = call.args.split(",");
        check(args.length == builtin.params.size(), "arity mismatch for " + call.name);
        for(int i = 0; i < args.length; i++){
            local.put(builtin.params.get(i), num(vars, args[i].trim()));
        }
        int pc = 0;
        while(pc < builtin.body.size()){
            String[] tokens = builtin.body.get(pc).split("\\s+");
            switch(tokens[0]){
                case "jump": {
                    if(condition(tokens[2], num(local, tokens[3]), num(local, tokens[4]))){
                        pc = Integer.parseInt(tokens[1]) - 1; // 文本下标 1 = body[0]
                        continue;
                    }
                    break;
                }
                case "op":
                    local.put(tokens[2], apply(tokens[1], num(local, tokens[3]), num(local, tokens[4])));
                    break;
                case "write": {
                    // write <value> <memory> <address>
                    int address = (int)num(local, tokens[3]);
                    if(address >= 0 && address < memory.length){
                        memory[address] = num(local, tokens[1]);
                    }
                    break;
                }
                case "return":
                    vars.put(call.dest, num(local, tokens[1].replace("\"", "")));
                    return;
                default:
                    throw new AssertionError("unexpected statement in a builtin body: " + builtin.body.get(pc));
            }
            pc++;
        }
        throw new AssertionError("builtin did not return: " + call.name);
    }

    private static double apply(String op, double a, double b){
        switch(op){
            case "add": return a + b;
            case "sub": return a - b;
            case "mul": return a * b;
            case "div": return a / b;
            case "min": return Math.min(a, b);
            case "max": return Math.max(a, b);
            case "mod": return a % b;
            case "lessThanEq": return a <= b ? 1 : 0;
            case "greaterThanEq": return a >= b ? 1 : 0;
            default: throw new AssertionError("unexpected op in a container expansion: " + op);
        }
    }

    private static boolean condition(String op, double a, double b){
        switch(op){
            case "always": return true;
            case "greaterThanEq": return a >= b;
            case "greaterThan": return a > b;
            case "lessThanEq": return a <= b;
            case "lessThan": return a < b;
            case "equal": return a == b;
            case "notEqual": return a != b;
            default: throw new AssertionError("unexpected jump condition: " + op);
        }
    }

    /** 操作数求值：变量优先，否则数字字面量；NaN/Inf 按原版 num() 视作 0。 */
    private static double num(Map<String, Double> vars, String operand){
        Double value = vars.get(operand);
        if(value == null){
            try{
                value = Double.parseDouble(operand);
            }catch(NumberFormatException e){
                return 0;
            }
        }
        return Double.isNaN(value) || Double.isInfinite(value) ? 0 : value;
    }

    /** 原始值（不做 NaN → 0 归一），用于断言「返回 NaN」。 */
    private static double raw(Map<String, Double> vars, String operand){
        Double value = vars.get(operand);
        if(value == null){
            try{
                return Double.parseDouble(operand);
            }catch(NumberFormatException e){
                return 0;
            }
        }
        return value;
    }

    private static final class Builtin{
        final List<String> params = new ArrayList<>();
        final List<String> body = new ArrayList<>();
    }

    private static final Map<String, Builtin> builtins = new LinkedHashMap<>();

    private static void loadBuiltins(){
        for(String text : ContainerIntrinsics.builtinSugar()){
            String[] lines = text.trim().split("\n");
            String[] header = lines[0].split("\\s+");
            Builtin builtin = new Builtin();
            for(String param : header[2].split(",")){
                builtin.params.add(param.trim());
            }
            // 去掉 funcdef 头与末尾 blockend
            for(int i = 1; i < lines.length - 1; i++){
                builtin.body.add(lines[i].trim());
            }
            builtins.put(header[1], builtin);
        }
    }

    // ===== 注入函数共享 / 未使用不入产物 =====

    private static void builtinInjectionAndSharing(){
        String sugar = "stack s cell1 0 4\n"
            + "ifbegin expr \"spush(s, 1) > 0\" 3\nset x 1\nblockend\n"
            + "ifbegin expr \"spush(s, 2) > 0\" 6\nset y 2\nblockend\n";
        String mlog = stripCompile(sugar);
        check(countOf(mlog, "__ls_func___ls_builtin_stkpush_entry:") == 1,
            "push body must be hoisted exactly once (shared subroutine):\n" + mlog);
        check(countOf(mlog, "jump __ls_func___ls_builtin_stkpush_entry always x false") == 2,
            "each push call site must jump to the shared entry:\n" + mlog);
        check(mlog.contains("set __ls_func___ls_builtin_stkpush_mem cell1"), "memory binding missing:\n" + mlog);
        check(mlog.contains("set __ls_func___ls_builtin_stkpush_base 0"), "base binding missing:\n" + mlog);
        check(mlog.contains("set __ls_func___ls_builtin_stkpush_size 4"), "size binding missing:\n" + mlog);
        check(mlog.contains("set __ls_func___ls_builtin_stkpush_top __ls_stk_s_top"), "top binding missing:\n" + mlog);
        check(mlog.contains("write __ls_func___ls_builtin_stkpush_v __ls_func___ls_builtin_stkpush_mem"),
            "push body write missing/mis-mangled:\n" + mlog);
        check(mlog.contains("set __ls_stk_s_top __ls_func___ls_builtin_stkpush_result"),
            "push result must be written back to the state variable:\n" + mlog);
        check(!mlog.contains("funccall ") && !mlog.contains("ifbegin ") && !mlog.contains("stack "),
            "sugar residue in the product:\n" + mlog);
    }

    private static void unusedBuiltinStaysOut(){
        String mlog = stripCompile("stack s cell1 0 4\nifbegin expr \"ssize(s) > 0\" 3\nset x 1\nblockend\n");
        check(!mlog.contains("__ls_builtin"), "unused push builtin leaked into the product:\n" + mlog);
        check(!mlog.contains("__ls_ct_"), "unused push body leaked into the product:\n" + mlog);
    }

    // ===== 声明校验 =====

    private static void declarationErrors(){
        checkRegistryThrows("stack s cell1 0 8\nstack s cell1 8 8", "duplicate stack name");
        checkRegistryThrows("stack s cell1 0 8\nqueue s cell1 8 8", "stack/queue name collision");
        checkRegistryThrows("queue q cell1 0 8\nqueue q cell1 8 8", "duplicate queue name");
        checkRegistryThrows("stack __ls_bad cell1 0 8", "reserved prefix");
        checkRegistryThrows("stack 1bad cell1 0 8", "invalid identifier");
        checkRegistryThrows("stack s cell1 ~ 8", "non-integer base");
        checkRegistryThrows("stack s cell1 -1 8", "negative base");
        checkRegistryThrows("stack s cell1 0 0", "size below 1");
        checkRegistryThrows("stack s cell1 0 abc", "non-integer size");
        checkRegistryThrows("stack s cell1 0 8\nqueue q cell1 4 8", "overlapping ranges on one memory block");
        checkRegistryThrows("stack s cell1 60 8", "cell capacity overflow");
        checkRegistryThrows("queue q bank1 510 4", "bank capacity overflow");
        checkReadThrows("stack s ~ 0 8", "missing memory cell");
        checkReadThrows("queue q ~ 0 8", "missing memory cell");

        // 与 array/matrix 重名：走完整编译（此时 ArrayRegistry 上下文才可见）
        checkCompileThrows("array s cell1 0 4\nstack s cell2 0 8\nset x 1\n", "conflict with an array name");
        checkCompileThrows("matrix m cell1 0 2 2\nqueue m cell2 0 8\nset x 1\n", "conflict with a matrix name");
        // 与函数重名
        checkCompileThrows("funcdef s a 3\nop add __ls_dummy a 1\nreturn \"__ls_dummy\"\nblockend\n"
            + "stack s cell1 0 8\nset x 1\n", "conflict with a function name");
        // 同内存块不同区间可以共存（同一注册表内不重叠即合法）
        withRegistry("stack s cell1 0 8\nqueue q cell1 8 8", () -> {
            check(ContainerModule.active().size() == 2, "two containers on one block must be accepted");
        });
        // 跨模块区间重叠是已知限制（契约 §7）：array 与 stack 重叠不报错
        String crossModule = "array a cell1 0 8\nstack s cell1 0 8\nset x 1\n";
        check(stripCompile(crossModule).contains("set x 1"), "cross-module overlap must not be rejected");
    }

    // ===== 编辑期标红 =====

    private static void editorMarkInvalid(){
        // 重复名字：第二张卡标红（第一张保持正常）
        List<LStatement> duplicate = listOf("stack s cell1 0 4\nstack s cell1 8 4\n");
        boolean[] invalid = new boolean[duplicate.size()];
        DataModules.markInvalid(duplicate, invalid, null);
        check(!invalid[0] && invalid[1], "duplicate declaration must be marked invalid in the editor");

        // 容量超限 / 与数组重名
        List<LStatement> overflow = listOf("stack s cell1 60 8\n");
        boolean[] overflowInvalid = new boolean[overflow.size()];
        DataModules.markInvalid(overflow, overflowInvalid, null);
        check(overflowInvalid[0], "capacity overflow must be marked invalid");

        List<LStatement> clash = listOf("array s cell1 0 4\nstack s cell2 0 4\n");
        boolean[] clashInvalid = new boolean[clash.size()];
        DataModules.markInvalid(clash, clashInvalid, null);
        check(clashInvalid[1], "container name clashing with an array must be marked invalid");

        // 合法声明 + 容器表达式在编辑期不标红（无画布时用显式注册表上下文模拟编辑器展开）
        Seq<LStatement> good = LAssembler.read(
            "stack s cell1 0 4\nifbegin expr \"spush(s, 1) > 0\" 3\nset x 1\nblockend\n", true);
        ContainerModule.Registry previous = ContainerModule.enter(
            ContainerModule.compileRegistry(listOf("stack s cell1 0 4"), null));
        boolean[] goodInvalid;
        try{
            goodInvalid = SugarCompiler.invalidStatements(good);
        }finally{
            ContainerModule.leave(previous);
        }
        check(!goodInvalid[0], "valid stack declaration was marked invalid");
        check(!goodInvalid[1], "container expression was marked invalid");
        check(!goodInvalid[2], "regular instruction was marked invalid");
    }

    // ===== 声明卡不产指令 / 载体往返 =====

    private static void declarationCardProducesNoLine(){
        String sugar = "stack s cell1 0 4\nqueue q cell2 0 4\nset x 1\n";
        String mlog = stripCompile(sugar);
        // 载体行 `set __ls_sugar "<base64>"` 会保留，但它只承载源码文本；
        // 检查的是没有任何声明卡文本与隐藏状态变量进入降级产物。
        check(!mlog.contains("__ls_stk_") && !mlog.contains("__ls_que_"),
            "hidden container state leaked into the product:\n" + mlog);
        for(String line : mlog.replace("\r\n", "\n").split("\n", -1)){
            String text = line.trim();
            check(!text.startsWith("stack ") && !text.startsWith("queue "),
                "declaration card leaked into the product: " + text);
        }
        check(mlog.contains("set x 1"), "regular instruction lost:\n" + mlog);
        // 编辑器复制卡片：write → copy（重新解析）必须保持文本一致
        for(LStatement card : listOf("stack s cell1 0 4\nqueue q cell2 4 2\n")){
            StringBuilder text = new StringBuilder();
            card.write(text);
            LStatement copy = card.copy();
            check(copy != null, "declaration card copy failed: " + text);
            StringBuilder copied = new StringBuilder();
            copy.write(copied);
            check(text.toString().equals(copied.toString()),
                "declaration card copy changed its text: " + text + " vs " + copied);
        }
        check(SugarCompiler.restore(SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip)).equals(sugar),
            "declaration cards did not survive the carrier round trip");
    }

    // ===== 产物纯原版 =====

    private static void outputIsPureVanilla(){
        String sugar = "stack s cell1 0 4\n"
            + "queue q cell2 0 4\n"
            + "ifbegin expr \"spush(s, 1) > 0\" 4\nset a 1\nblockend\n"
            + "ifbegin expr \"spop(s) > 0\" 7\nset b 2\nblockend\n"
            + "ifbegin expr \"speek(s) > 0\" 10\nset c 3\nblockend\n"
            + "ifbegin expr \"ssize(s) > 0\" 13\nset d 4\nblockend\n"
            + "ifbegin expr \"sclear(s) == 0\" 16\nset e 5\nblockend\n"
            + "ifbegin expr \"qpush(q, 2) > 0\" 19\nset f 6\nblockend\n"
            + "ifbegin expr \"qpop(q) > 0\" 22\nset g 7\nblockend\n"
            + "ifbegin expr \"qpeek(q) > 0\" 25\nset h 8\nblockend\n"
            + "ifbegin expr \"qsize(q) > 0\" 28\nset i 9\nblockend\n"
            + "ifbegin expr \"qclear(q) == 0\" 31\nset j 10\nblockend\n";
        String mlog = stripCompile(sugar);
        check(mlog.contains("write __ls_func___ls_builtin_stkpush_v __ls_func___ls_builtin_stkpush_mem"),
            "stack push write missing:\n" + mlog);
        check(mlog.contains("write __ls_func___ls_builtin_quepush_v __ls_func___ls_builtin_quepush_mem"),
            "queue push write missing:\n" + mlog);
        check(!mlog.contains("stack ") && !mlog.contains("queue ") && !mlog.contains("ifbegin ") && !mlog.contains("funccall "),
            "sugar residue in the product:\n" + mlog);
        for(String line : mlog.replace("\r\n", "\n").split("\n", -1)){
            String text = line.trim();
            if(text.isEmpty() || text.endsWith(":") || text.startsWith("#")) continue;
            int space = text.indexOf(' ');
            String opcode = space < 0 ? text : text.substring(0, space);
            check(vanillaOpcodes.contains(opcode), "non-vanilla instruction in product: " + text);
        }
    }

    // ===== 载体往返 =====

    private static void roundTripAndVerification(){
        String sugar = "stack s cell1 0 4\n"
            + "queue q cell2 0 4\n"
            + "ifbegin expr \"spush(s, 3) > 0\" 4\nset x 1\nblockend\n"
            + "ifbegin expr \"qpush(q, 4) > 0\" 7\nset y 2\nblockend\n"
            + "ifbegin expr \"spop(s) + qpop(q) > 0\" 10\nset z 3\nblockend\n";
        String compiled = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        check(SugarCompiler.restore(compiled).equals(sugar),
            "carrier did not preserve the container source:\n" + SugarCompiler.restore(compiled));
        check(SugarCompiler.verifyRestore(compiled, sugar), "verifyRestore rejected a container program");
        String recompiled = SugarCompiler.compile(SugarCompiler.restore(compiled), SugarCompiler.FuncMode.normal,
            null, null, SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        check(SugarCompiler.matchesStoredStream(recompiled, compiled),
            "recompiled container program drifted from the stored stream");
    }

    // ===== helpers =====

    private static final Set<String> vanillaOpcodes = new HashSet<>(Arrays.asList(
        "noop", "read", "write", "draw", "print", "printchar", "format", "drawflush", "printflush",
        "getlink", "control", "radar", "sensor", "set", "op", "select", "wait", "stop", "lookup",
        "packcolor", "unpackcolor", "end", "jump", "ubind", "ucontrol", "uradar", "ulocate",
        "query", "getblock", "setblock", "spawn", "bullet", "status", "weathersense", "weatherset",
        "spawnwave", "setrule", "message", "cutscene", "effect", "explosion", "setrate", "fetch",
        "sync", "clientdata", "getflag", "setflag", "setprop", "playsound", "playmusic",
        "setmarker", "makemarker", "localeprint"
    ));

    private static void withRegistry(String declarations, Runnable body){
        ContainerModule.Registry registry = ContainerModule.compileRegistry(listOf(declarations), null);
        ContainerModule.Registry previous = ContainerModule.enter(registry);
        try{
            body.run();
        }finally{
            ContainerModule.leave(previous);
        }
    }

    private static List<LStatement> listOf(String declarations){
        Seq<LStatement> statements = LAssembler.read(declarations, true);
        List<LStatement> list = new ArrayList<>();
        for(LStatement statement : statements) list.add(statement);
        return list;
    }

    private static String compile(String sugar){
        return SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
    }

    private static String stripCompile(String sugar){
        return SugarCompiler.stripMarkers(compile(sugar));
    }

    private static String textOf(List<ExprCompiler.Line> ops){
        StringBuilder out = new StringBuilder();
        for(int i = 0; i < ops.size(); i++){
            if(i > 0) out.append('\n');
            out.append(ops.get(i).toText());
        }
        return out.toString();
    }

    private static int countOf(String text, String needle){
        int count = 0, at = 0;
        while((at = text.indexOf(needle, at)) >= 0){
            count++;
            at += needle.length();
        }
        return count;
    }

    private static void checkRegistryThrows(String declarations, String what){
        try{
            ContainerModule.compileRegistry(listOf(declarations), null);
        }catch(IllegalArgumentException expected){
            return;
        }
        check(false, "registry build should have failed (" + what + "): " + declarations);
    }

    private static void checkReadThrows(String declarations, String what){
        try{
            listOf(declarations);
        }catch(RuntimeException expected){
            return;
        }
        check(false, "parse should have failed (" + what + "): " + declarations);
    }

    private static void checkCompileThrows(String sugar, String what){
        try{
            compile(sugar);
        }catch(RuntimeException expected){
            return;
        }
        check(false, "compile should have failed (" + what + "): " + sugar);
    }

    private static void checkLine(String expected, String actual){
        check(expected.equals(actual), "mlog mismatch\n  expected:\n" + expected + "\n  actual:\n" + actual);
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
