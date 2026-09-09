package logicsugar.assist;

public class EditHistorySelfTest{
    public static void main(String[] args){
        recordsAndUndoRedo();
        pendingEditIsOneUndo();
        redoClearedOnNewEdit();
        redoAbandonedAfterBranch();
        appliedResyncsBaseline();
        limitDropsOldest();
        System.out.println("LogicSugar EditHistory self-test passed.");
    }

    private static void recordsAndUndoRedo(){
        EditHistory history = new EditHistory();
        history.reset("a");
        check(!history.canUndo() && !history.canRedo(), "fresh history should be empty");
        history.record("b");
        history.record("c");
        check(history.canUndo() && !history.canRedo(), "two edits should enable undo only");
        check("b".equals(history.undo("c")), "undo from c should return b");
        history.applied("b");
        check("a".equals(history.undo("b")), "second undo should return a");
        history.applied("a");
        check(!history.canUndo() && history.canRedo(), "fully undone should only redo");
        check("b".equals(history.redo("a")), "redo should return b");
        history.applied("b");
        check("c".equals(history.redo("b")), "second redo should return c");
        history.applied("c");
        check(!history.canRedo(), "fully redone should have no redo");
    }

    private static void pendingEditIsOneUndo(){
        EditHistory history = new EditHistory();
        history.reset("a");
        history.record("b");
        check("b".equals(history.undo("b-typed")), "pending typing should undo to the last recorded snapshot");
        history.applied("b");
        check(history.canRedo(), "pending edit should land on the redo stack");
        check("b-typed".equals(history.redo("b")), "redo after pending undo should restore the typed snapshot");
    }

    private static void redoClearedOnNewEdit(){
        EditHistory history = new EditHistory();
        history.reset("a");
        history.record("b");
        history.undo("b");
        history.applied("a");
        history.record("c");
        check(!history.canRedo(), "a new edit must drop the redo stack");
        check("a".equals(history.undo("c")), "undo after a new branch should skip the discarded redo");
    }

    private static void redoAbandonedAfterBranch(){
        EditHistory history = new EditHistory();
        history.reset("a");
        history.record("b");
        history.undo("b");
        history.applied("a");
        check(history.redo("a-edited") == null, "editing after undo should abandon redo");
        check(!history.canRedo(), "abandoned redo stack must be empty");
        check("a".equals(history.undo("a-edited")), "the branched edit should still be undoable");
    }

    private static void appliedResyncsBaseline(){
        EditHistory history = new EditHistory();
        history.reset("a");
        history.record("b");
        history.record("c");
        check("b".equals(history.undo("c")), "undo snapshot");
        history.applied("b\n");
        history.record("b\n");
        check(history.canUndo(), "resyncing to a folded save() must keep earlier undo entries");
    }

    private static void limitDropsOldest(){
        EditHistory history = new EditHistory();
        history.reset("0");
        for(int i = 1; i <= EditHistory.LIMIT + 5; i++){
            history.record(String.valueOf(i));
        }
        int undos = 0;
        String current = String.valueOf(EditHistory.LIMIT + 5);
        while(true){
            String previous = history.undo(current);
            if(previous == null) break;
            history.applied(previous);
            current = previous;
            undos++;
        }
        check(undos == EditHistory.LIMIT, "history must cap undo depth at " + EditHistory.LIMIT + ", got " + undos);
        check("5".equals(current), "oldest retained snapshot should be the one just inside the cap");
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
