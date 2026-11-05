package logicsugar.assist.expr;

import logicsugar.LogicSugarMod;
import mindustry.Vars;
import mindustry.logic.GlobalVars;
import mindustry.logic.LAccess;
import mindustry.logic.LAssembler;
import mindustry.logic.LExecutor;
import mindustry.logic.LVar;
import mindustry.logic.Senseable;
import mindustry.logic.SugarCompiler;

import java.util.List;

/**
 * Self-test for the compile-time unit family/tier/planet getters and the reserved {@code @__}
 * constant namespace. Kept headless: it checks the expression streams and the carrier/verify
 * round trip, plus the pure logic-id table helpers. It does not initialize game content.
 */
public final class UnitClassTest{
    private UnitClassTest(){}

    private static int checks;

    public static void main(String[] args){
        Vars.logicVars = new GlobalVars();
        // The headless test does not run the full game content initializer. Register just the two
        // sensor access constants the generated code reads at runtime; the compiler itself only
        // sees the sugar source, which uses unit_family/unit_tier/unit_planet.
        Vars.logicVars.put("@type", LAccess.type);
        Vars.logicVars.put("@id", LAccess.id);
        LogicSugarMod.registerStatements();

        constants();
        table();
        expressionStreams();
        foldBehavior();
        conditionsAndCarrier();
        runtimeValues();
        reservedTokens();
        registrationIsIdempotent();

        System.out.println("LogicSugar unit-class self-test passed (" + checks + " checks).");
    }

    private static void constants(){
        check(eq(SugarConstants.value("@__T5__"), 5), "@__T5__");
        check(eq(SugarConstants.value("@__t5__"), 5), "constants are case-insensitive");
        check(eq(SugarConstants.value("@__AIR__"), 2), "@__AIR__");
        check(eq(SugarConstants.value("@__GROUND__"), 1), "@__GROUND__");
        check(eq(SugarConstants.value("@__UNKNOWN__"), 0), "@__UNKNOWN__");
        check(eq(SugarConstants.value("@__SERPULO__"), 1), "@__SERPULO__");
        check(eq(SugarConstants.value("@__EREKIR__"), 2), "@__EREKIR__");
        check(eq(SugarConstants.value("@__EPS__"), 1e-6), "@__EPS__");
        check(eq(SugarConstants.value("@__TICKS_PER_SECOND__"), 60), "@__TICKS_PER_SECOND__");
        check(SugarConstants.value("@__NOPE__") == null, "unknown constant must not resolve");
        check(SugarConstants.isReserved("@__T5__"), "reserved prefix");
        check(!SugarConstants.isReserved("@t5"), "single underscore is not reserved");
        check(!SugarConstants.isReserved("t5"), "plain name is not reserved");
    }

    private static void table(){
        for(int id = 0; id <= 55; id++){
            int expectedFamily = id <= 14 ? 1 : id <= 24 ? 2 : id <= 34 ? 3
                : id <= 37 ? 8 : id <= 42 ? 4 : id <= 47 ? 5 : id <= 52 ? 6 : 7;
            check(UnitIntrinsics.familyOfId(id) == expectedFamily,
                "family id " + id + " expected " + expectedFamily);
            check(UnitIntrinsics.tierOfId(id) == ((id >= 38 ? id - 38 : id) % 5 + 1),
                "tier id " + id);
            check(UnitIntrinsics.planetOfId(id) == (id < 38 ? 1 : 2), "planet id " + id);
        }

        // Boundaries: modded (-1), other negative ids (not reachable through getLogicId today),
        // future vanilla (56+), and a few named units.
        for(int id : new int[]{-1, -2, -3, -4, 56, 57, 100}){
            check(UnitIntrinsics.familyOfId(id) == 0, "unknown family " + id);
            check(UnitIntrinsics.tierOfId(id) == 0, "unknown tier " + id);
            check(UnitIntrinsics.planetOfId(id) == 0, "unknown planet " + id);
        }
        check(UnitIntrinsics.familyOfId(0) == 1 && UnitIntrinsics.familyOfId(14) == 1, "ground range");
        check(UnitIntrinsics.familyOfId(15) == 2 && UnitIntrinsics.familyOfId(24) == 2, "air range");
        check(UnitIntrinsics.familyOfId(25) == 3 && UnitIntrinsics.familyOfId(34) == 3, "naval range");
        check(UnitIntrinsics.familyOfId(35) == 8 && UnitIntrinsics.familyOfId(37) == 8, "core range");
        check(UnitIntrinsics.familyOfId(38) == 4 && UnitIntrinsics.familyOfId(42) == 4, "tank range");
        check(UnitIntrinsics.familyOfId(43) == 5 && UnitIntrinsics.familyOfId(47) == 5, "mech range");
        check(UnitIntrinsics.familyOfId(48) == 6 && UnitIntrinsics.familyOfId(52) == 6, "ship range");
        check(UnitIntrinsics.familyOfId(53) == 7 && UnitIntrinsics.familyOfId(55) == 7, "support range");
        check(UnitIntrinsics.tierOfId(4) == 5, "reign is T5");
        check(UnitIntrinsics.tierOfId(34) == 5, "navanax is T5");
        check(UnitIntrinsics.tierOfId(38) == 1, "stell is T1");
        check(UnitIntrinsics.tierOfId(42) == 5, "conquer is T5");
        check(UnitIntrinsics.tierOfId(48) == 1, "elude is T1");
        check(UnitIntrinsics.tierOfId(52) == 5, "disrupt is T5");
        check(UnitIntrinsics.tierOfId(55) == 3, "emanate is T3");
    }

