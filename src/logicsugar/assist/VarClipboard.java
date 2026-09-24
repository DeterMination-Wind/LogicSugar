package logicsugar.assist;

import mindustry.logic.LExecutor;
import mindustry.logic.LVar;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Clipboard dumps of the inspected processor's variables and print buffer. Ported from the
 * upstream MlogAssertions mod's LogicDialogAddon.
 *
 * <p>The clipboard format is a fixed data layout (TSV with an ASCII header), not UI text: it is
 * meant to be pasted into a spreadsheet and sorted, so it is intentionally not localized — only
 * the menu labels and the confirmation toast go through the bundle.</p>
 *
 * <p>The two dumps are offered as entries inside the logic dialog's edit menu
 * (see {@code SugarLogicDialog.installInspectionCopy}). They are deliberately not bottom-bar
 * buttons: the bar's cells are fixed-width, and two more of them overflow a narrow window.</p>
 */
public final class VarClipboard{
    public static final String copyVarsButtonName = "logicsugar-copyvars";
    public static final String copyBufferButtonName = "logicsugar-copybuffer";

    private VarClipboard(){}

    /** Full-precision, unrounded TSV dump of the executor's variables, sorted by name for
     *  spreadsheet inspection. */
    public static String variablesToText(LExecutor executor){
        return variablesToText(executor.vars);
    }

    /** Print-buffer dump, headed like the variables dump so a paste is self-describing. */
    public static String bufferToText(LExecutor executor){
        return bufferToText(executor.textBuffer);
    }

    /** Full-precision, unrounded TSV dump of the given variables, sorted by name for
     *  spreadsheet inspection. */
    public static String variablesToText(LVar[] vars){
        StringBuilder sbr = new StringBuilder(500);
        sbr.append("Variable\tValue\n");
        LVar[] sorted = new LVar[vars.length];
        System.arraycopy(vars, 0, sorted, 0, vars.length);
        Arrays.sort(sorted, Comparator.comparing(v -> v.name));
        for(LVar v : sorted){
            sbr.append(v.name).append('\t').append(valueToText(v)).append('\n');
        }
        return sbr.toString();
    }

    private static String valueToText(LVar v){
        return v.isobj ? LExecutor.PrintI.toString(v.objval) : v.numval + "";
    }

    /** Print-buffer dump, headed like the variables dump so a paste is self-describing. */
    public static String bufferToText(CharSequence buffer){
        return "Printbuffer contents:\n" + buffer;
    }
}
