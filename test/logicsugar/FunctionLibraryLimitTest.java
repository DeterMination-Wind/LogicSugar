package logicsugar;

import arc.struct.Seq;
import mindustry.logic.LExecutor;
import mindustry.logic.LStatement;
import mindustry.logic.SugarCompiler;
import mindustry.logic.SugarCompiler.AssertEmit;
import mindustry.logic.SugarCompiler.FuncMode;
import mindustry.logic.SugarCompiler.SwitchStrategy;
import mindustry.logic.SugarFunctions;

import java.util.Set;

/**
 * Function-library size regressions.
 *
 * <p>The library file is not a saved processor program: it is shared sugar text whose used
 * subset is inlined at compile time, so it may hold far more statements than the vanilla
 * 1000-instruction cap (currently up to {@link SugarFunctions#libraryInstructionLimit}).
 * Parsing it through {@code LAssembler.read} would silently truncate at 1000, so every library
 * entry point (sanitize, extract, save validation, editor session) must use the raised limit
 * while processor programs keep the vanilla cap.
 */
public class FunctionLibraryLimitTest{
    public static void main(String[] args){
        LogicSugarMod.registerStatements();
        readLibraryParsesBeyondProcessorCap();
        readLibraryRestoresTheProcessorCap();
        libraryOverLimitDetectsTheCeiling();
        sanitizerSeesFunctionsBeyondTheCap();
        extractionAndCompilationUseTailFunctions();
        libraryEditorRoundTripsOversizedText();
        longSingleFunctionBodyParses();
        System.out.println("LogicSugar FunctionLibraryLimit self-test passed.");
    }

    /** A library of {@code fillerFunctions} empty functions; the optional {@code target} function
     *  is appended last so it lands beyond the processor cap (and beyond 1000 for ~600 fillers). */
    private static String fillerLibrary(int fillerFunctions, boolean includeTarget){
        StringBuilder sb = new StringBuilder();
        int index = 0;
        for(int i = 0; i < fillerFunctions; i++){
            sb.append("funcdef fill").append(i).append(" ~ ").append(index + 1).append('\n');
            sb.append("blockend\n");
            index += 2;
        }
        if(includeTarget){
            sb.append("funcdef target x ").append(index + 2).append('\n');
            sb.append("op add out x 1\n");
            sb.append("blockend\n");
        }
        return sb.toString();
    }

    private static void readLibraryParsesBeyondProcessorCap(){
        String text = fillerLibrary(600, true);
        check(LExecutor.maxInstructions < 1001, "test expects the vanilla processor cap, got " + LExecutor.maxInstructions);
        Seq<LStatement> parsed = SugarFunctions.readLibrary(text, true);
        check(parsed.size > 1000, "readLibrary truncated the library at the processor cap: " + parsed.size);
        check(parsed.size == 1203, "readLibrary parsed an unexpected statement count: " + parsed.size);
        check(parsed.size == text.split("\n").length, "statement count should match the generated lines");
    }

    private static void readLibraryRestoresTheProcessorCap(){
        int previous = LExecutor.maxInstructions;
        SugarFunctions.readLibrary(fillerLibrary(600, true), true);
        check(LExecutor.maxInstructions == previous, "readLibrary leaked the raised limit: " + LExecutor.maxInstructions);

        boolean threw = false;
        try{
            SugarFunctions.withLibraryLimit(() -> { throw new RuntimeException("probe"); });
        }catch(RuntimeException e){
            threw = true;
        }
        check(threw, "withLibraryLimit swallowed the action exception");
        check(LExecutor.maxInstructions == previous, "withLibraryLimit leaked after an exception: " + LExecutor.maxInstructions);

        boolean[] inside = {false};
        SugarFunctions.withLibraryLimit(() -> inside[0] = LExecutor.maxInstructions >= SugarFunctions.libraryInstructionLimit);
        check(inside[0], "withLibraryLimit did not install the library limit");
        check(LExecutor.maxInstructions == previous, "withLibraryLimit did not restore the limit");

        String value = SugarFunctions.withLibraryLimitValue(() -> "ok");
        check("ok".equals(value), "withLibraryLimitValue did not return the action value");
        check(LExecutor.maxInstructions == previous, "withLibraryLimitValue did not restore the limit");
    }

