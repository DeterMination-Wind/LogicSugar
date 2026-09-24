package logicsugar;

import arc.func.Prov;
import arc.struct.IntSeq;
import arc.struct.Seq;
import mindustry.logic.LStatement;
import mindustry.logic.LogicDialog;
import mindustry.logic.SugarLogicDialog;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

/**
 * The logic editor is a single global reference ({@code Vars.ui.logic}), so a second mod that
 * extends {@link LogicDialog} can only be handled by explicit policy. These checks pin the two
 * halves that used to be wrong: the classification of "who owns the dialog right now", and the
 * naming contract for the setting and its three bundle labels.
 *
 * <p>Background: 逻辑工具 registers its {@code ClientLoadEvent} listener in its constructor while
 * this mod registers in {@code init()}, and {@code Core.app.post} is FIFO - so the other mod always
 * installs first and this mod always sees a foreign dialog. Its own guard only compares against
 * {@code SugarLogicDialog}, which no other mod can be, so it replaced that dialog and moved the
 * other mod's panels onto its own canvas, where they kept pointing at a detached instance.
 */
public final class EditorConflictTest{
    private EditorConflictTest(){}

    /** Stands in for a foreign mod's editor (e.g. logictool.ui.LogicToolLogicDialog). */
    public static class ForeignDialog extends LogicDialog{}

    /** Stands in for any future LogicSugar dialog subclass. */
    public static class SugarSubclass extends SugarLogicDialog{}

    public static void main(String[] args) throws IOException{
        classification();
        parseMatrix();
        bundleContract();
        installGuardHasNoAsymmetry();
        paletteHidingIsReversible();
        liveSwitchIsWired();
        cycleCoversEveryState();
        coexistCompilesOnTheClosePath();
        leavingCoexistRestoresTheForeignCanvas();
        askOffersEveryAnswer();
        coexistRebindsForeignPanels();
        coexistKeepsTheForeignPanelClickable();

        System.out.println("EditorConflictTest: all checks passed");
    }

    /** Null (nothing installed) and the game's own dialog are both safe to replace; everything else is not. */
    private static void classification(){
        check(LogicSugarMod.classify(null) == LogicSugarMod.EditorOwner.vanilla,
            "no dialog yet must classify as native");
        check(LogicSugarMod.classify(LogicDialog.class) == LogicSugarMod.EditorOwner.vanilla,
            "the game's own LogicDialog must classify as native");
        check(LogicSugarMod.classify(SugarLogicDialog.class) == LogicSugarMod.EditorOwner.sugar,
            "our own dialog must classify as sugar");
        check(LogicSugarMod.classify(SugarSubclass.class) == LogicSugarMod.EditorOwner.sugar,
            "a subclass of our dialog must still classify as sugar");
        check(LogicSugarMod.classify(ForeignDialog.class) == LogicSugarMod.EditorOwner.foreign,
            "another mod's LogicDialog subclass must classify as foreign, not as sugar");
    }

    /** Every state is exactly its id, and nothing else can turn the editor off by accident. */
    private static void parseMatrix(){
        check(LogicSugarMod.EditorConflict.parse("takeover") == LogicSugarMod.EditorConflict.takeover, "takeover");
        check(LogicSugarMod.EditorConflict.parse("ask") == LogicSugarMod.EditorConflict.ask, "ask");
        check(LogicSugarMod.EditorConflict.parse("stepaside") == LogicSugarMod.EditorConflict.stepAside, "stepaside");
        check(LogicSugarMod.EditorConflict.parse("coexist") == LogicSugarMod.EditorConflict.coexist, "coexist");
        check(LogicSugarMod.EditorConflict.parse("stepAside") == LogicSugarMod.EditorConflict.stepAside,
            "the stored value is matched case-insensitively");
        check(LogicSugarMod.EditorConflict.parse(null) == LogicSugarMod.EditorConflict.takeover,
            "a missing setting must fall back to takeover, never to a disabled editor");
        check(LogicSugarMod.EditorConflict.parse("") == LogicSugarMod.EditorConflict.takeover, "empty value");
        check(LogicSugarMod.EditorConflict.parse("garbage") == LogicSugarMod.EditorConflict.takeover, "unknown value");
    }

