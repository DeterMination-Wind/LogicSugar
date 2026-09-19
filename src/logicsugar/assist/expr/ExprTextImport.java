package logicsugar.assist.expr;

import arc.scene.Element;
import arc.struct.Seq;
import mindustry.gen.LogicIO;
import mindustry.logic.LAssembler;
import mindustry.logic.LCanvas;
import mindustry.logic.LCanvas.StatementElem;
import mindustry.logic.LStatement;
import mindustry.logic.LStatements.SetStatement;
import mindustry.logic.SugarCanvas;
import mindustry.logic.SugarStatements;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 文本导入层的「表达式语句」识别与落地。
 *
 * <p>背景：原版 {@code LParser} 只按 {@code tokens[0]} 查表（{@code LogicIO.read} +
 * {@code LAssembler.customParsers}），而 LogicSugar 的解析器全部挂在固定 token 上
 * （{@code array} / {@code forbegin} / {@code record} / …）。于是用户在处理器文本里写的
 * 表达式语句 {@code x = buf[3]}、{@code result = (a + b) * 2}、{@code buf[i] = 5}
 * 没有任何解析器认领，被静默替换成 {@code InvalidStatement}（注册名 {@code noop}，
 * 构建为 {@code NoopI}）——程序照跑但语义全丢。表达式语句其实只以卡片形式存在
 * （{@link ExprStatement}，由 {@code ExprHook.init()} 注入积木列表），文本没有任何形态，
 * 而 README 与数组教程又把 {@code x = buf[3]} 写成源码示例。</p>
 *
 * <p>本类补上文本形态：{@link #plan(String)} 扫描待加载文本，把形如
 * {@code <标识符>[下标/成员] = <表达式>} 的行换成唯一哨兵 {@code set __ls_import_N 0}
 * （一对一替换，语句条数不变，因此 jump 下标 / 标签解析完全不受影响）；
 * {@link #applyToCanvas} 或 {@link #applyToStatements} 再把哨兵换回
 * {@link ExprStatement} 卡片。产物与用户从 Operations 分类拖一张 Expr 卡完全一致：
 * 保存时由 {@code ExprHook.unfoldAll} 展开成 {@code read/write/op} 原版指令，
 * 重开时由 {@code ExprHook.foldAll} 折回卡片，因此多人兼容性与 reconstruction
 * 覆盖都沿用既有路径。</p>
 *
 * <p>保守边界（任何不确定都保持今天的行为）：</p>
 * <ul>
 *   <li>首 token 已被 {@code LogicIO.read} 或 {@code LAssembler.customParsers} 认领的行
 *       一律不动（例如 {@code set = 5}、{@code array = 5}）。</li>
 *   <li>{@code ==} / {@code !=} / {@code <=} / {@code >=} 不是赋值（{@code x == 5} 不转换）。</li>
 *   <li>顶层 {@code #} 注释之后的文本不参与判定；含顶层 {@code ;}（一行多语句）、
 *       未闭合字符串、跨行字符串的行整行跳过。</li>
 *   <li>文本里已经出现保留前缀 {@link #sentinelPrefix} 时整个导入放弃（防哨兵名撞车）。</li>
 * </ul>
 *
 * <p>表达式本身非法（例如 {@code x = (a +}）时不再静默：卡片照常落地并标红，
 * 保存被 {@link ExprStatement#write} 的报错拦住。这与手拖 Expr 卡的行为一致。</p>
 */
public final class ExprTextImport{

    /** 哨兵变量前缀。{@code __ls_} 是 LogicSugar 保留前缀，用户代码不得使用。 */
    public static final String sentinelPrefix = "__ls_import_";

    /** 哨兵写死的值；与名字一起用于识别「这是本类生成的语句」。 */
    private static final String sentinelValue = "0";

    /** 赋值目标：标识符，可跟任意个 {@code .member} 或 {@code [expr]}（下标内允许空格/运算符）。 */
    private static final Pattern assignTarget = Pattern.compile(
        "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_@][A-Za-z0-9_]*|\\[[^\\[\\]]*\\])*");

    private ExprTextImport(){}

    /** 一次文本导入的解析结果：替换后的文本（或原文本）＋ 哨兵到 (dest, expr) 的映射。 */
    public static final class Plan{
        private final String text;
        private final Map<String, Assignment> assignments;

        private Plan(String text, Map<String, Assignment> assignments){
            this.text = text;
            this.assignments = assignments;
        }

        /** 交给 {@code LCanvas.load} 的文本；没有任何匹配时与输入逐字节相同。 */
        public String text(){
            return text;
        }

        /** 没有识别到任何表达式语句时为 true，此时 {@link #text()} 就是原文本。 */
        public boolean isEmpty(){
            return assignments.isEmpty();
        }

        /** 哨兵 {@code set} 语句 → 新的 {@link ExprStatement}；不是本计划的哨兵时返回 null。 */
        public ExprStatement statementFor(LStatement statement){
            if(assignments.isEmpty() || !(statement instanceof SetStatement set)) return null;
            if(!sentinelValue.equals(set.from)) return null;
            Assignment assignment = assignments.get(set.to);
            if(assignment == null) return null;

            ExprStatement expr = new ExprStatement();
            expr.dest = assignment.dest();
            expr.expr = assignment.expr();
            return expr;
        }
    }

    /** 一条识别出来的赋值：目标（可能是 {@code buf[i]} / {@code p.hp}）与表达式文本。 */
    private record Assignment(String dest, String expr){}

    /**
     * 扫描文本，把所有「原版 mlog 解析不了、但形状是表达式赋值」的行换成哨兵 set 语句。
     * 纯文本运算，不接触 UI / 注册表，可无头测试。
     *
     * <p>同时识别 {@link ExprStatement#cardMarkerPrefix} 自描述标记：标记跟在单行表达式卡的
     * 展开行之后，说明上一行是卡片而不是普通 {@code set}/{@code op} 积木（保存文本里两者
     * 逐字相同），因此那一行按标记里记录的 dest/expr 还原成卡片。标记行本身留作文本里的
     * 注释，一对一替换，语句条数依旧不变。</p>
     */
    public static Plan plan(String asm){
        boolean marked = asm != null && asm.contains(ExprStatement.cardMarkerPrefix);
        if(asm == null || asm.isEmpty() || (!marked && asm.indexOf('=') < 0) || asm.contains(sentinelPrefix)){
            return new Plan(asm == null ? "" : asm, Map.of());
        }

        String text = asm.replace("\r\n", "\n");
        String[] lines = text.split("\n", -1);
        Map<String, Assignment> found = new LinkedHashMap<>();
        // 最近一条「代码行」的下标：标记写在卡片展开行的下一行，据此认领属于它的那一行
        int lastCodeLine = -1;
        for(int i = 0; i < lines.length; i++){
            Assignment marker = parseCardMarker(lines[i]);
            if(marker != null){
                // 认领紧邻上面的那一行；标签行（`foo:`）绝不认领——标记只跟在卡片展开行之后，
                // 文本被手工改动过时宁可丢掉标记，也不能把一条标签换成卡片。
                if(lastCodeLine >= 0 && !lines[lastCodeLine].trim().endsWith(":")){
                    String sentinel = sentinelPrefix + (found.size() + 1);
                    lines[lastCodeLine] = "set " + sentinel + " " + sentinelValue;
                    found.put(sentinel, marker);
                }
                lastCodeLine = -1;
                continue;
            }
            String trimmed = lines[i].trim();
            if(trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            Assignment assignment = parseAssignment(lines[i]);
            if(assignment == null){
                lastCodeLine = i;
                continue;
            }
            String sentinel = sentinelPrefix + (found.size() + 1);
            lines[i] = "set " + sentinel + " " + sentinelValue;
            found.put(sentinel, assignment);
            lastCodeLine = -1;
        }

        // 没有匹配时返回原文本（不做 \r\n 归一化），保证既有路径零差异。
        if(found.isEmpty()) return new Plan(asm, Map.of());
        return new Plan(String.join("\n", lines), found);
    }

    /**
     * {@link ExprStatement#cardMarkerPrefix} 标记行 → (dest, expr)；不是标记时返回 null。
     * 格式：{@code # @ls-expr-card <dest> "<转义后的表达式>"}（dest 为空时直接以引号开头）。
     */
    private static Assignment parseCardMarker(String line){
        String code = line.trim();
        if(!code.startsWith(ExprStatement.cardMarkerPrefix)) return null;
        String rest = code.substring(ExprStatement.cardMarkerPrefix.length()).trim();
        if(rest.isEmpty()) return null;

        String dest = "";
        String quoted = rest;
        if(!rest.startsWith("\"")){
            int space = rest.indexOf(' ');
            if(space < 0) return null; // 只有 dest 没有表达式：标记损坏，忽略
            dest = rest.substring(0, space).trim();
            quoted = rest.substring(space + 1).trim();
        }
        if(quoted.length() >= 2 && quoted.charAt(0) == '"' && quoted.charAt(quoted.length() - 1) == '"'){
            quoted = quoted.substring(1, quoted.length() - 1);
        }
        return new Assignment(dest, SugarStatements.unescapeQuoted(quoted));
    }

    /** 画布版本：把哨兵 set 语句原位换成 {@link ExprStatement} 卡（与 ExprHook 折叠同一套增删方式）。 */
    public static int applyToCanvas(LCanvas canvas, Plan plan){
        if(canvas == null || plan == null || plan.isEmpty() || canvas.statements == null) return 0;

        Seq<Element> children = canvas.statements.getChildren();
        int applied = 0;
        for(int i = 0; i < children.size; ){
            Element child = children.get(i);
            if(child instanceof StatementElem elem){
                ExprStatement expr = plan.statementFor(elem.st);
                if(expr != null){
                    elem.remove();
                    canvas.addAt(i, expr);
                    expr.setupUI();
                    applied++;
                    continue;
                }
            }
            i++;
        }

        if(applied > 0){
            // 语句条数不变，但积木高度与跳转线要重算（ExprHook 折叠后做的是同一件事）。
            canvas.statements.updateJumpHeights = true;
            SugarCanvas.markJumpHeightsDirty(canvas);
        }
        return applied;
    }

    /** 无头/纯语句列表版本：就地替换哨兵，规则与 {@link #applyToCanvas} 完全一致。 */
    public static int applyToStatements(Seq<LStatement> statements, Plan plan){
        if(statements == null || plan == null || plan.isEmpty()) return 0;

        int applied = 0;
        for(int i = 0; i < statements.size; i++){
            ExprStatement expr = plan.statementFor(statements.get(i));
            if(expr != null){
                statements.set(i, expr);
                applied++;
            }
        }
        return applied;
    }

    /** 单行判定：返回 (dest, expr)，不是表达式语句时返回 null。 */
    private static Assignment parseAssignment(String line){
        String code = stripCommentAndStatements(line);
        if(code == null) return null;
        code = code.trim();
        if(code.isEmpty()) return null;

        // 原版/其它 mod 认领的首 token 行（set / op / array / …）一律不动。
        if(claimed(firstToken(code))) return null;

        int eq = code.indexOf('=');
        if(eq <= 0) return null;
        if(isOperatorChar(code.charAt(eq - 1))) return null;
        if(eq + 1 < code.length() && code.charAt(eq + 1) == '=') return null;

        String dest = code.substring(0, eq).trim();
        String expr = code.substring(eq + 1).trim();
        if(!assignTarget.matcher(dest).matches()) return null;
        return new Assignment(dest, expr);
    }

    /**
     * 去掉行尾 {@code #} 注释；字符串未闭合、或顶层出现非尾部 {@code ;}（一行两语句）时返回 null。
     * 与 {@code LParser} 的 token 规则保持一致：字符串内的 {@code #}/{@code ;} 是字面量。
     */
    private static String stripCommentAndStatements(String line){
        boolean inString = false;
        for(int i = 0; i < line.length(); i++){
            char c = line.charAt(i);
            if(inString){
                if(c == '\\'){
                    i++;
                }else if(c == '"'){
                    inString = false;
                }
            }else if(c == '"'){
                inString = true;
            }else if(c == '#'){
                return line.substring(0, i);
            }else if(c == ';'){
                String rest = line.substring(i + 1).trim();
                // 尾部 `;`（后面只有空白/注释）仍是一条语句；否则保守跳过整行。
                if(rest.isEmpty() || rest.startsWith("#")) return line.substring(0, i);
                return null;
            }
        }
        // 行内字符串未闭合：LParser 会直接报错，保持不动。
        return inString ? null : line;
    }

    /** {@code LParser.token()} 的首 token：空白（代码里已无 {@code ;}/{@code #}）之前的全部内容。 */
    private static String firstToken(String code){
        int end = 0;
        while(end < code.length()){
            char c = code.charAt(end);
            if(c == ' ' || c == '\t') break;
            end++;
        }
        return code.substring(0, end);
    }

    /**
     * 首 token（以及 {@code x=5} 这种无空格形式的 {@code =} 前段）是否已被原版语句或
     * 某个 mod 的 custom parser 认领。认领即不动：那些行今天不是 noop，不能改语义。
     */
    private static boolean claimed(String token){
        if(token.isEmpty()) return true;
        int eq = token.indexOf('=');
        String head = eq > 0 ? token.substring(0, eq) : token;
        return isClaimedToken(token) || isClaimedToken(head);
    }

    private static boolean isClaimedToken(String token){
        if(token.isEmpty()) return false;
        if(LAssembler.customParsers != null && LAssembler.customParsers.containsKey(token)) return true;
        try{
            // 已注册的语句哪怕参数不足也会返回语句或抛异常，两种都算「被认领」。
            return LogicIO.read(new String[]{token}, 1) != null;
        }catch(Throwable ignored){
            return true;
        }
    }

    private static boolean isOperatorChar(char c){
        return c == '=' || c == '!' || c == '<' || c == '>';
    }
}
