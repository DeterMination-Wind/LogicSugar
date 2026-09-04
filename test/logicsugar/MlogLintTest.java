package logicsugar;

import logicsugar.assist.MlogLint;
import logicsugar.assist.MlogLint.Severity;
import logicsugar.assist.MlogLint.Warning;

import java.util.Arrays;
import java.util.List;

/**
 * Main-based self-tests for {@link MlogLint}. Statements are built directly as token arrays
 * (no text tokenizer needed); every rule code gets one positive and one negative case, and
 * clean programs must lint to zero warnings. Follows the JavaExec self-test convention of
 * {@code mindustry.logic.SugarDecompilerTest}.
 */
public final class MlogLintTest{
    private MlogLintTest(){}

    public static void main(String[] args){
        cleanProgramHasNoWarnings();
        emptyProgramHasNoWarnings();
        emptyTokenLinesAreSkipped();
        unknownOp();
        badArgCount();
        assignToLiteral();
        selfJump();
        jumpOutOfRange();
        emptyJumpCondition();
        unknownKind();
        quotedTokensAreNotSplit();
        warningsAreOrderedByLine();
        System.out.println("LogicSugar lint self-test passed.");
    }

    private static void cleanProgramHasNoWarnings(){
        List<Warning> warnings = MlogLint.lint(program(
            row("set", "x", "1"),
            row("op", "add", "y", "x", "2"),
            row("sensor", "t", "block1", "@copper"),
            row("jump", "5", "lessThan", "x", "10"),
            row("print", "x"),
            row("end")
        ));
        check(warnings.isEmpty(), "clean program produced warnings: " + warnings);
    }

    private static void emptyProgramHasNoWarnings(){
        check(MlogLint.lint(program()).isEmpty(), "empty program produced warnings");
    }

    private static void emptyTokenLinesAreSkipped(){
        List<Warning> warnings = MlogLint.lint(Arrays.asList(new String[0], row("end"), new String[0]));
        check(warnings.isEmpty(), "empty token lines must be skipped: " + warnings);
    }

    private static void unknownOp(){
        List<Warning> warnings = MlogLint.lint(program(row("op", "foo", "a", "b", "c")));
        check(warnings.size() == 1, "unknown op should produce one warning: " + warnings);
        Warning w = warnings.get(0);
        check(w.severity() == Severity.ERROR, "unknown-op must be ERROR: " + w);
        check(w.code().equals("unknown-op"), "wrong code: " + w.code());
        check(w.line() == 0, "wrong line: " + w.line());

        check(MlogLint.lint(program(row("op", "add", "a", "b", "c"))).isEmpty(), "op add was flagged");
        check(MlogLint.lint(program(row("op", "notEqual", "r", "x", "y"))).isEmpty(), "op notEqual was flagged");
        check(MlogLint.lint(program(row("op", "emod", "r", "x", "y"))).isEmpty(), "op emod was flagged");
        // "b-and" is the display symbol of LogicOp.and, not the enum name vanilla assembles by
        assertSingleError(MlogLint.lint(program(row("op", "b-and", "r", "x", "y"))), "unknown-op");
    }

    private static void badArgCount(){
        assertSingleError(MlogLint.lint(program(row("set", "x"))), "bad-arg-count");
        assertSingleError(MlogLint.lint(program(row("set", "x", "1", "extra"))), "bad-arg-count");
        assertSingleError(MlogLint.lint(program(row("op", "add", "a", "b"))), "bad-arg-count");
        assertSingleError(MlogLint.lint(program(row("end", "extra"))), "bad-arg-count");
        assertSingleError(MlogLint.lint(program(row("sensor", "a", "b"))), "bad-arg-count");
        assertSingleError(MlogLint.lint(program(row("jump"))), "bad-arg-count");
        assertSingleError(MlogLint.lint(program(row("jump", "1", "equal", "x", "y", "extra"),
            row("set", "x", "0"), row("set", "x", "1"))), "bad-arg-count");

        check(MlogLint.lint(program(row("set", "x", "1"))).isEmpty(), "valid set was flagged");
        check(MlogLint.lint(program(row("op", "add", "a", "b", "c"))).isEmpty(), "valid op was flagged");
        check(MlogLint.lint(program(row("end"))).isEmpty(), "valid end was flagged");
        check(MlogLint.lint(program(row("sensor", "t", "block1", "@copper"))).isEmpty(), "valid sensor was flagged");
        // padded to 3 lines so the jump targets stay in range for these shape-only checks
        check(MlogLint.lint(program(row("jump", "2"), row("set", "x", "1"), row("set", "x", "2"))).isEmpty(),
            "bare jump was flagged");
        check(MlogLint.lint(program(row("jump", "2", "equal", "x", "y"), row("set", "x", "1"),
            row("set", "x", "2"))).isEmpty(), "conditional jump was flagged");
    }

