package mindustry.logic;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.math.geom.Intersector;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.event.Touchable;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Scl;
import arc.struct.IntMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import arc.util.Tmp;
import logicsugar.assist.CounterJumpIndex;
import logicsugar.assist.JumpLanes;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Fonts;
import mindustry.ui.Styles;

import java.util.HashSet;
import java.util.Set;

/**
 * {@code @counter} 写入的左侧指示线（类 Jump，方向与原版右侧跳转按钮相反）。
 *
 * <p>原版 jump 卡片的跳转按钮在卡片最右侧，{@link LCanvas.JumpCurve} 的曲线从目标积木折回源
 * 积木，所以原版跳转线整体在<b>右</b>侧。{@code @counter = N} / {@code @counter += N} /
 * {@code @counter -= N}（set / op / Expr 积木）在运行时同样是跳转，本类在<b>左</b>侧画出镜像
 * 的指示线，位置在结构引导线（{@link SugarCanvas.StructureGuideLayer}）更外侧，两条轨道分离。</p>
 *
 * <h2>数据来源</h2>
 * <p>{@code @counter} 的值是<b>最终产物</b>的指令下标，与画布积木不是一一对应的（声明卡产出 0
 * 条指令、for 的 step/回跳落在 end 卡上、normal 模式函数体整体后置），因此目标积木由
 * {@link CounterJumpIndex} 解析绝对目标、再经编译期来源通道
 * （{@link SugarCompiler.CompileProvenance}）映射回语句下标。目标无法静态确定时（switch 跳转表、
 * 变量赋值、函数体内部）只画角标，悬停时按候选列虚影线 —— 绝不猜。</p>
 *
 * <h2>性能</h2>
 * <p>按画布文本签名缓存编译结果，只在文本真正变化时重算；每帧只做坐标记录与绘制。编译发生在
 * 编辑器进程内（处理器上限 1000 条指令），与编辑器自带的产物大小横幅同一量级。</p>
 */
public final class CounterJumpOverlay{
    /** 设置项：悬停时是否把不确定的候选目标画成虚影线。默认开；关掉后只剩角标。 */
    public static final String settingCandidateLines = "logicsugar.counterJump.candidates";

    /** 目标唯一时的线色（绿）。 */
    private static final Color targetColor = Color.valueOf("7ee787");
    /** 目标不确定（多候选）时的线色（琥珀）。 */
    private static final Color uncertainColor = Color.valueOf("ffd479");
    /** 目标不可解析（switch 跳转表、函数体内部等）：只画角标，用灰色。 */
    private static final Color unknownColor = Color.valueOf("9aa5b1");

    /** 线宽与原版 {@code JumpCurve.drawCurve} 一致（{@code Scl.scl(4f)}）。 */
    private static final float lineWidth = 4f;
    /** 目标端箭头的最大边长。原版用跳转按钮的宽度（约 30），本功能没有按钮，
     *  取一个与之接近但更收敛的固定值。 */
    private static final float arrowSize = 20f;
    /** 轨道横向距离：镜像原版 {@code JumpCurve.drawCurve} 的两个倍率（第 {@code lane} 层 =
     *  基准 + 步长 × lane），竖屏用原版那一对更小的值。 */
    private static final float railBase = 40f, railStep = 10f, railBasePortrait = 20f, railStepPortrait = 8f;
    private static final float badgeHeight = 13f;
    private static final float badgePad = 3f;

    private final SugarCanvas canvas;
    private final CounterLayer layer;

    /** 画布语句下标 -> 该语句的 @counter 写入。 */
    private final IntMap<CounterJumpIndex.Write> writes = new IntMap<>();
    /** 产物指令下标 -> 该指令的 @counter 写入；与 {@link #writes} 是同一批对象。
     *  未归属任何卡片的写入（函数体内部、编译器自己发射的入口 skip）只在这张表里，
     *  以画布底部的一列灰色小标出现，而不是消失。 */
    private final IntMap<CounterJumpIndex.Write> unattributed = new IntMap<>();
    /** 语句下标 -> 该语句发射的第一条指令（悬停提示用）。 */
    private final IntMap<Integer> firstInstruction = new IntMap<>();
    /** 指令下标 -> 语句下标；由 {@link SugarCompiler.CompileProvenance} 填满。
     *  {@code write.targets} 是指令下标，必须经它换算才能落到积木上。 */
    private int[] instructionOwner = new int[0];
    /** 已解析好的指示线（目标已换算成语句下标）。只在 {@link #rebuild} 里重建。 */
    private final Seq<CounterCurve> curves = new Seq<>();
    /** 上次编译时的画布文本签名；变了才重新编译。 */
    private String signature;
    /** 悬停提示的文本，每帧重算。 */
    private String hoverText;

