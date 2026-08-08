package mindustry.logic;

import arc.Core;
import arc.func.Cons;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.ui.Button;
import arc.scene.ui.Dialog;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.gen.Icon;
import mindustry.logic.LExecutor;
import mindustry.ui.Styles;
import logicsugar.FunctionLibraryDialog;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;

public class SugarLogicDialog extends LogicDialog{
    private static final String compiledCopyName = "logicsugar-copy-compiled";
    private static final Field consumerField = field(LogicDialog.class, "consumer");
    private final Map<Object, String> drafts = new IdentityHashMap<>();
    public LExecutor executor;
    /** When true, a failed compile during a close is passed back to the caller as raw sugar
     *  instead of being dropped. Used by the function library editing session (executor == null),
     *  so processor edits are never affected. */
    public boolean passThroughSugarOnError;
    private Element editButton;
    private float menuScanTimer;

    public SugarLogicDialog(){
        super();
        clearChildren();
        canvas = new SugarCanvas();
        add(canvas).grow().name("canvas");
        row();
        add(buttons).growX().name("buttons");
        // direct entry to the global function library, next to the other editor actions
        buttons.button("@logicsugar.funclib.open", Icon.book, () -> new FunctionLibraryDialog().show()).name("funclib");
        update(() -> {
            installEditHook();
            menuScanTimer += Time.delta;
            if(menuScanTimer >= 6f){
                menuScanTimer = 0f;
                installCompiledCopy();
            }
        });
    }

    private void installEditHook(){
        Element candidate = buttons.find("edit");
        if(candidate == editButton || !(candidate instanceof Button button)) return;
        editButton = candidate;
        button.clicked(() -> Core.app.post(this::installCompiledCopy));
    }

    private void installCompiledCopy(){
        TextButton copy = findCopyButton(Core.scene.root);
        if(copy == null || !(copy.parent instanceof Table menu)) return;
        if(menu.find(compiledCopyName) != null) return;

        Dialog dialog = parentDialog(copy);
        if(dialog == null) return;

        menu.row();
        menu.button("@logicsugar.copy.compiled", Icon.copy, Styles.flatt, () -> {
            try{
                Core.app.setClipboardText(SugarCompiler.compile(canvas.save()));
                dialog.hide();
                Vars.ui.showInfoFade("@logicsugar.copy.compiled.done");
            }catch(IllegalArgumentException exception){
                dialog.hide();
                showCompileError(exception, false);
            }
        }).size(280f, 60f).left().marginLeft(12f).get().name = compiledCopyName;
        menu.invalidateHierarchy();
    }

    private Dialog parentDialog(Element element){
        Element current = element;
        while(current != null && !(current instanceof Dialog)) current = current.parent;
        return (Dialog)current;
    }

    private TextButton findCopyButton(Element element){
        if(element instanceof TextButton button && button.getText().toString().equals(Core.bundle.get("copy.clipboard"))){
            return button;
        }
        if(element instanceof Group group){
            Seq<Element> children = group.getChildren();
            for(Element child : children){
                TextButton found = findCopyButton(child);
                if(found != null) return found;
            }
        }
        return null;
    }

    @Override
    public void show(String code, LExecutor executor, boolean privileged, Cons<String> modified){
        this.executor = executor;
        Object key = draftKey(executor);
        String editable = drafts.containsKey(key) ? drafts.get(key) : SugarCompiler.restore(code);
        Cons<String> submit = sugar -> submit(sugar, executor, modified, key, false);
        super.show(editable, executor, privileged, submit);

        // LogicDialog normally suppresses equal results. Sugar must always win the
        // close race against remote processor edits, so replace that consumer.
        setConsumer(sugar -> submit(sugar, executor, modified, key, true));
    }

    private void submit(String sugar, LExecutor executor, Cons<String> modified, Object key, boolean closing){
        try{
            String compiled = SugarCompiler.compile(sugar);
            if(executor != null && executor.build != null && !executor.build.isValid()){
                drafts.remove(key);
                return;
            }
            drafts.remove(key);
            modified.get(compiled);
        }catch(IllegalArgumentException exception){
            if(closing && key != null) drafts.put(key, sugar);
            if(closing && executor == null && passThroughSugarOnError){
                // Function library session: hand the raw sugar back so the caller can keep
                // the user's work and reopen the editor instead of dropping it.
                modified.get(sugar);
                return;
            }
            Core.app.post(() -> showCompileError(exception, closing));
        }
    }

    private Object draftKey(LExecutor executor){
        if(executor == null) return null;
        Building build = executor.build;
        return build != null ? build : executor;
    }

    private void setConsumer(Cons<String> consumer){
        try{
            consumerField.set(this, consumer);
        }catch(IllegalAccessException exception){
            throw new RuntimeException("Unable to configure Logic Sugar save behavior", exception);
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

    private void showCompileError(IllegalArgumentException exception, boolean draftKept){
        String key = draftKept ? "logicsugar.error.draft" : "logicsugar.error.compile";
        Vars.ui.showErrorMessage(Core.bundle.format(key, exception.getMessage()));
    }
}