    private static void assignToLiteral(){
        List<Warning> warnings = MlogLint.lint(program(row("set", "5", "x")));
        check(warnings.size() == 1, "set 5 x should produce one warning: " + warnings);
        Warning w = warnings.get(0);
        check(w.severity() == Severity.WARNING, "assign-to-literal must be WARNING: " + w);
        check(w.code().equals("assign-to-literal"), "wrong code: " + w.code());
        check(w.line() == 0, "wrong line: " + w.line());

        assertSingleWarning(MlogLint.lint(program(row("op", "add", "3", "b", "c"))), "assign-to-literal");
        assertSingleWarning(MlogLint.lint(program(row("set", "\"a b\"", "1"))), "assign-to-literal");
        assertSingleWarning(MlogLint.lint(program(row("set", "-2.5", "x"))), "assign-to-literal");

        check(MlogLint.lint(program(row("set", "x", "5"))).isEmpty(), "literal source was flagged");
        check(MlogLint.lint(program(row("set", "x", "count"))).isEmpty(), "variable source was flagged");
        check(MlogLint.lint(program(row("op", "add", "dest", "a", "3"))).isEmpty(), "literal op operand was flagged");
    }

    private static void selfJump(){
        List<Warning> warnings = MlogLint.lint(program(
            row("set", "x", "0"),
            row("set", "x", "1"),
            row("set", "x", "2"),
            row("jump", "3", "always", "x", "false"),
            row("end")
        ));
        check(warnings.size() == 1, "self jump should produce one warning: " + warnings);
        Warning w = warnings.get(0);
        check(w.severity() == Severity.WARNING, "self-jump must be WARNING: " + w);
        check(w.code().equals("self-jump"), "wrong code: " + w.code());
        check(w.line() == 3, "wrong line: " + w.line());

        List<Warning> ok = MlogLint.lint(program(
            row("set", "x", "0"),
            row("set", "x", "1"),
            row("set", "x", "2"),
            row("jump", "2", "always", "x", "false"),
            row("end")
        ));
        check(ok.isEmpty(), "forward jump was flagged as self-jump: " + ok);
    }

    private static void jumpOutOfRange(){
        List<Warning> warnings = MlogLint.lint(program(
            row("set", "x", "0"),
            row("set", "x", "1"),
            row("set", "x", "2"),
            row("jump", "99", "always", "x", "false"),
            row("end")
        ));
        check(warnings.size() == 1, "jump 99 should produce one warning: " + warnings);
        Warning w = warnings.get(0);
        check(w.severity() == Severity.WARNING, "jump-out-of-range must be WARNING: " + w);
        check(w.code().equals("jump-out-of-range"), "wrong code: " + w.code());
        check(w.line() == 3, "wrong line: " + w.line());

        assertSingleWarning(MlogLint.lint(program(row("jump", "-1", "always", "x", "false"), row("end"))),
            "jump-out-of-range");
        check(MlogLint.lint(program(row("jump", "1", "always", "x", "false"), row("end"))).isEmpty(),
            "in-range jump was flagged");
        // label targets are not integers and must be skipped, not reported
        check(MlogLint.lint(program(row("jump", "loop", "always", "x", "false"), row("end"))).isEmpty(),
            "label jump was flagged");
    }

