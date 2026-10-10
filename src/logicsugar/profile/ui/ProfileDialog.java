package logicsugar.profile.ui;

import arc.Core;
import arc.func.Prov;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.math.Mathf;
import arc.scene.Element;
import arc.scene.event.Touchable;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Image;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Label;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import arc.util.Align;
import logicsugar.LogicSugarSettings;
import logicsugar.assist.L10n;
import logicsugar.profile.Instrumentation;
import logicsugar.profile.InstrumentationEngine;
import logicsugar.vars.ui.VarsDialog;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;

import java.util.Arrays;
import java.util.List;

/**
 * 处理器 profiler 的主界面：每条指令的执行次数（或消耗的指令预算）、占比与分支比例，
 * 以及总计/覆盖率。
 *
 * <p>Ported from upstream MlogAssertions v0.11.6 ({@code cardillan.mlogassertions.ui.ProfileDialog});
 * 本地化：文案走 {@code logicsugar.profile.*} bundle 键 + 英文 fallback（{@link L10n#text}），
 * 源码列复用 {@link VarsDialog#escape}（字符串常量里的 {@code [} 会被 Arc 富文本当成颜色标签）。</p>
 *
 * <p><b>行控件只建一次</b>（上游 v0.11.4 修的就是这条）：数字、源码文本与序号都靠
 * {@code Label.update} 刷新，序号列用 {@code prevIndex} 判断「这一行换的是哪条指令」；
 * 排序开启时每 500ms 检查一次是否需要重排，超阈值只重算行序（{@code Core.app.post(this::sort)}），
 * <b>不再整页重建</b>——整页重建会吃掉输入焦点、并让列表在滚动中闪烁。</p>
 *
 * <p>「已访问」判定走 {@link Instrumentation#isCovered(int)}：快路径只写 {@code steps}、
 * 让出路径只写 {@code covered} 位图，未访问的行整行压暗（{@code emptyColor}）。</p>
 */
public class ProfileDialog extends BaseDialog{
    static boolean totals = true;
    static boolean percents = false;
    static boolean sorted = false;
    static boolean branches = false;
    static boolean colors = true;
    static boolean execTime = false;

    Instrumentation instrumentation = null;
    LogicBuild build;
    long lastCheck = 0;
    long lastSort = 0;

    // 行号 -> 指令下标（排序结果）与它上次显示过的值。行控件建一次、只改文本，所以必须记住
    // 每一行当前展示的是哪条指令：变了才改序号/源码/序号底色。
    int[] indexes;
    int[] prevIndex;

    // Saves/restores scroll position when the content of the pane changes
    float scroll = 0f;
    float w;

    public ProfileDialog(LogicBuild build){
        super(tr("logicsugar.profile.title", "Profiler"), Styles.fullDialog);
        this.build = build;

        Instrumentation instrumentation = InstrumentationEngine.getInstrumentation(build);
        if(instrumentation == null && Core.settings.getBool(LogicSugarSettings.settingStartProfilerImmediately, false)){
            instrumentation = InstrumentationEngine.startProfiling(build);
        }

        onResize(() -> {
            if(w != w()) setup();
        });
        setup(instrumentation);
    }

    private float w(){
        return Math.max(180f, Math.min(Core.graphics.getWidth() / Scl.scl(1.05f) - 210f, 800f));
    }

    /** 重排行序：排序助手在 {@link Instrumentation#order}（可无头自检）。重排只换行里显示的
     *  指令，不重建控件——行内容由各自的 {@code update} 按 {@code prevIndex} 自行跟进。 */
    private void sort(){
        lastSort = System.currentTimeMillis();
        indexes = Instrumentation.order(instrumentation.steps, instrumentation.time, execTime, sorted);
    }

    public void startStop(){
        instrumentation = InstrumentationEngine.getInstrumentation(build, true, instr -> instr.profiling = !instr.profiling);
        setup();
    }

    public void setup(Instrumentation instrumentation){
        if(this.instrumentation != instrumentation){
            this.instrumentation = instrumentation;
            setup();
        }
    }

    public void setup(boolean dummy){
        setup();
    }

