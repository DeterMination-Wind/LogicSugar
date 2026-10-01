package logicsugar.vars;

import arc.func.Cons;
import arc.struct.Seq;
import logicsugar.assist.L10n;
import logicsugar.vars.ui.SnapshotList;
import logicsugar.vars.ui.SnapshotsDialog;
import logicsugar.vars.ui.VarsDialog;
import mindustry.Vars;
import mindustry.core.GameState;
import mindustry.gen.Building;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/**
 * 变量对话框 UI 层的无界面自检：{@code logicsugar.vars.ui} 里唯一不依赖场景图的部分。
 *
 * <p>为什么只测这一部分：{@link VarsDialog} / {@link SnapshotsDialog} / {@code EllipsisLabel}
 * 的构造都要活的 {@code Core.scene}（{@code Label(CharSequence)} 会读 {@code Core.scene.getStyle}）
 * 或活的建筑，无法在无头环境里构造，硬测只会得到一串假通过。可以精确验证的是
 * {@link SnapshotList#list(Seq)} 的两段式导航（上一份/下一份/首尾/越界不移动/选中），
 * 也就是「翻快照」这个功能的全部逻辑；{@code forBuild(...)} 需要活建筑，这里只钉住它的
 * 公开签名，保证集成代码在别的包里仍然能编译。</p>
 *
 * <p>另外钉住两件容易被顺手改坏的事：pos()/title() 用的 bundle 键与英文 fallback，
 * 以及 {@code VarsDialog.globalsOpener} 这个集成钩子的类型与默认值。</p>
 */
public final class VarsUiLogicTest{
    private VarsUiLogicTest(){
    }

    public static void main(String[] args){
        // BaseVariableValues 的 timestamp 读 Vars.state.tick；自检里没有启动器，手工放一个
        // 纯数据 GameState（与 VarsDataTest 相同的做法）。
        Vars.state = new GameState();

        groupListNavigation();
        groupListTitleAndPosition();
        singleSnapshotList();
        integrationApiShape();

        System.out.println("LogicSugar vars UI logic self-test passed.");
    }

    // ===== 一组快照的导航 =====

    private static void groupListNavigation(){
        Seq<Snapshot> snapshots = new Seq<>();
        FakeSnapshot first = new FakeSnapshot(7, "first");
        FakeSnapshot second = new FakeSnapshot(8, "second");
        FakeSnapshot third = new FakeSnapshot(9, "third");
        snapshots.add(first);
        snapshots.add(second);
        snapshots.add(third);

        SnapshotList list = SnapshotList.list(snapshots);

        check(list.group(), "一组快照的 group() 为 true");
        check(list.liveData() == null, "组快照没有活数据（还原时 writeTo(null) 必须自己返回 false）");
        check(list.list() == snapshots, "组列表直接交出同一个 Seq（上游如此，不复制）");
        check(list.view() == first, "初始视图是第一份快照");
        check(!list.canRemove(), "组快照不能删除");
        check(!list.remove(), "组快照的 remove() 恒为 false（删除只对某块建筑的队列有效）");

        check(!list.hasPrev(), "第一份之前没有上一份");
        check(list.hasNext(), "还有下一份");
        check(!list.first(), "已经在第一份，first() 不移动并返回 false");
        check(!list.prev(), "在第一份时 prev() 不移动并返回 false");

        check(list.next(), "next() 移动到第二份");
        check(list.view() == second, "next() 之后的视图");
        checkEquals(2 + "/" + 3, list.pos(), "next() 之后的位置文本");
        check(list.hasPrev() && list.hasNext(), "中间位置两侧都有");

        check(list.last(), "last() 跳到第三份");
        check(list.view() == third, "last() 之后的视图");
        check(!list.hasNext(), "最后一份之后没有下一份");
        check(!list.next(), "在最后一份时 next() 不移动并返回 false");
        check(list.view() == third, "被拒绝的 next() 不能改变视图");
        check(!list.last(), "已经在最后一份，last() 不移动并返回 false");

        check(list.prev(), "prev() 回到第二份");
        check(list.view() == second, "prev() 之后的视图");
        check(list.first(), "first() 回到第一份");
        check(list.view() == first, "first() 之后的视图");
        check(!list.hasPrev(), "回到第一份后没有上一份");

        // 选中：命中同一份快照时索引跟着走，未命中时按上游规则回到第一份
        check(list.select(third) == list, "select() 返回自身以便链式调用");
        check(list.view() == third, "select() 命中第三份");
        checkEquals(3 + "/" + 3, list.pos(), "select() 之后的位置文本");
        list.select(new FakeSnapshot(99, "unknown"));
        check(list.view() == first, "select() 未命中时回到第一份（上游的 for 循环失败即 index 留在 0）");
        checkEquals(1 + "/" + 3, list.pos(), "未命中的 select() 之后位置回到 1");
    }

