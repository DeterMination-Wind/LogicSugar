package logicsugar.assist.expr;

import logicsugar.assist.data.ChainModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 链表的表达式扩展（{@code cinit/cclear/cnew/cfree/cget/cset/cnext/clink/cshead/chead/clen}）。
 *
 * <p>第一个实参必须是<b>已声明链表名</b>（{@link ChainModule} 的编译期注册表）。
 * 节点 i 的 value 在 {@code base + 2*i}、next 在 {@code base + 2*i + 1}，
 * {@code next == -1} 为链尾；隐藏状态 {@code __ls_chn_<name>_head}/{@code _free}。</p>
 *
 * <p><b>展开形态</b>：</p>
 * <ul>
 *   <li><b>直线类</b>：{@code chead}/{@code cshead} 只写隐藏变量（{@code op} 链），
 *       {@code cget} 是无分支越界守卫 + {@code read}（越界地址回落到 -1，原版越界读返回
 *       NaN，与 M3/M5 的空容器哨兵一致）；</li>
 *   <li><b>注入函数类</b>：{@code cinit}/{@code cclear}/{@code cnew}/{@code cfree}/
 *       {@code cset}/{@code cnext}/{@code clink}/{@code clen} 展开为对
 *       {@code __ls_builtin_chn*} 的 {@code funccall}。<b>原因</b>：{@link ExprCompiler.WriteLine}
 *       不被 {@code SugarFunctions.emitConditionExpression}/{@code emitReturn} 的分支识别
 *       （它们只处理 OpLine/ReadLine/SensorLine/CallLine/RawLine），写内存的 intrinsic 在
 *       条件/返回表达式里直接发射 write 行会抛 ClassCastException；循环（cinit 建空闲链、
 *       cfree 找前驱、clen 遍历）也只能在函数体里用 jump 回边，normal 模式全程序共享一份，
 *       未使用时不进入产物。{@code cnext} 的「越界返回 -1」若走直线链需要在读到的值上做
 *       NaN 掩码（NaN*0 仍为 NaN，不可靠），放进函数体用分支更省更稳。</li>
 * </ul>
 *
 * <p><b>边界语义</b>：</p>
 * <ul>
 *   <li>{@code cinit}/{@code cclear}：head = -1，空闲链 0→1→…→size-1→-1，返回 size；</li>
 *   <li>{@code cnew}：从空闲链摘一个节点并置其 next = -1，返回节点下标；空闲链空返回 -1；</li>
 *   <li>{@code cfree}：先按 next 链找到 i 的前驱并摘链（i 是头时 head 直接后移），再把 i
 *       挂回空闲链；非法下标返回 0 且不改任何状态，成功返回 1；</li>
 *   <li>{@code cget}：越界返回 NaN（越界读）；{@code cset}：越界不写入并返回 0，成功 1；</li>
 *   <li>{@code cnext}：越界返回 -1；{@code clink}：只校验 i，j 允许 -1（链尾），
 *       越界返回 0，成功 1；{@code cshead}：任何值（含 -1/越界）都接受，返回 1；</li>
 *   <li>{@code clen}：从头遍历计数（O(n)），空链返回 0。</li>
 * </ul>
 *
 * <p><b>必须先初始化</b>：未赋值变量读取为 0，不调用 {@code cinit}/{@code cclear} 直接
 * {@code cnew} 会把 0 号节点当作空闲节点。</p>
 *
 * <p><b>已知限制</b>：{@code cfree} 不检测重复释放（把已在空闲链上的节点再次释放会让空闲链
 * 成环）；{@code clen}/{@code cfree} 的遍历在链上出现环（用户手工 {@code clink} 造成）时
 * 不会终止。链表不变量（next 槽只由本模块写入、指向合法下标或 -1）由使用者维护。</p>
 */
public final class ChainIntrinsics implements ExprIntrinsics.Provider{
    public static final ChainIntrinsics INSTANCE = new ChainIntrinsics();

