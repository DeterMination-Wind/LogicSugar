package logicsugar.assist.data;

import logicsugar.LogicSugarMod;
import mindustry.Vars;
import mindustry.logic.GlobalVars;
import mindustry.logic.LAssembler;
import mindustry.logic.LExecutor;
import mindustry.logic.LPrintable;
import mindustry.logic.LReadable;
import mindustry.logic.LVar;
import mindustry.logic.LWritable;
import mindustry.logic.SugarCompiler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whole-program runtime self-test for the data structures, executed on the real game
 * {@link LExecutor} with fake cell/message blocks.
 *
 * <p>Why this file exists: {@code mapTest}/{@code setTest} already run their products on a real
 * executor, but the containers, bitset, list, heap and chain were only verified as isolated
 * instruction chains plus a hand-written op/read/write simulator inside each {@code *Test}.
 * A shared misconception between that simulator and the lowering would stay invisible. Here the
 * program is compiled, assembled by {@code LAssembler}, run instruction by instruction by the
 * game's own executor, and the resulting memory/variables are asserted — including the vanilla
 * {@code @counter} subroutine trampoline that the injected push/insert/alloc builtins use, and
 * the "drain a container with a while loop" shape that motivated this test.</p>
 *
 * <p>Semantics pinned here (all "vanilla {@code MemoryBlock} + LVar" behaviour): push on a full
 * container writes nothing; pop/peek/get on an empty (or out-of-range) container yields NaN;
 * hidden state variables start at 0 so a declaration card needs no instructions.</p>
 */
public final class DataRuntimeTest{
    private DataRuntimeTest(){}

    public static void main(String[] args){
        LogicSugarMod.registerStatements();
        Vars.logicVars = new GlobalVars();

        drainLoopSemantics();
        valuePreservation();
        stackRuntime();
        queueRuntime();
        dequeRuntime();
        bitsetRuntime();
        listRuntime();
        heapRuntime();
        sortRuntime();
        arrayBulkRuntime();
        chainRuntime();

        System.out.println("LogicSugar data runtime self-test passed.");
    }

    // ===== the reported shape: drain a container inside a while loop =====

    /**
     * The condition of {@code whilebegin} is the <b>loop</b> condition ("repeat while true"),
     * never an exit condition. {@code s.size() > 0} therefore drains the stack into
     * {@code acc} (321 = 3,2,1) and {@code !s.size()} leaves the stack untouched: exactly the
     * trap the Chinese {@code 结束条件} label used to set.
     */
    private static void drainLoopSemantics(){
        String base = "stack s cell1 0 8\n"
            + "datacall spush result \"s, 1\"\n"
            + "datacall spush result \"s, 2\"\n"
            + "datacall spush result \"s, 3\"\n";

        // 0 decl / 1-3 push / 4 set acc / 5 while / 6 peek / 7 pop / 8 mul / 9 add / 10 print / 11 printflush / 12 blockend
        String loopBody = "datacall speek tmp \"s\"\n"
            + "datacall spop result \"s\"\n"
            + "op mul acc acc 10\n"
            + "op add acc acc tmp\n"
            + "print tmp\n"
            + "printflush message1\n";
        String guard = "whilebegin expr \"%s\" 12\n" + loopBody + "blockend\n";

        Run drained = run(base + "set acc 0\n" + String.format(guard, "s.size() > 0"));
        checkNum(drained, "__ls_stk_s_top", 0, "stack must be drained");
        checkNum(drained, "acc", 321, "drained values must come out top-first (3,2,1)");
        checkMem(drained, "cell1", 0, 1, "popping must not erase the backing cells");

        // Same program with the inverted condition users wrote after reading "结束条件":
        // the seed must survive untouched, i.e. the body never runs.
        Run inverted = run(base + "set acc 7\n" + String.format(guard, "!s.size()"));
        checkNum(inverted, "__ls_stk_s_top", 3, "an inverted condition must never enter the body");
        checkNum(inverted, "acc", 7, "an inverted condition must leave the accumulator untouched");

        // print order: same drain without printflush so the text buffer survives the run
        // (0 decl / 1-3 push / 4 while / 5 pop / 6 print / 7 blockend).
        Run printed = run(base + "whilebegin expr \"s.size()\" 7\n"
            + "datacall spop result \"s\"\n"
            + "print result\n"
            + "blockend\n");
        check("321".contentEquals(printed.executor.textBuffer),
            "print must emit the popped values top-first, got '" + printed.executor.textBuffer + "'");
    }

    // ===== containers =====

