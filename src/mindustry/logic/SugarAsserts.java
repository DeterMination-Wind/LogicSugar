package mindustry.logic;

import arc.Core;
import arc.func.Cons;
import arc.func.Func;
import arc.func.Prov;
import arc.graphics.Color;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import mindustry.gen.Icon;
import mindustry.gen.Building;
import mindustry.gen.LogicIO;
import mindustry.gen.Unit;
import mindustry.game.Team;
import mindustry.ctype.Content;
import mindustry.logic.LExecutor.LInstruction;
import mindustry.logic.SugarStatements.SugarStatement;
import mindustry.ui.Styles;

/**
 * Assertion statement set for runtime checks and debugging, ported from the upstream
 * MlogAssertions mod (cardillan/mlogassertions) with its exact wire format, so programs
 * compiled in debug mode run identically under either mod and Mindcode-generated code
 * round-trips through the editor.
 *
 * <p>The cards serialize to the custom instruction tokens themselves ({@code assertBounds
 * ...}), which double as the sugar source format. The compiler decides their fate at
 * lowering time: by default ({@code strip}) they are compiled away — the sugar survives in
 * the persistence carrier and saved mlog stays vanilla-parseable — while {@code emit}
 * (debug build) writes them into the program as real instructions. Emitted instructions
 * are unparseable on vanilla clients (they degrade to InvalidStatement placeholders), so
 * the emit mode is an explicit opt-in setting.</p>
 *
 * <p>Coexistence: if another mod already owns an opcode in {@code LAssembler.customParsers}
 * (e.g. MlogAssertions loaded first), the affected cards are not registered at all —
 * duplicate palette entries and silently overwritten parsers would only confuse users.</p>
 */
public final class SugarAsserts{
    public static final LCategory assertsCategory = new LCategory("asserts", Color.slate, Icon.warningSmall);

    private static boolean parsersInstalled;

    private SugarAsserts(){}

    /** Registers every assertion token parser into {@code LAssembler.customParsers}.
     *  Called from {@link SugarStatements#installParsers()} so all entry points (mod init,
     *  decompiler preflight, self-tests) share one registration sequence. Idempotent per
     *  class loader. */
    public static void installParsers(){
        if(parsersInstalled) return;
        parsersInstalled = true;

        register(AssertBoundsCard::new, AssertBoundsCard.opcode, SugarAsserts::parseAssertBounds);
        register(AssertEqualsCard::new, AssertEqualsCard.opcode, SugarAsserts::parseAssertEquals);
        register(AssertFlushCard::new, AssertFlushCard.opcode, SugarAsserts::parseAssertFlush);
        register(AssertPrintsCard::new, AssertPrintsCard.opcode, SugarAsserts::parseAssertPrints);
        register(AssertTypeCard::new, AssertTypeCard.opcode, SugarAsserts::parseAssertType);
        register(ErrorCard::new, ErrorCard.opcode, SugarAsserts::parseError);
        register(LogCard::new, LogCard.opcode, SugarAsserts::parseLog);
        register(BreakpointCard::new, BreakpointCard.opcode, SugarAsserts::parseBreakpoint);
    }

    private static void register(Prov<LStatement> prov, String opcode, Func<String[], LStatement> parser){
        if(LAssembler.customParsers.containsKey(opcode)) return;
        LogicIO.allStatements.add(prov);
        LAssembler.customParsers.put(opcode, parser);
    }

    private static String text(String key, String fallback){
        return Core.bundle.get("logicsugar." + key, fallback);
    }

    /** The assertion opcodes this class owns; the compiler and the decompiler use this to
     *  decide whether the assert-emit dimension matters for a program. */
    public static final String[] opcodes = {
        AssertBoundsCard.opcode, AssertEqualsCard.opcode, AssertFlushCard.opcode,
        AssertPrintsCard.opcode, AssertTypeCard.opcode, ErrorCard.opcode, LogCard.opcode, BreakpointCard.opcode
    };

