package mindustry.logic;

import arc.struct.Seq;
import logicsugar.assist.expr.ArrayRegistry;
import logicsugar.assist.expr.ExprCompiler;
import logicsugar.assist.expr.ExprStatement;
import mindustry.Vars;
import mindustry.logic.SugarStatements.ArrayStatement;

import java.util.List;

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

    // ===== helpers =====

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
