package logicsugar.assist;

import arc.struct.Seq;
import mindustry.logic.LAssembler;
import mindustry.logic.LStatement;
import mindustry.logic.LStatements.JumpStatement;
import mindustry.logic.SugarStatements;
import mindustry.logic.SugarStatements.BeginStatement;

import java.util.HashMap;
import java.util.Map;

/**
 * Pins the fragment format behind the cross-processor clipboard. Everything {@link
 * StatementClipboard} touches is a statement or a string, so the whole path runs without a
 * canvas — which is the point of keeping the canvas out of that class.
 */
public class StatementClipboardSelfTest{
    public static void main(String[] args){
        SugarStatements.installParsers();

        payloadRoundTripsEveryStatement();
        headerMarksThePayload();
        headerWithoutBodyIsNotAPayload();
        crlfTextIsAccepted();
        plainMlogIsAccepted();
        unreadableTextIsRefused();
        acceptableAcceptsBothDirections();
        rebaseMapsJumpIntoFragment();
        rebaseReportsEscapingJump();
        unlinkedJumpIsNeitherMappedNorEscaped();
        pairBlockEndsRepairsFragmentIndices();
        pairBlockEndsRefusesUnpairedFragment();
        countEscapingJumpsFindsOutOfRangeTarget();

        System.out.println("LogicSugar StatementClipboard self-test passed.");
    }

    private static void payloadRoundTripsEveryStatement(){
        Seq<LStatement> fragment = read(
            "set x 1\nifbegin x lessThan 5 4\nprint \"hi\"\nblockend\njump 2 always x false");

        String payload = StatementClipboard.write(fragment);
        Seq<LStatement> back = StatementClipboard.parse(payload);

        check(back != null, "a payload written by write() must parse back");
        check(back.size == fragment.size, "statement count changed: " + back.size + " != " + fragment.size);
        check(StatementClipboard.write(back).equals(payload),
            "payload is not stable across a round trip:\n" + payload + " ->\n" + StatementClipboard.write(back));
    }

    private static void headerMarksThePayload(){
        String payload = StatementClipboard.write(read("set x 1"));

        check(StatementClipboard.isPayload(payload), "a written payload must be recognised");
        check(payload.startsWith("# @ls-fragment 1\n"), "unexpected header: " + payload);
        check(!StatementClipboard.isPayload("set x 1"), "plain mlog must not be taken as a payload");
        check(!StatementClipboard.isPayload(null), "null must not be taken as a payload");
    }

    private static void headerWithoutBodyIsNotAPayload(){
        // A header with nothing after it must be refused outright: the lenient substring used to
        // hand the header line itself to the parser, which would then read it as a statement.
        check(StatementClipboard.parse(StatementClipboard.headerPrefix) == null,
            "a header with no newline must not parse");
        check(StatementClipboard.parse(StatementClipboard.headerPrefix + "0\n") == null,
            "an empty payload must not parse");
    }

    private static void crlfTextIsAccepted(){
        // The parser treats \r as an ordinary token character, so a payload that travelled
        // through something that re-encoded it must still read as the same program.
        Seq<LStatement> fragment = read("set x 1\nprint \"a\"");
        String payload = StatementClipboard.write(fragment);
        String crlf = payload.replace("\n", "\r\n");

        Seq<LStatement> back = StatementClipboard.parse(crlf);
        check(back != null, "CRLF text must parse");
        check(back.size == 2, "CRLF changed the statement count: " + back.size);
        check(StatementClipboard.write(back).equals(payload), "CRLF changed the payload");
    }

    private static void plainMlogIsAccepted(){
        // One format for both directions: a program holding no Sugar card is written out as the
        // mlog it already was, so pasting hand-written mlog keeps working.
        Seq<LStatement> parsed = StatementClipboard.parse("set x 1\njump 0 always x false");

        check(parsed != null, "plain mlog must parse");
        check(parsed.size == 2, "plain mlog lost statements: " + parsed.size);
        check(!StatementClipboard.hasUnknownStatements(parsed), "plain mlog produced invalid statements");
    }

    private static void unreadableTextIsRefused(){
        check(StatementClipboard.parse(null) == null, "null must not parse");
        check(StatementClipboard.parse("") == null, "empty text must not parse");
        check(StatementClipboard.parse("   \n  ") == null, "whitespace must not parse");

        // Either it cannot be read at all, or it reads as statements nobody recognises. Both are
        // refused by the caller, and that is the whole contract.
        Seq<LStatement> junk = StatementClipboard.parse("hello world this is prose");
        check(junk == null || StatementClipboard.hasUnknownStatements(junk),
            "unrecognised text must not look like a clean program");
    }

