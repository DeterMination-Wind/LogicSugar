package mindustry.logic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Main-based self-test for the mlog control-flow graph IR. Follows the project's JavaExec
 * convention (see {@code SugarDecompilerTest}): plain {@code check} assertions, no JUnit
 * runner. Programs are written as mlog text and tokenized with the same rules as
 * {@code SugarDecompiler.Program}, so every fixture uses the exact input model of
 * {@link MlogCFG#build(List)}.
 */
public final class MlogCFGTest{
    private MlogCFGTest(){}

    public static void main(String[] args){
        straightLineIsOneBlock();
        ifForkAndJoin();
        whileBackEdgeAndNaturalLoop();
        multiEntryCycleIsNotANaturalLoop();
        codeAfterEndIsUnreachable();
        dominanceInvariants();
        writesCounterClassification();
        readsWritesExtraction();
        outOfRangeJumpsAreHarmless();
        emptyProgramHasNoBlocks();
        System.out.println("LogicSugar CFG self-test passed.");
    }

    /** set x 1 / print x / op add y x 1 / print y — one block, no edges, no loops. */
    private static void straightLineIsOneBlock(){
        MlogCFG cfg = build("set x 1\nprint x\nop add y x 1\nprint y\n");
        check(cfg.blockCount() == 1, "straight-line program expected exactly one block, got " + cfg.blockCount());
        MlogCFG.Block block = cfg.block(0);
        check(block.from == 0 && block.to == 4, "single block did not span instructions [0, 4)");
        check(block.successors.isEmpty() && block.predecessors.isEmpty(), "single block must have no edges");
        check(block.reachable, "entry block was not reachable");
        check(cfg.blockAt(0) == 0 && cfg.blockAt(3) == 0, "blockAt did not map instructions to the block");
        check(cfg.loops().isEmpty(), "straight-line program reported a loop");
    }

    /**
     * 0: jump 2 notEqual x 1
     * 1: set y 1        (then body)
     * 2: print y        (join)
     * 3: end
     * Blocks [0,1) [1,2) [2,4): the conditional jump forks, both paths rejoin at block 2.
     */
    private static void ifForkAndJoin(){
        MlogCFG cfg = build("jump 2 notEqual x 1\nset y 1\nprint y\nend\n");
        check(cfg.blockCount() == 3, "if shape expected 3 blocks, got " + cfg.blockCount());
        MlogCFG.Block cond = cfg.block(0);
        check(cond.from == 0 && cond.to == 1, "condition block should hold only the jump");
        check(cond.successors.size() == 2 && cond.successors.contains(1) && cond.successors.contains(2),
            "conditional jump must fork into fallthrough + target: " + cond.successors);
        check(cond.successors.get(0) == 2, "the taken edge is listed before the fallthrough edge");
        MlogCFG.Block body = cfg.block(1);
        check(body.from == 1 && body.to == 2, "then body should be exactly instruction 1");
        check(body.successors.size() == 1 && body.successors.contains(2),
            "then body must fall through into the join");
        MlogCFG.Block join = cfg.block(2);
        check(join.to == 4, "join block should run through end");
        check(join.predecessors.size() == 2 && join.predecessors.contains(0) && join.predecessors.contains(1),
            "join must have both fork paths as predecessors: " + join.predecessors);
        check(join.successors.isEmpty(), "end terminates the join");
        for(int b = 0; b < 3; b++) check(cfg.block(b).reachable, "every block of the if shape is reachable");
        check(cfg.blockAt(1) == 1 && cfg.blockAt(2) == 2 && cfg.blockAt(3) == 2,
            "blockAt mapping wrong for the if shape");
        check(cfg.loops().isEmpty(), "an if must not report loops");
    }

    /**
     * 0: jump 3 lessThanEq x 0   (guard: exit when x <= 0)
     * 1: set x sub x 1           (body)
     * 2: jump 0 always           (trailing always jump back to the header)
     * 3: print x
     * 4: end
     * Blocks [0,1) [1,3) [3,5); the always jump closes exactly one natural loop.
     */
    private static void whileBackEdgeAndNaturalLoop(){
        MlogCFG cfg = build("jump 3 lessThanEq x 0\nset x sub x 1\njump 0 always\nprint x\nend\n");
        check(cfg.blockCount() == 3, "while shape expected 3 blocks, got " + cfg.blockCount());
        check(cfg.block(1).successors.size() == 1 && cfg.block(1).successors.contains(0),
            "trailing always jump must be the body's only successor edge: " + cfg.block(1).successors);
        check(cfg.block(0).successors.size() == 2 && cfg.block(0).successors.contains(1)
            && cfg.block(0).successors.contains(2), "guard must fork into body + exit");
        List<MlogCFG.Loop> loops = cfg.loops();
        check(loops.size() == 1, "while shape expected exactly one back-edge loop, got " + loops.size());
        MlogCFG.Loop loop = loops.get(0);
        check(loop.header == 0, "loop header must be the guard block");
        check(loop.bodyBlocks.size() == 2 && loop.bodyBlocks.contains(0) && loop.bodyBlocks.contains(1),
            "natural loop body must be {header, body}: " + loop.bodyBlocks);
        check(!loop.bodyBlocks.contains(2), "exit block must stay outside the loop body");
    }

    /**
     * 0: jump 2 equal c 1    (entry forks: the taken edge enters the cycle at block 2)
     * 1: jump 3 always       (second entry: jumps into the cycle at block 3)
     * 2: set a 1             (cycle node X, falls through to block 3)
     * 3: jump 2 always       (cycle node Y, jumps back to block 2)
     * 4: end                 (unreachable tail)
     * The 2 <-> 3 cycle has two entries (0 -> 2 and 1 -> 3), so neither node dominates the
     * other and neither cycle edge is a back edge. Per the natural-loop definition this
     * shape is deliberately not reported as a loop — this test pins that defined behavior.
     */
    private static void multiEntryCycleIsNotANaturalLoop(){
        MlogCFG cfg = build("jump 2 equal c 1\njump 3 always\nset a 1\njump 2 always\nend\n");
        check(cfg.blockCount() == 5, "multi-entry shape expected 5 blocks, got " + cfg.blockCount());
        check(cfg.block(2).successors.contains(3) && cfg.block(3).successors.contains(2),
            "the cycle edges must exist in the graph");
        check(cfg.block(2).predecessors.contains(0) && cfg.block(3).predecessors.contains(1),
            "both entries must reach the cycle directly");
        check(!cfg.dominates(2, 3) && !cfg.dominates(3, 2),
            "neither cycle node may dominate the other under two entries");
        check(cfg.loops().isEmpty(),
            "a cycle with two entries has no back edge and must not be reported as a loop");
        check(!cfg.block(4).reachable, "the infinite cycle leaves trailing code unreachable");
    }

    /**
     * 0: set x 1
     * 1: end
     * 2: set y 2
     * 3: print y
     * The end at 1 cuts the program: block 1 ([2, 4)) has no incoming edge.
     */
    private static void codeAfterEndIsUnreachable(){
        MlogCFG cfg = build("set x 1\nend\nset y 2\nprint y\n");
        check(cfg.blockCount() == 2, "expected one block before end and one after, got " + cfg.blockCount());
        check(cfg.block(0).reachable && cfg.block(0).successors.isEmpty(),
            "end must leave the first block without successors");
        check(!cfg.block(1).reachable, "instructions after end must be unreachable");
        check(cfg.block(1).from == 2 && cfg.block(1).to == 4, "post-end block should span [2, 4)");
        check(cfg.blockAt(2) == 1 && cfg.blockAt(3) == 1, "post-end instructions map to the second block");
        check(!cfg.dominates(0, 1), "dominance over an unreachable block must report false");
        check(!cfg.dominates(1, 1), "an unreachable block does not dominate itself");
        check(cfg.loops().isEmpty(), "unreachable code must not contribute loops");
    }

    /** Entry dominates every reachable block; a loop header dominates its body, not vice
     *  versa; neither branch of an if dominates the join. */
    private static void dominanceInvariants(){
        MlogCFG loop = build("jump 3 lessThanEq x 0\nset x sub x 1\njump 0 always\nprint x\nend\n");
        check(loop.dominates(0, 0), "entry must dominate itself");
        check(loop.dominates(0, 1) && loop.dominates(0, 2), "entry must dominate all reachable blocks");
        check(loop.dominates(1, 1), "every reachable block dominates itself");
        check(!loop.dominates(1, 0) && !loop.dominates(2, 0), "body/exit must not dominate the entry");
        check(!loop.dominates(1, 2) && !loop.dominates(2, 1),
            "only the header may dominate inside the loop shape");

        MlogCFG branch = build("jump 2 notEqual x 1\nset y 1\nprint y\nend\n");
        check(branch.dominates(0, 2), "entry dominates the join");
        check(!branch.dominates(1, 2), "the branch body does not dominate the join");
        check(!branch.dominates(2, 1), "the join does not dominate the branch body");

        check(!loop.dominates(-1, 0) && !loop.dominates(0, 99), "out-of-range dominance queries return false");
    }

    private static void writesCounterClassification(){
        check(MlogCFG.writesCounter(tokenizeLine("set @counter 3")), "set @counter must count as a counter write");
        check(MlogCFG.writesCounter(tokenizeLine("op add @counter @counter x")),
            "op with an @counter destination must count");
        check(MlogCFG.writesCounter(tokenizeLine("read @counter cell1 0")),
            "reading into @counter must count as a counter write");
        check(MlogCFG.writesCounter(tokenizeLine("set @counter \"9\"")),
            "a quoted value operand does not stop the write");
        check(!MlogCFG.writesCounter(tokenizeLine("set x 1")), "ordinary set is not a counter write");
        check(!MlogCFG.writesCounter(tokenizeLine("print @counter")), "reading @counter is not a write");
        check(!MlogCFG.writesCounter(tokenizeLine("op add x @counter 1")),
            "@counter as an op operand is a read, not a write");
    }

    private static void readsWritesExtraction(){
        check(MlogCFG.reads(tokenizeLine("set x y")).equals(setOf("y")), "set must read token 2");
        check(MlogCFG.writes(tokenizeLine("set x y")).equals(setOf("x")), "set must write token 1");
        check(MlogCFG.reads(tokenizeLine("op add d a b")).equals(setOf("a", "b")), "op must read tokens 3 and 4");
        check(MlogCFG.writes(tokenizeLine("op add d a b")).equals(setOf("d")), "op must write token 2");
        check(MlogCFG.reads(tokenizeLine("sensor t block1 @type")).equals(setOf("block1")),
            "sensor must read token 2 only");
        check(MlogCFG.writes(tokenizeLine("sensor t block1 @type")).equals(setOf("t")), "sensor must write token 1");
        check(MlogCFG.reads(tokenizeLine("jump 5 equal x y")).equals(setOf("x", "y")), "jump must read tokens 3 and 4");
        check(MlogCFG.writes(tokenizeLine("jump 5 equal x y")).isEmpty(), "jump writes nothing");
        check(MlogCFG.reads(tokenizeLine("jump 0 always x false")).equals(setOf("x", "false")),
            "always-jump dummy operands are reported as reads (conservative over-approximation)");
        check(MlogCFG.reads(tokenizeLine("print x \"hi\" @counter")).equals(setOf("x", "\"hi\"", "@counter")),
            "print reads every operand verbatim, @ and quoted tokens included");
        check(MlogCFG.writes(tokenizeLine("print x")).isEmpty(), "print writes nothing");
        check(MlogCFG.reads(tokenizeLine("draw rect x y w h")).equals(setOf("rect", "x", "y", "w", "h")),
            "draw reads every operand");
        check(MlogCFG.reads(tokenizeLine("read result cell1 0")).equals(setOf("result", "cell1", "0")),
            "read over-approximates: every operand is reported as a read");
        check(MlogCFG.writes(tokenizeLine("read result cell1 0")).equals(setOf("result")),
            "read writes its destination");
        check(MlogCFG.writes(tokenizeLine("write 1 cell1 0")).equals(setOf("cell1")),
            "write mutates its cell target (token 2)");
        check(MlogCFG.reads(tokenizeLine("write 1 cell1 0")).equals(setOf("1", "cell1", "0")),
            "write conservatively reads every operand");
        check(MlogCFG.writes(tokenizeLine("control enabled turret1 1 0 0")).equals(setOf("turret1")),
            "control mutates its target block (token 2)");
        check(MlogCFG.reads(tokenizeLine("control enabled turret1 1 0 0")).equals(setOf("enabled", "turret1", "1", "0")),
            "control conservatively reads every operand");
        check(MlogCFG.reads(tokenizeLine("frobnicate a b")).isEmpty()
            && MlogCFG.writes(tokenizeLine("frobnicate a b")).isEmpty(), "unknown kinds extract nothing");
        check(MlogCFG.reads(tokenizeLine("end")).isEmpty() && MlogCFG.writes(tokenizeLine("end")).isEmpty(),
            "end extracts nothing");
    }

    /** Out-of-range targets produce no edge; an always jump never falls through, so the block
     *  after it is unreachable unless another path reaches it. */
    private static void outOfRangeJumpsAreHarmless(){
        MlogCFG always = build("jump 99 always\nset x 1\nend\n");
        check(always.blockCount() == 2, "jump + rest expected 2 blocks, got " + always.blockCount());
        check(always.block(0).successors.isEmpty(), "out-of-range always jump must produce no edge");
        check(!always.block(1).reachable, "nothing is reachable after a taken always jump with no valid target");
        check(always.loops().isEmpty(), "out-of-range jumps must not create loops");
        check(always.blockAt(99) == -1, "blockAt outside the program must return -1");
        check(!always.dominates(0, 99), "dominates outside the program must return false");

        MlogCFG conditional = build("jump -1 equal x y\nset x 1\nend\n");
        check(conditional.blockCount() == 2, "conditional + rest expected 2 blocks");
        check(conditional.block(0).successors.size() == 1 && conditional.block(0).successors.contains(1),
            "conditional jump with an invalid target keeps only its fallthrough edge");
        check(conditional.block(1).reachable, "the fallthrough path stays reachable");

        MlogCFG overflow = build("jump 99999999999999 always\nend\n");
        check(overflow.blockCount() == 2 && overflow.block(0).successors.isEmpty(),
            "an integer-overflow target must be treated as out of range, not crash");
    }

    private static void emptyProgramHasNoBlocks(){
        MlogCFG empty = build("");
        check(empty.blockCount() == 0, "empty program must have no blocks");
        check(empty.blockAt(0) == -1, "empty program has no instruction mapping");
        check(empty.loops().isEmpty(), "empty program has no loops");
        check(!empty.dominates(0, 0), "empty program has no dominance");
        check(MlogCFG.build(null).blockCount() == 0, "null input is read as an empty program");
    }

    // ===== Helpers =========================================================================

    /** Tokenizes mlog text into statements the same way SugarDecompiler.Program does. */
    private static MlogCFG build(String code){
        List<String[]> statements = new ArrayList<>();
        for(String line : code.split("\n")){
            String[] tokens = tokenizeLine(line);
            if(tokens.length > 0) statements.add(tokens);
        }
        return MlogCFG.build(statements);
    }

    /** Mirrors SugarDecompiler.Program.tokenize: strings run raw to the closing quote and
     *  '#' starts a comment outside strings. */
    private static String[] tokenizeLine(String line){
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

    private static Set<String> setOf(String... values){
        return new HashSet<>(List.of(values));
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
