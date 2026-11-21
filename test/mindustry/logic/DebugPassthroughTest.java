package mindustry.logic;

import arc.struct.Seq;
import logicsugar.assist.InstructionBudget;
import mindustry.Vars;

/**
 * Pinned coverage for the text-only debug-opcode layer ({@link SugarDebugLines}) and for the
 * pinned processor instruction budget.
 *
 * <p><b>Why a passthrough instead of cards.</b> Mlog Dev Tools owns the runtime behaviour of the
 * debug opcode family. LogicSugar keeps only what is needed for a saved program to survive:
 * a parsed line reproduces itself verbatim and assembles to one inert instruction, so an mlog
 * program containing a debug line still opens, round-trips, keeps every jump target and re-saves
 * on a machine where the line came from somewhere else.</p>
 *
 * <p><b>What is pinned here.</b> All 13 opcodes round-trip byte-identically (including the two
 * legacy wire shapes and {@code ~} padding), each line occupies exactly one instruction slot, the
 * {@code AssertEmit} switch decides whether the lines reach the saved product, and LogicSugar's
 * own generated bounds check survives decompilation as plain text (the recompilation gate compares
 * both the keep shape and the strip shape, using the same generated-line filter).</p>
 *
 * <p><b>Budget.</b> {@code LExecutor.maxInstructions} is a mutable static that other mods raise;
 * the saved product is nevertheless capped at the vanilla 1000 instructions, and the refusal
 * message has to state that number, not the raw static.</p>
 */
public final class DebugPassthroughTest{
    private DebugPassthroughTest(){
    }

    /** One representative line per opcode, in the exact shape each writer produces. */
    private static final String[] samples = {
        "assert equal x true \"msg\"",
        "assertBounds integer 2 0 lessThanEq i lessThanEq 10 \"msg\"",
        "assertequals x y \"msg\"",
        "assertflush 3",
        "assertprints 1 x \"msg\"",
        "asserttype string x \"msg\"",
        "error \"msg\" ~ ~ ~ ~ ~ ~ ~ ~ ~",
        "log info cat \"msg\" ~ ~ ~ ~ ~ ~ ~ ~ ~",
        "systemlog info \"msg\" ~ ~ ~ ~ ~ ~ ~ ~ ~",
        "breakpoint equal x true",
        "snapshot isolated cell1 20 \"msg\"",
        "profile start cell1",
        "restart cell1"
    };

    /** Old carrier shapes still found in existing saves: snapshot without the {@code steps}
     *  slot (4 tokens) and log without the {@code category} slot (12 tokens). */
    private static final String[] legacySamples = {
        "snapshot isolated cell1 \"legacy msg\"",
        "log info \"legacy msg\" ~ ~ ~ ~ ~ ~ ~ ~ ~"
    };

    public static void main(String[] args){
        SugarStatements.installParsers();
        // Production installs these from the post-ClientLoadEvent pass; headless tests mirror it.
        SugarDebugLines.installParsers();
        Vars.logicVars = new GlobalVars();

        allOpcodesRoundTrip();
        legacyWireShapes();
        oneInstructionPerLineAndJumpTargets();
        assertEmitShapes();
        foreignDebugStatements();
        generatedBoundsCheckStaysInRecoveredSugar();
        budgetPin();

        System.out.println("LogicSugar debug passthrough self-test passed.");
    }

    /** {@code write(read(text))} is byte-identical for every opcode, and a second pass changes
     *  nothing (idempotent), including {@code ~} padding in the middle of a line. */
    private static void allOpcodesRoundTrip(){
        for(String text : samples){
            String once = roundTrip(text);
            check(once.equals(text), "wire format drifted:\n  expected: " + text + "\n  actual:   " + once);
            String twice = roundTrip(once);
            check(twice.equals(once), "round trip is not idempotent:\n  1: " + once + "\n  2: " + twice);
            check(parsed(text).size == 1, "one line must parse to exactly one statement: " + text);
            check(SugarDebugLines.containsDebugLine(text), "the opcode pre-scan missed: " + text);
        }
    }

