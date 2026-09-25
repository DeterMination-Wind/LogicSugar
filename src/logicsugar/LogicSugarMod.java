package logicsugar;

import arc.Core;
import arc.files.Fi;
import arc.func.Boolf;
import arc.func.Prov;
import arc.scene.Element;
import arc.scene.ui.layout.Scl;
import arc.struct.IntSeq;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Log;
import arc.util.io.PropertiesUtils;
import java.util.Locale;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.gen.LogicIO;
import mindustry.logic.LStatement;
import mindustry.logic.LogicDialog;
import mindustry.logic.SugarCoexist;
import mindustry.logic.SugarFunctions;
import mindustry.logic.SugarLogicDialog;
import mindustry.logic.SugarStatements;
import mindustry.mod.Mod;
import mindustry.mod.Mods.LoadedMod;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.dialogs.SettingsMenuDialog;
import logicsugar.assist.BoxSelect;
import logicsugar.assist.JumpLineColor;
import logicsugar.assist.ProcessorStatus;
import logicsugar.assist.UnitFlags;
import logicsugar.assist.VarDisplayFilter;
import logicsugar.assist.data.ArrayBulkModule;
import logicsugar.assist.data.BitsetModule;
import logicsugar.assist.data.ChainModule;
import logicsugar.assist.data.ContainerModule;
import logicsugar.assist.data.DataModules;
import logicsugar.assist.data.ListHeapModule;
import logicsugar.assist.data.MapModule;
import logicsugar.assist.data.RecordModule;
import logicsugar.assist.data.SetModule;
import logicsugar.assist.expr.ArrayRegistry;
import logicsugar.assist.expr.ExprHook;

import static arc.Events.on;

public class LogicSugarMod extends Mod{
    public static boolean bekBundled = false;

    /** Setting key: what {@link #installEditor()} does when another mod already owns {@code Vars.ui.logic}. */
    public static final String settingEditorConflict = "logicsugar.editorConflict";

    private static boolean registered;

    /** The other mod's dialog, remembered when it is found in {@code Vars.ui.logic}: the settings
     *  button must be able to hand the editor back without a restart. Null when no other mod is
     *  involved, which also means the setting has nothing to arbitrate. */
    private static LogicDialog foreignEditor;
    private static String foreignModName;

    /** Sugar cards parked while another mod owns the editor, with the palette slots they came from.
     *  Only LogicSugar's own dialog compiles sugar, so leaving the cards listed in a foreign editor
     *  lets a user insert statements that can never compile: they save as raw sugar text and the
     *  program fails to load. The /add dialog re-reads {@code LogicIO.allStatements} every time it
     *  opens, so parking them takes effect immediately. */
    private static final Seq<Prov<LStatement>> shelvedCards = new Seq<>();
    private static final IntSeq shelvedIndexes = new IntSeq();
    private static boolean cardsShelved;

    /** The editor add-ons install scene listeners and update triggers, so they may run only once. */
    private static boolean addonsInstalled;

    /** LogicSugar's own dialog parked while a foreign editor keeps {@code Vars.ui.logic} (coexist
     *  mode). The function-library session can only run inside a {@link SugarLogicDialog}, and the
     *  foreign dialog cannot host it, so coexistence keeps one of ours for that - created on demand
     *  and never shown unless the library button is pressed. */
    private static SugarLogicDialog sideEditor;

    /** How the install pass treats a logic editor that another mod has already replaced. */
    public enum EditorConflict{
        /** Replace it, keeping only LogicSugar's editor. Destructive for the other mod: its editor
         *  UI is detached and only that mod could restore it, so this is deliberately not the
         *  default any more - see {@link #ask}. */
        takeover("takeover"),
        /** Ask once per launch, before touching anything. */
        ask("ask"),
        /** Keep the foreign editor; LogicSugar's editor and sugar language stay disabled. */
        stepAside("stepaside"),
        /** Keep the foreign editor's whole UI and put LogicSugar's canvas inside it, so both
         *  work at once: the other mod's panels and our sugar statements. */
        coexist("coexist");

        public final String id;

        EditorConflict(String id){
            this.id = id;
        }

        /** Tolerant of unknown or missing values, so a hand-edited settings file cannot break startup.
         *  Unknown, empty and missing all fall back to {@link #ask}: an unreadable value must never
         *  land on the destructive branch, and must never land on "editor disabled" either. */
        public static EditorConflict parse(String value){
            for(EditorConflict option : values()){
                if(option.id.equalsIgnoreCase(value)) return option;
            }
            return ask;
        }

