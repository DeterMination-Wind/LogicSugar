package logicsugar.assist.expr;

import arc.*;
import arc.graphics.*;
import arc.input.*;
import arc.math.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.logic.*;
import mindustry.logic.LExecutor.*;
import mindustry.logic.LStatements.*;
import mindustry.ui.Styles;

import java.util.*;

/**
 * 表达式语句：在逻辑编辑器中以表达式形式显示，保存时自动展开为 op 链。
 *
 * 折叠态：[dest] = [expr Label 覆盖 TextField，点击 Label 切换编辑]
 * 展开态：op cos _0 a 0 / op mul _0 _0 10 / op add x _0 x
 *
 * 关键设计：
 * - write() 输出 op 链文本，保证保存的代码始终是标准 mlog
 * - copy() 直接复制字段，不走 write→read 序列化（防止复制时展开）
 * - 行号显示由 LogicCanvas 管理，不在此类处理
 */
public class ExprStatement extends LStatement{

    /** 目标变量名 */
    public String dest = "result";
    /** 表达式字符串 */
    public String expr = "0";

    /** 上次编译的语句链（用于 fallback、行号计算和调试） */
    public transient List<ExprCompiler.Line> lastOps;

    /** 上次编译的错误消息（null = 无错误）。作为字段保持，避免 build() 重建时丢失错误状态 */
    public transient String lastError = null;

    @Override
    public void write(StringBuilder builder){
        List<ExprCompiler.Line> lines;
        try{
            lines = ExprCompiler.compile(dest, expr, functionChecker());
            lastOps = lines;
        }catch(Exception e){
            // 编译失败：有上次成功的链则回退输出（编辑中间态不破坏存档）；
            // 从未成功编译过则阻止保存——静默 fallback 成 op add dest dest 0 会
            // 让错误表达式"编译成功"但语义变成 dest+0（如 foo(a) → x+0）
            lines = lastOps;
            if(lines == null || lines.isEmpty()){
                throw new IllegalArgumentException("Invalid expression '" + expr + "': " + e.getMessage());
            }
        }
        for(int i = 0; i < lines.size(); i++){
            if(i > 0) builder.append("\n");
            builder.append(lines.get(i).toText());
        }
    }

    /** 编辑期函数名校验：本地 funcdef + 库函数（数学函数由 ExprCompiler 内置处理）。 */
    public static ExprCompiler.FunctionChecker functionChecker(){
        Set<String> names = new HashSet<>();
        SugarFunctions.LibraryIndex library = SugarFunctions.library();
        if(library != null) names.addAll(library.functions.keySet());
        SugarCanvas canvas = SugarCanvas.current();
        if(canvas != null && canvas.statements != null){
            for(arc.scene.Element child : canvas.statements.getChildren()){
                if(child instanceof LCanvas.StatementElem elem
                    && elem.st instanceof SugarStatements.FuncDefStatement def){
                    names.add(def.name);
                }
            }
        }
        return names::contains;
    }

