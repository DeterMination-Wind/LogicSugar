package mindustry.logic;

import arc.struct.Seq;
import logicsugar.assist.expr.ArrayRegistry;
import logicsugar.assist.expr.ExprCompiler;
import logicsugar.assist.expr.ExprHook;
import logicsugar.assist.expr.ExprStatement;
import mindustry.Vars;
import mindustry.logic.LStatements.ReadStatement;
import mindustry.logic.LStatements.WriteStatement;
import mindustry.logic.SugarStatements.ArrayStatement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pinned coverage for the {@code array} declaration card and the {@code buf[i]} subscript
 * translation (LogicSugar-original; v0 = literal base/size only). Three layers:
 *
 * <p><b>Expression layer</b> — inside an {@link ArrayRegistry} compile context, subscripts
 * compile to exact vanilla {@code read}/{@code write} lines: physical address = base + index,
 * literal addresses fold, variable addresses reuse the operand (base 0) or prepend one
 * {@code op add} (base &gt; 0). Literal out-of-range indexes and undeclared names fail the
 * compile; with no context registered (pure vanilla mlog) subscripts degrade to plain
 * variable emission and never throw.</p>
 *
 * <p><b>Compiler layer</b> — declaration cards lower to nothing (the saved mlog stays
 * vanilla-parseable), strict validation rejects duplicate names / overlapping ranges on the
 * same memory block / illegal base or size literals, and the carrier round trip (restore →
 * statements → recompile) reproduces the stored normalized stream.</p>
 *
 * <p><b>Fold layer</b> — the editor's unfold→fold round trip is exercised at the Line level
 * (the canvas walk itself needs scene/UI, which the headless self-test cannot create; the
 * folding decisions delegate to the same {@code rebuild}/{@code rebuildAssignment} verified
 * here): unfolded read/write chains fold back to {@code buf[i]} expressions that recompile
 * to the identical instruction stream.</p>
 */
public class ArraySugarTest{
    public static void main(String[] args){
        SugarStatements.installParsers();
        Vars.logicVars = new GlobalVars();

        literalSubscriptBaseZero();
        literalSubscriptBaseShifted();
        variableSubscriptAddressCalculation();
        subscriptAssignment();
        literalIndexOutOfBoundsFails();
        undeclaredArrayNameFails();
        noRegistryDegradesToPlainEmission();
        registryValidationFailures();
        declarationCardLowrsToNothingAndRoundTrips();
        conditionExpressionLowersToReadAndJump();
        outputIsPureVanilla();
        unfoldFoldRoundTrip();
        // ===== F1 v2: len / matrix / arrayinit / bounds asserts / capacity =====
        lenConstantFolding();
        lenTwoArgUnchanged();
        lenUnknownArrayFails();
        matrixLiteralSubscript();
        matrixVariableSubscript();
        matrixSubscriptAssignment();
        matrixLiteralOutOfBoundsFails();
        matrixValidationFailures();
        arrayInitEmitsWrites();
        arrayInitErrors();
        emitModeEmitsBoundsAsserts();
        memoryCapacityLimits();
        newCardsOutputIsPureVanilla();
        // ===== F3: matrix fold + expression-path bounds asserts =====
        matrixFoldRoundTrip();
        matrixFoldAddressShapes();
        matrixFoldWriteAndUncertain();
        arrayFoldVerifyGate();
        foldGateAcceptsRealChains();
        unfoldInsertsBoundsAsserts();
        unfoldAssertIdempotentAndMarked();
        unfoldProductCompilesAsSugar();
        autoAssertDoesNotBlockFolding();
        System.out.println("LogicSugar Array self-test passed.");
    }

    // ===== expression layer =====

    /** base=0 + literal subscript → exact `read` line at the literal address. */
    private static void literalSubscriptBaseZero(){
        withRegistry("array buf cell1 0 8", () -> {
            List<ExprCompiler.Line> ops = ExprCompiler.compile("x", "buf[3]");
            checkLine("read x cell1 3", textOf(ops));
        });
    }

    /** base&gt;0 + literal subscript → address folded to base+index at compile time. */
    private static void literalSubscriptBaseShifted(){
        withRegistry("array buf cell1 10 8", () -> {
            checkLine("read x cell1 13", textOf(ExprCompiler.compile("x", "buf[3]")));
            checkLine("read x cell1 10", textOf(ExprCompiler.compile("x", "buf[0]")));
        });
    }

    /** Variable subscript: base 0 reuses the operand directly; base&gt;0 prepends one op add. */
    private static void variableSubscriptAddressCalculation(){
        withRegistry("array buf cell1 0 8", () -> {
            checkLine("read x cell1 i", textOf(ExprCompiler.compile("x", "buf[i]")));
        });
        withRegistry("array buf cell1 10 8", () -> {
            checkLine("op add _0 10 i\nread x cell1 _0", textOf(ExprCompiler.compile("x", "buf[i]")));
        });
    }

    /** `buf[2] = 5` lowers to a single `write` line at base+2 (assignment dest path). */
    private static void subscriptAssignment(){
        withRegistry("array buf cell1 0 8", () -> {
            checkLine("write 5 cell1 2", textOf(ExprCompiler.compile("buf[2]", "5")));
        });
        withRegistry("array buf cell1 10 8", () -> {
            checkLine("write 5 cell1 12", textOf(ExprCompiler.compile("buf[2]", "5")));
            // variable index: address temp is computed before the write
            checkLine("op add _0 10 i\nwrite 5 cell1 _0", textOf(ExprCompiler.compile("buf[i]", "5")));
        });
    }

    /** A literal subscript outside [0, size) is a compile error (no silent wraparound). */
    private static void literalIndexOutOfBoundsFails(){
        withRegistry("array buf cell1 10 8", () -> {
            checkThrows(() -> ExprCompiler.compile("x", "buf[8]"), "out-of-range literal index");
            checkThrows(() -> ExprCompiler.compile("x", "buf[-1]"), "negative literal index");
        });
    }

    /** Names missing from a non-empty registry fail the compile (typos cannot pass silently). */
    private static void undeclaredArrayNameFails(){
        withRegistry("array buf cell1 0 8", () -> {
            checkThrows(() -> ExprCompiler.compile("x", "nosuch[0]"), "undeclared array name");
        });
    }

    /** Without a registry (no declaration card / pure vanilla mlog) subscripts degrade to
     *  plain variable emission — syntax check only, never a name or range error. */
    private static void noRegistryDegradesToPlainEmission(){
        ArrayRegistry previous = ArrayRegistry.enter(ArrayRegistry.empty());
        try{
            checkLine("read x buf 3", textOf(ExprCompiler.compile("x", "buf[3]")));
            checkLine("write 5 buf 2", textOf(ExprCompiler.compile("buf[2]", "5")));
        }finally{
            ArrayRegistry.restore(previous);
        }
    }

    // ===== compiler layer =====

