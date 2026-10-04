package logicsugar.vars;

import arc.struct.Seq;
import mindustry.logic.LVar;
import mindustry.logic.Senseable;

/**
 * 一座建筑的传感器视图快照：每行存「对象或数值」的一个副本（数值行用 {@link Double} 表示）。
 *
 * <p>Ported from upstream MlogAssertions v0.11.3 ({@code cardillan.mlogassertions.data.SensorSnapshot}),
 * verbatim（v0.11.2 把参数从建筑放宽到 {@link Senseable}，并给 {@code typeDistribution}
 * 加上懒计算）：{@link #writeTo} 恒为 false —— 传感器读数由建筑自己派生，没有可写回的东西，
 * 上游的恢复按钮也对 {@code properties} 类型禁用。</p>
 */
public class SensorSnapshot extends SensorVars implements Snapshot{
    public String name;
    public final SnapshotType type;
    public final int id;
    public final Seq<Snapshot> group;

    public final Object[] values = new Object[length];

    private float[] typeDistribution = null;

    public static SensorSnapshot create(Senseable entity, SnapshotType type, int id, Seq<Snapshot> group, String name){
        return new SensorSnapshot(entity, type, id, group, name);
    }

    private SensorSnapshot(Senseable entity, SnapshotType type, int id, Seq<Snapshot> group, String name){
        super(entity);
        this.type = type;
        this.id = id;
        this.group = group;
        this.name = name;

        for(int i = 0; i < length; i++){
            values[i] = super.isObj(i) ? super.obj(i) : super.num(i);
        }
    }

    @Override
    public boolean isObj(int index){
        return !(values[index] instanceof Double);
    }

    @Override
    public Object obj(int index){
        return values[index] instanceof Double ? null : values[index];
    }

    @Override
    public double num(int index){
        return values[index] instanceof Double d ? d : 0;
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
    public Seq<Snapshot> recording(){
        return null;
    }

    @Override
    public boolean writeTo(VariableValues liveData){
        return false;
    }

    @Override
    public float[] typeDistribution(){
        if(typeDistribution == null) typeDistribution = computeTypeDistribution();
        return typeDistribution;
    }

    @Override
    public void setDefaultFilter(LVar[] vars){
        // 传感器读数没有「指令变量」的概念
    }
}
