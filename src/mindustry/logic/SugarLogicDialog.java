package mindustry.logic;

import arc.func.Cons;
import mindustry.Vars;
import mindustry.logic.LExecutor;

public class SugarLogicDialog extends LogicDialog{
    @Override
    public void show(String code, LExecutor executor, boolean privileged, Cons<String> modified){
        String editable = SugarCompiler.restore(code);
        super.show(editable, executor, privileged, sugar -> {
            try{
                String compiled = SugarCompiler.compile(sugar);
                if(!compiled.equals(code)) modified.get(compiled);
            }catch(IllegalArgumentException exception){
                Vars.ui.showErrorMessage("Logic Sugar: " + exception.getMessage());
            }
        });
    }
}
