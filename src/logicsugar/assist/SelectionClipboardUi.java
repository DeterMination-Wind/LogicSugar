package logicsugar.assist;

import arc.Core;
import arc.input.KeyCode;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.style.Drawable;
import arc.scene.ui.Dialog;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.logic.LCanvas;
import mindustry.logic.LogicDialog;
import mindustry.ui.Styles;

/**
 * 跨逻辑复制的编辑器入口：编辑菜单里的「复制选区 / 粘贴选区」，以及桌面 Ctrl+C / Ctrl+V。
 *
 * <p>这两条入口原来只挂在 {@code SugarLogicDialog} 的 {@code update} 上。共存档显示的是对方的
 * 对话框，那个 {@code update} 根本不跑，于是不把冲突设置切成「接管」就没有跨逻辑复制。
 * 操作本身只依赖画布与选区，所以同一套入口可以挂到任何正在显示的逻辑对话框上——接管档挂在
 * 自家对话框，共存档挂在对方对话框里的那张画布。</p>
 *
 * <p>快捷键仍是轮询：{@code UI.update()} 会把焦点清成 {@code null}，挂在对话框上的 capture
 * 监听器收不到按键；arc 的 {@code handle()} 也不停止冒泡。输入框由 {@code Scene.hasField()}
 * 让开。让位档不挂这个类，糖编辑器在那一档是停用的。</p>
 */
public final class SelectionClipboardUi{
    static final String copySelectionName = "logicsugar-copy-selection";
    static final String pasteSelectionName = "logicsugar-paste-selection";

    private TextButton cachedCopyButton;
    private Table cachedCopyMenu;
    private Dialog cachedCopyDialog;
    private float menuScanTimer;
    /** 长按去抖：正在按住的键；物理松开后清空。keyTap 在系统连发期间持续为真。 */
    private KeyCode held;

    /**
     * 每帧调用一次。{@code editor} 是这块画布所属的对话框：它没显示时什么都不做，被后打开的
     * 对话框盖住时只保留菜单扫描、不抢快捷键，避免和上面那张画布各粘贴一次。
     */
    public void tick(LCanvas canvas, LogicDialog editor){
        if(editor == null || !editor.isShown() || canvas == null){
            if(held != null && !Core.input.keyDown(held)) held = null;
            return;
        }
        menuScanTimer += Time.delta;
        if(menuScanTimer >= 6f){
            menuScanTimer = 0f;
            install(canvas);
        }
        if(isFront(editor)) pollShortcuts(canvas);
    }

    private void install(LCanvas canvas){
        if(cachedCopyButton != null && !inSceneTree(cachedCopyButton)){
            cachedCopyButton = null;
            cachedCopyMenu = null;
            cachedCopyDialog = null;
        }
        if(cachedCopyButton == null && Core.scene != null){
            TextButton found = findCopyButton(Core.scene.root);
            if(found != null){
                cachedCopyButton = found;
                cachedCopyMenu = found.parent instanceof Table table ? table : null;
                cachedCopyDialog = parentDialog(found);
            }
        }
        if(cachedCopyMenu == null || cachedCopyDialog == null) return;
        Dialog dialog = cachedCopyDialog;

        if(cachedCopyMenu.find(copySelectionName) == null){
            installMenuButton(cachedCopyMenu, copySelectionName, "@logicsugar.copyselection",
                Icon.copy, () -> finish(BoxSelect.copySelection(canvas), true, dialog));
        }
        if(cachedCopyMenu.find(pasteSelectionName) == null){
            installMenuButton(cachedCopyMenu, pasteSelectionName, "@logicsugar.pasteselection",
                Icon.paste, () -> finish(BoxSelect.pasteClipboard(canvas), false, dialog));
        }
    }