    /** Duplicate names, overlapping ranges on one memory block and illegal base/size
     *  literals are rejected by the strict compile path. */
    private static void registryValidationFailures(){
        checkCompileThrows("array a cell1 0 8\narray a cell1 16 8\nset x 1\n", "duplicate array name");
        checkCompileThrows("array a cell1 0 8\narray b cell1 4 2\nset x 1\n", "overlapping ranges");
        checkCompileThrows("array a cell1 n 8\nset x 1\n", "variable base");
        checkCompileThrows("array a cell1 0 0\nset x 1\n", "zero size");
        checkCompileThrows("array a cell1 -2 8\nset x 1\n", "negative base");
        // a second block never overlaps the first
        SugarCompiler.compile("array a cell1 0 8\narray b cell2 0 8\nset x 1\n",
            SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
    }

    /** The declaration card itself produces no mlog line, and the carrier round trip
     *  (compile → restore → statements → recompile) reproduces the stored stream. */
    private static void declarationCardLowrsToNothingAndRoundTrips(){
        String sugar = "array buf cell1 0 8\n"
            + "read x cell1 3\n"
            + "op add y x 1\n";
        String compiled = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        String mlog = SugarCompiler.stripMarkers(compiled);
        check(mlog.contains("read x cell1 3") && mlog.contains("op add y x 1"),
            "regular instructions lost after compile: " + mlog);
        check(!mlog.contains("array "), "array declaration card leaked into mlog: " + mlog);

        // carrier keeps the sugar verbatim, statements re-parse with the array card intact
        String restored = SugarCompiler.restore(compiled);
        check(restored.equals(sugar), "carrier did not preserve the sugar source:\n" + restored);
        Seq<LStatement> statements = LAssembler.read(restored, true);
        check(statements.size == 3, "restored program should hold 3 statements, got " + statements.size);
        check(statements.get(0) instanceof ArrayStatement, "first statement is not an array card");
        ArrayStatement card = (ArrayStatement)statements.get(0);
        check(card.array.equals("buf") && card.memory.equals("cell1")
            && card.base.equals("0") && card.size.equals("8"),
            "array card fields drifted: " + card.array + "/" + card.memory + "/" + card.base + "/" + card.size);

        // recompiling the restored sugar matches the stored normalized stream
        String recompiled = SugarCompiler.compile(restored, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        check(SugarCompiler.matchesStoredStream(recompiled, compiled),
            "recompiled sugar did not match the stored stream");
        check(SugarCompiler.verifyRestore(compiled, sugar), "verifyRestore rejected its own output");
    }

    /** `if buf[0] > 1` lowers the subscript to a read line feeding the branch jump. */
    private static void conditionExpressionLowersToReadAndJump(){
        String sugar = "array buf cell1 0 8\n"
            + "ifbegin expr \"buf[0] > 1\" 3\n"
            + "set x 1\n"
            + "blockend\n";
        String compiled = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        String mlog = SugarCompiler.stripMarkers(compiled);
        Seq<String> lines = seq(mlog);
        check(hasLine(lines, "read __ls_cond_1 cell1 0"),
            "condition subscript did not lower to a read line:\n" + mlog);
        check(hasLine(lines, "op greaterThan __ls_cond_1 __ls_cond_1 1"),
            "condition comparison lost:\n" + mlog);
        check(hasLine(lines, "jump __ls_stmt_4 equal __ls_cond_1 0"),
            "branch jump structure broken:\n" + mlog);
        check(!mlog.contains("array "), "declaration card leaked into condition program: " + mlog);
    }

    /** Executable output carries no sugar opcodes (statements parse on vanilla clients). */
    private static void outputIsPureVanilla(){
        String sugar = "array buf cell1 0 8\n"
            + "read x cell1 3\n"
            + "write x cell1 4\n"
            + "ifbegin expr \"buf[0] > 1\" 5\n"
            + "op add y x 1\n"
            + "blockend\n";
        String compiled = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        String mlog = SugarCompiler.stripMarkers(compiled);
        for(String line : mlog.replace("\r\n", "\n").split("\n", -1)){
            String t = line.trim();
            if(t.isEmpty() || t.endsWith(":")) continue; // labels are legal vanilla syntax
            check(!t.startsWith("array ") && !t.startsWith("funccall ") && !t.startsWith("ifbegin ")
                && !t.startsWith("expr ") && !t.startsWith("funcdef "),
                "sugar residue in compiled mlog: " + t);
        }
    }

    // ===== fold layer =====

    /**
     * Unfold→fold round trip at the Line level (the canvas walk in ExprHook delegates to the
     * same compile/rebuild pair verified here; the headless self-test cannot build an LCanvas):
     * <ol>
     *   <li>{@code x = buf[i] * 2} unfolds to {@code read _0 cell1 i} + {@code op mul x _0 2};</li>
     *   <li>folding that chain back rebuilds {@code buf[i] * 2} (the address add folds away
     *       for base&gt;0 arrays too);</li>
     *   <li>recompiling the rebuilt expression reproduces the identical chain.</li>
     * </ol>
     * Assignment chains fold through {@code rebuildAssignment} in both the literal and the
     * variable-index shape.
     */
    private static void unfoldFoldRoundTrip(){
        withRegistry("array buf cell1 0 8", () -> {
            // unfold: ExprStatement writes the op/read chain it would insert into the canvas
            String unfolded = writeOf("x", "buf[i] * 2");
            checkLine("read _0 cell1 i\nop mul x _0 2", unfolded);
            // fold: the same chain (as the canvas cards would collect it) rebuilds the expression
            checkLine("x = buf[i]*2", foldBack(unfolded));
            // session round trip: recompiling the folded expression is stream-identical
            checkLine(unfolded, textOf(ExprCompiler.compile("x", "buf[i]*2")));
        });
        withRegistry("array buf cell1 10 8", () -> {
            // base>0: the op add defining the physical address is CONSUMED by the fold
            String unfolded = writeOf("x", "buf[i] * 2");
            checkLine("op add _0 10 i\nread _1 cell1 _0\nop mul x _1 2", unfolded);
            checkLine("x = buf[i]*2", foldBack(unfolded));
            checkLine(unfolded, textOf(ExprCompiler.compile("x", "buf[i]*2")));
        });
        withRegistry("array buf cell1 0 8", () -> {
            // assignment fold, literal index shape (single write line)
            String unfolded = writeOf("buf[2]", "5");
            checkLine("write 5 cell1 2", unfolded);
            String[] pair = ExprCompiler.rebuildAssignment(compileLines("buf[2]", "5"));
            check(pair != null && "buf[2]".equals(pair[0]) && "5".equals(pair[1]),
                "assignment fold failed on a literal-index write chain");
        });
        withRegistry("array buf cell1 10 8", () -> {
            // assignment fold, variable index shape (op add + write)
            String unfolded = writeOf("buf[i]", "5");
            checkLine("op add _0 10 i\nwrite 5 cell1 _0", unfolded);
            String[] pair = ExprCompiler.rebuildAssignment(compileLines("buf[i]", "5"));
            check(pair != null && "buf[i]".equals(pair[0]) && "5".equals(pair[1]),
                "assignment fold failed on a variable-index write chain");
            checkLine(unfolded, textOf(ExprCompiler.compile(pair[0], pair[1])));
        });
        withRegistry("array buf cell1 0 8", () -> {
            // vanilla statements must not fold: a read whose address sits outside every
            // declared range (or on an unregistered block) stays untouched
            check(foldBack("read x cell1 20") == null, "out-of-range read folded into an array");
            check(foldBack("read x vault1 3") == null, "unregistered memory folded into an array");
        });
    }

    // ===== F1 v2: len =====

    /** len(buf) with one argument folds to the declared array size at compile time; the
     *  product carries the literal only (no len() call, no declaration card). */
    private static void lenConstantFolding(){
        withRegistry("array buf cell1 10 8", () -> {
            checkLine("op add x 8 0", textOf(ExprCompiler.compile("x", "len(buf)")));
            checkLine("op add x 8 1", textOf(ExprCompiler.compile("x", "len(buf) + 1")));
        });
        withRegistry("array a cell1 0 3\narray b cell1 4 5", () -> {
            checkLine("op add x 3 0", textOf(ExprCompiler.compile("x", "len(a)")));
            checkLine("op add x 5 0", textOf(ExprCompiler.compile("x", "len(b)")));
        });
        // compiler layer: the condition lowers with the folded literal in place
        String sugar = "array buf cell1 0 8\n"
            + "ifbegin expr \"len(buf) > 2\" 3\n"
            + "set x 1\n"
            + "blockend\n";
        String mlog = stripCompile(sugar, SugarCompiler.AssertEmit.strip);
        check(mlog.contains("op greaterThan __ls_cond_1 8 2"),
            "len(buf) did not fold to its size literal:\n" + mlog);
        check(!mlog.contains("len("), "len() call leaked into the product:\n" + mlog);
        check(!mlog.contains("array "), "array card leaked into the product:\n" + mlog);
    }

    /** Two-argument len is still the vanilla vector-length builtin, registry or not. */
    private static void lenTwoArgUnchanged(){
        checkLine("op len x 3 4", textOf(ExprCompiler.compile("x", "len(3, 4)")));
        withRegistry("array buf cell1 0 8", () -> {
            checkLine("op len x a b", textOf(ExprCompiler.compile("x", "len(a, b)")));
        });
    }

    /** A one-argument len naming no declared array is a clear compile error. */
    private static void lenUnknownArrayFails(){
        withRegistry("array buf cell1 0 8", () -> {
            checkThrows(() -> ExprCompiler.compile("x", "len(nosuch)"), "len of an undeclared array");
            checkThrows(() -> ExprCompiler.compile("x", "len(buf[0])"), "len of a subscript");
        });
        ArrayRegistry previous = ArrayRegistry.enter(ArrayRegistry.empty());
        try{
            checkThrows(() -> ExprCompiler.compile("x", "len(buf)"), "len with no array context");
        }finally{
            ArrayRegistry.restore(previous);
        }
        checkCompileThrows("set x 1\nifbegin expr \"len(nosuch) > 0\" 3\nset y 2\nblockend\n",
            "len of an undeclared array (compile path)");
    }

    // ===== F1 v2: matrix =====

    /** Literal row/col fold to base + row*cols + col at compile time. */
    private static void matrixLiteralSubscript(){
        withRegistry("matrix m cell1 0 2 3", () -> {
            checkLine("read x cell1 0", textOf(ExprCompiler.compile("x", "m[0][0]")));
            checkLine("read x cell1 3", textOf(ExprCompiler.compile("x", "m[1][0]")));
            checkLine("read x cell1 5", textOf(ExprCompiler.compile("x", "m[1][2]")));
        });
        withRegistry("matrix m cell1 10 2 3", () -> {
            checkLine("read x cell1 15", textOf(ExprCompiler.compile("x", "m[1][2]")));
        });
    }

    /** Variable row/col emit op mul/op add address arithmetic; literals fold into the offset. */
    private static void matrixVariableSubscript(){
        withRegistry("matrix m cell1 0 2 3", () -> {
            checkLine("op mul _0 i 3\nop add _0 _0 j\nread x cell1 _0",
                textOf(ExprCompiler.compile("x", "m[i][j]")));
            checkLine("op add _0 3 j\nread x cell1 _0",
                textOf(ExprCompiler.compile("x", "m[1][j]")));
            checkLine("op mul _0 i 3\nop add _0 2 _0\nread x cell1 _0",
                textOf(ExprCompiler.compile("x", "m[i][2]")));
        });
        withRegistry("matrix m cell1 10 2 3", () -> {
            checkLine("op mul _0 i 3\nop add _0 _0 j\nop add _0 10 _0\nread x cell1 _0",
                textOf(ExprCompiler.compile("x", "m[i][j]")));
        });
        // cols == 1: no mul, the row index is the address directly
        withRegistry("matrix m cell1 0 4 1", () -> {
            checkLine("read x cell1 i", textOf(ExprCompiler.compile("x", "m[i][0]")));
        });
    }

    /** m[i][j] = value lowers to a write at the same computed address. */
    private static void matrixSubscriptAssignment(){
        withRegistry("matrix m cell1 0 2 3", () -> {
            checkLine("write 5 cell1 5", textOf(ExprCompiler.compile("m[1][2]", "5")));
            checkLine("op mul _0 i 3\nop add _0 _0 j\nwrite 5 cell1 _0",
                textOf(ExprCompiler.compile("m[i][j]", "5")));
        });
        withRegistry("matrix m cell1 10 2 3", () -> {
            checkLine("write 5 cell1 15", textOf(ExprCompiler.compile("m[1][2]", "5")));
        });
    }

    /** Literal subscripts outside [0, rows) / [0, cols) are compile errors. */
    private static void matrixLiteralOutOfBoundsFails(){
        withRegistry("matrix m cell1 0 2 3", () -> {
            checkThrows(() -> ExprCompiler.compile("x", "m[2][0]"), "row out of range");
            checkThrows(() -> ExprCompiler.compile("x", "m[0][3]"), "column out of range");
            checkThrows(() -> ExprCompiler.compile("x", "m[-1][0]"), "negative row");
            checkThrows(() -> ExprCompiler.compile("x", "m[0][-1]"), "negative column");
            checkThrows(() -> ExprCompiler.compile("m[2][0]", "5"), "assignment row out of range");
            checkThrows(() -> ExprCompiler.compile("x", "m[0]"), "single subscript on a matrix");
        });
        withRegistry("array a cell1 0 8", () -> {
            checkThrows(() -> ExprCompiler.compile("x", "a[0][0]"), "double subscript on an array");
        });
    }

    /** Matrix declaration validation mirrors the array rules (names, literals, overlap). */
    private static void matrixValidationFailures(){
        checkCompileThrows("matrix m cell1 0 2 3\nmatrix m cell1 6 2 3\nset x 1\n", "duplicate matrix name");
        checkCompileThrows("array a cell1 0 8\nmatrix m cell1 4 2 3\nset x 1\n", "matrix overlaps an array");
        checkCompileThrows("matrix m cell1 0 2 3\narray a cell1 0 2\nset x 1\n", "array overlaps a matrix");
        checkCompileThrows("matrix m cell1 0 0 3\nset x 1\n", "zero rows");
        checkCompileThrows("matrix m cell1 0 2 0\nset x 1\n", "zero cols");
        checkCompileThrows("matrix m cell1 0 r 3\nset x 1\n", "variable rows");
        checkCompileThrows("matrix m cell1 0 2 3\nmatrix n cell2 0 2 3\nmatrix m cell2 6 1 1\nset x 1\n",
            "duplicate matrix name across memory blocks");
        checkCompileThrows("matrix 1bad cell1 0 2 3\nset x 1\n", "illegal matrix identifier");
        checkCompileThrows("matrix __ls_x cell1 0 2 3\nset x 1\n", "reserved __ls_ matrix name");
        checkCompileThrows("funcdef m x 2\nmatrix m cell1 0 2 3\nblockend\nset x 1\n",
            "matrix name conflicts with a function");
    }

    // ===== F1 v2: arrayinit =====

    /** arrayinit emits one write per non-skipped slot at the card position; the card itself
     *  never reaches the product and the sugar survives the carrier round trip. */
    private static void arrayInitEmitsWrites(){
        String sugar = "array a cell1 10 4\n"
            + "arrayinit a 1 2 ~ 4 ~ ~ ~ ~\n"
            + "set x 1\n";
        String compiled = compile(sugar, SugarCompiler.AssertEmit.strip);
        String mlog = SugarCompiler.stripMarkers(compiled);
        Seq<String> lines = seq(mlog);
        check(hasLine(lines, "write 1 cell1 10"), "arrayinit slot 0 missing:\n" + mlog);
        check(hasLine(lines, "write 2 cell1 11"), "arrayinit slot 1 missing:\n" + mlog);
        check(!hasLine(lines, "write 3 cell1 12"), "arrayinit emitted a write for a skipped slot:\n" + mlog);
        check(hasLine(lines, "write 4 cell1 13"), "arrayinit slot 3 missing:\n" + mlog);
        check(!mlog.contains("arrayinit"), "arrayinit card leaked into the product:\n" + mlog);
        check(lines.indexOf("write 1 cell1 10") < lines.indexOf("set x 1"),
            "arrayinit writes are not emitted at the card position:\n" + mlog);
        check(SugarCompiler.restore(compiled).equals(sugar), "arrayinit sugar did not survive the carrier");
        check(SugarCompiler.verifyRestore(compiled, sugar), "arrayinit build failed carrier verification");
        // declarations are program-level: an arrayinit before the array card still resolves
        String orderFree = "arrayinit a 7 ~ ~ ~ ~ ~ ~ ~\narray a cell1 0 4\nset x 1\n";
        check(stripCompile(orderFree, SugarCompiler.AssertEmit.strip).contains("write 7 cell1 0"),
            "arrayinit must resolve arrays declared later in the program");
    }

    /** arrayinit rejects undeclared/matrix targets, non-literal values and out-of-range slots. */
    private static void arrayInitErrors(){
        checkCompileThrows("arrayinit nosuch 1 2 ~ ~ ~ ~ ~ ~\nset x 1\n", "undeclared array");
        checkCompileThrows("matrix m cell1 0 2 3\narrayinit m 1 ~ ~ ~ ~ ~ ~ ~\nset x 1\n", "matrix target");
        checkCompileThrows("array a cell1 0 2\narrayinit a 1 2 3 ~ ~ ~ ~ ~\nset x 1\n", "slot beyond size");
        checkCompileThrows("array a cell1 0 4\narrayinit a 1 x ~ ~ ~ ~ ~ ~\nset x 1\n", "variable value");
        checkCompileThrows("array a cell1 0 4\narrayinit a 1 2+3 ~ ~ ~ ~ ~ ~\nset x 1\n", "expression value");
        // boundary: size 2 accepts slots 0 and 1; skipped slots beyond size are not writes
        String ok = "array a cell1 0 2\narrayinit a 1 2 ~ ~ ~ ~ ~ ~\nset x 1\n";
        String mlog = stripCompile(ok, SugarCompiler.AssertEmit.strip);
        check(mlog.contains("write 1 cell1 0") && mlog.contains("write 2 cell1 1"),
            "boundary arrayinit failed:\n" + mlog);
        // integer, decimal and negative literals are accepted verbatim
        String decimals = "array a cell1 0 4\narrayinit a -1 2.5 -.5 0 ~ ~ ~ ~\nset x 1\n";
        String dmlog = stripCompile(decimals, SugarCompiler.AssertEmit.strip);
        check(dmlog.contains("write -1 cell1 0") && dmlog.contains("write 2.5 cell1 1")
            && dmlog.contains("write -.5 cell1 2") && dmlog.contains("write 0 cell1 3"),
            "numeric literals were not emitted verbatim:\n" + dmlog);
    }

    // ===== F1 v2: bounds asserts (emit mode) =====

    /** emit debug builds get assertBounds lines before variable-subscript reads/writes;
     *  strip builds and the editor paths never emit them. */
    private static void emitModeEmitsBoundsAsserts(){
        String sugar = "array buf cell1 0 8\n"
            + "ifbegin expr \"buf[i] > 1\" 3\n"
            + "set x 1\n"
            + "blockend\n";
        String emit = stripCompile(sugar, SugarCompiler.AssertEmit.emit);
        String expected = "assertBounds integer ~ 0 lessThanEq i lessThanEq 7 \"array 'buf' index out of bounds (0..7)\"";
        check(emit.contains(expected), "emit mode did not emit the array bounds assert:\n" + emit);
        check(emit.indexOf(expected) < emit.indexOf("read __ls_cond_1 cell1 i"),
            "bounds assert is not before the read:\n" + emit);
        String strip = stripCompile(sugar, SugarCompiler.AssertEmit.strip);
        check(!strip.contains("assertBounds"), "strip mode leaked a bounds assert:\n" + strip);
        // the generated assert is picked up by the verification matrix (stored-stream scan)
        check(SugarCompiler.verifyRestore(compile(sugar, SugarCompiler.AssertEmit.emit), sugar),
            "emit build with a generated bounds assert failed carrier verification");

        String matrix = "matrix m cell1 0 2 3\n"
            + "ifbegin expr \"m[i][j] > 1\" 3\n"
            + "set x 1\n"
            + "blockend\n";
        String matrixEmit = stripCompile(matrix, SugarCompiler.AssertEmit.emit);
        check(matrixEmit.contains("assertBounds integer ~ 0 lessThanEq i lessThanEq 1 \"matrix 'm' row out of bounds (0..1)\""),
            "matrix row assert missing:\n" + matrixEmit);
        check(matrixEmit.contains("assertBounds integer ~ 0 lessThanEq j lessThanEq 2 \"matrix 'm' column out of bounds (0..2)\""),
            "matrix column assert missing:\n" + matrixEmit);
        check(!stripCompile(matrix, SugarCompiler.AssertEmit.strip).contains("assertBounds"),
            "strip mode leaked a matrix bounds assert");
        // literal subscripts are compile-time checked, no runtime assert
        check(!stripCompile("array buf cell1 0 8\nifbegin expr \"buf[0] > 1\" 3\nset x 1\nblockend\n",
            SugarCompiler.AssertEmit.emit).contains("assertBounds"),
            "literal subscript should not emit a bounds assert");
        // return and funccall argument paths handle the generated assert line too
        String ret = "array buf cell1 0 8\n"
            + "funcdef f x 3\n"
            + "return \"buf[i]\"\n"
            + "blockend\n"
            + "funccall f 1 r\n";
        String retEmit = stripCompile(ret, SugarCompiler.AssertEmit.emit);
        check(retEmit.contains("assertBounds integer ~ 0 lessThanEq i lessThanEq 7 \"array 'buf' index out of bounds (0..7)\""),
            "return path did not emit the bounds assert:\n" + retEmit);
        check(retEmit.contains("read __ls_func_f_result cell1 i"),
            "return path lost the array read:\n" + retEmit);
        String arg = "array buf cell1 0 8\n"
            + "funcdef f x 3\n"
            + "set y x\n"
            + "blockend\n"
            + "funccall f \"buf[i]\" ~\n";
        String argEmit = stripCompile(arg, SugarCompiler.AssertEmit.emit);
        check(argEmit.contains("assertBounds integer ~ 0 lessThanEq i lessThanEq 7 \"array 'buf' index out of bounds (0..7)\""),
            "funccall argument path did not emit the bounds assert:\n" + argEmit);
        check(argEmit.contains("read _0 cell1 i"), "funccall argument path lost the array read:\n" + argEmit);
    }

    // ===== F1 v2: memory capacity =====

    /** cellN holds 64 slots, bankN/worldN hold 512; other names are skipped. */
    private static void memoryCapacityLimits(){
        // exactly at capacity compiles
        compile("array a cell1 56 8\nset x 1\n", SugarCompiler.AssertEmit.strip);
        compile("matrix m bank1 504 2 4\nset x 1\n", SugarCompiler.AssertEmit.strip);
        compile("matrix m world1 500 2 6\nset x 1\n", SugarCompiler.AssertEmit.strip);
        compile("array a custom 100000 8\nset x 1\n", SugarCompiler.AssertEmit.strip);
        // one past capacity fails
        checkCompileThrows("array a cell1 57 8\nset x 1\n", "cell1 overflow");
        checkCompileThrows("array a CELL1 60 8\nset x 1\n", "case-insensitive cell overflow");
        checkCompileThrows("matrix m cell1 63 1 2\nset x 1\n", "matrix cell overflow");
        checkCompileThrows("array a bank1 505 8\nset x 1\n", "bank1 overflow");
        checkCompileThrows("matrix m world1 500 2 7\nset x 1\n", "world1 overflow");
        check(ArrayRegistry.memoryCapacity("cell1") == 64
            && ArrayRegistry.memoryCapacity("BANK2") == 512
            && ArrayRegistry.memoryCapacity("world3") == 512
            && ArrayRegistry.memoryCapacity("vault1") == -1
            && ArrayRegistry.memoryCapacity("cell") == -1
            && ArrayRegistry.memoryCapacity(null) == -1, "memoryCapacity misclassifies block names");
    }

    // ===== F1 v2: vanilla product =====

    /** The new cards lower to nothing but vanilla instructions (strip mode). */
    private static void newCardsOutputIsPureVanilla(){
        String sugar = "array buf cell1 0 8\n"
            + "matrix m cell2 0 2 3\n"
            + "arrayinit buf 1 2 3 ~ ~ ~ ~ ~\n"
            + "ifbegin expr \"buf[i] + m[i][j] > len(buf)\" 5\n"
            + "set x 1\n"
            + "blockend\n";
        String mlog = stripCompile(sugar, SugarCompiler.AssertEmit.strip);
        for(String line : mlog.replace("\r\n", "\n").split("\n", -1)){
            String t = line.trim();
            if(t.isEmpty() || t.endsWith(":") || t.startsWith("#")) continue; // labels/comments are vanilla
            int space = t.indexOf(' ');
            String opcode = space < 0 ? t : t.substring(0, space);
            check(vanillaOpcodes.contains(opcode), "non-vanilla instruction in product: " + t);
        }
        check(!mlog.contains("arrayinit") && !mlog.contains("matrix "),
            "declaration card leaked into the product:\n" + mlog);
    }

    // ===== F3: matrix fold =====

    /** 矩阵链折回 m[i][j]：地址计算 op mul/op add 被消费，折回结果重编译指令流一致。 */
    private static void matrixFoldRoundTrip(){
        withRegistry("matrix m cell1 0 2 3", () -> {
            String unfolded = writeOf("x", "m[i][j] * 2");
            checkLine("op mul _0 i 3\nop add _0 _0 j\nread _1 cell1 _0\nop mul x _1 2", unfolded);
            checkLine("x = m[i][j]*2", foldBack(unfolded));
            checkLine(unfolded, textOf(ExprCompiler.compile("x", "m[i][j]*2")));
        });
        withRegistry("matrix m cell1 10 2 3", () -> {
            // base>0：最后一条 base 加法也被消费
            String unfolded = writeOf("x", "m[i][j] * 2");
            checkLine("op mul _0 i 3\nop add _0 _0 j\nop add _0 10 _0\nread _1 cell1 _0\nop mul x _1 2", unfolded);
            checkLine("x = m[i][j]*2", foldBack(unfolded));
            checkLine(unfolded, textOf(ExprCompiler.compile("x", "m[i][j]*2")));
        });
    }

    /** 矩阵地址的编译器形态（字面量地址 / 行字面量 / 列字面量 / cols==1）都能反解。 */
    private static void matrixFoldAddressShapes(){
        withRegistry("matrix m cell1 0 2 3", () -> {
            checkLine("x = m[1][2]", foldBack("read x cell1 5"));
            checkLine("x = m[1][j]", foldBack("op add _0 3 j\nread x cell1 _0"));
            checkLine("x = m[i][2]", foldBack("op mul _0 i 3\nop add _0 2 _0\nread x cell1 _0"));
        });
        withRegistry("matrix m cell1 10 2 3", () -> {
            checkLine("x = m[1][2]", foldBack("read x cell1 15"));
            checkLine("x = m[1][j]", foldBack("op add _0 13 j\nread x cell1 _0"));
            checkLine("x = m[i][2]", foldBack("op mul _0 i 3\nop add _0 12 _0\nread x cell1 _0"));
        });
        // cols == 1：行下标即地址（列只有 0）
        withRegistry("matrix m cell1 0 4 1", () -> {
            checkLine("x = m[i][0]", foldBack("read x cell1 i"));
        });
    }

    /** 矩阵写链折回 m[i][j] = v；行下标是表达式时吸收进表达式；不可判定时保持原样。 */
    private static void matrixFoldWriteAndUncertain(){
        withRegistry("matrix m cell1 0 2 3", () -> {
            checkLine("m[i][j] = 5", foldBack("op mul _0 i 3\nop add _0 _0 j\nwrite 5 cell1 _0"));
            checkLine("m[1][2] = 5", foldBack("write 5 cell1 5"));
            // 行下标是复合表达式：其 op add 定义行也被消费进表达式
            checkLine("x = m[i+1][j]",
                foldBack("op add _0 i 1\nop mul _0 _0 3\nop add _0 _0 j\nread x cell1 _0"));
            // 地址超出所有矩阵区间 / 链内无定值的临时地址 → 不可判定，保持原样
            check(foldBack("read x cell1 99") == null, "out-of-range matrix address must not fold");
            check(foldBack("read x cell1 _0") == null, "undefined temp address must not fold");
        });
    }

    /** 折回安全门：重编译指令流不一致的折回必须被拒绝（宁可少折回也不能折错）。 */
    private static void arrayFoldVerifyGate(){
        withRegistry("matrix m cell1 0 2 3", () -> {
            List<ExprCompiler.Line> ops = compileLines("x", "m[1][j]");
            check(ExprCompiler.verifyArrayFold(ops, "x", "m[1][j]", null), "gate rejected the exact fold");
            check(!ExprCompiler.verifyArrayFold(ops, "x", "m[2][j]", null), "gate accepted a wrong matrix fold");
            check(!ExprCompiler.verifyArrayFold(ops, "x", "buf[j]", null), "gate accepted a wrong array fold");
        });
        withRegistry("array buf cell1 10 8", () -> {
            List<ExprCompiler.Line> ops = compileLines("x", "buf[i]");
            check(ExprCompiler.verifyArrayFold(ops, "x", "buf[i]", null), "gate rejected the exact array fold");
            check(!ExprCompiler.verifyArrayFold(ops, "x", "buf[i + 1]", null), "gate accepted a wrong array fold");
        });
    }

    /** 折回安全门必须接受真实的展开链（否则编辑器折叠会静默失效）。 */
    private static void foldGateAcceptsRealChains(){
        withRegistry("array buf cell1 10 8\nmatrix m cell1 20 2 3", () -> {
            gateAccepts("x", "buf[i] * 2");
            gateAccepts("x", "buf[3]");
            gateAccepts("buf[2]", "5");
            gateAccepts("buf[i]", "5");
            gateAccepts("x", "buf[i] + buf[j]");
            gateAccepts("x", "m[i][j]");
            gateAccepts("x", "m[i][j] * 2");
            gateAccepts("x", "buf[k] + m[i][j]");
            gateAccepts("x", "unit.health + buf[i]");
            gateAccepts("x", "cos(a) * 10 + x");
            gateAccepts("m[1][2]", "buf[i] + 1");
        });
        withRegistry("matrix m cell1 0 2 3", () -> {
            gateAccepts("x", "m[i+1][j]");
            gateAccepts("x", "m[1][j+2]");
            gateAccepts("x", "m[i][0]");
        });
    }

    /** 编译 dest = expr，折回后要求安全门接受（与 ExprHook.foldAll 的判定一致）。 */
    private static void gateAccepts(String dest, String expr){
        List<ExprCompiler.Line> ops = ExprCompiler.compile(dest, expr);
        String folded, foldedDest;
        if(ops.get(ops.size() - 1) instanceof ExprCompiler.WriteLine){
            String[] pair = ExprCompiler.rebuildAssignment(ops);
            check(pair != null, "rebuildAssignment failed for " + dest + " = " + expr);
            foldedDest = pair[0];
            folded = pair[1];
        }else{
            folded = ExprCompiler.rebuild(ops);
            check(folded != null, "rebuild failed for " + dest + " = " + expr);
            // 链尾指令的写入目标（与 foldBack 同一口径：op 的 dest 在 token 2，
            // funccall 的 result 在末 token，其余指令在 token 1）
            String text = ops.get(ops.size() - 1).toText();
            String[] parts = text.split("\\s+");
            foldedDest = text.startsWith("op ") ? parts[2]
                : text.startsWith("funccall ") ? parts[parts.length - 1] : parts[1];
        }
        check(ExprCompiler.verifyArrayFold(ops, foldedDest, folded, null),
            "gate rejected a real chain: " + dest + " = " + expr
                + " (folded to " + foldedDest + " = " + folded + ")");
    }

    // ===== F3: expression-statement bounds asserts (emit) =====

    /** emit 下 x = buf[i] / x = m[i][j] 展开时在 read/write 之前插入 assertBounds 卡；
     *  strip 不插入。 */
    private static void unfoldInsertsBoundsAsserts(){
        withRegistry("array buf cell1 0 8", () -> {
            List<ExprCompiler.Line> ops = ExprCompiler.compile("x", "buf[i]", null, true);
            // 编译产物保持 F1 的断言行格式；标记只加在展开出的卡片消息上（autoAssertCard）
            checkLine("assertBounds integer ~ 0 lessThanEq i lessThanEq 7 \"array 'buf' index out of bounds (0..7)\"\nread x cell1 i",
                textOf(ops));
            List<LStatement> statements = ExprHook.toStatements(ops, null, true);
            check(statements.size() == 2, "emit unfold should insert exactly one assert card, got " + statements.size());
            check(statements.get(0) instanceof SugarAsserts.AssertBoundsCard && statements.get(1) instanceof ReadStatement,
                "assert card must precede the read card");
            check(ExprHook.isAutoAssert(statements.get(0)), "auto assert card must carry the marker");

            // write 路径同样在 write 之前
            List<LStatement> writeStatements = ExprHook.toStatements(
                ExprCompiler.compile("buf[i]", "5", null, true), null, true);
            check(writeStatements.size() == 2
                && writeStatements.get(0) instanceof SugarAsserts.AssertBoundsCard
                && writeStatements.get(1) instanceof WriteStatement, "assert card must precede the write card");

            // strip：即使传入带断言行的链也不插入卡片（生产路径 strip 下 compile 本身不产断言行）
            List<LStatement> stripped = ExprHook.toStatements(ops, null, false);
            check(stripped.size() == 1 && stripped.get(0) instanceof ReadStatement, "strip must not insert assert cards");
        });

        withRegistry("matrix m cell1 0 2 3", () -> {
            List<ExprCompiler.Line> ops = ExprCompiler.compile("x", "m[i][j]", null, true);
            List<LStatement> statements = ExprHook.toStatements(ops, null, true);
            int asserts = 0, readAt = -1;
            for(int i = 0; i < statements.size(); i++){
                if(statements.get(i) instanceof SugarAsserts.AssertBoundsCard) asserts++;
                if(statements.get(i) instanceof ReadStatement) readAt = i;
            }
            check(asserts == 2, "matrix read should insert row+column asserts, got " + asserts);
            check(readAt == statements.size() - 1, "matrix asserts must precede the read card");
        });

        // 无数组上下文（纯原版表达式）不产生任何断言行
        ArrayRegistry previous = ArrayRegistry.enter(ArrayRegistry.empty());
        try{
            check(!textOf(ExprCompiler.compile("x", "buf[i]", null, true)).contains("assertBounds"),
                "no array context must not emit bounds asserts");
        }finally{
            ArrayRegistry.restore(previous);
        }
    }

    /** 重复展开不重复插入；自动断言卡带固定前缀且经 write/read 往返后仍可识别。 */
    private static void unfoldAssertIdempotentAndMarked(){
        withRegistry("array buf cell1 0 8", () -> {
            List<ExprCompiler.Line> ops = ExprCompiler.compile("x", "buf[i]", null, true);
            List<LStatement> first = ExprHook.toStatements(ops, null, true);
            check(first.size() == 2, "first unfold should insert the assert");

            // 重复展开：插入点之前已有等价自动断言卡 → 不再插入
            // （真实展开路径传入的 preceding 就是链前的连续自动断言卡）
            List<LStatement> second = ExprHook.toStatements(ops, Arrays.<LStatement>asList(first.get(0)), true);
            check(second.size() == 1 && second.get(0) instanceof ReadStatement,
                "repeat unfold must not duplicate the auto assert");

            // 用户手写断言卡（无前缀）不参与去重：自动断言照常插入
            SugarAsserts.AssertBoundsCard manual = new SugarAsserts.AssertBoundsCard();
            manual.value = "i";
            check(!ExprHook.isAutoAssert(manual), "default assert card must not be treated as auto");
            List<LStatement> manualPreceding = new ArrayList<>();
            manualPreceding.add(manual);
            check(ExprHook.toStatements(ops, manualPreceding, true).size() == 2,
                "manual assert card must not suppress the auto insert");

            // 折叠清理：链前连续的自动断言卡被识别（折叠时移除、下次展开重建），
            // 用户手写卡片不参与
            check(ExprHook.trailingAutoAsserts(Arrays.asList(first.get(0))).size() == 1,
                "fold cleanup must recognize the trailing auto assert");
            check(ExprHook.trailingAutoAsserts(Arrays.asList(manual)).isEmpty(),
                "fold cleanup must not treat a manual assert card as auto");
            check(ExprHook.trailingAutoAsserts(Arrays.asList(manual, first.get(0))).size() == 1,
                "the trailing auto run must stop at the manual card");

            // 标记经 write/read 往返后仍可识别（存档 / 载体路径）
            StringBuilder text = new StringBuilder();
            first.get(0).write(text);
            check(text.toString().startsWith("assertBounds integer ~ 0 lessThanEq i lessThanEq 7 \"ls-auto: "),
                "auto assert wire format drifted: " + text);
            Seq<LStatement> parsed = LAssembler.read(text.toString(), true);
            check(parsed.size == 1 && ExprHook.isAutoAssert(parsed.get(0)),
                "auto marker must survive the write/read round trip");
            List<LStatement> afterRoundTrip = ExprHook.toStatements(ops, Arrays.<LStatement>asList(parsed.get(0)), true);
            check(afterRoundTrip.size() == 1, "round-tripped auto assert must still suppress the duplicate insert");

            // 编辑器路径的既有防御：ExprStatement.build() 跳过 RawLine（断言行）不崩溃
            ExprStatement broken = new ExprStatement();
            broken.dest = "x";
            broken.expr = "foo("; // 编译失败 → 回退 lastOps
            broken.lastOps = Arrays.asList(
                new ExprCompiler.AssertBoundsLine("integer", "~", "0", "lessThanEq", "i", "lessThanEq", "7", "\"x\""),
                new ExprCompiler.OpLine("add", "x", "1", "2"));
            check(broken.build(new LAssembler()) instanceof LExecutor.OpI,
                "ExprStatement.build must skip RawLine entries instead of crashing");
        });
    }

    /** 展开产物（自动断言卡 + 原版 read/write 卡）序列化后仍是合法糖文本：
     *  emit 构建保留断言、strip 构建剥离断言，载体往返通过。 */
    private static void unfoldProductCompilesAsSugar(){
        withRegistry("array buf cell1 0 8", () -> {
            List<LStatement> statements = ExprHook.toStatements(
                ExprCompiler.compile("x", "buf[i]", null, true), null, true);
            StringBuilder sugar = new StringBuilder();
            for(LStatement statement : statements){
                statement.write(sugar);
                sugar.append('\n');
            }
            String text = sugar.toString();
            check(text.contains("assertBounds integer ~ 0 lessThanEq i lessThanEq 7"),
                "assert card did not serialize:\n" + text);
            String emit = stripCompile(text, SugarCompiler.AssertEmit.emit);
            check(emit.contains("assertBounds integer ~ 0 lessThanEq i lessThanEq 7 \"ls-auto: array 'buf' index out of bounds (0..7)\""),
                "emit build dropped the unfolded assert card:\n" + emit);
            check(emit.contains("read x cell1 i"), "emit build dropped the read card:\n" + emit);
            String strip = stripCompile(text, SugarCompiler.AssertEmit.strip);
            check(!strip.contains("assertBounds"), "strip build leaked the assert card:\n" + strip);
            check(strip.contains("read x cell1 i"), "strip build dropped the read card:\n" + strip);
            check(SugarCompiler.verifyRestore(compile(text, SugarCompiler.AssertEmit.emit), text),
                "verifyRestore rejected the unfolded emit product");
        });
    }

    /** 自动断言卡位于链内（下标计算之后、read/write 之前），引用链内临时变量但不构成
     *  "链外读取"（否则带临时下标的链永远折不回来）；链外语句引用链内临时变量仍然阻止折叠。 */
    private static void autoAssertDoesNotBlockFolding(){
        withRegistry("array buf cell1 10 8", () -> {
            List<ExprCompiler.Line> emitOps = ExprCompiler.compile("x", "buf[i+1]", null, true);
            List<LStatement> canvas = ExprHook.toStatements(emitOps, null, true);
            // [op add _0 i 1, A(_0), op add _0 10 _0, read x cell1 _0]
            check(canvas.size() == 4, "expected one assert card plus three instructions, got " + canvas.size());
            check(canvas.get(1) instanceof SugarAsserts.AssertBoundsCard,
                "assert card should sit after the subscript computation");
            StringBuilder assertText = new StringBuilder();
            canvas.get(1).write(assertText);
            check(assertText.toString().contains("lessThanEq _0 lessThanEq 7"),
                "the auto assert should reference the chain temp: " + assertText);

            List<ExprCompiler.Line> chainOps = ExprCompiler.compile("x", "buf[i+1]", null, false);
            check(!ExprHook.hasExternalReads(canvas, 0, canvas.size(), chainOps),
                "auto assert inside the chain must not block folding");

            // 链外语句引用链内临时变量仍然阻止折叠
            List<LStatement> external = new ArrayList<>();
            SugarAsserts.AssertBoundsCard manual = new SugarAsserts.AssertBoundsCard();
            manual.value = "_0";
            external.add(manual);
            external.addAll(canvas.subList(2, canvas.size()));
            check(ExprHook.hasExternalReads(external, 1, external.size(), chainOps),
                "external read of a chain temp must block folding");
        });
    }

    private static final Set<String> vanillaOpcodes = new HashSet<>(Arrays.asList(
        "noop", "read", "write", "draw", "print", "printchar", "format", "drawflush", "printflush",
        "getlink", "control", "radar", "sensor", "set", "op", "select", "wait", "stop", "lookup",
        "packcolor", "unpackcolor", "end", "jump", "ubind", "ucontrol", "uradar", "ulocate",
        "query", "getblock", "setblock", "spawn", "bullet", "status", "weathersense", "weatherset",
        "spawnwave", "setrule", "message", "cutscene", "effect", "explosion", "setrate", "fetch",
        "sync", "clientdata", "getflag", "setflag", "setprop", "playsound", "playmusic",
        "setmarker", "makemarker", "localeprint"
    ));

    // ===== helpers =====

    /** Compiles one program with an explicit assertion-emission shape. */
    private static String compile(String sugar, SugarCompiler.AssertEmit emit){
        return SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
            SugarCompiler.SwitchStrategy.auto, emit);
    }

    /** Compiles and strips the marker comment block (the executable product). */
    private static String stripCompile(String sugar, SugarCompiler.AssertEmit emit){
        return SugarCompiler.stripMarkers(compile(sugar, emit));
    }

    /** Enters a strict registry built from the given declaration card(s); try/finally paired. */
    private static void withRegistry(String declarations, Runnable body){
        ArrayRegistry registry = ArrayRegistry.compileRegistry(LAssembler.read(declarations, true), null);
        ArrayRegistry previous = ArrayRegistry.enter(registry);
        try{
            body.run();
        }finally{
            ArrayRegistry.restore(previous);
        }
    }

    /** Lines of one compiled expression as mlog text (what unfold would insert / write() outputs). */
    private static String textOf(List<ExprCompiler.Line> ops){
        StringBuilder out = new StringBuilder();
        for(int i = 0; i < ops.size(); i++){
            if(i > 0) out.append('\n');
            out.append(ops.get(i).toText());
        }
        return out.toString();
    }

    /** What the editor's unfold inserts for one ExprStatement (same path as write()). */
    private static String writeOf(String dest, String expr){
        ExprStatement statement = new ExprStatement();
        statement.dest = dest;
        statement.expr = expr;
        StringBuilder out = new StringBuilder();
        statement.write(out);
        return out.toString();
    }

    private static List<ExprCompiler.Line> compileLines(String dest, String expr){
        return ExprCompiler.compile(dest, expr);
    }

    /** Folds a text chain the way ExprHook.foldAll feeds canvas cards to the rebuilder:
     *  read/write/op lines are parsed back into Lines, then rebuild (read chains) or
     *  rebuildAssignment (write chains) folds registry-matching lines to subscripts. */
    private static String foldBack(String chainText){
        List<ExprCompiler.Line> ops = new java.util.ArrayList<>();
        for(String line : chainText.replace("\r\n", "\n").split("\n", -1)){
            String t = line.trim();
            if(t.isEmpty()) continue;
            String[] parts = t.split("\\s+");
            if(t.startsWith("read ")){
                check(parts.length == 4, "bad read line: " + t);
                ops.add(new ExprCompiler.ReadLine(parts[1], parts[2], parts[3]));
            }else if(t.startsWith("sensor ")){
                check(parts.length == 4, "bad sensor line: " + t);
                ops.add(new ExprCompiler.SensorLine(parts[1], parts[2], parts[3]));
            }else if(t.startsWith("op ")){
                check(parts.length == 5, "bad op line: " + t);
                ops.add(new ExprCompiler.OpLine(parts[1], parts[2], parts[3], parts[4]));
            }else if(t.startsWith("write ")){
                check(parts.length == 4, "bad write line: " + t);
                ops.add(new ExprCompiler.WriteLine(parts[1], parts[2], parts[3]));
            }else{
                check(false, "fold helper cannot parse line: " + t);
            }
        }
        if(!ops.isEmpty() && ops.get(ops.size() - 1) instanceof ExprCompiler.WriteLine){
            String[] pair = ExprCompiler.rebuildAssignment(ops);
            return pair == null ? null : pair[0] + " = " + pair[1];
        }
        String expr = ExprCompiler.rebuild(ops);
        if(expr == null) return null;
        // chain dest = the target variable of the last line ("op <op> <dest> a b",
        // "sensor <dest> from type", "read <dest> memory address")
        String[] lines = chainText.trim().replace("\r\n", "\n").split("\n", -1);
        String[] parts = lines[lines.length - 1].trim().split("\\s+");
        String dest = parts[0].equals("op") ? parts[2] : parts[1];
        return dest + " = " + expr;
    }

    private static void checkCompileThrows(String sugar, String what){
        try{
            SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, SugarFunctions.library(), null,
                SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        }catch(RuntimeException e){
            return; // expected: strict registry validation aborted the compile
        }
        check(false, "compile should have failed (" + what + "): " + sugar);
    }

    private static void checkThrows(Runnable body, String what){
        try{
            body.run();
        }catch(ExprCompiler.ParseException e){
            return; // expected: expression-layer array error
        }
        check(false, "expression should have failed (" + what + ")");
    }

    /** Compiles one explicit-overload program and returns its stripped mlog lines. */
    private static Seq<String> seq(String mlog){
        Seq<String> result = new Seq<>();
        for(String line : mlog.replace("\r\n", "\n").split("\n", -1)) result.add(line.trim());
        return result;
    }

    private static boolean hasLine(Seq<String> lines, String expected){
        return lines.contains(expected);
    }

    private static void checkLine(String expected, String actual){
        check(expected.equals(actual), "mlog mismatch\n  expected: " + expected + "\n  actual:   " + actual);
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