    private static void expressionStreams(){
        String family = text(ExprCompiler.compile("x", "unit_family(u)"));
        check(family.startsWith("sensor _0 u @type\nsensor _1 _0 @id\n"), "family starts with type/id sensors: " + family);
        check(family.contains("op idiv"), "family uses integer division");
        check(family.contains("op strictEqual"), "family protects null");
        check(family.contains("op lessThanEq"), "family protects future ids");
        check(lastLine(family).startsWith("op ") && lastLine(family).contains(" x "), "family result is written to dest: " + lastLine(family));
        check(!family.contains("@__"), "family stream has no compiler constant");
        check(lines(family) == 24, "family is 24 instructions, got " + lines(family));

        String tier = text(ExprCompiler.compile("x", "unit_tier(u)"));
        check(tier.startsWith("sensor _0 u @type\nsensor _1 _0 @id\n"), "tier starts with type/id sensors");
        check(tier.contains("op mod"), "tier uses modulo");
        check(tier.contains("op strictEqual"), "tier protects null");
        check(lastLine(tier).startsWith("op ") && lastLine(tier).contains(" x "), "tier result is written to dest");
        check(!tier.contains("@__"), "tier stream has no compiler constant");
        check(lines(tier) == 14, "tier is 14 instructions, got " + lines(tier));

        String planet = text(ExprCompiler.compile("x", "unit_planet(u)"));
        check(planet.startsWith("sensor _0 u @type\nsensor _1 _0 @id\n"), "planet starts with type/id sensors");
        check(planet.contains("op greaterThanEq") && planet.contains("op lessThanEq"), "planet range guards");
        check(lastLine(planet).startsWith("op ") && lastLine(planet).contains(" x "), "planet result is written to dest");
        check(!planet.contains("@__"), "planet stream has no compiler constant");
        check(lines(planet) == 11, "planet is 11 instructions, got " + lines(planet));

        // The constants are literals by the time the expression is lowered.
        check(text(ExprCompiler.compile("x", "@__T5__")).equals("set x 5\n"), "bare tier constant");
        String compare = text(ExprCompiler.compile("x", "unit_tier(u) == @__T5__"));
        check(compare.contains(" 5\n") && !compare.contains("@__"), "tier comparison uses literal 5");
        check(text(ExprCompiler.compile("x", "@__UNKNOWN__ + 1")).contains("op add"), "constants compose");

        expectParseError(() -> ExprCompiler.compile("x", "@__NOPE__"), "@__NOPE__");
        expectParseError(() -> ExprCompiler.compile("x", "@__T5__(u)"), "@__T5__");
    }