    private static void stackRuntime(){
        // non-zero base: the empty-read address trick must stay inside the block and fall to -1.
        Run empty = run("stack s cell1 20 4\n"
            + "datacall spop r1 \"s\"\n"
            + "datacall ssize r2 \"s\"\n"
            + "datacall spush r3 \"s, 10\"\n"
            + "datacall spush r3 \"s, 11\"\n"
            + "datacall speek r4 \"s\"\n"
            + "datacall spop r5 \"s\"\n"
            + "datacall spop r6 \"s\"\n"
            + "datacall spop r7 \"s\"\n"
            + "datacall ssize r8 \"s\"\n");
        checkNaN(empty, "r1", "pop on an empty stack");
        checkNum(empty, "r2", 0, "empty stack size");
        checkNum(empty, "r3", 2, "push result is the new element count");
        checkNum(empty, "r4", 11, "peek returns the top element");
        checkNum(empty, "r5", 11, "pop returns the top element");
        checkNum(empty, "r6", 10, "pop returns the next element");
        checkNaN(empty, "r7", "pop on a drained stack");
        checkNum(empty, "r8", 0, "drained stack size");
        checkMem(empty, "cell1", 20, 10, "stack base must be honoured");
        checkMem(empty, "cell1", 21, 11, "stack grows upwards from base");
        checkMem(empty, "cell1", 19, 0, "stack must not write below base");
        checkMem(empty, "cell1", 22, 0, "stack must not write past its contents");

        Run full = run("stack s cell1 0 4\n"
            + "datacall spush r0 \"s, 1\"\n"
            + "datacall spush r0 \"s, 2\"\n"
            + "datacall spush r0 \"s, 3\"\n"
            + "datacall spush r0 \"s, 4\"\n"
            + "datacall spush r0 \"s, 5\"\n"
            + "datacall ssize r1 \"s\"\n");
        checkNum(full, "r0", -1, "push on a full stack reports -1");
        checkNum(full, "r1", 4, "full stack must not grow");
        checkMem(full, "cell1", 3, 4, "full stack keeps its last stored element");
        checkMem(full, "cell1", 4, 0, "push on a full stack must not write past the end");
    }

    private static void queueRuntime(){
        Run result = run("queue q cell1 30 3\n"
            + "datacall qpush r0 \"q, 1\"\n"
            + "datacall qpush r0 \"q, 2\"\n"
            + "datacall qpush r0 \"q, 3\"\n"
            + "datacall qpush r0 \"q, 4\"\n"
            + "datacall qpop r1 \"q\"\n"
            + "datacall qpush r0 \"q, 4\"\n"
            + "datacall qpop r2 \"q\"\n"
            + "datacall qpop r3 \"q\"\n"
            + "datacall qpop r4 \"q\"\n"
            + "datacall qpop r5 \"q\"\n"
            + "datacall qsize r6 \"q\"\n");
        checkNum(result, "r0", 3, "re-push after a pop returns the new count");
        checkNum(result, "r1", 1, "queue is FIFO");
        checkNum(result, "r2", 2, "queue is FIFO after a wraparound push");
        checkNum(result, "r3", 3, "queue keeps FIFO order across the ring");
        checkNum(result, "r4", 4, "re-pushed value comes out last");
        checkNaN(result, "r5", "pop on an empty queue");
        checkNum(result, "r6", 0, "drained queue size");
        checkMem(result, "cell1", 30, 4, "ring slot reused after wraparound");
        checkMem(result, "cell1", 31, 2, "ring slot keeps its value until overwritten");
        checkMem(result, "cell1", 32, 3, "ring slot keeps its value until overwritten");

        Run empty = run("queue q cell1 0 3\n"
            + "datacall qpeek r0 \"q\"\n"
            + "datacall qpop r1 \"q\"\n"
            + "datacall qsize r2 \"q\"\n");
        checkNaN(empty, "r0", "peek on an empty queue");
        checkNaN(empty, "r1", "pop on an empty queue");
        checkNum(empty, "r2", 0, "empty queue size");
    }