    // ===== 标题与位置 =====

    private static void groupListTitleAndPosition(){
        Seq<Snapshot> snapshots = new Seq<>();
        snapshots.add(new FakeSnapshot(7, "first"));
        SnapshotList list = SnapshotList.list(snapshots);

        // 键与英文 fallback 一起钉住：集成阶段补 bundle 键之前，英文原文必须原样显示。
        checkEquals("Snapshot #7: first", list.title(), "组标题用第一份快照的 id 与名字");
        checkEquals("1/1", list.pos(), "单份组列表的位置文本");

        // 走的是同一个键、同一组参数：占位符必须被替换且顺序是 (序号, 总数)。
        String expected = L10n.text("logicsugar.vars.snapshot.title", "Snapshot #{0}: {1}", 7, "first");
        checkEquals(expected, list.title(), "标题文案来自 logicsugar.vars.snapshot.title");
        check(!list.title().contains("{0}"), "标题里不能残留占位符");
        check(!list.pos().contains("{0}"), "位置里不能残留占位符");
    }

    // ===== 只有一份快照的组 =====

    private static void singleSnapshotList(){
        Seq<Snapshot> snapshots = new Seq<>();
        snapshots.add(new FakeSnapshot(1, "only"));
        SnapshotList list = SnapshotList.list(snapshots);

        check(!list.hasPrev() && !list.hasNext(), "只有一份时两侧都没有");
        check(!list.next() && !list.prev(), "只有一份时 next()/prev() 都返回 false");
        check(!list.first() && !list.last(), "只有一份时 first()/last() 都是 false（已经在唯一位置）");
        check(list.view() == snapshots.first(), "视图仍是那一份");
    }

    // ===== 集成接口的形状 =====

    private static void integrationApiShape(){
        // 集成代码在别的包里构造这些对话框、把 SnapshotList 传进去，所以两者都必须是 public。
        check(Modifier.isPublic(SnapshotList.class.getModifiers()) && SnapshotList.class.isInterface(),
                "SnapshotList 必须是 public 接口（否则包外的 VarsDialog.setup(SnapshotList) 无法调用）");

        checkPublicConstructor(VarsDialog.class, Building.class);
        checkPublicMethod(VarsDialog.class, "setup", SnapshotList.class);
        checkPublicMethod(VarsDialog.class, "setup", boolean.class);
        checkPublicConstructor(SnapshotsDialog.class, VarsDialog.class, SnapshotList.class);

        checkPublicStaticMethod(SnapshotList.class, "forBuild", Building.class);
        checkPublicStaticMethod(SnapshotList.class, "list", Seq.class);

        for(String name : new String[]{"group", "title", "liveData", "pos", "view", "next", "prev", "first", "last", "hasNext", "hasPrev", "canRemove", "remove", "list"}){
            checkPublicMethod(SnapshotList.class, name);
        }
        checkPublicMethod(SnapshotList.class, "select", VariableValues.class);

        // 上游用 LogicDialogAddon.globalsDialog 打开全局变量；这里换成可选的集成钩子。
        try{
            Field opener = VarsDialog.class.getField("globalsOpener");
            check(Modifier.isPublic(opener.getModifiers()) && Modifier.isStatic(opener.getModifiers()),
                    "VarsDialog.globalsOpener 必须是 public static");
            check(opener.getType() == Runnable.class, "VarsDialog.globalsOpener 的类型是 Runnable");
            check(opener.get(null) == null, "未注入时 globalsOpener 为 null（此时不显示那个按钮）");
        }catch(ReflectiveOperationException exception){
            throw new AssertionError("VarsDialog.globalsOpener 不存在: " + exception);
        }
    }