    /**
     * The settings button walks the states through a single {@code next()}, so a state that is missing
     * from that walk is a state the user cannot reach at all - which is exactly how a new mode ships
     * "implemented but unusable". One lap must therefore visit every declared state and come back.
     */
    private static void cycleCoversEveryState(){
        Seq<LogicSugarMod.EditorConflict> visited = new Seq<>();
        LogicSugarMod.EditorConflict state = LogicSugarMod.EditorConflict.takeover;

        for(int i = 0; i < LogicSugarMod.EditorConflict.values().length; i++){
            check(!visited.contains(state, true), "next() revisited " + state.id + ": the cycle would skip a state");
            visited.add(state);
            state = LogicSugarSettings.EditorConflictSetting.next(state);
        }

        check(state == LogicSugarMod.EditorConflict.takeover,
            "one lap must return to the default, got " + state.id);
        for(LogicSugarMod.EditorConflict declared : LogicSugarMod.EditorConflict.values()){
            check(visited.contains(declared, true), declared.id + " is unreachable from the settings button");
        }
    }

    /** Setting title/description keys and every value label must exist in every bundle. */
    private static void bundleContract() throws IOException{
        check("logicsugar.editorConflict".equals(LogicSugarMod.settingEditorConflict),
            "the setting key is what makes setting.<key>.name resolve; changing it breaks the title silently");

        File root = projectRoot();
        for(String name : new String[]{"bundle.properties", "bundle_zh_CN.properties", "bundle_zh_TW.properties"}){
            Properties bundle = load(root, name);
            for(String key : new String[]{
                // Setting() looks these two up from the setting name, not from a literal key
                "setting." + LogicSugarMod.settingEditorConflict + ".name",
                "setting." + LogicSugarMod.settingEditorConflict + ".description",
                "logicsugar.conflict.title",
                "logicsugar.conflict.ask",
                "logicsugar.conflict.useSugar",
                "logicsugar.conflict.useOther",
                "logicsugar.conflict.takeover",
                "logicsugar.conflict.stepaside",
                "logicsugar.conflict.coexist",
                "logicsugar.conflict.coexistfailed",
                "logicsugar.conflict.useCoexist",
                "logicsugar.coexist.failed"}){
                require(bundle, name, key);
            }
            for(LogicSugarMod.EditorConflict state : LogicSugarMod.EditorConflict.values()){
                require(bundle, name, "logicsugar.settings.editorconflict." + state.id);
            }

            // The button labels are looked up without format arguments (label() passes no args, and
            // the ask-dialog buttons are literal), so a placeholder would be printed literally;
            // every notice is formatted, so each must carry {0}.
            Seq<String> labels = new Seq<>();
            labels.add("logicsugar.conflict.useSugar", "logicsugar.conflict.useOther", "logicsugar.conflict.useCoexist");
            for(LogicSugarMod.EditorConflict state : LogicSugarMod.EditorConflict.values()){
                labels.add("logicsugar.settings.editorconflict." + state.id);
            }
            for(String key : labels){
                check(!bundle.getProperty(key).contains("{0}"), name + ": " + key + " must not contain {0}");
            }
            for(String key : new String[]{"logicsugar.conflict.ask", "logicsugar.conflict.takeover",
                "logicsugar.conflict.stepaside", "logicsugar.conflict.coexist",
                "logicsugar.conflict.coexistfailed", "logicsugar.coexist.failed"}){
                check(bundle.getProperty(key).contains("{0}"), name + ": " + key + " must contain {0}");
            }
        }
    }

    /**
     * The old guard asked "is this already mine?", which a foreign dialog always answers with "no",
     * so it silently replaced other mods. The classifier must stay the entry point instead.
     */
    private static void installGuardHasNoAsymmetry() throws IOException{
        String source = readSource("src/logicsugar/LogicSugarMod.java");

        check(!source.contains("!(Vars.ui.logic instanceof SugarLogicDialog)"),
            "the asymmetric guard must not come back: a foreign subclass fails it, so we would take over again");
        check(source.contains("EditorOwner owner = classify(current.getClass())"),
            "the install pass must go through classify()");
        check(source.contains("replaceEditor(foreignEditor, false)"),
            "taking over a foreign dialog must not carry its panels over");
        check(source.contains("if(!ownsEditor()) replaceEditor(foreignEditor, false)"),
            "takeover must replace the editor whenever it is not already ours. The old "
                + "`if(ownsEditor())` guard fired only when we already owned it, so switching to "
                + "takeover from coexist did nothing at all: coexist leaves Vars.ui.logic on the "
                + "foreign dialog, which made the guard false and the setting look broken");
        check(source.contains("transferOverlayPanels(old, sugar)"),
            "the game's own overlay panels (MindustryX) must still be carried over");
        check(source.contains("postInstall(false)"),
            "a step-aside install must skip the editor add-ons");
        check(source.contains("private static boolean ownsEditor()"),
            "the ownership question must stay in one place, so both branches answer it the same way");
    }

