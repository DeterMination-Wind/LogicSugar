package logicsugar.assist.expr;

import logicsugar.assist.data.MapModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 哈希表的表达式扩展：{@code mapset/mapget/maphas/mapdel/mapsize/mapclear}。
 *
 * <p>实参必须是<b>已声明哈希表名</b>（{@link MapModule} 的 {@code map} 卡）。编译期解析出
 * memory/base/capacity 后，展开为对注入函数 {@code __ls_builtin_map*} 的 {@code funccall}：
 * 循环体（线性探测）在 normal 模式下全程序共享一份，实参是内存块变量名与字面量
 * base/capacity，因此同一个内置函数服务所有哈希表。</p>
 *
 * <p><b>布局与探测</b>（契约 §4）：key 区 {@code [base, base+capacity)}、value 区
 * {@code [base+capacity, base+2*capacity)}；{@code hash = abs(key) % capacity}，
 * 线性探测。探测是整表环形扫描（O(capacity)），不因删除留下的墓碑而中断，
 * 因此删除只需把 key 槽置为 NaN 标记（墓碑策略，无需回填/重插）。</p>
 *
 * <p><b>空槽判定</b>：{@code op div <nan> 0 0} 生成 NaN 标记；判定"槽为空"用
 * {@code op strictEqual <t> <key> <nan>}。当前 BE 运行时把 NaN 存进内存后再读回是
 * <b>null 对象</b>（{@code LVar.isobj = true, objval = null}）：strictEqual 对两个 null
 * 对象判等、对"数字 vs 对象"判不等，因此该测试恰好等价于"槽里是 NaN 标记"。不能用
 * {@code op equal}：数字 0 与 null 对象在 equal 里会走数值路径判为相等。</p>
 *
 * <p><b>初始化</b>：未初始化内存槽读回数字 0（不是 NaN），会被当作"已占用且 key = 0"。
 * 哈希表首次使用前必须调用 {@code mapclear(m)} 把全部 key 槽写成 NaN 标记。</p>
 *
 * <p><b>已知限制</b>：字符串键不支持；NaN/±Inf 键会被拒绝（set 返回 -1、get 返回 NaN、
 * has/del 返回 0）；键比较沿用原版 {@code equal} 的 1e-6 容差；value 经 funcdef 返回值
 * 通道回传，对象值会退化为 1/0（与所有注入函数一致）。</p>
 */
public final class MapIntrinsics implements ExprIntrinsics.Provider{
    public static final MapIntrinsics INSTANCE = new MapIntrinsics();

    /** 注入函数名（统一 __ls_builtin_ 前缀，不进入用户函数库）。 */
    public static final String BUILTIN_SET = "__ls_builtin_mapset";
    public static final String BUILTIN_GET = "__ls_builtin_mapget";
    public static final String BUILTIN_HAS = "__ls_builtin_maphas";
    public static final String BUILTIN_DEL = "__ls_builtin_mapdel";
    public static final String BUILTIN_SIZE = "__ls_builtin_mapsize";
    public static final String BUILTIN_CLEAR = "__ls_builtin_mapclear";

    private static final String[] CALL_NAMES = {
        "mapset", "mapget", "maphas", "mapdel", "mapsize", "mapclear"
    };

    // 内置函数局部变量（__ls_ 前缀，编译器保留；不参与 mangle，函数间不互相调用因此可共享）
    private static final String NAN = "__ls_mp_nan";
    private static final String BAD = "__ls_mp_bad";
    private static final String H = "__ls_mp_h";
    private static final String I = "__ls_mp_i";
    private static final String N = "__ls_mp_n";
    private static final String A = "__ls_mp_a";
    private static final String A2 = "__ls_mp_a2";
    private static final String VA = "__ls_mp_va";
    private static final String VA2 = "__ls_mp_va2";
    private static final String K = "__ls_mp_k";
    private static final String E = "__ls_mp_e";
    private static final String FREE = "__ls_mp_free";
    private static final String RES = "__ls_mp_res";
    private static final String CNT = "__ls_mp_cnt";

    private MapIntrinsics(){}

    @Override
    public String[] callNames(){
        return CALL_NAMES;
    }

    @Override
    public int arity(String name){
        switch(name){
            case "mapset":
                return 3;
            case "mapget":
            case "maphas":
            case "mapdel":
                return 2;
            case "mapsize":
            case "mapclear":
                return 1;
            default:
                return -1;
        }
    }

