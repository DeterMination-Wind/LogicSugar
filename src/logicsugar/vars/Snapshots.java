package logicsugar.vars;

import arc.Events;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Queue;
import arc.struct.Seq;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.world.blocks.logic.LogicBlock;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;
import mindustry.world.blocks.logic.MemoryBlock;
import mindustry.world.blocks.logic.MemoryBlock.MemoryBuild;

/**
 * 每个建筑一条快照队列（新快照进队首），以及创建/删除/限量逻辑。
 *
 * <p>Ported from upstream MlogAssertions v0.11.1 ({@code cardillan.mlogassertions.data.Snapshots})。
 * 与上游的唯一结构差异：<b>去掉了 MapIndex</b>（上游那份「地图上所有处理器/内存块」的
 * 注册表）。{@link SnapshotType#global} 改为在创建快照的那一刻直接扫
 * {@link Groups#build}（地图顺序），因此这里只保留两个清理监听器，不注册任何索引。</p>
 *
 * <p>{@link #create(Building, SnapshotType, String)} 会被运行中的 mlog {@code snapshot} 指令
 * 调用，所以对无效/已拆除的建筑它不能抛异常（上游在此处只做 {@code valid()} 检查）。</p>
 */
public class Snapshots{
    public static int maxSnapshots = 20;
    private static int id = 0;

    // Snapshots
    private static final ObjectMap<Building, Queue<Snapshot>> snapshots = new ObjectMap<>();

    public static void init(){
        Events.on(EventType.ResetEvent.class, e -> {
            snapshots.clear();
            id = 0;
        });

        // 建筑被拆时丢掉它的队列（上游在外层已判 breaking，这里合并成一个条件）
        Events.on(EventType.BlockBuildEndEvent.class, e -> {
            if(e.breaking && (e.tile.build instanceof LogicBuild || e.tile.build instanceof MemoryBuild)){
                snapshots.remove(e.tile.build);
            }
        });
    }

    /** 上限调小（或设为 0）后立即裁剪已有的队列。 */
    public static void updateLimit(){
        if(maxSnapshots == 0){
            deleteAll();
        }else{
            snapshots.each((b, q) -> {
                while(q.size > maxSnapshots) q.removeLast();
            });
        }
    }

    public static VariableValues liveView(Building building){
        return building instanceof MemoryBlock.MemoryBuild build ? new MemoryVars(build) :
                building instanceof LogicBlock.LogicBuild build ? new ProcessorVars(build) :
                new SensorVars(building);
    }

    public static boolean hasSnapshots(Building building){
        Queue<Snapshot> queue = snapshots.get(building);
        return queue != null && queue.size > 0;
    }

    public static Queue<Snapshot> get(Building building){
        snapshots.putMissing(building, new Queue<>());
        return snapshots.get(building);
    }


    public static void create(Building building, String name){
        create(building, SnapshotType.connected, name);
    }

    public static void create(Building building, SnapshotType type, String name){
        if(maxSnapshots == 0) return;

        VariableValues content = liveView(building);
        if(!content.valid()) return;

        id++;
        Seq<Building> buildings = new Seq<>();
        switch(type){
            case isolated -> buildings.add(building);
            case connected -> {
                buildings.add(building);
                ObjectSet<Object> set = new ObjectSet<>();
                content.eachObject(b -> {
                    if(b instanceof Building build && set.add(b)) buildings.add(build);
                });
            }
            case global -> {
                // 上游在这里读 MapIndex 的两份清单；MapIndex 已删除，改为当场扫地图上的
                // 建筑（顺序即 Groups.build 的遍历顺序），去重是防御性的。
                ObjectSet<Object> set = new ObjectSet<>();
                Groups.build.each(b -> {
                    if((b instanceof LogicBuild || b instanceof MemoryBuild) && set.add(b)) buildings.add(b);
                });
            }
        }

        Seq<Snapshot> group = type == SnapshotType.isolated ? null : new Seq<>();

        for(Building b : buildings){
            Queue<Snapshot> queue = get(b);
            Snapshot snapshot = create(b, type, group, name);
            if(snapshot != null){
                queue.addFirst(snapshot);
                if(queue.size > maxSnapshots) queue.removeLast();
            }
        }
    }

    public static void deleteBuilding(Building building){
        snapshots.get(building).clear();
    }

    public static void deleteAll(){
        snapshots.each((b, q) -> q.clear());
        id = 0;
    }

    private static Snapshot create(Building building, SnapshotType type, Seq<Snapshot> group, String name){
        Snapshot snapshot =
                building instanceof MemoryBlock.MemoryBuild build ? MemorySnapshot.create(build, type, id, group, name) :
                building instanceof LogicBlock.LogicBuild build ? ProcessorSnapshot.create(build, type, id, group, name) :
                SensorSnapshot.create(building, type, id, group, name);

        if(group != null && snapshot != null) group.add(snapshot);
        return snapshot;
    }
}
