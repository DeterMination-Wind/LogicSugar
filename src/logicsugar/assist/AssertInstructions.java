package logicsugar.assist;

import arc.Core;
import arc.func.Func;
import arc.graphics.Color;
import arc.util.Log;
import mindustry.Vars;
import mindustry.logic.ConditionOp;
import mindustry.logic.LExecutor;
import mindustry.logic.LVar;
import mindustry.logic.SugarAsserts.AssertionDataType;
import mindustry.logic.SugarAsserts.AssertionType;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;

/**
 * Runtime instruction classes for LogicSugar's assertion statement set, ported from the
 * upstream MlogAssertions mod (cardillan/mlogassertions, currently v0.11.1, wire-format
 * compatible). Failure reporting goes through {@link ProcessorStatus}, which draws the
 * message above the processor and keeps the program looping on the failing instruction
 * (counter rewind + yield) until the condition passes or the processor is reconfigured. With
 * the {@code assertsAreBreakpoints} setting, a failed assertion pauses the game at the
 * instruction instead (upstream behavior).
 *
 * <p>Synced from v0.8.2 to v0.11.1: the generic {@code assert} instruction, failure texts
 * that name the compared values, {@code asserttype}'s expanded taxonomy, {@code assertprints}'
 * buffer truncation, the {@code {1}}/{@code {name}} message placeholders and the refusal to
 * pause the game in multiplayer. The placeholder engine lives in
 * {@link #formatMessage(Func, String, boolean, Object, Object[])} so it is testable without a
 * live executor ({@code AssertMessageTest}).</p>
 *
 * <p>Every class implements the {@link AssertInstruction} marker so the map overlay skips
 * these blocks during its scan: the instruction owns its message lifecycle, and a scan
 * that saw "not a stop/wait" would wipe the failure message every frame.</p>
 */
public final class AssertInstructions{
    private AssertInstructions(){}

    /** Marker interface for assertion instructions (overlay scan skip). */
    public interface AssertInstruction{
    }

    /** The generic {@code assert} instruction: the condition must hold, otherwise the
     *  program stops on the instruction. The message argument is optional — when it is not a
     *  non-empty string the failure text is built from the localized default, which names
     *  the compared values and the operator. */
    public static class AssertI implements LExecutor.LInstruction, AssertInstruction{
        public ConditionOp op = ConditionOp.notEqual;
        public LVar value, compare;
        public LVar message;

        public AssertI(ConditionOp op, LVar value, LVar compare, LVar message){
            this.op = op;
            this.value = value;
            this.compare = compare;
            this.message = message;
        }

        public AssertI(){
        }

        @Override
        public final void run(LExecutor exec){
            if(op.test(value, compare)){
                ProcessorStatus.reset(exec.build);
            }else{
                assertion(exec, "logicsugar.asserts.assertFailedWithValues", message,
                    value, op.symbol, compare);
            }
        }
    }

    public static class AssertBoundsI implements LExecutor.LInstruction, AssertInstruction{
        public AssertionType type = AssertionType.any;
        public LVar multiple;
        public LVar min;
        public ConditionOp opMin = ConditionOp.lessThanEq;
        public LVar value;
        public ConditionOp opMax = ConditionOp.lessThanEq;
        public LVar max;
        public LVar message;

        public AssertBoundsI(AssertionType type, LVar multiple, LVar min, ConditionOp opMin, LVar value, ConditionOp opMax, LVar max, LVar message){
            this.type = type;
            this.multiple = multiple;
            this.min = min;
            this.opMin = opMin;
            this.value = value;
            this.opMax = opMax;
            this.max = max;
            this.message = message;
        }

        public AssertBoundsI(){
        }

        @Override
        public final void run(LExecutor exec){
            if((value.isobj ? type.objFunction.get(value.objval) : type.function.get(value.num()))
                && (type != AssertionType.multiple || (value.num() % multiple.num() == 0))
                && (test(opMin, min.num(), value.num()))
                && (test(opMax, value.num(), max.num()))){
                ProcessorStatus.reset(exec.build);
            }else{
                assertion(exec, "logicsugar.asserts.boundsFailedWithValues", message,
                    min, value, max, opMin.symbol, opMax.symbol);
            }
        }

