package mindustry.logic;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.math.geom.Rect;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.event.Touchable;
import arc.scene.style.BaseDrawable;
import arc.scene.style.Drawable;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.WidgetGroup;
import arc.struct.Seq;
import arc.struct.SnapshotSeq;
import arc.util.Tmp;
import mindustry.Vars;
import mindustry.gen.Tex;
import mindustry.logic.LStatements.InvalidStatement;
import mindustry.logic.LStatements.JumpStatement;
import mindustry.logic.LStatements.PrintStatement;
import mindustry.logic.SugarStatements.BeginStatement;
import mindustry.logic.SugarStatements.BlockEndStatement;
import mindustry.logic.SugarStatements.CaseStatement;
import mindustry.logic.SugarStatements.ElseIfStatement;
import mindustry.logic.SugarStatements.ElseStatement;
import mindustry.logic.SugarStatements.IfBeginStatement;
import mindustry.logic.SugarStatements.SwitchBeginStatement;
import logicsugar.assist.BoxSelect;
import logicsugar.assist.JumpLineColor;
import logicsugar.assist.expr.ExprCompiler;
import logicsugar.assist.expr.ExprHook;
import logicsugar.assist.expr.ExprStatement;

import java.util.IdentityHashMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;

public class SugarCanvas extends LCanvas{
    private static final Color[] guideColors = {
        Color.valueOf("66c2ff"), Color.valueOf("ffb45c"), Color.valueOf("79d98b"),
        Color.valueOf("d58cff"), Color.valueOf("ffe066"), Color.valueOf("ff7f91")
    };

    final StructureController structure = new StructureController();
    private StructureGuideLayer guideLayer;
    private Group jumpLayer;
    private static final Field draggingField = field(LCanvas.class, "dragging");
    private static final Field privilegedField = field(LCanvas.class, "privileged");
    private static final Field spaceField = field(LCanvas.DragLayout.class, "space");
    private static final Field layoutJumpsField = optionalField(LCanvas.DragLayout.class, "jumps");
    private static final Field canvasJumpsField = optionalField(LCanvas.class, "jumps");
    private static final Field updateJumpHeightsField = optionalField(LCanvas.DragLayout.class, "updateJumpHeights");
    private static final Method recalculateMethod = optionalMethod(LCanvas.class, "recalculate");
    private static final Field addressLabelField = field(LCanvas.StatementElem.class, "addressLabel");
    private static final Field needsLayoutField = field(WidgetGroup.class, "needsLayout");

    public SugarCanvas(){
        super();
        setLayoutSpace();
        update(() -> {
            structure.normalizeElements();
            if(isDragging()) structure.expandAll();
            structure.refresh();
        });
    }

    @Override
    public void load(String asm){
        super.load(asm);
        ExprHook.foldAll(this);
    }

    @Override
    public String save(){
        structure.refresh();
        ExprHook.unfoldAll(this);
        String result = super.save();
        ExprHook.foldAll(this);
        return result;
    }

    @Override
    public void act(float delta){
        super.act(delta);
        updateMlogAddresses();
    }

    @Override
    public void draw(){
        if(BoxSelect.isDragging()) BoxSelect.drawInsertIndicatorUnder(this);
        super.draw();
        JumpLineColor.patchAllCurves(this);
        if(!BoxSelect.isSelecting() && !BoxSelect.isDragging()){
            arc.math.Mat oldTrans = new arc.math.Mat().set(Draw.trans());
            Draw.trans().idt();
            BoxSelect.drawHighlights(this);
            BoxSelect.drawColorScrollbar(this);
            Draw.trans(oldTrans);
        }
    }

