package logicsugar.profile;

import arc.graphics.Color;
import arc.struct.Seq;
import logicsugar.assist.AssertInstructions;
import logicsugar.vars.Snapshot;
import logicsugar.vars.Snapshots;
import logicsugar.vars.SnapshotType;
import mindustry.logic.LExecutor;
import mindustry.logic.LStatement;
import mindustry.logic.LVar;
import mindustry.world.blocks.logic.LogicBlock;

import java.util.Arrays;
import java.util.BitSet;

/**
 * 一块处理器的执行统计与「本地包装器」。
 *
 * <p>Ported from upstream MlogAssertions v0.11.6 ({@code cardillan.mlogassertions.logic.Instrumentation}),
 * 行为逐字保留；仅把上游的 {@code SnapshotManager} 换成 LogicSugar 的
 * {@link Snapshots}、日志前缀换成 LogicSugar。</p>
 *
 * <p><b>只改运行期的指令数组，不碰保存产物</b>：{@link #instrument} 把 {@code LExecutor.instructions}
 * 里的每条指令换成一个转发包装器（{@code InstrumentedWait} 另外继承原版 {@code WaitI}，
 * 因此原版那些 {@code instanceof WaitI} 的判断与 wait 指示弧仍然可用）。包装只发生在
 * 本地进程里，处理器保存的 mlog 一个字都不变；profiler 因此不是「改变保存产物的功能」，
 * 也不受联机门禁限制——但联机下每个客户端各算各的，调试观测不跨端。</p>
 *
 * <p>统计口径（上游 v0.11.6）：{@code steps} 是执行次数，{@code time} 是消耗的指令预算
 * （{@code double}；让出时按 {@code accumulator + edelta*ipt - maxInstructionScale*ipt} 估算
 * 「丢配额」），{@code branching} 记录跳转指令「没有落到下一条」的次数，覆盖率与峰值在
 * {@link Counters} 里。记录分两条路径：</p>
 *
 * <ul>
 * <li><b>快路径</b>（{@link InstrumentationEngine#noYielding} 里的原版指令，以及
 * {@code AssertInstructions.DevToolsInstruction.yields() == false} 的调试指令）：
 * 非让出指令恒 +1 步、+1 份配额，覆盖率看 {@code steps[index] == 0}，不读 {@code exec.yield}
 * （只有跳转指令才读一次 counter 记分支）。</li>
 * <li><b>让出路径</b>（可能让出执行权的指令：{@code wait}/{@code stop}/{@code flushmsg} 与
 * 会失败的断言）：只有计数器真的前进才算一步（{@code wait 0} 让出但确实执行了一次），
 * 配额按 {@link #stepQuota} 估算并累计 {@code lostQuota}，覆盖率看 {@link #covered} 位图。</li>
 * </ul>
 *
 * <p>两条路径的覆盖率来源不同（快路径只写 {@code steps}，让出路径只写 {@code covered}），
 * 所以读方必须走 {@link #isCovered(int)} 合并判断——只读其中一个会漏掉一半已执行的指令。</p>
 *
 * <p>包装器跑完指令后检测 {@code exec.stop}（上游 v0.11.4）：停机后统计与录制一起停下，
 * 界面顶部按钮显示已停。失败断言与 {@code error} 指令现在就是停机语义（见
 * {@code AssertInstructions.assertion}/{@code ErrorI}）。</p>
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
    /** 让出路径的访问位图（快路径的访问判定是 {@code steps[index] > 0}，合并规则见
     *  {@link #isCovered(int)}）。 */
    public final BitSet covered;
    public final int[] branching;
    public final int[] steps;
    public final double[] time;
    /** 覆盖率/峰值/总量：两条记录路径共用同一份累加器（见 {@link Counters}）。 */
    public final Counters counters = new Counters();

    // Snapshotting
    public Snapshot master;
    public int snapshotSteps = 0;

    public Instrumentation(LExecutor executor){
        this.executor = executor;
        this.size = executor.instructions.length;
        this.instructions = executor.instructions;
        this.maxInstructionScale = ((LogicBlock)executor.build.block).maxInstructionScale;

        this.covered = new BitSet(size);
        this.steps = new int[size];
        this.time = new double[size];
        this.branching = new int[size];
        this.colors = new Color[size];
        this.source = new String[size];

        Seq<LStatement> parsedSeq = InstrumentationEngine.parse(executor.build.code, executor.privileged);

        for(int i = 0; i < instructions.length; i++){
            LExecutor.LInstruction instruction = instructions[i];
            source[i] = i < parsedSeq.size ? printInstruction(parsedSeq.get(i)) : "unknown instruction";
            // 上游 v0.11.4：类别色在这里就压暗一次（行里不再二次乘，省掉每条指令一次的 cpy+mul）
            colors[i] = InstrumentationEngine.getCategory(instruction).color.cpy().mul(0.8f);
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

    /** 停机（{@code exec.stop}）后的共同收尾：停止统计，并放弃还没走完的 recording 快照
     *  （上游 v0.11.4）。运行时由两个包装器在指令跑完后调用。 */
    public void stopAll(){
        profiling = false;
        snapshotSteps = 0;
    }

    public void clearProfilingData(){
        Arrays.fill(steps, 0);
        Arrays.fill(time, 0d);
        for(int i = 0; i < branching.length; i++) branching[i] = Math.min(branching[i], 0);
        covered.clear();
        counters.clear();
    }

    /** 行序（上游 v0.11.6 的 {@code ProfileDialog.sort()}，提取成静态以便无头自检）：未排序时
     *  返回恒等映射；排序时把 {@code (计数 << 32) | (size - i)} 打包成 {@code long} 升序，再反向
     *  取索引——计数降序，计数相同时保持原始行序（低 32 位是唯一的 {@code size - i}，因此 int
     *  步数不会因为打包而溢出）。
     *
     *  @param steps    执行次数（{@code execTime} 为假时的排序键）
     *  @param time     消耗的指令预算（{@code execTime} 为真时的排序键）
     *  @param execTime 按配额而不是执行次数排序
     *  @param sorted   排序开关；关闭时返回恒等映射
     *  @return 行号到指令下标的映射（长度 = {@code steps.length}） */
    public static int[] order(int[] steps, double[] time, boolean execTime, boolean sorted){
        int size = steps.length;
        int[] indexes = new int[size];

        if(!sorted){
            for(int i = 0; i < size; i++) indexes[i] = i;
            return indexes;
        }

        long[] keys = new long[size];
        for(int i = 0; i < size; i++){
            long count = execTime ? (long)time[i] : steps[i];
            keys[i] = (count << 32) | (long)(size - i);
        }
        Arrays.sort(keys);

        for(int i = 0; i < size; i++){
            indexes[i] = size - (int)(keys[size - i - 1] & (long)Integer.MAX_VALUE);
        }
        return indexes;
    }

    /** 快路径：{@code index} 是刚执行完的指令下标（包装器从 {@code exec.counter} 反推）。
     *  非让出指令没有丢配额可言，因此只在自己是跳转指令时读一次 counter。 */
    private void recordStep(LExecutor exec, int index){
        if(!profiling || index < 0 || index >= steps.length) return;

        recordFastStep(steps, time, counters, index);
        if(branching[index] >= 0 && tookBranch((int)(exec.counter.numval), index)) branching[index]++;
    }

    /** 让出路径：同一次执行要判断「有没有真的前进」并估算丢掉的配额。逻辑与上游 v0.11.3 的
     *  {@code recordStep} 相同，只有覆盖率来源换成 {@link #covered}（上游 v0.11.4）。 */
    private void recordStepWithYielding(LExecutor exec, int index){
        if(!profiling || index < 0 || index >= steps.length) return;

        boolean yielded = exec.yield;
        int newCounter = (int)(exec.counter.numval);
        boolean step = countsAsStep(yielded, newCounter, index);
        double cost = yielded
            ? stepQuota(exec.build.accumulator, exec.build.edelta(), exec.build.ipt, maxInstructionScale)
            : 1d;

        recordYieldedStep(steps, time, covered, counters, index, yielded, step, cost);
        if(branching[index] >= 0 && tookBranch(newCounter, index)) branching[index]++;
    }

    /** 快路径的计数口径（无头可测，见 {@code ProfileInstrumentationTest}）：非让出指令恒
     *  +1 步、+1 份配额；覆盖率在第一次执行时 +1；{@code time} 就是执行次数。
     *
     *  <p>跳转指令的 {@code branching} 由调用方记录（它要读 {@code exec.counter}）。</p> */
    static void recordFastStep(int[] steps, double[] time, Counters counters, int index){
        counters.totalSteps++;
        counters.totalTime += 1d;

        if(steps[index] == 0) counters.coverage++;
        int updatedSteps = ++steps[index];
        time[index] = updatedSteps;

        if(updatedSteps > counters.maxSteps) counters.maxSteps = updatedSteps;
        if(updatedSteps > counters.maxTime) counters.maxTime = updatedSteps;
    }

    /** 让出路径的计数口径（无头可测）：{@code counts} 为假时不记步（让出且计数器没前进，
     *  例如 {@code wait 1}），{@code cost} 是这一步消耗的指令预算（非让出恒 1，让出时是
     *  {@link #stepQuota} 的估算值），{@code yielded} 为真时把 {@code cost} 计进
     *  {@code lostQuota}。覆盖率用 {@link BitSet}：让出指令即使一步都没记也标记为已访问。 */
    static void recordYieldedStep(int[] steps, double[] time, BitSet covered, Counters counters,
                                  int index, boolean yielded, boolean counts, double cost){
        if(!covered.get(index)){
            covered.set(index);
            counters.coverage++;
        }

        if(counts){
            counters.totalSteps++;
            // 与上游一致：峰值用自增前的步数（快路径用自增后的），因此让出路径的 maxSteps 会
            // 比实际值少 1。两条路径的进度条都除以同一个 maxSteps，这个差只影响条长，不影响步数。
            int updatedSteps = steps[index]++;
            if(updatedSteps > counters.maxSteps) counters.maxSteps = updatedSteps;
        }

        if(cost > 0){
            counters.totalTime += cost;
            double updatedTime = time[index] += cost;
            if(updatedTime > counters.maxTime) counters.maxTime = updatedTime;
        }

        if(yielded) counters.lostQuota += cost;
    }

    /** 「这条指令执行过没有」：快路径只写 {@code steps}、让出路径只写 {@code covered}，
     *  所以读方必须两个都看（上游 v0.11.6 的 {@code ProfileDialog} 内联了同一个判断）。
     *  无头可测的静态形式；对话框走 {@link #isCovered(int)}。 */
    static boolean isCovered(int[] steps, BitSet covered, int index){
        return steps[index] > 0 || covered.get(index);
    }

    /** {@link #isCovered(int[])} 的实例形式（对话框的行颜色用它）。 */
    public boolean isCovered(int index){
        return isCovered(steps, covered, index);
    }

    /** 该步是否计入 {@code steps}：让出执行权时只有计数器真的前进了才算一步
     *  （{@code wait 0} 会让出但确实执行了一次；{@code wait 1} 不前进，不计步）。 */
    static boolean countsAsStep(boolean yielded, int newCounter, int index){
        return !yielded || newCounter != index;
    }

    /** 跳转指令「没有落到下一条」才算一次分支（跳转与落空都算，target 恰好是 i+1 也算，
     *  因为 mlog 的跳转只在真的改写了计数器时才花时间）。上游 v0.11.3 的内联判断。 */
    static boolean tookBranch(int newCounter, int index){
        return newCounter != index + 1;
    }

    /** 一次 {@code yield} 消耗的指令预算（上游的估算）：执行器的 accumulator 加上本帧尚未
     *  结算的 {@code edelta * ipt}，减去被上限截断的部分；负值归零。它就是「丢配额」
     *  （{@code lostQuota}）的来源。 */
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

    /** 包装一条指令；对已经包装过的输入先解包，保证幂等。
     *  不会让出的指令走快路径包装器，其余走让出路径（上游 v0.11.6）。 */
    private InstrumentedInstruction instrument(LExecutor.LInstruction instruction){
        // Repeated instrumentation shouldn't happen, but if it does, we need to handle it gracefully.
        if(instruction instanceof InstrumentedInstruction ix) instruction = ix.instruction();

        boolean noYields = InstrumentationEngine.noYielding.contains(instruction.getClass())
            || instruction instanceof AssertInstructions.DevToolsInstruction ix && !ix.yields();

        return instruction instanceof LExecutor.WaitI wait ? new InstrumentedWait(wait)
            : noYields ? new BasicInstrumentedInstruction(instruction)
            : new YieldingInstrumentedInstruction(instruction);
    }

    private static StringBuilder sbr = new StringBuilder();
    private static String printInstruction(LStatement statement){
        sbr.setLength(0);
        statement.write(sbr);
        return sbr.toString();
    }

    /** 覆盖率/峰值/总量的累加器：两条记录路径共用同一份口径。
     *
     *  <p>与上游的差别（有意，且只影响代码形状）：上游把这些数字与 {@code steps}/{@code time}/
     *  {@code covered} 一起摊在 {@code Instrumentation} 的字段上，而 {@code Instrumentation}
     *  需要一块活的 {@code LogicBlock} 才能构造，测试造不出来。把「只有计步才会动」的那部分
     *  抽成独立对象后，{@link #recordFastStep}/{@link #recordYieldedStep} 可以完全无头地验证
     *  （{@code ProfileInstrumentationTest}）。数值语义与上游逐字一致。</p> */
    public static final class Counters{
        public int coverage = 0;
        public int maxSteps = 0;
        public int totalSteps = 0;
        public double maxTime = 0;
        public double totalTime = 0;
        public double lostQuota = 0;

        public void clear(){
            coverage = 0;
            maxSteps = 0;
            totalSteps = 0;
            maxTime = 0;
            totalTime = 0;
            lostQuota = 0;
        }
    }

    /** 包装器的公共接口：{@link InstrumentationEngine#unwrap} 与
     *  {@code ProcessorStatus} 的扫描都靠它把指令还原成原版对象。 */
    public interface InstrumentedInstruction extends LExecutor.LInstruction{
        LExecutor.LInstruction instruction();
    }

    /** 快路径包装器：不读 {@code exec.yield}/{@code exec.counter}（跳转指令除外）。 */
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

            // 停机（stop 指令、失败断言、error 指令）：统计与录制一起停
            if(exec.stop) stopAll();
        }
    }

    /** 让出路径包装器：与 {@link BasicInstrumentedInstruction} 同形，只是换用会估算配额的
     *  记录方法。隐式 {@code @unit} 无关紧要——会让出的指令里没有单位指令（上游同）。 */
    private class YieldingInstrumentedInstruction implements InstrumentedInstruction{
        LExecutor.LInstruction instruction;
        LVar[] vars = null;

        public YieldingInstrumentedInstruction(LExecutor.LInstruction instruction){
            this.instruction = instruction;
        }

        @Override
        public LExecutor.LInstruction instruction(){
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
            recordStepWithYielding(exec, index);

            if(snapshotSteps > 0){
                snapshotSteps--;
                createSnapshot(index, vars());
            }

            if(exec.stop) stopAll();
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
            recordStepWithYielding(exec, index);

            // 原版 WaitI 自己维护 curTime；包装器每步同步一次，让 wait 指示弧读到的是当前值
            curTime = instruction.curTime;
            if(curTime == 0 && snapshotSteps > 0){
                snapshotSteps--;
                createSnapshot(index, vars());
            }
        }
    }
}
