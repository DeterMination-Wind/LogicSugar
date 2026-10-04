package logicsugar.vars.ui;

import arc.struct.Queue;
import arc.struct.Seq;
import logicsugar.assist.L10n;
import logicsugar.vars.Snapshot;
import logicsugar.vars.SnapshotType;
import logicsugar.vars.Snapshots;
import logicsugar.vars.VariableValues;
import mindustry.logic.Senseable;

/**
 * {@link VarsDialog} 正在浏览的「视图序列」：一块建筑的活数据 + 它的快照队列，
 * 或者一批同组快照（{@code snapshot} 指令一次创建出来的所有块）。
 *
 * <p>Ported from upstream MlogAssertions v0.11.3
 * ({@code cardillan.mlogassertions.ui.Snapshots}；上游 v0.11.2 把本接口从
 * {@code SnapshotList} 改名为 {@code Snapshots}，LogicSugar 保留原名以避免与数据层的
 * {@link Snapshots} 撞名——两者行为逐字一致，含 {@code recording()} 与 recording 的位置
 * 文本）。两处对上游的偏离：</p>
 * <ul>
 * <li>接口本身从包内可见改为 {@code public}：集成代码（逻辑编辑器按钮、三方快照入口）
 * 在别的包里构造 {@link VarsDialog}/{@link SnapshotsDialog} 并把本类型传进去，
 * 否则 {@code VarsDialog.setup(SnapshotList)} 这个上游公开方法在包外无法调用。
 * 方法在接口里本来就是隐式 public，可见性没有放宽语义。</li>
 * <li>上游 {@code updateIndex()} 里的四行 {@code Log.info} 调试输出没有移植：{@code pos()}
 * 被标题栏的 {@code update(...)} 每帧调用，保留下来就是每帧往日志里写两行。
 * 索引推进本身逐字保留（{@code OBSOLETE}/{@code LIVE} 的取值、回退与钳制规则不变）。</li>
 * </ul>
 *
 * <p>用户可见文本走 {@code logicsugar.vars.*} bundle 键（{@link L10n#text}：
 * 键缺失时用英文 fallback，无头环境不抛异常）。</p>
 */
public interface SnapshotList{
    /** 是否是一批同组快照的列表（而不是某块建筑的活数据 + 快照）。 */
    boolean group();
    /** 是否是 recording 主快照内部的逐指令子快照列表（标题与位置文本改用指令序号）。 */
    boolean recording();
    String title();
    VariableValues liveData();

    String pos();

    VariableValues view();
    boolean next();
    boolean prev();
    boolean first();
    boolean last();
    boolean hasNext();
    boolean hasPrev();
    SnapshotList select(VariableValues var);

    boolean canRemove();
    boolean remove();

    Seq<Snapshot> list();