    public void setup(){
        buttons.clear();
        cont.clear();

        Color basicColor = Color.slate.cpy().mul(0.8f);
        Color barColor = basicColor.cpy().mul(0.66f);
        Color fillColor = basicColor.cpy().mul(0.33f);
        Color emptyColor = basicColor.cpy().mul(0.15f);

        w = w();
        float labelPad = 8f;
        float iWidth = branches ? w - 114f : w;

        if(instrumentation != null && instrumentation.size > 0){
            indexes = new int[instrumentation.size];
            prevIndex = new int[instrumentation.size];
            Arrays.fill(prevIndex, -1);
            sort();

            cont.table(t -> {
                t.defaults().size(40f).pad(5f);
                // 启停开关：同步跟随统计状态（停机时也会自己灭掉）
                ImageButton active = t.button(Icon.chartBar, Styles.clearTogglei, this::startStop)
                    .checked(instrumentation.profiling).get();
                active.update(() -> active.setChecked(instrumentation.profiling));
                t.button(Icon.edit, Styles.cleari, this::editCommands);
                t.image().growY().width(4f).pad(6f).color(Pal.gray);
                t.button(ProfilerIcons.time, Styles.clearTogglei, () -> setup(execTime = !execTime)).checked(execTime);
                t.button(ProfilerIcons.sortDesc, Styles.clearTogglei, () -> setup(sorted = !sorted)).checked(sorted);
                // 百分比与分支列都是「怎么显示已有的数字」：不重建控件，update 里现读开关
                t.button(ProfilerIcons.percent, Styles.clearTogglei, () -> percents = !percents).checked(percents);
                t.button(ProfilerIcons.branching, Styles.clearTogglei, () -> setup(branches = !branches)).checked(branches);
                t.button(ProfilerIcons.sum, Styles.clearTogglei, () -> setup(totals = !totals)).checked(totals);
                t.button(Icon.tag, Styles.clearTogglei, () -> setup(colors = !colors)).checked(colors);
                t.button(Icon.infoCircle, Styles.cleari, this::help);
            }).top().growX().fillX().row();

            cont.table(main -> {
                main.pane(p -> {
                    p.table(t -> {
                        t.defaults().fillX().height(35f).padRight(4f).padTop(4f);

                        for(int i = 0; i < instrumentation.size; i++){
                            int ind = i;

                            Image numImage = new Image(Tex.whiteui, colors ? instrumentation.colors[indexes[ind]] : basicColor);
                            Label numLabel = new Label(String.valueOf(indexes[ind]));
                            t.stack(numImage, new Table(l -> {
                                l.add(numLabel).color(Color.white).padLeft(labelPad).padRight(labelPad);
                            })).minWidth(65f).width(65f);

                            ProgressBackground ratio = new ProgressBackground(barColor, fillColor);
                            Label source = new Label(VarsDialog.escape(instrumentation.source[indexes[ind]]));
                            // 旧版 arc（Neon 聚合构建的编译类路径）没有 Cell.wrap(boolean)，
                            // Label.setWrap 两代都有（与 VarsDialog.noWrapLabel 同一约定）
                            source.setWrap(false);
                            t.stack(ratio, new Table(l -> {
                                l.add(source).color(Color.lightGray)
                                        .minWidth(0f).left().growX().fillX().padLeft(10f).padRight(10f)
                                        .ellipsis(true).get().setAlignment(Align.left);
                            })).minWidth(iWidth).width(iWidth).growX().fillX();

                            // 分支列只在开关打开时建（与 iWidth 的扣减对应）；建成后百分比/次数
                            // 都在同一个 update 里切换，不再整页重建
                            Label branchLabel = new Label("");
                            if(branches){
                                t.stack(new Image(Tex.whiteui, basicColor), new Table(l -> {
                                    l.add(branchLabel).color(Pal.accent).padLeft(labelPad).padRight(labelPad);
                                })).minWidth(110f).width(110f);
                            }

                            Label countLabel = new Label("");
                            countLabel.update(() -> {
                                int index = indexes[ind];

                                if(prevIndex[ind] != index){
                                    prevIndex[ind] = index;
                                    numImage.setColor(colors ? instrumentation.colors[index] : basicColor);
                                    numLabel.setText(String.valueOf(index));
                                    source.setText(VarsDialog.escape(instrumentation.source[index]));
                                }

                                boolean covered = instrumentation.isCovered(index);
                                source.setColor(covered ? Color.white : Color.lightGray);

                                if(branches){
                                    branchLabel.setText(instrumentation.branching[index] < 0 ? "" : percents
                                        ? formatPercent(instrumentation.branching[index], instrumentation.steps[index], "%.1f%%")
                                        : formatNumber(instrumentation.branching[index]));
                                }

                                if(execTime){
                                    countLabel.setText(percents
                                        ? formatPercent(instrumentation.time[index], instrumentation.counters.totalTime, "%.2f%%")
                                        : formatNumber(instrumentation.time[index]));
                                    ratio.progress = (float)(instrumentation.time[index] / instrumentation.counters.maxTime);
                                }else{
                                    countLabel.setText(percents
                                        ? formatPercent(instrumentation.steps[index], instrumentation.counters.totalSteps, "%.2f%%")
                                        : formatNumber(instrumentation.steps[index]));
                                    ratio.progress = instrumentation.steps[index] / (float)instrumentation.counters.maxSteps;
                                }
                                ratio.fillColor = covered ? fillColor : emptyColor;
                            });
                            t.stack(new Image(Tex.whiteui, basicColor), new Table(l -> {
                                l.add(countLabel).color(Pal.accent).padLeft(labelPad).padRight(labelPad);
                            })).minWidth(110f).width(110f).padRight(0);

                            t.row();
                        }
                    }).growY().top().marginRight(15f);
                }).scrollX(false).update(s -> scroll = s.getScrollY()).get().setScrollYForce(scroll);

                if(totals){
                    main.row();
                    main.table(t -> {
                        String[] titles = {
                                execTime ? tr("logicsugar.profile.totals.quota", "Total execution quota spent")
                                         : tr("logicsugar.profile.totals.steps", "Total instructions executed"),
                                tr("logicsugar.profile.totals.lost", "Execution quota lost to yields"),
                                tr("logicsugar.profile.totals.coverage", "Code coverage ({0} instructions in total)", instrumentation.size)
                        };
                        // 丢配额与覆盖率按当前视图显示百分比或原始值（上游 v0.11.4）
                        Prov<CharSequence> quotaOrSteps = execTime
                                ? () -> formatNumber(instrumentation.counters.totalTime)
                                : () -> formatNumber(instrumentation.counters.totalSteps);
                        List<Prov<CharSequence>> values = Arrays.asList(
                                quotaOrSteps,
                                () -> percents
                                        ? formatPercent(instrumentation.counters.lostQuota, instrumentation.counters.totalTime, "%.1f%%")
                                        : formatNumber(instrumentation.counters.lostQuota),
                                () -> percents
                                        ? formatPercent(instrumentation.counters.coverage, instrumentation.size, "%.1f%%")
                                        : String.valueOf(instrumentation.counters.coverage)
                        );

                        t.defaults().fillX().height(35f).padRight(4f).padTop(4f);

                        for(int i = 0; i < titles.length; i++){
                            int index = i;
                            t.image(Tex.whiteui, basicColor).minWidth(65f).width(65f);

                            Label title = new Label(titles[index]);
                            title.setWrap(false);
                            t.stack(new Image(Tex.whiteui, basicColor), new Table(l -> {
                                l.add(title).color(Pal.accent)
                                        .minWidth(0f).left().growX().fillX().padLeft(10f).padRight(10f)
                                        .ellipsis(true).get().setAlignment(Align.left);
                            })).minWidth(w).width(w).growX().fillX();

                            Label countLabel = new Label(values.get(index));
                            t.stack(new Image(Tex.whiteui, basicColor), new Table(l -> {
                                l.add(countLabel).color(Pal.accent).padLeft(labelPad).padRight(labelPad);
                            })).minWidth(110f).width(110f).padRight(0);
                            t.row();
                        }
                    }).left();
                }
            });

            // 排序刷新：每 500ms 看一次相邻行的差值，超阈值只重排行序（不重建页面）
            cont.update(() -> {
                if(!sorted || instrumentation == null) return;
                if(lastCheck >= System.currentTimeMillis() - 500) return;

                lastCheck = System.currentTimeMillis();
                int diff = System.currentTimeMillis() - lastSort > 1500 ? 1 : 5;

                for(int i = 1; i < indexes.length; i++){
                    double current = execTime
                            ? instrumentation.time[indexes[i]] - instrumentation.time[indexes[i - 1]]
                            : instrumentation.steps[indexes[i]] - instrumentation.steps[indexes[i - 1]];
                    if(current > diff){
                        Core.app.post(this::sort);
                        break;
                    }
                }
            });
        }else{
            cont.table(t -> {
                Label intro = new Label(tr("logicsugar.profile.intro",
                        "Profiler records the number of times each instruction executes. Once activated, it remains active even after leaving this screen, until stopped.\n\n"
                            + "If the processor's code gets updated, the profiler remains active, but the data gathered so far are cleared."));
                // 旧版 arc 的 Cell.wrap(boolean) 不存在（见 VarsDialog.noWrapLabel 的注释）
                intro.setWrap(true);
                t.add(intro).color(Color.lightGray).width(410f).minWidth(410f).padBottom(30f);
                t.row();
                t.check(tr("logicsugar.profile.dontshow", "Do not show again"),
                        b -> Core.settings.put(LogicSugarSettings.settingStartProfilerImmediately, b))
                        .padBottom(30f).growX().fillX();
                t.row();
                t.button(tr("logicsugar.profile.start", "Start profiling"), Icon.play, Styles.flatBordert,
                        () -> setup(InstrumentationEngine.startProfiling(build)))
                        .height(64f).growX().fillX();
            }).left();
        }
        addCloseButton();
    }

