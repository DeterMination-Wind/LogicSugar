package logicsugar.assist;

import arc.struct.Seq;
import mindustry.logic.LAssembler;
import mindustry.logic.LStatement;
import mindustry.logic.SugarCompiler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@code @counter} 写入索引：回答"程序里每一条改写 {@code @counter} 的指令，执行下一步落在哪"。
 *
 * <p><b>纯逻辑。</b>本类不碰 arc/mindustry 的 UI、场景与画布类型，只依赖
 * {@link LAssembler}/{@link LStatement} 的公开静态 API，因此可以在无头测试里直接跑；它
 * 也不读写任何全局状态（编译器的 carrier 判断是 private，故此处自实现一份）。</p>
 *
 * <p><b>归一化。</b>输入可以是编辑器里的编译产物：先去掉 marker 注释块与持久化载体行
 * （{@code set __ls_sugar "..."} / {@code set __ls_lib "..."} 及其分片
 * {@code set __ls_sugar_<n> "..."}），再经
 * {@code LAssembler.write(LAssembler.read(text, true))} 走一遍，标签就折成了数字跳转目标，
 * 一行一条指令。载体行是元数据、不是指令，因此不计入 {@link #instructionCount()}。解析失败
 * （未定义标签等）不抛异常：退化成"按行计数、丢掉标签行与载体行"，宁可持续降级也不让编辑器崩。</p>
 *
 * <p><b>归属（owner）。</b>{@code SugarFunctions} 降低主程序时，结构标签的形状是
 * {@code __ls_stmt_<N>:}（{@code <N>} 是 lowering 的可见主程序下标：{@code analyze} 会把 funcdef
 * 及其函数体从主程序剥掉，所以带函数的程序里 N 不等于画布语句下标；构造函数可选的
 * {@code mainToCanvas} 映射负责换算）；函数体用别的前缀
 * （{@code __ls_func_<name>_...}、内联副本 {@code __ls_i_<id>_...}），因此标签能反推语句归属。
 * 归一化会把标签行整行删掉（{@code LAssembler.write} 只写数字目标），所以标签扫描在本类自己的
 * 语句切分结果上做（与 {@code LParser} 一致：一行可按 {@code ;} 拆成多条语句，引号内不拆，
 * {@code #} 之后是注释，只有一个 token 且以 {@code :} 结尾的段是标签），再把位置映射回归一化后的
 * 指令下标；只有当"切分出的语句数 == instructionCount()"时才采信，否则一律 -1。
 * 归属原则是"宁可为 -1，绝不猜"：只有 {@code __ls_stmt_<N>:} 能确定语句，其余前缀的标签之后
 * 视为未知区域（-1），直到下一个 {@code __ls_stmt_} 标签。</p>
 *
 * <p><b>provenance 契约（调用方必读）。</b>构造函数第二参数接收的数组，下标必须是
 * <em>归一化之后</em>的指令下标（即与 {@link #instructionCount()} 同一套编号，载体行、标签行
 * 都已排除），元素是拥有该指令的语句下标，未知填 {@code -1}。原因：归一化会删掉标签行，
 * 逐行编号并不稳定，本类无法把"归一化前的行号"安全地换算过来，所以不做任何自动重编号。
 * 长度与 {@link #instructionCount()} 不一致时整个数组被忽略（等于传 {@code null}），退回标签
 * 回填——绝不静默错位。provenance 填过的槽位以其为准，负值槽位（{@code -1}，以及
 * {@code SugarCompiler.CompileProvenance} 里表示"编译器自己发射"的
 * {@code SugarFunctions.syntheticOrigin == -2}）都算未知，仍用标签回填，
 * 因此 {@link #ownerOf} 永远不会漏出 -2 这类哨兵值。
 * 传 {@code null} 即完全关闭 provenance 归属。</p>
 *
 * <p><b>写入识别。</b>目的操作数的位置与 {@code MlogCFG.writes(String[])} 保持一致
 * （{@code set}/{@code sensor}/{@code read} 取 token 1，{@code op} 取 token 2，
 * {@code write}/{@code control} 取 token 2），并补上其它"单个目的操作数"的语句种类；
 * 目的 token 正好是 {@code @counter} 时才算一次写入。语义依据 {@code LExecutor.runOnce()}
 * 的 {@code instructions[(int)(counter.numval++)].run(this)}：先读后自增，所以
 * {@code set @counter N} 续在指令 N，而跳转指令编译出来的目标才是 {@code 目标下标 - 1}；
 * 相对写 {@code op add @counter @counter k} 的续执行位置因此是
 * {@code 本指令下标 + 1 + k}（{@code sub} 为 {@code + 1 - k}）。</p>
 */
public final class CounterJumpIndex{
    /** 一条找到的 {@code @counter} 写入。 */
    public static final class Write{
        /** 拥有这条指令的语句下标；无法归属到主程序语句时为 -1。 */
        public final int statement;
        /** 执行写入的指令下标。 */
        public final int instruction;
        /** 指令原文（已 trim，无标签）。 */
        public final String line;
        /** 执行可能续在的绝对指令下标；空数组表示静态不可判定。 */
        public final int[] targets;
        /** 计算出的目标落在 {@code [0, instructionCount())} 之外（因而回绕到 0）时为 true。 */
        public final boolean wrap;
        /** 短小的机器稳定原因：{@code set-literal}/{@code relative}/{@code dynamic}/
         *  {@code entry-skip}/{@code function-return}/{@code switch-dispatch}。 */
        public final String note;

        Write(int statement, int instruction, String line, int[] targets, boolean wrap, String note){
            this.statement = statement;
            this.instruction = instruction;
            this.line = line;
            this.targets = targets;
            this.wrap = wrap;
            this.note = note;
        }

        @Override
        public String toString(){
            return line + " [" + note + (wrap ? ", wrap" : "") + "] -> " + Arrays.toString(targets);
        }
    }

    private static final int[] noTargets = new int[0];

    private final int count;
    private final String[] lines;
    private final int[] owner;
    /** 可选：可见主程序下标 -> 画布语句下标；null 表示标签编号原样使用。 */
    private final int[] mainToCanvas;
    private final List<Write> writes;

    /** 只有标签回填的索引；provenance 传 null。 */
    public CounterJumpIndex(String code){
        this(code, null);
    }

    /**
     * @param code 编译产物（允许带 marker/carrier）；null 或空文本得到空索引
     * @param provenance 归一化下标上的语句归属，未知填 -1；长度不匹配即忽略（见类注释）
     */
    public CounterJumpIndex(String code, int[] provenance){
        this(code, provenance, null);
    }

    /**
     * @param code 编译产物（允许带 marker/carrier）；null 或空文本得到空索引
     * @param provenance 归一化下标上的语句归属，未知填 -1；长度不匹配即忽略（见类注释）
     * @param mainToCanvas 可选映射：__ls_stmt_&lt;N&gt; 的 N 是 lowering 的可见主程序下标，
     *                     用它换算成画布语句下标（带 funcdef 的程序两者不同）；null = 不换算
     */
    public CounterJumpIndex(String code, int[] provenance, int[] mainToCanvas){
        String[] instructionLines = new String[0];
        int[] owners = new int[0];
        List<Write> found = new ArrayList<>();
        try{
            if(code != null && !code.isEmpty()){
                String stripped = stripMetadata(code);
                List<String> splitLines = null;
                try{
                    Seq<LStatement> parsed = LAssembler.read(stripped, true);
                    splitLines = instructionLines(LAssembler.write(parsed));
                }catch(Throwable ignored){
                    // 解析失败：退化为按行计数（标签行与载体行仍被排除），绝不把异常抛给编辑器。
                    splitLines = null;
                }
                List<Statement> statements = scanStatements(stripped);
                if(splitLines == null) splitLines = statementLines(statements);

                instructionLines = splitLines.toArray(new String[0]);
                owners = owners(statements, instructionLines.length, provenance, mainToCanvas);
                for(int i = 0; i < instructionLines.length; i++){
                    String[] tokens = tokens(instructionLines[i]);
                    int dest = destinationIndex(kind(tokens));
                    if(dest < 0 || dest >= tokens.length || !tokens[dest].equals("@counter")) continue;
                    found.add(classify(i, instructionLines, owners));
                }
            }
        }catch(Throwable ignored){
            // 任何意外都退化成空索引：编辑器/画布宁可什么都不显示，也不能因为一段坏文本崩掉。
            instructionLines = new String[0];
            owners = new int[0];
            found.clear();
        }
        this.lines = instructionLines;
        this.count = instructionLines.length;
        this.owner = owners;
        this.mainToCanvas = mainToCanvas;
        this.writes = Collections.unmodifiableList(found);
    }

    /** 归一化后的指令条数（标签行、载体行都不算）。 */
    public int instructionCount(){
        return count;
    }

    /** 拥有该指令的语句下标；越界或未知为 -1。 */
    public int ownerOf(int instruction){
        if(instruction < 0 || instruction >= owner.length) return -1;
        return owner[instruction];
    }

    /** 按指令顺序列出的全部写入；永不为 null。 */
    public List<Write> writes(){
        return writes;
    }

    /** 该指令上的写入，没有则 null。 */
    public Write writeAt(int instruction){
        for(Write write : writes){
            if(write.instruction == instruction) return write;
        }
        return null;
    }

    /** tooltip 用的一行短文本；目标就是一条已知语句（界面会直接指向那张卡）时返回 null。 */
    public String describe(Write write){
        if(write == null) return null;
        switch(write.note){
            case "entry-skip":
                return "entry skip (wraps to 0)";
            case "function-return":
                return "function return trampoline";
            case "switch-dispatch":
                return "switch jump table";
            case "dynamic":
                return "target depends on a runtime value";
            case "relative":
                String[] tokens = tokens(write.line);
                String op = tokens.length > 1 ? tokens[1] : "add";
                String step = tokens.length > 4 ? tokens[4] : "1";
                String to = write.targets.length == 0 ? "?" : Integer.toString(write.targets[0]);
                return "@counter " + (op.equals("sub") ? "-=" : "+=") + " " + step + " \u2192 " + to;
            default:
                if(write.targets.length == 1 && ownerOf(write.targets[0]) >= 0) return null;
                String target = write.targets.length == 0 ? "?" : Integer.toString(write.targets[0]);
                return "@counter = " + target + (write.wrap ? " (wraps to 0)" : "");
        }
    }

    // ===== 分类 ==============================================================================

    /**
     * 一条写入：种类 + 目标 + 回绕 + 原因。构造期字段还没赋值，所以指令流与归属都用参数传入
     * （这一步必须先于 {@code this.lines}/{@code this.count} 的赋值，否则回绕判断会拿不到长度）。
     */
    private static Write classify(int instruction, String[] lines, int[] owners){
        String line = lines[instruction];
        String[] tokens = tokens(line);
        String kind = tokens[0];
        int statement = instruction < owners.length ? owners[instruction] : -1;

        if(kind.equals("set")){
            String value = tokens.length > 2 ? tokens[2] : "";
            // 入口跳过：set @counter 0 位于主程序末尾（其后只有同形状的 0 写和一条 hoist 前导跳），
            // 它与"跑到程序末尾被执行器回绕"观察等价，UI 需要知道这一点。
            if(isZeroLiteral(value) && isEntrySkipAt(instruction, lines)){
                return new Write(statement, instruction, line, new int[]{0}, true, "entry-skip");
            }
            Integer literal = numberLiteral(value);
            if(literal != null){
                return new Write(statement, instruction, line, new int[]{literal}, outOfRange(literal, lines.length), "set-literal");
            }
            if(value.startsWith("__ls_")){
                // 函数返回蹦床：set @counter __ls_func_<name>_ret，目标是运行时返回值。
                return new Write(statement, instruction, line, noTargets, false, "function-return");
            }
            return new Write(statement, instruction, line, noTargets, false, "dynamic");
        }

        if(kind.equals("op")){
            String operation = tokens.length > 1 ? tokens[1] : "";
            String left = tokens.length > 3 ? tokens[3] : "";
            String right = tokens.length > 4 ? tokens[4] : "";
            boolean add = operation.equals("add");
            boolean sub = operation.equals("sub");
            if((add || sub) && left.equals("@counter")){
                Integer step = numberLiteral(right);
                if(step != null){
                    // 先读后自增：本指令执行后 counter 已是 instruction + 1，再加上/减去 k。
                    int target = instruction + 1 + (add ? step : -step);
                    return new Write(statement, instruction, line, new int[]{target}, outOfRange(target, lines.length), "relative");
                }
                if(add){
                    // 编译器 switch 跳转表的分发指令：op add @counter @counter <运行时索引>。
                    return new Write(statement, instruction, line, noTargets, false, "switch-dispatch");
                }
            }
            return new Write(statement, instruction, line, noTargets, false, "dynamic");
        }

        // read @counter ... 以及其它可能写目的操作数的种类：静态不可判定。
        return new Write(statement, instruction, line, noTargets, false, "dynamic");
    }

    private static boolean outOfRange(int target, int count){
        return target < 0 || target >= count;
    }

    /**
     * 该指令是否是"主程序末尾的 set @counter 0"：其后只允许同形状的 0 写、标签行（已不在归一化
     * 流里）和一条 hoist 前导跳 {@code jump __ls_end always x false}（它跨过被挪到 main 之后的
     * 函数体）。判定与 {@code SugarDecompiler.isEntrySkip} 的位置规则一致。
     */
    private static boolean isEntrySkipAt(int instruction, String[] lines){
        int count = lines.length;
        int at = instruction + 1;
        while(at < count && isEntrySkipShape(lines[at])) at++;
        if(at < count){
            int target = hoistPreludeTarget(lines[at]);
            if(target < 0) return false;
            at = Math.min(Math.max(target, at + 1), count);
        }
        return at >= count;
    }

    /** 只有形状的入口跳过判断：set @counter <零字面量>。 */
    private static boolean isEntrySkipShape(String line){
        String[] tokens = tokens(line);
        if(tokens.length != 3 || !tokens[0].equals("set") || !tokens[1].equals("@counter")) return false;
        Integer value = numberLiteral(tokens[2]);
        return value != null && value == 0;
    }

    /** hoist 前导跳（归一化后目标已是数字）的目标下标；不是这种跳转时 -1。 */
    private static int hoistPreludeTarget(String line){
        String[] tokens = tokens(line);
        if(tokens.length < 3 || !tokens[0].equals("jump") || !tokens[2].equals("always")) return -1;
        Integer target = numberLiteral(tokens[1]);
        if(target == null || target < 0) return -1;
        return target;
    }

    // ===== 归属 ==============================================================================

    private static int[] owners(List<Statement> statements, int count, int[] provenance, int[] mainToCanvas){
        int[] fromLabels = labelOwners(statements, count, mainToCanvas);
        if(provenance == null || provenance.length != count){
            // 长度不匹配 = 调用方给的编号体系不是归一化后的流，整份忽略而不是错位采信。
            return fromLabels;
        }
        int[] result = new int[count];
        for(int i = 0; i < count; i++){
            result[i] = provenance[i] >= 0 ? provenance[i] : fromLabels[i];
        }
        return result;
    }

    private static int[] labelOwners(List<Statement> statements, int count, int[] mainToCanvas){
        int[] result = new int[count];
        Arrays.fill(result, -1);

        List<List<String>> labelsBefore = new ArrayList<>(count);
        for(int i = 0; i < count; i++) labelsBefore.add(new ArrayList<>());
        int cursor = 0;
        for(Statement statement : statements){
            if(statement.label != null){
                if(cursor < count) labelsBefore.get(cursor).add(statement.label);
            }else{
                cursor++;
            }
        }
        // 切分出的语句数与归一化流不一致（多语句行、超限截断等）：不做归属，宁可为 -1。
        if(cursor != count) return result;

        int current = -1;
        boolean unknown = false;
        for(int i = 0; i < count; i++){
            for(String label : labelsBefore.get(i)){
                int statementIndex = mainStatementOf(label);
                // __ls_stmt_<N> 的 N 是 lowering 的可见主程序下标；带 map 时换算回画布下标，
                // 否则带 funcdef 的程序会整体偏位（函数体剥掉了几条，N 就小几）。
                if(statementIndex >= 0 && mainToCanvas != null){
                    statementIndex = statementIndex < mainToCanvas.length ? mainToCanvas[statementIndex] : -1;
                }
                if(statementIndex >= 0){
                    current = statementIndex;
                    unknown = false;
                }else{
                    // 函数体（含被 hoist 到 main 之后的那段）与其它非主程序标签：未知区域，
                    // 直到下一个 __ls_stmt_ 标签。指到错误的卡比指不出来更糟。
                    unknown = true;
                }
            }
            result[i] = unknown ? -1 : current;
        }
        return result;
    }

    /** {@code __ls_stmt_<N>} 的 N；形状不符（函数体标签、用户标签、溢出）返回 -1。 */
    private static int mainStatementOf(String label){
        String prefix = "__ls_stmt_";
        if(!label.startsWith(prefix) || label.length() == prefix.length()) return -1;
        String digits = label.substring(prefix.length());
        for(int i = 0; i < digits.length(); i++){
            if(!Character.isDigit(digits.charAt(i))) return -1;
        }
        try{
            return Integer.parseInt(digits);
        }catch(NumberFormatException overflow){
            return -1;
        }
    }

    // ===== 文本切分 ==========================================================================

    /** 去掉 marker 块与持久化载体行（保留行结构）的文本。 */
    private static String stripMetadata(String code){
        String normalized = SugarCompiler.stripMarkers(code.replace("\r\n", "\n"));
        StringBuilder out = new StringBuilder(normalized.length());
        for(String line : normalized.split("\n", -1)){
            if(isCarrierLine(line)) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    /**
     * 自实现的持久化载体行判断（编译器的同名判断是 private）：首个 token 正好是 {@code set}，
     * 第二个 token 是 {@code __ls_sugar}/{@code __ls_lib} 本身或带纯数字分片后缀
     * （{@code __ls_sugar_3}、{@code __ls_lib_2}）。因此用户变量 {@code __ls_sugarx} 不算。
     */
    private static boolean isCarrierLine(String line){
        String[] tokens = tokens(line);
        if(tokens.length < 2 || !tokens[0].equals("set")) return false;
        String name = tokens[1];
        return isCarrierName(name, "__ls_sugar") || isCarrierName(name, "__ls_lib");
    }

    private static boolean isCarrierName(String name, String base){
        if(name.equals(base)) return true;
        if(!name.startsWith(base + "_")) return false;
        String digits = name.substring(base.length() + 1);
        if(digits.isEmpty()) return false;
        for(int i = 0; i < digits.length(); i++){
            if(!Character.isDigit(digits.charAt(i))) return false;
        }
        try{
            return Long.parseLong(digits) > 0;
        }catch(NumberFormatException overflow){
            return false;
        }
    }

    /** 归一化后的指令行：一行一条指令，空行（不该有，防御性）不算。 */
    private static List<String> instructionLines(String normalized){
        List<String> result = new ArrayList<>();
        for(String line : normalized.replace("\r\n", "\n").split("\n", -1)){
            String trimmed = line.trim();
            if(!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    /** 解析失败时的退化指令行：切分出的非标签语句原文。 */
    private static List<String> statementLines(List<Statement> statements){
        List<String> result = new ArrayList<>(statements.size());
        for(Statement statement : statements){
            if(statement.label == null) result.add(statement.text);
        }
        return result;
    }

    /** 一条语句或一个标签（切分结果）。 */
    private static final class Statement{
        final String text;
        final String label;

        Statement(String text, String label){
            this.text = text;
            this.label = label;
        }
    }

    /**
     * 按 {@code LParser} 的规则切分文本：{@code \n} 与 {@code ;} 分隔语句，{@code #} 之后是注释，
     * 引号内不切；只有一个 token 且以 {@code :} 结尾的段是标签（标签不产生语句）。
     */
    private static List<Statement> scanStatements(String text){
        List<Statement> result = new ArrayList<>();
        for(String line : text.split("\n", -1)){
            int i = 0, length = line.length();
            while(i < length){
                // 段内按空白切 token，引号串整体算一个 token。
                List<String> words = new ArrayList<>();
                boolean comment = false;
                StringBuilder current = new StringBuilder();
                while(i < length){
                    char c = line.charAt(i);
                    if(c == ';' || c == '#') break;
                    if(c == ' ' || c == '\t'){
                        if(current.length() > 0){
                            words.add(current.toString());
                            current.setLength(0);
                        }
                        i++;
                        continue;
                    }
                    if(c == '"'){
                        if(current.length() > 0){
                            words.add(current.toString());
                            current.setLength(0);
                        }
                        int start = i++;
                        while(i < length && line.charAt(i) != '"') i++;
                        if(i < length) i++;
                        words.add(line.substring(start, i));
                        continue;
                    }
                    current.append(c);
                    i++;
                }
                if(current.length() > 0) words.add(current.toString());
                if(i < length && line.charAt(i) == '#'){
                    comment = true;
                    i = length;
                }else if(i < length && line.charAt(i) == ';'){
                    i++;
                }
                if(words.isEmpty()) continue;
                if(words.size() == 1 && words.get(0).endsWith(":")){
                    result.add(new Statement("", words.get(0).substring(0, words.get(0).length() - 1)));
                }else{
                    result.add(new Statement(String.join(" ", words), null));
                }
                if(comment) break;
            }
        }
        return result;
    }

    // ===== token ============================================================================

    /** 与 {@code LParser} 一致的引号感知空白切分（归一化后的行里 token 之间是单个空格）。 */
    private static String[] tokens(String line){
        List<String> result = new ArrayList<>();
        int i = 0, length = line.length();
        while(i < length){
            char c = line.charAt(i);
            if(c == ' ' || c == '\t'){
                i++;
                continue;
            }
            if(c == '"'){
                int start = i++;
                while(i < length && line.charAt(i) != '"') i++;
                if(i < length) i++;
                result.add(line.substring(start, i));
            }else{
                int start = i;
                while(i < length && line.charAt(i) != ' ' && line.charAt(i) != '\t') i++;
                result.add(line.substring(start, i));
            }
        }
        return result.toArray(new String[0]);
    }

    private static String kind(String[] tokens){
        return tokens.length == 0 ? "" : tokens[0];
    }

    /**
     * 目的操作数的 token 下标，与 {@code MlogCFG.writes(String[])} 的位置一致，并补上其它
     * 单个目的操作数的语句种类；没有目的操作数（或未知种类）返回 -1。
     */
    private static int destinationIndex(String kind){
        switch(kind){
            case "set":
            case "sensor":
            case "read":
            case "getlink":
            case "select":
            case "weathersense":
            case "getflag":
            case "sync":
                return 1;
            case "op":
            case "write":
            case "control":
            case "lookup":
            case "getblock":
                return 2;
            case "radar":
            case "uradar":
                return 7;
            default:
                return -1;
        }
    }

    /** 数值字面量；非数值/非有限值返回 null。镜像 {@code LAssembler} 的 0x/0b 与 ___ 写法。 */
    private static Integer numberLiteral(String token){
        if(token == null || token.isEmpty()) return null;
        String literal = token.startsWith("___") ? token.substring(3) : token;
        if(literal.isEmpty()) return null;
        try{
            boolean negative = literal.startsWith("-");
            String body = negative || literal.startsWith("+") ? literal.substring(1) : literal;
            if(body.startsWith("0x") || body.startsWith("0X")){
                return (int)(negative ? -Long.parseLong(body.substring(2), 16) : Long.parseLong(body.substring(2), 16));
            }
            if(body.startsWith("0b") || body.startsWith("0B")){
                return (int)(negative ? -Long.parseLong(body.substring(2), 2) : Long.parseLong(body.substring(2), 2));
            }
            double value = Double.parseDouble(literal);
            if(!Double.isFinite(value)) return null;
            // 与 LExecutor 的 (int)(counter.numval++) 一样是截断转换（超范围按 Java 规则饱和）。
            return (int)value;
        }catch(NumberFormatException notANumber){
            return null;
        }
    }

    /** 零字面量：0、0.0、00、___0、0x0 都算。 */
    private static boolean isZeroLiteral(String token){
        Integer value = numberLiteral(token);
        return value != null && value == 0;
    }
}
