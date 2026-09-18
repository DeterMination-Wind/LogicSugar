package logicsugar.assist.data;

import arc.struct.Seq;
import logicsugar.assist.expr.ExprIntrinsics;
import mindustry.logic.LAssembler;
import mindustry.logic.LStatement;
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

    /** 允许 null 的上下文栈（ArrayDeque 拒绝 null 元素）。 */
    private static final List<List<DataModule>> contextStack = new ArrayList<>();

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
    }

    /** 注册全部模块的声明卡解析器（集成阶段调用；模块实现须幂等）。 */
    public static void registerParsers(){
        for(DataModule module : new ArrayList<>(modules)){
            module.registerParsers();
        }
        if(!paletteStatementsRegistered){
            paletteStatementsRegistered = true;
            LAssembler.customParsers.put(DataCallStatement.TOKEN, DataCallStatement::parse);
            for(DataModule.PaletteCall call : paletteCalls().values()){
                boolean exists = false;
                for(arc.func.Prov<LStatement> provider : mindustry.gen.LogicIO.allStatements){
                    LStatement statement = provider.get();
                    if(statement instanceof DataCallStatement data
                        && call.name.equalsIgnoreCase(data.operation)){
                        exists = true;
                        break;
                    }
                }
                if(!exists) mindustry.gen.LogicIO.allStatements.add(() -> new DataCallStatement(call));
            }
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
        paletteStatementsRegistered = false;
        ExprIntrinsics.clearProviders();
    }
}