    private String formatNumber(int number){
        return number == 0 ? "-" : String.valueOf(number);
    }

    private String formatNumber(double number){
        return formatNumber((int)number);
    }

    /** 占比文本：0/无总数显示 {@code -}，满值直接写 {@code 100%}；由于格式化后可能显示成
     *  {@code 100.0%}（其实没到 100），那一档按上游改写成 {@code 99.9%}，避免误读。 */
    private String formatPercent(double part, double total, String format){
        if(total <= 0 || part <= 0) return "-";
        if(part >= total) return "100%";
        String result = String.format(format, 100d * part / total);
        return result.startsWith("100.") ? result.substring(1).replace('0', '9') : result;
    }

    /** 重载程序并从头统计（清数据 + 重新包装，profiling 保持开启）。 */
    private void restart(){
        build.updateCode(build.code);
        InstrumentationEngine.clearProfilingData(build);
        instrumentation = InstrumentationEngine.startProfiling(build);
        setup();
    }

    /** 剪贴板导出：制表符分隔的四列（序号 / 源码 / 执行次数 / 消耗配额），带表头。 */
    private void copyToClipboard(){
        StringBuilder sb = new StringBuilder(200 * instrumentation.size);
        sb.append("#")
                .append("\t").append("Instruction")
                .append("\t").append("Execution steps")
                .append("\t").append("Execution quota")
                .append("\n");

        for(int i = 0; i < instrumentation.size; i++){
            sb.append(i)
                    .append("\t").append(instrumentation.source[i])
                    .append("\t").append(instrumentation.steps[i])
                    .append("\t").append(instrumentation.time[i])
                    .append("\n");
        }
        Core.app.setClipboardText(sb.toString());
    }

