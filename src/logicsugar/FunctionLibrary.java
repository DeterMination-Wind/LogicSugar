package logicsugar;

import arc.Core;
import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.logic.LAssembler;
import mindustry.logic.LStatement;
import mindustry.logic.SugarFunctions;

/**
 * Global function library persistence.
 *
 * <p>The library is a plain sugar text file ({@code <game data>/mods/config/LogicSugar/functions.txt})
 * containing only function definitions ({@code funcdef ... blockend} pairs). It is parsed and
 * validated once per change; the parsed index is cached by content hash so processors can
 * resolve library calls without re-reading the file on every compile.
 *
 * <p>A missing or damaged library behaves like an empty library: processors that call a
 * missing function get a clear compile error, and the library dialog reports the exact
 * validation failure when saving.
 */
public final class FunctionLibrary{
    private static final String relativePath = "mods/config/LogicSugar/functions.txt";

    private static String cachedHash;
    private static SugarFunctions.LibraryIndex cached;
    private static boolean cachedDamaged;
    private static java.util.List<String> cachedWarnings = java.util.List.of();

    private FunctionLibrary(){}

    public static Fi file(){
        return Core.settings.getDataDirectory().child(relativePath);
    }

    /** Loads the library, salvaging damaged files function-by-function; the index is cached
     *  by content hash. A damaged file yields a partial index ({@link #isDamaged()} true) with
     *  the repaired problems listed in {@link #lastWarnings()}. */
    public static SugarFunctions.LibraryIndex index(){
        Fi file = file();
        if(!file.exists()) return null;
        String text = file.readString("UTF-8");
        String hash = Integer.toHexString(text.hashCode());
        if(cachedHash != null && cachedHash.equals(hash) && cached != null) return cached;

        cachedHash = hash;
        SugarFunctions.SanitizedLibrary sanitized = SugarFunctions.sanitizedLibrary(text);
        cached = sanitized.index;
        cachedDamaged = sanitized.damaged;
        cachedWarnings = sanitized.warnings;
        if(sanitized.damaged){
            Log.warn("LogicSugar: function library is damaged; @ recovered", sanitized.index.functions.size() + " functions");
            for(String warning : sanitized.warnings) Log.warn("LogicSugar:   - @", warning);
        }
        return cached;
    }

    /** Content hash of the current library file (0 when the file is missing). Used to detect
     *  library changes between editor open and submit. */
    public static int hash(){
        Fi file = file();
        return file.exists() ? file.readString("UTF-8").hashCode() : 0;
    }

    /** Whether the last loaded library file was damaged and was salvaged partially. */
    public static boolean isDamaged(){
        return cached != null && cachedDamaged;
    }

    /** Problems repaired while loading the last damaged library file. */
    public static java.util.List<String> lastWarnings(){
        return cachedWarnings;
    }

    /** Returns the raw library text (empty when the file does not exist). */
    public static String loadText(){
        Fi file = file();
        return file.exists() ? file.readString("UTF-8") : "";
    }

    /** Validates and writes the library file. Throws IllegalArgumentException when invalid. */
    public static void save(String text){
        SugarFunctions.buildLibrary(LAssembler.read(text, true));
        file().parent().mkdirs();
        file().writeString(text, false, "UTF-8");
        cachedHash = null;
        cached = null;
        cachedDamaged = false;
        cachedWarnings = java.util.List.of();
    }
}
