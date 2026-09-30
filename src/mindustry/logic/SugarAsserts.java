package mindustry.logic;

import arc.Core;
import arc.func.Cons;
import arc.func.Func;
import arc.func.Prov;
import arc.graphics.Color;
import arc.scene.ui.Label;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import mindustry.ai.UnitCommand;
import mindustry.ai.UnitStance;
import mindustry.ctype.Content;
import mindustry.entities.bullet.BulletType;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Icon;
import mindustry.gen.LogicIO;
import mindustry.gen.Unit;
import mindustry.logic.LExecutor.LInstruction;
import mindustry.logic.SugarStatements.SugarStatement;
import mindustry.type.Item;
import mindustry.type.Liquid;
import mindustry.type.StatusEffect;
import mindustry.type.UnitType;
import mindustry.type.Weather;
import mindustry.ui.Styles;
import mindustry.world.Block;
import mindustry.world.blocks.logic.CanvasBlock;
import mindustry.world.blocks.logic.LogicBlock;
import mindustry.world.blocks.logic.LogicDisplay;
import mindustry.world.blocks.logic.MemoryBlock;
import mindustry.world.blocks.logic.MessageBlock;

import java.util.Arrays;

/**
 * Assertion statement set for runtime checks and debugging, ported from the upstream
 * MlogAssertions mod (cardillan/mlogassertions, currently v0.11.1) with its wire format, so
 * programs compiled in debug mode run identically under either mod and Mindcode-generated
 * code round-trips through the editor. The one extension is {@code asserttype}'s null
 * type — see {@link AssertTypeCard}.
 *
 * <p>Synced from v0.8.2 to v0.11.1: the generic {@code assert} card, the failure messages
 * that name the compared values, {@code asserttype}'s expanded data-type taxonomy and its
 * {@code <type> <value>} token order (v0.10; the older {@code <value> <type>} text is still
 * read), {@code assertprints}' buffer truncation, and the {@code {1}}/{@code {name}}
 * message placeholders (the older {@code [[1]} form still renders). Upstream's snapshot
 * instruction and the Vars/Memory/Properties screens are not ported — see
 * {@code docs/architecture.md}.</p>
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

    /** 变量/表达式字段的设计宽度。
     *
     *  <p>{@code Cell.width()} 会乘 {@code Scl}（200% UI 下 130 → 260 像素），而
     *  {@code TextField.getPrefWidth()} 在 arc 里是常量 150 —— 不给宽度就按 150px 排，
     *  给了宽度才按指定值排。同时字体在 200% 下也是两倍宽（≈23px/字符），所以 110 这种
     *  宽度只能显示 7 个 ASCII 字符，{@code @counter} 会被截断成 {@code @counte}
     *  （2026-09-20 用户截图实测）。</p> */
    private static final float VAR_W = 130f;

    /** 数值/常量字段（上下界、倍数）。 */
    private static final float NUM_W = 90f;

    /** 消息参数槽 p1..p9。 */
    private static final float PARAM_W = 120f;

    private SugarAsserts(){}

    /** Registers every assertion token parser into {@code LAssembler.customParsers}.
     *  Called from {@link SugarStatements#installParsers()} so all entry points (mod init,
     *  decompiler preflight, self-tests) share one registration sequence. Idempotent per
     *  class loader. */
    public static void installParsers(){
        if(parsersInstalled) return;
        parsersInstalled = true;

        register(AssertConditionCard::new, AssertConditionCard.opcode, SugarAsserts::parseAssert);
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

    /** Card titles follow the same localization toggle as the control-flow cards
     *  ({@link SugarStatements#cardsLocalized()}); non-title labels stay always localized. */
    private static String cardText(String key, String fallback){
        return SugarStatements.cardText(key, fallback);
    }

    /** The assertion opcodes this class owns; the compiler and the decompiler use this to
     *  decide whether the assert-emit dimension matters for a program. */
    public static final String[] opcodes = {
        AssertConditionCard.opcode, AssertBoundsCard.opcode, AssertEqualsCard.opcode, AssertFlushCard.opcode,
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

        /**
         * 把一行控件做成**独立子表格**，并为该子表格和其中每个单元格显式指定左对齐。
         *
         * <p>这是本卡族排版唯一的正确做法，原因是 arc 的 {@code Table} 与 libGDX 原版不同：</p>
         * <ul>
         *   <li>{@code Cell.clear()} 把 {@code align} 置 0（既非 left 也非 right），而
         *       {@code Table.layout()} 的 {@code elementX} 是「left / right / 否则居中」三目
         *       ⇒ **控件比列窄时会被居中**，不是左对齐。</li>
         *   <li>一个 {@code growX} 单元格（如末尾的「消息」输入框）会让**整列**吃掉所有剩余
         *       宽度；同列中较窄的固定宽字段随即被居中推到列中央，其后的列整体右推。</li>
         * </ul>
         *
         * <p>实测（2026-09-20 用户截图）：断言边界卡第一行的 {@code value} 宽度 85 完全正确，
         * 但起点距「校验值」标签 483px —— 因为同一列被第二行的消息框撑宽。把每行各自成表后，
         * 行与行的列空间互不影响。</p>
         *
         * @param grow true 时该行作为「铺满卡片剩余宽度」的行（末尾消息行用）；
         *             此时子表格自身 {@code fillX}，内部 {@code growX} 字段才能撑到卡边。
         * @param tint 卡片当前配色（LStatement.field 会用 {@code 子表格.color} 给输入框着色，
         *             而 {@code table.table()} 新建的子表格颜色是白色，必须先同步过来）
         */
        protected Cell<Table> line(Table table, Color tint, Cons<Table> content, boolean grow){
            Cell<Table> cell = table.table(row -> {
                row.setColor(tint);
                content.get(row);
            }).left();
            if(grow) cell.growX();
            // arc 的 Cell.row() 返回 void（不能链式），所以直接结束外层表格的这一行。
            table.row();
            return cell;
        }

        /** 行内标签。显式 {@code left()}：见 {@link #line} 对 arc 默认居中的说明。 */
        protected Cell<Label> tag(Table row, String key, String fallback){
            return row.add(text(key, fallback)).padLeft(4).padRight(2).left();
        }

        /** 行内输入框，宽度显式指定。{@code field()} 已按子表格颜色着色，无需再传。 */
        protected Cell<TextField> input(Table row, String value, Cons<String> setter, float width){
            return field(row, value, setter).width(width).pad(2f);
        }

        /** 末尾的「消息」行：标签 + 铺满剩余宽度的输入框。留空表示不写自定义消息，失败时
         *  按比较值生成默认消息（上游 v0.11 语义），所以输入框用 hint 文案说明占位符写法；
         *  hint 只影响显示，不写进卡片。 */
        protected void messageLine(Table table, Color tint, String value, Cons<String> setter){
            line(table, tint, row -> {
                tag(row, "asserts.message", "message");
                Cell<TextField> cell = field(row, value, setter).width(0f).growX().pad(2f);
                if(cell.get() != null){
                    cell.get().setMessageText(text("asserts.messageHint", "optional; {1}, {2}\u2026 are replaced by the compared values"));
                }
            }, true);
        }
    }

    /** The generic {@code assert} instruction: halts the program when the condition is
     *  false, exactly like a {@code jump} that must not be taken. Ported from upstream
     *  v0.11.0. The message field is optional; an empty one falls back to the localized
     *  "Assertion [value] [op] [compare] failed." text. Inside a custom message,
     *  {@code {1}}/{@code {2}}/{@code {3}} stand for the value, the comparison and the
     *  operator (upstream's numbering). */
    public static class AssertConditionCard extends AssertCard{
        public static final String opcode = "assert";
        public ConditionOp op = ConditionOp.equal;
        public String value = "x", compare = "false";
        public String message = "";

        @Override
        public void build(Table table){
            table.clearChildren();
            Color tint = table.color;
            line(table, tint, row -> {
                tag(row, "asserts.assert", "assert");
                addCompactOp(row, op, o -> {
                    op = o;
                    build(table);
                }, value, s -> value = s, compare, s -> compare = s);
            }, false);
            messageLine(table, tint, message, s -> message = s);
        }

        @Override public String name(){ return cardText("asserts.assert.card", "Assert"); }
        @Override public String typeName(){ return "Assert"; }

        @Override
        public LInstruction build(LAssembler builder){
            return new logicsugar.assist.AssertInstructions.AssertI(op, builder.var(value),
                builder.var(compare), builder.var(message));
        }

        @Override
        public void write(StringBuilder out){
            out.append(opcode).append(' ').append(op.name()).append(' ')
                .append(optional(value)).append(' ').append(optional(compare)).append(' ')
                .append(optional(message));
        }
    }

    /** Value range/type check for an index or general numeric variable. */
    public static class AssertBoundsCard extends AssertCard{
        public static final String opcode = "assertBounds";
        /** The only operators a bounds check accepts (upstream restricts the game's
         *  {@link ConditionOp} the same way); anything else is a parse error. */
        static final ConditionOp[] ops = {ConditionOp.lessThan, ConditionOp.lessThanEq};
        public AssertionType type = AssertionType.integer;
        public String multiple = "2";
        public String min = "0";
        public ConditionOp opMin = ConditionOp.lessThanEq;
        public String value = "index";
        public ConditionOp opMax = ConditionOp.lessThanEq;
        public String max = "10";
        /** 空消息 = 用比较值生成默认文本（上游 v0.11 语义）；写了消息则原样显示，
         *  可用 {@code {1}}/{@code {2}}/{@code {3}} 引用上界/校验值/下界。 */
        public String message = "";

        @Override
        public void build(Table table){
            table.clearChildren();
            Color tint = table.color;
            // 一行放不下时不能靠换行抢救（本卡族 useWrapping()==false，是普通 Table，
            // 超出卡片宽度只会溢出），所以这里的宽度预算要留足：整行 ≈ 800 设计像素。
            line(table, tint, row -> {
                tag(row, "asserts.value", "value of");
                input(row, value, s -> value = s, VAR_W);
                typeButton(row, table);
                if(type == AssertionType.multiple){
                    tag(row, "asserts.of", "of");
                    input(row, multiple, s -> multiple = s, NUM_W);
                }
                tag(row, "asserts.bounds", "bounds");
                input(row, min, s -> min = s, NUM_W);
                opButton(row, opMin, o -> {
                    opMin = o;
                    build(table);
                });
                row.add(text("asserts.and", "..")).pad(2f).left();
                opButton(row, opMax, o -> {
                    opMax = o;
                    build(table);
                });
                input(row, max, s -> max = s, NUM_W);
            }, false);
            messageLine(table, tint, message, s -> message = s);
        }

        private void typeButton(Table row, Table owner){
            row.button(b -> {
                b.add(type.display());
                b.clicked(() -> showSelect(b, AssertionType.all, type, o -> {
                    type = o;
                    build(owner);
                }));
            }, Styles.logict, () -> {}).size(96f, 40f).pad(4f).color(row.color).left();
        }

        private void opButton(Table row, ConditionOp op, Cons<ConditionOp> setter){
            row.button(b -> {
                b.add(op.symbol);
                b.clicked(() -> showSelect(b, ops, op, setter));
            }, Styles.logict, () -> {}).size(48f, 40f).pad(4f).color(row.color).left();
        }

        @Override public String name(){ return cardText("asserts.bounds.card", "Assert Bounds"); }
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
        public String message = "";

        @Override
        public void build(Table table){
            table.clearChildren();
            Color tint = table.color;
            line(table, tint, row -> {
                tag(row, "asserts.expected", "expected");
                input(row, expected, s -> expected = s, VAR_W);
                tag(row, "asserts.actual", "actual");
                input(row, actual, s -> actual = s, VAR_W);
            }, false);
            messageLine(table, tint, message, s -> message = s);
        }

        @Override public String name(){ return cardText("asserts.equals.card", "Assert Equals"); }
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
            table.clearChildren();
            line(table, table.color, row -> {
                tag(row, "asserts.position", "position");
                input(row, position, s -> position = s, VAR_W);
            }, false);
        }

        @Override public String name(){ return cardText("asserts.flush.card", "Assert Flush"); }
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
        public String message = "";

        @Override
        public void build(Table table){
            table.clearChildren();
            Color tint = table.color;
            line(table, tint, row -> {
                tag(row, "asserts.position", "position");
                input(row, position, s -> position = s, VAR_W);
                tag(row, "asserts.expected", "expected");
                input(row, expected, s -> expected = s, VAR_W);
            }, false);
            messageLine(table, tint, message, s -> message = s);
        }

        @Override public String name(){ return cardText("asserts.prints.card", "Assert Prints"); }
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
     *
     *  <p>Upstream MlogAssertions added its own {@code asserttype} with the same opcode;
     *  v0.10 both widened the type taxonomy and swapped the token order to
     *  {@code <type> <value>}, which is what this card writes (upstream v0.8.2/0.9 and
     *  LogicSugar ≤5.5 wrote {@code <value> <type>} — {@link SugarAsserts#parseAssertType}
     *  still reads that form). LogicSugar additionally offers {@code none} ("null" on the
     *  wire), which upstream cannot name with {@code asserttype} at all.</p> */
    public static class AssertTypeCard extends AssertCard{
        public static final String opcode = "asserttype";
        public AssertionDataType type = AssertionDataType.number;
        public String value = "value";
        public String message = "";

        @Override
        public void build(Table table){
            table.clearChildren();
            Color tint = table.color;
            line(table, tint, row -> {
                tag(row, "asserts.value", "value");
                input(row, value, s -> value = s, VAR_W);
                tag(row, "asserts.istype", "is of type");
                row.button(b -> {
                    b.add(type.display());
                    b.clicked(() -> showSelect(b, AssertionDataType.all, type, o -> {
                        type = o;
                        build(table);
                    }));
                }, Styles.logict, () -> {}).size(140f, 40f).pad(4f).color(row.color).left();
            }, false);
            messageLine(table, tint, message, s -> message = s);
        }

        @Override public String name(){ return cardText("asserts.type.card", "Assert Type"); }
        @Override public String typeName(){ return "AssertType"; }

        @Override
        public LInstruction build(LAssembler builder){
            return new logicsugar.assist.AssertInstructions.AssertTypeI(type, builder.var(value), builder.var(message));
        }

        @Override
        public void write(StringBuilder out){
            out.append(opcode).append(' ').append(type.token()).append(' ')
                .append(optional(value)).append(' ').append(optional(message));
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
            // Upstream v0.11.1 makes the counter a variable reference ({@code {@counter}}
            // renders as the instruction index) and leaves p1..p9 free for user params.
            // LogicSugar ≤5.5 used "[[1]" with p1 bound to @counter; that form still
            // renders (legacy placeholder support in AssertInstructions).
            params[0] = "\"" + defaultTemplate + " at #{@counter}.\"";
            for(int i = 1; i < params.length; i++) params[i] = "null";
        }

        @Override
        public void build(Table table){
            table.clearChildren();
            Color tint = table.color;
            // 消息行独立成表：它的模板输入框是 growX，若与下面的 p1..p9 共用列，会把 p 列
            // 全部撑开并把 p1 的输入框居中（2026-09-20 用户截图里的 p 网格错位即此）。
            line(table, tint, row -> {
                tag(row, "asserts.message", "message");
                if(hasLevel){
                    row.button(b -> {
                        b.add(levelName(level));
                        b.clicked(() -> showSelect(b, levels, level, o -> {
                            level = o;
                            build(table);
                        }));
                    }, Styles.logict, () -> {}).size(80f, 40f).pad(4f).color(row.color).left();
                }
                field(row, params[0], s -> params[0] = s).width(0f).growX().pad(2f);
            }, true);
            // p1..p9 再自成一表：五行一组、共两行，列宽在表内共享，表格之间互不干扰。
            line(table, tint, row -> {
                for(int i = 1; i < params.length; i++){
                    final int index = i;
                    row.add("p" + i).padLeft(4).padRight(2).color(row.color).left();
                    input(row, params[index], s -> params[index] = s, PARAM_W);
                    if(i % 5 == 0) row.row();
                }
            }, false);
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

        @Override public String name(){ return cardText("asserts.error.card", "Error"); }
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

        @Override public String name(){ return cardText("asserts.log.card", "Log"); }
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
            table.clearChildren();
            Color tint = table.color;
            line(table, tint, row -> {
                tag(row, "asserts.trigger", "trigger");
                addCompactOp(row, op, o -> {
                    op = o;
                    build(table);
                }, value, s -> value = s, compare, s -> compare = s);
            }, false);
        }

        @Override public String name(){ return cardText("asserts.breakpoint.card", "Breakpoint"); }
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

    /** 日志级别按钮的显示名。与 {@link AssertionDataType#display()} 同一约定：只影响界面，
     *  卡片序列化出去的分级 token（{@code err}/{@code warn}/…）保持不变。 */
    private static String levelName(Log.LogLevel level){
        return Core.bundle.get("logicsugar.asserts.level." + level.name(), level.name());
    }

    // ===== parsers =====

    public static LStatement parseAssert(String[] tokens){
        AssertConditionCard result = new AssertConditionCard();
        result.op = parseEnum(ConditionOp.class, tokens[1], AssertConditionCard.opcode + " op");
        result.value = optionalValue(tokens[2]);
        result.compare = optionalValue(tokens[3]);
        result.message = optionalValue(tokens[4]);
        return result;
    }

    public static LStatement parseAssertBounds(String[] tokens){
        AssertBoundsCard result = new AssertBoundsCard();
        result.type = parseEnum(AssertionType.class, tokens[1], AssertBoundsCard.opcode + " type");
        result.multiple = optionalValue(tokens[2]);
        result.min = optionalValue(tokens[3]);
        result.opMin = parseBoundsOp(tokens[4]);
        result.value = optionalValue(tokens[5]);
        result.opMax = parseBoundsOp(tokens[6]);
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
        String first = tokens[1], second = tokens[2];
        // Upstream ≥v0.10 writes "<type> <value>"; upstream ≤0.9 and LogicSugar ≤5.5 wrote
        // "<value> <type>". A token that is not a data-type name can only be the value, so
        // the older line is still read. When both look like type names the current order
        // wins (that is the only order this card writes; a variable literally named after a
        // type in the legacy order is the one ambiguous case).
        boolean firstIsType = AssertionDataType.isToken(first), secondIsType = AssertionDataType.isToken(second);
        if(secondIsType && !firstIsType){
            result.type = AssertionDataType.parse(second);
            result.value = optionalValue(first);
        }else{
            result.type = AssertionDataType.parse(first);
            result.value = optionalValue(second);
        }
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

    /** A bounds operator, restricted to the two the instruction understands (a wider
     *  {@link ConditionOp} token in a saved program is a corrupt line, not a valid card). */
    private static ConditionOp parseBoundsOp(String token){
        for(ConditionOp op : AssertBoundsCard.ops){
            if(op.name().equals(token)) return op;
        }
        throw new IllegalArgumentException("Invalid " + AssertBoundsCard.opcode + " operator: '" + token + "'");
    }

    private static String optionalValue(String value){
        return value == null || value.equals("~") ? "" : value;
    }

    /** Runtime data types an {@link AssertTypeCard} can assert, mirroring the classification
     *  the game itself shows for logic variables.
     *
     *  <p>Ported from upstream MlogAssertions v0.11.1 ({@code AssertionDataType}): what was a
     *  flat list of six names now has per-content and per-building sub-types plus the
     *  property/readable/writable/senseable interface types. A general kind matches a
     *  reference to any instance of it, the numbered sub-types narrow that down. The wire
     *  token is {@link #name()} except for {@code none}, spelled {@code null} ({@code null} is
     *  a reserved word in Java); {@code none} is a LogicSugar extension — upstream cannot
     *  assert the null value with {@code asserttype}.</p> */
    public enum AssertionDataType{
        // Basic types
        number(0),
        none(0),
        string(0, String.class),

        // General and specific contents
        content(0, Content.class),
        item(1, Item.class),
        block(1, Block.class),
        bulletType(1, BulletType.class),
        liquid(1, Liquid.class),
        statusEffect(1, StatusEffect.class),
        unitType(1, UnitType.class),
        weather(1, Weather.class),
        team(1, Team.class),
        unitCommand(1, UnitCommand.class),
        unitStance(1, UnitStance.class),

        // Any unit
        unit(0, Unit.class),

        // General and specific buildings
        building(0, Building.class),
        processor(1, LogicBlock.LogicBuild.class),
        memory(1, MemoryBlock.MemoryBuild.class),
        message(1, MessageBlock.MessageBuild.class),
        display(1, LogicDisplay.LogicDisplayBuild.class),
        canvas(1, CanvasBlock.CanvasBuild.class),

        // Other special values
        property(0, LAccess.class),
        readable(0, LReadable.class),
        writable(0, LWritable.class),
        senseable(0, Senseable.class),
        ;

        public static final AssertionDataType[] all = values();
        /** Most specific first: {@link #actualType} reports the narrowest match, so an Item
         *  is named {@code item} and not merely {@code content}. */
        public static final AssertionDataType[] sorted;

        static{
            sorted = values();
            Arrays.sort(sorted, (a, b) -> Integer.compare(-a.level, -b.level));
        }

        private final int level;
        private final Class<?> objectClass;

        AssertionDataType(int level, Class<?> objectClass){
            this.level = level;
            this.objectClass = objectClass;
        }

        AssertionDataType(int level){
            this(level, null);
        }

        /** The wire-format token; the type select button shows the localized label. */
        public String token(){
            return this == none ? "null" : name();
        }

        /** Localized label for the type select button (falls back to the wire token). */
        public String display(){
            return Core.bundle.get("logicsugar.asserts.datatype." + token(), token());
        }

        public boolean matches(LVar var){
            if(this == none) return var.isobj && var.objval == null;
            if(this == number) return !var.isobj;
            return var.isobj && objectClass.isInstance(var.objval);
        }

        /** The classification a failure message shows for the actual value — the same
         *  taxonomy the game's own variable panel uses, so "expected unit, got null" reads
         *  exactly like the editor would describe the variable. */
        public static String actualType(LVar var){
            if(!var.isobj) return "number";
            if(var.objval == null) return "null";
            for(AssertionDataType type : sorted){
                if(type.objectClass != null && type.objectClass.isInstance(var.objval)) return type.name();
            }
            return "unknown";
        }

        /** Whether {@code token} names a data type; used to tell the two {@code asserttype}
         *  token orders apart (see {@link SugarAsserts#parseAssertType}). */
        public static boolean isToken(String token){
            for(AssertionDataType type : all){
                if(type.token().equals(token)) return true;
            }
            return false;
        }

        public static AssertionDataType parse(String token){
            for(AssertionDataType type : all){
                if(type.token().equals(token)) return type;
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

        /** 选择按钮的本地化显示名（{@code logicsugar.asserts.asserttype.<name>}）。
         *  只用于界面：卡片写出的 token 仍是 {@link #name()}，存档格式不受影响。
         *  此前按钮直接显示 {@code type.name()}，在同一张卡里与已经本地化的「数值」等
         *  数据型按钮自相矛盾。 */
        public String display(){
            return Core.bundle.get("logicsugar.asserts.asserttype." + name(), name());
        }

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

}
