package logicsugar.assist.data;

import arc.struct.Seq;
import logicsugar.assist.expr.ArrayBulkIntrinsics;
import logicsugar.assist.expr.ArrayRegistry;
import logicsugar.assist.expr.ExprCompiler;
import mindustry.Vars;
import mindustry.logic.GlobalVars;
import mindustry.logic.LAssembler;
import mindustry.logic.SugarCompiler;
import mindustry.logic.SugarFunctions;
import mindustry.logic.SugarStatements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * F2 数组批量运算自测（{@link ArrayBulkModule} + {@link ArrayBulkIntrinsics}）。
 *
 * <p>三层覆盖：</p>
 * <ul>
 *   <li><b>表达式层</b>：{@code sum/avg/min/max/count/indexof/fill/copy/sortasc/sortdesc}
 *       在 ArrayRegistry 上下文中展开为对 {@code __ls_builtin_arr*} 的 funccall；参数必须是
 *       已声明数组名（矩阵按行主序摊平），未声明名字/错误实参个数/copy 长度不等都在编译期报错；
 *       1 参 min/max 是数组运算，2 参仍是原版内置，len 行为不变。</li>
 *   <li><b>编译层</b>：内置函数库并入 analyze 的 LibraryIndex（normal 模式共享一份子程序、
 *       未使用不进产物），不进入 {@code __ls_lib} 载体，产物纯原版指令，载体往返可验证。</li>
 *   <li><b>编辑器可见性</b>：内置函数参数表对 paramsOf 可见。</li>
 * </ul>
 */
public class ArrayBulkTest{
    public static void main(String[] args){
        SugarStatements.installParsers();
        Vars.logicVars = new GlobalVars();
        DataModules.clearModules();
        DataModules.register(new ArrayBulkModule());

        expressionExpansions();
        matrixFlattening();
        arityDispatchAndBuiltins();
        argumentErrors();
        compilerInjectionAndSharing();
        builtinLibraryIsNotPersisted();
        unusedBuiltinsStayOut();
        outputIsPureVanilla();
        roundTripAndVerification();
        editorVisibility();

        DataModules.clearModules();
        System.out.println("LogicSugar ArrayBulk self-test passed.");
    }

    // ===== 表达式层 =====

    private static void expressionExpansions(){
        withRegistry("array buf cell1 0 8", () -> {
            checkLine("funccall __ls_builtin_arrsum \"cell1, 0, 8\" x", textOf(ExprCompiler.compile("x", "sum(buf)")));
            checkLine("funccall __ls_builtin_arravg \"cell1, 0, 8\" x", textOf(ExprCompiler.compile("x", "avg(buf)")));
            checkLine("funccall __ls_builtin_arrmin \"cell1, 0, 8\" x", textOf(ExprCompiler.compile("x", "min(buf)")));
            checkLine("funccall __ls_builtin_arrmax \"cell1, 0, 8\" x", textOf(ExprCompiler.compile("x", "max(buf)")));
            checkLine("funccall __ls_builtin_arrcount \"cell1, 0, 8, 3\" x", textOf(ExprCompiler.compile("x", "count(buf, 3)")));
            checkLine("funccall __ls_builtin_arrindexof \"cell1, 0, 8, i\" x", textOf(ExprCompiler.compile("x", "indexof(buf, i)")));
            checkLine("funccall __ls_builtin_arrfill \"cell1, 0, 8, 5\" x", textOf(ExprCompiler.compile("x", "fill(buf, 5)")));
            checkLine("funccall __ls_builtin_arrsort \"cell1, 0, 8, 1\" x", textOf(ExprCompiler.compile("x", "sortasc(buf)")));
            checkLine("funccall __ls_builtin_arrsort \"cell1, 0, 8, -1\" x", textOf(ExprCompiler.compile("x", "sortdesc(buf)")));
            checkLine("funccall __ls_builtin_arrrev \"cell1, 0, 8\" x", textOf(ExprCompiler.compile("x", "reverse(buf)")));
            checkLine("funccall __ls_builtin_arrrepl \"cell1, 0, 8, 1, 9\" x", textOf(ExprCompiler.compile("x", "replace(buf, 1, 9)")));
            checkLine("funccall __ls_builtin_arrswap \"cell1, 0, i, j\" x", textOf(ExprCompiler.compile("x", "swap(buf, i, j)")));
            checkLine("funccall __ls_builtin_arrbsearch \"cell1, 0, 8, 3\" x", textOf(ExprCompiler.compile("x", "bsearch(buf, 3)")));
            // 大小写不敏感（与 ExprCompiler 的数学内置一致）
            checkLine("funccall __ls_builtin_arrsum \"cell1, 0, 8\" x", textOf(ExprCompiler.compile("x", "SUM(buf)")));
            // 表达式链里与其它运算组合：实参先编译，再发调用
            checkLine("op add _0 i 1\nfunccall __ls_builtin_arrcount \"cell1, 0, 8, _0\" _1\nop mul x _1 2",
                textOf(ExprCompiler.compile("x", "count(buf, i + 1) * 2")));
        });
        withRegistry("array a cell1 0 8\narray b cell1 8 8", () -> {
            checkLine("funccall __ls_builtin_arrcopy \"cell1, 0, cell1, 8, 8\" x", textOf(ExprCompiler.compile("x", "copy(a, b)")));
        });
    }

