package logicsugar.assist.data;

import arc.struct.Seq;
import logicsugar.assist.expr.ArrayRegistry;
import logicsugar.assist.expr.ExprCompiler;
import logicsugar.assist.expr.ExprIntrinsics;
import logicsugar.assist.expr.ListHeapIntrinsics;
import mindustry.Vars;
import mindustry.logic.GlobalVars;
import mindustry.logic.LAssembler;
import mindustry.logic.LStatement;
import mindustry.logic.SugarCompiler;
import mindustry.logic.SugarFunctions;
import mindustry.logic.SugarStatements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M5 列表 + 小顶堆自测（{@link ListHeapModule} + {@link ListHeapIntrinsics}）。
 *
 * <p>覆盖契约 §5 与本模块规格：</p>
 * <ul>
 *   <li><b>表达式层</b>：10 个操作在编译期注册表上下文中展开为精确的原版链/注入函数调用，
 *       实参按 memory/base/size/count 绑定；未声明名字/种类不匹配/错误实参个数报编译错误。</li>
 *   <li><b>语义层</b>：用一个只认识原版 op/read/write/jump/set/funccall 的小解释器执行展开链，
 *       验证空/满/越界行为、linsert/lremove 的移动语义、lfind 的 -1、以及堆「连续 push 后
 *       依次 pop 升序」。解释器语义对齐原版：越界读 → NaN、越界写空操作、NaN 参与运算按 0
 *       （{@code LVar.num()}）。</li>
 *   <li><b>编译层</b>：声明卡不产指令；内置函数注入（normal 共享一份、未使用不进产物、
 *       不进 {@code __ls_lib} 载体）；产物逐行纯原版指令；载体往返与存储流一致。</li>
 *   <li><b>校验层</b>：重名/保留前缀/非法字面量/容量/区间重叠/与数组矩阵函数重名都在编译期报错，
 *       编辑期 markInvalid 标红。</li>
 * </ul>
 */
public class ListHeapTest{
    public static void main(String[] args){
        SugarStatements.installParsers();
        Vars.logicVars = new GlobalVars();
        DataModules.clearModules();
        DataModules.register(new ListHeapModule());
        DataModules.registerParsers();

        loadBuiltins();
        expressionExpansions();
        argumentErrors();
        listBehaviour();
        heapBehaviour();
        cardRoundTrip();
        declarationValidation();
        markInvalidWiring();
        declarationCardProducesNoLine();
        builtinInjectionAndSharing();
        unusedBuiltinsStayOut();
        outputIsPureVanilla();
        roundTripAndVerification();
        editorVisibility();

        DataModules.clearModules();
        System.out.println("LogicSugar ListHeap self-test passed.");
    }

    // ===== 表达式层：精确展开 =====