    private static void dequeRuntime(){
        Run result = run("deque d cell1 40 3\n"
            + "datacall dpushb r0 \"d, 2\"\n"
            + "datacall dpushf r0 \"d, 1\"\n"
            + "datacall dpushb r0 \"d, 3\"\n"
            + "datacall dpeekf r1 \"d\"\n"
            + "datacall dpeekb r2 \"d\"\n"
            + "datacall dpopf r3 \"d\"\n"
            + "datacall dpopb r4 \"d\"\n"
            + "datacall dsize r5 \"d\"\n"
            + "datacall dpeekf r6 \"d\"\n"
            + "datacall dpopf r7 \"d\"\n"
            + "datacall dpopf r8 \"d\"\n");
        checkNum(result, "r0", 3, "deque size after three pushes");
        checkNum(result, "r1", 1, "front of the deque");
        checkNum(result, "r2", 3, "back of the deque");
        checkNum(result, "r3", 1, "pop front");
        checkNum(result, "r4", 3, "pop back");
        checkNum(result, "r5", 1, "deque size after two pops");
        checkNum(result, "r6", 2, "front after the surrounding elements are popped");
        checkNum(result, "r7", 2, "pop the last element");
        checkNaN(result, "r8", "pop on an empty deque");

        // front pushes walk the head downwards and must wrap without touching other slots.
        Run front = run("deque d cell1 0 4\n"
            + "datacall dpushf r0 \"d, 1\"\n"
            + "datacall dpushf r0 \"d, 2\"\n"
            + "datacall dpushf r0 \"d, 3\"\n"
            + "datacall dpushf r0 \"d, 4\"\n"
            + "datacall dpeekf r1 \"d\"\n"
            + "datacall dpeekb r2 \"d\"\n"
            + "datacall dsize r3 \"d\"\n"
            + "datacall dpushf r4 \"d, 5\"\n"
            + "datacall dsize r5 \"d\"\n");
        checkNum(front, "r0", 4, "four front pushes fill the deque");
        checkNum(front, "r1", 4, "the last front push becomes the front");
        checkNum(front, "r2", 1, "the first front push becomes the back");
        checkNum(front, "r3", 4, "full deque size");
        checkNum(front, "r4", -1, "push on a full deque reports -1");
        checkNum(front, "r5", 4, "push on a full deque must not grow");
        checkMem(front, "cell1", 0, 4, "wrapped front element");
        checkMem(front, "cell1", 1, 3, "second element");
        checkMem(front, "cell1", 2, 2, "third element");
        checkMem(front, "cell1", 3, 1, "last element");

        Run backFull = run("deque d cell1 0 2\n"
            + "datacall dpushb r0 \"d, 1\"\n"
            + "datacall dpushb r0 \"d, 2\"\n"
            + "datacall dpushb r0 \"d, 3\"\n"
            + "datacall dpeekf r1 \"d\"\n"
            + "datacall dpeekb r2 \"d\"\n");
        checkNum(backFull, "r1", 1, "full back-push keeps the front");
        checkNum(backFull, "r2", 2, "full back-push writes nothing");
    }

    private static void bitsetRuntime(){
        Run result = run("bitset b cell2 0 2\n"
            + "datacall bset z \"b, 0\"\n"
            + "datacall bset z \"b, 5\"\n"
            + "datacall bset z \"b, 127\"\n"
            + "datacall bset z \"b, 5\"\n"
            + "datacall btest r0 \"b, 0\"\n"
            + "datacall btest r1 \"b, 1\"\n"
            + "datacall btest r2 \"b, 127\"\n"
            + "datacall bcount r3 \"b\"\n"
            + "datacall bclr z \"b, 0\"\n"
            + "datacall bcount r4 \"b\"\n"
            + "datacall btest r5 \"b, 0\"\n"
            + "datacall btest r6 \"b, 128\"\n");
        checkNum(result, "r0", 1, "bit 0 set");
        checkNum(result, "r1", 0, "bit 1 clear");
        checkNum(result, "r2", 1, "last bit of the second word set");
        checkNum(result, "r3", 3, "double set counts once");
        checkNum(result, "r4", 2, "clear removes one bit");
        checkNum(result, "r5", 0, "cleared bit reads back 0");
        checkNum(result, "r6", 0, "out-of-range bit reads as clear");
        checkMem(result, "cell2", 0, 32, "word 0 keeps 2^5 after bit 0 was cleared");
        checkMem(result, "cell2", 1, -9.223372036854776E18, "word 1 holds the sign bit (2^63)");
    }

