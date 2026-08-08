package mindustry.logic;

import arc.Core;
import arc.struct.Seq;
import mindustry.logic.LExecutor;
import mindustry.logic.SugarStatements.BeginStatement;
import mindustry.logic.SugarStatements.BlockEndStatement;
import mindustry.logic.SugarStatements.BreakStatement;
import mindustry.logic.SugarStatements.CaseStatement;
import mindustry.logic.SugarStatements.ForBeginStatement;
import mindustry.logic.SugarStatements.FuncCallStatement;
import mindustry.logic.SugarStatements.FuncDefStatement;
import mindustry.logic.SugarStatements.SwitchBeginStatement;
import mindustry.logic.SugarStatements.WhileBeginStatement;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

public final class SugarCompiler{
    private static final String markerBegin = "# @logic-sugar-v1 begin";
    private static final String markerLine = "# @logic-sugar-line ";
    private static final String markerEnd = "# @logic-sugar-v1 end";

    /** Function expansion mode. normal = shared @counter subroutine; inline = per-call copy. */
    public enum FuncMode{
        normal, inline;

        public static FuncMode parse(String value){
            if("inline".equalsIgnoreCase(value)) return inline;
            return normal;
        }
    }

    private SugarCompiler(){}

    public static String restore(String code){
        String[] lines = code.replace("\r\n", "\n").split("\n", -1);
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

    /** Compiles with the user-selected function mode (normal when settings are unavailable). */
    public static String compile(String sugar){
        return compile(sugar, currentMode());
    }

    public static String compile(String sugar, FuncMode mode){
        return compile(sugar, mode, SugarFunctions.library());
    }

    public static String compile(String sugar, FuncMode mode, SugarFunctions.LibraryIndex library){
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

        // LAssembler.read silently truncates at LExecutor.maxInstructions lines, so the count
        // must be computed from the emitted text itself (one instruction per non-label line).
        int instructionCount = countInstructions(out);
        if(instructionCount > LExecutor.maxInstructions){
            String hint = mode == FuncMode.inline ? " Switch to normal mode to share function bodies." : "";
            throw new IllegalArgumentException("Compiled program has " + instructionCount + " instructions; maximum is " + LExecutor.maxInstructions + "." + hint);
        }

        appendMarker(out, sugar);
        return out.toString();
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
        for(int i = 0; i < statements.size; i++){
            if(statements.get(i) instanceof CaseStatement && switchOwner[i] < 0){
                invalid[i] = true;
            }
            if(statements.get(i) instanceof BreakStatement && breakOwner[i] < 0){
                invalid[i] = true;
            }
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

    private static FuncMode currentMode(){
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