    private static void expressionExpansions(){
        withRegistry("list l cell1 2 4", () -> {
            checkLine("op add x __ls_lst_l_count 0", textOf(ExprCompiler.compile("x", "lsize(l)")));
            checkLine("op lessThan _0 i 0\n"
                    + "op lessThanEq _1 __ls_lst_l_count i\n"
                    + "op or _2 _0 _1\n"
                    + "op add _3 2 i\n"
                    + "op add _4 _3 1\n"
                    + "op mul _2 _2 _4\n"
                    + "op sub _3 _3 _2\n"
                    + "read x cell1 _3",
                textOf(ExprCompiler.compile("x", "lget(l, i)")));
            // 字面量下标编译期就是操作数，守卫链相同
            checkLine("op lessThan _0 0 0\n"
                    + "op lessThanEq _1 __ls_lst_l_count 0\n"
                    + "op or _2 _0 _1\n"
                    + "op add _3 2 0\n"
                    + "op add _4 _3 1\n"
                    + "op mul _2 _2 _4\n"
                    + "op sub _3 _3 _2\n"
                    + "read x cell1 _3",
                textOf(ExprCompiler.compile("x", "lget(l, 0)")));
            // lappend：CallLine dest 就是计数变量，表达式结果是新 count
            checkLine("funccall __ls_builtin_lstappend \"cell1, 2, 4, __ls_lst_l_count, 7\" __ls_lst_l_count\n"
                    + "op add x __ls_lst_l_count 0",
                textOf(ExprCompiler.compile("x", "lappend(l, 7)")));
            checkLine("funccall __ls_builtin_lstset \"cell1, 2, __ls_lst_l_count, i, 5\" x",
                textOf(ExprCompiler.compile("x", "lset(l, i, 5)")));
            checkLine("op add _0 __ls_lst_l_count 0\n"
                    + "funccall __ls_builtin_lstinsert \"cell1, 2, 4, __ls_lst_l_count, i, 5\" __ls_lst_l_count\n"
                    + "op notEqual _1 __ls_lst_l_count _0\n"
                    + "op add x _1 0",
                textOf(ExprCompiler.compile("x", "linsert(l, i, 5)")));
            checkLine("op add _0 __ls_lst_l_count 0\n"
                    + "op lessThan _1 i 0\n"
                    + "op greaterThanEq _2 i _0\n"
                    + "op or _3 _1 _2\n"
                    + "op sub _4 1 _3\n"
                    + "op sub __ls_lst_l_count _0 _4\n"
                    + "funccall __ls_builtin_lstremove \"cell1, 2, _0, i\" x",
                textOf(ExprCompiler.compile("x", "lremove(l, i)")));
            checkLine("funccall __ls_builtin_lstfind \"cell1, 2, __ls_lst_l_count, 5\" x",
                textOf(ExprCompiler.compile("x", "lfind(l, 5)")));
            // 大小写不敏感（与 ExprCompiler 的数学内置一致）
            checkLine("op add x __ls_lst_l_count 0", textOf(ExprCompiler.compile("x", "LSIZE(l)")));
            // 与其它运算组合：实参先编译，再发调用
            checkLine("op add _0 i 1\nfunccall __ls_builtin_lstfind \"cell1, 2, __ls_lst_l_count, _0\" _1\nop mul x _1 2",
                textOf(ExprCompiler.compile("x", "lfind(l, i + 1) * 2")));
        });
        withRegistry("heap h cell2 4 8", () -> {
            checkLine("op add x __ls_hep_h_count 0", textOf(ExprCompiler.compile("x", "hsize(h)")));
            checkLine("op add _0 __ls_hep_h_count 0\n"
                    + "funccall __ls_builtin_heppush \"cell2, 4, 8, __ls_hep_h_count, 5\" __ls_hep_h_count\n"
                    + "op notEqual _1 __ls_hep_h_count _0\n"
                    + "op add x _1 0",
                textOf(ExprCompiler.compile("x", "hpush(h, 5)")));
            checkLine("op add _0 __ls_hep_h_count 0\n"
                    + "op greaterThan _1 _0 0\n"
                    + "op sub __ls_hep_h_count _0 _1\n"
                    + "funccall __ls_builtin_heppop \"cell2, 4, 8, _0\" x",
                textOf(ExprCompiler.compile("x", "hpop(h)")));
        });
    }

    // ===== 语义层：列表 =====

