package logicsugar.assist.data;

import logicsugar.LogicSugarMod;
import mindustry.gen.LogicIO;
import mindustry.logic.LAssembler;
import mindustry.logic.LStatement;
import mindustry.logic.SugarCompiler;
import mindustry.logic.SugarStatements;

/** Regression checks for persistent intrinsic operation cards. */
public final class DataCallTest{
    private DataCallTest(){}

    public static void main(String[] args){
        LogicSugarMod.registerStatements();
        String[] names = {
            "sum", "avg", "min", "max", "count", "indexof", "fill", "copy", "sortasc",
            "sortdesc", "reverse", "replace", "swap", "bsearch",
            "spush", "spop", "speek", "ssize", "sclear",
            "qpush", "qpop", "qpeek", "qsize", "qclear",
            "dpushf", "dpushb", "dpopf", "dpopb", "dpeekf", "dpeekb", "dsize", "dclear",
            "bset", "bclr", "btest", "bcount",
            "mapset", "mapget", "maphas", "mapdel", "mapsize", "mapclear",
            "uadd", "uhas", "udel", "usize", "uclear",
            "lappend", "lget", "lset", "linsert", "lremove", "lfind", "lsize",
            "hpush", "hpop", "hsize",
            "cinit", "cclear", "cnew", "cfree", "cget", "cset", "cnext", "clink",
            "cshead", "chead", "clen"
        };
        for(String name : names) check(DataModules.paletteCall(name) != null, "missing palette call " + name);
        check(names.length == 68 && DataModules.paletteCalls().size() == names.length,
            "palette must expose every intrinsic exactly once: " + DataModules.paletteCalls().keySet());
        long paletteCards = LogicIO.allStatements.select(prov -> prov.get() instanceof DataCallStatement).size;
        check(paletteCards == names.length, "expected 68 data operation cards, got " + paletteCards);
        check(DataModules.paletteCall("spush").category == SugarStatements.stackOps, "stack category missing");
        check(DataModules.paletteCall("qpop").category == SugarStatements.queueOps, "queue category missing");
        check(DataModules.paletteCall("dclear").category == SugarStatements.dequeOps, "deque category missing");
        check(DataModules.paletteCall("sum").category == SugarStatements.arrayAlgo, "array category missing");
        check(DataModules.paletteCall("fill").arguments.equals("buf, value"), "fill defaults must match array declaration");
        check(DataModules.paletteCall("spop").arguments.equals("s"), "stack defaults must match stack declaration");

        check(LogicIO.allStatements.count(prov -> prov.get() instanceof SugarStatements.ArrayInitStatement) == 0,
            "legacy eight-slot arrayinit must be hidden from the palette");
        LStatement legacy = LAssembler.read("arrayinit buf 1 2 ~ ~ ~ ~ ~ ~", true).first();
        check(legacy instanceof SugarStatements.ArrayInitStatement,
            "legacy arrayinit carrier must remain parseable");

        String sugar = "stack s cell1 0 4\n"
            + "datacall spush result \"s, 7\"\n"
            + "datacall ssize result \"s\"\n"
            + "set x result\n";
        String compiled = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null);
        String product = SugarCompiler.stripMarkers(compiled);
        check(!product.contains("datacall "), "datacall leaked into product:\n" + product);
        check(!product.contains("funccall __ls_builtin_"), "builtin call leaked into product:\n" + product);
        check(SugarCompiler.restore(compiled).equals(sugar), "datacall did not survive carrier restore");
        check(SugarCompiler.verifyRestore(compiled, sugar), "datacall carrier verification failed");

        String fillSugar = "array buf cell1 0 4\n"
            + "datacall fill result \"buf, 9\"\n"
            + "set x result\n";
        String fillCompiled = SugarCompiler.compile(fillSugar, SugarCompiler.FuncMode.normal, null, null);
        String fillProduct = SugarCompiler.stripMarkers(fillCompiled);
        check(fillProduct.contains("__ls_func___ls_builtin_arrfill_entry:"),
            "fill card did not lower through the shared array fill builtin:\n" + fillProduct);
        check(!fillProduct.contains("datacall "), "fill datacall leaked into product:\n" + fillProduct);
        check(SugarCompiler.restore(fillCompiled).equals(fillSugar), "fill card did not survive carrier restore");
        System.out.println("DataCallTest passed");
    }

    private static void check(boolean value, String message){
        if(!value) throw new AssertionError(message);
    }
}
