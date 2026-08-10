package logicsugar.assist.expr;

import arc.Core;
import arc.Input;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.ui.Label;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Stack;
import arc.util.Align;
import arc.func.Cons;
import arc.func.Prov;
import mindustry.ui.Styles;

/**
 * 表达式输入框：以高亮 Label 显示表达式（数字金色 / 函数珊瑚色 / 变量白色 / 运算符浅灰），
 * 点击 Label 切换为 TextField 编辑，失焦后恢复高亮显示。交互与 ExprStatement 的表达式字段一致。
 *
 * <p>空值时显示灰色占位提示文本（hint），帮助用户理解该字段的填写格式。提示文本可以由
 * 提供者动态给出：空值时每帧重新求值，仅在提示串变化时重绘（Label + TextField message），
 * 因此提示可以跟随其它字段（如被调函数名）实时变化。
 */
public class ExpressionEditor extends Stack{
    private String value;
    private final Cons<String> setter;
    private final Prov<String> hintProvider;
    private String lastHint;

    /** Static-hint constructor; the hint never changes. */
    public ExpressionEditor(String initial, String hint, Cons<String> setter){
        this(initial, () -> hint, setter);
    }

    /**
     * Dynamic-hint constructor. {@code hint} is re-evaluated while the value is empty;
     * a null or empty provider result hides the placeholder.
     */
    public ExpressionEditor(String initial, Prov<String> hint, Cons<String> setter){
        this.value = initial;
        this.setter = setter;
        this.hintProvider = hint;

        Label exprLabel = new Label("");
        Label.LabelStyle labelStyle = new Label.LabelStyle(exprLabel.getStyle());
        if(Styles.nodeField.background != null){
            labelStyle.background = Styles.nodeField.background;
        }
        exprLabel.setStyle(labelStyle);
        exprLabel.setWrap(true);
        exprLabel.setAlignment(Align.left);
        exprLabel.touchable = Touchable.enabled;

        Runnable updateLabel = () -> {
            if(value == null || value.isEmpty()){
                exprLabel.setText("[#8b8b8b]" + effectiveHint() + "[]");
            }else{
                exprLabel.setColor(Color.white);
                exprLabel.setText(ExprStatement.highlight(value));
            }
        };
        updateLabel.run();

        TextField exprField = new TextField(initial);
        exprField.setStyle(Styles.nodeField);
        exprField.setMessageText(effectiveHint());
        exprField.setFilter((f, c) -> true);
        exprField.setMaxLength(0);
        exprField.changed(() -> {
            value = exprField.getText();
            setter.get(value);
            updateLabel.run();
        });

        add(exprLabel);
        add(exprField);
        // TextField 默认隐藏：不参与 Stack 高度计算，Label 换行高度自然撑开 Stack
        exprField.visible = false;
        exprField.touchable = Touchable.disabled;

        // 点击 Label → 编辑表达式
        // 移动端：直接弹出原生输入对话框（TextField 被隐藏，其 touchDown 不会触发，
        //         导致 setOnscreenKeyboardVisible 不被调用，焦点立即丢失）
        // 桌面端：切换 Label/TextField 可见性，设置焦点进行内联编辑
        exprLabel.addListener(new InputListener(){
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                if(Core.app.isMobile() && !Core.input.useKeyboard()){
                    Input.TextInput input = new Input.TextInput();
                    input.text = value;
                    input.accepted = text -> {
                        value = text;
                        exprField.setText(text);
                        exprField.change();
                    };
                    Core.input.getTextInput(input);
                    event.stop();
                    return true;
                }
                exprField.setText(value);
                exprLabel.visible = false;
                exprLabel.touchable = Touchable.disabled;
                exprField.visible = true;
                exprField.touchable = Touchable.enabled;
                Core.scene.setKeyboardFocus(exprField);
                Core.scene.setScrollFocus(exprField);
                event.stop();
                return true;
            }
        });

        // 失焦检测：TextField 失焦时切回 Label 显示
        final boolean[] wasFocused = {false};
        exprField.update(() -> {
            boolean focused = Core.scene.getKeyboardFocus() == exprField;
            if(wasFocused[0] && !focused){
                exprField.visible = false;
                exprField.touchable = Touchable.disabled;
                exprLabel.visible = true;
                exprLabel.touchable = Touchable.enabled;
                updateLabel.run();
            }
            wasFocused[0] = focused;
        });

        // 空值时的动态提示：每帧重新求值，仅当提示串变化时才重绘（廉价字段赋值）
        lastHint = effectiveHint();
        update(() -> {
            if(value == null || value.isEmpty()){
                String effective = effectiveHint();
                if(!effective.equals(lastHint)){
                    lastHint = effective;
                    updateLabel.run();
                    exprField.setMessageText(effective);
                }
            }
        });
    }

    private String effectiveHint(){
        String hint = hintProvider.get();
        return hint == null ? "" : hint;
    }

    @Override
    public float getPrefWidth(){
        return 0f;
    }
}
