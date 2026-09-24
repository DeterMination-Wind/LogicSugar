package logicsugar.assist.data;

import arc.func.Prov;
import arc.struct.Seq;
import logicsugar.assist.expr.ArrayRegistry;
import logicsugar.assist.expr.ExprCompiler;
import logicsugar.assist.expr.ExprIntrinsics;
import mindustry.gen.LogicIO;
import mindustry.logic.LAssembler;
import mindustry.logic.LCategory;
import mindustry.logic.LStatement;
import mindustry.logic.SugarFunctions;
import mindustry.logic.SugarStatements;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据子系统模块注册表与编译期上下文（{@link DataModule} 的统一驱动点）。
 *
 * <p>与 {@link logicsugar.assist.expr.ArrayRegistry#enter}/{@code restore} 相同的静态上下文
 * 模式：{@link #collectAll} 进入（对每个模块调用 {@link DataModule#collect}），
 * {@link #restore} 退出（对每个模块调用 {@link DataModule#restore}），由
 * {@code SugarCompiler.compile} 以 try/finally 配对，异常路径同样清理。</p>
 *
 * <p>模块的注册（{@link #register}）同时把 {@link DataModule#intrinsics()} 注册进
 * {@link ExprIntrinsics}：表达式展开在 analyze 阶段就要能识别 intrinsic 调用名
 * （可达性登记），所以 provider 必须在编译开始前就可见，而不是等到 collectAll。</p>
 */
public final class DataModules{
    private DataModules(){}

    private static final List<DataModule> modules = new ArrayList<>();

    /** v5.2：旧操作名（v5.1 及更早的 carrier/卡片文本）→ 规范新名。
     *  仅为加载/解析兼容，永不写回 {@link #paletteCalls()} 缓存，避免旧名重新出现在加号菜单。 */
    private static final Map<String, String> LEGACY_ALIASES = legacyAliases();

    /** 声明卡 token（{@link DataDeclaration#token()}）→ 运算分组键，供 {@link #orderPaletteCards()} 用。 */
    private static final Map<String, String> DECLARATION_GROUPS = declarationGroups();

    /** 声明卡不在 {@link SugarStatements#dataStructures} 栏的结构：分组键 → 它真正的栏。 */
    private static final Map<String, LCategory> GROUP_COLUMNS = groupColumns();

    /** 允许 null 的上下文栈（ArrayDeque 拒绝 null 元素）。 */
    private static final List<List<DataModule>> contextStack = new ArrayList<>();

    private static Map<String, String> declarationGroups(){
        Map<String, String> map = new LinkedHashMap<>();
        map.put("stack", "stackops");
        map.put("queue", "queueops");
        map.put("deque", "dequeops");
        map.put("bitset", "bitsetops");
        map.put("map", "mapops");
        map.put("uset", "setops");
        map.put("list", "listops");
        map.put("heap", "heapops");
        map.put("chain", "chainops");
        return Collections.unmodifiableMap(map);
    }

    /**
     * 结构分组 → 它的<em>声明卡</em>所在的选板栏（{@link #paletteColumn(String)} 的答案）。
     *
     * <p>分组键与选板栏是两件事，{@link #groupKey} 只回答"哪几个运算属于同一结构"，用的是各族
     * {@code LCategory} 的**名字**（{@code stackops}/{@code mapops}/…）——那些分类本身并不是选板
     * 栏，只作分组标签与悬停标题。运算卡该去哪一栏，取决于该结构的声明卡在哪一栏。</p>
     *
     * <p>9395a04 的取舍正是"运算卡与声明卡同栏"，但当时把栏直接写成
     * {@link SugarStatements#dataStructures}；而数组/矩阵是三处例外——它们的声明卡在
     * {@link SugarStatements#arrayAlgo}（该栏的 bundle 描述也明写含 {@code array_sum}、
     * {@code array_fill} 等可持久化批量运算积木，见 {@code LogicSugarMod.registerStatements} 里
     * 紧挨着 {@code matrix} 的注册），于是只有这一组的声明卡与运算卡被拆到了两栏。本表专为修它
     * 而设：未登记的结构与声明卡同栏，即落在 {@link SugarStatements#dataStructures}。</p>
     *
     * <p>键是 {@link #groupKey}，即该组 {@code PaletteCall} 所用分类的名字。{@code ArrayBulkModule}
     * 传的就是 {@code arrayAlgo}，所以数组组的键与栏同名——看着像恒等式，换别的结构就不是了。</p>
     */
    private static Map<String, LCategory> groupColumns(){
        Map<String, LCategory> map = new LinkedHashMap<>();
        map.put(SugarStatements.arrayAlgo.name, SugarStatements.arrayAlgo);
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, String> legacyAliases(){
        Map<String, String> map = new HashMap<>();
        map.put("spush", "stack_push");
        map.put("spop", "stack_pop");
        map.put("speek", "stack_top");
        map.put("ssize", "stack_size");
        map.put("sclear", "stack_clear");
        map.put("qpush", "queue_push");
        map.put("qpop", "queue_pop");
        map.put("qpeek", "queue_front");
        map.put("qsize", "queue_size");
        map.put("qclear", "queue_clear");
        map.put("dpushf", "deque_push_front");
        map.put("dpushb", "deque_push_back");
        map.put("dpopf", "deque_pop_front");
        map.put("dpopb", "deque_pop_back");
        map.put("dpeekf", "deque_front");
        map.put("dpeekb", "deque_back");
        map.put("dsize", "deque_size");
        map.put("dclear", "deque_clear");
        map.put("lappend", "vector_push_back");
        map.put("lget", "vector_at");
        map.put("lset", "vector_set");
        map.put("linsert", "vector_insert");
        map.put("lremove", "vector_erase");
        map.put("lfind", "vector_find");
        map.put("lsize", "vector_size");
        map.put("hpush", "heap_push");
        map.put("hpop", "heap_pop");
        map.put("hsize", "heap_size");
        map.put("mapset", "map_set");
        map.put("mapget", "map_get");
        map.put("maphas", "map_contains");
        map.put("mapdel", "map_erase");
        map.put("mapsize", "map_size");
        map.put("mapclear", "map_clear");
        map.put("uadd", "set_add");
        map.put("uhas", "set_contains");
        map.put("udel", "set_remove");
        map.put("usize", "set_size");
        map.put("uclear", "set_clear");
        map.put("cinit", "chain_init");
        map.put("cclear", "chain_clear");
        map.put("cnew", "chain_alloc");
        map.put("cfree", "chain_free");
        map.put("cget", "chain_get");
        map.put("cset", "chain_set");
        map.put("cnext", "chain_next");
        map.put("clink", "chain_link");
        map.put("cshead", "chain_set_head");
        map.put("chead", "chain_head");
        map.put("clen", "chain_len");
        map.put("bset", "bitset_set");
        map.put("bclr", "bitset_reset");
        map.put("btest", "bitset_test");
        map.put("bcount", "bitset_count");
        map.put("sum", "array_sum");
        map.put("avg", "array_avg");
        map.put("min", "array_min");
        map.put("max", "array_max");
        map.put("count", "array_count");
        map.put("indexof", "array_find");
        map.put("fill", "array_fill");
        map.put("copy", "array_copy");
        map.put("sortasc", "array_sort");
        map.put("sortdesc", "array_sort_desc");
        map.put("reverse", "array_reverse");
        map.put("replace", "array_replace");
        map.put("swap", "array_swap");
        map.put("bsearch", "array_lower_bound");
        return Collections.unmodifiableMap(map);
    }
    private static List<DataModule> current;
    private static Set<String> builtinNamesCache;
    private static Map<String, List<String>> builtinParamsCache;
    private static Map<String, DataModule.PaletteCall> paletteCallsCache;
    private static Map<String, List<DataModule.PaletteCall>> operationGroupsCache;
    private static boolean paletteStatementsRegistered;

    /** 登记一个模块（按 {@link DataModule#id()} 幂等）并注册其 intrinsic provider。 */
    public static void register(DataModule module){
        if(module == null) return;
        for(DataModule existing : modules){
            if(existing.id() != null && existing.id().equals(module.id())) return;
        }
        modules.add(module);
        ExprIntrinsics.Provider provider = module.intrinsics();
        if(provider != null) ExprIntrinsics.register(provider);
        builtinNamesCache = null;
        builtinParamsCache = null;
        paletteCallsCache = null;
        operationGroupsCache = null;
    }

    /**
     * 注册全部模块的声明卡解析器（集成阶段调用；模块实现须幂等）。
     *
     * <p>加号菜单只注册**每个结构一张卡**（{@link #operationGroups()}）：卡片内的运算选择器
     * 负责在结构内切换具体运算，避免把 68 个运算铺成 68 个积木让用户滚菜单
     *（对照原版 {@code op} 卡：一个积木 + 卡内运算按钮）。载体格式
     *（{@code datacall <operation> <dest> "<args>"}）与解析路径完全不变。</p>
     *
     * <p>注册完成后还会调用 {@link #orderPaletteCards()}，把每张运算卡挪到对应结构的声明卡后面。</p>
     */
    public static void registerParsers(){
        for(DataModule module : new ArrayList<>(modules)){
            module.registerParsers();
        }
        if(paletteStatementsRegistered) return;
        paletteStatementsRegistered = true;
        LAssembler.customParsers.put(DataCallStatement.TOKEN, DataCallStatement::parse);

        // 已登记过的分组（模组重载后 LogicIO 里还留着上一轮的卡片）不重复登记，但仍参与下面的重排。
        Set<String> registered = new LinkedHashSet<>();
        for(Prov<LStatement> provider : LogicIO.allStatements){
            if(provider.get() instanceof DataCallStatement data && data.groupKey() != null){
                registered.add(data.groupKey());
            }
        }
        for(Map.Entry<String, List<DataModule.PaletteCall>> entry : operationGroups().entrySet()){
            List<DataModule.PaletteCall> group = entry.getValue();
            if(group.isEmpty() || !registered.add(entry.getKey())) continue;
            LogicIO.allStatements.add(() -> new DataCallStatement(group.get(0)));
        }

        orderPaletteCards();
    }

    /**
     * 加号菜单按 {@link LogicIO#allStatements} 的顺序铺卡片，而声明卡（栈/队列/哈希表/…）由各模块在
     * 注册阶段先加进来、运算卡统一在最后加，于是菜单里同一结构被拆成上下两段（「栈」在第二行、
     * 「栈操作」在列表末尾），选运算要先跨行找卡片。
     *
     * <p>这里做一次稳定重排：把每个结构的运算卡搬到它的声明卡后面，其余语句的相对顺序不动。
     * 幂等——已排好的顺序再排一次结果不变，所以在模组重载后重复调用也安全。</p>
     */
    private static void orderPaletteCards(){
        Seq<Prov<LStatement>> statements = LogicIO.allStatements;

        Map<String, Prov<LStatement>> cards = new LinkedHashMap<>();
        for(Prov<LStatement> provider : statements){
            if(provider.get() instanceof DataCallStatement data && data.groupKey() != null){
                cards.putIfAbsent(data.groupKey(), provider);
            }
        }
        if(cards.isEmpty()) return;

        Seq<Prov<LStatement>> ordered = new Seq<>();
        List<Prov<LStatement>> moved = new ArrayList<>();
        Set<String> anchored = new LinkedHashSet<>();
        for(Prov<LStatement> provider : statements){
            if(moved.contains(provider)) continue; // 已在上面紧跟声明卡输出
            LStatement example = provider.get();
            ordered.add(provider);
            if(example instanceof DataDeclaration declaration){
                String key = DECLARATION_GROUPS.get(declaration.token());
                Prov<LStatement> card = key == null ? null : cards.get(key);
                if(card != null && anchored.add(key)){
                    ordered.add(card);
                    moved.add(card);
                }
            }
        }
        if(ordered.size != statements.size) return; // 不该发生：宁可不排也不丢卡片

        statements.clear();
        for(Prov<LStatement> statement : ordered){
            statements.add(statement);
        }
    }

    /** Intrinsic palette metadata, keyed case-insensitively by source operation. */
    public static Map<String, DataModule.PaletteCall> paletteCalls(){
        if(paletteCallsCache != null) return paletteCallsCache;
        Map<String, DataModule.PaletteCall> result = new LinkedHashMap<>();
        for(DataModule module : new ArrayList<>(modules)){
            List<DataModule.PaletteCall> calls = module.paletteCalls();
            if(calls == null) continue;
            for(DataModule.PaletteCall call : calls){
                if(call != null && call.name != null && !call.name.isEmpty()){
                    result.putIfAbsent(call.name.toLowerCase(java.util.Locale.ROOT), call);
                }
            }
        }
        paletteCallsCache = Collections.unmodifiableMap(result);
        return paletteCallsCache;
    }

    public static DataModule.PaletteCall paletteCall(String name){
        if(name == null) return null;
        DataModule.PaletteCall direct = paletteCalls().get(name.toLowerCase(java.util.Locale.ROOT));
        if(direct != null) return direct;
        // 旧名只作为解析别名：映射到规范新名后查 palette，不把旧名写入 paletteCallsCache。
        String canonical = LEGACY_ALIASES.get(name.toLowerCase(java.util.Locale.ROOT));
        return canonical == null ? null : paletteCalls().get(canonical);
    }

    /**
     * 运算卡的参数名列表，逐位对应 {@link DataModule.PaletteCall#arguments} 的默认实参串。
     *
     * <p>定参输入框每个框的占位提示就是这里的名字。之所以直接从默认串推导、不另建一份元数据：
     * v5.2 的 68 个有卡运算全量核对过，「默认串的段数 == {@code Provider.arity()}」且「每一段
     * 恰好就是一个参数名」，mismatch = 0；整个名字域只有 19 个词——结构名
     * {@code buf/dst/s/q/d/bits/map/l/h/c} 与值名
     * {@code value/src/oldValue/newValue/i/j/index/key/next}。新增运算只要把默认串写对，
     * 输入框的个数与名字就自动对上，不必再回来登记。</p>
     *
     * <p>拆分必须是括号感知的：默认串里可能带嵌套调用（如 {@code map_get(map, f(a, b))}），
     * 朴素按逗号切会把它切坏。这里复用编译路径同一个 {@link SugarFunctions#splitArgs}，
     * 保证「UI 拆出来的槽」与「载体重新拼回去的串」是同一套规则。</p>
     *
     * @return 参数名列表；未知运算返回空列表，调用方退回单框
     */
    public static List<String> paletteParams(String operation){
        DataModule.PaletteCall call = paletteCall(operation);
        if(call == null) return Collections.emptyList();
        return SugarFunctions.splitArgs(call.arguments);
    }

    /**
     * 运算卡的分组键：同族运算（原 per-family {@code LCategory}，如 {@code stackops}）共用一张卡。
     *
     * <p>分组只影响加号菜单与卡片内的运算选择器；{@link DataModule.PaletteCall#category}
     * 仍保留每族分类（供分组标签 {@code lcategory.<name>}）与分组键推导，卡片本身则挂在
     * **其声明卡所在的栏**下（见 {@link #paletteColumn(String)} 与
     * {@link DataCallStatement#category()}）。</p>
     */
    public static String groupKey(DataModule.PaletteCall call){
        return call == null || call.category == null ? null : call.category.name;
    }

    /**
     * 某个结构分组的运算卡落在哪个选板栏：**与该结构的声明卡同栏**。
     *
     * <p>数组/矩阵的声明卡在「数组算法」栏，所以 {@code array_sum}/{@code array_fill} 这些运算卡
     * 也去那里，和它们操作的结构待在一起；其余结构的声明卡在「数据结构」栏，运算卡跟着留下。
     * 规则表见 {@link #GROUP_COLUMNS}，由 {@link DataCallStatement#category()} 取用。</p>
     */
    public static LCategory paletteColumn(String groupKey){
        LCategory column = groupKey == null ? null : GROUP_COLUMNS.get(groupKey);
        return column == null ? SugarStatements.dataStructures : column;
    }

    /**
     * 结构分组 → 该结构的全部运算（按模块注册顺序），用于「一个结构一张运算卡」的加号菜单。
     *
     * <p>顺序即 {@link #paletteCalls()} 的插入顺序：模块注册顺序决定分组顺序，组内顺序即
     * 各模块 {@code callsWithFirst} 的书写顺序（首项同时是卡片的默认运算）。</p>
     */
    public static Map<String, List<DataModule.PaletteCall>> operationGroups(){
        if(operationGroupsCache != null) return operationGroupsCache;
        Map<String, List<DataModule.PaletteCall>> grouped = new LinkedHashMap<>();
        for(DataModule.PaletteCall call : paletteCalls().values()){
            String key = groupKey(call);
            if(key == null) continue;
            List<DataModule.PaletteCall> group = grouped.get(key);
            if(group == null){
                group = new ArrayList<>();
                grouped.put(key, group);
            }
            group.add(call);
        }
        Map<String, List<DataModule.PaletteCall>> result = new LinkedHashMap<>();
        for(Map.Entry<String, List<DataModule.PaletteCall>> entry : grouped.entrySet()){
            result.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        operationGroupsCache = Collections.unmodifiableMap(result);
        return operationGroupsCache;
    }

    /** 某个结构分组的运算列表；未知分组返回空列表（旧载体里的未知运算名走这条路径）。 */
    public static List<DataModule.PaletteCall> operationGroup(String groupKey){
        if(groupKey == null) return Collections.emptyList();
        List<DataModule.PaletteCall> calls = operationGroups().get(groupKey);
        return calls == null ? Collections.emptyList() : calls;
    }

    /** 旧操作名（v5.1 及更早的 carrier/卡片文本）→ 规范新名；新名与未知名字原样返回。
     *
     * <p>卡片解析时用它归一化 {@code operation} 字段：c7511b9 的承诺是"菜单只显示新名"，
     * 但只做别名解析会让旧载体重开的卡片继续显示旧名（卡片正文写 {@code spush(...)}、悬停提示
     * 也按旧名取键）并把它写回下一次保存的载体。
     * 归一化后旧存档打开即显示新名，重新保存也只写新名；两者的可执行流逐字相同
     *（{@code DataSubsystemIntegrationTest.renamedOpsLowerIdentically} 钉住这一点）。</p>
     */
    public static String canonicalOperation(String name){
        if(name == null) return null;
        String canonical = LEGACY_ALIASES.get(name.toLowerCase(java.util.Locale.ROOT));
        return canonical == null ? name : canonical;
    }

    /** 轻量扫描全部声明卡，得到 名字 → 结构种类（供 analyze 阶段解析方法糖）。
     *  同名冲突按模块注册顺序先到先得；不做严格校验，非法卡片由后续 collect 拦截。 */
    public static Map<String, String> declaredKinds(List<LStatement> statements){
        Map<String, String> result = new LinkedHashMap<>();
        if(statements == null) return result;
        for(DataModule module : new ArrayList<>(modules)){
            for(LStatement statement : statements){
                Map<String, String> declared = module.declaredKinds(statement);
                if(declared == null || declared.isEmpty()) continue;
                for(Map.Entry<String, String> entry : declared.entrySet()){
                    String name = entry.getKey();
                    if(name == null || name.isEmpty() || entry.getValue() == null) continue;
                    result.putIfAbsent(name, entry.getValue());
                }
            }
        }
        return result;
    }

    /** 进入编译期上下文：每个模块建立/进入自己的注册表（与 {@link #restore} 配对）。 */
    public static void collectAll(List<LStatement> statements, Set<String> functionNames){
        List<DataModule> snapshot = new ArrayList<>(modules);
        contextStack.add(current);
        current = snapshot;
        for(DataModule module : snapshot){
            module.collect(statements, functionNames);
        }
    }

    /** 退出编译期上下文：统一清理每个模块的编译期状态并恢复先前的上下文快照。 */
    public static void restore(){
        if(contextStack.isEmpty()) return; // 与 collectAll 不配对时安全空转
        List<DataModule> snapshot = current;
        current = contextStack.remove(contextStack.size() - 1);
        if(snapshot == null) return;
        for(DataModule module : snapshot){
            module.restore();
        }
    }

    /** 当前是否处于 {@link #collectAll} 与 {@link #restore} 之间。 */
    public static boolean isCollecting(){
        return current != null;
    }

    /** 当前编译上下文的模块快照（不在编译中时为空）。 */
    public static List<DataModule> activeModules(){
        return current == null ? Collections.emptyList() : Collections.unmodifiableList(current);
    }

    /** 编辑期标红：逐模块调用 {@link DataModule#markInvalid}。 */
    public static void markInvalid(List<LStatement> statements, boolean[] invalid, Set<String> functionNames){
        for(DataModule module : new ArrayList<>(modules)){
            module.markInvalid(statements, invalid, functionNames);
        }
    }

    // ===== 运算卡的编辑期标红 =====

    /** 编辑期试编译上下文（{@link #enterCallValidation} 与 {@link #leaveCallValidation} 配对）。 */
    private static CallValidationContext callValidation;

    /** 试编译上下文建立前保存的三份先前静态状态，供回滚。 */
    private static final class CallValidationContext{
        final Set<String> functions;
        final Map<String, String> kinds;
        final ArrayRegistry arrays;
        final boolean collecting;
        /** 这份上下文是否由当前调用建立。重入时共享的是外层那一份（{@code false}），
         *  此时调用方不是它的主人，不能 {@link DataModules#leaveCallValidation()} 把它拆掉。 */
        final boolean owned;

        CallValidationContext(Set<String> functions, Map<String, String> kinds, ArrayRegistry arrays,
                              boolean collecting, boolean owned){
            this.functions = functions;
            this.kinds = kinds;
            this.arrays = arrays;
            this.collecting = collecting;
            this.owned = owned;
        }
    }

    /**
     * 编辑期标红：运算卡（{@link DataCallStatement}）的实参写错时把该行标红。
     *
     * <p>分两层，都是为了让编辑器说的和编译器说的**是同一句话**：</p>
     * <ol>
     *   <li><b>形状检查</b>（不需要任何注册表，纯读卡片字段）：未知运算、有返回值的运算缺目标
     *       变量、实参数个数与运算定义不符（或实参串残缺到拆不开）。这三条正是
     *       {@code SugarFunctions.emitDataCall} 在调用 {@code ExprCompiler.compileForcedIntrinsic}
     *       **之前**拦下的错误。</li>
     *   <li><b>试编译</b>：临时建立编译期的静态上下文（数组注册表、声明种类表、用户函数遮蔽、
     *       各数据模块注册表——顺序见 {@code SugarCompiler.compile}），然后对每张卡跑与编译期
     *       同一个 {@link ExprCompiler#compileForcedIntrinsic}，抛错即标红。判断来源是同一个函数，
     *       所以"编辑期说合法、编译期却失败"的漂移不可能出现——不需要第二份参数校验规则。</li>
     * </ol>
     *
     * <p>上下文建立失败（某张声明卡自身非法导致 {@code collect} 抛错）时**放弃**第 2 层：
     * 那种情况下非法的声明卡自己已经标红，宁可漏报也不能误报。</p>
     *
     * <p>没有运算卡时零开销（不建立上下文）——绝大多数程序走这条路。</p>
     */
    public static void markInvalidCalls(List<LStatement> statements, boolean[] invalid, Set<String> functionNames){
        if(statements == null || invalid == null || statements.isEmpty()) return;

        List<Integer> pending = new ArrayList<>();
        for(int i = 0; i < statements.size() && i < invalid.length; i++){
            if(!(statements.get(i) instanceof DataCallStatement call)) continue;
            if(callShapeInvalid(call)){
                invalid[i] = true;
            }else{
                pending.add(i);
            }
        }
        if(pending.isEmpty()) return;

        CallValidationContext context = enterCallValidation(statements, functionNames);
        if(context == null) return;
        try{
            for(int index : pending){
                if(!callCompiles((DataCallStatement)statements.get(index))) invalid[index] = true;
            }
        }finally{
            // 只有建立者才拆：重入时共享的是外层那一份，替它拆掉会让外层的 callCompiles 在无上下文
            // 状态下跑——与"静态上下文泄漏"是同一条红线的两侧。
            if(context.owned) leaveCallValidation();
        }
    }

    /** 编辑期第一层：不依赖任何注册表的形状错误。覆盖范围与 {@code emitDataCall} 的前置校验
     *  <b>有重叠但不相等</b>：这里管「这张卡能不能编译」（运算名未知、有返回值却没目标、
     *  实参数多于参数量、实参串括号/引号不配平），编译期那边管「参数个数必须完全相等」。
     *
     *  <p>判据**独立于** {@link DataCallStatement#argumentSlots()}，这是刻意的：那个方法只负责
     *  「按字段数拆槽」（好让建卡与逐键写回共用同一份实现），它对中间态一律照拆——未配平的
     *  {@code max(a,}、某一格里多打的逗号，都仍然给出定参框。若拿它的 {@code null} 当标红信号，
     *  两者就互相牵制：放宽拆分等于顺手松掉标红，收紧拆分等于让用户打字打到一半就退框。
     *  所以这里自己看实参串，只看真正说明问题的两件事——配平与个数。</p>
     *
     *  <p>实参数<b>多于</b>参数量是错误（编译期必然报参数个数不符）；<b>少于</b>则不在这里判，
     *  交给 {@link #markInvalidCalls} 第二层的试编译——空槽连着占位参数名，本身就是「这里还没填」
     *  的提示（{@code DataCallTest.argumentSlotsAreFixedAndLossless} 钉住补齐空槽这一点），
     *  而卡片形态与标红是两件事：偏少该红，但红的同时必须保住定参框。</p> */
    private static boolean callShapeInvalid(DataCallStatement call){
        DataModule.PaletteCall spec = paletteCall(call.canonicalOperation());
        // 未知运算（损坏载体、或未来版本写入的名字）：编译期 emitDataCall 直接抛 unknown data intrinsic
        if(spec == null) return true;
        // 有返回值的运算必须有目标变量，否则编译期抛 requires a destination variable
        if(spec.returnsValue && isBlank(call.destination)) return true;
        // 括号/引号不配平的实参串编译不过（表达式残缺），当场标红比等到保存再报更直接
        if(!SugarFunctions.balancedArgs(call.arguments)) return true;
        // 实参数多于运算定义：定参框会把多出来的段并进最后一格（内容不丢），但编译期仍然报个数不符
        return SugarFunctions.splitArgs(call.arguments).size() > paletteParams(call.canonicalOperation()).size();
    }

    /**
     * 编辑期第二层：在编译期上下文里试编译这一张卡。
     *
     * <p>目标变量为空时用一个私有名字承载 intrinsic 的遗留返回值——与 {@code emitDataCall} 给
     * 无结果运算准备 discard 变量的做法一致，免得 {@code compileForcedIntrinsic} 少走
     * {@code finishResult} 而漏判目标名的合法性。越界断言行传 {@code false}：那是调试构建的
     * 产物形状，编辑器路径恒不发射。</p>
     */
    private static boolean callCompiles(DataCallStatement call){
        String operation = call.canonicalOperation();
        String destination = isBlank(call.destination) ? "__ls_datacall_validation_dest" : call.destination;
        try{
            ExprCompiler.compileForcedIntrinsic(destination, operation,
                call.arguments == null ? "" : call.arguments, false);
            return true;
        }catch(Throwable ignored){
            return false;
        }
    }

    private static boolean isBlank(String value){
        return value == null || value.trim().isEmpty();
    }

    /**
     * 建立试编译所需的编译期上下文，顺序与 {@code SugarCompiler.compile} 一致。
     *
     * <p>已在试编译中（重入）时直接复用外层那一份，不再叠一层。任何一步失败都回滚已建立的层并
     * 返回 {@code null}——静态上下文泄漏到下一次编译或编辑器渲染是明确的红线。</p>
     *
     * @return 本次调用所要依赖的上下文；{@code null} 表示建立失败（调用方放弃第 2 层）。
     *         {@link CallValidationContext#owned} 为 {@code false} 表示这是一次重入——复用的
     *         是外层已经建好的上下文，调用方<b>不得</b>拿它去调 {@link #leaveCallValidation()}
     */
    private static CallValidationContext enterCallValidation(List<LStatement> statements, Set<String> functionNames){
        // 重入：外层已经建好了上下文，本次共享它，并且不负责拆。这里必须交出一份**不拥有**的
        // 句柄：owned 记在上下文对象上，而外层那一份的 owned 是 true —— 直接把外层对象交出去，
        // 调用方的 `if(context.owned) leaveCallValidation()` 就会替外层收尾，拆掉的正是它要
        // 依赖的上下文（与"静态上下文泄漏"是同一条红线的两侧）。
        if(callValidation != null){
            CallValidationContext outer = callValidation;
            return new CallValidationContext(outer.functions, outer.kinds, outer.arrays, outer.collecting, false);
        }
        Set<String> names = functionNames == null ? Collections.emptySet() : functionNames;

        Set<String> previousFunctions = null;
        Map<String, String> previousKinds = null;
        ArrayRegistry previousArrays = null;
        boolean functionsEntered = false, kindsEntered = false, arraysEntered = false, collecting = false;
        try{
            previousFunctions = ExprIntrinsics.enterUserFunctions(names);
            functionsEntered = true;
            previousKinds = ExprIntrinsics.enterDeclaredKinds(declaredKinds(statements));
            kindsEntered = true;
            previousArrays = ArrayRegistry.enter(
                ArrayRegistry.compileRegistry(Seq.with(statements.toArray(new LStatement[0])), names));
            arraysEntered = true;
            // 置位在 collectAll 之前：它先安装上下文再逐模块 collect，任一模块抛错都必须
            // 由下面这一步配对清理（与 SugarCompiler.compile 的 modulesCollected 同理）
            collecting = true;
            collectAll(statements, names);
        }catch(Throwable ignored){
            // 声明卡自身非法（它们由各自的 markInvalid 标红）：安静回滚，放弃第 2 层
            if(collecting) restore();
            if(arraysEntered) ArrayRegistry.restore(previousArrays);
            if(kindsEntered) ExprIntrinsics.restoreDeclaredKinds(previousKinds);
            if(functionsEntered) ExprIntrinsics.restoreUserFunctions(previousFunctions);
            return null;
        }
        CallValidationContext context =
            new CallValidationContext(previousFunctions, previousKinds, previousArrays, collecting, true);
        callValidation = context;
        return context;
    }

    /** 退出试编译上下文（与 {@link #enterCallValidation} 配对，逆序回滚）。 */
    private static void leaveCallValidation(){
        CallValidationContext context = callValidation;
        if(context == null) return;
        callValidation = null;
        if(context.collecting) restore();
        ArrayRegistry.restore(context.arrays);
        ExprIntrinsics.restoreDeclaredKinds(context.kinds);
        ExprIntrinsics.restoreUserFunctions(context.functions);
    }

    /** 全部模块的注入函数源文本（每项是完整的 funcdef ... blockend）。 */
    public static List<String> builtinSugar(){
        List<String> result = new ArrayList<>();
        for(DataModule module : new ArrayList<>(modules)){
            List<String> texts = module.builtinSugar();
            if(texts == null) continue;
            for(String text : texts){
                if(text != null && !text.trim().isEmpty()) result.add(text.trim());
            }
        }
        return result;
    }

    /** 注入函数名集合（编辑器函数名校验：显式 funccall 到内置函数不算未定义）。 */
    public static Set<String> builtinFunctionNames(){
        if(builtinNamesCache == null){
            builtinNamesCache = Collections.unmodifiableSet(new LinkedHashSet<>(builtinParams().keySet()));
        }
        return builtinNamesCache;
    }

    /** 注入函数名 → 参数名列表（编辑器参数提示用）。 */
    public static List<String> builtinParams(String name){
        if(name == null) return null;
        return builtinParams().get(name);
    }

    private static Map<String, List<String>> builtinParams(){
        if(builtinParamsCache != null) return builtinParamsCache;
        Map<String, List<String>> result = new LinkedHashMap<>();
        for(String text : builtinSugar()){
            try{
                Seq<LStatement> statements = LAssembler.read(text, true);
                for(LStatement statement : statements){
                    if(!(statement instanceof SugarStatements.FuncDefStatement def)) continue;
                    List<String> params = new ArrayList<>();
                    if(def.params != null){
                        for(String part : def.params.split(",")){
                            String param = part.trim();
                            if(!param.isEmpty()) params.add(param);
                        }
                    }
                    result.put(def.name, params);
                }
            }catch(RuntimeException ignored){
                // 解析器未安装/文本损坏时退化为「看不到内置函数」，编译路径仍会自行解析
            }
        }
        builtinParamsCache = result;
        return result;
    }

    /** 清空全部模块与 intrinsic provider（仅供测试）。 */
    public static void clearModules(){
        modules.clear();
        contextStack.clear();
        current = null;
        builtinNamesCache = null;
        builtinParamsCache = null;
        paletteCallsCache = null;
        operationGroupsCache = null;
        paletteStatementsRegistered = false;
        ExprIntrinsics.clearProviders();
    }
}