        /** Bounds operators come from the game's {@link ConditionOp} but only the two
         *  inequality forms make sense here; anything else fails the check. */
        private boolean test(ConditionOp op, double a, double b){
            switch(op){
                case lessThan: return a < b;
                case lessThanEq: return a <= b;
                default: return false;
            }
        }
    }

    public static class AssertEqualsI implements LExecutor.LInstruction, AssertInstruction{
        public LVar expected;
        public LVar actual;
        public LVar message;

        public AssertEqualsI(LVar expected, LVar actual, LVar message){
            this.expected = expected;
            this.actual = actual;
            this.message = message;
        }

        public AssertEqualsI(){
        }

        @Override
        public final void run(LExecutor exec){
            if(ConditionOp.strictEqual.test(expected, actual)){
                ProcessorStatus.reset(exec.build);
            }else{
                assertion(exec, "logicsugar.asserts.equalFailedWithValues", message, expected, actual);
            }
        }
    }

    public static class AssertFlushI implements LExecutor.LInstruction{
        public LVar flushIndex;

        public AssertFlushI(LVar flushIndex){
            this.flushIndex = flushIndex;
        }

        public AssertFlushI(){
        }

        @Override
        public final void run(LExecutor exec){
            flushIndex.setnum(exec.textBuffer.length());
        }
    }

    public static class AssertPrintsI implements LExecutor.LInstruction, AssertInstruction{
        public LVar flushIndex;
        public LVar expected;
        public LVar message;

        public AssertPrintsI(LVar flushIndex, LVar expected, LVar message){
            this.flushIndex = flushIndex;
            this.expected = expected;
            this.message = message;
        }

        public AssertPrintsI(){
        }

        @Override
        public final void run(LExecutor exec){
            int flushIndex = this.flushIndex.numi();
            if(flushIndex < 0 || flushIndex > exec.textBuffer.length()){
                assertion(exec, "logicsugar.asserts.invalidFlushIndex", "");
            }else{
                String text = exec.textBuffer.substring(flushIndex);
                // Upstream v0.11.1: always rewind to the recorded position, so a failing
                // assertion neither grows the buffer on every retry nor leaves the output
                // of the checked region behind for the next assertprints.
                exec.textBuffer.setLength(flushIndex);

                if(!text.equals(expected.obj())){
                    assertion(exec, "logicsugar.asserts.equalFailedWithValues", message, expected, text);
                }else{
                    ProcessorStatus.reset(exec.build);
                }
            }
        }
    }

    /** Asserts the runtime data type of a value. The failure message automatically appends
     *  which type was expected and what the value actually holds, using the same taxonomy as
     *  the game's variable panel — e.g. "expected unit, got null". */
    public static class AssertTypeI implements LExecutor.LInstruction, AssertInstruction{
        public AssertionDataType expectedType = AssertionDataType.number;
        public LVar actualValue;
        public LVar message;

        public AssertTypeI(AssertionDataType expectedType, LVar actualValue, LVar message){
            this.expectedType = expectedType;
            this.actualValue = actualValue;
            this.message = message;
        }

        public AssertTypeI(){
        }

        @Override
        public final void run(LExecutor exec){
            if(expectedType.matches(actualValue)){
                ProcessorStatus.reset(exec.build);
            }else{
                assertion(exec, "logicsugar.asserts.equalFailedWithValues", message,
                    expectedType.name(), AssertionDataType.actualType(actualValue));
            }
        }
    }

    public static class BreakpointI implements LExecutor.LInstruction, AssertInstruction{
        public ConditionOp op = ConditionOp.notEqual;
        public LVar value, compare;

        public BreakpointI(ConditionOp op, LVar value, LVar compare){
            this.op = op;
            this.value = value;
            this.compare = compare;
        }

        public BreakpointI(){
        }

        @Override
        public void run(LExecutor exec){
            if(op.test(value, compare)){
                breakpoint(exec.build, Core.bundle.format("logicsugar.breakpoint.message", exec.counter.numval - 1));
            }
        }
    }

    public static class ErrorI implements LExecutor.LInstruction, AssertInstruction{
        public LVar[] vars = new LVar[10];

        public ErrorI(LVar[] vars){
            this.vars = vars;
        }