    /** The two families with a legacy shape keep their token count: the discriminating slot decides
     *  (a quoted string there cannot be a current value), so old saves do not gain a token. */
    private static void legacyWireShapes(){
        for(String text : legacySamples){
            String written = roundTrip(text);
            check(written.equals(text), "legacy shape was rewritten:\n  expected: " + text + "\n  actual:   " + written);
            check(written.split(" ").length == text.split(" ").length,
                "legacy token count drifted: " + written);
        }
        // Current shapes of the same two families keep their extra slot.
        check(roundTrip(samples[10]).split(" ").length == 5, "current snapshot must keep 5 tokens");
        check(roundTrip(samples[7]).split(" ").length == 13, "current log must keep 13 tokens");
    }

    /** Every debug line is one instruction (never {@code null}, which the assembler would drop and
     *  every following jump target would shift), and a numeric jump after one keeps its target. */
    private static void oneInstructionPerLineAndJumpTargets(){
        StringBuilder program = new StringBuilder();
        for(String text : samples) program.append(text).append('\n');
        Seq<LStatement> statements = parsed(program.toString());
        check(statements.size == samples.length, "expected one statement per line, got " + statements.size);
        LAssembler assembler = new LAssembler();
        for(LStatement statement : statements){
            check(statement.build(assembler) instanceof LExecutor.NoopI,
                "debug line did not build an inert instruction: " + write(statement));
        }

        // jump 2 must keep pointing at the print: the debug line occupies slot 0.
        String withJump = samples[7] + "\njump 2 always x false\nprint \"x\"\n";
        LAssembler assembled = LAssembler.assemble(withJump, true);
        check(assembled.instructions.length == 3,
            "a debug line must occupy an instruction slot, got " + assembled.instructions.length);
        check(assembled.instructions[0] instanceof LExecutor.NoopI, "slot 0 must be the debug line");
        check(assembled.instructions[1] instanceof LExecutor.JumpI, "slot 1 must be the jump");
        check(((LExecutor.JumpI)assembled.instructions[1]).address == 2,
            "the jump target shifted: " + ((LExecutor.JumpI)assembled.instructions[1]).address);
    }

    /** strip (the default, and the multiplayer shape) removes the lines from the saved product;
     *  emit keeps them verbatim. */
    private static void assertEmitShapes(){
        StringBuilder sugar = new StringBuilder("set x 1\n");
        for(String text : samples) sugar.append(text).append('\n');

        String strip = stripCompile(sugar.toString(), SugarCompiler.AssertEmit.strip);
        for(String text : samples){
            check(!strip.contains(text), "strip build leaked a debug line: " + text + "\n" + strip);
        }
        check(strip.contains("set x 1"), "strip build dropped the ordinary program:\n" + strip);

        String emit = stripCompile(sugar.toString(), SugarCompiler.AssertEmit.emit);
        int cursor = -1;
        for(String text : samples){
            int at = emit.indexOf(text);
            check(at >= 0, "emit build dropped a debug line: " + text + "\n" + emit);
            check(at > cursor, "emit build reordered the debug lines at: " + text);
            cursor = at;
        }
        // Still one instruction per line in the product: the stored program assembles.
        check(LAssembler.assemble(emit, true).instructions.length > 0,
            "the emitted program did not assemble");
    }