    /**
     * Parking the sugar cards has to be exactly reversible: a user who switches back must get the
     * same cards in the same palette slots, not a reordered or truncated list. The scan order is the
     * fiddly part, so it is exercised on a plain list rather than the game's palette.
     */
    private static void paletteHidingIsReversible(){
        Seq<Prov<LStatement>> all = new Seq<>();
        Prov<LStatement> sugar1 = () -> null, sugar2 = () -> null, sugar3 = () -> null;
        Prov<LStatement> other1 = () -> null, other2 = () -> null, other3 = () -> null;

        all.add(other1);
        all.add(sugar1);
        all.add(other2);
        all.add(sugar2);
        all.add(other3);
        all.add(sugar3);

        Seq<Prov<LStatement>> shelf = new Seq<>();
        IntSeq indexes = new IntSeq();

        LogicSugarMod.shelve(all, shelf, indexes, prov -> prov == sugar1 || prov == sugar2 || prov == sugar3);

        check(all.size == 3, "shelving must leave exactly the foreign cards, got " + all.size);
        check(shelf.size == 3, "all three sugar cards must be parked, got " + shelf.size);
        check(indexes.size == 3, "every parked card must remember its slot, got " + indexes.size);
        check(all.get(0) == other1 && all.get(1) == other2 && all.get(2) == other3,
            "the foreign cards must keep their relative order");

        LogicSugarMod.restore(all, shelf, indexes);

        check(all.size == 6, "restoring must put every card back, got " + all.size);
        check(all.get(0) == other1 && all.get(1) == sugar1 && all.get(2) == other2
                && all.get(3) == sugar2 && all.get(4) == other3 && all.get(5) == sugar3,
            "restored cards must land in the slots they came from");
        check(shelf.isEmpty() && indexes.isEmpty(),
            "the shelf must be emptied, or a later pass would insert the same cards twice");
    }

    /**
     * The setting is only read while an install pass runs, so the pass has to run again when the
     * setting changes. Without that, the label and the live editor disagree - exactly the state
     * where a user picks "take over", keeps the other mod's editor, and finds sugar cards that can
     * be inserted but never compile.
     */
    private static void liveSwitchIsWired() throws IOException{
        String mod = readSource("src/logicsugar/LogicSugarMod.java");
        String settings = readSource("src/logicsugar/LogicSugarSettings.java");

        check(mod.contains("public static void reapplyEditorConflict()"),
            "the settings button needs an entry point that re-applies the policy");
        check(settings.contains("LogicSugarMod.reapplyEditorConflict()"),
            "switching the setting must take effect immediately, not on the next launch");
        check(mod.contains("foreignEditor = current"),
            "the displaced dialog must be remembered, or 'step aside' cannot hand the editor back");
        check(mod.contains("Vars.ui.logic = foreignEditor"),
            "step aside must restore the other mod's dialog in place, not only hide our cards");
        check(mod.contains("shelveCards();"),
            "step aside must hide the sugar cards: a foreign editor can never compile them");
        check(mod.contains("restoreCards();"),
            "taking over must put the sugar cards back");
        check(mod.contains("getClassLoader() == LogicSugarMod.class.getClassLoader()"),
            "cards must be identified by loader, never by a hand-maintained class list");
        check(mod.contains("addonsInstalled"),
            "the editor add-ons must install once: re-running the pass would stack duplicate listeners");
    }

