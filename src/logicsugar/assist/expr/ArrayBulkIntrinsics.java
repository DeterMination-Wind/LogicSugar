package logicsugar.assist.expr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数组批量运算的表达式扩展（{@code sum/avg/min/max/count/indexof/fill/copy/sortasc/sortdesc}、
 * {@code reverse/replace/swap/bsearch}）。
 *
 * <p>参数必须是<b>已声明数组名</b>（{@link ArrayRegistry} 的一维 {@code array} 或二维
 * {@code matrix}；矩阵按行主序摊平为 {@code rows*cols} 个元素）。编译期解析出
 * memory/base/size 后，展开为对注入函数 {@code __ls_builtin_arr*} 的 {@code funccall}：
 * 循环体只在程序里出现一份（normal 模式共享子程序），实参是内存块变量名与字面量
 * base/size，因此同一个内置函数服务所有数组。</p>
 *
 * <p>空数组不存在（{@code array}/{@code matrix} 声明保证 size ≥ 1），所以 sum/avg/min/max
 * 始终至少读一个元素；{@code count} 无匹配返回 0，{@code indexof} 无匹配返回 -1。
 * {@code copy(dst,src)} 要求两者 size 相同（矩阵与数组混用按摊平后的元素数比较）。</p>
 */
public final class ArrayBulkIntrinsics implements ExprIntrinsics.Provider{
    public static final ArrayBulkIntrinsics INSTANCE = new ArrayBulkIntrinsics();

    /** 注入函数名（统一 __ls_builtin_ 前缀，不进入用户函数库）。 */
    public static final String BUILTIN_SUM = "__ls_builtin_arrsum";
    public static final String BUILTIN_AVG = "__ls_builtin_arravg";
    public static final String BUILTIN_MIN = "__ls_builtin_arrmin";
    public static final String BUILTIN_MAX = "__ls_builtin_arrmax";
    public static final String BUILTIN_COUNT = "__ls_builtin_arrcount";
    public static final String BUILTIN_INDEXOF = "__ls_builtin_arrindexof";
    public static final String BUILTIN_FILL = "__ls_builtin_arrfill";
    public static final String BUILTIN_COPY = "__ls_builtin_arrcopy";
    public static final String BUILTIN_SORT = "__ls_builtin_arrsort";
    public static final String BUILTIN_REVERSE = "__ls_builtin_arrrev";
    public static final String BUILTIN_REPLACE = "__ls_builtin_arrrepl";
    public static final String BUILTIN_SWAP = "__ls_builtin_arrswap";
    public static final String BUILTIN_BSEARCH = "__ls_builtin_arrbsearch";

    private static final String[] CALL_NAMES = {
        "array_sum", "array_avg", "array_min", "array_max", "array_count", "array_find", "array_fill",
        "array_copy", "array_sort", "array_sort_desc", "array_reverse", "array_replace", "array_swap",
        "array_lower_bound",
        "sum", "avg", "min", "max", "count", "indexof", "fill", "copy", "sortasc", "sortdesc",
        "reverse", "replace", "swap", "bsearch"
    };

    /** 旧拼写 → 规范名。保存产物（carrier）可能携带旧名，解析时统一归一到新名再分派。 */
    static String canonical(String name){
        switch(name == null ? "" : name){
            case "sum": return "array_sum";
            case "avg": return "array_avg";
            case "min": return "array_min";
            case "max": return "array_max";
            case "count": return "array_count";
            case "indexof": return "array_find";
            case "fill": return "array_fill";
            case "copy": return "array_copy";
            case "sortasc": return "array_sort";
            case "sortdesc": return "array_sort_desc";
            case "reverse": return "array_reverse";
            case "replace": return "array_replace";
            case "swap": return "array_swap";
            case "bsearch": return "array_lower_bound";
            default: return name;
        }
    }

    private ArrayBulkIntrinsics(){}

    @Override
    public String[] callNames(){
        return CALL_NAMES;
    }

    @Override
    public int arity(String name){
        switch(canonical(name)){
            case "array_sum":
            case "array_avg":
            case "array_min":
            case "array_max":
            case "array_sort":
            case "array_sort_desc":
            case "array_reverse":
                return 1;
            case "array_count":
            case "array_find":
            case "array_fill":
            case "array_copy":
            case "array_lower_bound":
                return 2;
            case "array_replace":
            case "array_swap":
                return 3;
            default:
                return -1;
        }
    }

