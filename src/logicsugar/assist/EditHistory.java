package logicsugar.assist;

import java.util.ArrayDeque;

/**
 * Logic 编辑器的撤销/重做快照栈。快照是 {@code SugarCanvas.save()} 产出的 sugar 文本。
 *
 * <p>新编辑会清空重做栈。应用撤销/重做时调用方应先 {@code load()} 再 {@link #applied(String)}
 * 与画布的实际 {@code save()} 对齐（fold/unfold 可能改变空白）。</p>
 */
public final class EditHistory{
    public static final int LIMIT = 80;

    private final ArrayDeque<String> undo = new ArrayDeque<>();
    private final ArrayDeque<String> redo = new ArrayDeque<>();
    private String baseline = "";
    private boolean restoring;

    public void reset(String initial){
        undo.clear();
        redo.clear();
        baseline = initial == null ? "" : initial;
        restoring = false;
    }

    /** 记录一次新编辑。与基线相同则忽略。 */
    public void record(String current){
        if(restoring) return;
        commitChanged(current, true);
    }

    public boolean canUndo(){
        return !undo.isEmpty();
    }

    public boolean canRedo(){
        return !redo.isEmpty();
    }

    /**
     * 撤销到上一快照。若 {@code current} 相对基线有未提交的改动，先把它收进栈再撤销
     * （一次撤销回到改动前，且丢掉重做方向上的旧分支）。
     *
     * @return 要 {@code load()} 的文本；没有可撤销项时返回 {@code null}
     */
    public String undo(String current){
        if(restoring) return null;
        commitChanged(current, true);
        if(undo.isEmpty()) return null;
        redo.addLast(baseline);
        baseline = undo.removeLast();
        restoring = true;
        return baseline;
    }

    /**
     * 重做。若当前文本相对基线已改，视为新分支：提交改动并清空重做栈。
     *
     * @return 要 {@code load()} 的文本；没有可重做项时返回 {@code null}
     */
    public String redo(String current){
        if(restoring) return null;
        if(!same(current, baseline)){
            commitChanged(current, true);
            return null;
        }
        if(redo.isEmpty()) return null;
        undo.addLast(baseline);
        while(undo.size() > LIMIT) undo.removeFirst();
        baseline = redo.removeLast();
        restoring = true;
        return baseline;
    }

    /** {@code load()} 之后用画布真实 {@code save()} 对齐基线并结束 restoring。 */
    public void applied(String actual){
        baseline = actual == null ? "" : actual;
        restoring = false;
    }

    public boolean isRestoring(){
        return restoring;
    }

    private void commitChanged(String current, boolean clearRedo){
        String now = current == null ? "" : current;
        if(now.equals(baseline)) return;
        undo.addLast(baseline);
        while(undo.size() > LIMIT) undo.removeFirst();
        baseline = now;
        if(clearRedo) redo.clear();
    }

    private static boolean same(String a, String b){
        String left = a == null ? "" : a;
        String right = b == null ? "" : b;
        return left.equals(right);
    }
}