    /** Whether a sugar source line starts with one of the assertion opcodes (cheap scan
     *  used to skip the extra verify matrix dimension when a program has no assertions). */
    public static boolean containsAssertStatements(String sugar){
        if(sugar == null) return false;
        for(String line : sugar.replace("\r\n", "\n").split("\n", -1)){
            String bare = line.trim();
            for(String opcode : opcodes){
                if(bare.startsWith(opcode + " ") || bare.equals(opcode)) return true;
            }
        }
        return false;
    }

    /** Base for all assertion cards: own palette category, serializer shared with the
     *  instruction opcode. All {@code field}/{@code showSelect} calls stay inside these
     *  subclass instance methods (cross-loader rule — see AGENTS.md). */
    public abstract static class AssertCard extends SugarStatement{
        @Override
        public LCategory category(){
            return assertsCategory;
        }

        /** Emits the card's token line; empty value slots become "~" so the fixed token
         *  count survives LParser's reused static token array (LogicSugar convention). */
        protected static String optional(String value){
            return value == null || value.isEmpty() ? "~" : value;
        }
    }

    /** Value range/type check for an index or general numeric variable. */
    public static class AssertBoundsCard extends AssertCard{
        public static final String opcode = "assertBounds";
        public AssertionType type = AssertionType.integer;
        public String multiple = "2";
        public String min = "0";
        public AssertOp opMin = AssertOp.lessThanEq;
        public String value = "index";
        public AssertOp opMax = AssertOp.lessThanEq;
        public String max = "10";
        public String message = "\"Index out of bounds (0 to 10).\"";

        @Override
        public void build(Table table){
            table.add(text("asserts.value", "value of")).padLeft(4);
            field(table, value, s -> value = s).width(85f).pad(2f);
            table.button(b -> {
                b.add(type.name());
                b.clicked(() -> showSelect(b, AssertionType.all, type, o -> {
                    type = o;
                    build(table);
                }));
            }, Styles.logict, () -> {}).size(96f, 40f).pad(4f).color(table.color);
            if(type == AssertionType.multiple){
                table.add(text("asserts.of", "of"));
                field(table, multiple, s -> multiple = s).width(70f).pad(2f);
            }
            table.add(text("asserts.bounds", "bounds")).padLeft(8);
            field(table, min, s -> min = s).width(70f).pad(2f);
            opButton(table, opMin, o -> {
                opMin = o;
                build(table);
            });
            table.add(text("asserts.and", ".."));
            opButton(table, opMax, o -> {
                opMax = o;
                build(table);
            });
            field(table, max, s -> max = s).width(70f).pad(2f);
            table.row();
            table.add(text("asserts.message", "message")).padLeft(4);
            field(table, message, s -> message = s).width(0f).growX().pad(2f);
        }

        private void opButton(Table table, AssertOp op, Cons<AssertOp> setter){
            table.button(b -> {
                b.add(op.symbol);
                b.clicked(() -> showSelect(b, AssertOp.all, op, setter));
            }, Styles.logict, () -> {}).size(48f, 40f).pad(4f).color(table.color);
        }

        @Override public String name(){ return text("asserts.bounds.card", "Assert Bounds"); }
        @Override public String typeName(){ return "AssertBounds"; }

        @Override
        public LInstruction build(LAssembler builder){
            return new logicsugar.assist.AssertInstructions.AssertBoundsI(type, builder.var(multiple),
                builder.var(min), opMin, builder.var(value), opMax, builder.var(max), builder.var(message));
        }

        @Override
        public void write(StringBuilder out){
            out.append(opcode).append(' ').append(type.name()).append(' ').append(optional(multiple)).append(' ')
                .append(optional(min)).append(' ').append(opMin.name()).append(' ').append(optional(value)).append(' ')
                .append(opMax.name()).append(' ').append(optional(max)).append(' ').append(optional(message));
        }
    }