    /** In-place array transformations are statement-like cards; their legacy size/index
     * sentinels remain available to expression callers but are not source-level results. */
    @Override
    public boolean returnsValue(String name){
        switch(canonical(name)){
            case "array_fill":
            case "array_copy":
            case "array_sort":
            case "array_sort_desc":
            case "array_reverse":
            case "array_swap":
                return false;
            default:
                return true;
        }
    }

    @Override
    public List<ExprCompiler.Line> expandCall(String name, List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        switch(canonical(name)){
            case "array_sum": return arrayOp(BUILTIN_SUM, name, args, ctx);
            case "array_avg": return arrayOp(BUILTIN_AVG, name, args, ctx);
            case "array_min": return arrayOp(BUILTIN_MIN, name, args, ctx);
            case "array_max": return arrayOp(BUILTIN_MAX, name, args, ctx);
            case "array_count": return valueOp(BUILTIN_COUNT, name, args, ctx);
            case "array_find": return valueOp(BUILTIN_INDEXOF, name, args, ctx);
            case "array_fill": return valueOp(BUILTIN_FILL, name, args, ctx);
            case "array_sort": return arrayOp(BUILTIN_SORT, name, args, ctx, "1");
            case "array_sort_desc": return arrayOp(BUILTIN_SORT, name, args, ctx, "-1");
            case "array_copy": return copyOp(args, ctx);
            case "array_reverse": return arrayOp(BUILTIN_REVERSE, name, args, ctx);
            case "array_replace": return replaceOp(args, ctx);
            case "array_swap": return swapOp(args, ctx);
            case "array_lower_bound": return valueOp(BUILTIN_BSEARCH, name, args, ctx);
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
        result.add(sum());
        result.add(avg());
        result.add(min());
        result.add(max());
        result.add(count());
        result.add(indexof());
        result.add(fill());
        result.add(copy());
        result.add(sort());
        result.add(reverse());
        result.add(replace());
        result.add(swap());
        result.add(bsearch());
        return result;
    }

    private static String builtinOf(String name){
        switch(canonical(name)){
            case "array_sum": return BUILTIN_SUM;
            case "array_avg": return BUILTIN_AVG;
            case "array_min": return BUILTIN_MIN;
            case "array_max": return BUILTIN_MAX;
            case "array_count": return BUILTIN_COUNT;
            case "array_find": return BUILTIN_INDEXOF;
            case "array_fill": return BUILTIN_FILL;
            case "array_copy": return BUILTIN_COPY;
            case "array_sort":
            case "array_sort_desc":
                return BUILTIN_SORT;
            case "array_reverse": return BUILTIN_REVERSE;
            case "array_replace": return BUILTIN_REPLACE;
            case "array_swap": return BUILTIN_SWAP;
            case "array_lower_bound": return BUILTIN_BSEARCH;
            default:
                return null;
        }
    }

    // ===== 展开 =====

    private static List<ExprCompiler.Line> arrayOp(String builtin, String op, List<ExprCompiler.Node> args,
                                                   ExprIntrinsics.Ctx ctx, String... extra){
        String[] flat = flat(op, args.get(0), ctx);
        List<String> operands = new ArrayList<>();
        Collections.addAll(operands, flat);
        Collections.addAll(operands, extra);
        return emit(builtin, ctx, operands);
    }

    private static List<ExprCompiler.Line> valueOp(String builtin, String op, List<ExprCompiler.Node> args,
                                                   ExprIntrinsics.Ctx ctx){
        String[] flat = flat(op, args.get(0), ctx);
        String value = ctx.compile(args.get(1));
        List<String> operands = new ArrayList<>();
        Collections.addAll(operands, flat);
        operands.add(value);
        return emit(builtin, ctx, operands);
    }

    private static List<ExprCompiler.Line> copyOp(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        String[] dst = flat("array_copy", args.get(0), ctx);
        String[] src = flat("array_copy", args.get(1), ctx);
        if(!dst[2].equals(src[2])){
            throw ctx.error(ExprIntrinsics.text("la.err.intrinsic_copy_size",
                "copy() requires arrays of equal size ({0} vs {1})", dst[2], src[2]));
        }
        List<String> operands = new ArrayList<>();
        Collections.addAll(operands, dst[0], dst[1], src[0], src[1], dst[2]);
        return emit(BUILTIN_COPY, ctx, operands);
    }

    private static List<ExprCompiler.Line> replaceOp(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        String[] flat = flat("array_replace", args.get(0), ctx);
        String oldValue = ctx.compile(args.get(1));
        String newValue = ctx.compile(args.get(2));
        List<String> operands = new ArrayList<>();
        Collections.addAll(operands, flat);
        operands.add(oldValue);
        operands.add(newValue);
        return emit(BUILTIN_REPLACE, ctx, operands);
    }

    private static List<ExprCompiler.Line> swapOp(List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        String[] flat = flat("array_swap", args.get(0), ctx);
        String i = ctx.compile(args.get(1));
        String j = ctx.compile(args.get(2));
        List<String> operands = new ArrayList<>();
        Collections.addAll(operands, flat[0], flat[1], i, j);
        return emit(BUILTIN_SWAP, ctx, operands);
    }

    /** 解析「已声明数组名」实参，返回 {memory, base, size} 三个操作数文本。 */
    private static String[] flat(String op, ExprCompiler.Node node, ExprIntrinsics.Ctx ctx){
        if(!(node instanceof ExprCompiler.Var var)){
            throw ctx.error(ExprIntrinsics.text("la.err.intrinsic_array_arg",
                "{0}() expects a declared array name", op));
        }
        ArrayRegistry registry = ArrayRegistry.active();
        if(registry == null){
            throw ctx.error(ExprIntrinsics.text("la.err.intrinsic_no_context",
                "{0}() cannot resolve '{1}': no array declaration context", op, var.name));
        }
        ArrayRegistry.ArrayInfo array = registry.get(var.name);
        if(array != null){
            return new String[]{array.memory, String.valueOf(array.base), String.valueOf(array.size)};
        }
        ArrayRegistry.MatrixInfo matrix = registry.getMatrix(var.name);
        if(matrix != null){
            long size = matrix.size();
            if(size > Integer.MAX_VALUE){
                throw ctx.error(ExprIntrinsics.text("la.err.intrinsic_matrix_size",
                    "{0}(): matrix '{1}' is too large", op, var.name));
            }
            return new String[]{matrix.memory, String.valueOf(matrix.base), String.valueOf(size)};
        }
        throw ctx.error(ExprIntrinsics.text("la.err.intrinsic_unknown_array",
            "{0}() references undeclared array '{1}'", op, var.name));
    }

    private static List<ExprCompiler.Line> emit(String builtin, ExprIntrinsics.Ctx ctx, List<String> operands){
        List<ExprCompiler.Line> lines = new ArrayList<>(1);
        lines.add(new ExprCompiler.CallLine(builtin, String.join(", ", operands), ctx.temp()));
        return lines;
    }

    // ===== 注入函数源文本 =====

    private static String sum(){
        Fn f = new Fn(BUILTIN_SUM, "mem,base,size");
        f.set("__ls_bs_acc", "0");
        f.forBegin("__ls_bs_i", "0", "1", "lessThan", "size", "L_END");
        f.op("add", "__ls_bs_addr", "base", "__ls_bs_i");
        f.read("__ls_bs_v", "mem", "__ls_bs_addr");
        f.op("add", "__ls_bs_acc", "__ls_bs_acc", "__ls_bs_v");
        f.blockEnd("L_END");
        f.line("return \"__ls_bs_acc\"");
        return f.build();
    }

    private static String avg(){
        Fn f = new Fn(BUILTIN_AVG, "mem,base,size");
        f.set("__ls_bs_acc", "0");
        f.forBegin("__ls_bs_i", "0", "1", "lessThan", "size", "L_END");
        f.op("add", "__ls_bs_addr", "base", "__ls_bs_i");
        f.read("__ls_bs_v", "mem", "__ls_bs_addr");
        f.op("add", "__ls_bs_acc", "__ls_bs_acc", "__ls_bs_v");
        f.blockEnd("L_END");
        f.op("div", "__ls_bs_acc", "__ls_bs_acc", "size");
        f.line("return \"__ls_bs_acc\"");
        return f.build();
    }

    private static String min(){
        return extreme(BUILTIN_MIN, "min");
    }

    private static String max(){
        return extreme(BUILTIN_MAX, "max");
    }

    /** min/max：先取第 0 个元素，再从下标 1 开始逐元素比较（size ≥ 1 恒成立）。 */
    private static String extreme(String name, String op){
        Fn f = new Fn(name, "mem,base,size");
        f.op("add", "__ls_bs_addr", "base", "0");
        f.read("__ls_bs_acc", "mem", "__ls_bs_addr");
        f.forBegin("__ls_bs_i", "1", "1", "lessThan", "size", "L_END");
        f.op("add", "__ls_bs_addr", "base", "__ls_bs_i");
        f.read("__ls_bs_v", "mem", "__ls_bs_addr");
        f.op(op, "__ls_bs_acc", "__ls_bs_acc", "__ls_bs_v");
        f.blockEnd("L_END");
        f.line("return \"__ls_bs_acc\"");
        return f.build();
    }

    private static String count(){
        Fn f = new Fn(BUILTIN_COUNT, "mem,base,size,v");
        f.set("__ls_bs_acc", "0");
        f.forBegin("__ls_bs_i", "0", "1", "lessThan", "size", "L_END");
        f.op("add", "__ls_bs_addr", "base", "__ls_bs_i");
        f.read("__ls_bs_v", "mem", "__ls_bs_addr");
        f.op("equal", "__ls_bs_eq", "__ls_bs_v", "v");
        f.op("add", "__ls_bs_acc", "__ls_bs_acc", "__ls_bs_eq");
        f.blockEnd("L_END");
        f.line("return \"__ls_bs_acc\"");
        return f.build();
    }

    /**
     * 返回第一个等于 {@code v} 的下标，未命中返回 -1。命中即跳出循环：read 只做到命中
     * 的那个元素，未命中才会读完 {@code size}。旧实现靠 "res 已经是 -1 就 continue"
     * 防止覆盖首个命中，但 read 在跳转之前，命中后仍会把整段数组读完；这里在命中后直接
     * 跳到循环之后。
     */
    private static String indexof(){
        Fn f = new Fn(BUILTIN_INDEXOF, "mem,base,size,v");
        f.set("__ls_bs_res", "-1");
        f.forBegin("__ls_bs_i", "0", "1", "lessThan", "size", "L_CONT");
        f.op("add", "__ls_bs_addr", "base", "__ls_bs_i");
        f.read("__ls_bs_v", "mem", "__ls_bs_addr");
        f.jump("L_CONT", "notEqual", "__ls_bs_v", "v");
        f.set("__ls_bs_res", "__ls_bs_i");
        f.jump("L_DONE", "always", "x", "false");
        f.blockEnd("L_CONT");
        f.label("L_DONE");
        f.line("return \"__ls_bs_res\"");
        return f.build();
    }

    private static String fill(){
        Fn f = new Fn(BUILTIN_FILL, "mem,base,size,v");
        f.forBegin("__ls_bs_i", "0", "1", "lessThan", "size", "L_END");
        f.op("add", "__ls_bs_addr", "base", "__ls_bs_i");
        f.write("v", "mem", "__ls_bs_addr");
        f.blockEnd("L_END");
        f.line("return \"size\"");
        return f.build();
    }

    /**
     * {@code copy(dmem, dbase, smem, sbase, size)}：逐元素复制。读操作必须用源基址
     * ({@code sbase}) 配 {@code smem}，写操作必须用目标基址 ({@code dbase}) 配
     * {@code dmem}；早期实现把两边的基址交叉了（读 dbase、写 sbase），导致同块 copy
     * 反向、跨块写错区域。
     */
    private static String copy(){
        Fn f = new Fn(BUILTIN_COPY, "dmem,dbase,smem,sbase,size");
        f.forBegin("__ls_bs_i", "0", "1", "lessThan", "size", "L_END");
        f.op("add", "__ls_bs_srcaddr", "sbase", "__ls_bs_i");
        f.read("__ls_bs_x", "smem", "__ls_bs_srcaddr");
        f.op("add", "__ls_bs_dstaddr", "dbase", "__ls_bs_i");
        f.write("__ls_bs_x", "dmem", "__ls_bs_dstaddr");
        f.blockEnd("L_END");
        f.line("return \"size\"");
        return f.build();
    }

    /**
     * 希尔排序：dir=1 升序、dir=-1 降序，两个入口共享同一份子程序。
     *
     * <p>间隔序列取 {@code size/2, size/4, …, 1}（Shell 原始序列）：每轮对间隔为 {@code gap}
     * 的子序列做插入排序，最后一轮 {@code gap=1} 即普通插入排序，因此结果与插入排序完全一致。
     * 它原地、不需要辅助栈。之所以在 mlog 上值得换：处理器按 tick 限定执行指令数，排序耗时正比
     * 于执行指令数，而插入排序在随机数据上是 O(n²)；希尔排序把中等规模的随机 / 逆序数据从
     * O(n²) 降到接近 O(n^1.5)，同时保留「已排序输入只需少量搬移」的适应性。</p>
     *
     * <p>为什么不是堆排序 / 快速排序：mlog 没有递归调用栈——{@code SugarFunctions} 显式拒绝
     * 递归，normal 模式每个函数只有一个返回变量——所以快速排序只能改成显式栈的迭代版本；而
     * mlog 又没有索引变量，栈访问只能展开成 if 链，函数体约为本实现的 3 倍（且末元素枢轴在
     * 已排序输入退化成 O(n²)）。原地堆排序可行（原地、最坏 O(n log n)），但 sift-down 在
     * build / extract 两个阶段要各写一遍，体量约为本实现的 2.4 倍。希尔排序在体积与随机数据
     * 性能之间取平衡。</p>
     */
    private static String sort(){
        Fn f = new Fn(BUILTIN_SORT, "mem,base,size,dir");
        // gap = size/2, size/4, ..., 1；gap 为 0 时结束
        f.op("idiv", "__ls_bs_gap", "size", "2");
        f.whileBegin("__ls_bs_gap", "greaterThan", "0", "L_GAP");
        f.set("__ls_bs_i", "__ls_bs_gap");
        f.whileBegin("__ls_bs_i", "lessThan", "size", "L_SCAN");
        f.op("add", "__ls_bs_ai", "base", "__ls_bs_i");
        f.read("__ls_bs_key", "mem", "__ls_bs_ai");
        f.set("__ls_bs_j", "__ls_bs_i");
        f.whileBegin("1", "notEqual", "0", "L_SHIFT");
        f.jump("L_PLACE", "lessThan", "__ls_bs_j", "__ls_bs_gap");
        f.op("sub", "__ls_bs_jg", "__ls_bs_j", "__ls_bs_gap");
        f.op("add", "__ls_bs_aj", "base", "__ls_bs_jg");
        f.read("__ls_bs_cur", "mem", "__ls_bs_aj");
        f.op("sub", "__ls_bs_d", "__ls_bs_cur", "__ls_bs_key");
        f.op("mul", "__ls_bs_d", "__ls_bs_d", "dir");
        f.jump("L_PLACE", "lessThanEq", "__ls_bs_d", "0");
        f.op("add", "__ls_bs_aj2", "base", "__ls_bs_j");
        f.write("__ls_bs_cur", "mem", "__ls_bs_aj2");
        f.set("__ls_bs_j", "__ls_bs_jg");
        // 落到 while 的 blockend 即回到条件检查（无需显式回跳，少一条指令）
        f.blockEnd("L_SHIFT");
        f.label("L_PLACE");
        f.op("add", "__ls_bs_aj3", "base", "__ls_bs_j");
        f.write("__ls_bs_key", "mem", "__ls_bs_aj3");
        f.op("add", "__ls_bs_i", "__ls_bs_i", "1");
        f.blockEnd("L_SCAN");
        f.op("idiv", "__ls_bs_gap", "__ls_bs_gap", "2");
        f.blockEnd("L_GAP");
        f.line("return \"size\"");
        return f.build();
    }

    /** 原地反转 [base, base+size)。 */
    private static String reverse(){
        Fn f = new Fn(BUILTIN_REVERSE, "mem,base,size");
        f.set("__ls_bs_i", "0");
        f.op("sub", "__ls_bs_j", "size", "1");
        f.whileBegin("__ls_bs_i", "lessThan", "__ls_bs_j", "L_END");
        f.op("add", "__ls_bs_a", "base", "__ls_bs_i");
        f.op("add", "__ls_bs_b", "base", "__ls_bs_j");
        f.read("__ls_bs_x", "mem", "__ls_bs_a");
        f.read("__ls_bs_y", "mem", "__ls_bs_b");
        f.write("__ls_bs_x", "mem", "__ls_bs_b");
        f.write("__ls_bs_y", "mem", "__ls_bs_a");
        f.op("add", "__ls_bs_i", "__ls_bs_i", "1");
        f.op("sub", "__ls_bs_j", "__ls_bs_j", "1");
        f.blockEnd("L_END");
        f.line("return \"size\"");
        return f.build();
    }

    /** 把等于 old 的元素换成 neu，返回替换次数。 */
    private static String replace(){
        Fn f = new Fn(BUILTIN_REPLACE, "mem,base,size,old,neu");
        f.set("__ls_bs_acc", "0");
        f.forBegin("__ls_bs_i", "0", "1", "lessThan", "size", "L_END");
        f.op("add", "__ls_bs_addr", "base", "__ls_bs_i");
        f.read("__ls_bs_v", "mem", "__ls_bs_addr");
        f.jump("L_SKIP", "notEqual", "__ls_bs_v", "old");
        f.write("neu", "mem", "__ls_bs_addr");
        f.op("add", "__ls_bs_acc", "__ls_bs_acc", "1");
        f.label("L_SKIP");
        f.blockEnd("L_END");
        f.line("return \"__ls_bs_acc\"");
        return f.build();
    }

    /** 交换下标 i 与 j（不做运行时越界检查，与 fill 等一致）。 */
    private static String swap(){
        Fn f = new Fn(BUILTIN_SWAP, "mem,base,i,j");
        f.op("add", "__ls_bs_a", "base", "i");
        f.op("add", "__ls_bs_b", "base", "j");
        f.read("__ls_bs_x", "mem", "__ls_bs_a");
        f.read("__ls_bs_y", "mem", "__ls_bs_b");
        f.write("__ls_bs_x", "mem", "__ls_bs_b");
        f.write("__ls_bs_y", "mem", "__ls_bs_a");
        f.line("return \"i\"");
        return f.build();
    }

    /** 升序数组上的二分查找：命中返回下标，否则 -1。 */
    private static String bsearch(){
        Fn f = new Fn(BUILTIN_BSEARCH, "mem,base,size,v");
        f.set("__ls_bs_lo", "0");
        f.set("__ls_bs_hi", "size");
        f.label("LOOP");
        f.jump("DONE", "greaterThanEq", "__ls_bs_lo", "__ls_bs_hi");
        f.op("add", "__ls_bs_mid", "__ls_bs_lo", "__ls_bs_hi");
        f.op("idiv", "__ls_bs_mid", "__ls_bs_mid", "2");
        f.op("add", "__ls_bs_addr", "base", "__ls_bs_mid");
        f.read("__ls_bs_cur", "mem", "__ls_bs_addr");
        f.jump("GO_LO", "lessThan", "__ls_bs_cur", "v");
        f.set("__ls_bs_hi", "__ls_bs_mid");
        f.jump("LOOP", "always", "x", "false");
        f.label("GO_LO");
        f.op("add", "__ls_bs_lo", "__ls_bs_mid", "1");
        f.jump("LOOP", "always", "x", "false");
        f.label("DONE");
        f.jump("MISS", "greaterThanEq", "__ls_bs_lo", "size");
        f.op("add", "__ls_bs_addr", "base", "__ls_bs_lo");
        f.read("__ls_bs_cur", "mem", "__ls_bs_addr");
        f.jump("MISS", "notEqual", "__ls_bs_cur", "v");
        f.line("return \"__ls_bs_lo\"");
        f.label("MISS");
        f.line("return \"-1\"");
        return f.build();
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

        void forBegin(String variable, String initial, String step, String op, String compare, String endLabel){
            line("forbegin " + variable + " " + initial + " " + step + " " + op + " " + compare + " @" + endLabel);
        }

        void whileBegin(String value, String op, String compare, String endLabel){
            line("whilebegin " + value + " " + op + " " + compare + " @" + endLabel);
        }

        void jump(String label, String op, String value, String compare){
            line("jump @" + label + " " + op + " " + value + " " + compare);
        }

        void blockEnd(String label){
            labels.put(label, body.size());
            line("blockend");
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
