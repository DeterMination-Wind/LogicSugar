package logicsugar.assist;

import arc.Core;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.logic.LExecutor;
import mindustry.logic.LVar;
import mindustry.logic.LogicDialog;

import java.lang.reflect.Field;

/**
 * Dialog buttons that copy the inspected processor's variables (all of them, unrounded,
 * tab-separated for spreadsheet pasting) and its print buffer to the clipboard. Ported from
 * the upstream MlogAssertions mod's LogicDialogAddon, but living inside SugarLogicDialog
 * (which replaces {@code Vars.ui.logic}) instead of hooking it externally.
 *
 * <p>The clipboard format is a fixed data layout (TSV with an ASCII header), not UI text:
 * it is meant to be pasted into a spreadsheet and sorted, so it is intentionally not
 * localized — only the button labels and the confirmation toast go through the bundle.</p>
 *
 * <p>{@link LogicDialog#executor} is package-private, so it is read through reflection
 * (cross-loader package access would throw IllegalAccessError — same pattern as
 * SugarLogicDialog.privilegedField). If reflection fails, the buttons simply do not
 * appear: this is a convenience feature, so it degrades instead of crashing the dialog.</p>
 */
public final class VarClipboard{
    public static final String copyVarsButtonName = "logicsugar-copyvars";
    public static final String copyBufferButtonName = "logicsugar-copybuffer";

    private static final Field executorField = resolveExecutorField();

    private VarClipboard(){}

    private static Field resolveExecutorField(){
        try{
            Field field = LogicDialog.class.getDeclaredField("executor");
            field.setAccessible(true);
            return field;
        }catch(Exception e){
            Log.warn("LogicSugar: cannot access LogicDialog.executor, variable copy disabled: @", e);
            return null;
        }
    }

    /** Appends the copy buttons to the dialog's button row when missing. No-op when the
     *  executor field is unavailable (reflection degraded) or this is a library-file
     *  editing session (no processor to inspect). */
    public static void addButtons(Table buttons, LogicDialog dialog){
        if(executorField == null || executor(dialog) == null) return;
        if(buttons.find(copyVarsButtonName) == null){
            buttons.button("@logicsugar.copyvars", Icon.copy, () -> {
                LExecutor executor = executor(dialog);
                if(executor == null) return;
                Core.app.setClipboardText(variablesToText(executor.vars));
                Vars.ui.showInfoFade("@logicsugar.copied");
            }).name(copyVarsButtonName);
        }
        if(buttons.find(copyBufferButtonName) == null){
            buttons.button("@logicsugar.copybuffer", Icon.copy, () -> {
                LExecutor executor = executor(dialog);
                if(executor == null) return;
                Core.app.setClipboardText(bufferToText(executor.textBuffer));
                Vars.ui.showInfoFade("@logicsugar.copied");
            }).name(copyBufferButtonName);
        }
    }

    /** Full-precision, unrounded TSV dump of the executor's variables, sorted by name for
     *  spreadsheet inspection. */
    public static String variablesToText(LVar[] vars){
        StringBuilder sbr = new StringBuilder(500);
        sbr.append("Variable\tValue\n");
        LVar[] sorted = new LVar[vars.length];
        System.arraycopy(vars, 0, sorted, 0, vars.length);
        java.util.Arrays.sort(sorted, java.util.Comparator.comparing(v -> v.name));
        for(LVar v : sorted){
            sbr.append(v.name).append('\t').append(valueToText(v)).append('\n');
        }
        return sbr.toString();
    }

    private static String valueToText(LVar v){
        return v.isobj ? LExecutor.PrintI.toString(v.objval) : v.numval + "";
    }

    private static String bufferToText(CharSequence buffer){
        return "Printbuffer contents:\n" + buffer;
    }

    private static LExecutor executor(LogicDialog dialog){
        try{
            return (LExecutor)executorField.get(dialog);
        }catch(Exception e){
            Log.warn("LogicSugar: cannot read LogicDialog.executor: @", e);
            return null;
        }
    }
}
