package mindustry.logic;

import arc.Core;
import arc.func.Cons;
import arc.func.Prov;
import arc.input.KeyCode;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.ui.Button;
import arc.scene.ui.Dialog;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.gen.Icon;
import mindustry.gen.LogicIO;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.logic.LExecutor;
import mindustry.ui.Fonts;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.blocks.logic.LogicBlock;
import logicsugar.FunctionLibrary;
import logicsugar.FunctionLibraryDialog;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

public class SugarLogicDialog extends LogicDialog{
    private static final String compiledCopyName = "logicsugar-copy-compiled";
    private static final Field consumerField = field(LogicDialog.class, "consumer");
    /** LogicDialog.privileged is package-private and lives in the MindustryX mod class loader at
     *  runtime, so it must be read reflectively (cross-loader package access throws
     *  IllegalAccessError). */
    private static final Field privilegedField = field(LogicDialog.class, "privileged");
    /** Mirrors LogicBlock.maxCompressedLen (private upstream); the compressed code must fit. */
    private static final int maxCompressedBytes = 16_000;
    private final Map<Object, String> drafts = new IdentityHashMap<>();
    public LExecutor executor;
    /** When true, a failed compile during a close is passed back to the caller as raw sugar
     *  instead of being dropped. Used by the function library editing session (executor == null),
     *  so processor edits are never affected. */
    public boolean passThroughSugarOnError;
    /** The stored code as it was when the dialog opened (stale-close protection). */
    private String openedCode = "";
    /** The sugar (or compiled fallback) the canvas was loaded with. */
    private String editable = "";
    /** Embedded plus local library snapshot for this editing session. */
    private SugarCompiler.EffectiveLibrary effectiveLibrary = SugarCompiler.effectiveLibrary("", null, "");
    /** Content hash of the library file when the dialog opened; used to refresh the stale
     *  session snapshot when the library is edited while the processor editor stays open. */
    private int libraryHashAtOpen;
    /** Shown only during library-file editing sessions: closes without saving. */
    private Button discardButton;
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
        // library-file editing sessions (executor == null) offer a discard escape so a user
        // who cannot or does not want to fix the library is not trapped in the reopen loop
        discardButton = buttons.button("@logicsugar.funclib.discard", Icon.cancel, this::discardLibraryChanges).get();
        discardButton.name = "funclib-discard";
        discardButton.visible = false;
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
                // copy with the session's effective library, so the embedded functions survive
                Core.app.setClipboardText(SugarCompiler.compile(canvas.save(), SugarCompiler.currentMode(),
                    effectiveLibrary.index, effectiveLibrary.text));
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
    public void showAddDialog(int position){
        BaseDialog dialog = new BaseDialog("@add");
        boolean priv;
        try{
            priv = (boolean)privilegedField.get(this);
        }catch(ReflectiveOperationException exception){
            throw new RuntimeException(exception);
        }
        dialog.cont.table(table -> {
            String[] searchText = {""};
            Prov[] matched = {null};
            Runnable[] rebuild = {() -> {}};

            table.background(Tex.button);

            table.table(s -> {
                s.image(Icon.zoom).padRight(8);
                var search = s.field(null, text -> {
                    searchText[0] = text;
                    rebuild[0].run();
                }).growX().get();
                search.setMessageText("@players.search");

                if(!Vars.mobile){
                    Core.app.post(search::requestKeyboard);

                    search.keyDown(KeyCode.enter, () -> {
                        if(!searchText[0].isEmpty() && matched[0] != null){
                            canvas.addAt(position == -1 ? canvas.statements.getChildren().size : position, (LStatement)matched[0].get());
                            dialog.hide();
                        }
                    });
                }
            }).growX().padBottom(4).row();

            table.pane(t -> {
                rebuild[0] = () -> {
                    t.clear();

                    var text = searchText[0].toLowerCase();

                    matched[0] = null;

                    for(Prov<LStatement> prov : LogicIO.allStatements){
                        LStatement example = prov.get();
                        if(example instanceof LStatements.InvalidStatement || example.hidden() || (example.privileged() && !priv) || (example.nonPrivileged() && priv) ||
                            (!text.isEmpty() && !example.name().toLowerCase(Locale.ROOT).contains(text) && !example.typeName().toLowerCase(Locale.ROOT).contains(text)) ||
                            (!priv && !Vars.state.rules.logicUnitControl && example.category() == LCategory.unit)) continue;

                        if(matched[0] == null){
                            matched[0] = prov;
                        }

                        LCategory category = example.category();
                        Table cat = t.find(category.name);
                        if(cat == null){
                            t.table(s -> {
                                if(category.icon != null){
                                    s.image(category.icon, Pal.darkishGray).left().size(15f).padRight(10f);
                                }
                                s.add(category.localized()).color(Pal.darkishGray).left().tooltip(category.description());
                                s.image(Tex.whiteui, Pal.darkishGray).left().height(5f).growX().padLeft(10f);
                            }).growX().pad(5f).padTop(10f);

                            t.row();

                            cat = t.table(c -> {
                                c.top().left();
                            }).name(category.name).top().left().growX().fillY().get();
                            t.row();
                        }

                        TextButtonStyle style = new TextButtonStyle(Styles.flatt);
                        style.fontColor = category.color;
                        style.font = Fonts.outline;

                        cat.button(example.name(), style, () -> {
                            canvas.addAt(position == -1 ? canvas.statements.getChildren().size : position, prov.get());
                            dialog.hide();
                        }).size(130f, 50f).self(c -> {
                            // LogicSugar statements use dedicated hint keys; vanilla ones keep the original lookup
                            String sugarKey = "logicsugar.lst." + example.typeName().toLowerCase(Locale.ROOT);
                            LCanvas.tooltip(c, Core.bundle.has(sugarKey) ? sugarKey : "lst." + example.name());
                        }).top().left();

                        if(cat.getChildren().size % 3 == 0) cat.row();
                    }
                };

                rebuild[0].run();
            }).grow();
        }).fill().maxHeight(Core.graphics.getHeight() * 0.8f);
        dialog.addCloseButton();
        dialog.show();
    }

