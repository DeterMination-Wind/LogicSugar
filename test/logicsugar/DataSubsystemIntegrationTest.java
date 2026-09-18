package logicsugar;

import arc.struct.Seq;
import logicsugar.assist.data.DataModule;
import logicsugar.assist.data.DataModules;
import logicsugar.assist.expr.ExprIntrinsics;
import mindustry.gen.LogicIO;
import mindustry.logic.LAssembler;
import mindustry.logic.LStatement;
import mindustry.logic.SugarCompiler;
import mindustry.logic.SugarFunctions;
import mindustry.logic.SugarStatements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * INT 集成自测：验证生产注册路径（{@link LogicSugarMod#init()}）而不是测试专用的
 * {@code clearModules()+register(...)} 路径。
 *
 * <ul>
 *   <li><b>注册幂等</b>：重复 init 不重复向 {@code LogicIO.allStatements} 添加卡片，
 *       parser / intrinsic provider 保持一份；全部数据声明卡与表达式内置可见。</li>
 *   <li><b>端到端</b>：数组/矩阵/记录/栈/队列/位集/哈希表/列表/堆混合程序经生产注册的模块
 *       编译，{@code stripMarkers} 产物只含原版指令，声明卡文本不泄漏。</li>
 *   <li><b>上下文泄漏</b>：任一模块 {@code collect} 抛异常时 {@code DataModules.restore()}
 *       仍被配对调用（编译异常路径不把编译期注册表泄漏给下一次编译）。</li>
 * </ul>
 */
public class DataSubsystemIntegrationTest{
    private static boolean armCollectFailure;
    private static int failingRestoreCalls;

    public static void main(String[] args){
        // 生产注册路径：与游戏内 Mod.init() 完全一致（不调用测试专用的 clearModules）。
        LogicSugarMod mod = new LogicSugarMod();
        mod.init();

        productionRegistrationIsIdempotent(mod);
        dataDeclarationsAreVisible();
        endToEndStructuresCompileToVanilla();
        renamedOpsLowerIdentically();
        collectFailureRestoresContext();
        stillCompilesAfterCollectFailure();

        System.out.println("LogicSugar data subsystem integration self-test passed.");
    }

    // ===== 生产注册 =====

    private static void productionRegistrationIsIdempotent(LogicSugarMod mod){
        int first = LogicIO.allStatements.size;
        check(first > 0, "production init registered no statements");

        // 每个数据声明卡在调色板里恰好出现一次
        check(countClass(SugarStatements.MatrixStatement.class) == 1, "matrix card registered " + countClass(SugarStatements.MatrixStatement.class) + " times");
        check(countClass(SugarStatements.ArrayInitStatement.class) == 0,
            "legacy arrayinit card must not remain in the palette");
        check(LAssembler.customParsers.containsKey("arrayinit"),
            "legacy arrayinit parser must remain available for saved carriers");
        check(countClass(logicsugar.assist.data.RecordModule.RecordStatement.class) == 1, "record card registered more than once");
        check(countClass(logicsugar.assist.data.ContainerModule.StackDeclStatement.class) == 1, "stack card registered more than once");
        check(countClass(logicsugar.assist.data.ContainerModule.QueueDeclStatement.class) == 1, "queue card registered more than once");
        check(countClass(logicsugar.assist.data.ContainerModule.DequeDeclStatement.class) == 1, "deque card registered more than once");
        check(countClass(logicsugar.assist.data.BitsetModule.BitsetStatement.class) == 1, "bitset card registered more than once");
        check(countClass(logicsugar.assist.data.MapModule.MapStatement.class) == 1, "map card registered more than once");
        check(countClass(logicsugar.assist.data.SetModule.USetStatement.class) == 1, "uset card registered more than once");
        check(countClass(logicsugar.assist.data.ListHeapModule.ListDeclStatement.class) == 1, "list card registered more than once");
        check(countClass(logicsugar.assist.data.ListHeapModule.HeapDeclStatement.class) == 1, "heap card registered more than once");

        check(new SugarStatements.ForBeginStatement().category() == SugarStatements.advancedControl,
            "for/while/switch should leave vanilla Flow Control");
        check(new SugarStatements.ArrayStatement().category() == SugarStatements.arrayAlgo
            && new SugarStatements.ArrayInitStatement().category() == SugarStatements.arrayAlgo,
            "array cards should be in the array-algorithm category");
        check(new logicsugar.assist.data.ContainerModule.StackDeclStatement().category() == SugarStatements.dataStructures
            && new logicsugar.assist.data.SetModule.USetStatement().category() == SugarStatements.dataStructures,
            "declaration cards should be in the data-structure category");

        // 重复 init：卡片列表长度不变（registered 守卫 + 模块 register 幂等）
        mod.init();
        int second = LogicIO.allStatements.size;
        check(second == first, "repeated init changed allStatements size: " + first + " -> " + second);
        check(countClass(SugarStatements.MatrixStatement.class) == 1, "repeated init duplicated the matrix card");
        check(countClass(logicsugar.assist.data.RecordModule.RecordStatement.class) == 1, "repeated init duplicated the record card");
    }

    private static void dataDeclarationsAreVisible(){
        // 声明卡解析器（LAssembler.customParsers）
        for(String token : Arrays.asList("matrix", "arrayinit", "record", "stack", "queue", "deque", "bitset", "map", "uset", "list", "heap", "chain")){
            check(LAssembler.customParsers.containsKey(token), "missing declaration parser: " + token);
        }
        // 表达式内置（生产注册即注册 provider，无需先编译）
        Set<String> expected = new HashSet<>(Arrays.asList(
            "sum", "avg", "min", "max", "count", "indexof", "fill", "copy", "sortasc", "sortdesc",
            "reverse", "replace", "swap", "bsearch",
            "spush", "spop", "speek", "ssize", "sclear",
            "qpush", "qpop", "qpeek", "qsize", "qclear",
            "dpushf", "dpushb", "dpopf", "dpopb", "dpeekf", "dpeekb", "dsize", "dclear",
            "bset", "bclr", "btest", "bcount",
            "mapset", "mapget", "maphas", "mapdel", "mapsize", "mapclear",
            "uadd", "uhas", "udel", "usize", "uclear",
            "lappend", "lget", "lset", "linsert", "lremove", "lfind", "lsize",
            "hpush", "hpop", "hsize",
            "stack_push", "stack_pop", "stack_top", "stack_size", "stack_clear",
            "queue_push", "queue_pop", "queue_front", "queue_size", "queue_clear",
            "deque_push_front", "deque_push_back", "deque_pop_front", "deque_pop_back",
            "deque_front", "deque_back", "deque_size", "deque_clear",
            "bitset_set", "bitset_reset", "bitset_test", "bitset_count",
            "map_set", "map_get", "map_contains", "map_erase", "map_size", "map_clear",
            "set_add", "set_contains", "set_remove", "set_size", "set_clear",
            "vector_push_back", "vector_at", "vector_set", "vector_insert",
            "vector_erase", "vector_find", "vector_size",
            "heap_push", "heap_pop", "heap_size",
            "chain_init", "chain_clear", "chain_alloc", "chain_free", "chain_get", "chain_set",
            "chain_next", "chain_link", "chain_set_head", "chain_head", "chain_len",
            "array_sum", "array_avg", "array_min", "array_max", "array_count", "array_find",
            "array_fill", "array_copy", "array_sort", "array_sort_desc", "array_reverse",
            "array_replace", "array_swap", "array_lower_bound"
        ));
        Set<String> actual = ExprIntrinsics.intrinsicNames();
        for(String name : expected){
            check(actual.contains(name), "missing intrinsic provider name: " + name);
        }
        check(ExprIntrinsics.isIntrinsic("sum"), "sum intrinsic not registered by production init");
        check(ExprIntrinsics.isIntrinsic("spush"), "spush intrinsic not registered by production init");
        check(ExprIntrinsics.isIntrinsic("mapset"), "mapset intrinsic not registered by production init");
        check(ExprIntrinsics.isIntrinsic("array_sum"), "array_sum intrinsic not registered by production init");
        check(ExprIntrinsics.isIntrinsic("stack_push"), "stack_push intrinsic not registered by production init");
        check(ExprIntrinsics.isIntrinsic("map_set"), "map_set intrinsic not registered by production init");
        check(ExprIntrinsics.isIntrinsic("chain_len"), "chain_len intrinsic not registered by production init");
    }

    // ===== 端到端：混合数据结构 → 纯原版产物 =====

    private static void endToEndStructuresCompileToVanilla(){
        String declarations = "array buf cell1 0 4\n"
            + "arrayinit buf 4 1 3 2 ~ ~ ~ ~\n"
            + "matrix mat cell2 0 2 2\n"
            + "record p hp mp ~ ~ ~ ~ ~ ~\n"
            + "stack s cell3 0 4\n"
            + "queue q cell3 4 4\n"
            + "deque d cell3 8 4\n"
            + "bitset b cell4 0 2\n"
            + "map m cell5 0 4\n"
            + "uset u cell5 8 4\n"
            + "list l cell6 0 4\n"
            + "heap h cell6 8 4\n";
        String sugar = wrap(declarations,
            "sum(buf) > 3",
            "mat[0][1] > 0",
            "p.hp > 0",
            "spush(s, 1) > 0",
            "qpush(q, 2) > 0",
            "dpushb(d, 3) > 0",
            "btest(b, 3) > 0",
            "mapset(m, 1, 2) > 0",
            "uadd(u, 1) > 0",
            "lappend(l, 5) > 0",
            "hpush(h, 6) > 0"
        );
        String mlog = stripCompile(sugar);

        // 声明卡文本不进入产物
        for(String card : Arrays.asList("arrayinit ", "matrix ", "record ", "stack ", "queue ", "deque ", "bitset ", "map ", "uset ", "list ", "heap ")){
            check(!mlog.contains(card), "declaration card leaked into the product: " + card + "\n" + mlog);
        }
        check(!mlog.contains("funccall "), "funccall leaked into the product:\n" + mlog);
        check(!mlog.contains("ifbegin ") && !mlog.contains("blockend"), "sugar control card leaked into the product:\n" + mlog);
        // 每一条非标签行都必须是原版指令
        for(String line : mlog.replace("\r\n", "\n").split("\n", -1)){
            String text = line.trim();
            if(text.isEmpty() || text.endsWith(":") || text.startsWith("#")) continue;
            int space = text.indexOf(' ');
            String opcode = space < 0 ? text : text.substring(0, space);
            check(vanillaOpcodes.contains(opcode), "non-vanilla instruction in product: " + text);
        }
        // 关键降级证据：数组初始化写、矩阵读、记录字段读、容器 push 的共享子程序入口
        check(mlog.contains("write 4 cell1 0") && mlog.contains("write 1 cell1 1"),
            "arrayinit writes missing:\n" + mlog);
        check(mlog.contains("read") && mlog.contains("__ls_func___ls_builtin_stkpush_entry:"),
            "expected read + shared stack push body:\n" + mlog);
        // 隐藏状态变量是普通 mlog 变量（运行时状态载体），随使用出现；声明卡本身不产指令
        check(mlog.contains("__ls_stk_s_top") && mlog.contains("__ls_que_q_count")
            && mlog.contains("__ls_deq_d_count") && mlog.contains("__ls_lst_l_count") && mlog.contains("__ls_hep_h_count"),
            "hidden state variables missing from a program that uses the containers:\n" + mlog);

        // 载体往返仍可恢复源码（声明卡是编译期元数据，不进指令流）
        String compiled = compile(sugar);
        check(SugarCompiler.restore(compiled).equals(sugar), "carrier did not preserve the data-subsystem source");
        check(SugarCompiler.verifyRestore(compiled, sugar), "verifyRestore rejected a data-subsystem program");
    }

    // ===== 异常路径：collect 失败也必须 restore =====

    private static void collectFailureRestoresContext(){
        DataModules.register(new FailingModule());
        check(!DataModules.isCollecting(), "context should not be active before the failing compile");

        armCollectFailure = true;
        int before = failingRestoreCalls;
        try{
            compile("record p f1 ~ ~ ~ ~ ~ ~ ~\nset x 1\n");
            check(false, "compile should have propagated the module collect failure");
        }catch(IllegalArgumentException expected){
            // expected: FailingModule.collect aborts the compile
        }finally{
            armCollectFailure = false;
        }

        check(!DataModules.isCollecting(), "DataModules context leaked after a collect failure");
        check(failingRestoreCalls > before, "module restore hook did not run after a collect failure");
    }

    /** collect 失败后的下一次编译必须照常工作（注册表没有泄漏）。 */
    private static void stillCompilesAfterCollectFailure(){
        String mlog = stripCompile("stack s cell1 0 4\nset x 1\n");
        check(mlog.contains("set x 1"), "compilation broken after a recovered collect failure:\n" + mlog);
    }

    // ===== v6 改名：旧名别名与新名降级产物必须完全一致 =====

    private static void renamedOpsLowerIdentically(){
        checkLowerIdentical("stack s cell1 0 4\n",
            new String[]{"spush(s, 1) > 0", "spop(s) >= 0", "speek(s) >= 0", "ssize(s) >= 1", "sclear(s) >= 0"},
            new String[]{"stack_push(s, 1) > 0", "stack_pop(s) >= 0", "stack_top(s) >= 0", "stack_size(s) >= 1", "stack_clear(s) >= 0"});
        checkLowerIdentical("queue q cell1 0 4\n",
            new String[]{"qpush(q, 2) > 0", "qpop(q) >= 0", "qpeek(q) >= 0", "qsize(q) >= 1", "qclear(q) >= 0"},
            new String[]{"queue_push(q, 2) > 0", "queue_pop(q) >= 0", "queue_front(q) >= 0", "queue_size(q) >= 1", "queue_clear(q) >= 0"});
        checkLowerIdentical("deque d cell1 0 4\n",
            new String[]{"dpushf(d, 3) > 0", "dpushb(d, 3) > 0", "dpopf(d) >= 0", "dpopb(d) >= 0",
                "dpeekf(d) >= 0", "dpeekb(d) >= 0", "dsize(d) >= 1", "dclear(d) >= 0"},
            new String[]{"deque_push_front(d, 3) > 0", "deque_push_back(d, 3) > 0", "deque_pop_front(d) >= 0", "deque_pop_back(d) >= 0",
                "deque_front(d) >= 0", "deque_back(d) >= 0", "deque_size(d) >= 1", "deque_clear(d) >= 0"});
        checkLowerIdentical("bitset b cell1 0 2\n",
            new String[]{"bset(b, 3) > 0", "bclr(b, 3) >= 0", "btest(b, 3) >= 0", "bcount(b) >= 0"},
            new String[]{"bitset_set(b, 3) > 0", "bitset_reset(b, 3) >= 0", "bitset_test(b, 3) >= 0", "bitset_count(b) >= 0"});
        checkLowerIdentical("map m cell1 0 4\n",
            new String[]{"mapset(m, 1, 2) > 0", "mapget(m, 1) >= 0", "maphas(m, 1) >= 0", "mapdel(m, 1) >= 0",
                "mapsize(m) >= 1", "mapclear(m) >= 0"},
            new String[]{"map_set(m, 1, 2) > 0", "map_get(m, 1) >= 0", "map_contains(m, 1) >= 0", "map_erase(m, 1) >= 0",
                "map_size(m) >= 1", "map_clear(m) >= 0"});
        checkLowerIdentical("uset u cell1 0 4\n",
            new String[]{"uadd(u, 1) > 0", "uhas(u, 1) >= 0", "udel(u, 1) >= 0", "usize(u) >= 1", "uclear(u) >= 0"},
            new String[]{"set_add(u, 1) > 0", "set_contains(u, 1) >= 0", "set_remove(u, 1) >= 0", "set_size(u) >= 1", "set_clear(u) >= 0"});
        checkLowerIdentical("list l cell1 0 4\n",
            new String[]{"lappend(l, 5) > 0", "lget(l, 0) >= 0", "lset(l, 0, 1) > 0", "linsert(l, 0, 1) > 0",
                "lremove(l, 0) >= 0", "lfind(l, 1) >= 0", "lsize(l) >= 1"},
            new String[]{"vector_push_back(l, 5) > 0", "vector_at(l, 0) >= 0", "vector_set(l, 0, 1) > 0", "vector_insert(l, 0, 1) > 0",
                "vector_erase(l, 0) >= 0", "vector_find(l, 1) >= 0", "vector_size(l) >= 1"});
        checkLowerIdentical("heap h cell1 0 4\n",
            new String[]{"hpush(h, 6) > 0", "hpop(h) >= 0", "hsize(h) >= 1"},
            new String[]{"heap_push(h, 6) > 0", "heap_pop(h) >= 0", "heap_size(h) >= 1"});
        checkLowerIdentical("chain c cell1 0 8\n",
            new String[]{"cinit(c) >= 0", "cclear(c) >= 0", "cnew(c) >= 0", "cfree(c, 0) >= 0", "cget(c, 0) >= 0",
                "cset(c, 0, 1) > 0", "cnext(c, 0) >= 0", "clink(c, 0, 1) > 0", "cshead(c, 0) > 0",
                "chead(c) >= 0", "clen(c) >= 0"},
            new String[]{"chain_init(c) >= 0", "chain_clear(c) >= 0", "chain_alloc(c) >= 0", "chain_free(c, 0) >= 0",
                "chain_get(c, 0) >= 0", "chain_set(c, 0, 1) > 0", "chain_next(c, 0) >= 0",
                "chain_link(c, 0, 1) > 0", "chain_set_head(c, 0) > 0", "chain_head(c) >= 0", "chain_len(c) >= 0"});
        checkLowerIdentical("array buf cell1 0 8\n",
            new String[]{"sum(buf) > 0", "avg(buf) >= 0", "min(buf) >= 0", "max(buf) >= 0",
                "count(buf, 1) >= 0", "indexof(buf, 1) >= 0", "fill(buf, 1) >= 0", "copy(buf, buf) >= 0",
                "sortasc(buf) >= 0", "sortdesc(buf) >= 0", "reverse(buf) >= 0", "replace(buf, 1, 2) >= 0",
                "swap(buf, 0, 1) >= 0", "bsearch(buf, 1) >= 0"},
            new String[]{"array_sum(buf) > 0", "array_avg(buf) >= 0", "array_min(buf) >= 0", "array_max(buf) >= 0",
                "array_count(buf, 1) >= 0", "array_find(buf, 1) >= 0", "array_fill(buf, 1) >= 0",
                "array_copy(buf, buf) >= 0", "array_sort(buf) >= 0", "array_sort_desc(buf) >= 0",
                "array_reverse(buf) >= 0", "array_replace(buf, 1, 2) >= 0", "array_swap(buf, 0, 1) >= 0",
                "array_lower_bound(buf, 1) >= 0"});
    }

    /** 编译一个模块的全部旧名/新名表达式，校验可执行流逐字一致。 */
    private static void checkLowerIdentical(String declarations, String[] legacy, String[] renamed){
        check(legacy.length == renamed.length, "rename fixture lists drifted apart");
        String legacySugar = wrap(declarations, legacy);
        String renamedSugar = wrap(declarations, renamed);
        String legacyMlog = stripCompile(legacySugar);
        String renamedMlog = stripCompile(renamedSugar);
        if(!legacyMlog.equals(renamedMlog)){
            throw new AssertionError("renamed operations must lower to the exact same executable stream as their legacy aliases");
        }
        check(SugarCompiler.restore(renamedSugar).equals(renamedSugar),
            "carrier did not preserve the renamed operation source");
    }

    // ===== 工具 =====

    /** 一个只在测试武装标志下抛错的模块（模拟模块 collect 校验失败）。 */
    private static class FailingModule extends DataModule{
        @Override
        public String id(){
            return "integration-failing";
        }

        @Override
        public void registerParsers(){
        }

        @Override
        public void collect(List<LStatement> statements, Set<String> functionNames){
            if(armCollectFailure){
                throw new IllegalArgumentException("integration test: collect failure");
            }
        }

        @Override
        public void markInvalid(List<LStatement> statements, boolean[] invalid, Set<String> functionNames){
        }

        @Override
        public void restore(){
            failingRestoreCalls++;
        }
    }

    /** 声明卡 + 每个表达式一个 ifbegin 块（jump 目标 = 对应 blockend 的语句下标）。 */
    private static String wrap(String declarations, String... expressions){
        String[] declLines = declarations.split("\n", -1);
        // split(-1) keeps a trailing empty element for the final newline; it is not a statement
        int declCount = declLines.length;
        if(declCount > 0 && declLines[declCount - 1].isEmpty()) declCount--;
        StringBuilder out = new StringBuilder(declarations);
        int index = declCount;
        for(int i = 0; i < expressions.length; i++){
            out.append("ifbegin expr \"").append(expressions[i]).append("\" ").append(index + 2).append('\n');
            out.append("set __ls_it").append(i).append(" 1\n");
            out.append("blockend\n");
            index += 3;
        }
        return out.toString();
    }

    private static int countClass(Class<?> type){
        int count = 0;
        Seq<arc.func.Prov<LStatement>> all = LogicIO.allStatements;
        for(int i = 0; i < all.size; i++){
            if(all.get(i).get().getClass() == type) count++;
        }
        return count;
    }

    private static String compile(String sugar){
        return SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
    }

    private static String stripCompile(String sugar){
        String mlog = SugarCompiler.stripMarkers(compile(sugar));
        StringBuilder executable = new StringBuilder();
        for(String line : mlog.replace("\r\n", "\n").split("\n", -1)){
            if(line.startsWith("set __ls_sugar") || line.startsWith("set __ls_lib")) continue;
            executable.append(line).append('\n');
        }
        return executable.toString();
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

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
