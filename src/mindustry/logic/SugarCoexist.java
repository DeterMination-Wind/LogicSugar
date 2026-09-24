package mindustry.logic;

import arc.Core;
import arc.func.Cons;
import arc.scene.Element;
import arc.scene.ui.layout.Cell;
import arc.util.Log;
import logicsugar.FunctionLibrary;
import mindustry.Vars;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 「共存」档：让第三方逻辑编辑器（例如逻辑工具）的界面直接驱动 LogicSugar 的画布。
 *
 * <p><b>为什么不是把对方的界面搬过来。</b>对方的浮层面板是在它的构造函数里
 * {@code new ClipboardFloatingBar(this, ...)} 造出来的，每个面板都持有那个对话框实例，并通过
 * {@code dialog.canvas} 取画布。所以把面板挂到我们的对话框上时，它们操作的是"那个已经不再显示的
 * 旧画布"——点得动、没反应。那条路结构上就是死的。</p>
 *
 * <p><b>反过来为什么成立。</b>原版 {@link LogicDialog} 对画布和保存回调都是"每次现读字段"：
 * {@code canvas} 是 public 字段、{@code show()} 里 {@code canvas.load(code)}、
 * {@code hidden(() -> consumer.get(canvas.save()))}。于是保留对方的对话框当外壳（浮层、历史、
 * 快捷键、工具按钮全都原样工作），只把 {@code canvas} 换成我们的，再把它关闭时的产物接上编译
 * ——对方一个字都不用改，就得到了糖语言。</p>
 *
 * <p><b>编译接在 consumer 上，而不是 {@code save()} 里。</b>实测（逻辑工具 v1.1.135）：
 * {@code LogicToolLogicDialog.currentCode()} 每次 update 都会调 {@code canvas.save()} 做历史与
 * 差异轮询。在那里编译既昂贵（每次都跑完整编译），又必然误报——用户还没写完就弹编译错误。
 * 原版只在关闭对话框时走 {@code hidden(() -> consumer.get(canvas.save()))}，
 * 那也是唯一需要 mlog 的时刻，所以编译层的正确落点是把 {@code consumer} 包一层。</p>
 *
 * <p>这里刻意<b>不改对方任何代码、也不读它的包私有字段</b>：画布是 public 的，保存路径是原版
 * {@code consumer}（反射只是因为跨类加载器的包级访问会抛 IllegalAccessError）。对方改自己的内部
 * 结构不会让这段代码失效。</p>
 */
public final class SugarCoexist{
    private SugarCoexist(){}

    /** {@code LogicDialog.consumer} 是包级私有的，且运行期属于另一个类加载器，只能反射读写。
     *  它是关闭路径的唯一出口，读不到就无处接入编译 ⇒ 直接放弃共存（调用方回落到接管档），
     *  不走降级：半装的共存恰好会制造"能插糖语句卡、却永远不编译"的编辑器。 */
    private static final Field consumerField = field(LogicDialog.class, "consumer");
    /** {@code LogicDialog.executor}，只用来判断这次打开是不是函数库会话（executor == null）。
     *  读不到就按普通处理器程序处理：那只影响超大函数库的语句上限，不会编译错内容。 */
    private static final Field executorField = field(LogicDialog.class, "executor");

    private static Field field(Class<?> owner, String name){
        try{
            Field result = owner.getDeclaredField(name);
            result.setAccessible(true);
            return result;
        }catch(Throwable ignored){
            return null;
        }
    }

