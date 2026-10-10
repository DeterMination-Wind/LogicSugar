package mindustry.logic;

import arc.struct.Seq;
import mindustry.logic.SugarAsserts.AssertionType;

/**
 * Wire-format round trips for the assertion statement set. The token layout must stay
 * byte-compatible with the upstream MlogAssertions mod (cardillan/mlogassertions):
 * Mindcode-generated programs and debug builds saved by either mod must parse here, and
 * our write() output must assemble there.
 */
public class SugarAssertsTest{
    public static void main(String[] args){
        SugarStatements.installParsers();

        wireFormatMatchesMlogAssertions();
        writeParseWriteIsIdempotent();
        handWrittenMlogAssertionsLinesParse();
        emptyFieldsKeepTokenCount();
        parseFailureIsACleanError();
        containsAssertStatementsScansLines();
        stripModeKeepsMlogVanilla();
        emitModeWritesInstructions();
        verifyRestoreAcceptsBothBuildShapes();
        decompileDebugBuildRoundTrip();
        assertTypeRoundTripAndClassification();
        assertConditionCardRoundTrip();
        snapshotCardRoundTrip();
        profileAndRestartCards();
        failuresSetTheStopFlag();
        System.out.println("LogicSugar SugarAsserts self-test passed.");
    }

    private static void wireFormatMatchesMlogAssertions(){
        // token layout transcribed from MlogAssertions' LogicStatements.write() (v0.11.3)
        checkLine("assert equal x false ~",
            compileLine("assert equal x false ~"));
        // the snapshot line carries the v0.11.2 `steps` slot before the message
        checkLine("snapshot isolated @unit 20 ~",
            compileLine("snapshot isolated @unit 20 ~"));
        checkLine("snapshot recording @this 50 \"rec\"",
            compileLine("snapshot recording @this 50 \"rec\""));
        checkLine("assertBounds integer 2 0 lessThanEq index lessThanEq 10 \"msg\"",
            compileLine("assertBounds integer 2 0 lessThanEq index lessThanEq 10 \"msg\""));
        checkLine("assertequals 0 i \"should be 0\"",
            compileLine("assertequals 0 i \"should be 0\""));
        checkLine("assertflush position",
            compileLine("assertflush position"));
        checkLine("assertprints position \"frog\" \"bad output\"",
            compileLine("assertprints position \"frog\" \"bad output\""));
        checkLine("error \"Runtime error at #{@counter}.\" null null null null null null null null null",
            compileLine("error \"Runtime error at #{@counter}.\" null null null null null null null null null"));
        checkLine("log info \"Logging a message at #{@counter}.\" null null null null null null null null null",
            compileLine("log info \"Logging a message at #{@counter}.\" null null null null null null null null null"));
        checkLine("breakpoint always x false",
            compileLine("breakpoint always x false"));
        // v0.11.3's profiler control cards
        checkLine("profile start @this",
            compileLine("profile start @this"));
        checkLine("restart @this",
            compileLine("restart @this"));
    }

    private static void writeParseWriteIsIdempotent(){
        String[] lines = {
            "assert lessThanEq x 10 ~",
            "snapshot connected cell1 \"named\"",
            "snapshot global ~ ~",
            "assertBounds multiple 3 1 lessThan i lessThanEq 9 \"idx\"",
            "assertBounds integer ~ ~ lessThan i lessThanEq ~ ~",
            "assertequals \"str\" v ~",
            "assertflush p1",
            "assertprints p1 \"out\" ~",
            "error \"boom [[2]\" @counter x1 null null null null null null null",
            "log err \"logged [[1]\" @counter null null null null null null null null null",
            "breakpoint lessThan x 10",
            "profile clear cell1",
            "restart cell1",
        };
        for(String line : lines){
            String once = compileLine(line);
            String twice = compileLine(once);
            checkLine(once, twice, "write/parse round trip is not idempotent for: " + line);
        }
    }

