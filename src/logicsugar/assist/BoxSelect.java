package logicsugar.assist;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.geom.*;
import arc.input.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import arc.util.Align;
import mindustry.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.logic.*;
import mindustry.logic.LCanvas.*;
import mindustry.logic.LStatements.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import java.lang.reflect.*;
import java.util.*;

/**
 * 框选功能 - 批量选择、复制、移动、删除积木（事件驱动架构）。
 *
 * 架构：
 * - 输入层：Core.scene.addCaptureListener 从事件源头拦截，event.stop() 阻止原版 StatementElem
 *   的 InputListener 收到事件。不再需要 restoreChildrenOrder 等对抗代码。
 * - 布局层：移动模式 relayoutNonSelected 紧凑非选中积木 + applyInsertShift
 *   直接修改 child.y 腾位（JumpCurve 基于 child.y 不含 translation）。
 *   clearDraggingField 防止原版 layout() 跳过错误积木。
 * - 接管按钮：选中积木后劫持其功能按钮（删除→批量删除，+→批量复制，复制→模式切换）。
 *
 * 交互流程：
 *   1. 空白点击拖动 → 框选积木（蓝=移动模式，绿=复制模式）
 *   2. 释放 → 选中积木高亮，显示工具栏，积木按钮被接管
 *   3. 拖动选中积木 → 积木/半透明预览跟随鼠标，显示插入指示器
 *   4. 松手 → 积木移动/复制到新位置
 *   5. Ctrl+点击单积木 → 选中并复制拖动
 *   6. Delete/Backspace → 快速删除选中积木
 *   7. 右键/Esc → 取消拖动
 *
 * ------------------------------------------------------------
 * 致谢 / Acknowledgements
 * ------------------------------------------------------------
 * 拖动移动和跳转索引转换逻辑参考了 MI2-Utilities 项目：
 *   - 项目地址: https://github.com/BlackDeluxeCat/MI2-Utilities-Java
 *   - 参考文件: src/mi2u/ui/LogicHelperMindow.java
 */
public class BoxSelect{

    // ===== 常量 =====
    private static final float MIN_DRAG_DIST = 8f;
    private static final float SCROLLBAR_WIDTH = 14f;
    private static final float AUTOSCROLL_MARGIN = 80f;
    private static final float AUTOSCROLL_SPEED = 15f;
    private static final float FILL_ALPHA = 0.15f;
    private static final float BORDER_ALPHA = 0.8f;
    private static final float COPY_PREVIEW_ALPHA = 0.5f;
    private static final float SCROLLBAR_SEG_ALPHA = 0.35f;
    private static final Mat tmpMat = new Mat();
    private static final Mat tmpMat2 = new Mat();

    // ===== 反射字段（包级私有，缓存 Field）=====
    private static final Field draggingField;
    private static final Field privilegedField;
    static Field needsLayoutField;
    static{
        try{
            draggingField = LCanvas.class.getDeclaredField("dragging");
            draggingField.setAccessible(true);
            privilegedField = LCanvas.class.getDeclaredField("privileged");
            privilegedField.setAccessible(true);
            needsLayoutField = arc.scene.ui.layout.WidgetGroup.class.getDeclaredField("needsLayout");
            needsLayoutField.setAccessible(true);
        }catch(Exception e){
            Log.err("[LogicAssist] Failed to access LCanvas fields", e);
            throw new RuntimeException(e);
        }
    }

    // ===== 状态 =====
    private enum State{
        IDLE, SELECTING, SELECTED, DRAGGING_MOVE, DRAGGING_COPY
    }
    private static State state = State.IDLE;

    // 拖拽模式：移动或复制（替代 Ctrl 键，支持移动端）
    private enum DragMode{ MOVE, COPY }
    private static DragMode dragMode = DragMode.MOVE;

    // 框选坐标（stage 坐标系，用于距离判定）
    private static float selStartX, selStartY;
    private static float selCurX, selCurY;
    // 框选坐标（DragLayout 本地坐标系，用于命中判定和绘制）
    private static float selStartLocalX, selStartLocalY;
    private static float selCurLocalX, selCurLocalY;
    private static boolean dragMoved = false;

    // 选中集合（保持插入顺序）
    private static final LinkedHashSet<StatementElem> selected = new LinkedHashSet<>();

    // 拖动状态
    private static float dragStartMouseX, dragStartMouseY;
    private static float dragStartLocalX, dragStartLocalY;
    private static int dragInsertPos = -1;

    // 拖动期间保存的原始 child.y（用于恢复 layout() 的修改）
    private static float[] dragBaseYs = null;

    // 插入指示器几何位置
    private static float indicatorX, indicatorY, indicatorW, indicatorH;

    // 复制用剪贴板（用 copy() 保持 ExprStatement 折叠状态，不经过 write+read）
    private static List<LStatement> clipboardCopies = null;
    private static int clipboardSize = 0;
    private static List<StatementElem> clipboardSources = null;

    // UI 元素
    private static Element overlay;
    private static boolean initialized = false;
    private static InputListener captureListener;

    // 反射缓存（vScrollBounds / vKnobBounds）
    private static Field vScrollBoundsField;
    private static Field vKnobBoundsField;

    // ===== 初始化 =====

    public static void init(){
        Core.app.post(() -> {
            LCanvas canvas = getCanvas();
            if(canvas == null){
                // canvas 还没准备好，下一帧重试
                Core.app.post(() -> init());
                return;
            }
            setup(canvas);
            initialized = true;
            Log.info("[LogicAssist] BoxSelect initialized (event-driven mode).");

            // 对话框关闭时重置状态（替代原 tick 轮询检测）
            LogicDialog dialog = Vars.ui.logic;
            if(dialog != null){
                dialog.hidden(() -> {
                    if(state != State.IDLE){
                        resetState(canvas);
                    }
                });
            }
        });

        // Delete/Backspace 键：事件驱动，不再轮询
        Core.scene.addListener(new InputListener(){
            @Override
            public boolean keyDown(InputEvent event, KeyCode key){
                if(key != KeyCode.del && key != KeyCode.backspace) return false;
                LogicDialog dialog = Vars.ui.logic;
                if(dialog == null || !dialog.isShown()) return false;
                if(state != State.SELECTED || selected.isEmpty()) return false;
                LCanvas canvas = getCanvas();
                if(canvas != null){
                    deleteSelected(canvas);
                }
                return false;
            }
        });
    }

