package logicsugar.assist.expr;

import logicsugar.assist.data.ListHeapModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 列表 + 小顶堆的表达式扩展（{@code lappend/lget/lset/linsert/lremove/lfind/lsize}、
 * {@code hpush/hpop/hsize}）。
 *
 * <p>第一个实参必须是<b>已声明结构名</b>（{@link ListHeapModule} 的编译期注册表），
 * 且函数与种类匹配（{@code l*} 只接受 list、{@code h*} 只接受 heap）。</p>
 *
 * <p><b>展开形态</b>：</p>
 * <ul>
 *   <li><b>读/变量类</b>（lget/lsize/hsize）：无分支直线 {@code op}/{@code read} 链。
 *       {@code lget} 的越界守卫用无分支算术：{@code invalid = (i < 0) || (i >= count)}，
 *       地址 {@code = base + i - invalid * (base + i + 1)}，无效下标时地址回落到 -1，
 *       原版 {@code MemoryBlock.read} 对越界地址返回 {@code Double.NaN}；</li>
 *   <li><b>写内存/循环类</b>（lappend/lset/linsert/lremove/lfind/hpush/hpop）：
 *       展开为对注入函数 {@code __ls_builtin_lst*}/{@code __ls_builtin_hep*} 的
 *       {@code funccall}（normal 模式全程序共享一份子程序，未使用时不进入产物）。
 *       <b>原因</b>：{@link ExprCompiler.WriteLine} 不被
 *       {@code SugarFunctions.emitConditionExpression}/{@code emitReturn} 的分支识别
 *       （它们只处理 OpLine/ReadLine/SensorLine/CallLine/RawLine），intrinsic 在条件/返回
 *       表达式里直接发射 write 行会抛 ClassCastException；把 write 与循环放进函数体，
 *       所有表达式上下文（条件/返回/实参/编辑器展开）都走 CallLine 通道。</li>
 * </ul>
 *
 * <p><b>返回值与状态更新</b>：内置函数不能写调用方的隐藏计数变量（库函数体只有参数被
 * 改名，函数体内的名字无法动态指向 {@code __ls_lst_<name>_count}），因此计数用
 * 「函数返回值 + 调用点回写」实现：{@code lappend} 的 CallLine dest 直接是计数变量；
 * {@code linsert}/{@code hpush} 用返回值与旧计数比较得到 1/0 成功标志，再回写计数；
 * {@code lremove}/{@code hpop} 在调用前算好有效标志并递减计数，调用本身是链尾
 * （保证 NaN 结果不被后续 {@code op} 归零——原版 {@code LVar.num()} 把 NaN 视作 0）。</p>
 *
 * <p><b>边界语义</b>：</p>
 * <ul>
 *   <li>{@code lappend}：满（count ≥ size）时不写入并返回当前 count，否则返回 count+1；</li>
 *   <li>{@code lget}：越界返回 NaN；</li>
 *   <li>{@code lset}：越界不写入并返回 0，成功返回 1；</li>
 *   <li>{@code linsert}：满或 i &lt; 0 或 i &gt; count 时不写入并返回 0，成功返回 1；</li>
 *   <li>{@code lremove}：越界返回 NaN，成功返回被删除的值（并左移填补）；</li>
 *   <li>{@code lfind}：返回首个匹配下标，未找到返回 -1；</li>
 *   <li>{@code hpush}：满时不写入并返回 0，成功返回 1；</li>
 *   <li>{@code hpop}：空返回 NaN（{@code op div <tmp> 0 0} 哨兵），否则返回最小值并下滤。</li>
 * </ul>
 *
 * <p>状态变量：{@code __ls_lst_<name>_count} / {@code __ls_hep_<name>_count}（元素个数）。
 * 未初始化时 mlog 读取为 0，因此「初始 0」不需要初始化指令（声明卡不产行）。</p>
 */
