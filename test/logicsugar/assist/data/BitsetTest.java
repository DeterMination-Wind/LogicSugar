package logicsugar.assist.data;

import arc.struct.Seq;
import logicsugar.assist.expr.BitsetIntrinsics;
import logicsugar.assist.expr.ExprCompiler;
import logicsugar.assist.expr.ExprIntrinsics;
import mindustry.Vars;
import mindustry.logic.GlobalVars;
import mindustry.logic.LAssembler;
import mindustry.logic.LStatement;
import mindustry.logic.SugarCompiler;
import mindustry.logic.SugarFunctions;
import mindustry.logic.SugarStatements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M3 位集自测（{@link BitsetModule} + {@link BitsetIntrinsics}）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li><b>表达式层</b>：{@code bset}/{@code bclr}/{@code btest} 的精确指令序列
 *       （{@code i//64}、{@code i%64}、{@code shl} mask、越界守卫、读-改-写回），
 *       {@code bcount} 展开为注入函数 funccall；大小写不敏感、用户函数优先、
 *       未声明位集/非名字实参编译期报错；</li>
 *   <li><b>语义层</b>：用一个只认识原版 op/read/write/jump 的小解释器执行展开链，
 *       验证置位/清位/测试与越界行为（i&lt;0 或 i≥words*64 时 set/clr 忽略、test 返回 0），
 *       并执行产物里的 {@code __ls_builtin_bitcount} 函数体验证跨 word 统计；</li>
 *   <li><b>编译层</b>：声明卡不产指令、内置函数 normal 共享一份且未使用不进产物、
 *       产物逐行纯原版、载体往返 + verifyRestore、编辑期标红与 paramsOf 可见性。</li>
 * </ul>
 */
public class BitsetTest{
    public static void main(String[] args){
        SugarStatements.installParsers();
        Vars.logicVars = new GlobalVars();
        DataModules.clearModules();
        DataModules.register(new BitsetModule());
        DataModules.registerParsers();

        declarationCardProducesNoLine();
        expressionExpansions();
        indexExpressionsAndCapacity();
        bcountBuiltinInjection();
        unusedBuiltinStaysOut();
        outOfRangeAndBitSemantics();
        returnContext();
        declarationValidation();
        intrinsicErrors();
        userFunctionWins();
        outputIsPureVanilla();
        roundTripAndVerification();
        editorVisibility();

        DataModules.clearModules();
        System.out.println("LogicSugar Bitset self-test passed.");
    }

    // ===== 声明卡 =====

    private static void declarationCardProducesNoLine(){
        String sugar = "bitset bs cell1 0 2\nset x 1\n";
        String mlog = stripCompile(sugar);
        check(!mlog.contains("bitset "), "declaration card leaked into the product:\n" + mlog);
        check(mlog.contains("set x 1"), "regular instruction lost:\n" + mlog);
        String compiled = compile(sugar);
        check(SugarCompiler.restore(compiled).equals(sugar),
            "declaration card did not survive the carrier round trip:\n" + SugarCompiler.restore(compiled));
    }

    // ===== 表达式层：精确指令序列 =====

    private static void expressionExpansions(){
        withBitsets("bitset bs cell1 0 2", () -> {
            checkLine(String.join("\n",
                "op idiv _0 i 64",
                "op mod _1 i 64",
                "op shl _2 1 _1",
                "op greaterThanEq _3 i 0",
                "op lessThan _4 i 128",
                "op land _5 _3 _4",
                "op mul _2 _2 _5",
                "op mul _0 _0 _5",
                "op add _6 0 _0",
                "read _7 cell1 _6",
                "op or _7 _7 _2",
                "funccall __ls_builtin_bwrite \"cell1, _6, _7\" x"), textOf(ExprCompiler.compile("x", "bset(bs, i)")));

            checkLine(String.join("\n",
                "op idiv _0 i 64",
                "op mod _1 i 64",
                "op shl _2 1 _1",
                "op greaterThanEq _3 i 0",
                "op lessThan _4 i 128",
                "op land _5 _3 _4",
                "op mul _2 _2 _5",
                "op mul _0 _0 _5",
                "op add _6 0 _0",
                "read _7 cell1 _6",
                "op not _8 _2 0",
                "op and _7 _7 _8",
                "funccall __ls_builtin_bwrite \"cell1, _6, _7\" x"), textOf(ExprCompiler.compile("x", "bclr(bs, i)")));

            checkLine(String.join("\n",
                "op idiv _0 i 64",
                "op mod _1 i 64",
                "op shl _2 1 _1",
                "op greaterThanEq _3 i 0",
                "op lessThan _4 i 128",
                "op land _5 _3 _4",
                "op mul _2 _2 _5",
                "op mul _0 _0 _5",
                "op add _6 0 _0",
                "read _7 cell1 _6",
                "op and _8 _7 _2",
                "op notEqual x _8 0"), textOf(ExprCompiler.compile("x", "btest(bs, i)")));

            // 大小写不敏感（与 ExprCompiler 的数学内置一致）
            checkLine("op notEqual x _8 0", lastLine(ExprCompiler.compile("x", "BTEST(bs, i)")));

            // 表达式链里与其它运算组合：下标先编译，结果接回外层
            checkLine(String.join("\n",
                "op add _0 i 1",
                "op idiv _1 _0 64",
                "op mod _2 _0 64",
                "op shl _3 1 _2",
                "op greaterThanEq _4 _0 0",
                "op lessThan _5 _0 128",
                "op land _6 _4 _5",
                "op mul _3 _3 _6",
                "op mul _1 _1 _6",
                "op add _7 0 _1",
                "read _8 cell1 _7",
                "op or _8 _8 _3",
                "funccall __ls_builtin_bwrite \"cell1, _7, _8\" x"), textOf(ExprCompiler.compile("x", "bset(bs, i + 1)")));
            checkLine("funccall __ls_builtin_bitcount \"cell1, 0, 2\" x",
                textOf(ExprCompiler.compile("x", "bcount(bs)")));
            checkLine("funccall __ls_builtin_bitcount \"cell1, 0, 2\" _0\nop add x _0 1",
                textOf(ExprCompiler.compile("x", "bcount(bs) + 1")));
        });
    }

    /** base/words 影响地址折叠与越界常量；bank 容量 512。 */
    private static void indexExpressionsAndCapacity(){
        withBitsets("bitset bs cell1 10 2", () -> {
            String text = textOf(ExprCompiler.compile("x", "bset(bs, i)"));
            check(text.contains("op add _6 10 _0"), "base must fold into the address add:\n" + text);
            check(text.contains("op lessThan _4 i 128"), "capacity guard must use words*64:\n" + text);
        });
        withBitsets("bitset bs bank1 0 3", () -> {
            String text = textOf(ExprCompiler.compile("x", "btest(bs, i)"));
            check(text.contains("op lessThan _4 i 192"), "bank capacity guard must use words*64:\n" + text);
        });
    }

    // ===== 内置函数注入 =====

    private static void bcountBuiltinInjection(){
        // bset/bclr 的内存写回子程序：条件表达式里各调用一次，共享一份函数体
        String writeSugar = "bitset bs cell1 0 2\n"
            + "ifbegin expr \"bset(bs, i) > 0\" 3\n"
            + "set x 1\n"
            + "blockend\n"
            + "ifbegin expr \"bclr(bs, i) > 0\" 6\n"
            + "set y 2\n"
            + "blockend\n";
        String writeMlog = stripCompile(writeSugar);
        check(countOf(writeMlog, "__ls_func___ls_builtin_bwrite_entry:") == 1,
            "write helper must be hoisted exactly once:\n" + writeMlog);
        check(countOf(writeMlog, "jump __ls_func___ls_builtin_bwrite_entry always x false") == 2,
            "each bset/bclr call site must jump to the shared write helper:\n" + writeMlog);
        check(writeMlog.contains("write __ls_func___ls_builtin_bwrite_value __ls_func___ls_builtin_bwrite_mem __ls_func___ls_builtin_bwrite_addr"),
            "write helper body missing/mis-mangled:\n" + writeMlog);
        int writeLines = 0;
        for(String line : writeMlog.split("\n")){
            if(line.trim().startsWith("write ")) writeLines++;
        }
        check(writeLines == 1 && !writeMlog.contains("funccall "),
            "only the shared helper body may contain a write; no funccall may survive:\n" + writeMlog);

        String sugar = "bitset bs cell1 0 2\n"
            + "ifbegin expr \"bcount(bs) > 0\" 3\n"
            + "set x 1\n"
            + "blockend\n"
            + "ifbegin expr \"bcount(bs) < 100\" 6\n"
            + "set y 2\n"
            + "blockend\n";
        String mlog = stripCompile(sugar);
        check(countOf(mlog, "__ls_func___ls_builtin_bitcount_entry:") == 1,
            "builtin body must be hoisted exactly once (shared subroutine):\n" + mlog);
        check(countOf(mlog, "jump __ls_func___ls_builtin_bitcount_entry always x false") == 2,
            "each call site must jump to the shared entry:\n" + mlog);
        check(mlog.contains("set __ls_func___ls_builtin_bitcount_mem cell1"), "memory argument binding missing:\n" + mlog);
        check(mlog.contains("set __ls_func___ls_builtin_bitcount_base 0"), "base argument binding missing:\n" + mlog);
        check(mlog.contains("set __ls_func___ls_builtin_bitcount_words 2"), "words argument binding missing:\n" + mlog);
        check(!mlog.contains("funccall ") && !mlog.contains("ifbegin "), "sugar residue in the product:\n" + mlog);

        // 直接执行产物里的函数体：word0 = 0b1011（3 位），word1 = 2^63（1 位）→ 4
        Map<String, Double> vars = new HashMap<>();
        Map<String, double[]> memories = new HashMap<>();
        double[] words = new double[512];
        words[0] = 0b1011;
        words[1] = -9.223372036854776E18; // Long.MIN_VALUE as double: bit 63 only
        memories.put("__ls_func___ls_builtin_bitcount_mem", words);
        vars.put("__ls_func___ls_builtin_bitcount_base", 0.0);
        vars.put("__ls_func___ls_builtin_bitcount_words", 2.0);
        execute(extractBody(mlog, "__ls_builtin_bitcount"), vars, memories);
        Double counted = vars.get("__ls_func___ls_builtin_bitcount_result");
        check(counted != null && counted == 4.0,
            "bitcount must count set bits across words (expected 4, got " + counted + "):\n" + mlog);
    }

    /** 未使用的内置函数不进入产物。 */
    private static void unusedBuiltinStaysOut(){
        String mlog = stripCompile("bitset bs cell1 0 2\nset x 1\n");
        check(!mlog.contains("__ls_builtin"), "unused builtin leaked into the product:\n" + mlog);
        check(!mlog.contains("__ls_bit_"), "unused builtin body leaked into the product:\n" + mlog);
    }

    // ===== 语义层：用最小解释器执行展开链 =====

    private static void outOfRangeAndBitSemantics(){
        withBitsets("bitset bs cell1 0 2", () -> {
            Map<String, Double> vars = new HashMap<>();
            Map<String, double[]> memories = new HashMap<>();

            execute(toLines(ExprCompiler.compile("r", "bset(bs, 5)")), vars, memories);
            check(valueOf(vars, "r") == 1.0, "bset must return 1");
            check(memoryAt(memories, "cell1", 0) == 32.0, "bset(5) must set bit 5 of word 0");

            execute(toLines(ExprCompiler.compile("r", "bset(bs, 70)")), vars, memories);
            check(memoryAt(memories, "cell1", 1) == 64.0, "bset(70) must set bit 6 of word 1");
            check(memoryAt(memories, "cell1", 0) == 32.0, "bset(70) must not touch word 0");

            execute(toLines(ExprCompiler.compile("r", "btest(bs, 5)")), vars, memories);
            check(valueOf(vars, "r") == 1.0, "btest of a set bit must return 1");
            execute(toLines(ExprCompiler.compile("r", "btest(bs, 6)")), vars, memories);
            check(valueOf(vars, "r") == 0.0, "btest of a clear bit must return 0");

            // 越界：set/clr 忽略（内存不变），test 返回 0
            double before0 = memoryAt(memories, "cell1", 0);
            double before1 = memoryAt(memories, "cell1", 1);
            execute(toLines(ExprCompiler.compile("r", "bset(bs, -1)")), vars, memories);
            check(valueOf(vars, "r") == 1.0, "out-of-range bset must still return 1");
            execute(toLines(ExprCompiler.compile("r", "bset(bs, 128)")), vars, memories);
            execute(toLines(ExprCompiler.compile("r", "bset(bs, 1000)")), vars, memories);
            check(memoryAt(memories, "cell1", 0) == before0 && memoryAt(memories, "cell1", 1) == before1,
                "out-of-range bset must be a no-op");
            execute(toLines(ExprCompiler.compile("r", "btest(bs, -1)")), vars, memories);
            check(valueOf(vars, "r") == 0.0, "btest(-1) must return 0");
            execute(toLines(ExprCompiler.compile("r", "btest(bs, 128)")), vars, memories);
            check(valueOf(vars, "r") == 0.0, "btest(128) must return 0");
            execute(toLines(ExprCompiler.compile("r", "bclr(bs, -1)")), vars, memories);
            execute(toLines(ExprCompiler.compile("r", "bclr(bs, 999)")), vars, memories);
            check(memoryAt(memories, "cell1", 0) == before0 && memoryAt(memories, "cell1", 1) == before1,
                "out-of-range bclr must be a no-op");

            execute(toLines(ExprCompiler.compile("r", "bclr(bs, 5)")), vars, memories);
            check(valueOf(vars, "r") == 1.0, "bclr must return 1");
            check(memoryAt(memories, "cell1", 0) == 0.0, "bclr(5) must clear bit 5");
            execute(toLines(ExprCompiler.compile("r", "btest(bs, 5)")), vars, memories);
            check(valueOf(vars, "r") == 0.0, "btest after bclr must return 0");

            // 高位（word 边界）：bit 64 → word 1 bit 0
            execute(toLines(ExprCompiler.compile("r", "bset(bs, 64)")), vars, memories);
            check(memoryAt(memories, "cell1", 1) == 65.0, "bset(64) must set bit 0 of word 1 (kept bit 6)");
        });
    }

    /** return 表达式里的 bset：链中的 bwrite 调用与 return 临时变量改名都要正确。 */
    private static void returnContext(){
        String sugar = "bitset bs cell1 0 2\n"
            + "funcdef mark x 3\n"
            + "return \"bset(bs, x)\"\n"
            + "blockend\n"
            + "funccall mark 3 r\n";
        String mlog = stripCompile(sugar);
        check(mlog.contains("jump __ls_func___ls_builtin_bwrite_entry always x false"),
            "bwrite call missing from the return lowering:\n" + mlog);
        check(mlog.contains("set __ls_func___ls_builtin_bwrite_addr __ls_rt_mark_")
            || mlog.contains("set __ls_func___ls_builtin_bwrite_addr __ls_func_mark_"),
            "return temp was not renamed into the function namespace:\n" + mlog);
        check(!mlog.contains("funccall "), "sugar residue in the product:\n" + mlog);
    }

    // ===== 声明校验 =====

    private static void declarationValidation(){
        checkCompileThrows("bitset bs cell1 0 0\nset x 1\n", "words = 0");
        checkCompileThrows("bitset bs cell1 0 65\nset x 1\n", "cell capacity overflow");
        checkCompileThrows("bitset bs bank1 500 20\nset x 1\n", "bank capacity overflow");
        checkCompileThrows("bitset bs cell1 0 w\nset x 1\n", "non-literal words");
        checkCompileThrows("bitset bs cell1 x 1\nset x 1\n", "non-literal base");
        checkCompileThrows("bitset bs cell1 -1 1\nset x 1\n", "negative base");
        checkCompileThrows("bitset 1bad cell1 0 1\nset x 1\n", "invalid identifier");
        checkCompileThrows("bitset __ls_bs cell1 0 1\nset x 1\n", "reserved prefix");
        checkCompileThrows("bitset a cell1 0 1\nbitset a cell1 1 1\nset x 1\n", "duplicate name");
        checkCompileThrows("bitset a cell1 0 2\nbitset b cell1 1 1\nset x 1\n", "overlapping ranges");
        checkCompileThrows("bitset bs ~ 0 1\nset x 1\n", "missing memory cell");
        checkCompileThrows("array buf cell1 0 1\nbitset buf cell1 1 1\nset x 1\n", "conflicts with an array");
        checkCompileThrows("matrix m cell1 0 1 1\nbitset m cell1 1 1\nset x 1\n", "conflicts with a matrix");
        checkCollectThrows("bitset foo cell1 0 1", setOf("foo"), "conflicts with a function");
        // 合法边界：base+words 恰好等于容量
        stripCompile("bitset bs cell1 60 4\nset x 1\n");

        // 编辑期标红：字段级问题标红，合法卡不标红，重名只标红后一张
        Seq<LStatement> statements = LAssembler.read("bitset good cell1 0 2\nbitset bad cell1 0 0\n", true);
        boolean[] invalid = SugarCompiler.invalidStatements(statements);
        check(!invalid[0], "valid bitset card was marked invalid");
        check(invalid[1], "words=0 was not marked invalid");
        Seq<LStatement> duplicates = LAssembler.read("bitset a cell1 0 1\nbitset a cell1 1 1\n", true);
        boolean[] dupInvalid = SugarCompiler.invalidStatements(duplicates);
        check(!dupInvalid[0] && dupInvalid[1], "duplicate bitset names must mark only the later card");
    }

    // ===== 表达式错误 =====

    private static void intrinsicErrors(){
        withBitsets("bitset bs cell1 0 2", () -> {
            checkThrows(() -> ExprCompiler.compile("x", "bset(nosuch, i)"), "undeclared bitset");
            checkThrows(() -> ExprCompiler.compile("x", "bclr(1, i)"), "non-name bitset argument");
            checkThrows(() -> ExprCompiler.compile("x", "bcount(nosuch)"), "undeclared bitset in bcount");
            checkThrows(() -> ExprCompiler.compile("x", "btest(bs[0], i)"), "subscript argument");
            // 错误实参个数不是 intrinsic：落到普通 funccall（无 checker 时不报错）
            checkLine("funccall bset \"bs\" x", textOf(ExprCompiler.compile("x", "bset(bs)")));
            checkLine("funccall bcount \"bs, i\" x", textOf(ExprCompiler.compile("x", "bcount(bs, i)")));
        });
    }

    private static void userFunctionWins(){
        withBitsets("bitset bs cell1 0 2", () -> {
            Set<String> previous = ExprIntrinsics.enterUserFunctions(setOf("bset"));
            try{
                checkLine("funccall bset \"bs, i\" x", textOf(ExprCompiler.compile("x", "bset(bs, i)")));
                check(!ExprIntrinsics.isIntrinsicName("bset", 2), "user function must shadow the intrinsic");
            }finally{
                ExprIntrinsics.restoreUserFunctions(previous);
            }
        });
    }

    // ===== 产物纯原版 / 载体往返 / 编辑器可见性 =====

    private static void outputIsPureVanilla(){
        String sugar = "bitset bs cell1 0 2\n"
            + "ifbegin expr \"bset(bs, i) > 0\" 3\n"
            + "set x 1\n"
            + "blockend\n"
            + "ifbegin expr \"btest(bs, i) != 0\" 6\n"
            + "set y 2\n"
            + "blockend\n"
            + "ifbegin expr \"bclr(bs, i) > 0\" 9\n"
            + "set z 3\n"
            + "blockend\n"
            + "ifbegin expr \"bcount(bs) > 0\" 12\n"
            + "set w 4\n"
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

    private static void roundTripAndVerification(){
        String sugar = "bitset bs cell1 0 2\n"
            + "ifbegin expr \"bset(bs, i) > 0\" 3\n"
            + "set x 1\n"
            + "blockend\n"
            + "ifbegin expr \"bcount(bs) > 0\" 6\n"
            + "set y 2\n"
            + "blockend\n";
        String compiled = compile(sugar);
        check(SugarCompiler.restore(compiled).equals(sugar),
            "carrier did not preserve the bitset source:\n" + SugarCompiler.restore(compiled));
        check(SugarCompiler.verifyRestore(compiled, sugar), "verifyRestore rejected a bitset program");
        String recompiled = compile(SugarCompiler.restore(compiled));
        check(SugarCompiler.matchesStoredStream(recompiled, compiled),
            "recompiled bitset program drifted from the stored stream");
    }

    private static void editorVisibility(){
        check(DataModules.builtinFunctionNames().contains(BitsetIntrinsics.BUILTIN_COUNT)
                && DataModules.builtinFunctionNames().contains(BitsetIntrinsics.BUILTIN_WRITE),
            "builtin names are not exposed to the editor");
        List<String> params = SugarFunctions.paramsOf(BitsetIntrinsics.BUILTIN_COUNT, null);
        check(params != null && params.equals(Arrays.asList("mem", "base", "words")),
            "bitcount params are not visible to paramsOf: " + params);
        List<String> writeParams = SugarFunctions.paramsOf(BitsetIntrinsics.BUILTIN_WRITE, null);
        check(writeParams != null && writeParams.equals(Arrays.asList("mem", "addr", "value")),
            "bwrite params are not visible to paramsOf: " + writeParams);
        check(ExprIntrinsics.isIntrinsicName("bset", 2) && ExprIntrinsics.isIntrinsicName("bcount", 1),
            "intrinsic names/arities are not registered");
        check(!ExprIntrinsics.isIntrinsicName("bset", 1) && !ExprIntrinsics.isIntrinsicName("bcount", 2),
            "wrong arity must not be treated as an intrinsic");
    }

    // ===== 最小原版解释器（op/read/write/set/jump，用于语义验证） =====

    private static void execute(List<String> lines, Map<String, Double> vars, Map<String, double[]> memories){
        Map<String, Integer> labels = new HashMap<>();
        List<String> code = new ArrayList<>();
        for(String raw : lines){
            String t = raw.trim();
            if(t.isEmpty()) continue;
            if(t.endsWith(":")){
                labels.put(t.substring(0, t.length() - 1), code.size());
                continue;
            }
            code.add(t);
        }
        int pc = 0, guard = 0;
        while(pc >= 0 && pc < code.size()){
            if(++guard > 500000) throw new AssertionError("program did not terminate:\n" + String.join("\n", code));
            String[] parts = code.get(pc).split("\\s+");
            switch(parts[0]){
                case "set" -> {
                    if(parts[1].equals("@counter")) return; // 函数返回：结果已写入 result 变量
                    vars.put(parts[1], value(parts[2], vars));
                    pc++;
                }
                case "op" -> {
                    double a = value(parts[3], vars), b = value(parts[4], vars);
                    vars.put(parts[2], apply(parts[1], a, b));
                    pc++;
                }
                case "read" -> {
                    double[] memory = memories.computeIfAbsent(parts[2], k -> new double[512]);
                    int address = (int)value(parts[3], vars);
                    vars.put(parts[1], address >= 0 && address < memory.length ? memory[address] : 0.0);
                    pc++;
                }
                case "write" -> {
                    double[] memory = memories.computeIfAbsent(parts[2], k -> new double[512]);
                    int address = (int)value(parts[3], vars);
                    if(address >= 0 && address < memory.length) memory[address] = value(parts[1], vars);
                    pc++;
                }
                case "jump" -> {
                    boolean take = parts[2].equals("always")
                        || apply(parts[2], value(parts[3], vars), value(parts[4], vars)) != 0;
                    if(take){
                        Integer target = labels.get(parts[1]);
                        if(target == null) throw new AssertionError("unresolved label: " + parts[1]);
                        pc = target;
                    }else{
                        pc++;
                    }
                }
                case "funccall" -> {
                    executeWriteCall(code.get(pc), vars, memories);
                    pc++;
                }
                case "end", "stop" -> {
                    return;
                }
                case "noop" -> pc++;
                default -> throw new AssertionError("unsupported instruction: " + code.get(pc));
            }
        }
    }

    /** 执行 {@code funccall __ls_builtin_bwrite "mem, addr, value" dest}（结果恒为 1）。 */
    private static void executeWriteCall(String line, Map<String, Double> vars, Map<String, double[]> memories){
        int open = line.indexOf('"'), close = line.lastIndexOf('"');
        check(open >= 0 && close > open, "malformed funccall: " + line);
        String name = line.substring(0, open).trim().substring("funccall".length()).trim();
        check(name.equals(BitsetIntrinsics.BUILTIN_WRITE), "unexpected funccall in the chain: " + line);
        List<String> args = new ArrayList<>();
        for(String part : line.substring(open + 1, close).split(",")) args.add(part.trim());
        check(args.size() == 3, "bwrite expects 3 arguments: " + line);
        double[] memory = memories.computeIfAbsent(args.get(0), k -> new double[512]);
        int address = (int)value(args.get(1), vars);
        if(address >= 0 && address < memory.length) memory[address] = value(args.get(2), vars);
        String dest = line.substring(close + 1).trim();
        if(!dest.isEmpty() && !dest.equals("~")) vars.put(dest, 1.0);
    }

    /** 与 Mindustry LogicOp 一致的运算语义（double 存储，位运算走 long 转换）。 */
    private static double apply(String op, double a, double b){
        return switch(op){
            case "add" -> a + b;
            case "sub" -> a - b;
            case "mul" -> a * b;
            case "div" -> a / b;
            case "idiv" -> Math.floor(a / b);
            case "mod" -> a % b;
            case "shl" -> (double)((long)a << (long)b);
            case "shr" -> (double)((long)a >> (long)b);
            case "ushr" -> (double)((long)a >>> (long)b);
            case "and" -> (double)((long)a & (long)b);
            case "or" -> (double)((long)a | (long)b);
            case "xor" -> (double)((long)a ^ (long)b);
            case "not" -> (double)(~(long)a);
            case "equal" -> Math.abs(a - b) < 0.000001 ? 1 : 0;
            case "notEqual" -> Math.abs(a - b) < 0.000001 ? 0 : 1;
            case "land" -> a != 0 && b != 0 ? 1 : 0;
            case "lessThan" -> a < b ? 1 : 0;
            case "lessThanEq" -> a <= b ? 1 : 0;
            case "greaterThan" -> a > b ? 1 : 0;
            case "greaterThanEq" -> a >= b ? 1 : 0;
            default -> throw new AssertionError("unsupported op: " + op);
        };
    }

    private static double value(String token, Map<String, Double> vars){
        try{
            return Double.parseDouble(token);
        }catch(NumberFormatException e){
            Double value = vars.get(token);
            return value == null ? 0.0 : value;
        }
    }

    private static double memoryAt(Map<String, double[]> memories, String memory, int address){
        double[] array = memories.get(memory);
        check(array != null, "memory '" + memory + "' was never written");
        return array[address];
    }

    private static double valueOf(Map<String, Double> vars, String name){
        Double value = vars.get(name);
        check(value != null, "variable '" + name + "' was not written by the executed chain");
        return value;
    }

    /** 从编译产物中截取一个注入函数的函数体（entry 与 exit 标签之间）。 */
    private static List<String> extractBody(String mlog, String functionName){
        String entry = "__ls_func_" + functionName + "_entry:";
        String exit = "__ls_func_" + functionName + "_exit:";
        List<String> body = new ArrayList<>();
        boolean inside = false;
        for(String line : mlog.replace("\r\n", "\n").split("\n")){
            String t = line.trim();
            if(t.equals(entry)){
                inside = true;
                continue;
            }
            if(t.equals(exit)) break;
            if(inside) body.add(t);
        }
        check(!body.isEmpty(), "builtin body not found in the product:\n" + mlog);
        return body;
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

    private static String compile(String sugar){
        return SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
    }

    private static String stripCompile(String sugar){
        return SugarCompiler.stripMarkers(compile(sugar));
    }

    private static void withBitsets(String declarations, Runnable body){
        Seq<LStatement> statements = LAssembler.read(declarations, true);
        List<LStatement> list = new ArrayList<>();
        for(LStatement statement : statements) list.add(statement);
        DataModules.collectAll(list, new HashSet<>());
        try{
            body.run();
        }finally{
            DataModules.restore();
        }
    }

    private static List<String> toLines(List<ExprCompiler.Line> ops){
        List<String> lines = new ArrayList<>(ops.size());
        for(ExprCompiler.Line line : ops) lines.add(line.toText());
        return lines;
    }

    private static String textOf(List<ExprCompiler.Line> ops){
        return String.join("\n", toLines(ops));
    }

    private static String lastLine(List<ExprCompiler.Line> ops){
        return ops.get(ops.size() - 1).toText();
    }

    private static int countOf(String text, String needle){
        int count = 0, at = 0;
        while((at = text.indexOf(needle, at)) >= 0){
            count++;
            at += needle.length();
        }
        return count;
    }

    private static Set<String> setOf(String... values){
        return new HashSet<>(Arrays.asList(values));
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
        }finally{
            // collect 阶段抛错时 DataModules 上下文可能未清理（框架只在 collectAll 返回后 restore）
            DataModules.restore();
        }
        check(false, "compile should have failed (" + what + "): " + sugar);
    }

    private static void checkCollectThrows(String declarations, Set<String> functions, String what){
        Seq<LStatement> statements = LAssembler.read(declarations, true);
        List<LStatement> list = new ArrayList<>();
        for(LStatement statement : statements) list.add(statement);
        try{
            DataModules.collectAll(list, functions);
        }catch(IllegalArgumentException e){
            return; // expected
        }finally{
            DataModules.restore();
        }
        check(false, "collect should have failed (" + what + "): " + declarations);
    }

    private static void checkLine(String expected, String actual){
        check(expected.equals(actual), "mlog mismatch\n  expected: " + expected + "\n  actual:   " + actual);
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