    private static void listRuntime(){
        Run result = run("list l cell1 0 4\n"
            + "datacall lappend z \"l, 1\"\n"
            + "datacall lappend z \"l, 2\"\n"
            + "datacall lappend z \"l, 3\"\n"
            + "datacall lsize r0 \"l\"\n"
            + "datacall lget r1 \"l, 0\"\n"
            + "datacall lset z \"l, 1, 9\"\n"
            + "datacall lget r2 \"l, 1\"\n"
            + "datacall linsert z \"l, 1, 5\"\n"
            + "datacall lget r3 \"l, 1\"\n"
            + "datacall lget r4 \"l, 2\"\n"
            + "datacall lsize r5 \"l\"\n"
            + "datacall lremove z \"l, 0\"\n"
            + "datacall lsize r6 \"l\"\n"
            + "datacall lget r7 \"l, 0\"\n"
            + "datacall lget r8 \"l, 99\"\n"
            + "datacall lget r9 \"l, -1\"\n"
            + "datacall lfind r10 \"l, 9\"\n"
            + "datacall lfind r11 \"l, 42\"\n");
        checkNum(result, "r0", 3, "list size after three appends");
        checkNum(result, "r1", 1, "first element");
        checkNum(result, "r2", 9, "lset overwrites");
        checkNum(result, "r3", 5, "linsert shifts to the right");
        checkNum(result, "r4", 9, "shifted element survives insertion");
        checkNum(result, "r5", 4, "list size after insert");
        checkNum(result, "r6", 3, "list size after remove");
        checkNum(result, "r7", 5, "remove shifts to the left");
        checkNaN(result, "r8", "lget past the end");
        checkNaN(result, "r9", "lget before the start");
        checkNum(result, "r10", 1, "lfind returns the first match");
        checkNum(result, "r11", -1, "lfind reports a miss as -1");

        Run full = run("list l cell1 0 2\n"
            + "datacall lappend z \"l, 1\"\n"
            + "datacall lappend z \"l, 2\"\n"
            + "datacall lappend r0 \"l, 3\"\n"
            + "datacall lsize r1 \"l\"\n"
            + "datacall linsert r2 \"l, 0, 4\"\n"
            + "datacall lget r3 \"l, 0\"\n");
        checkNum(full, "r0", -1, "append on a full list reports -1");
        checkNum(full, "r1", 2, "full list must not grow");
        checkNum(full, "r2", -1, "insert on a full list reports -1");
        checkNum(full, "r3", 1, "failed insert must not shift");

        Run misses = run("list l cell1 0 4\n"
            + "datacall lremove r0 \"l, 0\"\n"
            + "datacall lsize r1 \"l\"\n"
            + "datacall lappend z \"l, 1\"\n"
            + "datacall lremove r2 \"l, 1\"\n"
            + "datacall lsize r3 \"l\"\n"
            + "datacall lremove r4 \"l, -1\"\n");
        checkNaN(misses, "r0", "lremove on an empty list must return the NaN marker");
        checkNum(misses, "r1", 0, "failed remove must not change the count");
        checkNaN(misses, "r2", "lremove at count must return the NaN marker");
        checkNum(misses, "r3", 1, "failed remove must not shrink the list");
        checkNaN(misses, "r4", "lremove with a negative index must return the NaN marker");
    }

    private static void heapRuntime(){
        Run result = run("heap h cell2 0 4\n"
            + "datacall hpush z \"h, 5\"\n"
            + "datacall hpush z \"h, 3\"\n"
            + "datacall hpush z \"h, 4\"\n"
            + "datacall hpush z \"h, 1\"\n"
            + "datacall hsize r0 \"h\"\n"
            + "datacall hpop r1 \"h\"\n"
            + "datacall hpop r2 \"h\"\n"
            + "datacall hpop r3 \"h\"\n"
            + "datacall hpop r4 \"h\"\n"
            + "datacall hpop r5 \"h\"\n"
            + "datacall hsize r6 \"h\"\n");
        checkNum(result, "r0", 4, "heap size");
        checkNum(result, "r1", 1, "min-heap pops the smallest first");
        checkNum(result, "r2", 3, "min-heap order");
        checkNum(result, "r3", 4, "min-heap order");
        checkNum(result, "r4", 5, "min-heap order");
        checkNaN(result, "r5", "pop on an empty heap");
        checkNum(result, "r6", 0, "drained heap size");

        Run full = run("heap h cell2 0 2\n"
            + "datacall hpush z \"h, 1\"\n"
            + "datacall hpush z \"h, 2\"\n"
            + "datacall hpush r0 \"h, 3\"\n"
            + "datacall hsize r1 \"h\"\n");
        checkNum(full, "r0", -1, "push on a full heap reports -1");
        checkNum(full, "r1", 2, "full heap must not grow");
    }

