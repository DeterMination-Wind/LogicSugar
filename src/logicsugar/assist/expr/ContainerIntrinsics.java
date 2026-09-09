package logicsugar.assist.expr;

import logicsugar.assist.data.ContainerModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 栈 + 队列的表达式扩展（{@code spush/spop/speek/ssize/sclear}、
 * {@code qpush/qpop/qpeek/qsize/qclear}）。
 *
 * <p>第一个实参必须是<b>已声明容器名</b>（{@link ContainerModule} 的编译期注册表），
 * 且函数与容器种类匹配（{@code spush} 只接受 stack、{@code qpush} 只接受 queue）。</p>
 *
 * <p><b>展开形态</b>：</p>
 * <ul>
 *   <li><b>读/变量类</b>（spop/speek/ssize/sclear、qpop/qpeek/qsize/qclear）：无分支直线
 *       {@code op}/{@code read} 链，只写隐藏状态变量与临时变量；</li>
 *   <li><b>写内存类</b>（spush/qpush）：展开为对注入函数 {@code __ls_builtin_stkpush} /
 *       {@code __ls_builtin_quepush} 的 {@code funccall}（normal 模式全程序共享一份子程序，
 *       未使用时不进入产物）。<b>原因</b>：{@link ExprCompiler.WriteLine} 不被
 *       {@code SugarFunctions.emitConditionExpression}/{@code emitReturn} 的分支识别
 *       （它们只处理 OpLine/ReadLine/SensorLine/CallLine/RawLine），intrinsic 在条件/返回
 *       表达式里直接发射 write 行会抛 ClassCastException；注入函数把 write 放进函数体，
 *       所有表达式上下文（条件/返回/实参/编辑器展开）都走 CallLine 通道。</li>
 * </ul>
 *
 * <p><b>边界语义</b>（原版 {@code MemoryBlock} 行为）：</p>
 * <ul>
 *   <li><b>已满不写入</b>：push 在函数体内分支，满时直接返回 size，不执行 write；</li>
 *   <li><b>空容器返回 NaN</b>：pop/peek 的读地址在空时被重定向到 {@code -1}，原版
 *       {@code MemoryBlock.read} 对越界地址返回 {@code Double.NaN}（与 {@code op div <tmp> 0 0}
 *       是同一个 NaN 表示）；空 pop 的状态更新是 {@code max(x-1, 0)}，head 用
 *       {@code (head + min(count,1)) % size} 保持不动。</li>
 * </ul>
 *
 * <p>状态变量：栈 {@code __ls_stk_<name>_top}（元素个数）；队列
 * {@code __ls_que_<name>_head/_tail/_count}，恒有 {@code tail == (head + count) % size}。
 * 未初始化时 mlog 读取为 0，因此「初始 0」不需要初始化指令（声明卡不产行）。</p>
 *
 * <p>依赖原版内存语义：{@code memory} 应指向 cell/bank/world 内存块（与
 * {@link ArrayRegistry#memoryCapacity} 的容量检查口径一致）。</p>
 */
public final class ContainerIntrinsics implements ExprIntrinsics.Provider{
    public static final ContainerIntrinsics INSTANCE = new ContainerIntrinsics();

    /** 注入函数：栈 push（返回新元素个数；满时不写入并返回 size）。 */
    public static final String BUILTIN_STACK_PUSH = "__ls_builtin_stkpush";
    /** 注入函数：队列 push（返回新元素个数；满时不写入并返回 size）。 */
    public static final String BUILTIN_QUEUE_PUSH = "__ls_builtin_quepush";

    private static final String[] CALL_NAMES = {
        "spush", "spop", "speek", "ssize", "sclear",
        "qpush", "qpop", "qpeek", "qsize", "qclear"
    };

    private ContainerIntrinsics(){}

    @Override
    public String[] callNames(){
        return CALL_NAMES;
    }

    @Override
    public int arity(String name){
        switch(name){
            case "spush":
            case "qpush":
                return 2;
            case "spop":
            case "speek":
            case "ssize":
            case "sclear":
            case "qpop":
            case "qpeek":
            case "qsize":
            case "qclear":
                return 1;
            default:
                return -1;
        }
    }

    @Override
    public List<ExprCompiler.Line> expandCall(String name, List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        switch(name){
            case "spush": return stackPush(args, ctx);
            case "spop": return stackPop(args, ctx);
            case "speek": return stackPeek(args, ctx);
            case "ssize": return stackSize(args, ctx);
            case "sclear": return stackClear(args, ctx);
            case "qpush": return queuePush(args, ctx);
            case "qpop": return queuePop(args, ctx);
            case "qpeek": return queuePeek(args, ctx);
            case "qsize": return queueSize(args, ctx);
            case "qclear": return queueClear(args, ctx);
            default: return null;
        }
    }

    @Override
    public boolean isMemberBase(ExprCompiler.Node base){
        return false;
    }

    @Override
    public List<ExprCompiler.Line> readMember(ExprCompiler.Node base, String prop, ExprIntrinsics.Ctx ctx){
        return null;
    }

    @Override
    public List<ExprCompiler.Line> writeMember(ExprCompiler.Node base, String prop, ExprCompiler.Node value, ExprIntrinsics.Ctx ctx){
        return null;
    }

    @Override
    public List<String> callees(String name, int argc){
        if("spush".equals(name)) return Collections.singletonList(BUILTIN_STACK_PUSH);
        if("qpush".equals(name)) return Collections.singletonList(BUILTIN_QUEUE_PUSH);
        return Collections.emptyList();
    }

    /** 注入函数的 sugar 源文本（每项一个完整 funcdef 块）。 */
    public static List<String> builtinSugar(){
        List<String> result = new ArrayList<>(2);
        result.add(stackPushBody());
        result.add(queuePushBody());
        return result;
    }

    // ===== 栈 =====

    /** {@code ssize(s)}：{@code op add <r> top 0}。 */
    private static List<ExprCompiler.Line> stackSize(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ContainerModule.Info info = resolve("ssize", args.get(0), ContainerModule.KIND_STACK, ctx);
        List<ExprCompiler.Line> out = new ArrayList<>(1);
        out.add(new ExprCompiler.OpLine("add", ctx.temp(), info.stateVar(ContainerModule.FIELD_TOP), "0"));
        return out;
    }

    /** {@code sclear(s)}：top = 0，返回 0。 */
    private static List<ExprCompiler.Line> stackClear(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ContainerModule.Info info = resolve("sclear", args.get(0), ContainerModule.KIND_STACK, ctx);
        List<ExprCompiler.Line> out = new ArrayList<>(2);
        out.add(new ExprCompiler.OpLine("add", info.stateVar(ContainerModule.FIELD_TOP), "0", "0"));
        out.add(new ExprCompiler.OpLine("add", ctx.temp(), "0", "0"));
        return out;
    }

    /** {@code speek(s)}：空 → 越界读 → NaN；否则读 base+top-1。 */
    private static List<ExprCompiler.Line> stackPeek(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ContainerModule.Info info = resolve("speek", args.get(0), ContainerModule.KIND_STACK, ctx);
        String top = info.stateVar(ContainerModule.FIELD_TOP);
        String base = Integer.toString(info.base);
        List<ExprCompiler.Line> out = new ArrayList<>(6);
        String index = ctx.temp();
        String address = ctx.temp();
        String empty = ctx.temp();
        out.add(new ExprCompiler.OpLine("sub", index, top, "1"));
        out.add(new ExprCompiler.OpLine("add", address, base, index));
        out.add(new ExprCompiler.OpLine("lessThanEq", empty, top, "0"));
        out.add(new ExprCompiler.OpLine("mul", empty, empty, base));
        out.add(new ExprCompiler.OpLine("sub", address, address, empty));
        out.add(new ExprCompiler.ReadLine(ctx.temp(), info.memory, address));
        return out;
    }

    /** {@code spop(s)}：先算读地址（空 → -1），再 top = max(top-1, 0)，最后读出结果。 */
    private static List<ExprCompiler.Line> stackPop(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ContainerModule.Info info = resolve("spop", args.get(0), ContainerModule.KIND_STACK, ctx);
        String top = info.stateVar(ContainerModule.FIELD_TOP);
        String base = Integer.toString(info.base);
        List<ExprCompiler.Line> out = new ArrayList<>(8);
        String index = ctx.temp();
        String address = ctx.temp();
        String empty = ctx.temp();
        out.add(new ExprCompiler.OpLine("sub", index, top, "1"));
        out.add(new ExprCompiler.OpLine("add", address, base, index));
        out.add(new ExprCompiler.OpLine("lessThanEq", empty, top, "0"));
        out.add(new ExprCompiler.OpLine("mul", empty, empty, base));
        out.add(new ExprCompiler.OpLine("sub", address, address, empty));
        out.add(new ExprCompiler.OpLine("sub", top, top, "1"));
        out.add(new ExprCompiler.OpLine("max", top, top, "0"));
        out.add(new ExprCompiler.ReadLine(ctx.temp(), info.memory, address));
        return out;
    }

    /** {@code spush(s,v)}：注入函数写内存并返回新个数，函数结果直接写回状态变量。 */
    private static List<ExprCompiler.Line> stackPush(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ContainerModule.Info info = resolve("spush", args.get(0), ContainerModule.KIND_STACK, ctx);
        String top = info.stateVar(ContainerModule.FIELD_TOP);
        // 先编译待写入的值：其指令链追加到外层 ops，位于本展开之前（求值顺序正确）
        String value = ctx.compile(args.get(1));
        List<ExprCompiler.Line> out = new ArrayList<>(1);
        out.add(new ExprCompiler.CallLine(BUILTIN_STACK_PUSH,
            info.memory + ", " + info.base + ", " + info.size + ", " + top + ", " + value, top));
        return out;
    }

    // ===== 队列 =====

    /** {@code qsize(q)}：{@code op add <r> count 0}。 */
    private static List<ExprCompiler.Line> queueSize(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ContainerModule.Info info = resolve("qsize", args.get(0), ContainerModule.KIND_QUEUE, ctx);
        List<ExprCompiler.Line> out = new ArrayList<>(1);
        out.add(new ExprCompiler.OpLine("add", ctx.temp(), info.stateVar(ContainerModule.FIELD_COUNT), "0"));
        return out;
    }

    /** {@code qclear(q)}：head/tail/count = 0，返回 0。 */
    private static List<ExprCompiler.Line> queueClear(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ContainerModule.Info info = resolve("qclear", args.get(0), ContainerModule.KIND_QUEUE, ctx);
        List<ExprCompiler.Line> out = new ArrayList<>(4);
        out.add(new ExprCompiler.OpLine("add", info.stateVar(ContainerModule.FIELD_HEAD), "0", "0"));
        out.add(new ExprCompiler.OpLine("add", info.stateVar(ContainerModule.FIELD_TAIL), "0", "0"));
        out.add(new ExprCompiler.OpLine("add", info.stateVar(ContainerModule.FIELD_COUNT), "0", "0"));
        out.add(new ExprCompiler.OpLine("add", ctx.temp(), "0", "0"));
        return out;
    }

    /** {@code qpeek(q)}：空 → 越界读 → NaN；否则读 base+head。 */
    private static List<ExprCompiler.Line> queuePeek(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ContainerModule.Info info = resolve("qpeek", args.get(0), ContainerModule.KIND_QUEUE, ctx);
        String head = info.stateVar(ContainerModule.FIELD_HEAD);
        String count = info.stateVar(ContainerModule.FIELD_COUNT);
        String base = Integer.toString(info.base);
        List<ExprCompiler.Line> out = new ArrayList<>(6);
        String empty = ctx.temp();
        String address = ctx.temp();
        String bump = ctx.temp();
        out.add(new ExprCompiler.OpLine("lessThanEq", empty, count, "0"));
        out.add(new ExprCompiler.OpLine("add", address, base, head));
        out.add(new ExprCompiler.OpLine("add", bump, address, "1"));
        out.add(new ExprCompiler.OpLine("mul", empty, empty, bump));
        out.add(new ExprCompiler.OpLine("sub", address, address, empty));
        out.add(new ExprCompiler.ReadLine(ctx.temp(), info.memory, address));
        return out;
    }

    /** {@code qpop(q)}：空 → NaN 且 head/count 不变；否则 head=(head+1)%size、count-1。 */
    private static List<ExprCompiler.Line> queuePop(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ContainerModule.Info info = resolve("qpop", args.get(0), ContainerModule.KIND_QUEUE, ctx);
        String head = info.stateVar(ContainerModule.FIELD_HEAD);
        String count = info.stateVar(ContainerModule.FIELD_COUNT);
        String base = Integer.toString(info.base);
        String size = Integer.toString(info.size);
        List<ExprCompiler.Line> out = new ArrayList<>(11);
        String empty = ctx.temp();
        String address = ctx.temp();
        String bump = ctx.temp();
        out.add(new ExprCompiler.OpLine("lessThanEq", empty, count, "0"));
        out.add(new ExprCompiler.OpLine("add", address, base, head));
        out.add(new ExprCompiler.OpLine("add", bump, address, "1"));
        out.add(new ExprCompiler.OpLine("mul", empty, empty, bump));
        out.add(new ExprCompiler.OpLine("sub", address, address, empty));
        String step = ctx.temp();
        String moved = ctx.temp();
        out.add(new ExprCompiler.OpLine("min", step, count, "1"));
        out.add(new ExprCompiler.OpLine("add", moved, head, step));
        out.add(new ExprCompiler.OpLine("mod", head, moved, size));
        out.add(new ExprCompiler.OpLine("sub", count, count, "1"));
        out.add(new ExprCompiler.OpLine("max", count, count, "0"));
        out.add(new ExprCompiler.ReadLine(ctx.temp(), info.memory, address));
        return out;
    }

    /** {@code qpush(q,v)}：注入函数写内存并返回新个数，随后 tail = (head+count)%size。 */
    private static List<ExprCompiler.Line> queuePush(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ContainerModule.Info info = resolve("qpush", args.get(0), ContainerModule.KIND_QUEUE, ctx);
        String head = info.stateVar(ContainerModule.FIELD_HEAD);
        String tail = info.stateVar(ContainerModule.FIELD_TAIL);
        String count = info.stateVar(ContainerModule.FIELD_COUNT);
        String size = Integer.toString(info.size);
        String value = ctx.compile(args.get(1));
        List<ExprCompiler.Line> out = new ArrayList<>(4);
        out.add(new ExprCompiler.CallLine(BUILTIN_QUEUE_PUSH,
            info.memory + ", " + info.base + ", " + info.size + ", " + head + ", " + count + ", " + value, count));
        out.add(new ExprCompiler.OpLine("add", tail, head, count));
        out.add(new ExprCompiler.OpLine("mod", tail, tail, size));
        out.add(new ExprCompiler.OpLine("add", ctx.temp(), count, "0"));
        return out;
    }

    // ===== 解析 =====

    /** 解析第一个实参为已声明容器，并校验种类匹配。 */
    private static ContainerModule.Info resolve(String op, ExprCompiler.Node node, String expectedKind, ExprIntrinsics.Ctx ctx){
        if(!(node instanceof ExprCompiler.Var var)){
            throw ctx.error(ExprIntrinsics.text("la.err.intrinsic_container_arg",
                "{0}() expects a declared {1} name", op, expectedKind));
        }
        ContainerModule.Registry registry = ContainerModule.active();
        if(registry == null){
            throw ctx.error(ExprIntrinsics.text("la.err.intrinsic_no_context",
                "{0}() cannot resolve ''{1}'': no container declaration context", op, var.name));
        }
        ContainerModule.Info info = registry.get(var.name);
        if(info == null){
            throw ctx.error(ExprIntrinsics.text("la.err.intrinsic_unknown_container",
                "{0}() references undeclared container ''{1}''", op, var.name));
        }
        if(!expectedKind.equals(info.kind)){
            throw ctx.error(ExprIntrinsics.text("la.err.intrinsic_container_kind",
                "{0}() expects a {1} but ''{2}'' is a {3}", op, expectedKind, var.name, info.kind));
        }
        return info;
    }

    // ===== 注入函数源文本 =====

    /** 栈 push：满时跳过 write 直接返回 size，否则写 base+top 并返回 top+1。 */
    private static String stackPushBody(){
        Fn fn = new Fn(BUILTIN_STACK_PUSH, "mem,base,size,top,v");
        fn.jump("L_full", "greaterThanEq", "top", "size");
        fn.op("add", "__ls_ct_a", "base", "top");
        fn.write("v", "mem", "__ls_ct_a");
        fn.op("add", "__ls_ct_r", "top", "1");
        fn.jump("L_end", "always", "x", "false");
        fn.label("L_full");
        fn.op("add", "__ls_ct_r", "size", "0");
        fn.label("L_end");
        fn.line("return \"__ls_ct_r\"");
        return fn.build();
    }

    /** 队列 push：满时跳过 write 直接返回 size，否则写 (head+count)%size 并返回 count+1。 */
    private static String queuePushBody(){
        Fn fn = new Fn(BUILTIN_QUEUE_PUSH, "mem,base,size,head,count,v");
        fn.jump("L_full", "greaterThanEq", "count", "size");
        fn.op("add", "__ls_ct_p", "head", "count");
        fn.op("mod", "__ls_ct_p", "__ls_ct_p", "size");
        fn.op("add", "__ls_ct_a", "base", "__ls_ct_p");
        fn.write("v", "mem", "__ls_ct_a");
        fn.op("add", "__ls_ct_r", "count", "1");
        fn.jump("L_end", "always", "x", "false");
        fn.label("L_full");
        fn.op("add", "__ls_ct_r", "size", "0");
        fn.label("L_end");
        fn.line("return \"__ls_ct_r\"");
        return fn.build();
    }

    /**
     * 注入函数文本构造器（与 {@link ArrayBulkIntrinsics} 同一约定）：header 是第 0 行，
     * body 从第 1 行开始，末行是函数自身的 blockend。跳转目标先写 {@code @LABEL} 占位，
     * build() 时替换为绝对语句下标。
     */
    private static final class Fn{
        private final String name;
        private final String params;
        private final List<String> body = new ArrayList<>();
        private final Map<String, Integer> labels = new LinkedHashMap<>();

        Fn(String name, String params){
            this.name = name;
            this.params = params;
        }

        void line(String text){
            body.add(text);
        }

        void label(String label){
            labels.put(label, body.size());
        }

        void op(String op, String dest, String a, String b){
            line("op " + op + " " + dest + " " + a + " " + b);
        }

        void write(String value, String memory, String address){
            line("write " + value + " " + memory + " " + address);
        }

        void jump(String label, String op, String value, String compare){
            line("jump @" + label + " " + op + " " + value + " " + compare);
        }

        String build(){
            List<String> all = new ArrayList<>(body.size() + 2);
            all.add("funcdef " + name + " " + params + " " + (body.size() + 1));
            all.addAll(body);
            all.add("blockend");
            for(int i = 1; i < all.size(); i++){
                String text = all.get(i);
                if(text.indexOf('@') < 0) continue;
                for(Map.Entry<String, Integer> entry : labels.entrySet()){
                    text = text.replace("@" + entry.getKey(), String.valueOf(1 + entry.getValue()));
                }
                all.set(i, text);
            }
            return String.join("\n", all) + "\n";
        }
    }
}