        public static EditorConflict current(){
            return parse(Core.settings.getString(settingEditorConflict, ask.id));
        }
    }

    /** Who holds {@code Vars.ui.logic} right now. */
    public enum EditorOwner{
        /** Nothing installed yet, or the game's own dialog: replacing it is expected. */
        vanilla,
        /** LogicSugar's own dialog: the editor is already ours. */
        sugar,
        /** Another mod's subclass: replacing it silently destroys that mod's editor UI. */
        foreign
    }

    /** Classifies the dialog sitting in {@code Vars.ui.logic}. Pure, so the matrix stays testable headlessly. */
    public static EditorOwner classify(Class<?> dialogClass){
        if(dialogClass == null) return EditorOwner.vanilla;
        if(SugarLogicDialog.class.isAssignableFrom(dialogClass)) return EditorOwner.sugar;
        return dialogClass == LogicDialog.class ? EditorOwner.vanilla : EditorOwner.foreign;
    }

    /** Maps a foreign dialog class back to its mod, through the mod's own class loader. */
    public static String conflictingModName(Class<?> type){
        ClassLoader loader = type.getClassLoader();
        if(loader != null && Vars.mods != null){
            for(LoadedMod mod : Vars.mods.list()){
                if(mod.main != null && mod.main.getClass().getClassLoader() == loader
                    && mod.meta != null && mod.meta.name != null){
                    return mod.meta.name;
                }
            }
        }
        return type.getSimpleName();
    }

    @Override
    public void init(){
        mergeLocalizedBundle();
        registerStatements();
        SugarFunctions.setLibrarySource(FunctionLibrary::index);
        // Data-structure declarations check their slot range against the real capacity of the
        // memory block the variable is linked to (world-cell is linked as `cellN` but holds 512
        // slots, so the name alone is not evidence). Resolved lazily per use: the function
        // library session and headless self-tests have no processor and fall back to the name
        // heuristic inside ArrayRegistry.
        ArrayRegistry.setLinkResolverProvider(ArrayRegistry::processorLinks);
        on(ClientLoadEvent.class, event -> Core.app.post(LogicSugarMod::installEditor));
    }

    /** Re-applies the localized mod bundle, which the game's own pass cannot find on Android.
     *
     *  {@code Mods.buildFiles()} derives the file name it looks for from {@code Locale.toString()},
     *  but mod bundle files are named {@code bundle_<language>_<country>} - the spelling Arc's
     *  {@code I18NBundle.toFileHandle} builds. On the desktop the two agree, because
     *  {@code Locale.getDefault()} leaves the script field empty, so {@code zh} + {@code CN} prints
     *  as {@code zh_CN} and the file is found. Android's ICU fills that field in, {@code toString()}
     *  becomes {@code zh_CN_#Hans}, and the lookup asks for a file no mod ships: the {@code _zh_CN}
     *  round loads nothing, and every extra LogicSugar key keeps the English value supplied by the
     *  {@code bundle.properties} round. That is why the added statements read as English on a phone
     *  while the rest of the interface stays translated - the game's own keys come through Arc's
     *  path, which ignores the script subtag, and only the mod's keys go through this one.
     *
     *  Merging from the language and country fields here avoids the spelling question rather than
     *  answering it: nothing depends on how a platform punctuates the script subtag, and a new
     *  translation needs no extra file name. Loading the same file twice is harmless, so the desktop
     *  path - where the merge already succeeded - is unaffected, and a game language that has no
     *  translation here falls through without touching the English values.
     */
    private static void mergeLocalizedBundle(){
        try{
            if(Vars.mods == null || Core.bundle == null) return;

            LoadedMod mod = Vars.mods.getMod(LogicSugarMod.class);
            if(mod == null || mod.root == null) return;

            Fi folder = mod.root.child("bundles");
            if(!folder.exists()) return;

            Locale locale = Core.bundle.getLocale();
            String language = locale.getLanguage().replace("in", "id");
            String country = locale.getCountry();
            if(language.isEmpty()) return;

            // Most specific first, mirroring Arc's own candidate chain: bundle_zh_CN, then bundle_zh.
            String[] candidates = country.isEmpty()
                ? new String[]{"bundle_" + language}
                : new String[]{"bundle_" + language + "_" + country, "bundle_" + language};

            for(String name : candidates){
                Fi file = folder.child(name + ".properties");
                if(file.exists()){
                    PropertiesUtils.load(Core.bundle.getProperties(), file.reader());
                    Log.info("LogicSugar: merged '@.properties' into the current bundle.", name);
                    return;
                }
            }
        }catch(Throwable error){
            // A translation that cannot be merged must never stop the mod from loading.
            Log.err("LogicSugar: could not merge the localized bundle.", error);
        }
    }