    private void updateMlogAddresses(){
        if(statements == null) return;
        Seq<Element> children = statements.getChildren();
        if(children.isEmpty()) return;

        statements.invalidate();
        statements.validate();

        boolean changed = false;
        int mlogLine = 0;
        for(Element child : children){
            if(!(child instanceof LCanvas.StatementElem elem)) continue;

            int lineCount = 1;
            if(elem.st instanceof ExprStatement expression){
                if(expression.lastOps == null){
                    try{
                        expression.lastOps = ExprCompiler.compile(expression.dest, expression.expr);
                    }catch(Exception ignored){}
                }
                if(expression.lastOps != null) lineCount = expression.lastOps.size();
            }

            String text = lineCount > 1
                ? mlogLine + "->" + (mlogLine + lineCount - 1)
                : Integer.toString(mlogLine);
            try{
                Label label = (Label)addressLabelField.get(elem);
                if(label != null && !label.getText().toString().equals(text)){
                    label.setText(text);
                    changed = true;
                }
            }catch(IllegalAccessException ignored){}
            mlogLine += lineCount;
        }

        if(changed){
            try{
                needsLayoutField.setBoolean(statements, false);
            }catch(IllegalAccessException ignored){}
        }
    }

    @Override
    public void rebuild(){
        super.rebuild();
        setLayoutSpace();
        installGuideLayer();
    }

    private void setLayoutSpace(){
        if(statements == null) return;
        try{
            spaceField.setFloat(statements, 0f);
        }catch(IllegalAccessException exception){
            throw new RuntimeException("Unable to configure Logic Sugar layout", exception);
        }
    }

    private boolean isDragging(){
        try{
            return draggingField.get(this) != null;
        }catch(IllegalAccessException exception){
            return false;
        }
    }