    /**
     * With MDT installed the debug opcode belongs to MDT's parser, so the statement handed to the
     * compiler is MDT's class, not {@link SugarDebugLines.Line}. Nothing about that class is
     * known here except its written text — which is exactly what strip/emit and the compiler's
     * early return must key on. A stand-in class proves it without depending on MDT.
     */
    private static void foreignDebugStatements(){
        for(String text : samples){
            ForeignDebugStatement foreign = new ForeignDebugStatement(text);
            check(SugarDebugLines.isDebugStatement(foreign),
                "a foreign statement with debug text must be recognised: " + text);
            StringBuilder written = new StringBuilder();
            check(SugarDebugLines.writeDebugStatement(foreign, written) && written.toString().equals(text),
                "a foreign debug statement must write through its own write(): " + written);

            check(lowerOne(foreign, SugarCompiler.AssertEmit.strip).isEmpty(),
                "strip must drop a foreign debug statement: " + text);
            check(lowerOne(foreign, SugarCompiler.AssertEmit.emit).equals(text + "\n"),
                "emit must keep a foreign debug statement verbatim: " + text);
        }

        // A normal foreign statement is not claimed (it keeps the ordinary write path).
        ForeignDebugStatement ordinary = new ForeignDebugStatement("set x 1");
        check(!SugarDebugLines.isDebugStatement(ordinary), "a plain foreign statement is not a debug line");
        check(!SugarDebugLines.writeDebugStatement(ordinary, new StringBuilder()),
            "a plain foreign statement must not be written by the debug helper");
        check(!SugarCompiler.containsSugar(seq(ordinary)),
            "a plain vanilla program must keep the no-lowering early return");

        // containsSugar sees the text, so pure vanilla + one MDT line goes through lowering.
        ForeignDebugStatement line = new ForeignDebugStatement(samples[0]);
        check(SugarCompiler.containsSugar(seq(line)),
            "a foreign debug statement must count as sugar so strip removes it");
        check(SugarCompiler.containsSugar(seq(new SugarDebugLines.Line(new String[]{samples[0]}))),
            "LS's own Line still counts as sugar");
    }

    /**
     * Lowers one statement through the real {@link SugarFunctions#lower} entry point (empty
     * function set), i.e. the branch that decides strip vs emit.
     */
    private static String lowerOne(LStatement statement, SugarCompiler.AssertEmit emit){
        StringBuilder out = new StringBuilder();
        SugarFunctions.lower(seq(statement), "", new SugarFunctions.FunctionSet(null),
            SugarCompiler.FuncMode.normal, out, new SugarFunctions.CallIds(), null,
            SugarCompiler.SwitchStrategy.auto, emit);
        return out.toString();
    }

    private static <T> Seq<T> seq(T item){
        Seq<T> result = new Seq<>();
        result.add(item);
        return result;
    }

    /**
     * The keep policy's safety proof. A program carrying one of LogicSugar's own generated bounds
     * checks recovers as sugar that still contains the line as plain text; recompiling that sugar in
     * emit mode reproduces exactly the normalized input stream. Strip recompilation drops the line
     * again (debug builds are single-player only), and the gate still accepts that shape because it
     * compares both sides with the same generated-line filter.
     */
    private static void generatedBoundsCheckStaysInRecoveredSugar(){
        String generated = "assertBounds integer ~ 0 lessThanEq i lessThanEq 7 "
            + "\"array 'buf' index out of bounds (0..7)\"";
        String input = generated + "\nread x cell1 i\n";

        SugarDecompiler.Result result = SugarDecompiler.decompile(input, true);
        check(result.verified, "a generated-bounds program must still verify: " + result.notes);
        check(result.sugar.contains(generated),
            "the generated line must stay in the recovered sugar:\n" + result.sugar);
        check(result.sugar.contains("read x cell1 i"),
            "the read after the kept line must survive:\n" + result.sugar);

        // Emit recompilation reproduces the input stream with the check before the read. The
        // recompiled sugar is a sugar program again, so its own persistence carrier is ignored
        // exactly like verifyRestore / matchesStoredStream ignore it.
        String recompiled = normalize(SugarCompiler.compileWithoutEntrySkip(result.sugar,
            SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.emit, true));
        check(recompiled.indexOf(generated) >= 0
                && recompiled.indexOf(generated) < recompiled.indexOf("read x cell1 i"),
            "emit recompile must keep the check before the read:\n" + recompiled);
        check(SugarCompiler.matchesStoredStream(recompiled, input),
            "emit recompile of the recovered sugar must reproduce the executable stream"
                + "\n  expected: " + normalize(input).replace("\n", " | ")
                + "\n  actual:   " + recompiled.replace("\n", " | "));

        // Strip recompilation drops it; the same filter on both sides keeps the gate honest.
        String strip = normalize(SugarCompiler.compileWithoutEntrySkip(result.sugar,
            SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip, true));
        check(!strip.contains("assertBounds"), "strip recompile must drop the generated line:\n" + strip);
        check(SugarCompiler.matchesStoredStream(strip, SugarDebugLines.stripGeneratedBoundsLines(input)),
            "the strip shape must still match after the shared filter"
                + "\n  expected: " + SugarDebugLines.stripGeneratedBoundsLines(normalize(input)).replace("\n", " | ")
                + "\n  actual:   " + strip.replace("\n", " | "));

        // A user-authored assertBounds is never treated as generated: only the message signature is.
        String manual = "assertBounds integer ~ 0 lessThanEq i lessThanEq 7 \"my own check\"\nread x cell1 i\n";
        SugarDecompiler.Result kept = SugarDecompiler.decompile(manual, true);
        check(kept.verified, "a user-authored assertBounds program must still verify: " + kept.notes);
        check(kept.sugar.contains("\"my own check\""),
            "a user-authored assertBounds must not be filtered:\n" + kept.sugar);

        // A degenerate program that is nothing but a generated line keeps that line: the keep
        // policy must never collapse it into (or drop it from) an empty program.
        SugarDecompiler.Result lone = SugarDecompiler.decompile(generated + "\n", true);
        check(lone.sugar.trim().length() > 0, "a bounds-only program must not collapse to nothing");
        check(lone.sugar.contains(generated),
            "a bounds-only program must keep its passthrough line:\n" + lone.sugar);
    }