    private static void chainRuntime(){
        Run result = run("chain c cell1 0 3\n"
            + "datacall cinit r0 \"c\"\n"
            + "datacall cnew r1 \"c\"\n"
            + "datacall cset z \"c, r1, 11\"\n"
            + "datacall cshead z \"c, r1\"\n"
            + "datacall cnew r2 \"c\"\n"
            + "datacall cset z \"c, r2, 22\"\n"
            + "datacall clink z \"c, r2, r1\"\n"
            + "datacall cshead z \"c, r2\"\n"
            + "datacall chead r3 \"c\"\n"
            + "datacall clen r4 \"c\"\n"
            + "datacall cget r5 \"c, r2\"\n"
            + "datacall cget r6 \"c, r1\"\n"
            + "datacall cnext r7 \"c, r2\"\n"
            + "datacall cnext r8 \"c, r1\"\n"
            + "datacall cnext r9 \"c, 99\"\n"
            + "datacall cfree r10 \"c, r1\"\n"
            + "datacall clen r11 \"c\"\n"
            + "datacall cnew r12 \"c\"\n"
            + "datacall cnew r13 \"c\"\n"
            + "datacall cnew r14 \"c\"\n");
        checkNum(result, "r0", 3, "cinit returns the node count");
        checkNum(result, "r1", 0, "first allocation takes node 0");
        checkNum(result, "r2", 1, "second allocation takes node 1");
        checkNum(result, "r3", 1, "cshead sets the list head");
        checkNum(result, "r4", 2, "clen walks the live list");
        checkNum(result, "r5", 22, "cget reads the node value");
        checkNum(result, "r6", 11, "cget reads the linked node");
        checkNum(result, "r7", 0, "cnext follows the link");
        checkNum(result, "r8", -1, "cnext reports the tail as -1");
        checkNum(result, "r9", -1, "cnext on an invalid index is -1");
        checkNum(result, "r10", 1, "cfree succeeds");
        checkNum(result, "r11", 1, "freed node leaves the live list");
        checkNum(result, "r12", 0, "the freed node is reused first");
        checkNum(result, "r13", 2, "the remaining free node is handed out next");
        checkNum(result, "r14", -1, "cnew on an exhausted free list is -1");
    }

    // ===== array sort builtin (__ls_builtin_arrsort) =====

    /**
     * The builtin used to be an insertion sort; it is now an in-place Shell sort (gaps
     * size/2, size/4, ..., 1). Only the emitted body changed: the sortasc/sortdesc cards,
     * the "__ls_builtin_arrsort" signature and the carrier are untouched. These checks pin
     * the observable result on the real executor — ascending/descending, reversed input,
     * already-sorted input, duplicates, size 1, and a non-zero base that must not bleed
     * into neighbouring slots.
     */
    private static void sortRuntime(){
        double[] shuffled = {9, 3, 7, 1, 8, 2, 6, 0, 5, 4, 11, 10};

        Run asc = run("array buf cell1 0 12\ndatacall sortasc ~ \"buf\"\n", "cell1", 0, shuffled);
        for(int i = 0; i < shuffled.length; i++){
            checkMem(asc, "cell1", i, i, "sortasc must write ascending element " + i);
        }

        Run desc = run("array buf cell1 0 12\ndatacall sortdesc ~ \"buf\"\n", "cell1", 0, shuffled);
        for(int i = 0; i < shuffled.length; i++){
            checkMem(desc, "cell1", i, shuffled.length - 1 - i, "sortdesc must write descending element " + i);
        }

        // reversed input: insertion sort's worst case, still must come out sorted
        Run reversed = run("array buf cell1 0 8\ndatacall sortasc ~ \"buf\"\n", "cell1", 0,
            new double[]{8, 7, 6, 5, 4, 3, 2, 1});
        for(int i = 0; i < 8; i++){
            checkMem(reversed, "cell1", i, i + 1, "reversed input must sort ascending");
        }

        // already-sorted input exercises the "shift never runs" branch on every gap
        Run sorted = run("array buf cell1 0 8\ndatacall sortasc ~ \"buf\"\n", "cell1", 0,
            new double[]{1, 2, 3, 4, 5, 6, 7, 8});
        for(int i = 0; i < 8; i++){
            checkMem(sorted, "cell1", i, i + 1, "sorted input must stay sorted");
        }

        // equal elements must be treated as "already in place" (dir multiplier <= 0 stops the shift)
        Run duplicates = run("array buf cell1 0 8\ndatacall sortasc ~ \"buf\"\n", "cell1", 0,
            new double[]{5, 1, 5, 1, 3, 3, 2, 2});
        double[] expected = {1, 1, 2, 2, 3, 3, 5, 5};
        for(int i = 0; i < expected.length; i++){
            checkMem(duplicates, "cell1", i, expected[i], "duplicates must sort deterministically");
        }

        // size 1: gap = 1/2 = 0, the body must be a no-op
        Run single = run("array buf cell1 0 1\ndatacall sortasc ~ \"buf\"\n", "cell1", 0, new double[]{42});
        checkMem(single, "cell1", 0, 42, "a one-element array must be untouched");

        // non-zero base: honour base, never touch the slots just below/above the range
        Run offset = run("array buf cell1 20 6\ndatacall sortasc ~ \"buf\"\n", "cell1", 19,
            new double[]{111, 4, 1, 3, 2, 6, 5, 222});
        for(int i = 0; i < 6; i++){
            checkMem(offset, "cell1", 20 + i, i + 1, "sort must honour a non-zero base");
        }
        checkMem(offset, "cell1", 19, 111, "sort must not write below base");
        checkMem(offset, "cell1", 26, 222, "sort must not write past the end");
    }

