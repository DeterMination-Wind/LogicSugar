package mindustry.logic;

import arc.Core;
import arc.graphics.Color;
import arc.scene.Element;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import mindustry.graphics.Pal;
import mindustry.logic.LCanvas.JumpButton;
import mindustry.logic.LCanvas.JumpCurve;
import mindustry.logic.LCanvas.StatementElem;
import mindustry.logic.LExecutor.LInstruction;
import mindustry.logic.LExecutor.NoopI;
import mindustry.logic.LStatements.JumpStatement;
import mindustry.ui.Styles;
import logicsugar.assist.expr.ExpressionEditor;

import java.util.List;

public final class SugarStatements{
    private SugarStatements(){}

    private static String optional(String value){
        return value.isEmpty() ? "~" : value;
    }

    private static String optionalValue(String value){
        return value.equals("~") ? "" : value;
    }

    private static String text(String key, String fallback){
        return Core.bundle.get("logicsugar." + key, fallback);
    }

    public abstract static class SugarStatement extends LStatement{
        @Override
        public LInstruction build(LAssembler builder){
            return new NoopI();
        }

        @Override
        public LCategory category(){
            return LCategory.control;
        }
    }

    public abstract static class BeginStatement extends SugarStatement{
        public transient StatementElem dest;
        public int destIndex = -1;
        public boolean collapsed;

        protected void linkControl(Table table){
            table.add(new TypedJumpButton(this, () -> dest, target -> {
                dest = SugarCanvas.canLink(this, target) ? target : null;
                SugarCanvas.refreshCurrent();
            }, elem)).size(30f).padRight(4f);
        }

        protected void foldControl(Table table){
            var fold = table.button(collapsed ? mindustry.gen.Icon.rightOpen : mindustry.gen.Icon.downOpen, mindustry.ui.Styles.logici, () -> {
                collapsed = !collapsed;
                SugarCanvas.refreshCurrent();
            }).size(30f).padRight(2f).tooltip(text("fold", "Fold block")).get();
            fold.update(() -> fold.getStyle().imageUp = collapsed ? mindustry.gen.Icon.rightOpen : mindustry.gen.Icon.downOpen);
        }

        @Override
        public void setupUI(){
            if(elem != null && destIndex >= 0 && destIndex < elem.parent.getChildren().size){
                StatementElem candidate = (StatementElem)elem.parent.getChildren().get(destIndex);
                dest = candidate.st instanceof BlockEndStatement ? candidate : null;
            }
        }

        @Override
        public void saveUI(){
            if(elem != null){
                destIndex = dest == null ? -1 : dest.parent.getChildren().indexOf(dest);
            }
        }

        @Override
        public LStatement copy(){
            LStatement result = super.copy();
            if(result instanceof BeginStatement begin) begin.destIndex = -1;
            return result;
        }
    }

    private static class TypedJumpButton extends JumpButton{
        private final BeginStatement begin;

        TypedJumpButton(BeginStatement begin, arc.func.Prov<StatementElem> getter, arc.func.Cons<StatementElem> setter, StatementElem elem){
            super(getter, setter, elem);
            this.begin = begin;
            curve = new StructureJumpCurve(this, getter);
            update(() -> {
                Color color = SugarCanvas.isValidLink(begin, getter.get()) ? Color.white : Pal.remove;
                setColor(color);
                getStyle().imageUpColor = color;
            });
        }
    }

    private static class StructureJumpCurve extends JumpCurve{
        private final arc.func.Prov<StatementElem> target;

        StructureJumpCurve(JumpButton button, arc.func.Prov<StatementElem> target){
            super(button);
            this.target = target;
        }

        @Override
        public void draw(){
            if(target.get() == null) super.draw();
        }

        @Override
        public void prepareHeight(){
            if(target.get() == null){
                super.prepareHeight();
            }else{
                markedDone = true;
                predHeight = 0;
                flipped = false;
                jumpUIBegin = jumpUIEnd = Integer.MAX_VALUE;
            }
        }
    }

