package logicsugar.vars;

import arc.Events;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Queue;
import arc.struct.Seq;
import mindustry.ai.types.LogicAI;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.logic.LVar;
import mindustry.logic.Senseable;
import mindustry.world.blocks.logic.LogicBlock;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;
import mindustry.world.blocks.logic.MemoryBlock;
import mindustry.world.blocks.logic.MemoryBlock.MemoryBuild;

/**
 * 每个实体一条快照队列（新快照进队首），以及创建/删除/限量逻辑。
 *
 * <p>Ported from upstream MlogAssertions v0.11.3 的 {@code SnapshotManager} 行为语义，
 * 类名保留 LogicSugar 的 {@code Snapshots}（见 docs/architecture.md 的命名差异说明）。
 * 与上游的唯一结构差异：<b>去掉了 MapIndex</b>（上游那份「地图上所有处理器/内存块」的
 * 注册表）。{@link SnapshotType#global} 改为在创建快照的那一刻直接扫
 * {@link Groups#build}（地图顺序），因此这里只保留清理监听器，不注册任何索引。</p>
 *
 * <p>v0.11.2 起数据源是任意 {@link Senseable}（建筑、单位…），队列的键与
 * {@code liveView}/{@code get} 都是 Senseable；{@code connected} 快照还会把「挂在该处理器上
 * 的 {@link LogicAI} 单位」算进连通集合。</p>
 *
 * <p><b>计账（{@link SnapshotRecord#size}）</b>：recording 快照在队列里只占一个位置，
 * 但它内部逐指令录下的子快照每个都计入上限——{@code size} 是「队列条目 + 子快照」的总数，
 * 所以 {@code snapshotLimit} 对 recording 是「总共能保留多少条指令记录」而不是「多少个队列条目」。
 * 子快照创建时调用 {@link #register(Snapshot)} 计数，删除队列条目时按
 * {@code recording().size} 一次性扣回（{@link SnapshotRecord#removed}）。</p>
 *
 * <p>{@link #create(Senseable, SnapshotType, String)} 会被运行中的 mlog {@code snapshot} 指令
 * 调用，所以对无效/已拆除的实体它不能抛异常（上游在此处只做 {@code valid()} 检查）。</p>
 */
public class Snapshots{
    public static int maxSnapshots = 20;
    private static int id = 0;

    // Snapshots
    private static final ObjectMap<Senseable, SnapshotRecord> snapshots = new ObjectMap<>();

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

    /** 上限调小（或设为 0）后立即裁剪已有的队列（recording 的子快照也计入）。 */
    public static void updateLimit(){
        if(maxSnapshots == 0){
            deleteAll();
        }else{
            snapshots.each((b, record) -> record.adjustSize(maxSnapshots));
        }
    }

    public static VariableValues liveView(Senseable entity){
        return entity instanceof MemoryBlock.MemoryBuild build ? new MemoryVars(build) :
                entity instanceof LogicBlock.LogicBuild build ? new ProcessorVars(build) :
                new SensorVars(entity);
    }

    public static boolean hasSnapshots(Senseable entity){
        SnapshotRecord record = snapshots.get(entity);
        return record != null && record.queue.size > 0;
    }

    /** 实体的计账记录；不存在时创建一个（与上游一致，读操作也会建档）。 */
    public static SnapshotRecord getRecord(Senseable entity){
        if(snapshots.containsKey(entity)){
            return snapshots.get(entity);
        }else{
            SnapshotRecord snapshotRecord = new SnapshotRecord();
            snapshots.put(entity, snapshotRecord);
            return snapshotRecord;
        }
    }

    public static Queue<Snapshot> get(Senseable entity){
        return getRecord(entity).queue;
    }

    public static Snapshot create(Senseable entity, String name){
        return create(entity, SnapshotType.connected, name);
    }

    public static Snapshot create(Senseable entity, SnapshotType type, String name){
        return create(entity, type, name, null);
    }