    /** With another mod raising the static to 2000, the saved product still stops at 1000
     *  instructions, and the refusal names 1000. */
    private static void budgetPin(){
        int previous = LExecutor.maxInstructions;
        LExecutor.maxInstructions = 2000;
        try{
            check(InstructionBudget.vanillaLimit() == InstructionBudget.VANILLA_LIMIT,
                "the enforcement limit must stay at the vanilla cap, got " + InstructionBudget.vanillaLimit());

            // Compiled stream = lowered lines + one carrier shard. An array declaration card is
            // pure compile-time metadata (0 instructions) and makes the program "sugar", so the
            // compile-time count check runs. N noops give N + 1 (entry skip) + 1 (carrier).
            InstructionBudget.Snapshot allowed = InstructionBudget.of(
                programWith(InstructionBudget.VANILLA_LIMIT - 2), SugarCompiler.FuncMode.normal,
                SugarFunctions.library(), null);
            check(allowed.instructions == InstructionBudget.VANILLA_LIMIT && !allowed.over(),
                "1000 stored instructions must be allowed, got " + allowed.instructions
                    + " (error: " + allowed.compileError + ")");

            InstructionBudget.Snapshot refused = InstructionBudget.of(
                programWith(InstructionBudget.VANILLA_LIMIT - 1), SugarCompiler.FuncMode.normal,
                SugarFunctions.library(), null);
            check(refused.over(), "1001 stored instructions must be refused");
            check(refused.instructions == InstructionBudget.VANILLA_LIMIT + 1,
                "the refused count should have parsed out of the error, got " + refused.instructions);
            check(refused.compileError != null && refused.compileError.contains("maximum is 1000"),
                "the refusal must state 1000, got: " + refused.compileError);
            check(!refused.compileError.contains("2000"),
                "the refusal must not echo the raised static: " + refused.compileError);
        }finally{
            LExecutor.maxInstructions = previous;
        }
    }

    /** A sugar source with exactly {@code noops} noop instructions: the array declaration lowers
     *  to nothing but keeps the program on the lowering path. */
    private static String programWith(int noops){
        StringBuilder sugar = new StringBuilder("array buf cell1 0 8\n");
        for(int i = 0; i < noops; i++) sugar.append("noop\n");
        return sugar.toString();
    }

    // ===== helpers =====

    private static Seq<LStatement> parsed(String text){
        return LAssembler.read(text, true);
    }

    private static String write(LStatement statement){
        StringBuilder out = new StringBuilder();
        statement.write(out);
        return out.toString();
    }

    /** {@code LAssembler.write(LAssembler.read(text))} with the trailing newline removed. */
    private static String roundTrip(String text){
        return LAssembler.write(parsed(text)).trim();
    }

    private static String normalize(String code){
        return LAssembler.write(parsed(code));
    }

    private static String stripCompile(String sugar, SugarCompiler.AssertEmit emit){
        return SugarCompiler.stripMarkers(SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal,
            SugarFunctions.library(), null, SugarCompiler.SwitchStrategy.auto, emit));
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