    /** Edit 菜单（上游 v0.11.4 把刷新/复制/清空三个按钮收进这里）。 */
    private void editCommands(){
        BaseDialog dialog = new BaseDialog(tr("@edit", "Edit"));
        dialog.cont.pane(p -> {
            p.margin(10f);
            p.table(Tex.button, t -> {
                TextButton.TextButtonStyle style = Styles.flatt;
                t.defaults().size(360f, 60f).left();

                t.button(tr("logicsugar.profile.edit.reset", "Reset data"), Icon.cancel, style, () -> {
                    setup(InstrumentationEngine.clearProfilingData(build));
                    dialog.hide();
                }).marginLeft(12f).row();

                t.button(tr("logicsugar.profile.edit.restart", "Reset data and restart processor"), Icon.refresh, style, () -> {
                    restart();
                    dialog.hide();
                }).marginLeft(12f).row();

                t.button(tr("logicsugar.profile.edit.copy", "Copy profiling data\nto clipboard"), Icon.copy, style, () -> {
                    copyToClipboard();
                    dialog.hide();
                }).marginLeft(12f).row();

                t.button(tr("@back", "Back"), Icon.left, style, dialog::hide).padTop(10f).marginLeft(12f).name("back");
            });
        });

        dialog.addCloseListener();
        dialog.show();
    }

