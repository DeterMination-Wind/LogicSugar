package mindustry.logic;

import arc.struct.Seq;
import logicsugar.assist.AssertInstructions;
import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.ctype.Content;
import mindustry.ctype.ContentType;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.MechUnit;
import mindustry.logic.SugarAsserts.AssertionDataType;

/**
 * Pinned coverage for the {@code asserttype} card (upstream MlogAssertions has the same
 * instruction since v0.8.1; the wire format is pinned in SugarAssertsTest as well).
 *
 * <p><b>Compile layer</b> — a sugar program carrying {@code asserttype} cards lowers to
 * the exact fixed 4-token mlog lines in emit (debug build) mode and produces nothing but
 * a carrier in strip mode, with the sugar (assertions included) surviving in the carrier.
 * The token order is upstream ≥v0.10's {@code <type> <value> <message>}.</p>
 *
 * <p><b>Semantic layer</b> — {@link AssertionDataType#matches(LVar)} classifies every LVar
 * state into exactly one type: a non-object value ({@code !isobj}) is a <em>number</em>,
 * a null object ({@code isobj && objval == null}) is <em>none</em> ("null" on the wire),
 * and everything else by {@code instanceof} on the object value, with the narrowest
 * subtype winning in {@link AssertionDataType#actualType(LVar)}. The two states are the
 * exact split the game itself uses ({@code LAssembler.putVar} creates variables as null
 * objects; {@code LVar.setnum} flips back to the non-object state).</p>
 *
 * <p><b>Memory-object scenario (upstream #12459)</b> — starting with v160 memory blocks
 * can store objects, so a memory read can hand back an object LVar instead of a number.
 * asserttype's number(!isobj) vs building/unit/... (isobj instanceof) distinction is what
 * lets a debug build tell "this read produced a building/unit/string" from "this read
 * produced a number". The v160 memory-object change makes this distinction relevant for
 * memory reads as well; the coverage below pins both sides of the split.</p>
 */
public class AssertTypeTest{
    public static void main(String[] args){
        SugarStatements.installParsers();
        // headless content table: generated entity/content constructors register through
        // Vars.content (same headless pattern as Vars.logicVars in the other self-tests)
        Vars.content = new ContentLoader();

        emitModeWritesAssertTypeLines();
        emittedLinesRoundTripAndAssemble();
        stripModeKeepsMlogVanilla();
        verifyRestoreAcceptsAssertTypeSugar();
        numberVarMatchesOnlyNumber();
        objectVarsMatchByInstanceof();
        nullObjectMatchesOnlyNone();
        System.out.println("LogicSugar AssertType self-test passed.");
    }

    // ===== compile layer =====

    private static void emitModeWritesAssertTypeLines(){
        String sugar = "set n 1\n"
            + "asserttype number n \"n should be a number\"\n"
            + "asserttype building sensor \"sensor should be a building\"\n"
            + "asserttype unit flag ~\n"
            + "op add out n 1\n";
        String compiled = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.emit);
        String mlog = SugarCompiler.stripMarkers(compiled);

        Seq<String> emitted = new Seq<>();
        for(String line : mlog.replace("\r\n", "\n").split("\n", -1)){
            if(line.startsWith("asserttype ")) emitted.add(line);
        }
        check(emitted.size == 3, "expected exactly three emitted asserttype lines, got: " + emitted);

