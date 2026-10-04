package logicsugar.profile;

import arc.graphics.Color;
import arc.struct.Seq;
import logicsugar.vars.Snapshot;
import logicsugar.vars.Snapshots;
import logicsugar.vars.SnapshotType;
import mindustry.logic.LExecutor;
import mindustry.logic.LStatement;
import mindustry.logic.LVar;
import mindustry.world.blocks.logic.LogicBlock;

import java.util.Arrays;

/**
 * 一块处理器的执行统计与「本地包装器」。
 *
 * <p>Ported from upstream MlogAssertions v0.11.3 ({@code cardillan.mlogassertions.logic.Instrumentation}),
 * 行为逐字保留；仅把上游的 {@code SnapshotManager} 换成 LogicSugar 的
 * {@link Snapshots}、日志前缀换成 LogicSugar。</p>
 *
 * <p><b>只改运行期的指令数组，不碰保存产物</b>：{@link #instrument} 把 {@code LExecutor.instructions}
 * 里的每条指令换成一个转发包装器（{@code InstrumentedWait} 另外继承原版 {@code WaitI}，
 * 因此原版那些 {@code instanceof WaitI} 的判断与 wait 指示弧仍然可用）。包装只发生在
 * 本地进程里，处理器保存的 mlog 一个字都不变；profiler 因此不是「改变保存产物的功能」，
 * 也不受联机门禁限制——但联机下每个客户端各算各的，调试观测不跨端。</p>
 *
 * <p>统计口径（与上游一致）：{@code steps} 是执行次数，{@code time} 是消耗的指令预算
 * （{@code yield} 时按 {@code accumulator + edelta*ipt - maxInstructionScale*ipt} 估算lost quota），
 * {@code branching} 记录跳转指令「没有落到下一条」的次数，{@code coverage} 是被执行过的
 * 指令条数。{@code lostQuota} 累计被 yield 丢掉的预算。</p>
 */
public class Instrumentation{
    public final LExecutor executor;

    // Profiling: constant data
    public final int maxInstructionScale;
    public final LExecutor.LInstruction[] instructions;
    public final String[] source;
    public final Color[] colors;
    public final int size;

    // Profiling: live data
    public boolean profiling = false;
    public final int[] branching;
    public final int[] steps;
    public final float[] time;
    public int coverage = 0;
    public int maxSteps = 0;
    public int totalSteps = 0;
    public float maxTime = 0;
    public float totalTime = 0;
    public float lostQuota = 0;

    // Snapshotting
    public Snapshot master;
    public int snapshotSteps = 0;

    public Instrumentation(LExecutor executor){
        this.executor = executor;
        this.size = executor.instructions.length;
        this.instructions = executor.instructions;
        this.maxInstructionScale = ((LogicBlock)executor.build.block).maxInstructionScale;

        this.steps = new int[size];
        this.time = new float[size];
        this.branching = new int[size];
        this.colors = new Color[size];
        this.source = new String[size];

        Seq<LStatement> parsedSeq = InstrumentationEngine.parse(executor.build.code, executor.privileged);

        for(int i = 0; i < instructions.length; i++){
            LExecutor.LInstruction instruction = instructions[i];
            source[i] = i < parsedSeq.size ? printInstruction(parsedSeq.get(i)) : "unknown instruction";
            colors[i] = InstrumentationEngine.getCategory(instruction).color;
            branching[i] = instruction instanceof LExecutor.JumpI ? 0 : -1;
            instructions[i] = instrument(instruction);
        }
    }

    public void startProfiling(){
        profiling = true;
    }

    public void stopProfiling(){
        profiling = false;
    }

    public void clearProfilingData(){
        Arrays.fill(steps, 0);
        Arrays.fill(time, 0f);
        for(int i = 0; i < branching.length; i++) branching[i] = Math.min(branching[i], 0);
        coverage = 0;
        maxSteps = 0;
        totalSteps = 0;
        maxTime = 0;
        totalTime = 0;
        lostQuota = 0;
    }

    /** 一步统计。{@code index} 是刚执行完的指令下标（包装器从 {@code exec.counter} 反推）。 */
    private void recordStep(LExecutor exec, int index){
        if(profiling && index >= 0 && index < steps.length){
            int newCounter = (int)(exec.counter.numval);
            // 跳转指令「没有落到下一条」才算一次分支（落空/跳转都算，包括 target 恰好是 i+1）
            if(newCounter != index + 1 && branching[index] >= 0) branching[index]++;

            boolean step = countsAsStep(exec.yield, newCounter, index);
            float curTime = exec.yield
                ? stepQuota(exec.build.accumulator, exec.build.edelta(), exec.build.ipt, maxInstructionScale)
                : 1f;
            if(exec.yield) lostQuota += curTime;

            if(step){
                totalSteps++;
                int updatedSteps = steps[index]++;
                if(updatedSteps > maxSteps) maxSteps = updatedSteps;
            }

            if(time[index] == 0 && curTime > 0) coverage++;

            totalTime += curTime;
            float updatedTime = time[index] += curTime;
            if(updatedTime > maxTime) maxTime = updatedTime;
        }
    }

