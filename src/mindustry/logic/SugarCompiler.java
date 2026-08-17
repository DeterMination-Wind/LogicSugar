package mindustry.logic;

import arc.Core;
import arc.struct.Seq;
import arc.util.serialization.Base64Coder;
import mindustry.logic.LExecutor;
import mindustry.logic.SugarStatements.BeginStatement;
import mindustry.logic.SugarStatements.BlockEndStatement;
import mindustry.logic.SugarStatements.BreakStatement;
import mindustry.logic.SugarStatements.ContinueStatement;
import mindustry.logic.SugarStatements.CaseStatement;
import mindustry.logic.SugarStatements.ElseIfStatement;
import mindustry.logic.SugarStatements.ElseStatement;
import mindustry.logic.SugarStatements.ForBeginStatement;
import mindustry.logic.SugarStatements.FuncCallStatement;
import mindustry.logic.SugarStatements.FuncDefStatement;
import mindustry.logic.SugarStatements.IfBeginStatement;
import mindustry.logic.SugarStatements.SwitchBeginStatement;
import mindustry.logic.SugarStatements.WhileBeginStatement;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

public final class SugarCompiler{
    private static final String markerBegin = "# @logic-sugar-v1 begin";
    private static final String markerLine = "# @logic-sugar-line ";
    private static final String markerEnd = "# @logic-sugar-v1 end";

    /** Persistence carrier prefixes: real "set" statements that survive the vanilla
     *  parse/save round trip (comment markers are dropped by it). The sugar carrier holds
     *  the sugar source; the library carrier holds the used subset of the function library. */
    private static final String carrierSugarPrefix = "set __ls_sugar \"";
    private static final String carrierLibPrefix = "set __ls_lib \"";
    /** LParser rejects string tokens longer than 65535 UTF bytes; staying well below that
     *  keeps a stored program from ever making a vanilla client fail to open the editor. */
    private static final int carrierMaxChars = 60000;

    /** Function expansion mode. normal = shared @counter subroutine; inline = per-call copy. */
    public enum FuncMode{
        normal, inline;

        public static FuncMode parse(String value){
            if("inline".equalsIgnoreCase(value)) return inline;
            return normal;
        }
    }

    private SugarCompiler(){}

    /** Extracts the sugar source from stored code. The persistence carrier is authoritative;
     *  without one (v2.0.0 legacy programs) the comment marker block is used. */
    public static String restore(String code){
        String normalized = code.replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        // Scan from the end: genuine carriers are always the last sugar-carrying lines, so a
        // user statement that happens to look like a carrier loses the race only in its favor.
        for(int i = lines.length - 1; i >= 0; i--){
            String line = lines[i];
            if(line.startsWith(carrierSugarPrefix) && line.endsWith("\"")){
                try{
                    return decode(line.substring(carrierSugarPrefix.length(), line.length() - 1));
                }catch(Exception ignored){
                    // damaged carrier: fall back to the marker block below
                }
            }
        }

        int begin = -1, end = -1;
        for(int i = 0; i < lines.length; i++){
            if(lines[i].equals(markerBegin)) begin = i;
            if(begin >= 0 && lines[i].equals(markerEnd)) end = i;
        }
        if(begin < 0 || end <= begin) return code;

        StringBuilder result = new StringBuilder();
        for(int i = begin + 1; i < end; i++){
            if(!lines[i].startsWith(markerLine)) return code;
            result.append(lines[i].substring(markerLine.length())).append('\n');
        }
        return result.toString();
    }

    /** Returns the library source embedded in stored code (the used subset the program was
     *  compiled with), or null when the code carries no embedded library. */
    public static String libraryFromCode(String code){
        String normalized = code.replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        for(int i = lines.length - 1; i >= 0; i--){
            String line = lines[i];
            if(line.startsWith(carrierLibPrefix) && line.endsWith("\"")){
                try{
                    return decode(line.substring(carrierLibPrefix.length(), line.length() - 1));
                }catch(Exception ignored){
                    return null;
                }
            }
        }
        return null;
    }

    /** Removes the comment marker block (the redundant sugar source) from compiled code.
     *  The persistence carriers are kept, so restore() still works afterwards. */
    public static String stripMarkers(String code){
        String normalized = code.replace("\r\n", "\n");
        int begin = normalized.indexOf(markerBegin);
        if(begin < 0) return code;
        int end = normalized.indexOf(markerEnd, begin);
        if(end < 0) return code;
        int after = end + markerEnd.length();
        if(after < normalized.length() && normalized.charAt(after) == '\n') after++;
        return normalized.substring(0, begin) + normalized.substring(after);
    }