    /** Compares an actual value against an expected one with {@code strictEqual}. */
    public static class AssertEqualsCard extends AssertCard{
        public static final String opcode = "assertequals";
        public String expected = "0";
        public String actual = "value";
        public String message = "\"value should be equal to 0\"";

        @Override
        public void build(Table table){
            table.add(text("asserts.expected", "expected")).padLeft(4);
            field(table, expected, s -> expected = s).width(110f).pad(2f);
            table.add(text("asserts.actual", "actual")).padLeft(8);
            field(table, actual, s -> actual = s).width(110f).pad(2f);
            table.row();
            table.add(text("asserts.message", "message")).padLeft(4);
            // width(0) releases the field() helper's 144px cell cap so growX can actually
            // stretch the trailing field to the card edge (vanilla trailing-field pattern)
            field(table, message, s -> message = s).width(0f).growX().pad(2f);
        }

        @Override public String name(){ return text("asserts.equals.card", "Assert Equals"); }
        @Override public String typeName(){ return "AssertEquals"; }

        @Override
        public LInstruction build(LAssembler builder){
            return new logicsugar.assist.AssertInstructions.AssertEqualsI(builder.var(expected),
                builder.var(actual), builder.var(message));
        }

        @Override
        public void write(StringBuilder out){
            out.append(opcode).append(' ').append(optional(expected)).append(' ')
                .append(optional(actual)).append(' ').append(optional(message));
        }
    }

    /** Records the current print-buffer position; pairs with {@link AssertPrintsCard}. */
    public static class AssertFlushCard extends AssertCard{
        public static final String opcode = "assertflush";
        public String position = "position";

        @Override
        public void build(Table table){
            table.add(text("asserts.position", "position")).padLeft(4);
            field(table, position, s -> position = s).width(110f).pad(2f);
        }

        @Override public String name(){ return text("asserts.flush.card", "Assert Flush"); }
        @Override public String typeName(){ return "AssertFlush"; }

        @Override
        public LInstruction build(LAssembler builder){
            return new logicsugar.assist.AssertInstructions.AssertFlushI(builder.var(position));
        }

        @Override
        public void write(StringBuilder out){
            out.append(opcode).append(' ').append(optional(position));
        }
    }

    /** Compares the print-buffer output since the paired {@code assertflush} with an
     *  expected string; on success the buffer is rewound to the recorded position. */
    public static class AssertPrintsCard extends AssertCard{
        public static final String opcode = "assertprints";
        public String position = "position";
        public String expected = "\"frog\"";
        public String message = "\"text output should be equal to 'frog'\"";

        @Override
        public void build(Table table){
            table.add(text("asserts.position", "position")).padLeft(4);
            field(table, position, s -> position = s).width(110f).pad(2f);
            table.add(text("asserts.expected", "expected")).padLeft(8);
            field(table, expected, s -> expected = s).width(110f).pad(2f);
            table.row();
            table.add(text("asserts.message", "message")).padLeft(4);
            field(table, message, s -> message = s).width(0f).growX().pad(2f);
        }

        @Override public String name(){ return text("asserts.prints.card", "Assert Prints"); }
        @Override public String typeName(){ return "AssertPrints"; }

        @Override
        public LInstruction build(LAssembler builder){
            return new logicsugar.assist.AssertInstructions.AssertPrintsI(builder.var(position),
                builder.var(expected), builder.var(message));
        }

        @Override
        public void write(StringBuilder out){
            out.append(opcode).append(' ').append(optional(position)).append(' ')
                .append(optional(expected)).append(' ').append(optional(message));
        }
    }

    /** Checks that a variable currently holds a value of the expected runtime data type.
     *  LogicSugar-original (no MlogAssertions/Mindcode counterpart): their clients degrade
     *  this instruction to a placeholder, which only ever matters for shared debug builds. */
    public static class AssertTypeCard extends AssertCard{
        public static final String opcode = "asserttype";
        public String value = "value";
        public AssertDataType type = AssertDataType.number;
        public String message = "\"value should hold the expected data type\"";