    /** MindustryX 的「逻辑辅助器」面板（{@code mindustryX.features.ui.LogicSupport}）。
     *
     *  <p>它的「更新编辑的逻辑」按钮是 {@code consumer.get(canvas.save())}，用的就是面板构造时记下的
     *  那个 canvas 和 consumer，**完全不经过** {@code LogicDialog.consumer}——所以在别人的编辑器里
     *  那个 consumer 是处理器自己的保存回调，不重新接管就会把画布交出的<b>糖文本</b>当程序写进处理器
     *  （程序随即加载失败，变量面板也会跟着空掉，看上是块死掉的面板）。</p>
     *
     *  <p>MindustryX 是可选宿主（原版没有这个类，编译期也拿不到它），所以整条路走反射，找不到就跳过。</p> */
    /** MindustryX 面板的类。找不到说明宿主没有它（原版），或者加载器看不见它。这个结果必须能报出来：
     *  重绑一旦静默跳过，面板的失效方式是"点一下就把糖文本当程序写进处理器"。 */
    private static final Class<?> logicSupportClass = classFor("mindustryX.features.ui.LogicSupport");
    private static final Method logicSupportBuild = logicSupportClass == null ? null
        : method(logicSupportClass, "build", LCanvas.class, LExecutor.class, Cons.class);
    /** 面板自己记着的 executor（{@code LogicSupport.executor}）。原版在这次 show 里刚把它设成当前
     *  处理器的 executor，所以它就是"这一会话"的值，比反射读对话框字段更可靠。 */
    private static final Field logicSupportExecutorField =
        logicSupportClass == null ? null : field(logicSupportClass, "executor");

    private static Class<?> classFor(String className){
        // Mindustry 给每个 mod 一个子加载器，它只把 mindustry./arc. 之外的名字当"自己的类"去找，而
        // mindustryX.* 既不是 mindustry.* 也不是 arc.*，能否穿透完全取决于委派链。所以直接问游戏
        // 自己的加载器（MindustryX 是重编译的 core，就在那里），mod 加载器只作后备。
        ClassLoader game = Vars.class.getClassLoader();
        ClassLoader mod = SugarCoexist.class.getClassLoader();
        for(ClassLoader loader : new ClassLoader[]{game, mod}){
            if(loader == null) continue;
            try{
                return Class.forName(className, false, loader);
            }catch(Throwable ignored){
            }
        }
        return null;
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters){
        try{
            return owner.getMethod(name, parameters);
        }catch(Throwable ignored){
            return null;
        }
    }

    /**
     * The dialogs that already carry a {@code shown} listener. {@code Element.shown(...)} appends
     * and cannot be unregistered, so the listener is installed at most once per dialog and reads
     * the current canvas when it runs: switching settings replaces the canvas through
     * {@link #install}/{@link #uninstall}, which must not add another one. Keys are weak because
     * the dialog belongs to the other mod and may outlive this session; every access is on the
     * render thread, like the rest of the UI.
     */
    private static final Map<LogicDialog, Boolean> listened = new WeakHashMap<>();