    /**
     * Checks that the restored sugar really compiles to the stored code. Legacy v2.0.0
     * programs carry no carrier and are trusted as-is. Otherwise the sugar is recompiled
     * against the embedded library in both function modes and normalized (vanilla
     * read/write round trip, which also folds label jumps back into numeric indices); any
     * matching mode passes. A mismatch means the stored code was edited outside Logic Sugar.
     */
    public static boolean verifyRestore(String code, String restored){
        if(!containsCarrier(code, carrierSugarPrefix)) return true;
        String libText = libraryFromCode(code);
        SugarFunctions.LibraryIndex embedded = null;
        String embeddedSource = null;
        if(libText != null && !libText.trim().isEmpty()){
            SugarFunctions.SanitizedLibrary sanitized = SugarFunctions.sanitizedLibrary(libText);
            if(!sanitized.index.functions.isEmpty()){
                embedded = sanitized.index;
                embeddedSource = sanitized.text;
            }
        }
        String storedNormalized;
        try{
            storedNormalized = LAssembler.write(LAssembler.read(code, true));
        }catch(RuntimeException e){
            return false;
        }
        for(FuncMode mode : FuncMode.values()){
            try{
                String recompiled = compile(restored, mode, embedded, embeddedSource);
                if(LAssembler.write(LAssembler.read(recompiled, true)).equals(storedNormalized)) return true;
            }catch(RuntimeException ignored){
                // one mode may legitimately fail (e.g. inline blowup); the other may match
            }
        }
        return false;
    }

    /**
     * Stale-close protection: do not submit when the canvas was left untouched while the
     * stored code changed underneath it (another client saved). Edited canvases always
     * submit (last writer wins, same as vanilla).
     */
    public static boolean shouldSubmit(String canvasText, String editable, String openedCode, String currentCode){
        return !(canvasText.equals(editable) && !currentCode.equals(openedCode));
    }

    /** Compiles with the user-selected function mode (normal when settings are unavailable). */
    public static String compile(String sugar){
        return compile(sugar, currentMode());
    }

    public static String compile(String sugar, FuncMode mode){
        return compile(sugar, mode, SugarFunctions.library());
    }

    public static String compile(String sugar, FuncMode mode, SugarFunctions.LibraryIndex library){
        return compile(sugar, mode, library, null);
    }

    /** Compiles against an explicit library and its text. The library text is used to embed
     *  the used subset into the output ({@code set __ls_lib "..."}), so other machines can
     *  recompile the program without the local library file. */
    public static String compile(String sugar, FuncMode mode, SugarFunctions.LibraryIndex library, String libraryText){
        Seq<LStatement> statements = LAssembler.read(sugar, true);
        if(!containsSugar(statements)) return sugar;

        validatePairs(statements);
        SugarFunctions.FunctionSet functions = SugarFunctions.analyze(statements, library);

        StringBuilder out = new StringBuilder();
        SugarFunctions.CallIds ids = new SugarFunctions.CallIds();
        if(mode == FuncMode.normal){
            SugarFunctions.lower(functions.main, "", functions, mode, out, ids, null);
            for(SugarFunctions.Function function : functions.hoistOrder()){
                out.append(function.entryName()).append(":\n");
                SugarFunctions.lower(function.body, "func_" + function.name + "_", functions, mode, out, ids, function.name);
                out.append(function.exitName()).append(":\n");
                out.append("set @counter ").append(function.retName()).append('\n');
            }
        }else{
            SugarFunctions.lower(functions.main, "", functions, mode, out, ids, null);
        }

        // Persistence carriers: real "set" statements appended after the marker block. They
        // survive the vanilla parse/save round trip that drops the comment markers, and are
        // placed after them so lowered-code consumers (and the test helper) see the lowered
        // program untouched. They execute harmlessly every tick and count toward the limit.
        StringBuilder carriers = new StringBuilder();
        Set<String> usedLibrary = new HashSet<>();
        for(SugarFunctions.Function function : functions.hoistOrder()){
            if(function.library) usedLibrary.add(function.name);
        }
        if(libraryText != null && !libraryText.trim().isEmpty() && !usedLibrary.isEmpty()){
            String extracted = SugarFunctions.extractLibrarySource(libraryText, usedLibrary);
            if(!extracted.isEmpty()) appendCarrier(carriers, carrierLibPrefix, extracted);
        }
        appendCarrier(carriers, carrierSugarPrefix, sugar.replace("\r\n", "\n"));

        // LAssembler.read silently truncates at LExecutor.maxInstructions lines, so the count
        // must be computed from the emitted text itself (one instruction per non-label line).
        int instructionCount = countInstructions(out) + countInstructions(carriers);
        if(instructionCount > LExecutor.maxInstructions){
            String hint = mode == FuncMode.inline ? " Switch to normal mode to share function bodies." : "";
            throw new IllegalArgumentException("Compiled program has " + instructionCount + " instructions; maximum is " + LExecutor.maxInstructions + "." + hint);
        }

        appendMarker(out, sugar);
        out.append(carriers);
        return out.toString();
    }