    /** 某块建筑的活视图 + 快照队列（新快照在队首）。 */
    static SnapshotList forBuild(final Senseable entity){
        return new SnapshotList(){
            static final int OBSOLETE = -2;
            static final int LIVE = -1;

            final VariableValues live = Snapshots.liveView(entity);
            final Queue<Snapshot> queue = Snapshots.get(entity);
            VariableValues view = live;
            int index = LIVE;

            @Override
            public boolean group(){
                return false;
            }

            @Override
            public boolean recording(){
                return false;
            }

            @Override
            public String title(){
                // 建筑类型名（Vars / Memory / Properties）由 BlockDataType 自己本地化
                return live.dataType().name;
            }

            @Override
            public VariableValues liveData(){
                return live;
            }

            @Override
            public String pos(){
                updateIndex();
                return index == LIVE
                        ? L10n.text("logicsugar.vars.pos.live", "live")
                        : index < 0
                          ? L10n.text("logicsugar.vars.pos.obsolete", "--/{0}", queue.size)
                          : L10n.text("logicsugar.vars.pos.index", "{0}/{1}", index + 1, queue.size);
            }

            @Override
            public VariableValues view(){
                return view;
            }

            @Override
            public boolean next(){
                updateIndex();
                if(index >= LIVE && index < queue.size - 1){
                    index++;
                    view = queue.get(index);
                    return true;
                }
                return false;
            }

            @Override
            public boolean prev(){
                updateIndex();
                if(index == LIVE) return false;

                if(index >= 0){
                    index--;
                    view = index == LIVE ? live : queue.get(index);
                }else if(index == OBSOLETE){
                    view = queue.get(index = queue.size - 1);
                }
                return true;
            }

            @Override
            public boolean first(){
                if(index == LIVE) return false;

                index = LIVE;
                view = live;
                return true;
            }

            @Override
            public boolean last(){
                if(index == OBSOLETE || index >= queue.size - 1) return false;
                view = queue.get(index = queue.size - 1);
                return true;
            }

            @Override
            public boolean hasNext(){
                return index >= LIVE && index < queue.size - 1;
            }

            @Override
            public boolean hasPrev(){
                return index != LIVE;
            }

            @Override
            public SnapshotList select(VariableValues var){
                if(var instanceof Snapshot snapshot){
                    for(int i = 0; i < queue.size; i++){
                        if(queue.get(i) == snapshot){
                            index = i;
                            view = snapshot;
                            return this;
                        }
                    }
                    index = OBSOLETE;
                    view = snapshot;
                }else{
                    // Live view
                    first();
                }
                return this;
            }

            @Override
            public boolean remove(){
                if(updateIndex() < 0) return false;

                // 删除同时扣回 recording 子快照的额度（上游 v0.11.2）
                Snapshots.delete(queue.removeIndex(index));
                if(index > queue.size) index--;
                return true;
            }

            @Override
            public boolean canRemove(){
                return (updateIndex() >= 0);
            }

            @Override
            public Seq<Snapshot> list(){
                Seq<Snapshot> seq = new Seq<>();
                queue.each(seq::add);
                return seq;
            }

            /** 把 index 重新对齐到当前 view（快照可能已被新快照挤出队列）。 */
            private int updateIndex(){
                // Just to be sure
                if(!(view instanceof Snapshot)) return index = LIVE;

                if(index > LIVE){
                    for(int i = index; i < queue.size; i++){
                        if(queue.get(i) == view){
                            return index = i;
                        }
                    }

                    // Obsolete
                    return index = -2;
                }

                // Live and obsolete indexes can't change
                return index;
            }
        };
    }

    /** 一批同组快照（{@code snapshot} 指令一次创建的所有块，或一份 recording 主快照的
     *  逐指令子快照）。 */
    static SnapshotList list(Seq<Snapshot> snapshots){
        return new SnapshotList(){
            final boolean recording = snapshots.size > 0 && snapshots.first().type() == SnapshotType.recording;
            int index = 0;

            @Override
            public boolean group(){
                return true;
            }

            @Override
            public boolean recording(){
                return recording;
            }

            @Override
            public String title(){
                return L10n.text("logicsugar.vars.snapshot.title", "Snapshot #{0}: {1}",
                        snapshots.get(0).id(), snapshots.first().name());
            }

            @Override
            public VariableValues liveData(){
                return null;
            }

            @Override
            public String pos(){
                // recording 的第一份就是主快照本身（初始状态），所以序号从 0 起
                return recording
                        ? index + "/" + (snapshots.size - 1)
                        : L10n.text("logicsugar.vars.pos.index", "{0}/{1}", index + 1, snapshots.size);
            }

            @Override
            public Snapshot view(){
                return snapshots.get(index);
            }

            @Override
            public boolean next(){
                if(index >= snapshots.size - 1) return false;
                index++;
                return true;
            }

            @Override
            public boolean prev(){
                if(index == 0) return false;
                index--;
                return true;
            }

            @Override
            public boolean first(){
                if(index == 0) return false;
                index = 0;
                return true;
            }

            @Override
            public boolean last(){
                if(index >= snapshots.size - 1) return false;
                index = snapshots.size - 1;
                return true;
            }

            @Override
            public boolean hasNext(){
                return index < snapshots.size - 1;
            }

            @Override
            public boolean hasPrev(){
                return index > 0;
            }

            @Override
            public SnapshotList select(VariableValues var){
                index = 0;
                for(int i = 0; i < snapshots.size; i++){
                    if(snapshots.get(i) == var){
                        index = i;
                        break;
                    }
                }
                return this;
            }

            @Override
            public boolean remove(){
                return false;
            }

            @Override
            public boolean canRemove(){
                return false;
            }

            @Override
            public Seq<Snapshot> list(){
                return snapshots;
            }
        };
    }
}