    @Override
    public void show(String code, LExecutor executor, boolean privileged, Cons<String> modified){
        this.executor = executor;
        discardButton.visible = executor == null;
        this.openedCode = code;
        Object key = draftKey(executor);
        if(drafts.containsKey(key)){
            // a failed compile kept the user's work; trust it over any stored code
            editable = drafts.get(key);
        }else{
            String restored = SugarCompiler.restore(code);
            boolean verified;
            try{
                verified = SugarCompiler.verifyRestore(code, restored);
            }catch(Throwable t){
                // stored code cannot even be parsed (e.g. written by a newer mod version);
                // trust the carrier's sugar and let the next save regenerate clean code
                verified = true;
            }
            if(verified){
                editable = restored;
            }else{
                // the stored code was changed outside Logic Sugar: show it as-is
                editable = code;
                Core.app.post(() -> Vars.ui.showInfoFade("@logicsugar.external.edit"));
            }
        }
        effectiveLibrary = SugarCompiler.effectiveLibrary(code, SugarFunctions.library(), FunctionLibrary.loadText());
        libraryHashAtOpen = FunctionLibrary.hash();
        Cons<String> submit = sugar -> submit(sugar, executor, modified, key, false);
        super.show(editable, executor, privileged, submit);

        // LogicDialog normally suppresses equal results. Sugar must always win the
        // close race against remote processor edits, so replace that consumer.
        setConsumer(sugar -> submit(sugar, executor, modified, key, true));
    }

    private void submit(String sugar, LExecutor executor, Cons<String> modified, Object key, boolean closing){
        // Stale-close protection: an untouched canvas must not clobber a save that happened
        // while the dialog was open; an edited canvas always submits (last writer wins).
        if(executor != null && !SugarCompiler.shouldSubmit(sugar, editable, openedCode, currentCode(executor))){
            return;
        }
        if(executor != null){
            // the library file may have been edited while the processor editor stayed open;
            // refresh the session snapshot so calls resolve against the current library
            int hash = FunctionLibrary.hash();
            if(hash != libraryHashAtOpen){
                libraryHashAtOpen = hash;
                effectiveLibrary = SugarCompiler.effectiveLibrary(openedCode, SugarFunctions.library(), FunctionLibrary.loadText());
            }
        }
        try{
            String compiled = SugarCompiler.compile(sugar, SugarCompiler.currentMode(), effectiveLibrary.index, effectiveLibrary.text);
            if(executor != null && executor.build != null && !executor.build.isValid()){
                drafts.remove(key);
                return;
            }
            if(executor != null && executor.build != null){
                compiled = enforceStorageLimit(compiled, executor);
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

    /** The stored code as of right now (the build may have been reconfigured while open). */
    private String currentCode(LExecutor executor){
        return executor.build != null ? executor.build.code : openedCode;
    }

    /** Library-file editing sessions only: close the editor without saving, so a user who
     *  cannot (or does not want to) fix the library can leave instead of being forced to
     *  reopen until the content validates. The library file keeps its last saved content. */
    private void discardLibraryChanges(){
        if(executor != null) return; // processor sessions keep their normal close semantics
        passThroughSugarOnError = false;
        drafts.clear();
        hide();
    }

    /**
     * Pre-checks the 16KB compressed storage limit before the block's own consumer does.
     * Over the limit, the comment marker (redundant sugar source) is stripped and the
     * carrier-based restore is kept; still over, the compile fails loudly so the draft
     * retention path keeps the user's work instead of vanilla's silent drop.
     */
    private String enforceStorageLimit(String compiled, LExecutor executor){
        LogicBlock.LogicBuild build = (LogicBlock.LogicBuild)executor.build;
        Seq<LogicBlock.LogicLink> links = build.relativeConnections();
        byte[] bytes = LogicBlock.compress(compiled, links);
        if(bytes.length <= maxCompressedBytes) return compiled;
        String stripped = SugarCompiler.stripMarkers(compiled);
        byte[] retry = LogicBlock.compress(stripped, links);
        if(retry.length <= maxCompressedBytes) return stripped;
        throw new IllegalArgumentException("Compiled program is too large to store: " + retry.length
            + " compressed bytes (limit " + maxCompressedBytes + "), even without the comment marker.");
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
