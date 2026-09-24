package logicsugar.assist;

import arc.struct.Seq;
import mindustry.logic.LAssembler;
import mindustry.logic.LStatement;
import mindustry.logic.LStatements.InvalidStatement;
import mindustry.logic.LStatements.JumpStatement;

import java.util.Map;

/**
 * Plain-text clipboard payload for a fragment of statements, so a selection can be carried to
 * another processor (or another session) instead of only across the canvas it came from.
 *
 * <p>The format is the one the game already round-trips whole programs through: {@code
 * LCanvas.save()} is {@code LAssembler.write(statements)} and the first thing {@code
 * SugarCompiler.compile} does is {@code LAssembler.read(source, privileged)}. A fragment is
 * therefore just a shorter program. The only work specific to fragments is in the two things
 * that are meaningful solely relative to a whole program: block indices and jump targets.</p>
 *
 * <p>One statement per line is part of that format rather than a presentational choice, and it is
 * what makes the header line work: {@code LAssembler.write} never breaks a statement across
 * lines, and {@code LAssembler.read} reads one per line. A statement whose fields contained a
 * newline would shift every line after it, so a payload is only ever read back through the same
 * parser that produced it -- and foreign text that does not parse completely is rejected outright
 * (see {@link #isAcceptable}) instead of being guessed at.</p>
 *
 * <h2>Why sugar source rather than compiled mlog</h2>
 * <ul>
 *   <li>A fragment of a Sugar program frequently does not compile on its own, so the mlog form is
 *       not merely lossy, it is often unavailable: {@code SugarCompiler.validatePairs} rejects a
 *       begin whose end is missing, and {@code LParser} throws outright on a jump label it cannot
 *       resolve.</li>
 *   <li>Sugar source is a superset of mlog. {@code SugarCompiler.containsSugar} is an {@code
 *       instanceof} test and a program holding no Sugar card is returned untouched, so one format
 *       covers both directions and pasting plain mlog into LogicSugar keeps working.</li>
 *   <li>Compiled mlog has already resolved jumps to line numbers, and a line number is
 *       meaningless in any other program. A label is the only jump target that survives a move,
 *       and labels exist only in the source form.</li>
 * </ul>
 *
 * <p>Everything here works on statements and text alone, so it is testable without a canvas.</p>
 */
public final class StatementClipboard{
    /** Marks a payload as ours. A {@code #} line is a comment to the parser, so the header stays
     *  inert if the text is ever handed to something that does not know about it. The count that
     *  follows it is informational only: {@link #parse} drops the whole line and never checks the
     *  number, which exists so a payload stays readable in a plain text editor. */
    public static final String headerPrefix = "# @ls-fragment ";

    /** Outcome of a copy or paste; the caller turns it into a message. */
    public enum Result{
        /** Nothing selected to copy, or nothing usable on the clipboard. */
        EMPTY,
        /** Selected begin/end blocks are not paired: copying would tear the structure apart. */
        INCOMPLETE_STRUCTURE,
        /** A jump targets a statement outside the payload, so its line number means nothing
         *  anywhere else. */
        ESCAPING_JUMP,
        /** The clipboard does not hold logic code at all. */
        NOT_LOGIC,
        /** Inserting would exceed {@code LExecutor.maxInstructions}. */
        TOO_BIG,
        OK
    }

    private StatementClipboard(){}

    /**
     * Serialises a fragment in canvas order, one statement per line.
     *
     * <p>Callers must rebase first ({@link #rebase}): the index a statement carries is absolute
     * in the <em>source</em> program and would address an unrelated statement anywhere else.</p>
     */
    public static String write(Seq<LStatement> fragment){
        StringBuilder out = new StringBuilder();
        out.append(headerPrefix).append(fragment.size).append('\n');
        for(int i = 0; i < fragment.size; i++){
            fragment.get(i).write(out);
            out.append('\n');
        }
        return out.toString();
    }

    /** True when the text is one of our payloads (and so is trusted verbatim). */
    public static boolean isPayload(String text){
        return text != null && text.startsWith(headerPrefix);
    }

