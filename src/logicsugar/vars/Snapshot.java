package logicsugar.vars;

import arc.struct.Seq;
import mindustry.gen.Building;

/**
 * 一份变量视图的历史存档。
 *
 * <p>Ported from upstream MlogAssertions v0.11.1 ({@code cardillan.mlogassertions.data.Snapshot}),
 * verbatim。{@link #group()} 返回同一批快照里的所有成员（isolated 快照返回 {@code null}，
 * 不是空 {@link Seq}，调用方必须先判空）。</p>
 */
public interface Snapshot extends VariableValues{
    SnapshotType type();
    String name();
    int id();

    // List of all snapshots in the group
    // Retuns null - not an empty Seq! - when the snapshot is isolated.
    Seq<Snapshot> group();

    // Returns the porportion of each type in the snapshot
    float[] typeDistribution();

    /** 把快照值写回一份活数据；数据结构不匹配时返回 false。 */
    boolean writeTo(VariableValues liveData);
}
