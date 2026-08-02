package logicsugar.assist.expr;

import arc.*;
import arc.func.*;
import arc.scene.*;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.logic.*;
import mindustry.logic.LCanvas.*;
import mindustry.logic.LStatements.*;
import mindustry.logic.SugarStatements.BeginStatement;

import java.util.*;

/**
 * 表达式集成钩子：提供 op 链 ↔ 表达式的双向转换。
 *
 * 集成后：
 * - foldAll() 由 LogicCanvas.load() 直接调用，零延迟
 * - save() 由 LogicCanvas.save() 调用：unfoldAll → super.save → foldAll
 * - 行号由 LogicCanvas.act() 更新
 * - LogicIO.allStatements 是 public static 字段，直接访问无需反射
 * - 跳转高度刷新通过 SugarCanvas 的兼容入口处理
 *
 * ------------------------------------------------------------
 * 致谢 / Acknowledgements
 * ------------------------------------------------------------
 * op 链折叠（foldAll）思路参考了 mindcode 项目的 MlogDecompiler：
 *   - 项目地址: https://github.com/cardillan/mindcode
 *   - 参考文件: compiler/src/main/java/info/teksol/mc/mindcode/decompiler/MlogDecompiler.java
 *   - 参考内容: collapseExpressions() 检测线性指令块并折叠为表达式子树，
 *     用 isLinear() 判断指令是否可参与折叠（非 jump、非 @counter 赋值）。
 *     本项目用 hasJumpInRange() 实现等价的跳转安全检查。
 */
public class ExprHook{

    private static boolean statementRegistered = false;

    public static void init(){
        registerStatement();
    }

    /** 将 ExprStatement 注入 LogicIO.allStatements，使其出现在编辑器的积木列表中。 */
    private static void registerStatement(){
        if(statementRegistered) return;
        for(Prov<LStatement> prov : LogicIO.allStatements){
            if(prov.get() instanceof ExprStatement) return;
        }
        LogicIO.allStatements.add(() -> new ExprStatement());
        statementRegistered = true;
        Log.info("[LogicAssist] ExprStatement registered to LogicIO.allStatements");
    }

    // ===== 折叠：op 链 → ExprStatement =====

    public static void foldAll(LCanvas canvas){
        if(canvas == null || canvas.statements == null) return;

        Seq<Element> children = canvas.statements.getChildren();
        if(children.isEmpty()) return;

        saveUIAll(canvas);

        boolean changed = false;
        int i = 0;
        while(i < children.size){
            if(!(children.get(i) instanceof StatementElem) ||
               !(((StatementElem)children.get(i)).st instanceof OperationStatement)){
                i++;
                continue;
            }

            List<ExprCompiler.OpLine> ops = new ArrayList<>();
            int j = i;
            while(j < children.size){
                if(!(children.get(j) instanceof StatementElem)) break;
                StatementElem elem = (StatementElem)children.get(j);
                if(!(elem.st instanceof OperationStatement)) break;

                OperationStatement opStmt = (OperationStatement)elem.st;
                ops.add(new ExprCompiler.OpLine(
                    opStmt.op.name(), opStmt.dest, opStmt.a, opStmt.b));

                if(!ExprCompiler.isTemp(opStmt.dest)){
                    j++;
                    break;
                }
                j++;
            }

            int chainLen = j - i;
            if(chainLen >= 2){
                // 安全检查：若有 jump 指向链中间 [i+1, i+chainLen-1]，放弃折叠。
                // 场景：别人没装插件时写的 jump 指向 op 链中间，折叠会改变语义。
                // 指向链首 i 是允许的，折叠后仍指向 expr 积木。
                if(hasJumpInRange(canvas, i + 1, i + chainLen - 1)){
                    i = j; // 跳过整条链，不折叠
                    continue;
                }
                String expr = ExprCompiler.rebuild(ops);
                if(expr != null){
                    String dest = ops.get(ops.size() - 1).dest;

                    ExprStatement exprStmt = new ExprStatement();
                    exprStmt.dest = dest;
                    exprStmt.expr = expr;
                    exprStmt.lastOps = ops;

                    for(int k = 0; k < chainLen; k++){
                        ((StatementElem)children.get(i)).remove();
                    }

                    canvas.addAt(i, exprStmt);

                    changed = true;
                }else{
                    // rebuild 失败（如链不完整），跳过整条链，
                    // 避免 i++ 后从链中间重新查找子链导致误折叠
                    i = j;
                    continue;
                }
            }
            i++;
        }

        if(changed){
            // Element references survive the remove/insert operations. Recalculate their
            // serialized indices here; shifting the old values again corrupts nested blocks.
            saveUIAll(canvas);
            setupUIAll(canvas);
            // 行号由 LogicDragLayout.layout() 自动更新，无需手动调用
            SugarCanvas.markJumpHeightsDirty(canvas);
            Log.debug("[LogicAssist] Expression chains folded");
        }
    }

