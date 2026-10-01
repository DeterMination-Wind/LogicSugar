package logicsugar.vars;

import arc.struct.Seq;
import mindustry.world.blocks.logic.MemoryBlock.MemoryBuild;

/**
 * 一块内存单元的快照：{@code super(build, false)} 让 {@link MemoryVars} 复制两个数组
 * （而不是持有活数组的引用），所以快照之后内存被改写也不会影响它。
 *
 * <p>Ported from upstream MlogAssertions v0.11.1 ({@code cardillan.mlogassertions.data.MemorySnapshot}),
 * verbatim。</p>
 */
public class MemorySnapshot extends MemoryVars implements Snapshot{
    public String name;
    public final SnapshotType type;
    public final int id;
    public final Seq<Snapshot> group;

    public static MemorySnapshot create(MemoryBuild build, SnapshotType type, int id, Seq<Snapshot> group, String name){
        return new MemorySnapshot(build, type, id, group, name);
    }

    private MemorySnapshot(MemoryBuild build, SnapshotType type, int id, Seq<Snapshot> group, String name){
        super(build, false);
        this.type = type;
        this.id = id;
        this.group = group;
        this.name = name;
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
        if(liveData instanceof MemoryVars memory){
            if(memory.length != length) return false;
            System.arraycopy(objectMemory, 0, memory.objectMemory, 0, length);
            System.arraycopy(numberMemory, 0, memory.numberMemory, 0, length);
            return true;
        }else{
            return false;
        }
    }

    private float[] typeDistribution = null;

    @Override
    public float[] typeDistribution(){
        if(typeDistribution == null) typeDistribution = computeTypeDistribution();
        return typeDistribution;
    }
}