    private void pollShortcuts(LCanvas canvas){
        if(held != null && !Core.input.keyDown(held)) held = null;
        if(held != null || Vars.mobile) return;
        if(Core.scene != null && Core.scene.hasField()) return;
        if(!Core.input.ctrl() || Core.input.alt()) return;

        if(Core.input.keyTap(KeyCode.c)){
            held = KeyCode.c;
            // 没有可复制的选区时静默：Ctrl+C 是大家的快捷键，用户也可能想复制别的东西。
            StatementClipboard.Result result = BoxSelect.copySelection(canvas);
            if(result != StatementClipboard.Result.EMPTY) finish(result, true, cachedCopyDialog);
        }else if(Core.input.keyTap(KeyCode.v)){
            held = KeyCode.v;
            // 剪贴板里不是逻辑代码时同样静默——快捷键没有解释自己的机会，
            // 「粘贴选区」菜单项才是那种情形该走的显式入口。
            if(!StatementClipboard.isAcceptable(Core.app.getClipboardText())) return;
            StatementClipboard.Result result = BoxSelect.pasteClipboard(canvas);
            if(result != StatementClipboard.Result.EMPTY) finish(result, false, cachedCopyDialog);
        }
    }

    /** 菜单若开着先收起，否则粘贴的结果会被菜单挡住看不见。 */
    private static void finish(StatementClipboard.Result result, boolean copying, Dialog menu){
        if(menu != null && menu.isShown()) menu.hide();
        switch(result){
            case OK -> Vars.ui.showInfoFade(copying
                ? "@logicsugar.copyselection.done"
                : Core.bundle.format("logicsugar.pasteselection.done", BoxSelect.lastPasteCount()));
            case EMPTY -> Vars.ui.showInfoFade(copying
                ? "@logicsugar.copyselection.empty"
                : "@logicsugar.pasteselection.empty");
            case INCOMPLETE_STRUCTURE -> Vars.ui.showInfoFade(copying
                ? "@logicsugar.copyselection.incomplete"
                : "@logicsugar.pasteselection.incomplete");
            case ESCAPING_JUMP -> Vars.ui.showInfoFade(copying
                ? "@logicsugar.copyselection.escaping"
                : "@logicsugar.pasteselection.escaping");
            case NOT_LOGIC -> Vars.ui.showInfoFade("@logicsugar.pasteselection.notlogic");
            case TOO_BIG -> Vars.ui.showInfoFade("@logicsugar.pasteselection.toobig");
        }
    }

    /**
     * 后打开的对话框画在上面（scene root 里更靠后的子元素）。编辑菜单、函数库编辑器都是这样
     * 盖上来的；快捷键只交给最上面那张，菜单扫描则两边都做，按钮按名字去重。
     */
    private static boolean isFront(LogicDialog editor){
        if(Core.scene == null || Core.scene.root == null) return true;
        Seq<Element> children = Core.scene.root.getChildren();
        int index = children.indexOf(editor, true);
        if(index < 0) return true;
        for(int i = index + 1; i < children.size; i++){
            Element child = children.get(i);
            if(child instanceof Dialog dialog && dialog.isShown() && dialog.visible) return false;
        }
        return true;
    }

    private static TextButton installMenuButton(Table menu, String name, String labelKey,
                                                Drawable icon, Runnable handler){
        menu.row();
        TextButton result = menu.button(labelKey, icon, Styles.flatt, handler)
            .size(280f, 60f).left().marginLeft(12f).get();
        result.name = name;
        menu.invalidateHierarchy();
        return result;
    }

    private static Dialog parentDialog(Element element){
        Element current = element;
        while(current != null && !(current instanceof Dialog)) current = current.parent;
        return (Dialog)current;
    }

    private static boolean inSceneTree(Element element){
        Element current = element;
        while(current != null){
            if(Core.scene != null && current == Core.scene.root) return true;
            current = current.parent;
        }
        return false;
    }

    private static TextButton findCopyButton(Element element){
        if(element instanceof TextButton button
            && button.getText().toString().equals(Core.bundle.get("copy.clipboard"))){
            return button;
        }
        if(element instanceof Group group){
            for(Element child : group.getChildren()){
                TextButton found = findCopyButton(child);
                if(found != null) return found;
            }
        }
        return null;
    }
}
