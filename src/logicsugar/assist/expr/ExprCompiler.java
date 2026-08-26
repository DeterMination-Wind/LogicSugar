package logicsugar.assist.expr;

import arc.Core;
import mindustry.logic.LAccess;

import java.util.*;

/**
 * 表达式编译器：表达式字符串 ↔ op 语句链双向转换。
 *
 * 正向：x = cos(a) * 10 + x →
 *   op cos _0 a 0
 *   op mul _0 _0 10
 *   op add x _0 x
 *
 * 逆向：上述 op 链 → cos(a) * 10 + x
 *
 * 临时变量策略：统一使用 _0, _1, _2 ... 编号命名，通过栈式分配复用。
 * 每个临时变量写入一次、读取一次，形成线性链，以支持逆向重建。
 *
 * ------------------------------------------------------------
 * 致谢 / Acknowledgements
 * ------------------------------------------------------------
 * 反编译（op 链 → 表达式）思路参考了 mindcode 项目的 MlogDecompiler：
 *   - 项目地址: https://github.com/cardillan/mindcode
 *   - 参考文件: compiler/src/main/java/info/teksol/mc/mindcode/decompiler/MlogDecompiler.java
 *   - 参考内容: collapseExpressions() 用变量定义表跟踪临时变量，
 *     将后续引用替换为 OperationExpression 子树；与本项目 rebuild()
 *     + substituteTemp() 的递归替换思路一致。
 * 运算符分类（一元/函数型二元/符号二元）参考了 mindcode 的 Operation 枚举：
 *   - 参考文件: compiler/src/main/java/info/teksol/mc/mindcode/logic/arguments/Operation.java
 * 表达式优化规则（add x a 0 → a、mul x a 1 → a）参考了 mindcode 文档：
 *   - 参考文件: doc/syntax/optimizations/EXPRESSION-OPTIMIZATION.markdown
 */
public class ExprCompiler{

    // ===== 常量 =====
    public static final String TMP = "_";

