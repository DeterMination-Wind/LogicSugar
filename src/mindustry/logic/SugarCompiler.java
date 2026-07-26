package mindustry.logic;

import arc.struct.Seq;
import mindustry.logic.LStatements.JumpStatement;
import mindustry.logic.SugarStatements.BreakStatement;
import mindustry.logic.SugarStatements.BeginStatement;
import mindustry.logic.SugarStatements.BlockEndStatement;
import mindustry.logic.SugarStatements.CaseStatement;
import mindustry.logic.SugarStatements.ForBeginStatement;
import mindustry.logic.SugarStatements.SwitchBeginStatement;
import mindustry.logic.SugarStatements.WhileBeginStatement;

import java.util.ArrayDeque;
import java.util.Deque;

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
        StringBuilder out = new StringBuilder();

        for(int i = 0; i < statements.size; i++){
            out.append(statementLabel(i)).append(":\n");
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
                out.append("set __ls_switch_").append(i).append(' ').append(begin.value).append('\n');
                for(int at = i + 1; at < begin.destIndex; at++){
                    if(switchOwner[at] == i && statements.get(at) instanceof CaseStatement item){
                        out.append("jump __ls_case_").append(at).append(" equal __ls_switch_").append(i).append(' ').append(item.value).append('\n');
                    }
                }
                out.append("jump ").append(statementLabel(begin.destIndex + 1)).append(" always x false\n");
            }else if(statement instanceof CaseStatement){
                if(switchOwner[i] < 0) throw error("case", i, "is outside a switch");
                out.append("__ls_case_").append(i).append(":\n");
            }else if(statement instanceof BreakStatement){
                if(switchOwner[i] < 0) throw error("break", i, "is outside a switch");
                SwitchBeginStatement owner = (SwitchBeginStatement)statements.get(switchOwner[i]);
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
        out.append(statementLabel(statements.size)).append(":\n");

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
        for(int i = 0; i < statements.size; i++){
            if((statements.get(i) instanceof CaseStatement || statements.get(i) instanceof BreakStatement) && switchOwner[i] < 0){
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
        java.util.Arrays.fill(result, -1);
        Deque<Integer> stack = new ArrayDeque<>();
        for(int i = 0; i < statements.size; i++){
            while(!stack.isEmpty() && ((SwitchBeginStatement)statements.get(stack.peek())).destIndex < i) stack.pop();
            if(!stack.isEmpty()) result[i] = stack.peek();
            if(statements.get(i) instanceof SwitchBeginStatement) stack.push(i);
        }
        return result;
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
