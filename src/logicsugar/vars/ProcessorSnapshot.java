package logicsugar.vars;

import arc.struct.Seq;
import mindustry.logic.LExecutor;
import mindustry.logic.LVar;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;

/**
 * 处理器变量的一份快照：{@link #create} 时把每个 {@link LVar} 连同它的 id/isobj/constant
 * 及当前值复制出来，所以之后处理器继续跑也不会改变快照内容。
 *
 * <p>Ported from upstream MlogAssertions v0.11.1 ({@code cardillan.mlogassertions.data.ProcessorSnapshot}),
 * verbatim。{@link #writeTo} 要求活处理器的变量表与快照逐行同名同 id（程序改过就拒绝写回），
 * 并恢复文本缓冲与 {@code wait} 指令的计时。</p>
 */
public class ProcessorSnapshot extends ProcessorVars implements Snapshot{
    public String name;
    public final SnapshotType type;
    public final int id;
    public final Seq<Snapshot> group;

    public final String textBuffer;
    public final float timeWaited;

    private float[] typeDistribution = null;

    public static ProcessorSnapshot create(LogicBuild build, SnapshotType type, int id, Seq<Snapshot> group, String name){
        return build.executor.vars.length == 0 ? null : new ProcessorSnapshot(build, type, id, group, name);
    }

    private ProcessorSnapshot(LogicBuild build, SnapshotType type, int id, Seq<Snapshot> group, String name){
        super(build);
        this.type = type;
        this.id = id;
        this.group = group;
        this.textBuffer = executor.textBuffer.toString();
        this.timeWaited = timeWaited();
        this.name = name;

        setView(false, false, false);
        typeDistribution = computeTypeDistribution();
    }

    @Override
    protected void store(LVar var){
        if(var != null){
            LVar copy = new LVar(var.name);
            copy.id = var.id;
            copy.isobj = var.isobj;
            copy.constant = var.constant;
            copy.objval = var.objval;
            copy.numval = var.numval;
            data[length++] = copy;
        }
    }

    @Override
    public String textBuffer(){
        return textBuffer;
    }

    @Override
    public SnapshotType type(){
        return type;
    }

    @Override
    public String name(){
        return name;
    }

    @Override
    public int id(){
        return id;
    }

    @Override
    public Seq<Snapshot> group(){
        return group;
    }

    @Override
    public boolean writeTo(VariableValues liveData){
        if(liveData instanceof ProcessorVars processor){
            if(processor.data.length != data.length) return false;
            for(int i = 0; i < data.length; i++){
                if(!data[i].name.equals(processor.data[i].name) || data[i].constant != processor.data[i].constant || data[i].id != processor.data[i].id) return false;
            }

            for(int i = 0; i < data.length; i++){
                processor.data[i].objval = data[i].objval;
                processor.data[i].numval = data[i].numval;
            }

            processor.executor.textBuffer.setLength(0);
            processor.executor.textBuffer.append(textBuffer);

            int counter = (int)processor.executor.counter.numval;
            if(counter >= 0 && counter < executor.instructions.length && executor.instructions[counter] instanceof LExecutor.WaitI w){
                w.curTime = (float)timeWaited;
            }

            return true;
        }else{
            return false;
        }
    }

    @Override
    public float[] typeDistribution(){
        return typeDistribution;
    }
}
