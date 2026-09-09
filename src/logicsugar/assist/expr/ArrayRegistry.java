package logicsugar.assist.expr;

import arc.scene.Element;
import arc.struct.Seq;
import mindustry.logic.LCanvas;
import mindustry.logic.LStatement;
import mindustry.logic.SugarCanvas;
import mindustry.logic.SugarFunctions;
import mindustry.logic.SugarStatements.ArrayInitStatement;
import mindustry.logic.SugarStatements.ArrayStatement;
import mindustry.logic.SugarStatements.MatrixStatement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 数组注册表：{@code array}/{@code matrix} 声明卡（{@link ArrayStatement} /
 * {@link MatrixStatement}）的编译期元数据。
 *
 * <p>数组/矩阵是纯 sugar 抽象——mlog 没有数组，卡片本身不产出任何指令（lower 时被剥离）。
 * 表达式下标 {@code buf[i]} / {@code m[i][j]} / {@code buf[i] = x} / {@code m[i][j] = x}
 * 在编译时查此表，把逻辑下标换算成内存块（memory cell）的物理地址，产出原版
 * {@code read}/{@code write} 指令：一维地址 = base + 下标；矩阵地址 = base + i*cols + j
 * （行主序），[base, base+size)（矩阵 size = rows*cols）为该结构的专属区间。</p>
 *
 * <p>两个构建口径：
 * <ul>
 *   <li><b>严格</b>（{@link #compileRegistry}，编译路径）：任何字段问题（名字、字面量、
 *       重名、与函数重名、同内存块区间重叠、容量超限、arrayinit 引用/槽位）都抛出编译错误；</li>
 *   <li><b>宽松</b>（{@link #canvasRegistry}，编辑器路径）：从当前画布收集声明卡，
 *       跳过不合法的卡片（编辑期标红由 {@link #markInvalidStatements} 负责，不阻塞预览）。</li>
 * </ul></p>
 *
 * <p>上下文传递采用 {@link mindustry.logic.SugarCompiler#currentAssertEmit()} 式的静态
 * 编译期上下文：编译路径在 lowering 期间 enter/restore，编辑器路径没有上下文时回退到
 * 当前画布（{@link #active()}）。注册表为空或缺失时表达式下标退化为"仅语法校验"——
 * 按普通变量名发射 read/write，不做名字与越界检查（纯原版 mlog 与函数库预览不受影响）。</p>
 */
public final class ArrayRegistry{
    private ArrayRegistry(){}

    /** 一个已声明数组：内存块上的命名区间 [base, base+size)。 */
    public static final class ArrayInfo{
        public final String name;
        public final String memory;
        public final int base;
        public final int size;

        ArrayInfo(String name, String memory, int base, int size){
            this.name = name;
            this.memory = memory;
            this.base = base;
            this.size = size;
        }

        /** 逻辑下标是否落在 [0, size)。 */
        public boolean inRange(long index){
            return index >= 0 && index < size;
        }

        /** 逻辑下标对应的物理地址（base + index）。 */
        public long addressOf(long index){
            return base + index;
        }
    }

    /** 一个已声明的矩阵：行主序区间 [base, base+rows*cols)，地址 = base + row*cols + col。 */
    public static final class MatrixInfo{
        public final String name;
        public final String memory;
        public final int base;
        public final int rows;
        public final int cols;

        MatrixInfo(String name, String memory, int base, int rows, int cols){
            this.name = name;
            this.memory = memory;
            this.base = base;
            this.rows = rows;
            this.cols = cols;
        }

        /** 行下标是否落在 [0, rows)。 */
        public boolean inRows(long row){
            return row >= 0 && row < rows;
        }

        /** 列下标是否落在 [0, cols)。 */
        public boolean inCols(long col){
            return col >= 0 && col < cols;
        }

        /** 行主序物理地址（base + row*cols + col）。 */
        public long addressOf(long row, long col){
            return base + row * (long)cols + col;
        }

        /** 占用的内存槽数（rows*cols）。 */
        public long size(){
            return (long)rows * cols;
        }
    }

    private final Map<String, ArrayInfo> byName = new LinkedHashMap<>();
    private final Map<String, MatrixInfo> matrices = new LinkedHashMap<>();

    /** @return 按声明名查找的数组，未声明时为 null。 */
    public ArrayInfo get(String name){
        return name == null ? null : byName.get(name);
    }

    /** @return 按声明名查找的矩阵，未声明时为 null。 */
    public MatrixInfo getMatrix(String name){
        return name == null ? null : matrices.get(name);
    }

    public boolean isEmpty(){
        return byName.isEmpty() && matrices.isEmpty();
    }

    /** 声明在同一内存块上的全部数组（区间互不重叠，严格构建保证）。 */
    public List<ArrayInfo> byMemory(String memory){
        List<ArrayInfo> result = new ArrayList<>();
        if(memory != null){
            for(ArrayInfo info : byName.values()){
                if(memory.equals(info.memory)) result.add(info);
            }
        }
        return result;
    }

    /** 声明在同一内存块上的全部矩阵（区间互不重叠，严格构建保证）。
     *  折叠逆向（ExprHook/ExprCompiler 的地址反解）用它枚举候选矩阵。 */
    public List<MatrixInfo> matricesByMemory(String memory){
        List<MatrixInfo> result = new ArrayList<>();
        if(memory != null){
            for(MatrixInfo info : matrices.values()){
                if(memory.equals(info.memory)) result.add(info);
            }
        }
        return result;
    }

    /** 该内存块上是否存在 base==0 的矩阵（一维下标"地址即下标"的兜底判定用：
     *  存在 base 0 矩阵时地址可能是矩阵行主序地址，一维兜底放弃折叠）。 */
    public boolean hasZeroBaseMatrix(String memory){
        if(memory != null){
            for(MatrixInfo info : matrices.values()){
                if(memory.equals(info.memory) && info.base == 0) return true;
            }
        }
        return false;
    }

    // ===== 严格构建（编译路径） =====

    /**
     * 从语句列表收集全部 {@link ArrayStatement}/{@link MatrixStatement} 并严格校验，任何
     * 问题都抛出 {@link IllegalArgumentException}（格式对齐 {@code SugarFunctions.error}）。
     * 卡片允许出现在程序任意位置（包括函数体内），注册表始终是程序级的。第二遍校验
     * {@link ArrayInitStatement}：数组名必须已声明、槽位必须是数字字面量且不超出数组 size。
     *
     * @param functionNames 本地 funcdef + 库函数名的并集，数组/矩阵名不得与之冲突
     */
    public static ArrayRegistry compileRegistry(Seq<LStatement> statements, Set<String> functionNames){
        ArrayRegistry registry = new ArrayRegistry();
        // 同一内存块上已声明的区间（用于重叠校验）
        Map<String, List<long[]>> spans = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for(int i = 0; i < statements.size; i++){
            LStatement statement = statements.get(i);
            if(statement instanceof ArrayStatement card){
                String name = card.array == null ? "" : card.array.trim();
                validateName(i, "array", name, names, functionNames);
                String memory = card.memory == null ? "" : card.memory.trim();
                if(memory.isEmpty()) throw error(i, "array '" + name + "' needs a memory cell variable");
                Long base = parseIntLiteral(card.base);
                if(base == null || base < 0 || base > Integer.MAX_VALUE){
                    throw error(i, "array '" + name + "' base must be a non-negative integer literal (variables are not supported yet), got '" + card.base + "'");
                }
                Long size = parseIntLiteral(card.size);
                if(size == null || size < 1 || size > Integer.MAX_VALUE){
                    throw error(i, "array '" + name + "' size must be an integer literal of at least 1 (variables are not supported yet), got '" + card.size + "'");
                }
                addSpan(spans, i, "array", name, memory, base, size);
                checkCapacity(i, "array", name, memory, base + size);
                names.add(name);
                registry.byName.put(name, new ArrayInfo(name, memory, (int)(long)base, (int)(long)size));
            }else if(statement instanceof MatrixStatement card){
                String name = card.matrix == null ? "" : card.matrix.trim();
                validateName(i, "matrix", name, names, functionNames);
                String memory = card.memory == null ? "" : card.memory.trim();
                if(memory.isEmpty()) throw error(i, "matrix '" + name + "' needs a memory cell variable");
                Long base = parseIntLiteral(card.base);
                if(base == null || base < 0 || base > Integer.MAX_VALUE){
                    throw error(i, "matrix '" + name + "' base must be a non-negative integer literal (variables are not supported yet), got '" + card.base + "'");
                }
                Long rows = parseIntLiteral(card.rows);
                if(rows == null || rows < 1 || rows > Integer.MAX_VALUE){
                    throw error(i, "matrix '" + name + "' rows must be an integer literal of at least 1 (variables are not supported yet), got '" + card.rows + "'");
                }
                Long cols = parseIntLiteral(card.cols);
                if(cols == null || cols < 1 || cols > Integer.MAX_VALUE){
                    throw error(i, "matrix '" + name + "' cols must be an integer literal of at least 1 (variables are not supported yet), got '" + card.cols + "'");
                }
                long area = rows * cols;
                if(area > Integer.MAX_VALUE){
                    throw error(i, "matrix '" + name + "' rows*cols is too large (" + area + ")");
                }
                addSpan(spans, i, "matrix", name, memory, base, area);
                checkCapacity(i, "matrix", name, memory, base + area);
                names.add(name);
                registry.matrices.put(name, new MatrixInfo(name, memory, (int)(long)base, (int)(long)rows, (int)(long)cols));
            }
        }
        // 第二遍：arrayinit 引用已声明数组（声明位置不限，程序级注册表）
        for(int i = 0; i < statements.size; i++){
            if(!(statements.get(i) instanceof ArrayInitStatement card)) continue;
            String name = card.array == null ? "" : card.array.trim();
            ArrayInfo info = registry.byName.get(name);
            if(info == null){
                if(registry.matrices.containsKey(name)){
                    throw error("arrayinit", i, "cannot initialize matrix '" + name + "' (matrices are two-dimensional)");
                }
                throw error("arrayinit", i, "references undeclared array '" + name + "'");
            }
            for(int k = 0; k < card.values.length; k++){
                String value = card.values[k];
                if(value == null || value.isEmpty() || value.equals("~")) continue;
                if(parseNumberLiteral(value) == null){
                    throw error("arrayinit", i, "slot " + k + " of '" + name + "' must be a numeric literal, got '" + value + "'");
                }
                if(k >= info.size){
                    throw error("arrayinit", i, "slot " + k + " is outside array '" + name + "' (size " + info.size + ")");
                }
            }
        }
        return registry;
    }

    private static void validateName(int index, String card, String name, Set<String> names, Set<String> functionNames){
        if(name.isEmpty()) throw error(card, index, "name must not be empty");
        if(!isIdentifier(name)) throw error(card, index, "name '" + name + "' must match [A-Za-z_][A-Za-z0-9_]*");
        if(name.startsWith("__ls_")) throw error(card, index, "name '" + name + "' uses the reserved '__ls_' prefix");
        if(names.contains(name)) throw error(card, index, "duplicate name '" + name + "'");
        if(functionNames != null && functionNames.contains(name)){
            throw error(card, index, "name '" + name + "' conflicts with a function of the same name");
        }
    }

    private static void addSpan(Map<String, List<long[]>> spans, int index, String card, String name,
                                String memory, long base, long size){
        List<long[]> existing = spans.computeIfAbsent(memory, k -> new ArrayList<>());
        for(long[] span : existing){
            if(base < span[1] && span[0] < base + size){
                throw error(card, index, "'" + name + "' range [" + base + ", " + (base + size)
                    + ") overlaps another array or matrix on '" + memory + "'");
            }
        }
        existing.add(new long[]{base, base + size});
    }

    private static void checkCapacity(int index, String card, String name, String memory, long end){
        int capacity = memoryCapacity(memory);
        if(capacity > 0 && end > capacity){
            throw error(card, index, "'" + name + "' needs addresses up to " + (end - 1)
                + ", but memory '" + memory + "' only has " + capacity + " slots");
        }
    }

    /**
     * 逻辑内存块容量：{@code cellN} → 64，{@code bankN}/{@code worldN} → 512
     * （大小写不敏感，N 必须是数字）；其它名字返回 -1 表示跳过容量检查。
     */
    public static int memoryCapacity(String memory){
        if(memory == null) return -1;
        String name = memory.trim().toLowerCase(Locale.ROOT);
        if(name.startsWith("cell")) return digitsOnly(name.substring(4)) ? 64 : -1;
        if(name.startsWith("bank")) return digitsOnly(name.substring(4)) ? 512 : -1;
        if(name.startsWith("world")) return digitsOnly(name.substring(5)) ? 512 : -1;
        return -1;
    }

    private static boolean digitsOnly(String value){
        if(value.isEmpty()) return false;
        for(int i = 0; i < value.length(); i++){
            char c = value.charAt(i);
            if(c < '0' || c > '9') return false;
        }
        return true;
    }

    private static IllegalArgumentException error(int index, String detail){
        return new IllegalArgumentException("array at statement " + index + " " + detail + ".");
    }

    private static IllegalArgumentException error(String card, int index, String detail){
        return new IllegalArgumentException(card + " at statement " + index + " " + detail + ".");
    }

    // ===== 宽松构建（编辑器路径） =====

    /** 收集当前打开的 Sugar 画布上的数组/矩阵声明卡（跳过不合法的卡片），画布不可用时为 null。 */
    public static ArrayRegistry canvasRegistry(){
        try{
            return canvasRegistry(SugarCanvas.current());
        }catch(Throwable t){
            // 无头自测环境（Vars.ui 未初始化等）：视同没有编辑器上下文
            return null;
        }
    }

    /** 收集指定画布上的数组/矩阵声明卡（跳过不合法的卡片），画布不可用时为 null。
     *  折叠/展开（ExprHook）传入画布本体，避免依赖全局当前画布。 */
    public static ArrayRegistry canvasRegistry(LCanvas canvas){
        try{
            if(canvas == null || canvas.statements == null) return null;
            ArrayRegistry registry = new ArrayRegistry();
            for(Element child : canvas.statements.getChildren()){
                if(!(child instanceof LCanvas.StatementElem elem)) continue;
                if(elem.st instanceof ArrayStatement card){
                    String name = card.array == null ? "" : card.array.trim();
                    String memory = card.memory == null ? "" : card.memory.trim();
                    Long base = parseIntLiteral(card.base);
                    Long size = parseIntLiteral(card.size);
                    // 宽松口径：名字合法、未被占用、内存块非空、区间字面量合法才登记；
                    // 其余问题（重叠、与函数重名等）留给编译期严格校验与编辑期标红
                    if(!isIdentifier(name) || name.startsWith("__ls_")) continue;
                    if(memory.isEmpty() || registry.byName.containsKey(name) || registry.matrices.containsKey(name)) continue;
                    if(base == null || size == null || base < 0 || base > Integer.MAX_VALUE
                        || size < 1 || size > Integer.MAX_VALUE) continue;
                    registry.byName.put(name, new ArrayInfo(name, memory, (int)(long)base, (int)(long)size));
                }else if(elem.st instanceof MatrixStatement card){
                    String name = card.matrix == null ? "" : card.matrix.trim();
                    String memory = card.memory == null ? "" : card.memory.trim();
                    Long base = parseIntLiteral(card.base);
                    Long rows = parseIntLiteral(card.rows);
                    Long cols = parseIntLiteral(card.cols);
                    if(!isIdentifier(name) || name.startsWith("__ls_")) continue;
                    if(memory.isEmpty() || registry.byName.containsKey(name) || registry.matrices.containsKey(name)) continue;
                    if(base == null || rows == null || cols == null || base < 0 || base > Integer.MAX_VALUE
                        || rows < 1 || rows > Integer.MAX_VALUE || cols < 1 || cols > Integer.MAX_VALUE) continue;
                    registry.matrices.put(name, new MatrixInfo(name, memory, (int)(long)base, (int)(long)rows, (int)(long)cols));
                }
            }
            return registry;
        }catch(Throwable t){
            // 无头自测环境（Vars.ui 未初始化等）：视同没有编辑器上下文
            return null;
        }
    }

    /** 空注册表哨兵：显式表示"没有数组上下文"。enter 后 {@link #active()} 不再回退到
     *  画布探测，下标表达式的折叠按注册表缺失处理（仅语法校验）。 */
    public static ArrayRegistry empty(){
        return new ArrayRegistry();
    }

    // ===== 编辑期标红 =====

    /**
     * 编辑期字段级校验：把有问题的声明卡标红（{@code invalid[i] = true}）。
     * 只做字段层面的检查（名字/字面量/重名/与函数重名/区间重叠/容量/arrayinit 引用与槽位），
     * 不抛错——编译期的严格校验仍会拦截保存。
     */
    public static void markInvalidStatements(Seq<LStatement> statements, boolean[] invalid, Set<String> functionNames){
        Set<String> names = new HashSet<>();
        Map<String, List<long[]>> spans = new LinkedHashMap<>();
        Map<String, ArrayInfo> validArrays = new LinkedHashMap<>();
        for(int i = 0; i < statements.size; i++){
            LStatement statement = statements.get(i);
            if(statement instanceof ArrayStatement card){
                String name = card.array == null ? "" : card.array.trim();
                String memory = card.memory == null ? "" : card.memory.trim();
                Long base = parseIntLiteral(card.base);
                Long size = parseIntLiteral(card.size);
                boolean bad = name.isEmpty() || !isIdentifier(name) || name.startsWith("__ls_")
                    || memory.isEmpty()
                    || base == null || base < 0 || base > Integer.MAX_VALUE
                    || size == null || size < 1 || size > Integer.MAX_VALUE
                    || names.contains(name)
                    || (functionNames != null && functionNames.contains(name));
                if(!bad && base != null && size != null){
                    int capacity = memoryCapacity(memory);
                    bad = capacity > 0 && base + size > capacity;
                }
                if(!bad){
                    List<long[]> existing = spans.computeIfAbsent(memory, k -> new ArrayList<>());
                    for(long[] span : existing){
                        if(base < span[1] && span[0] < base + size){ bad = true; break; }
                    }
                    if(!bad) existing.add(new long[]{base, base + size});
                }
                if(bad){
                    invalid[i] = true;
                }else{
                    names.add(name);
                    validArrays.put(name, new ArrayInfo(name, memory, (int)(long)base, (int)(long)size));
                }
            }else if(statement instanceof MatrixStatement card){
                String name = card.matrix == null ? "" : card.matrix.trim();
                String memory = card.memory == null ? "" : card.memory.trim();
                Long base = parseIntLiteral(card.base);
                Long rows = parseIntLiteral(card.rows);
                Long cols = parseIntLiteral(card.cols);
                boolean bad = name.isEmpty() || !isIdentifier(name) || name.startsWith("__ls_")
                    || memory.isEmpty()
                    || base == null || base < 0 || base > Integer.MAX_VALUE
                    || rows == null || rows < 1 || rows > Integer.MAX_VALUE
                    || cols == null || cols < 1 || cols > Integer.MAX_VALUE
                    || names.contains(name)
                    || (functionNames != null && functionNames.contains(name));
                long area = (!bad) ? rows * cols : 0;
                if(!bad && area > Integer.MAX_VALUE) bad = true;
                if(!bad){
                    int capacity = memoryCapacity(memory);
                    bad = capacity > 0 && base + area > capacity;
                }
                if(!bad){
                    List<long[]> existing = spans.computeIfAbsent(memory, k -> new ArrayList<>());
                    for(long[] span : existing){
                        if(base < span[1] && span[0] < base + area){ bad = true; break; }
                    }
                    if(!bad) existing.add(new long[]{base, base + area});
                }
                if(bad){
                    invalid[i] = true;
                }else{
                    names.add(name);
                }
            }
        }
        // arrayinit：数组名必须已声明，槽位必须是数字字面量且不超出数组 size
        for(int i = 0; i < statements.size; i++){
            if(!(statements.get(i) instanceof ArrayInitStatement card)) continue;
            String name = card.array == null ? "" : card.array.trim();
            ArrayInfo info = validArrays.get(name);
            boolean bad = info == null;
            if(!bad){
                for(int k = 0; k < card.values.length; k++){
                    String value = card.values[k];
                    if(value == null || value.isEmpty() || value.equals("~")) continue;
                    if(parseNumberLiteral(value) == null || k >= info.size){ bad = true; break; }
                }
            }
            if(bad) invalid[i] = true;
        }
    }

    // ===== 静态编译期上下文 =====

    private static ArrayRegistry current;

    /** 进入编译期上下文，返回先前的注册表供 {@link #restore} 恢复（须 try/finally 配对）。 */
    public static ArrayRegistry enter(ArrayRegistry registry){
        ArrayRegistry previous = current;
        current = registry;
        return previous;
    }

    /** 恢复 {@link #enter} 返回的先前上下文。 */
    public static void restore(ArrayRegistry previous){
        current = previous;
    }

    /** 表达式下标折叠所用的注册表：编译期上下文优先，否则回退到当前画布。 */
    public static ArrayRegistry active(){
        ArrayRegistry context = current;
        if(context != null) return context;
        return canvasRegistry();
    }

    // ===== 工具 =====

    static boolean isIdentifier(String name){
        if(name.isEmpty()) return false;
        char first = name.charAt(0);
        if(!(Character.isLetter(first) || first == '_')) return false;
        for(int i = 1; i < name.length(); i++){
            char c = name.charAt(i);
            if(!(Character.isLetterOrDigit(c) || c == '_')) return false;
        }
        return true;
    }

    /** 解析纯十进制整数字面量（允许前导 '-'），失败（含溢出）返回 null。 */
    static Long parseIntLiteral(String token){
        if(token == null) return null;
        String t = token.trim();
        if(t.isEmpty()) return null;
        String digits = t.startsWith("-") ? t.substring(1) : t;
        if(digits.isEmpty()) return null;
        for(int i = 0; i < digits.length(); i++){
            char c = digits.charAt(i);
            if(c < '0' || c > '9') return null;
        }
        try{
            return Long.parseLong(t);
        }catch(NumberFormatException e){
            return null;
        }
    }

    /** 解析十进制数字字面量（整数或小数，可带负号），失败返回 null。
     *  仅用于 {@code arrayinit} 槽位：变量/表达式/科学计数法都拒绝。 */
    static Double parseNumberLiteral(String token){
        if(token == null) return null;
        String t = token.trim();
        if(t.isEmpty()) return null;
        int i = 0;
        if(t.charAt(i) == '-') i++;
        int digits = 0, dots = 0;
        for(; i < t.length(); i++){
            char c = t.charAt(i);
            if(c >= '0' && c <= '9'){
                digits++;
            }else if(c == '.' && dots == 0){
                dots++;
            }else{
                return null;
            }
        }
        if(digits == 0) return null;
        try{
            return Double.parseDouble(t);
        }catch(NumberFormatException e){
            return null;
        }
    }
}
