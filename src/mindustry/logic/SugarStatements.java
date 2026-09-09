package mindustry.logic;

import arc.Core;
import arc.func.Cons;
import arc.graphics.Color;
import arc.scene.Element;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Cell;
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

    /** Settings key for localized card titles (checkbox registered in LogicSugarSettings). */
    public static final String settingLocalizeCards = "logicsugar.localizeCards";

    private static boolean parsersInstalled;

    /** Registers every Sugar token parser into {@code LAssembler.customParsers}. This is the
     *  single registration point shared by mod init, the decompiler's parse preflight and the
     *  self-tests, so all entry points agree on parsing behavior (including the legacy
     *  dev-version markers). Idempotent per class loader. */
    public static void installParsers(){
        if(parsersInstalled) return;
        parsersInstalled = true;

        LAssembler.customParsers.put("forbegin", SugarStatements::parseForBegin);
        LAssembler.customParsers.put("forbeginc", tokens -> SugarStatements.parseForBegin(tokens, true));
        LAssembler.customParsers.put("whilebegin", SugarStatements::parseWhileBegin);
        LAssembler.customParsers.put("whilebeginc", tokens -> SugarStatements.parseWhileBegin(tokens, true));
        LAssembler.customParsers.put("switchbegin", SugarStatements::parseSwitchBegin);
        LAssembler.customParsers.put("switchbeginc", tokens -> SugarStatements.parseSwitchBegin(tokens, true));
        LAssembler.customParsers.put("ifbegin", SugarStatements::parseIfBegin);
        LAssembler.customParsers.put("ifbeginc", tokens -> SugarStatements.parseIfBegin(tokens, true));
        LAssembler.customParsers.put("case", SugarStatements::parseCase);
        LAssembler.customParsers.put("elif", SugarStatements::parseElseIf);
        LAssembler.customParsers.put("else", SugarStatements::parseElse);
        LAssembler.customParsers.put("break", tokens -> new SugarStatements.BreakStatement());
        LAssembler.customParsers.put("continue", tokens -> new SugarStatements.ContinueStatement());
        LAssembler.customParsers.put("blockend", tokens -> new SugarStatements.BlockEndStatement());
        LAssembler.customParsers.put("funcdef", SugarStatements::parseFuncDef);
        LAssembler.customParsers.put("funcdefc", tokens -> SugarStatements.parseFuncDef(tokens, true));
        LAssembler.customParsers.put("funccall", SugarStatements::parseFuncCall);
        LAssembler.customParsers.put("return", SugarStatements::parseReturn);
        LAssembler.customParsers.put("array", SugarStatements::parseArray);
        LAssembler.customParsers.put("matrix", SugarStatements::parseMatrix);
        LAssembler.customParsers.put("arrayinit", SugarStatements::parseArrayInit);

        // Read-only compatibility for markers produced by the first development version.
        LAssembler.customParsers.put("forend", tokens -> new SugarStatements.BlockEndStatement());
        LAssembler.customParsers.put("whileend", tokens -> new SugarStatements.BlockEndStatement());
        LAssembler.customParsers.put("switchend", tokens -> new SugarStatements.BlockEndStatement());

        SugarAsserts.installParsers();
    }

    private static String optional(String value){
        return value.isEmpty() ? "~" : value;
    }

    private static String optionalValue(String value){
        return value == null || value.equals("~") ? "" : value;
    }

    private static String text(String key, String fallback){
        return Core.bundle.get("logicsugar." + key, fallback);
    }

    /** Whether Sugar card titles render in the game language (setting
     *  {@link #settingLocalizeCards}, default on). Read live, so toggling it applies the
     *  next time the logic editor is opened — no settings refresh callback needed. */
    public static boolean cardsLocalized(){
        return Core.settings.getBool(settingLocalizeCards, true);
    }

    /** {@link #text} for card titles only ({@code name()} overrides): returns the
     *  untranslated English fallback while card-title localization is switched off. Public
     *  because the {@link SugarAsserts} card titles share the same policy. */
    public static String cardText(String key, String fallback){
        return cardsLocalized() ? text(key, fallback) : fallback;
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

        /** {@link JumpStatement#addOp} with the value/compare fields narrowed to 75px, so
         *  condition rows remain usable after structure indentation is applied.
         *
         *  Must be an instance method on this subclass: {@code field} and {@code showSelect}
         *  are protected LStatement members, and at runtime the mod class loader is a
         *  different runtime package from the game's, so a same-package call from a static
         *  helper throws IllegalAccessError (see AGENTS.md). Subclass access stays legal. */
        public void addCompactOp(Table t, ConditionOp op, Cons<ConditionOp> setOp,
                                 String value, Cons<String> setValue, String compare, Cons<String> setCompare){
            if(op != ConditionOp.always) field(t, value, setValue).width(75f).pad(2f);

            t.button(b -> {
                b.add(op.symbol);
                b.clicked(() -> showSelect(b, ConditionOp.all, op, setOp));
            }, Styles.logict, () -> {
            }).size(op == ConditionOp.always ? 80f : 48f, 40f).pad(4f).color(t.color);

            if(op != ConditionOp.always) field(t, compare, setCompare).width(75f).pad(2f);
        }

        /** Attaches a vanilla-style hover hint to a parameter label (bundle key: logicsugar.hint.<key>). */
        protected void hint(Cell<?> cell, String key){
            LCanvas.tooltip(cell, "logicsugar.hint." + key);
        }

        /** Label + input grouped into one nested cell, preventing sibling fields from expanding
         *  each other's columns in the compact For layout. */
        protected Cell<TextField> fieldsHint(Table table, String desc, String hintKey, String value, Cons<String> setter){
            Cell<TextField>[] result = new Cell[]{null};
            table.table(inner -> {
                inner.left();
                Color target = elem == null ? Pal.logicControl : elem.color;
                inner.setColor(target);
                inner.add(desc).padLeft(10).left().self(c -> hint(c, hintKey));
                // 输入框略收窄(85→65)，使窄屏下三个"标签+输入框"组连同右侧条件不超出卡片；
                // 同时收紧组间 padRight(10→6)。仅作用于 For 前置区，不影响其他语句。
                result[0] = field(inner, value, setter).width(65f).padRight(6).left();
                TextField input = result[0].get();
                input.setColor(target);
                // 跟随语句卡片状态（正常蓝色/无效红色），不能依赖父 Table 的初始化颜色：
                // table.table(Cons) 回调执行时外层 Cell.color 还没应用，table.color 仍可能是白色。
                inner.update(() -> {
                    Color current = elem == null ? Pal.logicControl : elem.color;
                    inner.setColor(current);
                    input.setColor(current);
                });
            }).left();
            return result[0];
        }
    }

    /**
     * Shared condition editor for if/elif/for/while: the native three-part selector and the
     * EXPR/OP mode toggle stay on the same row. The row colour follows the statement card
     * every frame, so invalid-state red marking can never leave a stale snapshot behind.
     *
     * @param compactCondition uses narrower condition fields (75px instead of the
     *                         144px ones vanilla {@code addOp} reserves).
     */
    private static void rebuildConditionEditor(LStatement owner, Table table, boolean expressionMode, String expr,
                                               Cons<String> setExpr, Runnable enterExpr, Runnable leaveExpr,
                                               ConditionOp op, Cons<ConditionOp> setOp,
                                               String value, Cons<String> setValue,
                                               String compare, Cons<String> setCompare,
                                               boolean compactCondition,
                                               Runnable rebuild){
        table.clearChildren();
        table.left();
        table.update(() -> {
            Color target = owner.elem == null ? Pal.logicControl : owner.elem.color;
            table.setColor(target);
            for(Element child : table.getChildren()){
                child.setColor(target);
            }
        });
        if(expressionMode){
            table.add(new ExpressionEditor(expr, text("condition.expr.hint", "a > b && enabled"), setExpr))
                .growX().fillX().pad(4f);
            // 显示当前状态：Expr 模式下按钮显示 "Expr"，点击切回三段式
            table.button(b -> {
                b.add(text("condition.expr", "Expr"));
                b.clicked(() -> {
                    leaveExpr.run();
                    rebuild.run();
                });
            }, Styles.logict, () -> {}).size(72f, 40f).pad(4f).color(table.color);
        }else{
            if(compactCondition){
                ((SugarStatement)owner).addCompactOp(table, op, result -> {
                    setOp.get(result);
                    rebuild.run();
                }, value, setValue, compare, setCompare);
            }else{
                JumpStatement.addOp(owner, table, op, result -> {
                    setOp.get(result);
                    rebuild.run();
                }, value, setValue, compare, setCompare);
            }
            // 条件三段式与 op 切换按钮始终保持在同一行；语句块层只负责 For 的固定分组换行。
            table.button(b -> {
                b.add(text("condition.expr.back", "op"));
                b.clicked(() -> {
                    enterExpr.run();
                    rebuild.run();
                });
            }, Styles.logict, () -> {}).size(48f, 40f).pad(4f).color(table.color);
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
            // logici draws a plain black glyph with no background, which vanishes on the
            // tinted statement card; use a visible background with a light icon instead.
            arc.scene.ui.ImageButton.ImageButtonStyle foldStyle = new arc.scene.ui.ImageButton.ImageButtonStyle(mindustry.ui.Styles.logici);
            foldStyle.up = mindustry.ui.Styles.logict.up;
            foldStyle.imageUpColor = arc.graphics.Color.white;
            var fold = table.button(collapsed ? mindustry.gen.Icon.rightOpen : mindustry.gen.Icon.downOpen, foldStyle, () -> {
                collapsed = !collapsed;
                SugarCanvas.refreshCurrent();
            }).size(34f, 40f).pad(4f).tooltip(text("fold", "Fold block")).get();
            fold.update(() -> fold.getStyle().imageUp = collapsed ? mindustry.gen.Icon.rightOpen : mindustry.gen.Icon.downOpen);
        }

        /** Adds the fold control at the end of the current row; row breaks are decided by
         *  the statement layout (e.g. For's fixed two-row grouping). */
        protected void foldControlRow(Table table){
            foldControl(table);
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
        /** When true, conditionExpr replaces the variable/operator/compare triplet. */
        public boolean expressionMode;
        /** When true, the expression is lowered as short-circuit control flow instead of op land/or. */
        public boolean shortCircuitMode;
        public String conditionExpr = "true";

        @Override
        public void build(Table table){
            // 先把前半行和后半行分成独立的子表格，避免条件控件的 growX 参与前面
            // 字段列宽分配。宽屏合并为一行，窄屏只在步长后换一次行。
            table.table(content -> {
                content.left();
                if(LCanvas.useRows()){
                    content.table(this::buildForPrefix).left();
                    content.row();
                    content.table(this::buildForCondition).growX().fillX().left();
                }else{
                    buildForPrefix(content);
                    buildForCondition(content);
                }
            }).growX().fillX().left();
        }

        private void buildForPrefix(Table table){
            fieldsHint(table, text("for.variable", "variable"), "for.variable", variable, value -> variable = value);
            fieldsHint(table, text("for.initial", "initial"), "for.initial", initial, value -> initial = value);
            fieldsHint(table, text("for.step", "step"), "for.step", step, value -> step = value);
        }

        private void buildForCondition(Table table){
            table.add(text("for.condition", "until")).padLeft(10).left().self(c -> hint(c, "for.condition"));
            table.table(this::rebuildCondition).growX().fillX().left();
            foldControl(table);
        }

        private void rebuildCondition(Table table){
            rebuildConditionEditor(this, table, expressionMode, conditionExpr,
                result -> conditionExpr = result,
                () -> expressionMode = true,
                () -> expressionMode = false,
                op, result -> op = result,
                variable, result -> variable = result,
                compare, result -> compare = result,
                true,
                () -> rebuildCondition(table));
        }

        @Override public String name(){ return cardText("for.begin", "For Begin"); }
        @Override public String typeName(){ return "ForBegin"; }

        @Override
        public void write(StringBuilder out){
            if(expressionMode){
                out.append(collapsed ? "forbeginc " : "forbegin ").append(variable).append(' ')
                    .append(optional(initial)).append(' ').append(optional(step)).append(' ')
                    .append(shortCircuitMode ? "exprsc \"" : "expr \"")
                    .append(conditionExpr).append("\" ").append(destIndex);
            }else{
                out.append(collapsed ? "forbeginc " : "forbegin ").append(variable).append(' ').append(optional(initial)).append(' ').append(optional(step)).append(' ')
                    .append(op.name()).append(' ').append(compare).append(' ').append(destIndex);
            }
        }
    }

    public static class WhileBeginStatement extends BeginStatement{
        public String value = "true", compare = "false";
        public ConditionOp op = ConditionOp.notEqual;
        public boolean expressionMode;
        /** When true, the expression is lowered as short-circuit control flow instead of op land/or. */
        public boolean shortCircuitMode;
        public String conditionExpr = "true";

        @Override
        public void build(Table table){
            table.add(text("condition", "condition")).self(c -> hint(c, "while.condition"));
            table.table(this::rebuildCondition).growX().fillX();
            foldControlRow(table);
        }

        private void rebuildCondition(Table table){
            rebuildConditionEditor(this, table, expressionMode, conditionExpr,
                result -> conditionExpr = result,
                () -> expressionMode = true,
                () -> expressionMode = false,
                op, result -> op = result,
                value, result -> value = result,
                compare, result -> compare = result,
                true,
                () -> rebuildCondition(table));
        }

        @Override public String name(){ return cardText("while.begin", "While Begin"); }
        @Override public String typeName(){ return "WhileBegin"; }
        @Override public void write(StringBuilder out){
            if(expressionMode){
                out.append(collapsed ? "whilebeginc " : "whilebegin ")
                    .append(shortCircuitMode ? "exprsc \"" : "expr \"")
                    .append(conditionExpr).append("\" ").append(destIndex);
            }else{
                out.append(collapsed ? "whilebeginc " : "whilebegin ").append(value).append(' ').append(op.name()).append(' ').append(compare).append(' ').append(destIndex);
            }
        }
    }

    public static class SwitchBeginStatement extends BeginStatement{
        public String value = "i";

        @Override
        public void build(Table table){
            table.add(text("switch.value", "switch")).self(c -> hint(c, "switch.value"));
            field(table, value, result -> value = result).width(85f);
            foldControlRow(table);
        }

        @Override public String name(){ return cardText("switch.begin", "Switch Start"); }
        @Override public String typeName(){ return "SwitchBegin"; }
        @Override public void write(StringBuilder out){ out.append(collapsed ? "switchbeginc " : "switchbegin ").append(value).append(' ').append(destIndex); }
    }

    public static class CaseStatement extends SugarStatement{
        public String value = "0";
        @Override public void build(Table table){
            table.add(text("case.value", "case")).self(c -> hint(c, "case"));
            field(table, value, result -> value = result);
        }
        @Override public String name(){ return cardText("case", "Case"); }
        @Override public String typeName(){ return "Case"; }
        @Override public void write(StringBuilder out){ out.append("case ").append(value); }
    }

    public static class IfBeginStatement extends BeginStatement{
        public String value = "true", compare = "false";
        public ConditionOp op = ConditionOp.notEqual;
        /** When true, conditionExpr replaces the variable/operator/compare triplet. */
        public boolean expressionMode;
        /** When true, the expression is lowered as short-circuit control flow instead of op land/or. */
        public boolean shortCircuitMode;
        public String conditionExpr = "true";

        @Override
        public void build(Table table){
            table.add(text("if.condition", "if")).self(c -> hint(c, "if.condition"));
            table.table(this::rebuildCondition).growX().fillX();
            foldControlRow(table);
        }

        private void rebuildCondition(Table table){
            rebuildConditionEditor(this, table, expressionMode, conditionExpr,
                result -> conditionExpr = result,
                () -> expressionMode = true,
                () -> expressionMode = false,
                op, result -> op = result,
                value, result -> value = result,
                compare, result -> compare = result,
                true,
                () -> rebuildCondition(table));
        }

        @Override public String name(){ return cardText("if.begin", "If Begin"); }
        @Override public String typeName(){ return "IfBegin"; }
        @Override public void write(StringBuilder out){
            if(expressionMode){
                out.append(collapsed ? "ifbeginc " : "ifbegin ")
                    .append(shortCircuitMode ? "exprsc \"" : "expr \"")
                    .append(conditionExpr).append("\" ").append(destIndex);
            }else{
                out.append(collapsed ? "ifbeginc " : "ifbegin ").append(value).append(' ')
                    .append(op.name()).append(' ').append(compare).append(' ').append(destIndex);
            }
        }
    }

    public static class ElseIfStatement extends SugarStatement{
        public String value = "true", compare = "false";
        public ConditionOp op = ConditionOp.notEqual;
        public boolean expressionMode;
        /** When true, the expression is lowered as short-circuit control flow instead of op land/or. */
        public boolean shortCircuitMode;
        public String conditionExpr = "true";

        @Override
        public void build(Table table){
            table.add(text("elif", "elif")).self(c -> hint(c, "elif"));
            table.table(this::rebuildCondition).growX().fillX();
        }

        private void rebuildCondition(Table table){
            rebuildConditionEditor(this, table, expressionMode, conditionExpr,
                result -> conditionExpr = result,
                () -> expressionMode = true,
                () -> expressionMode = false,
                op, result -> op = result,
                value, result -> value = result,
                compare, result -> compare = result,
                true,
                () -> rebuildCondition(table));
        }

        @Override public String name(){ return cardText("elif", "Elif"); }
        @Override public String typeName(){ return "ElseIf"; }
        @Override public void write(StringBuilder out){
            if(expressionMode){
                out.append("elif ").append(shortCircuitMode ? "exprsc \"" : "expr \"")
                    .append(conditionExpr).append("\"");
            }else{
                out.append("elif ").append(value).append(' ').append(op.name()).append(' ').append(compare);
            }
        }
    }

    public static class ElseStatement extends SugarStatement{
        @Override public void build(Table table){}
        @Override public String name(){ return cardText("else", "Else"); }
        @Override public String typeName(){ return "Else"; }
        @Override public void write(StringBuilder out){ out.append("else"); }
    }

    public static class FuncDefStatement extends BeginStatement{
        public String name = "func";
        public String params = "";

        @Override
        public void build(Table table){
            table.add(text("func.def", "func")).self(c -> hint(c, "func.def"));
            field(table, name, value -> name = value).width(90f);
            table.add("(");
            TextField paramsField = field(table, params, value -> params = value).width(130f).get();
            paramsField.setMessageText(text("func.params.hint", "a,b"));
            table.add(")");
            foldControlRow(table);
        }

        @Override public String name(){ return cardText("func.def", "Func Def"); }
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
            table.add(text("func.call", "call")).self(c -> hint(c, "func.call"));
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

        @Override public String name(){ return cardText("func.call", "Func Call"); }
        @Override public String typeName(){ return "FuncCall"; }

        @Override
        public void write(StringBuilder out){
            // The result slot is always written ("~" when absent): LParser reuses a static
            // token array, so an omitted trailing token cannot be told apart from a stale one.
            // Quotes inside the args are escaped so they cannot cut the string token short.
            out.append("funccall ").append(name).append(" \"").append(escapeQuoted(args)).append("\" ").append(optional(result));
        }
    }

    public static class ReturnStatement extends SugarStatement{
        /** Return expression; empty means a void (no value) return. */
        public String expr = "";

        @Override
        public void build(Table table){
            table.add(text("func.return", "return")).self(c -> hint(c, "func.return"));
            // 返回值：完整表达式，高亮显示，点击进入编辑
            table.add(new ExpressionEditor(expr, text("func.return.hint", "value"), value -> expr = value))
                .growX().padLeft(4f);
        }

        @Override public String name(){ return cardText("func.return", "Return"); }
        @Override public String typeName(){ return "Return"; }

        @Override
        public void write(StringBuilder out){
            // Always quoted so the void form is unambiguous: LParser reuses a static token
            // array, so a bare "return" line cannot be told apart from a stale token.
            // Quotes inside the expression are escaped so they cannot cut the string token short.
            out.append("return \"").append(escapeQuoted(expr)).append('"');
        }
    }

    /** 数组声明卡：把内存块的一段地址登记为命名数组（纯编译期元数据）。卡片本身
     *  不产出任何 mlog 行（lower 时剥离，编译产物保持纯原版指令）；表达式下标
     *  {@code buf[i]} / {@code buf[i] = x} 经 {@link logicsugar.assist.expr.ArrayRegistry}
     *  解析为原版 {@code read}/{@code write}。v0 仅支持整数字面量的 base/size。 */
    public static class ArrayStatement extends SugarStatement{
        /** 表达式中使用的数组名。 */
        public String array = "buf";
        /** 承载数据的内存块变量名（如 cell1）。 */
        public String memory = "cell1";
        /** 数组起始物理地址（非负整数字面量）。 */
        public String base = "0";
        /** 数组容量（≥1 整数字面量）。 */
        public String size = "8";

        @Override
        public void build(Table table){
            table.add(text("array.card", "Array")).self(c -> hint(c, "array.name"));
            field(table, array, value -> array = value).width(70f);
            table.add(text("array.memory", "mem")).self(c -> hint(c, "array.memory"));
            field(table, memory, value -> memory = value).width(70f);
            table.add(text("array.base", "base")).self(c -> hint(c, "array.base"));
            field(table, base, value -> base = value).width(45f);
            table.add(text("array.size", "size")).self(c -> hint(c, "array.size"));
            field(table, size, value -> size = value).width(45f);
        }

        @Override public String name(){ return cardText("array.card", "Array"); }
        @Override public String typeName(){ return "Array"; }

        @Override
        public void write(StringBuilder out){
            // 固定 token 数（空槽位 "~" 占位）：LParser 复用静态 token 数组，缺尾 token 无法与残值区分
            out.append("array ").append(optional(array)).append(' ').append(optional(memory)).append(' ')
                .append(optional(base)).append(' ').append(optional(size));
        }
    }

    /** 二维数组（矩阵）声明卡：把内存块的一段行主序区间 [base, base+rows*cols) 登记为
     *  命名矩阵（纯编译期元数据，卡片本身不产出 mlog 行）。表达式 {@code m[i][j]} 读、
     *  {@code m[i][j] = x} 写经 {@link logicsugar.assist.expr.ArrayRegistry} 换算为
     *  原版 {@code read}/{@code write}：物理地址 = base + i*cols + j。 */
    public static class MatrixStatement extends SugarStatement{
        /** 表达式中使用的矩阵名。 */
        public String matrix = "mat";
        /** 承载数据的内存块变量名（如 cell1）。 */
        public String memory = "cell1";
        /** 矩阵起始物理地址（非负整数字面量）。 */
        public String base = "0";
        /** 行数（≥1 整数字面量）。 */
        public String rows = "2";
        /** 列数（≥1 整数字面量）。 */
        public String cols = "2";

        @Override
        public void build(Table table){
            table.add(text("matrix.card", "Matrix")).self(c -> hint(c, "matrix.name"));
            field(table, matrix, value -> matrix = value).width(70f);
            table.add(text("array.memory", "mem")).self(c -> hint(c, "array.memory"));
            field(table, memory, value -> memory = value).width(70f);
            table.add(text("array.base", "base")).self(c -> hint(c, "array.base"));
            field(table, base, value -> base = value).width(45f);
            table.add(text("matrix.rows", "rows")).self(c -> hint(c, "matrix.rows"));
            field(table, rows, value -> rows = value).width(45f);
            table.add(text("matrix.cols", "cols")).self(c -> hint(c, "matrix.cols"));
            field(table, cols, value -> cols = value).width(45f);
        }

        @Override public String name(){ return cardText("matrix.card", "Matrix"); }
        @Override public String typeName(){ return "Matrix"; }

        @Override
        public void write(StringBuilder out){
            // 固定 token 数（空槽位 "~" 占位）：LParser 复用静态 token 数组，缺尾 token 无法与残值区分
            out.append("matrix ").append(optional(matrix)).append(' ').append(optional(memory)).append(' ')
                .append(optional(base)).append(' ').append(optional(rows)).append(' ').append(optional(cols));
        }
    }

    /** 数组初始化卡：{@code arrayinit <name> <v0>…<v7>}，卡片位置即初始化位置。
     *  只接受数字字面量（整数/小数，可带负号），{@code ~} 表示跳过该槽；lower 时在该位置
     *  发射 {@code write <v> <memory> <base+k>}（卡片本身不产出非原版行）。 */
    public static class ArrayInitStatement extends SugarStatement{
        /** 被初始化的数组名（必须已声明）。 */
        public String array = "buf";
        /** 8 个槽位值；空串表示跳过（写 "~"）。 */
        public String[] values = new String[8];

        public ArrayInitStatement(){
            java.util.Arrays.fill(values, "");
        }

        @Override
        public void build(Table table){
            table.add(text("arrayinit.card", "Array Init")).self(c -> hint(c, "arrayinit.name"));
            field(table, array, value -> array = value).width(70f);
            for(int i = 0; i < values.length; i++){
                final int index = i;
                if(i % 4 == 0) table.row();
                table.add(String.valueOf(i)).padLeft(i % 4 == 0 ? 4 : 2).color(table.color);
                field(table, values[index], value -> values[index] = value).width(45f).pad(2f);
            }
        }

        @Override public String name(){ return cardText("arrayinit.card", "Array Init"); }
        @Override public String typeName(){ return "ArrayInit"; }

        @Override
        public void write(StringBuilder out){
            // 固定 token 数（空槽位 "~" 占位）：LParser 复用静态 token 数组，缺尾 token 无法与残值区分
            out.append("arrayinit ").append(optional(array));
            for(String value : values){
                out.append(' ').append(optional(value));
            }
        }
    }

    public static class BreakStatement extends SugarStatement{
        @Override public void build(Table table){}
        @Override public String name(){ return cardText("break", "Break"); }
        @Override public String typeName(){ return "Break"; }
        @Override public void write(StringBuilder out){ out.append("break"); }
    }

    public static class ContinueStatement extends SugarStatement{
        @Override public void build(Table table){}
        @Override public String name(){ return cardText("continue", "Continue"); }
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
        if(result.variable == null || result.variable.isEmpty()){
            throw new IllegalArgumentException("Invalid forbegin statement: missing variable");
        }
        result.initial = optionalValue(tokens[2]);
        result.step = optionalValue(tokens[3]);
if(("expr".equals(tokens[4]) || "exprsc".equals(tokens[4])) && tokens[5].length() >= 2 && tokens[5].charAt(0) == '"'){
            result.expressionMode = true;
            result.shortCircuitMode = "exprsc".equals(tokens[4]);
            result.conditionExpr = stripQuotes(tokens[5]);
            result.destIndex = parseDestIndex(tokens[6]);
        }else{
            // Same guard as parseIfBegin: a null/garbage op must fail with a clean error instead
            // of a raw valueOf NPE. LParser passes no token count, so a stale op name that is
            // itself a valid ConditionOp is indistinguishable and parses as-is.
            ConditionOp op = parseConditionOp(tokens[4]);
            if(op == null) throw new IllegalArgumentException("Invalid forbegin condition operator: '" + tokens[4] + "'");
            result.op = op;
            result.compare = tokens[5];
            result.destIndex = parseDestIndex(tokens[6]);
        }
        result.collapsed = collapsed;
        return result;
    }

    public static LStatement parseWhileBegin(String[] tokens){
        return parseWhileBegin(tokens, false);
    }

    public static LStatement parseWhileBegin(String[] tokens, boolean collapsed){
        WhileBeginStatement result = new WhileBeginStatement();
        // "whilebegin expr "<cond>" <destIndex>" — the quoted expression distinguishes it
        // from a legacy variable literally named "expr".
        if(("expr".equals(tokens[1]) || "exprsc".equals(tokens[1])) && tokens[2].length() >= 2 && tokens[2].charAt(0) == '"'){
            result.expressionMode = true;
            result.shortCircuitMode = "exprsc".equals(tokens[1]);
            result.conditionExpr = stripQuotes(tokens[2]);
            result.destIndex = parseDestIndex(tokens[3]);
        }else{
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
        result.destIndex = parseDestIndex(tokens[2]);
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
        // "ifbegin expr \"<cond>\" <destIndex>" — require the quoted expression so an old
        // save whose variable is literally named "expr" is not silently re-parsed as an
        // expression condition (e.g. "ifbegin expr lessThan 5 4").
        if(("expr".equals(tokens[1]) || "exprsc".equals(tokens[1])) && tokens.length > 2 && tokens[2].length() >= 2 && tokens[2].charAt(0) == '"'){
            result.expressionMode = true;
            result.shortCircuitMode = "exprsc".equals(tokens[1]);
            result.conditionExpr = stripQuotes(tokens[2]);
            result.destIndex = parseDestIndex(tokens[3]);
        }else{
            result.value = tokens[1];
            ConditionOp op = parseConditionOp(tokens[2]);
            if(op == null) throw new IllegalArgumentException("Invalid ifbegin condition operator: '" + tokens[2] + "'");
            result.op = op;
            result.compare = tokens[3];
            result.destIndex = parseDestIndex(tokens[4]);
        }
        result.collapsed = collapsed;
        return result;
    }

    public static LStatement parseElseIf(String[] tokens){
        ElseIfStatement result = new ElseIfStatement();
        // Same quoted-expression guard as parseIfBegin: a variable named "expr" must not
        // be silently re-parsed as an expression condition.
        if(("expr".equals(tokens[1]) || "exprsc".equals(tokens[1])) && tokens.length > 2 && tokens[2].length() >= 2 && tokens[2].charAt(0) == '"'){
            result.expressionMode = true;
            result.shortCircuitMode = "exprsc".equals(tokens[1]);
            result.conditionExpr = stripQuotes(tokens[2]);
        }else{
            ConditionOp op = parseConditionOp(tokens[2]);
            if(op == null) throw new IllegalArgumentException("Invalid elif condition operator: '" + tokens[2] + "'");
            result.value = tokens[1];
            result.op = op;
            result.compare = tokens[3];
        }
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
        if(result.name == null || result.name.isEmpty()){
            throw new IllegalArgumentException("Invalid funcdef statement: missing function name");
        }
        result.params = optionalValue(tokens[2]);
        result.destIndex = parseDestIndex(tokens[3]);
        result.collapsed = collapsed;
        return result;
    }

    public static LStatement parseFuncCall(String[] tokens){
        FuncCallStatement result = new FuncCallStatement();
        result.name = tokens[1];
        if(result.name == null || result.name.isEmpty()){
            throw new IllegalArgumentException("Invalid funccall statement: missing function name");
        }
        result.args = unescapeQuoted(stripQuotes(tokens[2]));
        result.result = optionalValue(tokens[3]);
        return result;
    }

    public static LStatement parseReturn(String[] tokens){
        ReturnStatement result = new ReturnStatement();
        result.expr = unescapeQuoted(stripQuotes(tokens[1]));
        return result;
    }

    public static LStatement parseArray(String[] tokens){
        ArrayStatement result = new ArrayStatement();
        result.array = optionalValue(tokens[1]);
        if(result.array.isEmpty()){
            throw new IllegalArgumentException("Invalid array statement: missing array name");
        }
        result.memory = optionalValue(tokens[2]);
        if(result.memory.isEmpty()){
            throw new IllegalArgumentException("Invalid array statement: missing memory cell");
        }
        result.base = optionalValue(tokens[3]);
        result.size = optionalValue(tokens[4]);
        return result;
    }

    public static LStatement parseMatrix(String[] tokens){
        MatrixStatement result = new MatrixStatement();
        result.matrix = optionalValue(tokens[1]);
        if(result.matrix.isEmpty()){
            throw new IllegalArgumentException("Invalid matrix statement: missing matrix name");
        }
        result.memory = optionalValue(tokens[2]);
        if(result.memory.isEmpty()){
            throw new IllegalArgumentException("Invalid matrix statement: missing memory cell");
        }
        result.base = optionalValue(tokens[3]);
        result.rows = optionalValue(tokens[4]);
        result.cols = optionalValue(tokens[5]);
        return result;
    }

    public static LStatement parseArrayInit(String[] tokens){
        ArrayInitStatement result = new ArrayInitStatement();
        result.array = optionalValue(tokens[1]);
        if(result.array.isEmpty()){
            throw new IllegalArgumentException("Invalid arrayinit statement: missing array name");
        }
        for(int i = 0; i < result.values.length; i++){
            // 自定义解析器拿不到本行的 token 数量（LParser 复用静态 token 数组），
            // 超出本行的槽位读到的可能是残留值；卡片写盘时总是补满 8 个槽位，
            // 因此正常存档的往返始终精确。
            result.values[i] = 2 + i < tokens.length ? optionalValue(tokens[2 + i]) : "";
        }
        return result;
    }

    /** Removes the surrounding quotes that LParser keeps on string tokens. */
    private static String stripQuotes(String value){
        if(value == null || value.isEmpty()) return "";
        if(value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"'){
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** Escapes {@code ~} and {@code "} inside quoted statement tokens (see
     *  {@link #unescapeQuoted}). {@code "} would cut the LParser string token short, and
     *  {@code ~} must be escaped so the sequence is unambiguous. Public because the
     *  decompiler must escape with exactly these rules: any drift between the two sides
     *  would make recompilation verification silently reject valid recoveries. */
    public static String escapeQuoted(String value){
        if(value.indexOf('~') < 0 && value.indexOf('"') < 0) return value;
        StringBuilder out = new StringBuilder(value.length() + 8);
        for(int i = 0; i < value.length(); i++){
            char c = value.charAt(i);
            if(c == '~'){
                out.append("~~");
            }else if(c == '"'){
                out.append("~q");
            }else{
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Inverts {@link #escapeQuoted}: {@code ~~} becomes {@code ~}, {@code ~q} becomes
     *  {@code "}; any other {@code ~} sequence is kept literal. */
    public static String unescapeQuoted(String value){
        if(value.indexOf('~') < 0) return value;
        StringBuilder out = new StringBuilder(value.length());
        for(int i = 0; i < value.length(); i++){
            char c = value.charAt(i);
            if(c == '~' && i + 1 < value.length()){
                char next = value.charAt(i + 1);
                if(next == '~'){
                    out.append('~');
                    i++;
                }else if(next == 'q'){
                    out.append('"');
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