    // ===== 断言工具 =====

    private static void checkPublicConstructor(Class<?> type, Class<?>... parameterTypes){
        try{
            Constructor<?> constructor = type.getConstructor(parameterTypes);
            check(Modifier.isPublic(constructor.getModifiers()), type.getSimpleName() + " 的构造器必须是 public");
        }catch(ReflectiveOperationException exception){
            throw new AssertionError(type.getSimpleName() + " 缺少公开构造器 " + Arrays.toString(parameterTypes) + ": " + exception);
        }
    }

    private static void checkPublicMethod(Class<?> type, String name, Class<?>... parameterTypes){
        try{
            Method method = type.getMethod(name, parameterTypes);
            check(Modifier.isPublic(method.getModifiers()), type.getSimpleName() + "." + name + " 必须是 public");
        }catch(ReflectiveOperationException exception){
            throw new AssertionError(type.getSimpleName() + " 缺少公开方法 " + name + ": " + exception);
        }
    }

    private static void checkPublicStaticMethod(Class<?> type, String name, Class<?>... parameterTypes){
        try{
            Method method = type.getMethod(name, parameterTypes);
            check(Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers()),
                    type.getSimpleName() + "." + name + " 必须是 public static");
        }catch(ReflectiveOperationException exception){
            throw new AssertionError(type.getSimpleName() + " 缺少公开静态方法 " + name + ": " + exception);
        }
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }

    private static void checkEquals(String expected, String actual, String message){
        if(expected == null ? actual != null : !expected.equals(actual)){
            throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    // ===== 快照替身 =====

    /**
     * 只实现 {@link SnapshotList} 真正会读的接口方法：数据源本体（逐行变量表）不参与导航，
     * 所以这里是空表；{@link Snapshot#type()} 返回 null（导航不读类型，也避免在无头环境里
     * 触发 SnapshotType 的图标初始化）。
     */
    private static final class FakeSnapshot extends BaseVariableValues implements Snapshot{
        private final int id;
        private final String name;

        FakeSnapshot(int id, String name){
            super(null);
            this.id = id;
            this.name = name;
        }

        @Override
        public BlockDataType dataType(){
            return BlockDataType.processor;
        }

        @Override
        public int size(){
            return 0;
        }

        @Override
        public String label(int index, boolean hex){
            return "";
        }

        @Override
        public boolean isObj(int index){
            return false;
        }

        @Override
        public boolean isLink(int index){
            return false;
        }

        @Override
        public Object obj(int index){
            return null;
        }

        @Override
        public double num(int index){
            return 0;
        }

        @Override
        public String textBuffer(){
            return "";
        }

        @Override
        public void clear(){
        }

        @Override
        public void setView(boolean sorted, boolean filtered, boolean hideLinks){
        }

        @Override
        public void eachObject(Cons<Object> getter){
        }

        @Override
        public SnapshotType type(){
            return null;
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
            return null;
        }

        @Override
        public float[] typeDistribution(){
            return new float[ValueType.values().length];
        }

        @Override
        public boolean writeTo(VariableValues liveData){
            return false;
        }
    }
}