    /**
     * 把 {@code dialog} 的画布换成共存画布。返回 {@code false} 表示换不进去（布局里找不到画布所在
     * 的单元格、读不到保存路径，或对话框正开着）——调用方<b>必须</b>回落到接管档。
     */
    public static boolean install(LogicDialog dialog){
        if(dialog == null || dialog.canvas == null) return false;

        // 已经装过（画布还在布局里）：直接重新启用。shown 监听还挂着，下次 show 会自己重新接管
        // consumer。这里必须复用同一个画布，否则每切一次档就多一层 shown 监听。
        if(dialog.canvas instanceof CoexistCanvas coexist){
            coexist.active = true;
            return true;
        }

        if(!probeConsumer(dialog)) return false;

        // 对话框正开着时换画布，本次会话的关闭路径会把**空画布**交出去：这一会话的 consumer 仍是
        // 对方的裸保存回调（arm() 挂在 shown 监听上，只有本次 show 时画布已经是共存画布才会包一层），
        // 而 dialog.canvas 此刻已经换成我们那张还没 load 过的空画布 ⇒ 关闭时
        // consumer.get(canvas.save()) 把一个空程序写进处理器。
        // 现在的 UI 流程（要进设置页得先关掉逻辑编辑器）把它挡住了，但保护不该只存在于流程里。
        // 返回 false ⇒ 调用方回落到接管档。
        if(dialog.isShown()){
            Log.warn("LogicSugar: refusing to install the coexist canvas into the open editor '@'; "
                + "close it and switch the mode again. Falling back to taking the editor over.",
                dialog.getClass().getName());
            return false;
        }

        // 画布原来的 z 序必须在换之前记下来：Cell.setElement() 会把新元素追加到 children 末尾，
        // 还回去时要放回这一层（见 swapCanvas）。getZIndex() 就是"在父容器 children 里的位置"。
        int index = dialog.canvas.getZIndex();
        CoexistCanvas canvas = new CoexistCanvas(dialog, dialog.canvas, index);
        if(!swapCanvas(dialog, dialog.canvas, canvas, index)) return false;

        dialog.canvas = canvas;
        // 一次把定位「面板僵尸」要用的三件事报全：装到了哪个对话框上（不是界面上正开着的那个，
        // 就说明我们换错了对象，后面所有监听都不会触发）、MindustryX 面板的类找没找到、它的
        // build/executor 反射通不通 —— 缺任何一项，arm() 里的重绑都会静默跳过。
        Log.info("LogicSugar: coexist canvas installed into '@' (LogicSupport class=@, build=@, executorField=@).",
            dialog.getClass().getName(), logicSupportClass != null, logicSupportBuild != null,
            logicSupportExecutorField != null);

        // 原版每次 show 都会重装 consumer（show(String, ...) 里新建 lambda），所以每个会话都得
        // 重新包一层。shown 监听是追加的，不会顶掉对方自己注册的监听。
        //
        // 这里刻意跑两次。MindustryX 在 LogicDialog.show(String, ...) 里——也就是我们的监听被触发
        // 之前——就把「逻辑辅助器」面板指回了处理器的裸保存回调；而第三方编辑器在 super.show()
        // 返回之后还会继续动画布与控件（逻辑工具就是在 super.show() 之后又 bindCurrentCanvas()）。
        // 同步那一次紧跟 MindustryX 的绑定、不留窗口期，下一帧那一次压过对方在 show 之后的改动。
        // arm() 是幂等的：consumer 没变就跳过包装，只重绑面板。
        //
        // 监听只挂一次，且不捕获挂它时的画布：共存 → 接管/让位 → 共存 会把画布换进换出好几轮，
        // 而监听注销不掉，按"挂载时的画布"收尾就会留下一条对着早已 active=false 的旧画布的
        // 监听——功能上无害（arm() 立刻早退），代价是每开一次编辑器多刷若干条日志，而那条日志
        // 正是排查「面板僵尸」的主要线索。
        if(!listened.containsKey(dialog)){
            listened.put(dialog, Boolean.TRUE);
            dialog.shown(() -> {
                if(dialog.canvas instanceof CoexistCanvas coexist) coexist.arm(dialog);
                Core.app.post(() -> {
                    if(dialog.canvas instanceof CoexistCanvas coexist) coexist.arm(dialog);
                });
            });
        }
        return true;
    }

    /**
     * 把 {@code dialog} 的画布换回它原来那个。离开共存档时必须调用：否则对方编辑器里留着的还是
     * 我们的画布，让位档下糖语句照样会被编译——那正是让位档承诺不做的事。
     *
     * <p>{@link CoexistCanvas#active} 随之一并关掉，让先前挂上的 shown 监听不再重新接管 consumer
     * （监听无法注销，只能靠这个开关失效）。这一步在换回失败时同样执行。</p>
     *
     * @return {@code false} 表示没能还回去——调用方据此知道编辑器里可能还留着我们的画布，
     *         而不是被一个成功返回值骗过去
     */
    public static boolean uninstall(LogicDialog dialog){
        if(dialog == null || !(dialog.canvas instanceof CoexistCanvas coexist)) return false;

        coexist.active = false;
        // 放回装机时记下的那一层，而不是"现在的位置"——现在的位置是换画布时被挪到的末尾。
        if(coexist.original == null
            || !swapCanvas(dialog, coexist, coexist.original, coexist.originalIndex)){
            Log.warn("LogicSugar: could not hand the canvas back to '@'; that editor may still be "
                + "showing LogicSugar's coexisting canvas.", dialog.getClass().getName());
            return false;
        }

        dialog.canvas = coexist.original;
        return true;
    }

    /** 读写一次 {@code consumer} 并原样写回：这条路只依赖它，探不通就别装。 */
    private static boolean probeConsumer(LogicDialog dialog){
        Cons<String> current = readConsumer(dialog);
        if(current == null) return false;

        writeConsumer(dialog, current);
        return readConsumer(dialog) == current;
    }