    private Label hint;

    private CounterJumpOverlay(SugarCanvas canvas, Group jumpLayer){
        this.canvas = canvas;
        this.layer = new CounterLayer();
        layer.touchable = Touchable.disabled;
        layer.fillParent = true;
        layer.cullable = false;
        // 与原版一样挂在 jumps 层：它随 pane 一起滚动，坐标系与 JumpCurve 完全一致。
        // 插到最前：结构引导线与原生跳转曲线都画在它上面。
        jumpLayer.addChildAt(0, layer);
        // 复用 Element 的每帧回调（Element.act() 里执行），与 JumpLineColor 对 JumpCurve 用
        // update() 是同一套时序。
        layer.update(() -> refresh());
    }

    /** 幂等安装：同一层已有就不再插第二个。 */
    public static void install(SugarCanvas canvas){
        if(canvas == null || canvas.statements == null) return;
        Group jumps = SugarCanvas.getJumpLayer(canvas);
        if(jumps == null) return;
        for(Element child : jumps.getChildren()){
            if(child instanceof CounterLayer) return;
        }
        new CounterJumpOverlay(canvas, jumps);
    }

    /** 画布内容变了（编辑语句、粘贴、折叠、切换模式）：丢掉缓存，下一帧重算。 */
    public static void invalidate(SugarCanvas canvas){
        if(canvas == null || canvas.statements == null) return;
        Group jumps = SugarCanvas.getJumpLayer(canvas);
        if(jumps == null) return;
        for(Element child : jumps.getChildren()){
            if(child instanceof CounterLayer counter) counter.owner.signature = null;
        }
    }

    // ===== 刷新 ==========================================================================

    private void refresh(){
        if(Core.scene == null || canvas == null || canvas.statements == null) return;
        // 编辑器已关闭（画布脱离舞台）时立刻收掉提示并停止刷新：update() 回调可能比
        // 对话框移除晚一帧，而提示挂在 scene root 上 —— 不判断就会留在屏幕上（2026-09 残留报告）。
        if(canvas.getScene() != Core.scene){
            hideHint();
            return;
        }
        hoverText = null;
        // 快照而不是 save()：save() 会在取文本前后 unfold/fold（会 remove/addAt 积木元素，文本还是
        // 展开态），而本类的 elementAt() 索引的是屏幕上的折叠态；原版 saveUI() 还会因为 jump 目标
        // 已脱离而抛 NPE。这条回调每帧跑，一次抛出就会静默死掉 —— 指示线此后整场不画。
        String current;
        try{
            current = canvas.readonlyText();
        }catch(Throwable exception){
            noteOnce("snapshot", "cannot read the canvas text", exception);
            return;
        }
        if(!current.equals(signature)){
            signature = current;
            rebuild(current);
        }
        try{
            layer.actCurves();
            updateHint();
        }catch(Throwable exception){
            // 绘制路径同样不能把异常丢回渲染循环：丢掉一帧只是闪一下，回调死掉是整场不画。
            noteOnce("draw", "drawing failed", exception);
        }
    }

    /** 已报过的失败（按 key 去重）：见 {@link #noteOnce}。 */
    private static final Set<String> reported = new HashSet<>();

    /**
     * 失败只报一次，且绝不向外抛。
     *
     * <p>这套功能坏掉时屏幕上是"什么都不画"，没有异常、没有痕迹，只能靠报告猜（2026-09-25 的
     * "长逻辑里罢工"就是这样查了两轮）。失败要么在日志里留下确切原因，要么它根本不该静默。</p>
     */
    private static void noteOnce(String key, String what, Throwable exception){
        String detail = what + ": " + (exception.getMessage() == null
            ? exception.getClass().getSimpleName() : exception.getMessage());
        if(!reported.add(key + "|" + detail)) return;
        Log.warn("[LogicSugar] @counter indicator line disabled: @", detail);
    }

    /** 供对话框关闭时主动调用（不能只靠每帧回调发现画布已脱离舞台）。 */
    public static void hideAll(SugarCanvas canvas){
        if(canvas == null || canvas.statements == null) return;
        Group jumps = SugarCanvas.getJumpLayer(canvas);
        if(jumps == null) return;
        for(Element child : jumps.getChildren()){
            if(child instanceof CounterLayer counter) counter.owner.hideHint();
        }
    }