        public ErrorI(){
        }

        @Override
        public final void run(LExecutor exec){
            ProcessorStatus.setMessage(exec.build, () -> buildMessage(exec, "", true, vars[0], vars));
            exec.counter.numval--;
            exec.yield = true;
        }
    }

    public static class LogI implements LExecutor.LInstruction, AssertInstruction{
        public Log.LogLevel level = Log.LogLevel.info;
        public LVar[] vars = new LVar[10];

        public LogI(Log.LogLevel level, LVar[] vars){
            this.level = level;
            this.vars = vars;
        }

        public LogI(){
        }

        @Override
        public final void run(LExecutor exec){
            Log.log(level, buildMessage(exec, "[LogicSugar] ", true, vars[0], vars));
        }
    }

    /** Whether the game runs in a networked session. */
    static boolean multiplayer(){
        return Vars.net != null && Vars.net.active();
    }

    /** Reports a failed assertion. By default the program loops on the failing instruction
     *  and the message is drawn above the processor; with {@code assertsAreBreakpoints} the
     *  game pauses at the instruction instead.
     *
     *  <p>Upstream v0.10 refuses to pause in multiplayer, and LogicSugar's multiplayer floor
     *  requires the same refusal. Divergence from upstream: the failure is still reported and
     *  the program still loops on it (the non-breakpoint behavior) instead of being dropped —
     *  a client-side message cannot affect the other players, while a silent failure
     *  misreports what the program did.</p>
     *
     *  <p>{@code message} is the optional custom text; when it is not a non-empty string the
     *  localized text behind {@code defaultKey} describes the values instead.</p> */
    private static void assertion(LExecutor exec, String defaultKey, Object message, Object... values){
        if(ProcessorStatus.assertsAreBreakpoints && !multiplayer()){
            if(ProcessorStatus.disableBreakpoints) return;  // avoid building the message
            breakpoint(exec.build, assertionText(exec::optionalVar, defaultKey, message, values));
        }else{
            exec.counter.numval--;
            exec.yield = true;
            ProcessorStatus.setMessage(exec.build,
                () -> assertionText(exec::optionalVar, defaultKey, message, values));
        }
    }

    private static void breakpoint(LogicBuild build, String message){
        ProcessorStatus.breakpoint(build, message);
    }

    /** The text of a failed assertion: the custom message with its placeholders expanded, or
     *  the localized default describing the values.
     *
     *  <p>The message slot is the first entry of {@code values} (that is how the instructions
     *  are called), so the default text renders {@code values[1..]} only. Upstream v0.11.1
     *  passes the whole array to its default texts, which makes them print the message slot
     *  ("null" for the default card) instead of the compared value — LogicSugar renders the
     *  values and leaves the stray slot out.</p> */
    static String assertionText(Func<String, LVar> varLookup, String defaultKey, Object message, Object[] values){
        if(isCustomMessage(message)){
            return formatMessage(varLookup, "", false, message, values);
        }
        Object[] shown = values.length > 0 && values[0] == message
            ? java.util.Arrays.copyOfRange(values, 1, values.length) : values;
        if(shown.length == 0) return Core.bundle == null ? defaultKey : Core.bundle.get(defaultKey);
        return Core.bundle == null ? defaultKey : Core.bundle.format(defaultKey, printArgs(shown));
    }

    private static String buildMessage(LExecutor exec, String prefix, boolean appendUnused, Object message, LVar[] vars){
        return formatMessage(exec::optionalVar, prefix, appendUnused, message, vars);
    }

    /** Whether the message argument is text the author wrote (as opposed to the literal
     *  {@code null}/empty placeholder that asks for the default text). */
    static boolean isCustomMessage(Object message){
        if(message instanceof String str) return !str.isEmpty();
        return message instanceof LVar var && var.isobj && var.objval instanceof String str && !str.isEmpty();
    }