    public static class ForBeginStatement extends BeginStatement{
        public String variable = "i", initial = "0", step = "1", compare = "10";
        public ConditionOp op = ConditionOp.lessThanEq;

        @Override
        public void build(Table table){
            // Vanilla-style "label + input": fields() adds the label and field as separate
            // left-aligned cells, so nothing gets centered.
            fields(table, text("for.variable", "variable"), variable, value -> variable = value);
            row(table);
            fields(table, text("for.initial", "initial"), initial, value -> initial = value);
            row(table);
            fields(table, text("for.step", "step"), step, value -> step = value);
            row(table);
            // 终止条件：描述 + 三段式（value op compare）
            table.add(text("for.condition", "until")).padLeft(10).left();
            table.table(this::rebuildCondition);
            foldControl(table);
        }

        private void rebuildCondition(Table table){
            table.clearChildren();
            table.setColor(elem == null ? Pal.logicControl : elem.color);
            JumpStatement.addOp(this, table, op, result -> {
                op = result;
                rebuildCondition(table);
            }, variable, result -> variable = result, compare, result -> compare = result);
        }

        @Override public String name(){ return text("for.begin", "For Begin"); }
        @Override public String typeName(){ return "ForBegin"; }

        @Override
        public void write(StringBuilder out){
            out.append(collapsed ? "forbeginc " : "forbegin ").append(variable).append(' ').append(optional(initial)).append(' ').append(optional(step)).append(' ')
                .append(op.name()).append(' ').append(compare).append(' ').append(destIndex);
        }
    }

    public static class WhileBeginStatement extends BeginStatement{
        public String value = "true", compare = "false";
        public ConditionOp op = ConditionOp.notEqual;

        @Override
        public void build(Table table){
            table.add(text("condition", "condition"));
            table.table(this::rebuildCondition);
            foldControl(table);
        }

        private void rebuildCondition(Table table){
            table.clearChildren();
            table.setColor(elem == null ? Pal.logicControl : elem.color);
            JumpStatement.addOp(this, table, op, result -> {
                op = result;
                rebuildCondition(table);
            }, value, result -> value = result, compare, result -> compare = result);
        }

        @Override public String name(){ return text("while.begin", "While Begin"); }
        @Override public String typeName(){ return "WhileBegin"; }
        @Override public void write(StringBuilder out){ out.append(collapsed ? "whilebeginc " : "whilebegin ").append(value).append(' ').append(op.name()).append(' ').append(compare).append(' ').append(destIndex); }
    }

    public static class SwitchBeginStatement extends BeginStatement{
        public String value = "i";

        @Override
        public void build(Table table){
            table.add(text("switch.value", "switch"));
            field(table, value, result -> value = result);
            foldControl(table);
        }

        @Override public String name(){ return text("switch.begin", "Switch Start"); }
        @Override public String typeName(){ return "SwitchBegin"; }
        @Override public void write(StringBuilder out){ out.append(collapsed ? "switchbeginc " : "switchbegin ").append(value).append(' ').append(destIndex); }
    }

    public static class CaseStatement extends SugarStatement{
        public String value = "0";
        @Override public void build(Table table){ table.add(text("case.value", "case")); field(table, value, result -> value = result); }
        @Override public String name(){ return text("case", "Case"); }
        @Override public String typeName(){ return "Case"; }
        @Override public void write(StringBuilder out){ out.append("case ").append(value); }
    }

    public static class IfBeginStatement extends BeginStatement{
        public String value = "true", compare = "false";
        public ConditionOp op = ConditionOp.notEqual;

        @Override
        public void build(Table table){
            table.add(text("if.condition", "if"));
            table.table(this::rebuildCondition);
            foldControl(table);
        }

        private void rebuildCondition(Table table){
            table.clearChildren();
            table.setColor(elem == null ? Pal.logicControl : elem.color);
            JumpStatement.addOp(this, table, op, result -> {
                op = result;
                rebuildCondition(table);
            }, value, result -> value = result, compare, result -> compare = result);
        }

