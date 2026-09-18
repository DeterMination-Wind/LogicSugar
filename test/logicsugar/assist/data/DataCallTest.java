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
        check(!DataModules.paletteCall("fill").returnsValue, "fill cards must be statement-like");
        check(DataModules.paletteCall("copy").arguments.equals("dst, src"), "copy defaults must name source and destination");
        check(!DataModules.paletteCall("sortasc").returnsValue && !DataModules.paletteCall("reverse").returnsValue,
            "in-place array transforms must not expose a result field");
        for(String operation : new String[]{"fill", "copy", "sortasc", "sortdesc", "reverse", "swap",
            "sclear", "qclear", "dclear", "mapclear", "uclear",
            // v5 API: constant results carry no information, so these are void cards too
            "bset", "bclr", "cshead"}){
            check(!DataModules.paletteCall(operation).returnsValue,
                operation + " must be represented as a void palette card");
        }
        check(DataModules.paletteCall("spop").arguments.equals("s"), "stack defaults must match stack declaration");
        check(DataModules.paletteCall("spop").returnsValue, "pop cards must expose their value");
        check(!DataModules.paletteCall("sclear").returnsValue && !DataModules.paletteCall("mapclear").returnsValue
            && !DataModules.paletteCall("uclear").returnsValue, "clear cards must not expose a result field");

        DataCallStatement fillCard = new DataCallStatement(DataModules.paletteCall("array_fill"));
        StringBuilder fillCardSource = new StringBuilder();
        fillCard.write(fillCardSource);
        check(fillCardSource.toString().equals("datacall array_fill ~ \"buf, value\""),
            "new void cards must serialize an empty destination: " + fillCardSource);
        check(new DataCallStatement(DataModules.paletteCall("array_fill")).typeName().equals("datacall.array_fill"),
            "operation-specific datacall tooltip type missing");

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
            + "datacall fill ~ \"buf, 9\"\n";
        String fillCompiled = SugarCompiler.compile(fillSugar, SugarCompiler.FuncMode.normal, null, null);
        String fillProduct = SugarCompiler.stripMarkers(fillCompiled);
        check(fillProduct.contains("__ls_func___ls_builtin_arrfill_entry:"),
            "fill card did not lower through the shared array fill builtin:\n" + fillProduct);
        check(!fillProduct.contains("datacall "), "fill datacall leaked into product:\n" + fillProduct);
        check(SugarCompiler.restore(fillCompiled).equals(fillSugar), "fill card did not survive carrier restore");

        String voidSugar = "stack s cell1 0 4\n"
            + "datacall sclear ~ \"s\"\n";
        String voidCompiled = SugarCompiler.compile(voidSugar, SugarCompiler.FuncMode.normal, null, null);
        String voidProduct = SugarCompiler.stripMarkers(voidCompiled);
        check(voidProduct.contains("__ls_stk_s_top"), "void clear card did not lower safely:\n" + voidProduct);
        check(SugarCompiler.verifyRestore(voidCompiled, voidSugar), "void card carrier verification failed");

        checkCompileThrows("stack s cell1 0 4\n"
            + "datacall spush ~ \"s, 7\"\n", "requires a destination variable");

        // A dedicated operation card must choose its intrinsic even if ordinary expressions
        // would let a same-named local function shadow it.  Test both lowering modes because
        // normal mode needs builtin reachability/hoisting and inline expands at the call site.
        String shadowSugar = "stack s cell1 0 4\n"
            + "funcdef spush a,b 4\n"
            + "set hijacked 1\n"
            + "return \"99\"\n"
            + "blockend\n"
            + "datacall spush pushed \"s, 7\"\n";
        for(SugarCompiler.FuncMode mode : SugarCompiler.FuncMode.values()){
            String shadowCompiled = SugarCompiler.compile(shadowSugar, mode, null, null);
            String shadowProduct = SugarCompiler.stripMarkers(shadowCompiled);
            check(shadowProduct.contains("__ls_builtin_stkpush"),
                "datacall spush did not force the stack intrinsic in " + mode + ":\n" + shadowProduct);
            check(!shadowProduct.contains("set hijacked 1"),
                "same-named user function hijacked datacall spush in " + mode + ":\n" + shadowProduct);
            check(SugarCompiler.verifyRestore(shadowCompiled, shadowSugar),
                "shadowed datacall did not survive carrier verification in " + mode);
        }

        // Only the fixed root is forced. A user function nested in a card argument must
        // still be analyzed, retained and lowered normally.
        String nestedSugar = "stack s cell1 0 4\n"
            + "funcdef uservalue x 4\n"
            + "set nestedHit 1\n"
            + "return \"x\"\n"
            + "blockend\n"
            + "datacall spush pushed \"s, uservalue(7)\"\n";
        for(SugarCompiler.FuncMode mode : SugarCompiler.FuncMode.values()){
            String nestedProduct = SugarCompiler.stripMarkers(SugarCompiler.compile(nestedSugar, mode, null, null));
            check(nestedProduct.contains("set nestedHit 1"),
                "nested user function was not retained in " + mode + ":\n" + nestedProduct);
            check(nestedProduct.contains("__ls_builtin_stkpush"),
                "root stack intrinsic was lost in " + mode + ":\n" + nestedProduct);
        }

        // Bounds assertions emitted from a card in a function must use that function call's
        // private temp namespace; a bare _0 would be clobbered by surrounding expressions.
        String dynamicSugar = "array buf cell1 0 4\n"
            + "stack s cell1 4 4\n"
            + "funcdef consume i 5\n"
            + "datacall spush pushed \"s, buf[i + 1]\"\n"
            + "return \"pushed\"\n"
            + "blockend\n"
            + "funccall consume 1 result\n";
        for(SugarCompiler.FuncMode mode : SugarCompiler.FuncMode.values()){
            String dynamicCompiled = SugarCompiler.compile(dynamicSugar, mode, null, null,
                SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.emit);
            String dynamicProduct = SugarCompiler.stripMarkers(dynamicCompiled);
            check(dynamicProduct.contains("assertBounds integer ~ 0 lessThanEq __ls_"),
                "datacall bounds assert was not namespaced in " + mode + ":\n" + dynamicProduct);
            check(!dynamicProduct.contains("lessThanEq _0 lessThanEq"),
                "datacall bounds assert kept a bare temporary in " + mode + ":\n" + dynamicProduct);
            check(SugarCompiler.verifyRestore(dynamicCompiled, dynamicSugar),
                "dynamic datacall carrier verification failed in " + mode);
        }
        apiVersionVerification();
        System.out.println("DataCallTest passed");
    }

    /**
     * The v5 API changed failure values (0 to -1). Save written before it must still pass the
     * restore gate: the gate re-lowers them with the legacy API and compares streams.
     */
    private static void apiVersionVerification(){
        String sugar = "map m cell1 0 4\n"
            + "datacall mapclear ~ \"m\"\n"
            + "datacall mapdel r \"m, 1\"\n";
        String v2 = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null);

        SugarCompiler.Api previous = SugarCompiler.enterApi(SugarCompiler.Api.v1);
        String v1;
        try{
            v1 = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null);
        }finally{
            SugarCompiler.leaveApi(previous);
        }
        String v1Save = v1.replace("# @logic-sugar-v2", "# @logic-sugar-v1");

        check(SugarCompiler.storedFormat(v2) == 2 && SugarCompiler.storedFormat(v1Save) == 1,
            "marker versions must differ between the v2 and v1 lowering");
        check(!SugarCompiler.matchesStoredStream(v2, v1Save),
            "v1 and v2 failure values must not compare equal without the legacy pass");
        check(SugarCompiler.verifyRestore(v1Save, sugar),
            "a pre-v5 save must verify through the legacy lowering API:\n" + v1Save);
        check(SugarCompiler.verifyRestore(v2, sugar), "a v2 save must verify through the v2 API");

        // Tampered v1 save: the legacy pass must still reject a stream that does not reproduce.
        String tampered = v1Save.replace("set r ", "set r 1\nset r ");
        check(!SugarCompiler.verifyRestore(tampered, sugar),
            "a tampered v1 save must not be accepted");
    }

    private static void checkCompileThrows(String sugar, String expected){
        try{
            SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null);
            throw new AssertionError("expected compile failure containing '" + expected + "'");
        }catch(IllegalArgumentException exception){
            check(exception.getMessage().contains(expected),
                "unexpected compile failure: " + exception.getMessage());
        }
    }

    private static void check(boolean value, String message){
        if(!value) throw new AssertionError(message);
    }
}