    /** 注入函数：{@code cinit}/{@code cclear}（重建空闲链，返回 size）。 */
    public static final String BUILTIN_INIT = "__ls_builtin_chninit";
    /** 注入函数：{@code cnew}（返回新的空闲链头，节点 next 置 -1）。 */
    public static final String BUILTIN_NEW = "__ls_builtin_chnnew";
    /** 注入函数：{@code cfree}（摘链 + 挂回空闲链，返回新的链表头；非法下标原样返回旧头）。 */
    public static final String BUILTIN_FREE = "__ls_builtin_chnfree";
    /** 注入函数：{@code cset}（返回 1/0）。 */
    public static final String BUILTIN_SET = "__ls_builtin_chnset";
    /** 注入函数：{@code cnext}（返回 next，越界返回 -1）。 */
    public static final String BUILTIN_NEXT = "__ls_builtin_chnnext";
    /** 注入函数：{@code clink}（返回 1/0）。 */
    public static final String BUILTIN_LINK = "__ls_builtin_chnlink";
    /** 注入函数：{@code clen}（返回节点个数）。 */
    public static final String BUILTIN_LEN = "__ls_builtin_chnlen";

    private static final String[] CALL_NAMES = {
        "cinit", "cclear", "cnew", "cfree", "cget", "cset",
        "cnext", "clink", "cshead", "chead", "clen"
    };

    private ChainIntrinsics(){}

    @Override
    public String[] callNames(){
        return CALL_NAMES;
    }

    @Override
    public int arity(String name){
        switch(name){
            case "cinit":
            case "cclear":
            case "cnew":
            case "chead":
            case "clen":
                return 1;
            case "cfree":
            case "cget":
            case "cnext":
            case "cshead":
                return 2;
            case "cset":
            case "clink":
                return 3;
            default:
                return -1;
        }
    }

