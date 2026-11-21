package mindustry.logic;

import arc.scene.ui.layout.Table;
import arc.util.Log;
import logicsugar.assist.expr.ExprCompiler;
import mindustry.logic.LExecutor.LInstruction;
import mindustry.logic.LExecutor.NoopI;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Wire-format compatibility layer for debug statements that the game itself does not know:
 * the Mlog Dev Tools (MDT, formerly MlogAssertions) opcode family
 * {@code assert assertBounds assertequals assertflush assertprints asserttype error log
 * systemlog breakpoint snapshot profile restart}.
 *
 * <p><b>Why this exists and nothing more.</b> LogicSugar used to own these opcodes as real
 * cards with runtime instructions ({@code SugarAsserts} + {@code AssertInstructions}). That
 * duplicated MDT's job, so this stage hands the runtime behaviour back to MDT: what remains
 * here is purely a <em>text</em> layer. A parsed line reproduces itself verbatim on
 * {@code write()} and assembles to a {@link NoopI} — never {@code null}, because
 * {@code LAssembler} drops null instructions and every following jump target would shift.
 * There is no palette card ({@link #installParsers()} adds parsers only, never
 * {@code LogicIO.allStatements}) and no runtime behaviour.</p>
 *
 * <p><b>Registration order matters.</b> When MDT is installed it owns the same opcodes and
 * must win, so {@link #installParsers()} skips every opcode already present in
 * {@link LAssembler#customParsers} and is deliberately <em>not</em> called from
 * {@link SugarStatements#installParsers()} (that runs during mod {@code init()}, before other
 * mods register theirs). The mod calls it from the post-{@code ClientLoadEvent} pass instead —
 * the same {@code Core.app.post(...)} step that installs the editor. The decompiler's preflight
 * ({@code SugarDecompiler.installSugarParsers}) calls it too, since recovered sugar can now keep
 * one of these lines and the recompilation gate must parse it; that path only runs on a user
 * action, i.e. after every mod has registered. Once an opcode is taken ours, it stays ours:
 * {@code LAssembler.customParsers} is first-wins.</p>
 *
 * <p><b>Saved programs stay parseable.</b> An {@code assert}/{@code log}/… line written by a
 * debug build is ordinary text to this layer, so an emitted program still opens, round-trips
 * and re-saves; the compiler's strip/emit branch decides whether the line survives into the
 * saved mlog (see {@code SugarFunctions.lower}). That branch keys on the <em>written text</em>
 * ({@link #isDebugStatement}), not on our {@link Line} class: when MDT is installed it owns the
 * opcodes and parses those lines into its own statement classes, and strip must still remove
 * them. Nothing here changes the multiplayer rule: {@code AssertEmit} still forces
 * {@code strip} while a network session is active.</p>
 */
public final class SugarDebugLines{
    private SugarDebugLines(){}

    private static final String logTag = "[LogicSugar/debug]";

    /** The opcode family this layer keeps readable. Order is the registration order. */
    public static final String[] opcodes = {
        "assert", "assertBounds", "assertequals", "assertflush", "assertprints", "asserttype",
        "error", "log", "systemlog", "breakpoint", "snapshot", "profile", "restart"
    };

    private static boolean parsersInstalled;

    /**
     * Registers a passthrough parser for every opcode MDT has not claimed yet.
     *
     * <p>First-wins: an opcode already in {@link LAssembler#customParsers} (MDT loaded before
     * us, or a previous call) is left untouched, so MDT's real cards keep working. Idempotent
     * per class loader. No palette entry is added on purpose — this is not a card family.</p>
     */
    public static void installParsers(){
        if(parsersInstalled) return;
        parsersInstalled = true;

        for(String opcode : opcodes){
            if(LAssembler.customParsers.containsKey(opcode)) continue;
            LAssembler.customParsers.put(opcode, SugarDebugLines::parse);
        }
    }

    /** Whether watching this text for debug lines is needed at all (compiler/decompiler
     *  pre-scan). Replaces the deleted {@code SugarAsserts.containsAssertStatements}: same
     *  cheap line-prefix match over {@link #opcodes}. */
    public static boolean containsDebugLine(String text){
        if(text == null) return false;
        for(String line : text.replace("\r\n", "\n").split("\n", -1)){
            if(startsWithDebugOpcode(line)) return true;
        }
        return false;
    }

    /** Whether one written line (not a whole program) begins with one of {@link #opcodes}.
     *  The single-line half of {@link #containsDebugLine}, shared by the text pre-scan and the
     *  statement-level check below so the two can never disagree about which spelling counts. */
    public static boolean startsWithDebugOpcode(String line){
        if(line == null) return false;
        String bare = line.trim();
        for(String opcode : opcodes){
            if(bare.startsWith(opcode + " ") || bare.equals(opcode)) return true;
        }
        return false;
    }

    /**
     * The wire text of one statement, or {@code null} when its {@code write()} throws.
     *
     * <p>This is the only handle on a debug statement MDT owns: when MDT is installed it removes
     * our parsers for every opcode it knows, so the statement in the program is <em>MDT's</em>
     * class, not {@link Line}. Rendering it is the same text contract the wire format already
     * relies on.</p>
     */
    public static String statementText(LStatement statement){
        if(statement == null) return null;
        if(statement instanceof Line line) return line.text();
        StringBuilder text = new StringBuilder();
        try{
            statement.write(text);
        }catch(Throwable t){
            // Not our class: a third-party write() may need editor state. The callers fall back
            // to their old behaviour (normal write path / do not claim the line), never to a
            // silently dropped instruction.
            Log.warn(logTag + " could not render a foreign statement for the debug-line check", t);
            return null;
        }
        return text.toString();
    }

    /**
     * Whether {@code statement} is a debug line, whether it is our own {@link Line} (MDT absent,
     * so this layer parsed it) or MDT's statement class (MDT present, so it owns the opcode).
     *
     * <p>Used by the compiler's {@code containsSugar} pre-check and by the strip/emit branch in
     * {@code SugarFunctions.lower}. Pure text identity: no card type, no MDT dependency.</p>
     */
    public static boolean isDebugStatement(mindustry.logic.LStatement statement){
        return statement instanceof Line || startsWithDebugOpcode(statementText(statement));
    }

    /**
     * Writes one debug statement's wire text into {@code out}; {@code false} when the statement
     * is not a debug line or cannot be rendered.
     *
     * <p>LS's own {@link Line} writes its stored text; a foreign (MDT) statement writes through
     * its own {@code write()}, so the saved bytes stay whatever that mod defines. A render
     * failure is reported by {@link #statementText} and left to the caller: {@code lower} then
     * falls through to the normal write path, preserving the pre-existing exception behaviour
     * instead of dropping the instruction.</p>
     */
    public static boolean writeDebugStatement(LStatement statement, StringBuilder out){
        if(statement == null || out == null) return false;
        String text = statementText(statement);
        if(text == null) return false;
        if(statement instanceof Line || startsWithDebugOpcode(text)){
            out.append(text);
            return true;
        }
        return false;
    }

    // ===== generated bounds checks (LogicSugar's own emitter, KEEP(P4)) =====
    //
    // `ExprCompiler.emitBoundsAssert` (logicsugar.assist.expr) prepends an `assertBounds` line
    // before a non-constant array/matrix read or write in an AssertEmit=emit build. That emitter
    // is LogicSugar's own debug-build feature, NOT the MDT port, and it survives the decoupling.
    // Only the message text identifies such a line: there is no card type any more, and MDT owns
    // the opcode itself. The exact shape below is what the emitter builds:
    //
    //   "array '<name>' index out of bounds (<min>..<max>)"
    //   "matrix '<name>' column out of bounds (<min>..<max>)"
    //   "matrix '<name>' row out of bounds (<min>..<max>)"
    //
    // Matching that whole message (not just the opcode) is what keeps a user-authored
    // `assertBounds` with any other message untouched everywhere.

    private static final Pattern generatedBoundsMessage = Pattern.compile(
        "\"(?:array '[^']+' index|matrix '[^']+' (?:column|row)) out of bounds \\(-?[0-9]+\\.\\.-?[0-9]+\\)\"");

    /** Whether {@code message} is exactly the quoted text {@code ExprCompiler.emitBoundsAssert}
     *  generates (name, kind, separator and both numeric bounds all part of the signature). */
    public static boolean isGeneratedBoundsMessage(String message){
        return message != null && generatedBoundsMessage.matcher(message).matches();
    }

    /** Whether one wire line (text form) is a generated array/matrix bounds check. */
    public static boolean isGeneratedBoundsLine(String text){
        if(text == null) return false;
        String line = text.trim();
        if(!line.startsWith("assertBounds ")) return false;
        int quote = line.indexOf('"');
        return quote > 0 && isGeneratedBoundsMessage(line.substring(quote));
    }

    /** {@link #isGeneratedBoundsLine(String)} for an already tokenized line (the message is the
     *  tail from token 8 on, joined back in case a reader split it). */
    public static boolean isGeneratedBoundsLine(String[] tokens){
        if(tokens == null || tokens.length < 9 || !"assertBounds".equals(tokens[0])) return false;
        StringBuilder message = new StringBuilder();
        for(int i = 8; i < tokens.length; i++){
            if(i > 8) message.append(' ');
            message.append(tokens[i]);
        }
        return isGeneratedBoundsMessage(message.toString());
    }

    /** {@code code} without its generated bounds-check lines, newline shape preserved. */
    public static String stripGeneratedBoundsLines(String code){
        if(code == null) return null;
        String[] lines = code.replace("\r\n", "\n").split("\n", -1);
        StringBuilder result = new StringBuilder(code.length());
        for(int i = 0; i < lines.length; i++){
            if(isGeneratedBoundsLine(lines[i])) continue;
            result.append(lines[i]);
            if(i < lines.length - 1) result.append('\n');
        }
        return result.toString();
    }

    /** Whether {@code code} contains at least one generated bounds-check line. */
    public static boolean containsGeneratedBoundsLine(String code){
        if(code == null) return false;
        for(String line : code.replace("\r\n", "\n").split("\n", -1)){
            if(isGeneratedBoundsLine(line)) return true;
        }
        return false;
    }

    /**
     * A passthrough line built from its wire text, so {@link Line#write} reproduces it
     * byte-identically. Tokens are split on whitespace <em>outside</em> double quotes and keep
     * their quotes (a quoted message stays one token).
     *
     * <p>This is how LogicSugar keeps its own generated {@code assertBounds} check as plain
     * text in the sugar source: an expression card saves as its flattened mlog chain, so the
     * emitted line has to survive save/load on its own. No card type is created, nothing is
     * registered, and MDT's opcode ownership is untouched.</p>
     */
    public static Line fromText(String text){
        return new Line(split(text));
    }

    /** A generated bounds check as text: exactly the line {@code ExprCompiler.emitBoundsAssert}
     *  wrote — same fields, same order, same message. */
    public static Line fromBounds(ExprCompiler.AssertBoundsLine line){
        return fromText(line.toText());
    }

    /** Whitespace split that respects double-quoted tokens ({@code \"} does not close the
     *  string, matching {@code LParser.string()}). */
    private static String[] split(String text){
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean quoted = false, escaped = false;
        for(int i = 0; text != null && i < text.length(); i++){
            char c = text.charAt(i);
            if(quoted){
                token.append(c);
                if(escaped) escaped = false;
                else if(c == '\\') escaped = true;
                else if(c == '"') quoted = false;
                continue;
            }
            if(c == '"'){
                quoted = true;
                token.append(c);
                continue;
            }
            if(Character.isWhitespace(c)){
                if(token.length() > 0){
                    tokens.add(token.toString());
                    token.setLength(0);
                }
                continue;
            }
            token.append(c);
        }
        if(token.length() > 0) tokens.add(token.toString());
        return tokens.toArray(new String[0]);
    }

    /**
     * The number of tokens the line actually wrote, starting at the opcode.
     *
     * <p>{@code LParser} hands every custom parser its reused static 16-slot array and never
     * says how many slots belong to the current line, so the count cannot come from
     * {@code tokens.length}. Each opcode has a fixed shape except the two families whose
     * older form is still stored in existing carriers; those are told apart by what the
     * <em>writer</em> puts in the discriminating slot (the same rule the deleted parsers
     * used, see {@code docs/upstream.md}).</p>
     */
    static int tokenCount(String[] tokens){
        String opcode = tokens[0];
        if("snapshot".equals(opcode)){
            // Current: `snapshot type block steps message` (5). Legacy (≤v0.11.1):
            // `snapshot type block message` (4) — its 3rd payload slot holds the message, so
            // `~`, a quoted string, the `null` placeholder or empty can only be the legacy form.
            return legacyTextSlot(tokens, 3) ? 4 : 5;
        }
        if("log".equals(opcode)){
            // Current (MDT v0.12): `log level category msg p1..p9` (13). Legacy:
            // `log level msg p1..p9` (12) — the category slot is normalized to `null` or a bare
            // name, so `~`, a quoted string or empty in that slot can only be the legacy form.
            return legacyTextSlot(tokens, 2) ? 12 : 13;
        }
        switch(opcode){
            case "assert": return 5;          // op value compare message
            case "assertBounds": return 9;    // type multiple min opMin value opMax max message
            case "assertequals": return 4;    // expected actual message
            case "assertflush": return 2;     // position
            case "assertprints": return 4;    // position expected message
            case "asserttype": return 4;      // type value message
            case "error": return 11;          // message p1..p9
            case "systemlog": return 12;      // level message p1..p9
            case "breakpoint": return 4;      // op value compare
            case "profile": return 3;         // command block
            case "restart": return 2;         // block
            default: return 1;                // unknown opcode: keep just the head
        }
    }

    /** Whether the slot that differs between a family's two wire shapes holds legacy text
     *  ({@code ~}, a quoted string, {@code null} or empty) instead of a current value. */
    private static boolean legacyTextSlot(String[] tokens, int index){
        if(index >= tokens.length) return true;
        String slot = tokens[index];
        if(slot == null || slot.isEmpty() || slot.equals("~") || slot.equals("null")) return true;
        return slot.charAt(0) == '"';
    }

    private static LStatement parse(String[] tokens){
        int count = tokenCount(tokens);
        String[] kept = new String[count];
        for(int i = 0; i < count; i++){
            kept[i] = optional(tokens[i]);
        }
        return new Line(kept);
    }

    private static String optional(String value){
        return value == null || value.isEmpty() ? "~" : value;
    }

    /**
     * One debug line, kept as text only.
     *
     * <p>Read-only on the canvas and inert at run time; it exists so a program containing an
     * MDT line survives parse → assemble → save instead of degrading into
     * {@code InvalidStatement} (which would also shift every jump target, because invalid
     * statements are not instructions).</p>
     */
    public static class Line extends LStatement{
        private final String[] tokens;

        public Line(String[] tokens){
            this.tokens = tokens == null || tokens.length == 0 ? new String[]{"noop"} : tokens;
        }

        public String opcode(){
            return tokens[0];
        }

        /** The line exactly as it was written (tokens separated by one space). */
        public String text(){
            return String.join(" ", tokens);
        }

        /** Whether this parsed line is one of LogicSugar's own generated bounds checks (see
         *  {@link #isGeneratedBoundsMessage}). The expression layer uses it as a text predicate:
         *  those lines are transparent to folding (skipped as chain elements, removed with the
         *  chain) and are re-inserted as text when an emit build unfolds an expression card. */
        public boolean isGeneratedBoundsCheck(){
            return "assertBounds".equals(opcode()) && isGeneratedBoundsMessage(tokens[tokens.length - 1]);
        }

        @Override
        public void build(Table table){
            table.clearChildren();
            table.add(text()).left().pad(4f).color(table.color).wrap().growX();
        }

        /** Inert by design: no runtime behaviour lives here any more (MDT owns it), and a
         *  {@code null} would be dropped by the assembler and shift jump targets. */
        @Override
        public LInstruction build(LAssembler builder){
            return new NoopI();
        }

        @Override
        public LCategory category(){
            return LCategory.unknown;
        }

        @Override
        public void write(StringBuilder out){
            out.append(text());
        }
    }
}