    private static void foldBehavior(){
        // A multi-line Expr card is saved as its expanded instructions. On reopen foldAll must
        // turn the chain back into an Expr card (the equivalent formula is acceptable; raw sensor
        // cards are not). This is the "reconstruction" side of the getter.
        for(String call : new String[]{"unit_family(u)", "unit_tier(u)", "unit_planet(u)"}){
            String expanded = text(ExprCompiler.compile("x", call));
            String folded = ExprFoldHarness.bodyOf(expanded);
            check(folded.startsWith("x = "), call + " must fold to an Expr card: " + folded);
            check(!folded.contains("sensor "), call + " was left as raw sensors: " + folded);
        }
    }

    private static void conditionsAndCarrier(){
        // Expression-mode condition: the classifier expands; the constant becomes 2.
        String source = "ifbegin expr \"unit_family(u) == @__AIR__\" 2\nset x 1\nblockend\n";
        String code = SugarCompiler.compile(source);
        check(code.contains("sensor "), "condition classifier was not expanded: " + code);
        check(code.contains("op idiv"), "condition family idiv missing");
        check(!SugarCompiler.stripMarkers(code).contains("@__"), "compiled condition leaked a compiler constant");
        check(code.contains("equal __ls_cond_0 0"), "condition result jump missing");

        // The carrier keeps the condition source; recompiling it must reproduce the stream.
        String restored = SugarCompiler.restore(code);
        check(restored.contains("unit_family(u) == @__AIR__"), "carrier lost the condition source");
        check(SugarCompiler.verifyRestore(code, restored), "condition carrier failed verifyRestore");

        // The same getter works in a while condition too.
        String loop = SugarCompiler.compile("whilebegin expr \"unit_tier(u) == @__T5__\" 2\nset x 1\nblockend\n");
        check(loop.contains("op mod") && !SugarCompiler.stripMarkers(loop).contains("@__"), "while condition expansion");

        String forCode = SugarCompiler.compile(
            "forbegin i 0 1 expr \"unit_tier(u) == @__T5__\" 2\nset x 1\nblockend\n");
        check(forCode.contains("op mod") && !SugarCompiler.stripMarkers(forCode).contains("@__"), "for condition expansion");

        String elif = SugarCompiler.compile(
            "ifbegin expr \"false\" 4\nset x 1\n"
                + "elif expr \"unit_family(u) == @__AIR__\"\nset x 2\nblockend\n");
        check(elif.contains("op idiv") && !SugarCompiler.stripMarkers(elif).contains("@__"), "elif condition expansion");

        // Constants also survive carrier verification through a structural condition.
        String constantSource = "ifbegin expr \"@__T5__ == 5\" 2\nset x 1\nblockend\n";
        String constantCode = SugarCompiler.compile(constantSource);
        check(!SugarCompiler.stripMarkers(constantCode).contains("@__"), "constant condition leaked");
        check(SugarCompiler.verifyRestore(constantCode, SugarCompiler.restore(constantCode)),
            "constant condition carrier failed verifyRestore");

        // Non-integer constants must survive the vanilla parser too (formatNum can emit
        // scientific notation for @__EPS__, and the saved program has to parse in-game).
        String eps = SugarCompiler.compile("ifbegin expr \"@__EPS__ == 0.000001\" 2\nset x 1\nblockend\n");
        check(!SugarCompiler.stripMarkers(eps).contains("@__"), "EPS leaked into the program");
        LAssembler.assemble(eps, true);
        String tick = SugarCompiler.compile(
            "ifbegin expr \"@__SECONDS_PER_TICK__ == 0.016666666666666666\" 2\nset x 1\nblockend\n");
        LAssembler.assemble(tick, true);

        // Multi-line getter inside a function return: function-local temp renaming must handle it.
        String fn = SugarCompiler.compile(
            "funcdef f u 2\nreturn \"unit_tier(u)\"\nblockend\nfunccall f \"5\" r\n");
        check(fn.contains("op mod") && !SugarCompiler.stripMarkers(fn).contains("@__"), "return expression expansion");
        check(SugarCompiler.verifyRestore(fn, SugarCompiler.restore(fn)), "return expression carrier failed");
    }

