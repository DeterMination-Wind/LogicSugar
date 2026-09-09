package logicsugar.assist.expr;

import logicsugar.assist.data.SetModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 无序集合的表达式扩展：{@code uadd/uhas/udel/usize/uclear}。
 *
 * <p>实参必须是<b>已声明集合名</b>（{@link SetModule} 的 {@code uset} 卡）。编译期解析出
 * memory/base/capacity 后，展开为对注入函数 {@code __ls_builtin_uset*} 的 {@code funccall}。
 * 探测算法与 {@link MapIntrinsics} 相同（开放寻址、NaN 墓碑、整表扫描），但不写 value 区。</p>
 *
 * <p><b>初始化</b>：首次使用前必须调用 {@code uclear(s)} 把全部槽写成 NaN 标记。</p>
 *
 * <p><b>已知限制</b>：字符串键不支持；NaN/±Inf 键会被拒绝（add 返回 -1、has/del 返回 0）；
 * 键比较沿用原版 {@code equal} 的 1e-6 容差。</p>
 */
public final class SetIntrinsics implements ExprIntrinsics.Provider{
    public static final SetIntrinsics INSTANCE = new SetIntrinsics();

    public static final String BUILTIN_ADD = "__ls_builtin_usetadd";
    public static final String BUILTIN_HAS = "__ls_builtin_usethas";
    public static final String BUILTIN_DEL = "__ls_builtin_usetdel";
    public static final String BUILTIN_SIZE = "__ls_builtin_usetsize";
    public static final String BUILTIN_CLEAR = "__ls_builtin_usetclear";

    private static final String[] CALL_NAMES = {
        "uadd", "uhas", "udel", "usize", "uclear"
    };

    private static final String NAN = "__ls_us_nan";
    private static final String BAD = "__ls_us_bad";
    private static final String H = "__ls_us_h";
    private static final String I = "__ls_us_i";
    private static final String N = "__ls_us_n";
    private static final String A = "__ls_us_a";
    private static final String A2 = "__ls_us_a2";
    private static final String K = "__ls_us_k";
    private static final String E = "__ls_us_e";
    private static final String FREE = "__ls_us_free";
    private static final String RES = "__ls_us_res";
    private static final String CNT = "__ls_us_cnt";

    private SetIntrinsics(){}

    @Override
    public String[] callNames(){
        return CALL_NAMES;
    }

    @Override
    public int arity(String name){
        switch(name){
            case "uadd":
            case "uhas":
            case "udel":
                return 2;
            case "usize":
            case "uclear":
                return 1;
            default:
                return -1;
        }
    }

    @Override
    public List<ExprCompiler.Line> expandCall(String name, List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        switch(name){
            case "uadd": return call(BUILTIN_ADD, name, args, ctx, 1);
            case "uhas": return call(BUILTIN_HAS, name, args, ctx, 1);
            case "udel": return call(BUILTIN_DEL, name, args, ctx, 1);
            case "usize": return call(BUILTIN_SIZE, name, args, ctx, 0);
            case "uclear": return call(BUILTIN_CLEAR, name, args, ctx, 0);
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

    public static List<String> builtinSugar(){
        List<String> result = new ArrayList<>();
        result.add(add());
        result.add(has());
        result.add(del());
        result.add(size());
        result.add(clear());
        return result;
    }

    private static String builtinOf(String name){
        switch(name){
            case "uadd": return BUILTIN_ADD;
            case "uhas": return BUILTIN_HAS;
            case "udel": return BUILTIN_DEL;
            case "usize": return BUILTIN_SIZE;
            case "uclear": return BUILTIN_CLEAR;
            default: return null;
        }
    }

    private static List<ExprCompiler.Line> call(String builtin, String op, List<ExprCompiler.Node> args,
                                                ExprIntrinsics.Ctx ctx, int extraArgs){
        SetModule.SetInfo info = setOf(op, args.get(0), ctx);
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

    private static SetModule.SetInfo setOf(String op, ExprCompiler.Node node, ExprIntrinsics.Ctx ctx){
        if(!(node instanceof ExprCompiler.Var var)){
            throw ctx.error(ExprIntrinsics.text("la.err.intrinsic_uset_arg",
                "{0}() expects a declared set name", op));
        }
        SetModule.SetInfo info = SetModule.active(var.name);
        if(info != null) return info;
        if(!SetModule.hasContext()){
            throw ctx.error(ExprIntrinsics.text("la.err.intrinsic_uset_no_context",
                "{0}() cannot resolve '{1}': no set declaration context", op, var.name));
        }
        throw ctx.error(ExprIntrinsics.text("la.err.intrinsic_unknown_uset",
            "{0}() references undeclared set '{1}'", op, var.name));
    }

    /**
     * {@code uadd(mem, base, cap, key)}：插入或确认已存在。
     * 返回 1（成功）或 -1（表满 / 键不是有限数字）。
     */
    private static String add(){
        Fn f = new Fn(BUILTIN_ADD, "mem,base,cap,key");
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
        f.set(RES, "1");
        f.ret(RES);
        f.label("AFTER");
        f.jump("FULL", "lessThan", FREE, "0");
        f.op("add", A2, "base", FREE);
        f.write("key", "mem", A2);
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

    private static void guard(Fn f, String target){
        f.op("div", NAN, "0", "0");
        f.op("strictEqual", BAD, "key", NAN);
        f.jump(target, "equal", BAD, "1");
    }

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
