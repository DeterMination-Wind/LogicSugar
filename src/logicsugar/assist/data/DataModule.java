package logicsugar.assist.data;

import logicsugar.assist.expr.ExprIntrinsics;
import mindustry.logic.LStatement;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 一个数据结构模块：声明卡（{@link DataDeclaration} 子类）+ 编译期注册表 + 表达式/语句展开。
 *
 * <p>生命周期（由 {@link DataModules} 统一驱动）：</p>
 * <ol>
 *   <li>启动/集成阶段 {@link DataModules#register(DataModule)}：登记模块，并把
 *       {@link #intrinsics()} 注册进 {@link ExprIntrinsics}（表达式展开与编辑器函数名
 *       校验从此可见，无需等待某次编译）；</li>
 *   <li>集成阶段 {@link DataModules#registerParsers()} → {@link #registerParsers()}：
 *       注册 {@code LAssembler.customParsers} 与 {@code LogicIO.allStatements}（幂等）；</li>
 *   <li>每次编译 {@link DataModules#collectAll(List, Set)} → {@link #collect(List, Set)}：
 *       遍历程序建立本模块的编译期注册表（典型做法：静态字段持有注册表，在 collect 里
 *       整表重建，并可用 {@link logicsugar.assist.expr.ArrayRegistry#active()} 读取数组元数据）；</li>
 *   <li>lower 阶段：{@link #intrinsics()} 的 provider 通过 {@link ExprIntrinsics} 展开表达式；</li>
 *   <li>编译结束 {@link DataModules#restore()} → {@link #restore()}：清空第 3 步建立的
 *       编译期状态（与 collect 配对，避免状态泄漏到下一次编译）。</li>
 * </ol>
 *
 * <p>隐藏状态变量（{@code __ls_*}）、注入函数名（{@code __ls_builtin_*}）与声明名冲突
 * 校验都由模块自行负责；{@link #markInvalid(List, boolean[], Set)} 只做编辑期标红，
 * 编译期的严格校验仍由 {@link #collect} 抛错拦截。</p>
 */
public abstract class DataModule{

    /** 模块唯一 id（{@link DataModules#register} 据此去重）。 */
    public abstract String id();

    /** 注册本模块的声明卡解析器（{@code LogicIO.allStatements.add + LAssembler.customParsers.put}）。 */
    public abstract void registerParsers();

    /** 编译期收集：遍历程序建立本模块的注册表；发现问题直接抛 IllegalArgumentException。 */
    public abstract void collect(List<LStatement> statements, Set<String> functionNames);

    /** 编辑期字段级校验：把有问题的声明卡标红（{@code invalid[i] = true}），不抛错。 */
    public abstract void markInvalid(List<LStatement> statements, boolean[] invalid, Set<String> functionNames);

    /** 表达式扩展 provider（无表达式能力时返回 null）。 */
    public ExprIntrinsics.Provider intrinsics(){
        return null;
    }

    /** 注入的内置函数源文本（funcdef ... blockend，函数名统一 {@code __ls_builtin_*} 前缀）。 */
    public List<String> builtinSugar(){
        return Collections.emptyList();
    }

    /** 编译结束后的清理钩子（与 {@link #collect} 配对）；默认无状态。 */
    public void restore(){
    }
}
