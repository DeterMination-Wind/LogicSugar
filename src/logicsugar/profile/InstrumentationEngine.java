package logicsugar.profile;

import arc.Events;
import arc.func.Cons;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import logicsugar.vars.Snapshot;
import logicsugar.vars.Snapshots;
import mindustry.game.EventType;
import mindustry.logic.LCategory;
import mindustry.logic.LExecutor;
import mindustry.logic.LExecutor.ApplyEffectI;
import mindustry.logic.LExecutor.ClientDataI;
import mindustry.logic.LExecutor.ControlI;
import mindustry.logic.LExecutor.CutsceneI;
import mindustry.logic.LExecutor.DrawFlushI;
import mindustry.logic.LExecutor.DrawI;
import mindustry.logic.LExecutor.EffectI;
import mindustry.logic.LExecutor.EndI;
import mindustry.logic.LExecutor.ExplosionI;
import mindustry.logic.LExecutor.FetchI;
import mindustry.logic.LExecutor.FlushMessageI;
import mindustry.logic.LExecutor.FormatI;
import mindustry.logic.LExecutor.GetBlockI;
import mindustry.logic.LExecutor.GetFlagI;
import mindustry.logic.LExecutor.GetLinkI;
import mindustry.logic.LExecutor.JumpI;
import mindustry.logic.LExecutor.LocalePrintI;
import mindustry.logic.LExecutor.LookupI;
import mindustry.logic.LExecutor.MakeMarkerI;
import mindustry.logic.LExecutor.NoopI;
import mindustry.logic.LExecutor.OpI;
import mindustry.logic.LExecutor.PackColorI;
import mindustry.logic.LExecutor.PlayMusicI;
import mindustry.logic.LExecutor.PlaySoundI;
import mindustry.logic.LExecutor.PrintCharI;
import mindustry.logic.LExecutor.PrintFlushI;
import mindustry.logic.LExecutor.PrintI;
import mindustry.logic.LExecutor.QueryI;
import mindustry.logic.LExecutor.RadarI;
import mindustry.logic.LExecutor.ReadI;
import mindustry.logic.LExecutor.SelectI;
import mindustry.logic.LExecutor.SenseI;
import mindustry.logic.LExecutor.SenseWeatherI;
import mindustry.logic.LExecutor.SetBlockI;
import mindustry.logic.LExecutor.SetFlagI;
import mindustry.logic.LExecutor.SetI;
import mindustry.logic.LExecutor.SetMarkerI;
import mindustry.logic.LExecutor.SetPropI;
import mindustry.logic.LExecutor.SetRateI;
import mindustry.logic.LExecutor.SetRuleI;
import mindustry.logic.LExecutor.SetWeatherI;
import mindustry.logic.LExecutor.SpawnBulletI;
import mindustry.logic.LExecutor.SpawnUnitI;
import mindustry.logic.LExecutor.SpawnWaveI;
import mindustry.logic.LExecutor.StopI;
import mindustry.logic.LExecutor.SyncI;
import mindustry.logic.LExecutor.UnitBindI;
import mindustry.logic.LExecutor.UnitControlI;
import mindustry.logic.LExecutor.UnitLocateI;
import mindustry.logic.LExecutor.UnpackColorI;
import mindustry.logic.LExecutor.WaitI;
import mindustry.logic.LExecutor.WriteI;
import mindustry.logic.LParser;
import mindustry.logic.LStatement;
import mindustry.logic.LVar;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.util.Arrays;

import static mindustry.logic.LStatements.*;

/**
 * 每块处理器一份 {@link Instrumentation} 的注册表与反射桥。
 *
 * <p>Ported from upstream MlogAssertions v0.11.3
 * ({@code cardillan.mlogassertions.logic.InstrumentationEngine})，行为逐字保留，三处本地化：
 * 日志前缀 {@code [LogicSugar]}、{@code Hashtable → ObjectMap}（上游也是 ObjectMap）、
 * 以及新增对外的 {@link #unwrap(LExecutor.LInstruction)}——本 mod 的其它代码
 * （{@code ProcessorStatus} 的扫描、{@code ProcessorVars.timeWaited}、{@code ProcessorSnapshot}）
 * 会直接遍历 {@code executor.instructions}，必须先把包装器还原成原版指令对象，
 * 否则 {@code instanceof StopI/WaitI/DevToolsInstruction} 全部失效。</p>
 *
 * <p><b>反射与降级</b>：{@code LParser} 的构造器与 {@code parse()} 都是包内可见，跨类加载器
 * 只能反射（AGENTS.md 的跨加载器规则）。初始化失败（或 MindustryX/BE 分支改掉了签名）时
 * 只把 profiler 的**源码列**退化成 {@code unknown instruction}，其余功能（计数、配额、
 * 分支、recording）照常工作，绝不因为可选功能让游戏崩。</p>
 */