    private void help(Table t, TextureRegionDrawable icon, String text){
        t.image(icon).color(Color.lightGray).size(32f, 32f);
        t.add(text).color(Color.lightGray).width(350f).minWidth(0f).wrap().row();
    }

    private void help(){
        BaseDialog dialog = new BaseDialog(tr("logicsugar.profile.help.title", "Help"));
        dialog.titleTable.visible(() -> false).setHeight(0f);
        dialog.cont.pane(p -> {
            p.table(Tex.button, t -> {
                TextButton.TextButtonStyle style = Styles.squareTogglet;
                t.defaults().fillX().pad(6f, 15f, 6f, 15f).left();

                t.add(tr("logicsugar.profile.help.commands", "Available commands")).colspan(3).color(Pal.accent).center().padBottom(10f).get().setAlignment(Align.center);
                t.row();

                help(t, Icon.chartBar, tr("logicsugar.profile.help.startstop", "Start or stop profiling the current processor."));
                help(t, Icon.edit, tr("logicsugar.profile.help.edit", "Reset, restart or copy profiling data."));
                help(t, ProfilerIcons.time, tr("logicsugar.profile.help.time", "Display execution quota spent by instructions instead of execution steps (the [accent]wait[] instruction may spend lots of execution quota waiting)."));
                help(t, ProfilerIcons.sortDesc, tr("logicsugar.profile.help.sort", "Sort the instructions by execution steps/quota."));
                help(t, ProfilerIcons.percent, tr("logicsugar.profile.help.percent", "Display percentages instead of raw values."));
                help(t, ProfilerIcons.branching, tr("logicsugar.profile.help.branching", "Show the number of jumps made by jump instructions."));
                help(t, ProfilerIcons.sum, tr("logicsugar.profile.help.totals", "Show profiling totals."));
                help(t, Icon.tag, tr("logicsugar.profile.help.colors", "Use the instruction's category color in the list."));
                help(t, Icon.infoCircle, tr("logicsugar.profile.help.help", "Show this help."));

                t.add(tr("logicsugar.profile.help.editcommands", "Edit commands")).colspan(3).color(Pal.accent).center().padBottom(15f).get().setAlignment(Align.center);
                t.row();

                help(t, Icon.cancel, tr("logicsugar.profile.help.clear", "Clear the current processor's profiling data."));
                help(t, Icon.refresh, tr("logicsugar.profile.help.restart", "Restart the current processor and activate profiling from the beginning (existing profiling data are cleared)."));
                help(t, Icon.copy, tr("logicsugar.profile.help.copy", "Copy the profiling data into the clipboard in a tab-separated format (instruction #, instruction text, execution steps and quota)."));

                t.defaults().size(180f, 60f).growX().colspan(3).pad(15f);
                t.button(tr("@back", "Back"), Icon.left, Styles.defaultt, dialog::hide).center().marginLeft(12f).name("back");
            }).pad(10f).padRight(30f);
        });

        dialog.addCloseListener();
        dialog.show();
    }

    private static String tr(String key, String fallback, Object... args){
        return L10n.text(key, fallback, args);
    }

    /** 行背景：以 {@code progress} 为比例的两段色条（0..1）。未访问的行整行用 {@code fillColor}。 */
    private static class ProgressBackground extends Element{
        public float progress = 0f;
        public final Color barColor;
        public Color fillColor;

        public ProgressBackground(Color barColor, Color fillColor){
            touchable = Touchable.disabled;
            this.barColor = barColor;
            this.fillColor = fillColor;
        }

        @Override
        public void draw(){
            float barWidth = getWidth() * Mathf.clamp(progress);

            Draw.color(fillColor);
            Fill.rect(x + width / 2f, y + height / 2f, width, height);

            Draw.color(barColor);
            Fill.rect(x + barWidth / 2f, y + height / 2f, barWidth, height);

            Draw.reset();
        }
    }
}