    /** 创建一批快照（整个连通集合或全局集合），返回其中属于 {@code entity} 的第一份
     *  （recording 的「主快照」）。
     *
     *  <p>{@code vars} 非 null 表示这是 profiler 为某条指令生成的 recording 子快照：
     *  只录该指令涉及的实体，设置默认变量过滤，并且不进入队列（队列由主快照占据，
     *  子快照由 {@link #register(Snapshot)} 计数、从主快照的 {@code recording()} 列表访问）。</p> */
    public static Snapshot create(Senseable entity, SnapshotType type, String name, LVar[] vars){
        if(maxSnapshots == 0) return null;

        VariableValues content = liveView(entity);
        if(!content.valid()) return null;

        id++;
        Seq<Senseable> entities = new Seq<>();
        switch(type){
            case isolated -> entities.add(entity);
            case recording -> {
                if(vars != null){
                    ObjectSet<Object> set = new ObjectSet<>();
                    set.add(entity);
                    entities.add(entity);
                    for(LVar v : vars){
                        if(v.obj() instanceof Senseable e && set.add(e)) entities.add(e);
                    }
                    break;
                }
                // 主快照（vars == null）与 connected 相同：记录起始状态 + 连通集合
                collectConnected(entity, content, entities);
            }
            case connected -> collectConnected(entity, content, entities);
            case global -> {
                // 上游在这里读 MapIndex 的两份清单；MapIndex 已删除，改为当场扫地图上的
                // 建筑（顺序即 Groups.build 的遍历顺序），去重是防御性的。
                ObjectSet<Object> set = new ObjectSet<>();
                Groups.build.each(b -> {
                    if((b instanceof LogicBuild || b instanceof MemoryBuild) && set.add(b)) entities.add(b);
                });
            }
        }

        Seq<Snapshot> group = type == SnapshotType.isolated ? null : new Seq<>();
        Snapshot result = null;
        boolean first = true;

        for(Senseable e : entities){
            Snapshot snapshot = create(e, type, group, name);
            if(snapshot == null) return null;

            if(first){
                result = snapshot;
                if(vars != null) snapshot.setDefaultFilter(vars);
                first = false;
            }

            if(vars == null){
                getRecord(snapshot.entity()).add(snapshot);
            }
        }

        return result;
    }

    /** connected/recording 主快照的连通集合：实体本身 + 变量里引用的建筑/单位 +
     *  挂在该处理器上的 {@link LogicAI} 单位（v0.11.2）。 */
    private static void collectConnected(Senseable entity, VariableValues content, Seq<Senseable> entities){
        ObjectSet<Object> set = new ObjectSet<>();
        set.add(entity);
        entities.add(entity);

        content.eachObject(o -> {
            if(o instanceof Building build && set.add(o)) entities.add(build);
            if(o instanceof Unit unit && set.add(o)) entities.add(unit);
        });

        if(entity instanceof LogicBuild){
            Groups.unit.each(u -> {
                if(u.controller() instanceof LogicAI ai && ai.controller == entity && set.add(u)){
                    entities.add(u);
                }
            });
        }
    }

    /** recording 子快照创建后的计账（每个子快照占一个上限额度）。 */
    public static void register(Snapshot snapshot){
        snapshots.get(snapshot.entity()).register();
    }

    /** 删除队列里的一份快照，并把它的 recording 子快照额度扣回。 */
    public static void delete(Snapshot snapshot){
        snapshots.get(snapshot.entity()).removed(snapshot);
    }

    public static void deleteEntity(Senseable entity){
        snapshots.get(entity).clear();
    }

    public static void deleteAll(){
        snapshots.each((b, record) -> record.clear());
        id = 0;
    }

    private static Snapshot create(Senseable entity, SnapshotType type, Seq<Snapshot> group, String name){
        Snapshot snapshot =
                entity instanceof MemoryBlock.MemoryBuild build ? MemorySnapshot.create(build, type, id, group, name) :
                entity instanceof LogicBlock.LogicBuild build ? ProcessorSnapshot.create(build, type, id, group, name) :
                SensorSnapshot.create(entity, type, id, group, name);

        if(group != null && snapshot != null) group.add(snapshot);
        return snapshot;
    }

    /** 一个实体的快照队列与额度计账。上游把本类写成私有的内部类，这里放宽为 public 供
     *  无头自检直接验证 recording 的计账（{@code varsTest}）。 */
    public static class SnapshotRecord{
        public final Queue<Snapshot> queue = new Queue<>();
        /** 队列条目 + recording 子快照的总数（见类注释）。 */
        public int size = 0;

        public void add(Snapshot snapshot){
            queue.addFirst(snapshot);
            size += snapshot.recording() != null ? snapshot.recording().size : 1;
        }

        public void register(){
            size++;
        }

        public void clear(){
            queue.clear();
            size = 0;
        }

        public void adjustSize(int limit){
            while(size > limit){
                // 防御：额度计账没有对应的队列条目时（理论上只可能出现在计账已失衡的
                // 异常路径）不能无限循环，宁可直接归零。
                if(queue.size == 0){
                    size = 0;
                    return;
                }
                Snapshot snapshot = queue.removeLast();
                size -= snapshot.recording() != null ? snapshot.recording().size : 1;
            }
        }

        public void removed(Snapshot snapshot){
            size -= snapshot.recording() != null ? snapshot.recording().size : 1;
        }
    }
}
