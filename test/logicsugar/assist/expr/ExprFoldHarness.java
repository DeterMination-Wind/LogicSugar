package logicsugar.assist.expr;

import arc.struct.Seq;
import mindustry.logic.LAssembler;
import mindustry.logic.LStatement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 无头折叠台：把 {@code ExprHook.foldAll} 的判定跑在语句列表上。
 *
 * <p>画布版折叠要增删 {@code StatementElem}，还要检查 jump 目标是否落在链中间——无头环境搭不出
 * 画布。这里只把画布元素换成语句列表；被验证的判定（{@code ExprHook.isChainLine} /
 * {@code collectChain} / {@code foldPlan}，后者含链外读取检查与重新编译比对安全门）全是生产
 * 代码那一份，测试只补一个外层循环。</p>
 *
 * <p>为什么值得单独有这么一台：2026-10 之前「重开时卡片能不能折回」的测试只在文本层直接调
 * {@code ExprCompiler.rebuild}，跳过了链收集与安全门这两个真正会拦住折叠的地方。span 变量下标
 * 与容器 getter 的展开因此长期折不回来而测试照绿（2026-10 报告：「Expr 里 {@code stack.top()}
 * 这样的 getter 重建不出来」）。要断言“保存过的这张卡重开还在”，断言必须包含链收集。</p>
 */
public final class ExprFoldHarness{
    private ExprFoldHarness(){
    }

    /** 声明卡的首 token：它们是元数据，比对正文时跳过（不产指令，重开时仍在画布上）。 */
    private static final Set<String> DECLARATION_TOKENS = new HashSet<>(Arrays.asList(
        "array", "matrix", "span", "record",
        "stack", "queue", "deque", "bitset", "map", "uset", "list", "heap", "chain"
    ));

    /** 解析 mlog/sugar 文本为语句列表（{@code LAssembler.read} 的 {@code Seq} → {@code List}）。 */
    public static List<LStatement> parse(String text){
        Seq<LStatement> statements = LAssembler.read(text, true);
        List<LStatement> result = new ArrayList<>(statements.size);
        for(LStatement statement : statements) result.add(statement);
        return result;
    }

    /**
     * 无头版 {@code ExprHook.foldAll}：逐条语句试折叠，折回的链换成一张 {@link ExprStatement}。
     * 与画布版的差别只有元素增删（这里是列表下标）与画布专有的 jump 目标检查。
     */
    public static List<LStatement> fold(List<LStatement> input){
        List<LStatement> statements = new ArrayList<>(input);
        int i = 0;
        while(i < statements.size()){
            LStatement first = statements.get(i);
            if(first == null || !ExprHook.isChainLine(first)){
                i++;
                continue;
            }
            ExprHook.Chain chain = ExprHook.collectChain(statements, i);
            if(chain.ops.isEmpty()){
                i = chain.end;
                continue;
            }
            // 链首是注册表命中的 read/write 时单行也折叠（与 foldAllInContext 同一门槛）
            boolean arrayEdge = chain.ops.get(0) instanceof ExprCompiler.ReadLine
                || chain.ops.get(0) instanceof ExprCompiler.WriteLine;
            if(chain.length() < 2 && !arrayEdge){
                i++;
                continue;
            }
            ExprHook.Plan plan = ExprHook.foldPlan(statements, chain);
            if(plan == null){
                i = chain.end;
                continue;
            }
            ExprStatement card = new ExprStatement();
            card.dest = plan.dest() == null ? "result" : plan.dest();
            card.expr = plan.expr();
            for(int k = 0; k < chain.length(); k++) statements.remove(i);
            statements.add(i, card);
            i++;
        }
        return statements;
    }

    /** 折叠结果里这次比较关心的语句：表达式卡写成 {@code dest = expr}，声明卡跳过，其余原样。 */
    public static List<String> cards(List<LStatement> folded){
        List<String> parts = new ArrayList<>();
        for(LStatement statement : folded){
            if(statement instanceof ExprStatement card){
                parts.add(card.dest + " = " + card.expr);
                continue;
            }
            StringBuilder text = new StringBuilder();
            statement.write(text);
            String line = text.toString().trim();
            if(DECLARATION_TOKENS.contains(token(line))) continue;
            parts.add(line);
        }
        return parts;
    }

    /** {@link #cards} 的多行文本形态（断言用）。 */
    public static String body(List<LStatement> folded){
        return String.join("\n", cards(folded));
    }

    /** {@code parse} + {@link #fold} + {@link #body} 的快捷方式。 */
    public static String bodyOf(String programText){
        return body(fold(parse(programText)));
    }

    private static String token(String line){
        int space = line.indexOf(' ');
        return space < 0 ? line : line.substring(0, space);
    }
}