    /**
     * 重算一次：编译画布 → 解析 {@code @counter} 写入 → 映射回语句下标。任何失败都退化成
     * "没有指示线"，绝不把异常抛进渲染循环（编译失败在编辑器里已有红标与横幅）。
     */
    private void rebuild(String source){
        writes.clear();
        unattributed.clear();
        firstInstruction.clear();
        instructionOwner = new int[0];
        curves.clear();
        try{
            SugarCompiler.CompileProvenance provenance = SugarCompiler.compileRecorded(source,
                SugarCompiler.currentMode(), SugarFunctions.library(), null,
                SugarCompiler.currentStrategy(), SugarCompiler.currentAssertEmit(), canvas.privilegedSession());
            if(provenance == null) return;

            int statements = canvas.statements.getChildren().size;
            instructionOwner = new int[provenance.instructions()];
            // 逆序填充：同一语句被连续多条指令命中时，留下的是它的第一条指令。
            for(int instruction = provenance.instructions() - 1; instruction >= 0; instruction--){
                int statement = provenance.originOf(instruction);
                instructionOwner[instruction] = statement;
                if(statement < 0 || statement >= statements) continue;
                firstInstruction.put(statement, instruction);
            }

            CounterJumpIndex index = new CounterJumpIndex(provenance.code, provenance.origins);
            for(CounterJumpIndex.Write write : index.writes()){
                // 归属一律走编译期来源通道，不用 CounterJumpIndex.Write.statement：解析器拿到
                // provenance 时会把负值槽位（SugarFunctions.syntheticOrigin）当作"未知"再用标签
                // 启发式补，而这里已经知道确切答案。
                int statement = provenance.originOf(write.instruction);
                if(statement < 0 || statement >= statements){
                    // 没有卡片可指（函数体内部、编译器自己发射的指令）：不画线，但不隐藏 ——
                    // 底部灰色小标 + 悬停说明，用户至少知道"这里有一个改 @counter 的指令"。
                    unattributed.put(write.instruction, write);
                    continue;
                }
                // 一张卡上有多条写入时保留第一条：角标只回答"这张卡会改 @counter"，
                // 细节在悬停提示里。
                if(writes.containsKey(statement)) continue;
                writes.put(statement, write);
            }

            // 目标解析在这里做一次（不在绘制里做）：write.targets 是**产物指令下标**，
            // 必须经来源通道换成语句下标才能落到 elementAt() 上 —— 两者是不同的索引空间，
            // 混用会让线指到完全无关的积木（2026-09 的错位报告就是这么来的）。
            for(IntMap.Entry<CounterJumpIndex.Write> entry : writes){
                CounterJumpIndex.Write write = entry.value;
                Target resolved = resolve(write);
                if(resolved == null) continue;
                Integer first = firstInstruction.get(entry.key);
                curves.add(new CounterCurve(entry.key, resolved, first == null ? -1 : first, write));
            }
        }catch(Throwable t){
            writes.clear();
            unattributed.clear();
            firstInstruction.clear();
            instructionOwner = new int[0];
            curves.clear();
        }
    }

    /**
     * 一条写入指向的目标集合，已换算到语句下标空间。
     *
     * @param statement 唯一目标所在的语句下标，-1 表示目标不唯一或不可解析
     * @param exact     {@code targets.length == 1}：目标确定，画实线
     * @param candidates 多候选时的全部语句下标（去重后仍可能为空 —— 目标落在函数体等无归属区域）
     */
    private Target resolve(CounterJumpIndex.Write write){
        if(write.targets.length == 1){
            int statement = ownerOf(write.targets[0]);
            return statement < 0 ? null : new Target(statement, true, EMPTY_TARGETS);
        }
        if(write.targets.length == 0) return null;
        IntMap<Boolean> seen = new IntMap<>();
        Seq<Integer> list = new Seq<>();
        for(int target : write.targets){
            int statement = ownerOf(target);
            if(statement < 0 || seen.containsKey(statement)) continue;
            seen.put(statement, Boolean.TRUE);
            list.add(statement);
        }
        if(list.isEmpty()) return null;
        int[] candidates = new int[list.size];
        for(int i = 0; i < candidates.length; i++) candidates[i] = list.get(i);
        return new Target(-1, false, candidates);
    }

    /** 写入指向的语句下标集合。 */
    private static final class Target{
        final int statement;
        final boolean exact;
        final int[] candidates;

        Target(int statement, boolean exact, int[] candidates){
            this.statement = statement;
            this.exact = exact;
            this.candidates = candidates;
        }
    }

    private static final int[] EMPTY_TARGETS = new int[0];

    // ===== 坐标 ==========================================================================

