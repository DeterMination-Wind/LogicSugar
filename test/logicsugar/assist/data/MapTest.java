package logicsugar.assist.data;

import arc.struct.Seq;
import logicsugar.assist.expr.ArrayRegistry;
import logicsugar.assist.expr.ExprCompiler;
import logicsugar.assist.expr.MapIntrinsics;
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
 * M4 哈希表自测（{@link MapModule} + {@link MapIntrinsics}）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li><b>表达式层</b>：{@code mapset/mapget/maphas/mapdel/mapsize/mapclear} 展开为对
 *       {@code __ls_builtin_map*} 的 funccall（memory/base/capacity + key/value 绑定）；
 *       未声明名字、错误实参、无上下文都在编译期报错。</li>
 *   <li><b>声明校验</b>：重名、与数组/函数重名、非法标识符、base/capacity 字面量与
 *       容量检查（cellN=64、bankN/worldN=512）、同内存块区间重叠。</li>
 *   <li><b>编译层</b>：声明卡不产指令、内置函数 normal 模式共享一份、未使用不进产物、
 *       产物逐行纯原版、载体往返与 verifyRestore。</li>
 *   <li><b>运行层</b>（headless LExecutor + 与游戏 MemoryBlock 语义一致的假内存）：
 *       空表/满表/重复键更新、mapdel 后 get 返回 NaN、mapsize/mapclear、墓碑复用、
 *       NaN 键拒绝。</li>
 * </ul>
 */
public class MapTest{
    public static void main(String[] args){
        SugarStatements.installParsers();
        Vars.logicVars = new GlobalVars();
        DataModules.clearModules();
        DataModules.register(new MapModule());
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
        System.out.println("LogicSugar Map self-test passed.");
    }

    // ===== 表达式层 =====

    private static void expressionExpansions(){
        withMap("map m cell1 0 4", () -> {
            checkLine("funccall __ls_builtin_mapset \"cell1, 0, 4, 1, 2\" x",
                textOf(ExprCompiler.compile("x", "mapset(m, 1, 2)")));
            checkLine("funccall __ls_builtin_mapget \"cell1, 0, 4, 1\" x",
                textOf(ExprCompiler.compile("x", "mapget(m, 1)")));
            checkLine("funccall __ls_builtin_maphas \"cell1, 0, 4, 1\" x",
                textOf(ExprCompiler.compile("x", "maphas(m, 1)")));
            checkLine("funccall __ls_builtin_mapdel \"cell1, 0, 4, 1\" x",
                textOf(ExprCompiler.compile("x", "mapdel(m, 1)")));
            checkLine("funccall __ls_builtin_mapsize \"cell1, 0, 4\" x",
                textOf(ExprCompiler.compile("x", "mapsize(m)")));
            checkLine("funccall __ls_builtin_mapclear \"cell1, 0, 4\" x",
                textOf(ExprCompiler.compile("x", "mapclear(m)")));
            // 大小写不敏感（与 ExprCompiler 的数学内置一致）
            checkLine("funccall __ls_builtin_mapget \"cell1, 0, 4, 1\" x",
                textOf(ExprCompiler.compile("x", "MAPGET(m, 1)")));
            // 表达式链里与其它运算组合：实参先编译，再发调用
            checkLine("op add _0 i 1\nfunccall __ls_builtin_mapget \"cell1, 0, 4, _0\" _1\nop mul x _1 2",
                textOf(ExprCompiler.compile("x", "mapget(m, i + 1) * 2")));
            // value 实参是表达式
            checkLine("op add _0 a 1\nfunccall __ls_builtin_mapset \"cell1, 0, 4, 3, _0\" x",
                textOf(ExprCompiler.compile("x", "mapset(m, 3, a + 1)")));
        });
        // base>0：地址与容量原样绑定
        withMap("map m cell1 8 4", () -> {
            checkLine("funccall __ls_builtin_mapget \"cell1, 8, 4, 1\" x",
                textOf(ExprCompiler.compile("x", "mapget(m, 1)")));
        });
    }