    private static void matrixFlattening(){
        withRegistry("matrix m cell1 0 2 3", () -> {
            checkLine("funccall __ls_builtin_arrsum \"cell1, 0, 6\" x", textOf(ExprCompiler.compile("x", "sum(m)")));
            checkLine("funccall __ls_builtin_arrsort \"cell1, 0, 6, 1\" x", textOf(ExprCompiler.compile("x", "sortasc(m)")));
        });
        withRegistry("matrix m cell2 10 2 3\narray a cell2 0 6", () -> {
            // 矩阵摊平后与同 size 数组可互相 copy
            checkLine("funccall __ls_builtin_arrcopy \"cell2, 0, cell2, 10, 6\" x", textOf(ExprCompiler.compile("x", "copy(a, m)")));
        });
    }

    /** 1 参 min/max 是数组运算，2 参仍是原版内置；len 行为保持 F1 不变。 */
    private static void arityDispatchAndBuiltins(){
        withRegistry("array buf cell1 0 8", () -> {
            checkLine("op min x a b", textOf(ExprCompiler.compile("x", "min(a, b)")));
            checkLine("op max x a b", textOf(ExprCompiler.compile("x", "max(a, b)")));
            checkLine("op len x 3 4", textOf(ExprCompiler.compile("x", "len(3, 4)")));
            checkLine("op add x 8 0", textOf(ExprCompiler.compile("x", "len(buf)")));
            checkLine("op add x 8 1", textOf(ExprCompiler.compile("x", "len(buf) + 1")));
        });
    }

    private static void argumentErrors(){
        withRegistry("array buf cell1 0 8", () -> {
            checkThrows(() -> ExprCompiler.compile("x", "sum(nosuch)"), "undeclared array");
            checkThrows(() -> ExprCompiler.compile("x", "sum(buf[0])"), "subscript argument");
            checkThrows(() -> ExprCompiler.compile("x", "sum(1 + 2)"), "expression argument");
            checkThrows(() -> ExprCompiler.compile("x", "min(nosuch)"), "min of an undeclared array");
        });
        withRegistry("array a cell1 0 8\narray b cell1 8 4", () -> {
            checkThrows(() -> ExprCompiler.compile("x", "copy(a, b)"), "copy size mismatch");
        });
        withRegistry("array a cell1 0 8", () -> {
            // 错误实参个数不再是 intrinsic：落到普通 funccall，编译期报未定义函数
            checkCompileThrows("array a cell1 0 8\nifbegin expr \"copy(a, a, a) > 0\" 4\nset x 1\nblockend\n",
                "copy with three arguments");
        });
        // 没有数组上下文时给出明确错误（编辑器无画布/纯表达式预览）
        ArrayRegistry previous = ArrayRegistry.enter(ArrayRegistry.empty());
        try{
            checkThrows(() -> ExprCompiler.compile("x", "sum(buf)"), "no array context");
        }finally{
            ArrayRegistry.restore(previous);
        }
    }

    // ===== 编译层 =====

