package logicsugar.assist.data;

import arc.struct.Seq;
import logicsugar.assist.expr.ArrayRegistry;
import logicsugar.assist.expr.ExprCompiler;
import logicsugar.assist.expr.SetIntrinsics;
import mindustry.Vars;
import mindustry.logic.GlobalVars;
import mindustry.logic.LAssembler;
import mindustry.logic.LExecutor;
import mindustry.logic.LStatement;
import mindustry.logic.LVar;
import mindustry.logic.SugarCompiler;
import mindustry.logic.SugarFunctions;
import mindustry.logic.SugarStatements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 无序集合自测（{@link SetModule} + {@link SetIntrinsics}）。
 *
 * <p>token 是 {@code uset}（{@code set} 是原版 opcode）。覆盖表达式展开、声明校验、
 * 注入函数共享、未使用不进产物、纯原版产物、载体往返，以及 headless {@link LExecutor}
 * 上的 add/has/del/size/clear、满表、墓碑复用与 NaN 键拒绝。</p>
 */
public class SetTest{
    public static void main(String[] args){
        SugarStatements.installParsers();
        Vars.logicVars = new GlobalVars();
        DataModules.clearModules();
        DataModules.register(new SetModule());
        DataModules.registerParsers();

        expressionExpansions();
        argumentErrors();
        declarationValidation();
        declarationCardProducesNoLine();
        editorInvalidMarking();
        builtinInjectionAndSharing();
        unusedBuiltinsStayOut();
        outputIsPureVanilla();
        roundTripAndVerification();
        runtimeSemantics();
        editorVisibility();

        DataModules.clearModules();
        System.out.println("LogicSugar Set self-test passed.");
    }

    private static void expressionExpansions(){
        withSet("uset s cell1 0 4", () -> {
            checkLine("funccall __ls_builtin_usetadd \"cell1, 0, 4, 1\" x",
                textOf(ExprCompiler.compile("x", "uadd(s, 1)")));
            checkLine("funccall __ls_builtin_usethas \"cell1, 0, 4, 1\" x",
                textOf(ExprCompiler.compile("x", "uhas(s, 1)")));
            checkLine("funccall __ls_builtin_usetdel \"cell1, 0, 4, 1\" x",
                textOf(ExprCompiler.compile("x", "udel(s, 1)")));
            checkLine("funccall __ls_builtin_usetsize \"cell1, 0, 4\" x",
                textOf(ExprCompiler.compile("x", "usize(s)")));
            checkLine("funccall __ls_builtin_usetclear \"cell1, 0, 4\" x",
                textOf(ExprCompiler.compile("x", "uclear(s)")));
            checkLine("funccall __ls_builtin_usethas \"cell1, 0, 4, 1\" x",
                textOf(ExprCompiler.compile("x", "UHAS(s, 1)")));
            checkLine("op add _0 i 1\nfunccall __ls_builtin_usethas \"cell1, 0, 4, _0\" _1\nop mul x _1 2",
                textOf(ExprCompiler.compile("x", "uhas(s, i + 1) * 2")));
        });
        withSet("uset s cell1 8 4", () -> {
            checkLine("funccall __ls_builtin_usethas \"cell1, 8, 4, 1\" x",
                textOf(ExprCompiler.compile("x", "uhas(s, 1)")));
        });
    }

    private static void argumentErrors(){
        withSet("uset s cell1 0 4", () -> {
            checkThrows(() -> ExprCompiler.compile("x", "uhas(nosuch, 1)"), "undeclared set");
            checkThrows(() -> ExprCompiler.compile("x", "uhas(1, 1)"), "literal set argument");
        });
        check(!SetModule.hasContext(), "set context leaked out of withSet");
        checkThrows(() -> ExprCompiler.compile("x", "uhas(s, 1)"), "no set context");
        checkCompileThrows("uset s cell1 0 4\nifbegin expr \"uhas(s) > 0\" 3\nset x 1\nblockend\n",
            "uhas with one argument");
        checkCompileThrows("uset s cell1 0 4\nifbegin expr \"usize(s, 1) > 0\" 3\nset x 1\nblockend\n",
            "usize with two arguments");
    }

