package logicsugar.assist.expr;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Compile-time constants for the expression/condition language.
 *
 * <p>These are <b>not</b> mlog built-in variables. The compiler replaces them with a literal
 * before the program is written, so the saved product stays plain vanilla mlog and the constant
 * name survives only in the Logic Sugar source (carrier / condition field / single-line Expr
 * card marker).</p>
 *
 * <p>Names are case-insensitive and use the reserved {@code @__} prefix. Any {@code @__} token
 * that survives lowering is a compile error: a raw {@code set x @__T5__} would otherwise become
 * an ordinary (null) variable in the game, which is a silent wrong result. Use an expression
 * field ({@code x = @__T5__}) or an expression-mode condition instead.</p>
 *
 * <p><b>Freeze this table.</b> Values are baked into condition/carrier source that
 * {@code SugarCompiler.verifyRestore} recompiles; changing a value (or the meaning of a
 * classifier) makes old saves fail the restore gate and open in the vanilla view. Add a new
 * name/version instead of editing an existing one when semantics must change.</p>
 */
public final class SugarConstants{
    private SugarConstants(){}

    private static final Map<String, Double> VALUES;

    /** Getter calls that must only appear where the expression compiler expands them. */
    private static final String[] RESERVED_CALLS = {
        UnitIntrinsics.FAMILY + "(",
        UnitIntrinsics.TIER + "(",
        UnitIntrinsics.PLANET + "("
    };
    static{
        Map<String, Double> values = new LinkedHashMap<>();

        // Unit family enum (see UnitIntrinsics for the logic-id table).
        put(values, "@__UNKNOWN__", 0);
        put(values, "@__GROUND__", 1);
        put(values, "@__AIR__", 2);
        put(values, "@__NAVAL__", 3);
        put(values, "@__TANK__", 4);
        put(values, "@__MECH__", 5);
        put(values, "@__SHIP__", 6);
        put(values, "@__SUPPORT__", 7);
        put(values, "@__CORE__", 8);

        // Unit tier enum. 0 is reserved for "unknown" (`@__UNKNOWN__`).
        put(values, "@__T1__", 1);
        put(values, "@__T2__", 2);
        put(values, "@__T3__", 3);
        put(values, "@__T4__", 4);
        put(values, "@__T5__", 5);

        // Planet/faction enum returned by unit_planet().
        put(values, "@__SERPULO__", 1);
        put(values, "@__EREKIR__", 2);

        // Engine facts that vanilla does not expose as constants.
        put(values, "@__EPS__", 1e-6);
        put(values, "@__TILESIZE__", 8);
        put(values, "@__TICKS_PER_SECOND__", 60);
        put(values, "@__SECONDS_PER_TICK__", 1.0 / 60.0);

        VALUES = Collections.unmodifiableMap(values);
    }

    private static void put(Map<String, Double> values, String name, double value){
        values.put(name.toLowerCase(Locale.ROOT), value);
    }

    /** Constant value for {@code token}, or null when it is not registered. Case-insensitive. */
    public static Double value(String token){
        if(token == null) return null;
        return VALUES.get(token.toLowerCase(Locale.ROOT));
    }

    /** Whether {@code token} uses Logic Sugar's reserved compile-time-constant namespace. */
    public static boolean isReserved(String token){
        return token != null && token.length() >= 3
            && token.charAt(0) == '@' && token.charAt(1) == '_' && token.charAt(2) == '_';
    }

    /** Parse error text for an unknown constant or a constant used as a function name. */
    public static String unknown(String token){
        return "unknown LogicSugar constant '" + token + "'";
    }

    /**
     * Rejects any {@code @__...} token that survived compilation. Tokens inside quoted strings
     * and {@code #} comments are ignored, using the same escape rules as the v160.1
     * {@code LParser.string()} ({@code \\}, {@code \"}, {@code \n}, and a backslash-u
     * unicode escape).
     *
     * <p>Called twice: on the source of a program that has no sugar statements (the early return
     * path), and on the lowered instruction text of every sugar program. This is what keeps a raw
     * {@code set x @__T5__}, a simple (non-Expr) condition field, a {@code case @__T5__} value or a
     * raw {@code jump} operand from silently becoming a null variable in the saved program.</p>
     */
    public static void rejectUnresolved(String text, String where){
        if(text == null || text.isEmpty()) return;

        boolean inString = false;
        for(int i = 0; i < text.length(); i++){
            char c = text.charAt(i);
            if(inString){
                // v160.1's LParser.string() treats \\, \" and \n as escapes and accepts a
                // backslash-u unicode escape, so a quote right after a backslash does not close
                // the literal. Skipping the escaped character is enough for a quote scan: none of
                // those escapes puts a bare quote in the text. A lone backslash is ordinary.
                if(c == '\\' && i + 1 < text.length()){
                    char next = text.charAt(i + 1);
                    if(next == '\\' || next == '"' || next == 'n' || next == 'u') i++;
                }else if(c == '"'){
                    inString = false;
                }
                continue;
            }
            if(c == '"'){
                inString = true;
                continue;
            }
            if(c == '#'){
                while(i < text.length() && text.charAt(i) != '\n') i++;
                continue;
            }

            for(String call : RESERVED_CALLS){
                if(atTokenStart(text, i) && text.regionMatches(true, i, call, 0, call.length())){
                    String name = call.substring(0, call.length() - 1);
                    throw misplaced(text, i, where, "LogicSugar expression '" + name + "' is not allowed here;"
                        + " use an Expr card or an expression-mode condition (for switch/jump, "
                        + "store the value in a variable first).");
                }
            }

            if(c != '@' || !atTokenStart(text, i) || i + 2 >= text.length()
                || text.charAt(i + 1) != '_' || text.charAt(i + 2) != '_') continue;

            int start = i;
            i += 3;
            while(i < text.length()){
                char next = text.charAt(i);
                if(!Character.isLetterOrDigit(next) && next != '_') break;
                i++;
            }
            String token = text.substring(start, i);
            throw misplaced(text, start, where, "LogicSugar constant '" + token + "' is not allowed here;"
                + " constants are only supported in expression fields and expression-mode (`expr`) conditions.");
        }
    }

    /**
     * Whether {@code index} is the start of an mlog token. LParser.token() breaks on space, tab,
     * newline, {@code \r}, {@code #} and {@code ;}; a token can also start directly after a
     * closing quote. Matching only at token starts keeps ordinary names such as
     * {@code myunit_family(u)} or {@code foo@__bar} from being rejected.
     */
    private static boolean atTokenStart(String text, int index){
        if(index == 0) return true;
        char previous = text.charAt(index - 1);
        return previous == ' ' || previous == '\t' || previous == '\n' || previous == '\r'
            || previous == '#' || previous == ';' || previous == '"';
    }

    private static IllegalArgumentException misplaced(String text, int start, String where, String message){
        int line = 1, column = 1;
        for(int at = 0; at < start; at++){
            if(text.charAt(at) == '\n'){
                line++;
                column = 1;
            }else{
                column++;
            }
        }
        return new IllegalArgumentException(message + " (" + where + ", line " + line + ", column " + column + ").");
    }
}
