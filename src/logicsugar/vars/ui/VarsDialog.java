package logicsugar.vars.ui;

import arc.Core;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Button;
import arc.scene.ui.ButtonGroup;
import arc.scene.ui.Image;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Label;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import arc.util.Align;
import arc.util.Nullable;
import arc.util.Time;
import logicsugar.assist.L10n;
import logicsugar.vars.BlockDataType;
import logicsugar.vars.MemoryText;
import logicsugar.vars.Snapshot;
import logicsugar.vars.Snapshots;
import logicsugar.vars.ValueType;
import logicsugar.vars.VariableValues;
import logicsugar.vars.VarsOptions;
import mindustry.Vars;
import mindustry.core.GameState;
import mindustry.gen.Building;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.logic.SugarCanvas;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;

import java.util.Arrays;

/**
 * 变量 / 内存 / 属性视图对话框：列出某一时刻的变量表，可以前后翻看该建筑的历史快照，
 * 也可以还原快照、创建快照、清理内存，以及微调显示方式。
 *
 * <p>Ported from upstream MlogAssertions v0.11.1
 * ({@code cardillan.mlogassertions.ui.VarsDialog})，布局、按钮顺序、禁用规则与快捷键
 * 全部逐字保留。与上游的差异只有下面四处，都是集成约定而不是行为改动：</p>
 *
 * <ul>
 * <li>会话级显示偏好（{@code hex}/{@code sorted}/{@code filtered}/{@code hideLinks}/
 * {@code fullPrecision}/{@code significantDigits}/{@code alignment}/{@code updateFrequency}）
 * 改读写 {@link VarsOptions}。上游把它们放在本类的静态字段里，现在由 LogicSugar 的设置项
 * 写入（本类不再声明同名副本）。</li>
 * <li>编辑器里的「内置变量」按钮改为调用集成阶段注入的 {@link #globalsOpener}：上游直接调用
 * 未移植的 {@code LogicDialogAddon.globalsDialog.show()}，LogicSugar 的全局变量对话框由
 * 集成代码自己接线，本包不引用 {@code mindustry.logic.LogicDialog}。opener 未注入时不显示
 * 该按钮（否则会留下一个点了没反应的按钮）；显示位置与上游一致——只有处理器视图才有。</li>
 * <li>用户可见文本全部走 {@code logicsugar.vars.*} bundle 键 + 英文 fallback
 * （{@link L10n#text}），上传里写死的英文与 {@code varsdialog.*}/{@code setting.*}
 * 键的英文原文都作为 fallback 保留，bundle 键由集成阶段补齐。</li>
 * <li>{@code LCanvas.isCompact()} / {@code LCanvas.getTargetWidth()} 是上游目标版本 v154 的
 * 静态方法，本仓库的 v160 已把前者改名 {@code useRows()}、把后者收回实例字段
 * {@code targetWidth}，所以这里复用 LogicSugar 已有的兼容入口
 * {@link SugarCanvas#compactStatementLayout()}，并按同一构建里 {@code LCanvas.rebuild()} 的
 * 公式重算目标宽度。</li>
 * </ul>
 *
 * <p>跨加载器注意：本类只使用游戏类的 public API（{@code Vars.ui/state/mobile/iconLarge}、
 * {@code Styles}/{@code Tex}/{@code Icon}），以及 {@link EllipsisLabel} 这个自建子类；
 * 对话框里的 {@code VarsDialog.this} 与 {@link SnapshotList} 都是本 mod 的类型。</p>
 */
public class VarsDialog extends BaseDialog{
    private static final float reset = 1e10f;
    private static final int live = 0;

    private static int lastSnapshotId = -1;

    /** 打开编辑器「内置变量」对话框的入口，由集成代码注入（见类注释）。
     *  为 null 时 {@link #setup()} 不显示那个按钮。 */
    public static @Nullable Runnable globalsOpener;

    private Building building;

    // A list of snapshots that can be browsed through
    private SnapshotList snapshots;