    @Override
    public List<ExprCompiler.Line> expandCall(String name, List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        switch(name){
            case "mapset": return call(BUILTIN_SET, name, args, ctx, 2);
            case "mapget": return call(BUILTIN_GET, name, args, ctx, 1);
            case "maphas": return call(BUILTIN_HAS, name, args, ctx, 1);
            case "mapdel": return call(BUILTIN_DEL, name, args, ctx, 1);
            case "mapsize": return call(BUILTIN_SIZE, name, args, ctx, 0);
            case "mapclear": return call(BUILTIN_CLEAR, name, args, ctx, 0);
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
        String builtin = builtinOf(name);
        return builtin == null ? Collections.emptyList() : Collections.singletonList(builtin);
    }

    /** 注入函数的 sugar 源文本（每项一个完整 funcdef 块）。 */
    public static List<String> builtinSugar(){
        List<String> result = new ArrayList<>();
        result.add(set());
        result.add(get());
        result.add(has());
        result.add(del());
        result.add(size());
        result.add(clear());
        return result;
    }

    private static String builtinOf(String name){
        switch(name){
            case "mapset": return BUILTIN_SET;
            case "mapget": return BUILTIN_GET;
            case "maphas": return BUILTIN_HAS;
            case "mapdel": return BUILTIN_DEL;
            case "mapsize": return BUILTIN_SIZE;
            case "mapclear": return BUILTIN_CLEAR;
            default: return null;
        }
    }

    // ===== 展开 =====

    /** 解析第一个实参（已声明哈希表名），拼出 {memory, base, capacity} 操作数并发射 funccall。 */
    private static List<ExprCompiler.Line> call(String builtin, String op, List<ExprCompiler.Node> args,
                                                ExprIntrinsics.Ctx ctx, int extraArgs){
        MapModule.MapInfo info = mapOf(op, args.get(0), ctx);
        List<String> operands = new ArrayList<>();
        operands.add(info.memory);
        operands.add(String.valueOf(info.base));
        operands.add(String.valueOf(info.capacity));
        for(int i = 0; i < extraArgs; i++){
            operands.add(ctx.compile(args.get(i + 1)));
        }
        List<ExprCompiler.Line> lines = new ArrayList<>(1);
        lines.add(new ExprCompiler.CallLine(builtin, String.join(", ", operands), ctx.temp()));
        return lines;
    }

    /** 解析「已声明哈希表名」实参。 */
    private static MapModule.MapInfo mapOf(String op, ExprCompiler.Node node, ExprIntrinsics.Ctx ctx){
        if(!(node instanceof ExprCompiler.Var var)){
            throw ctx.error(ExprIntrinsics.text("la.err.intrinsic_map_arg",
                "{0}() expects a declared map name", op));
        }
        MapModule.MapInfo info = MapModule.active(var.name);
        if(info != null) return info;
        if(!MapModule.hasContext()){
            throw ctx.error(ExprIntrinsics.text("la.err.intrinsic_map_no_context",
                "{0}() cannot resolve '{1}': no map declaration context", op, var.name));
        }
        throw ctx.error(ExprIntrinsics.text("la.err.intrinsic_unknown_map",
            "{0}() references undeclared map '{1}'", op, var.name));
    }

    // ===== 注入函数源文本 =====

    /**
     * {@code mapset(mem, base, cap, key, value)}：写入或更新。
     * 返回 1（成功）或 -1（表满 / 键不是有限数字）。整表扫描：先找同 key 更新，
     * 否则用遇到的第一个空槽插入；空槽位置必须扫完整表后才知道（墓碑后可能还有同 key）。
     */
    private static String set(){
        Fn f = new Fn(BUILTIN_SET, "mem,base,cap,key,value");
        guard(f, "REJECT");
        f.op("abs", H, "key", "0");
        f.op("mod", I, H, "cap");
        f.set(N, "0");
        f.set(FREE, "-1");
        f.label("LOOP");
        f.jump("AFTER", "greaterThanEq", N, "cap");
        f.op("add", A, "base", I);
        f.read(K, "mem", A);
        f.op("strictEqual", E, K, NAN);
        f.jump("OCCUPIED", "equal", E, "0");
        f.jump("NEXT", "notEqual", FREE, "-1");
        f.set(FREE, I);
        f.jump("NEXT", "always", "x", "false");
        f.label("OCCUPIED");
        f.jump("FOUND", "equal", K, "key");
        f.label("NEXT");
        f.op("add", I, I, "1");
        f.op("mod", I, I, "cap");
        f.op("add", N, N, "1");
        f.jump("LOOP", "always", "x", "false");
        f.label("FOUND");
        f.op("add", VA, A, "cap");
        f.write("value", "mem", VA);
        f.set(RES, "1");
        f.ret(RES);
        f.label("AFTER");
        f.jump("FULL", "lessThan", FREE, "0");
        f.op("add", A2, "base", FREE);
        f.write("key", "mem", A2);
        f.op("add", VA2, A2, "cap");
        f.write("value", "mem", VA2);
        f.set(RES, "1");
        f.ret(RES);
        f.label("FULL");
        f.set(RES, "-1");
        f.ret(RES);
        f.label("REJECT");
        f.set(RES, "-1");
        f.ret(RES);
        return f.build();
    }

    /** {@code mapget(mem, base, cap, key)}：命中返回 value，未命中返回 NaN。 */
    private static String get(){
        Fn f = new Fn(BUILTIN_GET, "mem,base,cap,key");
        guard(f, "MISS");
        f.op("abs", H, "key", "0");
        f.op("mod", I, H, "cap");
        f.set(N, "0");
        f.label("LOOP");
        f.jump("MISS", "greaterThanEq", N, "cap");
        f.op("add", A, "base", I);
        f.read(K, "mem", A);
        f.op("strictEqual", E, K, NAN);
        f.jump("NEXT", "equal", E, "1");
        f.jump("HIT", "equal", K, "key");
        f.label("NEXT");
        f.op("add", I, I, "1");
        f.op("mod", I, I, "cap");
        f.op("add", N, N, "1");
        f.jump("LOOP", "always", "x", "false");
        f.label("HIT");
        f.op("add", VA, A, "cap");
        f.read(RES, "mem", VA);
        f.ret(RES);
        f.label("MISS");
        f.line("return \"0 / 0\"");
        return f.build();
    }

    /** {@code maphas(mem, base, cap, key)}：存在返回 1，否则 0。 */
    private static String has(){
        Fn f = new Fn(BUILTIN_HAS, "mem,base,cap,key");
        guard(f, "MISS");
        f.op("abs", H, "key", "0");
        f.op("mod", I, H, "cap");
        f.set(N, "0");
        f.label("LOOP");
        f.jump("MISS", "greaterThanEq", N, "cap");
        f.op("add", A, "base", I);
        f.read(K, "mem", A);
        f.op("strictEqual", E, K, NAN);
        f.jump("NEXT", "equal", E, "1");
        f.jump("HIT", "equal", K, "key");
        f.label("NEXT");
        f.op("add", I, I, "1");
        f.op("mod", I, I, "cap");
        f.op("add", N, N, "1");
        f.jump("LOOP", "always", "x", "false");
        f.label("HIT");
        f.set(RES, "1");
        f.ret(RES);
        f.label("MISS");
        f.set(RES, "0");
        f.ret(RES);
        return f.build();
    }

    /**
     * {@code mapdel(mem, base, cap, key)}：删除返回 1，未找到返回 0。
     * 墓碑策略：只把 key 槽写成 NaN 标记（value 槽保持原值），探测是整表扫描，
     * 墓碑不会截断探测链，因此无需回填/重插。
     */
    private static String del(){
        Fn f = new Fn(BUILTIN_DEL, "mem,base,cap,key");
        guard(f, "MISS");
        f.op("abs", H, "key", "0");
        f.op("mod", I, H, "cap");
        f.set(N, "0");
        f.label("LOOP");
        f.jump("MISS", "greaterThanEq", N, "cap");
        f.op("add", A, "base", I);
        f.read(K, "mem", A);
        f.op("strictEqual", E, K, NAN);
        f.jump("NEXT", "equal", E, "1");
        f.jump("HIT", "equal", K, "key");
        f.label("NEXT");
        f.op("add", I, I, "1");
        f.op("mod", I, I, "cap");
        f.op("add", N, N, "1");
        f.jump("LOOP", "always", "x", "false");
        f.label("HIT");
        f.write(NAN, "mem", A);
        f.set(RES, "1");
        f.ret(RES);
        f.label("MISS");
        f.set(RES, "0");
        f.ret(RES);
        return f.build();
    }

    /** {@code mapsize(mem, base, cap)}：统计非空 key 个数（O(capacity)）。 */
    private static String size(){
        Fn f = new Fn(BUILTIN_SIZE, "mem,base,cap");
        f.op("div", NAN, "0", "0");
        f.set(CNT, "0");
        f.set(N, "0");
        f.label("LOOP");
        f.jump("DONE", "greaterThanEq", N, "cap");
        f.op("add", A, "base", N);
        f.read(K, "mem", A);
        f.op("strictEqual", E, K, NAN);
        f.op("sub", E, "1", E);
        f.op("add", CNT, CNT, E);
        f.op("add", N, N, "1");
        f.jump("LOOP", "always", "x", "false");
        f.label("DONE");
        f.ret(CNT);
        return f.build();
    }

    /** {@code mapclear(mem, base, cap)}：把所有 key 槽写成 NaN 标记并返回 0。 */
    private static String clear(){
        Fn f = new Fn(BUILTIN_CLEAR, "mem,base,cap");
        f.op("div", NAN, "0", "0");
        f.set(N, "0");
        f.label("LOOP");
        f.jump("DONE", "greaterThanEq", N, "cap");
        f.op("add", A, "base", N);
        f.write(NAN, "mem", A);
        f.op("add", N, N, "1");
        f.jump("LOOP", "always", "x", "false");
        f.label("DONE");
        f.line("return \"0\"");
        return f.build();
    }

    /** 键必须是有限数字：NaN/±Inf 在运行时都是 null 对象，一律拒绝（跳到各自的结果标签）。 */
    private static void guard(Fn f, String target){
        f.op("div", NAN, "0", "0");
        f.op("strictEqual", BAD, "key", NAN);
        f.jump(target, "equal", BAD, "1");
    }

    /**
     * 注入函数文本构造器：header 是第 0 行，body 从第 1 行开始，末行是函数自身的 blockend。
     * 跳转目标先写 {@code @LABEL} 占位，build() 时替换为绝对语句下标（LAssembler 的
     * jump/begin 目标都是文本下标）。
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

        void ret(String variable){
            line("return \"" + variable + "\"");
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
