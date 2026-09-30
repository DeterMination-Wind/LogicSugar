package logicsugar.assist.expr;

import logicsugar.assist.expr.ArrayRegistry.SpanInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 唯一的 span 寻址展开。变量逻辑地址是 {@code idiv}/{@code mod}、{@code N} 条
 * {@code select equal q k}（第一条的失败分支是数字 {@code 0}，其余失败分支保留上次结果），
 * 再加一条 {@code read}/{@code write}，共 {@code N+3} 条（N=2 时是 5 条）。
 * {@code idiv} 是 {@code Math.floor}，负数的商 {@code <= -1}，对不上 {@code 0..N-1}，
 * 和 {@code q >= N} 一样留在数字 {@code 0} 上，读出来是空。不需要在 {@code idiv} 前再收一次负数。
 * 常量地址折叠成一条指向选中成员的 {@code read}/{@code write}；越界常量指向数字 {@code 0}。
 *
 * <p>直接 {@code read}/{@code write}、数组/矩阵下标和 {@code arrayinit} 都走这里。
 * 旧 {@code __ls_builtin_*} 函数体一字不改；调用它们时若参数是 span 名，由
 * {@code SugarFunctions} 报编译错误，而不是只打到第一格。{@code spanread}/{@code spanwrite}
 * 是同一套展开的注入函数，给以后的批量算法用，不替换已有函数体。{@code N} 在函数里是参数，
 * 所以固定展开 8 格后再用一条 {@code q >= n} 把补位槽清掉。
 */
public final class SpanAccess{
    public static final String BUILTIN_READ = "__ls_builtin_spanread";
    public static final String BUILTIN_WRITE = "__ls_builtin_spanwrite";

    public static final String QUOTIENT = "__ls_span_q";
    public static final String SLOT = "__ls_span_r";
    public static final String BUILDING = "__ls_span_b";

    private SpanAccess(){}

    public static boolean isSpan(String memory){
        return ArrayRegistry.findSpan(memory) != null;
    }

    public static boolean isSpanBuiltin(String name){
        return BUILTIN_READ.equals(name) || BUILTIN_WRITE.equals(name);
    }

    /** 每行以换行结尾，供 lower 直接拼进产物。 */
    public static void appendRead(StringBuilder out, String dest, String memory, String address){
        append(out, false, dest, memory, address);
    }

    public static void appendWrite(StringBuilder out, String value, String memory, String address){
        append(out, true, value, memory, address);
    }

    /** 行之间用换行连接，末尾不加换行。表达式卡 {@code write()} 用这个，避免多出一个空行。 */
    public static String renderRead(String dest, String memory, String address){
        return String.join("\n", accessLines(false, dest, memory, address));
    }

    public static String renderWrite(String value, String memory, String address){
        return String.join("\n", accessLines(true, value, memory, address));
    }

    /**
     * 数组/矩阵地址已经算完。常量折叠成 {@code {成员, 格内槽}}，不往 {@code ops} 里加行；
     * 变量地址把prologue写进 {@code ops}，返回临时建筑和格内槽，调用方再发那一条 read/write。
     */
    public static String[] relocate(List<ExprCompiler.Line> ops, String memory, String address){
        SpanInfo span = ArrayRegistry.findSpan(memory);
        if(span == null) return new String[]{memory, address};
        Long literal = integerLiteral(address);
        if(literal != null){
            Target target = fold(span, literal);
            return new String[]{target.memory, target.address};
        }
        for(String line : prologue(span, address)){
            ops.add(lineOf(line));
        }
        return new String[]{BUILDING, SLOT};
    }

    public static String readBuiltin(){
        List<String> body = new ArrayList<>();
        body.add("op idiv __ls_sp_q addr cap");
        body.add("op mod __ls_sp_r addr cap");
        body.add("select __ls_sp_b equal __ls_sp_q 0 c0 0");
        for(int cell = 1; cell < 8; cell++){
            body.add("select __ls_sp_b equal __ls_sp_q " + cell + " c" + cell + " __ls_sp_b");
        }
        body.add("select __ls_sp_b greaterThanEq __ls_sp_q n 0 __ls_sp_b");
        body.add("read __ls_sp_v __ls_sp_b __ls_sp_r");
        body.add("return \"__ls_sp_v\"");
        return function(BUILTIN_READ, "addr,cap,n,c0,c1,c2,c3,c4,c5,c6,c7", body);
    }