    private static void handWrittenMlogAssertionsLinesParse(){
        // as written by the upstream mod / Mindcode: defaults straight from its cards
        SugarAsserts.AssertBoundsCard bounds =
            (SugarAsserts.AssertBoundsCard)parseOne("assertBounds integer 2 0 lessThanEq index lessThanEq 10 \"Index out of bounds (0 to 10).\"");
        check(bounds.type == AssertionType.integer, "bounds type not parsed");
        check(bounds.opMin == ConditionOp.lessThanEq && bounds.opMax == ConditionOp.lessThanEq, "bounds ops not parsed");
        check(bounds.message.equals("\"Index out of bounds (0 to 10).\""), "quoted message kept raw, got: " + bounds.message);

        SugarAsserts.LogCard log = (SugarAsserts.LogCard)parseOne("log debug \"[[1] done\" @counter 1 2 null null null null null null");
        check(log.level == arc.util.Log.LogLevel.debug, "log level not parsed");
        check(log.params[3].equals("2"), "log param 3 not parsed: " + log.params[3]);

        SugarAsserts.BreakpointCard bp = (SugarAsserts.BreakpointCard)parseOne("breakpoint equal x 1");
        check(bp.op == ConditionOp.equal, "breakpoint op not parsed");

        // the generic assert card (upstream v0.11.0)
        SugarAsserts.AssertConditionCard assertion =
            (SugarAsserts.AssertConditionCard)parseOne("assert greaterThanEq x 10 \"x >= 10\"");
        check(assertion.op == ConditionOp.greaterThanEq, "assert op not parsed");
        check(assertion.value.equals("x") && assertion.compare.equals("10"), "assert operands not parsed");
        check(assertion.message.equals("\"x >= 10\""), "assert message not parsed");

        // a card default writes an empty message as the ~ placeholder, which the runtime
        // reads back as "no custom message"
        SugarAsserts.AssertEqualsCard empty = (SugarAsserts.AssertEqualsCard)parseOne("assertequals 0 i ~");
        check(empty.message.isEmpty(), "~ did not decode to an empty message: " + empty.message);
    }

    private static void emptyFieldsKeepTokenCount(){
        // "~" placeholders keep every line at its fixed token count, so LParser's reused
        // static token array can never inject stale tokens from another line
        SugarAsserts.AssertBoundsCard card = (SugarAsserts.AssertBoundsCard)parseOne("assertBounds integer ~ ~ lessThan i lessThanEq ~ ~");
        check(card.multiple.isEmpty() && card.min.isEmpty() && card.max.isEmpty(), "~ placeholders not decoded to empty");
        check(card.value.equals("i"), "value field lost");
        String back = writeOne(card);
        check(back.split(" ").length == 9, "assertBounds token count drifted: " + back);
    }

    private static void parseFailureIsACleanError(){
        // malformed enum tokens must fail with IllegalArgumentException (which LParser turns
        // into an InvalidStatement), not with a raw valueOf NPE
        try{
            LAssembler.read("assertBounds nonsense 2 0 lessThanEq i lessThanEq 10 \"m\"", true);
            check(false, "bad assertion type did not fail parsing");
        }catch(RuntimeException e){
            check(e.getMessage() != null && e.getMessage().contains("assertBounds"), "unclean parse error: " + e);
        }
    }

    private static void containsAssertStatementsScansLines(){
        check(SugarAsserts.containsAssertStatements("set x 1\nassertequals 0 y \"m\"\n"), "emit line not detected");
        check(SugarAsserts.containsAssertStatements("breakpoint always x false"), "breakpoint line not detected");
        check(!SugarAsserts.containsAssertStatements("set x 1\nop add y x 1"), "plain program flagged");
        check(!SugarAsserts.containsAssertStatements("assertx nonsense"), "similar opcode prefix flagged");
        check(!SugarAsserts.containsAssertStatements(null), "null flagged");
    }

    // ===== compile behavior =====

    private static void stripModeKeepsMlogVanilla(){
        String sugar = "set x 1\nassertequals 0 x \"x is 0\"\nop add y x 1\n";
        String compiled = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        // the marker comment block echoes the sugar source; the executable mlog is what
        // remains after stripping it (vanilla parses/keeps only instructions + carriers)
        String mlog = SugarCompiler.stripMarkers(compiled);
        check(!mlog.contains("assertequals"), "strip mode leaked the assert instruction into mlog");
        check(mlog.contains("op add y x 1"), "strip mode dropped regular instructions");
        check(SugarCompiler.isSugarProgram(compiled), "carrier missing after strip compile");
        String restored = SugarCompiler.restore(compiled);
        check(restored.contains("assertequals 0 x"), "carrier lost the assert statement");
    }

