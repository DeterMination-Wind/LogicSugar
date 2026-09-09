package logicsugar.assist.data;

import arc.Core;
import arc.scene.ui.layout.Table;
import logicsugar.assist.expr.ArrayRegistry;
import logicsugar.assist.expr.ContainerIntrinsics;
import logicsugar.assist.expr.ExprIntrinsics;
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
 * 栈 + 队列 + 双端队列（环形缓冲）模块：声明卡
 * {@code stack}/{@code queue}/{@code deque} {@code <name> <memory> <base> <size>}
 * （纯编译期元数据，不产出任何 mlog 行），表达式操作由 {@link ContainerIntrinsics} 展开：
 * 读/变量类是无分支 {@code op}/{@code read} 直线链，写内存类（push）是对注入函数
 * {@code __ls_builtin_stkpush}/{@code __ls_builtin_quepush}/{@code __ls_builtin_deqpushf}
 * 的 {@code funccall}（原因见 {@link ContainerIntrinsics} 类注释：框架的条件/返回 lowering
 * 不识别 {@link logicsugar.assist.expr.ExprCompiler.WriteLine}）。
 *
 * <p>状态是普通 mlog 变量（用户程序里 {@code __ls_} 前缀视为保留名）：</p>
 * <ul>
 *   <li>栈：{@code __ls_stk_<name>_top} = 元素个数（0 = 空）；</li>
 *   <li>队列：{@code __ls_que_<name>_head} / {@code __ls_que_<name>_tail} /
 *       {@code __ls_que_<name>_count}，恒有 {@code tail == (head + count) % size}；</li>
 *   <li>双端队列：{@code __ls_deq_<name>_head/_tail/_count}，同一环形不变式。</li>
 * </ul>
 * <p>mlog 变量未赋值时读取为 0（{@code LVar.num()} 把 null/NaN 视作 0），因此「初始 0」
 * 不需要任何初始化指令——声明卡不产行这条硬约束与语义不冲突。</p>
 *
 * <p>严格校验（{@link #collect}，编译路径，问题抛 {@link IllegalArgumentException}）：
 * 名字合法/唯一（本模块内 stack/queue/deque 互不重名，也不得与 array/matrix/函数重名）、
 * {@code memory} 非空且非保留前缀、{@code base}/{@code size} 为整数字面量
 * （{@code base >= 0}，{@code size >= 1}，与 {@code array} 卡口径一致）、
 * 同内存块区间不重叠、容量检查（cellN=64，bankN/worldN=512，其它名字跳过）。
 * {@link #markInvalidStatements} 是同一套规则的编辑期标红（不抛错）。</p>
 *
 * <p>跨模块约束：只校验本模块声明的区间；与其它模块（array/matrix/record/...）在
 * 同一内存块上的重叠不做校验（契约 §7 的已知限制）。与 array/matrix 的<b>重名</b>校验
 * 通过 {@link ArrayRegistry#active()} 完成，其它结构（record/list/...）的名字冲突
 * 需要集成阶段统一处理。</p>
 */
public class ContainerModule extends DataModule{
    public static final String ID = "container";

    public static final String STACK_TOKEN = "stack";
    public static final String QUEUE_TOKEN = "queue";
    public static final String DEQUE_TOKEN = "deque";
    public static final String KIND_STACK = "stack";
    public static final String KIND_QUEUE = "queue";
    public static final String KIND_DEQUE = "deque";

    /** 保留前缀：状态变量、注入函数名都以它开头，用户声明名不得使用。 */
    public static final String RESERVED_PREFIX = "__ls_";

    /** 状态字段名（{@link #stateVar} 用）。 */
    public static final String FIELD_TOP = "top";
    public static final String FIELD_HEAD = "head";
    public static final String FIELD_TAIL = "tail";
    public static final String FIELD_COUNT = "count";

    @Override
    public String id(){
        return ID;
    }

    // ===== 声明卡 =====

    /** {@code stack <name> <memory> <base> <size>} 声明卡（纯元数据，lower 跳过）。 */
    public static class StackDeclStatement extends DataDeclaration{
        public String name = "s";
        public String memory = "cell1";
        public String base = "0";
        public String size = "8";

        @Override public String token(){ return STACK_TOKEN; }
        @Override public String name(){ return cardText("stack.card", "Stack"); }

        @Override
        public void build(Table table){
            table.add(cardText("stack.card", "Stack")).self(c -> hint(c, "stack.name"));
            field(table, name, value -> name = value).width(70f);
            table.add(cardText("array.memory", "mem")).self(c -> hint(c, "stack.memory"));
            field(table, memory, value -> memory = value).width(70f);
            table.add(cardText("array.base", "base")).self(c -> hint(c, "stack.base"));
            field(table, base, value -> base = value).width(45f);
            table.add(cardText("array.size", "size")).self(c -> hint(c, "stack.size"));
            field(table, size, value -> size = value).width(45f);
        }

        @Override
        public void write(StringBuilder out){
            out.append(STACK_TOKEN).append(' ').append(optional(name)).append(' ')
                .append(optional(memory)).append(' ').append(optional(base)).append(' ')
                .append(optional(size));
        }
    }

    /** {@code queue <name> <memory> <base> <size>} 声明卡（环形缓冲，纯元数据）。 */
    public static class QueueDeclStatement extends DataDeclaration{
        public String name = "q";
        public String memory = "cell1";
        public String base = "0";
        public String size = "8";

        @Override public String token(){ return QUEUE_TOKEN; }
        @Override public String name(){ return cardText("queue.card", "Queue"); }

        @Override
        public void build(Table table){
            table.add(cardText("queue.card", "Queue")).self(c -> hint(c, "queue.name"));
            field(table, name, value -> name = value).width(70f);
            table.add(cardText("array.memory", "mem")).self(c -> hint(c, "queue.memory"));
            field(table, memory, value -> memory = value).width(70f);
            table.add(cardText("array.base", "base")).self(c -> hint(c, "queue.base"));
            field(table, base, value -> base = value).width(45f);
            table.add(cardText("array.size", "size")).self(c -> hint(c, "queue.size"));
            field(table, size, value -> size = value).width(45f);
        }

        @Override
        public void write(StringBuilder out){
            out.append(QUEUE_TOKEN).append(' ').append(optional(name)).append(' ')
                .append(optional(memory)).append(' ').append(optional(base)).append(' ')
                .append(optional(size));
        }
    }

    /** {@code deque <name> <memory> <base> <size>} 声明卡（双端环形缓冲，纯元数据）。 */
    public static class DequeDeclStatement extends DataDeclaration{
        public String name = "d";
        public String memory = "cell1";
        public String base = "0";
        public String size = "8";

        @Override public String token(){ return DEQUE_TOKEN; }
        @Override public String name(){ return cardText("deque.card", "Deque"); }

        @Override
        public void build(Table table){
            table.add(cardText("deque.card", "Deque")).self(c -> hint(c, "deque.name"));
            field(table, name, value -> name = value).width(70f);
            table.add(cardText("array.memory", "mem")).self(c -> hint(c, "deque.memory"));
            field(table, memory, value -> memory = value).width(70f);
            table.add(cardText("array.base", "base")).self(c -> hint(c, "deque.base"));
            field(table, base, value -> base = value).width(45f);
            table.add(cardText("array.size", "size")).self(c -> hint(c, "deque.size"));
            field(table, size, value -> size = value).width(45f);
        }

        @Override
        public void write(StringBuilder out){
            out.append(DEQUE_TOKEN).append(' ').append(optional(name)).append(' ')
                .append(optional(memory)).append(' ').append(optional(base)).append(' ')
                .append(optional(size));
        }
    }

    // ===== 编译期注册表 =====

    /** 一个已声明的容器：内存块上的区间 [base, base+size) 加隐藏状态变量。 */
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

        /** 该容器的隐藏状态变量名（{@code top}/{@code head}/{@code tail}/{@code count}）。 */
        public String stateVar(String field){
            return ContainerModule.stateVar(kind, name, field);
        }
    }

    /** 名字 → 声明信息（本模块内 stack/queue/deque 共用一张表，保证互不重名）。 */
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

    /** 隐藏状态变量名：栈 {@code __ls_stk_}、队列 {@code __ls_que_}、双端队列 {@code __ls_deq_}。 */
    public static String stateVar(String kind, String name, String field){
        String prefix = KIND_DEQUE.equals(kind) ? "__ls_deq_"
            : KIND_QUEUE.equals(kind) ? "__ls_que_"
            : "__ls_stk_";
        return prefix + name + "_" + field;
    }

    // ===== 静态编译期上下文 =====

    private static Registry current;
    private Registry previousRegistry;

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

    /** 收集当前打开的 Sugar 画布上的 stack/queue/deque 声明卡（跳过不合法的卡片），画布不可用时为 null。 */
    public static Registry canvasRegistry(){
        try{
            SugarCanvas canvas = SugarCanvas.current();
            if(canvas == null || canvas.statements == null) return null;
            Registry registry = new Registry();
            for(arc.scene.Element child : canvas.statements.getChildren()){
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
        if(parsersInstalled) return;
        parsersInstalled = true;
        LogicIO.allStatements.add(StackDeclStatement::new);
        LogicIO.allStatements.add(QueueDeclStatement::new);
        LogicIO.allStatements.add(DequeDeclStatement::new);
        LAssembler.customParsers.put(STACK_TOKEN, ContainerModule::parseStack);
        LAssembler.customParsers.put(QUEUE_TOKEN, ContainerModule::parseQueue);
        LAssembler.customParsers.put(DEQUE_TOKEN, ContainerModule::parseDeque);
    }

    private static boolean parsersInstalled;

    @Override
    public void collect(List<LStatement> statements, Set<String> functionNames){
        previousRegistry = enter(compileRegistry(statements, functionNames));
    }

    @Override
    public void restore(){
        leave(previousRegistry);
        previousRegistry = null;
    }

    @Override
    public void markInvalid(List<LStatement> statements, boolean[] invalid, Set<String> functionNames){
        markInvalidStatements(statements, invalid, functionNames);
    }

    @Override
    public ExprIntrinsics.Provider intrinsics(){
        return ContainerIntrinsics.INSTANCE;
    }

    @Override
    public List<String> builtinSugar(){
        // push 需要条件写内存；框架的条件/返回 lowering 不识别 WriteLine，
        // 因此写操作放进注入函数体（CallLine 通道在所有表达式上下文都可用）。
        return ContainerIntrinsics.builtinSugar();
    }

    // ===== 解析器 =====

    public static LStatement parseStack(String[] tokens){
        StackDeclStatement result = new StackDeclStatement();
        result.name = token(tokens, 1);
        if(result.name.isEmpty()){
            throw new IllegalArgumentException("Invalid stack statement: missing stack name");
        }
        result.memory = token(tokens, 2);
        if(result.memory.isEmpty()){
            throw new IllegalArgumentException("Invalid stack statement: missing memory cell");
        }
        result.base = token(tokens, 3);
        result.size = token(tokens, 4);
        return result;
    }

    public static LStatement parseQueue(String[] tokens){
        QueueDeclStatement result = new QueueDeclStatement();
        result.name = token(tokens, 1);
        if(result.name.isEmpty()){
            throw new IllegalArgumentException("Invalid queue statement: missing queue name");
        }
        result.memory = token(tokens, 2);
        if(result.memory.isEmpty()){
            throw new IllegalArgumentException("Invalid queue statement: missing memory cell");
        }
        result.base = token(tokens, 3);
        result.size = token(tokens, 4);
        return result;
    }

    public static LStatement parseDeque(String[] tokens){
        DequeDeclStatement result = new DequeDeclStatement();
        result.name = token(tokens, 1);
        if(result.name.isEmpty()){
            throw new IllegalArgumentException("Invalid deque statement: missing deque name");
        }
        result.memory = token(tokens, 2);
        if(result.memory.isEmpty()){
            throw new IllegalArgumentException("Invalid deque statement: missing memory cell");
        }
        result.base = token(tokens, 3);
        result.size = token(tokens, 4);
        return result;
    }

    // ===== 严格校验（编译路径） =====

    /**
     * 从语句列表收集全部 {@code stack}/{@code queue}/{@code deque} 声明卡并严格校验，任何问题都抛出
     * {@link IllegalArgumentException}。卡片允许出现在程序任意位置，注册表是程序级的。
     *
     * @param functionNames 本地 funcdef + 库函数名的并集（容器名不得与之冲突）；
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
        for(int i = 0; i < statements.size(); i++){
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
                    + ") overlaps another stack, queue or deque on '" + memory + "'");
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

    /** 声明卡视图（统一 stack/queue/deque 的处理）。 */
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
        if(statement instanceof StackDeclStatement stack){
            return new Card(KIND_STACK, stack.name, stack.memory, stack.base, stack.size);
        }
        if(statement instanceof QueueDeclStatement queue){
            return new Card(KIND_QUEUE, queue.name, queue.memory, queue.base, queue.size);
        }
        if(statement instanceof DequeDeclStatement deque){
            return new Card(KIND_DEQUE, deque.name, deque.memory, deque.base, deque.size);
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

    private static String cardText(String key, String fallback){
        try{
            if(Core.bundle != null) return Core.bundle.get("logicsugar." + key, fallback);
        }catch(Throwable ignored){
            // 无头环境（Core.bundle 未初始化）：退回英文 fallback
        }
        return fallback;
    }
}