public final class InstrumentationEngine{
    static boolean initialized = false;
    static Constructor<LParser> parserConstructor;
    static Method parseMethod;
    static final ObjectMap<Class<?>, Field[]> varFields = new ObjectMap<>();
    static final ObjectMap<Class<?>, LCategory> categories = new ObjectMap<>();

    static final ObjectMap<LogicBuild, Instrumentation> instrumentations = new ObjectMap<>();

    private InstrumentationEngine(){
    }

    public static void init(){
        if(initialized) return;

        try{
            parserConstructor = LParser.class.getDeclaredConstructor(String.class, boolean.class);
            parserConstructor.setAccessible(true);
            parserConstructor.newInstance("", false);       // try it out

            parseMethod = LParser.class.getDeclaredMethod("parse");
            parseMethod.setAccessible(true);
        }catch(ReflectiveOperationException | RuntimeException e){
            // 源码列降级为 "unknown instruction"；下面的指令表仍然注册，profiler 其余功能可用
            parserConstructor = null;
            parseMethod = null;
            Log.warn("LogicSugar: cannot access the vanilla parser, the profiler source column is disabled: @", e);
        }

        try{
            // Preload all known classes (变量字段的反射表与类别配色)
            registerInstruction(ApplyEffectI.class, new ApplyStatusStatement());
            registerInstruction(ClientDataI.class, new ClientDataStatement());
            registerInstruction(ControlI.class, new ControlStatement());
            registerInstruction(CutsceneI.class, new CutsceneStatement());
            registerInstruction(DrawFlushI.class, new DrawFlushStatement());
            registerInstruction(DrawI.class, new DrawStatement());
            registerInstruction(EffectI.class, new EffectStatement());
            registerInstruction(EndI.class, new EndStatement());
            registerInstruction(ExplosionI.class, new ExplosionStatement());
            registerInstruction(FetchI.class, new FetchStatement());
            registerInstruction(FlushMessageI.class, new FlushMessageStatement());
            registerInstruction(FormatI.class, new FormatStatement());
            registerInstruction(GetBlockI.class, new GetBlockStatement());
            registerInstruction(GetFlagI.class, new GetFlagStatement());
            registerInstruction(GetLinkI.class, new GetLinkStatement());
            registerInstruction(JumpI.class, new JumpStatement());
            registerInstruction(LocalePrintI.class, new LocalePrintStatement());
            registerInstruction(LookupI.class, new LookupStatement());
            registerInstruction(MakeMarkerI.class, new MakeMarkerStatement());
            registerInstruction(NoopI.class, new InvalidStatement());
            registerInstruction(OpI.class, new OperationStatement());
            registerInstruction(PackColorI.class, new PackColorStatement());
            registerInstruction(PlayMusicI.class, new PlayMusicStatement());
            registerInstruction(PlaySoundI.class, new PlaySoundStatement());
            registerInstruction(PrintCharI.class, new PrintCharStatement());
            registerInstruction(PrintFlushI.class, new PrintFlushStatement());
            registerInstruction(PrintI.class, new PrintStatement());
            registerInstruction(QueryI.class, new QueryStatement());
            registerInstruction(RadarI.class, new RadarStatement());
            registerInstruction(ReadI.class, new ReadStatement());
            registerInstruction(SelectI.class, new SelectStatement());
            registerInstruction(SenseI.class, new SensorStatement());
            registerInstruction(SenseWeatherI.class, new WeatherSenseStatement());
            registerInstruction(SetBlockI.class, new SetBlockStatement());
            registerInstruction(SetFlagI.class, new SetFlagStatement());
            registerInstruction(SetI.class, new SetStatement());
            registerInstruction(SetMarkerI.class, new SetMarkerStatement());
            registerInstruction(SetPropI.class, new SetPropStatement());
            registerInstruction(SetRateI.class, new SetRateStatement());
            registerInstruction(SetRuleI.class, new SetRuleStatement());
            registerInstruction(SetWeatherI.class, new WeatherSetStatement());
            registerInstruction(SpawnBulletI.class, new SpawnBulletStatement());
            registerInstruction(SpawnUnitI.class, new SpawnUnitStatement());
            registerInstruction(SpawnWaveI.class, new SpawnWaveStatement());
            registerInstruction(StopI.class, new StopStatement());
            registerInstruction(SyncI.class, new SyncStatement());
            registerInstruction(UnitBindI.class, new UnitBindStatement());
            registerInstruction(UnitControlI.class, new UnitControlStatement());
            registerInstruction(UnitLocateI.class, new UnitLocateStatement());
            registerInstruction(UnpackColorI.class, new UnpackColorStatement());
            registerInstruction(WaitI.class, new WaitStatement());
            registerInstruction(WriteI.class, new WriteStatement());
        }catch(Throwable t){
            // 指令表注册失败只影响 recording 快照的变量收集与配色，不能让 profiler 整体不可用
            Log.warn("LogicSugar: failed to preload the instruction reflection tables: @", t);
        }

        Events.on(EventType.ResetEvent.class, e -> instrumentations.clear());

        Events.on(EventType.BlockBuildEndEvent.class, e -> {
            if(e.breaking && (e.tile.build instanceof LogicBuild build)){
                instrumentations.remove(build);
            }
        });

        Events.on(EventType.ConfigEvent.class, e -> {
            if(e.tile instanceof LogicBuild build){
                // 代码被改过：包装过的指令数组已失效，重建并按需恢复 profiling
                Instrumentation instrumentation = instrumentations.remove(build);
                if(instrumentation != null && instrumentation.profiling){
                    startProfiling(build);
                }
            }
        });

        initialized = true;
    }

