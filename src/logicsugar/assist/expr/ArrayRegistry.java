package logicsugar.assist.expr;

import arc.scene.Element;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.logic.LCanvas;
import mindustry.logic.LExecutor;
import mindustry.logic.LStatement;
import mindustry.logic.SugarCanvas;
import mindustry.logic.SugarFunctions;
import mindustry.logic.SugarLogicDialog;
import mindustry.logic.SugarStatements.ArrayInitStatement;
import mindustry.logic.SugarStatements.ArrayStatement;
import mindustry.logic.SugarStatements.MatrixStatement;
import mindustry.logic.SugarStatements.SpanStatement;
import mindustry.world.blocks.logic.MemoryBlock;

import java.util.ArrayList;
import java.util.Collections;
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

    /**
     * 无头、且没有任何已链接方块时，{@code cellN} 成员按每格这个容量计算。
     * 真实链接打开时不用这个数：world-cell 的变量名也是 {@code cellN}，容量是链接上的
     * {@code memoryCapacity}（512），不是 64。{@code bankN}/{@code worldN} 由
     * {@link #memoryCapacity} 给出 512（与数组侧同一口径）。
     */
    public static final int HEADLESS_CELL_CAPACITY = 64;

    /** 一个 span：若干等容量内存块拼成的逻辑地址空间，容量 {@code N * C}。 */
    public static final class SpanInfo{
        public final String name;
        public final String[] members;
        public final int cellCapacity;
        public final int logicalCapacity;

        SpanInfo(String name, String[] members, int cellCapacity){
            this.name = name;
            this.members = members;
            this.cellCapacity = cellCapacity;
            this.logicalCapacity = members.length * cellCapacity;
        }

        /** @return 成员在地址顺序里的序号；不是本 span 的成员时返回 -1。 */
        public int indexOfMember(String member){
            if(member == null) return -1;
            for(int i = 0; i < members.length; i++){
                if(member.equals(members[i])) return i;
            }
            return -1;
        }

        /** @return 成员序列与每格容量是否完全一致（展开折叠反查用）。 */
        public boolean matchesShape(List<String> candidate, int cellCapacity){
            if(candidate == null || candidate.size() != members.length || this.cellCapacity != cellCapacity){
                return false;
            }
            for(int i = 0; i < members.length; i++){
                if(!members[i].equals(candidate.get(i))) return false;
            }
            return true;
        }
    }

    private final Map<String, ArrayInfo> byName = new LinkedHashMap<>();
    private final Map<String, MatrixInfo> matrices = new LinkedHashMap<>();
    private final Map<String, SpanInfo> spans = new LinkedHashMap<>();

    /** @return 按声明名查找的 span，未声明时为 null。 */
    public SpanInfo span(String name){
        return name == null ? null : spans.get(name);
    }

    /**
     * 反查：以 {@code member} 为成员的 span（按声明顺序；同一内存块可以出现在多条 span 里）。
     * 展开折叠要把「物理成员 + 格内地址」还原成「span 别名 + 逻辑地址」，这是唯一入口。
     */
    public List<SpanInfo> spansByMember(String member){
        List<SpanInfo> result = new ArrayList<>();
        if(member != null){
            for(SpanInfo info : spans.values()){
                if(info.indexOfMember(member) >= 0) result.add(info);
            }
        }
        return result;
    }

    /** @return 成员序列与每格容量完全一致的那条 span；形状重复（无法判定是哪一条）时 null。 */
    public SpanInfo spanByShape(List<String> members, int cellCapacity){
        SpanInfo found = null;
        for(SpanInfo info : spans.values()){
            if(!info.matchesShape(members, cellCapacity)) continue;
            if(found != null) return null;
            found = info;
        }
        return found;
    }

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
        ArrayRegistry previousCompiling = compiling;
        compiling = registry;
        try{
            return compileRegistryInto(registry, statements, functionNames);
        }finally{
            compiling = previousCompiling;
        }
    }

    private static ArrayRegistry compileRegistryInto(ArrayRegistry registry, Seq<LStatement> statements, Set<String> functionNames){
        // 同一内存块上已声明的区间（用于重叠校验）
        Map<String, List<long[]>> spans = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        // span 先于 array：capacityOf(span 名) 必须在数组容量检查时已经是 N*C，
        // 声明写在数组后面也一样（注册表是程序级的）。
        for(int i = 0; i < statements.size; i++){
            if(!(statements.get(i) instanceof SpanStatement card)) continue;
            registry.addSpan(card, i, names, functionNames);
        }
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

    /** 严格登记一张 span 卡。失败抛 {@link IllegalArgumentException}。 */
    private void addSpan(SpanStatement card, int index, Set<String> names, Set<String> functionNames){
        String name = card.name == null ? "" : card.name.trim();
        validateName(index, "span", name, names, functionNames);
        if(isCellLink(name) || isStorageLink(name)){
            throw error("span", index, "name '" + name + "' is a memory-link name; a span must not shadow a real link such as cell1");
        }
        List<String> members;
        try{
            members = SpanStatement.membersOf(card.expr);
        }catch(IllegalArgumentException e){
            throw error("span", index, "'" + name + "' " + e.getMessage());
        }
        for(String raw : members){
            if(raw.equals(name)){
                throw error("span", index, "member '" + raw + "' is the span name; a span must not shadow its own link");
            }
        }
        if(members.size() < 2){
            throw error("span", index, "'" + name + "' needs at least two cells");
        }
        Set<String> distinct = new HashSet<>();
        for(String member : members){
            if(!distinct.add(member)){
                throw error("span", index, "'" + name + "' repeats member '" + member + "'");
            }
            if(spans.containsKey(member)){
                throw error("span", index, "member '" + member + "' is itself a span");
            }
        }
        int cellCapacity = sharedCapacity(index, name, members);
        if(cellCapacity < 1){
            throw error("span", index, "'" + name + "' has no positive cell capacity");
        }
        long logical = (long)members.size() * cellCapacity;
        if(logical > Integer.MAX_VALUE){
            throw error("span", index, "'" + name + "' logical capacity is too large (" + logical + ")");
        }
        names.add(name);
        spans.put(name, new SpanInfo(name, members.toArray(new String[0]), cellCapacity));
    }

    /**
     * 成员共享容量。已链接的方块用它自己的 {@code memoryCapacity}，混合容量直接拒绝；
     * 特权内存块（world-cell）在非特权处理器上拒绝。
     *
     * <p>解析不到链接的成员与数组侧同一口径：回落 {@link #memoryCapacity} 的名字启发式
     * （{@code cellN}=64、{@code bankN}/{@code worldN}=512，大小写不敏感），猜不出来
     * （既没链接、名字也不是这三种链接名）仍然是编译错误——span 的 {@code idiv}/{@code mod}
     * 需要一个确定的每格容量，不能像数组容量检查那样“不限制”。</p>
     *
     * <p>2026-09 复核修正：这里原来只接受全 {@code cellN}，于是共享/换图后未链接的
     * {@code bank1 + bank2} 直接编译失败；而载体验证失败会让编辑器回落 vanilla 视图，
     * 用户下次保存就会丢掉只存在于载体里的 span/array 卡。对齐数组侧的猜测口径后，
     * 这类程序仍能编译（容量标为 inferred，错误信息会说来自变量名）。</p>
     */
    private static int sharedCapacity(int index, String name, List<String> members){
        LinkResolver resolver = linkResolver();
        int capacity = -1;
        boolean[] known = new boolean[members.size()];
        if(resolver != null){
            for(int m = 0; m < members.size(); m++){
                String member = members.get(m);
                int resolved = resolver.capacity(member);
                if(resolved > 0){
                    known[m] = true;
                    if(resolver.privilegedMemory(member) && !resolver.processorPrivileged()){
                        throw error("span", index, "'" + name + "' member '" + member
                            + "' is a privileged memory block on a non-privileged processor");
                    }
                    capacity = mergeCapacity(index, name, member, capacity, resolved, "linked", false);
                }else if(resolved == 0){
                    throw error("span", index, "'" + name + "' member '" + member + "' is not a linked memory block");
                }
            }
        }
        for(int m = 0; m < members.size(); m++){
            if(known[m]) continue;
            String member = members.get(m);
            int guessed = memoryCapacity(member);
            if(guessed <= 0){
                throw error("span", index, "'" + name + "' capacity is unknown for member '" + member
                    + "': it is not a linked memory block and not a cellN/bankN/worldN link name");
            }
            capacity = mergeCapacity(index, name, member, capacity, guessed, "inferred from the variable name", true);
        }
        return capacity;
    }

    /**
     * 合并一个成员的容量：与已确定值不同就是混合容量错误（也包括一个来自链接、另一个来自
     * 名字猜测的情况——span 的每次访问都要除以同一个 C）。{@code source} 只影响错误措辞。
     */
    private static int mergeCapacity(int index, String name, String member, int current, int value,
                                     String source, boolean guessed){
        if(current < 0) return value;
        if(current == value) return current;
        throw error("span", index, "'" + name + "' members have mixed capacities: " + current + " and "
            + value + " for '" + member + "'" + (guessed ? " (" + source + ")" : "")
            + "; every member of a span must address the same number of slots");
    }

    /** {@code cellN}：链接名模式（world-cell 的变量名也是它）。span 的逻辑名不得与真实链接重名。 */
    private static boolean isCellLink(String memory){
        return prefixedDigits(memory, "cell");
    }

    /** bankN / worldN / memoryN：同样是链接名，不能拿来当 span 的逻辑名。 */
    private static boolean isStorageLink(String memory){
        return prefixedDigits(memory, "bank") || prefixedDigits(memory, "world") || prefixedDigits(memory, "memory");
    }

    private static boolean prefixedDigits(String memory, String prefix){
        if(memory == null) return false;
        String link = memory.trim().toLowerCase(Locale.ROOT);
        return link.startsWith(prefix) && digitsOnly(link.substring(prefix.length()));
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
        int capacity = capacityOf(memory);
        if(capacity > 0 && end > capacity){
            throw error(card, index, "'" + name + "' needs addresses up to "
                + capacityExceeded(memory, end, capacity));
        }
    }

    /**
     * 逻辑内存块容量：{@code cellN} → 64，{@code bankN}/{@code worldN} → 512
     * （大小写不敏感，N 必须是数字）；其它名字返回 -1 表示跳过容量检查。
     *
     * <p><b>这是按名字猜的回落值，不是事实。</b>变量名与方块之间没有绑定关系：原版
     * {@code LogicBlock.getLinkName} 取方块名最后一个 '-' 之后的部分，所以 world-cell 的
     * 变量名同样是 {@code cellN}（它有 512 格，这里却猜 64）。能用 {@link #capacityOf} 拿到
     * 真实链接时就以真实容量为准；这个表只在无处理器上下文（无头自测、脱离处理器的编辑）
     * 时兜底。
     */
    public static int memoryCapacity(String memory){
        if(memory == null) return -1;
        String name = memory.trim().toLowerCase(Locale.ROOT);
        if(name.startsWith("cell")) return digitsOnly(name.substring(4)) ? HEADLESS_CELL_CAPACITY : -1;
        if(name.startsWith("bank")) return digitsOnly(name.substring(4)) ? 512 : -1;
        if(name.startsWith("world")) return digitsOnly(name.substring(5)) ? 512 : -1;
        return -1;
    }

    // ===== 真实容量解析（处理器链接） =====

    /** 容量数值的来源，决定错误措辞：解析到真实链接就不能把猜的数字说成事实。 */
    public enum CapacitySource{
        /** 由处理器当前链接的方块解析出的真实容量。 */
        linked,
        /** {@link #memoryCapacity} 按变量名猜出的容量。 */
        inferred,
        /** 已确认不是可寻址的内存块：跳过检查。 */
        notMemory,
        /** 无从判断（未知名字且无链接）：跳过检查。 */
        unknown
    }

    /** 把变量名解析成它当前链接的方块；返回 null 表示解析不到。 */
    public interface LinkResolver{
        Building linkedBuilding(String memory);

        /**
         * 该变量链接到的内存块容量。三态：
         * <ul>
         *   <li>{@code > 0}：解析到真实内存块，按此容量检查；</li>
         *   <li>{@code 0}：解析到了方块，但它不是可寻址的内存块 —— 确定不该限制；</li>
         *   <li>{@code < 0}：解析不到（无链接、变量缺失），调用方回落名字启发式。</li>
         * </ul>
         */
        int capacity(String memory);

        /** 该链接是特权内存块（world-cell）时为 true。默认不是。 */
        default boolean privilegedMemory(String memory){
            return false;
        }

        /** 当前处理器能否读特权内存块。没有处理器上下文时按特权处理，避免无头路径误拒。 */
        default boolean processorPrivileged(){
            return true;
        }

        /** 延迟提供解析器：提交/渲染时按需取当前会话的处理器，取不到就是"没有上下文"。 */
        interface Provider{
            LinkResolver resolve();
        }
    }

    private static LinkResolver.Provider linkResolverProvider;
    private static LinkResolver linkResolver;

    /** 安装延迟解析器提供者（mod 启动时调用一次）。 */
    public static void setLinkResolverProvider(LinkResolver.Provider provider){
        linkResolverProvider = provider;
    }

    /** 进入解析器上下文，返回先前的解析器供 {@link #restoreLinkResolver} 恢复（须 try/finally 配对）。 */
    public static LinkResolver enterLinkResolver(LinkResolver resolver){
        LinkResolver previous = linkResolver;
        linkResolver = resolver;
        return previous;
    }

    /** 恢复 {@link #enterLinkResolver} 返回的先前解析器。 */
    public static void restoreLinkResolver(LinkResolver previous){
        linkResolver = previous;
    }

    /** 当前解析器：显式上下文优先，否则按需取当前会话的处理器（渲染期也走这条）。 */
    private static LinkResolver linkResolver(){
        LinkResolver context = linkResolver;
        if(context != null) return context;
        LinkResolver.Provider provider = linkResolverProvider;
        if(provider == null) return null;
        try{
            return provider.resolve();
        }catch(Throwable t){
            // 无头自测环境（Vars.ui 未初始化等）：视同没有处理器上下文
            return null;
        }
    }

    /**
     * 真实容量：解析 {@code memory} 链接到的方块，是逻辑内存块就返回它的
     * {@link MemoryBlock#memoryCapacity}。解析不到返回 -1（调用方回落 {@link #memoryCapacity}），
     * 解析到但不是内存块返回 0（确定不该限制）。
     *
     * <p>读缓存按变量名存活在一次解析器实例内（容量是方块类型属性，不会变），所以画布渲染
     * 期间每个名字最多查一次链接。
     */
    public static int resolvedCapacity(String memory){
        LinkResolver resolver = linkResolver();
        if(resolver == null || memory == null) return -1;
        return resolver.capacity(memory);
    }

    /**
     * 最终容量口径：能解析到链接就以链接为准，否则回落名字启发式。{@code > 0} 才执行检查。
     *
     * <p>保守方向很重要：解析到"存在链接但不是内存块"（0）时**不**回落启发式——那样会拿一个
     * 猜的数字去拒绝一个确定的非内存目标；只有完全解析不到（-1）才用名字兜底。
     */
    public static int capacityOf(String memory){
        SpanInfo span = findSpan(memory);
        if(span != null) return span.logicalCapacity;
        int resolved = resolvedCapacity(memory);
        if(resolved > 0) return resolved;
        if(resolved == 0) return 0;
        return memoryCapacity(memory);
    }

    /** 编译中的注册表优先，其次是已 enter 的上下文，最后才是画布。 */
    public static SpanInfo findSpan(String memory){
        if(memory == null) return null;
        ArrayRegistry registry = contextRegistry();
        return registry == null ? null : registry.spans.get(memory);
    }

    /**
     * 反查：成员名 → 拥有该成员的 span（展开折叠用；取不到上下文时为空表）。
     * 返回多条时调用方必须按「不可判定」处理——多条 span 能解释同一段展开时
     * 折回哪一个别名并不唯一（最终仍有重编译比对兜底）。
     */
    public static List<SpanInfo> findSpansByMember(String member){
        if(member == null) return Collections.emptyList();
        ArrayRegistry registry = contextRegistry();
        return registry == null ? Collections.emptyList() : registry.spansByMember(member);
    }

    /** 反查：成员序列 + 每格容量 → span（展开折叠用；形状重复或没有上下文时 null）。 */
    public static SpanInfo findSpanByShape(List<String> members, int cellCapacity){
        ArrayRegistry registry = contextRegistry();
        return registry == null ? null : registry.spanByShape(members, cellCapacity);
    }

    /** 编译中 → 已 enter → 画布探测；都取不到返回 null。 */
    private static ArrayRegistry contextRegistry(){
        ArrayRegistry registry = compiling != null ? compiling : current;
        return registry != null ? registry : canvasRegistry();
    }

    /** {@link #capacityOf} 的数值来源。 */
    public static CapacitySource capacitySource(String memory){
        SpanInfo span = findSpan(memory);
        if(span != null){
            LinkResolver resolver = linkResolver();
            if(resolver != null && allMembersLinked(resolver, span)){
                return CapacitySource.linked;
            }
            // 自测/无链接上下文、或只有部分成员能解析时，容量可能来自名字启发式：
            // 宁可按“推断值”报，也不要让用户以为它是链接上读到的真实容量
            return CapacitySource.inferred;
        }
        int resolved = resolvedCapacity(memory);
        if(resolved > 0) return CapacitySource.linked;
        if(resolved == 0) return CapacitySource.notMemory;
        return memoryCapacity(memory) > 0 ? CapacitySource.inferred : CapacitySource.unknown;
    }

    private static boolean allMembersLinked(LinkResolver resolver, SpanInfo span){
        for(String member : span.members){
            if(resolver.capacity(member) <= 0) return false;
        }
        return span.members.length > 0;
    }

    /** 容量越界错误：解析到真实链接就直说，猜的要标明是推断值。 */
    public static String capacityExceeded(String memory, long end, int capacity){
        String detail = (end - 1) + ", but memory '" + memory + "' only has " + capacity + " slots";
        if(capacitySource(memory) == CapacitySource.linked) return detail;
        return detail + " (inferred from the variable name; no linked memory block was found)";
    }

    /** 当前的处理器链接解析器；无处理器上下文（函数库会话、无头自测）返回 null。 */
    public static LinkResolver processorLinks(){
        try{
            if(Vars.ui == null || !(Vars.ui.logic instanceof SugarLogicDialog dialog)) return null;
            LExecutor executor = dialog.executor;
            return executor == null ? null : new ExecutorLinks(executor);
        }catch(Throwable t){
            // 无头自测环境（Vars.ui 未初始化等）：视同没有处理器上下文
            return null;
        }
    }

    /** 处理器执行器的解析实现：按需读执行器变量表，把方块链接变成容量值。 */
    private static final class ExecutorLinks implements LinkResolver{
        private final LExecutor executor;
        private final Map<String, Integer> capacities = new java.util.HashMap<>();

        ExecutorLinks(LExecutor executor){
            this.executor = executor;
        }

        @Override
        public Building linkedBuilding(String memory){
            if(executor.build == null) return null;
            return executor.build.optionalLink(memory);
        }

        @Override
        public int capacity(String memory){
            if(capacities.containsKey(memory)) return capacities.get(memory);
            int capacity = -1;
            try{
                Building building = linkedBuilding(memory);
                if(building != null){
                    // linked to a live block: a memory block gives its real capacity, anything
                    // else is a definite "not addressable memory" (do not guess from the name)
                    capacity = building.isValid() && building.block instanceof MemoryBlock memoryBlock
                        ? memoryBlock.memoryCapacity
                        : 0;
                }
            }catch(Throwable t){
                // 链接查询失败时按"无法解析"处理，回落名字启发式
                capacity = -1;
            }
            capacities.put(memory, capacity);
            return capacity;
        }

        @Override
        public boolean privilegedMemory(String memory){
            Building building = linkedBuilding(memory);
            return building != null && building.block != null && building.block.privileged;
        }

        @Override
        public boolean processorPrivileged(){
            return executor.privileged;
        }
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
                if(elem.st instanceof SpanStatement card){
                    try{
                        registry.addSpan(card, 0, new HashSet<>(), null);
                    }catch(RuntimeException ignored){
                        // 宽松口径：不合法的 span 留给标红，不进入容量表
                    }
                }
            }
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
                    if(memory.isEmpty() || registry.byName.containsKey(name) || registry.matrices.containsKey(name)
                        || registry.spans.containsKey(name)) continue;
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
                    if(memory.isEmpty() || registry.byName.containsKey(name) || registry.matrices.containsKey(name)
                        || registry.spans.containsKey(name)) continue;
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
        ArrayRegistry scratch = new ArrayRegistry();
        for(int i = 0; i < statements.size; i++){
            if(!(statements.get(i) instanceof SpanStatement card)) continue;
            try{
                scratch.addSpan(card, i, names, functionNames);
            }catch(RuntimeException ignored){
                invalid[i] = true;
            }
        }
        ArrayRegistry previousCompiling = compiling;
        compiling = scratch;
        try{
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
                    int capacity = capacityOf(memory);
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
                    int capacity = capacityOf(memory);
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
        }finally{
            compiling = previousCompiling;
        }
    }

    // ===== 静态编译期上下文 =====

    private static ArrayRegistry current;
    /** {@link #compileRegistry} / {@link #markInvalidStatements} 建表期间，capacityOf 要看见尚未 enter 的 span。 */
    private static ArrayRegistry compiling;

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
