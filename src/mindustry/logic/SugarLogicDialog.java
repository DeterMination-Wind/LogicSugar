package mindustry.logic;

import arc.Core;
import arc.func.Cons;
import arc.func.Prov;
import arc.input.KeyCode;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.style.Drawable;
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
import logicsugar.assist.expr.ExprCompiler;
import logicsugar.assist.expr.ExprStatement;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

public class SugarLogicDialog extends LogicDialog{
    private static final String compiledCopyName = "logicsugar-copy-compiled";
    private static final String originalViewName = "logicsugar-view-original";
    private static final Field consumerField = field(LogicDialog.class, "consumer");
    /** LogicDialog.privileged is package-private and lives in the MindustryX mod class loader at
     *  runtime, so it must be read reflectively (cross-loader package access throws
     *  IllegalAccessError). Both this and consumer keep the hard field(): they are load-bearing
     *  for the dialog (there is no degraded mode), unlike SugarCanvas's optional feature fields. */
    private static final Field privilegedField = field(LogicDialog.class, "privileged");
    /** Mirrors LogicBlock.maxCompressedLen (private upstream); read reflectively so the limit
     *  tracks upstream instead of drifting silently when the game adjusts it. */
    private static final int maxCompressedBytes = compressedLimit();
    private final Map<Object, String> drafts = new IdentityHashMap<>();
    /** Cached copy-button scan results (see {@link #installCompiledCopy}); cleared on hide. */
    private TextButton cachedCopyButton;
    private Table cachedCopyMenu;
    private Dialog cachedCopyDialog;
    private TextButton originalViewButton;
    private Table originalViewMenu;
    private Dialog originalViewDialog;
    /** Raw code and recovered source for the optional original/Sugar view toggle. */
    private String originalCode;
    private String recoveredSugar;
    private boolean showingOriginal;
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
                installOriginalView();
            }
        });
    }

    private void installEditHook(){
        Element candidate = buttons.find("edit");
        if(candidate == editButton || !(candidate instanceof Button button)) return;
        editButton = candidate;
        button.clicked(() -> Core.app.post(this::installCompiledCopy));
    }

    private static int compressedLimit(){
        try{
            Field field = LogicBlock.class.getDeclaredField("maxCompressedLen");
            field.setAccessible(true);
            return field.getInt(null);
        }catch(Exception exception){
            // upstream renamed/removed the constant; fall back to the known value
            return 16_000;
        }
    }

    private void installCompiledCopy(){
        if(cachedCopyButton != null && !inSceneTree(cachedCopyButton)){
            // the menu (and its dialog) was closed and detached; rescan from scratch.
            // A bare parent check is not enough: an old edit dialog's menu object may
            // survive hidden in the scene tree, so the stale button keeps matching it.
            clearCompiledCopyCache();
        }
        if(cachedCopyButton == null){
            TextButton found = findCopyButton(Core.scene.root);
            if(found != null){
                cachedCopyButton = found;
                cachedCopyMenu = found.parent instanceof Table table ? table : null;
                cachedCopyDialog = parentDialog(found);
            }
        }
        if(cachedCopyButton == null || cachedCopyMenu == null || cachedCopyDialog == null) return;
        if(cachedCopyMenu.find(compiledCopyName) != null) return;

        Dialog dialog = cachedCopyDialog;
        installMenuButton(cachedCopyMenu, compiledCopyName, "@logicsugar.copy.compiled", Icon.copy, () -> {
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
        });
    }

    /** Adds a menu action for switching between an inferred Sugar view and the stored mlog. */
    private void installOriginalView(){
        if(originalCode == null || recoveredSugar == null) return;
        if(originalViewButton != null && !inSceneTree(originalViewButton)) clearOriginalViewCache();
        if(originalViewButton == null){
            TextButton candidate = findTextButton(Core.scene.root, originalViewName);
            if(candidate != null){
                originalViewButton = candidate;
                originalViewMenu = candidate.parent instanceof Table table ? table : null;
                originalViewDialog = parentDialog(candidate);
            }
        }
        if(originalViewButton != null) return;
        // anchor next to the compiled-copy action, which runs first in the periodic scan
        if(cachedCopyButton == null || cachedCopyMenu == null || cachedCopyDialog == null) return;
        originalViewButton = installMenuButton(cachedCopyMenu, originalViewName,
            "@logicsugar.view.original", Icon.edit, this::toggleOriginalView);
        originalViewMenu = cachedCopyMenu;
        originalViewDialog = cachedCopyDialog;
    }

    /** Mounts one named action into a copy-dialog menu and returns its button. Shared by the
     *  compiled-copy and view-toggle features so their layout stays in lockstep. */
    private static TextButton installMenuButton(Table menu, String name, String labelKey,
                                                Drawable icon, Runnable handler){
        menu.row();
        TextButton result = menu.button(labelKey, icon, Styles.flatt, handler)
            .size(280f, 60f).left().marginLeft(12f).get();
        result.name = name;
        menu.invalidateHierarchy();
        return result;
    }

    private void clearCompiledCopyCache(){
        cachedCopyButton = null;
        cachedCopyMenu = null;
        cachedCopyDialog = null;
    }

    private void clearOriginalViewCache(){
        originalViewButton = null;
        originalViewMenu = null;
        originalViewDialog = null;
    }

    private TextButton findTextButton(Element element, String name){
        if(element instanceof TextButton button && name.equals(button.name)) return button;
        if(element instanceof Group group){
            for(Element child : group.getChildren()){
                TextButton found = findTextButton(child, name);
                if(found != null) return found;
            }
        }
        return null;
    }

    private void toggleOriginalView(){
        if(originalCode == null || recoveredSugar == null) return;
        String target = showingOriginal ? recoveredSugar : originalCode;
        String snapshot = showingOriginal ? originalCode : recoveredSugar;
        String current = safeCanvasSave();
        if(current == null || !current.equals(snapshot)){
            // the current view was edited: loading the other snapshot would drop those
            // edits silently, so switching requires explicit confirmation. A failed
            // save() (uncompilable expression) counts as edited too: an untouched,
            // verified canvas always serializes successfully.
            Vars.ui.showConfirm(
                Core.bundle.get("logicsugar.view.confirm", "Unsaved Changes"),
                Core.bundle.get("logicsugar.view.confirm.text",
                    "The current view has unsaved edits. Switching views discards them.\nContinue?"),
                () -> applyViewSwitch(target));
            return;
        }
        applyViewSwitch(target);
    }

    /** Canvas serialization for change detection. {@code save()} throws on uncompilable
     *  expressions; the caller then treats the view as edited, since an untouched,
     *  verified canvas always serializes successfully. */
    private String safeCanvasSave(){
        try{
            return canvas.save();
        }catch(Throwable exception){
            return null;
        }
    }

    private void applyViewSwitch(String target){
        try{
            canvas.load(target);
            editable = target;
            showingOriginal = !showingOriginal;
            updateOriginalViewButton();
            if(originalViewDialog != null) originalViewDialog.hide();
        }catch(Throwable exception){
            showCompileError(new IllegalArgumentException("Cannot switch logic view: " + exception.getMessage()), false);
        }
    }

    private void updateOriginalViewButton(){
        if(originalViewButton == null) return;
        originalViewButton.setText(showingOriginal ? "@logicsugar.view.sugar" : "@logicsugar.view.original");
    }

    private Dialog parentDialog(Element element){
        Element current = element;
        while(current != null && !(current instanceof Dialog)) current = current.parent;
        return (Dialog)current;
    }

    /** True when the element is still attached under the scene root (a closed dialog's
     *  children are detached from the tree, so a cached button there is stale). */
    private static boolean inSceneTree(Element element){
        Element current = element;
        while(current != null){
            if(current == Core.scene.root) return true;
            current = current.parent;
        }
        return false;
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
        this.originalCode = null;
        this.recoveredSugar = null;
        this.showingOriginal = false;
        clearOriginalViewCache();
        // drafts are keyed by Building; drop entries whose processor is gone so the map
        // cannot grow without bound over a session
        drafts.keySet().removeIf(key -> key instanceof Building build && !build.isValid());
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
                // First try to infer a Sugar view from pure vanilla mlog. The result is only
                // used when its normalized recompilation is verified; otherwise retain the
                // existing raw-code behavior for externally edited programs.
                SugarDecompiler.Result recovered = SugarDecompiler.decompile(code, privileged);
                // structured > 0 also keeps the decompiler's carrier branch out of this view:
                // carrier restoration is checked (and failed) above, so a "carrier" Result here
                // would be unreachable; requiring at least one real structure is belt-and-braces.
                if(recovered.verified && recovered.matchedMode != null && !"flat".equals(recovered.matchedMode)
                    && recovered.structured > 0){
                    editable = recovered.sugar;
                    originalCode = code;
                    recoveredSugar = recovered.sugar;
                    Core.app.post(() -> Vars.ui.showInfoFade("@logicsugar.recovered"));
                }else{
                    editable = code;
                    Core.app.post(() -> Vars.ui.showInfoFade("@logicsugar.external.edit"));
                }
            }
        }
        effectiveLibrary = SugarCompiler.effectiveLibrary(code, SugarFunctions.library(), FunctionLibrary.loadText());
        libraryHashAtOpen = FunctionLibrary.hash();

        // Never open the editor with code it cannot parse: LogicDialog's load fallback
        // (canvas.load("")) would present an empty canvas, and the stale-close guard treats
        // an untouched empty canvas as "edited", so closing would submit an empty program and
        // silently wipe the processor. Pre-validate with the same parse the canvas performs.
        try{
            LAssembler.read(editable, privileged);
        }catch(Throwable exception){
            hide();
            showCompileError(new IllegalArgumentException("Cannot open the logic editor: " + exception.getMessage()), false);
            return;
        }

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

    @Override
    public void hide(){
        // 关闭保存路径（hidden -> canvas.save()）没有 try/catch：ExprStatement.write() 对
        // 从未成功编译的表达式抛 IllegalArgumentException 会冒泡成引擎级报错。这里在
        // hide 之前预检，命中则提示并阻止关闭——画布原样保留（标红可见），不破坏任何状态。
        // 仅处理器会话执行预检：函数库会话（executor == null）有自己的错误处理与
        // "放弃修改"路径（discardLibraryChanges），不应被拦截。
        if(executor != null && !passThroughSugarOnError && hasUncompilableExpression()){
            Core.app.post(() -> showCompileError(
                new IllegalArgumentException(uncompilableExpressionMessage()), true));
            return;
        }
        clearCompiledCopyCache();
        clearOriginalViewCache();
        super.hide();
    }

    /** 画布上是否存在从未成功编译的表达式（ExprStatement.write 会因此抛错）。 */
    private boolean hasUncompilableExpression(){
        return uncompilableExpressionMessage() != null;
    }

    /** 第一个不可编译表达式的错误消息；全部合法时返回 null。
     *  与 ExprStatement.write() 完全同口径：同样使用 functionChecker() 校验函数名，
     *  否则未定义用户函数（如 noSuch(a)）会在预检中被漏过、保存时才抛错。 */
    private String uncompilableExpressionMessage(){
        if(canvas == null || canvas.statements == null) return null;
        for(Element child : canvas.statements.getChildren()){
            if(child instanceof LCanvas.StatementElem elem && elem.st instanceof ExprStatement expr){
                try{
                    ExprCompiler.compile(expr.dest, expr.expr, ExprStatement.functionChecker());
                }catch(Exception e){
                    return e.getMessage();
                }
            }
        }
        return null;
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
