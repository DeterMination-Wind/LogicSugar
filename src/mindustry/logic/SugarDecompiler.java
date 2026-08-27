package mindustry.logic;

import logicsugar.assist.expr.ExprCompiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reverse view for LogicSugar.
 *
 * <p>The input is parsed as vanilla mlog first.  Every parsed instruction is then either
 * represented by a Sugar structure or emitted unchanged as a vanilla statement.  A candidate
 * is accepted only after compiling it again and comparing the normalized instruction stream;
 * this is the important safety boundary for arbitrary hand-written processor programs.</p>
 */
public final class SugarDecompiler{
    private SugarDecompiler(){}

    private static final Object PARSE_LOCK = new Object();

    public static final class Result{
        public final String sugar;
        public final boolean verified;
        public final String matchedMode;
        public final int structured;
        public final int passthrough;
        public final List<String> notes;

        Result(String sugar, boolean verified, String matchedMode, int structured,
               int passthrough, List<String> notes){
            this.sugar = sugar;
            this.verified = verified;
            this.matchedMode = matchedMode;
            this.structured = structured;
            this.passthrough = passthrough;
            this.notes = List.copyOf(notes);
        }
    }

    /** Decompiles with privileged parsing, suitable for a world-processor code string. */
    public static Result decompile(String code){
        return decompile(code, true);
    }

    /** Decompiles using the same privilege level as the target logic editor. */
    public static Result decompile(String code, boolean privileged){
        synchronized(PARSE_LOCK){
            return decompileLocked(code, privileged);
        }
    }

    private static Result decompileLocked(String code, boolean privileged){
        String input = normalizeLineEndings(code == null ? "" : code);
        List<String> notes = new ArrayList<>();
        installSugarParsers();

        // A valid LogicSugar carrier is lossless metadata. Prefer it over inference; this also
        // keeps the exact source (including function definitions and expression conditions).
        if(SugarCompiler.isSugarProgram(input)){
            try{
                String restored = SugarCompiler.restore(input);
                if(SugarCompiler.verifyRestore(input, restored)){
                    return new Result(restored, true, "carrier", 0, countStatements(restored), notes);
                }
            }catch(Throwable exception){
                notes.add("Sugar carrier could not be verified: " + message(exception));
            }

            // The stored sugar source is stale relative to the instructions: the program was
            // edited outside Logic Sugar, so the carrier variable is now the least trustworthy
            // content. Keeping it as a statement also breaks recompilation equality — a
            // recovered candidate regenerates its own carrier, which the verification strips,
            // while the target stream still contains the old one. Retry on the bare
            // instruction stream; the same recompilation gate applies.
            String bare = stripCarrierVariables(input);
            if(!bare.trim().isEmpty()){
                List<String> retryNotes = new ArrayList<>();
                retryNotes.add("stored sugar carrier was stale; recovered from the instruction stream");
                retryNotes.addAll(notes);
                Result retry = infer(bare, privileged, retryNotes);
                if(retry.verified && retry.matchedMode != null && !"flat".equals(retry.matchedMode)
                    && retry.structured > 0){
                    return retry;
                }
            }
        }

        return infer(input, privileged, notes);
    }

    /** Validation, structure recovery and recompilation-verified emission for one input. */
    private static Result infer(String input, boolean privileged, List<String> notes){
        String canonical;
        try{
            validateInput(input, privileged);
            canonical = normalize(input, privileged);
        }catch(Throwable exception){
            notes.add("input is not valid vanilla mlog: " + message(exception));
            return new Result(input, false, null, 0, countStatements(input), notes);
        }

        Program program = new Program(canonical);
        Candidate candidate = new Candidate(program, notes);
        try{
            candidate.recoverFunctions();
            candidate.parseMain();
        }catch(Throwable exception){
            notes.add("structure recovery stopped: " + message(exception));
            candidate.resetFlat();
        }

        String structured = candidate.emit();
        Verification verification = verify(structured, input, privileged);
        if(!verification.matched){
            notes.add("unstructured instructions were kept as vanilla mlog");
            return new Result(canonical, true, "flat", 0, program.statements.size(), notes);
        }
        return new Result(structured, true, verification.mode, candidate.structured,
            candidate.passthrough, notes);
    }

    /**
     * Removes one complete LogicSugar source-marker block.  Carrier-looking variables are
     * intentionally preserved: in arbitrary vanilla mlog they are ordinary valid variables.
     */
    public static String stripMetadata(String code){
        if(code == null) return "";
        String normalized = normalizeLineEndings(code);
        String[] lines = normalized.split("\n", -1);
        int begin = -1, end = -1;
        for(int i = 0; i < lines.length; i++){
            if(lines[i].equals("# @logic-sugar-v1 begin")){ begin = i; break; }
        }
        if(begin < 0) return normalized;
        for(int i = begin + 1; i < lines.length; i++){
            if(lines[i].equals("# @logic-sugar-v1 end")){ end = i; break; }
        }
        if(end < 0) return normalized;
        StringBuilder result = new StringBuilder();
        for(int i = 0; i < lines.length; i++){
            if(i >= begin && i <= end) continue;
            result.append(lines[i]).append('\n');
        }
        return result.toString();
    }

    /** Removes metadata from compiler-generated candidate output only. */
    private static String stripGeneratedMetadata(String code){
        StringBuilder result = new StringBuilder();
        boolean marker = false;
        for(String line : normalizeLineEndings(code).split("\n", -1)){
            if(line.equals("# @logic-sugar-v1 begin")){ marker = true; continue; }
            if(line.equals("# @logic-sugar-v1 end")){ marker = false; continue; }
            if(marker) continue;
            if(line.startsWith("set __ls_sugar \"") || line.startsWith("set __ls_lib \"")) continue;
            result.append(line).append('\n');
        }
        return result.toString();
    }

    /** Drops persistence-carrier 'set' lines (stale sugar source + embedded library) from a
     *  program that was recognized as LogicSugar-compiled but whose carrier failed
     *  verification. Only reached after isSugarProgram, so ordinary hand-written programs
     *  keep their carrier-looking variables untouched. */
    private static String stripCarrierVariables(String code){
        StringBuilder result = new StringBuilder();
        for(String line : normalizeLineEndings(code).split("\n", -1)){
            if(line.startsWith("set __ls_sugar \"") || line.startsWith("set __ls_lib \"")) continue;
            result.append(line).append('\n');
        }
        return result.toString();
    }