        @Override
        public void build(Table table){
            table.add(text("asserts.value", "value")).padLeft(4);
            field(table, value, s -> value = s).width(110f).pad(2f);
            table.add(text("asserts.istype", "is of type")).padLeft(8);
            table.button(b -> {
                b.add(type.display());
                b.clicked(() -> showSelect(b, AssertDataType.all, type, o -> {
                    type = o;
                    build(table);
                }));
            }, Styles.logict, () -> {}).size(96f, 40f).pad(4f).color(table.color);
            table.row();
            table.add(text("asserts.message", "message")).padLeft(4);
            field(table, message, s -> message = s).width(0f).growX().pad(2f);
        }

        @Override public String name(){ return text("asserts.type.card", "Assert Type"); }
        @Override public String typeName(){ return "AssertType"; }

        @Override
        public LInstruction build(LAssembler builder){
            return new logicsugar.assist.AssertInstructions.AssertTypeI(builder.var(value), type, builder.var(message));
        }

        @Override
        public void write(StringBuilder out){
            out.append(opcode).append(' ').append(optional(value)).append(' ')
                .append(type.token()).append(' ').append(optional(message));
        }
    }

    /** Shared editor/serializer for {@code error} and {@code log}: a message plus nine
     *  parameter slots. The message may reference params via {@code [[1]}..{@code [[9]};
     *  unused non-null params are appended after it. */
    public abstract static class MessageCard extends AssertCard{
        public final String opcode;
        public final boolean hasLevel;
        public Log.LogLevel level = Log.LogLevel.info;
        public String[] params = new String[10];

        public MessageCard(String opcode, boolean hasLevel, String defaultTemplate){
            this.opcode = opcode;
            this.hasLevel = hasLevel;
            params[0] = "\"" + defaultTemplate + " at #[[1]\"";
            params[1] = "@counter";
            for(int i = 2; i < params.length; i++) params[i] = "null";
        }

        @Override
        public void build(Table table){
            table.add(text("asserts.message", "message")).padLeft(4);
            if(hasLevel){
                table.button(b -> {
                    b.add(level.name());
                    b.clicked(() -> showSelect(b, levels, level, o -> {
                        level = o;
                        build(table);
                    }));
                }, Styles.logict, () -> {}).size(80f, 40f).pad(4f).color(table.color);
            }
            field(table, params[0], s -> params[0] = s).width(0f).growX().pad(2f);
            table.row();
            for(int i = 1; i < params.length; i++){
                final int index = i;
                table.add("p" + i).padLeft(4).color(table.color);
                field(table, params[index], s -> params[index] = s).width(110f).pad(2f);
                if(i % 5 == 0) table.row();
            }
        }

        protected LVar[] buildVars(LAssembler builder){
            LVar[] vars = new LVar[params.length];
            for(int i = 0; i < params.length; i++) vars[i] = builder.var(params[i]);
            return vars;
        }

        @Override
        public void write(StringBuilder out){
            out.append(opcode).append(' ');
            if(hasLevel) out.append(level.name()).append(' ');
            for(int i = 0; i < params.length; i++){
                out.append(optional(params[i]));
                if(i < params.length - 1) out.append(' ');
            }
        }

        protected void readTokens(String[] tokens){
            int i = 1;
            if(hasLevel) level = parseEnum(Log.LogLevel.class, tokens[i++], opcode + " level");
            for(int j = 0; j < params.length; j++) params[j] = optionalValue(tokens[i++]);
        }
    }

    /** Stops the program on the error line and shows the formatted message. */
    public static class ErrorCard extends MessageCard{
        public static final String opcode = "error";

        public ErrorCard(){
            super(opcode, false, "Runtime error");
        }

        @Override public String name(){ return text("asserts.error.card", "Error"); }
        @Override public String typeName(){ return "Error"; }

        @Override
        public LInstruction build(LAssembler builder){
            return new logicsugar.assist.AssertInstructions.ErrorI(buildVars(builder));
        }
    }