        @Override public String name(){ return text("if.begin", "If Begin"); }
        @Override public String typeName(){ return "IfBegin"; }
        @Override public void write(StringBuilder out){ out.append(collapsed ? "ifbeginc " : "ifbegin ").append(value).append(' ').append(op.name()).append(' ').append(compare).append(' ').append(destIndex); }
    }

    public static class ElseIfStatement extends SugarStatement{
        public String value = "true", compare = "false";
        public ConditionOp op = ConditionOp.notEqual;

        @Override
        public void build(Table table){
            table.add(text("elif", "elif"));
            table.table(this::rebuildCondition);
        }

        private void rebuildCondition(Table table){
            table.clearChildren();
            table.setColor(elem == null ? Pal.logicControl : elem.color);
            JumpStatement.addOp(this, table, op, result -> {
                op = result;
                rebuildCondition(table);
            }, value, result -> value = result, compare, result -> compare = result);
        }

        @Override public String name(){ return text("elif", "Elif"); }
        @Override public String typeName(){ return "ElseIf"; }
        @Override public void write(StringBuilder out){ out.append("elif ").append(value).append(' ').append(op.name()).append(' ').append(compare); }
    }

    public static class ElseStatement extends SugarStatement{
        @Override public void build(Table table){}
        @Override public String name(){ return text("else", "Else"); }
        @Override public String typeName(){ return "Else"; }
        @Override public void write(StringBuilder out){ out.append("else"); }
    }

    public static class FuncDefStatement extends BeginStatement{
        public String name = "func";
        public String params = "";

        @Override
        public void build(Table table){
            table.add(text("func.def", "func"));
            field(table, name, value -> name = value).width(90f);
            table.add("(");
            TextField paramsField = field(table, params, value -> params = value).width(130f).get();
            paramsField.setMessageText(text("func.params.hint", "a,b"));
            table.add(")");
            foldControl(table);
        }

        @Override public String name(){ return text("func.def", "Func Def"); }
        @Override public String typeName(){ return "FuncDef"; }

        @Override
        public void write(StringBuilder out){
            out.append(collapsed ? "funcdefc " : "funcdef ").append(name).append(' ').append(optional(params)).append(' ').append(destIndex);
        }
    }

    public static class FuncCallStatement extends SugarStatement{
        public String name = "func";
        /** Comma-separated argument expressions. */
        public String args = "";
        /** Optional result variable. */
        public String result = "";

        @Override
        public void build(Table table){
            table.add(text("func.call", "call"));
            field(table, name, value -> name = value).width(90f);
            table.add("(");
            // 实参：完整表达式，高亮显示，点击进入编辑；
            // 空值时提示被调函数的参数列表（动态跟随函数名/参数变化），找不到或函数无参数时退回通用提示
            table.add(new ExpressionEditor(args, this::argsHint, value -> args = value))
                .growX().padLeft(4f).padRight(2f);
            table.add(")");
            table.add("=");
            field(table, result, value -> result = value).width(70f).padLeft(4f);
        }

        /** 动态占位提示：被调函数（本地优先，库函数兜底）的参数列表，找不到或函数无参数时退回通用提示。 */
        private String argsHint(){
            SugarCanvas canvas = SugarCanvas.current();
            String fallback = text("func.args.hint", "a, b+1");
            if(canvas == null || canvas.statements == null) return fallback;
            Seq<LStatement> statements = new Seq<>();
            for(Element child : canvas.statements.getChildren()){
                if(child instanceof StatementElem elem) statements.add(elem.st);
            }
            List<String> params = SugarFunctions.paramsOf(name, statements);
            return params == null ? fallback : String.join(", ", params);
        }

        @Override public String name(){ return text("func.call", "Func Call"); }
        @Override public String typeName(){ return "FuncCall"; }

        @Override
        public void write(StringBuilder out){
            // The result slot is always written ("~" when absent): LParser reuses a static
            // token array, so an omitted trailing token cannot be told apart from a stale one.
            out.append("funccall ").append(name).append(" \"").append(args).append("\" ").append(optional(result));
        }
    }

    public static class ReturnStatement extends SugarStatement{
        /** Return expression; empty means a void (no value) return. */
        public String expr = "";

