package logicsugar.assist;

import mindustry.logic.LExecutor;
import mindustry.logic.SugarCompiler;
import mindustry.logic.SugarFunctions;

/**
 * Live / close-time processor budget: compiled instruction count versus
 * {@link LExecutor#maxInstructions}, plus optional compressed storage size.
 *
 * <p>Vanilla {@code LAssembler.read} silently truncates at the instruction cap, and
 * {@link mindustry.logic.SugarLogicDialog} previously only surfaced an over-limit
 * compile error after {@code hide()} had already started. This helper is the shared
 * check used by the editor banner and the close-time gate.</p>
 */
public final class InstructionBudget{
    private InstructionBudget(){}

    public static final class Snapshot{
        public final int instructions;
        public final int instructionLimit;
        public final int sourceLines;
        public final String compiled;
        public final String compileError;
        public final boolean overInstructions;

        Snapshot(int instructions, int instructionLimit, int sourceLines, String compiled,
                 String compileError, boolean overInstructions){
            this.instructions = instructions;
            this.instructionLimit = instructionLimit;
            this.sourceLines = sourceLines;
            this.compiled = compiled;
            this.compileError = compileError;
            this.overInstructions = overInstructions;
        }

        /** Count to show in the editor: compiled size when known, otherwise source lines. */
        public int displayCount(){
            return instructions >= 0 ? instructions : sourceLines;
        }

        public boolean over(){
            return overInstructions;
        }
    }

    public static Snapshot of(String sugar, SugarCompiler.FuncMode mode,
                              SugarFunctions.LibraryIndex library, String libraryText){
        int limit = LExecutor.maxInstructions;
        int sourceLines = SugarCompiler.emittedInstructionCount(sugar);
        try{
            String compiled = SugarCompiler.compile(sugar, mode, library, libraryText);
            int instructions = SugarCompiler.emittedInstructionCount(compiled);
            return new Snapshot(instructions, limit, sourceLines, compiled, null,
                instructions > limit || sourceLines > limit);
        }catch(IllegalArgumentException exception){
            Integer parsed = parseLimitCount(exception.getMessage());
            boolean over = parsed != null || sourceLines > limit;
            int instructions = parsed != null ? parsed : (over ? Math.max(sourceLines, limit + 1) : -1);
            return new Snapshot(instructions, limit, sourceLines, null, exception.getMessage(), over);
        }
    }

    /** Reads {@code Compiled program has N instructions; maximum is M.} */
    public static Integer parseLimitCount(String message){
        if(message == null) return null;
        String prefix = "Compiled program has ";
        int start = message.indexOf(prefix);
        if(start < 0) return null;
        int from = start + prefix.length();
        int to = message.indexOf(" instructions", from);
        if(to < 0) return null;
        try{
            return Integer.parseInt(message.substring(from, to).trim());
        }catch(NumberFormatException ignored){
            return null;
        }
    }
}