    /**
     * Coexist mode only works if it is wired the way the other editor actually behaves, and the two
     * facts that decide it were measured, not assumed:
     *
     * <ol>
     *   <li>逻辑工具 polls {@code canvas.save()} from {@code currentCode()} on every update, so
     *       compiling inside {@code save()} would run a full compile per frame and pop a compile
     *       error while the user is still typing. Compilation must hang off {@code consumer}, which
     *       vanilla calls exactly once, from {@code hidden(...)}.</li>
     *   <li>{@code LogicDialog.show} falls back to {@code canvas.load("")} when the canvas refuses
     *       the text, and 逻辑工具 reloads the canvas with {@code normalizeCode(canvas.save())} after
     *       {@code show} returns. Neither is "opening a program", and an untouched empty canvas looks
     *       "edited" to the close path - so if either of those overwrote the baseline, closing would
     *       silently overwrite the processor with sugar text or with an empty program.</li>
     * </ol>
     */
    private static void coexistCompilesOnTheClosePath() throws IOException{
        String source = readSource("src/mindustry/logic/SugarCoexist.java");
        String mod = readSource("src/logicsugar/LogicSugarMod.java");

        check(!source.contains("public String save()"),
            "the coexist canvas must not override save(): the other editor polls it every frame, "
                + "so compiling there costs a full compile per frame and reports errors mid-typing");
        check(source.contains("Cons<String> layer"), "the compile layer must be a consumer wrapper");
        check(source.contains("dialog.shown("),
            "LogicDialog rebuilds its consumer on every show, so the layer must be re-armed per show");
        check(source.contains("SugarDecompiler.decompile"),
            "opening a processor must decompile its mlog, otherwise carriers show up as junk cards");
        check(source.contains("reopening"),
            "a reload (canvas.load(\"\") fallback, or the other editor's post-show reload) must not be "
                + "mistaken for opening a program, or the embedded function library source is lost");
        check(!source.contains("downstream.get(baseline)"),
            "an untouched canvas must not hand its baseline back: the canvas holds sugar and vanilla "
                + "compares against mlog, so that would write sugar text into the processor");
        check(mod.contains("if(ownsEditor()) Vars.ui.logic = foreignEditor"),
            "coexist must hand the editor back: switched from the settings button, Vars.ui.logic may still be ours");
        check(mod.contains("public static SugarLogicDialog ownEditor()"),
            "the function library can only run in a SugarLogicDialog, so coexist must keep one reachable");
        check(readSource("src/logicsugar/FunctionLibraryDialog.java")
                .contains("LogicSugarMod.ownEditor()"),
            "the function library entry must go through ownEditor(), not through Vars.ui.logic");
    }

    /**
     * Leaving coexist must put the other editor's own canvas back. Without that, "step aside" would
     * only hide the sugar cards: the foreign dialog would still be running LogicSugar's canvas, so
     * sugar statements would keep compiling - the one thing step-aside promises not to do. The
     * install-time {@code shown} listener cannot be unregistered, so the parked canvas has to be
     * switched off as well or it would re-wrap the consumer the next time the dialog opens.
     */
    private static void leavingCoexistRestoresTheForeignCanvas() throws IOException{
        String source = readSource("src/mindustry/logic/SugarCoexist.java");
        String mod = readSource("src/logicsugar/LogicSugarMod.java");

        check(source.contains("public static boolean uninstall(LogicDialog dialog)"),
            "coexist needs an uninstall: the swapped-in canvas must be swappable back out");
        check(source.contains("coexist.active = false;"),
            "uninstall must deactivate the canvas, or the stale shown listener re-arms it later");
        check(source.contains("if(!active) return;"),
            "arm() must bail out once the canvas is no longer installed");
        check(mod.contains("SugarCoexist.uninstall(foreignEditor)"),
            "both leaving branches (takeover and step aside) must restore the other mod's canvas");
        check(source.contains("dialog.canvas instanceof CoexistCanvas coexist)"),
            "re-installing an already installed canvas must reuse it, or each switch stacks a "
                + "new shown listener and a second Consumer layer");
    }

    /**
     * The startup prompt used to offer only two of the four settings, which is how a mode ships
     * "implemented but unreachable". Every answer must be present and must run the same code as the
     * corresponding setting, or answering the prompt and switching the setting would diverge.
     */
    private static void askOffersEveryAnswer() throws IOException{
        String mod = readSource("src/logicsugar/LogicSugarMod.java");

        check(mod.contains("case takeover -> takeEditorOver();")
                && mod.contains("case stepAside -> stepAside();")
                && mod.contains("case coexist -> coexist();"),
            "the setting must dispatch to the three shared behaviours");
        // switch labels are the enum *constant* names (stepAside), not the lower-cased setting ids
        // (stepaside) - pinning the id here would silently check a string that never appears.
        for(LogicSugarMod.EditorConflict state : LogicSugarMod.EditorConflict.values()){
            check(mod.contains("case " + state.name() + " ->"), state.id + " is missing from the dispatch");
        }

        // the prompt's three buttons call the shared behaviours, not a private copy of them
        int prompt = mod.indexOf("private static void askForEditor()");
        check(prompt > 0, "the ask dialog must exist");
        String body = mod.substring(prompt, Math.min(mod.length(), prompt + 2600));
        for(String call : new String[]{"takeEditorOver();", "coexist();", "stepAside();"}){
            check(body.contains(call), "the ask dialog must offer " + call);
        }
        check(body.contains("logicsugar.conflict.useCoexist"),
            "the coexisting answer needs its own label key");
        check(!body.contains("replaceEditor(foreign, false)"),
            "the ask dialog must not keep a second copy of the takeover branch");
    }

