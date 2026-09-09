package logicsugar.assist;

import arc.Core;
import arc.graphics.Color;
import arc.util.Log;
import mindustry.logic.ConditionOp;
import mindustry.logic.LExecutor;
import mindustry.logic.LVar;
import mindustry.logic.SugarAsserts.AssertDataType;
import mindustry.logic.SugarAsserts.AssertOp;
import mindustry.logic.SugarAsserts.AssertionType;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;

/**
 * Runtime instruction classes for LogicSugar's assertion statement set, ported from the
 * upstream MlogAssertions mod (cardillan/mlogassertions, wire-format compatible). Failure
 * reporting goes through {@link ProcessorStatus}, which draws the message above the
 * processor and keeps the program looping on the failing instruction (counter rewind +
 * yield) until the condition passes or the processor is reconfigured. With the
 * {@code assertsAreBreakpoints} setting, a failed assertion pauses the game at the
 * instruction instead (upstream v0.8.2).
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

    public static class AssertBoundsI implements LExecutor.LInstruction, AssertInstruction{
        public AssertionType type = AssertionType.any;
        public LVar multiple;
        public LVar min;
        public AssertOp opMin = AssertOp.lessThanEq;
        public LVar value;
        public AssertOp opMax = AssertOp.lessThanEq;
        public LVar max;
        public LVar message;

        public AssertBoundsI(AssertionType type, LVar multiple, LVar min, AssertOp opMin, LVar value, AssertOp opMax, LVar max, LVar message){
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
                && (opMin.function.get(min.num(), value.num()))
                && (opMax.function.get(value.num(), max.num()))){
                ProcessorStatus.reset(exec.build);
            }else{
                assertion(exec, message, null, null);
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
                assertion(exec, message, expected, actual);
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
                assertion(exec, Core.bundle.get("logicsugar.asserts.invalidFlushIndex"), null, null);
            }else{
                String text = exec.textBuffer.substring(flushIndex);

                if(!text.equals(expected.obj())){
                    assertion(exec, message, expected, text);
                }else{
                    exec.textBuffer.setLength(flushIndex);
                    ProcessorStatus.reset(exec.build);
                }
            }
        }
    }

    /** Asserts the runtime data type of a value (number / string / content / building /
     *  unit / team / null). The failure message automatically appends which type was
     *  expected and what the value actually holds, using the same taxonomy as the game's
     *  variable panel — e.g. "Assertion failed: … (expected unit, got null)". */
    public static class AssertTypeI implements LExecutor.LInstruction, AssertInstruction{
        public LVar value;
        public AssertDataType type = AssertDataType.number;
        public LVar message;

        public AssertTypeI(LVar value, AssertDataType type, LVar message){
            this.value = value;
            this.type = type;
            this.message = message;
        }

        public AssertTypeI(){
        }

        @Override
        public final void run(LExecutor exec){
            if(type.matches(value)){
                ProcessorStatus.reset(exec.build);
            }else{
                assertion(exec, message, type.token(), AssertDataType.actualType(value));
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
            ProcessorStatus.setMessage(exec.build, () -> buildMessage("", vars));
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
            Log.log(level, buildMessage("[LogicSugar] ", vars));
        }
    }

    /** Reports a failed assertion. By default the program loops on the failing instruction
     *  and the message is drawn above the processor; with {@code assertsAreBreakpoints} the
     *  game pauses at the instruction instead (upstream v0.8.2 behavior). When both
     *  {@code expected} and {@code actual} are given, the message appends
     *  " (expected X, got Y)". */
    private static void assertion(LExecutor exec, Object message, Object expected, Object actual){
        if(ProcessorStatus.assertsAreBreakpoints){
            if(ProcessorStatus.disableBreakpoints) return;  // avoid building the message
            breakpoint(exec.build, expected == null && actual == null
                ? Core.bundle.format("logicsugar.asserts.failed", print(message))
                : Core.bundle.format("logicsugar.asserts.failedWithValues", print(message), print(expected), print(actual)));
        }else{
            exec.counter.numval--;
            exec.yield = true;

            ProcessorStatus.setMessage(exec.build, expected == null && actual == null
                ? () -> Core.bundle.format("logicsugar.asserts.failed", print(message))
                : () -> Core.bundle.format("logicsugar.asserts.failedWithValues", print(message), print(expected), print(actual)));
        }
    }

    private static void breakpoint(LogicBuild build, String message){
        ProcessorStatus.breakpoint(build, message);
    }

    /** Expands {@code [[1]}..{@code [[9]} placeholders from vars[1..9], then appends any
     *  unused non-null params (strings quoted, colors as literals). Ported verbatim from
     *  upstream; vars[0] is the message itself. */
    private static String buildMessage(String prefix, LVar[] vars){
        int used = 0;
        StringBuilder sbr = prefix.isEmpty() ? new StringBuilder(print(vars[0])) : new StringBuilder(prefix).append(print(vars[0]));
        int pos = sbr.indexOf("[[");
        while(pos >= 0){
            if(sbr.charAt(pos + 2) >= '1' && sbr.charAt(pos + 2) <= '9' && sbr.charAt(pos + 3) == ']'){
                int index = sbr.charAt(pos + 2) - '0';
                String str = print(vars[index]);
                sbr.replace(pos, pos + 4, str);
                pos = sbr.indexOf("[[", pos + str.length());
                used |= (1 << index);
            }else{
                pos = sbr.indexOf("[[", pos + 1);
            }
        }

        for(int i = 1; i < vars.length; i++){
            if((used & (1 << i)) == 0 && nonNull(vars[i])) sbr.append(' ').append(print(vars[i], true));
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
        return value instanceof LVar lvar ? print(lvar, false) : String.valueOf(value);
    }

    private static String print(LVar value, boolean formatString){
        if(value.isobj){
            return formatString && value.objval instanceof String str ? '"' + str + '"' : LExecutor.PrintI.toString(value.objval);
        }else if(value.numval <= COLOR_LIMIT && value.numval > 0){
            long color = Double.doubleToLongBits(value.numval) & 0xFFFFFFFFL;
            return '%' + Integer.toHexString((int)color);
        }else{
            return String.valueOf(value.numval);
        }
    }
}
