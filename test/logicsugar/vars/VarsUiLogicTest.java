package logicsugar.vars;

import arc.func.Cons;
import arc.struct.Seq;
import logicsugar.assist.L10n;
import logicsugar.vars.ui.EllipsisLabel;
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
        recordingListPositionText();
        restoreAndDeleteReachableWithoutCompact();
        uiCodeAvoidsOldArcIncompatibilities();
        escapeOnlyDoublesOpeningBrackets();
        ellipsisLabelBoundsHugeStrings();

        System.out.println("LogicSugar vars UI logic self-test passed.");
    }

    /** recording 主快照的子列表：{@code pos()} 用「子序号/总数」且标题/类型不参与导航。 */
    private static void recordingListPositionText(){
        Seq<Snapshot> subs = new Seq<>();
        subs.add(new FakeSnapshot(5, "0: set x 1", SnapshotType.recording));
        subs.add(new FakeSnapshot(5, "1: op add y x 1", SnapshotType.recording));
        subs.add(new FakeSnapshot(5, "2: jump 0 always", SnapshotType.recording));

        SnapshotList list = SnapshotList.list(subs);
        check(list.recording(), "首份是 recording 时列表标记为 recording（标题不带 id/类型）");
        checkEquals("0/2", list.pos(), "recording 序号从 0 起（第一份就是主快照自身的初始状态）");
        checkEquals("Snapshot #5: 0: set x 1", list.title(), "标题仍取第一份的 id/名字（上游行为）");
        check(list.next(), "next() 进入第一条指令的子快照");
        checkEquals("1/2", list.pos(), "next() 后的序号");
        check(list.last(), "last() 跳到最后一格");
        checkEquals("2/2", list.pos(), "最后一格的序号");

        // 普通组列表的位置文本仍是 1 起算的 index+1/size
        Seq<Snapshot> group = new Seq<>();
        group.add(new FakeSnapshot(7, "first"));
        group.add(new FakeSnapshot(7, "second"));
        SnapshotList plain = SnapshotList.list(group);
        check(!plain.recording(), "普通组列表不是 recording");
        checkEquals("1/2", plain.pos(), "普通组列表沿用 1 起算的位置文本");
    }

    /** 恢复/删除必须在不进入紧凑布局时也能到达（v0.11.2 把两者从标题栏移进 Edit 菜单），
     *  且 profiler 的两个入口点（标题栏 + Edit 菜单）都要在——快照关闭时标题栏不存在。 */
    private static void restoreAndDeleteReachableWithoutCompact(){
        String source = readSource("src/logicsugar/vars/ui/VarsDialog.java");
        String edit = logicsugar.SourceNails.methodBody(source, "private void editCommands(){");
        String title = logicsugar.SourceNails.methodBody(source, "private void rebuildTitle(Table titleTable){");

        check(edit.contains("this::restoreSnapshot"), "Edit 菜单里必须有恢复快照入口");
        check(edit.contains("this::removeSnapshot"), "Edit 菜单里必须有删除快照入口");
        check(!edit.contains("if(compact)"), "恢复/删除不能再被紧凑布局门控（v0.11.2）");
        check(edit.contains("Snapshots.deleteEntity"), "删除全部快照使用 deleteEntity(Senseable)");
        check(edit.contains("FileChooser.save") && edit.contains("FileChooser.open"),
            "内存值的文件导出/导入入口");
        check(edit.contains("importData("), "剪贴板与文件共用同一个 importData");

        // 标题栏不再放恢复/删除（它们只在 Edit 菜单里）
        check(!title.contains("this::restoreSnapshot") && !title.contains("this::removeSnapshot"),
            "标题栏不应再有恢复/删除按钮");

        // profiler 入口：标题栏按钮（非处理器禁用）+ Edit 菜单里的项
        check(title.contains("ProfileDialog"), "标题栏必须有 profiler 入口");
        check(title.contains("setDisabled"), "标题栏的 profiler 入口必须对非处理器视图禁用");
        check(edit.contains("ProfileDialog"), "Edit 菜单必须有 profiler 入口（快照关闭时标题栏不存在）");

        // 设置行的注册：LogicSugarSettings.addVarsPrefs 是自有设置页与 Neon 聚合页共用的唯一注册点
        String settings = readSource("src/logicsugar/LogicSugarSettings.java");
        check(logicsugar.SourceNails.methodBody(settings, "static void addVarsPrefs(").contains("settingStartProfilerImmediately"),
            "profiler 设置行必须由 addVarsPrefs 注册（自有设置页 + Neon 聚合页共用）");
        check(logicsugar.SourceNails.methodBody(settings, "private static void build(").contains("addVarsPrefs"),
            "自有设置页必须调用 addVarsPrefs");
        String mod = readSource("src/logicsugar/LogicSugarMod.java");
        check(logicsugar.SourceNails.methodBody(mod, "public void bekBuildSettings(").contains("addVarsPrefs"),
            "Neon 聚合页必须调用 addVarsPrefs（双形态要求）");
    }

    /** 读仓库源文件（SourceNails 的容错包装：读不到直接断言失败，不吞异常）。 */
    private static String readSource(String file){
        try{
            return logicsugar.SourceNails.readSource(file);
        }catch(java.io.IOException e){
            throw new AssertionError("cannot read " + file + ": " + e, e);
        }
    }

    /**
     * Neon 聚合构建把工作区里的旧版 arc 放在编译类路径最前面，旧版 {@code Cell} 没有
     * {@code wrap(boolean)}（只有无参 {@code wrap()}），新增界面代码用了它就会在 Neon 构建里
     * 编译失败（本次 profiler 的 ProfileDialog 就这样撞过一次）。对 Label 用
     * {@code Label.setWrap(...)}，对卡片用无参 {@code wrap()}。
     */
    private static void uiCodeAvoidsOldArcIncompatibilities(){
        for(String file : new String[]{
            "src/logicsugar/vars/ui/VarsDialog.java",
            "src/logicsugar/vars/ui/SnapshotsDialog.java",
            "src/logicsugar/profile/ui/ProfileDialog.java"
        }){
            String source = withoutComments(readSource(file));
            check(!source.contains(".wrap(true)") && !source.contains(".wrap(false)"),
                file + " 使用了旧版 arc 没有的 Cell.wrap(boolean)（Neon 聚合构建会编译失败）");
        }
    }

    /** 一段源码去掉注释（整行与行尾），只留可执行文本：源码钉子不叮注释里的反例写法。 */
    private static String withoutComments(String source){
        StringBuilder out = new StringBuilder();
        for(String line : source.split("\n", -1)){
            String bare = line.trim();
            if(bare.startsWith("//") || bare.startsWith("*") || bare.startsWith("/*")) continue;
            int comment = line.indexOf("//");
            if(comment >= 0) line = line.substring(0, comment);
            out.append(line).append('\n');
        }
        return out.toString();
    }

    // ===== 显示时的转义与截断（无头可测的部分） =====

    /** 字符串值里的 {@code [} 在 Arc 的富文本里是标记起点，必须写成 {@code [[}；
     *  {@code ]} 是字面量，不能写成 {@code ]]}（那样会多渲染一个括号）。 */
    private static void escapeOnlyDoublesOpeningBrackets(){
        checkEquals("value", VarsDialog.escape("value"), "普通文本不变");
        checkEquals("list[[1]", VarsDialog.escape("list[1]"), "'[' 写成 '[['（与 ExprStatement.highlight 同一规则）");
        checkEquals("[[[[", VarsDialog.escape("[["), "连续 '[' 全部加倍");
        checkEquals("a]b", VarsDialog.escape("a]b"), "']' 是字面量，不得转义");
        checkEquals("", VarsDialog.escape(""), "空串不变");
        check(VarsDialog.escape("x[").equals("x[["), "含 '[' 时走 replace 分支");
        String plain = "no brackets here";
        check(VarsDialog.escape(plain) == plain, "不含 '[' 时原样返回同一个对象（不浪费分配）");
    }

    /** v0.11.3 的修复：MB 级字符串只在 256 字符以内测量，二分次数与长度无关。 */
    private static void ellipsisLabelBoundsHugeStrings(){
        String huge = "x".repeat(1_000_000);
        checkEquals(huge.substring(0, EllipsisLabel.maxStringLength), EllipsisLabel.truncate(huge),
            "超长原文只保留 maxStringLength 个字符");
        // 截断是 Marker 感知的（subSequence 而不是拆分代理对）
        checkEquals("abc", EllipsisLabel.truncate("abc"), "短文本原样保留");
        checkEquals("", EllipsisLabel.truncate(null), "null 视为空串");

        // 二分：只允许 O(log n) 次测量，且 1MB 与 256 字符的代价相同
        int[] calls = {0};
        int fit = EllipsisLabel.fittingPrefix(huge.length(), length -> {
            calls[0]++;
            return length <= 123;
        });
        check(fit == 123, "二分必须找到最后一个满足条件的长度: " + fit);
        check(calls[0] < 24, "1MB 字符串的二分测量次数应小于 24，实际 " + calls[0]);

        calls[0] = 0;
        int none = EllipsisLabel.fittingPrefix(1_000_000, length -> {
            calls[0]++;
            return false;
        });
        check(none == 0, "一个都放不下时返回 0: " + none);
        check(calls[0] < 24, "放不下时的二分次数同样有限，实际 " + calls[0]);

        // 上界就是传入的长度（原来的 min(200, ...) 会让长文本永远截不到 200 以上）
        int all = EllipsisLabel.fittingPrefix(300, length -> true);
        check(all == 300, "放得下时返回完整长度（上界不再是 200）: " + all);
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

        checkPublicStaticMethod(SnapshotList.class, "forBuild", mindustry.logic.Senseable.class);
        checkPublicStaticMethod(SnapshotList.class, "list", Seq.class);

        for(String name : new String[]{"group", "recording", "title", "liveData", "pos", "view", "next", "prev", "first", "last", "hasNext", "hasPrev", "canRemove", "remove", "list"}){
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
        private final SnapshotType type;

        FakeSnapshot(int id, String name){
            this(id, name, null);
        }

        FakeSnapshot(int id, String name, SnapshotType type){
            super(null);
            this.id = id;
            this.name = name;
            this.type = type;
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
            return null;
        }

        @Override
        public Seq<Snapshot> recording(){
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

        @Override
        public void setDefaultFilter(mindustry.logic.LVar[] vars){
        }
    }
}