    /** Writes a formatted message into the game log file. */
    public static class LogCard extends MessageCard{
        public static final String opcode = "log";

        public LogCard(){
            super(opcode, true, "Logging a message");
        }

        @Override public String name(){ return text("asserts.log.card", "Log"); }
        @Override public String typeName(){ return "Log"; }

        @Override
        public LInstruction build(LAssembler builder){
            return new logicsugar.assist.AssertInstructions.LogI(level, buildVars(builder));
        }
    }

    /** Pauses the game at the breakpoint when its condition holds (all accumulators are
     *  reset; unpausing continues execution). */
    public static class BreakpointCard extends AssertCard{
        public static final String opcode = "breakpoint";
        public ConditionOp op = ConditionOp.always;
        public String value = "x", compare = "false";

        @Override
        public void build(Table table){
            table.add(text("asserts.trigger", "trigger")).padLeft(4);
            addCompactOp(table, op, o -> {
                op = o;
                build(table);
            }, value, s -> value = s, compare, s -> compare = s);
        }

        @Override public String name(){ return text("asserts.breakpoint.card", "Breakpoint"); }
        @Override public String typeName(){ return "Breakpoint"; }

        @Override
        public LInstruction build(LAssembler builder){
            return new logicsugar.assist.AssertInstructions.BreakpointI(op, builder.var(value), builder.var(compare));
        }

        @Override
        public void write(StringBuilder out){
            out.append(opcode).append(' ').append(op.name()).append(' ')
                .append(optional(value)).append(' ').append(optional(compare));
        }
    }

    private static final Log.LogLevel[] levels = {
        Log.LogLevel.err, Log.LogLevel.warn, Log.LogLevel.info, Log.LogLevel.debug,
    };

    // ===== parsers =====

    public static LStatement parseAssertBounds(String[] tokens){
        AssertBoundsCard result = new AssertBoundsCard();
        result.type = parseEnum(AssertionType.class, tokens[1], AssertBoundsCard.opcode + " type");
        result.multiple = optionalValue(tokens[2]);
        result.min = optionalValue(tokens[3]);
        result.opMin = parseEnum(AssertOp.class, tokens[4], AssertBoundsCard.opcode + " min op");
        result.value = optionalValue(tokens[5]);
        result.opMax = parseEnum(AssertOp.class, tokens[6], AssertBoundsCard.opcode + " max op");
        result.max = optionalValue(tokens[7]);
        result.message = optionalValue(tokens[8]);
        return result;
    }

    public static LStatement parseAssertEquals(String[] tokens){
        AssertEqualsCard result = new AssertEqualsCard();
        result.expected = optionalValue(tokens[1]);
        result.actual = optionalValue(tokens[2]);
        result.message = optionalValue(tokens[3]);
        return result;
    }

    public static LStatement parseAssertFlush(String[] tokens){
        AssertFlushCard result = new AssertFlushCard();
        result.position = optionalValue(tokens[1]);
        return result;
    }

    public static LStatement parseAssertPrints(String[] tokens){
        AssertPrintsCard result = new AssertPrintsCard();
        result.position = optionalValue(tokens[1]);
        result.expected = optionalValue(tokens[2]);
        result.message = optionalValue(tokens[3]);
        return result;
    }

    public static LStatement parseAssertType(String[] tokens){
        AssertTypeCard result = new AssertTypeCard();
        result.value = optionalValue(tokens[1]);
        result.type = AssertDataType.parse(tokens[2]);
        result.message = optionalValue(tokens[3]);
        return result;
    }

    public static LStatement parseError(String[] tokens){
        ErrorCard result = new ErrorCard();
        result.readTokens(tokens);
        return result;
    }

    public static LStatement parseLog(String[] tokens){
        LogCard result = new LogCard();
        result.readTokens(tokens);
        return result;
    }

