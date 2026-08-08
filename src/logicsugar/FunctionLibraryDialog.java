package logicsugar;

import arc.Core;
import arc.scene.ui.Dialog;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.logic.SugarCanvas;

/**
 * Dialog for editing the global function library. Reuses {@link SugarCanvas} so library
 * functions are edited with the same visual blocks as processor programs; the saved text
 * is validated before it is written to the library file.
 *
 * <p>While the dialog is open the canvas is the active structure canvas (fold buttons and
 * structure guides refresh it instead of the processor editor).
 */
public class FunctionLibraryDialog extends Dialog{
    private final SugarCanvas canvas = new SugarCanvas();

    public FunctionLibraryDialog(){
        super("@logicsugar.funclib.title");
        add(canvas).grow().width(760f).height(480f);
        row();
        buttons.defaults().size(170f, 54f).pad(6f);
        buttons.button("@logicsugar.funclib.save", Icon.save, this::save);
        buttons.button("@logicsugar.funclib.cancel", Icon.cancel, this::hide);
    }

    @Override
    public Dialog show(){
        canvas.load(FunctionLibrary.loadText());
        SugarCanvas.setActiveOverride(canvas);
        return super.show();
    }

    @Override
    public void hide(){
        super.hide();
        SugarCanvas.setActiveOverride(null);
    }

    private void save(){
        try{
            FunctionLibrary.save(canvas.save());
            Vars.ui.showInfoFade("@logicsugar.funclib.saved");
            hide();
        }catch(IllegalArgumentException e){
            Vars.ui.showErrorMessage(Core.bundle.format("logicsugar.funclib.error", e.getMessage()));
        }
    }
}