    private static void listBehaviour(){
        withRegistry("list l cell1 0 4", () -> {
            double[] memory = new double[64];
            Map<String, Double> vars = new HashMap<>();

            // 空列表
            exec("lsize(l)", vars, memory);
            check(num(vars, "x") == 0, "empty list size must be 0");
            exec("lget(l, 0)", vars, memory);
            check(Double.isNaN(raw(vars, "x")), "lget on an empty list must return NaN");
            exec("lremove(l, 0)", vars, memory);
            check(Double.isNaN(raw(vars, "x")) && num(vars, "__ls_lst_l_count") == 0,
                "lremove on an empty list must return NaN and keep count");
            exec("lfind(l, 5)", vars, memory);
            check(num(vars, "x") == -1, "lfind on an empty list must return -1");

            // lappend：返回新 count、写 base+count
            exec("lappend(l, 30)", vars, memory);
            check(num(vars, "x") == 1 && num(vars, "__ls_lst_l_count") == 1, "first append must return 1");
            check(memory[0] == 30, "append must write at base+count, got " + memory[0]);
            exec("lappend(l, 10)", vars, memory);
            exec("lappend(l, 20)", vars, memory);
            check(num(vars, "x") == 3, "third append must return 3");
            check(memory[0] == 30 && memory[1] == 10 && memory[2] == 20,
                "append order wrong: " + Arrays.toString(memory));

            // lget 边界
            exec("lget(l, 1)", vars, memory);
            check(num(vars, "x") == 10, "lget must return the element at the index");
            exec("lget(l, 3)", vars, memory);
            check(Double.isNaN(raw(vars, "x")), "lget at count must return NaN");
            exec("lget(l, -1)", vars, memory);
            check(Double.isNaN(raw(vars, "x")), "lget with a negative index must return NaN");
            exec("lget(l, 1000)", vars, memory);
            check(Double.isNaN(raw(vars, "x")), "lget far out of bounds must return NaN");

            // lset 边界
            exec("lset(l, 1, 99)", vars, memory);
            check(num(vars, "x") == 1 && memory[1] == 99, "lset in range must return 1 and write");
            exec("lset(l, 3, 5)", vars, memory);
            check(num(vars, "x") == 0 && memory[3] == 0, "lset at count must return 0 and not write");
            exec("lset(l, -1, 5)", vars, memory);
            check(num(vars, "x") == 0, "lset with a negative index must return 0");

            // linsert：右移语义 [30, 99, 20] → [30, 15, 99, 20]
            exec("linsert(l, 1, 15)", vars, memory);
            check(num(vars, "x") == 1 && num(vars, "__ls_lst_l_count") == 4, "linsert must return 1 and grow count");
            check(memory[0] == 30 && memory[1] == 15 && memory[2] == 99 && memory[3] == 20,
                "linsert must shift elements right: " + Arrays.toString(memory));
            exec("linsert(l, 4, 7)", vars, memory);
            check(num(vars, "x") == 0 && num(vars, "__ls_lst_l_count") == 4,
                "linsert into a full list must return 0 and keep count");
            check(memory[3] == 20, "full linsert must not overwrite the last element");
            exec("linsert(l, 5, 7)", vars, memory);
            check(num(vars, "x") == 0, "linsert past count must return 0");
            exec("linsert(l, -1, 7)", vars, memory);
            check(num(vars, "x") == 0, "linsert with a negative index must return 0");

            // lremove：左移语义 [30, 15, 99, 20] → 删除下标 1 → [30, 99, 20]
            exec("lremove(l, 1)", vars, memory);
            check(num(vars, "x") == 15 && num(vars, "__ls_lst_l_count") == 3,
                "lremove must return the removed value and shrink count");
            check(memory[0] == 30 && memory[1] == 99 && memory[2] == 20,
                "lremove must shift elements left: " + Arrays.toString(memory));
            exec("lremove(l, 3)", vars, memory);
            check(Double.isNaN(raw(vars, "x")) && num(vars, "__ls_lst_l_count") == 3,
                "lremove at count must return NaN and keep count");
            exec("lremove(l, -1)", vars, memory);
            check(Double.isNaN(raw(vars, "x")) && num(vars, "__ls_lst_l_count") == 3,
                "lremove with a negative index must return NaN and keep count");

            // lfind：首个匹配 / 未找到 -1
            exec("lfind(l, 99)", vars, memory);
            check(num(vars, "x") == 1, "lfind must return the first matching index");
            exec("lfind(l, 12345)", vars, memory);
            check(num(vars, "x") == -1, "lfind must return -1 when the value is missing");
            exec("lappend(l, 99)", vars, memory);
            exec("lfind(l, 99)", vars, memory);
            check(num(vars, "x") == 1, "lfind must return the first of several matches");

            // 删空后可复用
            exec("lremove(l, 0)", vars, memory);
            exec("lremove(l, 0)", vars, memory);
            exec("lremove(l, 0)", vars, memory);
            exec("lremove(l, 0)", vars, memory);
            check(num(vars, "__ls_lst_l_count") == 0, "list must be empty after removing everything");
            exec("lappend(l, 42)", vars, memory);
            check(num(vars, "x") == 1 && memory[0] == 42, "list must be reusable after being emptied");
            // 下标 0 的插入/删除（移位循环的边界）
            exec("linsert(l, 0, 7)", vars, memory);
            check(num(vars, "x") == 1 && num(vars, "__ls_lst_l_count") == 2 && memory[0] == 7 && memory[1] == 42,
                "linsert at 0 must shift everything right: " + Arrays.toString(memory));
            exec("lremove(l, 0)", vars, memory);
            check(num(vars, "x") == 7 && num(vars, "__ls_lst_l_count") == 1 && memory[0] == 42,
                "lremove at 0 must shift everything left: " + Arrays.toString(memory));
        });
    }

    // ===== 语义层：小顶堆 =====

