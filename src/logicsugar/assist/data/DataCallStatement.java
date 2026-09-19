package logicsugar.assist.data;

import arc.scene.ui.layout.Table;
import logicsugar.assist.expr.ExpressionEditor;
import mindustry.logic.LCategory;
import mindustry.logic.LAssembler;
import mindustry.logic.LStatement;
import mindustry.logic.SugarStatements;

import java.util.Locale;

/**
 * A persistent, editable card for one data intrinsic call.  The card keeps the
 * source-level call in the Sugar carrier; {@link mindustry.logic.SugarFunctions}
 * lowers it through the same ExprCompiler path used by expression statements.
 */
public class DataCallStatement extends SugarStatements.SugarStatement{
    public static final String TOKEN = "datacall";

    public String operation = "stack_push";
    public String destination = "result";
    public String arguments = "s, 1";
    private transient DataModule.PaletteCall palette;

    public DataCallStatement(){
    }

    public DataCallStatement(DataModule.PaletteCall palette){
        applyPalette(palette);
    }

    private void applyPalette(DataModule.PaletteCall value){
        palette = value;
        if(value != null){
            operation = value.name;
            destination = value.destination;
            arguments = value.arguments;
        }
    }

    public DataModule.PaletteCall palette(){
        DataModule.PaletteCall current = DataModules.paletteCall(operation);
        return current == null ? palette : current;
    }

    /**
     * 卡片对外使用的操作名：旧名（v5.1 及更早的 carrier）统一显示成规范新名，卡片正文、
     * 悬停提示键、卡片标题与 {@link #write} 写回载体的名字都走这里，避免旧名残留。
     */
    public String canonicalOperation(){
        return DataModules.canonicalOperation(operation);
    }

    @Override
    public void build(Table table){
        // Each palette entry is a distinct operation block. Keeping the operation fixed avoids
        // a generic expression-like card whose title/category can drift after editing.
        DataModule.PaletteCall call = palette();
        boolean returnsValue = call == null || call.returnsValue;
        if(returnsValue){
            field(table, destination, value -> destination = value).width(78f);
            table.add(" = ");
        }
        table.add(canonicalOperation() + "(").self(c -> hint(c, operationHintKey("operation")));
        table.add(new ExpressionEditor(arguments, "data, value", value -> arguments = value))
            .growX().minWidth(90f);
        table.add(")").self(c -> hint(c, operationHintKey("arguments")));
    }

    private static String cardText(String op, String fallback){
        try{
            return SugarStatements.cardsLocalized()
                ? arc.Core.bundle.get("logicsugar.datacall." + op, fallback) : fallback;
        }catch(Throwable ignored){
            return fallback;
        }
    }

    @Override public String name(){
        // 关闭卡片本地化时 name() 直接显示 fallback，因此 fallback 也必须是规范新名
        String op = canonicalOperation();
        return cardText(op, op);
    }
    @Override public String typeName(){
        String op = canonicalOperation();
        if(op == null || op.isEmpty()) return TOKEN;
        // SugarLogicDialog derives its add-palette tooltip from typeName().  Keep the
        // generic token for malformed cards, and select the per-operation key for
        // registered cards (e.g. logicsugar.lst.datacall.stack_pop).
        return TOKEN + "." + op.toLowerCase(Locale.ROOT);
    }
    @Override public LCategory category(){
        DataModule.PaletteCall call = palette();
        return call == null ? SugarStatements.dataStructures : call.category;
    }

    @Override
    public void write(StringBuilder out){
        out.append(TOKEN).append(' ').append(optional(canonicalOperation())).append(' ')
            .append(optional(destination)).append(' ').append('"')
            .append(SugarStatements.escapeQuoted(arguments == null ? "" : arguments))
            .append('"');
    }

    private static String optional(String value){
        return value == null || value.isEmpty() ? "~" : value;
    }

    private String operationHintKey(String field){
        String op = canonicalOperation();
        op = op == null ? "" : op.toLowerCase(Locale.ROOT);
        return DataModules.paletteCall(op) == null ? "datacall." + field : "datacall." + op;
    }

    @Override
    public LStatement copy(){
        DataCallStatement result = new DataCallStatement();
        result.operation = operation;
        result.destination = destination;
        result.arguments = arguments;
        result.palette = palette;
        return result;
    }

    public static LStatement parse(String[] tokens){
        DataCallStatement result = new DataCallStatement();
        // 旧名（v5.1 及更早的载体）在解析时归一化成规范新名：卡片正文、悬停提示键与下一次
        // 保存写出的载体都只出现新名，可执行流不变（见 DataModules.canonicalOperation）。
        result.operation = DataModules.canonicalOperation(optionalValue(tokens, 1));
        result.destination = optionalValue(tokens, 2);
        result.arguments = unquote(tokens, 3);
        DataModule.PaletteCall palette = DataModules.paletteCall(result.operation);
        result.palette = palette;
        if(result.operation.isEmpty()) throw new IllegalArgumentException("Invalid datacall: missing operation");
        return result;
    }

    private static String optionalValue(String[] tokens, int index){
        if(tokens == null || index >= tokens.length || tokens[index] == null || "~".equals(tokens[index])) return "";
        return tokens[index];
    }

    private static String unquote(String[] tokens, int index){
        String value = optionalValue(tokens, index);
        if(value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"'){
            value = value.substring(1, value.length() - 1);
        }
        return SugarStatements.unescapeQuoted(value);
    }
}
