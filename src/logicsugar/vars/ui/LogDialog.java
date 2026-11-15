package logicsugar.vars.ui;

import arc.Core;
import arc.graphics.Color;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Label;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import arc.util.Align;
import arc.util.Log;
import arc.util.Log.LogLevel;
import logicsugar.assist.L10n;
import logicsugar.vars.LogMessages;
import logicsugar.vars.LogMessages.LogMessage;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.ui.FileChooser;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;

import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * 处理器自带日志的查看窗口（上游 v0.12.0 的 {@code ui.LogDialog}）。
 *
 * <p>列出 {@link LogMessages} 为某个处理器记录的条目：级别图标、帧号、分类与文本，可按时
 * 级别/分类过滤、按时间正反排序、复制到剪贴板或导出为制表符分隔的文本。数据只活在内存里，
 * 关图即丢；窗口打开时暂停游戏（联机不暂停，由原版 {@code BaseDialog.shouldPause} 判定），
 * 所以列表在打开期间是稳定的。</p>
 *
 * <p>与上游的差异只有集成约定：用户可见文本全部走 {@code logicsugar.log.*} bundle 键 +
 * 英文 fallback（{@link L10n#text}）；图标经 {@link logicsugar.assist.UiSprites} 解析 Neon 聚合
 * 前缀（排序按钮复用 profiler 的 sort 图标）；Label 换行用 {@code setWrap(true)}（Neon 的旧 arc 没有
 * {@code Cell.wrap(boolean)}，见 {@code varsUiTest} 的源码钉子）。</p>
 */
public class LogDialog extends BaseDialog{
    public static final int imageSize = 20;
    private static boolean descending = true;

    private final LogicBuild build;
    private final Set<LogLevel> enabledLevels = EnumSet.allOf(LogLevel.class);
    private final Set<String> enabledCategories = new TreeSet<>();
    private final Table messageList = new Table();

    private LogMessage[] messages;
    private int lastWidth;

    public LogDialog(LogicBuild build){
        super(tr("logicsugar.log.title", "Log"));

        this.build = build;
        this.messages = LogMessages.getMessages(build);
        this.shouldPause = true;

        enabledCategories.clear();
        for(LogMessage m : messages) enabledCategories.add(m.category);

        addCloseButton();
        shown(this::rebuild);

        onResize(() -> {
            if(lastWidth != Core.graphics.getWidth() / 16){
                rebuildMessages();
            }
        });

        rebuild();
    }

    private void rebuild(){
        cont.clear();

        Table filters = new Table();
        filters.left();

        // Sorting option
        filters.button(logicsugar.profile.ui.ProfilerIcons.sortDesc, Styles.clearTogglei, () -> {
            descending = !descending;
            rebuildMessages();
        }).size(40).padRight(10f).checked(descending).tooltip(tr("logicsugar.log.newest", "Newest first"));

        // Level filters
        LogLevel[] levels = {LogLevel.err, LogLevel.warn, LogLevel.info, LogLevel.debug};
        for(LogLevel level : levels){
            filters.button(button -> button.image(icon(level).tint(color(level))).size(imageSize),
                    Styles.clearTogglei, () -> {
                        toggle(enabledLevels, level);
                        rebuildMessages();
                    }).size(40).checked(true).tooltip(level.name().toLowerCase());
        }

        filters.add().width(10);
        filters.defaults().height(42f);

        // Category filters
        for(String category : enabledCategories){
            filters.button(category.isEmpty() ? tr("logicsugar.log.emptyCategory", "<empty>") : category,
                    Styles.togglet, () -> {
                        toggle(enabledCategories, category);
                        rebuildMessages();
                    }).pad(2).checked(true);
        }

        cont.add(filters).center().row();

        ScrollPane scroll = new ScrollPane(messageList);
        scroll.setFadeScrollBars(false);
        scroll.setScrollingDisabled(true, false);

        cont.add(scroll).grow();

        rebuildMessages();

        buttons.clear();
        buttons.button("@back", Icon.left, this::hide).name("back");
        buttons.button("@edit", Icon.edit, this::editCommands).name("edit");
    }

    private static <T> void toggle(Set<T> set, T value){
        if(!set.remove(value)) set.add(value);
    }

    private void rebuildMessages(){
        lastWidth = Core.graphics.getWidth() / 16;
        messageList.clear();
        messageList.top().left();

        int count = 0;
        for(int i = 0; i < messages.length; i++){
            LogMessage message = messages[descending ? messages.length - i - 1 : i];
            if(enabledLevels.contains(message.level) && enabledCategories.contains(message.category)){
                addMessage(messageList, message);
                count++;
            }
        }

        if(count == 0){
            String text = LogMessages.maxMessages == 0 ? tr("logicsugar.log.disabled", "Logging disabled in settings.")
                : messages.length == 0 ? tr("logicsugar.log.empty", "No messages logged in the current processor.")
                : tr("logicsugar.log.noMatch", "No messages matching the filter.");
            messageList.center();
            messageList.add(text).center();
        }
    }

    private void addMessage(Table table, LogMessage message){
        table.table(row -> {
            row.left().defaults().pad(3, 7, 3, 7);

            // Level icon
            row.image(icon(message.level).tint(color(message.level))).size(imageSize);

            // Frame ID — right-aligned, width based on content
            row.add(Long.toString(message.frameId))
                    .color(Color.lightGray)
                    .minWidth(65)
                    .right()
                    .get().setAlignment(Align.right);

            // Category — centered, width based on content
            if(!message.category.isEmpty()){
                row.add(message.category)
                        .color(Color.gray)
                        .minWidth(100)
                        .center()
                        .get().setAlignment(Align.center);
            }

            // Message — takes the remaining width
            Label label = new Label(message.message);
            label.setWrap(true);
            label.setAlignment(Align.left);

            row.add(label).growX().left();
        }).growX().row();

        table.image().color(Color.darkGray)
                .height(1)
                .growX()
                .pad(2)
                .row();
    }

    private static TextureRegionDrawable icon(LogLevel level){
        return switch(level){
            case err -> Icon.cancel;
            case warn -> Icon.warning;
            case info -> Icon.infoCircle;
            default -> Icon.settings;
        };
    }

    private static Color color(LogLevel level){
        return switch(level){
            case err -> Color.salmon;
            case warn -> Color.gold;
            case info -> Color.forest;
            default -> Color.lightGray;
        };
    }

    /** 导出文本：制表符分隔，表头保持英文原样（与内存导出同理，是数据格式而不是界面文本）。 */
    private String createText(){
        StringBuilder sb = new StringBuilder(30 * messages.length);
        sb.append("Level\tFrame\tCategory\tMessage\n");
        for(LogMessage message : messages){
            sb.append(message.level.name()).append('\t');
            sb.append(message.frameId).append('\t');
            sb.append(message.category == null ? "" : message.category).append('\t');
            sb.append(message.message).append('\n');
        }
        return sb.toString();
    }

    private void editCommands(){
        BaseDialog dialog = new BaseDialog(tr("@edit", "Edit"));
        dialog.cont.pane(p -> {
            p.margin(10f);
            p.table(Tex.button, t -> {
                TextButton.TextButtonStyle style = Styles.flatt;
                t.defaults().size(360f, 60f).left();

                t.button(tr("logicsugar.log.clear", "Clear log messages"), Icon.cancel, style, () -> {
                    LogMessages.clear(build);
                    messages = new LogMessage[0];
                    rebuildMessages();
                    dialog.hide();
                }).marginLeft(12f).row();

                t.button(tr("@copy.clipboard", "Copy to Clipboard"), Icon.copy, style, () -> {
                    Core.app.setClipboardText(createText());
                    dialog.hide();
                }).marginLeft(12f).row();

                t.button(tr("logicsugar.log.export", "Export to a text file"), Icon.upload, style, () -> {
                    FileChooser.save("txt").name("log_messages.txt").submit(file -> {
                        try{
                            file.writeString(createText());
                        }catch(Exception e){
                            Log.err("[LogicSugar] Error writing the log file: ", e);
                            Vars.ui.showErrorMessage(tr("logicsugar.log.writeError", "Error writing file {0}", file.absolutePath()));
                        }
                    });
                    dialog.hide();
                }).marginLeft(12f).row();

                t.button(tr("@back", "Back"), Icon.left, style, dialog::hide).padTop(10f).marginLeft(12f).name("back");
            });
        });

        dialog.addCloseListener();
        dialog.show();
    }

    private static String tr(String key, String fallback, Object... args){
        return L10n.text(key, fallback, args);
    }
}