    private static void emitModeWritesInstructions(){
        String sugar = "set x 1\nassertequals 0 x \"x is 0\"\n";
        String compiled = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.emit);
        String mlog = SugarCompiler.stripMarkers(compiled);
        check(mlog.contains("assertequals 0 x \"x is 0\""), "emit mode did not write the assert instruction");
        check(mlog.contains("set x 1"), "emit mode dropped regular instructions");
    }

    private static void verifyRestoreAcceptsBothBuildShapes(){
        String sugar = "set x 1\nassertequals 0 x \"x is 0\"\n";
        String debug = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.emit);
        check(SugarCompiler.verifyRestore(debug, sugar), "debug build failed carrier verification");
        String stripped = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        check(SugarCompiler.verifyRestore(stripped, sugar), "strip build failed carrier verification");
        // a program WITHOUT assertions keeps the single-shape verification (no emit needed)
        String plain = "set x 1\nop add y x 1\n";
        check(SugarCompiler.verifyRestore(SugarCompiler.compile(plain), plain), "plain build verification broke");
    }

    private static void decompileDebugBuildRoundTrip(){
        String sugar = "set x 1\nassertequals 0 x \"x is 0\"\nop add y x 1\n";
        String debug = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.emit);
        // strip markers + carriers to force the recovery path: the naked instruction stream
        // is what the decompiler must reconstruct (and re-verify) assertions included
        SugarDecompiler.Result result = SugarDecompiler.decompile(stripGenerated(debug));
        check(result.verified, "debug build did not survive the recovery gate: " + result.notes);
        check(result.sugar.contains("assertequals 0 x"), "assert card lost in recovery: " + result.sugar);
        check(result.sugar.contains("op add y x 1"), "regular statements lost in recovery: " + result.sugar);
    }

    /** asserttype covers the upstream v0.11 taxonomy (plus LogicSugar's null type): the wire
     *  format, the tolerant read of the pre-v0.10 token order and the value classification
     *  are pinned here. */
    private static void assertTypeRoundTripAndClassification(){
        // current upstream order: <type> <value> <message>
        SugarAsserts.AssertTypeCard card =
            (SugarAsserts.AssertTypeCard)parseOne("asserttype unit @unit \"should be a unit\"");
        check(card.value.equals("@unit") && card.type == SugarAsserts.AssertionDataType.unit,
            "asserttype fields not parsed");
        checkLine("asserttype unit @unit \"should be a unit\"", writeOne(card));

        // none is spelled "null" on the wire (reserved word in Java)
        SugarAsserts.AssertTypeCard noneCard = (SugarAsserts.AssertTypeCard)parseOne("asserttype null x ~");
        check(noneCard.type == SugarAsserts.AssertionDataType.none, "wire token 'null' not parsed as none");
        checkLine("asserttype null x ~", writeOne(noneCard));

        // pre-v0.10 order (<value> <type>) is still read, and re-written in the current order
        SugarAsserts.AssertTypeCard legacy = (SugarAsserts.AssertTypeCard)parseOne("asserttype @unit unit ~");
        check(legacy.value.equals("@unit") && legacy.type == SugarAsserts.AssertionDataType.unit,
            "legacy asserttype order not accepted: " + legacy.value + "/" + legacy.type);
        checkLine("asserttype unit @unit ~", writeOne(legacy));

        // unknown tokens stay a clean error (LParser turns it into InvalidStatement)
        try{
            parseOne("asserttype bogus x ~");
            check(false, "unknown asserttype data type did not fail parsing");
        }catch(RuntimeException e){
            check(e.getMessage() != null && e.getMessage().contains("asserttype"), "unclean parse error: " + e);
        }

        // runtime classification (the taxonomy the failure message shows)
        check(SugarAsserts.AssertionDataType.number.matches(num("n", 1.5)), "number not matched");
        check(!SugarAsserts.AssertionDataType.number.matches(objectVar("s", "frog")), "string matched as number");
        check(SugarAsserts.AssertionDataType.none.matches(objectVar("n", null)), "null not matched");
        check(SugarAsserts.AssertionDataType.string.matches(objectVar("s", "frog")), "string not matched");
        check(SugarAsserts.AssertionDataType.team.matches(objectVar("t", mindustry.game.Team.derelict)), "team not matched");
        check(SugarAsserts.AssertionDataType.actualType(num("n", 1.5)).equals("number"), "actual type of a number");
        check(SugarAsserts.AssertionDataType.actualType(objectVar("n", null)).equals("null"), "actual type of null");
        check(SugarAsserts.AssertionDataType.actualType(objectVar("s", "frog")).equals("string"), "actual type of a string");
        check(SugarAsserts.AssertionDataType.actualType(objectVar("t", mindustry.game.Team.derelict)).equals("team"), "actual type of a team");
        check(SugarAsserts.AssertionDataType.actualType(objectVar("e", ConditionOp.equal)).equals("unknown"),
            "actual type of an unclassified enum must be 'unknown': " + SugarAsserts.AssertionDataType.actualType(objectVar("e", ConditionOp.equal)));
        check(SugarAsserts.AssertionDataType.actualType(objectVar("o", new Object())).equals("unknown"), "actual type of an unknown object");
    }

    /** The {@code snapshot} card: the v0.11.2 five-slot wire format, the tolerant read of
     *  the legacy four-slot text, emit lowering and the carrier round trip. Creating a
     *  snapshot is client-side only, so strip mode leaves the saved program untouched. */
    private static void snapshotCardRoundTrip(){
        String sugar = "set x 1\nsnapshot connected cell1 \"named\"\n";
        String emitted = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.emit);
        String mlog = SugarCompiler.stripMarkers(emitted);
        // legacy sugar text (no steps slot) is normalized to the current five-slot form
        check(mlog.contains("snapshot connected cell1 20 \"named\""), "emit mode did not write the snapshot instruction");
        check(SugarCompiler.verifyRestore(emitted, sugar), "snapshot debug build failed carrier verification");

        String stripped = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        check(!SugarCompiler.stripMarkers(stripped).contains("snapshot "), "strip mode leaked the snapshot instruction");
        check(SugarCompiler.restore(stripped).contains("snapshot connected cell1"), "carrier lost the snapshot statement");

        // the opcode belongs to the assert set, otherwise verifyRestore skips the emit shape
        // comparison for snapshot-only programs
        check(SugarAsserts.containsAssertStatements(sugar), "snapshot opcode not recognized as an assertion");
        // a global snapshot has no target block; "~" keeps the token count fixed
        checkLine("snapshot global ~ 20 ~", compileLine("snapshot global ~ ~"));

        // 1) legacy text (upstream <=v0.11.1 / LogicSugar <=5.7.2): three payload tokens, the
        //    last one is the message, so the steps slot takes the card default
        SugarAsserts.SnapshotCard legacy =
            (SugarAsserts.SnapshotCard)parseOne("snapshot isolated @unit \"old name\"");
        check(legacy.type == logicsugar.vars.SnapshotType.isolated, "legacy snapshot type not parsed");
        check(legacy.message.equals("\"old name\""), "legacy snapshot message not parsed: " + legacy.message);
        check(legacy.steps.equals("20"), "legacy snapshot should take the default steps: " + legacy.steps);
        checkLine("snapshot isolated @unit 20 \"old name\"", writeOne(legacy));

        // a legacy line whose message is the empty placeholder keeps the token count and the
        // default name handling
        SugarAsserts.SnapshotCard emptyMessage = (SugarAsserts.SnapshotCard)parseOne("snapshot connected cell1 ~");
        check(emptyMessage.message.isEmpty(), "~ did not decode to an empty message");
        checkLine("snapshot connected cell1 20 ~", writeOne(emptyMessage));

        // 2) current text: type block steps message in either direction
        SugarAsserts.SnapshotCard current =
            (SugarAsserts.SnapshotCard)parseOne("snapshot connected cell1 5 \"named\"");
        check(current.steps.equals("5"), "steps not parsed: " + current.steps);
        check(current.message.equals("\"named\""), "message not parsed: " + current.message);
        checkLine("snapshot connected cell1 5 \"named\"", writeOne(current));

        // a variable steps slot survives (the shape is chosen by the slot's writing, not by
        // its content), and a cleared field is normalized back to the default on write
        SugarAsserts.SnapshotCard variableStep =
            (SugarAsserts.SnapshotCard)parseOne("snapshot recording @this n \"variable\"");
        check(variableStep.steps.equals("n"), "variable steps not parsed: " + variableStep.steps);
        checkLine("snapshot recording @this n \"variable\"", writeOne(variableStep));
        variableStep.steps = "";
        checkLine("snapshot recording @this 20 \"variable\"", writeOne(variableStep));

        // 3) recording snapshot: type token, recording layout and emit lowering
        check(logicsugar.vars.SnapshotType.recording.name().equals("recording"), "wire token drifted");
        check(logicsugar.vars.SnapshotType.all.length == 4, "recording missing from the type list");
        checkLine("snapshot recording @this 8 ~", compileLine("snapshot recording @this 8 ~"));
        String recSugar = "set x 1\nsnapshot recording @this 8 \"rec\"\n";
        String recEmitted = SugarCompiler.compile(recSugar, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.emit);
        check(SugarCompiler.stripMarkers(recEmitted).contains("snapshot recording @this 8 \"rec\""),
            "recording instruction not emitted");
        check(SugarCompiler.verifyRestore(recEmitted, recSugar), "recording debug build failed carrier verification");
        check(SugarCompiler.restore(SugarCompiler.compile(recSugar, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip)).contains("snapshot recording @this 8"),
            "carrier lost the recording snapshot");

        // an unknown type token stays a located error (LParser turns it into InvalidStatement)
        try{
            parseOne("snapshot bogus @this 5 ~");
            check(false, "unknown snapshot type did not fail parsing");
        }catch(RuntimeException e){
            check(e.getMessage() != null && e.getMessage().contains("snapshot"), "unclean parse error: " + e);
        }
    }

    /** The generic {@code assert} card: emit-mode lowering plus the carrier round trip. */
    private static void assertConditionCardRoundTrip(){
        String sugar = "set x 1\nassert lessThanEq x 10 ~\n";
        String emitted = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.emit);
        String mlog = SugarCompiler.stripMarkers(emitted);
        check(mlog.contains("assert lessThanEq x 10 ~"), "emit mode did not write the assert instruction");
        check(SugarCompiler.verifyRestore(emitted, sugar), "assert debug build failed carrier verification");

        String stripped = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        check(!SugarCompiler.stripMarkers(stripped).contains("assert lessThanEq"), "strip mode leaked the assert instruction");
        check(SugarCompiler.restore(stripped).contains("assert lessThanEq x 10"), "carrier lost the assert statement");

        // the opcode must be part of the assert set, otherwise verifyRestore skips the emit
        // shape comparison for assert-only programs
        check(SugarAsserts.containsAssertStatements(sugar), "assert opcode not recognized as an assertion");
    }

    /** The {@code profile} / {@code restart} cards (upstream v0.11.3): wire format, emit
     *  lowering, carrier round trip and the located error for an unknown command. */
    private static void profileAndRestartCards(){
        SugarAsserts.ProfileCard profile =
            (SugarAsserts.ProfileCard)parseOne("profile stop processor1");
        check(profile.command == logicsugar.profile.ProfilingCommand.stop, "profile command not parsed");
        check(profile.block.equals("processor1"), "profile target not parsed: " + profile.block);
        checkLine("profile stop processor1", writeOne(profile));

        SugarAsserts.ProfileCard clear = (SugarAsserts.ProfileCard)parseOne("profile clear @this");
        check(clear.command == logicsugar.profile.ProfilingCommand.clear, "profile clear not parsed");

        SugarAsserts.RestartCard restart = (SugarAsserts.RestartCard)parseOne("restart processor1");
        check(restart.block.equals("processor1"), "restart target not parsed: " + restart.block);
        checkLine("restart processor1", writeOne(restart));

        // emit / carrier round trips go through the same assert-set machinery
        String sugar = "set x 1\nprofile start @this\nrestart cell1\n";
        check(SugarAsserts.containsAssertStatements(sugar), "profile/restart opcodes not recognized as assertions");
        String emitted = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.emit);
        String mlog = SugarCompiler.stripMarkers(emitted);
        check(mlog.contains("profile start @this"), "emit mode did not write the profile instruction");
        check(mlog.contains("restart cell1"), "emit mode did not write the restart instruction");
        check(SugarCompiler.verifyRestore(emitted, sugar), "profiler debug build failed carrier verification");

        String stripped = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        check(!SugarCompiler.stripMarkers(stripped).contains("profile "), "strip mode leaked the profile instruction");
        check(!SugarCompiler.stripMarkers(stripped).contains("restart "), "strip mode leaked the restart instruction");
        String restored = SugarCompiler.restore(stripped);
        check(restored.contains("profile start @this") && restored.contains("restart cell1"),
            "carrier lost the profiler cards:\n" + restored);

        // an unknown command stays a located error (LParser turns it into InvalidStatement)
        try{
            parseOne("profile bounce @this");
            check(false, "unknown profile command did not fail parsing");
        }catch(RuntimeException e){
            check(e.getMessage() != null && e.getMessage().contains("profile"), "unclean parse error: " + e);
        }
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

    /** Removes the marker block and carrier lines so the decompiler works from the naked
     *  instruction stream (same helper as SugarDecompilerTest). */
    private static String stripGenerated(String code){
        StringBuilder out = new StringBuilder();
        boolean marker = false;
        for(String line : code.replace("\r\n", "\n").split("\n", -1)){
            if(SugarCompiler.isMarkerBeginLine(line)){ marker = true; continue; }
            if(SugarCompiler.isMarkerEndLine(line)){ marker = false; continue; }
            if(marker || line.startsWith("set __ls_sugar \"") || line.startsWith("set __ls_lib \"")) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    // ===== helpers =====

    /** Compiles a single line through the same parser the compiler uses and re-emits it. */
    private static String compileLine(String line){
        return writeOne(parseOne(line));
    }

    private static LStatement parseOne(String line){
        Seq<LStatement> statements = LAssembler.read(line, true);
        check(statements.size == 1, "line did not parse into exactly one statement: " + line);
        return statements.get(0);
    }

    private static String writeOne(LStatement statement){
        StringBuilder out = new StringBuilder();
        statement.write(out);
        return out.toString();
    }

    /**
     * 上游 v0.11.4：失败断言与 {@code error} 指令把处理器真正置为停机（{@code exec.stop}），
     * 不再是纯自旋——profiler 因此停下统计（{@code Instrumentation} 的包装器读到 stop 就停），
     * 世界处理器的 {@code LogicScript} 也因此收工。计数器仍然回退，所以普通处理器每帧重跑失败
     * 指令、消息持续刷新。
     *
     * <p>为什么是源码钉子而不是真跑：两个位置都先调 {@code ProcessorStatus.setMessage(exec.build, …)}，
     * 而 {@code LExecutor.build} 在无头环境里只能是 null——arc 的 {@code ObjectMap.get(null)}
     * 直接抛 {@code IllegalArgumentException}("key cannot be null.")（已实测），而造一块真的
     * {@code LogicBuild} 需要活的
     * Tile/World。因此这里只钉住代码形状：两个位置都有停机标志，且断言路径把它写在非断点分支里。
     * 运行时一致性由 {@code profilerTest} 的包装器用例与手测清单覆盖。</p>
     */
    private static void failuresSetTheStopFlag(){
        String source = readSource("src/logicsugar/assist/AssertInstructions.java");
        String errorRun = logicsugar.SourceNails.blockFrom(source,
            "public final void run(LExecutor exec){\n            ProcessorStatus.setMessage(");
        String assertion = logicsugar.SourceNails.methodBody(source,
            "private static void assertion(LExecutor exec, String defaultKey, Object message, Object... values){");

        check(errorRun.contains("exec.yield = true;") && errorRun.contains("exec.stop = true;"),
            "error 指令必须同时让出并置停机标志（上游 v0.11.4）");
        check(errorRun.indexOf("exec.counter.numval--") < errorRun.indexOf("exec.stop = true;"),
            "error 指令先回退计数器再停机（消息要能随每帧重跑刷新）");

        int stopAt = assertion.indexOf("exec.stop = true;");
        check(stopAt >= 0, "断言失败路径必须置 exec.stop");
        check(stopAt == assertion.lastIndexOf("exec.stop = true;"), "断言失败路径只能置一次停机标志");
        int elseAt = assertion.indexOf("}else{");
        check(elseAt >= 0 && elseAt < stopAt, "停机标志必须写在非断点分支里");
        check(!assertion.substring(0, elseAt).contains("exec.stop"), "断点路径不能置停机标志（那是暂停，不是停机）");
    }

    /** 读仓库源文件（SourceNails 的容错包装：读不到直接断言失败，不吞异常）。 */
    private static String readSource(String file){
        try{
            return logicsugar.SourceNails.readSource(file);
        }catch(java.io.IOException e){
            throw new AssertionError("cannot read " + file + ": " + e, e);
        }
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