public final class ListHeapIntrinsics implements ExprIntrinsics.Provider{
    public static final ListHeapIntrinsics INSTANCE = new ListHeapIntrinsics();

    /** 注入函数：{@code lappend}（返回新 count；满时返回当前 count）。 */
    public static final String BUILTIN_APPEND = "__ls_builtin_lstappend";
    /** 注入函数：{@code lset}（返回 1/0）。 */
    public static final String BUILTIN_SET = "__ls_builtin_lstset";
    /** 注入函数：{@code linsert}（返回新 count；失败时返回当前 count）。 */
    public static final String BUILTIN_INSERT = "__ls_builtin_lstinsert";
    /** 注入函数：{@code lremove}（返回被删除值；越界返回 NaN，不改内存）。 */
    public static final String BUILTIN_REMOVE = "__ls_builtin_lstremove";
    /** 注入函数：{@code lfind}（返回下标；未找到返回 -1）。 */
    public static final String BUILTIN_FIND = "__ls_builtin_lstfind";
    /** 注入函数：{@code hpush}（返回新 count；满时返回当前 count）。 */
    public static final String BUILTIN_PUSH = "__ls_builtin_heppush";
    /** 注入函数：{@code hpop}（返回最小值；空返回 NaN）。 */
    public static final String BUILTIN_POP = "__ls_builtin_heppop";

    private static final String[] CALL_NAMES = {
        "lappend", "lget", "lset", "linsert", "lremove", "lfind", "lsize",
        "hpush", "hpop", "hsize"
    };

    private ListHeapIntrinsics(){}

    @Override
    public String[] callNames(){
        return CALL_NAMES;
    }

    @Override
    public int arity(String name){
        switch(name){
            case "lappend":
            case "lget":
            case "lremove":
            case "lfind":
            case "hpush":
                return 2;
            case "lset":
            case "linsert":
                return 3;
            case "lsize":
            case "hpop":
            case "hsize":
                return 1;
            default:
                return -1;
        }
    }

    @Override
    public List<ExprCompiler.Line> expandCall(String name, List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        switch(name){
            case "lsize": return sizeOp("lsize", args, ListHeapModule.KIND_LIST, ctx);
            case "hsize": return sizeOp("hsize", args, ListHeapModule.KIND_HEAP, ctx);
            case "lget": return listGet(args, ctx);
            case "lappend": return listAppend(args, ctx);
            case "lset": return listSet(args, ctx);
            case "linsert": return listInsert(args, ctx);
            case "lremove": return listRemove(args, ctx);
            case "lfind": return listFind(args, ctx);
            case "hpush": return heapPush(args, ctx);
            case "hpop": return heapPop(args, ctx);
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
            case "lappend": return Collections.singletonList(BUILTIN_APPEND);
            case "lset": return Collections.singletonList(BUILTIN_SET);
            case "linsert": return Collections.singletonList(BUILTIN_INSERT);
            case "lremove": return Collections.singletonList(BUILTIN_REMOVE);
            case "lfind": return Collections.singletonList(BUILTIN_FIND);
            case "hpush": return Collections.singletonList(BUILTIN_PUSH);
            case "hpop": return Collections.singletonList(BUILTIN_POP);
            default: return Collections.emptyList();
        }
    }

    /** 注入函数的 sugar 源文本（每项一个完整 funcdef 块）。 */
    public static List<String> builtinSugar(){
        List<String> result = new ArrayList<>(7);
        result.add(appendBody());
        result.add(setBody());
        result.add(insertBody());
        result.add(removeBody());
        result.add(findBody());
        result.add(pushBody());
        result.add(popBody());
        return result;
    }

    // ===== 列表展开 =====

    /** {@code lsize(l)}：{@code op add <r> count 0}。 */
    private static List<ExprCompiler.Line> sizeOp(String op, List<ExprCompiler.Node> args, String kind, ExprIntrinsics.Ctx ctx){
        ListHeapModule.Info info = resolve(op, args.get(0), kind, ctx);
        List<ExprCompiler.Line> out = new ArrayList<>(1);
        out.add(new ExprCompiler.OpLine("add", ctx.temp(), info.countVar(), "0"));
        return out;
    }