        // exact token layout: opcode, type wire token, value, message ("~" for empty),
        // in source order (upstream >= v0.10)
        String[][] expected = {
            {"asserttype number n \"n should be a number\"", "number", "n"},
            {"asserttype building sensor \"sensor should be a building\"", "building", "sensor"},
            {"asserttype unit flag ~", "unit", "flag"},
        };
        for(int i = 0; i < expected.length; i++){
            String[] exp = expected[i];
            checkLine(exp[0], emitted.get(i));
            Seq<String> tokens = wireTokens(exp[0]);
            check(tokens.size == 4, "asserttype line must keep a fixed 4-token layout: " + exp[0] + " -> " + tokens);
            check(tokens.get(0).equals("asserttype"), "opcode token drifted: " + exp[0]);
            check(tokens.get(1).equals(exp[1]), "type token drifted: " + exp[0]);
            check(tokens.get(2).equals(exp[2]), "value token drifted: " + exp[0]);
        }
        check(mlog.contains("set n 1") && mlog.contains("op add out n 1"),
            "emit mode dropped regular instructions");
    }

    /** Emitted lines must be machine-readable: they re-parse into the same card and
     *  assemble into an {@code AssertTypeI} wired to the parsed type and value variable. */
    private static void emittedLinesRoundTripAndAssemble(){
        // LAssembler.var() consults the game constants table; the self-test runs headless
        Vars.logicVars = new GlobalVars();

        checkAssemblesTo("asserttype number n \"n should be a number\"", "n", AssertionDataType.number);
        checkAssemblesTo("asserttype building sensor \"sensor should be a building\"", "sensor", AssertionDataType.building);
        // none is spelled "null" on the wire (reserved word in Java); empty message is "~"
        checkAssemblesTo("asserttype null x ~", "x", AssertionDataType.none);
    }

    private static void checkAssemblesTo(String line, String valueName, AssertionDataType type){
        Seq<LStatement> parsed = LAssembler.read(line, true);
        check(parsed.size == 1, "emitted line did not parse into exactly one statement: " + line);
        check(parsed.get(0) instanceof SugarAsserts.AssertTypeCard, "not an AssertTypeCard: " + line);
        StringBuilder out = new StringBuilder();
        parsed.get(0).write(out);
        checkLine(line, out.toString(), "emit line round trip");

        LAssembler asm = LAssembler.assemble(line, true);
        check(asm.instructions.length == 1, "assemble produced wrong instruction count: " + line);
        check(asm.instructions[0] instanceof AssertInstructions.AssertTypeI, "not an AssertTypeI: " + line);
        AssertInstructions.AssertTypeI instr = (AssertInstructions.AssertTypeI)asm.instructions[0];
        check(instr.expectedType == type, "instruction type not wired from the card, expected " + type.token() + ": " + line);
        check(instr.actualValue.name.equals(valueName), "value var not wired, expected '" + valueName + "': " + line);
        if(line.contains("\"")){
            check(instr.message.isobj && instr.message.objval instanceof String str && !str.isEmpty(),
                "quoted message literal not unwrapped: " + instr.message.objval);
        }
    }

    private static void stripModeKeepsMlogVanilla(){
        String sugar = "set x 1\n"
            + "asserttype number x \"x should be a number\"\n"
            + "asserttype building sensor \"sensor should be a building\"\n"
            + "op add y x 1\n";
        String compiled = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        // the marker comment block echoes the sugar source; the executable mlog is what
        // remains after stripping it (vanilla parses/keeps only instructions + carriers)
        String mlog = SugarCompiler.stripMarkers(compiled);
        check(!mlog.contains("asserttype"), "strip mode leaked asserttype into mlog");
        check(mlog.contains("set x 1") && mlog.contains("op add y x 1"), "strip mode dropped regular instructions");
        check(SugarCompiler.isSugarProgram(compiled), "carrier missing after strip compile");
        String restored = SugarCompiler.restore(compiled);
        check(restored.contains("asserttype number x") && restored.contains("asserttype building sensor"),
            "carrier lost the asserttype statements");
    }

    private static void verifyRestoreAcceptsAssertTypeSugar(){
        // asserttype must be recognized as an assert opcode so verifyRestore tries both
        // emit shapes for asserttype-only debug builds (see SugarCompiler.verifyRestore)
        String sugar = "set x 1\nasserttype number x \"x should be a number\"\n";
        String debug = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.emit);
        check(SugarCompiler.verifyRestore(debug, sugar), "asserttype debug build failed carrier verification");
        String stripped = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        check(SugarCompiler.verifyRestore(stripped, sugar), "asserttype strip build failed carrier verification");
    }

    // ===== semantic layer: AssertionDataType.matches =====

    private static void numberVarMatchesOnlyNumber(){
        // non-object values: number matches regardless of the value (42.5 and 0 alike),
        // and never the object types — this is the "memory read gave a number" state
        LVar num = new LVar("n");
        num.isobj = false;
        num.numval = 42.5;
        checkOnlyMatches(AssertionDataType.number, num, "number 42.5");

        LVar zero = new LVar("zero");
        zero.isobj = false;
        zero.numval = 0;
        checkOnlyMatches(AssertionDataType.number, zero, "number 0");
    }

    private static void objectVarsMatchByInstanceof(){
        // The taxonomy is hierarchical since v0.11: a general kind matches any instance of
        // it, the numbered sub-types narrow it down, and the interface types (property /
        // readable / writable / senseable) cut across the class hierarchy. Only the leaf
        // classifications are disjoint; the failure message picks the narrowest match.
        LVar building = objectVar("b", new Building(){});
        checkMatches(AssertionDataType.building, true, building, "building");
        checkMatches(AssertionDataType.senseable, true, building, "a building is senseable");
        checkActual(building, "building");

        // MechUnit: a concrete generated unit class (mindustry.gen.Unit leaves
        // Builderc.validatePlans abstract, so Unit itself cannot be instantiated);
        // its constructor is protected, hence the anonymous subclass
        LVar unit = objectVar("u", new MechUnit(){});
        checkMatches(AssertionDataType.unit, true, unit, "unit");
        checkMatches(AssertionDataType.senseable, true, unit, "a unit is senseable");
        checkActual(unit, "unit");

        LVar content = objectVar("c", testContent());
        checkOnlyMatches(AssertionDataType.content, content, "content");
        checkActual(content, "content");

        LVar team = objectVar("t", Team.derelict);
        checkMatches(AssertionDataType.team, true, team, "team");
        checkMatches(AssertionDataType.senseable, true, team, "a team is senseable");
        checkActual(team, "team");
    }

    private static void nullObjectMatchesOnlyNone(){
        // isobj + objval == null is the game's own "null" state: it is what putVar creates
        // fresh variables as, what the null constant is, and what a null object value
        // (e.g. a sensor / memory read with no result) looks like
        LVar nullObj = objectVar("x", null);
        checkOnlyMatches(AssertionDataType.none, nullObj, "null object");
        checkActual(nullObj, "null");
        // the boundary the memory-object scenario leans on: a plain number is not null
        check(!AssertionDataType.none.matches(num("n", 1.5)), "number matched as null");
        check(!AssertionDataType.number.matches(nullObj), "null matched as number");
    }

    /** Asserts that exactly one type — {@code expected} — matches the variable. */
    private static void checkOnlyMatches(AssertionDataType expected, LVar var, String what){
        for(AssertionDataType type : AssertionDataType.all){
            boolean matched = type.matches(var);
            check(type == expected ? matched : !matched,
                what + ": expected " + expected.token() + (matched ? " only, but " : " to match, but ")
                    + type.token() + (type == expected ? " did not" : " matched"));
        }
    }

    /** Asserts that {@code type} is (or is not) in the set of types matching the variable. */
    private static void checkMatches(AssertionDataType type, boolean expected, LVar var, String what){
        check(type.matches(var) == expected, what + ": " + type.token() + (expected ? " did not match" : " matched"));
    }

    /** Asserts that the failure message would name {@code expected} for this value. */
    private static void checkActual(LVar var, String expected){
        String actual = AssertionDataType.actualType(var);
        check(actual.equals(expected), "actualType must report the narrowest match: expected " + expected + ", got " + actual);
    }

    // ===== helpers =====

    /** Token view of a wire line the way LParser sees it: quoted strings stay one token
     *  (the test lines carry no escaped quotes, so a simple quote toggle suffices). */
    private static Seq<String> wireTokens(String line){
        Seq<String> result = new Seq<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for(int i = 0; i < line.length(); i++){
            char c = line.charAt(i);
            if(c == '"'){
                quoted = !quoted;
                current.append(c);
            }else if(c == ' ' && !quoted && current.length() > 0){
                result.add(current.toString());
                current.setLength(0);
            }else{
                current.append(c);
            }
        }
        if(current.length() > 0) result.add(current.toString());
        return result;
    }

    private static LVar objectVar(String name, Object value){
        LVar var = new LVar(name);
        var.isobj = true;
        var.objval = value;
        return var;
    }

    private static LVar num(String name, double value){
        LVar var = new LVar(name);
        var.isobj = false;
        var.numval = value;
        return var;
    }

    /** A minimal Content instance (Vars.content is installed in main). */
    private static Content testContent(){
        return new Content(){
            @Override
            public ContentType getContentType(){
                return ContentType.item;
            }
        };
    }

    private static void checkLine(String expected, String actual){
        checkLine(expected, actual, "wire format mismatch");
    }

    private static void checkLine(String expected, String actual, String message){
        check(expected.equals(actual), message + "\n  expected: " + expected + "\n  actual:   " + actual);
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