    private static void heapBehaviour(){
        withRegistry("heap h cell2 3 8", () -> {
            double[] memory = new double[64];
            Map<String, Double> vars = new HashMap<>();

            exec("hsize(h)", vars, memory);
            check(num(vars, "x") == 0, "empty heap size must be 0");
            exec("hpop(h)", vars, memory);
            check(Double.isNaN(raw(vars, "x")) && num(vars, "__ls_hep_h_count") == 0,
                "hpop on an empty heap must return NaN and keep count");

            int[] input = {5, 3, 9, 1, 7, 2, 8, 4};
            for(int value : input){
                exec("hpush(h, " + value + ")", vars, memory);
                check(num(vars, "x") == 1, "hpush must return 1, value " + value);
            }
            check(num(vars, "__ls_hep_h_count") == 8, "heap count after 8 pushes");
            // 堆内存不变量：每个节点不大于其孩子（下标 3 = base + i）
            for(int i = 0; i < 4; i++){
                int left = 2 * i + 1, right = 2 * i + 2;
                if(left < 8) check(memory[3 + i] <= memory[3 + left], "heap invariant broken at " + i + " (left)");
                if(right < 8) check(memory[3 + i] <= memory[3 + right], "heap invariant broken at " + i + " (right)");
            }
            exec("hpush(h, 0)", vars, memory);
            check(num(vars, "x") == 0 && num(vars, "__ls_hep_h_count") == 8,
                "hpush into a full heap must return 0 and keep count");
            for(int i = 3; i < 3 + 8; i++){
                check(memory[i] >= 1, "full hpush must not overwrite memory, got " + memory[i]);
            }

            int[] sorted = {1, 2, 3, 4, 5, 7, 8, 9};
            for(int expected : sorted){
                exec("hpop(h)", vars, memory);
                check(num(vars, "x") == expected,
                    "hpop order broken: expected " + expected + " but got " + num(vars, "x"));
            }
            exec("hpop(h)", vars, memory);
            check(Double.isNaN(raw(vars, "x")) && num(vars, "__ls_hep_h_count") == 0,
                "over-pop must return NaN and stay empty");

            // 重复值 / 重新入堆
            exec("hpush(h, 4)", vars, memory);
            exec("hpush(h, 4)", vars, memory);
            exec("hpush(h, 2)", vars, memory);
            exec("hsize(h)", vars, memory);
            check(num(vars, "x") == 3, "hsize must report the element count");
            exec("hpop(h)", vars, memory);
            check(num(vars, "x") == 2, "hpop must return the smallest value");
            exec("hpop(h)", vars, memory);
            check(num(vars, "x") == 4, "hpop must return duplicates in order");
        });
        // 逆序输入：每个新值都要上滤到根（sift-up 深路径）
        withRegistry("heap h2 cell3 0 8", () -> {
            double[] memory = new double[64];
            Map<String, Double> vars = new HashMap<>();
            for(int value = 8; value >= 1; value--){
                exec("hpush(h2, " + value + ")", vars, memory);
                check(num(vars, "x") == 1, "descending hpush must return 1");
            }
            for(int expected = 1; expected <= 8; expected++){
                exec("hpop(h2)", vars, memory);
                check(num(vars, "x") == expected,
                    "descending heap pop order broken: expected " + expected + " but got " + num(vars, "x"));
            }
        });
    }

    // ===== 声明卡 =====

    private static void cardRoundTrip(){
        Seq<LStatement> cards = LAssembler.read("list l cell3 4 8\nheap h cell4 12 16\n", true);
        check(cards.size == 2, "expected two declaration cards");
        check(cards.get(0) instanceof ListHeapModule.ListDeclStatement, "first card must be a list declaration");
        check(cards.get(1) instanceof ListHeapModule.HeapDeclStatement, "second card must be a heap declaration");
        check(cardText(cards.get(0)).equals("list l cell3 4 8"), "list card text mismatch: " + cardText(cards.get(0)));
        check(cardText(cards.get(1)).equals("heap h cell4 12 16"), "heap card text mismatch: " + cardText(cards.get(1)));
        check(ListHeapModule.stateVar(ListHeapModule.KIND_LIST, "l").equals("__ls_lst_l_count"),
            "list state variable name mismatch");
        check(ListHeapModule.stateVar(ListHeapModule.KIND_HEAP, "h").equals("__ls_hep_h_count"),
            "heap state variable name mismatch");
        // 空槽 ~ 解析为空串（非法字面量由编译期校验拦截）
        Seq<LStatement> slots = LAssembler.read("list l cell3 ~ 8\n", true);
        check(cardText(slots.get(0)).equals("list l cell3 ~ 8"), "empty slot must round trip as ~: " + cardText(slots.get(0)));
    }