    // ===== 展开：ExprStatement → op 链 =====

    public static void unfoldAll(LCanvas canvas){
        if(canvas == null || canvas.statements == null) return;

        Seq<Element> children = canvas.statements.getChildren();
        if(children.isEmpty()) return;

        saveUIAll(canvas);

        boolean changed = false;
        for(int i = 0; i < children.size; i++){
            if(!(children.get(i) instanceof StatementElem)) continue;
            StatementElem elem = (StatementElem)children.get(i);
            if(!(elem.st instanceof ExprStatement)) continue;

            ExprStatement exprStmt = (ExprStatement)elem.st;

            List<ExprCompiler.OpLine> ops;
            try{
                ops = ExprCompiler.compile(exprStmt.dest, exprStmt.expr);
            }catch(Exception e){
                // 编译失败：保留 ExprStatement 不展开，write() 会输出 lastOps
                // 避免 unfold→fold 循环用 lastOps 重建 ExprStatement 覆盖错误的 expr
                continue;
            }

            int chainLen = ops.size();

            elem.remove();

            for(int k = 0; k < chainLen; k++){
                ExprCompiler.OpLine line = ops.get(k);
                OperationStatement opStmt = new OperationStatement();
                opStmt.op = LogicOp.valueOf(line.op);
                opStmt.dest = line.dest;
                opStmt.a = line.a;
                opStmt.b = line.b;

                canvas.addAt(i + k, opStmt);
            }

            changed = true;
            i += chainLen - 1;
        }

        if(changed){
            // See foldAll(): the destination elements have already moved with the layout.
            saveUIAll(canvas);
            setupUIAll(canvas);
            SugarCanvas.markJumpHeightsDirty(canvas);
            Log.debug("[LogicAssist] Expression statements unfolded");
        }
    }

    // ===== 目标索引调整 =====

    private static void adjustStatementIndices(LCanvas canvas, int threshold, int delta){
        if(delta == 0) return;
        Seq<Element> children = canvas.statements.getChildren();
        for(Element child : children){
            if(!(child instanceof StatementElem)) continue;
            adjustStatementIndex(((StatementElem)child).st, threshold, delta);
        }
    }

    /**
     * Shifts targets after a statement range is replaced. Structured blocks use
     * the same index model as jumps, so both must be updated together.
     */
    public static void adjustStatementIndex(LStatement statement, int threshold, int delta){
        if(delta == 0) return;
        if(statement instanceof JumpStatement jump && jump.destIndex > threshold){
            jump.destIndex += delta;
        }else if(statement instanceof BeginStatement begin && begin.destIndex > threshold){
            begin.destIndex += delta;
        }
    }

    /** 检查是否有 JumpStatement 的 destIndex 落在 [lo, hi] 范围内 */
    private static boolean hasJumpInRange(LCanvas canvas, int lo, int hi){
        if(lo > hi) return false;
        Seq<Element> children = canvas.statements.getChildren();
        for(Element child : children){
            if(!(child instanceof StatementElem)) continue;
            StatementElem elem = (StatementElem)child;
            if(elem.st instanceof JumpStatement){
                JumpStatement jump = (JumpStatement)elem.st;
                if(jump.destIndex >= lo && jump.destIndex <= hi){
                    return true;
                }
            }
        }
        return false;
    }

    // ===== 工具方法 =====

    private static void saveUIAll(LCanvas canvas){
        for(Element child : canvas.statements.getChildren()){
            if(child instanceof StatementElem){
                ((StatementElem)child).st.saveUI();
            }
        }
    }

    private static void setupUIAll(LCanvas canvas){
        for(Element child : canvas.statements.getChildren()){
            if(child instanceof StatementElem){
                ((StatementElem)child).st.setupUI();
            }
        }
    }
}