    /**
     * {@code lget(l, i)}：无分支越界守卫 + 越界读 → NaN。
     * 地址 {@code = base + i - invalid * (base + i + 1)}，invalid 时落到 -1。
     */
    private static List<ExprCompiler.Line> listGet(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ListHeapModule.Info info = resolve("lget", args.get(0), ListHeapModule.KIND_LIST, ctx);
        String index = ctx.compile(args.get(1));
        String count = info.countVar();
        List<ExprCompiler.Line> out = new ArrayList<>();
        String negative = ctx.temp();
        out.add(new ExprCompiler.OpLine("lessThan", negative, index, "0"));
        String tooHigh = ctx.temp();
        out.add(new ExprCompiler.OpLine("lessThanEq", tooHigh, count, index));
        String invalid = ctx.temp();
        out.add(new ExprCompiler.OpLine("or", invalid, negative, tooHigh));
        String address = ctx.temp();
        out.add(new ExprCompiler.OpLine("add", address, String.valueOf(info.base), index));
        String bump = ctx.temp();
        out.add(new ExprCompiler.OpLine("add", bump, address, "1"));
        out.add(new ExprCompiler.OpLine("mul", invalid, invalid, bump));
        out.add(new ExprCompiler.OpLine("sub", address, address, invalid));
        out.add(new ExprCompiler.ReadLine(ctx.temp(), info.memory, address));
        return out;
    }

    /** {@code lappend(l, v)}：注入函数返回新 count，CallLine dest 直接写回计数变量。 */
    private static List<ExprCompiler.Line> listAppend(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ListHeapModule.Info info = resolve("lappend", args.get(0), ListHeapModule.KIND_LIST, ctx);
        String value = ctx.compile(args.get(1));
        List<ExprCompiler.Line> out = new ArrayList<>(1);
        out.add(new ExprCompiler.CallLine(BUILTIN_APPEND,
            info.memory + ", " + info.base + ", " + info.size + ", " + info.countVar() + ", " + value,
            info.countVar()));
        return out;
    }

    /** {@code lset(l, i, v)}：注入函数返回 1/0。 */
    private static List<ExprCompiler.Line> listSet(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ListHeapModule.Info info = resolve("lset", args.get(0), ListHeapModule.KIND_LIST, ctx);
        String index = ctx.compile(args.get(1));
        String value = ctx.compile(args.get(2));
        List<ExprCompiler.Line> out = new ArrayList<>(1);
        out.add(new ExprCompiler.CallLine(BUILTIN_SET,
            info.memory + ", " + info.base + ", " + info.countVar() + ", " + index + ", " + value,
            ctx.temp()));
        return out;
    }

    /** {@code linsert(l, i, v)}：函数返回新 count（失败为旧 count），调用点换算 1/0 并回写计数。 */
    private static List<ExprCompiler.Line> listInsert(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ListHeapModule.Info info = resolve("linsert", args.get(0), ListHeapModule.KIND_LIST, ctx);
        String index = ctx.compile(args.get(1));
        String value = ctx.compile(args.get(2));
        String count = info.countVar();
        List<ExprCompiler.Line> out = new ArrayList<>(4);
        String old = ctx.temp();
        out.add(new ExprCompiler.OpLine("add", old, count, "0"));
        out.add(new ExprCompiler.CallLine(BUILTIN_INSERT,
            info.memory + ", " + info.base + ", " + info.size + ", " + count + ", " + index + ", " + value,
            count));
        String ok = ctx.temp();
        out.add(new ExprCompiler.OpLine("notEqual", ok, count, old));
        out.add(new ExprCompiler.OpLine("add", ctx.temp(), ok, "0"));
        return out;
    }