    @Override
    public void build(Table table){
        // 重新验证：lastError 不为 null 时，expr 可能已被外部修正（如 foldAll），需重新编译检查
        if(lastError != null){
            try{
                ExprCompiler.compile(dest, expr, functionChecker());
                lastError = null;
            }catch(Exception e){
                lastError = e.getMessage();
            }
        }

        // 初始化 lastOps：手动添加的 Expr 可能还没有编译过
        if(lastOps == null){
            try{
                lastOps = ExprCompiler.compile(dest, expr, functionChecker());
            }catch(Exception e){
                lastError = e.getMessage();
            }
        }

        table.left();
        // dest 字段
        field(table, dest, str -> dest = str);
        table.add(" = ");

        // 表达式显示：Label（高亮 + 自动换行）与 TextField（编辑）切换
        // Label 复用 Styles.nodeField 的背景，保证非编辑态也有白色横杠
        Label exprLabel = new Label("");
        Label.LabelStyle labelStyle = new Label.LabelStyle(exprLabel.getStyle());
        if(Styles.nodeField.background != null){
            labelStyle.background = Styles.nodeField.background;
        }
        exprLabel.setStyle(labelStyle);
        exprLabel.setWrap(true);
        exprLabel.setAlignment(Align.left);
        exprLabel.touchable = Touchable.enabled;

        // 错误信息 Label：显示在表达式下方，仅错误时可见
        Label errorLabel = new Label("");
        errorLabel.setStyle(new Label.LabelStyle(Styles.outlineLabel));
        errorLabel.setColor(Color.scarlet);
        errorLabel.setWrap(true);
        errorLabel.setAlignment(Align.left);
        errorLabel.visible = false;

        // 更新 Label 的高亮文本与错误提示
        // 不调用 pack()——pack() 会把宽度设为 getPrefWidth()，而 setWrap(true) 时
        // getPrefWidth() 返回 0，导致 Label 宽度为 0 无法接收点击。 setText() 已触发
        // invalidateHierarchy()，Stack 的 layout() 会用正确宽度重新布局。
        Runnable updateLabel = () -> {
            if(lastError != null){
                exprLabel.setColor(Color.scarlet);
                // 转义 [ ] 防止富文本解析错误
                String safe = lastError.replace("[", "[[").replace("]", "]]");
                errorLabel.setText("[#ff5555]" + safe);
                errorLabel.visible = true;
            }else{
                exprLabel.setColor(Color.white);
                errorLabel.visible = false;
            }
            exprLabel.setText(ExprStatement.highlight(expr));
        };
        updateLabel.run();

        TextField exprField = new TextField(expr);
        exprField.setStyle(Styles.nodeField);
        exprField.setMessageText("expr");
        // 允许所有字符（含空格、运算符），不走 LStatement.field() 的 sanitize
        exprField.setFilter((f, c) -> true);
        exprField.setMaxLength(0);
        exprField.changed(() -> {
            expr = exprField.getText();
            try{
                lastOps = ExprCompiler.compile(dest, expr);
                lastError = null;
            }catch(Exception e){
                // 输入中的语法错误，保留旧 lastOps，记录错误消息
                lastError = e.getMessage();
            }
            // 编辑中实时更新错误提示
            updateLabel.run();
        });

        // Stack 叠放 Label 和 TextField，占同一空间
        // 覆盖 getPrefWidth() 返回 0，让外层 cell 不被 Stack 的 prefWidth 撑开
        arc.scene.ui.layout.Stack stack = new arc.scene.ui.layout.Stack(){
            @Override
            public float getPrefWidth(){ return 0; }
        };
        stack.add(exprLabel);
        stack.add(exprField);
        table.add(stack).growX().padLeft(4f).fillX();
        // TextField 默认隐藏：不参与 Stack 高度计算，Label 换行高度自然撑开 Stack
        exprField.visible = false;
        exprField.touchable = Touchable.disabled;

        // 错误信息行：占满整行（合并上方 3 列），visible=false 时不占高度
        table.row();
        table.add(errorLabel).growX().padLeft(4f).padTop(2f).colspan(3);

        // 点击 Label → 编辑表达式
        // 移动端：直接弹出原生输入对话框（TextField 被隐藏，其 touchDown 不会触发，
        //         导致 setOnscreenKeyboardVisible 不被调用，焦点立即丢失）
        // 桌面端：切换 Label/TextField 可见性，设置焦点进行内联编辑
        exprLabel.addListener(new InputListener(){
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                if(Core.app.isMobile() && !Core.input.useKeyboard()){
                    Input.TextInput input = new Input.TextInput();
                    input.text = expr;
                    input.accepted = text -> {
                        expr = text;
                        exprField.setText(text);
                        exprField.change();
                    };
                    Core.input.getTextInput(input);
                    event.stop();
                    return true;
                }
                exprField.setText(expr);
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
    }

    /** 把表达式转为带颜色标记的富文本，用于 Label 高亮显示。
     *  复用 ExprCompiler.tokenize 分类着色，用 token.start 保留原始空白：
     *  - 数字：金色
     *  - 函数名：珊瑚色（后跟左括号）
     *  - 变量名：白色
     *  - 运算符/括号/逗号：浅灰 */
    public static String highlight(String expr){
        if(expr == null || expr.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        try{
            List<ExprCompiler.Token> tokens = ExprCompiler.tokenize(expr);
            int lastEnd = 0;
            for(int i = 0; i < tokens.size(); i++){
                ExprCompiler.Token tok = tokens.get(i);
                if(tok.type == ExprCompiler.TokType.EOF) break;

                // 输出上一个 token 到当前 token 之间的原始空白
                if(tok.start > lastEnd){
                    sb.append(expr, lastEnd, tok.start);
                }

                String color;
                if(tok.type == ExprCompiler.TokType.NUM){
                    color = "goldenrod";
                }else if(tok.type == ExprCompiler.TokType.IDENT){
                    boolean isFunc = (i + 1 < tokens.size()
                        && tokens.get(i + 1).type == ExprCompiler.TokType.LPAREN);
                    // 成员访问：`.` 之后的标识符（unit.Health 的 Health）用天蓝色区分
                    boolean isMember = (i > 0 && tokens.get(i - 1).type == ExprCompiler.TokType.OP
                        && tokens.get(i - 1).text.equals("."));
                    color = isFunc ? "coral" : isMember ? "sky" : "white";
                }else{
                    color = "lightgray";
                }
                // 富文本中 [ ] 需转义为 [[ ]]
                String text = tok.text.replace("[", "[[").replace("]", "]]");
                sb.append("[").append(color).append("]").append(text).append("[]");
                lastEnd = tok.start + tok.text.length();
            }
            // 尾部空白
            if(lastEnd < expr.length()){
                sb.append(expr, lastEnd, expr.length());
            }
        }catch(Exception e){
            // 解析失败，原样返回（转义 [ ]）
            sb.setLength(0);
            sb.append(expr.replace("[", "[[").replace("]", "]]"));
        }
        return sb.toString();
    }

    @Override
    public LStatement copy(){
        Log.debug("[LogicAssist] ExprStatement.copy() called: dest=@ expr=@", dest, expr);
        ExprStatement copy = new ExprStatement();
        copy.dest = this.dest;
        copy.expr = this.expr;
        copy.lastOps = this.lastOps;
        return copy;
    }

    @Override
    public LInstruction build(LAssembler builder){
        // 正常流程下不会走到这里：LogicCanvas.save() 会先 unfoldAll()，
        // ExprStatement 会被替换为 OperationStatement。
        // 但如果代码通过 customParsers 加载后直接执行（不经过编辑器 save），
        // 返回一个 no-op 指令防止静默跳过。
        List<ExprCompiler.Line> ops;
        try{
            ops = ExprCompiler.compile(dest, expr);
        }catch(Exception e){
            ops = lastOps;
        }
        if(ops == null || ops.isEmpty()){
            return new OpI(LogicOp.add, builder.var(dest), builder.var("0"), builder.var(dest));
        }
        // 返回第一条指令，后续指令在 write() 中输出为文本
        ExprCompiler.Line first = ops.get(0);
        if(first instanceof ExprCompiler.SensorLine sensor){
            // sensor to from type → SenseI(from, to, type)
            return new SenseI(builder.var(sensor.a), builder.var(sensor.dest), builder.var(sensor.b));
        }
        if(first instanceof ExprCompiler.CallLine){
            // 函数调用无法映射为单条原版指令：该路径本不该出现（正常流程先 unfold）
            throw new IllegalArgumentException("expression contains a function call and cannot execute directly");
        }
        ExprCompiler.OpLine op = (ExprCompiler.OpLine)first;
        return new OpI(LogicOp.valueOf(op.op),
                        builder.var(op.a), builder.var(op.b), builder.var(op.dest));
    }

    @Override
    public String name(){
        return "Expr";
    }

    @Override
    public LCategory category(){
        return LCategory.operation;
    }
}