    public static LStatement parseBreakpoint(String[] tokens){
        BreakpointCard result = new BreakpointCard();
        result.op = parseEnum(ConditionOp.class, tokens[1], BreakpointCard.opcode + " op");
        result.value = optionalValue(tokens[2]);
        result.compare = optionalValue(tokens[3]);
        return result;
    }

    // ===== shared helpers =====

    private static <T extends Enum<T>> T parseEnum(Class<T> type, String token, String what){
        try{
            return Enum.valueOf(type, token);
        }catch(IllegalArgumentException e){
            throw new IllegalArgumentException("Invalid " + what + ": '" + token + "'");
        }
    }

    private static String optionalValue(String value){
        return value == null || value.equals("~") ? "" : value;
    }

    /** Runtime data types an {@link AssertTypeCard} can assert, mirroring the
     *  classification the game itself shows for logic variables. {@code none} is spelled
     *  {@code null} on the wire ({@code null} is a reserved word in Java). */
    public enum AssertDataType{
        number("number"), string("string"), content("content"), building("building"),
        unit("unit"), team("team"), none("null"),
        ;

        public static final AssertDataType[] all = values();

        private final String token;

        AssertDataType(String token){
            this.token = token;
        }

        /** The wire-format token; the type select button shows the localized label. */
        public String token(){
            return token;
        }

        /** Localized label for the type select button (falls back to the wire token). */
        public String display(){
            return Core.bundle.get("logicsugar.asserts.datatype." + token, token);
        }

        public boolean matches(LVar var){
            if(this == none) return var.isobj && var.objval == null;
            if(this == number) return !var.isobj;
            if(!var.isobj) return false;
            Object o = var.objval;
            return switch(this){
                case string -> o instanceof String;
                case content -> o instanceof Content;
                case building -> o instanceof Building;
                case unit -> o instanceof Unit;
                case team -> o instanceof Team;
                default -> false;
            };
        }

        /** The classification a failure message shows for the actual value — the same
         *  taxonomy the game's own variable panel uses (number/null/string/content/
         *  building/unit/team/enum/unknown), so "expected unit, got null" reads exactly
         *  like the editor would describe the variable. */
        public static String actualType(LVar var){
            if(!var.isobj) return "number";
            if(var.objval == null) return "null";
            if(var.objval instanceof String) return "string";
            if(var.objval instanceof Content) return "content";
            if(var.objval instanceof Building) return "building";
            if(var.objval instanceof Unit) return "unit";
            if(var.objval instanceof Team) return "team";
            if(var.objval instanceof Enum<?>) return "enum";
            return "unknown";
        }

        public static AssertDataType parse(String token){
            for(AssertDataType type : all){
                if(type.token.equals(token)) return type;
            }
            throw new IllegalArgumentException("Invalid asserttype data type: '" + token + "'");
        }
    }

    public enum AssertionType{
        any(num -> true, obj -> true),
        notNull(num -> true, java.util.Objects::nonNull),
        decimal(num -> true),
        integer(num -> num == (long)num),
        multiple(num -> num == (long)num),
        ;

        public static final AssertionType[] all = values();
        public final AssertionTypeObjLambda objFunction;
        public final AssertionTypeLambda function;

        AssertionType(AssertionTypeLambda function){
            this(function, obj -> false);
        }

        AssertionType(AssertionTypeLambda function, AssertionTypeObjLambda objFunction){
            this.function = function;
            this.objFunction = objFunction;
        }

        public interface AssertionTypeObjLambda{
            boolean get(Object obj);
        }

        public interface AssertionTypeLambda{
            boolean get(double val);
        }
    }

    public enum AssertOp{
        lessThan("<", (a, b) -> a < b),
        lessThanEq("<=", (a, b) -> a <= b),
        ;

        public static final AssertOp[] all = values();

        public final AssertOpLambda function;
        public final String symbol;

        AssertOp(String symbol, AssertOpLambda function){
            this.symbol = symbol;
            this.function = function;
        }

        @Override
        public String toString(){
            return symbol;
        }

        public interface AssertOpLambda{
            boolean get(double a, double b);
        }
    }
}