    // ===== array bulk runtime: copy / indexof builtins =====

    private static void arrayBulkRuntime(){
        arrayCopyRuntime();
        arrayIndexofRuntime();
    }

    /**
     * {@code copy(dst, src)} must read the source range and write the destination range. The
     * injected body used to cross the two bases: it read {@code smem[dbase + i]} and wrote
     * {@code dmem[sbase + i]}, so two arrays in the same block copied dst := src (inverted)
     * while arrays in different blocks scribbled at the wrong offsets. Both sides are seeded
     * with distinct values so any base crossing changes an observable slot.
     */
    private static void arrayCopyRuntime(){
        // same memory block, different bases: dst a@0, src b@4
        Run inBlock = run("array a cell1 0 4\narray b cell1 4 4\n"
            + "ifbegin expr \"copy(a, b) >= 0\" 4\n"
            + "set ok 1\n"
            + "blockend\n", new Seed("cell1", 0, new double[]{1, 2, 3, 4, 9, 8, 7, 6}));
        checkNum(inBlock, "ok", 1, "copy must run and return a value");
        for(int i = 0; i < 4; i++){
            checkMem(inBlock, "cell1", i, 9 - i, "same-block copy must write src into dst (a[" + i + "])");
            checkMem(inBlock, "cell1", 4 + i, 9 - i, "same-block copy must leave src untouched (b[" + i + "])");
        }

        // cross-block copy with a non-zero base on each side
        Run cross = run("array a cell1 2 4\narray b cell2 8 4\n"
            + "ifbegin expr \"copy(a, b) >= 0\" 4\n"
            + "set ok 1\n"
            + "blockend\n",
            new Seed("cell1", 2, new double[]{111, 222, 333, 444}),
            new Seed("cell2", 8, new double[]{9, 8, 7, 6}));
        checkNum(cross, "ok", 1, "cross-block copy must run and return a value");
        for(int i = 0; i < 4; i++){
            checkMem(cross, "cell1", 2 + i, 9 - i, "cross-block copy must honour both bases");
        }
        checkMem(cross, "cell1", 1, 0, "cross-block copy must not write before the dst base");
        checkMem(cross, "cell2", 7, 0, "cross-block copy must not write before the src base");
    }

    /**
     * {@code indexof} must return the first match and stop reading there. The old body kept
     * cycling to {@code size} after a hit (the "already found" jump only skipped the result
     * assignment, not the read); the per-cell read counter pins the early exit and the
     * first-match (not last/any) semantics.
     */
    private static void arrayIndexofRuntime(){
        // hit at index 3: reads must stop right after it, not scan all 8 slots
        Run hit = run("array buf cell1 0 8\nset z 0\n"
            + "ifbegin expr \"indexof(buf, 9) >= 0\" 4\n"
            + "set z 1\n"
            + "blockend\n", new Seed("cell1", 0, new double[]{7, 7, 7, 9, 7, 7, 7, 7}));
        checkNum(hit, "z", 1, "indexof must report a hit");
        check(hit.cells.get("cell1").reads == 4, "indexof must stop at the first hit, read "
            + hit.cells.get("cell1").reads + " of 8 slots");

        // duplicates: the first match wins and the scan stops there
        Run first = run("array buf cell1 0 8\nset z 0\n"
            + "ifbegin expr \"indexof(buf, 5) == 1\" 4\n"
            + "set z 1\n"
            + "blockend\n", new Seed("cell1", 0, new double[]{7, 5, 7, 5, 7, 7, 7, 7}));
        checkNum(first, "z", 1, "indexof must return the first of several matches");
        check(first.cells.get("cell1").reads == 2, "indexof must stop at the first match, read "
            + first.cells.get("cell1").reads + " of 8 slots");

        // miss: the whole array is read and the caller sees no match
        Run miss = run("array buf cell1 0 8\nset z 0\n"
            + "ifbegin expr \"indexof(buf, 99) >= 0\" 4\n"
            + "set z 1\n"
            + "blockend\n", new Seed("cell1", 0, new double[]{7, 7, 7, 7, 7, 7, 7, 7}));
        checkNum(miss, "z", 0, "a miss must not enter the if body");
        check(miss.cells.get("cell1").reads == 8, "a miss must still scan all 8 slots, read "
            + miss.cells.get("cell1").reads + " of 8");

        // inline mode expands the same body at the call site; the forward break must survive too
        Run inline = run(SugarCompiler.FuncMode.inline, "array buf cell1 0 8\nset z 0\n"
            + "ifbegin expr \"indexof(buf, 9) >= 0\" 4\n"
            + "set z 1\n"
            + "blockend\n", new Seed("cell1", 0, new double[]{7, 7, 7, 9, 7, 7, 7, 7}));
        checkNum(inline, "z", 1, "inline indexof must report a hit");
        check(inline.cells.get("cell1").reads == 4, "inline indexof must stop at the first hit, read "
            + inline.cells.get("cell1").reads + " of 8 slots");
    }

