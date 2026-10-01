package logicsugar.assist;

import arc.Core;

/**
 * 本地化取词：{@code logicsugar.*} 键存在时用它（带 {@code {0}} 占位替换），否则退回英文
 * fallback。
 *
 * <p>为什么不直接用 {@code Core.bundle.get}：bundle 缺键时 arc 会返回 {@code ???key???}，
 * 而变量界面/快照指令的键是后加的——缺键时必须显示英文原文；顺便兜住无头环境（自测里
 * {@code Core.bundle} 存在但没有任何键）。语义与 {@code ExprIntrinsics.text} 一致，但那是
 * 编译期表达式子系统的工具类，界面与断言运行期代码不该依赖它。</p>
 */
public final class L10n{
    private L10n(){
    }

    public static String text(String key, String fallback, Object... args){
        try{
            if(Core.bundle != null){
                if(Core.bundle.has(key)){
                    return args.length == 0 ? Core.bundle.get(key) : Core.bundle.format(key, args);
                }
                if(args.length > 0) return Core.bundle.formatString(fallback, args);
            }
        }catch(Throwable ignored){
            // 无头环境（Core.bundle 未初始化）：退回原始 fallback
        }
        return fallback;
    }
}