    /** Installs - or deliberately leaves alone - the logic editor; see {@link EditorConflict}. */
    private static void installEditor(){
        if(Vars.ui == null || Vars.ui.logic == null) return;

        LogicDialog current = Vars.ui.logic;
        EditorOwner owner = classify(current.getClass());

        if(owner == EditorOwner.sugar){
            restoreCards();
            postInstall(true);
            return;
        }
        if(owner == EditorOwner.vanilla){
            replaceEditor(current, true);
            restoreCards();
            postInstall(true);
            return;
        }

        // Another mod already replaced ui.logic with its own LogicDialog subclass. Both mods defer
        // to Core.app.post, so the winner is decided purely by load order: whichever listener was
        // registered first installs first, and the other one then sees a foreign dialog. Neither
        // guard can spot the other reliably (a foreign subclass still passes `instanceof
        // LogicDialog`), so the outcome has to be chosen here instead of left to a race.
        foreignEditor = current;
        foreignModName = conflictingModName(current.getClass());
        Log.info("LogicSugar: the logic editor is currently provided by mod '@' (@)", foreignModName, current.getClass().getName());
        applyConflict();
    }

    /**
     * Applies {@link EditorConflict} to {@link #foreignEditor}. Shared by the startup pass and the
     * settings button, so switching the setting takes effect the next time the editor is opened
     * rather than the next time the game starts - a restart-only setting is indistinguishable from
     * a broken one.
     */
    private static void applyConflict(){
        if(foreignEditor == null) return;

        switch(EditorConflict.current()){
            case takeover -> takeEditorOver();
            case stepAside -> stepAside();
            case ask -> askForEditor();
            case coexist -> coexist();
        }
    }

    /** 「接管」：换掉对方的编辑器，只留我们的。也是共存装不上时的回落档。 */
    private static void takeEditorOver(){
        // 已经是我们自己的编辑器就别再换一次：那会平白丢掉正开着的画布。反之必须换——共存档
        // 下 Vars.ui.logic 是对方的对话框（里面装着我们的画布），只摘卡不换编辑器的话
        // 「切换到接管」就什么都不会发生，用户看到的就是"设置没起作用"。
        if(!ownsEditor()) replaceEditor(foreignEditor, false);
        // 上一步拿走编辑器后对方那个对话框就废了，但它里面可能还装着我们的共存画布；先还回去，
        // 免得以后切回让位档时对方的编辑器里还留着我们的画布（那会让糖语句照样被编译）。
        SugarCoexist.uninstall(foreignEditor);
        restoreCards();
        postInstall(true);

        // The outgoing dialog and the panels it built are now detached, and only that mod
        // could restore them: say so rather than dropping its UI without a trace.
        Log.warn("LogicSugar: took the logic editor over from '@'; that mod's editor UI is now detached.", editorModName());
        notice("logicsugar.conflict.takeover", editorModName());
    }

    /** 「让位」：把编辑器与糖语句一起停用，交还给对方。 */
    private static void stepAside(){
        shelveCards();
        // 画布也必须还回去：共存档留下的那个画布会继续编译糖语句，而让位档承诺的正是"停用糖语言"。
        SugarCoexist.uninstall(foreignEditor);
        // Hand the editor itself back. Skipping this would make the setting bite only after
        // a restart, and would hide the cards while our own editor is still installed.
        if(ownsEditor()) Vars.ui.logic = foreignEditor;
        postInstall(false);
        Log.warn("LogicSugar: mod '@' owns the logic editor, so the LogicSugar editor stays disabled.", editorModName());
        notice("logicsugar.conflict.stepaside", editorModName());
    }

    /** 「共存」：对方画界面，我们的画布跑在里面。装不上就回落接管档——半装的共存恰好会制造
     *  "能插糖语句卡、却永远不编译"的编辑器，那正是这一档要避免的。 */
    private static void coexist(){
        // Hand the editor back first. This branch is reachable from the settings button too,
        // where our own dialog may still be installed - and coexistence would then be a
        // no-op, because the game keeps opening the editor we left behind.
        if(ownsEditor()) Vars.ui.logic = foreignEditor;

        if(SugarCoexist.install(foreignEditor)){
            // The other editor keeps the palette, so the sugar cards must be listed for it.
            restoreCards();
            postInstall(true);
            Log.info("LogicSugar: mod '@' draws the editor, LogicSugar's canvas edits the program.", editorModName());
            notice("logicsugar.conflict.coexist", editorModName());
        }else{
            Log.warn("LogicSugar: cannot place LogicSugar's canvas inside mod '@' editor; taking the editor over instead.", editorModName());
            takeEditorOver();
            notice("logicsugar.conflict.coexistfailed", editorModName());
        }
    }