    private static String normalizeLineEndings(String code){
        return code.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String normalize(String code, boolean privileged){
        return LAssembler.write(LAssembler.read(code, privileged));
    }

    private static String message(Throwable exception){
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private static Verification verify(String candidate, String original, boolean privileged){
        try{
            String target = normalize(original, privileged);
            // Jump threading (2.3.1+) retargets unconditional jumps inside lowered output,
            // so candidates compiled today may only match older artifacts after applying the
            // same idempotent pass to them.
            String threadedTarget = normalize(SugarCompiler.threadAlwaysJumpTargets(original), privileged);
            for(SugarCompiler.FuncMode mode : SugarCompiler.FuncMode.values()){
                try{
                    String compiled = SugarCompiler.compile(candidate, mode);
                    if(normalize(stripGeneratedMetadata(compiled), privileged).equals(target)
                        || normalize(stripGeneratedMetadata(compiled), privileged).equals(threadedTarget)){
                        return new Verification(true, mode.name());
                    }
                }catch(Throwable ignored){
                    // Try the other function mode.
                }
            }
        }catch(Throwable ignored){
            // The input was already checked, but verification must never break the UI.
        }
        return new Verification(false, null);
    }

    private record Verification(boolean matched, String mode){}

    /** Defensive re-registration for headless/self-test environments; the game mod path
     *  installs them at init via {@link SugarStatements#installParsers()} (idempotent). */
    private static void installSugarParsers(){
        SugarStatements.installParsers();
    }

    private static void validateInput(String code, boolean privileged){
        List<String> heads = logicalHeads(code);
        if(heads.size() > LExecutor.maxInstructions){
            throw new IllegalArgumentException("logic program exceeds the " + LExecutor.maxInstructions + " instruction limit");
        }
        int index = 0;
        for(LStatement statement : LAssembler.read(code, privileged)){
            if(statement instanceof LStatements.InvalidStatement){
                String head = index < heads.size() ? heads.get(index) : "";
                if(!"noop".equals(head)) throw new IllegalArgumentException("unrecognized mlog statement: " + head);
            }
            index++;
        }
        if(index != heads.size()) throw new IllegalArgumentException("mlog parser did not consume the complete program");
    }

    /** Statement heads in input order, used by {@link #validateInput} to name the offending
     *  statement and to prove {@code LAssembler.read} consumed the whole program.
     *
     *  <p>These heads cannot be taken from the parse result itself: vanilla InvalidStatement
     *  carries no fields (no token/position info), and LogicIO.write serializes every
     *  invalid statement back as "noop", which would make genuinely broken code
     *  indistinguishable from a deliberate noop. This tokenizer therefore mirrors LParser's
     *  rules — statements end at newline/';', '#' starts a comment outside strings, strings
     *  run raw to the closing quote, and a lone 'label:' line registers a jump location
     *  instead of a statement (LParser.statement + parse). The only known divergences are
     *  inputs that make LParser throw outright (unterminated string, missing space before a
     *  quote); those reject the entire program upstream, so misalignment here is unreachable,
     *  and any future drift fails toward rejecting more input — the safe direction.</p> */
    private static List<String> logicalHeads(String code){
        List<String> result = new ArrayList<>();
        for(String line : normalizeLineEndings(code).split("\n", -1)){
            List<String> tokens = new ArrayList<>();
            int p = 0;
            while(p < line.length()){
                char c = line.charAt(p);
                if(c == ' ' || c == '\t'){ p++; continue; }
                if(c == '#') break;
                if(c == ';'){
                    addHead(tokens, result);
                    tokens.clear();
                    p++;
                    continue;
                }
                if(c == '"'){
                    int start = p++;
                    while(p < line.length() && line.charAt(p) != '"') p++;
                    if(p < line.length()) p++;
                    tokens.add(line.substring(start, p));
                    continue;
                }
                int start = p++;
                while(p < line.length()){
                    char d = line.charAt(p);
                    if(d == ' ' || d == '\t' || d == '#' || d == ';' || d == '"') break;
                    p++;
                }
                tokens.add(line.substring(start, p));
            }
            addHead(tokens, result);
        }
        return result;
    }

    private static void addHead(List<String> tokens, List<String> result){
        if(!tokens.isEmpty() && !(tokens.size() == 1 && tokens.get(0).endsWith(":"))){
            result.add(tokens.get(0));
        }
    }

    private static int countStatements(String code){
        int result = 0;
        for(String line : normalizeLineEndings(code).split("\n", -1)){
            String trimmed = line.trim();
            if(!trimmed.isEmpty() && !trimmed.startsWith("#")) result++;
        }
        return result;
    }

    // ===== Parsed mlog =====================================================================

    private static final class Statement{
        final String[] tokens;
        int target = -1;

        Statement(String[] tokens){ this.tokens = tokens; }
        String kind(){ return tokens.length == 0 ? "" : tokens[0]; }
        String token(int index){ return index < tokens.length ? tokens[index] : ""; }
        boolean isJump(){ return kind().equals("jump") && tokens.length >= 2; }
        boolean isAlways(){ return isJump() && tokens.length >= 5 && token(2).equals("always"); }
        boolean isConditional(){ return isJump() && !isAlways() && tokens.length >= 5 && target >= 0; }
    }

    private static final class Program{
        final List<Statement> statements = new ArrayList<>();

        Program(String canonical){
            for(String line : canonical.split("\n")){
                String[] tokens = tokenize(line);
                if(tokens.length > 0) statements.add(new Statement(tokens));
            }
            for(Statement statement : statements){
                if(!statement.isJump()) continue;
                if(isInteger(statement.token(1))){
                    try{ statement.target = Integer.parseInt(statement.token(1)); }
                    catch(NumberFormatException ignored){}
                }
            }
        }

        private static String[] tokenize(String line){
            List<String> result = new ArrayList<>();
            int p = 0;
            while(p < line.length()){
                char c = line.charAt(p);
                if(c == ' ' || c == '\t'){ p++; continue; }
                if(c == '#') break;
                if(c == '"'){
                    int start = p++;
                    while(p < line.length() && line.charAt(p) != '"') p++;
                    if(p < line.length()) p++;
                    result.add(line.substring(start, p));
                    continue;
                }
                int start = p++;
                while(p < line.length()){
                    c = line.charAt(p);
                    if(c == ' ' || c == '\t' || c == '#') break;
                    p++;
                }
                result.add(line.substring(start, p));
            }
            return result.toArray(new String[0]);
        }

        private static boolean isInteger(String value){
            if(value == null || value.isEmpty()) return false;
            int p = value.charAt(0) == '-' || value.charAt(0) == '+' ? 1 : 0;
            if(p == value.length()) return false;
            while(p < value.length()) if(!Character.isDigit(value.charAt(p++))) return false;
            return true;
        }
    }

    // ===== Output items ====================================================================

    private abstract static class Item{
        final int from, to;
        Item(int from, int to){ this.from = from; this.to = to; }
        abstract void write(StringBuilder out, Emitter emitter);
        int priority(){ return 10; }
    }

    private static final class RawItem extends Item{
        final String[] tokens;
        RawItem(int origin, String[] tokens){ super(origin, origin); this.tokens = tokens; }
        @Override void write(StringBuilder out, Emitter emitter){
            for(int i = 0; i < tokens.length; i++){
                if(i > 0) out.append(' ');
                out.append(tokens[i]);
            }
            out.append('\n');
        }
        @Override int priority(){ return 100; }
    }

    private static final class JumpItem extends Item{
        final int destination;
        final String operation, value, compare;
        JumpItem(int origin, int destination, String operation, String value, String compare){
            super(origin, origin);
            this.destination = destination;
            this.operation = operation;
            this.value = value;
            this.compare = compare;
        }
        @Override void write(StringBuilder out, Emitter emitter){
            out.append("jump ").append(emitter.targetSlot(destination)).append(' ')
                .append(operation).append(' ').append(value).append(' ').append(compare).append('\n');
        }
        @Override int priority(){ return 100; }
    }

    private static final class EndItem extends Item{
        EndItem(int origin){ super(origin, origin); }
        @Override void write(StringBuilder out, Emitter emitter){ out.append("end\n"); }
        @Override int priority(){ return 100; }
    }

    private static final class BreakItem extends Item{
        BreakItem(int origin){ super(origin, origin); }
        @Override void write(StringBuilder out, Emitter emitter){ out.append("break\n"); }
        @Override int priority(){ return 100; }
    }

    private static final class ContinueItem extends Item{
        ContinueItem(int origin){ super(origin, origin); }
        @Override void write(StringBuilder out, Emitter emitter){ out.append("continue\n"); }
        @Override int priority(){ return 100; }
    }

    private static final class BlockEndItem extends Item{
        BlockEndItem(int boundary){ super(boundary, boundary); }
        @Override void write(StringBuilder out, Emitter emitter){ out.append("blockend\n"); }
        @Override int priority(){ return 0; }
    }

    private static final class Condition{
        final boolean expression;
        final String text, value, operation, compare;

        Condition(String value, String operation, String compare){
            this.expression = false;
            this.text = "";
            this.value = value;
            this.operation = operation;
            this.compare = compare;
        }

        Condition(String text){
            this.expression = true;
            this.text = text;
            this.value = "";
            this.operation = "notEqual";
            this.compare = "0";
        }
    }

    private static final class IfItem extends Item{
        final Condition condition;
        BlockEndItem end;
        IfItem(int from, int to, Condition condition){ super(from, to); this.condition = condition; }
        @Override void write(StringBuilder out, Emitter emitter){
            if(condition.expression){
                out.append("ifbegin expr \"").append(escape(condition.text)).append("\" ");
            }else{
                out.append("ifbegin ").append(condition.value).append(' ')
                    .append(condition.operation).append(' ').append(condition.compare).append(' ');
            }
            out.append(emitter.itemSlot(end)).append('\n');
        }
        @Override int priority(){ return 20; }
    }

    private static final class ElseIfItem extends Item{
        final Condition condition;
        ElseIfItem(int from, int to, Condition condition){ super(from, to); this.condition = condition; }
        @Override void write(StringBuilder out, Emitter emitter){
            if(condition.expression){
                out.append("elif expr \"").append(escape(condition.text)).append("\"\n");
            }else{
                out.append("elif ").append(condition.value).append(' ')
                    .append(condition.operation).append(' ').append(condition.compare).append('\n');
            }
        }
        @Override int priority(){ return 20; }
    }

    private static final class ElseItem extends Item{
        ElseItem(int origin){ super(origin, origin); }
        @Override void write(StringBuilder out, Emitter emitter){ out.append("else\n"); }
        @Override int priority(){ return 20; }
    }

    private static final class WhileItem extends Item{
        final Condition condition;
        BlockEndItem end;
        WhileItem(int from, int to, Condition condition){ super(from, to); this.condition = condition; }
        @Override void write(StringBuilder out, Emitter emitter){
            if(condition.expression){
                out.append("whilebegin expr \"").append(escape(condition.text)).append("\" ");
            }else{
                out.append("whilebegin ").append(condition.value).append(' ')
                    .append(condition.operation).append(' ').append(condition.compare).append(' ');
            }
            out.append(emitter.itemSlot(end)).append('\n');
        }
        @Override int priority(){ return 20; }
    }

    private static final class ForItem extends Item{
        final String variable, initial, step;
        final Condition condition;
        BlockEndItem end;
        ForItem(int from, int to, String variable, String initial, String step, Condition condition){
            super(from, to);
            this.variable = variable;
            this.initial = initial;
            this.step = step;
            this.condition = condition;
        }
        @Override void write(StringBuilder out, Emitter emitter){
            out.append("forbegin ").append(variable).append(' ')
                .append(initial.isEmpty() ? "~" : initial).append(' ')
                .append(step.isEmpty() ? "~" : step).append(' ');
            if(condition.expression){
                out.append("expr \"").append(escape(condition.text)).append("\" ");
            }else{
                out.append(condition.operation).append(' ').append(condition.compare).append(' ');
            }
            out.append(emitter.itemSlot(end)).append('\n');
        }
        @Override int priority(){ return 20; }
    }

    private static final class SwitchItem extends Item{
        final String value;
        BlockEndItem end;
        SwitchItem(int origin, String value){ super(origin, origin); this.value = value; }
        @Override void write(StringBuilder out, Emitter emitter){
            out.append("switchbegin ").append(value).append(' ')
                .append(emitter.itemSlot(end)).append('\n');
        }
        @Override int priority(){ return 20; }
    }

    private static final class CaseItem extends Item{
        final String value;
        CaseItem(int origin, String value){ super(origin, origin); this.value = value; }
        @Override void write(StringBuilder out, Emitter emitter){ out.append("case ").append(value).append('\n'); }
        @Override int priority(){ return 100; }
    }

    private static final class FuncDefItem extends Item{
        final String name, params;
        BlockEndItem end;
        FuncDefItem(int origin, String name, String params){ super(origin, origin); this.name = name; this.params = params; }
        @Override void write(StringBuilder out, Emitter emitter){
            out.append("funcdef ").append(name).append(' ')
                .append(params.isEmpty() ? "~" : params).append(' ')
                .append(emitter.itemSlot(end)).append('\n');
        }
        @Override int priority(){ return 20; }
    }

    private static final class FuncCallItem extends Item{
        final String name, args, result;
        FuncCallItem(int from, int to, String name, String args, String result){
            super(from, to); this.name = name; this.args = args; this.result = result;
        }
        @Override void write(StringBuilder out, Emitter emitter){
            out.append("funccall ").append(name).append(" \"")
                .append(escape(args)).append("\" ")
                .append(result.isEmpty() ? "~" : result).append('\n');
        }
        @Override int priority(){ return 20; }
    }

    private static final class ReturnItem extends Item{
        final String expression;
        ReturnItem(int from, int to, String expression){ super(from, to); this.expression = expression; }
        @Override void write(StringBuilder out, Emitter emitter){
            out.append("return \"").append(escape(expression)).append("\"\n");
        }
        @Override int priority(){ return 100; }
    }

    private static String escape(String text){
        // Must stay in lockstep with the parser side (SugarStatements.escapeQuoted); sharing
        // the implementation keeps recompilation verification immune to one-sided rule changes.
        return SugarStatements.escapeQuoted(text);
    }

    // ===== Candidate and inverse patterns ================================================

    private record Context(int breakTarget, int continueTarget, Context parent){
        int nearestBreak(){
            for(Context c = this; c != null; c = c.parent) if(c.breakTarget >= 0) return c.breakTarget;
            return -1;
        }
        int nearestContinue(){
            for(Context c = this; c != null; c = c.parent) if(c.continueTarget >= 0) return c.continueTarget;
            return -1;
        }
    }

    private record ConditionParse(int start, int jump, int body, int falseTarget, Condition condition){}

    private static final class IfFrame{
        final List<ConditionParse> branches = new ArrayList<>();
        final List<Integer> bodyEnds = new ArrayList<>();
        boolean hasElse;
        int elseMarker, elseStart, elseEnd, exit;
    }

    private static final class Frame{
        enum Kind{ IF, WHILE, FOR, SWITCH }
        Kind kind;
        int start, bodyStart, bodyEnd, exit, resume, continueTarget;
        /** Breaks may be threaded past the structural exit label; the blockend still belongs
         *  at exit, while this optional target is accepted when recognizing BreakItem jumps. */
        int breakTarget = -1;
        String variable, initial, step, switchValue;
        Condition condition;
        IfFrame ifFrame;
        List<Integer> caseTargets;
        List<String> caseValues;
    }

    private static final class FunctionInfo{
        final String name;
        final int entry, finalTail;
        String params = "";
        FunctionInfo(String name, int entry, int finalTail){ this.name = name; this.entry = entry; this.finalTail = finalTail; }
        int zoneEnd(){ return finalTail + 1; }
        String ret(){ return "__ls_func_" + name + "_ret"; }
        String result(){ return "__ls_func_" + name + "_result"; }
    }

    private static final class CallSite{
        final int from, to, anchor;
        final FunctionInfo function;
        final String args, result;
        CallSite(int from, int to, int anchor, FunctionInfo function, String args, String result){
            this.from = from; this.to = to; this.anchor = anchor; this.function = function;
            this.args = args; this.result = result;
        }
    }

    private static final class Candidate{
        final Program program;
        final List<String> notes;
        final List<Item> items = new ArrayList<>();
        final List<FunctionInfo> functions = new ArrayList<>();
        final Map<Integer, CallSite> calls = new HashMap<>();
        final Set<Integer> claimed = new HashSet<>();
        final Set<Integer> hidden = new HashSet<>();
        int structured, passthrough;

        Candidate(Program program, List<String> notes){ this.program = program; this.notes = notes; }

        void recoverFunctions(){
            Map<String, List<Integer>> tails = new HashMap<>();
            for(int i = 0; i < program.statements.size(); i++){
                Statement s = program.statements.get(i);
                if(s.kind().equals("set") && s.tokens.length >= 3 && s.token(1).equals("@counter")){
                    String name = functionNameFromReturn(s.token(2));
                    if(name != null) tails.computeIfAbsent(name, k -> new ArrayList<>()).add(i);
                }
            }
            if(tails.isEmpty()) return;

            Map<String, Integer> entries = new HashMap<>();
            List<Integer> preludePositions = new ArrayList<>();
            for(int i = 0; i + 2 < program.statements.size(); i++){
                Statement set = program.statements.get(i);
                if(!set.kind().equals("set") || set.tokens.length < 3 || !set.token(1).startsWith("__ls_func_")
                    || !set.token(2).equals("@counter")) continue;
                String name = functionNameFromReturn(set.token(1));
                if(name == null || !matches(i + 1, "op", "add", set.token(1), set.token(1), "2")) continue;
                Statement jump = program.statements.get(i + 2);
                if(!jump.isAlways() || jump.target < 0 || !tails.containsKey(name)) continue;
                entries.putIfAbsent(name, jump.target);
                if(entries.get(name) == jump.target) preludePositions.add(i);
            }
            if(entries.isEmpty()) return;

            List<FunctionInfo> found = new ArrayList<>();
            for(Map.Entry<String, Integer> entry : entries.entrySet()){
                List<Integer> list = tails.get(entry.getKey());
                int finalTail = list.get(list.size() - 1);
                if(entry.getValue() < finalTail) found.add(new FunctionInfo(entry.getKey(), entry.getValue(), finalTail));
            }
            found.sort((a, b) -> Integer.compare(a.entry, b.entry));
            for(int i = 0; i + 1 < found.size(); i++){
                if(found.get(i).zoneEnd() > found.get(i + 1).entry){
                    notes.add("overlapping function bodies; function recovery skipped");
                    return;
                }
            }
            functions.addAll(found);
            for(FunctionInfo function : functions){
                for(int i = function.entry; i < function.zoneEnd(); i++) claimed.add(i);
            }

            // The normal-mode compiler inserts one jump over all hoisted bodies.
            int firstEntry = functions.stream().mapToInt(f -> f.entry).min().orElse(program.statements.size());
            int lastEnd = functions.stream().mapToInt(FunctionInfo::zoneEnd).max().orElse(program.statements.size());
            // The compiler adds exactly one main-tail jump immediately before the first
            // hoisted function entry. Do not hide every earlier jump to the same terminal:
            // jump-threaded switch break/default edges can legitimately target __ls_end too.
            int mainTailJump = firstEntry - 1;
            if(mainTailJump >= 0 && mainTailJump < program.statements.size()){
                Statement s = program.statements.get(mainTailJump);
                if(s.isAlways() && s.target >= lastEnd) hidden.add(mainTailJump);
            }

            for(int position : preludePositions){
                CallSite site = makeCallSite(position);
                if(site != null){
                    calls.put(site.from, site);
                    for(int i = site.from; i <= site.to; i++) claimed.add(i);
                }
            }
        }

        /** Binds call arguments by scanning backwards from the @counter prelude for consecutive
         *  'set' statements whose targets appear inside the function body. This relies on name
         *  coincidence: an unrelated earlier 'set' of a same-named variable can be miscounted as
         *  an argument. Harmless here — wrong bindings fail recompilation verification and the
         *  region falls back to raw vanilla statements. */
        private CallSite makeCallSite(int position){
            Statement setRet = program.statements.get(position);
            FunctionInfo function = functionByName(functionNameFromReturn(setRet.token(1)));
            if(function == null) return null;

            Set<String> bodyNames = bodyNames(function);
            List<Integer> bindingPositions = new ArrayList<>();
            int p = position - 1;
            while(p >= 0 && !claimed.contains(p)){
                Statement s = program.statements.get(p);
                if(!s.kind().equals("set") || s.tokens.length < 3 || s.token(1).startsWith("__ls_")) break;
                if(!bodyNames.contains(s.token(1))) break;
                bindingPositions.add(0, p);
                p--;
            }

            StringBuilder args = new StringBuilder();
            List<String> params = new ArrayList<>();
            for(int i = 0; i < bindingPositions.size(); i++){
                if(i > 0) args.append(", ");
                Statement binding = program.statements.get(bindingPositions.get(i));
                String source = binding.token(2);
                if(ExprCompiler.isTemp(source)) return null;
                args.append(source);
                params.add(binding.token(1));
            }
            if(function.params.isEmpty() && !params.isEmpty()) function.params = String.join(",", params);

            int end = position + 2;
            String result = "";
            if(position + 3 < program.statements.size()){
                Statement copy = program.statements.get(position + 3);
                if(copy.kind().equals("set") && copy.tokens.length >= 3 && copy.token(2).equals(function.result())){
                    result = copy.token(1);
                    end = position + 3;
                }
            }
            int from = bindingPositions.isEmpty() ? position : bindingPositions.get(0);
            return new CallSite(from, end, position, function, args.toString(), result);
        }

        private Set<String> bodyNames(FunctionInfo function){
            Set<String> result = new HashSet<>();
            for(int i = function.entry; i < function.finalTail; i++){
                Statement s = program.statements.get(i);
                for(int k = 1; k < s.tokens.length; k++){
                    String token = s.token(k);
                    if(isIdentifier(token) && !token.startsWith("__ls_")) result.add(token);
                }
            }
            return result;
        }

        private static boolean isIdentifier(String token){
            if(token == null || token.isEmpty() || token.startsWith("@") || token.startsWith("\"")) return false;
            char first = token.charAt(0);
            return Character.isLetter(first) || first == '_';
        }

        /** Integral literal in a table operand (guards, op sub offset), or null. */
        private static Double integerLiteral(String token){
            if(token == null || token.isEmpty()) return null;
            try{
                double value = Double.parseDouble(token);
                return value == Math.rint(value) && Double.isFinite(value) ? value : null;
            }catch(NumberFormatException ignored){
                return null;
            }
        }

        private static String functionNameFromReturn(String token){
            if(token == null || !token.startsWith("__ls_func_") || !token.endsWith("_ret")) return null;
            String name = token.substring("__ls_func_".length(), token.length() - "_ret".length());
            return name.isEmpty() ? null : name;
        }

        private FunctionInfo functionByName(String name){
            if(name == null) return null;
            for(FunctionInfo function : functions) if(function.name.equals(name)) return function;
            return null;
        }

        private boolean matches(int index, String... expected){
            if(index < 0 || index >= program.statements.size()) return false;
            Statement statement = program.statements.get(index);
            if(statement.tokens.length < expected.length) return false;
            for(int i = 0; i < expected.length; i++){
                if(!expected[i].equals("*") && !expected[i].equals(statement.token(i))) return false;
            }
            return true;
        }

        void parseMain(){
            parseRange(0, program.statements.size(), null);
            for(FunctionInfo function : functions) emitFunction(function);
        }

        void emitFunction(FunctionInfo function){
            FuncDefItem definition = new FuncDefItem(function.entry, function.name, function.params);
            items.add(definition);
            int cursor = function.entry;
            while(cursor < function.finalTail){
                int next = emitReturnIfPresent(cursor, function);
                if(next > cursor){ cursor = next; continue; }
                if(claimed.contains(cursor)){
                    CallSite call = callAt(cursor);
                    if(call != null){
                        items.add(new FuncCallItem(call.from, call.to, call.function.name, call.args, call.result));
                        structured++;
                        cursor = call.to + 1;
                        continue;
                    }
                }
                Frame frame = tryFrames(cursor, function.finalTail);
                if(frame != null){ emitFrame(frame, null); cursor = frame.resume; }
                else { addFlat(cursor, null); cursor++; }
            }
            BlockEndItem end = new BlockEndItem(function.finalTail);
            items.add(end);
            definition.end = end;
            structured++;
        }

        int emitReturnIfPresent(int cursor, FunctionInfo function){
            Statement current = program.statements.get(cursor);
            if(current.kind().equals("set") && current.tokens.length >= 3
                && current.token(1).equals("@counter") && current.token(2).equals(function.ret())){
                items.add(new ReturnItem(cursor, cursor, ""));
                structured++;
                return cursor + 1;
            }
            if(!isChainStatement(current)) return cursor;
            List<ExprCompiler.Line> lines = new ArrayList<>();
            int p = cursor;
            while(p < function.finalTail && isChainStatement(program.statements.get(p))){
                lines.add(toExprLine(program.statements.get(p)));
                p++;
            }
            if(p >= function.finalTail) return cursor;
            Statement ret = program.statements.get(p);
            if(!ret.kind().equals("set") || ret.tokens.length < 3 || !ret.token(1).equals("@counter")
                || !ret.token(2).equals(function.ret())) return cursor;
            if(lines.isEmpty()) return cursor;
            ExprCompiler.Line last = lines.get(lines.size() - 1);
            if(!(last instanceof ExprCompiler.OpLine op) || !op.dest.equals(function.result())) return cursor;
            String expression = rebuild(lines);
            if(expression == null) return cursor;
            items.add(new ReturnItem(cursor, p, expression));
            structured++;
            return p + 1;
        }

        private boolean isChainStatement(Statement s){
            return s.kind().equals("op") || s.kind().equals("sensor");
        }

        private ExprCompiler.Line toExprLine(Statement s){
            if(s.kind().equals("sensor")) return new ExprCompiler.SensorLine(s.token(1), s.token(2), s.token(3));
            return new ExprCompiler.OpLine(s.token(1), s.token(2), s.token(3), s.token(4));
        }

        private String rebuild(List<ExprCompiler.Line> lines){
            try{ return ExprCompiler.rebuild(lines); }
            catch(Throwable ignored){ return null; }
        }

        private CallSite callAt(int cursor){
            for(CallSite call : calls.values()) if(cursor >= call.from && cursor <= call.to) return call;
            return null;
        }

        void resetFlat(){
            items.clear();
            structured = 0;
            passthrough = 0;
            for(int i = 0; i < program.statements.size(); i++){
                if(hidden.contains(i) || claimedFunction(i)) continue;
                addFlat(i, null);
            }
        }

        private boolean claimedFunction(int index){
            for(FunctionInfo function : functions){
                if(index >= function.entry && index < function.zoneEnd()) return true;
            }
            return false;
        }

        private void parseRange(int from, int to, Context context){
            int cursor = from;
            while(cursor < to){
                if(hidden.contains(cursor)){ cursor++; continue; }
                FunctionInfo owner = functionAt(cursor);
                if(owner != null){ cursor = owner.zoneEnd(); continue; }
                CallSite call = calls.get(cursor);
                if(call != null){
                    items.add(new FuncCallItem(call.from, call.to, call.function.name, call.args, call.result));
                    structured++;
                    cursor = call.to + 1;
                    continue;
                }

                Frame frame = tryFrames(cursor, to);
                if(frame != null){
                    emitFrame(frame, context);
                    cursor = frame.resume;
                }else{
                    addFlat(cursor, context);
                    cursor++;
                }
            }
        }

        /** Single structure-trial pipeline shared by the main range and function bodies, so
         *  identical instruction shapes recover identically everywhere. The @counter jump
         *  table is tried before the comparison-chain switch (its {@code op add @counter}
         *  dispatch distinguishes the shapes unambiguously); {@code while} is tried before
         *  {@code for} because a loop without a leading 'set' initialization compiles to the
         *  exact same instruction stream as its whilebegin form; trying for first would
         *  rewrite every while into a degenerate forbegin. An explicit initializer cannot be
         *  claimed by while at all (readCondition rejects 'set' heads), so real for-loops
         *  still recover as for either way. Any wrong guess is caught by recompilation
         *  verification. */
        private Frame tryFrames(int at, int limit){
            Frame frame = trySwitchTable(at, limit);
            if(frame == null) frame = trySwitch(at, limit);
            if(frame == null) frame = tryWhile(at, limit);
            if(frame == null) frame = tryFor(at, limit);
            if(frame == null) frame = tryIf(at, limit);
            return frame;
        }

        /**
         * Recovers the @counter jump-table lowering of a switch: [optional op sub] + two
         * bounds guards + {@code op add @counter @counter idx} + span unconditional slot
         * rows. Guards and hole slots share the default target; case slots point at body
         * starts and group back into ascending case labels. Slot targets retargeted by jump
         * threading (a hole that hops straight to wherever the default chain ended) are
         * classified by chasing unconditional chains, so both lowering eras recover.
         * Anything unmatched falls through to the comparison-chain or flat vanilla paths;
         * recompilation verification stays the final gate.
         */
        private Frame trySwitchTable(int at, int limit){
            if(at >= limit) return null;
            double min = 0;
            int cursor = at;
            String switchValue;
            Statement first = program.statements.get(at);
            boolean hasSub = first.kind().equals("op") && first.tokens.length >= 5
                && "sub".equals(first.token(1)) && first.token(2).startsWith("__ls_sw_");
            if(hasSub){
                Double parsed = integerLiteral(first.token(4));
                if(parsed == null || parsed != Math.rint(parsed) || Math.abs(parsed) > 9007199254740992d
                    || first.token(3).isEmpty()) return null;
                min = parsed;
                switchValue = first.token(3);
                cursor++;
            }else{
                switchValue = null;
            }

            // lower bound: jump D lessThan <idx> 0
            if(cursor + 2 >= limit) return null;
            Statement guardLow = program.statements.get(cursor);
            if(guardLow.target < 0
                || !(guardLow.isConditional() && "lessThan".equals(guardLow.token(2)) && "0".equals(guardLow.token(4)))) return null;
            String idx = guardLow.token(3);
            if(idx.isEmpty()) return null;
            if(hasSub){
                if(!idx.equals(first.token(2))) return null;
            }else{
                switchValue = idx;
            }

            // upper bound: jump D greaterThan <idx> <span-1>
            Statement guardHigh = program.statements.get(cursor + 1);
            if(!(guardHigh.isConditional() && "greaterThan".equals(guardHigh.token(2))
                && guardHigh.token(3).equals(idx) && guardHigh.target == guardLow.target)) return null;
            Double spanMinusOne = integerLiteral(guardHigh.token(4));
            if(spanMinusOne == null || spanMinusOne < 0 || spanMinusOne > SugarFunctions.MAX_TABLE_SPAN - 1) return null;
            int span = (int)(double)spanMinusOne + 1;

            // dispatch: op add @counter @counter <idx>
            Statement dispatch = program.statements.get(cursor + 2);
            if(!(dispatch.kind().equals("op") && dispatch.tokens.length >= 5
                && "add".equals(dispatch.token(1)) && "@counter".equals(dispatch.token(2))
                && "@counter".equals(dispatch.token(3)) && dispatch.token(4).equals(idx))) return null;

            int rowsStart = cursor + 3;
            int lastRow = rowsStart + span - 1;
            if(lastRow >= limit) return null;
            int[] rowTargets = new int[span];
            for(int k = 0; k < span; k++){
                Statement row = program.statements.get(rowsStart + k);
                if(!row.isAlways() || row.target < 0) return null;
                rowTargets[k] = row.target;
            }

            int exit = guardLow.target;
            if(exit <= lastRow || exit > limit) return null;
            int terminalExit = followAlwaysChain(exit);

            // Group slot values by their body target. The direct default target and its
            // terminal always-jump target are both default holes, which covers output from
            // before and after the compiler's jump-threading pass.
            TreeMap<Integer, TreeSet<Long>> interior = new TreeMap<>();
            for(int k = 0; k < span; k++){
                long value = (long)min + k;
                int target = rowTargets[k];
                if(target == exit || target == terminalExit) continue;
                if(target < lastRow + 1 || target >= exit) return null;
                interior.computeIfAbsent(target, key -> new TreeSet<>()).add(value);
            }
            if(interior.isEmpty()) return null;

            Frame frame = new Frame();
            frame.kind = Frame.Kind.SWITCH;
            frame.start = at; frame.exit = exit; frame.resume = exit;
            frame.breakTarget = terminalExit != exit ? terminalExit : exit;
            frame.switchValue = switchValue;
            frame.caseTargets = new ArrayList<>();
            frame.caseValues = new ArrayList<>();
            for(Map.Entry<Integer, TreeSet<Long>> entry : interior.entrySet()){
                for(long value : entry.getValue()){
                    frame.caseTargets.add(entry.getKey());
                    frame.caseValues.add(Long.toString(value));
                }
            }

            // Repeated case values which targeted the same first label are invisible after
            // label folding. Reinsert only zero-length labels (same target, before its body)
            // until a recompilation still selects the table; they add no executable lines.
            int tableCost = (min != 0 ? 1 : 0) + 3 + span;
            int requiredCases = tableCost - 1;
            int additions = Math.max(0, requiredCases - frame.caseValues.size());
            if(additions > 0){
                int target = frame.caseTargets.get(0);
                String value = frame.caseValues.get(0);
                for(int i = 0; i < additions; i++){
                    frame.caseTargets.add(0, target);
                    frame.caseValues.add(0, value);
                }
            }
            return frame;
        }

        /** Follows an unconditional-jump chain from a folded label position to its end. */
        private int followAlwaysChain(int at){
            Set<Integer> seen = new HashSet<>();
            int current = at;
            while(current >= 0 && current < program.statements.size() && seen.add(current)){
                Statement s = program.statements.get(current);
                if(!s.isAlways() || s.target < 0) break;
                current = s.target;
            }
            return current;
        }

        private FunctionInfo functionAt(int index){
            for(FunctionInfo function : functions){
                if(index == function.entry) return function;
            }
            return null;
        }

        private void addFlat(int index, Context context){
            if(index < 0 || index >= program.statements.size()) return;
            Statement s = program.statements.get(index);
            if(s.isJump() && s.tokens.length >= 5 && s.target >= 0){
                if(s.isAlways() && context != null){
                    if(s.target == context.nearestBreak()){
                        items.add(new BreakItem(index)); structured++; return;
                    }
                    if(s.target == context.nearestContinue()){
                        items.add(new ContinueItem(index)); structured++; return;
                    }
                }
                items.add(new JumpItem(index, s.target, s.isAlways() ? "always" : s.token(2), s.token(3), s.token(4)));
                passthrough++;
            }else if(s.kind().equals("end")){
                items.add(new EndItem(index)); passthrough++;
            }else{
                items.add(new RawItem(index, s.tokens)); passthrough++;
            }
        }

        private void emitFrame(Frame frame, Context parent){
            switch(frame.kind){
                case FOR -> {
                    ForItem header = new ForItem(frame.start, frame.start, frame.variable, frame.initial, frame.step, frame.condition);
                    items.add(header);
                    parseRange(frame.bodyStart, frame.bodyEnd, new Context(frame.exit, frame.continueTarget, parent));
                    BlockEndItem end = new BlockEndItem(frame.exit);
                    items.add(end);
                    header.end = end;
                    structured++;
                }
                case WHILE -> {
                    WhileItem header = new WhileItem(frame.start, frame.start, frame.condition);
                    items.add(header);
                    parseRange(frame.bodyStart, frame.bodyEnd, new Context(frame.exit, frame.continueTarget, parent));
                    BlockEndItem end = new BlockEndItem(frame.exit);
                    items.add(end);
                    header.end = end;
                    structured++;
                }
                case SWITCH -> {
                    SwitchItem header = new SwitchItem(frame.start, frame.switchValue);
                    items.add(header);
                    for(int i = 0; i < frame.caseTargets.size(); i++){
                        int start = frame.caseTargets.get(i);
                        int end = i + 1 < frame.caseTargets.size() ? frame.caseTargets.get(i + 1) : frame.exit;
                        items.add(new CaseItem(start, frame.caseValues.get(i)));
                        int breakTarget = frame.breakTarget >= 0 ? frame.breakTarget : frame.exit;
                        parseRange(start, end, new Context(breakTarget, -1, parent));
                        structured++;
                    }
                    BlockEndItem end = new BlockEndItem(frame.exit);
                    items.add(end);
                    header.end = end;
                    structured++;
                }
                case IF -> {
                    IfFrame chain = frame.ifFrame;
                    ConditionParse first = chain.branches.get(0);
                    IfItem header = new IfItem(first.start, first.jump, first.condition);
                    items.add(header);
                    parseRange(first.jump + 1, chain.bodyEnds.get(0), parent);
                    for(int i = 1; i < chain.branches.size(); i++){
                        ConditionParse branch = chain.branches.get(i);
                        items.add(new ElseIfItem(branch.start, branch.jump, branch.condition));
                        structured++;
                        parseRange(branch.jump + 1, chain.bodyEnds.get(i), parent);
                    }
                    if(chain.hasElse){
                        items.add(new ElseItem(chain.elseMarker));
                        structured++;
                        parseRange(chain.elseStart, chain.elseEnd, parent);
                    }
                    BlockEndItem end = new BlockEndItem(chain.exit);
                    items.add(end);
                    header.end = end;
                    structured++;
                }
            }
        }

        private Frame trySwitch(int at, int limit){
            if(at >= limit) return null;
            Statement first = program.statements.get(at);
            if(!first.isConditional() || !first.token(2).equals("equal")) return null;
            String value = first.token(3);
            List<Integer> targets = new ArrayList<>();
            List<String> values = new ArrayList<>();
            int cursor = at;
            while(cursor < limit){
                Statement dispatch = program.statements.get(cursor);
                if(!dispatch.isConditional() || !dispatch.token(2).equals("equal")
                    || !dispatch.token(3).equals(value)) break;
                targets.add(dispatch.target);
                values.add(dispatch.token(4));
                cursor++;
            }
            if(targets.isEmpty() || cursor >= limit) return null;
            Statement defaultJump = program.statements.get(cursor);
            if(!defaultJump.isAlways()) return null;
            int exit = defaultJump.target;
            if(exit <= cursor || exit > limit || targets.get(0) != cursor + 1) return null;
            for(int i = 0; i + 1 < targets.size(); i++) if(targets.get(i) >= targets.get(i + 1)) return null;
            for(int target : targets) if(target <= cursor || target >= exit) return null;
            Frame frame = new Frame();
            frame.kind = Frame.Kind.SWITCH;
            frame.start = at; frame.exit = exit; frame.resume = exit;
            frame.switchValue = value; frame.caseTargets = targets; frame.caseValues = values;
            return frame;
        }

        private Frame tryFor(int at, int limit){
            if(at >= limit) return null;
            int init = -1;
            ConditionParse condition = readCondition(at, limit);
            if(condition == null && isInitialSet(at)){
                init = at;
                condition = readCondition(at + 1, limit);
            }
            if(condition == null) return null;
            if(condition.condition.expression && init < 0) return null; // variable cannot be recovered safely
            int exitJumpIndex = condition.jump + 1;
            if(exitJumpIndex >= limit || !program.statements.get(exitJumpIndex).isAlways()) return null;
            int exit = program.statements.get(exitJumpIndex).target;
            int body = condition.body;
            if(exit <= body || exit > limit) return null;
            int back = exit - 1;
            if(back < body) return null;
            Statement backJump = program.statements.get(back);
            if(!backJump.isAlways() || backJump.target != condition.start) return null;

            String variable;
            String initial;
            if(init >= 0){ variable = program.statements.get(init).token(1); initial = program.statements.get(init).token(2); }
            else{
                if(condition.condition.expression) return null;
                variable = condition.condition.value;
                initial = "";
            }
            String step = "";
            int bodyEnd = back;
            if(back - 1 >= body){
                Statement increment = program.statements.get(back - 1);
                if(isIncrement(increment, variable)){
                    step = increment.token(4);
                    bodyEnd = back - 1;
                }
            }
            Frame frame = new Frame();
            frame.kind = Frame.Kind.FOR;
            frame.start = init >= 0 ? init : condition.start;
            frame.bodyStart = body; frame.bodyEnd = bodyEnd;
            frame.exit = exit; frame.resume = exit; frame.continueTarget = step.isEmpty() ? back : bodyEnd;
            frame.variable = variable; frame.initial = initial; frame.step = step; frame.condition = condition.condition;
            return frame;
        }

        private Frame tryWhile(int at, int limit){
            ConditionParse condition = readCondition(at, limit);
            if(condition == null) return null;
            int exitJumpIndex = condition.jump + 1;
            if(exitJumpIndex >= limit || !program.statements.get(exitJumpIndex).isAlways()) return null;
            int exit = program.statements.get(exitJumpIndex).target;
            int body = condition.body;
            if(exit <= body || exit > limit) return null;
            int back = exit - 1;
            Statement backJump = program.statements.get(back);
            if(!backJump.isAlways() || backJump.target != condition.start) return null;
            Frame frame = new Frame();
            frame.kind = Frame.Kind.WHILE;
            frame.start = condition.start; frame.bodyStart = body; frame.bodyEnd = back;
            frame.exit = exit; frame.resume = exit; frame.continueTarget = condition.start;
            frame.condition = condition.condition;
            return frame;
        }

        private Frame tryIf(int at, int limit){
            ConditionParse first = readCondition(at, limit);
            if(first == null || first.condition.expression && first.start == first.jump) return null;
            // A native comparison is handled here; expression conditions are handled too, but
            // only when they start with an op/sensor chain (not a bare private-temp jump).
            IfFrame chain = new IfFrame();
            first = withIfPolarity(first);
            chain.branches.add(first);
            int head = first.start;
            int falseTarget = first.falseTarget;
            while(true){
                if(falseTarget <= head || falseTarget > limit) return null;
                int guard = falseTarget - 1;
                boolean guardPresent = guard > head && guard < limit && program.statements.get(guard).isAlways()
                    && program.statements.get(guard).target > falseTarget;
                if(guardPresent){
                    int exit = program.statements.get(guard).target;
                    if(chain.exit != 0 && chain.exit != exit) return null;
                    chain.exit = exit;
                    chain.bodyEnds.add(guard);
                    ConditionParse next = readCondition(falseTarget, limit);
                    if(next != null){
                        next = withIfPolarity(next);
                        chain.branches.add(next);
                        head = next.start;
                        falseTarget = next.falseTarget;
                        continue;
                    }
                    chain.hasElse = true;
                    chain.elseMarker = guard;
                    chain.elseStart = falseTarget;
                    chain.elseEnd = exit;
                    break;
                }
                chain.bodyEnds.add(falseTarget);
                chain.exit = falseTarget;
                break;
            }
            if(chain.branches.size() != chain.bodyEnds.size() || chain.exit <= at || chain.exit > limit) return null;
            Frame frame = new Frame();
            frame.kind = Frame.Kind.IF; frame.start = at; frame.resume = chain.exit; frame.exit = chain.exit; frame.ifFrame = chain;
            return frame;
        }

        private ConditionParse withIfPolarity(ConditionParse source){
            if(source.condition.expression) return source;
            ConditionOp lowered = op(program.statements.get(source.jump).token(2));
            ConditionOp positive = unNegate(lowered);
            return positive == null ? source
                : new ConditionParse(source.start, source.jump, source.body, source.falseTarget,
                    new Condition(source.condition.value, positive.name(), source.condition.compare));
        }

        private static ConditionOp unNegate(ConditionOp operation){
            return switch(operation){
                case notEqual -> ConditionOp.equal;
                case equal -> ConditionOp.notEqual;
                case lessThan -> ConditionOp.greaterThanEq;
                case lessThanEq -> ConditionOp.greaterThan;
                case greaterThan -> ConditionOp.lessThanEq;
                case greaterThanEq -> ConditionOp.lessThan;
                default -> null;
            };
        }

        private ConditionParse readCondition(int start, int limit){
            if(start < 0 || start >= limit) return null;
            Statement direct = program.statements.get(start);
            if(direct.isConditional()){
                // raw strictEqual has no exact inverse; keep the jump as a vanilla statement
                ConditionOp lowered = op(direct.token(2));
                if(lowered == null || lowered == ConditionOp.always || lowered == ConditionOp.strictEqual) return null;
                if(direct.token(3).startsWith("__ls_cond_")) return null;
                return new ConditionParse(start, start, direct.target, direct.target,
                    new Condition(direct.token(3), lowered.name(), direct.token(4)));
            }
            if(!isChainStatement(direct) || !isPrivateDestination(direct)) return null;
            List<ExprCompiler.Line> lines = new ArrayList<>();
            int cursor = start;
            while(cursor < limit && isChainStatement(program.statements.get(cursor))
                && isPrivateDestination(program.statements.get(cursor))){
                lines.add(toExprLine(program.statements.get(cursor)));
                cursor++;
            }
            if(cursor >= limit) return null;
            Statement jump = program.statements.get(cursor);
            if(!jump.isConditional() || !jump.token(3).startsWith("__ls_cond_") || !jump.token(4).equals("0")) return null;
            String expression = rebuildPrivate(lines);
            if(expression == null) return null;
            if(!jump.token(2).equals("equal") && !jump.token(2).equals("notEqual")) return null;
            return new ConditionParse(start, cursor, cursor + 1, jump.target, new Condition(expression));
        }

        private String rebuildPrivate(List<ExprCompiler.Line> original){
            Map<String, String> names = new HashMap<>();
            List<ExprCompiler.Line> lines = new ArrayList<>();
            int next = 0;
            for(ExprCompiler.Line line : original){
                if(line instanceof ExprCompiler.SensorLine sensor){
                    String dest = privateTemp(sensor.dest, names, next);
                    next = names.size();
                    String a = privateTemp(sensor.a, names, next);
                    next = names.size();
                    String b = privateTemp(sensor.b, names, next);
                    next = names.size();
                    lines.add(new ExprCompiler.SensorLine(dest, a, b));
                }else if(line instanceof ExprCompiler.OpLine op){
                    String dest = privateTemp(op.dest, names, next);
                    next = names.size();
                    String a = privateTemp(op.a, names, next);
                    next = names.size();
                    String b = privateTemp(op.b, names, next);
                    next = names.size();
                    lines.add(new ExprCompiler.OpLine(op.op, dest, a, b));
                }
            }
            if(lines.isEmpty()) return null;
            return rebuild(lines);
        }

        private String privateTemp(String value, Map<String, String> names, int next){
            if(!value.startsWith("__ls_cond_")) return value;
            return names.computeIfAbsent(value, k -> "_" + names.size());
        }

        private static boolean isPrivateDestination(Statement s){
            return s.kind().equals("sensor")
                ? s.tokens.length >= 2 && s.token(1).startsWith("__ls_cond_")
                : s.tokens.length >= 3 && s.token(2).startsWith("__ls_cond_");
        }

        private static ConditionOp op(String token){
            try{ return ConditionOp.valueOf(token); }
            catch(IllegalArgumentException exception){ return null; }
        }

        private boolean isInitialSet(int index){
            if(index < 0 || index >= program.statements.size()) return false;
            Statement s = program.statements.get(index);
            return s.kind().equals("set") && s.tokens.length >= 3 && !s.token(1).equals("@counter")
                && !ExprCompiler.isTemp(s.token(1));
        }

        private static boolean isIncrement(Statement s, String variable){
            return s.kind().equals("op") && s.tokens.length >= 5 && s.token(1).equals("add")
                && s.token(2).equals(variable) && s.token(3).equals(variable);
        }

        String emit(){ return new Emitter(items, program.statements.size()).emit(); }
    }

    // ===== Emitter ========================================================================

    private static final class Coverage{
        final int from, to, priority, slot;
        Coverage(int from, int to, int priority, int slot){ this.from = from; this.to = to; this.priority = priority; this.slot = slot; }
    }

    private static final class Emitter{
        final List<Item> items;
        final int statementCount;
        final Map<Item, Integer> slots = new HashMap<>();
        final List<Coverage> coverage = new ArrayList<>();

        Emitter(List<Item> items, int statementCount){ this.items = items; this.statementCount = statementCount; }

        String emit(){
            for(int i = 0; i < items.size(); i++){
                Item item = items.get(i);
                slots.put(item, i);
                coverage.add(new Coverage(item.from, item.to, item.priority(), i));
            }
            StringBuilder result = new StringBuilder();
            for(Item item : items) item.write(result, this);
            return result.toString();
        }

        int itemSlot(Item item){
            Integer slot = slots.get(item);
            return slot == null ? items.size() : slot;
        }

        int targetSlot(int original){
            if(original < 0) return 0;
            if(original >= statementCount) return items.size();
            Coverage best = null;
            for(Coverage candidate : coverage){
                if(candidate.priority <= 0) continue;
                if(original >= candidate.from && original <= candidate.to
                    && (best == null || candidate.priority > best.priority)) best = candidate;
            }
            if(best != null) return best.slot;
            for(int i = 0; i < items.size(); i++){
                Item item = items.get(i);
                if(item instanceof BlockEndItem) continue;
                if(item.from >= original) return i;
            }
            return items.size();
        }
    }
}