    // ===== value semantics: v5 copies must not numericize objects / the NaN marker =====

    /**
     * v5 API: every value copy is {@code set dest src} (or a builtin body {@code set}) instead of
     * the pre-v5 {@code op add dest src 0}. {@code op} reads its operands through {@code LVar.num()},
     * which folds units/buildings/strings to 1 and the NaN marker (an object variable with a null
     * payload) to 0, so the old spelling silently destroyed those values.
     */
    private static void valuePreservation(){
        // (1) The NaN marker / null object survives a function return: `@unit` is a null object
        // headlessly, and the pre-v5 numeric copy turned it into the number 0.
        Run nullReturn = run("set b @unit\nfuncdef f ~ 5\nreturn \"b\"\nblockend\nfunccall f \"\" r\n");
        LVar nullResult = nullReturn.executor.optionalVar("r");
        check(nullResult != null && nullResult.isobj && nullResult.objval == null,
            "returning the null-object (NaN marker) value must not copy it numerically");

        // (2) A real object (the bound cell2 block) survives a return; the pre-v5 copy turned any
        // object into the number 1.
        Run objectReturn = run("set b cell2\nfuncdef f ~ 5\nreturn \"b\"\nblockend\nfunccall f \"\" r\n");
        LVar objectResult = objectReturn.executor.optionalVar("r");
        check(objectResult != null && objectResult.isobj && objectResult.objval == objectReturn.cells.get("cell2"),
            "returning an object value must keep the identical object");

        // (3) Object stored in a data structure and read back through the builtin hit path
        // (hpush writes it, hpop reads it through the injected body's value copy).
        Run roundTrip = run("set b cell2\n"
            + "heap h cell1 0 4\n"
            + "datacall hpush z \"h, b\"\n"
            + "datacall hpop rh \"h\"\n");
        LVar popped = roundTrip.executor.optionalVar("rh");
        check(popped != null && popped.isobj && popped.objval == roundTrip.cells.get("cell2"),
            "an object pushed on a heap must come back through hpop unchanged");
    }

    // ===== harness =====

    private static Run run(String sugar){
        return run(sugar, new Seed[0]);
    }

    private static Run run(String sugar, String seedCell, int seedStart, double[] seed){
        return run(sugar, new Seed(seedCell, seedStart, seed));
    }

    private static Run run(String sugar, Seed... seeds){
        return run(SugarCompiler.FuncMode.normal, sugar, seeds);
    }

    /**
     * Runs {@code sugar} on the real executor. Each {@code seed} is pre-written into its cell
     * starting at {@code seed.start} before the program runs — required by the array-sort /
     * copy tests, whose input must exist before the builtin touches it.
     */
    private static Run run(SugarCompiler.FuncMode mode, String sugar, Seed... seeds){
        String code = SugarCompiler.stripMarkers(SugarCompiler.compile(sugar, mode, null, null));
        LExecutor executor = new LExecutor();
        executor.load(LAssembler.assemble(code, true));

        Run run = new Run(executor);
        for(int i = 1; i <= 8; i++){
            FakeMemory memory = new FakeMemory(64);
            run.cells.put("cell" + i, memory);
            bind(executor, "cell" + i, memory);
        }
        bind(executor, "message1", new FakePrintable());
        bind(executor, "message2", new FakePrintable());

        for(Seed seed : seeds){
            FakeMemory memory = run.cells.get(seed.cell);
            check(memory != null, "no fake memory '" + seed.cell + "' to seed");
            for(int i = 0; i < seed.values.length; i++){
                memory.numbers[seed.start + i] = seed.values[i];
            }
        }

        int cap = 200000;
        int steps = 0;
        while(steps < cap && executor.counter.numval >= 0 && executor.counter.numval < executor.instructions.length){
            executor.runOnce();
            steps++;
        }
        check(steps < cap, "program did not terminate (possible infinite loop):\n" + code);
        return run;
    }