    /** 注册 capture listener 和 overlay。
     *  capture listener 加在 Core.scene 的 root 上（通过 addCaptureListener），
     *  在事件捕获阶段（target 之前）执行，用 event.stop() 阻止原版 StatementElem 的 InputListener 收到事件。 */
    private static void setup(LCanvas canvas){
        // 注册 capture listener（只注册一次，跨对话框开关复用）
        if(captureListener == null){
            captureListener = new InputListener(){
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                    return handleTouchDown(event, x, y, pointer, button);
                }

                @Override
                public void touchDragged(InputEvent event, float x, float y, int pointer){
                    handleTouchDragged(event, x, y, pointer);
                }

                @Override
                public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
                    handleTouchUp(event, x, y, pointer, button);
                }
            };
            Core.scene.addCaptureListener(captureListener);
            Log.info("[LogicAssist] Capture listener registered.");
        }

        // overlay 用于绘制框选框、高亮、插入指示器
        overlay = new Element(){
            @Override
            public void draw(){
                drawOverlay();
            }
        };
        overlay.touchable = Touchable.disabled;
        overlay.cullable = false;
        overlay.visible = true;
        Core.scene.add(overlay);
        overlay.update(() -> {
            // 不使用 visible 控制显示——visible=false 会导致 act() 不执行，
            // update() 不会被调用，形成死锁。直接执行 setSize + toFront 即可。
            overlay.setSize(Core.graphics.getWidth(), Core.graphics.getHeight());
            // 只在需要绘制覆盖层时才 toFront，避免干扰 MindustryX 等第三方 UI 的层级
            // SELECTED/IDLE 状态的高亮和滚动条改由 LogicCanvas.draw() 绘制，无需 toFront
            if(state == State.SELECTING || state == State.DRAGGING_MOVE || state == State.DRAGGING_COPY){
                overlay.toFront();
            }

            // 拖拽期间每帧重新计算插入指示器位置（滚轮滚动时 touchDragged 不触发）
            if(state == State.DRAGGING_MOVE || state == State.DRAGGING_COPY){
                LCanvas c = getCanvas();
                if(c != null){
                    float mx = Core.input.mouseX();
                    float my = Core.input.mouseY();
                    updateDrag(c, mx, my);
                    autoScroll(c);
                }
            }
        });
    }

    // ===== 事件处理（Capture 阶段，在 target 之前执行）=====

    /** 判断当前事件是否应该由我们处理。
     *  只在 LogicDialog 显示且 canvas 可用时才介入。 */
    private static boolean shouldIntercept(LCanvas canvas){
        LogicDialog dialog = Vars.ui.logic;
        return dialog != null && dialog.isShown() && canvas != null && canvas.statements != null;
    }

    /** 判断点击是否在积木的拖动区（header 条）上，而非按钮上。
     *  原版 InputListener 用 event.targetActor instanceof Image 来排除按钮点击。
     *  我们用类似逻辑：如果 target 是 Image（按钮图标），放行给原版处理。 */
    private static boolean isClickOnButton(Element target){
        return target instanceof Image;
    }

    /** 判断元素是否是 canvas（LCanvas）的后代。
     *  返回按钮、变量按钮等在 LogicDialog.buttons 区，不在 canvas 内。
     *  只有 canvas 内的空白区才允许框选。 */
    private static boolean isDescendantOfCanvas(Element elem, LCanvas canvas){
        Element current = elem;
        while(current != null){
            if(current == canvas) return true;
            current = current.parent;
        }
        return false;
    }

    /** 兜底检测：点击位置是否落在某个 Button 的实际边界内。
     *  当 arc 的 hit test 因父容器裁剪、层叠顺序等原因未命中按钮内部元素时，
     *  通过遍历 target 所在 StatementElem 的子元素树，手动检测按钮边界。
     *  避免 MindustryX 的 JUMP/pencil 等按钮被误判为"点击积木"而触发框选/拖动。 */
    private static boolean isClickWithinButtonBounds(Element target, float stageX, float stageY){
        // 找到 target 所在的 StatementElem
        StatementElem stmt = null;
        Element cur = target;
        while(cur != null){
            if(cur instanceof StatementElem){
                stmt = (StatementElem)cur;
                break;
            }
            cur = cur.parent;
        }
        if(stmt == null) return false;
        return hasButtonAtRecursive(stmt, stageX, stageY);
    }

    /** 递归遍历元素树，检查是否有 Button 的边界包含指定 stage 坐标 */
    private static boolean hasButtonAtRecursive(Element elem, float stageX, float stageY){
        if(elem instanceof Button){
            Vec2 local = elem.stageToLocalCoordinates(Tmp.v2.set(stageX, stageY));
            // 加 2px 容差，避免边缘点击漏判
            if(local.x >= -2f && local.x <= elem.getWidth() + 2f &&
               local.y >= -2f && local.y <= elem.getHeight() + 2f){
                return true;
            }
        }
        if(elem instanceof Group){
            Seq<Element> children = ((Group)elem).getChildren();
            for(int i = 0; i < children.size; i++){
                if(hasButtonAtRecursive(children.get(i), stageX, stageY)) return true;
            }
        }
        return false;
    }

    private static boolean handleTouchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
        LCanvas canvas = getCanvas();
        if(canvas == null || !shouldIntercept(canvas)) return false;

        // 只处理鼠标左键和中键（中键原版用于复制单个积木）
        if(button != KeyCode.mouseLeft && button != KeyCode.mouseMiddle){
            return false;
        }

        // 右键在 touchDown 不会被触发（mouseRight），但以防万一
        if(button == KeyCode.mouseRight) return false;

        Vec2 stageCoords = Tmp.v1.set(x, y);
        Element target = event.targetActor;

        // 只处理 canvas 内的点击。
        // MindustryX 的 LogicSupport 左侧面板等非 canvas UI 直接放行，
        // 避免其按钮（ImageButton 内的 Image）进入 tryHijackButton 影响事件传递。
        // z-order 已在 replaceCanvas 中修正（canvas 在 children 列表中的位置保持原样），
        // 面板按钮的 hit test 能正确返回面板元素而非 canvas。
        if(!isDescendantOfCanvas(target, canvas)){
            return false;
        }

        // canvas 内的按钮：检查是否是选中积木的功能按钮
        if(isClickOnButton(target)){
            if(tryHijackButton(canvas, event, target)){
                return true;
            }
            return false;
        }
        // MindustryX 兼容：TextButton（如 JUMP 按钮、注释切换按钮）内部是 Label 而非 Image，
        // 不被 isClickOnButton 捕获。必须放行给原版处理，否则会被当作"点击积木"拦截。
        // 检查 target 自身或祖先链中是否有 Button
        Element btnCheck = target;
        while(btnCheck != null && !(btnCheck instanceof Button)) btnCheck = btnCheck.parent;
        if(btnCheck != null){
            return false;
        }
        // 兜底：hit test 可能因父容器裁剪/层叠等原因未命中按钮内部元素，
        // 手动检测点击位置是否落在任何 Button 的边界内（MindustryX 的 JUMP/pencil 按钮等）
        if(isClickWithinButtonBounds(target, stageCoords.x, stageCoords.y)){
            return false;
        }

        // 滚动条点击跳转：点击轨道（非滑块）时直接跳转到对应位置，点击滑块放行给原版拖拽
        if(canvas.pane != null && canvas.pane.hasScroll()){
            float paneX = canvas.pane.x;
            float paneW = canvas.pane.getWidth();
            if(stageCoords.x > paneX + paneW - SCROLLBAR_WIDTH){
                return handleScrollbarClick(canvas.pane, stageCoords.x, stageCoords.y);
            }
        }

        StatementElem clickedStmt = null;
        Element current = target;
        while(current != null){
            if(current instanceof StatementElem){
                clickedStmt = (StatementElem)current;
                break;
            }
            current = current.parent;
        }

        boolean onStatement = clickedStmt != null;
        boolean onSelectedStatement = onStatement && selected.contains(clickedStmt);

        if(onStatement && !onSelectedStatement){
            // Ctrl+点击非选中积木 → 选中该积木并开始复制拖动
            boolean ctrlDown = Core.input.keyDown(KeyCode.controlLeft);
            if(ctrlDown){
                selected.clear();
                selected.add(clickedStmt);
                startDrag(canvas, stageCoords.x, stageCoords.y, button);
                event.stop();
                return true;
            }
            // 普通点击非选中积木 → 放行给原版（让原版处理单积木拖拽）
            // 但如果当前有选中，先清空选中
            if(!selected.isEmpty()){
                clearSelection();
            }
            return false;
        }

        if(onSelectedStatement){
            // 点击选中积木 → 开始拖动，拦截事件
            startDrag(canvas, stageCoords.x, stageCoords.y, button);
            event.stop(); // 阻止原版 InputListener 收到事件
            return true;  // 注册 touchFocus，接收后续 drag/up
        }

        // canvas 内的空白区点击 → 开始框选，拦截事件
        startBoxSelect(canvas, stageCoords.x, stageCoords.y);
        event.stop();
        return true;
    }

    private static void handleTouchDragged(InputEvent event, float x, float y, int pointer){
        LCanvas canvas = getCanvas();
        if(canvas == null || state == State.IDLE) return;

        float mx = x;
        float my = y;

        if(state == State.SELECTING){
            selCurX = mx;
            selCurY = my;
            Vec2 curLocal = canvas.statements.stageToLocalCoordinates(Tmp.v2.set(mx, my));
            selCurLocalX = curLocal.x;
            selCurLocalY = curLocal.y;
            float dx = Math.abs(selCurX - selStartX);
            float dy = Math.abs(selCurY - selStartY);
            if(dx > MIN_DRAG_DIST || dy > MIN_DRAG_DIST){
                dragMoved = true;
                updateSelection(canvas);
            }
            autoScroll(canvas);
        }else if(state == State.DRAGGING_MOVE || state == State.DRAGGING_COPY){
            updateDrag(canvas, mx, my);
            float dx = Math.abs(mx - dragStartMouseX);
            float dy = Math.abs(my - dragStartMouseY);
            if(dx > MIN_DRAG_DIST || dy > MIN_DRAG_DIST){
                dragMoved = true;
            }
            // 拖动时也支持自动滚动
            autoScroll(canvas);
        }
    }

    private static void handleTouchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
        LCanvas canvas = getCanvas();
        if(canvas == null) return;

        if(state == State.SELECTING){
            if(!dragMoved){
                if(!selected.isEmpty()) clearSelection();
                state = State.IDLE;
            }else{
                if(!selected.isEmpty()){
                    state = State.SELECTED;
                    // 更新选中积木的按钮图标（显示 copy/move 模式）
                    updateSelectedButtonIcons(canvas);
                }else{
                    state = State.IDLE;
                }
            }
        }else if(state == State.DRAGGING_MOVE){
            if(dragMoved && dragInsertPos >= 0){
                executeDragMove(canvas, dragInsertPos);
            }else{
                cancelDrag(canvas);
            }
        }else if(state == State.DRAGGING_COPY){
            if(dragMoved && dragInsertPos >= 0){
                executeDragCopy(canvas, dragInsertPos);
            }else{
                cancelDrag(canvas);
            }
        }
    }

    // ==================================================================
    // 接管按钮（方向1：劫持选中积木上的功能按钮）
    // ==================================================================

    /** 尝试接管选中积木上的按钮点击。
     *  如果点击的是选中积木上的按钮（删除/+号/复制），执行批量操作并拦截事件。
     *  @return true 如果已拦截，false 如果应放行 */
    private static boolean tryHijackButton(LCanvas canvas, InputEvent event, Element target){
        if(selected.isEmpty()) return false;

        Element btn = target.parent;
        while(btn != null && !(btn instanceof ImageButton)) btn = btn.parent;
        if(btn == null) return false;
        ImageButton button = (ImageButton)btn;

        StatementElem stmtElem = null;
        Element p = btn.parent;
        while(p != null){
            if(p instanceof StatementElem){
                stmtElem = (StatementElem)p;
                break;
            }
            p = p.parent;
        }
        if(stmtElem == null || !selected.contains(stmtElem)) return false;

        // 识别按钮：通过 style.imageUp（包括被接管后改过的图标）
        Drawable icon = button.getStyle().imageUp;
        // 删除按钮：Icon.cancel → 批量删除
        if(icon == Icon.cancel){
            event.stop();
            Core.app.post(() -> deleteSelected(canvas));
            return true;
        }
        // +按钮：Icon.add → 在选中积木下方批量复制一份
        if(icon == Icon.add){
            event.stop();
            Core.app.post(() -> duplicateSelectedBelow(canvas));
            return true;
        }
        // 复制/移动切换按钮：Icon.copy 或 Icon.move → 切换模式
        if(icon == Icon.copy || icon == Icon.move){
            event.stop();
            dragMode = (dragMode == DragMode.MOVE) ? DragMode.COPY : DragMode.MOVE;
            updateSelectedButtonIcons(canvas);
            return true;
        }
        // 其他按钮放行（MindustryX 的额外按钮等）
        return false;
    }

    /** 更新所有选中积木的复制/移动按钮图标 */
    private static void updateSelectedButtonIcons(LCanvas canvas){
        Drawable modeIcon = (dragMode == DragMode.MOVE) ? Icon.move : Icon.copy;
        for(StatementElem elem : selected){
            findAndSetIcon(elem, Icon.copy, modeIcon);
            findAndSetIcon(elem, Icon.move, modeIcon);
        }
    }

    /** 在 StatementElem 中查找指定图标的 ImageButton 并替换图标 */
    private static void findAndSetIcon(StatementElem elem, Drawable oldIcon, Drawable newIcon){
        findAndSetIconRecursive(elem, oldIcon, newIcon);
    }

    private static boolean findAndSetIconRecursive(Element e, Drawable oldIcon, Drawable newIcon){
        if(e instanceof ImageButton){
            ImageButton btn = (ImageButton)e;
            if(btn.getStyle().imageUp == oldIcon){
                // 创建新样式副本，避免修改全局样式
                ImageButton.ImageButtonStyle newStyle = new ImageButton.ImageButtonStyle(btn.getStyle());
                newStyle.imageUp = newIcon;
                btn.setStyle(newStyle);
                return true;
            }
        }
        if(e instanceof Group){
            for(Element child : ((Group)e).getChildren()){
                if(findAndSetIconRecursive(child, oldIcon, newIcon)) return true;
            }
        }
        return false;
    }

    /** 恢复所有积木的按钮图标（取消选中时调用） */
    private static void restoreButtonIcons(LCanvas canvas){
        if(canvas == null || canvas.statements == null) return;
        for(Element child : canvas.statements.getChildren()){
            if(child instanceof StatementElem){
                // 把 Icon.move 改回 Icon.copy
                findAndSetIcon((StatementElem)child, Icon.move, Icon.copy);
            }
        }
    }

    /** 在选中积木最后一块的下方批量复制一份 */
    private static void duplicateSelectedBelow(LCanvas canvas){
        clearDraggingField(canvas);

        List<StatementElem> sorted = getSortedSelected(canvas);
        Seq<Element> children = canvas.statements.getChildren();
        int lastIdx = children.indexOf(sorted.get(sorted.size() - 1), true);
        int insertPos = lastIdx + 1;

        Seq<LStatement> copies = new Seq<>();
        List<StatementElem> copySources = new ArrayList<>();
        for(int i = 0; i < sorted.size(); i++){
            sorted.get(i).st.saveUI();
            LStatement copy = sorted.get(i).st.copy();
            Log.debug("[LogicAssist] duplicateSelectedBelow: st=@ copy=@", sorted.get(i).st.getClass().getSimpleName(), copy == null ? "null" : copy.getClass().getSimpleName());
            if(copy != null){
                copies.add(copy);
                copySources.add(sorted.get(i));
            }
        }
        int copySize = copies.size;

        if(children.size + copySize + copiedBlockEndCount(copySources) > LExecutor.maxInstructions){
            Log.debug("[LogicAssist] Duplicate aborted: would exceed maxInstructions");
            return;
        }

        if(copies.isEmpty()) return;

        insertCopiedStatements(canvas, insertPos, copies, copySources);

        finalizeLayout(canvas);
        // 更新所有 Jump 的 destIndex（反映插入后的新位置）并刷新跳转线
        saveAllJumpUI(canvas);
        canvas.statements.jumps.act(0f);
        refreshStructureLayout(canvas);
        // 先恢复所有积木的按钮图标（旧选中积木的 Icon.move 改回 Icon.copy）
        restoreButtonIcons(canvas);
        selected.clear();
        reselectRange(canvas, insertPos, copies.size);
        enterSelectedState(canvas);
        Log.debug("[LogicAssist] Duplicated " + copies.size + " blocks below selection.");
    }

    // ===== 框选 =====

    private static void startBoxSelect(LCanvas canvas, float mx, float my){
        selStartX = mx;
        selStartY = my;
        selCurX = mx;
        selCurY = my;
        Vec2 startLocal = canvas.statements.stageToLocalCoordinates(Tmp.v2.set(mx, my));
        selStartLocalX = startLocal.x;
        selStartLocalY = startLocal.y;
        selCurLocalX = startLocal.x;
        selCurLocalY = startLocal.y;
        dragMoved = false;
        state = State.SELECTING;
    }

    private static void updateSelection(LCanvas canvas){
        selected.clear();
        float minX = Math.min(selStartLocalX, selCurLocalX);
        float minY = Math.min(selStartLocalY, selCurLocalY);
        float maxX = Math.max(selStartLocalX, selCurLocalX);
        float maxY = Math.max(selStartLocalY, selCurLocalY);

        for(Element child : canvas.statements.getChildren()){
            if(!(child instanceof StatementElem)) continue;
            float cx = child.x;
            float cy = child.y;
            float cw = child.getWidth();
            float ch = child.getHeight();
            if(minX < cx + cw && maxX > cx && minY < cy + ch && maxY > cy){
                selected.add((StatementElem)child);
            }
        }
    }

    private static void clearSelection(){
        LCanvas canvas = getCanvas();
        if(canvas != null) restoreButtonIcons(canvas);
        selected.clear();
        state = State.IDLE;
    }

    /** 重置所有积木的 translation（移动模式下选中积木设了 translation 跟随鼠标） */
    private static void resetAllTranslations(LCanvas canvas){
        for(Element child : canvas.statements.getChildren()){
            child.setTranslation(0, 0);
        }
    }

    private static void resetState(LCanvas canvas){
        clearDraggingField(canvas);
        restoreButtonIcons(canvas);
        resetAllTranslations(canvas);
        selected.clear();
        state = State.IDLE;
        dragInsertPos = -1;
        dragMoved = false;
        dragBaseYs = null;
        clipboardCopies = null;
        clipboardSize = 0;
        clipboardSources = null;
    }

    // ===== 拖动 =====

    private static void startDrag(LCanvas canvas, float mx, float my, KeyCode button){
        dragStartMouseX = mx;
        dragStartMouseY = my;
        Vec2 startLocal = canvas.statements.stageToLocalCoordinates(Tmp.v2.set(mx, my));
        dragStartLocalX = startLocal.x;
        dragStartLocalY = startLocal.y;
        dragInsertPos = -1;
        dragMoved = false;

        // 保存所有积木的原始 y 坐标（layout() 会修改，拖动期间需要恢复）
        Seq<Element> children = canvas.statements.getChildren();
        dragBaseYs = new float[children.size];
        for(int i = 0; i < children.size; i++){
            dragBaseYs[i] = children.get(i).y;
        }

        // 拖动模式：dragMode 持久模式 + Ctrl/中键临时覆盖。
        // 框选框/高亮框颜色由 getModeColor() 实时反映此判断，保证与松手后拖动模式一致。
        boolean ctrlDown = Core.input.keyDown(KeyCode.controlLeft);
        boolean isCopy = ctrlDown || dragMode == DragMode.COPY || button == KeyCode.mouseMiddle;

        // 清除原版 dragging 字段，防止原版 layout 跳过错误积木
        clearDraggingField(canvas);

        if(isCopy){
            prepareCopyData(canvas);
            state = State.DRAGGING_COPY;
        }else{
            state = State.DRAGGING_MOVE;
        }
    }

    /** 拖动期间每帧更新 translation 和插入位置。
     *  关键：不触发 layout()，而是恢复 dragBaseYs 后用 translation 做所有偏移。
     *  translation 会被 localToAscendantCoordinates 正确计算，JumpCurve 能跟随。 */
    private static void updateDrag(LCanvas canvas, float mx, float my){
        clearDraggingField(canvas);

        Seq<Element> children = canvas.statements.getChildren();

        // 恢复原始 y 坐标（防止 layout() 的修改累积）
        if(dragBaseYs != null && dragBaseYs.length == children.size){
            for(int i = 0; i < children.size; i++){
                children.get(i).y = dragBaseYs[i];
            }
        }

        // 重置 needsLayout，防止 draw() 中 validate() → layout() 覆盖我们的修改
        try{
            needsLayoutField.setBoolean(canvas.statements, false);
        }catch(Exception ignored){}

        // 干净起点，避免累积
        resetAllTranslations(canvas);

        if(state == State.DRAGGING_MOVE){
            // 移动模式：紧凑排列非选中积木，消除选中积木原始位置占用的空间
            relayoutNonSelected(canvas);
            // 选中积木用 translation 跟随鼠标
            Vec2 localMouse = canvas.statements.stageToLocalCoordinates(Tmp.v2.set(mx, my));
            float dx = localMouse.x - dragStartLocalX;
            float dy = localMouse.y - dragStartLocalY;
            for(StatementElem elem : selected){
                elem.setTranslation(dx, dy);
            }
        }
        // 复制模式：原积木保持原位，预览由 drawCopyPreview() 绘制

        // 计算插入位置（移动模式下用 relayoutNonSelected 后的紧凑位置）
        int newInsertPos = computeInsertPosition(canvas, my);
        if(newInsertPos != dragInsertPos){
            dragInsertPos = newInsertPos;
        }

        // 腾位：将插入点下方的非选中积木向下移，撑开空间显示插入位置。
        // 关键：用 translation 而非修改 child.y，因为 translation 会被
        // localToAscendantCoordinates 正确计算，JumpCurve 能跟随。
        applyInsertShift(canvas);

        // 腾位后更新跳转线位置——此时 translation 已反映腾位，JumpCurve 能正确定位
        canvas.statements.jumps.act(0f);
        updateIndicatorGeometry(canvas);

        if(Core.input.keyTap(KeyCode.mouseRight) || Core.input.keyTap(KeyCode.escape)){
            cancelDrag(canvas);
        }
    }

    // ===== 插入位置计算 =====

    private static int computeInsertPosition(LCanvas canvas, float stageY){
        Seq<Element> children = canvas.statements.getChildren();
        if(children.isEmpty()) return 0;

        Vec2 local = canvas.statements.stageToLocalCoordinates(Tmp.v2.set(0, stageY));
        float localY = local.y;

        if(state == State.DRAGGING_COPY){
            // 复制模式：遍历所有 children，用视觉位置（y + translation.y）计算
            for(int i = 0; i < children.size; i++){
                Element child = children.get(i);
                float visualY = child.y + child.translation.y;
                float centerLocalY = visualY + child.getHeight() / 2f;
                if(localY > centerLocalY){
                    return i;
                }
            }
            return children.size;
        }

        // 移动模式：跳过选中积木（它们已从原位移走），用视觉位置计算
        int nonSelectedCount = 0;
        for(Element child : children){
            if(child instanceof StatementElem && selected.contains(child)) continue;
            float visualY = child.y + child.translation.y;
            float centerLocalY = visualY + child.getHeight() / 2f;
            if(localY > centerLocalY){
                return nonSelectedCount;
            }
            nonSelectedCount++;
        }
        return nonSelectedCount;
    }

    // ===== 腾位 =====

    /** 移动模式：紧凑排列非选中积木，消除选中积木原始位置占用的空间。
     *  用 translation 设置偏移（相对于 dragBaseYs 的原始位置），
     *  这样 localToAscendantCoordinates 能正确计算，JumpCurve 跟随。 */
    private static void relayoutNonSelected(LCanvas canvas){
        Seq<Element> children = canvas.statements.getChildren();
        float space = Scl.scl(10f);

        // 从顶部开始紧凑排列非选中积木（用 translation 表示相对于原始位置的偏移）
        float compactY = 0; // 紧凑布局中的累积 y（从顶部开始）
        for(int i = 0; i < children.size; i++){
            Element e = children.get(i);
            if(e instanceof StatementElem && selected.contains(e)) continue;
            // 原始位置（从顶部开始）：totalHeight - originalYFromTop
            // 紧凑位置（从顶部开始）：compactY
            // translation = 紧凑位置 - 原始位置（在 DragLayout 本地坐标系中，y 向上为正）
            // 但 DragLayout 的 y 是从底部向上的，所以需要用 height 转换
            float totalHeight = canvas.statements.getHeight();
            float originalY = dragBaseYs != null && i < dragBaseYs.length ? dragBaseYs[i] : e.y;
            // originalY 是底对齐的，转换为顶对齐：topY = totalHeight - originalY - height
            // 紧凑位置的顶对齐：compactTopY = compactY
            // 新的底对齐 y = totalHeight - compactY - height
            // translation.y = 新底对齐y - 原始底对齐y
            float newY = totalHeight - compactY - e.getPrefHeight();
            float transY = newY - originalY;
            e.setTranslation(0, transY);
            compactY += e.getPrefHeight() + space;
        }
    }

    /** 腾位：将插入点下方的非选中积木向下移，撑开空间显示插入位置。
     *  用 translation 而非修改 child.y，因为 translation 会被
     *  localToAscendantCoordinates 正确计算，JumpCurve 能跟随。 */
    private static void applyInsertShift(LCanvas canvas){
        if(dragInsertPos < 0 || selected.isEmpty()) return;

        Seq<Element> children = canvas.statements.getChildren();
        float space = Scl.scl(10f);

        // 腾位量 = 所有选中积木高度 + 间距，减去末尾多余的一个间距
        float shiftAmount = 0;
        for(StatementElem elem : selected){
            shiftAmount += elem.getHeight() + space;
        }
        shiftAmount -= space;
        if(shiftAmount <= 0) return;

        if(state == State.DRAGGING_COPY){
            // 复制模式：dragInsertPos 是绝对索引，移该索引及以下的积木
            for(int i = dragInsertPos; i < children.size; i++){
                Element child = children.get(i);
                child.setTranslation(child.translation.x, child.translation.y - shiftAmount);
            }
        }else{
            // 移动模式：dragInsertPos 是非选中积木的相对索引，
            // 需跳过选中积木找到对应绝对位置
            int nonSelectedSeen = 0;
            for(Element child : children){
                if(child instanceof StatementElem && selected.contains(child)) continue;
                if(nonSelectedSeen >= dragInsertPos){
                    child.setTranslation(child.translation.x, child.translation.y - shiftAmount);
                }
                nonSelectedSeen++;
            }
        }
    }

    // ===== 指示器几何 =====

    private static void updateIndicatorGeometry(LCanvas canvas){
        if(dragInsertPos < 0) return;

        Seq<Element> children = canvas.statements.getChildren();
        float paneWidth = canvas.statements.getWidth();
        float space = Scl.scl(10f);

        float totalH = 0;
        for(StatementElem elem : selected){
            totalH += elem.getHeight() + space;
        }
        totalH -= space;

        float insertLocalY;
        float drawLocalX = 0;

        if(state == State.DRAGGING_COPY){
            // 复制模式：dragInsertPos 是真实 child 索引，用视觉位置计算
            if(children.isEmpty() || dragInsertPos == 0){
                insertLocalY = canvas.statements.getHeight();
            }else if(dragInsertPos >= children.size){
                Element last = children.get(children.size - 1);
                insertLocalY = last.y + last.translation.y - space;
                drawLocalX = last.x;
            }else{
                Element before = children.get(dragInsertPos - 1);
                insertLocalY = before.y + before.translation.y - space;
                drawLocalX = before.x;
            }
        }else{
            // 移动模式：用非选中 children 的视觉位置计算
            List<Element> nonSelected = new ArrayList<>();
            for(Element child : children){
                if(child instanceof StatementElem && selected.contains(child)) continue;
                nonSelected.add(child);
            }

            if(nonSelected.isEmpty() || dragInsertPos == 0){
                insertLocalY = canvas.statements.getHeight();
            }else if(dragInsertPos >= nonSelected.size()){
                Element last = nonSelected.get(nonSelected.size() - 1);
                insertLocalY = last.y + last.translation.y - space;
                drawLocalX = last.x;
            }else{
                Element before = nonSelected.get(dragInsertPos - 1);
                insertLocalY = before.y + before.translation.y - space;
                drawLocalX = before.x;
            }
        }

        Vec2 stagePos = canvas.statements.localToStageCoordinates(Tmp.v1.set(drawLocalX, insertLocalY));
        indicatorX = stagePos.x;
        indicatorY = stagePos.y - totalH;
        indicatorW = paneWidth;
        indicatorH = totalH;
    }

    // ===== 自动滚动 =====

    private static void autoScroll(LCanvas canvas){
        if(canvas.pane == null) return;
        float mouseY = Core.input.mouseY();
        float screenH = Core.graphics.getHeight();
        float margin = Scl.scl(AUTOSCROLL_MARGIN);
        float speed = Scl.scl(AUTOSCROLL_SPEED) * Time.delta;

        if(mouseY < margin){
            canvas.pane.setScrollY(canvas.pane.getScrollY() + speed);
        }else if(mouseY > screenH - margin){
            canvas.pane.setScrollY(canvas.pane.getScrollY() - speed);
        }
    }

    /** 处理滚动条点击：点击滑块放行给原版拖拽，点击轨道跳转到对应位置 */
    private static boolean handleScrollbarClick(ScrollPane pane, float stageX, float stageY){
        try{
            if(vScrollBoundsField == null){
                vScrollBoundsField = ScrollPane.class.getDeclaredField("vScrollBounds");
                vScrollBoundsField.setAccessible(true);
            }
            if(vKnobBoundsField == null){
                vKnobBoundsField = ScrollPane.class.getDeclaredField("vKnobBounds");
                vKnobBoundsField.setAccessible(true);
            }
            Rect vScrollBounds = (Rect)vScrollBoundsField.get(pane);
            Rect vKnobBounds = (Rect)vKnobBoundsField.get(pane);
            if(vScrollBounds == null || vScrollBounds.width <= 0 || vScrollBounds.height <= 0) return false;

            Vec2 local = pane.stageToLocalCoordinates(Tmp.v1.set(stageX, stageY));

            // 点击在滑块上 → 放行给原版拖拽
            if(vKnobBounds != null && vKnobBounds.width > 0 && vKnobBounds.height > 0 &&
               local.x >= vKnobBounds.x && local.x <= vKnobBounds.x + vKnobBounds.width &&
               local.y >= vKnobBounds.y && local.y <= vKnobBounds.y + vKnobBounds.height){
                return false;
            }

            // 点击在轨道上（非滑块）→ 跳转到对应位置
            float scrollHeight = pane.getScrollHeight();
            float visibleHeight = pane.getHeight();
            float maxScroll = scrollHeight - visibleHeight;
            if(maxScroll <= 0) return false;

            // Y 轴向上：轨道顶部对应 scrollY=0，底部对应 scrollY=maxScroll
            float ratio = (vScrollBounds.y + vScrollBounds.height - local.y) / vScrollBounds.height;
            ratio = Mathf.clamp(ratio, 0f, 1f);

            pane.setScrollY(ratio * maxScroll);
            return true;
        }catch(Exception e){
            return false;
        }
    }

    // ===== 绘制 =====

    private static void drawOverlay(){
        LCanvas canvas = getCanvas();
        if(canvas == null || canvas.statements == null) return;
        LogicDialog dialog = Vars.ui.logic;
        if(dialog == null || !dialog.isShown()) return;

        switch(state){
            case SELECTING:
                drawSelectionBox(canvas);
                drawHighlights(canvas);
                break;
            case DRAGGING_MOVE:
                // 插入指示器在 LogicCanvas.draw() 中绘制（在积木下方）
                redrawSelectedBlocksOnTop(canvas);
                break;
            case DRAGGING_COPY:
                // 复制模式：原积木在原位由 DragLayout.draw 正常绘制
                // 插入指示器在 LogicCanvas.draw() 中绘制，半透明预览跟随鼠标
                drawCopyPreview(canvas);
                break;
            case SELECTED:
                // SELECTED 状态的高亮由 LogicCanvas.draw() 绘制，避免 toFront 干扰 MindustryX
                break;
            default:
                break;
        }

        // 彩色滚动条：overlay 在前时（SELECTING/DRAGGING）由此绘制；
        // IDLE/SELECTED 时由 LogicCanvas.draw() 绘制
        if(state == State.SELECTING || state == State.DRAGGING_MOVE || state == State.DRAGGING_COPY){
            drawColorScrollbar(canvas);
        }
    }

    /** 返回框选框/高亮框颜色，实时反映"松手后会进入的拖动模式"。
     *  拖动中按 state 判断；框选/选中态按 dragMode + Ctrl 实时判断，保证与按钮图标和拖动模式一致。 */
    private static Color getModeColor(){
        boolean isCopy;
        if(state == State.DRAGGING_MOVE){
            isCopy = false;
        }else if(state == State.DRAGGING_COPY){
            isCopy = true;
        }else{
            // 框选/选中态：与 startDrag 的判断保持一致
            boolean ctrlDown = Core.input.keyDown(KeyCode.controlLeft);
            isCopy = ctrlDown || dragMode == DragMode.COPY;
        }
        return isCopy ? Pal.heal : Pal.place;
    }

    private static void drawSelectionBox(LCanvas canvas){
        float minX = Math.min(selStartLocalX, selCurLocalX);
        float minY = Math.min(selStartLocalY, selCurLocalY);
        float maxX = Math.max(selStartLocalX, selCurLocalX);
        float maxY = Math.max(selStartLocalY, selCurLocalY);

        Vec2 bottomLeft = canvas.statements.localToStageCoordinates(Tmp.v1.set(minX, minY));
        Vec2 topRight = canvas.statements.localToStageCoordinates(Tmp.v2.set(maxX, maxY));

        float sx = bottomLeft.x;
        float sy = bottomLeft.y;
        float w = topRight.x - bottomLeft.x;
        float h = topRight.y - bottomLeft.y;

        Color modeColor = getModeColor();
        Draw.color(modeColor);
        Draw.alpha(FILL_ALPHA);
        Fill.crect(sx, sy, w, h);

        Draw.color(modeColor);
        Draw.alpha(BORDER_ALPHA);
        Lines.stroke(Scl.scl(1.5f), modeColor);
        Lines.rect(sx, sy, w, h);
        Draw.reset();
    }

    public static void drawHighlights(LCanvas canvas){
        if(selected.isEmpty()) return;

        // 计算所有选中积木的总包围框
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;
        for(StatementElem elem : selected){
            Vec2 v = elem.localToStageCoordinates(Tmp.v1.set(0, 0));
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
            maxX = Math.max(maxX, v.x + elem.getWidth());
            maxY = Math.max(maxY, v.y + elem.getHeight());
        }

        float pad = Scl.scl(4f);
        float x = minX - pad;
        float y = minY - pad;
        float w = maxX - minX + pad * 2;
        float h = maxY - minY + pad * 2;

        // 选中框：半透明填充 + 边框
        Color modeColor = getModeColor();
        Draw.color(modeColor);
        Draw.alpha(FILL_ALPHA);
        Fill.crect(x, y, w, h);

        Draw.color(modeColor);
        Draw.alpha(BORDER_ALPHA);
        Lines.stroke(Scl.scl(1.5f), modeColor);
        Lines.rect(x, y, w, h);
        Draw.reset();
    }

    /** 绘制彩色滚动条：在 ScrollPane 的垂直滚动条轨道上，按每个积木的比例绘制对应类别颜色。 */
    public static void drawColorScrollbar(LCanvas canvas){
        ScrollPane pane = canvas.pane;
        if(pane == null || !pane.hasScroll()) return;

        // 用反射读取 vScrollBounds（ScrollPane 内部的滚动条轨道 Rect，本地坐标）
        Rect vScrollBounds = null;
        try{
            if(vScrollBoundsField == null){
                vScrollBoundsField = ScrollPane.class.getDeclaredField("vScrollBounds");
                vScrollBoundsField.setAccessible(true);
            }
            vScrollBounds = (Rect)vScrollBoundsField.get(pane);
        }catch(Exception e){
            return;
        }
        if(vScrollBounds == null || vScrollBounds.width <= 0 || vScrollBounds.height <= 0) return;

        // 将 vScrollBounds（本地坐标）转换为 stage 坐标
        Vec2 bl = pane.localToStageCoordinates(Tmp.v1.set(vScrollBounds.x, vScrollBounds.y));
        float scrollbarX = bl.x;
        float scrollbarBottom = bl.y;
        float scrollbarW = vScrollBounds.width;
        float scrollbarH = vScrollBounds.height;
        float scrollbarTop = scrollbarBottom + scrollbarH;

        // 计算总高度和每个积木的位置
        Seq<Element> children = canvas.statements.getChildren();
        if(children.isEmpty()) return;

        float space = Scl.scl(10f);
        float totalHeight = 0;
        for(Element child : children){
            totalHeight += child.getPrefHeight() + space;
        }
        totalHeight -= space;
        if(totalHeight <= 0) return;

        // 积木总高度不超过可视区域时不绘制彩色滚动条
        if(totalHeight <= pane.getHeight()) return;

        // 绘制每个积木对应的颜色段，手动裁剪到滚动条可视区域内（不使用 ScissorStack，
        // 避免与 MindustryX 面板的 ScrollPane scissor 产生交集导致裁剪异常）
        float cy = 0;
        for(Element child : children){
            float elemH = child.getPrefHeight();
            float elemColorH = (elemH + space) / totalHeight * scrollbarH;
            float elemTop = scrollbarTop - (cy / totalHeight) * scrollbarH;

            Color c = Color.white;
            if(child instanceof StatementElem se && se.st != null){
                LCategory cat = se.st.category();
                if(cat != null && cat.color != null){
                    c = cat.color;
                }
            }

            // 手动裁剪到 [scrollbarBottom, scrollbarTop] 范围内
            float segTop = Math.min(scrollbarTop, elemTop);
            float segBottom = Math.max(scrollbarBottom, elemTop - elemColorH);
            if(segTop > segBottom){
                Draw.color(c);
                Draw.alpha(SCROLLBAR_SEG_ALPHA);
                Fill.crect(scrollbarX, segBottom, scrollbarW, segTop - segBottom);
            }

            cy += elemH + space;
        }
        Draw.flush();
        Draw.reset();
    }

    /** 是否正在拖动（供 LogicCanvas.draw() 查询） */
    public static boolean isDragging(){
        return state == State.DRAGGING_MOVE || state == State.DRAGGING_COPY;
    }

    /** 是否正在框选（供 LogicCanvas.draw() 查询） */
    public static boolean isSelecting(){
        return state == State.SELECTING;
    }

    /** 在画布坐标系中绘制插入指示器（在积木下方）。
     *  供 LogicCanvas.draw() 在 super.draw() 之前调用。
     *  indicatorX/Y 是 stage 坐标，直接用 identity 矩阵在 stage 坐标系绘制，
     *  避免坐标转换错误（localToStageCoordinates 方向反了会导致位置偏移）。 */
    public static void drawInsertIndicatorUnder(LCanvas canvas){
        if(dragInsertPos < 0) return;
        Mat oldTrans = tmpMat.set(Draw.trans());
        Draw.trans(tmpMat2.idt());
        Draw.reset();
        Tex.pane.draw(indicatorX, indicatorY, indicatorW, indicatorH);
        Draw.reset();
        Draw.trans(oldTrans);
    }

    private static void drawInsertIndicator(LCanvas canvas){
        if(dragInsertPos < 0) return;
        Draw.reset();
        Tex.pane.draw(indicatorX, indicatorY, indicatorW, indicatorH);
        Draw.reset();
    }

    /** 在正确的 transform 矩阵内重画选中积木，确保积木画在插入阴影上方。
     *  DRAGGING_MOVE 时用选中积木的 translation（鼠标偏移）作为绘制偏移。 */
    private static void redrawSelectedBlocksOnTop(LCanvas canvas){
        if(selected.isEmpty()) return;
        // 所有选中积木共享相同的 translation（在 updateDrag 中统一设置）
        StatementElem first = selected.iterator().next();
        drawElementsWithOffset(canvas, first.translation.x, first.translation.y, 1f);
    }

    /** 复制模式：绘制半透明的积木预览跟随鼠标。 */
    private static void drawCopyPreview(LCanvas canvas){
        if(selected.isEmpty()) return;

        float mx = Core.input.mouseX();
        float my = Core.input.mouseY();
        Vec2 stageMouse = new Vec2();
        Core.scene.screenToStageCoordinates(stageMouse.set(mx, my));
        Vec2 localMouse = new Vec2();
        canvas.statements.stageToLocalCoordinates(localMouse.set(stageMouse));
        float dx = localMouse.x - dragStartLocalX;
        float dy = localMouse.y - dragStartLocalY;

        drawElementsWithOffset(canvas, dx, dy, COPY_PREVIEW_ALPHA);
    }

    /** 统一的绘制方法：保存矩阵 → 设置 translation → 临时修改 x/y → draw → finally 恢复
     *  @param alpha 1f = 不透明（重画在顶层），0.5f = 半透明（复制预览） */
    private static void drawElementsWithOffset(LCanvas canvas, float dx, float dy, float alpha){
        if(selected.isEmpty()) return;

        Mat oldTrans = tmpMat.set(Draw.trans());

        Vec2 origin = canvas.statements.localToStageCoordinates(Tmp.v1.set(0, 0));
        tmpMat2.idt().setToTranslation(origin.x, origin.y);
        Draw.trans(tmpMat2);

        Draw.reset();
        Draw.alpha(alpha);
        for(StatementElem elem : selected){
            boolean oldCullable = elem.cullable;
            elem.cullable = false;
            elem.x += dx;
            elem.y += dy;
            try{
                elem.draw();
            }finally{
                elem.x -= dx;
                elem.y -= dy;
                elem.cullable = oldCullable;
            }
        }
        Draw.reset();

        Draw.trans(oldTrans);
    }
    // ===== 拖动移动 =====

    private static void executeDragMove(LCanvas canvas, int insertPos){
        clearDraggingField(canvas);

        resetAllTranslations(canvas);
        dragBaseYs = null;

        List<StatementElem> sorted = getSortedSelected(canvas);
        Seq<Element> children = canvas.statements.getChildren();
        int count = sorted.size();

        for(StatementElem elem : sorted){
            elem.remove();
        }

        int actualInsert = Math.max(0, Math.min(insertPos, children.size));

        for(int i = 0; i < count; i++){
            canvas.statements.addChildAt(actualInsert + i, sorted.get(i));
        }

        // 双重 invalidate + validate 处理高度变化，jumps.act 同步跳转线位置
        // saveAllJumpUI 通过 dest.parent.getChildren().indexOf(dest) 反查更新 destIndex，
        // 覆盖 jump 自身被移动 / dest 被移动 / 两者都被移动 / 都未被移动 所有情况
        finalizeLayout(canvas);
        saveAllJumpUI(canvas);
        // saveAllJumpUI 改变了 destIndex，需再次 act 让 JumpCurve 重新连接目标
        canvas.statements.jumps.act(0f);
        refreshStructureLayout(canvas);
        reselectRange(canvas, actualInsert, count);
        enterSelectedState(canvas);
        Log.debug("[LogicAssist] Drag-moved " + count + " blocks to position " + actualInsert);
    }

    // ===== 拖动复制 =====

    private static void prepareCopyData(LCanvas canvas){
        List<StatementElem> sorted = getSortedSelected(canvas);
        Seq<Element> children = canvas.statements.getChildren();

        // 用 copy() 保持 ExprStatement 折叠状态（write+read 会展开表达式为 op 链）
        clipboardCopies = new ArrayList<>();
        clipboardSources = new ArrayList<>();
        for(StatementElem elem : sorted){
            elem.st.saveUI();
            LStatement copy = elem.st.copy();
            Log.debug("[LogicAssist] prepareCopyData: st=@ copy=@", elem.st.getClass().getSimpleName(), copy == null ? "null" : copy.getClass().getSimpleName());
            if(copy != null){
                clipboardCopies.add(copy);
                clipboardSources.add(elem);
            }
        }
        clipboardSize = clipboardCopies.size();
    }

    private static void executeDragCopy(LCanvas canvas, int insertPos){
        clearDraggingField(canvas);

        resetAllTranslations(canvas);
        dragBaseYs = null;

        if(clipboardCopies == null || clipboardCopies.isEmpty()){
            enterSelectedState(canvas);
            return;
        }

        int currentSize = canvas.statements.getChildren().size;
        if(currentSize + clipboardSize + copiedBlockEndCount(clipboardSources) > LExecutor.maxInstructions){
            Log.debug("[LogicAssist] Copy aborted: would exceed maxInstructions");
            enterSelectedState(canvas);
            return;
        }

        // 从 clipboardCopies 创建新副本（每次复制都需要独立对象）
        Seq<LStatement> copies = new Seq<>();
        for(LStatement st : clipboardCopies){
            LStatement copy = st.copy();
            Log.debug("[LogicAssist] executeDragCopy: st=@ copy=@", st.getClass().getSimpleName(), copy == null ? "null" : copy.getClass().getSimpleName());
            copies.add(copy);
        }
        if(copies.isEmpty()){
            enterSelectedState(canvas);
            return;
        }

        int actualInsert = Math.max(0, Math.min(insertPos, canvas.statements.getChildren().size));

        insertCopiedStatements(canvas, actualInsert, copies, clipboardSources);

        finalizeLayout(canvas);
        // 更新所有 Jump 的 destIndex（反映插入后的新位置）并刷新跳转线
        saveAllJumpUI(canvas);
        canvas.statements.jumps.act(0f);
        refreshStructureLayout(canvas);
        reselectRange(canvas, actualInsert, copies.size);

        clipboardCopies = null;
        clipboardSize = 0;
        clipboardSources = null;

        enterSelectedState(canvas);
        Log.debug("[LogicAssist] Drag-copied " + copies.size + " blocks to position " + insertPos);
    }

    private static void cancelDrag(LCanvas canvas){
        clearDraggingField(canvas);

        resetAllTranslations(canvas);
        dragBaseYs = null;
        clipboardCopies = null;
        clipboardSize = 0;
        clipboardSources = null;
        dragInsertPos = -1;
        state = State.SELECTED;
        Log.debug("[LogicAssist] Drag cancelled.");
    }

    /** Delete 键快速删除选中积木 */
    private static void deleteSelected(LCanvas canvas){
        clearDraggingField(canvas);

        resetAllTranslations(canvas);

        List<StatementElem> sorted = getSortedSelected(canvas);
        int count = sorted.size();

        for(StatementElem elem : sorted){
            elem.remove();
        }

        saveAllJumpUI(canvas);
        finalizeLayout(canvas);
        refreshStructureLayout(canvas);

        selected.clear();
        state = State.IDLE;
        Log.debug("[LogicAssist] Deleted " + count + " blocks.");
    }

    // ===== 辅助方法 =====

    private static List<StatementElem> getSortedSelected(LCanvas canvas){
        List<StatementElem> sorted = new ArrayList<>(selected);
        Seq<Element> children = canvas.statements.getChildren();
        sorted.sort((a, b) -> {
            int ia = children.indexOf(a, true);
            int ib = children.indexOf(b, true);
            return Integer.compare(ia, ib);
        });
        return sorted;
    }

    /** 从 destIndex 重建 JumpStatement.dest 引用。
     *  JumpStatement 默认 copy() 用 write→read 序列化，dest 是 transient 字段不会被复制，
     *  setupUI() 是空方法不会自动重建。必须手动从 children 列表按 destIndex 查找。 */
    private static void resolveJumpDests(LCanvas canvas){
        Seq<Element> children = canvas.statements.getChildren();
        for(Element child : children){
            if(!(child instanceof StatementElem)) continue;
            LStatement st = ((StatementElem)child).st;
            if(st instanceof JumpStatement js && js.destIndex >= 0 && js.destIndex < children.size){
                Element destChild = children.get(js.destIndex);
                if(destChild instanceof StatementElem){
                    js.dest = (StatementElem)destChild;
                }
            }
        }
    }

    /** Number of generated ends needed when a copied selection omits a structure's matching end. */
    private static int copiedBlockEndCount(List<StatementElem> sources){
        if(sources == null) return 0;
        IdentityHashMap<StatementElem, Boolean> included = new IdentityHashMap<>();
        for(StatementElem source : sources) included.put(source, true);

        int result = 0;
        for(StatementElem source : sources){
            if(source.st instanceof SugarStatements.BeginStatement begin && !included.containsKey(begin.dest)){
                result++;
            }
        }
        return result;
    }

    /**
     * Inserts a copied selection as one stable batch. SugarCanvas.addAt() creates an end immediately
     * for a Begin block, but later insertions shift that end and invalidate its stored index.
     */
    private static void insertCopiedStatements(LCanvas canvas, int insertPos, Seq<LStatement> copies, List<StatementElem> sources){
        if(!(canvas instanceof SugarCanvas) || sources == null || sources.size() != copies.size){
            for(int i = 0; i < copies.size; i++){
                canvas.addAt(insertPos + i, copies.get(i));
                copies.get(i).setupUI();
            }
            return;
        }

        IdentityHashMap<StatementElem, Integer> sourcePositions = new IdentityHashMap<>();
        for(int i = 0; i < sources.size(); i++) sourcePositions.put(sources.get(i), i);

        for(int i = 0; i < copies.size; i++){
            canvas.statements.addChildAt(insertPos + i, canvas.new StatementElem(copies.get(i)));
        }

        List<SugarStatements.BeginStatement> unpairedBegins = new ArrayList<>();
        for(int i = 0; i < copies.size; i++){
            if(!(copies.get(i) instanceof SugarStatements.BeginStatement copyBegin)
                || !(sources.get(i).st instanceof SugarStatements.BeginStatement sourceBegin)) continue;

            Integer target = sourcePositions.get(sourceBegin.dest);
            if(target != null && copies.get(target) instanceof SugarStatements.BlockEndStatement){
                copyBegin.dest = copies.get(target).elem;
            }else{
                unpairedBegins.add(copyBegin);
            }
        }

        // Close unmatched copied blocks after the batch, from inner to outer.
        int endAt = insertPos + copies.size;
        for(int i = unpairedBegins.size() - 1; i >= 0; i--){
            SugarStatements.BlockEndStatement end = new SugarStatements.BlockEndStatement();
            canvas.statements.addChildAt(endAt++, canvas.new StatementElem(end));
            unpairedBegins.get(i).dest = end.elem;
        }

        for(int i = 0; i < copies.size; i++){
            LStatement copy = copies.get(i);
            if(copy instanceof JumpStatement copyJump && sources.get(i).st instanceof JumpStatement sourceJump){
                Integer target = sourcePositions.get(sourceJump.dest);
                copyJump.dest = target == null ? sourceJump.dest : copies.get(target).elem;
            }else if(!(copy instanceof SugarStatements.BeginStatement)){
                copy.setupUI();
            }
        }
    }

    /** 从 children 中重新选中指定范围的积木 */
    private static void reselectRange(LCanvas canvas, int start, int count){
        selected.clear();
        Seq<Element> newChildren = canvas.statements.getChildren();
        for(int i = start; i < start + count && i < newChildren.size; i++){
            if(newChildren.get(i) instanceof StatementElem){
                selected.add((StatementElem)newChildren.get(i));
            }
        }
        // 接管新选中积木的 copy/move 按钮图标
        updateSelectedButtonIcons(canvas);
    }

    /** 双重 invalidate + validate，处理高度变化后的布局稳定 */
    private static void finalizeLayout(LCanvas canvas){
        canvas.statements.updateJumpHeights = true;
        canvas.statements.invalidate();
        canvas.statements.validate();
        // layout() 发现 height 变化后调用 invalidateHierarchy() 标记父节点，
        // 但自身 layout() 已执行完毕（用的是旧 height）。需要第二次 validate 用新 height 重新布局。
        canvas.statements.invalidate();
        canvas.statements.validate();
        // 更新跳转线位置（基于最终布局）
        canvas.statements.jumps.act(0f);
    }

    /** Keep Logic Sugar's structural indentation and guide layer in sync with the reordered child list. */
    private static void refreshStructureLayout(LCanvas canvas){
        if(canvas instanceof SugarCanvas sugarCanvas){
            sugarCanvas.refreshStructureLayout();
        }
    }

    /** 更新所有积木的 JumpStatement destIndex（移动/删除后调用） */
    private static void saveAllJumpUI(LCanvas canvas){
        for(Element child : canvas.statements.getChildren()){
            if(child instanceof StatementElem se && se.st instanceof JumpStatement js){
                // dest 可能指向已删除的积木（dest.parent == null），需要安全检查
                if(js.dest == null || js.dest.parent == null){
                    js.destIndex = -1;
                }else{
                    js.saveUI();
                }
            }
        }
    }

    /** 进入 SELECTED 状态 */
    private static void enterSelectedState(LCanvas canvas){
        state = State.SELECTED;
    }

    private static LCanvas getCanvas(){
        try{
            if(Vars.ui.logic == null) return null;
            return Vars.ui.logic.canvas;
        }catch(Exception e){
            return null;
        }
    }

    private static boolean isPrivileged(LCanvas canvas){
        try{
            return privilegedField.getBoolean(canvas);
        }catch(Exception e){
            return false;
        }
    }

    private static void clearDraggingField(LCanvas canvas){
        try{
            draggingField.set(canvas, null);
        }catch(Exception e){
            Log.warn("[LogicAssist] Failed to clear dragging field", e);
        }
    }
}