        @Override
        public void build(Table table){
            table.add(text("func.return", "return"));
            // 返回值：完整表达式，高亮显示，点击进入编辑
            table.add(new ExpressionEditor(expr, text("func.return.hint", "value"), value -> expr = value))
                .growX().padLeft(4f);
        }

        @Override public String name(){ return text("func.return", "Return"); }
        @Override public String typeName(){ return "Return"; }

        @Override
        public void write(StringBuilder out){
            // Always quoted so the void form is unambiguous: LParser reuses a static token
            // array, so a bare "return" line cannot be told apart from a stale token.
            out.append("return \"").append(expr).append('"');
        }
    }

    public static class BreakStatement extends SugarStatement{
        @Override public void build(Table table){}
        @Override public String name(){ return text("break", "Break"); }
        @Override public String typeName(){ return "Break"; }
        @Override public void write(StringBuilder out){ out.append("break"); }
    }

    public static class ContinueStatement extends SugarStatement{
        @Override public void build(Table table){}
        @Override public String name(){ return text("continue", "Continue"); }
        @Override public String typeName(){ return "Continue"; }
        @Override public void write(StringBuilder out){ out.append("continue"); }
    }

    public static class BlockEndStatement extends SugarStatement{
        @Override public void build(Table table){}
        @Override public String name(){ return "}"; }
        @Override public String typeName(){ return "BlockEnd"; }
        @Override public void write(StringBuilder out){ out.append("blockend"); }
    }

    public static LStatement parseForBegin(String[] tokens){
        return parseForBegin(tokens, false);
    }

    public static LStatement parseForBegin(String[] tokens, boolean collapsed){
        ForBeginStatement result = new ForBeginStatement();
        result.variable = tokens[1];
        result.initial = optionalValue(tokens[2]);
        result.step = optionalValue(tokens[3]);
        result.op = ConditionOp.valueOf(tokens[4]);
        result.compare = tokens[5];
        result.destIndex = Integer.parseInt(tokens[6]);
        result.collapsed = collapsed;
        return result;
    }

    public static LStatement parseWhileBegin(String[] tokens){
        return parseWhileBegin(tokens, false);
    }

    public static LStatement parseWhileBegin(String[] tokens, boolean collapsed){
        WhileBeginStatement result = new WhileBeginStatement();
        ConditionOp parsedOp = parseConditionOp(tokens[2]);
        if(parsedOp != null){
            result.value = tokens[1];
            result.op = parsedOp;
            result.compare = tokens[3];
            result.destIndex = parseDestIndex(tokens[4]);
        }else{
            // legacy single-value condition: "whilebegin <cond> <destIndex>"
            result.value = tokens[1];
            result.op = ConditionOp.notEqual;
            result.compare = "false";
            result.destIndex = parseDestIndex(tokens[2]);
        }
        result.collapsed = collapsed;
        return result;
    }

    public static LStatement parseSwitchBegin(String[] tokens){
        return parseSwitchBegin(tokens, false);
    }

    public static LStatement parseSwitchBegin(String[] tokens, boolean collapsed){
        SwitchBeginStatement result = new SwitchBeginStatement();
        result.value = tokens[1];
        result.destIndex = Integer.parseInt(tokens[2]);
        result.collapsed = collapsed;
        return result;
    }

    public static LStatement parseCase(String[] tokens){
        CaseStatement result = new CaseStatement();
        result.value = tokens[1];
        return result;
    }

    public static LStatement parseIfBegin(String[] tokens){
        return parseIfBegin(tokens, false);
    }

    public static LStatement parseIfBegin(String[] tokens, boolean collapsed){
        IfBeginStatement result = new IfBeginStatement();
        result.value = tokens[1];
        ConditionOp op = parseConditionOp(tokens[2]);
        if(op == null) throw new IllegalArgumentException("Invalid ifbegin condition operator: '" + tokens[2] + "'");
        result.op = op;
        result.compare = tokens[3];
        result.destIndex = parseDestIndex(tokens[4]);
        result.collapsed = collapsed;
        return result;
    }