    /**
     * 用 {@link Cell#setElement} 把 {@code old} 换成 {@code replacement}，并把新元素的 z 序复位回
     * {@code index}。
     *
     * <p>直接给 {@code dialog.canvas} 赋值只换掉字段，场景图里仍然是旧画布（用户看到的还是原版那个），
     * 所以必须动 Cell——顺带保留 {@code .grow()} 等布局属性。但 {@code Cell.setElement} 内部是
     * {@code table.addChild(newElement)}（{@code arc Cell.java:82}），也就是把新元素<b>追加到 children
     * 列表末尾</b>：cell 的布局位置没变（尺寸、排版都对得上），可 Arc 按 children 顺序绘制、按<b>逆序</b>
     * 做命中测试——画布于是被画到 MindustryX「逻辑辅助器」面板之上，并抢走它所有按钮的点击。现象就是
     * "面板被压在语句块下面、按钮点不动"，而这两件事都只在共存档出现（只有共存档换画布）。</p>
     *
     * <p>复位必须用 {@link Element#setZIndex}，<b>绝不能</b>用 {@code removeChild + addChildAt}：
     * {@code Table.removeChild} 会把该子元素对应单元格的 element 置空（{@code arc Table.java:605}），
     * 于是 {@code Table.layout()} 直接跳过这个没有元素的单元格 ⇒ 画布永远拿不到尺寸与位置，整块画布
     * 塌成不可见（实测现象：换完画布后<b>所有语句都消失</b>）。{@code setZIndex} 只动 {@code children}
     * 这一个列表，cells 与单元格关联都不受影响。</p>
     *
     * <p>两个方向都用它：装进去（原版画布 → 共存画布）与还回去（共存画布 → 原版画布）。还回去必须传
     * <b>装机时记下的那一层</b>，不能就地取——那时画布自己已经被追加到末尾了。</p>
     */
    private static boolean swapCanvas(LogicDialog dialog, LCanvas old, LCanvas replacement, int index){
        for(Cell<?> cell : dialog.getCells()){
            if(cell.get() == old){
                cell.setElement(replacement);
                // setZIndex 对负值会抛异常，而读取失败（父容器为空）时正是 -1。它自己会安全处理
                // "已经在目标层"与"父容器只有一个子元素"这两种情况，不会做无谓的摘除/插入。
                if(index >= 0) replacement.setZIndex(index);
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Cons<String> readConsumer(LogicDialog dialog){
        try{
            return (Cons<String>)consumerField.get(dialog);
        }catch(Throwable ignored){
            return null;
        }
    }

    private static void writeConsumer(LogicDialog dialog, Cons<String> consumer){
        try{
            consumerField.set(dialog, consumer);
        }catch(Throwable ignored){
            // 读得通就写得通（probeConsumer 已确认），这里只是不让异常逃出关闭路径
        }
    }

    /**
     * 共存档下用户实际编辑的画布。
     *
     * <p>{@link #save()} 保持原义——交出手上的糖文本，因此第三方每帧轮询的
     * {@code currentCode()} 既便宜又不会误报错误；编译发生在 {@link #arm(LogicDialog)} 包出来的
     * consumer 外层，那里才是原版真正要把结果写回处理器的时刻。</p>
     */
    public static class CoexistCanvas extends SugarCanvas{
        /** 装着这个画布的第三方对话框。只用来判断"这次打开是不是函数库会话"。 */
        private final LogicDialog dialog;
        /** 被我们换下去的那个原版画布。离开共存档时要还回去（{@link SugarCoexist#uninstall}），
         *  否则对方编辑器里留着的是我们的画布，让位档就会名不副实。 */
        private final LCanvas original;
        /** 原版画布在对话框 children 里的 z 序。还回去时必须放回同一层，否则画布仍留在最上层，
         *  MindustryX 的面板会继续被压住、按钮继续点不动（见 {@link SugarCoexist#swapCanvas}）。 */
        private final int originalIndex;
        /** 这个画布现在还装着吗。离开共存档后置 false：shown 监听注销不掉，只能靠它失效，
         *  否则对方编辑器重新打开时又会被我们接管 consumer。 */
        private boolean active = true;
        /** 这次会话打开时收到的原文（一般是处理器的 mlog）。它是嵌入函数库的唯一来源，
         *  与"用户改没改"无关：改没改由 {@link #baselineSugar} 判定。 */
        private String baseline = "";
        /** 上一次 {@link #load} 之后画布自己的渲染结果。关闭时拿它和当前内容比，就知道用户到底
         *  改没改——不能拿糖文本直接比对 mlog：两者本来就不同，那样每次关闭都会误判成改过。 */
        private String baselineSugar = "";
        /** 已经装进对话框的编译层。用来识别"当前 consumer 已经是我们的"，避免重复包装。 */
        private Cons<String> compiled;
        /** 编译层替我们记住的下游（处理器自己的保存回调）。逻辑辅助器面板的"更新编辑的逻辑"
         *  需要绕过 {@link #compiled} 的"没改就不提交"判断直接落盘，所以单独存一份。 */
        private Cons<String> downstream;

        CoexistCanvas(LogicDialog dialog, LCanvas original, int originalIndex){
            this.dialog = dialog;
            this.original = original;
            this.originalIndex = originalIndex;
        }

        @Override
        public void load(String asm){
            String text = asm == null ? "" : asm;

            // 要载入的内容和画布现状一致 => 这次不是"打开一个新程序"，而是回灌：
            // 原版在解析失败时会补一次 canvas.load("")，第三方编辑器在 show() 返回后还会用
            // normalizeCode(canvas.save()) 再灌一次。两者都不能顶掉 baseline，否则编译会改用
            // 另一个（或根本没有）嵌入函数库。
            boolean reopening = same(text, super.save());

            // 同一个对话框也负责函数库文件（executor == null），那种会话的语句上限高得多，
            // 而 load 是唯一用得上这个信息的地方，所以在这里问一次。
            librarySession = isLibrarySession(dialog);

            // 打开时先解糖：处理器里存的是 mlog（糖程序还会带 carrier 注释），直接塞进画布会
            // 变成一堆裸指令卡。解不出来的输入会原样返回，因此对已经是糖文本的输入也是恒等的。
            SugarDecompiler.Result recovered = SugarDecompiler.decompile(text, editingPrivileged());
            // 解析失败就照原样抛给原版，让它走自己的退化路径（canvas.load("")）。这里不需要额外的
            // 屏障：那种情况下画布是空的，而空画布与 baselineSugar 相同，关闭时被判为"没改过"。
            super.load(recovered.sugar);

            // 用画布自己的渲染结果当基准，而不是 recovered.sugar：经过一次规范化往返之后两者
            // 才严格可比，未修改的会话才不会因为排版差异被误判成改过。
            baselineSugar = super.save();
            if(!reopening) baseline = text;
        }

        /**
         * 把对话框当前的 consumer 包一层编译：画布交出的糖文本先编译成 mlog 再交给原版。
         *
         * <p>放在 consumer 上是刻意的：第三方模组每帧都调 {@code canvas.save()} 做轮询，而
         * {@code consumer} 只在关闭对话框时被原版调用一次。</p>
         */
        private void arm(LogicDialog dialog){
            Cons<String> next = readConsumer(dialog);
            boolean alreadyOurs = next != null && next == compiled;
            // 「面板僵尸」这类问题只能在这里断案，所以这条日志把四个关键事实一次说清：监听有没有
            // 跑到（每次 show 都打，看到它说明监听还挂在对方对话框上）、画布还活不活、consumer
            // 换没换、executor 读没读到（读不到就不会去动面板）。
            //
            // 已经处于目标状态（consumer 就是我们包的那层）时只打 debug：正常路径每次 show 都会
            // 走到这里两次，一直打 info 会把真正有用的那几条（状态真的变了、重绑失败）淹掉。
            // 排查「面板僵尸」时把日志级别放开就能拿到它。
            if(alreadyOurs){
                Log.debug("LogicSugar: coexist arm on '@' (already armed; active=@, executor=@)",
                    dialog.getClass().getSimpleName(), active, readExecutor(dialog) != null);
            }else{
                Log.info("LogicSugar: coexist arm on '@' (active=@, consumer=@, alreadyOurs=@, executor=@)",
                    dialog.getClass().getSimpleName(), active, next != null, false, readExecutor(dialog) != null);
            }

            // 这个画布已经被换下去了（用户切到了别的档位）：shown 监听注销不掉，靠这里失效。
            if(!active) return;

            // 只在 consumer 换了新的一层时才重新包：原版每次 show 都新建一个 lambda，所以正常每个
            // 会话都会走到这里；而对方在 show 之后把它设回我们那层时（next == compiled），包不包
            // 都一样，跳过即可。
            if(next != null && next != compiled){
                downstream = next;

                Cons<String> layer = sugar -> {
                    if(sugar == null || same(sugar, baselineSugar)){
                        // 没改过（或把改动撤回了）：什么都不提交。
                        //
                        // 注意这里不是"交回原文"：原版拿到的是 mlog，而画布交出的是糖文本，两者本来
                        // 就不同，交回什么都过不了它那句 result.equals(code)。直接不调用下游，处理器
                        // 就保持原程序——与原版"没改就不写"的行为一致，且不需要知道 code 是什么。
                        // （原版的 forceRestart 在这里被跳过，但它在 show() 里就被重置为 false，
                        //   实际不可达。）
                        return;
                    }
                    push(sugar);
                };

                compiled = layer;
                writeConsumer(dialog, layer);
            }

            // 面板的重绑必须无条件执行，所以不能放进上面那个分支里。MindustryX 每次 show 都会把
            // 自己的静态回调指回"处理器裸保存回调"，只要有一次没覆盖上，点一次「更新编辑的逻辑」
            // 就会把糖文本当成程序写进处理器 —— 而这跟 consumer 换没换层没有任何关系。
            bindLogicSupport();
        }

        /** 编译一段糖文本并交给处理器。编译失败时绝不把糖文本交出去：原版会把它当 mlog 存进
         *  处理器，程序随即加载失败——什么都不提交，处理器保持原内容，只多一条提示。
         *
         *  <p>整条路都跑在<b>别人的关闭流程</b>里：{@code push} 由包出去的 {@code consumer} 调用，
         *  而那是原版 {@code hidden(...)} 的出口，异常逃出去就会打断对话框的关闭。所以这里按
         *  「绝不逃逸」处理，而不是按异常类型做精确分类。</p> */
        private void push(String sugar){
            if(downstream == null) return;

            String program;
            try{
                program = compile(sugar);
            }catch(RuntimeException exception){
                report(exception);
                return;
            }

            try{
                downstream.get(program);
            }catch(RuntimeException exception){
                // 下游是处理器自己的保存回调（第三方编辑器会再包一层）。它坏掉不该由我们负责，
                // 但同样不能让它掀掉对话框的关闭流程。
                Log.warn("LogicSugar: the downstream save callback failed for '@': @",
                    dialog.getClass().getSimpleName(), exception);
            }
        }

        /**
         * 把 MindustryX「逻辑辅助器」面板重新接到这个画布上。
         *
         * <p>那个面板把 canvas 与 consumer 记在自己的静态字段里，并在每次 {@code LogicDialog.show}
         * 的末尾由原版重新绑定：{@code consumer.get(canvas.save())}。在这里 {@code canvas} 是我们的
         * 画布（对的），但 {@code consumer} 是<b>对方的</b>裸保存回调（错的）——它的「更新编辑的逻辑」
         * 按钮于是会把糖文本当程序写进处理器。所以每个会话都要用编译过的回调把它接管回来。</p>
         *
         * <p>{@code build} 是 MindustryX 的 API，原版没有这个类，编译期也拿不到，所以走反射；
         * 找不到（原版宿主）就什么都不做。</p>
         */
        @SuppressWarnings("unchecked")
        private void bindLogicSupport(){
            if(logicSupportBuild == null){
                if(logicSupportClass == null){
                    // 原版宿主（或加载器看不见这个类）是常态，每个会话都会走到这里：只留 debug，
                    // 否则这条 warn 会被刷成噪声，把真正排查「面板僵尸」用的那几行日志淹没。
                    Log.debug("LogicSugar: MindustryX's LogicSupport is not present; nothing to rebind.");
                }else{
                    // 类在、方法不在：那是 MindustryX 的 API 变了，与"宿主本来就没有"是两回事。
                    // 面板会退回"点一下就把糖文本当程序写进处理器"，必须留一条看得见的痕迹。
                    Log.warn("LogicSugar: MindustryX's LogicSupport.build is unavailable, so its panel keeps "
                        + "the processor's raw save callback and must not be used from the coexist editor.");
                }
                return;
            }

            // 取 executor 优先问面板自己：原版在这次 show 里刚把当前处理器的 executor 写进它的静态
            // 字段，那是"这一会话"的确切值；对话框字段只作后备（函数库会话里两者都是 null）。
            LExecutor executor = logicSupportExecutor();
            if(executor == null) executor = readExecutor(dialog);

            // 读不到就什么都不做：LogicSupport.build() 会把面板的 executor 覆盖成传入值，而
            // rebuildVarsTable() 第一件事就是 executor == null 时直接返回 —— 传 null 会清空变量表，
            // 把一个"能用但写错程序"的面板变成一块空面板，那比不修还糟。
            if(executor == null){
                Log.warn("LogicSugar: no executor is available for MindustryX's panel, so it was left as is.");
                return;
            }

            try{
                logicSupportBuild.invoke(null, this, executor, (Cons<String>)this::push);
                // 成功这条只在 debug：MindustryX 宿主上每次 show 都会重绑两次，常态路径不值得刷
                // info；失败那条（下面的 warn）才是必须看得见的那一条。
                Log.debug("LogicSugar: rebound MindustryX's logic-support panel to LogicSugar's canvas.");
            }catch(Throwable throwable){
                // 不吞：面板坏了只有这里能看出来，而它的失效方式是"把糖文本写进处理器"，静默失败
                // 等于把最危险的一种失败藏起来。反射失败也只是少一个可选功能，不影响编译路径。
                Log.warn("LogicSugar: could not rebind MindustryX's logic-support panel: @", throwable);
            }
        }

        /** 面板自己记着的 executor。读不到返回 null，调用方再退回对话框字段。 */
        private static LExecutor logicSupportExecutor(){
            if(logicSupportExecutorField == null) return null;
            try{
                return (LExecutor)logicSupportExecutorField.get(null);
            }catch(Throwable ignored){
                return null;
            }
        }

        /** 与 {@link SugarLogicDialog#submit} 同口径：嵌入库与本地库都取自打开时的那段原文。 */
        private String compile(String sugar){
            SugarCompiler.EffectiveLibrary library = SugarCompiler.effectiveLibrary(
                baseline, SugarFunctions.library(), FunctionLibrary.loadText());
            return SugarCompiler.compile(sugar, SugarCompiler.currentMode(), library.index, library.text,
                SugarCompiler.currentStrategy(), SugarCompiler.currentAssertEmit(), editingPrivileged(), false);
        }

        private void report(RuntimeException exception){
            Log.warn("LogicSugar: the coexist editor could not compile the program, so the processor keeps its previous program: @",
                exception.getMessage());
            Core.app.post(() -> {
                if(Vars.ui != null){
                    Vars.ui.showErrorMessage(Core.bundle.format("logicsugar.coexist.failed", exception.getMessage()));
                }
            });
        }

        /** 逐行去掉首尾空白后比较：画布往返一次可能只差空白，那不算用户改过。 */
        private static boolean same(String a, String b){
            if(a == null || b == null) return a == b;
            return a.equals(b) || normalize(a).equals(normalize(b));
        }

        private static String normalize(String text){
            StringBuilder builder = new StringBuilder(text.length());
            for(String line : text.split("\n", -1)){
                builder.append(line.trim()).append('\n');
            }
            return builder.toString();
        }
    }

    /** {@code LogicDialog.executor} 是否为空，用来判断这次打开的是函数库会话。读不到就按普通
     *  处理器程序处理（不假装是函数库：那只影响语句上限，不影响编译内容）。 */
    static boolean isLibrarySession(LogicDialog dialog){
        return executorField != null && readExecutor(dialog) == null;
    }

    /** {@code LogicDialog.executor}。逻辑辅助器面板要靠它显示变量表，读不到就给 null —— 面板会
     *  显示一张空表，编译路径不受影响。 */
    static LExecutor readExecutor(LogicDialog dialog){
        if(executorField == null) return null;
        try{
            return (LExecutor)executorField.get(dialog);
        }catch(Throwable ignored){
            return null;
        }
    }
}