    /**
     * MindustryX's 逻辑辅助器 panel (mindustryX.features.ui.LogicSupport) remembers the canvas and
     * the consumer it was built with, and its 更新编辑的逻辑 button is consumer.get(canvas.save()) -
     * it never goes through LogicDialog.consumer. Inside a foreign editor that consumer is the
     * processor's own save callback, so without re-binding, one click would store our sugar text as
     * the program and the processor would stop loading it. The panel is optional (vanilla hosts do
     * not have the class) so this must stay reflective and must never break the compile path.
     *
     * <p>Two details decide whether the rebind actually lands, and both were got wrong once:</p>
     * <ol>
     *   <li>{@code LogicSupport.build()} is called by LogicDialog.show(String, ...) <em>before</em>
     *       it calls {@code show()}, i.e. before our {@code shown} listener runs - so the rebind has
     *       to sit at {@code arm()}'s own level and run on every session. Putting it after an early
     *       {@code return} (or inside the consumer-rewrapping branch) silently skips it whenever the
     *       consumer did not change, which is exactly the session where MindustryX has just pointed
     *       the panel back at the raw callback.</li>
     *   <li>{@code build()} assigns the panel's static executor to whatever it is given, and
     *       {@code rebuildVarsTable()} returns immediately on a null executor - so passing null
     *       (a failed reflection read) would blank a working panel instead of leaving it alone.</li>
     * </ol>
     */
    private static void coexistRebindsForeignPanels() throws IOException{
        String source = readSource("src/mindustry/logic/SugarCoexist.java");

        check(source.contains("mindustryX.features.ui.LogicSupport"),
            "the MindustryX logic-support panel must be re-bound to our canvas");
        check(source.contains("private void bindLogicSupport()"), "the rebind must be its own step");

        // arm()'s own body is indented 12 spaces; a line inside its branches is indented deeper.
        // Anchoring on the indentation is how this pins "runs unconditionally, at method level".
        int arm = source.indexOf("private void arm(LogicDialog dialog){");
        check(arm > 0, "arm() must exist");
        String armBody = source.substring(arm, Math.min(source.length(), arm + 2400));
        check(armBody.contains("\n            bindLogicSupport();"),
            "bindLogicSupport() must sit at arm()'s own indentation level: inside the "
                + "consumer-rewrapping branch, a session whose consumer did not change skips the rebind");
        check(!armBody.contains("|| next == compiled) return;"),
            "arm() must not early-return before the rebind: MindustryX re-points the panel on every "
                + "show, whether or not the consumer changed");

        check(source.contains("logicSupportBuild.invoke(null, this, executor,"),
            "the panel must get our canvas, the dialog's executor and a compiling consumer");
        check(source.contains("if(executor == null){"),
            "a null executor must be left alone: build() would blank the panel's variable table");
        check(source.contains("could not rebind MindustryX's logic-support panel"),
            "a failed rebind must be logged, not swallowed: it fails by writing sugar text into the "
                + "processor, which is the worst thing to swallow");
        check(source.contains("LogicSupport.build is unavailable"),
            "a host with MindustryX but a changed API must be reported, not silently ignored");
        // arm() must still run twice per session -- and the listener that runs it must be installed
        // at most once per dialog: Element.shown() appends and cannot be unregistered, so a dialog
        // that goes coexist -> takeover -> coexist would otherwise stack one listener per switch,
        // each of them running against a canvas that by then has active=false (harmless, but it
        // multiplies the very log lines used to diagnose a wedged panel).
        check(source.split("coexist\\.arm\\(dialog\\);", -1).length - 1 == 2,
            "arm() must run both right after MindustryX's bind and once more on the next frame: the "
                + "foreign editor keeps touching the canvas after super.show() returns");
        check(source.contains("listened.put(dialog, Boolean.TRUE)"),
            "the shown listener must be installed at most once per dialog");
        check(source.contains("catch(Throwable ignored)")
                && source.contains("Class.forName(className, false"),
            "MindustryX is optional, so a missing class must be tolerated");
    }