    private static void runtimeValues(){
        // Run the actual vanilla executor, not just inspect the text: a fake unit type makes
        // `sensor u @type` / `sensor t @id` return the chosen logic id, so the arithmetic itself
        // is exercised for every family boundary. The condition is compared with the expected
        // numeric value (the @__ constants are covered separately).
        int[] ids = new int[56 + 6];
        for(int id = 0; id <= 55; id++) ids[id] = id;
        ids[56] = -1;
        ids[57] = -2;
        ids[58] = -3;
        ids[59] = -4;
        ids[60] = 56;
        ids[61] = 100;
        for(int id : ids){
            int family = UnitIntrinsics.familyOfId(id);
            int tier = UnitIntrinsics.tierOfId(id);
            int planet = UnitIntrinsics.planetOfId(id);
            LExecutor executor = runClassifier(family, tier, planet, new FakeUnit(new FakeType(id)));

            check(flag(executor, "fFamily") == 1, "runtime family id " + id + " expected " + family);
            check(flag(executor, "fTier") == 1, "runtime tier id " + id + " expected " + tier);
            check(flag(executor, "fPlanet") == 1, "runtime planet id " + id + " expected " + planet);
        }

        // A null unit must be unknown for all three getters (id reads as the numeric value 0,
        // so the null guard has to do the work).
        LExecutor nullUnit = runClassifier(0, 0, 0, null);
        check(flag(nullUnit, "fFamily") == 1, "null unit family must be unknown");
        check(flag(nullUnit, "fTier") == 1, "null unit tier must be unknown");
        check(flag(nullUnit, "fPlanet") == 1, "null unit planet must be unknown");
    }

    private static LExecutor runClassifier(int family, int tier, int planet, Object unit){
        String source = "ifbegin expr \"unit_family(u) == " + family + "\" 2\n"
            + "set fFamily 1\n"
            + "blockend\n"
            + "ifbegin expr \"unit_tier(u) == " + tier + "\" 5\n"
            + "set fTier 1\n"
            + "blockend\n"
            + "ifbegin expr \"unit_planet(u) == " + planet + "\" 8\n"
            + "set fPlanet 1\n"
            + "blockend\n";
        String code = SugarCompiler.compile(source);
        LExecutor executor = new LExecutor();
        executor.load(LAssembler.assemble(code, true));
        LVar variable = executor.optionalVar("u");
        check(variable != null, "compiled program has no unit variable");
        variable.setobj(unit);

        int steps = 0;
        int cap = executor.instructions.length * 4 + 64;
        while(steps < cap && executor.counter.numval >= 0
            && executor.counter.numval < executor.instructions.length
            && !(steps > 0 && executor.counter.numval == 0)){
            executor.runOnce();
            steps++;
        }
        return executor;
    }

    private static double flag(LExecutor executor, String name){
        LVar variable = executor.optionalVar(name);
        return variable == null ? 0 : variable.num();
    }

    private static final class FakeType implements Senseable{
        final int id;

        FakeType(int id){
            this.id = id;
        }

        @Override
        public double sense(LAccess sensor){
            return sensor == LAccess.id ? id : Double.NaN;
        }

        @Override
        public Object senseObject(LAccess sensor){
            return noSensed;
        }
    }

    private static final class FakeUnit implements Senseable{
        final Senseable type;

        FakeUnit(Senseable type){
            this.type = type;
        }

        @Override
        public double sense(LAccess sensor){
            return Double.NaN;
        }

        @Override
        public Object senseObject(LAccess sensor){
            return sensor == LAccess.type ? type : noSensed;
        }
    }