    /**
     * 锚点 X：卡片自己的<b>正左侧</b>（相对元素的局部坐标，0 = 左边缘）。
     *
     * <p>与结构引导线（{@code SugarCanvas.StructureGuideLayer}）同一套做法：把"元素局部坐标里的
     * 一个点"交给 {@code localToAscendantCoordinates} 换算，整体缩进量（{@code elem.x}）与
     * scroll 偏移都由那一步带出来，因此每张卡在自己的缩进层级上得到同样的相对位置。</p>
     *
     * <p>原版 jump 线的两端就是积木的左右边缘中点（{@link LCanvas.JumpCurve} 取
     * {@code button.elem} 与目标元素中心，按钮贴在最右侧，所以线贴在积木右缘）。本功能镜像到
     * 左缘：0 而不是负偏移 —— 早期版本把锚点外移到积木之外（"轨道"），线因此离开积木悬空，
     * 且嵌套卡片会与结构竖线挤在一起（2026-09 报告）。</p>
     *
     * <p><b>两个轴都必须是局部坐标</b>（见 {@link #anchorY}）：换算那一步会把元素在父表里的
     * x/y 自己加上去，锚点里再写一次 {@code elem.x}/{@code elem.y} 就是重复计入，线与角标会
     * 离开卡片飞到别处。X 轴这里是 0（正好等于"不加偏移"），所以这个错误只在 Y 轴上露出来过。</p>
     */
    private static float anchorLocalX(){
        return 0f;
    }

    private static float anchorY(LCanvas.StatementElem elem){
        // 卡片局部坐标的竖直中点：原点在卡片自己左下角，所以只取高度一半。
        //
        // **不能**再加 {@code elem.y}：{@code localToAscendantCoordinates} 自己会把元素在父表里的
        // 偏移（x/y）加进去，写成 {@code elem.y + h/2} 就重复计入一次，锚点被抬高 {@code elem.y}。
        // 语句表是顶对齐的，越靠程序开头的卡片 y 越大 —— 于是同一条线两端抬升量不同：靠后的写卡片
        // 几乎不动，靠前的目标卡片被甩到屏幕上方，看起来就是"线飞出积木"（2026-09-25 报告）。
        // 原版 JumpCurve 也只取 {@code hover.getHeight()/2f}，StructureGuideLayer 取 0 / getHeight()。
        return elem.getHeight() / 2f;
    }

    /** 卡片当前是否可见（折叠块内部、被移除的卡片都不画）。名字刻意避开
     *  {@code Element.visible(Boolp)} 的重载。 */
    private static boolean drawable(LCanvas.StatementElem elem){
        return elem != null && elem.visible && elem.parent != null;
    }

    private LCanvas.StatementElem elementAt(int index){
        Seq<Element> children = canvas.statements == null ? null : canvas.statements.getChildren();
        if(children == null || index < 0 || index >= children.size) return null;
        return children.get(index) instanceof LCanvas.StatementElem elem ? elem : null;
    }

    /** 绝对指令下标 -> 画布语句下标；没有来源（编译器自己发射 / 函数体）时返回 -1。 */
    private int ownerOf(int instruction){
        if(instruction < 0 || instruction >= instructionOwner.length) return -1;
        return instructionOwner[instruction];
    }

    // ===== 曲线 ==========================================================================

    /**
     * 一条写入卡片的指示线。端点每帧重算（积木拖动/滚动都会动），目标集合在
     * {@link #rebuild} 里已经换算成语句下标。
     */
    private final class CounterCurve{
        private final int statement;
        private final Target target;
        /** 源卡片发射的第一条指令，-1 表示未知（悬停提示用）。 */
        private final int sourceInstruction;
        private final CounterJumpIndex.Write write;
        /** 当前显示的目标：唯一目标，或悬停时逐个走的候选。 */
        private int shown;
        private boolean seeded;
        private float sx, sy, tx, ty;
        private float alpha;
        /** 轨道层号（0 = 最贴近积木），每帧由 {@link CounterLayer#assignLanes} 分配。 */
        private int lane;
        /** 当前轨道横向距离；按原版那样指数平滑，换轨时不会闪跳。 */
        private float rail = -1f;
        /** 目标在源卡片下方（原版同名字段，只决定合并时按区间哪一端归类）。 */
        private boolean flipped;

        CounterCurve(int statement, Target target, int sourceInstruction, CounterJumpIndex.Write write){
            this.statement = statement;
            this.target = target;
            this.sourceInstruction = sourceInstruction;
            this.write = write;
            this.shown = target.exact ? target.statement : -1;
        }

        boolean exact(){ return target.exact; }

