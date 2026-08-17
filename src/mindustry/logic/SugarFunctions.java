package mindustry.logic;

import arc.struct.Seq;
import logicsugar.assist.expr.ExprCompiler;
import mindustry.logic.LStatements.GetLinkStatement;
import mindustry.logic.LStatements.InvalidStatement;
import mindustry.logic.LStatements.JumpStatement;
import mindustry.logic.LStatements.OperationStatement;
import mindustry.logic.LStatements.PackColorStatement;
import mindustry.logic.LStatements.ReadStatement;
import mindustry.logic.LStatements.SensorStatement;
import mindustry.logic.LStatements.SetStatement;
import mindustry.logic.SugarCompiler.FuncMode;
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
import mindustry.logic.SugarStatements.ReturnStatement;
import mindustry.logic.SugarStatements.SwitchBeginStatement;
import mindustry.logic.SugarStatements.WhileBeginStatement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Function machinery for Logic Sugar: local functions (defined inside a processor) and
 * library functions (defined in a global library file) share one compilation pipeline.
 *
 * <p>Pipeline: {@link #analyze} extracts function definitions, validates them (names,
 * structure, jump boundaries, recursion, call resolution), prepares bodies (remapping
 * statement indices into body-relative space, renaming compiler temporaries and, for
 * library functions, mangling every name the body writes), and produces the main
 * statement list with function blocks removed. {@link #lower} then emits mlog text,
 * expanding each call site either inline ({@code INLINE}) or through an @counter
 * subroutine ({@code NORMAL}).
 *
 * <p>NORMAL mode calling convention (no recursion, so one return variable per function
 * suffices; LExecutor increments @counter before executing the instruction, so reading
 * it inside the "set" yields the index of the next instruction):
 * <pre>
 *   set __ls_func_f_ret @counter
 *   op add __ls_func_f_ret __ls_func_f_ret 2
 *   jump __ls_func_f_entry always x false
 *   ... return point ...
 * </pre>
 * The function body is hoisted once at the end of the program and ends with
 * {@code set @counter __ls_func_f_ret}.
 *
 * <p>INLINE mode emits a fresh copy of the body per call site; every compiler-generated
 * label and guard variable is prefixed with {@code __ls_i_<callId>_}.
 *
 * <p>Library (方案2) semantics: a function may not modify caller variables. Every name
 * the body writes (plus its parameters) is mangled to {@code __ls_func_<name>_<name>};
 * read-only names stay untouched so the function can still read the caller's globals.
 * {@code @}-prefixed system variables and {@code cellN}/{@code bankN}/{@code memoryN}
 * storage devices are exempt.
 */
public final class SugarFunctions{
    private SugarFunctions(){}

    /** Compiler-reserved prefix; user function/parameter names may not use it. */
    public static final String reservedPrefix = "__ls_";

    /** Sentinels for jumps inside a function body that target the function's own end block. */
    public static final int exitTarget = -2;

    /** Where library statements come from. Installed by the mod; tests install their own. */
    public interface LibrarySource{
        /** @return the parsed and validated library index, or null when unavailable. */
        LibraryIndex load();
    }

    private static LibrarySource librarySource;

    /** Installs the library file source (called by the mod). */
    public static void setLibrarySource(LibrarySource source){
        librarySource = source;
    }

    /** Returns the current library index, or null when none is available. */
    public static LibraryIndex library(){
        if(librarySource == null) return null;
        try{
            return librarySource.load();
        }catch(IllegalArgumentException e){
            // Damaged library files behave like an empty library; processors that call a
            // missing function report a clear error at compile time.
            return null;
        }
    }

    /** One compiled function: parameters, body statements and analysis results. */
    public static final class Function{
        public final String name;
        public final List<String> params = new ArrayList<>();
        /** Body statements; indices are body-relative, jumps to the function end use {@link #exitTarget}. */
        public Seq<LStatement> body = new Seq<>();
        /** Function-local names (library functions only): original -> mangled. */
        public final Map<String, String> mangle = new HashMap<>();
        /** Resolved callee names (local or library). */
        public final Set<String> callees = new HashSet<>();
        public boolean library;
        public boolean hasValueReturn;

        Function(String name, boolean library){
            this.name = name;
            this.library = library;
        }

        /** Name the k-th parameter is bound to at call sites. */
        public String bindingName(int k){
            return library ? "__ls_func_" + name + "_" + params.get(k) : params.get(k);
        }

        public String entryName(){ return "__ls_func_" + name + "_entry"; }
        public String exitName(){ return "__ls_func_" + name + "_exit"; }
        public String retName(){ return "__ls_func_" + name + "_ret"; }
        public String resultName(){ return "__ls_func_" + name + "_result"; }
    }

    /** Parsed and validated global function library. */
    public static final class LibraryIndex{
        public final Map<String, Function> functions = new LinkedHashMap<>();
        /** True when the index was salvaged from a damaged library file and may be missing
         *  definitions that could not be recovered. */
        public boolean damaged;
        /** Problems that were repaired while loading a damaged library file. */
        public List<String> warnings = new ArrayList<>();
    }

    /** Local functions plus the resolved main statement list. */
    public static final class FunctionSet{
        public final Map<String, Function> functions = new LinkedHashMap<>();
        public final LibraryIndex library;
        /** Main program statements with function definitions removed and indices remapped. */
        public Seq<LStatement> main = new Seq<>();
        /** Resolved callee names of the main program. */
        public final List<String> mainCalls = new ArrayList<>();
        /** Names reachable from the main program (transitive closure). */
        public final Set<String> reachable = new HashSet<>();

        FunctionSet(LibraryIndex library){
            this.library = library;
        }

        /** Local function first, then library (local shadows library). */
        public Function resolve(String name){
            Function local = functions.get(name);
            return local != null ? local : library != null ? library.functions.get(name) : null;
        }

        /** Reachable functions in definition order (local functions first, then library).
         *  A shadowed library function is skipped: its name resolves to the local one. */
        public List<Function> hoistOrder(){
            List<Function> result = new ArrayList<>();
            for(Function function : functions.values()){
                if(reachable.contains(function.name) && resolve(function.name) == function) result.add(function);
            }
            if(library != null){
                for(Function function : library.functions.values()){
                    if(reachable.contains(function.name) && resolve(function.name) == function) result.add(function);
                }
            }
            return result;
        }
    }

    /** Shared call-site id counter (INLINE mode prefixes). */
    public static final class CallIds{
        private int next;
        public int next(){ return next++; }
    }

    /**
     * Validates the whole program and prepares function bodies. Mutates statement indices
     * (jump targets and structure ends are remapped to body-relative / main-relative space).
     */
    public static FunctionSet analyze(Seq<LStatement> statements, LibraryIndex library){
        FunctionSet set = new FunctionSet(library);
        int n = statements.size;

        // --- 1. locate function definitions, reject nesting -------------------------------
        int[] endOf = new int[n];
        Arrays.fill(endOf, -1);
        int[] beginOf = new int[n];
        Arrays.fill(beginOf, -1);
        int[] ownerBody = new int[n];
        Arrays.fill(ownerBody, -1);
        Deque<Integer> openEnds = new ArrayDeque<>();
        for(int i = 0; i < n; i++){
            while(!openEnds.isEmpty() && openEnds.peek() < i) openEnds.pop();
            LStatement statement = statements.get(i);
            if(statement instanceof FuncDefStatement){
                if(!openEnds.isEmpty()){
                    throw error("funcdef", i, "must be at the top level; nested function definitions are not supported");
                }
                BeginStatement begin = (BeginStatement)statement;
                if(begin.destIndex <= i || begin.destIndex >= n || !(statements.get(begin.destIndex) instanceof BlockEndStatement)){
                    throw error("funcdef", i, "must point to a block end below it");
                }
                endOf[i] = begin.destIndex;
                beginOf[begin.destIndex] = i;
                for(int k = i + 1; k < begin.destIndex; k++) ownerBody[k] = i;
            }
            if(statement instanceof BeginStatement begin){
                openEnds.push(begin.destIndex);
            }
        }

        // --- 2. jumps may not cross function boundaries -----------------------------------
        for(int j = 0; j < n; j++){
            if(!(statements.get(j) instanceof JumpStatement jump)) continue;
            int target = jump.destIndex;
            if(target < 0 || target > n){
                throw error("jump", j, "has no valid destination");
            }
            int owner = ownerBody[j];
            if(owner >= 0){
                int end = endOf[owner];
                if(target < owner + 1 || target > end){
                    throw error("jump", j, "jumps across the boundary of function '" + funcName(statements, owner)
                        + "'; function bodies must be self-contained");
                }
            }else{
                for(int s = 0; s < n; s++){
                    if(endOf[s] < 0) continue;
                    int e = endOf[s];
                    if(target > s && target < e){
                        throw error("jump", j, "jumps into the body of function '" + funcName(statements, s) + "', which is not allowed");
                    }
                    if(target == s || target == e){
                        throw error("jump", j, "targets the boundary of function '" + funcName(statements, s) + "'; jumps may not cross function definitions");
                    }
                }
            }
        }

        // --- 3. build local functions ------------------------------------------------------
        for(int i = 0; i < n; i++){
            if(endOf[i] < 0) continue;
            Function function = buildFunction(statements, i, endOf[i], false);
            if(set.functions.containsKey(function.name)){
                throw error("funcdef", i, "duplicate function name '" + function.name + "'");
            }
            set.functions.put(function.name, function);
        }

        // --- 4. resolve calls, validate arity and result usage ----------------------------
        for(int i = 0; i < n; i++){
            if(ownerBody[i] >= 0) continue;
            if(endOf[i] >= 0 || beginOf[i] >= 0) continue;
            LStatement statement = statements.get(i);
            if(statement instanceof FuncCallStatement call){
                resolveCall(call, null, set, i);
            }else if(statement instanceof ReturnStatement){
                throw error("return", i, "is outside a function");
            }
        }
        for(Function function : set.functions.values()){
            for(int i = 0; i < function.body.size; i++){
                LStatement statement = function.body.get(i);
                if(statement instanceof FuncCallStatement call){
                    resolveCall(call, function, set, i);
                }
            }
        }

        // --- 5. prepare bodies: rename temps, mangle library locals -----------------------
        for(Function function : set.functions.values()){
            prepBody(function);
        }
        if(library != null){
            for(Function function : library.functions.values()){
                if(!function.mangle.isEmpty()) continue;
                prepBody(function);
            }
        }

        // --- 6. recursion detection (full call graph, including unreachable functions) ----
        checkRecursion(set);

        // --- 7. reachability (NORMAL mode hoists only reachable bodies) --------------------
        Deque<String> queue = new ArrayDeque<>(set.mainCalls);
        set.reachable.addAll(set.mainCalls);
        while(!queue.isEmpty()){
            String name = queue.poll();
            Function function = set.resolve(name);
            if(function == null) continue;
            for(String callee : function.callees){
                if(set.reachable.add(callee)) queue.add(callee);
            }
        }

        // --- 8. visible main list: strip definitions, remap indices ------------------------
        int[] visibleIndex = new int[n];
        Arrays.fill(visibleIndex, -1);
        for(int i = 0; i < n; i++){
            if(ownerBody[i] >= 0 || endOf[i] >= 0 || beginOf[i] >= 0) continue;
            visibleIndex[i] = set.main.size;
            set.main.add(statements.get(i));
        }
        for(int i = 0; i < n; i++){
            if(visibleIndex[i] < 0) continue;
            LStatement statement = statements.get(i);
            if(statement instanceof BeginStatement begin && begin.destIndex >= 0 && begin.destIndex < n){
                begin.destIndex = visibleIndex[begin.destIndex];
            }else if(statement instanceof JumpStatement jump && jump.destIndex >= 0 && jump.destIndex < n){
                jump.destIndex = visibleIndex[jump.destIndex];
            }
        }
        return set;
    }

    /**
     * Parses and validates a library file. The library may contain function definitions
     * only; library bodies may only call other library functions.
     */
    public static LibraryIndex buildLibrary(Seq<LStatement> statements){
        LibraryIndex index = new LibraryIndex();
        int n = statements.size;
        if(n == 0) return index;

        int[] endOf = new int[n];
        Arrays.fill(endOf, -1);
        int[] beginOf = new int[n];
        Arrays.fill(beginOf, -1);
        int[] ownerBody = new int[n];
        Arrays.fill(ownerBody, -1);
        Deque<Integer> openEnds = new ArrayDeque<>();
        for(int i = 0; i < n; i++){
            while(!openEnds.isEmpty() && openEnds.peek() < i) openEnds.pop();
            LStatement statement = statements.get(i);
            if(statement instanceof FuncDefStatement){
                if(!openEnds.isEmpty()){
                    throw error("funcdef", i, "must be at the top level; nested function definitions are not supported");
                }
                BeginStatement begin = (BeginStatement)statement;
                if(begin.destIndex <= i || begin.destIndex >= n || !(statements.get(begin.destIndex) instanceof BlockEndStatement)){
                    throw error("funcdef", i, "must point to a block end below it");
                }
                endOf[i] = begin.destIndex;
                beginOf[begin.destIndex] = i;
                for(int k = i + 1; k < begin.destIndex; k++) ownerBody[k] = i;
            }else if(ownerBody[i] < 0 && beginOf[i] < 0){
                throw error(statement.typeName(), i, "is not allowed in the function library; only function definitions may appear at the top level");
            }
            if(statement instanceof BeginStatement begin){
                openEnds.push(begin.destIndex);
            }
        }
        for(int i = 0; i < n; i++){
            if(beginOf[i] >= 0 && endOf[beginOf[i]] != i){
                throw error("blockend", i, "is not matched with a function definition");
            }
            if(endOf[i] < 0 && beginOf[i] < 0 && ownerBody[i] < 0){
                throw error("blockend", i, "is not matched with a function definition");
            }
        }

        // jump boundary rules identical to local functions
        for(int j = 0; j < n; j++){
            if(!(statements.get(j) instanceof JumpStatement jump)) continue;
            int target = jump.destIndex;
            if(target < 0 || target > n){
                throw error("jump", j, "has no valid destination");
            }
            int owner = ownerBody[j];
            if(owner >= 0){
                int end = endOf[owner];
                if(target < owner + 1 || target > end){
                    throw error("jump", j, "jumps across the boundary of function '" + funcName(statements, owner)
                        + "'; function bodies must be self-contained");
                }
            }else{
                for(int s = 0; s < n; s++){
                    if(endOf[s] < 0) continue;
                    int e = endOf[s];
                    if(target > s && target < e){
                        throw error("jump", j, "jumps into the body of function '" + funcName(statements, s) + "', which is not allowed");
                    }
                    if(target == s || target == e){
                        throw error("jump", j, "targets the boundary of function '" + funcName(statements, s) + "'; jumps may not cross function definitions");
                    }
                }
            }
        }

        for(int i = 0; i < n; i++){
            if(endOf[i] < 0) continue;
            Function function = buildFunction(statements, i, endOf[i], true);
            if(index.functions.containsKey(function.name)){
                throw error("funcdef", i, "duplicate function name '" + function.name + "'");
            }
            index.functions.put(function.name, function);
        }

        // library bodies may only call library functions; validate before mangling
        for(Function function : index.functions.values()){
            int[] switchOwner = switchOwners(function.body);
            int[] breakOwner = breakOwners(function.body);
            int[] ifOwner = ifOwners(function.body);
            boolean[] ifBad = ifChainViolations(function.body, ifOwner);
            for(int i = 0; i < function.body.size; i++){
                LStatement statement = function.body.get(i);
                if(statement instanceof CaseStatement && switchOwner[i] < 0){
                    throw error("case", i, "in library function '" + function.name + "' is outside a switch");
                }
                if(statement instanceof BreakStatement && breakOwner[i] < 0){
                    throw error("break", i, "in library function '" + function.name + "' is outside a loop or switch");
                }
                if(statement instanceof ElseIfStatement && ifOwner[i] < 0){
                    throw error("elif", i, "in library function '" + function.name + "' is outside an if");
                }
                if(statement instanceof ElseStatement && ifOwner[i] < 0){
                    throw error("else", i, "in library function '" + function.name + "' is outside an if");
                }
                if(ifBad[i]){
                    throw error(statement instanceof ElseStatement ? "else" : "elif", i,
                        "in library function '" + function.name + "' appears after else (or is a duplicate else) in the same if chain");
                }
                if(statement instanceof FuncCallStatement call){
                    Function target = index.functions.get(call.name);
                    if(target == null){
                        throw error("funccall", i, "in library function '" + function.name
                            + "' calls undefined library function '" + call.name + "'");
                    }
                    int argc = splitArgs(call.args).size();
                    if(argc != target.params.size()){
                        throw error("funccall", i, "in library function '" + function.name + "' calls '" + call.name
                            + "' with " + argc + " argument(s) but it expects " + target.params.size());
                    }
                    if(!call.result.isEmpty() && !target.hasValueReturn){
                        throw error("funccall", i, "in library function '" + function.name + "' requests a result from '"
                            + call.name + "' but its body never returns a value");
                    }
                    function.callees.add(call.name);
                }
            }
        }

        // library recursion check (full graph)
        Map<String, Function> all = new LinkedHashMap<>(index.functions);
        checkRecursion(all);

        // mangling happens exactly once; cached by the caller
        for(Function function : index.functions.values()){
            prepBody(function);
        }
        return index;
    }

    /**
     * Extracts the definitions of the requested functions from a library text into a
     * self-contained library text (functions in their original order). Used to embed the
     * used subset of the library into compiled processor code so other machines can
     * recompile the program without the local library file.
     *
     * <p>Statement indices are remapped from the full-text space into the extracted-slice
     * space: every begin/jump target inside the slice shifts by the slice start, so the
     * funcdef keeps pointing at its own blockend. When the extracted text is re-validated
     * with {@link #buildLibrary}, {@link #buildFunction} converts a jump to the blockend
     * into {@link #exitTarget} as usual, and body targets become body-relative.
     */
    public static String extractLibrarySource(String libraryText, Set<String> usedNames){
        Seq<LStatement> statements = LAssembler.read(libraryText, true);
        int n = statements.size;
        int[] endOf = new int[n];
        Arrays.fill(endOf, -1);
        for(int i = 0; i < n; i++){
            if(statements.get(i) instanceof FuncDefStatement def && usedNames.contains(def.name)){
                int end = ((BeginStatement)statements.get(i)).destIndex;
                if(end > i && end < n) endOf[i] = end;
            }
        }
        StringBuilder out = new StringBuilder();
        int appended = 0;
        for(int i = 0; i < n; i++){
            int e = endOf[i];
            if(e < 0) continue;
            appended += copySlice(statements, i, e, out, appended);
        }
        return out.toString();
    }

    /**
     * Copies statements [i..e] into the output, remapping begin/jump targets from the
     * full-text coordinate space into the output space: every target shifts by (base - i)
     * (base = statements already appended), so a funcdef keeps pointing at its own blockend
     * when consecutive slices are concatenated. Returns the number of statements appended.
     */
    private static int copySlice(Seq<LStatement> statements, int i, int e, StringBuilder out, int base){
        int appended = 0;
        for(int k = i; k <= e; k++){
            LStatement statement = statements.get(k);
            // BeginStatement.copy() resets destIndex, so capture the original first.
            int dest = -1;
            if(statement instanceof BeginStatement begin){
                dest = begin.destIndex;
            }else if(statement instanceof JumpStatement jump){
                dest = jump.destIndex;
            }
            LStatement copy = statement.copy();
            if(copy == null){
                throw new IllegalArgumentException("internal error: failed to copy a library statement");
            }
            if(copy instanceof BeginStatement begin){
                begin.destIndex = dest - i + base;
            }else if(copy instanceof JumpStatement jump){
                jump.destIndex = dest - i + base;
            }
            copy.write(out);
            out.append('\n');
            appended++;
        }
        return appended;
    }

    /** Result of salvaging a (possibly damaged) library text. */
    public static final class SanitizedLibrary{
        /** Sanitized library text (byte-identical to the input when the library was valid). */
        public final String text;
        /** Partial index built from the functions that could be recovered. */
        public final LibraryIndex index;
        /** Problems that were repaired; empty when the library was valid. */
        public final List<String> warnings;
        /** True when anything had to be repaired. */
        public final boolean damaged;

        public SanitizedLibrary(String text, LibraryIndex index, List<String> warnings, boolean damaged){
            this.text = text;
            this.index = index;
            this.warnings = warnings;
            this.damaged = damaged;
        }
    }

    /** The splice points (statement index pairs) around top-level function definitions.
     *  Structurally broken funcdefs (no valid block end) are recorded as warnings. */
    private static List<int[]> topLevelFuncdefSlices(Seq<LStatement> statements, List<String> warnings){
        List<int[]> slices = new ArrayList<>();
        int n = statements.size;
        for(int i = 0; i < n; ){
            if(statements.get(i) instanceof FuncDefStatement){
                int dest = ((BeginStatement)statements.get(i)).destIndex;
                if(dest > i && dest < n && statements.get(dest) instanceof BlockEndStatement){
                    slices.add(new int[]{i, dest});
                    i = dest + 1; // body + end belong to this slice (nested funcdefs damage it)
                }else{
                    warnings.add("funcdef at statement " + i + " is damaged and was skipped");
                    i++;
                }
            }else{
                i++;
            }
        }
        return slices;
    }

    /**
     * Salvages a library text function-by-function instead of rejecting the whole file when
     * one definition is damaged: structurally broken or un-rewritable funcdefs are skipped,
     * duplicate names keep the last definition, top-level stray statements are discarded, and
     * survivors that call removed functions (or form call cycles) are removed too. The output
     * is a sanitized text whose index is guaranteed to re-validate with {@link #buildLibrary};
     * a fully valid input is returned byte-identical with no warnings.
     */
    public static SanitizedLibrary sanitizedLibrary(String text){
        List<String> warnings = new ArrayList<>();
        Seq<LStatement> statements;
        try{
            statements = LAssembler.read(text, true);
        }catch(Throwable t){
            String message = "the library text cannot be parsed: " + t.getMessage();
            return new SanitizedLibrary("", new LibraryIndex(), List.of(message), true);
        }

        // Fast path: the whole library validates unchanged (output must equal the input).
        try{
            return new SanitizedLibrary(text, buildLibrary(statements), List.of(), false);
        }catch(IllegalArgumentException ignored){
            // buildLibrary remaps the bodies it processed before failing; parse again fresh
            statements = LAssembler.read(text, true);
        }

        // identify top-level funcdef slices in source order
        List<int[]> slices = topLevelFuncdefSlices(statements, warnings);

        // top-level statements that belong to no funcdef slice are discarded as junk
        boolean[] covered = new boolean[statements.size];
        for(int[] slice : slices){
            Arrays.fill(covered, slice[0], slice[1] + 1, true);
        }
        int strayCount = 0;
        for(int i = 0; i < statements.size; i++){
            if(!covered[i] && !(statements.get(i) instanceof FuncDefStatement)) strayCount++;
        }
        if(strayCount > 0){
            warnings.add("top-level statement(s) outside any function were discarded");
        }

        // salvage each slice independently: build the function body without requiring its
        // callees to exist yet (cross-function call validation happens below); duplicates
        // keep the last definition
        Map<String, Function> survivors = new LinkedHashMap<>();
        Map<String, int[]> survivorSlices = new LinkedHashMap<>();
        for(int[] slice : slices){
            String name;
            Function function;
            try{
                StringBuilder copyText = new StringBuilder();
                copySlice(statements, slice[0], slice[1], copyText, 0);
                Seq<LStatement> single = LAssembler.read(copyText.toString(), true);
                if(single.size != slice[1] - slice[0] + 1){
                    throw new IllegalArgumentException("slice round-trip changed the statement count");
                }
                function = buildFunction(single, 0, single.size - 1, true);
                prepBody(function);
                name = function.name;
            }catch(RuntimeException e){
                warnings.add("funcdef at statement " + slice[0] + " is damaged and was skipped (" + e.getMessage() + ")");
                continue;
            }
            if(survivors.containsKey(name)){
                warnings.add("duplicate function name '" + name + "'; keeping the last definition (statement " + slice[0] + ")");
            }
            survivors.put(name, function);
            survivorSlices.put(name, slice);
        }

        // survivors whose in-library calls no longer resolve are removed (fixpoint)
        removeInvalidCallers(survivors, survivorSlices, warnings);

        // recursion among survivors would invalidate the whole library; drop the cycle members
        Set<String> cycles = cycleMembers(survivors);
        if(!cycles.isEmpty()){
            warnings.add("library recursion was detected; the functions " + cycles + " were removed");
            for(String name : cycles){
                survivors.remove(name);
                survivorSlices.remove(name);
            }
            removeInvalidCallers(survivors, survivorSlices, warnings);
        }

        // re-serialize the survivors and prove the result re-validates (safety net: the
        // sanitizer must never hand back a text that buildLibrary would reject)
        String output = serializeSlices(statements, survivorSlices.values());
        LibraryIndex index = new LibraryIndex();
        while(true){
            try{
                index = buildLibrary(LAssembler.read(output, true));
                break;
            }catch(IllegalArgumentException e){
                if(survivorSlices.isEmpty()){
                    warnings.add("the damaged library could not be salvaged; it was emptied (" + e.getMessage() + ")");
                    break;
                }
                String last = null;
                for(String name : survivorSlices.keySet()) last = name;
                warnings.add("library function '" + last + "' was removed because the salvaged library still failed validation (" + e.getMessage() + ")");
                survivors.remove(last);
                survivorSlices.remove(last);
                output = serializeSlices(statements, survivorSlices.values());
            }
        }
        if(index.functions.isEmpty() && !warnings.isEmpty()){
            warnings.add("no usable functions could be recovered from the damaged library");
        }
        index.damaged = true;
        index.warnings = new ArrayList<>(warnings);
        return new SanitizedLibrary(output, index, warnings, true);
    }

    /** Removes survivors whose body calls do not resolve within the survivor set. */
    private static void removeInvalidCallers(Map<String, Function> survivors, Map<String, int[]> survivorSlices, List<String> warnings){
        boolean changed;
        do{
            changed = false;
            for(String name : new ArrayList<>(survivors.keySet())){
                if(!libraryBodyValid(survivors.get(name), survivors)){
                    warnings.add("library function '" + name
                        + "' was removed because it calls an undefined or invalid library function");
                    survivors.remove(name);
                    survivorSlices.remove(name);
                    changed = true;
                }
            }
        }while(changed);
    }

    /** Whether a salvaged function's body is self-contained and its calls resolve within the
     *  survivor set (mirrors buildLibrary's per-function call rules); also rebuilds the
     *  function's resolved callee set so recursion detection sees real edges. */
    private static boolean libraryBodyValid(Function function, Map<String, Function> survivors){
        int[] switchOwner = switchOwners(function.body);
        int[] breakOwner = breakOwners(function.body);
        function.callees.clear();
        for(int i = 0; i < function.body.size; i++){
            LStatement statement = function.body.get(i);
            if(statement instanceof CaseStatement && switchOwner[i] < 0) return false;
            if(statement instanceof BreakStatement && breakOwner[i] < 0) return false;
            if(statement instanceof FuncCallStatement call){
                Function target = survivors.get(call.name);
                if(target == null) return false;
                if(splitArgs(call.args).size() != target.params.size()) return false;
                if(!call.result.isEmpty() && !target.hasValueReturn) return false;
                function.callees.add(call.name);
            }
        }
        return true;
    }

    /** Serializes the given funcdef slices into one library text (source order, concatenated). */
    private static String serializeSlices(Seq<LStatement> statements, Collection<int[]> slices){
        StringBuilder out = new StringBuilder();
        int appended = 0;
        for(int[] slice : slices){
            appended += copySlice(statements, slice[0], slice[1], out, appended);
        }
        return out.toString();
    }

    /** Names that take part in at least one call cycle. */
    private static Set<String> cycleMembers(Map<String, Function> all){
        Set<String> inCycle = new HashSet<>();
        Map<String, Integer> state = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        for(String name : all.keySet()){
            if(state.get(name) == null) visitCycle(name, all, state, stack, inCycle);
        }
        return inCycle;
    }

    private static void visitCycle(String name, Map<String, Function> all, Map<String, Integer> state,
                                   Deque<String> stack, Set<String> inCycle){
        state.put(name, 1);
        stack.push(name);
        Function function = all.get(name);
        if(function != null){
            for(String callee : function.callees){
                if(!all.containsKey(callee)) continue;
                Integer seen = state.get(callee);
                if(seen == null){
                    visitCycle(callee, all, state, stack, inCycle);
                }else if(seen == 1){
                    for(String node : stack){
                        inCycle.add(node);
                        if(node.equals(callee)) break;
                    }
                }
            }
        }
        stack.pop();
        state.put(name, 2);
    }

    /** Splits a comma-separated parameter declaration list; empty entries are dropped. */
    static List<String> parseParams(String raw){
        List<String> result = new ArrayList<>();
        if(raw == null || raw.isEmpty()) return result;
        for(String part : raw.split(",", -1)){
            String param = part.trim();
            if(!param.isEmpty()) result.add(param);
        }
        return result;
    }

    /**
     * Looks up the parameter names of a function by name: local {@code func} definitions
     * shadow the library (same resolution order as {@link #analyze}). Returns {@code null}
     * when the function is not found or takes no parameters.
     */
    public static List<String> paramsOf(String name, Seq<LStatement> localStatements){
        if(localStatements != null){
            for(LStatement statement : localStatements){
                if(statement instanceof FuncDefStatement def && def.name.equals(name)){
                    List<String> params = parseParams(def.params);
                    return params.isEmpty() ? null : params;
                }
            }
        }
        LibraryIndex library = library();
        if(library != null){
            Function function = library.functions.get(name);
            if(function != null){
                return function.params.isEmpty() ? null : function.params;
            }
        }
        return null;
    }

    /** Extracts one function definition and remaps its body into body-relative space. */
    private static Function buildFunction(Seq<LStatement> statements, int s, int e, boolean library){
        FuncDefStatement def = (FuncDefStatement)statements.get(s);
        Function function = new Function(def.name, library);
        validateName(def.name, "function");
        for(String param : parseParams(def.params)){
            validateName(param, "parameter");
            if(function.params.contains(param)){
                throw error("funcdef", s, "duplicate parameter '" + param + "' in function '" + def.name + "'");
            }
            function.params.add(param);
        }
        for(int k = s + 1; k < e; k++){
            LStatement statement = statements.get(k);
            if(statement instanceof FuncDefStatement){
                throw error("funcdef", k, "must be at the top level; nested function definitions are not supported");
            }
            if(statement instanceof ReturnStatement ret && !ret.expr.isEmpty()){
                function.hasValueReturn = true;
            }
            if(statement instanceof BeginStatement begin){
                begin.destIndex -= (s + 1);
            }else if(statement instanceof JumpStatement jump){
                if(jump.destIndex == e){
                    jump.destIndex = exitTarget;
                }else{
                    jump.destIndex -= (s + 1);
                }
            }
            function.body.add(statement);
        }
        return function;
    }

    /** Validates a call site and records the resolved callee. */
    private static void resolveCall(FuncCallStatement call, Function owner, FunctionSet set, int index){
        Function target = set.resolve(call.name);
        if(target == null){
            String hint = libraryProblemHint(set.library);
            if(owner != null && owner.library){
                throw error("funccall", index, "in library function '" + owner.name
                    + "' calls undefined library function '" + call.name + "' (library functions can only call other library functions)" + hint);
            }
            throw error("funccall", index, "calls undefined function '" + call.name + "'" + hint);
        }
        if(owner != null && owner.library && !target.library){
            throw error("funccall", index, "in library function '" + owner.name
                + "' calls undefined library function '" + call.name + "' (library functions can only call other library functions)");
        }
        int argc = splitArgs(call.args).size();
        if(argc != target.params.size()){
            throw error("funccall", index, "calls '" + call.name + "' with " + argc
                + " argument(s) but it expects " + target.params.size());
        }
        if(!call.result.isEmpty() && !target.hasValueReturn){
            throw error("funccall", index, "requests a result from '" + call.name
                + "' but its body never returns a value");
        }
        (owner == null ? set.mainCalls : owner.callees).add(call.name);
    }

    /** Localizes an "undefined function" error when the library state explains the miss.
     *  An otherwise-valid library keeps the plain message (a function really does not
     *  exist); a missing or damaged library points the user at the repair path. */
    private static String libraryProblemHint(LibraryIndex library){
        if(library == null){
            return ". The global function library is unavailable; check Settings -> Function Library";
        }
        if(library.damaged){
            String first = library.warnings.isEmpty() ? "the file needs repair" : library.warnings.get(0);
            return ". Note: the function library has errors (" + first + "); fix it in Settings -> Function Library";
        }
        return "";
    }

    private static void validateName(String name, String kind){
        if(!isIdentifier(name)){
            throw new IllegalArgumentException("'" + name + "' is not a valid " + kind + " name (letters, digits and underscores only)");
        }
        if(name.startsWith(reservedPrefix)){
            throw new IllegalArgumentException("'" + name + "' uses the reserved '" + reservedPrefix + "' prefix");
        }
    }

    private static boolean isIdentifier(String name){
        if(name == null || name.isEmpty()) return false;
        char first = name.charAt(0);
        if(!(Character.isLetter(first) || first == '_')) return false;
        for(int i = 1; i < name.length(); i++){
            char c = name.charAt(i);
            if(!(Character.isLetterOrDigit(c) || c == '_')) return false;
        }
        return true;
    }

    private static String funcName(Seq<LStatement> statements, int funcdefIndex){
        return ((FuncDefStatement)statements.get(funcdefIndex)).name;
    }

    /** Splits a comma-separated argument list, respecting parentheses. */
    public static List<String> splitArgs(String args){
        List<String> result = new ArrayList<>();
        if(args == null || args.trim().isEmpty()) return result;
        int depth = 0, start = 0;
        for(int i = 0; i < args.length(); i++){
            char c = args.charAt(i);
            if(c == '('){
                depth++;
            }else if(c == ')'){
                depth--;
            }else if(c == ',' && depth == 0){
                result.add(args.substring(start, i).trim());
                start = i + 1;
            }
        }
        result.add(args.substring(start).trim());
        return result;
    }

    // ===== body preparation ===============================================================

    /**
     * Renames compiler temporaries (so inlined bodies cannot clobber live caller temps) and,
     * for library functions, mangles every name the body writes plus its parameters.
     */
    private static void prepBody(Function function){
        Map<String, String> map = new HashMap<>();
        for(LStatement statement : function.body){
            collectTemps(statement, function, map);
        }
        if(function.library){
            Set<String> written = writtenNames(function);
            for(String name : written){
                if(!isExempt(name)){
                    map.putIfAbsent(name, "__ls_func_" + function.name + "_" + name);
                }
            }
            function.mangle.putAll(map);
        }
        function.body = rewriteBody(function, map);
    }

    /** Collects {@code _<digits>} temporary names from a statement's serialized text. */
    private static void collectTemps(LStatement statement, Function function, Map<String, String> map){
        StringBuilder text = new StringBuilder();
        statement.write(text);
        for(String token : text.toString().split("\\s+")){
            collectTempToken(token, function, map);
        }
        if(statement instanceof FuncCallStatement call){
            collectTempToken(call.args, function, map);
        }else if(statement instanceof ReturnStatement ret){
            collectTempToken(ret.expr, function, map);
        }
    }

    private static void collectTempToken(String token, Function function, Map<String, String> map){
        if(isTempToken(token) && !map.containsKey(token)){
            map.put(token, "__ls_f_" + function.name + "_" + token.substring(1));
        }
    }

    private static boolean isTempToken(String token){
        if(token.length() < 2 || token.charAt(0) != '_') return false;
        for(int i = 1; i < token.length(); i++){
            if(!Character.isDigit(token.charAt(i))) return false;
        }
        return true;
    }

    /** Names the body writes (dest positions) plus parameters; these become function-local. */
    private static Set<String> writtenNames(Function function){
        Set<String> result = new HashSet<>(function.params);
        for(LStatement statement : function.body){
            if(statement instanceof SetStatement set){
                result.add(set.to);
            }else if(statement instanceof OperationStatement op){
                result.add(op.dest);
            }else if(statement instanceof SensorStatement sensor){
                result.add(sensor.to);
            }else if(statement instanceof ReadStatement read){
                result.add(read.output);
            }else if(statement instanceof GetLinkStatement getlink){
                result.add(getlink.output);
            }else if(statement instanceof PackColorStatement packcolor){
                result.add(packcolor.result);
            }else if(statement instanceof FuncCallStatement call){
                if(!call.result.isEmpty()) result.add(call.result);
            }else if(statement instanceof ForBeginStatement forBegin){
                result.add(forBegin.variable);
            }
        }
        return result;
    }

    private static boolean isExempt(String name){
        if(name.isEmpty()) return true;
        if(name.charAt(0) == '@') return true;
        if(name.startsWith(reservedPrefix)) return true;
        // storage devices: cell1/bank1/memory1 (and bare cell/bank/memory forms)
        if(name.startsWith("cell") || name.startsWith("bank") || name.startsWith("memory")){
            for(int i = 4; i < name.length(); i++){
                if(!Character.isDigit(name.charAt(i))) return false;
            }
            return true;
        }
        return false;
    }

    /** Rebuilds the body with renamed names. Text-level rewrite: only exact identifier tokens change. */
    private static Seq<LStatement> rewriteBody(Function function, Map<String, String> map){
        if(map.isEmpty()) return function.body;
        Seq<LStatement> rewritten = new Seq<>();
        for(LStatement statement : function.body){
            if(statement instanceof FuncCallStatement call){
                FuncCallStatement copy = new FuncCallStatement();
                copy.name = call.name;
                copy.args = rewriteExpression(call.args, map);
                copy.result = map.getOrDefault(call.result, call.result);
                rewritten.add(copy);
                continue;
            }
            if(statement instanceof ReturnStatement ret){
                ReturnStatement copy = new ReturnStatement();
                copy.expr = rewriteExpression(ret.expr, map);
                rewritten.add(copy);
                continue;
            }
            StringBuilder text = new StringBuilder();
            statement.write(text);
            String rewrittenText = rewriteTokens(text.toString(), map);
            Seq<LStatement> parsed = LAssembler.read(rewrittenText, true);
            if(parsed.size != 1 || parsed.get(0) instanceof InvalidStatement){
                throw new IllegalArgumentException("internal error: failed to rewrite statement inside function '" + function.name + "'");
            }
            rewritten.add(parsed.get(0));
        }
        return rewritten;
    }

    private static String rewriteTokens(String text, Map<String, String> map){
        StringBuilder out = new StringBuilder(text.length() + 16);
        for(String token : text.split("\\s+")){
            if(!token.isEmpty()){
                out.append(map.getOrDefault(token, token));
                out.append(' ');
            }
        }
        if(out.length() > 0) out.setLength(out.length() - 1);
        return out.toString();
    }

    /** Rewrites identifiers inside an expression string, preserving all other characters. */
    public static String rewriteExpression(String expr, Map<String, String> map){
        if(expr == null || expr.isEmpty() || map.isEmpty()) return expr;
        StringBuilder out = new StringBuilder(expr.length());
        int i = 0;
        while(i < expr.length()){
            char c = expr.charAt(i);
            if(c == '@'){
                int start = i++;
                while(i < expr.length() && (Character.isLetterOrDigit(expr.charAt(i)) || expr.charAt(i) == '_')) i++;
                out.append(expr, start, i);
            }else if(Character.isLetter(c) || c == '_'){
                int start = i;
                while(i < expr.length() && (Character.isLetterOrDigit(expr.charAt(i)) || expr.charAt(i) == '_')) i++;
                String token = expr.substring(start, i);
                out.append(map.getOrDefault(token, token));
            }else{
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    // ===== recursion detection ============================================================

    private static void checkRecursion(FunctionSet set){
        Map<String, Function> all = new LinkedHashMap<>(set.functions);
        if(set.library != null){
            for(Map.Entry<String, Function> entry : set.library.functions.entrySet()){
                all.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        checkRecursion(all);
    }

    private static void checkRecursion(Map<String, Function> all){
        Map<String, Integer> state = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        for(String name : all.keySet()){
            if(state.get(name) == null) visit(name, all, state, stack);
        }
    }

    private static void visit(String name, Map<String, Function> all, Map<String, Integer> state, Deque<String> stack){
        state.put(name, 1);
        stack.push(name);
        Function function = all.get(name);
        if(function != null){
            for(String callee : function.callees){
                Integer seen = state.get(callee);
                if(seen == null){
                    visit(callee, all, state, stack);
                }else if(seen == 1){
                    StringBuilder path = new StringBuilder();
                    Deque<String> reversed = new ArrayDeque<>();
                    for(String node : stack){
                        reversed.push(node);
                        if(node.equals(callee)) break;
                    }
                    for(String node : reversed){
                        if(path.length() > 0) path.append(" -> ");
                        path.append(node);
                    }
                    path.append(" -> ").append(callee);
                    throw new IllegalArgumentException("recursion is not supported: " + path);
                }
            }
        }
        stack.pop();
        state.put(name, 2);
    }

    // ===== lowering =======================================================================

    /**
     * Emits vanilla mlog for a statement list. The list is either the main program
     * (prefix "") or a prepared function body (prefix {@code __ls_func_<name>_} in NORMAL
     * mode, {@code __ls_i_<id>_} per inline copy). Call sites expand recursively.
     */
    public static void lower(Seq<LStatement> statements, String prefix, FunctionSet functions, FuncMode mode,
                             StringBuilder out, CallIds ids, String funcName){
        int[] switchOwner = switchOwners(statements);
        int[] breakOwner = breakOwners(statements);
        int[] continueOwner = continueOwners(statements);
        int[] ifOwner = ifOwners(statements);
        int[] nextBranch = nextBranch(statements, ifOwner);
        boolean[] statementLabels = statementLabels(statements, breakOwner);
        String[] optimizedOperations = optimizeOperations(statements, statementLabels);

        boolean[] ifBad = ifChainViolations(statements, ifOwner);
        for(int i = 0; i < statements.size; i++){
            if(ifBad[i]){
                LStatement statement = statements.get(i);
                throw error(statement instanceof ElseStatement ? "else" : "elif", i,
                    "appears after else (or is a duplicate else) in the same if chain");
            }
        }

        for(int i = 0; i < statements.size; i++){
            if(statementLabels[i]) out.append(label(prefix, "stmt_", i)).append(":\n");
            if(optimizedOperations[i] != null){
                out.append(optimizedOperations[i]);
                continue;
            }
            LStatement statement = statements.get(i);

            if(statement instanceof ForBeginStatement begin){
                if(!begin.initial.isEmpty()) out.append("set ").append(begin.variable).append(' ').append(begin.initial).append('\n');
                out.append(label(prefix, "for_check_", i)).append(":\n");
                out.append("jump ").append(label(prefix, "for_body_", i)).append(' ').append(begin.op.name()).append(' ')
                    .append(begin.variable).append(' ').append(begin.compare).append('\n');
                out.append("jump ").append(label(prefix, "stmt_", begin.destIndex + 1)).append(" always x false\n");
                out.append(label(prefix, "for_body_", i)).append(":\n");
            }else if(statement instanceof WhileBeginStatement begin){
                out.append("jump ").append(label(prefix, "while_body_", i)).append(' ').append(begin.op.name()).append(' ')
                    .append(begin.value).append(' ').append(begin.compare).append('\n');
                out.append("jump ").append(label(prefix, "stmt_", begin.destIndex + 1)).append(" always x false\n");
                out.append(label(prefix, "while_body_", i)).append(":\n");
            }else if(statement instanceof SwitchBeginStatement begin){
                for(int at = i + 1; at < begin.destIndex; at++){
                    if(switchOwner[at] == i && statements.get(at) instanceof CaseStatement item){
                        out.append("jump ").append(label(prefix, "case_", at)).append(" equal ").append(begin.value).append(' ').append(item.value).append('\n');
                    }
                }
                out.append("jump ").append(label(prefix, "stmt_", begin.destIndex + 1)).append(" always x false\n");
            }else if(statement instanceof CaseStatement){
                if(switchOwner[i] < 0) throw error("case", i, "is outside a switch");
                out.append(label(prefix, "case_", i)).append(":\n");
            }else if(statement instanceof IfBeginStatement begin){
                String target = nextBranch[i] >= 0
                    ? label(prefix, "if_branch_", nextBranch[i])
                    : label(prefix, "stmt_", begin.destIndex + 1);
                ConditionOp negated = negate(begin.op);
                if(negated != null){
                    out.append("jump ").append(target).append(' ').append(negated.name()).append(' ')
                        .append(begin.value).append(' ').append(begin.compare).append('\n');
                }
            }else if(statement instanceof ElseIfStatement item){
                int owner = ifOwner[i];
                if(owner < 0) throw error("elif", i, "is outside an if");
                int end = ((BeginStatement)statements.get(owner)).destIndex;
                out.append("jump ").append(label(prefix, "stmt_", end + 1)).append(" always x false\n");
                out.append(label(prefix, "if_branch_", i)).append(":\n");
                String target = nextBranch[i] >= 0
                    ? label(prefix, "if_branch_", nextBranch[i])
                    : label(prefix, "stmt_", end + 1);
                ConditionOp negated = negate(item.op);
                if(negated != null){
                    out.append("jump ").append(target).append(' ').append(negated.name()).append(' ')
                        .append(item.value).append(' ').append(item.compare).append('\n');
                }
            }else if(statement instanceof ElseStatement){
                int owner = ifOwner[i];
                if(owner < 0) throw error("else", i, "is outside an if");
                int end = ((BeginStatement)statements.get(owner)).destIndex;
                out.append("jump ").append(label(prefix, "stmt_", end + 1)).append(" always x false\n");
                out.append(label(prefix, "if_branch_", i)).append(":\n");
            }else if(statement instanceof BreakStatement){
                if(breakOwner[i] < 0) throw error("break", i, "is outside a loop or switch");
                BeginStatement owner = (BeginStatement)statements.get(breakOwner[i]);
                out.append("jump ").append(label(prefix, "stmt_", owner.destIndex + 1)).append(" always x false\n");
            }else if(statement instanceof ContinueStatement){
                int owner = continueOwner[i];
                if(owner < 0) throw error("continue", i, "is outside a loop");
                LStatement ownerStmt = statements.get(owner);
                if(ownerStmt instanceof ForBeginStatement){
                    out.append("jump ").append(label(prefix, "for_continue_", owner)).append(" always x false\n");
                }else if(ownerStmt instanceof WhileBeginStatement){
                    out.append("jump ").append(label(prefix, "stmt_", owner)).append(" always x false\n");
                }
            }else if(statement instanceof BlockEndStatement){
                int beginIndex = findOwner(statements, i);
                LStatement owner = statements.get(beginIndex);
                if(owner instanceof ForBeginStatement begin){
                    out.append(label(prefix, "for_continue_", beginIndex)).append(":\n");
                    if(!begin.step.isEmpty()) out.append("op add ").append(begin.variable).append(' ').append(begin.variable).append(' ').append(begin.step).append('\n');
                    out.append("jump ").append(label(prefix, "for_check_", beginIndex)).append(" always x false\n");
                }else if(owner instanceof WhileBeginStatement){
                    out.append("jump ").append(label(prefix, "stmt_", beginIndex)).append(" always x false\n");
                }
            }else if(statement instanceof JumpStatement jump){
                if(jump.destIndex == exitTarget){
                    out.append("jump __ls_").append(prefix).append("exit").append(' ').append(jump.op.name()).append(' ')
                        .append(jump.value).append(' ').append(jump.compare).append('\n');
                }else if(jump.destIndex < 0 || jump.destIndex > statements.size){
                    throw error("jump", i, "has no valid destination");
                }else{
                    out.append("jump ").append(label(prefix, "stmt_", jump.destIndex)).append(' ').append(jump.op.name()).append(' ')
                        .append(jump.value).append(' ').append(jump.compare).append('\n');
                }
            }else if(statement instanceof FuncCallStatement call){
                expandCall(call, functions, mode, out, ids);
            }else if(statement instanceof ReturnStatement){
                if(funcName == null) throw error("return", i, "is outside a function");
                emitReturn((ReturnStatement)statement, prefix, mode, out, funcName);
            }else if(statement instanceof FuncDefStatement){
                throw error("funcdef", i, "cannot be lowered; function definitions are expanded at call sites");
            }else{
                statement.write(out);
                out.append('\n');
            }
        }
        if(statementLabels[statements.size]) out.append(label(prefix, "stmt_", statements.size)).append(":\n");
    }

    private static void emitReturn(ReturnStatement ret, String prefix, FuncMode mode, StringBuilder out, String funcName){
        if(!ret.expr.isEmpty()){
            try{
                List<ExprCompiler.OpLine> ops = ExprCompiler.compile("__ls_func_" + funcName + "_result", ret.expr);
                for(ExprCompiler.OpLine op : ops) out.append(op.toText()).append('\n');
            }catch(Exception e){
                throw new IllegalArgumentException("Invalid return expression '" + ret.expr + "': " + e.getMessage());
            }
        }
        if(mode == FuncMode.inline){
            // value returns jump to the ret label (result copy runs), void returns jump past it
            out.append("jump __ls_").append(prefix).append(ret.expr.isEmpty() ? "exit" : "ret").append(" always x false\n");
        }else{
            out.append("set @counter __ls_func_").append(funcName).append("_ret\n");
        }
    }

    /** Expands one call site. */
    private static void expandCall(FuncCallStatement call, FunctionSet functions, FuncMode mode, StringBuilder out, CallIds ids){
        Function target = functions.resolve(call.name);
        if(target == null){
            throw new IllegalArgumentException("call to undefined function '" + call.name + "'");
        }
        List<String> args = splitArgs(call.args);
        if(mode == FuncMode.inline){
            int id = ids.next();
            String prefix = "i_" + id + "_";
            for(int k = 0; k < args.size(); k++){
                emitArg(out, args.get(k), target.bindingName(k));
            }
            lower(target.body, prefix, functions, mode, out, ids, target.name);
            // Value returns jump here so the caller-side result copy still runs;
            // void returns and jumps to the function end skip it via the exit label.
            out.append("__ls_").append(prefix).append("ret:\n");
            if(!call.result.isEmpty()){
                out.append("set ").append(call.result).append(' ').append(target.resultName()).append('\n');
            }
            out.append("__ls_").append(prefix).append("exit:\n");
        }else{
            for(int k = 0; k < args.size(); k++){
                emitArg(out, args.get(k), target.bindingName(k));
            }
            out.append("set ").append(target.retName()).append(" @counter\n");
            out.append("op add ").append(target.retName()).append(' ').append(target.retName()).append(" 2\n");
            out.append("jump ").append(target.entryName()).append(" always x false\n");
            if(!call.result.isEmpty()){
                out.append("set ").append(call.result).append(' ').append(target.resultName()).append('\n');
            }
        }
    }

    /** Compiles one argument expression and binds it to the parameter. */
    private static void emitArg(StringBuilder out, String arg, String param){
        List<ExprCompiler.OpLine> ops;
        try{
            ops = ExprCompiler.compile("_0", arg);
        }catch(Exception e){
            throw new IllegalArgumentException("Invalid argument expression '" + arg + "': " + e.getMessage());
        }
        if(ops.size() == 1 && ops.get(0).op.equals("add") && ops.get(0).b.equals("0")){
            out.append("set ").append(param).append(' ').append(ops.get(0).a).append('\n');
            return;
        }
        for(ExprCompiler.OpLine op : ops) out.append(op.toText()).append('\n');
        out.append("set ").append(param).append(" _0\n");
    }

    private static String label(String prefix, String kind, int id){
        return "__ls_" + prefix + kind + id;
    }

    // ===== structure helpers (moved from SugarCompiler, parameterized by prefix) ==========

    private static int[] switchOwners(Seq<LStatement> statements){
        int[] result = new int[statements.size];
        Arrays.fill(result, -1);
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
        Arrays.fill(result, -1);
        Deque<Integer> stack = new ArrayDeque<>();
        for(int i = 0; i < statements.size; i++){
            while(!stack.isEmpty() && ((IfBeginStatement)statements.get(stack.peek())).destIndex < i) stack.pop();
            if(!stack.isEmpty()) result[i] = stack.peek();
            if(statements.get(i) instanceof IfBeginStatement) stack.push(i);
        }
        return result;
    }

    /** Marks each elif/else that violates the chain rule: an if chain may have at most one
     *  {@code else}, and no {@code elif} may follow it (it would be silently dead code).
     *  This is the single source of truth for the rule, shared by canvas highlighting
     *  (SugarCompiler.invalidStatements), the compile path ({@link #lower}) and the library
     *  builder ({@link #buildLibrary}), so text-sourced and library programs are rejected the
     *  same way as the canvas. */
    static boolean[] ifChainViolations(Seq<LStatement> statements, int[] ifOwner){
        boolean[] bad = new boolean[statements.size];
        for(int i = 0; i < statements.size; i++){
            if(!(statements.get(i) instanceof IfBeginStatement begin)) continue;
            boolean seenElse = false;
            for(int at = i + 1; at < begin.destIndex; at++){
                if(ifOwner[at] != i) continue;
                LStatement statement = statements.get(at);
                if(statement instanceof ElseIfStatement){
                    if(seenElse) bad[at] = true;
                }else if(statement instanceof ElseStatement){
                    if(seenElse) bad[at] = true;
                    seenElse = true;
                }
            }
        }
        return bad;
    }

    /** For each if-begin and elif, the index of the next elif/else in the same chain, or -1. */
    private static int[] nextBranch(Seq<LStatement> statements, int[] ifOwner){
        int[] next = new int[statements.size];
        Arrays.fill(next, -1);
        for(int i = 0; i < statements.size; i++){
            if(!(statements.get(i) instanceof IfBeginStatement begin)) continue;
            int prev = i;
            for(int at = i + 1; at < begin.destIndex; at++){
                LStatement statement = statements.get(at);
                if(ifOwner[at] == i && (statement instanceof ElseIfStatement || statement instanceof ElseStatement)){
                    next[prev] = at;
                    prev = at;
                }
            }
        }
        return next;
    }

    /** Returns the op whose truth is the negation of {@code op}, or null for {@code always}
     *  (a condition that is always true never needs its false branch taken). */
    private static ConditionOp negate(ConditionOp op){
        switch(op){
            case equal: return ConditionOp.notEqual;
            case notEqual: return ConditionOp.equal;
            case lessThan: return ConditionOp.greaterThanEq;
            case lessThanEq: return ConditionOp.greaterThan;
            case greaterThan: return ConditionOp.lessThanEq;
            case greaterThanEq: return ConditionOp.lessThan;
            case strictEqual:
                // Mindustry has no strict-not-equal op, so negating strictEqual falls back to
                // notEqual. This is exact for same-typed operands but only approximate for
                // cross-type comparisons (e.g. "a" strictEqual 1 is false, so its negation should
                // be true, whereas "a" notEqual 1 is also true in mlog — coincidentally matching;
                // the divergence is confined to values that coercion makes equal, such as 1 vs "1").
                return ConditionOp.notEqual;
            default: return null; // always
        }
    }

    /** Returns the innermost enclosing structure that accepts a break statement. */
    private static int[] breakOwners(Seq<LStatement> statements){
        int[] result = new int[statements.size];
        Arrays.fill(result, -1);
        Deque<Integer> stack = new ArrayDeque<>();
        for(int i = 0; i < statements.size; i++){
            while(!stack.isEmpty() && ((BeginStatement)statements.get(stack.peek())).destIndex < i) stack.pop();
            if(!stack.isEmpty()) result[i] = stack.peek();
            if(isBreakable(statements.get(i))) stack.push(i);
        }
        return result;
    }

    /** Returns the innermost enclosing loop that accepts a continue statement. */
    private static int[] continueOwners(Seq<LStatement> statements){
        int[] result = new int[statements.size];
        Arrays.fill(result, -1);
        Deque<Integer> stack = new ArrayDeque<>();
        for(int i = 0; i < statements.size; i++){
            while(!stack.isEmpty() && ((BeginStatement)statements.get(stack.peek())).destIndex < i) stack.pop();
            if(!stack.isEmpty()) result[i] = stack.peek();
            if(statements.get(i) instanceof ForBeginStatement || statements.get(i) instanceof WhileBeginStatement) stack.push(i);
        }
        return result;
    }

    private static boolean isBreakable(LStatement statement){
        return statement instanceof ForBeginStatement || statement instanceof WhileBeginStatement || statement instanceof SwitchBeginStatement;
    }

    /** Marks only labels that are actual jump destinations; the remaining labels add no control-flow value. */
    private static boolean[] statementLabels(Seq<LStatement> statements, int[] breakOwner){
        boolean[] result = new boolean[statements.size + 1];
        for(int i = 0; i < statements.size; i++){
            LStatement statement = statements.get(i);
            if(statement instanceof JumpStatement jump){
                if(jump.destIndex == exitTarget) continue;
                if(jump.destIndex >= 0 && jump.destIndex <= statements.size) result[jump.destIndex] = true;
            }else if(statement instanceof BeginStatement begin){
                result[begin.destIndex + 1] = true;
            }else if(statement instanceof BreakStatement && breakOwner[i] >= 0){
                BeginStatement owner = (BeginStatement)statements.get(breakOwner[i]);
                result[owner.destIndex + 1] = true;
            }else if(statement instanceof BlockEndStatement){
                int owner = findOwner(statements, i);
                if(statements.get(owner) instanceof WhileBeginStatement) result[owner] = true;
            }
        }
        return result;
    }

    /**
     * Optimizes only straight runs of ordinary op statements. A run is cut at every possible
     * statement-label entry, so constants are never propagated across a jump target.
     */
    private static String[] optimizeOperations(Seq<LStatement> statements, boolean[] statementLabels){
        String[] result = new String[statements.size];
        Map<String, Integer> remainingReferences = temporaryReferenceCounts(statements);
        for(int start = 0; start < statements.size; ){
            if(!(statements.get(start) instanceof OperationStatement) || statementLabels[start]){
                removeTemporaryReferences(remainingReferences, statements.get(start));
                start++;
                continue;
            }

            int end = start + 1;
            while(end < statements.size && statements.get(end) instanceof OperationStatement && !statementLabels[end]) end++;
            for(int i = start; i < end; i++) removeTemporaryReferences(remainingReferences, statements.get(i));
            List<OptimizedOperation> operations = new ArrayList<>();
            Map<String, String> constants = new HashMap<>();
            for(int i = start; i < end; i++){
                OperationStatement operation = (OperationStatement)statements.get(i);
                String a = constants.getOrDefault(operation.a, operation.a);
                String b = constants.getOrDefault(operation.b, operation.b);
                String value = constantValue(operation, a, b);
                if(value != null){
                    operations.add(new OptimizedOperation("set " + operation.dest + " " + value, operation.dest, true));
                    constants.put(operation.dest, value);
                }else{
                    operations.add(new OptimizedOperation("op " + operation.op.name() + " " + operation.dest + " " + a + " " + b,
                        operation.dest, operation.op != LogicOp.rand));
                    constants.remove(operation.dest);
                }
            }

            // Earlier writes to a compiler temporary can disappear when their value was folded
            // into a later operation. Keep the final value of each temporary conservatively.
            Set<String> live = new HashSet<>(remainingReferences.keySet());
            for(int i = operations.size() - 1; i >= 0; i--){
                OptimizedOperation operation = operations.get(i);
                if(isTemporary(operation.dest)){
                    if(!live.remove(operation.dest) && operation.removable){
                        operation.remove = true;
                        continue;
                    }
                }
                addTemporaryOperands(live, operation.text);
            }

            StringBuilder lowered = new StringBuilder();
            for(OptimizedOperation operation : operations){
                if(!operation.remove) lowered.append(operation.text).append('\n');
            }
            result[start] = lowered.toString();
            for(int i = start + 1; i < end; i++) result[i] = "";
            start = end;
        }
        return result;
    }

    private static String constantValue(OperationStatement operation, String a, String b){
        Double left = finiteNumber(a);
        Double right = finiteNumber(b);
        if(left == null || right == null) return null;
        Double value = constantOperation(operation.op, left, right);
        return value != null && Double.isFinite(value) ? formatNumber(value) : null;
    }

    // LogicOp stores its lambdas in package-private nested interfaces. Mods use a separate
    // class loader, so invoking those fields causes IllegalAccessError at runtime.
    private static Double constantOperation(LogicOp op, double a, double b){
        switch(op){
            case add: return a + b;
            case sub: return a - b;
            case mul: return a * b;
            case div: return a / b;
            case idiv: return Math.floor(a / b);
            case mod: return a % b;
            case emod: return ((a % b) + b) % b;
            case pow: return Math.pow(a, b);
            case max: return Math.max(a, b);
            case min: return Math.min(a, b);
            case abs: return Math.abs(a);
            case sign: return Math.signum(a);
            case log: return Math.log(a);
            case logn: return Math.log(a) / Math.log(b);
            case log10: return Math.log10(a);
            case floor: return Math.floor(a);
            case ceil: return Math.ceil(a);
            case round: return (double)Math.round(a);
            case sqrt: return Math.sqrt(a);
            default: return null;
        }
    }

    private static Double finiteNumber(String value){
        try{
            double result = Double.parseDouble(value);
            return Double.isFinite(result) ? result : null;
        }catch(NumberFormatException ignored){
            return null;
        }
    }

    private static String formatNumber(double value){
        if(value != 0d && value == Math.rint(value) && Math.abs(value) <= Long.MAX_VALUE){
            return Long.toString((long)value);
        }
        return Double.toString(value);
    }

    private static boolean isTemporary(String value){
        if(value.length() < 2 || value.charAt(0) != '_') return false;
        for(int i = 1; i < value.length(); i++){
            if(!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }

    private static void addTemporaryOperands(Set<String> live, String text){
        String[] tokens = text.split(" ");
        int offset = tokens[0].equals("op") ? 3 : 2;
        for(int i = offset; i < tokens.length; i++){
            if(isTemporary(tokens[i])) live.add(tokens[i]);
        }
    }

    private static Map<String, Integer> temporaryReferenceCounts(Seq<LStatement> statements){
        Map<String, Integer> result = new HashMap<>();
        for(LStatement statement : statements) addTemporaryReferences(result, statement, 1);
        return result;
    }

    private static void removeTemporaryReferences(Map<String, Integer> counts, LStatement statement){
        addTemporaryReferences(counts, statement, -1);
    }

    private static void addTemporaryReferences(Map<String, Integer> counts, LStatement statement, int amount){
        StringBuilder text = new StringBuilder();
        statement.write(text);
        for(String token : text.toString().split("\\s+")){
            if(!isTemporary(token)) continue;
            int count = counts.getOrDefault(token, 0) + amount;
            if(count <= 0) counts.remove(token);
            else counts.put(token, count);
        }
    }

    private static final class OptimizedOperation{
        final String text;
        final String dest;
        final boolean removable;
        boolean remove;

        OptimizedOperation(String text, String dest, boolean removable){
            this.text = text;
            this.dest = dest;
            this.removable = removable;
        }
    }

    private static int findOwner(Seq<LStatement> statements, int end){
        for(int i = end - 1; i >= 0; i--){
            LStatement statement = statements.get(i);
            if(statement instanceof BeginStatement begin && begin.destIndex == end) return i;
        }
        throw error("end", end, "has no matching begin block");
    }

    private static IllegalArgumentException error(String block, int index, String detail){
        return new IllegalArgumentException(block + " at statement " + index + " " + detail + ".");
    }
}
