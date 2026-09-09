package logicsugar.assist.data;

import arc.scene.Element;
import arc.scene.ui.layout.Table;
import logicsugar.assist.expr.ArrayRegistry;
import logicsugar.assist.expr.ChainIntrinsics;
import logicsugar.assist.expr.ExprIntrinsics;
import mindustry.gen.LogicIO;
import mindustry.logic.LAssembler;
import mindustry.logic.LCanvas;
import mindustry.logic.LStatement;
import mindustry.logic.SugarCanvas;
import mindustry.logic.SugarStatements;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 链表模块（单链 + 空闲链）：声明卡 {@code chain <name> <memory> <base> <size>}
 * （纯编译期元数据，{@link DataDeclaration} 在 lower 阶段整体跳过，不产出任何 mlog 行）。
 *
 * <p><b>内存布局</b>：{@code size} 个节点，节点 i 占两个连续槽——value 在
 * {@code base + 2*i}，next 在 {@code base + 2*i + 1}；{@code next == -1} 表示链尾。
 * 声明区间为 {@code [base, base + 2*size)}。</p>
 *
 * <p><b>隐藏状态</b>是普通 mlog 变量（用户程序里 {@code __ls_} 前缀视为保留名）：</p>
 * <ul>
 *   <li>{@code __ls_chn_<name>_head}：链表头节点下标，{@code -1} = 空链；</li>
 *   <li>{@code __ls_chn_<name>_free}：空闲链头，{@code -1} = 无空闲节点。</li>
 * </ul>
 *
 * <p><b>必须显式初始化</b>：未赋值变量读取为 0（{@code LVar.num()}），不初始化直接
 * {@code cnew} 会把 0 号节点误认为空闲。第一次使用前调用 {@code cinit(c)}（等价
 * {@code cclear(c)}）：head = -1，空闲链重建为 {@code 0→1→…→size-1→-1}，返回 size。</p>
 *
 * <p>表达式操作（{@code cinit/cclear/cnew/cfree/cget/cset/cnext/clink/cshead/chead/clen}）
 * 见 {@link ChainIntrinsics}：读类与状态变量写是无分支直线链，写内存类与循环类是对
 * {@code __ls_builtin_chn*} 注入函数的 {@code funccall}（normal 模式全程序共享一份，
 * 未使用时不进入产物）。</p>
 *
 * <p><b>严格校验</b>（{@link #collect}，编译路径，问题抛 {@link IllegalArgumentException}）：
 * 名字合法/唯一（本模块内 chain 互斥，不得与 array/matrix、函数、其它数据结构声明卡重名）、
 * {@code memory} 非空且非保留前缀、{@code base}/{@code size} 为整数字面量（{@code base >= 0}，
 * {@code size >= 1}，与 {@code array} 卡口径一致）、同内存块声明区间不重叠、
 * 容量检查（cellN=64，bankN/worldN=512，其它名字跳过）。
 * {@link #markInvalid} 是同一套规则的编辑期标红（不抛错）。</p>
 *
 * <p><b>跨模块约束</b>：与其它数据结构的内存区间重叠不做校验（契约 §7 的已知限制）。
 * 与其它数据结构声明卡的<b>重名</b>用反射读取同批声明卡的 {@code name}/{@code map} 字段
 * 尽力拦截（字段缺失时静默跳过），权威校验仍以集成阶段为准。</p>
 */
public class ChainModule extends DataModule{
    public static final String ID = "chain";

    public static final String CHAIN_TOKEN = "chain";
    public static final String KIND_CHAIN = "chain";

    /** 保留前缀：状态变量、注入函数名都以它开头，用户声明名不得使用。 */
    public static final String RESERVED_PREFIX = "__ls_";

    /** 状态字段名（{@link #stateVar} 用）。 */
    public static final String FIELD_HEAD = "head";
    public static final String FIELD_FREE = "free";

    @Override
    public String id(){
        return ID;
    }

    // ===== 声明卡 =====

    /** {@code chain <name> <memory> <base> <size>} 声明卡（纯元数据，lower 跳过）。 */
    public static class ChainDeclStatement extends DataDeclaration{
        public String name = "c";
        public String memory = "cell1";
        public String base = "0";
        public String size = "8";

        @Override public String token(){ return CHAIN_TOKEN; }
        @Override public String name(){ return cardLabel("chain.card", "Chain"); }

        @Override
        public void build(Table table){
            table.add(cardLabel("chain.card", "Chain")).self(c -> hint(c, "chain.name"));
            field(table, name, value -> name = value).width(70f);
            table.add(cardLabel("array.memory", "mem")).self(c -> hint(c, "chain.memory"));
            field(table, memory, value -> memory = value).width(70f);
            table.add(cardLabel("array.base", "base")).self(c -> hint(c, "chain.base"));
            field(table, base, value -> base = value).width(45f);
            table.add(cardLabel("array.size", "size")).self(c -> hint(c, "chain.size"));
            field(table, size, value -> size = value).width(45f);
        }

        @Override
        public void write(StringBuilder out){
            out.append(CHAIN_TOKEN).append(' ').append(optional(name)).append(' ')
                .append(optional(memory)).append(' ').append(optional(base)).append(' ')
                .append(optional(size));
        }
    }

    // ===== 编译期注册表 =====

    /** 一个已声明的链表：内存块上的区间 [base, base+2*size) 加 head/free 隐藏状态变量。 */
    public static final class Info{
        public final String name;
        public final String memory;
        public final int base;
        public final int size;

        Info(String name, String memory, int base, int size){
            this.name = name;
            this.memory = memory;
            this.base = base;
            this.size = size;
        }

        /** 链表头节点下标变量（{@code __ls_chn_<name>_head}）。 */
        public String headVar(){
            return stateVar(name, FIELD_HEAD);
        }

        /** 空闲链头变量（{@code __ls_chn_<name>_free}）。 */
        public String freeVar(){
            return stateVar(name, FIELD_FREE);
        }

        /** 节点 i 的 value 槽物理地址。 */
        public int valueAddress(int i){
            return base + 2 * i;
        }

        /** 节点 i 的 next 槽物理地址。 */
        public int nextAddress(int i){
            return base + 2 * i + 1;
        }
    }

    /** 名字 → 声明信息（本模块内 chain 互不重名）。 */
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

    /** 隐藏状态变量名：{@code __ls_chn_<name>_<field>}。 */
    public static String stateVar(String name, String field){
        return "__ls_chn_" + name + "_" + field;
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

    /** 从当前画布收集 chain 声明卡；无 UI（无头自测）或画布不可用时为 null。 */
    public static Registry canvasRegistry(){
        try{
            SugarCanvas canvas = SugarCanvas.current();
            if(canvas == null || canvas.statements == null) return null;
            Registry registry = new Registry();
            Set<String> external = new HashSet<>();
            List<LStatement> siblings = new ArrayList<>();
            for(Element child : canvas.statements.getChildren()){
                if(!(child instanceof LCanvas.StatementElem elem)) continue;
                siblings.add(elem.st);
                if(!(elem.st instanceof ChainDeclStatement)){
                    String other = declaredName(elem.st);
                    if(other != null && !other.isEmpty()) external.add(other);
                }
            }
            for(LStatement statement : siblings){
                if(!(statement instanceof ChainDeclStatement card)) continue;
                String name = card.name.trim();
                String memory = card.memory.trim();
                Long base = parseIntLiteral(card.base);
                Long size = parseIntLiteral(card.size);
                // 宽松口径：名字合法、未被占用、内存块非空、区间字面量合法才登记；
                // 其余问题（重复、重叠、容量、与函数/数组重名）留给编译期严格校验与编辑期标红
                if(!isIdentifier(name) || name.startsWith(RESERVED_PREFIX)) continue;
                if(memory.isEmpty() || registry.get(name) != null || external.contains(name)) continue;
                if(base == null || base < 0 || base > Integer.MAX_VALUE
                    || size == null || size < 1 || size > Integer.MAX_VALUE) continue;
                registry.put(new Info(name, memory, (int)(long)base, (int)(long)size));
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
        if(LAssembler.customParsers.containsKey(CHAIN_TOKEN)) return;
        LogicIO.allStatements.add(ChainDeclStatement::new);
        LAssembler.customParsers.put(CHAIN_TOKEN, ChainModule::parseChain);
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
        return ChainIntrinsics.INSTANCE;
    }

    @Override
    public List<String> builtinSugar(){
        // 写内存（cnew/cfree/cset/clink）与循环（cinit/cclear/clen，以及 cfree 的摘链、
        // cnext 的越界哨兵）都放进注入函数体：WriteLine 不被条件/返回 lowering 识别，
        // 循环是 funcdef 内的 jump 回边，normal 模式全程序共享一份。
        return ChainIntrinsics.builtinSugar();
    }

    // ===== 解析器 =====

    public static LStatement parseChain(String[] tokens){
        ChainDeclStatement result = new ChainDeclStatement();
        result.name = token(tokens, 1);
        if(result.name.isEmpty()){
            throw new IllegalArgumentException("Invalid chain statement: missing chain name");
        }
        result.memory = token(tokens, 2);
        if(result.memory.isEmpty()){
            throw new IllegalArgumentException("Invalid chain statement: missing memory cell");
        }
        result.base = token(tokens, 3);
        result.size = token(tokens, 4);
        return result;
    }

    // ===== 严格校验（编译路径） =====

    /**
     * 从语句列表收集全部 {@code chain} 声明卡并严格校验，任何问题都抛出
     * {@link IllegalArgumentException}。卡片允许出现在程序任意位置，注册表是程序级的。
     *
     * @param functionNames 本地 funcdef + 库函数名的并集（链表名不得与之冲突）；
     *                      可为 null（不校验函数重名）
     */
    public static Registry compileRegistry(List<LStatement> statements, Set<String> functionNames){
        Registry registry = new Registry();
        Set<String> names = new HashSet<>();
        Map<String, List<long[]>> spans = new LinkedHashMap<>();
        ArrayRegistry arrays = ArrayRegistry.active();
        Set<String> external = otherDeclarationNames(statements);
        for(int i = 0; i < statements.size(); i++){
            LStatement statement = statements.get(i);
            if(!(statement instanceof ChainDeclStatement card)) continue;
            String name = card.name.trim();
            String memory = card.memory.trim();
            validateName(i, name, names, functionNames, arrays, external);
            validateMemory(i, name, memory);
            long base = literal(i, name, "base", card.base, 0);
            long size = literal(i, name, "size", card.size, 1);
            long end = base + 2 * size;
            addSpan(spans, i, name, memory, base, end);
            checkCapacity(i, name, memory, end);
            names.add(name);
            registry.put(new Info(name, memory, (int)base, (int)size));
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
            }else if(!(statement instanceof ChainDeclStatement)){
                String other = declaredName(statement);
                if(other != null && !other.isEmpty()) externalNames.add(other);
            }
        }
        for(int i = 0; i < statements.size() && i < invalid.length; i++){
            LStatement statement = statements.get(i);
            if(!(statement instanceof ChainDeclStatement card)) continue;
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
            long end = bad ? 0 : base + 2 * size;
            if(!bad){
                int capacity = ArrayRegistry.memoryCapacity(memory);
                bad = capacity > 0 && end > capacity;
            }
            if(!bad){
                List<long[]> existing = spans.computeIfAbsent(memory, k -> new ArrayList<>());
                for(long[] span : existing){
                    if(base < span[1] && span[0] < end){ bad = true; break; }
                }
                if(!bad) existing.add(new long[]{base, end});
            }
            if(bad){
                invalid[i] = true;
            }else{
                names.add(name);
            }
        }
    }

    // ===== 校验工具 =====

    private static void validateName(int index, String name, Set<String> names,
                                     Set<String> functionNames, ArrayRegistry arrays, Set<String> external){
        if(name.isEmpty()) throw error(index, "name must not be empty");
        if(!isIdentifier(name)) throw error(index, "name '" + name + "' must match [A-Za-z_][A-Za-z0-9_]*");
        if(name.startsWith(RESERVED_PREFIX)) throw error(index, "name '" + name + "' uses the reserved '" + RESERVED_PREFIX + "' prefix");
        if(names.contains(name)) throw error(index, "duplicate name '" + name + "'");
        if(functionNames != null && functionNames.contains(name)){
            throw error(index, "name '" + name + "' conflicts with a function of the same name");
        }
        if(arrays != null && (arrays.get(name) != null || arrays.getMatrix(name) != null)){
            throw error(index, "name '" + name + "' conflicts with an array or matrix of the same name");
        }
        if(external.contains(name)){
            throw error(index, "name '" + name + "' conflicts with another data structure of the same name");
        }
    }

    private static void validateMemory(int index, String name, String memory){
        if(memory.isEmpty()) throw error(index, "chain '" + name + "' needs a memory cell variable");
        if(memory.startsWith(RESERVED_PREFIX)){
            throw error(index, "memory '" + memory + "' uses the reserved '" + RESERVED_PREFIX + "' prefix");
        }
    }

    private static long literal(int index, String name, String field, String token, long minimum){
        Long value = parseIntLiteral(token);
        if(value == null || value < minimum || value > Integer.MAX_VALUE){
            throw error(index, "chain '" + name + "' " + field + " must be "
                + (minimum == 0 ? "a non-negative" : "an") + " integer literal of at least " + minimum
                + " (variables are not supported yet), got '" + token + "'");
        }
        return value;
    }

    private static void addSpan(Map<String, List<long[]>> spans, int index, String name,
                                String memory, long base, long end){
        List<long[]> existing = spans.computeIfAbsent(memory, k -> new ArrayList<>());
        for(long[] span : existing){
            if(base < span[1] && span[0] < end){
                throw error(index, "'" + name + "' range [" + base + ", " + end
                    + ") overlaps another chain on '" + memory + "'");
            }
        }
        existing.add(new long[]{base, end});
    }

    private static void checkCapacity(int index, String name, String memory, long end){
        int capacity = ArrayRegistry.memoryCapacity(memory);
        if(capacity > 0 && end > capacity){
            throw error(index, "'" + name + "' needs addresses up to " + (end - 1)
                + ", but memory '" + memory + "' only has " + capacity + " slots");
        }
    }

    private static IllegalArgumentException error(int index, String detail){
        return new IllegalArgumentException("chain at statement " + index + " " + detail + ".");
    }

    // ===== 跨结构重名（尽力而为） =====

    /**
     * 同一批声明卡里其它数据结构的名字（{@link DataDeclaration} 卡片都带 public
     * {@code name}/{@code map} 字段，反射读取；字段缺失或不可访问时静默跳过）。
     * 跨模块的权威重名校验仍由集成阶段统一处理（契约 §7）。
     */
    private static Set<String> otherDeclarationNames(List<LStatement> statements){
        Set<String> names = new HashSet<>();
        for(LStatement statement : statements){
            if(statement instanceof ChainDeclStatement) continue;
            String name = declaredName(statement);
            if(name != null && !name.isEmpty()) names.add(name);
        }
        return names;
    }

    private static String declaredName(LStatement statement){
        if(!(statement instanceof DataDeclaration)) return null;
        for(String field : new String[]{"name", "map"}){
            try{
                Field declared = statement.getClass().getField(field);
                Object value = declared.get(statement);
                if(value instanceof String text) return text.trim();
            }catch(Throwable ignored){
                // 字段缺失/不可访问：该结构不参与重名检查
            }
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