        boolean hovered(){
            LCanvas.StatementElem source = elementAt(statement);
            if(drawable(source) && safeHasMouse(source)) return true;
            LCanvas.StatementElem targetElem = elementAt(shown);
            return drawable(targetElem) && safeHasMouse(targetElem);
        }

        /** 每帧取端点；返回 false 表示这一帧不该绘制。 */
        boolean act(Group common){
            LCanvas.StatementElem source = elementAt(statement);
            LCanvas.StatementElem targetElem = elementAt(shown);
            if(!drawable(source) || !drawable(targetElem)) return false;

            // 各自在自己局部坐标里取"积木正左侧"的锚点，再由 localToAscendantCoordinates
            // 换算 —— 缩进与 scroll 都含在那一步里（见 anchorLocalX）。
            Vec2 from = Tmp.v1.set(anchorLocalX(), anchorY(source));
            Vec2 to = Tmp.v2.set(anchorLocalX(), anchorY(targetElem));
            source.localToAscendantCoordinates(common, from);
            targetElem.localToAscendantCoordinates(common, to);
            sx = from.x;
            sy = from.y;
            tx = to.x;
            ty = to.y;

            boolean over = hovered();
            float wanted = target.exact ? 1f : 0.5f;
            if(!seeded){
                // 首帧直接到位：否则新出现的线会从 alpha=0 淡入，看起来像"线在长出来"。
                alpha = wanted;
                seeded = true;
            }else{
                // 与 JumpCurve.drawCurve 相同的指数平滑，避免切换候选时闪跳。
                alpha = Mathf.lerp(alpha, over ? 1f : wanted, Mathf.pow(0.85f, Time.delta * 60f));
            }
            return alpha > 0.02f;
        }

        /**
         * 画法照抄原版 {@code LCanvas.JumpCurve}：{@code Lines.stroke(Scl.scl(4f), color)} 画
         * 梯形路由，目标端再画一枚 {@code Tex.logicNode} 箭头（原版那个是跳转按钮的图标，
         * 尺寸取按钮宽度；本功能没有按钮，用 {@link #arrowSize}）。
         *
         * <p>唯一的差别是背包方向：原版按钮贴积木右缘、曲线朝右凸（{@code x + uiHeight}），
         * 本功能贴在左缘、朝左凸（{@code x - rail}）。横向距离取的是<b>本曲线自己的轨道</b>
         * （{@link #lane}），不是固定值 —— 原版正是靠这个把互相重叠的跳转线分到不同轨道上，
         * 否则所有线挤在同一条竖线上互相穿插。多候选时线更细更淡
         * （{@link #uncertainColor}），表示"目标不唯一"。</p>
         *
         * <p>箭头那两行是这套几何里最容易写错的地方：原版用负宽度实现"跨在缘上 + 指向卡内"，
         * 镜像时 x 偏移与宽度<b>必须一起翻</b>，只翻一个就会整枚漂到卡片外面去。</p>
         */
        void draw(){
            if(alpha <= 0.02f) return;
            Draw.color(target.exact ? targetColor : uncertainColor, alpha);
            Lines.stroke(Scl.scl(target.exact ? lineWidth : lineWidth * 0.7f));

            // 轨道横向距离按原版公式与平滑（Mathf.lerp(目标, 当前, pow(0.9, delta))）。
            boolean portrait = Core.graphics != null && Core.graphics.isPortrait();
            float targetRail = Scl.scl(portrait ? railBasePortrait : railBase)
                + Scl.scl(portrait ? railStepPortrait : railStep) * lane;
            // 首帧直接到位：否则新出现的线会从卡片边缘滑出去。
            rail = rail < 0f ? targetRail : Mathf.lerp(targetRail, rail, Mathf.pow(0.9f, Time.delta));

            // 与原版 JumpCurve.drawCurve 同构的梯形路由，只是背包朝左（-rail）。
            float dy = (ty == sy ? 0f : ty > sy ? 1f : -1f) * rail * 0.5f;
            Lines.beginLine();
            Lines.linePoint(sx, sy);
            if(Intersector.intersectSegments(sx, sy, sx - rail, sy + dy, tx, ty, sx - rail, ty - dy, Tmp.v3)){
                Lines.linePoint(Tmp.v3.x, Tmp.v3.y);
            }else{
                Lines.linePoint(sx - rail, sy + dy);
                Lines.linePoint(sx - rail, ty - dy);
            }
            Lines.linePoint(tx, ty);
            Lines.endLine();

            // 目标端箭头：原版 {@code Tex.logicNode.draw(t.x + 0.75f*s, t.y - s/2f, -s, s)} —— 负宽度
            // 让 libGDX 把矩形归一化成 [t.x - 0.25s, t.x + 0.75s] 并水平翻转贴图，于是箭头跨在卡片
            // 右缘上、指向卡内。镜像到左缘要**两次镜像互相抵消**：x 偏移取负、宽度取正，
            // 矩形才是 [tx - 0.75s, tx + 0.25s]（同样跨在缘上）。早先只把 x 取负、宽度仍是负的，
            // 箭头就整枚漂在卡片左缘外面、贴图还翻着指向卡外（2026-09-25 报告）。
            float s = Scl.scl(arrowSize);
            Draw.color(target.exact ? targetColor : uncertainColor, alpha);
            Tex.logicNode.draw(tx - s * 0.75f, ty - s / 2f, s, s);
            Draw.reset();
        }
    }

