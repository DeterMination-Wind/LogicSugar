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
}