    private static void reservedTokens(){
        // Plain vanilla card: no sugar statement, so the source-level guard must reject it.
        expectIllegal(() -> SugarCompiler.compile("set x @__T5__\n"), "@__T5__");
        // Simple (non-expression) condition field: the lowered-text guard must reject it.
        expectIllegal(() -> SugarCompiler.compile("ifbegin @__T5__ equal 5 2\nset x 1\nblockend\n"), "@__T5__");
        // Case values are deliberately not expanded.
        expectIllegal(() -> SugarCompiler.compile("switchbegin x 3\ncase @__T5__\nset y 1\nblockend\n"), "@__T5__");
        // A getter call in a raw token position is also rejected, not stored as an inert variable.
        expectIllegal(() -> SugarCompiler.compile("set x unit_family(u)\n"), "unit_family");
        expectIllegal(() -> SugarCompiler.compile("ifbegin unit_tier(u) equal 2 2\nset x 1\nblockend\n"), "unit_tier");
        expectIllegal(() -> SugarCompiler.compile("switchbegin unit_planet(u) 3\ncase 1\nset y 1\nblockend\n"), "unit_planet");
        // mlog strings have no backslash escapes: `print "C:\"` ends at the quote. The scanner
        // must not desync on it (both a bypass and a false rejection used to be possible).
        expectIllegal(() -> SugarCompiler.compile("print \"C:\\\"\nset x @__T5__\n"), "@__T5__");
        expectIllegal(() -> SugarCompiler.compile("print \"C:\\\"\nifbegin unit_family(u) equal 2 2\nset x 1\nblockend\n"), "unit_family");
        String backslash = SugarCompiler.compile("print \"C:\\\"\nprint \"@__T5__\"\n");
        check(backslash.contains("\"C:\\\"") && backslash.contains("\"@__T5__\""), "backslash string desynced the scan");
        // Constants in a short-circuit condition are still raw tokens.
        expectIllegal(() -> SugarCompiler.compile(
            "ifbegin exprsc \"@__T5__ == 5\" 2\nset x 1\nblockend\n"), "@__T5__");
        // Raw jump operand.
        expectIllegal(() -> SugarCompiler.compile("jump 0 equal @__T5__ 5\n"), "@__T5__");
        // Token-boundary check: these are ordinary variable names, not reserved tokens.
        SugarCompiler.compile("set x myunit_family(u)\nset y foo@__bar\n");
        // Inside a quoted string / comment it is just text; a user function with the same name is
        // still a normal funccall (no parentheses in the lowered form).
        String quoted = SugarCompiler.compile("set x \"@__T5__\"\n");
        check(quoted.contains("\"@__T5__\""), "quoted constant should be preserved");
        String commented = SugarCompiler.compile("set x 1\n# @__T5__ unit_family(u)\n");
        check(commented.contains("set x 1"), "comment-only constant should not fail");
        String quotedCall = SugarCompiler.compile("set x \"unit_family(u)\"\n");
        check(quotedCall.contains("\"unit_family(u)\""), "quoted getter call should be preserved");
        String userFunction = SugarCompiler.compile(
            "funcdef unit_family x 2\nreturn \"x\"\nblockend\nfunccall unit_family \"5\" r\n");
        check(userFunction.contains("funccall unit_family"), "user function must shadow the intrinsic");
    }

    private static void registrationIsIdempotent(){
        LogicSugarMod.registerStatements();
        check(ExprIntrinsics.isIntrinsicName("unit_family", 1), "unit_family intrinsic registered");
        check(ExprIntrinsics.isIntrinsicName("unit_tier", 1), "unit_tier intrinsic registered");
        check(ExprIntrinsics.isIntrinsicName("unit_planet", 1), "unit_planet intrinsic registered");
        check(!ExprIntrinsics.isIntrinsicName("unit_family", 2), "unit_family arity is one");
    }

    // ===== helpers =====

    private static String text(List<ExprCompiler.Line> lines){
        StringBuilder out = new StringBuilder();
        for(ExprCompiler.Line line : lines) out.append(line.toText()).append('\n');
        return out.toString();
    }

    private static String lastLine(String text){
        String trimmed = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        int at = trimmed.lastIndexOf('\n');
        return at < 0 ? trimmed : trimmed.substring(at + 1);
    }

    private static int lines(String text){
        int count = 0;
        for(int i = 0; i < text.length(); i++){
            if(text.charAt(i) == '\n') count++;
        }
        return count;
    }

    private static boolean eq(Double value, double expected){
        return value != null && value == expected;
    }

    private static void expectParseError(Runnable action, String fragment){
        try{
            action.run();
            throw new AssertionError("expected parse error containing " + fragment);
        }catch(ExprCompiler.ParseException expected){
            check(expected.getMessage().contains(fragment), "parse error mentions " + fragment + ": " + expected.getMessage());
        }
    }

    private static void expectIllegal(Runnable action, String fragment){
        try{
            action.run();
            throw new AssertionError("expected compile error containing " + fragment);
        }catch(IllegalArgumentException expected){
            check(expected.getMessage().contains(fragment), "compile error mentions " + fragment + ": " + expected.getMessage());
        }
    }

    private static void check(boolean condition, String message){
        checks++;
        if(!condition) throw new AssertionError(message);
    }
}
