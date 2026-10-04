package logicsugar.assist;

import arc.Core;
import arc.func.Func;
import arc.graphics.Color;
import arc.util.Log;
import logicsugar.profile.ProfilingCommand;
import logicsugar.vars.Snapshot;
import logicsugar.vars.SnapshotType;
import logicsugar.vars.Snapshots;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.logic.ConditionOp;
import mindustry.logic.LExecutor;
import mindustry.logic.LVar;
import mindustry.logic.Senseable;
import mindustry.logic.SugarAsserts.AssertionDataType;
import mindustry.logic.SugarAsserts.AssertionType;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;

/**
 * Runtime instruction classes for LogicSugar's assertion statement set, ported from the
 * upstream MlogAssertions mod (cardillan/mlogassertions, currently v0.11.3, wire-format
 * compatible). Failure reporting goes through {@link ProcessorStatus}, which draws the
 * message above the processor and keeps the program looping on the failing instruction
 * (counter rewind + yield) until the condition passes or the processor is reconfigured. With
 * the {@code assertsAreBreakpoints} setting, a failed assertion pauses the game at the
 * instruction instead (upstream behavior).
 *
 * <p>Synced from v0.8.2 to v0.11.3: the generic {@code assert} instruction, failure texts
 * that name the compared values, {@code asserttype}'s expanded taxonomy, {@code assertprints}'
 * buffer truncation, the {@code {1}}/{@code {name}} message placeholders and the refusal to
 * pause the game in multiplayer. The placeholder engine lives in
 * {@link #formatMessage(Func, String, boolean, Object, Object[])} so it is testable without a
 * live executor ({@code AssertMessageTest}).</p>
 *
 * <p>Every class implements the {@link DevToolsInstruction} marker so the map overlay skips
 * these blocks during its scan: the instruction owns its message lifecycle, and a scan
 * that saw "not a stop/wait" would wipe the failure message every frame. Upstream v0.11.3
 * extends the same marker with {@code vars()} — the variable slots an instruction touches,
 * which the profiler's recording snapshots collect ({@code profile.InstrumentationEngine}).
 * The name follows upstream; pre-5.8.0 builds called it {@code AssertInstruction}.</p>
 */
public final class AssertInstructions{
    private AssertInstructions(){}

    /** Marker interface for assertion/debug instructions: the overlay scan skips them, and
     *  {@code vars()} lists the variable slots the instruction reads or writes (in the
     *  instruction's own field order, {@code null} slots included) for snapshot recording. */
    public interface DevToolsInstruction extends LExecutor.LInstruction{
        LVar[] vars();
    }