    private static void declarationValidation(){
        checkThrows("list l cell1 0 8\nlist l cell1 8 8", "duplicate list name");
        checkThrows("list l cell1 0 8\nheap l cell1 8 8", "list/heap name collision");
        checkThrows("heap h cell1 0 8\nheap h cell1 8 8", "duplicate heap name");
        checkThrows("list __ls_bad cell1 0 8", "reserved prefix");
        checkThrows("list 1bad cell1 0 8", "invalid identifier");
        checkThrows("heap h ~ 0 8", "missing memory");
        checkThrows("list l cell1 ~ 8", "non-integer base");
        checkThrows("list l cell1 -1 8", "negative base");
        checkThrows("list l cell1 0 0", "size below 1");
        checkThrows("list l cell1 0 abc", "non-integer size");
        checkThrows("heap h cell1 0 1.5", "non-integer size literal");
        checkThrows("list l cell1 60 8", "cell capacity exceeded");
        checkThrows("heap h bank1 508 8", "bank capacity exceeded");
        checkThrows("list a cell1 0 8\nheap b cell1 4 8", "overlapping ranges on one memory");
        checkThrowsWithArrays("list buf cell1 8 8", "array buf cell1 0 8", "list name conflicts with an array");
        checkThrowsWithArrays("heap m cell1 8 8", "matrix m cell1 0 2 2", "heap name conflicts with a matrix");
        checkThrowsWithFunctions("list f cell1 0 8", Collections.singleton("f"), "list name conflicts with a function");
        ListHeapModule.Registry registry = ListHeapModule.compileRegistry(
            statements("list a cell1 0 8\nheap b cell2 0 8"), null);
        check(registry.size() == 2, "distinct memories must not conflict");
    }

    private static void markInvalidWiring(){
        Seq<LStatement> statements = LAssembler.read("list l cell1 0 8\nheap l cell1 8 8\n", true);
        boolean[] invalid = SugarCompiler.invalidStatements(statements);
        check(!invalid[0], "valid list card was marked invalid");
        check(invalid[1], "duplicate list/heap name was not marked invalid");
        Seq<LStatement> good = LAssembler.read("list l cell1 0 8\nheap h cell2 0 8\n", true);
        boolean[] goodInvalid = SugarCompiler.invalidStatements(good);
        check(!goodInvalid[0] && !goodInvalid[1], "valid declarations were marked invalid");
    }

    private static void declarationCardProducesNoLine(){
        String sugar = "list l cell1 0 8\nheap h cell2 0 8\nset x 1\n";
        String mlog = stripCompile(sugar);
        check(!mlog.contains("list ") && !mlog.contains("heap "),
            "declaration cards leaked into the product:\n" + mlog);
        check(mlog.contains("set x 1"), "regular instruction lost:\n" + mlog);
        String compiled = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        check(SugarCompiler.restore(compiled).equals(sugar),
            "declaration cards did not survive the carrier round trip:\n" + SugarCompiler.restore(compiled));
    }

    // ===== 编译层：注入函数 =====

    private static void builtinInjectionAndSharing(){
        String sugar = "list l cell1 0 4\n"
            + "ifbegin expr \"lappend(l, 1) > 0\" 3\nset x 1\nblockend\n"
            + "ifbegin expr \"lappend(l, 2) > 0\" 6\nset y 2\nblockend\n";
        String mlog = stripCompile(sugar);
        check(countOf(mlog, "__ls_func___ls_builtin_lstappend_entry:") == 1,
            "append body must be hoisted exactly once (shared subroutine):\n" + mlog);
        check(countOf(mlog, "jump __ls_func___ls_builtin_lstappend_entry always x false") == 2,
            "each append call site must jump to the shared entry:\n" + mlog);
        check(mlog.contains("set __ls_func___ls_builtin_lstappend_mem cell1"), "memory binding missing:\n" + mlog);
        check(mlog.contains("set __ls_func___ls_builtin_lstappend_base 0"), "base binding missing:\n" + mlog);
        check(mlog.contains("set __ls_func___ls_builtin_lstappend_size 4"), "size binding missing:\n" + mlog);
        check(mlog.contains("set __ls_func___ls_builtin_lstappend_count __ls_lst_l_count"),
            "count binding missing:\n" + mlog);
        check(mlog.contains("write __ls_func___ls_builtin_lstappend_v __ls_func___ls_builtin_lstappend_mem __ls_lh_a"),
            "append body write missing/mis-mangled:\n" + mlog);
        check(mlog.contains("set __ls_lst_l_count __ls_func___ls_builtin_lstappend_result"),
            "append result must be written back to the state variable:\n" + mlog);
        check(!mlog.contains("funccall ") && !mlog.contains("ifbegin ") && !mlog.contains("list "),
            "sugar residue in the product:\n" + mlog);

        // 堆：push 回写计数、pop 递减计数并返回函数结果
        String heap = "heap h cell2 0 4\n"
            + "ifbegin expr \"hpush(h, 5) > 0\" 3\nset a 1\nblockend\n"
            + "ifbegin expr \"hpop(h) > 0\" 6\nset b 2\nblockend\n";
        String heapMlog = stripCompile(heap);
        check(countOf(heapMlog, "__ls_func___ls_builtin_heppush_entry:") == 1,
            "push body must be hoisted exactly once:\n" + heapMlog);
        check(heapMlog.contains("set __ls_func___ls_builtin_heppush_count __ls_hep_h_count"),
            "push count binding missing:\n" + heapMlog);
        check(heapMlog.contains("set __ls_hep_h_count __ls_func___ls_builtin_heppush_result"),
            "push result must be written back to the state variable:\n" + heapMlog);
        check(heapMlog.contains("set __ls_func___ls_builtin_heppop_count "),
            "pop count argument binding missing:\n" + heapMlog);
        check(heapMlog.contains("__ls_func___ls_builtin_heppop_result"),
            "pop result missing from the product:\n" + heapMlog);
        check(heapMlog.contains("op div __ls_lh_r 0 0"), "empty-pop NaN sentinel missing:\n" + heapMlog);
        check(!heapMlog.contains("funccall ") && !heapMlog.contains("heap "),
            "sugar residue in the heap product:\n" + heapMlog);
    }