    private static Field field(Class<?> type, String name){
        try{
            Field result = type.getDeclaredField(name);
            result.setAccessible(true);
            return result;
        }catch(ReflectiveOperationException exception){
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Field optionalField(Class<?> type, String name){
        try{
            Field result = type.getDeclaredField(name);
            result.setAccessible(true);
            return result;
        }catch(ReflectiveOperationException exception){
            return null;
        }
    }

    private static Method optionalMethod(Class<?> type, String name, Class<?>... parameterTypes){
        try{
            Method result = type.getDeclaredMethod(name, parameterTypes);
            result.setAccessible(true);
            return result;
        }catch(ReflectiveOperationException exception){
            return null;
        }
    }

    private void installGuideLayer(){
        jumpLayer = resolveJumpLayer(this);
        if(jumpLayer == null) return;
        guideLayer = new StructureGuideLayer();
        guideLayer.touchable = Touchable.disabled;
        guideLayer.fillParent = true;
        guideLayer.cullable = false;
        jumpLayer.addChildAt(0, guideLayer);
    }

    /** Returns the jump overlay for both modern and legacy LCanvas layouts. */
    public static Group getJumpLayer(LCanvas canvas){
        if(canvas instanceof SugarCanvas sugar && sugar.jumpLayer != null){
            return sugar.jumpLayer;
        }
        return resolveJumpLayer(canvas);
    }

    private static Group resolveJumpLayer(LCanvas canvas){
        if(canvas == null || canvas.statements == null) return null;

        Field field = layoutJumpsField;
        Object owner = canvas.statements;
        if(field == null){
            field = canvasJumpsField;
            owner = canvas;
        }
        if(field == null) return null;

        try{
            return (Group)field.get(owner);
        }catch(IllegalAccessException | ClassCastException exception){
            return null;
        }
    }

    /** Marks jump heights dirty on modern clients and recalculates them on legacy clients. */
    public static void markJumpHeightsDirty(LCanvas canvas){
        if(canvas == null || canvas.statements == null) return;

        if(updateJumpHeightsField != null){
            try{
                updateJumpHeightsField.setBoolean(canvas.statements, true);
                return;
            }catch(IllegalAccessException ignored){}
        }

        recalculateLegacyJumps(canvas);
    }

    /** Refreshes legacy jump metrics and updates the jump overlay after a layout change. */
    public static void refreshJumpLayer(LCanvas canvas){
        recalculateLegacyJumps(canvas);
        Group jumps = getJumpLayer(canvas);
        if(jumps != null) jumps.act(0f);
    }

    private static void recalculateLegacyJumps(LCanvas canvas){
        if(updateJumpHeightsField != null || recalculateMethod == null) return;
        try{
            recalculateMethod.invoke(canvas);
        }catch(ReflectiveOperationException ignored){}
    }

    @Override
    public void add(LStatement statement){
        statements.addChild(new SugarStatementElem(statement));
    }

    @Override
    public void addAt(int at, LStatement statement){
        SugarStatementElem added = new SugarStatementElem(statement);
        statements.addChildAt(at, added);

        if(statement instanceof BeginStatement begin && begin.destIndex < 0){
            SugarStatementElem end = new SugarStatementElem(new BlockEndStatement());
            statements.addChildAt(at + 1, end);
            begin.dest = end;
            begin.destIndex = at + 1;
            markJumpHeightsDirty(this);
        }
        structure.refresh();
    }

    public static void refreshCurrent(){
        SugarCanvas canvas = current();
        if(canvas != null) canvas.refreshStructureLayout();
    }

    /** Rebuild structure-dependent presentation immediately after an external statement reorder. */
    public void refreshStructureLayout(){
        if(statements == null) return;
        structure.refresh();
        markJumpHeightsDirty(this);
        statements.invalidate();
        statements.validate();
        refreshJumpLayer(this);
    }

    public static boolean canLink(BeginStatement begin, StatementElem target){
        SugarCanvas canvas = current();
        return canvas != null && canvas.structure.canLink(begin, target);
    }

    public static boolean isValidLink(BeginStatement begin, StatementElem target){
        SugarCanvas canvas = current();
        return canvas != null && canvas.structure.isValid(begin, target);
    }

    public static SugarCanvas current(){
        if(Vars.ui != null && Vars.ui.logic != null && Vars.ui.logic.canvas instanceof SugarCanvas canvas) return canvas;
        return null;
    }

    public class SugarStatementElem extends StatementElem{
        int structureDepth = -1;
        boolean foldedHidden;
        boolean structureInvalid;
        float inset;

        SugarStatementElem(LStatement statement){
            super(statement);
            background(new InsetDrawable(this, Tex.whitePane));
            update(this::refreshInset);
            if(statement instanceof BlockEndStatement && getCells().size > 1){
                getCells().peek().height(0f).minHeight(0f).pad(0f);
                getChildren().peek().visible = false;
            }
        }

        void applyStructure(int depth, boolean hidden, boolean invalid){
            if(structureDepth != depth || foldedHidden != hidden || structureInvalid != invalid){
                structureDepth = depth;
                foldedHidden = hidden;
                structureInvalid = invalid;
                visible = !hidden;
                setColor(invalid ? mindustry.graphics.Pal.remove : st.category().color);
                invalidateHierarchy();
            }
            refreshInset();
        }

        private void refreshInset(){
            // marginLeft() applies Scl.scl() itself, so inset must stay in design units here;
            // pre-scaling it would double-scale (visible at 200% UI scale).
            float unit = Core.graphics.isPortrait() ? 17f : 24f;
            float minWidth = Core.graphics.isPortrait() ? 285f : 360f;
            float maxInset = Math.max(0f, getWidth() / Scl.scl(1f) - minWidth);
            float nextInset = Math.min(Math.max(0, structureDepth) * unit, maxInset);
            if(Math.abs(inset - nextInset) > 0.1f){
                inset = nextInset;
                marginLeft(inset);
                marginBottom(7f);
                invalidateHierarchy();
            }
        }

        @Override
        public float getPrefHeight(){
            return foldedHidden ? 0f : super.getPrefHeight();
        }

        @Override
        public void copy(){
            st.saveUI();
            LStatement copied = st.copy();
            if(copied == null) return;

            if(copied instanceof JumpStatement jump && jump.destIndex != -1){
                int index = statements.getChildren().indexOf(this);
                if(index != -1 && index < jump.destIndex) jump.destIndex++;
            }

            int index = statements.getChildren().indexOf(this);
            SugarCanvas.this.addAt(index + 1, copied);
            copied.setupUI();
            markJumpHeightsDirty(SugarCanvas.this);
        }

        /**
         * Toggles this statement between its block form and a raw-text {@code print} form.
         * This is a runtime override of MindustryX's {@code StatementElem#toggleComment}
         * (identical signature): the original serializes via the generated {@code LogicIO.write},
         * which only knows {@code @RegisterStatement} statements and silently emits nothing for
         * Neon's custom-parser statements, dropping their code. This uses {@code LStatement.write},
         * which every statement (including Neon's) implements, so the round trip preserves them.
         */
        public void toggleComment(){
            StatementElem newElem;
            if(st instanceof PrintStatement pst && !pst.value.isEmpty()){ //print -> block
                String code = pst.value.replace("_", " ");
                LStatement stNew;
                try{
                    stNew = LAssembler.read(code, isPrivileged()).first();
                }catch(Exception e){
                    showConvertError();
                    return;
                }
                if(stNew instanceof InvalidStatement){
                    showConvertError();
                    return;
                }
                newElem = new SugarStatementElem(stNew);
            }else{ //block -> print
                st.saveUI();
                StringBuilder thisText = new StringBuilder();
                st.write(thisText);
                PrintStatement stNew = new PrintStatement();
                stNew.value = thisText.toString().replace(' ', '_');
                newElem = new SugarStatementElem(stNew);
            }

            //preserve jump destinations that referenced this element
            for(Element c : statements.getChildren()){
                if(c instanceof StatementElem ste && ste.st instanceof JumpStatement jst && (jst.dest == null || jst.dest == st.elem)){
                    if(jst.destIndex < 0 || jst.destIndex >= statements.getChildren().size) continue;
                    jst.saveUI();
                }
            }
            statements.addChildBefore(this, newElem);
            remove();
            for(Element c : statements.getChildren()){
                if(c instanceof StatementElem ste && ste.st instanceof JumpStatement jst && (jst.dest == null || jst.dest == st.elem)){
                    if(jst.destIndex < 0 || jst.destIndex >= statements.getChildren().size) continue;
                    jst.setupUI();
                }
            }
            newElem.st.setupUI();
            structure.refresh();
            markJumpHeightsDirty(SugarCanvas.this);
        }

        private void showConvertError(){
            Vars.ui.showInfoFade(Core.bundle.get("logicsugar.textEdit.convertError", "Cannot convert this statement to/from text."));
        }

        /** Reads LCanvas.privileged via reflection: it is package-private and the mod class
         *  loader cannot access it directly (IllegalAccessError) even though the package names match. */
        private boolean isPrivileged(){
            try{
                return privilegedField.getBoolean(SugarCanvas.this);
            }catch(IllegalAccessException e){
                return false;
            }
        }
    }

    private static class InsetDrawable extends BaseDrawable{
        private final SugarStatementElem owner;
        private final Drawable source;

        InsetDrawable(SugarStatementElem owner, Drawable source){
            super(source);
            this.owner = owner;
            this.source = source;
        }

        @Override
        public void draw(float x, float y, float width, float height){
            float offset = Scl.scl(owner.inset);
            source.draw(x + offset, y, Math.max(0f, width - offset), height);
        }

        @Override
        public void draw(float x, float y, float originX, float originY, float width, float height, float scaleX, float scaleY, float rotation){
            float offset = Scl.scl(owner.inset);
            source.draw(x + offset, y, originX, originY, Math.max(0f, width - offset), height, scaleX, scaleY, rotation);
        }
    }

    final class StructureController{
        final Seq<Pair> pairs = new Seq<>();
        final IdentityHashMap<StatementElem, Integer> indices = new IdentityHashMap<>();
        boolean[] compilerInvalid = {};
        private int signature;

        void refresh(){
            if(statements == null) return;
            normalizeElements();
            SnapshotSeq<Element> children = statements.getChildren();
            syncStatementIndices(children);
            int nextSignature = 31 * children.size + Math.round(getWidth()) + Math.round(statements.getWidth()) + (Core.graphics.isPortrait() ? 1 : 0);
            for(int i = 0; i < children.size; i++){
                StatementElem elem = (StatementElem)children.get(i);
                nextSignature = 31 * nextSignature + System.identityHashCode(elem);
                nextSignature = 31 * nextSignature + Math.round(elem.getWidth());
                if(elem.st instanceof BeginStatement begin){
                    nextSignature = 31 * nextSignature + System.identityHashCode(begin.dest);
                    nextSignature = 31 * nextSignature + (begin.collapsed ? 1 : 0);
                }
            }
            if(nextSignature == signature) return;
            signature = nextSignature;
            rebuildStructure(children);
        }

        void normalizeElements(){
            if(statements == null) return;
            SnapshotSeq<Element> current = statements.getChildren();
            boolean needsNormalization = false;
            for(Element child : current){
                if(!(child instanceof SugarStatementElem)){
                    needsNormalization = true;
                    break;
                }
            }
            if(!needsNormalization) return;

            // Preserve index-based links before replacing elements created by an external LCanvas path.
            for(Element child : current){
                ((StatementElem)child).st.saveUI();
            }

            for(int i = 0; i < statements.getChildren().size; i++){
                Element child = statements.getChildren().get(i);
                if(child instanceof SugarStatementElem) continue;

                StatementElem old = (StatementElem)child;
                LStatement statement = old.st;
                SugarStatementElem replacement = new SugarStatementElem(statement);
                statements.addChildAt(i, replacement);
                old.remove();
            }

            for(Element child : statements.getChildren()){
                ((StatementElem)child).st.setupUI();
            }
            signature = 0;
        }

        void expandAll(){
            boolean changed = false;
            for(Element child : statements.getChildren()){
                if(((StatementElem)child).st instanceof BeginStatement begin && begin.collapsed){
                    begin.collapsed = false;
                    changed = true;
                }
            }
            if(changed) signature = 0;
        }

        boolean canLink(BeginStatement begin, StatementElem target){
            if(target == null) return true;
            if(!(target.st instanceof BlockEndStatement) || begin.elem == null) return false;
            refreshIndices();
            Integer from = indices.get(begin.elem), to = indices.get(target);
            if(from == null || to == null || to <= from) return false;
            for(Element child : statements.getChildren()){
                LStatement statement = ((StatementElem)child).st;
                if(statement instanceof BeginStatement other && other != begin && other.dest == target) return false;
            }
            return !crossesExisting(begin, from, to);
        }

        boolean isValid(BeginStatement begin, StatementElem target){
            if(target == null || !(target.st instanceof BlockEndStatement) || begin.elem == null) return false;
            refreshIndices();
            Integer from = indices.get(begin.elem), to = indices.get(target);
            if(from == null || to == null || to <= from) return false;
            for(Element child : statements.getChildren()){
                LStatement statement = ((StatementElem)child).st;
                if(statement instanceof BeginStatement other && other != begin && other.dest == target) return false;
            }
            return !crossesExisting(begin, from, to);
        }

        private boolean crossesExisting(BeginStatement begin, int from, int to){
            for(Element child : statements.getChildren()){
                LStatement statement = ((StatementElem)child).st;
                if(!(statement instanceof BeginStatement other) || other == begin || other.elem == null || other.dest == null) continue;
                Integer otherFrom = indices.get(other.elem), otherTo = indices.get(other.dest);
                if(otherFrom == null || otherTo == null || otherTo <= otherFrom) continue;
                if((from < otherFrom && otherFrom < to && to < otherTo) || (otherFrom < from && from < otherTo && otherTo < to)) return true;
            }
            return false;
        }

        private void rebuildStructure(SnapshotSeq<Element> children){
            refreshIndices();
            pairs.clear();
            IdentityHashMap<StatementElem, Pair> claimed = new IdentityHashMap<>();
            Seq<LStatement> source = new Seq<>(children.size);
            for(Element child : children) source.add(((StatementElem)child).st);
            compilerInvalid = SugarCompiler.invalidStatements(source);

            for(int i = 0; i < children.size; i++){
                StatementElem elem = (StatementElem)children.get(i);
                if(elem.st instanceof BeginStatement begin){
                    Integer end = begin.dest == null ? null : indices.get(begin.dest);
                    Pair pair = new Pair(begin, elem, begin.dest, i, end == null ? -1 : end);
                    pair.valid = end != null && end > i && begin.dest.st instanceof BlockEndStatement && !claimed.containsKey(begin.dest);
                    if(pair.valid){
                        pair.valid = !crossesExisting(begin, i, end);
                        if(pair.valid) claimed.put(begin.dest, pair);
                    }
                    pairs.add(pair);
                }
            }

            for(Element child : children){
                SugarStatementElem elem = (SugarStatementElem)child;
                int index = children.indexOf(child, true);
                elem.applyStructure(0, false, compilerInvalid[index] || elem.st instanceof BlockEndStatement && !claimed.containsKey(elem));
            }
            assignRange(0, children.size, 0, false, false, children);
            statements.invalidateHierarchy();
            markJumpHeightsDirty(SugarCanvas.this);
        }

        /** Match each closing block with the nearest still-open structured block. */
        private void syncStatementIndices(SnapshotSeq<Element> children){
            Deque<BeginStatement> opens = new ArrayDeque<>();
            for(Element child : children){
                StatementElem elem = (StatementElem)child;
                if(elem.st instanceof BeginStatement begin){
                    opens.push(begin);
                }else if(elem.st instanceof BlockEndStatement && !opens.isEmpty()){
                    BeginStatement begin = opens.pop();
                    begin.dest = elem;
                    begin.destIndex = children.indexOf(elem, true);
                }
            }
            while(!opens.isEmpty()){
                BeginStatement begin = opens.pop();
                begin.dest = null;
                begin.destIndex = -1;
            }
            for(Element child : children){
                LStatement statement = ((StatementElem)child).st;
                if(statement instanceof JumpStatement jump && (jump.dest == null || jump.dest.parent == null)){
                    jump.dest = null;
                    jump.destIndex = -1;
                }
                statement.saveUI();
            }
        }

        private void assignRange(int from, int to, int depth, boolean switchBody, boolean ifBody, SnapshotSeq<Element> children){
            int currentDepth = depth;
            for(int i = from; i < to; i++){
                SugarStatementElem elem = (SugarStatementElem)children.get(i);
                Pair pair = pairAt(i);

                if(switchBody && elem.st instanceof CaseStatement){
                    elem.applyStructure(depth, false, compilerInvalid[i]);
                    currentDepth = depth + 1;
                    continue;
                }

                if(ifBody && (elem.st instanceof ElseIfStatement || elem.st instanceof ElseStatement)){
                    //elif/else are siblings of the if-begin, not nested under it: keep them at the if's depth
                    elem.applyStructure(depth - 1, false, compilerInvalid[i]);
                    currentDepth = depth;
                    continue;
                }

                if(pair != null && pair.valid && pair.endIndex < to){
                    elem.applyStructure(currentDepth, false, compilerInvalid[i]);
                    SugarStatementElem end = (SugarStatementElem)children.get(pair.endIndex);
                    if(pair.begin.collapsed){
                        for(int at = i + 1; at < pair.endIndex; at++){
                            ((SugarStatementElem)children.get(at)).applyStructure(currentDepth + 1, true, false);
                        }
                    }else{
                        assignRange(i + 1, pair.endIndex, currentDepth + 1, pair.begin instanceof SwitchBeginStatement, pair.begin instanceof IfBeginStatement, children);
                    }
                    end.applyStructure(currentDepth, false, compilerInvalid[pair.endIndex]);
                    i = pair.endIndex;
                    continue;
                }

                boolean invalid = compilerInvalid[i] || elem.st instanceof BeginStatement || (elem.st instanceof BlockEndStatement && !isClaimed(elem));
                elem.applyStructure(currentDepth, false, invalid);
            }
        }

        private Pair pairAt(int beginIndex){
            for(Pair pair : pairs) if(pair.beginIndex == beginIndex) return pair;
            return null;
        }

        private boolean isClaimed(StatementElem end){
            for(Pair pair : pairs) if(pair.valid && pair.end == end) return true;
            return false;
        }

        private void refreshIndices(){
            indices.clear();
            SnapshotSeq<Element> children = statements.getChildren();
            for(int i = 0; i < children.size; i++) indices.put((StatementElem)children.get(i), i);
        }
    }

    final class StructureGuideLayer extends Element{
        @Override
        public void draw(){
            // statements and jumps are sibling overlays; use their common parent so scrolling
            // moves the structure cards and this guide by the same transform.
            if(parent == null || parent.parent == null) return;
            Group common = parent.parent;
            Rect cullingArea = parent.getCullingArea();
            float visibleBottom = Float.NEGATIVE_INFINITY;
            float visibleTop = Float.POSITIVE_INFINITY;
            if(cullingArea != null){
                Vec2 cullBottom = Tmp.v3.set(0f, cullingArea.y);
                Vec2 cullTop = Tmp.v4.set(0f, cullingArea.y + cullingArea.height);
                localToAscendantCoordinates(common, cullBottom);
                localToAscendantCoordinates(common, cullTop);
                visibleBottom = Math.min(cullBottom.y, cullTop.y);
                visibleTop = Math.max(cullBottom.y, cullTop.y);
            }

            for(Pair pair : structure.pairs){
                if(!pair.valid || pair.beginElem.foldedHidden || pair.end == null || !pair.end.visible) continue;
                SugarStatementElem begin = (SugarStatementElem)pair.beginElem;
                SugarStatementElem end = (SugarStatementElem)pair.end;
                Color color = guideColors[Math.floorMod(begin.structureDepth, guideColors.length)];

                float guideX = Scl.scl(begin.inset + 6f);
                Vec2 beginBottom = Tmp.v1.set(guideX, 0f);
                Vec2 endTop = Tmp.v2.set(guideX, end.getHeight());
                begin.localToAscendantCoordinates(common, beginBottom);
                end.localToAscendantCoordinates(common, endTop);
                // Non-transform groups carry the scroll offset in this layer's live draw position.
                localToAscendantCoordinates(common, beginBottom);
                localToAscendantCoordinates(common, endTop);

                float lineBottom = Math.max(Math.min(beginBottom.y, endTop.y), visibleBottom);
                float lineTop = Math.min(Math.max(beginBottom.y, endTop.y), visibleTop);
                if(lineTop <= lineBottom) continue;

                Draw.color(color, parentAlpha);
                Lines.stroke(Scl.scl(2.2f));
                Lines.line(beginBottom.x, lineBottom, beginBottom.x, lineTop);
            }
            Draw.reset();
        }
    }

    static final class Pair{
        final BeginStatement begin;
        final SugarStatementElem beginElem;
        final StatementElem end;
        final int beginIndex, endIndex;
        boolean valid;

        Pair(BeginStatement begin, StatementElem beginElem, StatementElem end, int beginIndex, int endIndex){
            this.begin = begin;
            this.beginElem = (SugarStatementElem)beginElem;
            this.end = end;
            this.beginIndex = beginIndex;
            this.endIndex = endIndex;
        }
    }
}
