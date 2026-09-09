package logicsugar.assist.expr;

import logicsugar.assist.expr.ExprCompiler.Line;
import logicsugar.assist.expr.ExprCompiler.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 表达式内建（intrinsic）展开点：表达式函数名 → 原版指令链的编译期扩展注册表。
 *
 * <p>数据子系统模块（数组批量运算、record、stack、queue…）把「表达式里的函数调用」展开为
 * 原版 {@code op}/{@code read}/{@code write}/{@code funccall} 链。展开发生在
 * {@link ExprCompiler} 的 AST → 指令链阶段（{@code compileNode(Call)} / {@code compileNode(Member)} /
 * 成员赋值路径），因此编辑器展开与 lower 阶段共用同一套语义。</p>
 *
 * <p><b>用户函数优先</b>：{@link #enterUserFunctions(Set)} 安装当前编译的用户函数名集合
 * （本地 funcdef + 库函数），被同名用户函数遮蔽的 intrinsic 名字不再展开（走普通
 * {@code funccall} 路径）。名字匹配大小写不敏感（与 ExprCompiler 的数学内置一致），
 * 但用户函数集合按不区分大小写判遮蔽。</p>
 *
 * <p><b>Provider 实现必须放在本包</b>：{@code Node}/{@code Line} 是
 * {@link ExprCompiler} 的包私有类型，其它包的实现类无法引用这些签名。</p>
 *
 * <p><b>展开结果约定</b>：{@code expandCall}/{@code readMember} 返回的行链，最后一行必须是
 * 写操作（{@code op}/{@code sensor}/{@code read}/{@code funccall}），其 dest 即表达式结果
 * 操作数——调用方（ExprCompiler）据此把结果接到外层表达式链上。{@code writeMember}
 * 返回完整语句链，无结果约定。返回 {@code null} 表示本 provider 不处理该调用
 * （调用方继续走原逻辑）；需要报编译错误时用 {@link Ctx#error(String)} 抛
 * {@link ExprCompiler.ParseException}。</p>
 */
public final class ExprIntrinsics{
    private ExprIntrinsics(){}

    /** 展开上下文：由 ExprCompiler 在编译现场提供。 */
    public interface Ctx{
        /** 追加指令行（顺序即执行顺序）。 */
        List<Line> ops();
        /** 编译子表达式，返回操作数名。 */
        String compile(Node node);
        /** 分配/复用临时变量。 */
        String temp(String... operands);
        /** 名字是否是某个已声明结构（数组/矩阵等）。 */
        boolean isDeclaredName(String name);
        /** 构造统一格式的编译错误（ExprCompiler.ParseException）。 */
        RuntimeException error(String message);
    }

    /** 一个数据结构的表达式扩展提供者。 */
    public interface Provider{
        /** 表达式函数名（小写规范名）。 */
        String[] callNames();
        /** 展开函数调用；null = 不处理（继续走用户函数/未知函数逻辑）。 */
        List<Line> expandCall(String name, List<Node> args, Ctx ctx);
        /** 记录变量等成员基底判定。 */
        boolean isMemberBase(Node base);
        /** 成员读；null = 不处理（退回 sensor 属性路径）。 */
        List<Line> readMember(Node base, String prop, Ctx ctx);
        /** 成员写；null = 不处理（退回普通赋值目标语义）。 */
        List<Line> writeMember(Node base, String prop, Node value, Ctx ctx);
        /** 该名字接受的实参个数；&lt;0 表示任意（默认）。按 arity 分派用。 */
        default int arity(String name){
            return -1;
        }
        /** 展开后会调用的注入函数名（analyze 阶段登记可达性/hoist 用；默认无）。 */
        default List<String> callees(String name, int argc){
            return Collections.emptyList();
        }
    }

    private static final List<Provider> providers = new ArrayList<>();
    private static Set<String> userFunctions = Collections.emptySet();

    /** 注册一个 provider（按实现类幂等，重复注册被忽略）。 */
    public static void register(Provider provider){
        if(provider == null) return;
        for(Provider existing : providers){
            if(existing == provider || existing.getClass() == provider.getClass()) return;
        }
        providers.add(provider);
    }

    /** 是否存在注册了该名字（不区分大小写、不考虑遮蔽）的 intrinsic。 */
    public static boolean isIntrinsic(String name){
        return name != null && providerFor(name) != null;
    }

    /** 该名字 + 实参个数在当前上下文中是否是可展开的 intrinsic（考虑用户函数遮蔽与 arity）。 */
    public static boolean isIntrinsicName(String name, int argc){
        return canonicalName(name, argc) != null;
    }

    /** intrinsic 的规范名（provider 注册时的大小写）；不可展开时返回 null。 */
    public static String canonicalName(String name, int argc){
        if(name == null || isUserFunction(name)) return null;
        for(Provider provider : providers){
            String[] names = provider.callNames();
            if(names == null) continue;
            for(String candidate : names){
                if(candidate == null || !candidate.equalsIgnoreCase(name)) continue;
                int arity = provider.arity(candidate);
                if(arity < 0 || arity == argc) return candidate;
            }
        }
        return null;
    }

    /** 展开函数调用；null = 不是 intrinsic / 无 provider 处理。 */
    public static List<Line> tryExpandCall(String name, List<Node> args, Ctx ctx){
        int argc = args == null ? 0 : args.size();
        String canonical = canonicalName(name, argc);
        if(canonical == null) return null;
        for(Provider provider : providers){
            if(!hasCallName(provider, canonical)) continue;
            List<Line> lines = provider.expandCall(canonical, args, ctx);
            if(lines != null && !lines.isEmpty()) return lines;
        }
        return null;
    }

    /** 基底是否是某个 provider 的成员结构（record 变量等）。 */
    public static boolean isMemberBase(Node base){
        if(base == null) return false;
        for(Provider provider : providers){
            if(provider.isMemberBase(base)) return true;
        }
        return false;
    }

    /** 成员读展开；null = 不处理（退回 sensor 属性路径）。 */
    public static List<Line> tryReadMember(Node base, String prop, Ctx ctx){
        if(base == null || prop == null) return null;
        for(Provider provider : providers){
            if(!provider.isMemberBase(base)) continue;
            List<Line> lines = provider.readMember(base, prop, ctx);
            if(lines != null && !lines.isEmpty()) return lines;
        }
        return null;
    }

    /** 成员写展开；null = 不处理（退回普通赋值目标语义）。 */
    public static List<Line> tryWriteMember(Node base, String prop, Node value, Ctx ctx){
        if(base == null || prop == null) return null;
        for(Provider provider : providers){
            if(!provider.isMemberBase(base)) continue;
            List<Line> lines = provider.writeMember(base, prop, value, ctx);
            if(lines != null && !lines.isEmpty()) return lines;
        }
        return null;
    }

    /** 一次 intrinsic 展开会调用的注入函数名（可达性登记用）。 */
    public static List<String> calleesOf(String name, int argc){
        String canonical = canonicalName(name, argc);
        if(canonical == null) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for(Provider provider : providers){
            if(!hasCallName(provider, canonical)) continue;
            List<String> callees = provider.callees(canonical, argc);
            if(callees == null) continue;
            for(String callee : callees){
                if(callee != null && !callee.isEmpty() && !result.contains(callee)) result.add(callee);
            }
        }
        return result;
    }

    /** 全部已注册的 intrinsic 名字（编辑器函数名校验用）。 */
    public static Set<String> intrinsicNames(){
        Set<String> result = new LinkedHashSet<>();
        for(Provider provider : providers){
            String[] names = provider.callNames();
            if(names == null) continue;
            for(String name : names){
                if(name != null && !name.isEmpty()) result.add(name);
            }
        }
        return result;
    }

    /** 安装当前编译的用户函数名集合（遮蔽 intrinsic），返回先前的集合供恢复。 */
    public static Set<String> enterUserFunctions(Set<String> names){
        Set<String> previous = userFunctions;
        userFunctions = names == null ? Collections.emptySet() : names;
        return previous;
    }

    /** 恢复 {@link #enterUserFunctions} 返回的先前集合。 */
    public static void restoreUserFunctions(Set<String> previous){
        userFunctions = previous == null ? Collections.emptySet() : previous;
    }

    /** 清空全部 provider 与遮蔽上下文（仅供测试）。 */
    public static void clearProviders(){
        providers.clear();
        userFunctions = Collections.emptySet();
    }

    /**
     * 本地化文本：bundle 有键时用翻译（{@code {0}} 占位），否则用英文 fallback
     * （bundle 键由集成阶段统一补齐）。provider 抛错时用
     * {@code ctx.error(text("la.err.intrinsic_*", "English {0}", arg))}，与 ExprCompiler
     * 的 {@code la.err.*} 消息风格一致。
     */
    public static String text(String key, String fallback, Object... args){
        try{
            if(arc.Core.bundle != null){
                if(arc.Core.bundle.has(key)){
                    return args.length == 0 ? arc.Core.bundle.get(key) : arc.Core.bundle.format(key, args);
                }
                if(args.length > 0) return arc.Core.bundle.formatString(fallback, args);
            }
        }catch(Throwable ignored){
            // 无头环境（Core.bundle 未初始化）：退回原始 fallback
        }
        return fallback;
    }

    private static boolean isUserFunction(String name){
        if(userFunctions.isEmpty()) return false;
        if(userFunctions.contains(name)) return true;
        for(String user : userFunctions){
            if(user != null && user.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static Provider providerFor(String name){
        for(Provider provider : providers){
            if(hasCallName(provider, name)) return provider;
        }
        return null;
    }

    private static boolean hasCallName(Provider provider, String name){
        String[] names = provider.callNames();
        if(names == null) return false;
        for(String candidate : names){
            if(candidate != null && candidate.equalsIgnoreCase(name)) return true;
        }
        return false;
    }
}
