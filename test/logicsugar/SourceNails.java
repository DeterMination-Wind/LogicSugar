package logicsugar;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Shared helper for the tests that pin source text ("nails"). Reading a repository file needs two
 * things that every test used to re-implement: locating the project directory (gradle runs from it,
 * tests may run from a subdirectory) and normalising line endings.
 *
 * <p>Windows checkouts hand these files back with CRLF ({@code core.autocrlf}), which makes any
 * anchor that expects a newline directly after a token miss - silently, since {@code contains()}
 * just returns false. Normalising here keeps the nails about the code and not the checkout.
 */
public final class SourceNails{
    private SourceNails(){
    }

    /** Project directory that contains {@code assets/bundles}; gradle runs from it, tests may run from a subdirectory. */
    public static File root(){
        for(String candidate : new String[]{".", ".."}){
            File root = new File(candidate);
            if(new File(root, "assets/bundles/bundle.properties").isFile()
                && new File(root, "src/logicsugar/LogicSugarMod.java").isFile()){
                return root;
            }
        }
        throw new AssertionError("LogicSugar project directory not found from " + new File(".").getAbsolutePath());
    }

    /** Reads a repository file under the project root, with line endings normalised to LF. */
    public static String readSource(String relative) throws IOException{
        return Files.readString(root().toPath().resolve(relative), StandardCharsets.UTF_8)
            .replace("\r\n", "\n");
    }

    /**
     * The source text of one method or constructor, from its signature to the matching closing brace.
     *
     * <p>Exists because the alternative - {@code source.substring(at, at + 2400)} - fails open. A
     * {@code !body.contains("...")} nail over a fixed window passes as soon as the method grows past
     * the window, so the nail keeps reporting green while the thing it forbids sits just beyond the
     * cut. One of these windows stood 136 characters away from that (see {@code arm()} in
     * {@code SugarCoexist}), which is less than a single edit. Brace matching cannot drift.</p>
     *
     * @throws AssertionError if {@code signature} is absent or its braces do not balance, so a
     *         renamed method fails loudly instead of yielding an empty string that satisfies every
     *         "must not contain" check.
     */
    public static String methodBody(String source, String signature){
        int at = source.indexOf(signature);
        if(at < 0) throw new AssertionError("source nail: method signature not found: " + signature);
        return block(source, at, signature);
    }

    /**
     * The text from {@code anchor} to the end of the first brace block that follows it.
     *
     * <p>{@link #methodBody} is this anchored at a signature; the same reasoning applies to any nested
     * block a nail has to isolate - the lambda handed to {@code dialog.hidden(...)} or
     * {@code Element.shown(...)}, for instance, where "which callback runs this" is the entire point
     * and a whole-file {@code contains()} would happily accept the call from a different callback
     * that happens to sit nearby. The anchor is normally the opening line of the block, braces
     * included, so the matched block is exactly what the reader sees there.</p>
     *
     * @throws AssertionError if the anchor is absent or its braces do not balance, so a deleted or
     *         rewritten block fails loudly instead of yielding an empty string that satisfies every
     *         "must not contain" check.
     */
    public static String blockFrom(String source, String anchor){
        int at = source.indexOf(anchor);
        if(at < 0) throw new AssertionError("source nail: anchor not found: " + anchor);
        return block(source, at, anchor);
    }

    /** Brace-match the block that starts at the first {@code {} after {@code at}. */
    private static String block(String source, int at, String what){
        int open = source.indexOf('{', at);
        if(open < 0) throw new AssertionError("source nail: no body after: " + what);
        int depth = 0;
        for(int i = open; i < source.length(); i++){
            char c = source.charAt(i);
            if(c == '{'){
                depth++;
            }else if(c == '}'){
                // Strings and comments are not parsed on purpose: a source file that had an
                // unbalanced brace inside a literal would break the compiler first.
                if(--depth == 0) return source.substring(at, i + 1);
            }
        }
        throw new AssertionError("source nail: unbalanced braces after: " + what);
    }
}
