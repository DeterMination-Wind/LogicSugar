package mindustry.logic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Static control-flow graph (CFG) over tokenized vanilla mlog: a pure-Java IR for the
 * decompiler with no Mindustry dependency, safe on every class loader.
 *
 * <p><b>Input model.</b> {@link #build(List)} receives the already-tokenized statements of a
 * normalized mlog program; the list index of a statement is its instruction number, matching
 * {@code SugarDecompiler.Program.statements}. {@code tokens[0]} is the instruction kind. A
 * jump has the shape {@code ["jump", <numeric target>, <condition op>, <a>, <b>]}; an
 * unconditional jump carries {@code "always"} at {@code token(2)} and may shorten its operand
 * list (e.g. {@code jump 4 always}). {@code "end"} terminates the program. Label lines never
 * appear: every jump target is already a numeric instruction index.</p>
 *
 * <p><b>Blocks.</b> Classic leader rules: instruction 0, every in-range jump target, and every
 * instruction that follows a jump or an {@code end}. A block covers the instruction interval
 * {@code [from, to)} with {@code to} exclusive. A jump or an {@code end} is always the last
 * instruction of its block by construction. {@link #blockAt} maps an instruction number to its
 * block, or {@code -1} outside the program.</p>
 *
 * <p><b>Edges.</b> A conditional jump adds its taken edge to the target block plus a
 * fallthrough edge to the next block (the deduplicated successor list puts the taken edge
 * first). An always jump adds only its target edge — it never falls through. An {@code end}
 * adds no edge, and the final block gets no implicit exit edge. A jump whose target is out of
 * range ({@code < 0}, {@code >= size}, or not an integer literal) is treated as having no
 * target: the target edge is dropped and a conditional jump keeps only its fallthrough edge.
 * A jump without the {@code "always"} marker is treated as conditional even when its operands
 * are missing — the conservative direction never drops a possible edge.</p>
 *
 * <p><b>Reachability, dominance, loops.</b> {@code reachable} is computed from the entry
 * (block 0) over the successor graph. Dominance uses the standard iterative data-flow
 * algorithm over reachable blocks; the entry dominates itself and every reachable block.
 * Dominance is undefined for unreachable blocks, so {@link #dominates} returns {@code false}
 * when either argument is unreachable or out of range. A back edge is an edge {@code b -> h}
 * where {@code h} dominates {@code b}; each back edge yields one {@link Loop} whose body is
 * the standard natural loop (the header plus every reachable block that reaches the back-edge
 * source without passing through the header), walked over reachable predecessors only.
 * Multiple back edges into one header are reported as separate loops. By definition, a cycle
 * with a second entry that skips its would-be header is not reported as a loop.</p>
 *
 * <p><b>Failure direction.</b> Edges are never invented and nothing crashes on malformed
 * input: truncated statements yield fewer extracted operands, unknown kinds contribute no
 * reads or writes, and reads over-approximate for the operand-driven kinds documented on
 * {@link #reads(String[])}. All returned collections are unmodifiable; iteration orders are
 * deterministic.</p>
 */
public final class MlogCFG{
    private final List<Block> blocks;
    private final int[] blockOfInstruction;
    private final BitSet reachableBlocks;
    private final BitSet[] dominatorSets;
    private final List<Loop> loopList;

    private MlogCFG(List<Block> blocks, int[] blockOfInstruction, BitSet reachableBlocks,
                    BitSet[] dominatorSets, List<Loop> loopList){
        this.blocks = List.copyOf(blocks);
        this.blockOfInstruction = blockOfInstruction;
        this.reachableBlocks = reachableBlocks;
        this.dominatorSets = dominatorSets;
        this.loopList = Collections.unmodifiableList(loopList);
    }

    /**
     * Builds the static CFG for one tokenized mlog program. The build never throws on
     * malformed statements; see the class Javadoc for the exact conservative behavior.
     *
     * @param statements tokenized statements, list index == instruction number; {@code null}
     *        is read as an empty program
     */
    public static MlogCFG build(List<String[]> statements){
        int size = statements == null ? 0 : statements.size();

        // Phase 1: leaders — the entry, in-range jump targets, and instructions after a jump
        // or an end. A jump/end is therefore always the last instruction of its block.
        boolean[] leader = new boolean[size];
        if(size > 0) leader[0] = true;
        for(int i = 0; i < size; i++){
            String[] tokens = asTokens(statements.get(i));
            if(isJump(tokens)){
                int target = jumpTarget(tokens);
                if(target >= 0 && target < size) leader[target] = true;
            }
            if(terminates(tokens) && i + 1 < size) leader[i + 1] = true;
        }

        // Phase 2: block intervals plus the instruction -> block map.
        List<Integer> starts = new ArrayList<>();
        for(int i = 0; i < size; i++) if(leader[i]) starts.add(i);
        int count = starts.size();
        int[] from = new int[count];
        int[] to = new int[count];
        int[] blockOfInstruction = new int[size];
        for(int b = 0; b < count; b++){
            from[b] = starts.get(b);
            to[b] = b + 1 < count ? starts.get(b + 1) : size;
            for(int i = from[b]; i < to[b]; i++) blockOfInstruction[i] = b;
        }

        // Phase 3: static edges (class Javadoc "Edges").
        List<Set<Integer>> successors = emptyBlockSets(count);
        List<Set<Integer>> predecessors = emptyBlockSets(count);
        for(int b = 0; b < count; b++){
            String[] last = asTokens(statements.get(to[b] - 1));
            if(isJump(last)){
                int target = jumpTarget(last);
                if(target >= 0 && target < size) link(successors, predecessors, b, blockOfInstruction[target]);
                if(!isAlwaysJump(last) && b + 1 < count) link(successors, predecessors, b, b + 1);
            }else if(!isEnd(last) && b + 1 < count){
                link(successors, predecessors, b, b + 1);
            }
        }

        // Phase 4: reachability from the entry (block 0).
        BitSet reachable = new BitSet(count);
        if(count > 0){
            Deque<Integer> pending = new ArrayDeque<>();
            reachable.set(0);
            pending.push(0);
            while(!pending.isEmpty()){
                int b = pending.pop();
                for(int next : successors.get(b)){
                    if(!reachable.get(next)){
                        reachable.set(next);
                        pending.push(next);
                    }
                }
            }
        }

        // Phase 5: dominators, iterative data flow over reachable blocks. The entry starts as
        // {entry}, other reachable blocks start optimistic and shrink to the fixed point;
        // unreachable blocks keep an empty set (dominance is undefined there).
        BitSet[] dominators = new BitSet[count];
        for(int b = 0; b < count; b++){
            dominators[b] = new BitSet(count);
            if(b == 0) dominators[b].set(0);
            else if(reachable.get(b)) dominators[b].set(0, count);
        }
        boolean changed = true;
        while(changed){
            changed = false;
            for(int b = 1; b < count; b++){
                if(!reachable.get(b)) continue;
                BitSet merged = null;
                for(int p : predecessors.get(b)){
                    if(!reachable.get(p)) continue;
                    if(merged == null) merged = (BitSet)dominators[p].clone();
                    else merged.and(dominators[p]);
                }
                if(merged == null) merged = new BitSet(count);
                merged.set(b);
                if(!merged.equals(dominators[b])){
                    dominators[b] = merged;
                    changed = true;
                }
            }
        }

        // Phase 6: back edges (b -> h with h dominating b) and their natural loops.
        List<Loop> loops = new ArrayList<>();
        for(int b = 0; b < count; b++){
            if(!reachable.get(b)) continue;
            for(int h : successors.get(b)){
                if(!dominates(dominators, reachable, h, b)) continue;
                Set<Integer> body = new LinkedHashSet<>();
                body.add(h);
                if(b != h){
                    Deque<Integer> pending = new ArrayDeque<>();
                    pending.push(b);
                    while(!pending.isEmpty()){
                        int m = pending.pop();
                        if(!body.add(m)) continue;
                        for(int p : predecessors.get(m)){
                            if(reachable.get(p)) pending.push(p);
                        }
                    }
                }
                loops.add(new Loop(h, body));
            }
        }

        List<Block> blockList = new ArrayList<>(count);
        for(int b = 0; b < count; b++){
            blockList.add(new Block(from[b], to[b],
                List.copyOf(successors.get(b)), List.copyOf(predecessors.get(b)),
                reachable.get(b)));
        }
        return new MlogCFG(blockList, blockOfInstruction, reachable, dominators, loops);
    }

    /** One basic block: the instruction interval {@code [from, to)} plus its graph role. */
    public static final class Block{
        /** First instruction of the block, inclusive. */
        public final int from;
        /** Instruction after the block's last instruction, exclusive. */
        public final int to;
        /** Successor block numbers, deduplicated; a conditional jump lists its taken edge
         *  before the fallthrough edge. Unmodifiable. */
        public final List<Integer> successors;
        /** Predecessor block numbers, deduplicated, in edge-creation order. Unmodifiable. */
        public final List<Integer> predecessors;
        /** Whether the block is reachable from the entry (block 0). */
        public final boolean reachable;

        Block(int from, int to, List<Integer> successors, List<Integer> predecessors,
              boolean reachable){
            this.from = from;
            this.to = to;
            this.successors = successors;
            this.predecessors = predecessors;
            this.reachable = reachable;
        }
    }

    /**
     * One natural loop: the body of one back edge {@code b -> header}. Reported per back
     * edge, so two back edges into the same header produce two loops sharing the header.
     */
    public static final class Loop{
        /** The loop header: the back edge target, which dominates every body block. */
        public final int header;
        /**
         * Header plus every reachable block that can reach the back-edge source without
         * passing through the header (standard natural loop). Iteration order is the
         * discovery order of the predecessor walk starting at the header — deterministic,
         * but treat it as a set. Unmodifiable.
         */
        public final Set<Integer> bodyBlocks;

        Loop(int header, Set<Integer> bodyBlocks){
            this.header = header;
            this.bodyBlocks = Collections.unmodifiableSet(bodyBlocks);
        }
    }

    /** Number of basic blocks; {@code 0} for an empty program. */
    public int blockCount(){
        return blocks.size();
    }

    /** Block with the given number, in construction order (ascending {@code from}). */
    public Block block(int index){
        return blocks.get(index);
    }

    /** All blocks, unmodifiable, in construction order (ascending {@code from}). */
    public List<Block> blocks(){
        return blocks;
    }

    /**
     * Block containing the given instruction, or {@code -1} when the instruction number is
     * outside the program.
     */
    public int blockAt(int instruction){
        if(instruction < 0 || instruction >= blockOfInstruction.length) return -1;
        return blockOfInstruction[instruction];
    }

    /**
     * Whether block {@code a} dominates block {@code b}. Every reachable block dominates
     * itself; the entry dominates every reachable block. Dominance is undefined for
     * unreachable blocks, so this returns {@code false} when either argument is unreachable
     * or out of range.
     */
    public boolean dominates(int a, int b){
        return dominates(dominatorSets, reachableBlocks, a, b);
    }

    /**
     * Natural loops, one per back edge. Ordered deterministically by ascending back-edge
     * source block, then by successor order within a block. Unmodifiable.
     */
    public List<Loop> loops(){
        return loopList;
    }

    /**
     * Whether the statement writes the {@code @counter} variable, i.e. whether any write
     * position documented on {@link #writes(String[])} holds the literal token
     * {@code "@counter"}. Covers {@code set @counter ...}, {@code op ... @counter ...} with a
     * counter destination, {@code read @counter ...}, and every other documented write
     * position. The comparison is exact token equality; quoted spellings are not resolved,
     * and statements of unknown kind report no writes.
     */
    public static boolean writesCounter(String[] statement){
        return writes(statement).contains("@counter");
    }

    /**
     * Variables read by one tokenized statement, extracted by instruction kind (token 0):
     *
     * <ul>
     *   <li>{@code set}: token 2 (the value).</li>
     *   <li>{@code op}: tokens 3 and 4 (the operands).</li>
     *   <li>{@code sensor}: token 2 (the sensed block; the property token is not a read).</li>
     *   <li>{@code jump}: tokens 3 and 4 (the compared operands; an always jump's dummy
     *       operands are included on purpose — reads over-approximate).</li>
     *   <li>{@code print}, {@code read}, {@code write}, {@code control}, {@code draw}: every
     *       operand token (1..n), a deliberate over-approximation for these side-effect
     *       kinds.</li>
     *   <li>Any other kind (including {@code end}, truncated and unknown statements): empty.</li>
     * </ul>
     *
     * <p>Tokens are returned verbatim: {@code @}-prefixed and quoted tokens appear exactly as
     * written, filtering is the caller's job. Documented positions are taken only when
     * present. Order follows token order, duplicates removed; the set is unmodifiable.</p>
     */
    public static Set<String> reads(String[] statement){
        return operands(statement, true);
    }

    /**
     * Variables written by one tokenized statement, extracted by instruction kind (token 0):
     *
     * <ul>
     *   <li>{@code set}: token 1.</li>
     *   <li>{@code op}: token 2 (the destination).</li>
     *   <li>{@code sensor}: token 1 (the result).</li>
     *   <li>{@code read}: token 1 (the destination) — a genuine write, reported even though
     *       {@link #reads(String[])} also lists this token for the conservative kinds.</li>
     *   <li>{@code write}: token 2 (the memory cell being mutated).</li>
     *   <li>{@code control}: token 2 (the controlled block being mutated).</li>
     *   <li>Any other kind (including {@code jump}, {@code print}, {@code draw}, truncated
     *       and unknown statements): empty.</li>
     * </ul>
     *
     * <p>Tokens are returned verbatim; documented positions are taken only when present.
     * Order follows token order, duplicates removed; the set is unmodifiable.</p>
     */
    public static Set<String> writes(String[] statement){
        return operands(statement, false);
    }

    // ===== Extraction ======================================================================

    private static Set<String> operands(String[] raw, boolean read){
        String[] tokens = asTokens(raw);
        String kind = kind(tokens);
        Set<String> result = new LinkedHashSet<>();
        if(read){
            if(kind.equals("set") || kind.equals("sensor")) collect(tokens, result, 2);
            else if(kind.equals("op") || kind.equals("jump")) collect(tokens, result, 3, 4);
            else if(kind.equals("print") || kind.equals("read") || kind.equals("write")
                || kind.equals("control") || kind.equals("draw")) collectAll(tokens, result);
        }else{
            if(kind.equals("set") || kind.equals("sensor") || kind.equals("read")) collect(tokens, result, 1);
            else if(kind.equals("op")) collect(tokens, result, 2);
            else if(kind.equals("write") || kind.equals("control")) collect(tokens, result, 2);
        }
        return result;
    }

    /** Adds the token at each given index when present. */
    private static void collect(String[] tokens, Set<String> into, int... indices){
        for(int index : indices){
            if(index >= 0 && index < tokens.length) into.add(tokens[index]);
        }
    }

    /** Adds every operand token from index 1 to the end. */
    private static void collectAll(String[] tokens, Set<String> into){
        for(int i = 1; i < tokens.length; i++) into.add(tokens[i]);
    }

    // ===== Graph helpers ===================================================================

    private static List<Set<Integer>> emptyBlockSets(int count){
        List<Set<Integer>> result = new ArrayList<>(count);
        for(int i = 0; i < count; i++) result.add(new LinkedHashSet<>());
        return result;
    }

    private static void link(List<Set<Integer>> successors, List<Set<Integer>> predecessors,
                             int from, int to){
        if(successors.get(from).add(to)) predecessors.get(to).add(from);
    }

    private static boolean dominates(BitSet[] dominators, BitSet reachable, int a, int b){
        if(a < 0 || a >= dominators.length || b < 0 || b >= dominators.length) return false;
        if(!reachable.get(a) || !reachable.get(b)) return false;
        return a == b || dominators[b].get(a);
    }

    // ===== Token model =====================================================================

    private static String[] asTokens(String[] tokens){
        return tokens == null ? new String[0] : tokens;
    }

    private static String kind(String[] tokens){
        return tokens.length == 0 ? "" : tokens[0];
    }

    private static boolean isJump(String[] tokens){
        return kind(tokens).equals("jump") && tokens.length >= 2;
    }

    private static boolean isAlwaysJump(String[] tokens){
        return isJump(tokens) && tokens.length >= 3 && tokens[2].equals("always");
    }

    private static boolean isEnd(String[] tokens){
        return kind(tokens).equals("end");
    }

    /** Whether the statement closes its block: any jump, or {@code end}. */
    private static boolean terminates(String[] tokens){
        return isJump(tokens) || isEnd(tokens);
    }

    /**
     * Numeric jump target, or {@code -1} when token 1 is missing, not an integer literal, or
     * does not fit an {@code int} — all treated as "no target" by the edge rules.
     */
    private static int jumpTarget(String[] tokens){
        if(tokens.length < 2) return -1;
        String raw = tokens[1];
        if(raw.isEmpty()) return -1;
        int p = raw.charAt(0) == '-' || raw.charAt(0) == '+' ? 1 : 0;
        if(p == raw.length()) return -1;
        for(int i = p; i < raw.length(); i++) if(!Character.isDigit(raw.charAt(i))) return -1;
        try{
            return Integer.parseInt(raw);
        }catch(NumberFormatException ignored){
            return -1;
        }
    }
}