    private static void acceptableAcceptsBothDirections(){
        // our own payload: trusted verbatim, even holding a card the source canvas itself
        // failed to parse
        String payload = StatementClipboard.write(read("set x 1\n"));
        check(StatementClipboard.isAcceptable(payload), "a payload must be acceptable");

        // hand-written mlog is valid sugar source, so it is acceptable too
        check(StatementClipboard.isAcceptable("set x 1\njump 0 always x false"),
            "plain mlog must be acceptable");

        // prose, an empty payload and nothing at all are not
        check(!StatementClipboard.isAcceptable("hello world this is prose"),
            "prose must not be acceptable");
        check(!StatementClipboard.isAcceptable(StatementClipboard.headerPrefix + "0\n"),
            "an empty payload must not be acceptable");
        check(!StatementClipboard.isAcceptable(null), "null must not be acceptable");
        check(!StatementClipboard.isAcceptable("   \n "), "whitespace must not be acceptable");
    }

    private static void rebaseMapsJumpIntoFragment(){
        // jump targets absolute line 4 in the source; it lands at index 2 of the fragment
        Seq<LStatement> fragment = read("set a 1\njump 4 equal x 1\nprint \"t\"");
        Map<Integer, Integer> absoluteToRelative = new HashMap<>();
        absoluteToRelative.put(0, 0);
        absoluteToRelative.put(1, 1);
        absoluteToRelative.put(4, 2);

        int escaped = StatementClipboard.rebase(fragment, absoluteToRelative);

        check(escaped == 0, "a jump inside the selection was reported as escaping");
        check(jumpAt(fragment, 1).destIndex == 2,
            "jump was not rebased: " + jumpAt(fragment, 1).destIndex);
    }

    private static void rebaseReportsEscapingJump(){
        Seq<LStatement> fragment = read("jump 9 equal x 1");

        int escaped = StatementClipboard.rebase(fragment, new HashMap<>());

        check(escaped == 1, "a jump leaving the selection was not reported: " + escaped);
        check(jumpAt(fragment, 0).destIndex == -1,
            "an untranslatable jump must be unlinked, not left stale: " + jumpAt(fragment, 0).destIndex);
    }

    private static void unlinkedJumpIsNeitherMappedNorEscaped(){
        JumpStatement unlinked = new JumpStatement();
        unlinked.destIndex = -1;
        Seq<LStatement> fragment = new Seq<>();
        fragment.add(unlinked);

        check(StatementClipboard.rebase(fragment, new HashMap<>()) == 0,
            "an unlinked jump is not an escaping one");
        check(StatementClipboard.countEscapingJumps(fragment) == 0,
            "an unlinked jump must not count as out of range");
    }

    private static void pairBlockEndsRepairsFragmentIndices(){
        // 99 is the index the card carried from another program: irrelevant, and ignored.
        Seq<LStatement> fragment = read("ifbegin x lessThan 5 99\nprint \"a\"\nblockend");

        check(SugarStatements.pairBlockEnds(fragment), "a balanced fragment must pair");
        check(beginAt(fragment, 0).destIndex == 2,
            "begin index was not recomputed from nesting: " + beginAt(fragment, 0).destIndex);
    }

    private static void pairBlockEndsRefusesUnpairedFragment(){
        check(!SugarStatements.pairBlockEnds(read("ifbegin x lessThan 5 1\nprint \"a\"")),
            "a begin without its end must not pair");
        check(!SugarStatements.pairBlockEnds(read("print \"a\"\nblockend")),
            "an end without its begin must not pair");
        check(!SugarStatements.pairBlockEnds(read("blockend\nifbegin x lessThan 5 0")),
            "an end before its begin must not pair");
    }

    private static void countEscapingJumpsFindsOutOfRangeTarget(){
        check(StatementClipboard.countEscapingJumps(read("set a 1\njump 7 equal x 1")) == 1,
            "a target past the end of the fragment was not detected");
        check(StatementClipboard.countEscapingJumps(read("set a 1\njump 0 equal x 1")) == 0,
            "a target inside the fragment was reported as escaping");
        check(StatementClipboard.countEscapingJumps(read("jump 1 equal x 1")) == 1,
            "a target exactly at the end (one past the last statement) was not detected");
    }

    private static Seq<LStatement> read(String source){
        Seq<LStatement> statements = LAssembler.read(source, true);
        check(statements != null && !statements.isEmpty(), "test fixture did not parse: " + source);
        return statements;
    }

    private static JumpStatement jumpAt(Seq<LStatement> statements, int index){
        check(statements.get(index) instanceof JumpStatement, "expected a jump at " + index);
        return (JumpStatement)statements.get(index);
    }

    private static BeginStatement beginAt(Seq<LStatement> statements, int index){
        check(statements.get(index) instanceof BeginStatement, "expected a begin at " + index);
        return (BeginStatement)statements.get(index);
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