    // ===== 图层 ==========================================================================

    private final class CounterLayer extends Element{
        /** 反向引用：{@link #invalidate} 从层里拿回所属 overlay（元素本身不是静态可达的）。 */
        private final CounterJumpOverlay owner = CounterJumpOverlay.this;
        /** 每帧重建的绘制列表：确定目标的曲线 + 悬停时展开的候选曲线。 */
        private final Seq<CounterCurve> drawing = new Seq<>();
        /** 轨道分配用的数组（复用，避免每帧分配）。 */
        private final Seq<CounterCurve> laneCurves = new Seq<>();
        private int[] laneBegin = new int[8], laneEnd = new int[8];
        private boolean[] laneFlipped = new boolean[8];

        /**
         * 轨道分配：把每条线覆盖的语句区间交给 {@link JumpLanes}（原版
         * {@code StatementsTable.setJumpHeights} 的镜像），重叠的线因此落到不同轨道。
         * 固定横向距离会让所有线挤在同一条竖线上互相穿插（2026-09-25 报告）。
         */
        void assignLanes(){
            laneCurves.clear();
            for(CounterCurve curve : drawing) laneCurves.add(curve);
            int count = laneCurves.size;
            if(count > laneBegin.length){
                laneBegin = new int[count];
                laneEnd = new int[count];
                laneFlipped = new boolean[count];
            }
            for(int i = 0; i < count; i++){
                CounterCurve curve = laneCurves.get(i);
                curve.flipped = curve.statement >= curve.shown;
                laneBegin[i] = Math.min(curve.statement, curve.shown);
                laneEnd[i] = Math.max(curve.statement, curve.shown);
                laneFlipped[i] = curve.flipped;
            }
            int[] lanes = JumpLanes.assign(laneBegin, laneEnd, laneFlipped);
            for(int i = 0; i < count; i++) laneCurves.get(i).lane = lanes[i];
        }

        /** 每帧决定画哪些线。曲线对象在 rebuild() 里建好（目标已换算成语句下标），
         *  候选线随悬停按需展开。 */
        void actCurves(){
            if(parent == null || parent.parent == null) return;
            Group common = parent.parent;
            drawing.clear();
            for(CounterCurve curve : curves){
                if(curve.exact()){
                    if(curve.act(common)) drawing.add(curve);
                    continue;
                }
                // 目标不唯一：只在悬停该卡片（或候选线开关打开）时按候选画虚影线。
                if(!candidateLines()) continue;
                if(!hovering(curve.statement) && !curve.hovered()) continue;
                for(int target : curve.target.candidates){
                    CounterCurve candidate = new CounterCurve(curve.statement, new Target(target, false, EMPTY_TARGETS),
                        curve.sourceInstruction, curve.write);
                    if(candidate.act(common)) drawing.add(candidate);
                }
            }
            assignLanes();
            collectHoverText();
        }

        @Override
        public void draw(){
            if(parent == null || parent.parent == null) return;
            Group common = parent.parent;
            for(CounterCurve curve : drawing) curve.draw();
            drawBadges(common);
        }

        /** 悬停提示的文案：优先显示确定目标的绝对下标，否则列出候选或说明原因。 */
        private void collectHoverText(){
            for(IntMap.Entry<CounterJumpIndex.Write> entry : writes){
                if(!hovering(entry.key)) continue;
                hoverText = explain(entry.value, firstInstruction.get(entry.key), null);
                return;
            }
            Chip hit = chipAtMouse();
            if(hit != null) hoverText = explain(hit.write, null, hit.write.instruction);
        }