    /**
     * {@code lremove(l, i)}：调用前算有效标志并递减计数（原版 num() 会把 NaN 归零，
     * 所以返回被删除值的 CallLine 必须是链尾），函数返回被删除值或 NaN。
     */
    private static List<ExprCompiler.Line> listRemove(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ListHeapModule.Info info = resolve("lremove", args.get(0), ListHeapModule.KIND_LIST, ctx);
        String index = ctx.compile(args.get(1));
        String count = info.countVar();
        List<ExprCompiler.Line> out = new ArrayList<>(6);
        String old = ctx.temp();
        out.add(new ExprCompiler.OpLine("add", old, count, "0"));
        String negative = ctx.temp();
        out.add(new ExprCompiler.OpLine("lessThan", negative, index, "0"));
        String tooHigh = ctx.temp();
        out.add(new ExprCompiler.OpLine("greaterThanEq", tooHigh, index, old));
        String invalid = ctx.temp();
        out.add(new ExprCompiler.OpLine("or", invalid, negative, tooHigh));
        String valid = ctx.temp();
        out.add(new ExprCompiler.OpLine("sub", valid, "1", invalid));
        out.add(new ExprCompiler.OpLine("sub", count, old, valid));
        out.add(new ExprCompiler.CallLine(BUILTIN_REMOVE,
            info.memory + ", " + info.base + ", " + old + ", " + index, ctx.temp()));
        return out;
    }

    /** {@code lfind(l, v)}：注入函数返回首个匹配下标或 -1。 */
    private static List<ExprCompiler.Line> listFind(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ListHeapModule.Info info = resolve("lfind", args.get(0), ListHeapModule.KIND_LIST, ctx);
        String value = ctx.compile(args.get(1));
        List<ExprCompiler.Line> out = new ArrayList<>(1);
        out.add(new ExprCompiler.CallLine(BUILTIN_FIND,
            info.memory + ", " + info.base + ", " + info.countVar() + ", " + value, ctx.temp()));
        return out;
    }

    // ===== 小顶堆展开 =====

    /** {@code hpush(h, v)}：函数返回新 count（失败为旧 count），调用点换算 1/0 并回写计数。 */
    private static List<ExprCompiler.Line> heapPush(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ListHeapModule.Info info = resolve("hpush", args.get(0), ListHeapModule.KIND_HEAP, ctx);
        String value = ctx.compile(args.get(1));
        String count = info.countVar();
        List<ExprCompiler.Line> out = new ArrayList<>(4);
        String old = ctx.temp();
        out.add(new ExprCompiler.OpLine("add", old, count, "0"));
        out.add(new ExprCompiler.CallLine(BUILTIN_PUSH,
            info.memory + ", " + info.base + ", " + info.size + ", " + count + ", " + value, count));
        String ok = ctx.temp();
        out.add(new ExprCompiler.OpLine("notEqual", ok, count, old));
        out.add(new ExprCompiler.OpLine("add", ctx.temp(), ok, "0"));
        return out;
    }

    /** {@code hpop(h)}：调用前递减计数，返回最小值的 CallLine 是链尾（空 → NaN 不被归零）。 */
    private static List<ExprCompiler.Line> heapPop(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        ListHeapModule.Info info = resolve("hpop", args.get(0), ListHeapModule.KIND_HEAP, ctx);
        String count = info.countVar();
        List<ExprCompiler.Line> out = new ArrayList<>(4);
        String old = ctx.temp();
        out.add(new ExprCompiler.OpLine("add", old, count, "0"));
        String nonEmpty = ctx.temp();
        out.add(new ExprCompiler.OpLine("greaterThan", nonEmpty, old, "0"));
        out.add(new ExprCompiler.OpLine("sub", count, old, nonEmpty));
        out.add(new ExprCompiler.CallLine(BUILTIN_POP,
            info.memory + ", " + info.base + ", " + info.size + ", " + old, ctx.temp()));
        return out;
    }