    /**
     * True when the editor should accept the clipboard at all.
     *
     * <p>Two different standards, on purpose. A payload of ours is reproduced faithfully, so it
     * is accepted even where the source canvas held an invalid statement. Foreign text has to
     * parse completely: a paragraph of prose would otherwise turn into a pile of silent invalid
     * cards, which looks like the paste worked.</p>
     *
     * <p>The single place this question is answered — {@code BoxSelect.pasteClipboard} and the
     * Ctrl+V shortcut both ask it, and a shortcut that kept its own copy would be the one to
     * drift.</p>
     */
    public static boolean isAcceptable(String text){
        if(text == null || text.trim().isEmpty()) return false;
        // 我们自己的载荷是原样复刻的（源画布上本来就有的无效卡也要照搬），但仍然得能解析出
        // 语句来：空载荷、或被外部工具截断的载荷都不该被当成可用内容。
        if(isPayload(text)) return parse(text) != null;
        Seq<LStatement> parsed = parse(text);
        return parsed != null && !hasUnknownStatements(parsed);
    }

    /**
     * Parses clipboard text back into statements, or returns null when it is not logic code.
     *
     * <p>Privileged is assumed, matching {@code LStatement.copy}: the statements are validated
     * when the program is next compiled, which is also where an unprivileged session rejects
     * them.</p>
     */
    public static Seq<LStatement> parse(String text){
        if(text == null) return null;
        String body = stripHeader(text);
        if(body.trim().isEmpty()) return null;
        Seq<LStatement> parsed;
        try{
            parsed = LAssembler.read(body, true);
        }catch(Throwable ignored){
            // LParser throws on an unresolvable jump label and on an over-long token; neither is
            // worth taking the editor down for.
            return null;
        }
        return parsed == null || parsed.isEmpty() ? null : parsed;
    }

    /**
     * Rewrites jump indices from positions in the source canvas into fragment-local ones.
     *
     * <p>Only jumps need this. A begin card's index is already dropped by {@code
     * BeginStatement.copy()} and is re-derived from nesting when the fragment is inserted (see
     * {@code SugarStatements.pairBlockEnds}), because a begin's number is only meaningful next to
     * its own end and pairing from nesting is the unique non-crossing answer.</p>
     *
     * @param absoluteToRelative source position in the origin canvas to index in the fragment
     * @return how many jumps pointed at a statement the fragment does not contain. Their index
     *         cannot be translated at all — it would address an unrelated line in the target
     *         program — so the caller decides whether to accept or refuse the copy.
     */
    public static int rebase(Seq<LStatement> fragment, Map<Integer, Integer> absoluteToRelative){
        int escaped = 0;
        for(int i = 0; i < fragment.size; i++){
            if(!(fragment.get(i) instanceof JumpStatement jump)) continue;

            Integer relative = absoluteToRelative.get(jump.destIndex);
            // -1 already means "unlinked", and there is nothing to translate for it.
            if(relative == null && jump.destIndex >= 0) escaped++;
            jump.destIndex = relative == null ? -1 : relative;
        }
        return escaped;
    }

    /**
     * Number of jumps in parsed text that target a statement the fragment does not contain.
     *
     * <p>The best a payload off the clipboard can offer: with only indices to go on, a target
     * beyond the end of the fragment is the detectable case. Payloads written by {@link #write}
     * were already checked against the live canvas by {@link #rebase}, so this only has to catch
     * text that arrived from somewhere else.</p>
     */
    public static int countEscapingJumps(Seq<LStatement> fragment){
        int escaped = 0;
        for(int i = 0; i < fragment.size; i++){
            if(fragment.get(i) instanceof JumpStatement jump && jump.destIndex >= fragment.size){
                escaped++;
            }
        }
        return escaped;
    }

    /**
     * True when the parser produced statements it did not understand. Only meaningful for text
     * that was not written by {@link #write}: a payload of ours is reproduced faithfully, even
     * where the source canvas itself held an invalid statement.
     */
    public static boolean hasUnknownStatements(Seq<LStatement> statements){
        for(int i = 0; i < statements.size; i++){
            if(statements.get(i) instanceof InvalidStatement) return true;
        }
        return false;
    }

    /** Drops the header and normalises line endings: the parser treats {@code \r} as an ordinary
     *  character, so CRLF text would otherwise leave a stray token on every line. */
    private static String stripHeader(String text){
        String body = text;
        if(isPayload(text)){
            int newline = text.indexOf('\n');
            body = newline < 0 ? "" : text.substring(newline + 1);
        }
        return body.replace("\r\n", "\n").replace("\r", "\n");
    }
}