    private Object[] lastObject;
    private double[] lastMemory;
    private float[] counter;
    private boolean[] updated;
    private int length;

    boolean wasPortrait, compact, paused;
    int rows, cols;

    public VarsDialog(Building building){
        this(SnapshotList.forBuild(building));
    }

    private VarsDialog(SnapshotList snapshots){
        super(snapshots.title());
        this.snapshots = snapshots;

        onResize(() -> {
            if(cols != cols() || wasPortrait != Core.graphics.isPortrait() || compact != compact()){
                setup();
            }else{
                rebuildTitle(titleTable);
            }
        });

        addListener(new InputListener(){
            @Override
            public boolean keyDown(InputEvent event, KeyCode keycode){
                switch(keycode){
                    case pageUp, left -> prev();
                    case pageDown, right -> next();
                    case home -> first();
                    case end -> last();
                    default -> {
                        return false;
                    }
                }
                return true;
            }
        });

        setup();
    }

    public void setup(SnapshotList snapshotList){
        this.snapshots = snapshotList;
        setup();
    }

    public void setup(boolean updated){
        if(updated){
            setup();
        }
    }

    private int cols(){
        return Math.max(1, (int)(Core.graphics.getWidth() / Scl.scl(snapshots.view().dataType().maxColWidth)));
    }

    private void prev(){
        setup(snapshots.prev());
    }

    private void next(){
        setup(snapshots.next());
    }

    private void first(){
        setup(snapshots.first());
    }

    private void last(){
        setup(snapshots.last());
    }

    private void createSnapshot(){
        Snapshots.create(snapshots.view().building(), tr("logicsugar.vars.usersnapshot", "User snapshot"));
        rebuildTitle(titleTable);
    }

    private void restoreSnapshot(){
        // TODO Group restore
        if(snapshots.view() instanceof Snapshot snapshot){
            if(snapshot.writeTo(snapshots.liveData())){
                Vars.ui.showInfo(tr("logicsugar.vars.restored", "The processor's state has been restored from the snapshot."));
                setup(SnapshotList.forBuild(snapshot.building()));
                return;
            }
        }
        Vars.ui.showErrorMessage(tr("logicsugar.vars.restorefailed", "Cannot restore this snapshot: either the snapshot is invalid, or the processor's code has been recompiled."));
    }

    private void removeSnapshot(){
        setup(snapshots.remove());
    }

