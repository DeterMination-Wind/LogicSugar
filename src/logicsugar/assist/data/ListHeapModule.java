package logicsugar.assist.data;

import arc.scene.Element;
import arc.scene.ui.layout.Table;
import logicsugar.assist.expr.ArrayRegistry;
import logicsugar.assist.expr.ExprIntrinsics;
import logicsugar.assist.expr.ListHeapIntrinsics;
import mindustry.gen.LogicIO;
import mindustry.logic.LAssembler;
import mindustry.logic.LCanvas;
import mindustry.logic.LStatement;
import mindustry.logic.SugarCanvas;
import mindustry.logic.SugarStatements;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 列表 + 小顶堆模块（契约 §1「列表」「堆（小顶）」）：
 * 声明卡 {@code list <name> <memory> <base> <size>} /
 * {@code heap <name> <memory> <base> <size>}（纯编译期元数据，{@link DataDeclaration}
 * 在 lower 阶段整体跳过，不产出任何 mlog 行）。
 *
 * <p>表达式操作见 {@link ListHeapIntrinsics}：</p>
 * <ul>
 *   <li><b>列表</b>（顺序存储，紧凑无空洞）：{@code lappend/lget/lset/linsert/lremove/lfind/lsize}；</li>
 *   <li><b>小顶堆</b>（二叉堆，数组实现）：{@code hpush/hpop/hsize}。</li>
 * </ul>
 *
 * <p>状态是普通 mlog 变量（用户程序里 {@code __ls_} 前缀视为保留名）：
 * {@code __ls_lst_<name>_count} / {@code __ls_hep_<name>_count} = 元素个数（0 = 空）。
 * mlog 变量未赋值时读取为 0（{@code LVar.num()} 把 null/NaN 视作 0），因此「初始 0」
 * 不需要任何初始化指令——声明卡不产行这条硬约束与语义不冲突。</p>
 *
 * <p>严格校验（{@link #collect}，编译路径，问题抛 {@link IllegalArgumentException}）：
 * 名字合法/唯一（本模块内 list/heap 互斥，也不得与 array/matrix/函数重名）、
 * {@code memory} 非空且非保留前缀、{@code base}/{@code size} 为整数字面量
 * （{@code base >= 0}，{@code size >= 1}，与 {@code array} 卡口径一致）、
 * 同内存块区间不重叠、容量检查（cellN=64，bankN/worldN=512，其它名字跳过）。
 * {@link #markInvalid} 是同一套规则的编辑期标红（不抛错）。</p>
 *
 * <p>跨模块约束：只校验本模块声明的区间；与其它模块（array/matrix/record/stack/queue/...）
 * 在同一内存块上的重叠不做校验（契约 §7 的已知限制）。与 array/matrix 的<b>重名</b>校验
 * 通过 {@link ArrayRegistry#active()} 完成。</p>
 */
public class ListHeapModule extends DataModule{
    public static final String ID = "list-heap";

    public static final String LIST_TOKEN = "list";
    public static final String HEAP_TOKEN = "heap";
    public static final String KIND_LIST = "list";
    public static final String KIND_HEAP = "heap";

    /** 保留前缀：状态变量、注入函数名都以它开头，用户声明名不得使用。 */
    public static final String RESERVED_PREFIX = "__ls_";

    /** 状态字段名（{@link #stateVar} 用）。 */
    public static final String FIELD_COUNT = "count";

    @Override
    public String id(){
        return ID;
    }

    // ===== 声明卡 =====

    /** {@code list <name> <memory> <base> <size>} 声明卡（纯元数据，lower 跳过）。 */
    public static class ListDeclStatement extends DataDeclaration{
        public String name = "l";
        public String memory = "cell1";
        public String base = "0";
        public String size = "8";

        @Override public String token(){ return LIST_TOKEN; }
        @Override public String name(){ return cardLabel("list.card", "List"); }

        @Override
        public void build(Table table){
            table.add(cardLabel("list.card", "List")).self(c -> hint(c, "list.name"));
            field(table, name, value -> name = value).width(70f);
            table.add(cardLabel("array.memory", "mem")).self(c -> hint(c, "list.memory"));
            field(table, memory, value -> memory = value).width(70f);
            table.add(cardLabel("array.base", "base")).self(c -> hint(c, "list.base"));
            field(table, base, value -> base = value).width(45f);
            table.add(cardLabel("array.size", "size")).self(c -> hint(c, "list.size"));
            field(table, size, value -> size = value).width(45f);
        }

        @Override
        public void write(StringBuilder out){
            out.append(LIST_TOKEN).append(' ').append(optional(name)).append(' ')
                .append(optional(memory)).append(' ').append(optional(base)).append(' ')
                .append(optional(size));
        }
    }

    /** {@code heap <name> <memory> <base> <size>} 声明卡（小顶堆，纯元数据）。 */
    public static class HeapDeclStatement extends DataDeclaration{
        public String name = "h";
        public String memory = "cell1";
        public String base = "0";
        public String size = "8";

        @Override public String token(){ return HEAP_TOKEN; }
        @Override public String name(){ return cardLabel("heap.card", "Heap"); }

        @Override
        public void build(Table table){
            table.add(cardLabel("heap.card", "Heap")).self(c -> hint(c, "heap.name"));
            field(table, name, value -> name = value).width(70f);
            table.add(cardLabel("array.memory", "mem")).self(c -> hint(c, "heap.memory"));
            field(table, memory, value -> memory = value).width(70f);
            table.add(cardLabel("array.base", "base")).self(c -> hint(c, "heap.base"));
            field(table, base, value -> base = value).width(45f);
            table.add(cardLabel("array.size", "size")).self(c -> hint(c, "heap.size"));
            field(table, size, value -> size = value).width(45f);
        }

        @Override
        public void write(StringBuilder out){
            out.append(HEAP_TOKEN).append(' ').append(optional(name)).append(' ')
                .append(optional(memory)).append(' ').append(optional(base)).append(' ')
                .append(optional(size));
        }
    }

    // ===== 编译期注册表 =====

    /** 一个已声明的列表/堆：内存块上的区间 [base, base+size) 加隐藏计数变量。 */
    public static final class Info{
        public final String kind;
        public final String name;
        public final String memory;
        public final int base;
        public final int size;

        Info(String kind, String name, String memory, int base, int size){
            this.kind = kind;
            this.name = name;
            this.memory = memory;
            this.base = base;
            this.size = size;
        }

        /** 该结构的隐藏计数变量（{@code __ls_lst_<name>_count} / {@code __ls_hep_<name>_count}）。 */
        public String countVar(){
            return stateVar(kind, name);
        }
    }

    /** 名字 → 声明信息（本模块内 list/heap 共用一张表，保证互不重名）。 */
    public static final class Registry{
        private final Map<String, Info> byName = new LinkedHashMap<>();

        public Info get(String name){
            return name == null ? null : byName.get(name);
        }

        public boolean isEmpty(){
            return byName.isEmpty();
        }

        public int size(){
            return byName.size();
        }

        public List<Info> all(){
            return new ArrayList<>(byName.values());
        }

        void put(Info info){
            byName.put(info.name, info);
        }
    }

    /** 隐藏计数变量名：{@code __ls_lst_<name>_count} / {@code __ls_hep_<name>_count}。 */
    public static String stateVar(String kind, String name){
        return (KIND_HEAP.equals(kind) ? "__ls_hep_" : "__ls_lst_") + name + "_" + FIELD_COUNT;
    }

    // ===== 静态编译期上下文 =====

    /** 注册表栈：{@link #collect} 压入、{@link #restore} 弹出（与 DataModules 配对，支持嵌套）。 */
    private static final List<Registry> registryStack = new ArrayList<>();
    private static Registry current;

    /** 进入编译期上下文，返回先前的注册表供 {@link #leave} 恢复（须 try/finally 配对）。 */
    public static Registry enter(Registry registry){
        Registry previous = current;
        current = registry;
        return previous;
    }

    /** 恢复 {@link #enter} 返回的先前上下文。 */
    public static void leave(Registry previous){
        current = previous;
    }

    /** 当前编译期注册表：编译上下文优先，否则回退到当前画布（编辑器展开用）。 */
    public static Registry active(){
        Registry context = current;
        if(context != null) return context;
        return canvasRegistry();
    }

    /** 从当前画布收集 list/heap 声明卡；无 UI（无头自测）或画布不可用时为 null。 */
    public static Registry canvasRegistry(){
        try{
            SugarCanvas canvas = SugarCanvas.current();
            if(canvas == null || canvas.statements == null) return null;
            Registry registry = new Registry();
            for(Element child : canvas.statements.getChildren()){
                if(!(child instanceof LCanvas.StatementElem elem)) continue;
                Card card = card(elem.st);
                if(card == null) continue;
                String name = card.name.trim();
                String memory = card.memory.trim();
                Long base = parseIntLiteral(card.base);
                Long size = parseIntLiteral(card.size);
                // 宽松口径：名字合法、未被占用、内存块非空、区间字面量合法才登记；
                // 其余问题（重复、重叠、容量、与函数/数组重名）留给编译期严格校验与编辑期标红
                if(!isIdentifier(name) || name.startsWith(RESERVED_PREFIX)) continue;
                if(memory.isEmpty() || registry.get(name) != null) continue;
                if(base == null || base < 0 || base > Integer.MAX_VALUE
                    || size == null || size < 1 || size > Integer.MAX_VALUE) continue;
                registry.put(new Info(card.kind, name, memory, (int)(long)base, (int)(long)size));
            }
            return registry;
        }catch(Throwable t){
            // 无头自测环境（Vars.ui 未初始化等）：视同没有编辑器上下文
            return null;
        }
    }

    // ===== DataModule =====

    @Override
    public void registerParsers(){
        // 幂等：集成阶段可能重复调用（与 BitsetModule 同款 containsKey 守卫）
        if(!LAssembler.customParsers.containsKey(LIST_TOKEN)){
            LogicIO.allStatements.add(ListDeclStatement::new);
            LAssembler.customParsers.put(LIST_TOKEN, ListHeapModule::parseList);
        }
        if(!LAssembler.customParsers.containsKey(HEAP_TOKEN)){
            LogicIO.allStatements.add(HeapDeclStatement::new);
            LAssembler.customParsers.put(HEAP_TOKEN, ListHeapModule::parseHeap);
        }
    }

    @Override
    public void collect(List<LStatement> statements, Set<String> functionNames){
        registryStack.add(current);
        current = compileRegistry(statements, functionNames);
    }

    @Override
    public void restore(){
        current = registryStack.isEmpty() ? null : registryStack.remove(registryStack.size() - 1);
    }

    @Override
    public void markInvalid(List<LStatement> statements, boolean[] invalid, Set<String> functionNames){
        markInvalidStatements(statements, invalid, functionNames);
    }

    @Override
    public ExprIntrinsics.Provider intrinsics(){
        return ListHeapIntrinsics.INSTANCE;
    }

    @Override
    public List<String> builtinSugar(){
        // 循环型操作（insert/remove/find/push/pop）放进注入函数体：循环是 funcdef 内的
        // jump 回边，normal 模式全程序共享一份；写内存也只在函数体里出现（WriteLine 不被
        // 条件/返回 lowering 识别，见 ListHeapIntrinsics 类注释）。
        return ListHeapIntrinsics.builtinSugar();
    }

    // ===== 解析器 =====

    public static LStatement parseList(String[] tokens){
        ListDeclStatement result = new ListDeclStatement();
        result.name = token(tokens, 1);
        if(result.name.isEmpty()){
            throw new IllegalArgumentException("Invalid list statement: missing list name");
        }
        result.memory = token(tokens, 2);
        if(result.memory.isEmpty()){
            throw new IllegalArgumentException("Invalid list statement: missing memory cell");
        }
        result.base = token(tokens, 3);
        result.size = token(tokens, 4);
        return result;
    }

    public static LStatement parseHeap(String[] tokens){
        HeapDeclStatement result = new HeapDeclStatement();
        result.name = token(tokens, 1);
        if(result.name.isEmpty()){
            throw new IllegalArgumentException("Invalid heap statement: missing heap name");
        }
        result.memory = token(tokens, 2);
        if(result.memory.isEmpty()){
            throw new IllegalArgumentException("Invalid heap statement: missing memory cell");
        }
        result.base = token(tokens, 3);
        result.size = token(tokens, 4);
        return result;
    }

    // ===== 严格校验（编译路径） =====

    /**
     * 从语句列表收集全部 {@code list}/{@code heap} 声明卡并严格校验，任何问题都抛出
     * {@link IllegalArgumentException}。卡片允许出现在程序任意位置，注册表是程序级的。
     *
     * @param functionNames 本地 funcdef + 库函数名的并集（结构名不得与之冲突）；
     *                      可为 null（不校验函数重名）
     */
    public static Registry compileRegistry(List<LStatement> statements, Set<String> functionNames){
        Registry registry = new Registry();
        Set<String> names = new HashSet<>();
        Map<String, List<long[]>> spans = new LinkedHashMap<>();
        ArrayRegistry arrays = ArrayRegistry.active();
        for(int i = 0; i < statements.size(); i++){
            Card card = card(statements.get(i));
            if(card == null) continue;
            String name = card.name.trim();
            String memory = card.memory.trim();
            validateName(card.kind, i, name, names, functionNames, arrays);
            validateMemory(card.kind, i, name, memory);
            long base = literal(card.kind, i, name, "base", card.base, 0);
            long size = literal(card.kind, i, name, "size", card.size, 1);
            addSpan(spans, card.kind, i, name, memory, base, size);
            checkCapacity(card.kind, i, name, memory, base + size);
            names.add(name);
            registry.put(new Info(card.kind, name, memory, (int)base, (int)size));
        }
        return registry;
    }

    // ===== 编辑期标红 =====

    /**
     * 编辑期字段级校验：把有问题的声明卡标红（{@code invalid[i] = true}），不抛错。
     * 规则与 {@link #compileRegistry} 一致；array/matrix 重名从同一语句列表里就地收集
     * （编辑期不一定存在编译注册表上下文）。
     */
    public static void markInvalidStatements(List<LStatement> statements, boolean[] invalid, Set<String> functionNames){
        Set<String> names = new HashSet<>();
        Map<String, List<long[]>> spans = new LinkedHashMap<>();
        Set<String> externalNames = new HashSet<>();
        for(LStatement statement : statements){
            if(statement instanceof SugarStatements.ArrayStatement array){
                externalNames.add(trim(array.array));
            }else if(statement instanceof SugarStatements.MatrixStatement matrix){
                externalNames.add(trim(matrix.matrix));
            }
        }
        for(int i = 0; i < statements.size() && i < invalid.length; i++){
            Card card = card(statements.get(i));
            if(card == null) continue;
            String name = card.name.trim();
            String memory = card.memory.trim();
            Long base = parseIntLiteral(card.base);
            Long size = parseIntLiteral(card.size);
            boolean bad = name.isEmpty() || !isIdentifier(name) || name.startsWith(RESERVED_PREFIX)
                || memory.isEmpty() || memory.startsWith(RESERVED_PREFIX)
                || base == null || base < 0 || base > Integer.MAX_VALUE
                || size == null || size < 1 || size > Integer.MAX_VALUE
                || names.contains(name)
                || externalNames.contains(name)
                || (functionNames != null && functionNames.contains(name));
            if(!bad){
                int capacity = ArrayRegistry.memoryCapacity(memory);
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
            }
        }
    }

    // ===== 校验工具 =====

    private static void validateName(String kind, int index, String name, Set<String> names,
                                     Set<String> functionNames, ArrayRegistry arrays){
        if(name.isEmpty()) throw error(kind, index, "name must not be empty");
        if(!isIdentifier(name)) throw error(kind, index, "name '" + name + "' must match [A-Za-z_][A-Za-z0-9_]*");
        if(name.startsWith(RESERVED_PREFIX)) throw error(kind, index, "name '" + name + "' uses the reserved '" + RESERVED_PREFIX + "' prefix");
        if(names.contains(name)) throw error(kind, index, "duplicate name '" + name + "'");
        if(functionNames != null && functionNames.contains(name)){
            throw error(kind, index, "name '" + name + "' conflicts with a function of the same name");
        }
        if(arrays != null && (arrays.get(name) != null || arrays.getMatrix(name) != null)){
            throw error(kind, index, "name '" + name + "' conflicts with an array or matrix of the same name");
        }
    }

    private static void validateMemory(String kind, int index, String name, String memory){
        if(memory.isEmpty()) throw error(kind, index, "container '" + name + "' needs a memory cell variable");
        if(memory.startsWith(RESERVED_PREFIX)){
            throw error(kind, index, "memory '" + memory + "' uses the reserved '" + RESERVED_PREFIX + "' prefix");
        }
    }

    private static long literal(String kind, int index, String name, String field, String token, long minimum){
        Long value = parseIntLiteral(token);
        if(value == null || value < minimum || value > Integer.MAX_VALUE){
            throw error(kind, index, "container '" + name + "' " + field + " must be "
                + (minimum == 0 ? "a non-negative" : "an") + " integer literal of at least " + minimum
                + " (variables are not supported yet), got '" + token + "'");
        }
        return value;
    }

    private static void addSpan(Map<String, List<long[]>> spans, String kind, int index, String name,
                                String memory, long base, long size){
        List<long[]> existing = spans.computeIfAbsent(memory, k -> new ArrayList<>());
        for(long[] span : existing){
            if(base < span[1] && span[0] < base + size){
                throw error(kind, index, "'" + name + "' range [" + base + ", " + (base + size)
                    + ") overlaps another list or heap on '" + memory + "'");
            }
        }
        existing.add(new long[]{base, base + size});
    }

    private static void checkCapacity(String kind, int index, String name, String memory, long end){
        int capacity = ArrayRegistry.memoryCapacity(memory);
        if(capacity > 0 && end > capacity){
            throw error(kind, index, "'" + name + "' needs addresses up to " + (end - 1)
                + ", but memory '" + memory + "' only has " + capacity + " slots");
        }
    }

    private static IllegalArgumentException error(String kind, int index, String detail){
        return new IllegalArgumentException(kind + " at statement " + index + " " + detail + ".");
    }

    /** 声明卡视图（统一 list/heap 的处理）。 */
    private static final class Card{
        final String kind, name, memory, base, size;

        Card(String kind, String name, String memory, String base, String size){
            this.kind = kind;
            this.name = name == null ? "" : name;
            this.memory = memory == null ? "" : memory;
            this.base = base == null ? "" : base;
            this.size = size == null ? "" : size;
        }
    }

    private static Card card(LStatement statement){
        if(statement instanceof ListDeclStatement list){
            return new Card(KIND_LIST, list.name, list.memory, list.base, list.size);
        }
        if(statement instanceof HeapDeclStatement heap){
            return new Card(KIND_HEAP, heap.name, heap.memory, heap.base, heap.size);
        }
        return null;
    }

    // ===== 工具 =====

    static boolean isIdentifier(String name){
        if(name == null || name.isEmpty()) return false;
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

    private static String token(String[] tokens, int index){
        if(tokens == null || index >= tokens.length || tokens[index] == null) return "";
        String value = tokens[index];
        return value.equals("~") ? "" : value;
    }

    private static String trim(String value){
        return value == null ? "" : value.trim();
    }

    private static String optional(String value){
        return value == null || value.isEmpty() ? "~" : value;
    }

    /** 卡片标签：bundle 有键时用翻译，无头环境退回英文 fallback。 */
    private static String cardLabel(String key, String fallback){
        try{
            return SugarStatements.cardText(key, fallback);
        }catch(Throwable ignored){
            // 无头环境（Core.settings/Core.bundle 未初始化）：退回英文 fallback
            return fallback;
        }
    }
}