    /** 内置函数被注入：normal 模式两个调用点共享一份子程序，实参按 memory/base/size 绑定。 */
    private static void compilerInjectionAndSharing(){
        String sugar = "array buf cell1 0 4\n"
            + "arrayinit buf 4 1 3 2 ~ ~ ~ ~\n"
            + "ifbegin expr \"sum(buf) > 3\" 4\n"
            + "set x 1\n"
            + "blockend\n"
            + "ifbegin expr \"sum(buf) > 100\" 7\n"
            + "set y 2\n"
            + "blockend\n";
        String mlog = stripCompile(sugar);
        check(countOf(mlog, "__ls_func___ls_builtin_arrsum_entry:") == 1,
            "builtin body must be hoisted exactly once (shared subroutine):\n" + mlog);
        check(countOf(mlog, "jump __ls_func___ls_builtin_arrsum_entry always x false") == 2,
            "each call site must jump to the shared entry:\n" + mlog);
        check(mlog.contains("set __ls_func___ls_builtin_arrsum_mem cell1"), "memory argument binding missing:\n" + mlog);
        check(mlog.contains("set __ls_func___ls_builtin_arrsum_base 0"), "base argument binding missing:\n" + mlog);
        check(mlog.contains("set __ls_func___ls_builtin_arrsum_size 4"), "size argument binding missing:\n" + mlog);
        check(mlog.contains("set __ls_cond_2 __ls_func___ls_builtin_arrsum_result"),
            "call result was not assigned into the condition temporary:\n" + mlog);
        check(mlog.contains("read __ls_bs_v __ls_func___ls_builtin_arrsum_mem __ls_bs_addr"),
            "builtin body read missing/mis-mangled:\n" + mlog);
        check(!mlog.contains("array ") && !mlog.contains("funccall ") && !mlog.contains("ifbegin "),
            "sugar residue in the product:\n" + mlog);
    }

    /** 载体持久化只包含用户库：内置函数不进 __ls_lib，但编译时仍可用。 */
    private static void builtinLibraryIsNotPersisted(){
        String lib = "funcdef userfn x 3\n"
            + "op add __ls_dummy x 1\n"
            + "return \"__ls_dummy\"\n"
            + "blockend\n";
        String sugar = "array buf cell1 0 4\n"
            + "funccall userfn 1 r\n"
            + "ifbegin expr \"sum(buf) > 3\" 4\n"
            + "set x 1\n"
            + "blockend\n";
        SugarFunctions.SanitizedLibrary sanitized = SugarFunctions.sanitizedLibrary(lib);
        String compiled = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, sanitized.index, sanitized.text,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        String embedded = SugarCompiler.libraryFromCode(compiled);
        check(embedded != null && embedded.contains("userfn"), "used user library function missing from the carrier: " + embedded);
        check(embedded != null && !embedded.contains("__ls_builtin"), "builtin leaked into the library carrier: " + embedded);
        check(SugarCompiler.stripMarkers(compiled).contains("__ls_func___ls_builtin_arrsum_entry:"),
            "builtin body missing from the product:\n" + SugarCompiler.stripMarkers(compiled));
        // 用户库入口保持纯净
        check(SugarFunctions.library() == null || !SugarFunctions.library().functions.containsKey("__ls_builtin_arrsum"),
            "builtin leaked into the user library index");
    }

    /** 未使用的内置函数不进入产物。 */
    private static void unusedBuiltinsStayOut(){
        String mlog = stripCompile("array buf cell1 0 4\nset x 1\n");
        check(!mlog.contains("__ls_builtin"), "unused builtin leaked into the product:\n" + mlog);
        check(!mlog.contains("__ls_bs_"), "unused builtin body leaked into the product:\n" + mlog);
    }

    /** 产物只含原版指令（strip 模式）。 */
    private static void outputIsPureVanilla(){
        String sugar = "array buf cell1 0 4\n"
            + "arrayinit buf 4 1 3 2 ~ ~ ~ ~\n"
            + "ifbegin expr \"sum(buf) + avg(buf) > 3\" 4\n"
            + "set x 1\n"
            + "blockend\n"
            + "ifbegin expr \"indexof(buf, 2) >= 0\" 7\n"
            + "set y 2\n"
            + "blockend\n"
            + "ifbegin expr \"sortasc(buf) >= 0\" 10\n"
            + "set z 3\n"
            + "blockend\n";
        String mlog = stripCompile(sugar);
        for(String line : mlog.replace("\r\n", "\n").split("\n", -1)){
            String t = line.trim();
            if(t.isEmpty() || t.endsWith(":") || t.startsWith("#")) continue;
            int space = t.indexOf(' ');
            String opcode = space < 0 ? t : t.substring(0, space);
            check(vanillaOpcodes.contains(opcode), "non-vanilla instruction in product: " + t);
        }
    }