    /** 该步是否计入 {@code steps}：让出执行权时只有计数器真的前进了才算一步
     *  （{@code wait 0} 会让出但确实执行了一次；{@code wait 1} 不前进，不计步）。 */
    static boolean countsAsStep(boolean yielded, int newCounter, int index){
        return !yielded || newCounter != index;
    }

    /** 一次 {@code yield} 消耗的指令预算（上游 v0.11.3 的估算）：执行器的 accumulator
     *  加上本帧尚未结算的 {@code edelta * ipt}，减去被上限截断的部分；负值归零。
     *  它就是「丢配额」（{@code lostQuota}）的来源。 */
    static float stepQuota(float accumulator, float edelta, float ipt, int maxInstructionScale){
        float futureAccumulator = accumulator + edelta * ipt;
        float loss = futureAccumulator - maxInstructionScale * ipt;
        return Math.max(0, loss);
    }

    /** recording 快照的逐指令子快照（由 {@link InstrumentationEngine#startInstructionSnapshots} 触发）。 */
    private void createSnapshot(int index, LVar[] vars){
        String text = index >= 0 && index < source.length ? source[index] : "unknown instruction";
        Snapshot snapshot = Snapshots.create(executor.build, SnapshotType.recording, index + ": " + text, vars);
        master.recording().add(snapshot);
        Snapshots.register(snapshot);
    }

    /** 包装一条指令；对已经包装过的输入先解包，保证幂等。 */
    private InstrumentedInstruction instrument(LExecutor.LInstruction instruction){
        // Repeated instrumentation shouldn't happen, but if it does, we need to handle it gracefully.
        if(instruction instanceof InstrumentedInstruction ix) instruction = ix.instruction();
        return instruction instanceof LExecutor.WaitI wait ? new InstrumentedWait(wait) : new BasicInstrumentedInstruction(instruction);
    }

    private static StringBuilder sbr = new StringBuilder();
    private static String printInstruction(LStatement statement){
        sbr.setLength(0);
        statement.write(sbr);
        return sbr.toString();
    }

    /** 包装器的公共接口：{@link InstrumentationEngine#unwrap} 与
     *  {@code ProcessorStatus} 的扫描都靠它把指令还原成原版对象。 */
    public interface InstrumentedInstruction extends LExecutor.LInstruction{
        LExecutor.LInstruction instruction();
    }

    private class BasicInstrumentedInstruction implements InstrumentedInstruction{
        LExecutor.LInstruction instruction;
        boolean implicitUnit;
        LVar[] vars = null;

        public BasicInstrumentedInstruction(LExecutor.LInstruction instruction){
            this.instruction = instruction;
            this.implicitUnit = instruction instanceof LExecutor.UnitBindI
                    || instruction instanceof LExecutor.UnitControlI
                    || instruction instanceof LExecutor.UnitLocateI;
        }

        @Override
        public LExecutor.LInstruction instruction(){
            return instruction;
        }

        /** 指令读写的变量（recording 子快照的默认过滤）：原版指令经反射取 LVar 字段，
         *  隐式使用 {@code @unit} 的单位指令把它放在第一位。 */
        public LVar[] vars(){
            if(vars == null){
                vars = InstrumentationEngine.getVars(instruction, implicitUnit ? 1 : 0);
                if(implicitUnit) vars[0] = executor.unit;
            }
            return vars;
        }

        @Override
        public void run(LExecutor exec){
            int index = (int)(exec.counter.numval - 1);
            instruction.run(exec);
            recordStep(exec, index);

            if(snapshotSteps > 0){
                snapshotSteps--;
                createSnapshot(index, vars());
            }
        }
    }

    private class InstrumentedWait extends LExecutor.WaitI implements InstrumentedInstruction{
        LExecutor.WaitI instruction;
        LVar[] vars = null;

        public InstrumentedWait(LExecutor.WaitI instruction){
            this.instruction = instruction;
            this.value = instruction.value;
            this.curTime = instruction.curTime;
        }

        @Override
        public LExecutor.WaitI instruction(){
            return instruction;
        }

        public LVar[] vars(){
            if(vars == null) vars = InstrumentationEngine.getVars(instruction, 0);
            return vars;
        }

        @Override
        public void run(LExecutor exec){
            int index = (int)(exec.counter.numval - 1);
            instruction.run(exec);
            recordStep(exec, index);

            // 原版 WaitI 自己维护 curTime；包装器每步同步一次，让 wait 指示弧读到的是当前值
            curTime = instruction.curTime;
            if(curTime == 0 && snapshotSteps > 0){
                snapshotSteps--;
                createSnapshot(index, vars());
            }
        }
    }
}
