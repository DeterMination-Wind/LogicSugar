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

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