    public static String writeBuiltin(){
        List<String> body = new ArrayList<>();
        body.add("op idiv __ls_sp_q addr cap");
        body.add("op mod __ls_sp_r addr cap");
        body.add("select __ls_sp_b equal __ls_sp_q 0 c0 0");
        for(int cell = 1; cell < 8; cell++){
            body.add("select __ls_sp_b equal __ls_sp_q " + cell + " c" + cell + " __ls_sp_b");
        }
        body.add("select __ls_sp_b greaterThanEq __ls_sp_q n 0 __ls_sp_b");
        body.add("write value __ls_sp_b __ls_sp_r");
        body.add("return \"1\"");
        return function(BUILTIN_WRITE, "value,addr,cap,n,c0,c1,c2,c3,c4,c5,c6,c7", body);
    }

    private static void append(StringBuilder out, boolean write, String payload, String memory, String address){
        for(String line : accessLines(write, payload, memory, address)){
            out.append(line).append('\n');
        }
    }

    private static List<String> accessLines(boolean write, String payload, String memory, String address){
        SpanInfo span = ArrayRegistry.findSpan(memory);
        if(span == null){
            List<String> one = new ArrayList<>(1);
            one.add(write
                ? "write " + payload + " " + memory + " " + address
                : "read " + payload + " " + memory + " " + address);
            return one;
        }
        Long literal = integerLiteral(address);
        if(literal != null){
            Target target = fold(span, literal);
            List<String> one = new ArrayList<>(1);
            one.add(write
                ? "write " + payload + " " + target.memory + " " + target.address
                : "read " + payload + " " + target.memory + " " + target.address);
            return one;
        }
        List<String> lines = prologue(span, address);
        lines.add(write
            ? "write " + payload + " " + BUILDING + " " + SLOT
            : "read " + payload + " " + BUILDING + " " + SLOT);
        return lines;
    }

    /**
     * 商、余数和选建筑。不含最后的 read/write。失败分支从数字 {@code 0} 起步：
     * {@code floor} 把负数收成 {@code <= -1}，{@code q >= N} 也对不上任何成员，两者都留在空目标上。
     */
    private static List<String> prologue(SpanInfo span, String address){
        int count = span.members.length;
        List<String> lines = new ArrayList<>(count + 2);
        lines.add("op idiv " + QUOTIENT + " " + address + " " + span.cellCapacity);
        lines.add("op mod " + SLOT + " " + address + " " + span.cellCapacity);
        lines.add(select(BUILDING, "equal", QUOTIENT, "0", span.members[0], "0"));
        for(int cell = 1; cell < count; cell++){
            lines.add(select(BUILDING, "equal", QUOTIENT, Integer.toString(cell), span.members[cell], BUILDING));
        }
        return lines;
    }

    private static String select(String result, String op, String comp0, String comp1, String a, String b){
        return "select " + result + " " + op + " " + comp0 + " " + comp1 + " " + a + " " + b;
    }

    private static Target fold(SpanInfo span, long address){
        if(address < 0 || address >= span.logicalCapacity) return new Target("0", "0");
        int cell = (int)(address / span.cellCapacity);
        int local = (int)(address % span.cellCapacity);
        return new Target(span.members[cell], Integer.toString(local));
    }

    static Long integerLiteral(String token){
        if(token == null) return null;
        String text = token.trim();
        if(text.isEmpty()) return null;
        int start = 0;
        if(text.charAt(0) == '-'){
            if(text.length() == 1) return null;
            start = 1;
        }
        for(int i = start; i < text.length(); i++){
            char c = text.charAt(i);
            if(c < '0' || c > '9') return null;
        }
        try{
            return Long.parseLong(text);
        }catch(NumberFormatException e){
            return null;
        }
    }

    private static ExprCompiler.Line lineOf(String text){
        if(text.startsWith("op ")){
            ExprCompiler.OpLine op = ExprCompiler.OpLine.fromText(text);
            if(op != null) return op;
        }
        if(text.startsWith("select ")){
            String[] parts = text.split(" ");
            if(parts.length == 7){
                return new ExprCompiler.SelectLine(parts[1], parts[2], parts[3], parts[4], parts[5], parts[6]);
            }
        }
        return new ExprCompiler.RawLine(text);
    }

    private static String function(String name, String params, List<String> body){
        StringBuilder out = new StringBuilder();
        out.append("funcdef ").append(name).append(' ').append(params).append(' ').append(body.size() + 1).append('\n');
        for(String line : body) out.append(line).append('\n');
        out.append("blockend\n");
        return out.toString();
    }

    private static final class Target{
        final String memory;
        final String address;

        Target(String memory, String address){
            this.memory = memory;
            this.address = address;
        }
    }
}
