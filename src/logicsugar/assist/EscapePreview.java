package logicsugar.assist;

import arc.Core;
import arc.graphics.Color;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.Touchable;
import arc.scene.ui.Label;
import arc.scene.ui.TextField;
import arc.scene.ui.ScrollPane;
import mindustry.logic.LAssembler;
import mindustry.logic.SugarCanvas;
import mindustry.ui.Styles;

import java.lang.reflect.Method;

/** A display-only preview for mlog string escapes in the currently focused logic field.
 *  The source field is never rewritten, so saving/copying retains the exact wire text. */
public final class EscapePreview{
    public enum Status{ none, preview, unsupported, invalid }

    public record Result(Status status, String text){}

    private static final Method unescapeMethod = findUnescape();
    /** True only after invoking the runtime method and checking all four v160 behaviours. */
    private static final boolean modernEscapes = probeModernUnescape();
    private final Vec2 position = new Vec2();
    private final Vec2 paneMin = new Vec2();
    private final Vec2 paneMax = new Vec2();
    private Label label;

    public void update(SugarCanvas canvas){
        Element focus = Core.scene == null ? null : Core.scene.getKeyboardFocus();
        if(!(focus instanceof TextField field) || !canShow(canvas, field)){
            hide(canvas == null || Core.scene == null || canvas.getScene() != Core.scene);
            return;
        }

        Result result = analyze(field.getText(), modernEscapes);
        if(result.status == Status.none){
            hide();
            return;
        }

        if(label == null){
            label = new Label("", Styles.outlineLabel);
            label.touchable = Touchable.disabled;
            label.setFontScale(0.75f);
        }
        if(label.parent != Core.scene.root){
            if(label.parent != null) label.remove();
            Core.scene.root.addChild(label);
        }

        String prefix;
        if(result.status == Status.preview){
            prefix = Core.bundle.get("logicsugar.escape.preview", "escape preview") + ": ";
            label.setColor(Color.lightGray);
        }else if(result.status == Status.unsupported){
            prefix = Core.bundle.get("logicsugar.escape.unsupported", "unsupported by this game build") + ": ";
            label.setColor(Color.orange);
        }else{
            prefix = Core.bundle.get("logicsugar.escape.invalid", "invalid escape") + ": ";
            label.setColor(Color.scarlet);
        }
        label.setText(prefix + singleLine(result.text));
        label.pack();
        if(!place(field, canvas.pane, label.getWidth(), label.getHeight())){
            hide();
            return;
        }
        label.visible = true;
        label.toFront();
    }

    private void hide(){
        hide(false);
    }

    private void hide(boolean detach){
        if(label != null){
            label.visible = false;
            // A dialog/canvas can be removed from the scene while its update callback has one
            // more frame to run.  Detach the overlay then, so it cannot survive the dialog.
            if(detach && label.parent != null) label.remove();
        }
    }

    private static boolean canShow(SugarCanvas canvas, TextField field){
        if(canvas == null || Core.scene == null || canvas.getScene() != Core.scene
            || !canvas.visible || !field.visible || field.getScene() != Core.scene
            || canvas.pane == null || !canvas.pane.visible || !descendantOf(field, canvas)) return false;

        // Do not show a label for a field that is merely focused while its statement/card is
        // hidden by a folded block.  Checking all ancestors also handles a hidden dialog.
        for(Element current = field; current != null && current != canvas; current = current.parent){
            if(!current.visible) return false;
        }
        return true;
    }

    private boolean place(TextField field, ScrollPane pane, float width, float height){
        pane.localToStageCoordinates(paneMin.set(0f, 0f));
        pane.localToStageCoordinates(paneMax.set(pane.getWidth(), pane.getHeight()));

        // Prefer the right side, then the left side.  If neither side fits in the scroll pane,
        // suppress the preview instead of placing it over another card or outside the viewport.
        field.localToStageCoordinates(position.set(field.getWidth() + 6f,
            (field.getHeight() - height) / 2f));
        if(fits(position.x, position.y, width, height)){
            labelPosition(position.x, position.y);
            return true;
        }

        field.localToStageCoordinates(position.set(-width - 6f,
            (field.getHeight() - height) / 2f));
        if(fits(position.x, position.y, width, height)){
            labelPosition(position.x, position.y);
            return true;
        }
        return false;
    }

    private boolean fits(float x, float y, float width, float height){
        return x >= paneMin.x && y >= paneMin.y
            && x + width <= paneMax.x && y + height <= paneMax.y;
    }