    private static void declarationValidation(){
        checkCompileThrows("uset s cell1 0 4\nuset s cell1 8 4\nset x 1\n", "duplicate set name");
        checkCompileThrows("array a cell1 0 4\nuset a cell1 4 4\nset x 1\n", "set name conflicts with array");
        checkCompileThrows("uset foo cell1 0 4\nfuncdef foo ~ 3\nset x 1\nblockend\n", "set name conflicts with function");
        checkCompileThrows("uset 1s cell1 0 4\nset x 1\n", "identifier starting with a digit");
        checkCompileThrows("uset __ls_x cell1 0 4\nset x 1\n", "reserved __ls_ prefix");
        checkCompileThrows("uset s cell1 -1 4\nset x 1\n", "negative base");
        checkCompileThrows("uset s cell1 x 4\nset x 1\n", "variable base");
        checkCompileThrows("uset s cell1 0 0\nset x 1\n", "zero capacity");
        checkCompileThrows("uset s cell1 0 65\nset x 1\n", "cell capacity overflow");
        compile("uset s cell1 0 64\nset x 1\n");
        checkCompileThrows("uset s cell1 0 4\nuset t cell1 2 4\nset x 1\n", "overlapping uset ranges");
        compile("uset s cell1 0 4\nuset t cell1 4 4\nset x 1\n");
        compile("array a cell1 0 8\nuset s cell1 0 4\nset x 1\n");
    }

    private static void declarationCardProducesNoLine(){
        String sugar = "uset s cell1 0 4\nset x 1\n";
        String mlog = stripCompile(sugar);
        check(!mlog.contains("uset s "), "declaration card leaked into the product:\n" + mlog);
        check(mlog.contains("set x 1"), "regular instruction lost:\n" + mlog);
        String compiled = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        check(SugarCompiler.restore(compiled).equals(sugar), "declaration card did not survive the carrier round trip");
    }

    private static void editorInvalidMarking(){
        Seq<LStatement> good = LAssembler.read("uset s cell1 0 4\nset x 1\n", true);
        boolean[] invalid = SugarCompiler.invalidStatements(good);
        check(!invalid[0], "valid uset card was marked invalid");
        Seq<LStatement> bad = LAssembler.read("uset s cell1 0 65\nset x 1\n", true);
        boolean[] badInvalid = SugarCompiler.invalidStatements(bad);
        check(badInvalid[0], "capacity-overflow uset card was not marked invalid");
        Seq<LStatement> duplicate = LAssembler.read("uset s cell1 0 4\nuset s cell1 8 4\nset x 1\n", true);
        boolean[] duplicateInvalid = SugarCompiler.invalidStatements(duplicate);
        check(!duplicateInvalid[0] && duplicateInvalid[1], "duplicate uset card marking is wrong");
    }

    private static void builtinInjectionAndSharing(){
        String sugar = "uset s cell1 0 4\n"
            + "ifbegin expr \"uhas(s, 1) > 0\" 3\n"
            + "set x 1\n"
            + "blockend\n"
            + "ifbegin expr \"uhas(s, 2) > 0\" 6\n"
            + "set y 2\n"
            + "blockend\n";
        String mlog = stripCompile(sugar);
        check(countOf(mlog, "__ls_func___ls_builtin_usethas_entry:") == 1,
            "builtin body must be hoisted exactly once:\n" + mlog);
        check(countOf(mlog, "jump __ls_func___ls_builtin_usethas_entry always x false") == 2,
            "each call site must jump to the shared entry:\n" + mlog);
        check(mlog.contains("op strictEqual __ls_us_e __ls_us_k __ls_us_nan"),
            "empty-slot test missing:\n" + mlog);
        check(!mlog.contains("uset s ") && !mlog.contains("funccall ") && !mlog.contains("ifbegin "),
            "sugar residue in the product:\n" + mlog);
    }

    private static void unusedBuiltinsStayOut(){
        String mlog = stripCompile("uset s cell1 0 4\nset x 1\n");
        check(!mlog.contains("__ls_builtin"), "unused builtin leaked into the product:\n" + mlog);
        check(!mlog.contains("__ls_us_"), "unused builtin body leaked into the product:\n" + mlog);
    }

    private static void outputIsPureVanilla(){
        String sugar = program("uset s cell1 0 4",
            "uclear(s)", "usize(s)", "uadd(s, 1)", "uhas(s, 1)", "udel(s, 1)");
        String mlog = stripCompile(sugar);
        for(String line : mlog.replace("\r\n", "\n").split("\n", -1)){
            String t = line.trim();
            if(t.isEmpty() || t.endsWith(":") || t.startsWith("#")) continue;
            int space = t.indexOf(' ');
            String opcode = space < 0 ? t : t.substring(0, space);
            check(vanillaOpcodes.contains(opcode), "non-vanilla instruction in product: " + t);
        }
    }