    private static void unusedBuiltinsStayOut(){
        String mlog = stripCompile("list l cell1 0 4\nheap h cell2 0 4\n"
            + "ifbegin expr \"lsize(l) + hsize(h) > 0\" 4\nset x 1\nblockend\n");
        check(!mlog.contains("__ls_builtin"), "unused builtin leaked into the product:\n" + mlog);
        check(!mlog.contains("__ls_lh_"), "unused builtin body leaked into the product:\n" + mlog);
    }

    private static void outputIsPureVanilla(){
        String sugar = "list l cell1 0 4\n"
            + "heap h cell2 0 4\n"
            + "ifbegin expr \"lappend(l, 5) > 0\" 4\nset a 1\nblockend\n"
            + "ifbegin expr \"lset(l, 0, 3) > 0\" 7\nset b 2\nblockend\n"
            + "ifbegin expr \"linsert(l, 0, 4) > 0\" 10\nset c 3\nblockend\n"
            + "ifbegin expr \"lremove(l, 0) >= 0\" 13\nset d 4\nblockend\n"
            + "ifbegin expr \"lfind(l, 5) >= 0\" 16\nset e 5\nblockend\n"
            + "ifbegin expr \"hpush(h, 5) > 0\" 19\nset f 6\nblockend\n"
            + "ifbegin expr \"hpop(h) >= 0\" 22\nset g 7\nblockend\n"
            + "ifbegin expr \"lsize(l) + hsize(h) > 0\" 25\nset i 8\nblockend\n"
            + "ifbegin expr \"lget(l, 0) >= 0\" 28\nset j 9\nblockend\n";
        String mlog = stripCompile(sugar);
        for(String line : mlog.replace("\r\n", "\n").split("\n", -1)){
            String t = line.trim();
            if(t.isEmpty() || t.endsWith(":") || t.startsWith("#")) continue;
            int space = t.indexOf(' ');
            String opcode = space < 0 ? t : t.substring(0, space);
            check(vanillaOpcodes.contains(opcode), "non-vanilla instruction in product: " + t);
        }
        check(mlog.contains("__ls_lh_r"), "heap/remove builtin bodies missing from the product:\n" + mlog);
    }

