package logicsugar.assist;

import arc.func.Func;
import arc.graphics.Color;
import arc.struct.ObjectMap;
import mindustry.logic.LVar;

/**
 * The message placeholder engine of {@link AssertInstructions}, pinned headlessly.
 *
 * <p>Why it needs its own test: the engine is the only part of the assertion port that
 * renders text at runtime, it changed shape in upstream v0.11 (the message slot shifts the
 * {@code {1}} numbering, {@code {name}} resolves variables, whole numbers print without a
 * decimal part), and an off-by-one slot renders a plausible-looking but wrong message that
 * no compile-time test can see. A live {@link mindustry.logic.LExecutor} is not needed — the
 * engine takes the variable lookup as a function, so the test supplies its own table.</p>
 *
 * <p>The identity of the message argument matters: the engine decides whether to shift the
 * placeholder numbering by comparing the message with {@code arguments[0]}. Every test case
 * therefore passes the very same LVar instance it uses as the message.</p>
 */
public class AssertMessageTest{
    public static void main(String[] args){
        numberedPlaceholdersShiftByTheMessageSlot();
        variableNamesResolveAgainstTheLookup();
        legacyDoubleBracketPlaceholdersStillRender();
        unresolvablePlaceholdersAreLeftAsTyped();
        unusedParametersAreAppendedOnlyWhenAsked();
        valuesArePrintedLikeTheGameDoes();
        emptyOrNullMessagesAskForTheDefaultText();
        System.out.println("LogicSugar assert message self-test passed.");
    }

    /** {@code {1}} is the first argument after the message when the message is arguments[0]
     *  (the upstream v0.11 rule the cards document). */
    private static void numberedPlaceholdersShiftByTheMessageSlot(){
        checkLine("expected 5, got 6", withMessage("expected {1}, got {2}", num("expected", 5), num("actual", 6)),
            "numbered placeholders must skip the message slot");

        // no message in the argument list: {1} is the first argument
        checkLine("first 5", format(str("m", "first {1}"), new Object[]{num("a", 5)}),
            "placeholders without a message slot must be 1-based on the arguments");

        // an out-of-range slot stays as typed and is not an error
        checkLine("kept {9}", withMessage("kept {9}", num("a", 1)),
            "an out-of-range placeholder must stay as typed");
    }

    private static void variableNamesResolveAgainstTheLookup(){
        ObjectMap<String, LVar> vars = new ObjectMap<>();
        vars.put("x", num("x", 42.5));
        vars.put("s", str("s", "frog"));
        vars.put("@counter", num("@counter", 12));

        checkLine("x = 42.5", withLookup(vars, "", false, str("m", "x = {x}"), new Object[0]),
            "a variable placeholder must print the live value");
        checkLine("s = frog", withLookup(vars, "", false, str("m", "s = {s}"), new Object[0]),
            "variable placeholders must not quote strings");
        // the counter points at the instruction being retried, so @counter renders one less
        checkLine("at 11", withLookup(vars, "", false, str("m", "at {@counter}"), new Object[0]),
            "@counter must render the failing instruction index");
        checkLine("at {nope}", withLookup(vars, "", false, str("m", "at {nope}"), new Object[0]),
            "an unknown variable placeholder must stay as typed");
    }

    /** LogicSugar ≤5.5 / upstream ≤v0.10 wrote {@code [[1]}; those saved messages must keep
     *  rendering with the parameters substituted. */
    private static void legacyDoubleBracketPlaceholdersStillRender(){
        checkLine("boom 2", withMessage("boom [[2]", num("p1", 1), num("p2", 2)),
            "legacy [[N] placeholders must substitute from the parameter slots");

        // in the error/log shape the message is the template and p1 starts at index 1
        LVar template = str("t", "log [[1]");
        Object[] params = {template, str("p1", "text"), num("p2", 2)};
        checkLine("log \"text\"", format(template, params),
            "legacy placeholders must use the parameter index directly");
    }

    private static void unresolvablePlaceholdersAreLeftAsTyped(){
        checkLine("{1} {2}", withMessage("{1} {2}"),
            "placeholders without a matching argument must stay as typed");
        checkLine("plain text", withMessage("plain text"),
            "a message without placeholders must be unchanged");
    }