    @Override
    public List<ExprCompiler.Line> expandCall(String name, List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        switch(name){
            case "cinit": return init("cinit", args, ctx);
            case "cclear": return init("cclear", args, ctx);
            case "cnew": return newNode(args, ctx);
            case "cfree": return freeNode(args, ctx);
            case "cget": return getValue(args, ctx);
            case "cset": return setValue(args, ctx);
            case "cnext": return nextNode(args, ctx);
            case "clink": return link(args, ctx);
            case "cshead": return setHead(args, ctx);
            case "chead": return head(args, ctx);
            case "clen": return length(args, ctx);
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
        switch(name){
            case "cinit":
            case "cclear":
                return Collections.singletonList(BUILTIN_INIT);
            case "cnew": return Collections.singletonList(BUILTIN_NEW);
            case "cfree": return Collections.singletonList(BUILTIN_FREE);
            case "cset": return Collections.singletonList(BUILTIN_SET);
            case "cnext": return Collections.singletonList(BUILTIN_NEXT);
            case "clink": return Collections.singletonList(BUILTIN_LINK);
            case "clen": return Collections.singletonList(BUILTIN_LEN);
            default: return Collections.emptyList();
        }
    }

    /** 注入函数的 sugar 源文本（每项一个完整 funcdef 块）。 */
    public static List<String> builtinSugar(){
        List<String> result = new ArrayList<>(7);
        result.add(initBody());
        result.add(newBody());
        result.add(freeBody());
        result.add(setBody());
        result.add(nextBody());
        result.add(linkBody());
        result.add(lenBody());
        return result;
    }

    // ===== 展开：直线类 =====

    /** {@code chead(c)}：{@code op add <r> head 0}。 */
    private static List<ExprCompiler.Line> head(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ChainModule.Info info = resolve("chead", args.get(0), ctx);
        List<ExprCompiler.Line> out = new ArrayList<>(1);
        out.add(new ExprCompiler.OpLine("add", ctx.temp(), info.headVar(), "0"));
        return out;
    }

    /** {@code cshead(c, i)}：head = i（不校验），返回 1。 */
    private static List<ExprCompiler.Line> setHead(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ChainModule.Info info = resolve("cshead", args.get(0), ctx);
        String value = ctx.compile(args.get(1));
        List<ExprCompiler.Line> out = new ArrayList<>(2);
        out.add(new ExprCompiler.OpLine("add", info.headVar(), value, "0"));
        out.add(new ExprCompiler.OpLine("add", ctx.temp(), "1", "0"));
        return out;
    }

    /**
     * {@code cget(c, i)}：无分支越界守卫 + 读 value 槽。
     * invalid = (i &lt; 0) || (i &gt;= size)；地址 {@code = base + 2*i - invalid*(base + 2*i + 1)}，
     * invalid 时回落到 -1，原版越界读返回 NaN。
     */
    private static List<ExprCompiler.Line> getValue(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ChainModule.Info info = resolve("cget", args.get(0), ctx);
        String index = ctx.compile(args.get(1));
        String result = ctx.temp();
        String negative = ctx.temp();
        String tooHigh = ctx.temp();
        String invalid = ctx.temp();
        String address = ctx.temp();
        String bump = ctx.temp();
        String base = Integer.toString(info.base);
        List<ExprCompiler.Line> out = new ArrayList<>(9);
        out.add(new ExprCompiler.OpLine("lessThan", negative, index, "0"));
        out.add(new ExprCompiler.OpLine("greaterThanEq", tooHigh, index, Integer.toString(info.size)));
        out.add(new ExprCompiler.OpLine("or", invalid, negative, tooHigh));
        out.add(new ExprCompiler.OpLine("mul", address, index, "2"));
        out.add(new ExprCompiler.OpLine("add", address, base, address));
        out.add(new ExprCompiler.OpLine("add", bump, address, "1"));
        out.add(new ExprCompiler.OpLine("mul", invalid, invalid, bump));
        out.add(new ExprCompiler.OpLine("sub", address, address, invalid));
        out.add(new ExprCompiler.ReadLine(result, info.memory, address));
        return out;
    }

    // ===== 展开：注入函数类 =====

    /** {@code cinit(c)} / {@code cclear(c)}：head = -1、free = 0，重建空闲链，返回 size。 */
    private static List<ExprCompiler.Line> init(String op, List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ChainModule.Info info = resolve(op, args.get(0), ctx);
        List<ExprCompiler.Line> out = new ArrayList<>(3);
        out.add(new ExprCompiler.OpLine("sub", info.headVar(), "0", "1"));
        out.add(new ExprCompiler.OpLine("add", info.freeVar(), "0", "0"));
        out.add(new ExprCompiler.CallLine(BUILTIN_INIT, memoryArgs(info), ctx.temp()));
        return out;
    }

    /**
     * {@code cnew(c)}：先存下旧空闲链头（即返回的节点下标，空链时就是 -1），
     * 注入函数摘链并返回新的空闲链头，调用点把它写回 {@code free}。
     */
    private static List<ExprCompiler.Line> newNode(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ChainModule.Info info = resolve("cnew", args.get(0), ctx);
        String result = ctx.temp();
        String old = ctx.temp();
        List<ExprCompiler.Line> out = new ArrayList<>(3);
        out.add(new ExprCompiler.OpLine("add", old, info.freeVar(), "0"));
        out.add(new ExprCompiler.CallLine(BUILTIN_NEW,
            info.memory + ", " + info.base + ", " + info.freeVar(), info.freeVar()));
        out.add(new ExprCompiler.OpLine("add", result, old, "0"));
        return out;
    }

    /**
     * {@code cfree(c, i)}：注入函数摘链并返回新的链表头（非法下标原样返回旧头），
     * 调用点按有效标志回写 head/free 并返回 1/0。
     */
    private static List<ExprCompiler.Line> freeNode(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ChainModule.Info info = resolve("cfree", args.get(0), ctx);
        String index = ctx.compile(args.get(1));
        String result = ctx.temp();
        String low = ctx.temp();
        String high = ctx.temp();
        String valid = ctx.temp();
        String newHead = ctx.temp();
        String invalid = ctx.temp();
        String take = ctx.temp();
        String keep = ctx.temp();
        List<ExprCompiler.Line> out = new ArrayList<>(10);
        out.add(new ExprCompiler.OpLine("greaterThanEq", low, index, "0"));
        out.add(new ExprCompiler.OpLine("lessThan", high, index, Integer.toString(info.size)));
        out.add(new ExprCompiler.OpLine("land", valid, low, high));
        out.add(new ExprCompiler.CallLine(BUILTIN_FREE,
            memoryArgs(info) + ", " + info.headVar() + ", " + info.freeVar() + ", " + index, newHead));
        out.add(new ExprCompiler.OpLine("add", info.headVar(), newHead, "0"));
        out.add(new ExprCompiler.OpLine("sub", invalid, "1", valid));
        out.add(new ExprCompiler.OpLine("mul", take, index, valid));
        out.add(new ExprCompiler.OpLine("mul", keep, info.freeVar(), invalid));
        out.add(new ExprCompiler.OpLine("add", info.freeVar(), take, keep));
        out.add(new ExprCompiler.OpLine("add", result, valid, "0"));
        return out;
    }

    /** {@code cset(c, i, v)}：注入函数写 value 槽并返回 1/0。 */
    private static List<ExprCompiler.Line> setValue(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ChainModule.Info info = resolve("cset", args.get(0), ctx);
        String index = ctx.compile(args.get(1));
        String value = ctx.compile(args.get(2));
        List<ExprCompiler.Line> out = new ArrayList<>(1);
        out.add(new ExprCompiler.CallLine(BUILTIN_SET,
            memoryArgs(info) + ", " + index + ", " + value, ctx.temp()));
        return out;
    }

    /** {@code cnext(c, i)}：注入函数返回 next，越界返回 -1。 */
    private static List<ExprCompiler.Line> nextNode(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ChainModule.Info info = resolve("cnext", args.get(0), ctx);
        String index = ctx.compile(args.get(1));
        List<ExprCompiler.Line> out = new ArrayList<>(1);
        out.add(new ExprCompiler.CallLine(BUILTIN_NEXT,
            memoryArgs(info) + ", " + index, ctx.temp()));
        return out;
    }

    /** {@code clink(c, i, j)}：注入函数把节点 i 的 next 设为 j 并返回 1/0（j 不校验）。 */
    private static List<ExprCompiler.Line> link(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ChainModule.Info info = resolve("clink", args.get(0), ctx);
        String index = ctx.compile(args.get(1));
        String target = ctx.compile(args.get(2));
        List<ExprCompiler.Line> out = new ArrayList<>(1);
        out.add(new ExprCompiler.CallLine(BUILTIN_LINK,
            memoryArgs(info) + ", " + index + ", " + target, ctx.temp()));
        return out;
    }

    /** {@code clen(c)}：注入函数从头遍历计数，空链返回 0。 */
    private static List<ExprCompiler.Line> length(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ChainModule.Info info = resolve("clen", args.get(0), ctx);
        List<ExprCompiler.Line> out = new ArrayList<>(1);
        out.add(new ExprCompiler.CallLine(BUILTIN_LEN,
            info.memory + ", " + info.base + ", " + info.headVar(), ctx.temp()));
        return out;
    }

    /** 注入函数的实参前缀：{@code mem, base, size}。 */
    private static String memoryArgs(ChainModule.Info info){
        return info.memory + ", " + info.base + ", " + info.size;
    }

    // ===== 解析 =====

    /** 解析第一个实参为已声明链表。 */
    private static ChainModule.Info resolve(String op, ExprCompiler.Node node, ExprIntrinsics.Ctx ctx){
        if(!(node instanceof ExprCompiler.Var var)){
            throw ctx.error(ExprIntrinsics.text("la.err.intrinsic_chain_arg",
                "{0}() expects a declared chain name", op));
        }
        ChainModule.Registry registry = ChainModule.active();
        if(registry == null){
            throw ctx.error(ExprIntrinsics.text("la.err.intrinsic_no_context",
                "{0}() cannot resolve ''{1}'': no chain declaration context", op, var.name));
        }
        ChainModule.Info info = registry.get(var.name);
        if(info == null){
            throw ctx.error(ExprIntrinsics.text("la.err.intrinsic_unknown_chain",
                "{0}() references undeclared chain ''{1}''", op, var.name));
        }
        return info;
    }

    // ===== 注入函数源文本 =====

    /** {@code cinit}/{@code cclear}：next[i] = i+1，再把最后一个节点的 next 改成 -1，返回 size。 */
    private static String initBody(){
        Fn f = new Fn(BUILTIN_INIT, "mem,base,size");
        f.set("__ls_ci_i", "0");
        f.label("L_loop");
        f.jump("L_done", "greaterThanEq", "__ls_ci_i", "size");
        f.op("mul", "__ls_ci_a", "__ls_ci_i", "2");
        f.op("add", "__ls_ci_a", "base", "__ls_ci_a");
        f.op("add", "__ls_ci_n", "__ls_ci_a", "1");
        f.op("add", "__ls_ci_v", "__ls_ci_i", "1");
        f.write("__ls_ci_v", "mem", "__ls_ci_n");
        f.op("add", "__ls_ci_i", "__ls_ci_i", "1");
        f.jump("L_loop", "always", "x", "false");
        f.label("L_done");
        f.op("sub", "__ls_ci_l", "size", "1");
        f.op("mul", "__ls_ci_a", "__ls_ci_l", "2");
        f.op("add", "__ls_ci_a", "base", "__ls_ci_a");
        f.op("add", "__ls_ci_n", "__ls_ci_a", "1");
        f.write("-1", "mem", "__ls_ci_n");
        f.op("add", "__ls_ci_r", "size", "0");
        f.line("return \"__ls_ci_r\"");
        return f.build();
    }

    /** {@code cnew}：空链返回 -1；否则读出 head 的 next 作为新空闲链头，并把该 next 置 -1。 */
    private static String newBody(){
        Fn f = new Fn(BUILTIN_NEW, "mem,base,head");
        f.jump("L_empty", "lessThan", "head", "0");
        f.op("mul", "__ls_ci_a", "head", "2");
        f.op("add", "__ls_ci_a", "base", "__ls_ci_a");
        f.op("add", "__ls_ci_n", "__ls_ci_a", "1");
        f.read("__ls_ci_r", "mem", "__ls_ci_n");
        f.write("-1", "mem", "__ls_ci_n");
        f.jump("L_end", "always", "x", "false");
        f.label("L_empty");
        f.op("sub", "__ls_ci_r", "0", "1");
        f.label("L_end");
        f.line("return \"__ls_ci_r\"");
        return f.build();
    }

    /**
     * {@code cfree}：非法下标原样返回旧 head；否则先算新 head（head == i 时取 next[i]），
     * 再从 head 起找 i 的前驱把 next 接到 next[i]（head == i 时无前驱），最后 next[i] = free。
     */
    private static String freeBody(){
        Fn f = new Fn(BUILTIN_FREE, "mem,base,size,head,free,i");
        f.jump("L_bad", "lessThan", "i", "0");
        f.jump("L_bad", "greaterThanEq", "i", "size");
        // 先读 next[i] 并算好新 head，再写空闲链（否则 next[i] 已被覆盖）
        f.op("mul", "__ls_ci_a", "i", "2");
        f.op("add", "__ls_ci_a", "base", "__ls_ci_a");
        f.op("add", "__ls_ci_n", "__ls_ci_a", "1");
        f.read("__ls_ci_ni", "mem", "__ls_ci_n");
        f.op("equal", "__ls_ci_same", "head", "i");
        f.op("sub", "__ls_ci_d", "__ls_ci_ni", "head");
        f.op("mul", "__ls_ci_d", "__ls_ci_same", "__ls_ci_d");
        f.op("add", "__ls_ci_h", "head", "__ls_ci_d");
        // head == i：i 没有前驱，直接挂回空闲链
        f.jump("L_push", "equal", "__ls_ci_same", "1");
        f.set("__ls_ci_j", "head");
        f.label("L_walk");
        f.jump("L_push", "lessThan", "__ls_ci_j", "0");
        f.op("mul", "__ls_ci_a", "__ls_ci_j", "2");
        f.op("add", "__ls_ci_a", "base", "__ls_ci_a");
        f.op("add", "__ls_ci_n", "__ls_ci_a", "1");
        f.read("__ls_ci_nj", "mem", "__ls_ci_n");
        f.jump("L_found", "equal", "__ls_ci_nj", "i");
        f.set("__ls_ci_j", "__ls_ci_nj");
        f.jump("L_walk", "always", "x", "false");
        f.label("L_found");
        f.op("mul", "__ls_ci_a", "i", "2");
        f.op("add", "__ls_ci_a", "base", "__ls_ci_a");
        f.op("add", "__ls_ci_a", "__ls_ci_a", "1");
        f.read("__ls_ci_t", "mem", "__ls_ci_a");
        f.write("__ls_ci_t", "mem", "__ls_ci_n");
        f.label("L_push");
        f.op("mul", "__ls_ci_a", "i", "2");
        f.op("add", "__ls_ci_a", "base", "__ls_ci_a");
        f.op("add", "__ls_ci_a", "__ls_ci_a", "1");
        f.write("free", "mem", "__ls_ci_a");
        f.op("add", "__ls_ci_r", "__ls_ci_h", "0");
        f.jump("L_end", "always", "x", "false");
        f.label("L_bad");
        f.op("add", "__ls_ci_r", "head", "0");
        f.label("L_end");
        f.line("return \"__ls_ci_r\"");
        return f.build();
    }

    /** {@code cset}：i 越界返回 0，否则写 value 槽并返回 1。 */
    private static String setBody(){
        Fn f = new Fn(BUILTIN_SET, "mem,base,size,i,v");
        f.jump("L_bad", "lessThan", "i", "0");
        f.jump("L_bad", "greaterThanEq", "i", "size");
        f.op("mul", "__ls_ci_a", "i", "2");
        f.op("add", "__ls_ci_a", "base", "__ls_ci_a");
        f.write("v", "mem", "__ls_ci_a");
        f.set("__ls_ci_r", "1");
        f.jump("L_end", "always", "x", "false");
        f.label("L_bad");
        f.set("__ls_ci_r", "0");
        f.label("L_end");
        f.line("return \"__ls_ci_r\"");
        return f.build();
    }

    /** {@code cnext}：i 越界返回 -1，否则读 next 槽。 */
    private static String nextBody(){
        Fn f = new Fn(BUILTIN_NEXT, "mem,base,size,i");
        f.jump("L_bad", "lessThan", "i", "0");
        f.jump("L_bad", "greaterThanEq", "i", "size");
        f.op("mul", "__ls_ci_a", "i", "2");
        f.op("add", "__ls_ci_a", "base", "__ls_ci_a");
        f.op("add", "__ls_ci_a", "__ls_ci_a", "1");
        f.read("__ls_ci_r", "mem", "__ls_ci_a");
        f.jump("L_end", "always", "x", "false");
        f.label("L_bad");
        f.op("sub", "__ls_ci_r", "0", "1");
        f.label("L_end");
        f.line("return \"__ls_ci_r\"");
        return f.build();
    }

    /** {@code clink}：只校验 i，把节点 i 的 next 设为 j（j 允许 -1）。 */
    private static String linkBody(){
        Fn f = new Fn(BUILTIN_LINK, "mem,base,size,i,j");
        f.jump("L_bad", "lessThan", "i", "0");
        f.jump("L_bad", "greaterThanEq", "i", "size");
        f.op("mul", "__ls_ci_a", "i", "2");
        f.op("add", "__ls_ci_a", "base", "__ls_ci_a");
        f.op("add", "__ls_ci_a", "__ls_ci_a", "1");
        f.write("j", "mem", "__ls_ci_a");
        f.set("__ls_ci_r", "1");
        f.jump("L_end", "always", "x", "false");
        f.label("L_bad");
        f.set("__ls_ci_r", "0");
        f.label("L_end");
        f.line("return \"__ls_ci_r\"");
        return f.build();
    }

    /** {@code clen}：从 head 沿 next 遍历计数，空链（head < 0）返回 0。 */
    private static String lenBody(){
        Fn f = new Fn(BUILTIN_LEN, "mem,base,head");
        f.set("__ls_ci_i", "head");
        f.set("__ls_ci_r", "0");
        f.label("L_loop");
        f.jump("L_done", "lessThan", "__ls_ci_i", "0");
        f.op("add", "__ls_ci_r", "__ls_ci_r", "1");
        f.op("mul", "__ls_ci_a", "__ls_ci_i", "2");
        f.op("add", "__ls_ci_a", "base", "__ls_ci_a");
        f.op("add", "__ls_ci_a", "__ls_ci_a", "1");
        f.read("__ls_ci_i", "mem", "__ls_ci_a");
        f.jump("L_loop", "always", "x", "false");
        f.label("L_done");
        f.line("return \"__ls_ci_r\"");
        return f.build();
    }

    /**
     * 注入函数文本构造器（与 {@link ContainerIntrinsics}/{@link ListHeapIntrinsics} 同一约定）：
     * header 是第 0 行，body 从第 1 行开始，末行是函数自身的 blockend。跳转目标先写
     * {@code @LABEL} 占位，build() 时替换为绝对语句下标（LAssembler 的 jump 目标都是文本下标）。
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

        void set(String dest, String value){
            line("set " + dest + " " + value);
        }

        void read(String dest, String memory, String address){
            line("read " + dest + " " + memory + " " + address);
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