    private static String editorModName(){
        return foreignModName == null ? foreignEditor.getClass().getSimpleName() : foreignModName;
    }

    /** The dialog a LogicSugar editing session should run in: ours when we own the editor, otherwise
     *  the parked side editor in coexist mode. Null when neither exists, which is the signal that
     *  LogicSugar's editing sessions are deliberately disabled. */
    public static SugarLogicDialog ownEditor(){
        if(Vars.ui != null && Vars.ui.logic instanceof SugarLogicDialog sugar) return sugar;
        if(EditorConflict.current() != EditorConflict.coexist) return null;
        if(sideEditor == null) sideEditor = new SugarLogicDialog();
        return sideEditor;
    }

    /** Whether {@code Vars.ui.logic} is currently ours, asked through the classifier rather than an
     *  {@code instanceof} test so the ownership question has exactly one implementation. */
    private static boolean ownsEditor(){
        return Vars.ui != null && Vars.ui.logic != null && classify(Vars.ui.logic.getClass()) == EditorOwner.sugar;
    }

    /**
     * Re-applies the current conflict setting in place. Called from the settings button: the setting
     * is read on every install pass, but a pass used to happen only at startup, so switching it
     * changed nothing until the game was restarted.
     */
    public static void reapplyEditorConflict(){
        if(Vars.ui == null || Vars.ui.logic == null) return;

        if(foreignEditor == null){
            // No third party involved: every setting keeps LogicSugar's editor, so only the editor
            // add-ons have to be refreshed (they are what a step-aside pass skips).
            if(classify(Vars.ui.logic.getClass()) == EditorOwner.vanilla){
                replaceEditor(Vars.ui.logic, true);
            }
            restoreCards();
            postInstall(true);
            return;
        }
        applyConflict();
    }

    /** Builds LogicSugar's dialog. {@code carryPanels} moves the outgoing dialog's overlay panels
     *  onto it, which is only correct for the game's own dialog: a foreign mod's panels keep
     *  pointing at that mod's (now detached) dialog instance and would go dead inside ours. */
    private static void replaceEditor(LogicDialog old, boolean carryPanels){
        SugarLogicDialog sugar = new SugarLogicDialog();
        if(carryPanels) transferOverlayPanels(old, sugar);
        Vars.ui.logic = sugar;
    }

    /** The editor add-ons only make sense on our own canvas, so a step-aside pass skips them. */
    private static void postInstall(boolean ownsEditor){
        if(Vars.ui == null || Vars.ui.logic == null) return;

        // Attaching twice would stack duplicate scene listeners and update triggers, and the
        // setting can now be switched any number of times after this pass.
        if(ownsEditor && !addonsInstalled){
            addonsInstalled = true;
            Vars.ui.logic.hidden(JumpLineColor::clearCache);
            BoxSelect.init();
            ExprHook.init();
            VarDisplayFilter.init();
            ProcessorStatus.init();
            ProcessorStatus.applySettings();
            UnitFlags.init();
            UnitFlags.applySettings();
        }

        // When bundled into Neon, every settings row is registered through bekBuildSettings
        // (host sets bekBundled, host calls bekBuildSettings), so the mod-owned category is
        // skipped entirely to avoid duplicate entries. It survives a step-aside either way,
        // because that page is the only way back to the other setting.
        if(!bekBundled){
            LogicSugarSettings.setup(true);
        }
    }