    // ===== 解析 =====

    /** 解析第一个实参为已声明结构，并校验种类匹配。 */
    private static ListHeapModule.Info resolve(String op, ExprCompiler.Node node, String expectedKind, ExprIntrinsics.Ctx ctx){
        if(!(node instanceof ExprCompiler.Var var)){
            throw ctx.error(ExprIntrinsics.text("la.err.intrinsic_listheap_arg",
                "{0}() expects a declared {1} name", op, expectedKind));
        }
        ListHeapModule.Registry registry = ListHeapModule.active();
        if(registry == null){
            throw ctx.error(ExprIntrinsics.text("la.err.intrinsic_no_context",
                "{0}() cannot resolve ''{1}'': no list/heap declaration context", op, var.name));
        }
        ListHeapModule.Info info = registry.get(var.name);
        if(info == null){
            throw ctx.error(ExprIntrinsics.text("la.err.intrinsic_unknown_listheap",
                "{0}() references undeclared list or heap ''{1}''", op, var.name));
        }
        if(!expectedKind.equals(info.kind)){
            throw ctx.error(ExprIntrinsics.text("la.err.intrinsic_listheap_kind",
                "{0}() expects a {1} but ''{2}'' is a {3}", op, expectedKind, var.name, info.kind));
        }
        return info;
    }

    // ===== 注入函数源文本 =====

    /** {@code lappend}：满时跳过 write 直接返回 count，否则写 base+count 并返回 count+1。 */
    private static String appendBody(){
        Fn f = new Fn(BUILTIN_APPEND, "mem,base,size,count,v");
        f.jump("L_full", "greaterThanEq", "count", "size");
        f.op("add", "__ls_lh_a", "base", "count");
        f.write("v", "mem", "__ls_lh_a");
        f.op("add", "__ls_lh_r", "count", "1");
        f.jump("L_end", "always", "x", "false");
        f.label("L_full");
        f.op("add", "__ls_lh_r", "count", "0");
        f.label("L_end");
        f.line("return \"__ls_lh_r\"");
        return f.build();
    }

    /** {@code lset}：i 越界返回 0，否则写入并返回 1。 */
    private static String setBody(){
        Fn f = new Fn(BUILTIN_SET, "mem,base,count,i,v");
        f.jump("L_bad", "lessThan", "i", "0");
        f.jump("L_bad", "greaterThanEq", "i", "count");
        f.op("add", "__ls_lh_a", "base", "i");
        f.write("v", "mem", "__ls_lh_a");
        f.set("__ls_lh_r", "1");
        f.jump("L_end", "always", "x", "false");
        f.label("L_bad");
        f.set("__ls_lh_r", "0");
        f.label("L_end");
        f.line("return \"__ls_lh_r\"");
        return f.build();
    }

    /** {@code linsert}：满/越界返回 count（调用点据此换算 0），否则右移一段并写入，返回 count+1。 */
    private static String insertBody(){
        Fn f = new Fn(BUILTIN_INSERT, "mem,base,size,count,i,v");
        f.jump("L_bad", "greaterThanEq", "count", "size");
        f.jump("L_bad", "lessThan", "i", "0");
        f.jump("L_bad", "greaterThan", "i", "count");
        f.set("__ls_lh_j", "count");
        f.label("L_loop");
        f.jump("L_place", "lessThanEq", "__ls_lh_j", "i");
        f.op("sub", "__ls_lh_jm", "__ls_lh_j", "1");
        f.op("add", "__ls_lh_ja", "base", "__ls_lh_j");
        f.op("add", "__ls_lh_jma", "base", "__ls_lh_jm");
        f.read("__ls_lh_t", "mem", "__ls_lh_jma");
        f.write("__ls_lh_t", "mem", "__ls_lh_ja");
        f.set("__ls_lh_j", "__ls_lh_jm");
        f.jump("L_loop", "always", "x", "false");
        f.label("L_place");
        f.op("add", "__ls_lh_ia", "base", "i");
        f.write("v", "mem", "__ls_lh_ia");
        f.op("add", "__ls_lh_r", "count", "1");
        f.jump("L_end", "always", "x", "false");
        f.label("L_bad");
        f.op("add", "__ls_lh_r", "count", "0");
        f.label("L_end");
        f.line("return \"__ls_lh_r\"");
        return f.build();
    }

