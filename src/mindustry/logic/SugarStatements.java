package mindustry.logic;

import arc.Core;
import arc.graphics.Color;
import arc.scene.ui.layout.Table;
import mindustry.graphics.Pal;
import mindustry.logic.LCanvas.JumpButton;
import mindustry.logic.LCanvas.StatementElem;
import mindustry.logic.LExecutor.LInstruction;
import mindustry.logic.LExecutor.NoopI;
import mindustry.logic.LStatements.JumpStatement;

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

    private abstract static class SugarStatement extends LStatement{
        @Override
        public LInstruction build(LAssembler builder){
            return new NoopI();
        }

        @Override
        public LCategory category(){
            return LCategory.control;
        }
    }

    private abstract static class BeginStatement extends SugarStatement{
        public transient StatementElem dest;
        public int destIndex = -1;

        protected abstract Class<? extends LStatement> endType();

        protected void arrow(Table table){
            table.add().growX();
            table.add(new TypedJumpButton(() -> dest, target -> {
                dest = target != null && endType().isInstance(target.st) ? target : null;
            }, elem)).size(30f).right().padLeft(-8f);
        }

        @Override
        public void setupUI(){
            if(elem != null && destIndex >= 0 && destIndex < elem.parent.getChildren().size){
                StatementElem candidate = (StatementElem)elem.parent.getChildren().get(destIndex);
                dest = endType().isInstance(candidate.st) ? candidate : null;
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
        TypedJumpButton(arc.func.Prov<StatementElem> getter, arc.func.Cons<StatementElem> setter, StatementElem elem){
            super(getter, setter, elem);
            update(() -> {
                Color color = getter.get() == null ? Pal.remove : Color.white;
                setColor(color);
                getStyle().imageUpColor = color;
            });
        }
    }

    public static class ForBeginStatement extends BeginStatement{
        public String variable = "i", initial = "0", step = "1", compare = "10";
        public ConditionOp op = ConditionOp.lessThanEq;

        @Override
        public void build(Table table){
            table.add(text("for.variable", "for"));
            field(table, variable, value -> variable = value).width(85f);
            table.add(text("for.from", "from"));
            field(table, initial, value -> initial = value).width(70f);
            table.add(text("for.step", "step"));
            field(table, step, value -> step = value).width(70f);
            row(table);
            table.add(text("condition", "while"));
            Table condition = table.table(t -> rebuildCondition(t)).get();
            arrow(table);
        }

        private void rebuildCondition(Table table){
            table.clearChildren();
            table.setColor(elem == null ? Pal.logicControl : elem.color);
            JumpStatement.addOp(this, table, op, value -> {
                op = value;
                rebuildCondition(table);
            }, variable, value -> variable = value, compare, value -> compare = value);
        }

        @Override protected Class<? extends LStatement> endType(){ return ForEndStatement.class; }
        @Override public String name(){ return text("for.begin", "For Begin"); }
        @Override public String typeName(){ return "ForBegin"; }

        @Override
        public void write(StringBuilder out){
            out.append("forbegin ").append(variable).append(' ').append(optional(initial)).append(' ').append(optional(step)).append(' ')
                .append(op.name()).append(' ').append(compare).append(' ').append(destIndex);
        }
    }

    public static class ForEndStatement extends SugarStatement{
        @Override public void build(Table table){}
        @Override public String name(){ return text("for.end", "For End"); }
        @Override public String typeName(){ return "ForEnd"; }
        @Override public void write(StringBuilder out){ out.append("forend"); }
    }

    public static class WhileBeginStatement extends BeginStatement{
        public String condition = "true";

        @Override
        public void build(Table table){
            table.add(text("condition", "condition"));
            field(table, condition, value -> condition = value);
            arrow(table);
        }

        @Override protected Class<? extends LStatement> endType(){ return WhileEndStatement.class; }
        @Override public String name(){ return text("while.begin", "While Begin"); }
        @Override public String typeName(){ return "WhileBegin"; }
        @Override public void write(StringBuilder out){ out.append("whilebegin ").append(condition).append(' ').append(destIndex); }
    }

    public static class WhileEndStatement extends SugarStatement{
        @Override public void build(Table table){}
        @Override public String name(){ return text("while.end", "While End"); }
        @Override public String typeName(){ return "WhileEnd"; }
        @Override public void write(StringBuilder out){ out.append("whileend"); }
    }

    public static class SwitchBeginStatement extends BeginStatement{
        public String value = "i";

        @Override
        public void build(Table table){
            table.add(text("switch.value", "switch"));
            field(table, value, result -> value = result);
            arrow(table);
        }

        @Override protected Class<? extends LStatement> endType(){ return SwitchEndStatement.class; }
        @Override public String name(){ return text("switch.begin", "Switch Start"); }
        @Override public String typeName(){ return "SwitchBegin"; }
        @Override public void write(StringBuilder out){ out.append("switchbegin ").append(value).append(' ').append(destIndex); }
    }

    public static class CaseStatement extends SugarStatement{
        public String value = "0";
        @Override public void build(Table table){ table.add(text("case.value", "case")); field(table, value, result -> value = result); }
        @Override public String name(){ return text("case", "Case"); }
        @Override public String typeName(){ return "Case"; }
        @Override public void write(StringBuilder out){ out.append("case ").append(value); }
    }

    public static class BreakStatement extends SugarStatement{
        @Override public void build(Table table){}
        @Override public String name(){ return text("break", "Break"); }
        @Override public String typeName(){ return "Break"; }
        @Override public void write(StringBuilder out){ out.append("break"); }
    }

    public static class SwitchEndStatement extends SugarStatement{
        @Override public void build(Table table){}
        @Override public String name(){ return text("switch.end", "Switch End"); }
        @Override public String typeName(){ return "SwitchEnd"; }
        @Override public void write(StringBuilder out){ out.append("switchend"); }
    }

    public static LStatement parseForBegin(String[] tokens){
        ForBeginStatement result = new ForBeginStatement();
        result.variable = tokens[1];
        result.initial = optionalValue(tokens[2]);
        result.step = optionalValue(tokens[3]);
        result.op = ConditionOp.valueOf(tokens[4]);
        result.compare = tokens[5];
        result.destIndex = Integer.parseInt(tokens[6]);
        return result;
    }

    public static LStatement parseWhileBegin(String[] tokens){
        WhileBeginStatement result = new WhileBeginStatement();
        result.condition = tokens[1];
        result.destIndex = Integer.parseInt(tokens[2]);
        return result;
    }

    public static LStatement parseSwitchBegin(String[] tokens){
        SwitchBeginStatement result = new SwitchBeginStatement();
        result.value = tokens[1];
        result.destIndex = Integer.parseInt(tokens[2]);
        return result;
    }

    public static LStatement parseCase(String[] tokens){
        CaseStatement result = new CaseStatement();
        result.value = tokens[1];
        return result;
    }
}