    /**
     * The message placeholder engine, shared by the assertions and by {@code error}/{@code log}.
     *
     * <ul>
     *   <li>{@code {1}}..{@code {9}} — the corresponding value argument. When {@code message}
     *       is the first argument the numbering starts at the following slot ({@code {1}} is
     *       the compared value), which is upstream v0.11.1's rule and what the cards
     *       document.</li>
     *   <li>{@code {name}} — the value of that variable at failure time. {@code {@counter}}
     *       resolves to the failing instruction's index (the counter points at the
     *       instruction being retried, so one is subtracted — same as upstream).</li>
     *   <li>{@code [[1]}..{@code [[9]} — the pre-v0.11 upstream placeholder syntax, still
     *       honoured so programs saved by LogicSugar ≤5.5 or by older MlogAssertions keep
     *       rendering their messages.</li>
     * </ul>
     *
     * Unresolvable placeholders are left as typed. With {@code appendUnused} the parameters
     * that no placeholder used are appended after the message (string values quoted).
     */
    static String formatMessage(Func<String, LVar> varLookup, String prefix, boolean appendUnused, Object message, Object[] arguments){
        int used = 0;
        StringBuilder sbr = new StringBuilder(50).append(prefix).append(print(message));
        int offset = arguments.length > 0 && message == arguments[0] ? 1 : 0;

        int pos = sbr.indexOf("{");
        while(pos >= 0){
            int end = sbr.indexOf("}", pos);
            if(end < 0) break;

            String token = sbr.substring(pos + 1, end);
            String str = null;
            if(token.length() == 1 && token.charAt(0) >= '1' && token.charAt(0) <= '9'){
                int index = token.charAt(0) - '1' + offset;
                if(index < arguments.length){
                    str = print(arguments[index], true);
                    used |= 1 << index;
                }
            }else{
                LVar var = varLookup.get(token);
                if(var != null){
                    str = var.name.equals("@counter") ? String.valueOf((int)var.numval - 1) : print(var);
                }
            }

            if(str != null){
                sbr.replace(pos, end + 1, str);
                end = pos + str.length();
                if(end >= sbr.length()) break;
            }
            pos = sbr.indexOf("{", end);
        }

        pos = sbr.indexOf("[[");
        while(pos >= 0 && pos + 3 < sbr.length()){
            char digit = sbr.charAt(pos + 2);
            if(digit >= '1' && digit <= '9' && sbr.charAt(pos + 3) == ']'){
                int index = digit - '0';
                if(index < arguments.length){
                    String str = print(arguments[index], true);
                    used |= 1 << index;
                    sbr.replace(pos, pos + 4, str);
                    pos = sbr.indexOf("[[", pos + str.length());
                    continue;
                }
            }
            pos = sbr.indexOf("[[", pos + 1);
        }

        if(appendUnused){
            for(int i = 1; i < arguments.length; i++){
                if(arguments[i] instanceof LVar var && (used & (1 << i)) == 0 && nonNull(var)){
                    sbr.append(' ').append(print(var, true));
                }
            }
        }

        return sbr.toString();
    }

    private static boolean nonNull(LVar var){
        return !"null".equals(var.name);
    }

    private static final double COLOR_LIMIT = Color.white.toDoubleBits();

    private static String print(LVar value){
        return print(value, false);
    }

    /** Formats an assertion message part: an {@link LVar} through the logic printer, any
     *  other value (already-classified type name, actual buffer text) as plain text. */
    private static String print(Object value){
        return print(value, false);
    }

    private static String print(Object value, boolean formatString){
        return value instanceof LVar lvar ? print(lvar, formatString) : String.valueOf(value);
    }

    private static String print(LVar value, boolean formatString){
        if(value.isobj){
            return formatString && value.objval instanceof String str ? '"' + str + '"' : LExecutor.PrintI.toString(value.objval);
        }else if(value.numval <= COLOR_LIMIT && value.numval > 0){
            long color = Double.doubleToLongBits(value.numval) & 0xFFFFFFFFL;
            return '%' + Integer.toHexString((int)color);
        }else if((long)value.numval == value.numval){
            // Upstream v0.11.1: a whole number is printed without the decimal part, so
            // "got 5.0" no longer appears next to "expected 5".
            return String.valueOf((long)value.numval);
        }else{
            return String.valueOf(value.numval);
        }
    }

    private static Object[] printArgs(Object[] args){
        Object[] printed = new String[args.length];
        for(int i = 0; i < args.length; i++){
            printed[i] = print(args[i]);
        }
        return printed;
    }
}
