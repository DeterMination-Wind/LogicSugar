package logicsugar.assist;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Bang {@code logic_lint}-style static checker for compiled MLog statement lists.
 *
 * <p>Advisory only: {@link #lint(List)} reports problems and never mutates its input, and
 * this iteration is not wired into the compiler pipeline.</p>
 *
 * <p>Input contract: each element is one already-tokenized statement whose {@code token[0]}
 * is the instruction kind, matching the tokenization model of
 * {@code mindustry.logic.SugarDecompiler.Program} (which mirrors vanilla {@code LParser});
 * quoted strings arrive as a single token including their quotes. The {@code line} field of
 * every {@link Warning} is the 0-based instruction index within the linted list (mlog jump
 * targets use the same 0-based indexing).</p>
 *
 * <p>Rule index (stable {@code code} strings; all facts verified against Mindustry-master
 * sources, none from memory):
 * <ul>
 *   <li>{@code unknown-op} (ERROR) — the {@code op} operator (token[1]) is not a
 *       {@code LogicOp} enum name. Names transcribed from
 *       {@code mindustry/logic/LogicOp.java} (45 constants); vanilla assembles them via
 *       {@code LogicOp.valueOf(tokens[1])} in the generated {@code mindustry.gen.LogicIO}.</li>
 *   <li>{@code bad-arg-count} (ERROR) — token count outside the fixed arity family:
 *       {@code set}=3, {@code op}=5, {@code end}=1, {@code sensor}=4 (field lists of the
 *       corresponding statements in {@code mindustry/logic/LStatements.java}) and
 *       {@code jump}=2 (bare {@code jump <target>}, the label form LParser rewrites) or 5
 *       (canonical {@code jump <target> <cond> <x> <y>}, the only shape
 *       {@code LogicIO.write} emits). 3-4 token jumps are reported by
 *       {@code empty-jump-condition} instead of being double-reported here; other kinds are
 *       not checked (conservative).</li>
 *   <li>{@code assign-to-literal} (WARNING) — the write target of {@code set} (token[1]) or
 *       {@code op} (token[2]) is a numeric literal or a quoted string; the assembler folds
 *       such targets into a constant value, so the written result is lost.</li>
 *   <li>{@code self-jump} (WARNING) — a numeric jump target equals the jump's own line.</li>
 *   <li>{@code jump-out-of-range} (WARNING) — a numeric jump target is negative or at/after
 *       the end of the program. Non-integer targets (labels) are skipped.</li>
 *   <li>{@code empty-jump-condition} (ERROR) — a 3-4 token jump. {@code LogicIO.write}
 *       always serializes the full 5-segment form and the label form is 2 segments, so a
 *       partial condition can only come from a broken writer (vanilla {@code LogicIO.read}
 *       happens to tolerate it by filling field defaults, which is exactly why a lint
 *       should flag it).</li>
 *   <li>{@code unknown-kind} (INFO) — the kind is not one of the 53 {@code @RegisterStatement}
 *       ids of {@code mindustry/logic/LStatements.java}. Kept INFO on purpose: mods may
 *       register extra statement kinds via {@code LAssembler.customParsers}, so an unknown
 *       kind must not be an error.</li>
 * </ul></p>
 */
public final class MlogLint{

    public enum Severity{ INFO, WARNING, ERROR }

    /** One finding. {@code line} is the 0-based instruction index inside the linted list. */
    public record Warning(int line, Severity severity, String code, String message){}

    private MlogLint(){}

    /** {@code LogicOp} enum names, transcribed 1:1 from mindustry/logic/LogicOp.java. */
    private static final Set<String> LOGIC_OPS = Set.of(
        "add", "sub", "mul", "div", "idiv", "mod", "emod", "pow",
        "equal", "notEqual", "land", "lessThan", "lessThanEq", "greaterThan", "greaterThanEq",
        "strictEqual",
        "shl", "shr", "ushr", "or", "and", "xor", "not",
        "max", "min", "angle", "angleDiff", "len", "noise", "abs", "sign", "log", "logn",
        "log10", "floor", "ceil", "round", "sqrt", "rand",
        "sin", "cos", "tan", "asin", "acos", "atan"
    );

    /** @RegisterStatement ids, transcribed 1:1 from mindustry/logic/LStatements.java. */
    private static final Set<String> KNOWN_KINDS = Set.of(
        "noop", "read", "write", "draw", "print", "printchar", "format", "drawflush",
        "printflush", "getlink", "control", "radar", "sensor", "set", "op", "select", "wait",
        "stop", "lookup", "packcolor", "unpackcolor", "end", "jump", "ubind", "ucontrol",
        "uradar", "ulocate", "query", "getblock", "setblock", "spawn", "bullet", "status",
        "weathersense", "weatherset", "spawnwave", "setrule", "message", "cutscene", "effect",
        "explosion", "setrate", "fetch", "sync", "clientdata", "getflag", "setflag", "setprop",
        "playsound", "playmusic", "setmarker", "makemarker", "localeprint"
    );

    /** Lints a tokenized statement list; findings come back in line order. */
    public static List<Warning> lint(List<String[]> lines){
        List<Warning> result = new ArrayList<>();
        if(lines == null) return result;
        for(int i = 0; i < lines.size(); i++){
            String[] tokens = lines.get(i);
            if(tokens == null || tokens.length == 0) continue;
            for(Warning warning : lineWarnings(tokens, i, lines.size())){
                result.add(warning);
            }
        }
        return result;
    }

    /** All findings for one statement, in fixed rule order; pure, unit-testable. */
    static List<Warning> lineWarnings(String[] tokens, int line, int lineCount){
        List<Warning> result = new ArrayList<>();
        add(result, unknownKind(line, tokens));
        add(result, badArgCount(line, tokens));
        add(result, unknownOp(line, tokens));
        add(result, assignToLiteral(line, tokens));
        add(result, emptyJumpCondition(line, tokens));
        add(result, selfJump(line, tokens));
        add(result, jumpOutOfRange(line, tokens, lineCount));
        return result;
    }

    /** unknown-kind: kind not in the LStatements @RegisterStatement id list (INFO). */
    static Warning unknownKind(int line, String[] tokens){
        if(KNOWN_KINDS.contains(tokens[0])) return null;
        return warn(line, Severity.INFO, "unknown-kind",
            "statement kind '" + tokens[0] + "' is not a @RegisterStatement id in mindustry.logic.LStatements");
    }

    /** bad-arg-count: token count outside the fixed arity family of the checked kinds. */
    static Warning badArgCount(int line, String[] tokens){
        switch(tokens[0]){
            case "set":
                return tokens.length == 3 ? null
                    : badArgs(line, "set", "'set <to> <from>'", 3);
            case "op":
                return tokens.length == 5 ? null
                    : badArgs(line, "op", "'op <operator> <dest> <a> <b>'", 5);
            case "sensor":
                return tokens.length == 4 ? null
                    : badArgs(line, "sensor", "'sensor <to> <from> <type>'", 4);
            case "end":
                return tokens.length == 1 ? null
                    : badArgs(line, "end", "'end'", 1);
            case "jump":
                if(tokens.length == 2 || tokens.length == 5) return null;
                // 3-4 tokens: reported by emptyJumpCondition, not double-reported here
                if(tokens.length >= 3 && tokens.length <= 4) return null;
                return badArgs(line, "jump",
                    "a bare 'jump <target>' (2 tokens) or 'jump <target> <cond> <x> <y>' (5 tokens)",
                    tokens.length);
            default:
                return null; // conservative: kinds without a verified arity are not checked
        }
    }

    /** unknown-op: op operator is not a LogicOp enum name. */
    static Warning unknownOp(int line, String[] tokens){
        if(!tokens[0].equals("op") || tokens.length < 2) return null;
        if(LOGIC_OPS.contains(tokens[1])) return null;
        return warn(line, Severity.ERROR, "unknown-op",
            "op operator '" + tokens[1] + "' is not a LogicOp name (mindustry.logic.LogicOp)");
    }

    /** assign-to-literal: set/op write target is a numeric literal or quoted string. */
    static Warning assignToLiteral(int line, String[] tokens){
        int index = -1;
        if(tokens[0].equals("set") && tokens.length > 1){
            index = 1;
        }else if(tokens[0].equals("op") && tokens.length > 2){
            index = 2;
        }
        if(index < 0) return null;
        String target = tokens[index];
        if(!isNumericLiteral(target) && !isQuotedString(target)) return null;
        return warn(line, Severity.WARNING, "assign-to-literal",
            tokens[0] + " write target '" + target + "' is a literal; the written value is lost");
    }

    /** empty-jump-condition: jump with a truncated 3-4 token condition shape. */
    static Warning emptyJumpCondition(int line, String[] tokens){
        if(!tokens[0].equals("jump") || tokens.length < 3 || tokens.length > 4) return null;
        return warn(line, Severity.ERROR, "empty-jump-condition",
            "jump with " + tokens.length + " tokens has a truncated condition; use 'jump <target>'"
                + " or 'jump <target> <cond> <x> <y>'");
    }

    /** self-jump: numeric jump target equals the jump's own line. */
    static Warning selfJump(int line, String[] tokens){
        Integer target = jumpTarget(tokens);
        if(target == null || target.intValue() != line) return null;
        return warn(line, Severity.WARNING, "self-jump",
            "jump " + target + " targets its own instruction (infinite loop)");
    }

    /** jump-out-of-range: numeric target below 0 or at/past the end of the program. */
    static Warning jumpOutOfRange(int line, String[] tokens, int lineCount){
        Integer target = jumpTarget(tokens);
        if(target == null || (target.intValue() >= 0 && target.intValue() < lineCount)) return null;
        return warn(line, Severity.WARNING, "jump-out-of-range",
            "jump target " + target + " is outside the program (valid targets 0.." + (lineCount - 1) + ")");
    }

    /** Integer value of {@code text}, or null when it is not a plain signed integer. */
    static Integer jumpTarget(String[] tokens){
        if(!tokens[0].equals("jump") || tokens.length < 2) return null;
        String text = tokens[1];
        int start = text.startsWith("-") || text.startsWith("+") ? 1 : 0;
        if(start == text.length()) return null;
        for(int i = start; i < text.length(); i++){
            char c = text.charAt(i);
            if(c < '0' || c > '9') return null; // label targets are skipped, not reported
        }
        try{
            return Integer.valueOf(text);
        }catch(NumberFormatException e){
            return null; // out of int range
        }
    }

    /** True for decimal literals as the assembler reads them: optional sign, digits with at
     *  most one '.' (1, -2.5, .5). Deliberately rejects identifiers and scientific notation —
     *  false negatives are the safe direction for an advisory lint. */
    static boolean isNumericLiteral(String text){
        if(text.isEmpty() || isQuotedString(text)) return false;
        int i = text.startsWith("-") || text.startsWith("+") ? 1 : 0;
        boolean digits = false, dot = false;
        for(; i < text.length(); i++){
            char c = text.charAt(i);
            if(c == '.'){
                if(dot) return false;
                dot = true;
            }else if(c >= '0' && c <= '9'){
                digits = true;
            }else{
                return false;
            }
        }
        return digits;
    }

    /** True for a quoted-string token; the SugarDecompiler.Program/LParser tokenizer keeps
     *  the quotes as part of that single token. */
    static boolean isQuotedString(String text){
        return text.startsWith("\"");
    }

    private static Warning badArgs(int line, String kind, String shape, int found){
        return warn(line, Severity.ERROR, "bad-arg-count",
            kind + " expects " + shape + ", found " + found + " tokens");
    }

    private static Warning warn(int line, Severity severity, String code, String message){
        return new Warning(line, severity, code, message);
    }

    private static void add(List<Warning> list, Warning warning){
        if(warning != null) list.add(warning);
    }
}