    private static void bind(LExecutor executor, String name, Object value){
        LVar var = executor.optionalVar(name);
        if(var != null) var.setobj(value);
    }

    /** One pre-run cell seed; the varargs run() accepts any number of them. */
    static final class Seed{
        final String cell;
        final int start;
        final double[] values;

        Seed(String cell, int start, double[] values){
            this.cell = cell;
            this.start = start;
            this.values = values;
        }
    }

    static final class Run{
        final LExecutor executor;
        final Map<String, FakeMemory> cells = new LinkedHashMap<>();

        Run(LExecutor executor){
            this.executor = executor;
        }
    }

    /**
     * Numeric value of a variable as mlog arithmetic sees it. Note the game represents both
     * "never assigned" and NaN as an object variable with a null payload, so {@code num()}
     * (which maps that payload to 0) is the right accessor for numeric expectations.
     */
    private static double numOf(Run run, String name){
        LVar var = run.executor.optionalVar(name);
        check(var != null, "variable '" + name + "' is missing from the program");
        return var.num();
    }

    private static void checkNum(Run run, String name, double expected, String what){
        double actual = numOf(run, name);
        check(!Double.isNaN(expected) && Math.abs(actual - expected) < 1e-9,
            what + ": expected " + fmt(expected) + " but " + name + " = " + fmt(actual));
    }

    /** Empty-container reads must produce the runtime's NaN marker (object variable, null payload). */
    private static void checkNaN(Run run, String name, String what){
        LVar var = run.executor.optionalVar(name);
        check(var != null, "variable '" + name + "' is missing from the program");
        check(var.isobj && var.objval == null, what + ": '" + name + "' is not the NaN marker");
        check(Double.isNaN(var.numOrNan()), what + ": '" + name + "'.numOrNan() is not NaN");
    }

    private static void checkMem(Run run, String cell, int index, double expected, String what){
        FakeMemory memory = run.cells.get(cell);
        check(memory != null, "no fake " + cell + " bound");
        double actual = memory.numbersAt(index);
        check(!Double.isNaN(expected) && actual == expected,
            what + ": expected " + cell + "[" + index + "] = " + fmt(expected) + " but was " + fmt(actual));
    }

    private static String fmt(double value){
        if(Double.isNaN(value)) return "NaN";
        if(value == Math.rint(value) && Math.abs(value) < 1e15) return Long.toString((long)value);
        return Double.toString(value);
    }

    /** Fake memory with the game's MemoryBlock semantics (object/sentinel + NaN on out of range). */
    static final class FakeMemory implements LReadable, LWritable{
        private static final Object SENTINEL = new Object();
        final Object[] objects;
        final double[] numbers;

        FakeMemory(int size){
            objects = new Object[size];
            numbers = new double[size];
            Arrays.fill(objects, SENTINEL);
        }

        @Override public boolean readable(LExecutor exec){ return true; }
        @Override public boolean writable(LExecutor exec){ return true; }

        double numbersAt(int index){
            return index >= 0 && index < numbers.length ? numbers[index] : Double.NaN;
        }

        int reads;

        @Override
        public void read(LVar position, LVar output){
            reads++;
            int address = position.numi();
            if(address < 0 || address >= objects.length){
                output.setnum(Double.NaN);
                return;
            }
            Object object = objects[address];
            if(object == SENTINEL){
                output.setnum(numbers[address]);
            }else{
                output.setobj(object);
            }
        }

        @Override
        public void write(LVar position, LVar value){
            int address = position.numi();
            if(address < 0 || address >= objects.length) return;
            if(value.isobj){
                objects[address] = value.objval;
            }else{
                objects[address] = SENTINEL;
                numbers[address] = value.numval;
            }
        }
    }

    static final class FakePrintable implements LPrintable{
        final StringBuilder text = new StringBuilder();
        @Override public boolean printable(LExecutor exec){ return true; }
        @Override public void print(StringBuilder value){ text.append(value); }
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