    private static void registerInstruction(Class<? extends LExecutor.LInstruction> instructionClass, LStatement statement){
        getVarFields(instructionClass);
        categories.put(instructionClass, statement.category());
    }

    /** 启动「录下目标处理器接下来 {@code steps} 条指令」的 recording 子快照。 */
    public static void startInstructionSnapshots(LogicBuild build, int steps, Snapshot master){
        getInstrumentation(build, true, instrumentation -> {
            instrumentation.snapshotSteps = Math.min(steps, Snapshots.maxSnapshots);
            instrumentation.master = master;
        });
    }

    public static Instrumentation getInstrumentation(LogicBuild build){
        return getInstrumentation(build, false, instrumentation -> { });
    }

    public static Instrumentation startProfiling(LogicBuild build){
        return getInstrumentation(build, true, Instrumentation::startProfiling);
    }

    public static Instrumentation stopProfiling(LogicBuild build){
        return getInstrumentation(build, false, Instrumentation::stopProfiling);
    }

    public static Instrumentation clearProfilingData(LogicBuild build){
        return getInstrumentation(build, false, Instrumentation::clearProfilingData);
    }

    /** 还原一条（可能被本 profiler 包装过的）指令；未包装的指令原样返回。
     *  {@code ProcessorStatus} 与变量视图都必须在做 {@code instanceof} 之前调用它。 */
    public static LExecutor.LInstruction unwrap(LExecutor.LInstruction instruction){
        return instruction instanceof Instrumentation.InstrumentedInstruction ix ? ix.instruction() : instruction;
    }

    static Seq<LStatement> parse(String code, boolean privileged){
        if(parserConstructor == null || parseMethod == null) return new Seq<>();

        try{
            LParser parser = parserConstructor.newInstance(code, privileged);
            //noinspection unchecked
            return (Seq<LStatement>)parseMethod.invoke(parser);
        }catch(ReflectiveOperationException | RuntimeException e){
            Log.err("LogicSugar: failed to parse the processor code for the profiler", e);
            return new Seq<>();
        }
    }

    private static Field[] getVarFields(Class<?> clazz){
        Field[] fields = varFields.get(clazz);
        if(fields != null) return fields;

        fields = new Field[clazz.getDeclaredFields().length];
        int count = 0;

        for(Field field : clazz.getDeclaredFields()){
            if(LVar.class.isAssignableFrom(field.getType())){
                field.setAccessible(true);
                fields[count++] = field;
            }
        }

        fields = Arrays.copyOf(fields, count);
        varFields.put(clazz, fields);
        return fields;
    }

    static LCategory getCategory(LExecutor.LInstruction instruction){
        return categories.get(instruction.getClass(), LCategory.unknown);
    }

    /** 指令读写到的变量表：本 mod 的调试指令直接给出 {@code vars()}，原版指令反射取
     *  {@code LVar} 字段；反射不可用（或没有变量）时返回空表，recording 快照退化为
     *  「没有默认过滤」而不是报错。 */
    static LVar[] getVars(LExecutor.LInstruction instruction, int reservedSpace){
        if(instruction instanceof logicsugar.assist.AssertInstructions.DevToolsInstruction ix) return ix.vars();

        try{
            Field[] fields = getVarFields(instruction.getClass());
            if(fields == null) return new LVar[0];

            LVar[] result = new LVar[reservedSpace + fields.length];
            for(int i = 0; i < fields.length; i++){
                result[i + reservedSpace] = (LVar)fields[i].get(instruction);
            }
            return result;
        }catch(InaccessibleObjectException | SecurityException | IllegalAccessException e){
            return new LVar[0];
        }
    }

    public static Instrumentation getInstrumentation(LogicBuild build, boolean instrument, Cons<Instrumentation> action){
        if(!initialized || build.executor.instructions.length == 0) return null;

        Instrumentation instrumentation = instrumentations.get(build);
        if(instrumentation == null || instrumentation.instructions != build.executor.instructions){
            if(!instrument){
                instrumentations.remove(build);
                return null;
            }
            instrumentation = new Instrumentation(build.executor);
            instrumentations.put(build, instrumentation);
        }

        if(action != null) action.get(instrumentation);
        return instrumentation;
    }
}
