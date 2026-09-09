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
    /** 允许 null 的上下文栈（ArrayDeque 拒绝 null 元素）。 */
    private static final List<List<DataModule>> contextStack = new ArrayList<>();
    private static List<DataModule> current;
    private static Set<String> builtinNamesCache;
    private static Map<String, List<String>> builtinParamsCache;

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
    }

    /** 注册全部模块的声明卡解析器（集成阶段调用；模块实现须幂等）。 */
    public static void registerParsers(){
        for(DataModule module : new ArrayList<>(modules)){
            module.registerParsers();
        }
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
        ExprIntrinsics.clearProviders();
    }
}