    private static void argumentErrors(){
        withMap("map m cell1 0 4", () -> {
            checkThrows(() -> ExprCompiler.compile("x", "mapget(nosuch, 1)"), "undeclared map");
            checkThrows(() -> ExprCompiler.compile("x", "mapget(1, 1)"), "literal map argument");
            checkThrows(() -> ExprCompiler.compile("x", "mapset(m[0], 1, 2)"), "subscript map argument");
        });
        // 没有声明上下文时给出明确错误（编辑器无画布/纯表达式预览）
        check(!MapModule.hasContext(), "map context leaked out of withMap");
        checkThrows(() -> ExprCompiler.compile("x", "mapget(m, 1)"), "no map context");
        // 错误实参个数不再是 intrinsic：落到普通 funccall，编译期报未定义函数
        checkCompileThrows("map m cell1 0 4\nifbegin expr \"mapget(m) > 0\" 3\nset x 1\nblockend\n",
            "mapget with one argument");
        checkCompileThrows("map m cell1 0 4\nifbegin expr \"mapsize(m, 1) > 0\" 3\nset x 1\nblockend\n",
            "mapsize with two arguments");
    }

    // ===== 声明校验 =====

    private static void declarationValidation(){
        // 重名
        checkCompileThrows("map m cell1 0 4\nmap m cell1 8 4\nset x 1\n", "duplicate map name");
        // 与数组/矩阵重名
        checkCompileThrows("array a cell1 0 4\nmap a cell1 4 4\nset x 1\n", "map name conflicts with array");
        checkCompileThrows("matrix mx cell1 0 2 2\nmap mx cell1 8 4\nset x 1\n", "map name conflicts with matrix");
        // 与函数重名
        checkCompileThrows("map foo cell1 0 4\nfuncdef foo ~ 3\nset x 1\nblockend\n", "map name conflicts with function");
        // 非法名字
        checkCompileThrows("map 1m cell1 0 4\nset x 1\n", "identifier starting with a digit");
        checkCompileThrows("map __ls_x cell1 0 4\nset x 1\n", "reserved __ls_ prefix");
        // base / capacity 字面量
        checkCompileThrows("map m cell1 -1 4\nset x 1\n", "negative base");
        checkCompileThrows("map m cell1 x 4\nset x 1\n", "variable base");
        checkCompileThrows("map m cell1 0 0\nset x 1\n", "zero capacity");
        checkCompileThrows("map m cell1 0 1.5\nset x 1\n", "fractional capacity");
        // 容量检查：cellN=64、bankN=512（base + 2*capacity）
        checkCompileThrows("map m cell1 0 33\nset x 1\n", "cell capacity overflow");
        checkCompileThrows("map m cell1 32 32\nset x 1\n", "cell capacity overflow at offset base");
        checkCompileThrows("map m bank1 0 257\nset x 1\n", "bank capacity overflow");
        compile("map m cell1 0 32\nset x 1\n");
        compile("map m bank1 0 256\nset x 1\n");
        // 同内存块区间重叠
        checkCompileThrows("map m cell1 0 4\nmap n cell1 4 4\nset x 1\n", "overlapping map ranges");
        compile("map m cell1 0 4\nmap n cell1 8 4\nset x 1\n");
        // 已知限制：与 array/matrix 的跨模块区间重叠不校验（契约 §7）
        compile("array a cell1 0 8\nmap m cell1 8 4\nset x 1\n");
    }

    // ===== 声明卡 =====

    private static void declarationCardProducesNoLine(){
        String sugar = "map m cell1 0 4\nset x 1\n";
        String mlog = stripCompile(sugar);
        check(!mlog.contains("map m "), "declaration card leaked into the product:\n" + mlog);
        check(mlog.contains("set x 1"), "regular instruction lost:\n" + mlog);
        String compiled = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        check(SugarCompiler.restore(compiled).equals(sugar), "declaration card did not survive the carrier round trip");
    }

    /** 编辑期标红：字段有问题的 map 卡标红，合法的卡不标红。 */
    private static void editorInvalidMarking(){
        Seq<LStatement> good = LAssembler.read("map m cell1 0 4\nset x 1\n", true);
        boolean[] invalid = SugarCompiler.invalidStatements(good);
        check(!invalid[0], "valid map card was marked invalid");
        Seq<LStatement> bad = LAssembler.read("map m cell1 0 33\nset x 1\n", true);
        boolean[] badInvalid = SugarCompiler.invalidStatements(bad);
        check(badInvalid[0], "capacity-overflow map card was not marked invalid");
        Seq<LStatement> duplicate = LAssembler.read("map m cell1 0 4\nmap m cell1 8 4\nset x 1\n", true);
        boolean[] duplicateInvalid = SugarCompiler.invalidStatements(duplicate);
        check(!duplicateInvalid[0] && duplicateInvalid[1], "duplicate map card marking is wrong");
    }