    private static void emptyJumpCondition(){
        List<Warning> warnings = MlogLint.lint(program(
            row("jump", "2", "always"),
            row("set", "x", "1"),
            row("set", "x", "2")
        ));
        check(warnings.size() == 1, "truncated jump should produce one warning: " + warnings);
        Warning w = warnings.get(0);
        check(w.severity() == Severity.ERROR, "empty-jump-condition must be ERROR: " + w);
        check(w.code().equals("empty-jump-condition"), "wrong code: " + w.code());
        check(w.line() == 0, "wrong line: " + w.line());

        List<Warning> four = MlogLint.lint(program(
            row("jump", "2", "equal", "x"),
            row("set", "x", "1"),
            row("set", "x", "2")
        ));
        check(four.size() == 1 && four.get(0).code().equals("empty-jump-condition"),
            "4-token jump must report empty-jump-condition exactly once: " + four);

        check(MlogLint.lint(program(row("jump", "2"), row("set", "x", "1"), row("set", "x", "2"))).isEmpty(),
            "bare jump was flagged");
        check(MlogLint.lint(program(row("jump", "2", "equal", "x", "y"), row("set", "x", "1"),
            row("set", "x", "2"))).isEmpty(), "full conditional jump was flagged");
    }

    private static void unknownKind(){
        List<Warning> warnings = MlogLint.lint(program(row("frobnicate", "a", "b")));
        check(warnings.size() == 1, "unknown kind should produce one warning: " + warnings);
        Warning w = warnings.get(0);
        check(w.severity() == Severity.INFO, "unknown-kind must be INFO: " + w);
        check(w.code().equals("unknown-kind"), "wrong code: " + w.code());
        check(w.line() == 0, "wrong line: " + w.line());

        check(MlogLint.lint(program(row("wait", "0.5"))).isEmpty(), "known kind wait was flagged");
        check(MlogLint.lint(program(row("noop"))).isEmpty(), "known kind noop was flagged");
        check(MlogLint.lint(program(row("ulocate", "building", "core", "true", "@copper",
            "outx", "outy", "found", "building"))).isEmpty(), "known kind ulocate was flagged");
    }

    private static void quotedTokensAreNotSplit(){
        List<Warning> warnings = MlogLint.lint(program(
            row("set", "msg", "\"a b\""),
            row("print", "\"hello world\""),
            row("op", "equal", "r", "\"a b\"", "c")
        ));
        check(warnings.isEmpty(), "quoted strings must not be split or flagged: " + warnings);
    }

    private static void warningsAreOrderedByLine(){
        List<Warning> warnings = MlogLint.lint(program(
            row("op", "foo", "a", "b", "c"),            // line 0: unknown-op
            row("set", "5", "x"),                       // line 1: assign-to-literal
            row("jump", "99", "always", "x", "false"),  // line 2: jump-out-of-range
            row("frobnicate")                           // line 3: unknown-kind
        ));
        check(warnings.size() == 4, "expected one warning per broken line, got: " + warnings);
        for(int i = 0; i < warnings.size(); i++){
            check(warnings.get(i).line() == i, "warnings must be in line order at index " + i
                + ": " + warnings);
        }
        check(warnings.get(0).code().equals("unknown-op"), "wrong code at 0: " + warnings.get(0));
        check(warnings.get(1).code().equals("assign-to-literal"), "wrong code at 1: " + warnings.get(1));
        check(warnings.get(2).code().equals("jump-out-of-range"), "wrong code at 2: " + warnings.get(2));
        check(warnings.get(3).code().equals("unknown-kind"), "wrong code at 3: " + warnings.get(3));
    }

    private static List<String[]> program(String[]... lines){
        return Arrays.asList(lines);
    }

    private static String[] row(String... tokens){
        return tokens;
    }

    private static void assertSingleWarning(List<Warning> warnings, String code){
        check(warnings.size() == 1 && warnings.get(0).code().equals(code),
            "expected exactly one " + code + " warning, got: " + warnings);
    }

    private static void assertSingleError(List<Warning> warnings, String code){
        assertSingleWarning(warnings, code);
        check(warnings.get(0).severity() == Severity.ERROR,
            code + " must be ERROR: " + warnings.get(0));
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
