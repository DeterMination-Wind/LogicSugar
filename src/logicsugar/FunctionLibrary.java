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

    private FunctionLibrary(){}

    public static Fi file(){
        return Core.settings.getDataDirectory().child(relativePath);
    }

    /** Loads and validates the library, caching the index by content hash. */
    public static SugarFunctions.LibraryIndex index(){
        Fi file = file();
        if(!file.exists()) return null;
        String text = file.readString("UTF-8");
        String hash = Integer.toHexString(text.hashCode());
        if(cachedHash != null && cachedHash.equals(hash) && cached != null) return cached;

        cachedHash = hash;
        try{
            cached = SugarFunctions.buildLibrary(LAssembler.read(text, true));
        }catch(IllegalArgumentException e){
            Log.warn("LogicSugar: function library is invalid: @", e.getMessage());
            cached = null;
        }
        return cached;
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
    }
}