    /** {@code lremove}：越界返回 NaN（{@code op div 0 0}），否则读出被删值、左移填补。 */
    private static String removeBody(){
        Fn f = new Fn(BUILTIN_REMOVE, "mem,base,count,i");
        f.jump("L_bad", "lessThan", "i", "0");
        f.jump("L_bad", "greaterThanEq", "i", "count");
        f.op("add", "__ls_lh_ia", "base", "i");
        f.read("__ls_lh_r", "mem", "__ls_lh_ia");
        f.set("__ls_lh_j", "i");
        f.op("sub", "__ls_lh_last", "count", "1");
        f.label("L_loop");
        f.jump("L_done", "greaterThanEq", "__ls_lh_j", "__ls_lh_last");
        f.op("add", "__ls_lh_jn", "__ls_lh_j", "1");
        f.op("add", "__ls_lh_ja", "base", "__ls_lh_j");
        f.op("add", "__ls_lh_jna", "base", "__ls_lh_jn");
        f.read("__ls_lh_t", "mem", "__ls_lh_jna");
        f.write("__ls_lh_t", "mem", "__ls_lh_ja");
        f.set("__ls_lh_j", "__ls_lh_jn");
        f.jump("L_loop", "always", "x", "false");
        f.label("L_done");
        f.jump("L_end", "always", "x", "false");
        f.label("L_bad");
        f.op("div", "__ls_lh_r", "0", "0");
        f.label("L_end");
        f.line("return \"__ls_lh_r\"");
        return f.build();
    }

    /** {@code lfind}：顺序扫描，返回首个匹配下标，未找到 -1。 */
    private static String findBody(){
        Fn f = new Fn(BUILTIN_FIND, "mem,base,count,v");
        f.set("__ls_lh_r", "-1");
        f.set("__ls_lh_i", "0");
        f.label("L_loop");
        f.jump("L_done", "greaterThanEq", "__ls_lh_i", "count");
        f.op("add", "__ls_lh_a", "base", "__ls_lh_i");
        f.read("__ls_lh_t", "mem", "__ls_lh_a");
        f.jump("L_next", "notEqual", "__ls_lh_t", "v");
        f.set("__ls_lh_r", "__ls_lh_i");
        f.jump("L_done", "always", "x", "false");
        f.label("L_next");
        f.op("add", "__ls_lh_i", "__ls_lh_i", "1");
        f.jump("L_loop", "always", "x", "false");
        f.label("L_done");
        f.line("return \"__ls_lh_r\"");
        return f.build();
    }

    /** {@code hpush}：满时返回 count；否则上滤（父节点下沉），最后把 v 放到空位。 */
    private static String pushBody(){
        Fn f = new Fn(BUILTIN_PUSH, "mem,base,size,count,v");
        f.jump("L_full", "greaterThanEq", "count", "size");
        f.set("__ls_lh_i", "count");
        f.label("L_loop");
        f.jump("L_place", "lessThanEq", "__ls_lh_i", "0");
        f.op("sub", "__ls_lh_p", "__ls_lh_i", "1");
        f.op("idiv", "__ls_lh_p", "__ls_lh_p", "2");
        f.op("add", "__ls_lh_pa", "base", "__ls_lh_p");
        f.read("__ls_lh_pv", "mem", "__ls_lh_pa");
        f.jump("L_place", "lessThanEq", "__ls_lh_pv", "v");
        f.op("add", "__ls_lh_ia", "base", "__ls_lh_i");
        f.write("__ls_lh_pv", "mem", "__ls_lh_ia");
        f.set("__ls_lh_i", "__ls_lh_p");
        f.jump("L_loop", "always", "x", "false");
        f.label("L_place");
        f.op("add", "__ls_lh_ia2", "base", "__ls_lh_i");
        f.write("v", "mem", "__ls_lh_ia2");
        f.op("add", "__ls_lh_r", "count", "1");
        f.jump("L_end", "always", "x", "false");
        f.label("L_full");
        f.op("add", "__ls_lh_r", "count", "0");
        f.label("L_end");
        f.line("return \"__ls_lh_r\"");
        return f.build();
    }