    private static void roundTripAndVerification(){
        String sugar = program("uset s cell1 0 4", "uclear(s)", "uadd(s, 1)", "uhas(s, 1)", "usize(s)");
        String compiled = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        check(SugarCompiler.restore(compiled).equals(sugar),
            "carrier did not preserve the intrinsic source:\n" + SugarCompiler.restore(compiled));
        check(SugarCompiler.verifyRestore(compiled, sugar), "verifyRestore rejected a set-using program");
        String recompiled = SugarCompiler.compile(SugarCompiler.restore(compiled), SugarCompiler.FuncMode.normal,
            null, null, SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        check(SugarCompiler.matchesStoredStream(recompiled, compiled), "recompiled set program drifted from the stored stream");
    }

    private static void runtimeSemantics(){
        String basic = program("uset s cell1 0 4",
            "uclear(s)",
            "usize(s)",
            "uhas(s, 1)",
            "uadd(s, 1)",
            "uhas(s, 1)",
            "uhas(s, 2)",
            "uadd(s, 1)",
            "usize(s)",
            "udel(s, 1)",
            "uhas(s, 1)",
            "usize(s)",
            "udel(s, 1)");
        RunResult run = run(basic, 64);
        checkNum(run, "r0", 0);
        checkNum(run, "r1", 0);
        checkNum(run, "r2", 0);
        checkNum(run, "r3", 1);
        checkNum(run, "r4", 1);
        checkNum(run, "r5", 0);
        checkNum(run, "r6", 1);
        checkNum(run, "r7", 1);
        checkNum(run, "r8", 1);
        checkNum(run, "r9", 0);
        checkNum(run, "r10", 0);
        checkNum(run, "r11", 0);

        String full = program("uset s cell1 0 4",
            "uclear(s)",
            "uadd(s, 1)",
            "uadd(s, 2)",
            "uadd(s, 3)",
            "uadd(s, 4)",
            "usize(s)",
            "uadd(s, 5)",
            "uhas(s, 5)",
            "uhas(s, 1)",
            "udel(s, 2)",
            "uadd(s, 6)",
            "uhas(s, 6)",
            "usize(s)");
        run = run(full, 64);
        checkNum(run, "r0", 0);
        checkNum(run, "r1", 1);
        checkNum(run, "r2", 1);
        checkNum(run, "r3", 1);
        checkNum(run, "r4", 1);
        checkNum(run, "r5", 4);
        checkNum(run, "r6", -1);
        checkNum(run, "r7", 0);
        checkNum(run, "r8", 1);
        checkNum(run, "r9", 1);
        checkNum(run, "r10", 1);
        checkNum(run, "r11", 1);
        checkNum(run, "r12", 4);

        String nanKeys = program("uset s cell1 0 4",
            "uclear(s)",
            "uadd(s, 0 / 0)",
            "uhas(s, 0 / 0)",
            "udel(s, 0 / 0)",
            "usize(s)");
        run = run(nanKeys, 64);
        checkNum(run, "r0", 0);
        checkNum(run, "r1", -1);
        checkNum(run, "r2", 0);
        checkNum(run, "r3", 0);
        checkNum(run, "r4", 0);

        String collisions = program("uset s cell1 0 4",
            "uclear(s)",
            "uadd(s, 1)",
            "uadd(s, 5)",
            "uadd(s, 9)",
            "uhas(s, 9)",
            "udel(s, 5)",
            "uhas(s, 9)",
            "uhas(s, 1)",
            "usize(s)");
        run = run(collisions, 64);
        checkNum(run, "r0", 0);
        checkNum(run, "r1", 1);
        checkNum(run, "r2", 1);
        checkNum(run, "r3", 1);
        checkNum(run, "r4", 1);
        checkNum(run, "r5", 1);
        checkNum(run, "r6", 1);
        checkNum(run, "r7", 1);
        checkNum(run, "r8", 2);
    }

    private static void editorVisibility(){
        check(SugarFunctions.paramsOf("__ls_builtin_usetadd", null) != null
            && SugarFunctions.paramsOf("__ls_builtin_usetadd", null).equals(Arrays.asList("mem", "base", "cap", "key")),
            "usetadd params are not visible to paramsOf");
        check(DataModules.builtinFunctionNames().contains("__ls_builtin_usetsize"), "builtin names are not exposed");
        check(SetIntrinsics.builtinSugar().size() == 5, "expected 5 builtin function bodies");
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

    private static final class FakeMemory implements mindustry.logic.LReadable, mindustry.logic.LWritable{
        private static final Object SENTINEL = new Object();
        final Object[] objects;
        final double[] numbers;

        FakeMemory(int size){
            objects = new Object[size];
            numbers = new double[size];
            Arrays.fill(objects, SENTINEL);
        }

        @Override
        public boolean readable(LExecutor exec){
            return true;
        }

        @Override
        public void read(LVar position, LVar output){
            int address = position.numi();
            if(address < 0 || address >= objects.length){
                output.setobj(null);
                return;
            }
            Object object = objects[address];
            if(object == SENTINEL){
                output.setnum(numbers[address]);
            }else{
                output.setobj(object);
            }
        }

        @Override
        public boolean writable(LExecutor exec){
            return true;
        }

        @Override
        public void write(LVar position, LVar value){
            int address = position.numi();
            if(address < 0 || address >= objects.length) return;
            if(value.isobj){
                objects[address] = value.objval;
            }else{
                objects[address] = SENTINEL;
                numbers[address] = value.numval;
            }
        }
    }

    private static final class RunResult{
        final LExecutor executor;

        RunResult(LExecutor executor){
            this.executor = executor;
        }

        LVar var(String name){
            return executor.optionalVar(name);
        }
    }

    private static RunResult run(String sugar, int memorySize){
        String code = compile(sugar);
        LExecutor executor = new LExecutor();
        executor.load(LAssembler.assemble(code, true));
        FakeMemory memory = new FakeMemory(memorySize);
        LVar cell = executor.optionalVar("cell1");
        check(cell != null, "memory variable 'cell1' missing from the program");
        cell.setobj(memory);
        for(int i = 0; i < 200000 && executor.counter.numval >= 0 && executor.counter.numval < executor.instructions.length; i++){
            executor.runOnce();
        }
        return new RunResult(executor);
    }

    private static String program(String declaration, String... expressions){
        String[] declLines = declaration.split("\n", -1);
        StringBuilder out = new StringBuilder();
        out.append(declaration).append('\n');
        for(int i = 0; i < expressions.length; i++){
            out.append("funccall c").append(i).append(" \"\" r").append(i).append('\n');
        }
        out.append("end\n");
        int index = declLines.length + expressions.length + 1;
        for(int i = 0; i < expressions.length; i++){
            out.append("funcdef c").append(i).append(" ~ ").append(index + 2).append('\n');
            out.append("return \"").append(expressions[i]).append("\"\n");
            out.append("blockend\n");
            index += 3;
        }
        return out.toString();
    }

    private static void withSet(String declarations, Runnable body){
        Seq<LStatement> statements = LAssembler.read(declarations, true);
        List<LStatement> list = new ArrayList<>(statements.size);
        for(LStatement statement : statements) list.add(statement);
        ArrayRegistry previous = ArrayRegistry.enter(ArrayRegistry.empty());
        DataModules.collectAll(list, Collections.emptySet());
        try{
            body.run();
        }finally{
            DataModules.restore();
            ArrayRegistry.restore(previous);
        }
    }

    private static String compile(String sugar){
        return SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
    }

    private static String stripCompile(String sugar){
        return SugarCompiler.stripMarkers(compile(sugar));
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
            return;
        }
        check(false, "expression should have failed (" + what + ")");
    }

    private static void checkCompileThrows(String sugar, String what){
        try{
            compile(sugar);
        }catch(RuntimeException e){
            return;
        }
        check(false, "compile should have failed (" + what + "): " + sugar);
    }

    private static void checkNum(RunResult run, String variable, double expected){
        LVar value = run.var(variable);
        check(value != null, variable + " is missing from the executed program");
        check(Math.abs(value.num() - expected) < 1e-9,
            variable + " expected " + expected + " but got " + value.num());
    }

    private static void checkLine(String expected, String actual){
        check(expected.equals(actual), "mlog mismatch\n  expected: " + expected + "\n  actual:   " + actual);
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