    /** The merged library for editing a stored program: embedded functions first, then
     *  local functions the embedded ones do not shadow. Both sources are salvaged through
     *  {@link SugarFunctions#sanitizedLibrary} before merging, so a damaged local file yields
     *  an index with the recoverable functions instead of silently null. The text mirrors the
     *  index, so the compiler can re-extract the used subset from it when saving
     *  (self-correcting). */
    public static EffectiveLibrary effectiveLibrary(String code, SugarFunctions.LibraryIndex local, String localText){
        // the caller-provided local index is advisory; the sanitized local text is authoritative
        String embeddedText = libraryFromCode(code);
        String embedded = embeddedText == null ? "" : embeddedText.trim();
        Set<String> embeddedNames = new HashSet<>();
        String embeddedSource = "";
        SugarFunctions.SanitizedLibrary sanitizedEmbedded = null;
        if(!embedded.isEmpty()){
            sanitizedEmbedded = SugarFunctions.sanitizedLibrary(embedded);
            embeddedSource = sanitizedEmbedded.text;
            for(String name : sanitizedEmbedded.index.functions.keySet()) embeddedNames.add(name);
        }
        StringBuilder text = new StringBuilder(embeddedSource);
        SugarFunctions.SanitizedLibrary sanitizedLocal = null;
        if(localText != null && !localText.trim().isEmpty()){
            sanitizedLocal = SugarFunctions.sanitizedLibrary(localText);
            Set<String> extras = new HashSet<>(sanitizedLocal.index.functions.keySet());
            extras.removeAll(embeddedNames);
            if(!extras.isEmpty()){
                // extract from the sanitized text: raw slices could copy damaged duplicates
                String extracted = SugarFunctions.extractLibrarySource(sanitizedLocal.text, extras);
                if(!extracted.isEmpty()){
                    if(text.length() > 0) text.append('\n');
                    text.append(extracted);
                }
            }
        }
        String effectiveText = text.toString();
        // nothing to merge (no embedded carrier and no local file) behaves like an unavailable
        // library; otherwise the sanitized merge is always valid, but keeps the sources' damage
        // state so unresolved calls can point the user at the repair path
        SugarFunctions.LibraryIndex effective = null;
        if(!effectiveText.trim().isEmpty()){
            effective = SugarFunctions.sanitizedLibrary(effectiveText).index;
            boolean damaged = (sanitizedEmbedded != null && sanitizedEmbedded.damaged)
                || (sanitizedLocal != null && sanitizedLocal.damaged);
            if(damaged){
                effective.damaged = true;
                if(effective.warnings.isEmpty()){
                    effective.warnings = new java.util.ArrayList<>();
                    if(sanitizedEmbedded != null && !sanitizedEmbedded.warnings.isEmpty()){
                        effective.warnings.addAll(sanitizedEmbedded.warnings);
                    }
                    if(sanitizedLocal != null && !sanitizedLocal.warnings.isEmpty()){
                        effective.warnings.addAll(sanitizedLocal.warnings);
                    }
                }
            }
        }
        return new EffectiveLibrary(effective, effectiveText);
    }

    /** Parsed library index plus the text it was built from. */
    public static final class EffectiveLibrary{
        public final SugarFunctions.LibraryIndex index;
        public final String text;

        public EffectiveLibrary(SugarFunctions.LibraryIndex index, String text){
            this.index = index;
            this.text = text;
        }
    }

    private static void appendCarrier(StringBuilder out, String prefix, String text){
        String encoded = encode(text);
        if(encoded.length() <= carrierMaxChars){
            out.append(prefix).append(encoded).append("\"\n");
        }
    }

