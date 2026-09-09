package logicsugar.assist.expr;

import arc.Core;
import mindustry.logic.LAccess;
import mindustry.logic.SugarCompiler;

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
    /** 数组下标：buf[i] → read。base 必须是已声明数组的名字（经注册表解析），index 是逻辑下标。
     *  矩阵下标 m[i][j] 解析为 Index(Index(Var m, i), j)，由 {@link #emitArrayAddress} 识别。 */
    static class Index extends Node{ final Node base; final Node index; Index(Node b,Node i){base=b;index=i;} }
    /** 单参数 len(buf)：已声明数组的长度，编译期折叠为 size 字面量。len(a,b) 仍是原版向量长度。 */
    static class ArrayLen extends Node{ final Node arg; ArrayLen(Node a){arg=a;} }
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

    /**
     * read 指令行：read <dest> <memory> <address>（数组下标读）。与 SensorLine 同理继承
     * OpLine（op="read"、dest=结果、a=内存块、b=地址），lineDest() 等下游零改动；
     * 4 参调用点（compile 收尾改名、emitCondition/Return、unfoldAll）按类分支处理。
     * 逆向（折叠）仅在注册表把 memory 解析到已声明数组时进行——用户手写的普通 read
     * 不会被误折，纯原版 mlog 不受影响。
     */
    public static class ReadLine extends OpLine{
        public ReadLine(String dest, String memory, String address){
            super("read", dest, memory, address);
        }
        @Override public String toText(){
            return "read " + dest + " " + a + " " + b;
        }
    }

    /** write 指令行：write <value> <memory> <address>（数组下标赋值）。没有 dest：
     *  作为赋值链的终结行，逆向走 {@link #rebuildAssignment}。 */
    public static class WriteLine extends Line{
        public final String value, memory, address;
        public WriteLine(String value, String memory, String address){
            this.value = value; this.memory = memory; this.address = address;
        }
        @Override public String toText(){
            return "write " + value + " " + memory + " " + address;
        }
    }

    /** 任意文本行：原样输出。用于表达式链里的非 op 指令（数组越界断言行等）。
     *  下游（ExprStatement.write、SugarFunctions 的 lower 路径）按类分支处理：
     *  toText() 即最终 mlog 文本，不参与临时变量改名之外的任何重写。 */
    public static class RawLine extends Line{
        public final String text;
        public RawLine(String text){
            this.text = text;
        }
        @Override public String toText(){
            return text;
        }
    }

    /** 数组/矩阵越界断言行（emit 调试构建专用）：线格式与
     *  {@link mindustry.logic.SugarAsserts.AssertBoundsCard} 完全一致——
     *  {@code assertBounds <type> <multiple> <min> <opMin> <value> <opMax> <max> "<message>"}。
     *  结构化保存 value，条件/返回表达式 lowering 时按各自命名空间改名临时变量。 */
    public static class AssertBoundsLine extends RawLine{
        public final String type, multiple, min, opMin, value, opMax, max, message;

        public AssertBoundsLine(String type, String multiple, String min, String opMin,
                                String value, String opMax, String max, String message){
            super(buildText(type, multiple, min, opMin, value, opMax, max, message));
            this.type = type;
            this.multiple = multiple;
            this.min = min;
            this.opMin = opMin;
            this.value = value;
            this.opMax = opMax;
            this.max = max;
            this.message = message;
        }

        private static String buildText(String type, String multiple, String min, String opMin,
                                        String value, String opMax, String max, String message){
            return "assertBounds " + type + " " + multiple + " " + min + " " + opMin + " "
                + value + " " + opMax + " " + max + " " + message;
        }

        /** 断言操作数改名后的副本（条件/返回表达式的临时变量命名空间转换）。 */
        public AssertBoundsLine withValue(String newValue){
            return new AssertBoundsLine(type, multiple, min, opMin, newValue, opMax, max, message);
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
    /** "." 是成员访问符（a.b）；小数点在前面的数字分支处理（.5、1.5），互不冲突。
     *  "[" "]" 是数组下标符（buf[i]），解析进 Index 节点后按注册表编译为 read/write。 */
    static final String[] SINGLE_OPS = {"+", "-", "*", "/", "%", "^", "<", ">", "&", "|", "~", "!", "(", ")", ",", ".", "[", "]"};

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
                        }else if("len".equals(funcName) && args.size() == 1){
                            // F1: len(buf) —— 已声明数组的长度（编译期常量 size）；
                            // len(a, b) 两参仍是原版向量长度内置函数（走下面的 Binary 分支）
                            base = new ArrayLen(args.get(0));
                        }else if(ExprIntrinsics.isIntrinsicName(funcName, args.size())){
                            // F2: 1 参 min/max 是数组批量运算（按实参个数分派）；2 参仍是原版内置
                            base = new Call(funcName, args);
                        }else{
                            if(args.size() != 2) throw new ParseException(msg("la.err.requires_2_args", funcName));
                            base = new Binary(funcName, args.get(0), args.get(1));
                        }
                    }else{
                        // F2: intrinsic 名字（sum/avg/count/...，大小写不敏感；用户函数优先）
                        String intrinsic = ExprIntrinsics.canonicalName(name, args.size());
                        if(intrinsic != null){
                            base = new Call(intrinsic, args);
                        }else if(checker != null && !checker.isFunction(name)){
                            // 编辑期校验：名字不是已知用户函数/库函数
                            throw new ParseException(msg("la.err.unknown_func", name));
                        }else{
                            // 用户函数调用（无 checker 的编译路径不做校验，由 analyze/lower 负责）
                            base = new Call(name, args);
                        }
                    }
                }else{
                    base = new Var(name);
                }
            }else{
                throw new ParseException(msg("la.err.unexpected_token", tok.text));
            }

            // 后置成员访问与数组下标：unit.Health、unit.type.id、cos(a).Health、buf[i]、a.b[0]
            while(isOp(".") || isOp("[")){
                if(isOp(".")){
                    next();
                    if(peek().type != TokType.IDENT)
                        throw new ParseException(msg("la.err.expected_member"));
                    base = new Member(base, next().text);
                }else{
                    next();
                    Node subscript = parseExpr();
                    if(!isOp("]"))
                        throw new ParseException(msg("la.err.expected_rbracket"));
                    next();
                    base = new Index(base, subscript);
                }
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
     * F2: intrinsic provider 的编译上下文。Node/Line 是包私有类型，所以 Ctx 实现必须留在
     * 本包；provider 通过它追加指令行、编译子表达式、分配临时变量并抛统一格式的编译错误。
     */
    static final class IntrinsicCtx implements ExprIntrinsics.Ctx{
        private final List<Line> ops;
        private final TempStack temps;

        IntrinsicCtx(List<Line> ops, TempStack temps){
            this.ops = ops;
            this.temps = temps;
        }

        @Override
        public List<Line> ops(){
            return ops;
        }

        @Override
        public String compile(Node node){
            return compileNode(node, ops, temps);
        }

        @Override
        public String temp(String... operands){
            return temps.alloc(operands);
        }

        @Override
        public boolean isDeclaredName(String name){
            ArrayRegistry registry = ArrayRegistry.active();
            return registry != null && (registry.get(name) != null || registry.getMatrix(name) != null);
        }

        @Override
        public RuntimeException error(String message){
            return new ParseException(message);
        }
    }

    /** 越界断言发射开关（emit 调试构建）。编译路径显式传入，编辑器/预览路径恒为 false：
     *  编辑器展开（ExprHook.unfoldAll）会把链写回画布，断言行只在真正的 lower 阶段生成。 */
    private static boolean boundsAsserts;

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
        return compile(dest, expr, checker, false);
    }

    /**
     * 编译表达式为语句链，并可选地在非常量下标的 read/write 之前发射数组/矩阵越界断言
     * （{@link AssertBoundsLine}，仅 {@code SugarCompiler.AssertEmit.emit} 调试构建；
     * strip 模式与编辑器路径恒为 false）。编译上下文是静态的（同
     * {@link ArrayRegistry#enter} 的模式），enter/restore 保证嵌套调用后恢复。
     */
    public static List<Line> compile(String dest, String expr, FunctionChecker checker, boolean emitBoundsAsserts){
        boolean previous = boundsAsserts;
        boundsAsserts = emitBoundsAsserts;
        try{
            return compileInternal(dest, expr, checker);
        }finally{
            boundsAsserts = previous;
        }
    }

    private static List<Line> compileInternal(String dest, String expr, FunctionChecker checker){
        List<Token> tokens = tokenize(expr);
        Parser parser = new Parser(tokens, checker);
        Node ast = parser.parse();

        // 下标赋值：dest 文本形如 arr[<下标表达式>] 时走写路径（write 指令）。仅当 dest
        // 含 '[' 才尝试解析——普通变量名（含命名空间里的怪名字）保持既有语义不变。
        if(dest != null && dest.indexOf('[') >= 0){
            Node target;
            try{
                target = new Parser(tokenize(dest), checker).parse();
            }catch(ParseException e){
                throw e;
            }catch(Exception e){
                throw new ParseException(msg("la.err.assign_target", dest));
            }
            if(!(target instanceof Index))
                throw new ParseException(msg("la.err.assign_target", dest));
            return compileAssignment((Index)target, ast);
        }

        // F2: 成员赋值目标（p.f1 = value）——intrinsic provider 命中时走写路径。
        // 只在 dest 含 '.' 时尝试；解析失败或没有 provider 处理时保持既有"当普通变量名"语义。
        if(dest != null && dest.indexOf('.') >= 0){
            Node target = parseAssignTarget(dest, checker);
            if(target instanceof Member member){
                List<Line> memberOps = new ArrayList<>();
                List<Line> written = ExprIntrinsics.tryWriteMember(member.base, member.prop, ast,
                    new IntrinsicCtx(memberOps, new TempStack()));
                if(written != null){
                    memberOps.addAll(written);
                    return memberOps;
                }
            }
        }

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
                }else if(last instanceof ReadLine read){
                    ops.set(ops.size() - 1, new ReadLine(dest, read.a, read.b));
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

    /** 下标赋值写路径：先编译 value 表达式，再编译下标并计算地址（base + idx），
     *  产出 write <value> <memory> <address>。 */
    private static List<Line> compileAssignment(Index target, Node valueAst){
        List<Line> ops = new ArrayList<>();
        TempStack temps = new TempStack();
        String value = compileNode(valueAst, ops, temps);
        String[] memoryAddress = emitArrayAddress(target, ops, temps);
        ops.add(new WriteLine(value, memoryAddress[0], memoryAddress[1]));
        return ops;
    }

    /** 解析赋值目标文本为 AST；不是合法表达式时返回 null（调用方回退既有语义）。 */
    private static Node parseAssignTarget(String dest, FunctionChecker checker){
        try{
            return new Parser(tokenize(dest), checker).parse();
        }catch(Exception e){
            return null;
        }
    }

    /**
     * 解析下标目标的数组/矩阵信息并发射地址计算，返回 {memory, address}。
     * 一维数组规则：base==0 且下标为字面量 → 地址=下标字面量；base==0 且非字面量 →
     * 地址=下标本身；base&gt;0 且字面量 → 地址折叠为 base+下标字面量；否则先
     * {@code op add _t <base> <下标>}。矩阵 {@code m[i][j]}（解析为 Index(Index(...))）
     * 走 {@link #emitMatrixAddress}，地址 = base + i*cols + j，字面量下标编译期折叠。
     * 注册表存在且非空时做严格校验（未声明名字、字面量下标越界都报错）；
     * 注册表缺失/为空（语法校验场景）退化为按普通变量名发射 read/write，不做检查。
     * emit 调试构建下非常量下标会在 read/write 之前追加 {@code assertBounds} 行。
     */
    private static String[] emitArrayAddress(Index ix, List<Line> ops, TempStack temps){
        ArrayRegistry registry = ArrayRegistry.active();
        boolean strict = registry != null && !registry.isEmpty();

        // 二维下标 m[i][j]：外层 Index 的 base 是内层 Index
        if(ix.base instanceof Index inner){
            if(!(inner.base instanceof Var))
                throw new ParseException(msg("la.err.array_base_var"));
            if(!strict)
                throw new ParseException(msg("la.err.array_base_var"));
            String name = ((Var)inner.base).name;
            ArrayRegistry.MatrixInfo matrix = registry.getMatrix(name);
            if(matrix == null){
                if(registry.get(name) != null)
                    throw new ParseException(msg("la.err.array_extra_index", name));
                throw new ParseException(msg("la.err.array_unknown", name));
            }
            return emitMatrixAddress(matrix, inner.index, ix.index, ops, temps);
        }

        if(!(ix.base instanceof Var))
            throw new ParseException(msg("la.err.array_base_var"));
        String name = ((Var)ix.base).name;
        if(strict && registry.getMatrix(name) != null)
            throw new ParseException(msg("la.err.matrix_two_indices", name));
        ArrayRegistry.ArrayInfo info = strict ? registry.get(name) : null;
        if(strict && info == null)
            throw new ParseException(msg("la.err.array_unknown", name));

        Long literal = literalValue(ix.index);
        if(literal != null){
            if(info != null && !info.inRange(literal))
                throw new ParseException(msg("la.err.array_index_oob", name, formatNum(literal), info.size));
            long address = info == null ? literal : info.addressOf(literal);
            return new String[]{info == null ? name : info.memory, formatNum(address)};
        }
        String subscript = compileNode(ix.index, ops, temps);
        if(info != null) emitBoundsAssert(ops, "array '" + name + "' index", subscript, 0, info.size - 1);
        int base = info == null ? 0 : info.base;
        if(base == 0) return new String[]{info == null ? name : info.memory, subscript};
        String temp = temps.alloc(subscript);
        ops.add(new OpLine("add", temp, String.valueOf(base), subscript));
        return new String[]{info == null ? name : info.memory, temp};
    }

    /**
     * 矩阵地址计算：地址 = base + row*cols + col（行主序）。字面量下标编译期折叠
     * （含 base 折叠），越界字面量报错；非常量下标用 TempStack 发射 op mul/op add，
     * emit 调试构建下在读写之前追加行/列两条 assertBounds。
     */
    private static String[] emitMatrixAddress(ArrayRegistry.MatrixInfo matrix, Node rowNode, Node colNode,
                                              List<Line> ops, TempStack temps){
        Long rowLit = literalValue(rowNode);
        Long colLit = literalValue(colNode);
        if(rowLit != null && !matrix.inRows(rowLit))
            throw new ParseException(msg("la.err.matrix_row_oob", matrix.name, formatNum(rowLit), matrix.rows));
        if(colLit != null && !matrix.inCols(colLit))
            throw new ParseException(msg("la.err.matrix_col_oob", matrix.name, formatNum(colLit), matrix.cols));

        if(rowLit != null && colLit != null){
            return new String[]{matrix.memory, formatNum(matrix.addressOf(rowLit, colLit))};
        }

        if(rowLit != null){
            // 行是字面量：地址 = (base + row*cols) + col
            String col = compileNode(colNode, ops, temps);
            emitBoundsAssert(ops, "matrix '" + matrix.name + "' column", col, 0, matrix.cols - 1);
            long offset = matrix.base + rowLit * (long)matrix.cols;
            if(offset == 0) return new String[]{matrix.memory, col};
            String dest = temps.alloc(col);
            ops.add(new OpLine("add", dest, formatNum(offset), col));
            return new String[]{matrix.memory, dest};
        }

        if(colLit != null){
            // 列是字面量：地址 = (base + col) + row*cols
            String row = compileNode(rowNode, ops, temps);
            emitBoundsAssert(ops, "matrix '" + matrix.name + "' row", row, 0, matrix.rows - 1);
            String product = row;
            if(matrix.cols != 1){
                product = temps.alloc(row);
                ops.add(new OpLine("mul", product, row, String.valueOf(matrix.cols)));
            }
            long offset = matrix.base + colLit;
            if(offset == 0) return new String[]{matrix.memory, product};
            String dest = temps.alloc(product);
            ops.add(new OpLine("add", dest, formatNum(offset), product));
            return new String[]{matrix.memory, dest};
        }

        // 行列都是变量：product = row*cols, sum = product + col, address = base + sum
        String row = compileNode(rowNode, ops, temps);
        emitBoundsAssert(ops, "matrix '" + matrix.name + "' row", row, 0, matrix.rows - 1);
        String col = compileNode(colNode, ops, temps);
        emitBoundsAssert(ops, "matrix '" + matrix.name + "' column", col, 0, matrix.cols - 1);
        // 行、列是同一个临时变量时不能复用它的名字做乘法目标（会先覆盖再相加）；
        // TempStack.alloc 只复用"操作数里的临时变量"，别名场景改发新临时变量。
        boolean alias = isTemp(row) && row.equals(col);
        String product = row;
        if(matrix.cols != 1){
            product = alias ? temps.fresh() : temps.alloc(row);
            ops.add(new OpLine("mul", product, row, String.valueOf(matrix.cols)));
        }
        String sum = temps.alloc(product, col);
        ops.add(new OpLine("add", sum, product, col));
        if(matrix.base == 0) return new String[]{matrix.memory, sum};
        String dest = temps.alloc(sum);
        ops.add(new OpLine("add", dest, String.valueOf(matrix.base), sum));
        return new String[]{matrix.memory, dest};
    }

    /** emit 调试构建下发射一条越界断言行（strip 模式与编辑器路径恒不发射）。 */
    private static void emitBoundsAssert(List<Line> ops, String what, String operand, long min, long max){
        if(!boundsAsserts) return;
        String message = "\"" + what + " out of bounds (" + min + ".." + max + ")\"";
        ops.add(new AssertBoundsLine("integer", "~", String.valueOf(min), "lessThanEq",
            operand, "lessThanEq", String.valueOf(max), message));
    }

    /** 常量折叠裸字面量下标：Num 或 -Num（整数），其余返回 null。 */
    static Long literalValue(Node node){
        if(node instanceof Num){
            double val = ((Num)node).val;
            return val == Math.rint(val) ? (long)val : null;
        }
        if(node instanceof Unary && ((Unary)node).op.equals("neg") && ((Unary)node).operand instanceof Num){
            double val = ((Num)((Unary)node).operand).val;
            return val == Math.rint(val) ? -(long)val : null;
        }
        return null;
    }

    static String compileNode(Node node, List<Line> ops, TempStack temps){
        if(node instanceof Num) return formatNum(((Num)node).val);
        if(node instanceof Var) return ((Var)node).name;

        if(node instanceof ArrayLen){
            // F1: len(buf) —— 已声明数组的长度，编译期折叠为 size 字面量
            ArrayLen len = (ArrayLen)node;
            if(!(len.arg instanceof Var))
                throw new ParseException(msg("la.err.len_arg_var"));
            String name = ((Var)len.arg).name;
            ArrayRegistry registry = ArrayRegistry.active();
            ArrayRegistry.ArrayInfo info = registry == null ? null : registry.get(name);
            if(info == null){
                if(registry != null && registry.getMatrix(name) != null)
                    throw new ParseException(msg("la.err.len_matrix", name));
                throw new ParseException(msg("la.err.len_unknown", name));
            }
            return String.valueOf(info.size);
        }

        // F2: ExprIntrinsics dispatch point
        if(node instanceof Call){
            Call c = (Call)node;
            // intrinsic 展开（用户 funcdef/库函数优先：被遮蔽的名字在 ExprIntrinsics 里返回 null，
            // 走下面的普通 funccall 路径）
            List<Line> expanded = ExprIntrinsics.tryExpandCall(c.name, c.args, new IntrinsicCtx(ops, temps));
            if(expanded != null){
                ops.addAll(expanded);
                String result = lineDest(expanded.get(expanded.size() - 1));
                if(result == null) throw new ParseException("intrinsic '" + c.name + "' did not produce a result operand");
                return result;
            }
            StringBuilder args = new StringBuilder();
            for(int i = 0; i < c.args.size(); i++){
                if(i > 0) args.append(", ");
                args.append(compileNode(c.args.get(i), ops, temps));
            }
            String temp = temps.fresh();
            ops.add(new CallLine(c.name, args.toString(), temp));
            return temp;
        }

        if(node instanceof Index){
            // 数组下标读：地址计算 → read <tmp> <memory> <address>
            String[] memoryAddress = emitArrayAddress((Index)node, ops, temps);
            String temp = temps.fresh();
            ops.add(new ReadLine(temp, memoryAddress[0], memoryAddress[1]));
            return temp;
        }

        if(node instanceof Member){
            Member m = (Member)node;
            // F2: 记录变量等 intrinsic 成员读（provider 未命中时退回 sensor 属性路径）
            List<Line> expanded = ExprIntrinsics.tryReadMember(m.base, m.prop, new IntrinsicCtx(ops, temps));
            if(expanded != null){
                ops.addAll(expanded);
                String result = lineDest(expanded.get(expanded.size() - 1));
                if(result == null) throw new ParseException("intrinsic member read '" + m.prop + "' did not produce a result operand");
                return result;
            }
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
        Map<Line, ArrayFold> folds = resolveArrayFolds(ops);

        // 从最后一条开始（dest 为目标变量，非临时变量）
        Line root = ops.get(ops.size() - 1);
        Node expr = opToNode(root, folds);
        if(expr == null) return null;

        // 向前遍历，替换临时变量引用
        for(int i = ops.size() - 2; i >= 0; i--){
            Line op = ops.get(i);
            // base>0 数组的地址加法行已被消费：物理地址临时不再进入表达式
            if(folds != null && folds.get(op) == ArrayFold.CONSUMED) continue;
            String dest = lineDest(op);
            if(dest != null && isTemp(dest)){
                Node sub = opToNode(op, folds);
                if(sub == null) return null;
                expr = substituteTemp(expr, dest, sub);
            }
        }

        return nodeToString(expr);
    }

    /**
     * 重建下标赋值链（最后一条为 {@link WriteLine}）：返回 {dest 文本, value 表达式文本}，
     * 例如 {"buf[i + 1]", "a + b"}；注册表缺失、memory 未命中或无法无损重建时返回 null。
     */
    public static String[] rebuildAssignment(List<Line> ops){
        if(ops == null || ops.isEmpty()) return null;
        Line last = ops.get(ops.size() - 1);
        if(!(last instanceof WriteLine)) return null;
        Map<Line, ArrayFold> folds = resolveArrayFolds(ops);
        if(folds == null) return null;
        ArrayFold fold = folds.get(last);
        if(fold == null || fold == ArrayFold.CONSUMED) return null;
        Node dest = foldNode(fold);
        Node value = operandToNode(((WriteLine)last).value);
        for(int i = ops.size() - 2; i >= 0; i--){
            Line line = ops.get(i);
            if(folds.get(line) == ArrayFold.CONSUMED) continue;
            String temp = lineDest(line);
            if(temp != null && isTemp(temp)){
                Node sub = opToNode(line, folds);
                if(sub == null) return null;
                dest = substituteTemp(dest, temp, sub);
                value = substituteTemp(value, temp, sub);
            }
        }
        return new String[]{nodeToString(dest), nodeToString(value)};
    }

    /** 一条 read/write 行折叠时解析到的数组/矩阵归属。CONSUMED 标记被消费的地址计算行
     *  （其 dest 是物理地址临时，不进入表达式，也不再参与替换）。
     *  <ul>
     *    <li>一维数组：{@link #info} + {@link #indexOperand}（逻辑下标操作数）；</li>
     *    <li>矩阵：{@link #matrix} + {@link #row}/{@link #col}（行/列下标 AST——地址链的
     *        子表达式已展开进 AST，被消费的地址计算行记在 {@link #consumed} 里）。</li>
     *  </ul> */
    static final class ArrayFold{
        static final ArrayFold CONSUMED = new ArrayFold(null, null, null, null, null, null);
        final ArrayRegistry.ArrayInfo info;
        final ArrayRegistry.MatrixInfo matrix;
        final String indexOperand;
        final Node row, col;
        final List<Line> consumed;

        ArrayFold(ArrayRegistry.ArrayInfo info, String indexOperand, Line consumed){
            this(info, null, indexOperand, null, null,
                consumed == null ? null : Collections.singletonList(consumed));
        }

        ArrayFold(ArrayRegistry.MatrixInfo matrix, Node row, Node col, List<Line> consumed){
            this(null, matrix, null, row, col, consumed);
        }

        private ArrayFold(ArrayRegistry.ArrayInfo info, ArrayRegistry.MatrixInfo matrix, String indexOperand,
                          Node row, Node col, List<Line> consumed){
            this.info = info;
            this.matrix = matrix;
            this.indexOperand = indexOperand;
            this.row = row;
            this.col = col;
            this.consumed = consumed;
        }
    }

    /** 折叠解析结果 → 下标 AST（一维 {@code buf[i]} 或矩阵 {@code m[i][j]}）。 */
    private static Node foldNode(ArrayFold fold){
        if(fold.matrix != null){
            return new Index(new Index(new Var(fold.matrix.name), fold.row), fold.col);
        }
        return new Index(new Var(fold.info.name), operandToNode(fold.indexOperand));
    }

    /**
     * 解析链中所有 read/write 行的数组/矩阵归属——仅当注册表存在且 memory 操作数命中已声明
     * 数组/矩阵时（用户手写的普通 read/write 与纯原版 mlog 不受影响）。一维数组规则：
     * <ul>
     *   <li>地址为整数字面量 → 归属区间 [base, base+size) 包含该地址的数组，下标=地址-base；
     *       越界地址不折叠（避免折出下次编译报错的卡片）；</li>
     *   <li>地址非字面量 → 回溯最近一条 {@code op add _t <base> <下标>} 定义行（base 与某数组
     *       相等），下标取其加法操作数（加法前的旧值），并把该定义行标记为 CONSUMED——
     *       这样 {@code buf[i]} 折回后重编译的指令流与原链一致；</li>
     *   <li>无 base 加法定义 → 仅当该内存块上恰有一个 base==0 数组且没有 base==0 矩阵时，
     *       下标=地址本身（base 0 矩阵的地址可能是行主序地址，存在时不折叠）。</li>
     * </ul>
     * 一维未命中时尝试矩阵折叠（{@link #resolveMatrixFold}）：地址字面量直接反解行/列；
     * 变量地址沿链内 {@code op add}/{@code op mul} 定义链反解（{@code base + row*cols + col}
     * 的三种编译器形态的逆）。任何无法唯一确定的归属都返回 null——宁可少折回也不能折错，
     * 最终由 {@link #verifyArrayFold} 的重新编译比对兜底。
     */
    private static Map<Line, ArrayFold> resolveArrayFolds(List<Line> ops){
        ArrayRegistry registry = ArrayRegistry.active();
        if(registry == null || registry.isEmpty()) return null;
        Map<Line, ArrayFold> folds = null;
        for(int p = 0; p < ops.size(); p++){
            Line line = ops.get(p);
            String memory, address;
            if(line instanceof ReadLine read){
                memory = read.a;
                address = read.b;
            }else if(line instanceof WriteLine write){
                memory = write.memory;
                address = write.address;
            }else{
                continue;
            }
            ArrayFold fold = resolveArrayFold(registry, memory, address, ops, p);
            if(fold == null) fold = resolveMatrixFold(registry, memory, address, ops, p);
            if(fold == null) continue;
            if(folds == null) folds = new IdentityHashMap<>();
            folds.put(line, fold);
            if(fold.consumed != null){
                for(Line consumed : fold.consumed) folds.put(consumed, ArrayFold.CONSUMED);
            }
        }
        return folds;
    }

    /** 一维数组归属解析（规则见 {@link #resolveArrayFolds}），未命中返回 null。 */
    private static ArrayFold resolveArrayFold(ArrayRegistry registry, String memory, String address, List<Line> ops, int p){
        Long literal = ArrayRegistry.parseIntLiteral(address);
        if(literal != null){
            for(ArrayRegistry.ArrayInfo candidate : registry.byMemory(memory)){
                if(candidate.base <= literal && literal < candidate.base + candidate.size){
                    long index = literal - candidate.base;
                    if(index < 0 || index >= candidate.size) return null;
                    return new ArrayFold(candidate, formatNum(index), null);
                }
            }
            return null;
        }
        for(int j = p - 1; j >= 0; j--){
            Line def = ops.get(j);
            if(!(def instanceof OpLine opdef) || !opdef.op.equals("add") || !opdef.dest.equals(address)) continue;
            Long baseA = ArrayRegistry.parseIntLiteral(opdef.a);
            Long baseB = baseA == null ? ArrayRegistry.parseIntLiteral(opdef.b) : null;
            ArrayRegistry.ArrayInfo byA = baseA == null ? null : findByBase(registry, memory, baseA);
            ArrayRegistry.ArrayInfo byB = byA == null && baseB != null ? findByBase(registry, memory, baseB) : null;
            if(byA != null) return new ArrayFold(byA, opdef.b, def);
            if(byB != null) return new ArrayFold(byB, opdef.a, def);
        }
        // 地址即下标：仅当该内存块上恰有一个 base==0 数组、且不存在 base==0 矩阵
        if(registry.hasZeroBaseMatrix(memory)) return null;
        ArrayRegistry.ArrayInfo zero = null;
        for(ArrayRegistry.ArrayInfo candidate : registry.byMemory(memory)){
            if(candidate.base == 0){
                if(zero != null) return null;
                zero = candidate;
            }
        }
        return zero == null ? null : new ArrayFold(zero, address, null);
    }

    /** 矩阵归属解析：地址字面量直接反解；变量地址沿链内 op add/mul 定义反解。 */
    private static ArrayFold resolveMatrixFold(ArrayRegistry registry, String memory, String address, List<Line> ops, int p){
        List<ArrayRegistry.MatrixInfo> candidates = registry.matricesByMemory(memory);
        if(candidates.isEmpty()) return null;

        Long literal = ArrayRegistry.parseIntLiteral(address);
        if(literal != null){
            ArrayFold found = null;
            for(ArrayRegistry.MatrixInfo matrix : candidates){
                long offset = literal - matrix.base;
                if(offset < 0 || offset >= matrix.size()) continue;
                ArrayFold fold = new ArrayFold(matrix, new Num(offset / matrix.cols), new Num(offset % matrix.cols), null);
                if(found != null) return null; // 多个矩阵区间都能解释该地址 → 不可判定
                found = fold;
            }
            return found;
        }

        Set<Line> absorbed = new LinkedHashSet<>();
        AddrNode root = buildAddrNode(ops, p, address, absorbed);
        if(!resolvableTemps(root, ops, p)) return null;
        ArrayFold found = null;
        for(ArrayRegistry.MatrixInfo matrix : candidates){
            ArrayFold fold = unifyMatrix(matrix, root, absorbed);
            if(fold != null){
                if(found != null) return null; // 多个矩阵都能解释地址链 → 不可判定
                found = fold;
            }
        }
        return found;
    }

    // ===== 矩阵地址逆向：read/write 的 memory/address → m[i][j] =====

    /** 地址操作数的符号表达式：只沿链内 op add/mul 定义展开；其余定值（read/sensor/sub/...）
     *  与普通变量保持为叶子，交给 {@code rebuild} 的临时变量替换机制处理。 */
    abstract static class AddrNode{
        final String operand; // 该子表达式的操作数名（临时变量/变量/字面量）
        AddrNode(String operand){ this.operand = operand; }
    }
    static final class AddrLit extends AddrNode{
        final long value;
        AddrLit(String operand, long value){ super(operand); this.value = value; }
    }
    static final class AddrVar extends AddrNode{
        AddrVar(String operand){ super(operand); }
    }
    static final class AddrBin extends AddrNode{
        final String op; // add / mul
        final AddrNode a, b;
        AddrBin(String operand, String op, AddrNode a, AddrNode b){
            super(operand); this.op = op; this.a = a; this.b = b;
        }
    }

    /** 沿链内 op add/mul 定义展开地址操作数（{@code before} 之前），展开过的定义行记入 used。 */
    private static AddrNode buildAddrNode(List<Line> ops, int before, String operand, Set<Line> used){
        Long literal = ArrayRegistry.parseIntLiteral(operand);
        if(literal != null) return new AddrLit(operand, literal);
        for(int j = before - 1; j >= 0; j--){
            Line line = ops.get(j);
            String dest = lineDest(line);
            if(dest == null || !dest.equals(operand)) continue;
            if(line instanceof OpLine op && (op.op.equals("add") || op.op.equals("mul"))){
                used.add(line);
                return new AddrBin(operand, op.op,
                    buildAddrNode(ops, j, op.a, used), buildAddrNode(ops, j, op.b, used));
            }
            // 最近的定值行不可逆（read/sub/sensor/...）：保持操作数本身，由替换机制还原
            return new AddrVar(operand);
        }
        return new AddrVar(operand);
    }

    /** DAG 里的临时变量叶子必须在链内有定值行，否则折叠会留下无定义的 _N。 */
    private static boolean resolvableTemps(AddrNode node, List<Line> ops, int p){
        if(node instanceof AddrBin bin) return resolvableTemps(bin.a, ops, p) && resolvableTemps(bin.b, ops, p);
        if(node instanceof AddrVar){
            if(!isTemp(node.operand)) return true;
            for(int j = 0; j < p; j++){
                String dest = lineDest(ops.get(j));
                if(dest != null && dest.equals(node.operand)) return true;
            }
            return false;
        }
        return true;
    }

    /** 把一个候选矩阵与地址 DAG 做模式匹配（编译器三种地址形态的逆），不匹配返回 null。
     *  形态优先级：行列都变量 → 列字面量 → 行字面量（都能重编译成同一指令流时取更具体的）。 */
    private static ArrayFold unifyMatrix(ArrayRegistry.MatrixInfo matrix, AddrNode root, Set<Line> used){
        ArrayFold fold = matchBothVariable(matrix, root, used);
        if(fold == null) fold = matchColLiteral(matrix, root, used);
        if(fold == null) fold = matchRowLiteral(matrix, root, used);
        return fold;
    }

    /** 行列都是变量：地址 = [base +] (row*cols + col)（cols==1 时省略乘法）。 */
    private static ArrayFold matchBothVariable(ArrayRegistry.MatrixInfo matrix, AddrNode root, Set<Line> used){
        AddrNode sum = root;
        if(matrix.base != 0){
            if(!(sum instanceof AddrBin baseAdd) || !baseAdd.op.equals("add") || !isLit(baseAdd.a, matrix.base)) return null;
            sum = baseAdd.b;
        }
        if(!(sum instanceof AddrBin add) || !add.op.equals("add")) return null;
        AddrNode product = add.a, colNode = add.b, rowNode;
        if(matrix.cols != 1){
            if(!(product instanceof AddrBin mul) || !mul.op.equals("mul") || !isLit(mul.b, matrix.cols)) return null;
            rowNode = mul.a;
        }else{
            rowNode = product;
        }
        if(rowNode instanceof AddrLit || colNode instanceof AddrLit) return null; // 字面量下标走其它形态
        return new ArrayFold(matrix, addrToNode(rowNode), addrToNode(colNode), new ArrayList<>(used));
    }

    /** 列是字面量：地址 = [base + col] + row*cols（cols==1 时省略乘法）。 */
    private static ArrayFold matchColLiteral(ArrayRegistry.MatrixInfo matrix, AddrNode root, Set<Line> used){
        AddrNode product;
        long col;
        if(root instanceof AddrBin add && add.op.equals("add") && add.a instanceof AddrLit offset){
            long rel = offset.value - matrix.base;
            if(rel < 0 || rel >= matrix.cols) return null;
            col = rel;
            product = add.b;
        }else if(matrix.base == 0){
            col = 0;
            product = root;
        }else{
            return null;
        }
        AddrNode rowNode;
        if(matrix.cols != 1){
            if(!(product instanceof AddrBin mul) || !mul.op.equals("mul") || !isLit(mul.b, matrix.cols)) return null;
            rowNode = mul.a;
        }else{
            rowNode = product;
        }
        if(rowNode instanceof AddrLit) return null;
        return new ArrayFold(matrix, addrToNode(rowNode), new Num(col), new ArrayList<>(used));
    }

    /** 行是字面量：地址 = [base + row*cols] + col（偏移为 0 时地址就是列操作数）。 */
    private static ArrayFold matchRowLiteral(ArrayRegistry.MatrixInfo matrix, AddrNode root, Set<Line> used){
        if(root instanceof AddrBin add && add.op.equals("add") && add.a instanceof AddrLit offset){
            long rel = offset.value - matrix.base;
            if(rel >= 0 && rel % matrix.cols == 0){
                long row = rel / matrix.cols;
                if(row < matrix.rows && !(add.b instanceof AddrLit)){
                    return new ArrayFold(matrix, new Num(row), addrToNode(add.b), new ArrayList<>(used));
                }
            }
        }
        if(matrix.base == 0 && !(root instanceof AddrLit)){
            return new ArrayFold(matrix, new Num(0), addrToNode(root), new ArrayList<>(used));
        }
        return null;
    }

    private static boolean isLit(AddrNode node, long value){
        return node instanceof AddrLit lit && lit.value == value;
    }

    /** AddrNode（地址 DAG）→ AST 节点。 */
    private static Node addrToNode(AddrNode node){
        if(node instanceof AddrLit lit) return new Num(lit.value);
        if(node instanceof AddrVar) return new Var(node.operand);
        AddrBin bin = (AddrBin)node;
        return new Binary(bin.op, addrToNode(bin.a), addrToNode(bin.b));
    }

    /**
     * 折回安全门：仅当链内确实存在数组/矩阵折叠时才校验——折回结果重新编译后与原链
     * 逐行一致才允许折叠（宁可少折回也不能折错）。链内没有数组/矩阵折叠时直接返回 true，
     * 既有的 op/sensor/funccall 折叠行为不变（funccall 实参文本的空格规范化差异也不会误伤）。
     */
    public static boolean verifyArrayFold(List<Line> ops, String dest, String expr, FunctionChecker checker){
        if(resolveArrayFolds(ops) == null) return true;
        if(dest == null) return false;
        try{
            return sameStream(compile(dest, expr, checker), ops);
        }catch(RuntimeException e){
            return false;
        }
    }

    /** 两条指令链逐行等价（funccall 实参只比较去掉空白后的值列表）。 */
    private static boolean sameStream(List<Line> a, List<Line> b){
        if(a.size() != b.size()) return false;
        for(int i = 0; i < a.size(); i++){
            if(!sameLine(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    private static boolean sameLine(Line a, Line b){
        if(a instanceof CallLine ca && b instanceof CallLine cb){
            return ca.name.equals(cb.name) && ca.dest.equals(cb.dest)
                && stripSpaces(ca.args).equals(stripSpaces(cb.args));
        }
        return a.toText().equals(b.toText());
    }

    private static String stripSpaces(String text){
        StringBuilder out = new StringBuilder(text.length());
        for(int i = 0; i < text.length(); i++){
            char c = text.charAt(i);
            if(!Character.isWhitespace(c)) out.append(c);
        }
        return out.toString();
    }

    /** 按 base 在同一内存块上找唯一数组；不唯一或 base 非法时返回 null。 */
    private static ArrayRegistry.ArrayInfo findByBase(ArrayRegistry registry, String memory, Long base){
        if(base == null || base < 0 || base > Integer.MAX_VALUE) return null;
        ArrayRegistry.ArrayInfo found = null;
        for(ArrayRegistry.ArrayInfo candidate : registry.byMemory(memory)){
            if(candidate.base == base){
                if(found != null) return null;
                found = candidate;
            }
        }
        return found;
    }

    /** 行写入的变量名（op/sensor 的 dest、funccall 的 result），null 表示不写变量 */
    static String lineDest(Line line){
        if(line instanceof OpLine op) return op.dest;
        if(line instanceof CallLine call) return call.dest;
        return null;
    }

    /** 将一条指令转为 AST 节点，包含简化规则。folds 是 {@link #resolveArrayFolds} 的解析结果
     *  （可为 null）：read 行仅在命中已声明数组时折回 Index 节点，否则保持原样不折叠。 */
    static Node opToNode(Line op, Map<Line, ArrayFold> folds){
        // funccall → 函数调用节点（foo(a, b)）
        if(op instanceof CallLine call){
            List<Node> args = new ArrayList<>();
            for(String arg : splitValues(call.args)){
                args.add(operandToNode(arg));
            }
            return new Call(call.name, args);
        }
        if(!(op instanceof OpLine opLine)) return null;
        // read 指令行 → 数组/矩阵下标节点（buf[i] / m[i][j]）；未命中注册表（含手写 read）返回 null 不折叠
        if(op instanceof ReadLine read){
            ArrayFold fold = folds == null ? null : folds.get(read);
            if(fold == null || fold == ArrayFold.CONSUMED) return null;
            return foldNode(fold);
        }
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
        }else if(node instanceof Index){
            collectCallNodes(((Index)node).base, out);
            collectCallNodes(((Index)node).index, out);
        }else if(node instanceof ArrayLen){
            collectCallNodes(((ArrayLen)node).arg, out);
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
        if(node instanceof Index){
            Index ix = (Index)node;
            return new Index(
                substituteTemp(ix.base, tempName, replacement),
                substituteTemp(ix.index, tempName, replacement));
        }
        if(node instanceof ArrayLen){
            return new ArrayLen(substituteTemp(((ArrayLen)node).arg, tempName, replacement));
        }
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

        if(node instanceof Index){
            Index ix = (Index)node;
            String base = nodeToString(ix.base);
            // 下标基底是复合表达式时加括号（当前编译只产 Var 基底，此处兜底）
            if(ix.base instanceof Binary || ix.base instanceof Unary){
                base = "(" + base + ")";
            }
            return base + "[" + nodeToString(ix.index) + "]";
        }

        if(node instanceof ArrayLen){
            return "len(" + nodeToString(((ArrayLen)node).arg) + ")";
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

            if(FUNC_BINARY_OPS.contains(b.op)){
                return b.op + "(" + left + ", " + right + ")";
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