    private Table titleTable;
    private void rebuildTitle(Table titleTable){
        if(Snapshots.maxSnapshots == 0) return;
        compact = compact();

        VariableValues view = snapshots.view();

        this.titleTable = titleTable;

        // Snapshot is null for live view
        Snapshot snapshot = view instanceof Snapshot s ? s : null;
        cont.align(snapshots.group() ? Align.top : Align.center);

        // Snapshot navigation
        titleTable.clear();
        titleTable.table(t -> {
            // Previous
            if(!compact){
                t.button(Icon.left, Styles.defaulti, this::prev).size(48f, 64f).pad(5f).disabled(!snapshots.hasPrev());
            }

            if(snapshots.group()){
                t.image(view.building().block.uiIcon).size(64f).pad(5f);

                t.table(left -> {
                    noWrapLabel(left, view.buildingDescMulti()).growX().top().left();
                }).minWidth(0f).pad(5f).padLeft(10f).top().growX();

                t.table(right -> {
                    right.add(snapshots.pos()).color(Pal.accent).top().right().growX().get().setAlignment(Align.right);
                    right.row();
                    right.add(snapshot.time()).color(Color.gray).top().right().growX().get().setAlignment(Align.right);
                }).right().minWidth(0f).pad(5f).padLeft(10f).top().growX();
            }else{
                t.table(title -> {
                    title.table(tBlock -> {
                        tBlock.image(view.building().block.uiIcon).size(Vars.iconLarge).padRight(5f);
                        tBlock.table(text -> {
                            noWrapLabel(text, view.buildingDesc()).color(Color.white).growX().get().setAlignment(Align.left);
                            text.row();
                            text.table(tProperties -> {
                                noWrapLabel(tProperties, view.buildingPos()).color(Color.gray).growX().get().setAlignment(Align.left);
                                if(snapshot != null){
                                    tProperties.add(snapshot.time()).color(Color.gray).growX().get().setAlignment(Align.right);
                                }
                            }).top().growX();
                        }).top().growX();
                    }).top().growX();
                    title.row();

                    title.table(tSnapshot -> {
                        if(view.live()){
                            tSnapshot.add(tr("logicsugar.vars.live", "Live")).color(Pal.accent).top().growX().get().setAlignment(Align.left);
                        }else{
                            noWrapLabel(tSnapshot, tr("logicsugar.vars.snapshot.label", "#{0}: {1} {2}",
                                            snapshot.id(), snapshot.type().charIcon, snapshot.name()))
                                    .color(Pal.accent).growX().get().setAlignment(Align.left);

                            Label l = tSnapshot.add(snapshots.pos()).color(Pal.accent).growX().padLeft(10f).get();
                            l.setAlignment(Align.right);
                            l.update(() -> l.setText(snapshots.pos()));
                        }
                    }).top().growX();
                    title.row();
                }).growX().minWidth(0f).pad(5f);
            }

            // Next snapshot
            if(!compact){
                t.button(Icon.right, Styles.defaulti, this::next).size(48f, 64f).pad(5f).get().setDisabled(() -> !snapshots.hasNext());
            }
        }).growX().fillX().row();

        // View/snapshot commands
        titleTable.table(t -> {
            t.defaults().size(40f).pad(5f);

            ImageButton.ImageButtonStyle style = Styles.cleari;

            if(compact){
                t.button(Icon.left, style, this::prev).padRight(15f).disabled(!snapshots.hasPrev());
            }

            t.button(Icon.filters, style, this::viewOptions);
            t.button(Icon.edit, style, this::editCommands);

            // Play/pause the game
            Image icon = new Image(Vars.state.isPlaying() ? Icon.pause : Icon.play);
            Button b = new Button();
            b.add(icon).size(64f);
            b.setStyle(style);
            b.clicked(() -> {
                if(Vars.state.isPlaying()){
                    paused = true;
                    Vars.state.set(GameState.State.paused);
                    icon.setDrawable(Icon.play);
                }else{
                    paused = false;
                    Vars.state.set(GameState.State.playing);
                    icon.setDrawable(Icon.pause);
                }
            });
            t.add(b);

            t.image().growY().width(4f).pad(6f).color(Pal.gray);


            // Select a snapshot from the current block's list of snapshots
            t.button(Icon.folderOpen, style,
                            () -> new SnapshotsDialog(VarsDialog.this, snapshots.group() ? SnapshotList.forBuild(building) : snapshots).show())
                    .get().setDisabled(() -> !Snapshots.hasSnapshots(building));

            // Select a snapshot from a group snapshot
            t.button(Icon.logic, style,
                            () -> new SnapshotsDialog(VarsDialog.this, snapshots.group() ? snapshots : SnapshotList.list(snapshot.group())).show())
                    .disabled(snapshot == null || snapshot.group() == null);

            // Create a snapshot
            t.button(Icon.box, style, this::createSnapshot).disabled(snapshot != null);

            if(!compact){
                t.button(Icon.download, style, this::restoreSnapshot).disabled(snapshot == null || snapshot.dataType() == BlockDataType.properties);
                t.button(Icon.trash, style, this::removeSnapshot).disabled(!snapshots.canRemove());
            }

            t.button(Icon.infoCircle, style, this::help);

            if(compact){
                t.button(Icon.right, style, this::next).padLeft(15f).get().setDisabled(() -> !snapshots.hasNext());
            }
        }).growX().fillX();

        titleTable.addListener(new InputListener(){
            private float startX;
            private float startY;
            private boolean dragging;

            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                startX = x;
                startY = y;
                dragging = true;
                return true;
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
                if(!dragging) return;
                dragging = false;

                float dx = x - startX;
                float dy = y - startY;

                // Only treat predominantly horizontal movement as a swipe.
                if(Math.abs(dx) < 80f || Math.abs(dx) < 1.5f * Math.abs(dy)) return;

                if(dx < 0){
                    next();
                }else{
                    prev();
                }
            }
        });
    }

    private void setup(){
        title.setText(snapshots.title());

        // Current view
        VariableValues view = snapshots.view();
        view.setView(false, false, false);
        lastSnapshotId = view instanceof Snapshot s ? s.id() : -1;
        building = view.building();

        length = view.size();
        counter = new float[length];
        lastObject = new Object[length];
        lastMemory = new double[length];
        updated = new boolean[length];

        view.setView(VarsOptions.sorted, VarsOptions.filtered, VarsOptions.hideLinks);
        Arrays.fill(counter, reset);

        buttons.clear();
        cont.clear();

        if(Snapshots.maxSnapshots > 0){
            cont.table(this::rebuildTitle).width(Math.min(700f, targetWidth())).fillX();
            cont.row();
        }

        cont.pane(p -> {
            p.margin(10f).marginTop(0f);
            p.table(Tex.button, t -> {
                t.defaults().fillX().height(45f);
                length = view.size();
                cols = cols();
                rows = (length + cols - 1) / cols;

                for(int row = 0; row < rows; row++){
                    for(int col = 0; col < cols; col++){
                        int index = col * rows + row;
                        if(index >= length) break;

                        Color varColor = Pal.gray;
                        float stub = 8f, mul = 0.5f, pad = 4;

                        t.add(new Image(Tex.whiteui, varColor.cpy().mul(mul))).width(stub);
                        t.stack(new Image(Tex.whiteui, varColor), new Label(() -> view.label(index, VarsOptions.hex)){{
                            setAlignment(Align.center);
                            setColor(Pal.accent);
                            setStyle(Styles.outlineLabel);
                        }}).padRight(pad);

                        t.add(new Image(Tex.whiteui, Pal.gray.cpy().mul(mul))).width(stub);
                        EllipsisLabel valueLabel = new EllipsisLabel("");
                        valueLabel.act(1f);
                        valueLabel.maxLines(2);
                        Cell<Table> val = t.table(Tex.pane, out -> out.add(valueLabel).style(Styles.outlineLabel)
                                    .padLeft(4).padRight(4).width(compact ? 140f : 220f)
                        ).padRight(pad);

                        ValueType valueType = view.type(index);
                        Image halfShade = t.add(new Image(Tex.whiteui, valueType.darkShade)).width(stub).get();
                        Image fullShade = new Image(Tex.whiteui, valueType.shade);
                        Label typeLabel = new Label("");
                        typeLabel.setAlignment(Align.center);
                        typeLabel.setStyle(Styles.outlineLabel);
                        t.stack(fullShade, typeLabel).minWidth(120f).get();

                        valueLabel.update(() -> {
                            if((counter[index] += Time.delta) >= VarsOptions.updateFrequency){
                                Object objval = view.obj(index);
                                double numval = view.num(index);
                                if(counter[index] >= reset || objval != lastObject[index] || numval != lastMemory[index]){
                                    lastObject[index] = objval;
                                    lastMemory[index] = numval;

                                    String text = view.formatted(index, VarsOptions.hex, VarsOptions.fullPrecision ? 16 : VarsOptions.significantDigits);
                                    ValueType type = view.type(index);
                                    valueLabel.setText(text);
                                    valueLabel.setAlignment(VarsOptions.alignment);
                                    typeLabel.setText(type.paddedTitle);

                                    halfShade.setColor(type.darkShade);
                                    fullShade.setColor(type.shade);

                                    updated[index] = counter[index] < reset;
                                    valueLabel.setColor(updated[index] ? Pal.accent : Color.white);
                                }else if(!paused){
                                    updated[index] = false;
                                    valueLabel.setColor(Color.white);
                                }
                                counter[index] = 0f;
                            }
                        });
                    }
                    t.row();
                    t.add().growX().colspan(6).height(4).row();
                }
            });
        });

        // Dialog buttons
        buttons.defaults().size(200f, 64f);

        if(Snapshots.maxSnapshots == 0){
            // No snapshots: no commands above the list
            if(snapshots.view().dataType() == BlockDataType.processor){
                buttons.button(tr("@back", "Back"), Icon.left, this::hide).name("back");
                globalsButton(buttons);
                if(Core.graphics.isPortrait()) buttons.row();
                buttons.button(tr("logicsugar.vars.options", "Options"), Icon.filters, this::viewOptions).name("options");
                buttons.button(tr("@edit", "Edit"), Icon.edit, () -> editCommands()).name("edit");
            }else{
                buttons.button(tr("@back", "Back"), Icon.left, this::hide).name("back");
                buttons.button(tr("@edit", "Edit"), Icon.edit, () -> editCommands()).name("edit");
                if(Core.graphics.isPortrait()) buttons.row();
                buttons.button(tr("logicsugar.vars.hex", "Hex"), Styles.squareTogglet, () -> refreshView(VarsOptions.hex = !VarsOptions.hex)).name("hex").checked(VarsOptions.hex);
                if(VarsOptions.significantDigits < 16){
                    buttons.button(tr("logicsugar.vars.fullprecision", "All digits"), Styles.squareTogglet, () -> refreshView(VarsOptions.fullPrecision = !VarsOptions.fullPrecision))
                            .name("fullprecision").checked(VarsOptions.fullPrecision);
                }
            }
        }else{
            // Snapshots are enabled: most commands are displayed above the list
            buttons.button(tr("@back", "Back"), Icon.left, this::hide).name("back");
            if(snapshots.view().dataType() == BlockDataType.processor){
                globalsButton(buttons);
            }
        }

        wasPortrait = Core.graphics.isPortrait();
        addCloseListener();
    }

    /** 上游直接调用 {@code LogicDialogAddon.globalsDialog.show()}；这里只调用集成阶段注入的
     *  opener（未注入时不显示按钮）。显示条件与上游相同：只有处理器视图才有这个按钮。 */
    private void globalsButton(Table buttons){
        if(globalsOpener == null) return;
        buttons.button(tr("@logic.globals", "Built-in Variables"), Icon.list, globalsOpener);
    }

    private void help(Table t, TextureRegionDrawable icon, String text){
        help(t, icon, null, text);
    }

    private void help(Table t, TextureRegionDrawable icon1, TextureRegionDrawable icon2, String text){
        if(icon2 == null){
            t.image(icon1).colspan(2).color(Color.lightGray);
        }else{
            t.image(icon1).color(Color.lightGray).padRight(4f);
            t.image(icon2).color(Color.lightGray).padLeft(4f);
        }

        t.add(text).color(Color.lightGray).width(350f).minWidth(0f).wrap().row();
    }

    private void help(){
        BaseDialog dialog = new BaseDialog(tr("logicsugar.vars.help", "Help"));
        dialog.titleTable.visible(() -> false).setHeight(0f);
        dialog.cont.pane(p -> {
            p.table(Tex.button, t -> {
                TextButton.TextButtonStyle style = Styles.squareTogglet;
                t.defaults().fillX().pad(6f, 15f, 6f, 15f).left();

                t.add(tr("logicsugar.vars.help.commands", "Available commands")).colspan(3).color(Pal.accent).center().padBottom(10f).get().setAlignment(Align.center);
                t.row();

                help(t, Icon.left, Icon.right, Vars.mobile
                        ? tr("logicsugar.vars.help.navigate.swipe", "Navigate to the previous/next snapshot (or swipe the header left/right).")
                        : tr("logicsugar.vars.help.navigate.keys", "Navigate to the previous/next snapshot (also the PgUp/PgDn and Home/End keys)."));
                help(t, Icon.filters, tr("logicsugar.vars.help.filters", "Customize the view (for the duration of the session)."));
                help(t, Icon.edit, tr("logicsugar.vars.help.edit", "Export, import or modify the data of this block."));
                help(t, Icon.pause, Icon.play, tr("logicsugar.vars.help.pause", "Pause/resume the game."));
                help(t, Icon.folderOpen, tr("logicsugar.vars.help.folder", "Show a list of this block's snapshots."));
                help(t, Icon.logic, tr("logicsugar.vars.help.logic", "Navigate to a different block contained in this snapshot."));
                help(t, Icon.box, tr("logicsugar.vars.help.snapshot", "Create a new snapshot of this block and all connected blocks."));
                if(!compact){
                    help(t, Icon.download, tr("logicsugar.vars.help.restore", "Restore the current processor or memory block's state from a snapshot."));
                    help(t, Icon.trash, tr("logicsugar.vars.help.delete", "Delete the current snapshot."));
                }
                help(t, Icon.infoCircle, tr("logicsugar.vars.help.help", "Show this help."));

                t.add(tr("logicsugar.vars.help.editcommands", "Edit commands")).colspan(3).color(Pal.accent).center().padBottom(15F).get().setAlignment(Align.center);
                t.row();

                help(t, Icon.cancel, tr("logicsugar.vars.help.clearmemory", "Reset memory block to all zeroes."));
                help(t, Icon.copy, tr("logicsugar.vars.help.copyvariables", "Copy variable values to Clipboard."));
                help(t, Icon.download, tr("logicsugar.vars.help.importvariables", "Import memory block values from Clipboard."));
                if(compact){
                    help(t, Icon.download, tr("logicsugar.vars.help.restore", "Restore the current processor or memory block's state from a snapshot."));
                    help(t, Icon.trash, tr("logicsugar.vars.help.delete", "Delete the current snapshot."));
                }
                help(t, Icon.trash, tr("logicsugar.vars.help.deleteall", "Delete all snapshots of this block (they may still be accessible as part of connected or global snapshots)."));

                t.defaults().size(180f, 60f).growX().colspan(3).pad(15f);
                t.button(tr("@back", "Back"), Icon.left, Styles.flatt, dialog::hide).center().marginLeft(12f).name("back");
            }).pad(10f).padRight(30f);
        });

        dialog.addCloseListener();
        dialog.show();
    }

    private void viewOptions(){
        BaseDialog dialog = new BaseDialog(tr("logicsugar.vars.options", "Options"));
        dialog.titleTable.visible(() -> false);
        dialog.cont.pane(p -> {
            p.margin(10f);
            p.table(Tex.button, t -> {
                TextButton.TextButtonStyle style = Styles.squareTogglet;
                t.defaults().height(45f).growX().fillX().uniformX().colspan(3).pad(3f).left();

                t.add(tr("logicsugar.vars.viewcustomization", "View customization")).colspan(6).color(Pal.accent).center().padBottom(10f).get().setAlignment(Align.center);
                t.row();

                ButtonGroup<TextButton> hexGroup = new ButtonGroup<>();
                t.button(tr("logicsugar.vars.dec", "Dec"), style, () -> refreshView(VarsOptions.hex = false)).name("dec").group(hexGroup).checked(!VarsOptions.hex);
                t.button(tr("logicsugar.vars.hex", "Hex"), style, () -> refreshView(VarsOptions.hex = true)).name("hex").group(hexGroup).checked(VarsOptions.hex);
                t.row();
                ButtonGroup<TextButton> sortedGroup = new ButtonGroup<>();
                t.button(tr("logicsugar.vars.sorted", "Sorted"), style, () -> updateView(VarsOptions.sorted = true)).name("sorted").group(sortedGroup).checked(VarsOptions.sorted);
                t.button(tr("logicsugar.vars.unsorted", "Unsorted"), style, () -> updateView(VarsOptions.sorted = false)).name("unsorted").group(sortedGroup).checked(!VarsOptions.sorted);
                t.row();
                ButtonGroup<TextButton> tempsGroup = new ButtonGroup<>();
                t.button(tr("logicsugar.vars.showall", "Show all"), style, () -> updateView(VarsOptions.filtered = false)).name("showall").group(tempsGroup).checked(!VarsOptions.filtered);
                t.button(tr("logicsugar.vars.hidetemps", "Hide temps"), style, () -> updateView(VarsOptions.filtered = true)).name("hidetemps").group(tempsGroup).checked(VarsOptions.filtered);
                t.row();
                ButtonGroup<TextButton> linksGroup = new ButtonGroup<>();
                t.button(tr("logicsugar.vars.showlinks", "Show links"), style, () -> updateView(VarsOptions.hideLinks = false)).name("showlinks").group(linksGroup).checked(!VarsOptions.hideLinks);
                t.button(tr("logicsugar.vars.hidelinks", "Hide links"), style, () -> updateView(VarsOptions.hideLinks = true)).name("hidelinks").group(linksGroup).checked(VarsOptions.hideLinks);
                t.row();
                if(VarsOptions.significantDigits < 16){
                    ButtonGroup<TextButton> precisionGroup = new ButtonGroup<>();
                    t.button(tr("logicsugar.vars.limitedprecision", "{0} digits", VarsOptions.significantDigits), style,
                            () -> updateView(VarsOptions.fullPrecision = false)).name("limitedprecission").group(precisionGroup).checked(!VarsOptions.fullPrecision);
                    // 上游这里也写着 name("hidelinks")（复制粘贴遗漏），保持一致以免改动 name 查找行为。
                    t.button(tr("logicsugar.vars.fullprecision", "All digits"), style, () -> updateView(VarsOptions.fullPrecision = true)).name("hidelinks").group(precisionGroup).checked(VarsOptions.fullPrecision);
                    t.row();
                }
                ButtonGroup<TextButton> alignmentGroup = new ButtonGroup<>();
                t.defaults().height(45f).growX().fillX().uniformX().colspan(2).pad(3f).left();
                t.button(tr("logicsugar.vars.align.left", "Left"), style, () -> updateView(VarsOptions.alignment = Align.left)).group(alignmentGroup).checked(VarsOptions.alignment == Align.left);
                t.button(tr("logicsugar.vars.align.center", "Center"), style, () -> updateView(VarsOptions.alignment = Align.center)).group(alignmentGroup).checked(VarsOptions.alignment == Align.center);
                t.button(tr("logicsugar.vars.align.right", "Right"), style, () -> updateView(VarsOptions.alignment = Align.right)).group(alignmentGroup).checked(VarsOptions.alignment == Align.right);
                t.row();

                t.defaults().height(60f).growX().fillX().uniformX().colspan(6).pad(15f);
                t.button(tr("@back", "Back"), Icon.left, Styles.flatt, dialog::hide).marginLeft(12f).name("back");
            }).width(350f).growX();
        });

        dialog.addCloseListener();
        dialog.show();
    }

    private void editCommands(){
        BaseDialog dialog = new BaseDialog(tr("@edit", "Edit"));
        dialog.cont.pane(p -> {
            p.margin(10f);
            p.table(Tex.button, t -> {
                TextButton.TextButtonStyle style = Styles.flatt;
                t.defaults().size(360f, 60f).left();

                if(snapshots.view().dataType() == BlockDataType.memory && snapshots.view().live()){
                    t.button(tr("logicsugar.vars.clearmemory", "Clear memory"), Icon.cancel, style, () -> {
                        snapshots.view().clear();
                        Arrays.fill(counter, reset / 2);  // Animate change
                        dialog.hide();
                    }).marginLeft(12f).row();
                }

                t.button(tr("logicsugar.vars.copyvariables", "Copy values to Clipboard"), Icon.copy, style, () -> {
                    Core.app.setClipboardText(MemoryText.write(snapshots.view(), VarsOptions.hex));
                    dialog.hide();
                }).marginLeft(12f).row();

                if(snapshots.view().dataType() == BlockDataType.memory && snapshots.view().live()){
                    t.button(tr("logicsugar.vars.importvariables", "Import values from Clipboard"), Icon.download, style, () -> {
                        String text = Core.app.getClipboardText();
                        String error = MemoryText.validate(text, length);
                        if(error == null) error = MemoryText.read(text, length, snapshots.view());

                        if(error != null){
                            Vars.ui.showInfoFade(tr("logicsugar.vars.importfailed", "Invalid lines: {0}", error));
                            return;
                        }

                        Arrays.fill(counter, reset / 2);
                        dialog.hide();
                    }).marginLeft(12f).row();
                }

                if(compact){
                    if(snapshots.view().dataType() != BlockDataType.properties && snapshots.view() instanceof Snapshot snapshot){
                        t.button(tr("logicsugar.vars.restorecurrent", "Restore current snapshot"), Icon.download, style, this::restoreSnapshot).marginLeft(12f).row();
                    }

                    if(snapshots.canRemove()){
                        // Can't remove snapshots from snapshot groups
                        t.button(tr("logicsugar.vars.deletecurrent", "Delete current snapshot"), Icon.trash, style, this::removeSnapshot).marginLeft(12f).row();
                    }
                }

                t.button(tr("logicsugar.vars.deleteall", "Delete all snapshots of this block"), Icon.trash, style, () -> {
                    Snapshots.deleteBuilding(snapshots.view().building());
                    dialog.hide();
                    first();
                }).marginLeft(12f).row();

                t.button(tr("@back", "Back"), Icon.left, style, dialog::hide).padTop(10f).marginLeft(12f).name("back");
            });
        });

        dialog.addCloseListener();
        dialog.show();
    }

    private void refreshView(boolean update){
        Arrays.fill(counter, reset);
    }

    private void updateView(int update){
        updateView(true);
    }

    private void updateView(boolean update){
        snapshots.view().setView(VarsOptions.sorted, VarsOptions.filtered, VarsOptions.hideLinks);
        if(snapshots.view().size() != length){
            setup();
        }else{
            Arrays.fill(counter, reset);
        }
    }

    /** 上游 v154 的 {@code LCanvas.isCompact()}：本仓库 v160 改名为 {@code useRows()}，
     *  复用 LogicSugar 的兼容入口（两个名字都不存在时按相同的宽度阈值回退）。 */
    private static boolean compact(){
        return SugarCanvas.compactStatementLayout();
    }

    /** 上游 v154 的 {@code LCanvas.getTargetWidth()}：v160 把 targetWidth 收回实例字段且没有
     *  getter，这里按同一构建里 {@code LCanvas.rebuild()} 的取值重算（窄屏 400、宽屏 900），
     *  与语句卡的宽度保持一致。 */
    private static float targetWidth(){
        return compact() ? 400f : 900f;
    }

    /** 本地化文本：{@code logicsugar.vars.<key>}，bundle 缺键时用英文 fallback；
     *  {@link L10n#text} 同时处理了 {@code {0}} 占位与无头环境。 */

    /**
     * 添加一个不换行、超宽省略的标签。
     *
     * <p>为什么不用 {@code Cell.wrap(false)}：那是 v160 arc 才有的重载。Neon 聚合构建把工作区
     * 里那份旧 arc（{@code Arc/arc-core/build/libs/arc-core-1.0.jar}）放在编译类路径最前面，
     * Mindustry.jar 自带的新 arc 被它遮住；旧版 {@code Cell} 只有无参 {@code wrap()}，而
     * {@code wrapLabel(boolean)} 只认 TextButton，对 Label 都没用。{@code Label.setWrap(false)}
     * 两代 arc 都有，且新版 {@code Cell.wrap(boolean)} 本来就转发到它，语义完全一致。</p>
     */
    static Cell<Label> noWrapLabel(Table table, CharSequence text){
        Label label = new Label(text);
        label.setWrap(false);
        return table.add(label).ellipsis(true);
    }

    private static String tr(String key, String fallback, Object... args){
        return L10n.text(key, fallback, args);
    }
}