    /** {@code hpop}：空返回 NaN；否则取根、把末元素放到根再下滤。 */
    private static String popBody(){
        Fn f = new Fn(BUILTIN_POP, "mem,base,size,count");
        f.jump("L_empty", "lessThanEq", "count", "0");
        f.op("add", "__ls_lh_a0", "base", "0");
        f.read("__ls_lh_min", "mem", "__ls_lh_a0");
        f.op("sub", "__ls_lh_n", "count", "1");
        f.jump("L_done", "lessThanEq", "__ls_lh_n", "0");
        f.op("add", "__ls_lh_la", "base", "__ls_lh_n");
        f.read("__ls_lh_last", "mem", "__ls_lh_la");
        f.write("__ls_lh_last", "mem", "__ls_lh_a0");
        f.set("__ls_lh_i", "0");
        f.label("L_loop");
        f.op("mul", "__ls_lh_l", "__ls_lh_i", "2");
        f.op("add", "__ls_lh_l", "__ls_lh_l", "1");
        f.jump("L_done", "greaterThanEq", "__ls_lh_l", "__ls_lh_n");
        f.op("add", "__ls_lh_s", "__ls_lh_l", "0");
        f.op("add", "__ls_lh_rr", "__ls_lh_l", "1");
        f.jump("L_cmp", "greaterThanEq", "__ls_lh_rr", "__ls_lh_n");
        f.op("add", "__ls_lh_ra", "base", "__ls_lh_rr");
        f.read("__ls_lh_rv", "mem", "__ls_lh_ra");
        f.op("add", "__ls_lh_sa", "base", "__ls_lh_s");
        f.read("__ls_lh_sv", "mem", "__ls_lh_sa");
        f.jump("L_cmp", "lessThanEq", "__ls_lh_sv", "__ls_lh_rv");
        f.set("__ls_lh_s", "__ls_lh_rr");
        f.label("L_cmp");
        f.op("add", "__ls_lh_sa", "base", "__ls_lh_s");
        f.read("__ls_lh_sv", "mem", "__ls_lh_sa");
        f.op("add", "__ls_lh_ia", "base", "__ls_lh_i");
        f.read("__ls_lh_iv", "mem", "__ls_lh_ia");
        f.jump("L_done", "greaterThanEq", "__ls_lh_sv", "__ls_lh_iv");
        f.write("__ls_lh_sv", "mem", "__ls_lh_ia");
        f.write("__ls_lh_iv", "mem", "__ls_lh_sa");
        f.set("__ls_lh_i", "__ls_lh_s");
        f.jump("L_loop", "always", "x", "false");
        f.label("L_done");
        f.op("add", "__ls_lh_r", "__ls_lh_min", "0");
        f.jump("L_end", "always", "x", "false");
        f.label("L_empty");
        f.op("div", "__ls_lh_r", "0", "0");
        f.label("L_end");
        f.line("return \"__ls_lh_r\"");
        return f.build();
    }

    /**
     * 注入函数文本构造器（与 {@link ArrayBulkIntrinsics} 同一约定）：header 是第 0 行，
     * body 从第 1 行开始，末行是函数自身的 blockend。跳转目标先写 {@code @LABEL} 占位，
     * build() 时替换为绝对语句下标（LAssembler 的 jump 目标都是文本下标）。
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
