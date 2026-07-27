package logicsugar.assist.expr;

import arc.Core;
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
    static final Set<String> UNARY_OPS = Set.of(
        "not", "abs", "sign", "log", "log10", "floor", "ceil", "round",
        "sqrt", "rand", "sin", "cos", "tan", "asin", "acos", "atan"
    );

    /** 函数型二元运算符（LogicOp.func=true），表达式使用 func(a, b) 语法 */
    static final Set<String> FUNC_BINARY_OPS = Set.of(
        "max", "min", "angle", "angleDiff", "len", "noise", "logn"
    );

    /** 已知的函数名（一元 + 二元） */
    static final Set<String> KNOWN_FUNCS = new HashSet<>();
    static{
        KNOWN_FUNCS.addAll(UNARY_OPS);
        KNOWN_FUNCS.addAll(FUNC_BINARY_OPS);
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

    // ===== OpLine =====
    public static class OpLine{
        public final String op, dest, a, b;
        public OpLine(String op, String dest, String a, String b){
            this.op=op; this.dest=dest; this.a=a; this.b=b;
        }
        public String toText(){
            return "op " + op + " " + dest + " " + a + " " + b;
        }
        public static OpLine fromText(String line){
            String[] parts = line.trim().split("\\s+");
            if(parts.length < 5 || !parts[0].equals("op")) return null;
            return new OpLine(parts[1], parts[2], parts[3], parts[4]);
        }
        @Override public String toString(){ return toText(); }
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
    static final String[] SINGLE_OPS = {"+", "-", "*", "/", "%", "^", "<", ">", "&", "|", "~", "!", "(", ")", ","};

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
        int pos = 0;

        Parser(List<Token> tokens){ this.tokens = tokens; }

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
            if(tok.type == TokType.NUM){
                next();
                return new Num(Double.parseDouble(tok.text));
            }
            if(tok.type == TokType.LPAREN){
                next();
                Node inner = parseExpr();
                if(peek().type != TokType.RPAREN)
                    throw new ParseException(msg("la.err.expected_rparen"));
                next();
                return inner;
            }
            if(tok.type == TokType.IDENT){
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
                    if(funcName == null)
                        throw new ParseException(msg("la.err.unknown_func", name));
                    if(UNARY_OPS.contains(funcName)){
                        if(args.size() != 1) throw new ParseException(msg("la.err.requires_1_arg", funcName));
                        return new Unary(funcName, args.get(0));
                    }
                    if(FUNC_BINARY_OPS.contains(funcName)){
                        if(args.size() != 2) throw new ParseException(msg("la.err.requires_2_args", funcName));
                        return new Binary(funcName, args.get(0), args.get(1));
                    }
                    throw new ParseException(msg("la.err.unknown_func", name));
                }
                return new Var(name);
            }
            throw new ParseException(msg("la.err.unexpected_token", tok.text));
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
    }

    // ===== 正向编译：表达式 → op 链 =====

    /**
     * 编译表达式为 op 语句链。
     * @param dest 目标变量名
     * @param expr 表达式字符串（如 "cos(a) * 10 + x"）
     * @return op 语句列表，最后一条的 dest 为目标变量
     */
    public static List<OpLine> compile(String dest, String expr){
        List<Token> tokens = tokenize(expr);
        Parser parser = new Parser(tokens);
        Node ast = parser.parse();

        List<OpLine> ops = new ArrayList<>();
        TempStack temps = new TempStack();
        String result = compileNode(ast, ops, temps);

        if(isTemp(result)){
            // 优化：将最后一条 op 的 dest 改为目标变量
            OpLine last = ops.get(ops.size() - 1);
            ops.set(ops.size() - 1, new OpLine(last.op, dest, last.a, last.b));
        }else{
            // 结果是简单值，生成赋值 op
            ops.add(new OpLine("add", dest, result, "0"));
        }
        return ops;
    }

    static String compileNode(Node node, List<OpLine> ops, TempStack temps){
        if(node instanceof Num) return formatNum(((Num)node).val);
        if(node instanceof Var) return ((Var)node).name;

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
     * 从 op 语句链重建表达式字符串。
     * @param ops op 语句列表，最后一条的 dest 为目标变量
     * @return 表达式字符串（如 "cos(a) * 10 + x"），无法重建时返回 null
     */
    public static String rebuild(List<OpLine> ops){
        if(ops == null || ops.isEmpty()) return null;

        // 从最后一条 op 开始（dest 为目标变量，非临时变量）
        OpLine root = ops.get(ops.size() - 1);
        Node expr = opToNode(root);
        if(expr == null) return null;

        // 向前遍历，替换临时变量引用
        for(int i = ops.size() - 2; i >= 0; i--){
            OpLine op = ops.get(i);
            if(isTemp(op.dest)){
                Node sub = opToNode(op);
                if(sub == null) return null;
                expr = substituteTemp(expr, op.dest, sub);
            }
        }

        return nodeToString(expr);
    }

    /** 将一条 op 转为 AST 节点，包含简化规则 */
    static Node opToNode(OpLine op){
        // 简化：add x a 0 → a
        if(op.op.equals("add") && op.b.equals("0")) return operandToNode(op.a);
        // 简化：sub x 0 a → -a
        if(op.op.equals("sub") && op.a.equals("0")) return new Unary("neg", operandToNode(op.b));
        // 简化：mul x a 1 → a
        if(op.op.equals("mul") && op.b.equals("1")) return operandToNode(op.a);
        // 简化：equal x a 0 → !a（逻辑非）
        if(op.op.equals("equal") && op.b.equals("0")) return new Unary("lnot", operandToNode(op.a));

        // 一元运算符
        if(UNARY_OPS.contains(op.op)) return new Unary(op.op, operandToNode(op.a));

        // 二元运算符（含函数型）
        return new Binary(op.op, operandToNode(op.a), operandToNode(op.b));
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