    private void labelPosition(float x, float y){
        label.setPosition(x, y);
    }

    private static boolean descendantOf(Element element, Element ancestor){
        return element != null && ancestor != null && element.isDescendantOf(ancestor);
    }

    private static Method findUnescape(){
        try{
            Method method = LAssembler.class.getDeclaredMethod("unescape", String.class);
            method.setAccessible(true);
            return method;
        }catch(ReflectiveOperationException | RuntimeException ignored){
            return null;
        }
    }

    /**
     * Presence alone is not enough: some forks carry a compatibility stub, and v159 has no
     * modern decoder at all.  Invoke the actual runtime method with one probe containing each
     * supported escape plus an unknown escape, and require the exact UTF-16 result.
     */
    private static boolean probeModernUnescape(){
        if(unescapeMethod == null) return false;
        String probe = new String(new char[]{'\\', 'n', '\\', '"', '\\', '\\', '\\', 'u', '0', '0', '4', '1', '\\', 'q'});
        String expected = new String(new char[]{'\n', '"', '\\', 'A', '\\', 'q'});
        try{
            Object decoded = unescapeMethod.invoke(null, probe);
            return decoded instanceof String && expected.equals(decoded);
        }catch(Throwable ignored){
            return false;
        }
    }

    /** Exposed for the compatibility self-test; this value comes from a real invocation. */
    public static boolean modernEscapesSupported(){
        return modernEscapes;
    }

    /**
     * Decodes the exact quoted mlog string grammar.  The outer quotes are deliberately retained
     * in the result because the preview is a view of the complete source token.  Unknown
     * escapes remain byte-for-byte unchanged; a four-digit Unicode escape appends one Java char,
     * matching the
     * upstream decoder's UTF-16 code-unit semantics.
     */
    public static Result analyze(String source, boolean modern){
        if(!isCompleteQuotedString(source)) return new Result(Status.none, "");
        String body = source.substring(1, source.length() - 1);
        if(body.indexOf('\\') < 0) return new Result(Status.none, "");

        StringBuilder out = new StringBuilder(source.length());
        out.append('"');
        boolean recognized = false;
        boolean needsModern = false;
        for(int i = 0; i < body.length(); i++){
            char current = body.charAt(i);
            if(current != '\\' || i + 1 >= body.length()){
                out.append(current);
                continue;
            }
            char escaped = body.charAt(++i);
            if(escaped == 'n'){
                recognized = true;
                out.append('\n');
            }else if(escaped == '"' || escaped == '\\'){
                recognized = true;
                needsModern = true;
                out.append(escaped);
            }else if(escaped == 'u'){
                recognized = true;
                needsModern = true;
                if(i + 4 >= body.length()) return new Result(Status.invalid, "\\u requires four hex digits");
                int value = 0;
                for(int digit = 1; digit <= 4; digit++){
                    int hex = Character.digit(body.charAt(i + digit), 16);
                    if(hex < 0) return new Result(Status.invalid, "\\u requires four hex digits");
                    value = value * 16 + hex;
                }
                out.append((char)value);
                i += 4;
            }else{
                out.append('\\').append(escaped);
            }
        }
        out.append('"');
        if(!recognized) return new Result(Status.none, "");
        return new Result(needsModern && !modern ? Status.unsupported : Status.preview, out.toString());
    }

    /** Rejects a token that only happens to start/end with quotes but cannot be one mlog string. */
    private static boolean isCompleteQuotedString(String source){
        if(source == null || source.length() < 2 || source.charAt(0) != '"'
            || source.charAt(source.length() - 1) != '"') return false;

        int trailingSlashes = 0;
        for(int i = source.length() - 2; i >= 0 && source.charAt(i) == '\\'; i--) trailingSlashes++;
        if((trailingSlashes & 1) != 0) return false;

        // LParser terminates a string at the first unescaped quote and rejects raw line breaks.
        for(int i = 1; i < source.length() - 1; i++){
            char c = source.charAt(i);
            if(c == '\n' || c == '\r' || c == '"') return false;
            if(c == '\\' && i + 1 < source.length() - 1) i++;
        }
        return true;
    }

    private static String singleLine(String value){
        String text = value.replace("\r", "").replace("\n", " ↵ ");
        return text.length() <= 48 ? text : text.substring(0, 47) + "…";
    }
}
