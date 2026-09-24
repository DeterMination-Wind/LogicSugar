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
        int open = source.indexOf('{', at);
        if(open < 0) throw new AssertionError("source nail: no body after: " + signature);
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
        throw new AssertionError("source nail: unbalanced braces after: " + signature);
    }
}
