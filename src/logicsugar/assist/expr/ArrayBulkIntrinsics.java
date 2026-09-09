package logicsugar.assist.expr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数组批量运算的表达式扩展（{@code sum/avg/min/max/count/indexof/fill/copy/sortasc/sortdesc}）。
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

    private static final String[] CALL_NAMES = {
        "sum", "avg", "min", "max", "count", "indexof", "fill", "copy", "sortasc", "sortdesc"
    };

    private ArrayBulkIntrinsics(){}

    @Override
    public String[] callNames(){
        return CALL_NAMES;
    }

    @Override
    public int arity(String name){
        switch(name){
            case "sum":
            case "avg":
            case "min":
            case "max":
            case "sortasc":
            case "sortdesc":
                return 1;
            case "count":
            case "indexof":
            case "fill":
            case "copy":
                return 2;
            default:
                return -1;
        }
    }

    @Override
    public List<ExprCompiler.Line> expandCall(String name, List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        switch(name){
            case "sum": return arrayOp(BUILTIN_SUM, name, args, ctx);
            case "avg": return arrayOp(BUILTIN_AVG, name, args, ctx);
            case "min": return arrayOp(BUILTIN_MIN, name, args, ctx);
            case "max": return arrayOp(BUILTIN_MAX, name, args, ctx);
            case "count": return valueOp(BUILTIN_COUNT, name, args, ctx);
            case "indexof": return valueOp(BUILTIN_INDEXOF, name, args, ctx);
            case "fill": return valueOp(BUILTIN_FILL, name, args, ctx);
            case "sortasc": return arrayOp(BUILTIN_SORT, name, args, ctx, "1");
            case "sortdesc": return arrayOp(BUILTIN_SORT, name, args, ctx, "-1");
            case "copy": return copyOp(args, ctx);
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
        return result;
    }

    private static String builtinOf(String name){
        switch(name){
            case "sum": return BUILTIN_SUM;
            case "avg": return BUILTIN_AVG;
            case "min": return BUILTIN_MIN;
            case "max": return BUILTIN_MAX;
            case "count": return BUILTIN_COUNT;
            case "indexof": return BUILTIN_INDEXOF;
            case "fill": return BUILTIN_FILL;
            case "copy": return BUILTIN_COPY;
            case "sortasc":
            case "sortdesc":
                return BUILTIN_SORT;
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
        String[] dst = flat("copy", args.get(0), ctx);
        String[] src = flat("copy", args.get(1), ctx);
        if(!dst[2].equals(src[2])){
            throw ctx.error(ExprIntrinsics.text("la.err.intrinsic_copy_size",
                "copy() requires arrays of equal size ({0} vs {1})", dst[2], src[2]));
        }
        List<String> operands = new ArrayList<>();
        Collections.addAll(operands, dst[0], dst[1], src[0], src[1], dst[2]);
        return emit(BUILTIN_COPY, ctx, operands);
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

    private static String indexof(){
        Fn f = new Fn(BUILTIN_INDEXOF, "mem,base,size,v");
        f.set("__ls_bs_res", "-1");
        f.forBegin("__ls_bs_i", "0", "1", "lessThan", "size", "L_CONT");
        f.op("add", "__ls_bs_addr", "base", "__ls_bs_i");
        f.read("__ls_bs_v", "mem", "__ls_bs_addr");
        f.jump("L_CONT", "notEqual", "__ls_bs_v", "v");
        f.jump("L_CONT", "notEqual", "__ls_bs_res", "-1");
        f.set("__ls_bs_res", "__ls_bs_i");
        f.blockEnd("L_CONT");
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

    private static String copy(){
        Fn f = new Fn(BUILTIN_COPY, "dmem,dbase,smem,sbase,size");
        f.forBegin("__ls_bs_i", "0", "1", "lessThan", "size", "L_END");
        f.op("add", "__ls_bs_a", "dbase", "__ls_bs_i");
        f.read("__ls_bs_x", "smem", "__ls_bs_a");
        f.op("add", "__ls_bs_b", "sbase", "__ls_bs_i");
        f.write("__ls_bs_x", "dmem", "__ls_bs_b");
        f.blockEnd("L_END");
        f.line("return \"size\"");
        return f.build();
    }

    /** 插入排序：dir=1 升序、dir=-1 降序，两个入口共享同一份子程序。 */
    private static String sort(){
        Fn f = new Fn(BUILTIN_SORT, "mem,base,size,dir");
        f.forBegin("__ls_bs_i", "1", "1", "lessThan", "size", "L_OUTER");
        f.op("add", "__ls_bs_ai", "base", "__ls_bs_i");
        f.read("__ls_bs_key", "mem", "__ls_bs_ai");
        f.set("__ls_bs_j", "__ls_bs_i");
        f.whileBegin("1", "notEqual", "0", "L_INNER");
        f.jump("L_DONE", "lessThan", "__ls_bs_j", "0");
        f.op("sub", "__ls_bs_jm", "__ls_bs_j", "1");
        f.op("add", "__ls_bs_aj", "base", "__ls_bs_jm");
        f.read("__ls_bs_cur", "mem", "__ls_bs_aj");
        f.op("sub", "__ls_bs_d", "__ls_bs_cur", "__ls_bs_key");
        f.op("mul", "__ls_bs_d", "__ls_bs_d", "dir");
        f.jump("L_DONE", "lessThanEq", "__ls_bs_d", "0");
        f.op("add", "__ls_bs_aj2", "base", "__ls_bs_j");
        f.write("__ls_bs_cur", "mem", "__ls_bs_aj2");
        f.set("__ls_bs_j", "__ls_bs_jm");
        // 落到 while 的 blockend 即回到条件检查（无需显式回跳，少一条指令）
        f.blockEnd("L_INNER");
        f.label("L_DONE");
        f.op("add", "__ls_bs_aj3", "base", "__ls_bs_j");
        f.write("__ls_bs_key", "mem", "__ls_bs_aj3");
        f.blockEnd("L_OUTER");
        f.line("return \"size\"");
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