    /** 载体往返：restore 保留 intrinsic 源码，recompile 命中同一存储流。 */
    private static void roundTripAndVerification(){
        String sugar = "array buf cell1 0 4\n"
            + "arrayinit buf 4 1 3 2 ~ ~ ~ ~\n"
            + "ifbegin expr \"sum(buf) > 3\" 4\n"
            + "set x 1\n"
            + "blockend\n";
        String compiled = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        check(SugarCompiler.restore(compiled).equals(sugar), "carrier did not preserve the intrinsic source:\n" + SugarCompiler.restore(compiled));
        check(SugarCompiler.verifyRestore(compiled, sugar), "verifyRestore rejected a builtin-using program");
        String recompiled = SugarCompiler.compile(SugarCompiler.restore(compiled), SugarCompiler.FuncMode.normal,
            SugarFunctions.library(), null, SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        check(SugarCompiler.matchesStoredStream(recompiled, compiled), "recompiled intrinsic program drifted from the stored stream");
    }

    /** 内置函数参数表对编辑器 paramsOf 可见。 */
    private static void editorVisibility(){
        check(SugarFunctions.paramsOf("__ls_builtin_arrsum", null) != null
            && SugarFunctions.paramsOf("__ls_builtin_arrsum", null).equals(Arrays.asList("mem", "base", "size")),
            "builtin params are not visible to paramsOf");
        check(DataModules.builtinFunctionNames().contains("__ls_builtin_arrsort"), "builtin names are not exposed");
        check(ArrayBulkIntrinsics.builtinSugar().size() == 13, "expected 13 builtin function bodies");
    }

    private static final Set<String> vanillaOpcodes = new HashSet<>(Arrays.asList(
        "noop", "read", "write", "draw", "print", "printchar", "format", "drawflush", "printflush",
        "getlink", "control", "radar", "sensor", "set", "op", "select", "wait", "stop", "lookup",
        "packcolor", "unpackcolor", "end", "jump", "ubind", "ucontrol", "uradar", "ulocate",
        "query", "getblock", "setblock", "spawn", "bullet", "status", "weathersense", "weatherset",
        "spawnwave", "setrule", "message", "cutscene", "effect", "explosion", "setrate", "fetch",
        "sync", "clientdata", "getflag", "setflag", "setprop", "playsound", "playmusic",
        "setmarker", "makemarker", "localeprint"
    ));

    // ===== helpers =====

    private static String compile(String sugar){
        return SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
    }

    private static String stripCompile(String sugar){
        return SugarCompiler.stripMarkers(compile(sugar));
    }

    private static void withRegistry(String declarations, Runnable body){
        ArrayRegistry registry = ArrayRegistry.compileRegistry(LAssembler.read(declarations, true), null);
        ArrayRegistry previous = ArrayRegistry.enter(registry);
        try{
            body.run();
        }finally{
            ArrayRegistry.restore(previous);
        }
    }

    private static String textOf(List<ExprCompiler.Line> ops){
        StringBuilder out = new StringBuilder();
        for(int i = 0; i < ops.size(); i++){
            if(i > 0) out.append('\n');
            out.append(ops.get(i).toText());
        }
        return out.toString();
    }

    private static int countOf(String text, String needle){
        int count = 0, at = 0;
        while((at = text.indexOf(needle, at)) >= 0){
            count++;
            at += needle.length();
        }
        return count;
    }

    private static void checkThrows(Runnable body, String what){
        try{
            body.run();
        }catch(ExprCompiler.ParseException e){
            return; // expected
        }
        check(false, "expression should have failed (" + what + ")");
    }

    private static void checkCompileThrows(String sugar, String what){
        try{
            compile(sugar);
        }catch(RuntimeException e){
            return; // expected: intrinsic/undefined-function error aborted the compile
        }
        check(false, "compile should have failed (" + what + "): " + sugar);
    }

    private static void checkLine(String expected, String actual){
        check(expected.equals(actual), "mlog mismatch\n  expected: " + expected + "\n  actual:   " + actual);
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