    private static void libraryOverLimitDetectsTheCeiling(){
        int half = SugarFunctions.libraryInstructionLimit / 2;
        String atCeiling = fillerLibrary(half, false);
        check(atCeiling.split("\n").length == SugarFunctions.libraryInstructionLimit, "ceiling fixture has the wrong size");
        check(!SugarFunctions.libraryOverLimit(atCeiling), "library at the ceiling was reported over limit");

        String overCeiling = fillerLibrary(half + 1, false);
        check(SugarFunctions.libraryOverLimit(overCeiling), "library over the ceiling was not detected");
        check(!SugarFunctions.libraryOverLimit("funcdef f ~ 1\nblockend\n"), "small valid library reported over limit");
        check(!SugarFunctions.libraryOverLimit(null), "null library reported over limit");
    }

    private static void sanitizerSeesFunctionsBeyondTheCap(){
        String text = fillerLibrary(600, true);
        SugarFunctions.SanitizedLibrary sanitized = SugarFunctions.sanitizedLibrary(text);
        check(!sanitized.damaged, "valid oversized library was reported damaged: " + sanitized.warnings);
        check(sanitized.index.functions.size() == 601, "sanitizer lost tail functions: " + sanitized.index.functions.size());
        check(sanitized.index.functions.containsKey("target"), "sanitizer did not see the tail function");

        SugarFunctions.LibraryIndex built = SugarFunctions.buildLibrary(SugarFunctions.readLibrary(text, true));
        check(built.functions.containsKey("target"), "buildLibrary did not see the tail function");
    }

    private static void extractionAndCompilationUseTailFunctions(){
        String text = fillerLibrary(600, true);
        String extracted = SugarFunctions.extractLibrarySource(text, Set.of("target"));
        SugarFunctions.LibraryIndex extractedIndex = SugarFunctions.buildLibrary(SugarFunctions.readLibrary(extracted, true));
        check(extractedIndex.functions.containsKey("target"), "extraction lost the tail function");
        check(extractedIndex.functions.size() == 1, "extraction kept unrelated functions: " + extractedIndex.functions.keySet());

        SugarFunctions.LibraryIndex library = SugarFunctions.buildLibrary(SugarFunctions.readLibrary(text, true));
        String processor = "funccall target \"5\" ~\n";
        String compiled = SugarCompiler.compile(processor, FuncMode.normal, library, text);
        check(compiled.contains("__ls_func_target_entry"), "tail library function was not hoisted:\n" + compiled);
        check(compiled.contains("op add __ls_func_target_out __ls_func_target_x 1"), "tail library body was not lowered");

        String embedded = SugarCompiler.libraryFromCode(compiled);
        check(embedded != null && embedded.contains("funcdef target"), "tail library function was not embedded");
        SugarFunctions.LibraryIndex embeddedIndex = SugarFunctions.buildLibrary(SugarFunctions.readLibrary(embedded, true));
        String recompiled = SugarCompiler.compile(processor, FuncMode.normal, embeddedIndex, embedded);
        check(SugarCompiler.matchesStoredStream(recompiled, compiled), "embedded subset did not reproduce the program");
    }

    private static void libraryEditorRoundTripsOversizedText(){
        String text = fillerLibrary(600, true);
        SugarFunctions.LibraryIndex library = SugarFunctions.buildLibrary(SugarFunctions.readLibrary(text, true));
        String compiled = SugarCompiler.compile(text, FuncMode.normal, library, text,
            SwitchStrategy.auto, AssertEmit.strip, true, true);
        String restored = SugarCompiler.restore(compiled, true);
        check(restored.replace("\r\n", "\n").trim().equals(text.trim()),
            "library session round-trip lost content (" + restored.length() + " vs " + text.length() + ")");
    }

    private static void longSingleFunctionBodyParses(){
        int bodyLines = 1200;
        StringBuilder sb = new StringBuilder("funcdef big ~ ").append(bodyLines + 1).append('\n');
        for(int i = 0; i < bodyLines; i++){
            sb.append("op add x x 1\n");
        }
        sb.append("blockend\n");
        String text = sb.toString();

        Seq<LStatement> parsed = SugarFunctions.readLibrary(text, true);
        check(parsed.size == bodyLines + 2, "long single-function library was truncated: " + parsed.size);
        SugarFunctions.LibraryIndex index = SugarFunctions.buildLibrary(parsed);
        check(index.functions.containsKey("big"), "long single function did not validate");
        check(index.functions.get("big").body.size == bodyLines, "long function body was truncated");
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
