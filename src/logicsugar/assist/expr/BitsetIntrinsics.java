package logicsugar.assist.expr;

import logicsugar.assist.data.BitsetModule;
import logicsugar.assist.data.BitsetModule.BitsetInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 位集的表达式扩展（{@code bset}/{@code bclr}/{@code btest}/{@code bcount}）。
 *
 * <p>第一个实参必须是<b>已声明位集名</b>（{@link BitsetModule} 的编译期注册表，编辑器
 * 路径回退到画布声明）。位下标 {@code i} 可以是任意表达式：word = {@code i // 64}
 * （{@code op idiv}），bit = {@code i % 64}（{@code op mod}），mask = {@code 1 << bit}
 * （{@code op shl}），读-改-写回原版 {@code read}/{@code write}。位运算全部内联在调用点的
 * 表达式链里；内存写回经共享注入函数 {@code __ls_builtin_bwrite} 完成（条件/返回表达式的
 * lowering 不支持链中的 {@code write} 行，见 {@link #write} 的注释）。</p>
 *
 * <p><b>越界语义</b>：{@code i < 0} 或 {@code i >= words*64} 时 set/clr 忽略（不改内存）、
 * test 返回 0。实现是无分支的：{@code valid = (i >= 0) && (i < words*64)}，{@code mask *= valid}、
 * {@code word *= valid}；无效时地址回落到 base（仍在区间内），读到的值原样写回，等价于空操作。
 * bset/bclr 返回 1（含无效下标），btest 返回 1/0。</p>
 *
 * <p>{@code bcount(b)} 展开为对注入函数 {@code __ls_builtin_bitcount} 的 {@code funccall}：
 * 逐 word 用 {@code and 1} + {@code ushr} 移位循环统计置位数（normal 模式共享一份子程序，
 * 未使用时经可达性分析不进入产物）。注意 mlog 内存单元是 double（53 位尾数），位 53..63
 * 的任意组合无法精确存储，高位置位时低位可能被舍入——这是原版内存的固有限制，模块报告
 * 中已注明。</p>
 */
public final class BitsetIntrinsics implements ExprIntrinsics.Provider{
    public static final BitsetIntrinsics INSTANCE = new BitsetIntrinsics();

    /** 注入函数名（统一 __ls_builtin_ 前缀，不进入用户函数库）。 */
    public static final String BUILTIN_COUNT = "__ls_builtin_bitcount";
    public static final String BUILTIN_WRITE = "__ls_builtin_bwrite";

    private static final String[] CALL_NAMES = {"bset", "bclr", "btest", "bcount"};

    private BitsetIntrinsics(){}

    @Override
    public String[] callNames(){
        return CALL_NAMES;
    }

    @Override
    public int arity(String name){
        return "bcount".equals(name) ? 1 : 2;
    }

    @Override
    public List<ExprCompiler.Line> expandCall(String name, List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        if("bcount".equals(name)) return countOp(args, ctx);
        if("bset".equals(name) || "bclr".equals(name) || "btest".equals(name)) return bitOp(name, args, ctx);
        return null;
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
        if("bcount".equals(name)) return Collections.singletonList(BUILTIN_COUNT);
        if("bset".equals(name) || "bclr".equals(name)) return Collections.singletonList(BUILTIN_WRITE);
        return Collections.emptyList();
    }

    // ===== 展开 =====

    /**
     * bset/bclr/btest 的公共展开：编译下标 → word/bit/mask/valid → 地址 → 读 → 改 → 写回。
     * 返回链的最后一行有结果操作数（bset/bclr 是 bwrite 调用的返回值 1，btest 是
     * {@code op notEqual} 的 1/0），调用方据此把结果接到外层表达式链上。
     */
    private static List<ExprCompiler.Line> bitOp(String name, List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        BitsetInfo info = resolve(name, args, ctx);
        String index = ctx.compile(args.get(1));
        List<ExprCompiler.Line> lines = new ArrayList<>();

        String word = ctx.temp();
        lines.add(new ExprCompiler.OpLine("idiv", word, index, "64"));
        String bit = ctx.temp();
        lines.add(new ExprCompiler.OpLine("mod", bit, index, "64"));
        String mask = ctx.temp();
        lines.add(new ExprCompiler.OpLine("shl", mask, "1", bit));

        // 越界守卫（无分支）：valid = (i >= 0) && (i < words*64)；mask/word 乘 valid，
        // 无效下标时 mask=0、地址回落到 base，读-改-写回等价于空操作。
        String nonNegative = ctx.temp();
        lines.add(new ExprCompiler.OpLine("greaterThanEq", nonNegative, index, "0"));
        String inRange = ctx.temp();
        lines.add(new ExprCompiler.OpLine("lessThan", inRange, index, String.valueOf(info.bitCapacity())));
        String valid = ctx.temp();
        lines.add(new ExprCompiler.OpLine("land", valid, nonNegative, inRange));
        lines.add(new ExprCompiler.OpLine("mul", mask, mask, valid));
        lines.add(new ExprCompiler.OpLine("mul", word, word, valid));

        String address = ctx.temp();
        lines.add(new ExprCompiler.OpLine("add", address, String.valueOf(info.base), word));
        String current = ctx.temp();
        lines.add(new ExprCompiler.ReadLine(current, info.memory, address));

        if("bset".equals(name)){
            lines.add(new ExprCompiler.OpLine("or", current, current, mask));
            lines.add(write(current, info.memory, address, ctx));
        }else if("bclr".equals(name)){
            String inverse = ctx.temp();
            lines.add(new ExprCompiler.OpLine("not", inverse, mask, "0"));
            lines.add(new ExprCompiler.OpLine("and", current, current, inverse));
            lines.add(write(current, info.memory, address, ctx));
        }else{
            String masked = ctx.temp();
            lines.add(new ExprCompiler.OpLine("and", masked, current, mask));
            lines.add(new ExprCompiler.OpLine("notEqual", ctx.temp(), masked, "0"));
        }
        return lines;
    }

    /**
     * 内存写回：经共享注入函数 {@code __ls_builtin_bwrite} 完成。原版 {@code write} 不是
     * 表达式链支持的行类型——条件/返回表达式的 lowering 只认 op/sensor/read/funccall，
     * 链中直接放 WriteLine 会在 {@code emitConditionExpression}/{@code emitReturn} 处抛
     * ClassCastException。函数体是一条 {@code write}，返回 1，因此 bset/bclr 的链尾即结果。
     */
    private static ExprCompiler.Line write(String value, String memory, String address, ExprIntrinsics.Ctx ctx){
        return new ExprCompiler.CallLine(BUILTIN_WRITE, memory + ", " + address + ", " + value, ctx.temp());
    }

    /** bcount(b)：调用共享注入函数 {@code __ls_builtin_bitcount(mem, base, words)}。 */
    private static List<ExprCompiler.Line> countOp(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        BitsetInfo info = resolve("bcount", args, ctx);
        List<ExprCompiler.Line> lines = new ArrayList<>(1);
        lines.add(new ExprCompiler.CallLine(BUILTIN_COUNT,
            info.memory + ", " + info.base + ", " + info.words, ctx.temp()));
        return lines;
    }

    /** 解析第一个实参「已声明位集名」。 */
    private static BitsetInfo resolve(String op, List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        if(args.isEmpty() || !(args.get(0) instanceof ExprCompiler.Var var)){
            throw ctx.error(ExprIntrinsics.text("la.err.intrinsic_bitset_arg",
                "{0}() expects a declared bitset name", op));
        }
        BitsetInfo info = BitsetModule.find(var.name);
        if(info == null){
            throw ctx.error(ExprIntrinsics.text("la.err.intrinsic_bitset_unknown",
                "{0}() references undeclared bitset '{1}'", op, var.name));
        }
        return info;
    }

    // ===== 注入函数源文本 =====

    /** {@code bset}/{@code bclr} 的写回子程序：{@code write value mem addr}，返回 1。 */
    public static String writeBuiltinSugar(){
        return "funcdef " + BUILTIN_WRITE + " mem,addr,value 3\n"
            + "write value mem addr\n"
            + "return \"1\"\n"
            + "blockend\n";
    }

    /**
     * {@code bcount} 的注入函数：逐 word 用 {@code and 1} + {@code ushr} 移位循环统计置位数。
     * 参数 {@code mem,base,words}；局部变量统一 {@code __ls_bit_} 前缀（与其它模块/用户变量
     * 不冲突）。索引为注入文本的语句下标（whilebegin 的 destIndex 指向自己的 blockend）。
     */
    public static String countBuiltinSugar(){
        return "funcdef " + BUILTIN_COUNT + " mem,base,words 14\n"
            + "set __ls_bit_count 0\n"
            + "set __ls_bit_w 0\n"
            + "whilebegin __ls_bit_w lessThan words 12\n"
            + "op add __ls_bit_addr base __ls_bit_w\n"
            + "read __ls_bit_word mem __ls_bit_addr\n"
            + "whilebegin __ls_bit_word notEqual 0 10\n"
            + "op and __ls_bit_tmp __ls_bit_word 1\n"
            + "op add __ls_bit_count __ls_bit_count __ls_bit_tmp\n"
            + "op ushr __ls_bit_word __ls_bit_word 1\n"
            + "blockend\n"
            + "op add __ls_bit_w __ls_bit_w 1\n"
            + "blockend\n"
            + "return \"__ls_bit_count\"\n"
            + "blockend\n";
    }
}
