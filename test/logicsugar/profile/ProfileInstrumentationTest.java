package logicsugar.profile;

import arc.struct.Seq;
import logicsugar.assist.AssertInstructions;
import mindustry.logic.ConditionOp;
import mindustry.logic.LCategory;
import mindustry.logic.LExecutor;
import mindustry.logic.LParser;
import mindustry.logic.LStatement;
import mindustry.logic.LVar;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Profiler 子系统的无头自检。
 *
 * <p>无头限制：{@link Instrumentation} 本身要一块活的 {@code LogicBlock}（构造读
 * {@code Vars.content}），所以这里不构造它，而是钉住它依赖的三样东西：
 * 包装器的解包契约（{@link InstrumentationEngine#unwrap}）、配额/计步的纯计算
 * （{@link Instrumentation#countsAsStep}/{@link Instrumentation#stepQuota}）、
 * 以及反射桥失败时的降级路径（源码列退化为 {@code unknown instruction}，其余功能保留）。</p>
 *
 * <p>反射降级的用例必须最先跑：它把 {@code parserConstructor}/{@code parseMethod} 临时置空，
 * 之后调用 {@link InstrumentationEngine#init()} 恢复真实的反射对象并验证解析真的能用。</p>
 */
public final class ProfileInstrumentationTest{
    private ProfileInstrumentationTest(){
    }

    public static void main(String[] args){
        reflectionDegradation();
        wireTokens();
        unwrapContract();
        stepAccounting();
        varsCollection();
        yieldsTable();
        noYieldingRegistration();
        stepPathAccounting();
        stopStopsProfiling();
        rowOrder();

        System.out.println("LogicSugar profiler self-test passed.");
    }

    // ===== 反射桥与降级 =====

    private static void reflectionDegradation(){
        // 初始化前（或反射不可用的 MindustryX/BE 分支）：源码列必须退化成空解析，而不是抛异常
        Constructor<LParser> savedConstructor = InstrumentationEngine.parserConstructor;
        Method savedParse = InstrumentationEngine.parseMethod;
        InstrumentationEngine.parserConstructor = null;
        InstrumentationEngine.parseMethod = null;

        try{
            check(InstrumentationEngine.parse("set x 1", true).size == 0,
                "反射不可用时 parse 必须返回空表（源码列退化为 unknown instruction）");
        }finally{
            InstrumentationEngine.parserConstructor = savedConstructor;
            InstrumentationEngine.parseMethod = savedParse;
        }

        // 真实的反射桥：解析一行原版 mlog 必须成功（否则源码列永远显示 unknown instruction）
        InstrumentationEngine.init();

        Seq<LStatement> parsed = InstrumentationEngine.parse("set x 1", true);
        check(parsed.size == 1, "原版 LParser 反射解析失败，profiler 源码列会一直是 unknown instruction");

        StringBuilder out = new StringBuilder();
        parsed.first().write(out);
        check(out.toString().contains("set x 1"), "解析出的语句文本不对: " + out);

        // 参数错误、代码含未知 token 时也不能抛异常（退化为空表/空串）
        try{
            InstrumentationEngine.parse("op bogusOp x 1 2 3", true);
        }catch(Throwable t){
            throw new AssertionError("解析未知指令不应抛出异常", t);
        }
    }

    // ===== 线格式 token =====

    private static void wireTokens(){
        // 三个 token 就是 mlog 行里写的字（profile 卡与指令共用），改名等于改存档格式
        check(ProfilingCommand.start.name().equals("start"), "start token drifted");
        check(ProfilingCommand.stop.name().equals("stop"), "stop token drifted");
        check(ProfilingCommand.clear.name().equals("clear"), "clear token drifted");
        check(ProfilingCommand.all.length == 3, "unexpected profiling command count: " + ProfilingCommand.all.length);
        for(ProfilingCommand command : ProfilingCommand.all){
            String display = command.display();
            check(display != null && !display.isEmpty(), "no display name for " + command);
        }

        // recording 快照是 profiler 的搭档：它必须仍然存在于线格式类型表里
        check(logicsugar.vars.SnapshotType.recording.name().equals("recording"), "recording token drifted");
    }

    // ===== 解包契约 =====

    private static void unwrapContract(){
        LExecutor.LInstruction inner = exec -> {
        };
        Instrumentation.InstrumentedInstruction wrapper = new Instrumentation.InstrumentedInstruction(){
            @Override
            public LExecutor.LInstruction instruction(){
                return inner;
            }

            @Override
            public void run(LExecutor exec){
                inner.run(exec);
            }
        };

        check(InstrumentationEngine.unwrap(wrapper) == inner, "unwrap 必须还原包装前的指令对象");
        check(InstrumentationEngine.unwrap(inner) == inner, "未包装的指令必须原样返回");
        check(InstrumentationEngine.unwrap(null) == null, "null 原样返回");
        check(InstrumentationEngine.unwrap(wrapper) != wrapper, "包装器不能自己套自己");
    }

    // ===== 计步与配额 =====

    private static void stepAccounting(){
        // 非让出：一定计一步
        check(Instrumentation.countsAsStep(false, 5, 5), "普通执行必须计一步");
        check(Instrumentation.countsAsStep(false, 5, 5), "普通执行必须计一步（计数器无关）");

        // 让出：计数器前进了才算一步（wait 0 让出但执行了一次；wait 1 不前进，不计步）
        check(Instrumentation.countsAsStep(true, 6, 5), "wait 0 让出但要计一步");
        check(!Instrumentation.countsAsStep(true, 5, 5), "wait 1 让出且不前进，不计步");

        // 配额：未让出时每步 1（由调用方给，不在这里算）；让出时按「未来 accumulator 超出上限的部分」估算
        check(Instrumentation.stepQuota(0f, 0.5f, 10f, 5) == 0f,
            "accumulator 远低于上限时没有丢配额");
        check(Instrumentation.stepQuota(60f, 0.5f, 10f, 5) == 15f,
            "丢配额 = acc + edelta*ipt - scale*ipt = 60 + 5 - 50");
        check(Instrumentation.stepQuota(60f, 0f, 10f, 5) == 10f,
            "本帧已结算（edelta 0）时丢配额 = acc - scale*ipt = 60 - 50");
        check(Instrumentation.stepQuota(100f, 1f, 10f, 5) == 60f,
            "超上限多少就算丢多少: 100 + 10 - 50");
        check(Instrumentation.stepQuota(0f, 0f, 0f, 5) == 0f, "ipt 为 0 时归零");
    }

    // ===== recording 的变量收集 =====

    private static void varsCollection(){
        LVar a = new LVar("a"), b = new LVar("b"), message = new LVar("m");

        // 本 mod 的调试指令直接给出 vars()（reflection 表里没有它们）
        AssertInstructions.AssertI assertion = new AssertInstructions.AssertI(ConditionOp.equal, a, b, message);
        LVar[] vars = InstrumentationEngine.getVars(assertion, 0);
        check(vars.length == 3 && vars[0] == a && vars[1] == b && vars[2] == message,
            "DevToolsInstruction.vars() 必须原样交给 recording 快照的默认过滤");

        AssertInstructions.AssertFlushI flush = new AssertInstructions.AssertFlushI(a);
        check(InstrumentationEngine.getVars(flush, 0).length == 1,
            "AssertFlushI 也要实现变量收集（v0.11.3 对齐）");

        AssertInstructions.SnapshotI snapshot =
            new AssertInstructions.SnapshotI(logicsugar.vars.SnapshotType.recording, a, b, message);
        check(InstrumentationEngine.getVars(snapshot, 0).length == 3,
            "recording 快照指令自身也要能收集变量");

        // 原版指令：反射取 LVar 字段（SetI 的 from/to）
        LVar[] setVars = InstrumentationEngine.getVars(new LExecutor.SetI(b, a), 0);
        check(setVars.length == 2 && (setVars[0] == b || setVars[0] == a) && (setVars[1] == b || setVars[1] == a),
            "原版指令的 LVar 字段必须经反射收集: " + setVars.length);

        // 没有注册过的类别退化为 unknown，而不是 null（对话框取 .color 会 NPE）
        check(InstrumentationEngine.getCategory(assertion) == LCategory.unknown,
            "未注册的指令类别必须退化为 LCategory.unknown");
    }

    // ===== 自定义调试指令的 yields()（上游 v0.11.6） =====

    /**
     * 12 个调试指令的让出表必须与上游逐字一致：profiler 根据它选快路径还是让出路径，
     * 选错的表现是「配额统计悄悄偏一点」（快路径不给让出指令估丢配额），不报错。
     */
    private static void yieldsTable(){
        checkYields(new AssertInstructions.AssertI(), true, "AssertI");
        checkYields(new AssertInstructions.AssertBoundsI(), true, "AssertBoundsI");
        checkYields(new AssertInstructions.AssertEqualsI(), true, "AssertEqualsI");
        checkYields(new AssertInstructions.AssertFlushI(), false, "AssertFlushI");
        checkYields(new AssertInstructions.AssertPrintsI(), true, "AssertPrintsI");
        checkYields(new AssertInstructions.AssertTypeI(), true, "AssertTypeI");
        checkYields(new AssertInstructions.BreakpointI(), false, "BreakpointI");
        checkYields(new AssertInstructions.ErrorI(), true, "ErrorI");
        checkYields(new AssertInstructions.LogI(), false, "LogI");
        checkYields(new AssertInstructions.ProfileI(), false, "ProfileI");
        checkYields(new AssertInstructions.RestartI(), false, "RestartI");
        checkYields(new AssertInstructions.SnapshotI(), false, "SnapshotI");
    }

    private static void checkYields(AssertInstructions.DevToolsInstruction instruction, boolean expected, String name){
        check(instruction.yields() == expected,
            name + ".yields() 必须是 " + expected + "（上游 v0.11.6 的取值表）");
    }

    // ===== noYielding 名单（上游 v0.11.6） =====

    /**
     * 快路径名单必须恰好是「已注册的原版指令里除会 {@code exec.yield = true} 的三条之外的全部」：
     * 只有 {@code WaitI}/{@code StopI}/{@code FlushMessageI} 会真的让出（已逐条核对
     * {@code LExecutor}）。漏加一个的后果是丢失配额统计，多加的后果是每个非让出指令多算一次
     * 配额与位图访问——两种都不报错，只让数字偏。
     */
    private static void noYieldingRegistration(){
        InstrumentationEngine.init();

        Set<Class<?>> registered = new LinkedHashSet<>();
        for(Class<?> type : InstrumentationEngine.categories.keys()) registered.add(type);

        Set<Class<?>> yielding = new LinkedHashSet<>();
        yielding.add(LExecutor.FlushMessageI.class);
        yielding.add(LExecutor.StopI.class);
        yielding.add(LExecutor.WaitI.class);

        Set<Class<?>> expected = new LinkedHashSet<>(registered);
        expected.removeAll(yielding);

        Set<Class<?>> actual = new LinkedHashSet<>();
        for(Class<?> type : InstrumentationEngine.noYielding) actual.add(type);

        Set<Class<?>> missing = new LinkedHashSet<>(expected);
        missing.removeAll(actual);
        Set<Class<?>> extra = new LinkedHashSet<>(actual);
        extra.removeAll(expected);

        check(missing.isEmpty() && extra.isEmpty(),
            "noYielding 名单漂了（missing=" + missing + ", extra=" + extra + "）");
        check(registered.containsAll(yielding), "三个会让出的原版指令必须都已注册: " + registered.size());
        for(Class<?> type : yielding){
            check(!InstrumentationEngine.noYielding.contains(type),
                type.getSimpleName() + " 会让出执行权，不能进快路径名单");
        }
        // 自定义调试指令的让出与否由 yields() 决定，不进原版名单
        check(!InstrumentationEngine.noYielding.contains(AssertInstructions.AssertI.class),
            "调试指令不能混进原版快路径名单");
    }

    // ===== 两条记录路径的计步口径（上游 v0.11.6） =====

    /**
     * 快路径（非让出）与让出路径（配额估算）的口径，以及两条路径的覆盖率来源不同这件事：
     * 快路径只写 {@code steps}，让出路径只写 {@code covered}，读方必须用
     * {@link Instrumentation#isCovered(int[])} 合并——只读其中一个会漏掉一半已执行指令。
     */
    private static void stepPathAccounting(){
        // ---- 快路径：恒 +1 步 / +1 配额 ----
        int[] steps = new int[3];
        double[] time = new double[3];
        BitSet covered = new BitSet(3);
        Instrumentation.Counters counters = new Instrumentation.Counters();

        Instrumentation.recordFastStep(steps, time, counters, 1);
        Instrumentation.recordFastStep(steps, time, counters, 1);
        Instrumentation.recordFastStep(steps, time, counters, 2);

        check(steps[1] == 2 && time[1] == 2d, "快路径：步数与配额（time）同步加一");
        check(steps[2] == 1 && time[2] == 1d, "快路径：另一条指令独立计数");
        check(counters.totalSteps == 3 && counters.totalTime == 3d, "快路径总量 = 步数");
        check(counters.coverage == 2, "快路径覆盖率按「第一次执行」（steps == 0）计数");
        check(counters.maxSteps == 2 && counters.maxTime == 2d, "快路径峰值取自增后的步数");
        check(counters.lostQuota == 0d, "快路径不应产生丢配额");
        check(!covered.get(1), "快路径不写 covered 位图（那是让出路径的覆盖率来源）");
        check(Instrumentation.isCovered(steps, covered, 1), "快路径的已访问判定看 steps");
        check(!Instrumentation.isCovered(steps, covered, 0), "没执行过的指令不算已访问");

        counters.clear();
        check(counters.coverage == 0 && counters.maxSteps == 0 && counters.totalSteps == 0
            && counters.maxTime == 0d && counters.totalTime == 0d && counters.lostQuota == 0d,
            "Counters.clear() 必须把六项全部归零");

        // ---- 让出路径 ----
        int[] ySteps = new int[2];
        double[] yTime = new double[2];
        BitSet yCovered = new BitSet(2);
        Instrumentation.Counters yCounters = new Instrumentation.Counters();

        // wait 0：让出但计数器真的前进了 → 计一步，且没有丢配额（cost 0）
        Instrumentation.recordYieldedStep(ySteps, yTime, yCovered, yCounters, 0, true, true, 0d);
        // wait 1：让出且不前进 → 不记步，但仍然是「已访问」
        Instrumentation.recordYieldedStep(ySteps, yTime, yCovered, yCounters, 1, true, false, 0d);
        // 理论上不会发生的分支（让出路径里的非让出执行）：按恒 1 步/1 配额计
        Instrumentation.recordYieldedStep(ySteps, yTime, yCovered, yCounters, 0, true, true, 3.5d);

        check(ySteps[0] == 2, "让出路径：计数器前进的两个步骤都计步");
        check(ySteps[1] == 0, "让出路径：wait 1 不前进就不计步");
        check(yTime[0] == 3.5d, "让出路径：time 累计的是配额而不是步数");
        check(yCounters.totalSteps == 2 && yCounters.totalTime == 3.5d, "让出路径总量");
        check(yCounters.lostQuota == 3.5d, "丢配额只累计让出时估算出的 cost");
        check(yCounters.coverage == 2, "让出路径的覆盖率看 covered 位图（wait 1 也标记）");
        check(yCounters.maxSteps == 1, "让出路径峰值用自增前的步数（上游口径，比实际少 1）");
        check(yCounters.maxTime == 3.5d, "让出路径的配额峰值");
        check(Instrumentation.isCovered(ySteps, yCovered, 0) && Instrumentation.isCovered(ySteps, yCovered, 1),
            "isCovered 必须把两条路径的来源合并（steps > 0 || covered）");
        check(yCovered.get(1), "wait 1 虽然一步都不计，仍要标记为已访问");

        // ---- 分支计数（两条路径共用同一个判定） ----
        check(!Instrumentation.tookBranch(6, 5), "落到下一条不算分支");
        check(Instrumentation.tookBranch(7, 5), "向前跳转算一次分支");
        check(Instrumentation.tookBranch(0, 5), "向后跳转也算一次分支");
    }

    // ===== 停机检测（上游 v0.11.4） =====

    /**
     * 停机后必须停统计并放弃未完成的录制。两个包装器的 {@code run()} 都要检查
     * {@code exec.stop}（{@code stop} 指令、失败断言、{@code error} 指令都会置它）；
     * {@code InstrumentedWait} 不需要（原版 {@code WaitI} 不会置 stop），保持上游的写法。
     *
     * <p>{@code Instrumentation} 需要一块活的 {@code LogicBlock}，无头造不出来，所以这里扫源码。
     */
    private static void stopStopsProfiling(){
        String source = source("src/logicsugar/profile/Instrumentation.java");

        String stopAll = logicsugar.SourceNails.methodBody(source, "public void stopAll(){");
        check(stopAll.contains("profiling = false") && stopAll.contains("snapshotSteps = 0"),
            "stopAll() 必须同时停统计与录制");

        String fast = logicsugar.SourceNails.blockFrom(source,
            "public void run(LExecutor exec){\n            int index = (int)(exec.counter.numval - 1);\n            instruction.run(exec);\n            recordStep(exec, index);");
        String yielding = logicsugar.SourceNails.blockFrom(source,
            "public void run(LExecutor exec){\n            int index = (int)(exec.counter.numval - 1);\n            instruction.run(exec);\n            recordStepWithYielding(exec, index);\n\n            if(snapshotSteps > 0){");

        check(fast.contains("if(exec.stop) stopAll();"), "快路径包装器必须检测 exec.stop");
        check(yielding.contains("if(exec.stop) stopAll();"), "让出路径包装器必须检测 exec.stop");
        check(fast.indexOf("createSnapshot") < fast.indexOf("exec.stop"),
            "停机检测必须在快照分支之后（停机前的最后一条指令仍然要被记录）");
        check(yielding.indexOf("createSnapshot") < yielding.indexOf("exec.stop"),
            "停机检测必须在快照分支之后（让出路径同理）");

        String wait = logicsugar.SourceNails.blockFrom(source,
            "public void run(LExecutor exec){\n            int index = (int)(exec.counter.numval - 1);\n            instruction.run(exec);\n            recordStepWithYielding(exec, index);\n\n            // 原版 WaitI");
        check(!wait.contains("exec.stop"), "InstrumentedWait 不加停机检测（上游同：WaitI 不会置 stop）");
    }

    // ===== 排序助手（上游 v0.11.6 的 ProfileDialog.sort） =====

    /**
     * 行序：计数降序、并列保持原始行序、未排序时恒等、大计数不溢出。
     * {@code execTime} 换成配额作为排序键。
     */
    private static void rowOrder(){
        int[] steps = {5, 9, 9, 0};
        double[] time = {1d, 2.5d, 0.5d, 0d};

        check(Arrays.equals(Instrumentation.order(steps, time, false, false), new int[]{0, 1, 2, 3}),
            "未排序时必须是恒等映射");
        check(Arrays.equals(Instrumentation.order(steps, time, false, true), new int[]{1, 2, 0, 3}),
            "计数降序 + 并列保持原始行序: " + Arrays.toString(Instrumentation.order(steps, time, false, true)));
        check(Arrays.equals(Instrumentation.order(steps, time, true, true), new int[]{1, 0, 2, 3}),
            "execTime 分支按配额排序: " + Arrays.toString(Instrumentation.order(steps, time, true, true)));

        // 打包成 long：低 32 位是 size - i，所以 int 上限的计数也不会互相压掉
        int[] huge = {Integer.MAX_VALUE, Integer.MAX_VALUE - 1, 0};
        check(Arrays.equals(Instrumentation.order(huge, new double[3], false, true), new int[]{0, 1, 2}),
            "大计数排序不溢出: " + Arrays.toString(Instrumentation.order(huge, new double[3], false, true)));

        // 全部并列（含全 0 的初始状态）时保持原序
        int[] zeros = new int[5];
        check(Arrays.equals(Instrumentation.order(zeros, new double[5], false, true), new int[]{0, 1, 2, 3, 4}),
            "全并列时保持原序");

        // 大小变化（换处理器/改代码后重建）时只看传入的长度，不引用旧的数组
        check(Arrays.equals(Instrumentation.order(new int[]{3}, new double[]{0d}, false, true), new int[]{0}),
            "单元素数组");
        check(Instrumentation.order(new int[0], new double[0], false, true).length == 0, "空数组");
    }

    // ===== 工具 =====

    private static String source(String file){
        try{
            return logicsugar.SourceNails.readSource(file);
        }catch(java.io.IOException e){
            throw new AssertionError("cannot read " + file + ": " + e, e);
        }
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
