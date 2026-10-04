package logicsugar.vars;

import arc.struct.Seq;
import mindustry.logic.LVar;

/**
 * 一份变量视图的历史存档。
 *
 * <p>Ported from upstream MlogAssertions v0.11.3 ({@code cardillan.mlogassertions.data.Snapshot}),
 * verbatim。{@link #group()} 返回同一批快照里的所有成员（isolated 快照返回 {@code null}，
 * 不是空 {@link Seq}，调用方必须先判空）；{@link #recording()} 返回 recording 快照内部
 * 逐指令录下的子快照（非 recording 快照返回 {@code null}）。</p>
 */
public interface Snapshot extends VariableValues{
    SnapshotType type();
    String name();
    int id();

    // List of all snapshots in the group
    // Retuns null - not an empty Seq! - when the snapshot is isolated.
    Seq<Snapshot> group();

    // List of snaphots within a recording snapshott, null if not a recording snapshot
    Seq<Snapshot> recording();

    // Returns the porportion of each type in the snapshot
    float[] typeDistribution();

    boolean writeTo(VariableValues liveData);

    /** recording 快照的默认过滤（只显示触发指令用到的变量）；其余快照忽略。 */
    void setDefaultFilter(LVar[] vars);
}