    private static void unusedParametersAreAppendedOnlyWhenAsked(){
        LVar message = str("m", "custom");
        Object[] args = {message, num("a", 1), str("s", "text"), str("null", "ignored")};

        checkLine("custom 1 \"text\"", format(true, message, args),
            "unused non-null parameters must be appended, strings quoted");
        checkLine("custom", format(false, message, args),
            "parameters must not be appended when the caller did not ask for it");

        checkLine("p1=1 2", withMessageAppendUnused("p1={1}", num("a", 1), num("b", 2)),
            "an already-used parameter must not be appended again");
    }

    private static void valuesArePrintedLikeTheGameDoes(){
        checkLine("5", print(num("n", 5)), "a whole number must print without a decimal part");
        checkLine("1.5", print(num("n", 1.5)), "a fractional number must keep its fraction");
        checkLine("NaN", print(num("n", Double.NaN)), "NaN must stay printable");
        checkLine("%ffffffff", print(num("n", Color.white.toDoubleBits())),
            "a value in the color range must print as a color literal");
        checkLine("frog", print(str("s", "frog")), "an object must print through the logic printer");
        checkLine("unit", print("unit"), "a plain value must print as its string form");
    }

    private static void emptyOrNullMessagesAskForTheDefaultText(){
        check(AssertInstructions.isCustomMessage(str("m", "text")), "a non-empty string is a custom message");
        check(!AssertInstructions.isCustomMessage(str("m", "")), "an empty string is not a custom message");
        check(!AssertInstructions.isCustomMessage(nullVar("m")), "the null value is not a custom message");
        check(!AssertInstructions.isCustomMessage(num("m", 5)), "a number is not a custom message");
        check(AssertInstructions.isCustomMessage("text"), "a plain string is a custom message");
        check(!AssertInstructions.isCustomMessage(""), "an empty plain string is not a custom message");

        // default branch: the caller gets the localized text before the values (the raw key
        // stands in when no bundle is loaded, which is the case in this headless run)
        LVar message = nullVar("m");
        Object[] values = {message, num("expected", 5), num("actual", 6)};
        String defaultText = AssertInstructions.assertionText(name -> null, "logicsugar.asserts.equalFailedWithValues", message, values);
        check(defaultText.contains("logicsugar.asserts.equalFailedWithValues"),
            "the default branch must ask for the localized text, not render the message slot: " + defaultText);

        // custom branch: the message is rendered with the value slots
        LVar custom = str("m", "wanted {1}");
        Object[] customValues = {custom, num("expected", 5), num("actual", 6)};
        checkLine("wanted 5",
            AssertInstructions.assertionText(name -> null, "logicsugar.asserts.equalFailedWithValues", custom, customValues),
            "a custom message must be rendered instead of the default text");
    }

    // ===== helpers =====

    /** A message plus its value arguments, with the message as {@code arguments[0]}
     *  (the shape the assertion instructions use). */
    private static String withMessage(String text, Object... values){
        LVar message = str("m", text);
        Object[] args = new Object[values.length + 1];
        args[0] = message;
        System.arraycopy(values, 0, args, 1, values.length);
        return format(message, args);
    }

    private static String withMessageAppendUnused(String text, Object... values){
        LVar message = str("m", text);
        Object[] args = new Object[values.length + 1];
        args[0] = message;
        System.arraycopy(values, 0, args, 1, values.length);
        return format(true, message, args);
    }

    private static String format(LVar message, Object[] args){
        return AssertInstructions.formatMessage(name -> null, "", false, message, args);
    }

    private static String format(boolean appendUnused, LVar message, Object[] args){
        return AssertInstructions.formatMessage(name -> null, "", appendUnused, message, args);
    }

    private static String withLookup(ObjectMap<String, LVar> vars, String prefix, boolean appendUnused, Object message, Object[] args){
        Func<String, LVar> lookup = vars::get;
        return AssertInstructions.formatMessage(lookup, prefix, appendUnused, message, args);
    }

    private static String print(Object value){
        // the same entry the message engine uses for a single value
        return AssertInstructions.formatMessage(name -> null, "", false, value, new Object[0]);
    }

    private static LVar num(String name, double value){
        LVar var = new LVar(name);
        var.isobj = false;
        var.numval = value;
        return var;
    }

    private static LVar str(String name, String value){
        LVar var = new LVar(name);
        var.isobj = true;
        var.objval = value;
        return var;
    }

    private static LVar nullVar(String name){
        LVar var = new LVar(name);
        var.isobj = true;
        var.objval = null;
        return var;
    }

    private static void checkLine(String expected, String actual, String message){
        check(expected.equals(actual), message + "\n  expected: " + expected + "\n  actual:   " + actual);
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