    /**
     * The one-query dialog for {@link EditorConflict#ask}. The three answers run the same code as the
     * three settings, so answering here and switching the setting later cannot diverge; dismissing it
     * without an answer keeps the default.
     */
    private static void askForEditor(){
        boolean[] answered = {false};
        BaseDialog dialog = new BaseDialog(Core.bundle.get("logicsugar.conflict.title", "Logic editor conflict"));
        dialog.cont.add(Core.bundle.format("logicsugar.conflict.ask", editorModName()))
            .width(Math.min(460f, Core.graphics.getWidth() / 1.4f / Scl.scl(1f))).wrap().pad(4f)
            .get().setAlignment(Align.center, Align.center);
        // Three answers now share one row: at 200% UI scale they must still fit one screen width.
        dialog.buttons.defaults().size(176f, 54f).pad(2f);
        dialog.buttons.button(Core.bundle.get("logicsugar.conflict.useSugar", "LogicSugar"), () -> {
            answered[0] = true;
            dialog.hide();
            takeEditorOver();
        });
        dialog.buttons.button(Core.bundle.get("logicsugar.conflict.useCoexist", "Coexist"), () -> {
            answered[0] = true;
            dialog.hide();
            coexist();
        });
        dialog.buttons.button(Core.bundle.get("logicsugar.conflict.useOther", "Other mod"), () -> {
            answered[0] = true;
            dialog.hide();
            stepAside();
        });
        dialog.hidden(() -> {
            if(!answered[0]){
                // Dismissing without an answer must not pick the destructive branch: leave the other
                // mod's editor exactly as it is. The setting stays on ask, so the next launch asks
                // again; answering is what opts into takeover or coexistence.
                stepAside();
            }
        });
        dialog.show();
    }

    /** A toast rather than a modal: the conflict resurfaces on every launch and must not nag. */
    private static void notice(String key, String mod){
        if(Vars.ui == null) return;
        Vars.ui.showInfoFade(Core.bundle.format(key, mod));
    }

    /** Transfers foreign overlay panels (e.g. MindustryX logic support) from the old dialog onto the new one.
     * Only children other than the canvas/buttons are moved, preserving their z-order above both. */
    private static void transferOverlayPanels(LogicDialog old, SugarLogicDialog sugar){
        if(old == null) return;
        int transferred = 0;
        Seq<Element> children = old.getChildren().copy();
        for(Element child : children){
            if(child == old.canvas || child == old.buttons) continue;
            child.remove();
            sugar.addChild(child);
            transferred++;
        }
        if(transferred > 0){
            sugar.invalidateHierarchy();
            Log.info("LogicSugar: transferred @ overlay panel(s)", transferred);
        }
    }

    /** True when a palette card was registered by this mod. The game's own statements are loaded by
     *  the app loader and another mod's by that mod's loader, so loader identity separates them
     *  without a hand-maintained list of classes. A card that cannot be instantiated is treated as
     *  foreign: never remove what cannot be identified. */
    private static boolean isOurs(Prov<LStatement> prov){
        try{
            LStatement statement = prov.get();
            return statement != null && statement.getClass().getClassLoader() == LogicSugarMod.class.getClassLoader();
        }catch(Throwable ignored){
            return false;
        }
    }

    /** Hides every sugar statement card from the palette while another mod owns the editor. */
    private static void shelveCards(){
        if(cardsShelved) return;
        cardsShelved = true;

        shelve(LogicIO.allStatements, shelvedCards, shelvedIndexes, LogicSugarMod::isOurs);
        if(shelvedCards.size > 0){
            Log.info("LogicSugar: hid @ statement card(s); the editor in use cannot compile them", shelvedCards.size);
        }
    }

    /** Puts the sugar cards back into the palette slots they were taken from. */
    private static void restoreCards(){
        if(!cardsShelved) return;
        cardsShelved = false;

        restore(LogicIO.allStatements, shelvedCards, shelvedIndexes);
    }

    /** Moves every card {@code isOurs} accepts out of {@code all} into {@code shelf}, recording the
     *  slot each one came from. Split out from {@link #shelveCards()} so the ordering rule - the
     *  part that is easy to get wrong - can be tested without the game's statics. */
    static void shelve(Seq<Prov<LStatement>> all, Seq<Prov<LStatement>> shelf, IntSeq indexes, Boolf<Prov<LStatement>> isOurs){
        // walking backwards keeps both lists ascending, so a later restore replays the original order
        for(int i = all.size - 1; i >= 0; i--){
            if(isOurs.get(all.get(i))){
                indexes.insert(0, i);
                shelf.insert(0, all.remove(i));
            }
        }
    }

    /** Inverse of {@link #shelve}: slot N gets back the card that was taken from it. */
    static void restore(Seq<Prov<LStatement>> all, Seq<Prov<LStatement>> shelf, IntSeq indexes){
        for(int i = 0; i < shelf.size; i++){
            all.insert(Math.min(indexes.get(i), all.size), shelf.get(i));
        }
        shelf.clear();
        indexes.clear();
    }