        /** 角标 + 底部"未归属"小标。角标贴在源卡片的正左侧（线的起点上），小标浮在视口
         *  左下角 —— 它们没有可指的卡片（函数体内部、编译器自己发射的指令），但也不能消失。 */
        private void drawBadges(Group common){
            Font font = Fonts.outline;
            if(font == null) return;
            boolean ints = font.usesIntegerPositions();
            font.setUseIntegerPositions(false);

            for(IntMap.Entry<CounterJumpIndex.Write> entry : writes){
                LCanvas.StatementElem elem = elementAt(entry.key);
                if(!drawable(elem)) continue;
                // 与曲线同源：积木正左侧的锚点，交给 localToAscendantCoordinates 换算。
                Vec2 at = Tmp.v1.set(anchorLocalX(), anchorY(elem));
                elem.localToAscendantCoordinates(common, at);
                drawChip(font, entry.value, at.x, at.y, badgeColor(entry.value));
            }

            // 未归属小标没有可依附的卡片（函数体内部 / 编译器发射），改成浮在视口左下角：
            // 原先按画布左边缘定位，卡片一多就会被推出可见区，用户只看到提示却看不到标。
            int shown = 0;
            for(IntMap.Entry<CounterJumpIndex.Write> entry : unattributed){
                if(shown >= chipRows) break;
                Vec2 corner = Tmp.v5.set(0f, 0f);
                localToAscendantCoordinates(common, corner);
                drawChip(font, entry.value, corner.x + Scl.scl(badgeWidth(entry.value) / 2f + 6f),
                    corner.y + Scl.scl(badgeHeight / 2f + 6f) + Scl.scl(badgeHeight + 4f) * shown, unknownColor);
                shown++;
            }

            font.setUseIntegerPositions(ints);
            font.setColor(Color.white);
        }

        /** 一个小方块 + 文本。{@code cx}/{@code cy} 是方块中心。 */
        private void drawChip(Font font, CounterJumpIndex.Write write, float cx, float cy, Color color){
            String text = badgeText(write);
            float height = Scl.scl(badgeHeight);
            float width = badgeWidth(write);
            float x = cx - width / 2f;
            float y = cy - height / 2f;

            Fill.dropShadow(x, y, width, height, Scl.scl(6f), 0.45f);
            Draw.color(Pal.darkerGray, 0.9f);
            Fill.crect(x, y, width, height);
            Draw.color(color);
            Lines.stroke(Scl.scl(1.2f));
            Lines.rect(x, y, width, height);

            font.setColor(color);
            font.draw(text, x + Scl.scl(badgePad), y + height / 2f + font.getCapHeight() / 2f,
                width - Scl.scl(badgePad) * 2f, 1, false);
            Draw.reset();
        }

        private Chip chipAtMouse(){
            if(Core.scene == null || parent == null || parent.parent == null) return null;
            Group common = parent.parent;
            Vec2 mouse = Tmp.v4.set(Core.input.mouseX(), Core.input.mouseY());
            common.stageToLocalCoordinates(mouse);
            Vec2 corner = Tmp.v5.set(0f, 0f);
            localToAscendantCoordinates(common, corner);

            int shown = 0;
            for(IntMap.Entry<CounterJumpIndex.Write> entry : unattributed){
                if(shown >= chipRows) break;
                float width = badgeWidth(entry.value);
                float cx = corner.x + Scl.scl(width / 2f + 6f);
                float cy = corner.y + Scl.scl(badgeHeight / 2f + 6f) + Scl.scl(badgeHeight + 4f) * shown;
                if(Math.abs(mouse.x - cx) <= width / 2f && Math.abs(mouse.y - cy) <= Scl.scl(badgeHeight) / 2f){
                    return new Chip(entry.value);
                }
                shown++;
            }
            return null;
        }
    }

    /** 底部小标的命中结果（只为了把鼠标位置翻译成一条写入）。 */
    private static final class Chip{
        final CounterJumpIndex.Write write;
        Chip(CounterJumpIndex.Write write){ this.write = write; }
    }

    /** 未归属写入最多显示几行（超出部分只保留数据，不画）。 */
    private static final int chipRows = 6;

    // ===== 悬停提示 =====================================================================

    /** 收掉悬停提示并从舞台摘掉它。对话框关闭时必须走到这里，否则标签会留在屏幕上。 */
    private void hideHint(){
        hoverText = null;
        if(hint == null) return;
        hint.visible = false;
        hint.remove();
    }