    public static LStatement parseElseIf(String[] tokens){
        ElseIfStatement result = new ElseIfStatement();
        ConditionOp op = parseConditionOp(tokens[2]);
        if(op == null) throw new IllegalArgumentException("Invalid elif condition operator: '" + tokens[2] + "'");
        result.value = tokens[1];
        result.op = op;
        result.compare = tokens[3];
        return result;
    }

    public static LStatement parseElse(String[] tokens){
        return new ElseStatement();
    }

    public static LStatement parseFuncDef(String[] tokens){
        return parseFuncDef(tokens, false);
    }

    public static LStatement parseFuncDef(String[] tokens, boolean collapsed){
        FuncDefStatement result = new FuncDefStatement();
        result.name = tokens[1];
        result.params = optionalValue(tokens[2]);
        result.destIndex = Integer.parseInt(tokens[3]);
        result.collapsed = collapsed;
        return result;
    }

    public static LStatement parseFuncCall(String[] tokens){
        FuncCallStatement result = new FuncCallStatement();
        result.name = tokens[1];
        result.args = stripQuotes(tokens[2]);
        result.result = optionalValue(tokens[3]);
        return result;
    }

    public static LStatement parseReturn(String[] tokens){
        ReturnStatement result = new ReturnStatement();
        result.expr = stripQuotes(tokens[1]);
        return result;
    }

    /** Removes the surrounding quotes that LParser keeps on string tokens. */
    private static String stripQuotes(String value){
        if(value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"'){
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** Parses a token as a ConditionOp, or null when it is not one (e.g. a legacy destIndex).
     *  LParser reuses a static token array, so token count cannot distinguish the legacy
     *  single-value form from the three-part form; the op name is the reliable marker. */
    private static ConditionOp parseConditionOp(String name){
        if(name == null) return null;
        try{
            return ConditionOp.valueOf(name);
        }catch(IllegalArgumentException e){
            return null;
        }
    }

    /** Parses a destination index token, throwing a clean error instead of a bare
     *  NumberFormatException when the token is missing or malformed (LParser reuses a static
     *  token array, so a short line leaves stale or empty trailing tokens). */
    private static int parseDestIndex(String token){
        try{
            return Integer.parseInt(token);
        }catch(NumberFormatException e){
            throw new IllegalArgumentException("Invalid statement destination index: '" + token + "'");
        }
    }

    /**
     * Encodes a single statement's serialized text (its {@code write()} output) into one
     * mlog token so it can ride inside a {@code print} statement and survive the round trip
     * losslessly. An unquoted mlog token cannot contain a space (the separator), so:
     * <ul>
     *   <li>{@code ~} (the escape char) becomes {@code ~~}</li>
     *   <li>{@code ' '} becomes {@code ~_}</li>
     * </ul>
     * Every other character — including {@code _} (identifiers like {@code my_var}, {@code __ls_*}),
     * {@code "} and {@code @} — is kept literal. This is a proper prefix-free code: in the output
     * every {@code ~} is always followed by {@code ~} or {@code _}, so {@link #decodeStatementText}
     * is unambiguous.
     */
    public static String encodeStatementText(String text){
        StringBuilder out = new StringBuilder(text.length() + 8);
        for(int i = 0; i < text.length(); i++){
            char c = text.charAt(i);
            if(c == '~'){
                out.append("~~");
            }else if(c == ' '){
                out.append("~_");
            }else{
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Inverts {@link #encodeStatementText}. A {@code ~} not followed by {@code ~} or {@code _}
     *  (possible only in hand-edited print text) is kept as a literal {@code ~}. */
    public static String decodeStatementText(String text){
        StringBuilder out = new StringBuilder(text.length());
        for(int i = 0; i < text.length(); i++){
            char c = text.charAt(i);
            if(c == '~' && i + 1 < text.length()){
                char next = text.charAt(i + 1);
                if(next == '~'){
                    out.append('~');
                    i++;
                }else if(next == '_'){
                    out.append(' ');
                    i++;
                }else{
                    out.append(c);
                }
            }else{
                out.append(c);
            }
        }
        return out.toString();
    }
}