    // ===== 内置函数注入 =====

    /** 内置函数被注入：normal 模式两个调用点共享一份子程序，实参按 memory/base/capacity/key 绑定。 */
    private static void builtinInjectionAndSharing(){
        String sugar = "map m cell1 0 4\n"
            + "ifbegin expr \"mapget(m, 1) > 0\" 3\n"
            + "set x 1\n"
            + "blockend\n"
            + "ifbegin expr \"mapget(m, 2) > 0\" 6\n"
            + "set y 2\n"
            + "blockend\n";
        String mlog = stripCompile(sugar);
        check(countOf(mlog, "__ls_func___ls_builtin_mapget_entry:") == 1,
            "builtin body must be hoisted exactly once (shared subroutine):\n" + mlog);
        check(countOf(mlog, "jump __ls_func___ls_builtin_mapget_entry always x false") == 2,
            "each call site must jump to the shared entry:\n" + mlog);
        check(mlog.contains("set __ls_func___ls_builtin_mapget_mem cell1"), "memory argument binding missing:\n" + mlog);
        check(mlog.contains("set __ls_func___ls_builtin_mapget_base 0"), "base argument binding missing:\n" + mlog);
        check(mlog.contains("set __ls_func___ls_builtin_mapget_cap 4"), "capacity argument binding missing:\n" + mlog);
        check(mlog.contains("set __ls_func___ls_builtin_mapget_key 1")
            && mlog.contains("set __ls_func___ls_builtin_mapget_key 2"), "key argument binding missing:\n" + mlog);
        check(mlog.contains("op strictEqual __ls_mp_e __ls_mp_k __ls_mp_nan"),
            "empty-slot test (strictEqual against the NaN marker) missing:\n" + mlog);
        check(mlog.contains("op div __ls_func___ls_builtin_mapget_result 0 0"),
            "miss path must produce a NaN result:\n" + mlog);
        check(!mlog.contains("map m ") && !mlog.contains("funccall ") && !mlog.contains("ifbegin "),
            "sugar residue in the product:\n" + mlog);
    }

    /** 未使用的内置函数不进入产物。 */
    private static void unusedBuiltinsStayOut(){
        String mlog = stripCompile("map m cell1 0 4\nset x 1\n");
        check(!mlog.contains("__ls_builtin"), "unused builtin leaked into the product:\n" + mlog);
        check(!mlog.contains("__ls_mp_"), "unused builtin body leaked into the product:\n" + mlog);
    }