    private static boolean containsCarrier(String code, String prefix){
        String normalized = code.replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        for(int i = lines.length - 1; i >= 0; i--){
            if(lines[i].startsWith(prefix) && lines[i].endsWith("\"")) return true;
        }
        return false;
    }

    /** UTF-8 base64 via arc's coder (minSdk 21 forbids java.util.Base64). */
    private static String encode(String text){
        return new String(Base64Coder.encode(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String decode(String text){
        return new String(Base64Coder.decode(text), StandardCharsets.UTF_8);
    }

    public static boolean[] invalidStatements(Seq<LStatement> statements){
        boolean[] invalid = new boolean[statements.size];
        boolean[] claimed = new boolean[statements.size];

        for(int i = 0; i < statements.size; i++){
            if(!(statements.get(i) instanceof BeginStatement begin)) continue;
            int destination = begin.destIndex;
            if(destination <= i || destination >= statements.size || !(statements.get(destination) instanceof BlockEndStatement)){
                invalid[i] = true;
            }else if(claimed[destination]){
                invalid[i] = true;
            }else{
                claimed[destination] = true;
            }
        }

        for(int i = 0; i < statements.size; i++){
            if(statements.get(i) instanceof BlockEndStatement && !claimed[i]) invalid[i] = true;
        }

        Deque<Integer> ends = new ArrayDeque<>();
        for(int i = 0; i < statements.size; i++){
            while(!ends.isEmpty() && ends.peek() < i) ends.pop();
            if(statements.get(i) instanceof BeginStatement begin && begin.destIndex > i && begin.destIndex < statements.size){
                if(!ends.isEmpty() && begin.destIndex > ends.peek()) invalid[i] = true;
                ends.push(begin.destIndex);
            }
        }

        int[] switchOwner = switchOwners(statements);
        int[] breakOwner = breakOwners(statements);
        int[] continueOwner = continueOwners(statements);
        int[] ifOwner = ifOwners(statements);
        for(int i = 0; i < statements.size; i++){
            if(statements.get(i) instanceof CaseStatement && switchOwner[i] < 0){
                invalid[i] = true;
            }
            if(statements.get(i) instanceof BreakStatement && breakOwner[i] < 0){
                invalid[i] = true;
            }
            if(statements.get(i) instanceof ContinueStatement && continueOwner[i] < 0){
                invalid[i] = true;
            }
            if(statements.get(i) instanceof ElseIfStatement && ifOwner[i] < 0){
                invalid[i] = true;
            }
            if(statements.get(i) instanceof ElseStatement && ifOwner[i] < 0){
                invalid[i] = true;
            }
        }

        // an if chain may have at most one else, and no elif may follow it (shared rule,
        // also enforced by the compile path and the library builder)
        boolean[] ifBad = SugarFunctions.ifChainViolations(statements, ifOwner);
        for(int i = 0; i < statements.size; i++){
            if(ifBad[i]) invalid[i] = true;
        }

        // function calls whose name resolves nowhere are marked invalid (library is loaded lazily)
        Set<String> local = new HashSet<>();
        for(LStatement statement : statements){
            if(statement instanceof FuncDefStatement def) local.add(def.name);
        }
        SugarFunctions.LibraryIndex library = SugarFunctions.library();
        for(int i = 0; i < statements.size; i++){
            if(statements.get(i) instanceof FuncCallStatement call && !local.contains(call.name)
                && (library == null || !library.functions.containsKey(call.name))){
                invalid[i] = true;
            }
        }
        return invalid;
    }

    public static FuncMode currentMode(){
        try{
            if(Core.settings != null){
                return FuncMode.parse(Core.settings.getString("logicsugar.funcMode", "normal"));
            }
        }catch(Exception ignored){
            // settings unavailable (e.g. self-test environment): fall back to normal
        }
        return FuncMode.normal;
    }

    private static boolean containsSugar(Seq<LStatement> statements){
        for(LStatement statement : statements){
            if(statement.getClass().getEnclosingClass() == SugarStatements.class) return true;
        }
        return false;
    }

    private static void validatePairs(Seq<LStatement> statements){
        boolean[] claimed = new boolean[statements.size];
        for(int i = 0; i < statements.size; i++){
            LStatement statement = statements.get(i);
            if(!(statement instanceof BeginStatement begin)) continue;
            int destination = begin.destIndex;
            if(destination <= i || destination >= statements.size || !(statements.get(destination) instanceof BlockEndStatement)){
                throw error(statement.typeName(), i, "must point to a block end below it");
            }
            if(claimed[destination]) throw error(statement.typeName(), i, "shares an end block with another begin block");
            claimed[destination] = true;
        }

        for(int i = 0; i < statements.size; i++){
            LStatement statement = statements.get(i);
            if(statement instanceof BlockEndStatement && !claimed[i]){
                throw error(statement.typeName(), i, "has no matching begin block");
            }
        }

        Deque<Integer> ends = new ArrayDeque<>();
        for(int i = 0; i < statements.size; i++){
            while(!ends.isEmpty() && ends.peek() < i) ends.pop();
            if(statements.get(i) instanceof BeginStatement begin){
                if(!ends.isEmpty() && begin.destIndex > ends.peek()){
                    throw error(begin.typeName(), i, "crosses another structured block");
                }
                ends.push(begin.destIndex);
            }
        }
    }

    private static int[] switchOwners(Seq<LStatement> statements){
        int[] result = new int[statements.size];
        java.util.Arrays.fill(result, -1);
        Deque<Integer> stack = new ArrayDeque<>();
        for(int i = 0; i < statements.size; i++){
            while(!stack.isEmpty() && ((SwitchBeginStatement)statements.get(stack.peek())).destIndex < i) stack.pop();
            if(!stack.isEmpty()) result[i] = stack.peek();
            if(statements.get(i) instanceof SwitchBeginStatement) stack.push(i);
        }
        return result;
    }

    /** Returns the innermost enclosing if block index for each statement. */
    private static int[] ifOwners(Seq<LStatement> statements){
        int[] result = new int[statements.size];
        java.util.Arrays.fill(result, -1);
        Deque<Integer> stack = new ArrayDeque<>();
        for(int i = 0; i < statements.size; i++){
            while(!stack.isEmpty() && ((IfBeginStatement)statements.get(stack.peek())).destIndex < i) stack.pop();
            if(!stack.isEmpty()) result[i] = stack.peek();
            if(statements.get(i) instanceof IfBeginStatement) stack.push(i);
        }
        return result;
    }

    /** Returns the innermost enclosing structure that accepts a break statement. */
    private static int[] breakOwners(Seq<LStatement> statements){
        int[] result = new int[statements.size];
        java.util.Arrays.fill(result, -1);
        Deque<Integer> stack = new ArrayDeque<>();
        for(int i = 0; i < statements.size; i++){
            while(!stack.isEmpty() && ((BeginStatement)statements.get(stack.peek())).destIndex < i) stack.pop();
            if(!stack.isEmpty()) result[i] = stack.peek();
            if(statements.get(i) instanceof WhileBeginStatement || statements.get(i) instanceof SwitchBeginStatement
                || statements.get(i) instanceof ForBeginStatement) stack.push(i);
        }
        return result;
    }
    
    /** Returns the innermost enclosing loop that accepts a continue statement. */
    private static int[] continueOwners(Seq<LStatement> statements){
        int[] result = new int[statements.size];
        java.util.Arrays.fill(result, -1);
        Deque<Integer> stack = new ArrayDeque<>();
        for(int i = 0; i < statements.size; i++){
            while(!stack.isEmpty() && ((BeginStatement)statements.get(stack.peek())).destIndex < i) stack.pop();
            if(!stack.isEmpty()) result[i] = stack.peek();
            if(statements.get(i) instanceof WhileBeginStatement || statements.get(i) instanceof ForBeginStatement) stack.push(i);
        }
        return result;
    }

    private static int countInstructions(StringBuilder out){
        int count = 0;
        int index = 0;
        while(index < out.length()){
            int end = out.indexOf("\n", index);
            if(end < 0) end = out.length();
            String line = out.substring(index, end);
            if(!line.isEmpty() && !line.endsWith(":")) count++;
            index = end + 1;
        }
        return count;
    }

    private static IllegalArgumentException error(String block, int index, String detail){
        return new IllegalArgumentException(block + " at statement " + index + " " + detail + ".");
    }

    private static void appendMarker(StringBuilder out, String sugar){
        out.append(markerBegin).append('\n');
        String normalized = sugar.replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        int count = lines.length;
        if(count > 0 && lines[count - 1].isEmpty()) count--;
        for(int i = 0; i < count; i++) out.append(markerLine).append(lines[i]).append('\n');
        out.append(markerEnd).append('\n');
    }
}
