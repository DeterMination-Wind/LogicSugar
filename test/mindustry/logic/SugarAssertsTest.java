package mindustry.logic;

import arc.struct.Seq;
import mindustry.logic.SugarAsserts.AssertOp;
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
        System.out.println("LogicSugar SugarAsserts self-test passed.");
    }

    private static void wireFormatMatchesMlogAssertions(){
        // token layout transcribed from MlogAssertions' LogicStatements.write()
        checkLine("assertBounds integer 2 0 lessThanEq index lessThanEq 10 \"msg\"",
            compileLine("assertBounds integer 2 0 lessThanEq index lessThanEq 10 \"msg\""));
        checkLine("assertequals 0 i \"should be 0\"",
            compileLine("assertequals 0 i \"should be 0\""));
        checkLine("assertflush position",
            compileLine("assertflush position"));
        checkLine("assertprints position \"frog\" \"bad output\"",
            compileLine("assertprints position \"frog\" \"bad output\""));
        checkLine("error \"Runtime error at #[[1]\" @counter null null null null null null null null",
            compileLine("error \"Runtime error at #[[1]\" @counter null null null null null null null null"));
        checkLine("log info \"Logging a message at #[[1]\" @counter null null null null null null null null",
            compileLine("log info \"Logging a message at #[[1]\" @counter null null null null null null null null"));
        checkLine("breakpoint always x false",
            compileLine("breakpoint always x false"));
    }

    private static void writeParseWriteIsIdempotent(){
        String[] lines = {
            "assertBounds multiple 3 1 lessThan i lessThanEq 9 \"idx\"",
            "assertBounds integer ~ ~ lessThan i lessThanEq ~ ~",
            "assertequals \"str\" v ~",
            "assertflush p1",
            "assertprints p1 \"out\" ~",
            "error \"boom [[2]\" @counter x1 null null null null null null null",
            "log err \"logged [[1]\" @counter null null null null null null null null",
            "breakpoint lessThan x 10",
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
        check(bounds.opMin == AssertOp.lessThanEq && bounds.opMax == AssertOp.lessThanEq, "bounds ops not parsed");
        check(bounds.message.equals("\"Index out of bounds (0 to 10).\""), "quoted message kept raw, got: " + bounds.message);

        SugarAsserts.LogCard log = (SugarAsserts.LogCard)parseOne("log debug \"[[1] done\" @counter 1 2 null null null null null null");
        check(log.level == arc.util.Log.LogLevel.debug, "log level not parsed");
        check(log.params[3].equals("2"), "log param 3 not parsed: " + log.params[3]);

        SugarAsserts.BreakpointCard bp = (SugarAsserts.BreakpointCard)parseOne("breakpoint equal x 1");
        check(bp.op == ConditionOp.equal, "breakpoint op not parsed");
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