    /** The generic {@code assert} instruction: the condition must hold, otherwise the
     *  program stops on the instruction. The message argument is optional — when it is not a
     *  non-empty string the failure text is built from the localized default, which names
     *  the compared values and the operator. */
    public static class AssertI implements DevToolsInstruction{
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
        public LVar[] vars(){
            return new LVar[]{value, compare, message};
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

    public static class AssertBoundsI implements DevToolsInstruction{
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
        public LVar[] vars(){
            return new LVar[]{multiple, min, value, max, message};
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

    public static class AssertEqualsI implements DevToolsInstruction{
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
        public LVar[] vars(){
            return new LVar[]{expected, actual, message};
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

    public static class AssertFlushI implements DevToolsInstruction{
        public LVar flushIndex;

        public AssertFlushI(LVar flushIndex){
            this.flushIndex = flushIndex;
        }

        public AssertFlushI(){
        }

        @Override
        public LVar[] vars(){
            return new LVar[]{flushIndex};
        }

        @Override
        public final void run(LExecutor exec){
            flushIndex.setnum(exec.textBuffer.length());
        }
    }

    public static class AssertPrintsI implements DevToolsInstruction{
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
        public LVar[] vars(){
            return new LVar[]{flushIndex, expected, message};
        }

        @Override
        public final void run(LExecutor exec){
            int flushIndex = this.flushIndex.numi();
            if(flushIndex < 0 || flushIndex > exec.textBuffer.length()){
                assertion(exec, "logicsugar.asserts.invalidFlushIndex", "");
            }else{
                String text = exec.textBuffer.substring(flushIndex);
                // Upstream v0.11.3: always rewind to the recorded position, so a failing
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
    public static class AssertTypeI implements DevToolsInstruction{
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
        public LVar[] vars(){
            return new LVar[]{actualValue, message};
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

    public static class BreakpointI implements DevToolsInstruction{
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
        public LVar[] vars(){
            return new LVar[]{value, compare};
        }

        @Override
        public void run(LExecutor exec){
            if(op.test(value, compare)){
                breakpoint(exec.build, Core.bundle.format("logicsugar.breakpoint.message", exec.counter.numval - 1));
            }
        }
    }

    public static class ErrorI implements DevToolsInstruction{
        public LVar[] vars = new LVar[10];

        public ErrorI(LVar[] vars){
            this.vars = vars;
        }

        public ErrorI(){
        }

        @Override
        public LVar[] vars(){
            return vars;
        }

        @Override
        public final void run(LExecutor exec){
            ProcessorStatus.setMessage(exec.build, () -> buildMessage(exec, "", true, vars[0], vars));
            exec.counter.numval--;
            exec.yield = true;
        }
    }

    public static class LogI implements DevToolsInstruction{
        public Log.LogLevel level = Log.LogLevel.info;
        public LVar[] vars = new LVar[10];

        public LogI(Log.LogLevel level, LVar[] vars){
            this.level = level;
            this.vars = vars;
        }

        public LogI(){
        }

        @Override
        public LVar[] vars(){
            return vars;
        }

        @Override
        public final void run(LExecutor exec){
            Log.log(level, buildMessage(exec, "[LogicSugar] ", true, vars[0], vars));
        }
    }

    /** Creates a snapshot of an entity through the snapshot subsystem. Purely client-side: it
     *  never changes the saved program, so it needs no multiplayer gate. The name is the
     *  card's message when that is a non-empty string, otherwise the localized
     *  "Mlog &lt;type&gt; snapshot" default. Portable: an invalid/dead entity is ignored inside
     *  {@link Snapshots#create}.
     *
     *  <p>v0.11.2's {@code recording} type records the target processor's next {@code steps}
     *  instructions: the created snapshot is the initial connected state, each executed
     *  instruction adds a sub-snapshot, and the master is its own first recording entry
     *  (so the sub-list starts with the state before the first recorded instruction).</p> */
    public static class SnapshotI implements DevToolsInstruction{
        public SnapshotType type = SnapshotType.isolated;
        public LVar block, steps, message;

        public SnapshotI(SnapshotType type, LVar block, LVar steps, LVar message){
            this.type = type;
            this.block = block;
            this.steps = steps;
            this.message = message;
        }

        public SnapshotI(){
        }

        @Override
        public LVar[] vars(){
            return new LVar[]{block, steps, message};
        }

        @Override
        public void run(LExecutor exec){
            if(block.obj() instanceof Senseable senseable){
                Snapshot master = Snapshots.create(senseable, type, isCustomMessage(message)
                    ? formatMessage(exec::optionalVar, "", false, message, new Object[0])
                    : L10n.text("logicsugar.vars.snapshot.mlogname", "Mlog {0} snapshot", type.name()));

                if(master != null && type == SnapshotType.recording && senseable instanceof LogicBuild build){
                    master.recording().add(master);
                    logicsugar.profile.InstrumentationEngine.startInstructionSnapshots(build, steps.numi(), master);
                }
            }
        }
    }

    /** Starts/stops/clears the profiler of a target processor (upstream v0.11.3). Like every
     *  other debugging instruction it only exists as real mlog in an {@code emit} build; it is
     *  pure local observation, so it needs no multiplayer gate (each client profiles its own
     *  view). */
    public static class ProfileI implements DevToolsInstruction{
        public ProfilingCommand type = ProfilingCommand.start;
        public LVar target;

        public ProfileI(ProfilingCommand type, LVar target){
            this.type = type;
            this.target = target;
        }

        public ProfileI(){
        }

        @Override
        public LVar[] vars(){
            return new LVar[]{target};
        }

        @Override
        public void run(LExecutor exec){
            if(target.obj() instanceof LogicBuild build){
                switch(type){
                    case start -> logicsugar.profile.InstrumentationEngine.startProfiling(build);
                    case stop -> logicsugar.profile.InstrumentationEngine.stopProfiling(build);
                    case clear -> logicsugar.profile.InstrumentationEngine.clearProfilingData(build);
                }
            }
        }
    }

    /** Restarts the target processor (code reload + variables reset), so profiling or snapshot
     *  recording of its initialization code can be started from another processor (upstream
     *  v0.11.3).
     *
     *  <p>Side effects worth documenting: this replaces the running program of another block
     *  and resets its variables. It is a debugging instruction that only exists in an
     *  {@code emit} build, and multiplayer matches force {@code strip} (see
     *  {@code docs/architecture.md}): the instruction row never reaches a saved program of a
     *  multiplayer-safe map.</p> */
    public static class RestartI implements DevToolsInstruction{
        public LVar target;

        public RestartI(LVar target){
            this.target = target;
        }

        public RestartI(){
        }

        @Override
        public LVar[] vars(){
            return new LVar[]{target};
        }

        @Override
        public void run(LExecutor exec){
            if(target.obj() instanceof LogicBuild build){
                if(build.accumulator < 2f){
                    // Make sure the accumulator allows executing this instruction and the following one,
                    // so resetting a processor and activating snapshotting on it happens in one frame.
                    exec.counter.numval--;
                    exec.yield = true;
                    return;
                }

                // 'build.updateCode' doesn't trigger the ConfigEvent, so restart profiling explicitly
                logicsugar.profile.Instrumentation instrumentation =
                    logicsugar.profile.InstrumentationEngine.getInstrumentation(build);
                build.updateCode(build.code);
                if(instrumentation != null && instrumentation.profiling){
                    logicsugar.profile.InstrumentationEngine.startProfiling(build);
                }
            }
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
            String text = assertionText(exec::optionalVar, defaultKey, message, values);
            exec.counter.numval--;
            exec.yield = true;
            ProcessorStatus.setMessage(exec.build, () -> text);
            // Upstream v0.11: an isolated snapshot of the failing processor, named after the
            // failure message. Client-side, so it is not gated on multiplayer.
            if(ProcessorStatus.snapshotOnAssertion){
                Snapshots.create(exec.build, SnapshotType.isolated, text);
            }
        }
    }

    private static void breakpoint(LogicBuild build, String message){
        // Upstream v0.11 takes a connected snapshot when a breakpoint hits (its own code
        // asks snapshotOnAssertion() here, which we read as the breakpoint setting it means).
        if(ProcessorStatus.snapshotOnBreakpoint){
            Snapshots.create(build, SnapshotType.connected,
                L10n.text("logicsugar.vars.snapshot.breakpoint", "Breakpoint snapshot at #{0}",
                    (int)build.executor.counter.numval - 1));
        }
        ProcessorStatus.breakpoint(build, message);
    }

    /** The text of a failed assertion: the custom message with its placeholders expanded, or
     *  the localized default describing the values.
     *
     *  <p>The message slot is the first entry of {@code values} (that is how the instructions
     *  are called), so the default text renders {@code values[1..]} only. Upstream v0.11.3
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
     *       the compared value), which is upstream v0.11.3's rule and what the cards
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
            // Upstream v0.11.3: a whole number is printed without the decimal part, so
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