    /** 产物只含原版指令（strip 模式）。 */
    private static void outputIsPureVanilla(){
        String sugar = program("map m cell1 0 4",
            "mapclear(m)", "mapsize(m)", "mapset(m, 1, 10)", "mapget(m, 1)", "maphas(m, 1)",
            "mapdel(m, 1)", "mapset(m, 2, 20)", "mapget(m, 2)");
        String mlog = stripCompile(sugar);
        for(String line : mlog.replace("\r\n", "\n").split("\n", -1)){
            String t = line.trim();
            if(t.isEmpty() || t.endsWith(":") || t.startsWith("#")) continue;
            int space = t.indexOf(' ');
            String opcode = space < 0 ? t : t.substring(0, space);
            check(vanillaOpcodes.contains(opcode), "non-vanilla instruction in product: " + t);
        }
        // strip/emit 产物一致：map 内置函数体不含下标读写，emit 模式不追加断言
        String emit = SugarCompiler.stripMarkers(SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.emit));
        check(emit.contains("__ls_func___ls_builtin_mapget_entry:"), "builtin body missing from the emit product");
        check(!emit.contains("assertBounds"), "map operations must not emit bounds asserts:\n" + emit);
    }

    /** 载体往返：restore 保留 intrinsic 源码，recompile 命中同一存储流。 */
    private static void roundTripAndVerification(){
        String sugar = program("map m cell1 0 4", "mapclear(m)", "mapset(m, 1, 10)", "mapget(m, 1)", "mapsize(m)");
        String compiled = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        check(SugarCompiler.restore(compiled).equals(sugar),
            "carrier did not preserve the intrinsic source:\n" + SugarCompiler.restore(compiled));
        check(SugarCompiler.verifyRestore(compiled, sugar), "verifyRestore rejected a map-using program");
        String recompiled = SugarCompiler.compile(SugarCompiler.restore(compiled), SugarCompiler.FuncMode.normal,
            null, null, SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        check(SugarCompiler.matchesStoredStream(recompiled, compiled), "recompiled map program drifted from the stored stream");
    }

    // ===== 运行层 =====

    /**
     * 用 headless LExecutor + 与游戏 MemoryBlock 语义一致的假内存执行真实产物：
     * 空表/重复键更新/删除/满表/墓碑复用/NaN 键。
     */
    private static void runtimeSemantics(){
        // 空表 + 基本读写 + 重复键更新 + 删除
        String basic = program("map m cell1 0 4",
            "mapclear(m)",      // r0: 0
            "mapsize(m)",       // r1: 0
            "mapget(m, 1)",     // r2: NaN（空表未命中）
            "mapset(m, 1, 10)", // r3: 1
            "mapget(m, 1)",     // r4: 10
            "maphas(m, 1)",     // r5: 1
            "maphas(m, 2)",     // r6: 0
            "mapset(m, 1, 99)", // r7: 1（重复键更新）
            "mapget(m, 1)",     // r8: 99
            "mapsize(m)",       // r9: 1（更新不增加 size）
            "mapdel(m, 1)",     // r10: 1
            "mapget(m, 1)",     // r11: NaN（删除后未命中）
            "mapsize(m)",       // r12: 0
            "mapdel(m, 1)");    // r13: 0（再删不存在）
        RunResult run = run(basic, 64);
        checkNum(run, "r0", 0);
        checkNum(run, "r1", 0);
        checkNaN(run, "r2", "mapget on an empty table must return NaN");
        checkNum(run, "r3", 1);
        checkNum(run, "r4", 10);
        checkNum(run, "r5", 1);
        checkNum(run, "r6", 0);
        checkNum(run, "r7", 1);
        checkNum(run, "r8", 99);
        checkNum(run, "r9", 1);
        checkNum(run, "r10", 1);
        checkNaN(run, "r11", "mapget after mapdel must return NaN");
        checkNum(run, "r12", 0);
        checkNum(run, "r13", 0);

        // 满表：容量 4，key 1..4 哈希互不相同，第 5 个键必须返回 -1 且不覆盖任何已有键
        String full = program("map m cell1 0 4",
            "mapclear(m)",       // r0
            "mapset(m, 1, 10)",  // r1: 1
            "mapset(m, 2, 20)",  // r2: 1
            "mapset(m, 3, 30)",  // r3: 1
            "mapset(m, 4, 40)",  // r4: 1
            "mapsize(m)",        // r5: 4
            "mapset(m, 5, 50)",  // r6: -1（表满）
            "mapget(m, 5)",      // r7: NaN
            "mapget(m, 1)",      // r8: 10（未被覆盖）
            "mapdel(m, 2)",      // r9: 1
            "mapset(m, 6, 60)",  // r10: 1（复用墓碑槽）
            "mapget(m, 6)",      // r11: 60
            "mapsize(m)");       // r12: 4
        run = run(full, 64);
        checkNum(run, "r0", 0);
        checkNum(run, "r1", 1);
        checkNum(run, "r2", 1);
        checkNum(run, "r3", 1);
        checkNum(run, "r4", 1);
        checkNum(run, "r5", 4);
        checkNum(run, "r6", -1);
        checkNaN(run, "r7", "mapget for a key rejected by a full table must return NaN");
        checkNum(run, "r8", 10);
        checkNum(run, "r9", 1);
        checkNum(run, "r10", 1);
        checkNum(run, "r11", 60);
        checkNum(run, "r12", 4);

        // NaN 键：set 返回 -1，get 返回 NaN，has/del 返回 0（空表也不被污染）
        String nanKeys = program("map m cell1 0 4",
            "mapclear(m)",            // r0
            "mapset(m, 0 / 0, 1)",    // r1: -1
            "mapget(m, 0 / 0)",       // r2: NaN
            "maphas(m, 0 / 0)",       // r3: 0
            "mapdel(m, 0 / 0)",       // r4: 0
            "mapsize(m)");            // r5: 0
        run = run(nanKeys, 64);
        checkNum(run, "r0", 0);
        checkNum(run, "r1", -1);
        checkNaN(run, "r2", "mapget with a NaN key must return NaN");
        checkNum(run, "r3", 0);
        checkNum(run, "r4", 0);
        checkNum(run, "r5", 0);

        // 冲突探测：capacity 4 全用 key = 1 的不同副本（哈希相同），验证线性探测与删除后的链完整
        String collisions = program("map m cell1 0 4",
            "mapclear(m)",        // r0
            "mapset(m, 1, 10)",   // r1
            "mapset(m, 5, 50)",   // r2（hash 1）
            "mapset(m, 9, 90)",   // r3（hash 1）
            "mapget(m, 9)",       // r4: 90
            "mapdel(m, 5)",       // r5: 1
            "mapget(m, 9)",       // r6: 90（墓碑不截断探测链）
            "mapget(m, 1)",       // r7: 10
            "mapsize(m)");        // r8: 2
        run = run(collisions, 64);
        checkNum(run, "r0", 0);
        checkNum(run, "r1", 1);
        checkNum(run, "r2", 1);
        checkNum(run, "r3", 1);
        checkNum(run, "r4", 90);
        checkNum(run, "r5", 1);
        checkNum(run, "r6", 90);
        checkNum(run, "r7", 10);
        checkNum(run, "r8", 2);
    }

    // ===== 编辑器可见性 =====

    private static void editorVisibility(){
        check(SugarFunctions.paramsOf("__ls_builtin_mapset", null) != null
            && SugarFunctions.paramsOf("__ls_builtin_mapset", null).equals(Arrays.asList("mem", "base", "cap", "key", "value")),
            "mapset params are not visible to paramsOf");
        check(SugarFunctions.paramsOf("__ls_builtin_mapget", null) != null
            && SugarFunctions.paramsOf("__ls_builtin_mapget", null).equals(Arrays.asList("mem", "base", "cap", "key")),
            "mapget params are not visible to paramsOf");
        check(DataModules.builtinFunctionNames().contains("__ls_builtin_mapsize"), "builtin names are not exposed");
        check(MapIntrinsics.builtinSugar().size() == 6, "expected 6 builtin function bodies");
    }

    // ===== helpers =====

    private static final Set<String> vanillaOpcodes = new HashSet<>(Arrays.asList(
        "noop", "read", "write", "draw", "print", "printchar", "format", "drawflush", "printflush",
        "getlink", "control", "radar", "sensor", "set", "op", "select", "wait", "stop", "lookup",
        "packcolor", "unpackcolor", "end", "jump", "ubind", "ucontrol", "uradar", "ulocate",
        "query", "getblock", "setblock", "spawn", "bullet", "status", "weathersense", "weatherset",
        "spawnwave", "setrule", "message", "cutscene", "effect", "explosion", "setrate", "fetch",
        "sync", "clientdata", "getflag", "setflag", "setprop", "playsound", "playmusic",
        "setmarker", "makemarker", "localeprint"
    ));

    /** 与游戏 MemoryBlock 一致的假内存（objectMemory/numberMemory + sentinel）。 */
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
        final FakeMemory memory;

        RunResult(LExecutor executor, FakeMemory memory){
            this.executor = executor;
            this.memory = memory;
        }

        LVar var(String name){
            return executor.optionalVar(name);
        }
    }

    /** 编译并执行一个 map 程序；{@code cell1} 被绑定到假内存。 */
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
        return new RunResult(executor, memory);
    }

    /**
     * 生成 main + funcdef 程序：每个表达式放进一个返回它的函数体，依次调用并把结果写入
     * {@code r0..rN}。funcdef 的 blockend 下标按语句位置自动计算，避免手写错位。
     */
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

    private static void withMap(String declarations, Runnable body){
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
            return; // expected
        }
        check(false, "expression should have failed (" + what + ")");
    }

    private static void checkCompileThrows(String sugar, String what){
        try{
            compile(sugar);
        }catch(RuntimeException e){
            return; // expected
        }
        check(false, "compile should have failed (" + what + "): " + sugar);
    }

    private static void checkNum(RunResult run, String variable, double expected){
        LVar value = run.var(variable);
        check(value != null, variable + " is missing from the executed program");
        check(Math.abs(value.num() - expected) < 1e-9,
            variable + " expected " + expected + " but got " + value.num());
    }

    /** NaN 在当前运行时的表示是 null 对象（isobj=true, objval=null）。 */
    private static void checkNaN(RunResult run, String variable, String what){
        LVar value = run.var(variable);
        check(value != null, variable + " is missing from the executed program");
        check(value.isobj && value.objval == null, what + " (" + variable + " is not the NaN marker)");
        check(Double.isNaN(value.numOrNan()), what + " (" + variable + ".numOrNan() is not NaN)");
    }

    private static void checkLine(String expected, String actual){
        check(expected.equals(actual), "mlog mismatch\n  expected: " + expected + "\n  actual:   " + actual);
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
