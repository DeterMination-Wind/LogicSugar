package mindustry.logic;

import arc.struct.Seq;
import mindustry.logic.LStatements.JumpStatement;
import mindustry.logic.LStatements.OperationStatement;
import mindustry.logic.SugarStatements.BreakStatement;
import mindustry.logic.SugarStatements.BeginStatement;
import mindustry.logic.SugarStatements.BlockEndStatement;
import mindustry.logic.SugarStatements.CaseStatement;
import mindustry.logic.SugarStatements.ForBeginStatement;
import mindustry.logic.SugarStatements.SwitchBeginStatement;
import mindustry.logic.SugarStatements.WhileBeginStatement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SugarCompiler{
    private static final String markerBegin = "# @logic-sugar-v1 begin";
    private static final String markerLine = "# @logic-sugar-line ";
    private static final String markerEnd = "# @logic-sugar-v1 end";

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

    public static String compile(String sugar){
        Seq<LStatement> statements = LAssembler.read(sugar, true);
        if(!containsSugar(statements)) return sugar;

        validatePairs(statements);
        int[] switchOwner = switchOwners(statements);
        int[] breakOwner = breakOwners(statements);
        boolean[] statementLabels = statementLabels(statements, breakOwner);
        String[] optimizedOperations = optimizeOperations(statements, statementLabels);
        StringBuilder out = new StringBuilder();

        for(int i = 0; i < statements.size; i++){
            if(statementLabels[i]) out.append(statementLabel(i)).append(":\n");
            if(optimizedOperations[i] != null){
                out.append(optimizedOperations[i]);
                continue;
            }
            LStatement statement = statements.get(i);

            if(statement instanceof ForBeginStatement begin){
                String id = Integer.toString(i);
                out.append("jump __ls_for_check_").append(id).append(" notEqual __ls_for_init_").append(id).append(" 0\n");
                if(!begin.initial.isEmpty()) out.append("set ").append(begin.variable).append(' ').append(begin.initial).append('\n');
                out.append("set __ls_for_init_").append(id).append(" 1\n");
                out.append("__ls_for_check_").append(id).append(":\n");
                out.append("jump __ls_for_body_").append(id).append(' ').append(begin.op.name()).append(' ')
                    .append(begin.variable).append(' ').append(begin.compare).append('\n');
                out.append("set __ls_for_init_").append(id).append(" 0\n");
                out.append("jump ").append(statementLabel(begin.destIndex + 1)).append(" always x false\n");
                out.append("__ls_for_body_").append(id).append(":\n");
            }else if(statement instanceof WhileBeginStatement begin){
                out.append("jump __ls_while_body_").append(i).append(" notEqual ").append(begin.condition).append(" false\n");
                out.append("jump ").append(statementLabel(begin.destIndex + 1)).append(" always x false\n");
                out.append("__ls_while_body_").append(i).append(":\n");
            }else if(statement instanceof SwitchBeginStatement begin){
                for(int at = i + 1; at < begin.destIndex; at++){
                    if(switchOwner[at] == i && statements.get(at) instanceof CaseStatement item){
                        out.append("jump __ls_case_").append(at).append(" equal ").append(begin.value).append(' ').append(item.value).append('\n');
                    }
                }
                out.append("jump ").append(statementLabel(begin.destIndex + 1)).append(" always x false\n");
            }else if(statement instanceof CaseStatement){
                if(switchOwner[i] < 0) throw error("case", i, "is outside a switch");
                out.append("__ls_case_").append(i).append(":\n");
            }else if(statement instanceof BreakStatement){
                if(breakOwner[i] < 0) throw error("break", i, "is outside a loop or switch");
                BeginStatement owner = (BeginStatement)statements.get(breakOwner[i]);
                out.append("jump ").append(statementLabel(owner.destIndex + 1)).append(" always x false\n");
            }else if(statement instanceof BlockEndStatement){
                int beginIndex = findOwner(statements, i);
                LStatement owner = statements.get(beginIndex);
                if(owner instanceof ForBeginStatement begin){
                    if(!begin.step.isEmpty()) out.append("op add ").append(begin.variable).append(' ').append(begin.variable).append(' ').append(begin.step).append('\n');
                    out.append("jump __ls_for_check_").append(beginIndex).append(" always x false\n");
                }else if(owner instanceof WhileBeginStatement){
                    out.append("jump ").append(statementLabel(beginIndex)).append(" always x false\n");
                }
            }else if(statement instanceof JumpStatement jump){
                if(jump.destIndex < 0 || jump.destIndex > statements.size){
                    throw error("jump", i, "has no valid destination");
                }
                out.append("jump ").append(statementLabel(jump.destIndex)).append(' ').append(jump.op.name()).append(' ')
                    .append(jump.value).append(' ').append(jump.compare).append('\n');
            }else{
                statement.write(out);
                out.append('\n');
            }
        }
        if(statementLabels[statements.size]) out.append(statementLabel(statements.size)).append(":\n");

        int instructionCount = LAssembler.read(out.toString(), true).size;
        if(instructionCount > LExecutor.maxInstructions){
            throw new IllegalArgumentException("Compiled program has " + instructionCount + " instructions; maximum is " + LExecutor.maxInstructions + ".");
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
        return invalid;
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
        Arrays.fill(result, -1);
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
        Arrays.fill(result, -1);
        Deque<Integer> stack = new ArrayDeque<>();
        for(int i = 0; i < statements.size; i++){
            while(!stack.isEmpty() && ((BeginStatement)statements.get(stack.peek())).destIndex < i) stack.pop();
            if(!stack.isEmpty()) result[i] = stack.peek();
            if(isBreakable(statements.get(i))) stack.push(i);
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

    private static String statementLabel(int index){
        return "__ls_stmt_" + index;
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