    /** 一段跟着光标走的说明标签。挂在 scene root 上（与 EscapePreview 同一套做法），
     *  这样它不会被 pane 的裁剪切掉，也不参与画布布局。 */
    private void updateHint(){
        if(hoverText == null || Core.scene == null){
            if(hint != null) hint.visible = false;
            return;
        }
        if(hint == null){
            hint = new Label("", Styles.outlineLabel);
            hint.touchable = Touchable.disabled;
            hint.setFontScale(0.8f);
        }
        if(hint.parent != Core.scene.root){
            if(hint.parent != null) hint.remove();
            Core.scene.root.addChild(hint);
        }
        hint.setColor(Color.lightGray);
        hint.setText(hoverText);
        hint.pack();
        hint.setPosition(Core.input.mouseX() + Scl.scl(14f), Core.input.mouseY() - hint.getHeight() - Scl.scl(6f));
        hint.visible = true;
        hint.toFront();
    }

    /** 角标文字是纯函数（只读 write 的目标），{@link #badgeWidth} 等静态口径必须能直接调用。 */
    private static String badgeText(CounterJumpIndex.Write write){
        if(write.targets.length == 1) return Integer.toString(write.targets[0]);
        if(write.targets.length > 1){
            return write.targets[0] + ".." + write.targets[write.targets.length - 1];
        }
        // 目标不可静态确定（switch 跳转表、变量赋值、函数返回跳板）：只报"这里改 @counter"。
        return "?";
    }

    /** 角标宽度：与 {@link #drawChip} 用同一口径，命中判定才不会与画出来的方块错开。 */
    private static float badgeWidth(CounterJumpIndex.Write write){
        return badgeText(write).length() * Scl.scl(6.4f) + Scl.scl(badgePad) * 2f;
    }

    /** 角标颜色：目标唯一 = 绿（确定），多候选 = 琥珀（不确定），无候选 = 灰（不可解析）。 */
    private static Color badgeColor(CounterJumpIndex.Write write){
        if(write.targets.length == 1) return targetColor;
        return write.targets.length == 0 ? unknownColor : uncertainColor;
    }

    /**
     * 悬停提示的完整文案。
     *
     * @param first   该写入所属卡片发射的第一条指令（没有则为 null）
     * @param orphan  未归属写入的指令下标（没有则为 null）—— 用来在提示里点明"函数体内部，
     *                下标只在该函数体被后置后的产物里有意义"
     */
    private static String explain(CounterJumpIndex.Write write, Integer first, Integer orphan){
        StringBuilder out = new StringBuilder(noteKey(write));
        if(orphan != null) out.append("  #").append(orphan);
        if(write.targets.length == 1){
            out.append(" → ").append(write.targets[0]);
            if(first != null) out.append("  (").append(first).append(" → ").append(write.targets[0]).append(')');
        }else if(write.targets.length > 1){
            out.append(" → ").append(joinTargets(write.targets));
        }
        if(orphan != null){
            out.append("  ").append(Core.bundle.get("logicsugar.counterJump.inFunction",
                "emitted inside a function body: its instruction index only means something after hoisting"));
        }
        if(write.wrap){
            out.append("  ").append(Core.bundle.get("logicsugar.counterJump.wrap",
                "outside the program: execution wraps to instruction 0"));
        }
        return out.toString();
    }

    private static String joinTargets(int[] targets){
        StringBuilder out = new StringBuilder();
        for(int i = 0; i < targets.length && i < 6; i++){
            if(i > 0) out.append(", ");
            out.append(targets[i]);
        }
        if(targets.length > 6) out.append(", …");
        return out.toString();
    }

    /** 写入形态的说明文案：已知形状给具体解释，其余走通用文案。 */
    private static String noteKey(CounterJumpIndex.Write write){
        String key = switch(write.note == null ? "" : write.note){
            case "set-literal" -> "logicsugar.counterJump.literal";
            case "relative" -> "logicsugar.counterJump.relative";
            case "switch-dispatch" -> "logicsugar.counterJump.switchTable";
            case "function-return" -> "logicsugar.counterJump.functionReturn";
            case "entry-skip" -> "logicsugar.counterJump.entrySkip";
            default -> "logicsugar.counterJump.dynamic";
        };
        return Core.bundle.get(key, write.note);
    }

    private boolean hovering(int statement){
        LCanvas.StatementElem elem = elementAt(statement);
        return drawable(elem) && safeHasMouse(elem);
    }

    /** {@code Element.hasMouse()} 在 Core.scene 为 null 时会 NPE，渲染回调里必须先挡一层。 */
    private static boolean safeHasMouse(Element elem){
        try{
            return Core.scene != null && elem.hasMouse();
        }catch(Throwable t){
            return false;
        }
    }

    private static boolean candidateLines(){
        try{
            return Core.settings.getBool(settingCandidateLines, true);
        }catch(Throwable t){
            return true;
        }
    }
}