    /**
     * The swapped-in canvas must keep the z-order it had. Arc's {@code Cell.setElement} is
     * {@code table.addChild(newElement)} internally, so the element keeps its <em>cell</em> (size and
     * layout are correct) but lands at the <em>end</em> of {@code children} - and Arc draws in that
     * order while hit-testing walks it in reverse. The canvas therefore ends up painted on top of
     * MindustryX's 逻辑辅助器 panel and swallows every click meant for its buttons, which reads as
     * "the panel moved below the statement blocks and none of its buttons work".
     *
     * <p>Measured in game: it only happens in coexist mode. Step-aside looked fine because that
     * session never swapped a canvas at all, so this is caused by the swap and nothing else.</p>
     *
     * <p><b>The z-order must be restored with {@code setZIndex}, never with
     * {@code removeChild + addChildAt}.</b> That first attempt made every statement disappear:
     * {@code Table.removeChild} nulls the element reference of the child's cell
     * ({@code arc Table.java:605}), so {@code Table.layout()} skips it, the canvas is never sized or
     * positioned, and the whole canvas collapses - with no exception in the log. {@code setZIndex}
     * only reorders {@code children}; {@code cells} and the cell/element link stay intact.</p>
     */
    private static void coexistKeepsTheForeignPanelClickable() throws IOException{
        String source = readSource("src/mindustry/logic/SugarCoexist.java");

        check(source.contains("private static boolean swapCanvas(LogicDialog dialog, LCanvas old, "
                + "LCanvas replacement, int index)"),
            "the swap must carry the original z-order: Cell.setElement() appends to children, and that "
                + "order decides both drawing and hit-testing");
        check(!source.contains(".removeChild("),
            "the z-order must never be restored with removeChild(): Table.removeChild nulls the "
                + "cell's element, Table.layout() then skips the canvas and every statement disappears");
        check(source.contains("\n                cell.setElement(replacement);\n                ")
                && source.contains("if(index >= 0) replacement.setZIndex(index);"),
            "setZIndex must run in the same cell branch as setElement(), not once after the loop - a "
                + "miss silently leaves the canvas on top of the foreign panel");
        check(source.contains("int index = dialog.canvas.getZIndex();"),
            "the z-order must be read before the swap (getZIndex on the outgoing canvas), never after "
                + "it - by then the new canvas has already been appended to the end");
        check(source.contains("coexist.originalIndex"),
            "uninstall must restore the z-order remembered at install time");
        check(readSource("src/logicsugar/assist/BoxSelect.java")
                .contains("if(!isDescendantOfCanvas(target, canvas)){"),
            "BoxSelect must keep letting clicks outside the canvas through, or a mis-layered panel is "
                + "still unclickable even after the z-order is fixed");
    }

    private static void require(Properties bundle, String where, String key){
        check(bundle.getProperty(key) != null, where + " is missing " + key);
    }
    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }

    private static Properties load(File root, String name) throws IOException{
        Properties properties = new Properties();
        try(Reader reader = new InputStreamReader(
            Files.newInputStream(root.toPath().resolve("assets/bundles").resolve(name)), StandardCharsets.UTF_8)){
            properties.load(reader);
        }
        return properties;
    }

    /** Project directory that contains {@code assets/bundles}; gradle runs from it, tests may run from a subdirectory. */
    /**
     * Reads a repository file for a source nail, with the line endings normalised to LF.
     * Windows checkouts hand these files back with CRLF (core.autocrlf), which makes any anchor
     * that expects a newline directly after a token miss - silently, since contains() just returns
     * false. Normalising here keeps the nails about the code and not the checkout.
     */
    private static String readSource(String relative) throws IOException{
        return Files.readString(projectRoot().toPath().resolve(relative), StandardCharsets.UTF_8)
            .replace("\r\n", "\n");
    }

    private static File projectRoot(){
        for(String candidate : new String[]{".", ".."}){
            File root = new File(candidate);
            if(new File(root, "assets/bundles/bundle.properties").isFile()
                && new File(root, "src/logicsugar/LogicSugarMod.java").isFile()){
                return root;
            }
        }
        throw new AssertionError("LogicSugar project directory not found from " + new File(".").getAbsolutePath());
    }
}