    private static void roundTripAndVerification(){
        String sugar = "list l cell1 0 4\n"
            + "heap h cell2 0 4\n"
            + "ifbegin expr \"lappend(l, 1) > 0\" 4\nset x 1\nblockend\n"
            + "ifbegin expr \"hpop(h) > 0\" 7\nset y 2\nblockend\n";
        String compiled = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        check(SugarCompiler.restore(compiled).equals(sugar),
            "carrier did not preserve the list/heap source:\n" + SugarCompiler.restore(compiled));
        check(SugarCompiler.verifyRestore(compiled, sugar), "verifyRestore rejected a list/heap program");
        String recompiled = SugarCompiler.compile(SugarCompiler.restore(compiled), SugarCompiler.FuncMode.normal,
            SugarFunctions.library(), null, SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        check(SugarCompiler.matchesStoredStream(recompiled, compiled),
            "recompiled list/heap program drifted from the stored stream");
    }

    private static void editorVisibility(){
        check(DataModules.builtinFunctionNames().contains("__ls_builtin_lstappend"), "builtin names are not exposed");
        check(DataModules.builtinFunctionNames().contains("__ls_builtin_heppop"), "heap builtin names are not exposed");
        check(SugarFunctions.paramsOf("__ls_builtin_heppush", null) != null
            && SugarFunctions.paramsOf("__ls_builtin_heppush", null).equals(Arrays.asList("mem", "base", "size", "count", "v")),
            "builtin params are not visible to paramsOf");
        check(ExprIntrinsics.isIntrinsicName("lappend", 2) && !ExprIntrinsics.isIntrinsicName("lappend", 1),
            "lappend arity dispatch is broken");
        check(ExprIntrinsics.isIntrinsicName("lget", 2) && ExprIntrinsics.isIntrinsicName("lset", 3),
            "list intrinsic names/arities are not registered");
        check(ExprIntrinsics.isIntrinsicName("hpush", 2) && ExprIntrinsics.isIntrinsicName("hpop", 1),
            "heap intrinsic names/arities are not registered");
        check(!ExprIntrinsics.isIntrinsicName("hpop", 2) && !ExprIntrinsics.isIntrinsicName("lsize", 2),
            "wrong arity must not be treated as an intrinsic");
        check(ListHeapIntrinsics.builtinSugar().size() == 7, "expected 7 builtin function bodies");
    }

    // ===== 错误路径 =====

    private static void argumentErrors(){
        withRegistry("list l cell1 0 4\nheap h cell2 0 4", () -> {
            checkThrowsExpr(() -> ExprCompiler.compile("x", "lsize(nosuch)"), "undeclared name");
            checkThrowsExpr(() -> ExprCompiler.compile("x", "lget(h, 0)"), "list op on a heap");
            checkThrowsExpr(() -> ExprCompiler.compile("x", "hpop(l)"), "heap op on a list");
            checkThrowsExpr(() -> ExprCompiler.compile("x", "lappend(l[0], 1)"), "subscript argument");
            checkThrowsExpr(() -> ExprCompiler.compile("x", "lsize(1 + 2)"), "expression argument");
        });
        // 没有 list/heap 声明上下文时给出明确错误（编辑器无画布/纯表达式预览）
        checkThrowsExpr(() -> ExprCompiler.compile("x", "lsize(l)"), "no declaration context");
    }

    // ===== 最小原版解释器（op/read/write/set/jump/funccall） =====

    private static void run(List<ExprCompiler.Line> lines, Map<String, Double> vars, double[] memory){
        for(ExprCompiler.Line line : lines){
            if(line instanceof ExprCompiler.ReadLine read){
                int address = (int)num(vars, read.b);
                vars.put(read.dest, address >= 0 && address < memory.length ? memory[address] : Double.NaN);
            }else if(line instanceof ExprCompiler.CallLine call){
                runBuiltin(call, vars, memory);
            }else if(line instanceof ExprCompiler.OpLine op){
                vars.put(op.dest, apply(op.op, num(vars, op.a), num(vars, op.b)));
            }else{
                throw new AssertionError("unexpected line in a list/heap expansion: " + line.toText());
            }
        }
    }

    /** 执行注入函数体（逐行解释 jump/op/set/read/write/return；跳转目标按函数文本下标解析）。 */
    private static void runBuiltin(ExprCompiler.CallLine call, Map<String, Double> vars, double[] memory){
        Builtin builtin = builtins.get(call.name);
        check(builtin != null, "unknown injected function in a list/heap expansion: " + call.name);
        Map<String, Double> local = new HashMap<>();
        String[] args = call.args.split(",");
        check(args.length == builtin.params.size(), "arity mismatch for " + call.name);
        for(int i = 0; i < args.length; i++){
            local.put(builtin.params.get(i), num(vars, args[i].trim()));
        }
        int pc = 0, guard = 0;
        while(pc >= 0 && pc < builtin.body.size()){
            if(++guard > 100000) throw new AssertionError("builtin did not terminate: " + call.name);
            String[] tokens = builtin.body.get(pc).split("\\s+");
            switch(tokens[0]){
                case "jump": {
                    if(condition(tokens[2], num(local, tokens[3]), num(local, tokens[4]))){
                        pc = Integer.parseInt(tokens[1]) - 1; // 文本下标 1 = body[0]
                        continue;
                    }
                    break;
                }
                case "op":
                    local.put(tokens[2], apply(tokens[1], num(local, tokens[3]), num(local, tokens[4])));
                    break;
                case "set":
                    local.put(tokens[1], num(local, tokens[2]));
                    break;
                case "read": {
                    int address = (int)num(local, tokens[3]);
                    local.put(tokens[1], address >= 0 && address < memory.length ? memory[address] : Double.NaN);
                    break;
                }
                case "write": {
                    int address = (int)num(local, tokens[3]);
                    if(address >= 0 && address < memory.length){
                        memory[address] = num(local, tokens[1]);
                    }
                    break;
                }
                case "return":
                    vars.put(call.dest, local.get(tokens[1].replace("\"", "")));
                    return;
                default:
                    throw new AssertionError("unexpected statement in a builtin body: " + builtin.body.get(pc));
            }
            pc++;
        }
        throw new AssertionError("builtin did not return: " + call.name);
    }

    private static final class Builtin{
        final List<String> params = new ArrayList<>();
        final List<String> body = new ArrayList<>();
    }

    private static final Map<String, Builtin> builtins = new LinkedHashMap<>();

    private static void loadBuiltins(){
        for(String text : ListHeapIntrinsics.builtinSugar()){
            String[] lines = text.trim().split("\n");
            String[] header = lines[0].split("\\s+");
            Builtin builtin = new Builtin();
            for(String param : header[2].split(",")){
                builtin.params.add(param.trim());
            }
            for(int i = 1; i < lines.length - 1; i++){
                builtin.body.add(lines[i].trim());
            }
            builtins.put(header[1], builtin);
        }
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
            case "min" -> Math.min(a, b);
            case "max" -> Math.max(a, b);
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

    private static boolean condition(String op, double a, double b){
        switch(op){
            case "always": return true;
            case "greaterThanEq": return a >= b;
            case "greaterThan": return a > b;
            case "lessThanEq": return a <= b;
            case "lessThan": return a < b;
            case "equal": return Math.abs(a - b) < 0.000001;
            case "notEqual": return Math.abs(a - b) >= 0.000001;
            default: throw new AssertionError("unexpected jump condition: " + op);
        }
    }

    /** 操作数求值：变量优先，否则数字字面量；NaN/Inf 按原版 {@code LVar.num()} 视作 0。 */
    private static double num(Map<String, Double> vars, String operand){
        Double value = vars.get(operand);
        if(value == null){
            try{
                value = Double.parseDouble(operand);
            }catch(NumberFormatException e){
                return 0;
            }
        }
        return Double.isNaN(value) || Double.isInfinite(value) ? 0 : value;
    }

    /** 原始值（不做 NaN → 0 归一），用于断言「返回 NaN」。 */
    private static double raw(Map<String, Double> vars, String operand){
        Double value = vars.get(operand);
        if(value == null){
            try{
                return Double.parseDouble(operand);
            }catch(NumberFormatException e){
                return 0;
            }
        }
        return value;
    }

    private static void exec(String expr, Map<String, Double> vars, double[] memory){
        run(ExprCompiler.compile("x", expr), vars, memory);
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

    private static void withRegistry(String declarations, Runnable body){
        ListHeapModule.Registry registry = ListHeapModule.compileRegistry(statements(declarations), null);
        ListHeapModule.Registry previous = ListHeapModule.enter(registry);
        try{
            body.run();
        }finally{
            ListHeapModule.leave(previous);
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

    private static void checkThrows(String declarations, String what){
        checkThrowsFns(declarations, null, what);
    }

    private static void checkThrowsWithArrays(String declarations, String arrayDeclarations, String what){
        ArrayRegistry previous = ArrayRegistry.enter(
            ArrayRegistry.compileRegistry(LAssembler.read(arrayDeclarations, true), null));
        try{
            checkThrowsFns(declarations, null, what);
        }finally{
            ArrayRegistry.restore(previous);
        }
    }

    private static void checkThrowsWithFunctions(String declarations, Set<String> functions, String what){
        checkThrowsFns(declarations, functions, what);
    }

    private static void checkThrowsFns(String declarations, Set<String> functions, String what){
        try{
            ListHeapModule.compileRegistry(statements(declarations), functions);
        }catch(IllegalArgumentException e){
            return; // expected
        }
        check(false, "declaration should have failed (" + what + "): " + declarations);
    }

    private static List<LStatement> statements(String sugar){
        Seq<LStatement> seq = LAssembler.read(sugar, true);
        List<LStatement> list = new ArrayList<>(seq.size);
        for(LStatement statement : seq) list.add(statement);
        return list;
    }

    private static String cardText(LStatement statement){
        StringBuilder out = new StringBuilder();
        statement.write(out);
        return out.toString();
    }

    private static void checkThrowsExpr(Runnable body, String what){
        try{
            body.run();
        }catch(ExprCompiler.ParseException e){
            return; // expected
        }
        check(false, "expression should have failed (" + what + ")");
    }

    private static void checkLine(String expected, String actual){
        check(expected.equals(actual), "mlog mismatch\n  expected: " + expected + "\n  actual:   " + actual);
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
