package logicsugar.assist.expr;

import logicsugar.assist.data.ContainerModule;
import mindustry.logic.SugarCompiler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 栈 + 队列 + 双端队列的表达式扩展（{@code spush/spop/speek/ssize/sclear}、
 * {@code qpush/qpop/qpeek/qsize/qclear}、
 * {@code dpushf/dpushb/dpopf/dpopb/dpeekf/dpeekb/dsize/dclear}）。
 *
 * <p>第一个实参必须是<b>已声明容器名</b>（{@link ContainerModule} 的编译期注册表），
 * 且函数与容器种类匹配（{@code spush} 只接受 stack、{@code qpush} 只接受 queue、
 * {@code dpushf} 只接受 deque）。</p>
 *
 * <p><b>展开形态</b>：</p>
 * <ul>
 *   <li><b>读/变量类</b>（pop/peek/size/clear）：无分支直线
 *       {@code op}/{@code read} 链，只写隐藏状态变量与临时变量；</li>
 *   <li><b>写内存类</b>（spush/qpush/dpushb/dpushf）：展开为对注入函数
 *       {@code __ls_builtin_stkpush} / {@code __ls_builtin_quepush} /
 *       {@code __ls_builtin_deqpushf} 的 {@code funccall}（normal 模式全程序共享一份子程序，
 *       未使用时不进入产物）。<b>原因</b>：{@link ExprCompiler.WriteLine} 不被
 *       {@code SugarFunctions.emitConditionExpression}/{@code emitReturn} 的分支识别
 *       （它们只处理 OpLine/ReadLine/SensorLine/CallLine/RawLine），intrinsic 在条件/返回
 *       表达式里直接发射 write 行会抛 ClassCastException；注入函数把 write 放进函数体，
 *       所有表达式上下文（条件/返回/实参/编辑器展开）都走 CallLine 通道。</li>
 * </ul>
 *
 * <p><b>边界语义</b>（原版 {@code MemoryBlock} 行为）：</p>
 * <ul>
 *   <li><b>已满不写入</b>：push 在函数体内分支，满时直接返回 size，不执行 write；
 *       双端队列前端 push 满时同样不改 head；</li>
 *   <li><b>空容器返回 NaN</b>：pop/peek 的读地址在空时被重定向到 {@code -1}，原版
 *       {@code MemoryBlock.read} 对越界地址返回 {@code Double.NaN}（与 {@code op div <tmp> 0 0}
 *       是同一个 NaN 表示）；空 pop 的状态更新是 {@code max(x-1, 0)}，head 用
 *       {@code (head + min(count,1)) % size} 保持不动。head 回绕用
 *       {@code (head + size - 1) % size}，避免 mlog 对负数取模。</li>
 * </ul>
 *
 * <p>状态变量：栈 {@code __ls_stk_<name>_top}（元素个数）；队列
 * {@code __ls_que_<name>_head/_tail/_count}，双端队列 {@code __ls_deq_<name>_head/_tail/_count}，
 * 恒有 {@code tail == (head + count) % size}。
 * 未初始化时 mlog 读取为 0，因此「初始 0」不需要初始化指令（声明卡不产行）。</p>
 *
 * <p>依赖原版内存语义：{@code memory} 应指向 cell/bank/world 内存块（与
 * {@link ArrayRegistry#memoryCapacity} 的容量检查口径一致）。</p>
 */
public final class ContainerIntrinsics implements ExprIntrinsics.Provider{
    public static final ContainerIntrinsics INSTANCE = new ContainerIntrinsics();

    /** 注入函数：栈 push（返回新元素个数；满时不写入并返回 size）。 */
    public static final String BUILTIN_STACK_PUSH = "__ls_builtin_stkpush";
    /** 注入函数：队列 / 双端队列后端 push（返回新元素个数；满时不写入并返回 size）。 */
    public static final String BUILTIN_QUEUE_PUSH = "__ls_builtin_quepush";
    /** 注入函数：双端队列前端 push（返回新 head；满时不写入并返回旧 head）。 */
    public static final String BUILTIN_DEQUE_PUSH_FRONT = "__ls_builtin_deqpushf";

    private static final String[] CALL_NAMES = {
        "stack_push", "stack_pop", "stack_top", "stack_size", "stack_clear",
        "queue_push", "queue_pop", "queue_front", "queue_size", "queue_clear",
        "deque_push_front", "deque_push_back", "deque_pop_front", "deque_pop_back",
        "deque_front", "deque_back", "deque_size", "deque_clear",
        "spush", "spop", "speek", "ssize", "sclear",
        "qpush", "qpop", "qpeek", "qsize", "qclear",
        "dpushf", "dpushb", "dpopf", "dpopb", "dpeekf", "dpeekb", "dsize", "dclear"
    };

    /** 旧拼写 → 规范名。保存产物（carrier）可能携带旧名，解析时统一归一到新名再分派。 */
    static String canonical(String name){
        switch(name == null ? "" : name){
            case "spush": return "stack_push";
            case "spop": return "stack_pop";
            case "speek": return "stack_top";
            case "ssize": return "stack_size";
            case "sclear": return "stack_clear";
            case "qpush": return "queue_push";
            case "qpop": return "queue_pop";
            case "qpeek": return "queue_front";
            case "qsize": return "queue_size";
            case "qclear": return "queue_clear";
            case "dpushf": return "deque_push_front";
            case "dpushb": return "deque_push_back";
            case "dpopf": return "deque_pop_front";
            case "dpopb": return "deque_pop_back";
            case "dpeekf": return "deque_front";
            case "dpeekb": return "deque_back";
            case "dsize": return "deque_size";
            case "dclear": return "deque_clear";
            default: return name;
        }
    }

    private ContainerIntrinsics(){}

    @Override
    public String[] callNames(){
        return CALL_NAMES;
    }

    @Override
    public int arity(String name){
        switch(canonical(name)){
            case "stack_push":
            case "queue_push":
            case "deque_push_front":
            case "deque_push_back":
                return 2;
            case "stack_pop":
            case "stack_top":
            case "stack_size":
            case "stack_clear":
            case "queue_pop":
            case "queue_front":
            case "queue_size":
            case "queue_clear":
            case "deque_pop_front":
            case "deque_pop_back":
            case "deque_front":
            case "deque_back":
            case "deque_size":
            case "deque_clear":
                return 1;
            default:
                return -1;
        }
    }

    /** clear operations mutate only hidden container state and have no source-level result. */
    @Override
    public boolean returnsValue(String name){
        String canon = canonical(name);
        return !"stack_clear".equals(canon) && !"queue_clear".equals(canon) && !"deque_clear".equals(canon);
    }

    @Override
    public List<ExprCompiler.Line> expandCall(String name, List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        switch(canonical(name)){
            case "stack_push": return stackPush(args, ctx);
            case "stack_pop": return stackPop(args, ctx);
            case "stack_top": return stackPeek(args, ctx);
            case "stack_size": return stackSize(args, ctx);
            case "stack_clear": return stackClear(args, ctx);
            case "queue_push": return queuePush(args, ctx);
            case "queue_pop": return queuePop(args, ctx);
            case "queue_front": return queuePeek(args, ctx);
            case "queue_size": return queueSize(args, ctx);
            case "queue_clear": return queueClear(args, ctx);
            case "deque_push_front": return dequePushFront(args, ctx);
            case "deque_push_back": return dequePushBack(args, ctx);
            case "deque_pop_front": return dequePopFront(args, ctx);
            case "deque_pop_back": return dequePopBack(args, ctx);
            case "deque_front": return dequePeekFront(args, ctx);
            case "deque_back": return dequePeekBack(args, ctx);
            case "deque_size": return dequeSize(args, ctx);
            case "deque_clear": return dequeClear(args, ctx);
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
        String canon = canonical(name);
        if("stack_push".equals(canon)) return Collections.singletonList(BUILTIN_STACK_PUSH);
        if("queue_push".equals(canon) || "deque_push_back".equals(canon)) return Collections.singletonList(BUILTIN_QUEUE_PUSH);
        if("deque_push_front".equals(canon)) return Collections.singletonList(BUILTIN_DEQUE_PUSH_FRONT);
        return Collections.emptyList();
    }

    // ===== 方法糖（只读 getter）=====

    @Override
    public String kindOf(ExprCompiler.Node receiver){
        if(!(receiver instanceof ExprCompiler.Var var)) return null;
        ContainerModule.Registry registry = ContainerModule.active();
        if(registry == null) return null;
        ContainerModule.Info info = registry.get(var.name);
        return info == null ? null : info.kind;
    }

    @Override
    public String methodIntrinsic(String kind, String method, int argc){
        if(argc != 0) return null;
        String m = method.toLowerCase(java.util.Locale.ROOT);
        if(ContainerModule.KIND_STACK.equals(kind)){
            if(m.equals("top") || m.equals("peek")) return "stack_top";
            if(m.equals("size") || m.equals("count")) return "stack_size";
        }else if(ContainerModule.KIND_QUEUE.equals(kind)){
            if(m.equals("front") || m.equals("peek")) return "queue_front";
            if(m.equals("size") || m.equals("count")) return "queue_size";
        }else if(ContainerModule.KIND_DEQUE.equals(kind)){
            if(m.equals("front") || m.equals("peekfront")) return "deque_front";
            if(m.equals("back") || m.equals("peekback")) return "deque_back";
            if(m.equals("size") || m.equals("count")) return "deque_size";
        }
        return null;
    }

    // ===== 反向：展开链 → getter（折叠层）=====

    /** 隐藏状态变量字段名（{@link ContainerModule#stateVar} 的 field）：预筛一行是否提到容器。 */
    private static final String[] STATE_FIELDS = {
        ContainerModule.FIELD_TOP, ContainerModule.FIELD_HEAD,
        ContainerModule.FIELD_TAIL, ContainerModule.FIELD_COUNT
    };
    /** 隐藏状态变量前缀：预筛的廉价第一道（不含 {@code __ls_} 前缀的行不可能提到容器状态）。 */
    private static final String[] STATE_PREFIXES = {
        ContainerModule.statePrefix(ContainerModule.KIND_STACK),
        ContainerModule.statePrefix(ContainerModule.KIND_QUEUE),
        ContainerModule.statePrefix(ContainerModule.KIND_DEQUE)
    };

    /**
     * 反向折叠的候选 getter 方法名（与 {@link #methodIntrinsic} 的别名表同源）：
     * 只读 getter 折回这一种写法，别名（{@code peek}/{@code peekfront}/…）与函数形式
     * （{@code speek(s)}/…）展开出的指令流与规范形式逐字相同，因此统一收敛到规范写法。
     */
    private static List<String> getterMethods(String kind){
        if(ContainerModule.KIND_STACK.equals(kind)) return Arrays.asList("top", "size");
        if(ContainerModule.KIND_QUEUE.equals(kind)) return Arrays.asList("front", "size");
        if(ContainerModule.KIND_DEQUE.equals(kind)) return Arrays.asList("front", "back", "size");
        return Collections.emptyList();
    }

    /**
     * 折叠层反向钩子：链上一段以 {@code index} 结尾的行 → 容器 getter 节点
     * （{@code s.top()} / {@code s.size()} / {@code q.front()} / {@code d.front()} / {@code d.back()}）。
     *
     * <p>形状不手写模式表，而是「用正向展开重新编译候选 getter 再逐行比对」
     * （{@link #sameLowering}）。正向 lowering 改了、容器状态变量名或 base/size 语义变了，
     * 比对立刻不成立——反向层不会静默失效，{@code containerTest} 的（种类 × getter）全表
     * 自测会红。反过来，比对成立意味着这一段就是同一条 getter 编译出来的指令流，
     * {@code ExprCompiler} 的重新编译门因此恒能通过（它比的是同一份展开）。</p>
     *
     * <p>预筛只认两种行（其余行直接返回 null，不编译探针）：落在已声明容器内存块上的
     * {@code read} 行（peek 类展开的末尾行），或提到某个容器隐藏状态变量的行
     * （{@code size} 类展开只有一行 {@code op add <dest> <count|top> 0}，它也可能出现在
     * 别的卡片的链里，例如 {@code x = q.front() + q.size()}）。真匹配不上时返回 null，
     * 折叠保持原样。</p>
     */
    @Override
    public ExprIntrinsics.Fold foldAt(List<ExprCompiler.Line> ops, int index){
        if(index < 0 || index >= ops.size()) return null;
        for(ContainerModule.Info info : containerCandidates(ops.get(index))){
            for(String method : getterMethods(info.kind)){
                List<ExprCompiler.Line> pattern = lowerGetter(info.name + "." + method + "()");
                if(pattern == null || pattern.size() > index + 1) continue;
                if(!sameLowering(pattern, ops, index)) continue;
                return new ExprIntrinsics.Fold(new ExprCompiler.Method(
                    new ExprCompiler.Var(info.name), method, Collections.<ExprCompiler.Node>emptyList()), pattern.size());
            }
        }
        return null;
    }

    /** 该内存块是否是某个已声明容器的内存（折叠链的 read 行判定）。 */
    @Override
    public boolean declaresMemory(String memory){
        return !containersOn(memory).isEmpty();
    }

    /**
     * 当前声明上下文里内存块等于 {@code memory} 的容器（同一内存块上可能有多个容器区间）。
     * 走 {@link ContainerModule#active()}：编译路径用本次编译的注册表，编辑器折叠路径回退到
     * 画布声明卡。声明卡不参与折叠，因此折叠过程中这张表是稳定的；代价是每次查询扫一遍画布，
     * 所以调用方按行预筛（见 {@link #containerCandidates}），只有可疑的行才走到这里。
     */
    private static List<ContainerModule.Info> containersOn(String memory){
        if(memory == null || memory.isEmpty()) return Collections.emptyList();
        ContainerModule.Registry registry = ContainerModule.active();
        if(registry == null || registry.isEmpty()) return Collections.emptyList();
        List<ContainerModule.Info> result = new ArrayList<>(2);
        for(ContainerModule.Info info : registry.all()){
            if(memory.equals(info.memory)) result.add(info);
        }
        return result;
    }

    /**
     * 这一行可能属于哪个容器的 getter 展开（形状探针的预筛）：
     * {@code read} 行按内存块认（peek 类展开的末尾行），其余行按隐藏状态变量名认。
     */
    private static List<ContainerModule.Info> containerCandidates(ExprCompiler.Line line){
        if(line instanceof ExprCompiler.ReadLine read) return containersOn(read.a);
        // 廉价预筛：折叠会对链上每一行调到这里，而注册表查询在编辑器里是一次画布扫描，
        // 所以先用状态变量前缀把绝大多数行挡回去（普通 op 行不含 __ls_stk_/__ls_que_/__ls_deq_）。
        String text = line.toText();
        boolean mentionsState = false;
        for(String prefix : STATE_PREFIXES){
            if(text.contains(prefix)){
                mentionsState = true;
                break;
            }
        }
        if(!mentionsState) return Collections.emptyList();
        ContainerModule.Registry registry = ContainerModule.active();
        if(registry == null || registry.isEmpty()) return Collections.emptyList();
        List<ContainerModule.Info> result = new ArrayList<>(1);
        for(ContainerModule.Info info : registry.all()){
            for(String field : STATE_FIELDS){
                if(text.contains(info.stateVar(field))){
                    result.add(info);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 用正向展开编译一段 getter 文本（形状探针）。结果只读：探针的目标变量名不参与比对
     * （窗口末行的结果槽是通配），失败（声明/名字非法等）返回 null，反向层不报错、只放弃本次折叠。
     */
    private static List<ExprCompiler.Line> lowerGetter(String form){
        try{
            return ExprCompiler.compile("result", form);
        }catch(RuntimeException e){
            return null;
        }
    }

    /**
     * 正向展开 pattern 与链尾逐行比对。pattern 里的临时变量槽（{@code _0, _1, …}）是捕获位：
     * 同一槽位必须对应同一个实际操作数，编号可以不同（同一段展开在不同表达式里编号本来就不同）；
     * 其余槽位按字面量比较——容器状态变量名、内存块与 base/size 字面量都在其中，因此这段链
     * 只可能属于这一个容器。末行的结果槽是通配：卡片的目标变量由链尾自己决定。
     */
    private static boolean sameLowering(List<ExprCompiler.Line> pattern, List<ExprCompiler.Line> ops, int index){
        int offset = index + 1 - pattern.size();
        Map<String, String> captured = new HashMap<>();
        for(int i = 0; i < pattern.size(); i++){
            String[] expected = tokens(pattern.get(i));
            String[] actual = tokens(ops.get(offset + i));
            if(expected == null || actual == null || expected.length != actual.length) return false;
            boolean last = i == pattern.size() - 1;
            int result = last ? resultSlot(pattern.get(i)) : -1;
            for(int k = 0; k < expected.length; k++){
                String slot = expected[k];
                // 带引号的槽位（funccall 实参串）不能按空白切分比较；getter 展开里没有这样的行
                if(slot.indexOf('"') >= 0 || actual[k].indexOf('"') >= 0) return false;
                if(k == result) continue; // 窗口末行的结果槽：卡片的目标变量自由
                if(ExprCompiler.isTemp(slot)){
                    String bound = captured.get(slot);
                    if(bound == null) captured.put(slot, actual[k]);
                    else if(!bound.equals(actual[k])) return false;
                }else if(!slot.equals(actual[k])){
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 一行指令的结果槽下标（窗口末行的目标变量是通配）；没有结果槽返回 -1。
     * 槽位与 {@code toText()} 的空白切分一致：{@code op <op> <dest> a b} → 2，
     * {@code read/sensor <dest> …} → 1，{@code funccall <name> "args" <dest>} → 3。
     */
    private static int resultSlot(ExprCompiler.Line line){
        if(line instanceof ExprCompiler.ReadLine || line instanceof ExprCompiler.SensorLine) return 1;
        if(line instanceof ExprCompiler.OpLine) return 2;
        if(line instanceof ExprCompiler.CallLine) return 3;
        return -1;
    }

    /** 一行指令的比对槽位（空白切分，与 {@code toText()} 同源）；切不出槽位返回 null。 */
    private static String[] tokens(ExprCompiler.Line line){
        String text = line.toText().trim();
        return text.isEmpty() ? null : text.split("\\s+");
    }

    /** 注入函数的 sugar 源文本（每项一个完整 funcdef 块）。 */
    public static List<String> builtinSugar(){
        List<String> result = new ArrayList<>(3);
        result.add(stackPushBody());
        result.add(queuePushBody());
        result.add(dequePushFrontBody());
        return result;
    }

    // ===== 栈 =====

    /** {@code ssize(s)}：{@code op add <r> top 0}。 */
    private static List<ExprCompiler.Line> stackSize(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ContainerModule.Info info = resolve("stack_size", args.get(0), ContainerModule.KIND_STACK, ctx);
        List<ExprCompiler.Line> out = new ArrayList<>(1);
        out.add(new ExprCompiler.OpLine("add", ctx.temp(), info.stateVar(ContainerModule.FIELD_TOP), "0"));
        return out;
    }

    /** {@code sclear(s)}：top = 0，返回 0。 */
    private static List<ExprCompiler.Line> stackClear(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ContainerModule.Info info = resolve("stack_clear", args.get(0), ContainerModule.KIND_STACK, ctx);
        List<ExprCompiler.Line> out = new ArrayList<>(2);
        out.add(new ExprCompiler.OpLine("add", info.stateVar(ContainerModule.FIELD_TOP), "0", "0"));
        out.add(new ExprCompiler.OpLine("add", ctx.temp(), "0", "0"));
        return out;
    }

    /** {@code speek(s)}：空 → 越界读 → NaN；否则读 base+top-1。 */
    private static List<ExprCompiler.Line> stackPeek(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ContainerModule.Info info = resolve("stack_top", args.get(0), ContainerModule.KIND_STACK, ctx);
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
        ContainerModule.Info info = resolve("stack_pop", args.get(0), ContainerModule.KIND_STACK, ctx);
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
        ContainerModule.Info info = resolve("stack_push", args.get(0), ContainerModule.KIND_STACK, ctx);
        String top = info.stateVar(ContainerModule.FIELD_TOP);
        // 先编译待写入的值：其指令链追加到外层 ops，位于本展开之前（求值顺序正确）
        String value = ctx.compile(args.get(1));
        List<ExprCompiler.Line> out = new ArrayList<>(SugarCompiler.legacyApi() ? 1 : 5);
        String old = SugarCompiler.legacyApi() ? null : ctx.temp();
        if(old != null) out.add(new ExprCompiler.OpLine("add", old, top, "0"));
        out.add(new ExprCompiler.CallLine(BUILTIN_STACK_PUSH,
            info.memory + ", " + info.base + ", " + info.size + ", " + top + ", " + value, top));
        if(old != null){
            // v5 API: 满时结果统一报 -1，成功仍返回新元素个数
            String ok = ctx.temp();
            out.add(new ExprCompiler.OpLine("notEqual", ok, top, old));
            ExprCompiler.emitFailureSelect(out, ctx.temp(), ok, top);
        }
        return out;
    }

    // ===== 队列 =====

    /** {@code qsize(q)}：{@code op add <r> count 0}。 */
    private static List<ExprCompiler.Line> queueSize(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ContainerModule.Info info = resolve("queue_size", args.get(0), ContainerModule.KIND_QUEUE, ctx);
        List<ExprCompiler.Line> out = new ArrayList<>(1);
        out.add(new ExprCompiler.OpLine("add", ctx.temp(), info.stateVar(ContainerModule.FIELD_COUNT), "0"));
        return out;
    }

    /** {@code qclear(q)}：head/tail/count = 0，返回 0。 */
    private static List<ExprCompiler.Line> queueClear(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ContainerModule.Info info = resolve("queue_clear", args.get(0), ContainerModule.KIND_QUEUE, ctx);
        List<ExprCompiler.Line> out = new ArrayList<>(4);
        out.add(new ExprCompiler.OpLine("add", info.stateVar(ContainerModule.FIELD_HEAD), "0", "0"));
        out.add(new ExprCompiler.OpLine("add", info.stateVar(ContainerModule.FIELD_TAIL), "0", "0"));
        out.add(new ExprCompiler.OpLine("add", info.stateVar(ContainerModule.FIELD_COUNT), "0", "0"));
        out.add(new ExprCompiler.OpLine("add", ctx.temp(), "0", "0"));
        return out;
    }

    /** {@code qpeek(q)}：空 → 越界读 → NaN；否则读 base+head。 */
    private static List<ExprCompiler.Line> queuePeek(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ContainerModule.Info info = resolve("queue_front", args.get(0), ContainerModule.KIND_QUEUE, ctx);
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
        ContainerModule.Info info = resolve("queue_pop", args.get(0), ContainerModule.KIND_QUEUE, ctx);
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
        ContainerModule.Info info = resolve("queue_push", args.get(0), ContainerModule.KIND_QUEUE, ctx);
        String head = info.stateVar(ContainerModule.FIELD_HEAD);
        String tail = info.stateVar(ContainerModule.FIELD_TAIL);
        String count = info.stateVar(ContainerModule.FIELD_COUNT);
        String size = Integer.toString(info.size);
        String value = ctx.compile(args.get(1));
        List<ExprCompiler.Line> out = new ArrayList<>(SugarCompiler.legacyApi() ? 4 : 7);
        String old = SugarCompiler.legacyApi() ? null : ctx.temp();
        if(old != null) out.add(new ExprCompiler.OpLine("add", old, count, "0"));
        out.add(new ExprCompiler.CallLine(BUILTIN_QUEUE_PUSH,
            info.memory + ", " + info.base + ", " + info.size + ", " + head + ", " + count + ", " + value, count));
        out.add(new ExprCompiler.OpLine("add", tail, head, count));
        out.add(new ExprCompiler.OpLine("mod", tail, tail, size));
        if(old == null){
            out.add(new ExprCompiler.OpLine("add", ctx.temp(), count, "0"));
        }else{
            // v5 API: 满时结果统一报 -1，成功仍返回新元素个数
            String ok = ctx.temp();
            out.add(new ExprCompiler.OpLine("notEqual", ok, count, old));
            ExprCompiler.emitFailureSelect(out, ctx.temp(), ok, count);
        }
        return out;
    }

    // ===== 双端队列 =====

    /** {@code dsize(d)}：{@code op add <r> count 0}。 */
    private static List<ExprCompiler.Line> dequeSize(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ContainerModule.Info info = resolve("deque_size", args.get(0), ContainerModule.KIND_DEQUE, ctx);
        List<ExprCompiler.Line> out = new ArrayList<>(1);
        out.add(new ExprCompiler.OpLine("add", ctx.temp(), info.stateVar(ContainerModule.FIELD_COUNT), "0"));
        return out;
    }

    /** {@code dclear(d)}：head/tail/count = 0，返回 0。 */
    private static List<ExprCompiler.Line> dequeClear(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ContainerModule.Info info = resolve("deque_clear", args.get(0), ContainerModule.KIND_DEQUE, ctx);
        List<ExprCompiler.Line> out = new ArrayList<>(4);
        out.add(new ExprCompiler.OpLine("add", info.stateVar(ContainerModule.FIELD_HEAD), "0", "0"));
        out.add(new ExprCompiler.OpLine("add", info.stateVar(ContainerModule.FIELD_TAIL), "0", "0"));
        out.add(new ExprCompiler.OpLine("add", info.stateVar(ContainerModule.FIELD_COUNT), "0", "0"));
        out.add(new ExprCompiler.OpLine("add", ctx.temp(), "0", "0"));
        return out;
    }

    /** {@code dpeekf(d)}：空 → 越界读 → NaN；否则读 base+head。 */
    private static List<ExprCompiler.Line> dequePeekFront(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        return ringPeek("deque_front", args, ctx, true);
    }

    /** {@code dpeekb(d)}：空 → 越界读 → NaN；否则读 (head+count-1)%size。 */
    private static List<ExprCompiler.Line> dequePeekBack(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        return ringPeek("deque_back", args, ctx, false);
    }

    /** {@code dpopf(d)}：与队列 pop 相同（从前端取出）。 */
    private static List<ExprCompiler.Line> dequePopFront(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        return ringPopFront("deque_pop_front", args, ctx);
    }

    /** {@code dpopb(d)}：空 → NaN；否则从后端取出并 count-1，随后同步 tail。 */
    private static List<ExprCompiler.Line> dequePopBack(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ContainerModule.Info info = resolve("deque_pop_back", args.get(0), ContainerModule.KIND_DEQUE, ctx);
        String head = info.stateVar(ContainerModule.FIELD_HEAD);
        String tail = info.stateVar(ContainerModule.FIELD_TAIL);
        String count = info.stateVar(ContainerModule.FIELD_COUNT);
        String base = Integer.toString(info.base);
        String size = Integer.toString(info.size);
        List<ExprCompiler.Line> out = new ArrayList<>(14);
        String empty = ctx.temp();
        String index = ctx.temp();
        String address = ctx.temp();
        String bump = ctx.temp();
        out.add(new ExprCompiler.OpLine("lessThanEq", empty, count, "0"));
        // (head + count - 1 + size) % size：先加 size 再减 1，避免空时 head-1 对负数取模
        out.add(new ExprCompiler.OpLine("add", index, head, count));
        out.add(new ExprCompiler.OpLine("add", index, index, size));
        out.add(new ExprCompiler.OpLine("sub", index, index, "1"));
        out.add(new ExprCompiler.OpLine("mod", index, index, size));
        out.add(new ExprCompiler.OpLine("add", address, base, index));
        out.add(new ExprCompiler.OpLine("add", bump, address, "1"));
        out.add(new ExprCompiler.OpLine("mul", empty, empty, bump));
        out.add(new ExprCompiler.OpLine("sub", address, address, empty));
        out.add(new ExprCompiler.OpLine("sub", count, count, "1"));
        out.add(new ExprCompiler.OpLine("max", count, count, "0"));
        out.add(new ExprCompiler.OpLine("add", tail, head, count));
        out.add(new ExprCompiler.OpLine("mod", tail, tail, size));
        out.add(new ExprCompiler.ReadLine(ctx.temp(), info.memory, address));
        return out;
    }

    /** {@code dpushb(d,v)}：与队列 push 相同（写入后端）。 */
    private static List<ExprCompiler.Line> dequePushBack(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ContainerModule.Info info = resolve("deque_push_back", args.get(0), ContainerModule.KIND_DEQUE, ctx);
        return ringPushBack(info, args, ctx);
    }

    /**
     * {@code dpushf(d,v)}：注入函数返回新 head（满时旧 head），随后
     * {@code count = min(count+1, size)} 并同步 tail。表达式结果是新 count。
     */
    private static List<ExprCompiler.Line> dequePushFront(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ContainerModule.Info info = resolve("deque_push_front", args.get(0), ContainerModule.KIND_DEQUE, ctx);
        String head = info.stateVar(ContainerModule.FIELD_HEAD);
        String tail = info.stateVar(ContainerModule.FIELD_TAIL);
        String count = info.stateVar(ContainerModule.FIELD_COUNT);
        String size = Integer.toString(info.size);
        String value = ctx.compile(args.get(1));
        List<ExprCompiler.Line> out = new ArrayList<>(SugarCompiler.legacyApi() ? 6 : 9);
        String old = SugarCompiler.legacyApi() ? null : ctx.temp();
        if(old != null) out.add(new ExprCompiler.OpLine("add", old, count, "0"));
        out.add(new ExprCompiler.CallLine(BUILTIN_DEQUE_PUSH_FRONT,
            info.memory + ", " + info.base + ", " + info.size + ", " + head + ", " + count + ", " + value, head));
        String bump = ctx.temp();
        out.add(new ExprCompiler.OpLine("add", bump, count, "1"));
        out.add(new ExprCompiler.OpLine("min", count, bump, size));
        out.add(new ExprCompiler.OpLine("add", tail, head, count));
        out.add(new ExprCompiler.OpLine("mod", tail, tail, size));
        if(old == null){
            out.add(new ExprCompiler.OpLine("add", ctx.temp(), count, "0"));
        }else{
            // v5 API: 满时结果统一报 -1，成功仍返回新元素个数
            String ok = ctx.temp();
            out.add(new ExprCompiler.OpLine("notEqual", ok, count, old));
            ExprCompiler.emitFailureSelect(out, ctx.temp(), ok, count);
        }
        return out;
    }

    private static List<ExprCompiler.Line> ringPeek(String op, List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx, boolean front){
        ContainerModule.Info info = resolve(op, args.get(0), ContainerModule.KIND_DEQUE, ctx);
        String head = info.stateVar(ContainerModule.FIELD_HEAD);
        String count = info.stateVar(ContainerModule.FIELD_COUNT);
        String base = Integer.toString(info.base);
        String size = Integer.toString(info.size);
        List<ExprCompiler.Line> out = new ArrayList<>(10);
        String empty = ctx.temp();
        String address = ctx.temp();
        String bump = ctx.temp();
        out.add(new ExprCompiler.OpLine("lessThanEq", empty, count, "0"));
        if(front){
            out.add(new ExprCompiler.OpLine("add", address, base, head));
        }else{
            String index = ctx.temp();
            out.add(new ExprCompiler.OpLine("add", index, head, count));
            out.add(new ExprCompiler.OpLine("add", index, index, size));
            out.add(new ExprCompiler.OpLine("sub", index, index, "1"));
            out.add(new ExprCompiler.OpLine("mod", index, index, size));
            out.add(new ExprCompiler.OpLine("add", address, base, index));
        }
        out.add(new ExprCompiler.OpLine("add", bump, address, "1"));
        out.add(new ExprCompiler.OpLine("mul", empty, empty, bump));
        out.add(new ExprCompiler.OpLine("sub", address, address, empty));
        out.add(new ExprCompiler.ReadLine(ctx.temp(), info.memory, address));
        return out;
    }

    private static List<ExprCompiler.Line> ringPopFront(String op, List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ContainerModule.Info info = resolve(op, args.get(0), ContainerModule.KIND_DEQUE, ctx);
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

    private static List<ExprCompiler.Line> ringPushBack(ContainerModule.Info info, List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        String head = info.stateVar(ContainerModule.FIELD_HEAD);
        String tail = info.stateVar(ContainerModule.FIELD_TAIL);
        String count = info.stateVar(ContainerModule.FIELD_COUNT);
        String size = Integer.toString(info.size);
        String value = ctx.compile(args.get(1));
        List<ExprCompiler.Line> out = new ArrayList<>(SugarCompiler.legacyApi() ? 4 : 7);
        String old = SugarCompiler.legacyApi() ? null : ctx.temp();
        if(old != null) out.add(new ExprCompiler.OpLine("add", old, count, "0"));
        out.add(new ExprCompiler.CallLine(BUILTIN_QUEUE_PUSH,
            info.memory + ", " + info.base + ", " + info.size + ", " + head + ", " + count + ", " + value, count));
        out.add(new ExprCompiler.OpLine("add", tail, head, count));
        out.add(new ExprCompiler.OpLine("mod", tail, tail, size));
        if(old == null){
            out.add(new ExprCompiler.OpLine("add", ctx.temp(), count, "0"));
        }else{
            // v5 API: 满时结果统一报 -1，成功仍返回新元素个数
            String ok = ctx.temp();
            out.add(new ExprCompiler.OpLine("notEqual", ok, count, old));
            ExprCompiler.emitFailureSelect(out, ctx.temp(), ok, count);
        }
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

    /** 双端队列前端 push：满时返回旧 head；否则 head=(head+size-1)%size，写 base+head，返回新 head。 */
    private static String dequePushFrontBody(){
        Fn fn = new Fn(BUILTIN_DEQUE_PUSH_FRONT, "mem,base,size,head,count,v");
        fn.jump("L_full", "greaterThanEq", "count", "size");
        fn.op("add", "__ls_ct_h", "head", "size");
        fn.op("sub", "__ls_ct_h", "__ls_ct_h", "1");
        fn.op("mod", "__ls_ct_h", "__ls_ct_h", "size");
        fn.op("add", "__ls_ct_a", "base", "__ls_ct_h");
        fn.write("v", "mem", "__ls_ct_a");
        fn.op("add", "__ls_ct_r", "__ls_ct_h", "0");
        fn.jump("L_end", "always", "x", "false");
        fn.label("L_full");
        fn.op("add", "__ls_ct_r", "head", "0");
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
