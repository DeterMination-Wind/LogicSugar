package logicsugar;

import arc.Core;
import arc.scene.ui.Dialog;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.logic.LAssembler;
import mindustry.logic.SugarCompiler;
import mindustry.logic.SugarFunctions;
import mindustry.logic.SugarLogicDialog;

/**
 * 函数库编辑入口。
 *
 * <p>函数库不在独立的迷你画布中编辑，而是复用成熟的逻辑处理器编辑器：点击
 * "在处理器中编辑"后，函数库内容加载进逻辑处理器编辑器（不绑定任何处理器），
 * 新增/修改/删除函数与编辑普通处理器完全一致；关闭编辑器时自动校验并保存回
 * 函数库文件，保存失败会重新打开编辑器且修改不丢失。
 */
public class FunctionLibraryDialog extends Dialog{
    public FunctionLibraryDialog(){
        super("@logicsugar.funclib.title");
        cont.add("@logicsugar.funclib.edit.hint").width(560f).wrap().pad(16f);
        row();
        buttons.defaults().size(260f, 56f).pad(6f);
        buttons.button("@logicsugar.funclib.edit", Icon.edit, this::editInProcessor);
        buttons.button("@back", Icon.left, this::hide);
    }

    private void editInProcessor(){
        hide();
        String text = FunctionLibrary.loadText();
        if(!text.trim().isEmpty()){
            try{
                // 提前校验；损坏时仍加载进编辑器让用户修复，保存时再次校验
                SugarFunctions.buildLibrary(LAssembler.read(text, true));
            }catch(IllegalArgumentException e){
                Vars.ui.showInfoFade(Core.bundle.format("logicsugar.funclib.damaged", e.getMessage()));
            }
        }
        openEditor(text);
    }

    private void openEditor(String sugar){
        if(Vars.ui.logic instanceof SugarLogicDialog logic){
            logic.passThroughSugarOnError = true;
            logic.show(sugar, null, true, compiled -> {
                logic.passThroughSugarOnError = false;
                storeBack(compiled);
            });
        }else{
            Vars.ui.showErrorMessage("@logicsugar.funclib.noeditor");
        }
    }

    private void storeBack(String compiledOrSugar){
        // restore 对不含 marker 的 sugar 文本是幂等的，因此编译失败时回传的 sugar 也能处理
        String sugar = SugarCompiler.restore(compiledOrSugar);
        try{
            FunctionLibrary.save(sugar);
            Vars.ui.showInfoFade("@logicsugar.funclib.saved");
        }catch(IllegalArgumentException e){
            Vars.ui.showErrorMessage(Core.bundle.format("logicsugar.funclib.error", e.getMessage()));
            // 重新打开编辑器并保留内容，让用户修正后再次保存
            Core.app.post(() -> openEditor(sugar));
        }
    }
}