    /** 一元运算符（Mindustry LogicOp.unary=true），op 格式：op <name> <dest> <a> 0 */
    // Java 8-compatible set: Set.of is a Java 9 API that is missing on Android < 11.
    static final Set<String> UNARY_OPS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "not", "abs", "sign", "log", "log10", "floor", "ceil", "round",
        "sqrt", "rand", "sin", "cos", "tan", "asin", "acos", "atan"
    )));

    /** 函数型二元运算符（LogicOp.func=true），表达式使用 func(a, b) 语法 */
    static final Set<String> FUNC_BINARY_OPS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "max", "min", "angle", "angleDiff", "len", "noise", "logn"
    )));

    /** 已知的函数名（一元 + 二元） */
    static final Set<String> KNOWN_FUNCS = new HashSet<>();
    static{
        KNOWN_FUNCS.addAll(UNARY_OPS);
        KNOWN_FUNCS.addAll(FUNC_BINARY_OPS);
    }

    /**
     * 可传感属性表（小写名 → LAccess 规范名）：`unit.Health` 的成员名在此查找，
     * 编译为 `sensor x unit @health`。直接引用 {@link LAccess#senseable} 枚举，
     * 跟随游戏版本自动同步；mod 无法扩展 LAccess（Java 枚举固定），无需手工维护。
     */
    static final Map<String, String> SENSOR_MEMBERS = new HashMap<>();
    static{
        for(LAccess access : LAccess.senseable){
            SENSOR_MEMBERS.put(access.name().toLowerCase(), access.name());
        }
    }

    /** 成员名 → LAccess 规范名（大小写不敏感，容忍前导 @）；未知属性返回 null。 */
    static String resolveMember(String prop){
        String name = prop;
        if(name.startsWith("@")) name = name.substring(1);
        return SENSOR_MEMBERS.get(name.toLowerCase());
    }

    /** 去掉 type 的 @ 前缀，用于逆向重建时展示成员名（如 @maxHealth → maxHealth）。 */
    static String memberDisplay(String type){
        return type.startsWith("@") ? type.substring(1) : type;
    }

    // ===== 运算符优先级 =====
    static final int PREC_OR = 1, PREC_AND = 2, PREC_EQ = 3, PREC_REL = 4;
    static final int PREC_XOR = 5, PREC_BAND = 6, PREC_SHIFT = 7;
    static final int PREC_ADD = 8, PREC_MUL = 9, PREC_UNARY = 10, PREC_POW = 11;
    static final int PREC_ATOM = 12;

    /** op 名称 → 表达式符号 */
    static final Map<String, String> OP_TO_SYMBOL = new HashMap<>();
    /** op 名称 → 优先级 */
    static final Map<String, Integer> OP_PRECEDENCE = new HashMap<>();
    static{
        put2("or", "||", PREC_OR);
        put2("land", "&&", PREC_AND);
        put2("equal", "==", PREC_EQ);
        put2("notEqual", "!=", PREC_EQ);
        put2("strictEqual", "===", PREC_EQ);
        put2("lessThan", "<", PREC_REL);
        put2("greaterThan", ">", PREC_REL);
        put2("lessThanEq", "<=", PREC_REL);
        put2("greaterThanEq", ">=", PREC_REL);
        put2("xor", " xor ", PREC_XOR);
        put2("and", "&", PREC_BAND);
        put2("shl", "<<", PREC_SHIFT);
        put2("shr", ">>", PREC_SHIFT);
        put2("ushr", ">>>", PREC_SHIFT);
        put2("add", "+", PREC_ADD);
        put2("sub", "-", PREC_ADD);
        put2("mul", "*", PREC_MUL);
        put2("div", "/", PREC_MUL);
        put2("idiv", "//", PREC_MUL);
        put2("mod", "%", PREC_MUL);
        put2("emod", "%%", PREC_MUL);
        put2("pow", "^", PREC_POW);
    }
    static void put2(String op, String sym, int prec){
        OP_TO_SYMBOL.put(op, sym);
        OP_PRECEDENCE.put(op, prec);
    }

    // ===== AST 节点 =====
    abstract static class Node{}
    static class Num extends Node{ final double val; Num(double v){val=v;} }
    static class Var extends Node{ final String name; Var(String n){name=n;} }
    static class Unary extends Node{ final String op; final Node operand; Unary(String o,Node n){op=o;operand=n;} }
    static class Binary extends Node{ final String op; final Node l,r; Binary(String o,Node a,Node b){op=o;l=a;r=b;} }
    /** 成员访问：unit.Health → sensor。base 可以是任意值表达式，prop 是属性名。 */
    static class Member extends Node{ final Node base; final String prop; Member(Node b,String p){base=b;prop=p;} }
    /** 用户函数调用：foo(a, b)。名字不是数学函数时生成，校验推迟到调用方。 */
    static class Call extends Node{ final String name; final List<Node> args; Call(String n,List<Node> a){name=n;args=a;} }

    /** 表达式链中的一行：op / sensor / 函数调用。compile() 返回的链由 Line 组成。 */
    public abstract static class Line{
        /** 序列化为 mlog 文本（op/sensor 为标准指令；funccall 为 sugar 语句文本） */
        public abstract String toText();
        @Override public String toString(){ return toText(); }
    }

    /** 用户函数调用行：funccall <name> "<args>" <result>。args 是已编译的值名。 */
    public static class CallLine extends Line{
        public final String name;
        public final String args;
        public final String dest;
        public CallLine(String name, String args, String dest){
            this.name = name; this.args = args; this.dest = dest;
        }
        @Override public String toText(){
            String result = dest == null || dest.isEmpty() ? "~" : dest;
            return "funccall " + name + " \"" + args + "\" " + result;
        }
    }

    /** 表达式中的函数名校验回调：true 表示该名字是用户函数/库函数。null 表示不校验。 */
    public interface FunctionChecker{
        boolean isFunction(String name);
    }

    /** 链中一行调用的静态信息（analyze 阶段收集 expr 文本内的调用用） */
    public static class CallSite{
        public final String name;
        public final String args;
        CallSite(String name, String args){ this.name = name; this.args = args; }
    }

    // ===== Line =====
    public static class OpLine extends Line{
        public final String op, dest, a, b;
        public OpLine(String op, String dest, String a, String b){
            this.op=op; this.dest=dest; this.a=a; this.b=b;
        }
        @Override public String toText(){
            return "op " + op + " " + dest + " " + a + " " + b;
        }
        public static OpLine fromText(String line){
            String[] parts = line.trim().split("\\s+");
            if(parts.length < 5 || !parts[0].equals("op")) return null;
            return new OpLine(parts[1], parts[2], parts[3], parts[4]);
        }
    }

    /**
     * sensor 指令行：sensor <to> <from> <type>。与 OpLine 同为 4 元组，
     * 映射为 op="sensor"、dest=to、a=from、b=type，下游 toText() 调用点零改动。
     */
    public static class SensorLine extends OpLine{
        public SensorLine(String dest, String from, String type){
            super("sensor", dest, from, type);
        }
        @Override public String toText(){
            return "sensor " + dest + " " + a + " " + b;
        }
    }

    // ===== 异常 =====
    public static class ParseException extends RuntimeException{
        public ParseException(String msg){ super(msg); }
    }

    // ===== Tokenizer =====
    enum TokType{ NUM, IDENT, OP, LPAREN, RPAREN, COMMA, EOF }
    static class Token{
        final TokType type;
        final String text;
        /** token 在原始字符串中的起始位置（用于高亮保留原始空白） */
        final int start;
        Token(TokType t, String s, int start){ this.type = t; this.text = s; this.start = start; }
    }

    static final String[] MULTI_OPS = {"===", ">>>", "<=", ">=", "==", "!=", "<<", ">>", "%%", "//", "&&", "||"};
    /** "." 是成员访问符（a.b）；小数点在前面的数字分支处理（.5、1.5），互不冲突 */
    static final String[] SINGLE_OPS = {"+", "-", "*", "/", "%", "^", "<", ">", "&", "|", "~", "!", "(", ")", ",", "."};

    static List<Token> tokenize(String expr){
        List<Token> tokens = new ArrayList<>();
        int i = 0, len = expr.length();
        while(i < len){
            char c = expr.charAt(i);
            if(Character.isWhitespace(c)){ i++; continue; }
            if(Character.isDigit(c) || (c == '.' && i+1 < len && Character.isDigit(expr.charAt(i+1)))){
                int start = i;
                while(i < len && (Character.isDigit(expr.charAt(i)) || expr.charAt(i) == '.')) i++;
                tokens.add(new Token(TokType.NUM, expr.substring(start, i), start));
                continue;
            }
            if(Character.isLetter(c) || c == '_' || c == '@'){
                int start = i;
                if(c == '@') i++;
                while(i < len && (Character.isLetterOrDigit(expr.charAt(i)) || expr.charAt(i) == '_')) i++;
                tokens.add(new Token(TokType.IDENT, expr.substring(start, i), start));
                continue;
            }
            if(c == '('){ tokens.add(new Token(TokType.LPAREN, "(", i)); i++; continue; }
            if(c == ')'){ tokens.add(new Token(TokType.RPAREN, ")", i)); i++; continue; }
            if(c == ','){ tokens.add(new Token(TokType.COMMA, ",", i)); i++; continue; }
            boolean matched = false;
            for(String op : MULTI_OPS){
                if(i + op.length() <= len && expr.substring(i, i + op.length()).equals(op)){
                    tokens.add(new Token(TokType.OP, op, i));
                    i += op.length();
                    matched = true;
                    break;
                }
            }
            if(matched) continue;
            for(String op : SINGLE_OPS){
                if(c == op.charAt(0)){
                    tokens.add(new Token(TokType.OP, String.valueOf(c), i));
                    i++;
                    matched = true;
                    break;
                }
            }
            if(matched) continue;
            throw new ParseException(msg("la.err.unrecognized_char", c, i));
        }
        tokens.add(new Token(TokType.EOF, "", i));
        return tokens;
    }

    // ===== Parser（递归下降 + 优先级） =====
    static class Parser{
        final List<Token> tokens;
        final FunctionChecker checker;
        int pos = 0;

        Parser(List<Token> tokens, FunctionChecker checker){
            this.tokens = tokens;
            this.checker = checker;
        }

        Token peek(){ return tokens.get(pos); }
        Token next(){ return tokens.get(pos++); }
        boolean isOp(String text){ return peek().type == TokType.OP && peek().text.equals(text); }
        boolean isIdent(String text){ return peek().type == TokType.IDENT && peek().text.equalsIgnoreCase(text); }

        Node parse(){
            Node node = parseExpr();
            if(peek().type != TokType.EOF)
                throw new ParseException(msg("la.err.unexpected_token", peek().text));
            return node;
        }

        Node parseExpr(){ return parseOr(); }

        Node parseOr(){
            Node left = parseAnd();
            while(isOp("||")){ next(); left = new Binary("or", left, parseAnd()); }
            return left;
        }

        Node parseAnd(){
            Node left = parseEq();
            while(isOp("&&")){ next(); left = new Binary("land", left, parseEq()); }
            return left;
        }

        Node parseEq(){
            Node left = parseRel();
            while(isOp("==") || isOp("!=") || isOp("===")){
                String sym = next().text;
                String op = sym.equals("==") ? "equal" : sym.equals("!=") ? "notEqual" : "strictEqual";
                left = new Binary(op, left, parseRel());
            }
            return left;
        }

        Node parseRel(){
            Node left = parseXor();
            while(isOp("<") || isOp(">") || isOp("<=") || isOp(">=")){
                String sym = next().text;
                String op = sym.equals("<") ? "lessThan" : sym.equals(">") ? "greaterThan"
                    : sym.equals("<=") ? "lessThanEq" : "greaterThanEq";
                left = new Binary(op, left, parseXor());
            }
            return left;
        }

        Node parseXor(){
            Node left = parseBand();
            while(isIdent("xor")){ next(); left = new Binary("xor", left, parseBand()); }
            return left;
        }

        Node parseBand(){
            Node left = parseShift();
            while(isOp("&")){ next(); left = new Binary("and", left, parseShift()); }
            return left;
        }

        Node parseShift(){
            Node left = parseAdd();
            while(isOp("<<") || isOp(">>") || isOp(">>>")){
                String sym = next().text;
                String op = sym.equals("<<") ? "shl" : sym.equals(">>") ? "shr" : "ushr";
                left = new Binary(op, left, parseAdd());
            }
            return left;
        }

        Node parseAdd(){
            Node left = parseMul();
            while(isOp("+") || isOp("-")){
                String sym = next().text;
                left = new Binary(sym.equals("+") ? "add" : "sub", left, parseMul());
            }
            return left;
        }

        Node parseMul(){
            Node left = parseUnary();
            while(isOp("*") || isOp("/") || isOp("//") || isOp("%") || isOp("%%")){
                String sym = next().text;
                String op;
                switch(sym){
                    case "*": op = "mul"; break;
                    case "/": op = "div"; break;
                    case "//": op = "idiv"; break;
                    case "%": op = "mod"; break;
                    default: op = "emod"; break;
                }
                left = new Binary(op, left, parseUnary());
            }
            return left;
        }

        Node parseUnary(){
            if(isOp("-")){ next(); return new Unary("neg", parseUnary()); }
            if(isOp("!")){ next(); return new Unary("lnot", parseUnary()); }
            if(isOp("~")){ next(); return new Unary("not", parseUnary()); }
            return parsePow();
        }

        /** ^ 右结合 */
        Node parsePow(){
            Node base = parseAtom();
            if(isOp("^")){
                next();
                return new Binary("pow", base, parseUnary());
            }
            return base;
        }

        Node parseAtom(){
            Token tok = peek();
            Node base;
            if(tok.type == TokType.NUM){
                next();
                base = new Num(Double.parseDouble(tok.text));
            }else if(tok.type == TokType.LPAREN){
                next();
                base = parseExpr();
                if(peek().type != TokType.RPAREN)
                    throw new ParseException(msg("la.err.expected_rparen"));
                next();
            }else if(tok.type == TokType.IDENT){
                next();
                String name = tok.text;
                if(peek().type == TokType.LPAREN){
                    next();
                    List<Node> args = new ArrayList<>();
                    if(peek().type != TokType.RPAREN){
                        args.add(parseExpr());
                        while(peek().type == TokType.COMMA){
                            next();
                            args.add(parseExpr());
                        }
                    }
                    if(peek().type != TokType.RPAREN)
                        throw new ParseException(msg("la.err.expected_rparen_func"));
                    next();
                    String funcName = resolveFuncName(name);
                    if(funcName != null){
                        // 数学函数优先：用户函数重名被遮蔽（与既有一致）
                        if(UNARY_OPS.contains(funcName)){
                            if(args.size() != 1) throw new ParseException(msg("la.err.requires_1_arg", funcName));
                            base = new Unary(funcName, args.get(0));
                        }else{
                            if(args.size() != 2) throw new ParseException(msg("la.err.requires_2_args", funcName));
                            base = new Binary(funcName, args.get(0), args.get(1));
                        }
                    }else if(checker != null && !checker.isFunction(name)){
                        // 编辑期校验：名字不是已知用户函数/库函数
                        throw new ParseException(msg("la.err.unknown_func", name));
                    }else{
                        // 用户函数调用（无 checker 的编译路径不做校验，由 analyze/lower 负责）
                        base = new Call(name, args);
                    }
                }else{
                    base = new Var(name);
                }
            }else{
                throw new ParseException(msg("la.err.unexpected_token", tok.text));
            }

            // 后置成员访问：unit.Health、unit.type.id、cos(a).Health
            while(isOp(".")){
                next();
                if(peek().type != TokType.IDENT)
                    throw new ParseException(msg("la.err.expected_member"));
                String prop = next().text;
                base = new Member(base, prop);
            }
            return base;
        }

        static String resolveFuncName(String name){
            String lower = name.toLowerCase();
            for(String op : UNARY_OPS) if(op.toLowerCase().equals(lower)) return op;
            for(String op : FUNC_BINARY_OPS) if(op.toLowerCase().equals(lower)) return op;
            return null;
        }
    }

    // ===== 临时变量分配器 =====
    static class TempStack{
        int counter = 0;

        /** 分配临时变量，优先复用 operand 中的临时变量 */
        String alloc(String... operands){
            for(String op : operands){
                if(isTemp(op)) return op;
            }
            return TMP + counter++;
        }

        /** 分配全新临时变量（不复用操作数）：sensor 的 dest 必须新分配，避免
         *  出现 `sensor _0 _0 @x` 这类依赖指令内先读后写时序的写法 */
        String fresh(){
            return TMP + counter++;
        }
    }

    // ===== 正向编译：表达式 → op 链 =====

    /**
     * 编译表达式为语句链（op / sensor / 函数调用）。
     * @param dest 目标变量名
     * @param expr 表达式字符串（如 "cos(a) * 10 + x"）
     * @return 语句列表，最后一条的 dest 为目标变量
     */
    public static List<Line> compile(String dest, String expr){
        return compile(dest, expr, null);
    }

    /**
     * 编译表达式为语句链，带函数名校验回调（编辑期用：未知函数立即报错标红）。
     * checker 为 null 时不做校验——编译路径（lower 阶段）由 analyze/resolveCall 负责。
     */
    public static List<Line> compile(String dest, String expr, FunctionChecker checker){
        List<Token> tokens = tokenize(expr);
        Parser parser = new Parser(tokens, checker);
        Node ast = parser.parse();

        List<Line> ops = new ArrayList<>();
        TempStack temps = new TempStack();
        String result = compileNode(ast, ops, temps);

        if(isTemp(result)){
            if(ops.isEmpty()){
                // dest 本身就是 temp 且结果是简单值（如 compile("_0", "_0")：
                // funccall 实参传递已编译的临时变量）：无法改名，按简单值处理
                ops.add(new OpLine("add", dest, result, "0"));
            }else{
                // 优化：将最后一条指令的 dest 改为目标变量
                Line last = ops.get(ops.size() - 1);
                if(last instanceof SensorLine){
                    SensorLine sl = (SensorLine)last;
                    ops.set(ops.size() - 1, new SensorLine(dest, sl.a, sl.b));
                }else if(last instanceof OpLine opLine){
                    ops.set(ops.size() - 1, new OpLine(opLine.op, dest, opLine.a, opLine.b));
                }else if(last instanceof CallLine callLine){
                    ops.set(ops.size() - 1, new CallLine(callLine.name, callLine.args, dest));
                }else{
                    throw new ParseException(msg("la.err.unknown_node"));
                }
            }
        }else{
            // 结果是简单值，生成赋值 op
            ops.add(new OpLine("add", dest, result, "0"));
        }
        return ops;
    }

    static String compileNode(Node node, List<Line> ops, TempStack temps){
        if(node instanceof Num) return formatNum(((Num)node).val);
        if(node instanceof Var) return ((Var)node).name;

        if(node instanceof Call){
            Call c = (Call)node;
            StringBuilder args = new StringBuilder();
            for(int i = 0; i < c.args.size(); i++){
                if(i > 0) args.append(", ");
                args.append(compileNode(c.args.get(i), ops, temps));
            }
            String temp = temps.fresh();
            ops.add(new CallLine(c.name, args.toString(), temp));
            return temp;
        }

        if(node instanceof Member){
            Member m = (Member)node;
            String canonical = resolveMember(m.prop);
            if(canonical == null)
                throw new ParseException(msg("la.err.unknown_member", m.prop));
            String base = compileNode(m.base, ops, temps);
            String temp = temps.fresh();
            ops.add(new SensorLine(temp, base, "@" + canonical));
            return temp;
        }

        if(node instanceof Unary){
            Unary u = (Unary)node;
            String operand = compileNode(u.operand, ops, temps);
            String temp = temps.alloc(operand);
            String opName;
            String a, b;
            switch(u.op){
                case "neg": opName = "sub"; a = "0"; b = operand; break;
                case "lnot": opName = "equal"; a = operand; b = "0"; break;
                default: opName = u.op; a = operand; b = "0"; break;
            }
            ops.add(new OpLine(opName, temp, a, b));
            return temp;
        }

        if(node instanceof Binary){
            Binary bn = (Binary)node;
            String left = compileNode(bn.l, ops, temps);
            String right = compileNode(bn.r, ops, temps);
            String temp = temps.alloc(left, right);
            ops.add(new OpLine(bn.op, temp, left, right));
            return temp;
        }

        throw new ParseException(msg("la.err.unknown_node"));
    }

    // ===== 逆向重建：op 链 → 表达式 =====

    /**
     * 从语句链重建表达式字符串。
     * @param ops 语句列表，最后一条的 dest 为目标变量
     * @return 表达式字符串（如 "cos(a) * 10 + x"），无法重建时返回 null
     */
    public static String rebuild(List<Line> ops){
        if(ops == null || ops.isEmpty()) return null;

        // 从最后一条开始（dest 为目标变量，非临时变量）
        Line root = ops.get(ops.size() - 1);
        Node expr = opToNode(root);
        if(expr == null) return null;

        // 向前遍历，替换临时变量引用
        for(int i = ops.size() - 2; i >= 0; i--){
            Line op = ops.get(i);
            String dest = lineDest(op);
            if(dest != null && isTemp(dest)){
                Node sub = opToNode(op);
                if(sub == null) return null;
                expr = substituteTemp(expr, dest, sub);
            }
        }

        return nodeToString(expr);
    }

    /** 行写入的变量名（op/sensor 的 dest、funccall 的 result），null 表示不写变量 */
    static String lineDest(Line line){
        if(line instanceof OpLine op) return op.dest;
        if(line instanceof CallLine call) return call.dest;
        return null;
    }

    /** 将一条指令转为 AST 节点，包含简化规则 */
    static Node opToNode(Line op){
        // funccall → 函数调用节点（foo(a, b)）
        if(op instanceof CallLine call){
            List<Node> args = new ArrayList<>();
            for(String arg : splitValues(call.args)){
                args.add(operandToNode(arg));
            }
            return new Call(call.name, args);
        }
        if(!(op instanceof OpLine opLine)) return null;
        // sensor 指令 → 成员访问节点（unit.Health）
        if(op instanceof SensorLine){
            return new Member(operandToNode(opLine.a), memberDisplay(opLine.b));
        }
        // 简化：add x a 0 → a
        if(opLine.op.equals("add") && opLine.b.equals("0")) return operandToNode(opLine.a);
        // 简化：sub x 0 a → -a
        if(opLine.op.equals("sub") && opLine.a.equals("0")) return new Unary("neg", operandToNode(opLine.b));
        // 简化：mul x a 1 → a
        if(opLine.op.equals("mul") && opLine.b.equals("1")) return operandToNode(opLine.a);
        // 简化：equal x a 0 → !a（逻辑非）
        if(opLine.op.equals("equal") && opLine.b.equals("0")) return new Unary("lnot", operandToNode(opLine.a));

        // 一元运算符
        if(UNARY_OPS.contains(opLine.op)) return new Unary(opLine.op, operandToNode(opLine.a));

        // 二元运算符（含函数型）
        return new Binary(opLine.op, operandToNode(opLine.a), operandToNode(opLine.b));
    }

    /** 逗号分隔的值列表（funccall 实参：temp/变量/数字，无嵌套表达式） */
    public static List<String> splitValues(String args){
        List<String> result = new ArrayList<>();
        if(args == null || args.isEmpty()) return result;
        for(String part : args.split(",")){
            result.add(part.trim());
        }
        return result;
    }

    /**
     * 宽松解析表达式文本，收集其中的用户函数调用（数学函数除外）。
     * 供 analyze 阶段登记调用图（递归检测 / normal 模式 reachability / 参数校验）。
     * 未知函数名与未知成员名在此不报错——文本可能引用尚未定义的位置。
     */
    public static List<CallSite> collectCalls(String expr){
        List<CallSite> result = new ArrayList<>();
        if(expr == null || expr.isEmpty()) return result;
        try{
            List<Token> tokens = tokenize(expr);
            collectCallNodes(new Parser(tokens, null).parse(), result);
        }catch(Exception ignored){
            // 解析失败的文本（编辑中间态）不产生调用记录；编译路径会另行报错
        }
        return result;
    }

    private static void collectCallNodes(Node node, List<CallSite> out){
        if(node instanceof Call){
            Call c = (Call)node;
            StringBuilder args = new StringBuilder();
            for(int i = 0; i < c.args.size(); i++){
                if(i > 0) args.append(", ");
                args.append(nodeToString(c.args.get(i)));
            }
            out.add(new CallSite(c.name, args.toString()));
            for(Node arg : c.args) collectCallNodes(arg, out);
        }else if(node instanceof Member){
            collectCallNodes(((Member)node).base, out);
        }else if(node instanceof Unary){
            collectCallNodes(((Unary)node).operand, out);
        }else if(node instanceof Binary){
            collectCallNodes(((Binary)node).l, out);
            collectCallNodes(((Binary)node).r, out);
        }
    }

    static Node operandToNode(String operand){
        try{
            return new Num(Double.parseDouble(operand));
        }catch(NumberFormatException e){
            return new Var(operand);
        }
    }

    /** 在 AST 中将指定临时变量名替换为 replacement 子树 */
    static Node substituteTemp(Node node, String tempName, Node replacement){
        if(node instanceof Var){
            return ((Var)node).name.equals(tempName) ? replacement : node;
        }
        if(node instanceof Num) return node;
        if(node instanceof Member){
            Member m = (Member)node;
            return new Member(substituteTemp(m.base, tempName, replacement), m.prop);
        }
        if(node instanceof Call){
            Call c = (Call)node;
            List<Node> args = new ArrayList<>(c.args.size());
            for(Node arg : c.args) args.add(substituteTemp(arg, tempName, replacement));
            return new Call(c.name, args);
        }
        if(node instanceof Unary){
            Unary u = (Unary)node;
            return new Unary(u.op, substituteTemp(u.operand, tempName, replacement));
        }
        if(node instanceof Binary){
            Binary b = (Binary)node;
            return new Binary(b.op,
                substituteTemp(b.l, tempName, replacement),
                substituteTemp(b.r, tempName, replacement));
        }
        return node;
    }

    // ===== AST → 字符串 =====

    static String nodeToString(Node node){
        if(node instanceof Num) return formatNum(((Num)node).val);
        if(node instanceof Var) return ((Var)node).name;

        if(node instanceof Call){
            Call c = (Call)node;
            StringBuilder out = new StringBuilder(c.name).append('(');
            for(int i = 0; i < c.args.size(); i++){
                if(i > 0) out.append(", ");
                out.append(nodeToString(c.args.get(i)));
            }
            return out.append(')').toString();
        }

        if(node instanceof Member){
            Member m = (Member)node;
            String base = nodeToString(m.base);
            // 成员访问优先级高于二元/一元：(-a).Health、(a+b).Health 必须加括号
            if(m.base instanceof Binary || m.base instanceof Unary){
                base = "(" + base + ")";
            }
            return base + "." + m.prop;
        }

        if(node instanceof Unary){
            Unary u = (Unary)node;
            String inner = nodeToString(u.operand);
            if(u.op.equals("neg")){
                if(u.operand instanceof Binary) return "-(" + inner + ")";
                if(u.operand instanceof Num) return "-" + inner;
                if(u.operand instanceof Unary && ((Unary)u.operand).op.equals("neg"))
                    return "-(" + inner + ")";
                return "-" + inner;
            }
            if(u.op.equals("lnot")){
                if(u.operand instanceof Binary) return "!(" + inner + ")";
                return "!" + inner;
            }
            // 函数调用：cos(a), sin(a) 等
            return u.op + "(" + inner + ")";
        }

        if(node instanceof Binary){
            Binary b = (Binary)node;
            int prec = getPrecedence(b.op);
            String sym = opToSymbol(b.op);
            String left = nodeToString(b.l);
            String right = nodeToString(b.r);

            // 左子节点加括号
            if(b.l instanceof Binary){
                int lp = getPrecedence(((Binary)b.l).op);
                if(lp < prec || (lp == prec && b.op.equals("pow")))
                    left = "(" + left + ")";
            }

            // 右子节点加括号
            if(b.r instanceof Binary){
                int rp = getPrecedence(((Binary)b.r).op);
                if(rp < prec || (rp == prec && !b.op.equals("pow")))
                    right = "(" + right + ")";
            }

            return left + sym + right;
        }

        throw new ParseException(msg("la.err.unknown_node"));
    }

    // ===== 工具方法 =====

    /** 从 bundle 获取本地化消息，找不到时返回 key 本身（开发提醒） */
    private static String msg(String key, Object... args){
        if(Core.bundle == null || !Core.bundle.has(key)) return key;
        return args.length == 0 ? Core.bundle.get(key) : Core.bundle.format(key, args);
    }

    /** 判断变量名是否为临时变量（_0, _1, _2 ... 格式，_ 后必须跟数字） */
    public static boolean isTemp(String name){
        if(name == null || name.length() < 2 || !name.startsWith(TMP)) return false;
        for(int i = 1; i < name.length(); i++){
            if(!Character.isDigit(name.charAt(i))) return false;
        }
        return true;
    }

    static int getPrecedence(String op){
        Integer p = OP_PRECEDENCE.get(op);
        return p != null ? p : PREC_ATOM;
    }

    static String opToSymbol(String op){
        String s = OP_TO_SYMBOL.get(op);
        return s != null ? s : op;
    }

    static String formatNum(double val){
        if(val == (long)val) return String.valueOf((long)val);
        return String.valueOf(val);
    }
}