    /** Shared registration for game init, the decompiler preflight and headless tests.
     *  Idempotent: repeated calls do not duplicate palette cards or parsers. */
    public static void registerStatements(){
        if(registered) return;
        registered = true;

        LogicIO.allStatements.add(SugarStatements.ForBeginStatement::new);
        LogicIO.allStatements.add(SugarStatements.WhileBeginStatement::new);
        LogicIO.allStatements.add(SugarStatements.SwitchBeginStatement::new);
        LogicIO.allStatements.add(SugarStatements.IfBeginStatement::new);
        LogicIO.allStatements.add(SugarStatements.CaseStatement::new);
        LogicIO.allStatements.add(SugarStatements.DefaultStatement::new);
        LogicIO.allStatements.add(SugarStatements.ElseIfStatement::new);
        LogicIO.allStatements.add(SugarStatements.ElseStatement::new);
        LogicIO.allStatements.add(SugarStatements.BreakStatement::new);
        LogicIO.allStatements.add(SugarStatements.ContinueStatement::new);
        LogicIO.allStatements.add(SugarStatements.BlockEndStatement::new);
        LogicIO.allStatements.add(SugarStatements.FuncDefStatement::new);
        LogicIO.allStatements.add(SugarStatements.FuncCallStatement::new);
        LogicIO.allStatements.add(SugarStatements.ReturnStatement::new);
        LogicIO.allStatements.add(SugarStatements.ArrayStatement::new);
        LogicIO.allStatements.add(SugarStatements.MatrixStatement::new);
        // The old eight-slot arrayinit card remains parser-compatible for existing carriers,
        // but new programs use the array module's fill(buf, value) operation card instead.

        // Data subsystem modules (F2 framework): registering a module installs its
        // expression intrinsics provider (needed before the first compile/editor use);
        // registerParsers() below installs the declaration-card parsers. Both are
        // idempotent, so a repeated init() cannot duplicate palette entries or parsers.
        DataModules.register(new ArrayBulkModule());
        DataModules.register(new RecordModule());
        DataModules.register(new ContainerModule());
        DataModules.register(new BitsetModule());
        DataModules.register(new MapModule());
        DataModules.register(new SetModule());
        DataModules.register(new ListHeapModule());
        DataModules.register(new ChainModule());

        // single registration point shared with the decompiler preflight and the self-tests
        SugarStatements.installParsers();
        // record/stack/queue/deque/bitset/map/uset/list/heap/chain declaration cards + parsers
        DataModules.registerParsers();
    }

    /** Host (Neon) settings aggregation: function mode, editor conflict, library entry, overlays and
     *  jump line coloring. Must stay in sync with {@link LogicSugarSettings#setup} - a row missing
     *  here is a row a bundled user cannot reach at all, which is the bug class the editorConflict
     *  row below exists to prevent.
     *
     *  <p>Deliberate exception: {@code logicsugar.switchStrategy} is registered only in the standalone
     *  page, so a bundled user is pinned to the {@code auto} default. Unlike editorConflict that
     *  default is non-destructive - it only selects the cheaper lowering - so the row is left out to
     *  keep the aggregate settings page shorter. Do not add it here casually, and if either form ever
     *  changes, update the Neon-side note and the sync assertions in the same change (this repo's rule
     *  requires a deliberate dual-form gap to be recorded on both sides, not just here). */
    public void bekBuildSettings(SettingsMenuDialog.SettingsTable table){
        table.pref(new LogicSugarSettings.FuncModeSetting(LogicSugarSettings.settingFuncMode, "normal"));
        table.pref(new LogicSugarSettings.AssertEmitSetting(LogicSugarSettings.settingAssertEmit, "strip"));
        // Reachable in the aggregate form too: this is the only settings page a Neon user sees, and
        // without it the editor conflict stays stuck on whatever value it happens to hold.
        table.pref(new LogicSugarSettings.EditorConflictSetting(LogicSugarMod.settingEditorConflict, LogicSugarMod.EditorConflict.ask.id));
        table.pref(new LogicSugarSettings.LibraryButtonSetting("logicsugar.funclib"));
        LogicSugarSettings.addProcessorStatusPrefs(table);
        LogicSugarSettings.addUnitFlagsPref(table);
        LogicSugarSettings.addHideVarsPref(table);
        LogicSugarSettings.addBoxSelectPrefs(table);
        LogicSugarSettings.addCompactCardsPref(table);
        LogicSugarSettings.addCounterJumpPrefs(table);
        JumpLineColor.buildSettings(table);
    }
}
