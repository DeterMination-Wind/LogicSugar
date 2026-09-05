package logicsugar.assist;

import mindustry.logic.LVar;

public class VarClipboardSelfTest{
    public static void main(String[] args){
        variablesDumpIsTabSeparatedAndSorted();
        fullPrecisionNumbersArePreserved();
        objectValuesUsePrintFormatting();
        System.out.println("LogicSugar VarClipboard self-test passed.");
    }

    private static void variablesDumpIsTabSeparatedAndSorted(){
        LVar[] vars = {num("zebra", 1), num("alpha", 2)};
        String text = VarClipboard.variablesToText(vars);
        String[] lines = text.split("\n");
        check(lines[0].equals("Variable\tValue"), "missing TSV header: " + lines[0]);
        check(lines[1].startsWith("alpha\t"), "variables not sorted by name: " + lines[1]);
        check(lines[2].startsWith("zebra\t"), "variables not sorted by name: " + lines[2]);
        check(lines.length == 3, "unexpected row count: " + lines.length);
    }

    private static void fullPrecisionNumbersArePreserved(){
        // the unrounded double must survive the dump (0.1 has no exact binary form;
        // a rounded path like "%.2f" would collapse it and defeat spreadsheet debugging)
        LVar[] vars = {num("x", 0.1)};
        check(VarClipboard.variablesToText(vars).contains("x\t0.1"), "full-precision value lost");
        LVar[] big = {num("y", 1.0E300)};
        check(VarClipboard.variablesToText(big).contains("1.0E300"), "large magnitude lost");
    }

    private static void objectValuesUsePrintFormatting(){
        LVar str = num("s", 0);
        str.isobj = true;
        str.objval = "frog";
        LVar nul = num("n", 0);
        nul.isobj = true;
        nul.objval = null;
        String text = VarClipboard.variablesToText(new LVar[]{str, nul});
        check(text.contains("s\tfrog"), "string object not printed raw: " + text);
        check(text.contains("n\tnull"), "null object not printed as null: " + text);
    }

    private static LVar num(String name, double value){
        LVar var = new LVar(name);
        var.isobj = false;
        var.numval = value;
        return var;
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
