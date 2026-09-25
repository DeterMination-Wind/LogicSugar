package mindustry.logic;

/**
 * Main-based regression tests for the vanilla-mlog -> Sugar reverse view.
 * The project uses JavaExec self-tests rather than the JUnit runner, so this class follows
 * the same convention as {@code IfElseCompileTest} and {@code SugarCompilerSelfTest}.
 */
public final class SugarDecompilerTest{
    private SugarDecompilerTest(){}

    public static void main(String[] args){
        SugarStatements.installParsers();
        plainVanillaRoundTrip();
        ifRoundTrip();
        ifElseAndElifRoundTrip();
        elifChainOfThreeRoundTrip();
        whileRoundTrip();
        forRoundTrip();
        switchRoundTrip();
        switchFallthroughRoundTrip();
        switchTableRoundTrip();
        threadedSwitchTableRoundTrip();
        switchDefaultCaseRoundTrip();
        rawJumpTableRoundTrip();
        handWrittenProgramWithoutEntrySkipRecovers();
        numericJumpChainIsThreadedForTheGate();
        realWorldJumpTableRecovers();
        editorOpensHandWrittenProgramsAsSugar();
        nestedRoundTrip();
        metadataAndLineEndings();
        malformedInputIsPreserved();
        strictEqualityDoesNotCrash();
        expressionControlFlowIsPreserved();
        eagerVanillaConditionIsRecovered();
        shortCircuitExprRoundTrip();
        nestedShortCircuitRoundTrip();
        deepShortCircuitTreeRoundTrip();
        singleAtomShortCircuitRoundTrip();
        notWrappedShortCircuitRoundTrip();
        shortCircuitLoopRoundTrip();
        shortCircuitWhileContinueRoundTrip();
        switchStrategyMatrixRoundTrip();
        functionControlFlowIsPreserved();
        tildeExpressionEscapingSurvivesRecovery();
        quotedEscapeIsSelfInverse();
        deletedCarrierDegradesToInference();
        staleCarrierRecoversFromInstructions();
        dynamicCounterStaysFlat();
        entrySkipIsRecognisedOnlyAtTheEndOfMain();
        functionZoneViolationSkipsRecovery();
        functionStructuresSurviveZoneCheck();
        System.out.println("LogicSugar decompiler self-test passed.");
    }

    private static void plainVanillaRoundTrip(){
        String source = "set x 1\nprint x\n";
        SugarDecompiler.Result result = SugarDecompiler.decompile(source);
        check(result.verified, "plain vanilla was not verified");
        check(result.sugar.equals(source), "plain vanilla changed");
    }

    private static void ifRoundTrip(){
        assertCompiled("ifbegin x greaterThan 0 2\nset y 1\nblockend\nprint y\n", "ifbegin");
    }

    private static void ifElseAndElifRoundTrip(){
        assertCompiled("ifbegin x greaterThan 0 6\nset y 1\nelif x lessThan 0\nset y 2\nelse\nset y 3\nblockend\nprint y\n", "elif");
    }

    private static void elifChainOfThreeRoundTrip(){
        assertCompiled("ifbegin x greaterThan 2 8\nset y 1\nelif x equal 1\nset y 2\nelif x lessThan -5\nset y 3\nelse\nset y 4\nblockend\nprint y\n", "elif");
    }

    private static void whileRoundTrip(){
        assertCompiled("whilebegin x greaterThan 0 2\nset x 0\nblockend\nprint x\n", "whilebegin");
    }

    private static void forRoundTrip(){
        assertCompiled("forbegin i 0 1 lessThan 3 2\nset x i\nblockend\nprint x\n", "forbegin");
    }

    private static void switchRoundTrip(){
        assertCompiled("switchbegin x 7\ncase 1\nset y 1\nbreak\ncase 2\nset y 2\nbreak\nblockend\nprint y\n", "switchbegin");
    }

    private static void switchFallthroughRoundTrip(){
        // case 1 has no break: its body must flow into case 2. Either a structured recovery
        // that verifies, or the safe flat fallback is acceptable — losing code is not.
        String source = "switchbegin x 6\ncase 1\nset y 1\ncase 2\nset y 2\nbreak\nblockend\nprint y\n";
        String compiled = SugarCompiler.compile(source);
        SugarDecompiler.Result result = SugarDecompiler.decompile(stripGenerated(compiled));
        check(result.sugar.contains("set y 1") && result.sugar.contains("set y 2"),
            "switch fallthrough lost statements: " + result.sugar);
        check(result.sugar.contains("case 1") && result.sugar.contains("case 2"),
            "switch fallthrough lost case labels: " + result.sugar);
    }

    /** A duplication-heavy integer switch selects the jump table; duplicate empty case labels
     *  are intentionally present so recovery can preserve a table-winning cost without adding
     *  executable instructions. */
    private static void switchTableRoundTrip(){
        String source = tableSource(false);
        String compiled = SugarCompiler.compile(source);
        String raw = stripGenerated(compiled);
        check(raw.contains("op add @counter @counter x"), "table source did not compile to a jump table");
        SugarDecompiler.Result result = SugarDecompiler.decompile(raw);
        check(result.verified, "jump-table candidate did not recompile identically: " + result.notes);
        check(result.structured > 0 && result.sugar.contains("switchbegin")
            && result.sugar.contains("case 0") && result.sugar.contains("case 1"),
            "jump-table switch was not recovered: " + result.sugar);
    }

    /** The same table at the end of a normal-mode main program has its default/hole rows
     *  threaded past the exit label into __ls_end. Recovery must still pass the verify gate. */
    private static void threadedSwitchTableRoundTrip(){
        String source = tableSource(true);
        String compiled = SugarCompiler.compile(source, SugarCompiler.FuncMode.normal);
        String raw = stripGenerated(compiled);
        check(raw.contains("jump __ls_end always x false"), "threaded table fixture has no hoist exit jump");
        check(!raw.contains("jump __ls_stmt_" + switchDestForThreadedSource() + " always x false"),
            "threaded table retained a default-label jump:\n" + raw);
        SugarDecompiler.Result result = SugarDecompiler.decompile(raw);
        check(result.verified, "threaded jump-table candidate did not recompile identically: " + result.notes);
        check(result.structured > 0 && result.sugar.contains("switchbegin")
            && result.sugar.contains("case 0") && result.sugar.contains("case 1"),
            "threaded jump-table switch was not recovered: " + result.sugar);
    }

    /** Builds the same table shape used by the compiler tests. With a function prefix, the
     *  switch is the final main structure so its default label is threaded into __ls_end. */
    private static String tableSource(boolean withFunction){
        StringBuilder body = new StringBuilder();
        body.append("case 0\nprint zero\nbreak\n");
        body.append("case 1\nprint one\nbreak\n");
        for(int i = 0; i < 8; i++) body.append("case 0\ncase 1\n");
        int bodyLines = body.toString().split("\\n", -1).length - 1;
        // The blockend follows the switch header and all body statements. In the function
        // fixture the header starts at source index 4, so its body starts at index 5.
        int switchDest = withFunction ? 5 + bodyLines : 1 + bodyLines;
        StringBuilder source = new StringBuilder();
        if(withFunction){
            source.append("funcdef f ~ 2\nset flag 1\nblockend\nfunccall f \"\" ~\n");
        }
        source.append("switchbegin x ").append(switchDest).append('\n').append(body)
            .append("blockend\n");
        return source.toString();
    }

    /** Default-label statement index in the function fixture: switch begins at 4 and the
     *  generated body has 22 statements, so its blockend is 27 and the label is stmt_28. */
    private static int switchDestForThreadedSource(){
        return 28;
    }

    /** A {@code default} case is the target of the table's hole rows (and of the bounds guards),
     *  so the same source round-trips through the gate with the default card intact. The repeated
     *  case labels keep the table the cheaper lowering, like {@link #tableSource}. */
    private static void switchDefaultCaseRoundTrip(){
        StringBuilder body = new StringBuilder();
        body.append("case 0\nprint zero\nbreak\n");
        body.append("default\nprint other\nbreak\n");
        for(int i = 0; i < 4; i++) body.append("case 0\n");
        int bodyLines = body.toString().split("\\n", -1).length - 1;
        String sugar = "switchbegin x " + (1 + bodyLines) + "\n" + body + "blockend\n";
        String raw = stripGenerated(SugarCompiler.compile(sugar));
        check(raw.contains("op add @counter @counter x"),
            "default-case fixture did not lower to a jump table:\n" + raw);
        check(raw.contains("__ls_default_"), "default case got no label of its own:\n" + raw);
        SugarDecompiler.Result result = SugarDecompiler.decompile(raw);
        check(result.verified, "default-case candidate did not recompile identically: " + result.notes);
        check(result.structured > 0 && result.sugar.contains("switchbegin")
            && result.sugar.contains("default"),
            "default case was not recovered: " + result.sugar);
    }

    /**
     * A guard-less {@code @counter} jump table — the shape hand-written and third-party programs
     * use — recovers as a raw-table switch: the dispatch, the slot rows and the holes' shared
     * target all have to come back, which is what makes the card a lossless replacement for the
     * rows. The program deliberately has no entry skip and no carrier.
     */
    private static void rawJumpTableRoundTrip(){
        String raw = "set v 0\n"
            + "op add @counter @counter v\n"
            + "jump 5 always x false\n"
            + "jump 4 always x false\n"
            + "jump 0 always x false\n"
            + "print \"a\"\n"
            + "jump 0 always x false\n"
            + "print \"end\"\n";
        SugarDecompiler.Result result = SugarDecompiler.decompile(raw);
        check(result.verified, "raw jump table was not verified: " + result.notes);
        check(result.structured > 0 && result.sugar.contains("switchbegin v") && result.sugar.contains("raw"),
            "raw jump table did not recover as a raw switch: " + result.sugar);
        check(result.sugar.contains("\ndefault\n"),
            "the holes' shared target did not recover as the default case: " + result.sugar);
        // The card has to reproduce the program, not rewrite it: compiling the recovered source
        // yields the same instruction stream once jump chains are normalized.
        check(productStream(result.sugar).equals(inputStream(raw)),
            "the recovered raw table does not recompile to the original program:\n"
                + productStream(result.sugar));
    }

    /** The recovered source's product in the gate's comparison space: carriers and marker blocks
     *  stripped, then parsed and written back by the vanilla assembler (labels dropped, jumps
     *  resolved to instruction indices). */
    private static String productStream(String sugar){
        String produced = SugarCompiler.compileWithoutEntrySkip(sugar, SugarCompiler.FuncMode.normal,
            SugarFunctions.library(), null, SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip, true);
        return LAssembler.write(LAssembler.read(stripGenerated(produced), true));
    }

    /** A vanilla input in the same space, threaded first: the compiler threads its own output,
     *  so unconditional-jump chains collapse on the product but not on the input. */
    private static String inputStream(String vanilla){
        return LAssembler.write(LAssembler.read(
            stripGenerated(SugarCompiler.threadNumericJumpTargets(vanilla)), true));
    }

    /**
     * What the editor actually opens with, decided by {@code SugarDecompiler.openingSource}.
     *
     * <p>This is the reported bug's address: {@code verifyRestore} answers "true" for a program
     * with no carrier (nothing to verify), and the dialog read that as "trusted stored sugar", so
     * it loaded hand-written and third-party programs as their own source text and never asked
     * the decompiler — the Sugar view existed in tests but no user could reach it. The real-world
     * fixture is asserted here for both editor privilege levels, since an ordinary processor
     * edits with {@code privileged == false} while the earlier tests decompile privileged.</p>
     */
    private static void editorOpensHandWrittenProgramsAsSugar(){
        String raw;
        try{
            raw = logicsugar.SourceNails.readSource("test/fixtures/realworld-jump-table.mlog");
        }catch(java.io.IOException exception){
            throw new AssertionError("real-world fixture could not be read: " + exception.getMessage());
        }
        for(boolean privileged : new boolean[]{true, false}){
            SugarDecompiler.Opening opening = SugarDecompiler.openingSource(raw, privileged, false);
            check(opening.mode == SugarDecompiler.OpeningMode.inferred,
                "the editor opened a hand-written program as " + opening.mode
                    + " (privileged=" + privileged + ") instead of an inferred Sugar view");
            check(opening.source.contains("switchbegin id") && opening.source.contains(" raw"),
                "the opened source lost the recovered raw switch (privileged=" + privileged + ")");
            check(!opening.source.equals(raw), "the opened source is still the vanilla program");
        }

        // Sugar this mod saved keeps its stored source, and a library file keeps its text.
        String stored = SugarCompiler.compile("ifbegin x greaterThan 0 4\nset y 1\nelse\nset y 2\nblockend\nprint y\n");
        SugarDecompiler.Opening storedOpening = SugarDecompiler.openingSource(stored, true, false);
        check(storedOpening.mode == SugarDecompiler.OpeningMode.stored,
            "a saved program stopped using its stored sugar: " + storedOpening.mode);
        check(storedOpening.source.contains("ifbegin x greaterThan 0"),
            "the stored sugar source was not restored: " + storedOpening.source);

        String library = "funcdef f a 3\nreturn \"a + 1\"\nblockend\n";
        SugarDecompiler.Opening libraryOpening = SugarDecompiler.openingSource(library, false, true);
        check(libraryOpening.mode == SugarDecompiler.OpeningMode.stored,
            "library text was routed through inference: " + libraryOpening.mode);
        check(libraryOpening.source.contains("funcdef f"), "library text was rewritten");

        // Genuinely unstructured vanilla keeps the old raw behavior (and its notice).
        String plain = "set x 1\nprint x\n";
        check(SugarDecompiler.openingSource(plain, true, false).mode == SugarDecompiler.OpeningMode.raw,
            "an unstructured vanilla program should open as raw code");
    }

    /** Hand-written mlog is not produced by this compiler, so it carries neither the persistence
     *  carrier nor the trailing entry skip. Recovery must still accept it: the gate compiles the
     *  candidate in both skip eras exactly like {@code SugarCompiler.verifyLowering} does for
     *  stored saves. Before that, every structured candidate gained the skip and was rejected. */
    private static void handWrittenProgramWithoutEntrySkipRecovers(){
        String raw = "jump 3 lessThanEq x 0\nset y 1\nset z 2\nprint y\n";
        check(!raw.contains(SugarCompiler.entrySkipLine), "fixture unexpectedly carries the entry skip");
        SugarDecompiler.Result result = SugarDecompiler.decompile(raw);
        check(result.verified, "hand-written program was not verified: " + result.notes);
        check(result.structured > 0 && !"flat".equals(result.matchedMode),
            "hand-written program stayed flat: mode=" + result.matchedMode + " " + result.sugar);
        check(result.sugar.contains("ifbegin x greaterThan 0"),
            "hand-written if was not recovered: " + result.sugar);
    }

    /** Numeric jump targets are what hand-written programs use, and the compiler's lowering
     *  threads unconditional-jump chains. The gate has to compare in that same normal form, so a
     *  row that hops onto another unconditional jump reads as the jump it ends on. */
    private static void numericJumpChainIsThreadedForTheGate(){
        String chain = "set x 0\njump 3 always x false\nprint a\njump 4 always x false\nprint b\n";
        check("set x 0\njump 4 always x false\nprint a\njump 4 always x false\nprint b\n"
            .equals(SugarCompiler.threadNumericJumpTargets(chain)),
            "unconditional jump chain was not threaded: " + SugarCompiler.threadNumericJumpTargets(chain));
        // A cycle keeps its targets, exactly like the label-based pass.
        String cycle = "jump 1 always x false\njump 0 always x false\n";
        check(cycle.equals(SugarCompiler.threadNumericJumpTargets(cycle)), "cyclic chain was rewritten");
        // Conditional jumps are neither chain nodes nor rewritten lines.
        String conditional = "set x 0\njump 2 lessThan x 1\njump 0 always x false\n";
        check(conditional.equals(SugarCompiler.threadNumericJumpTargets(conditional)),
            "conditional jump was rewritten");
        // A destination outside the program is left alone rather than clamped.
        String outside = "set x 0\njump 9 always x false\nprint a\n";
        check(outside.equals(SugarCompiler.threadNumericJumpTargets(outside)),
            "out-of-range destination was rewritten");

        // End to end: the body's last statement hops onto another unconditional jump, so the
        // recovered candidate's product only matches the input through the threaded comparison.
        String raw = "set x 0\njump 4 lessThanEq x 0\nset y 1\njump 5 always x false\nprint y\njump 1 always x false\n";
        SugarDecompiler.Result result = SugarDecompiler.decompile(raw);
        check(result.verified, "jump-chain program was not verified: " + result.notes);
    }

    /**
     * The reported real-world program (655 instructions, third-party tool output, 251-slot
     * {@code @counter} jump table, no carrier and no entry skip): it must open as Sugar with the
     * table recovered as a raw switch instead of the flat vanilla view.
     */
    private static void realWorldJumpTableRecovers(){
        String raw;
        try{
            raw = logicsugar.SourceNails.readSource("test/fixtures/realworld-jump-table.mlog");
        }catch(java.io.IOException exception){
            throw new AssertionError("real-world fixture could not be read: " + exception.getMessage());
        }
        check(!raw.contains(SugarCompiler.entrySkipLine), "fixture unexpectedly carries the entry skip");
        check(raw.contains("op add @counter @counter id"), "fixture lost its jump-table dispatch");

        SugarDecompiler.Result result = SugarDecompiler.decompile(raw);
        check(result.verified, "real-world program was not verified: " + result.notes);
        check(result.structured > 0 && !"flat".equals(result.matchedMode),
            "real-world program opened as flat vanilla: mode=" + result.matchedMode
                + " structured=" + result.structured);
        check(result.sugar.contains("switchbegin id") && result.sugar.contains(" raw"),
            "the 251-slot jump table was not recovered as a raw switch");
        check(result.sugar.contains("\ndefault\n"),
            "the table's holes did not recover as the default case");
        check(result.sugar.contains("ifbegin block notEqual null"),
            "the enclosing link loop guard was not recovered");
        // The recovered source has to be a faithful replacement: its product must match the
        // threaded input stream exactly (the gate already proved it, this pins it independently).
        check(productStream(result.sugar).equals(inputStream(raw)),
            "the recovered raw table does not recompile to the original program");
    }

    private static void nestedRoundTrip(){
        assertCompiled("whilebegin x greaterThan 0 5\nifbegin y equal 1 3\nset z 1\nblockend\nset x 0\nblockend\nprint z\n", "whilebegin");
    }

    private static void metadataAndLineEndings(){
        String source = "set x 1\nprint x\n";
        String compiled = SugarCompiler.compile(source);
        SugarDecompiler.Result result = SugarDecompiler.decompile(stripGenerated(compiled));
        check(result.verified, "carrier-bearing code was not accepted");
        check(result.sugar.equals(source), "carrier-bearing code did not restore through the decompiler");

        String carrierLike = "set __ls_sugar \"ordinary value\"\nset x 1\n";
        SugarDecompiler.Result carrierResult = SugarDecompiler.decompile(carrierLike);
        check(carrierResult.sugar.contains("set __ls_sugar \"ordinary value\""), "ordinary carrier-like variable was lost");

        SugarDecompiler.Result crOnly = SugarDecompiler.decompile("set x 1\rset y 2\r");
        check(crOnly.sugar.contains("set x 1") && crOnly.sugar.contains("set y 2"), "CR-only input was not normalized");
    }

    private static void malformedInputIsPreserved(){
        String source = "unknown-op value\nset x 1\n";
        SugarDecompiler.Result result = SugarDecompiler.decompile(source);
        check(!result.verified, "unknown opcode was falsely verified");
        check(result.sugar.equals(source), "unknown opcode was rewritten");
    }

    private static void strictEqualityDoesNotCrash(){
        String source = "jump 2 strictEqual x y\nset z 1\nprint z\n";
        SugarDecompiler.Result result = SugarDecompiler.decompile(source);
        check(result.verified, "strict equality jump did not preserve vanilla code");
        check(result.sugar.contains("strictEqual"), "strict equality was changed");
    }

    private static void expressionControlFlowIsPreserved(){
        String source = "ifbegin expr \"x > 0 && ready\" 2\nset z 1\nblockend\nprint z\n";
        String compiled = SugarCompiler.compile(source);
        SugarDecompiler.Result result = SugarDecompiler.decompile(SugarDecompiler.stripMetadata(compiled));
        check(result.verified, "expression control flow was not verified");
        check(!result.sugar.contains("ifbegin __ls_cond_"), "expression condition was misidentified as a native if");
    }

    private static void eagerVanillaConditionIsRecovered(){
        String raw = "op lessThan x a b\n"
            + "op greaterThanEq y c d\n"
            + "op land cond x y\n"
            + "jump 5 notEqual cond false\n"
            + "set hit 1\n"
            + "print hit\n";
        SugarDecompiler.Result result = SugarDecompiler.decompile(raw);
        check(result.verified, "vanilla eager condition was not verified");
        check(result.sugar.contains("op land cond x y"), "eager condition unexpectedly hid user value");
        check(!result.sugar.contains("exprsc"), "eager land was misclassified as short-circuit");
    }

    private static void shortCircuitExprRoundTrip(){
        String sugar = "ifbegin exprsc \"a && b\" 4\n"
            + "set hit 1\n"
            + "else\n"
            + "set hit 0\n"
            + "blockend\n";
        String raw = stripGenerated(SugarCompiler.compile(sugar));
        SugarDecompiler.Result result = SugarDecompiler.decompile(raw);
        check(result.verified, "short-circuit expression was not verified");
        check(result.sugar.contains("exprsc"), "short-circuit expression was not recovered: " + result.sugar);
        check(SugarCompiler.compile(result.sugar).contains("op") == false
            || SugarCompiler.compile(result.sugar).contains("jump"),
            "short-circuit condition did not lower to jumps");
    }

    /** Nested boolean trees recover from their lowered pairs: the guard parser rebuilds the
     *  tree from pair destinations instead of matching one fixed instruction layout. */
    private static void nestedShortCircuitRoundTrip(){
        assertCompiled("ifbegin exprsc \"a && (b || c)\" 4\n"
            + "set hit 1\n"
            + "else\n"
            + "set hit 0\n"
            + "blockend\n", "exprsc");
    }

    /** Left-deep chains and Or-under-And trees exercise the other split branch: an And puts
     *  its internal continuation on the true edge, an Or on the false edge. */
    private static void deepShortCircuitTreeRoundTrip(){
        assertCompiled("ifbegin exprsc \"a && b && c\" 4\n"
            + "set hit 1\n"
            + "else\n"
            + "set hit 0\n"
            + "blockend\n", "exprsc");
        assertCompiled("ifbegin exprsc \"(a || b) && c\" 4\n"
            + "set hit 1\n"
            + "else\n"
            + "set hit 0\n"
            + "blockend\n", "exprsc");
    }

    /** A single-atom guard (two instructions) has no second pair and, without an else branch,
     *  no trailing exit jump either — previously this shape fell back to flat vanilla. */
    private static void singleAtomShortCircuitRoundTrip(){
        assertCompiled("ifbegin exprsc \"a < b\" 2\nset hit 1\nblockend\nprint hit\n", "exprsc");
    }

    /** A top-level Not swaps the pair destinations instead of emitting extra instructions;
     *  the fallback therefore re-enters the body and the conditional jump targets the exit. */
    private static void notWrappedShortCircuitRoundTrip(){
        assertCompiled("ifbegin exprsc \"!(a < b)\" 2\nset hit 1\nblockend\nprint hit\n", "exprsc");
    }

    /** whilebegin/forbegin lower their exprsc condition to the same guard shape, with the
     *  loop back edge re-entering the guard head; forbegin keeps its initializer and step. */
    private static void shortCircuitLoopRoundTrip(){
        assertCompiled("whilebegin exprsc \"a < b\" 2\nset x 0\nblockend\nprint x\n", "exprsc");
        assertCompiled("forbegin i 0 1 exprsc \"i < 3\" 2\nprint i\nblockend\nprint x\n", "forbegin");
    }

    /** continue lowers to an always-jump into the guard head — an interior back edge that is
     *  a legitimate part of a while loop and must recover as a continue statement, not
     *  disqualify the loop frame. */
    private static void shortCircuitWhileContinueRoundTrip(){
        assertCompiled("whilebegin exprsc \"a < b\" 3\nset x 0\ncontinue\nblockend\nprint x\n", "whilebegin");
    }

    /** A program saved under chainOnly must survive a decompiler running with the local
     *  default (auto): the verify gate now compiles candidates across the whole
     *  function-mode x switch-strategy matrix instead of only the configured lowering. */
    private static void switchStrategyMatrixRoundTrip(){
        String source = tableSource(false);
        String chainSaved = SugarCompiler.compile(source, SugarCompiler.FuncMode.inline,
            SugarFunctions.library(), null, SugarCompiler.SwitchStrategy.chainOnly);
        check(!chainSaved.contains("op add @counter @counter"),
            "chainOnly fixture did not lower to a comparison chain");
        SugarDecompiler.Result result = SugarDecompiler.decompile(stripGenerated(chainSaved));
        check(result.verified, "chain-saved switch did not verify under the strategy matrix: " + result.notes);
        check(result.sugar.contains("switchbegin"), "chain-saved switch was not recovered: " + result.sugar);
    }

    private static void functionControlFlowIsPreserved(){
        String source = "funcdef f a 2\nreturn \"a + 1\"\nblockend\nset x 3\nfunccall f \"x\" out\nprint out\n";
        String compiled = SugarCompiler.compile(source, SugarCompiler.FuncMode.normal);
        SugarDecompiler.Result result = SugarDecompiler.decompile(stripGenerated(compiled));
        check(result.verified, "function candidate did not recompile identically: " + result.notes);
        check(result.sugar.contains("funccall f") && result.sugar.contains("funcdef f"),
            "function constructs were not recovered: " + result.sugar);
        check(result.sugar.contains("return"), "function return was not recovered: " + result.sugar);
    }

    /** Unary operators in returned expressions are rebuilt symbolically (e.g. {@code ~a}
     *  becomes "not(a)"), so no quote ever reaches the decompiler's escaper through
     *  supported expressions — pin that faithful rebuild plus a fully verified recovery.
     *  The escaping rules themselves are guarded by {@link #quotedEscapeIsSelfInverse}. */
    private static void tildeExpressionEscapingSurvivesRecovery(){
        String source = "funcdef g a 2\nreturn \"~a\"\nblockend\nset x true\nfunccall g \"x\" out\nprint out\n";
        String compiled = SugarCompiler.compile(source, SugarCompiler.FuncMode.normal);
        SugarDecompiler.Result result = SugarDecompiler.decompile(stripGenerated(compiled));
        check(result.verified, "tilde return expression did not recompile identically: " + result.notes);
        check(result.sugar.contains("funcdef g") && result.sugar.contains("funccall g")
            && result.sugar.contains("return \"not(a)\""),
            "recovered function body did not faithfully rebuild the unary return: " + result.sugar);
    }

    /** Locks the invariant between both sides of the quoted-token escaping now that the
     *  decompiler calls {@link SugarStatements#escapeQuoted} directly. */
    private static void quotedEscapeIsSelfInverse(){
        String tricky = "say ~~ then \"quoted\" ~q and lone tilde ~ end";
        check(SugarStatements.unescapeQuoted(SugarStatements.escapeQuoted(tricky)).equals(tricky),
            "escapeQuoted/unescapeQuoted are not inverses");
    }

    /** Hand-deleting the persistence carrier (an external edit) must drop the program into
     *  the inference path instead of failing or pretending a verified carrier restore. */
    private static void deletedCarrierDegradesToInference(){
        String sugar = "ifbegin x greaterThan 0 2\nset y 1\nblockend\nprint y\n";
        String compiled = SugarCompiler.compile(sugar);
        check(compiled.contains("set __ls_sugar "), "compiled code unexpectedly lost its carrier");
        StringBuilder mangled = new StringBuilder();
        for(String line : compiled.replace("\r\n", "\n").split("\n", -1)){
            if(!line.startsWith("set __ls_sugar ")) mangled.append(line).append('\n');
        }
        SugarDecompiler.Result result = SugarDecompiler.decompile(mangled.toString());
        check(result.verified, "carrier-stripped program was rejected outright: " + result.notes);
        check(!"carrier".equals(result.matchedMode), "deleted metadata must not look like a carrier restore");
        check(result.sugar.contains("ifbegin") && result.sugar.contains("print y"),
            "carrier-stripped program lost its logic: " + result.sugar);
    }

    /** Mirror of the reported scenario: the carrier line survives but the instructions were
     *  edited by another client, so the stored sugar source is stale. The structure must be
     *  recovered from the instruction stream, with the carrier demoted to untrusted
     *  metadata instead of blocking recompilation equality. */
    private static void staleCarrierRecoversFromInstructions(){
        String sugar = "forbegin i 0 1 lessThanEq 10 3\nprint i\nprintflush message1\nblockend\n";
        String compiled = SugarCompiler.compile(sugar);
        check(compiled.contains("set __ls_sugar "), "compiled code unexpectedly lost its carrier");
        // external edit: loop step changed from 1 to 2 in the instruction stream
        String edited = compiled.replace("op add i i 1", "op add i i 2");
        check(!edited.equals(compiled), "external edit did not change the program");
        // dialog-level restore must fail (stale source), so the decompiler is the recovery path
        check(!SugarCompiler.verifyRestore(edited, SugarCompiler.restore(edited)),
            "stale carrier unexpectedly verified");

        SugarDecompiler.Result result = SugarDecompiler.decompile(edited);
        check(result.verified, "externally edited program was rejected outright: " + result.notes);
        check(result.structured > 0 && result.sugar.contains("forbegin i 0 2"),
            "stale-carrier program did not recover its structure: " + result.sugar);
        check(!result.sugar.contains("__ls_sugar"), "stale carrier leaked into the recovered view");
    }

    /** Hand-written dynamic @counter dispatch is computed control flow the structured views
     *  cannot express. The triage must return the flat result directly: verified, unstructured,
     *  byte-identical to the input, with a note naming the offending instruction. */
    private static void dynamicCounterStaysFlat(){
        String source = "set target 0\nset @counter target\nprint x\n";
        SugarDecompiler.Result result = SugarDecompiler.decompile(source);
        check(result.verified, "dynamic @counter program was rejected: " + result.notes);
        check("flat".equals(result.matchedMode),
            "dynamic @counter program was not triaged flat: " + result.matchedMode);
        check(result.sugar.equals(source), "dynamic @counter program was rewritten: " + result.sugar);
        check(result.structured == 0, "dynamic @counter program claimed structure");
        check(result.notes.stream().anyMatch(note -> note.contains("@counter")),
            "dynamic @counter triage left no explanatory note: " + result.notes);
    }

    /**
     * The entry skip ({@link SugarCompiler#entrySkipLine}) is the compiler's own statement only
     * at the end of main, and that position is part of the shape: read on shape alone, any
     * author's {@code set @counter 0} -- a counter reset inside a loop -- would count as
     * known-safe and swallow the triage that keeps the structured views from burning their
     * recovery attempts on a program they cannot express.
     *
     * <p>Both compiled layouts have to keep reading as the compiler's: the skip sits directly
     * above the carriers, and with function bodies hoisted past the end of main there is also
     * one prelude jump above them. If either stops being recognised, every saved program starts
     * triaging to flat, so this pins the positions rather than the shapes alone.</p>
     */
    private static void entrySkipIsRecognisedOnlyAtTheEndOfMain(){
        // No hoisted bodies: the skip sits directly above the carriers.
        String plain = "ifbegin x greaterThan 0 4\nset y 1\nelse\nset y 2\nblockend\nprint y\n";
        SugarDecompiler.Result flat = SugarDecompiler.decompile(SugarCompiler.compile(plain));
        check(flat.notes.stream().noneMatch(note -> note.contains("dynamic @counter")),
            "a compiled program tripped the dynamic @counter triage: " + flat.notes);

        // Hoisted bodies: a prelude jump now sits between the skip and the carriers, and the
        // bodies it spans are reached from their call sites rather than from main.
        String withFunction = "set x 1\nfunccall f \"\" ~\nprint x\nfuncdef f ~ 5\nset inside 9\nblockend\n";
        SugarDecompiler.Result hoisted = SugarDecompiler.decompile(SugarCompiler.compile(withFunction));
        check(hoisted.notes.stream().noneMatch(note -> note.contains("dynamic @counter")),
            "a compiled program with a hoisted function tripped the dynamic @counter triage: "
                + hoisted.notes);

        // The same statement among ordinary statements is a computed jump, not the entry skip.
        String authored = "set x 1\nset @counter 0\nprint x\n";
        SugarDecompiler.Result triaged = SugarDecompiler.decompile(authored);
        check(triaged.verified, "mid-program @counter write was rejected: " + triaged.notes);
        check("flat".equals(triaged.matchedMode),
            "mid-program @counter write was not triaged flat: " + triaged.matchedMode);
        check(triaged.notes.stream().anyMatch(note -> note.contains("@counter")),
            "mid-program @counter triage left no explanatory note: " + triaged.notes);
    }

    /**
     * Hand-written mlog that mimics the compiler's function shapes (prelude + ret trampoline)
     * but breaks the zone closure invariants must skip function recovery entirely, with the
     * reason recorded in the notes, and stay faithful. The stray-jump fixture keeps a verified
     * if-structure around the region (the region itself is expressible), so the skip is proven
     * by the note plus the absence of function constructs; the escaping-jump fixture has no
     * expressible structure and re-verifies byte-identically as flat vanilla.
     */
    private static void functionZoneViolationSkipsRecovery(){
        // Stray jump from outside into the mimicked body zone [7, 9): a static jump entering
        // a function zone must be a call-prelude jump, which this one is not.
        String stray = "jump 7 equal x 1\n"
            + "set a 1\n"
            + "print a\n"
            + "set __ls_func_f_ret @counter\n"
            + "op add __ls_func_f_ret __ls_func_f_ret 2\n"
            + "jump 7 always x false\n"
            + "set b 2\n"
            + "print b\n"
            + "set @counter __ls_func_f_ret\n"
            + "print end\n";
        SugarDecompiler.Result result = SugarDecompiler.decompile(stray);
        check(result.verified, "stray-jump fixture was rejected: " + result.notes);
        check(result.notes.stream().anyMatch(note -> note.contains("function recovery skipped")),
            "stray-jump fixture did not skip function recovery: " + result.notes);
        check(!result.sugar.contains("funccall") && !result.sugar.contains("funcdef"),
            "stray-jump fixture still recovered function constructs: " + result.sugar);

        // Always jump from inside the mimicked body zone [4, 7) escaping to instruction 7:
        // compiled bodies only leave their zone through the final ret trampoline.
        String escape = "set __ls_func_f_ret @counter\n"
            + "op add __ls_func_f_ret __ls_func_f_ret 2\n"
            + "jump 4 always x false\n"
            + "set c 0\n"
            + "print c\n"
            + "jump 7 always x false\n"
            + "set @counter __ls_func_f_ret\n"
            + "print done\n";
        SugarDecompiler.Result escapeResult = SugarDecompiler.decompile(escape);
        check(escapeResult.verified, "escaping-jump fixture was rejected: " + escapeResult.notes);
        check(escapeResult.sugar.equals(escape), "escaping-jump fixture was rewritten: " + escapeResult.sugar);
        check(escapeResult.notes.stream().anyMatch(note -> note.contains("function recovery skipped")),
            "escaping-jump fixture did not skip function recovery: " + escapeResult.notes);
    }

    /**
     * Real compiler output must never trip the zone closure check: switch guard/case edges and
     * loop back edges stay inside their function's zone, main reaches a body only through a
     * prelude jump, a nested call's prelude jump legitimately leaves the caller's zone (g's
     * body jumping to f's entry), and the jump-table dispatch is a known-safe @counter write.
     * All shapes must recover verified exactly as before the zone check existed.
     */
    private static void functionStructuresSurviveZoneCheck(){
        String switchSource = "funcdef f a 9\nswitchbegin a 8\ncase 1\nprint one\nbreak\ncase 2\nprint two\nbreak\nblockend\nblockend\nfunccall f \"1\" ~\nprint r\n";
        SugarDecompiler.Result switchResult = SugarDecompiler.decompile(
            stripGenerated(SugarCompiler.compile(switchSource, SugarCompiler.FuncMode.normal)));
        check(switchResult.verified, "switch-in-function candidate did not recompile identically: " + switchResult.notes);
        check(switchResult.sugar.contains("funcdef f") && switchResult.sugar.contains("switchbegin"),
            "switch inside a function was not recovered: " + switchResult.sugar);

        String loopSource = "funcdef f a 7\nwhilebegin a lessThan 3 6\nifbegin a equal 1 4\nbreak\nblockend\nop add a a 1\nblockend\nblockend\nfunccall f \"0\" ~\nprint r\n";
        SugarDecompiler.Result loopResult = SugarDecompiler.decompile(
            stripGenerated(SugarCompiler.compile(loopSource, SugarCompiler.FuncMode.normal)));
        check(loopResult.verified, "loop-in-function candidate did not recompile identically: " + loopResult.notes);
        check(loopResult.sugar.contains("funcdef f") && loopResult.sugar.contains("whilebegin"),
            "loop inside a function was not recovered: " + loopResult.sugar);

        String nestedSource = "funcdef f a 2\nreturn \"a + 1\"\nblockend\n"
            + "funcdef g b 5\nreturn \"f(b) + 1\"\nblockend\n"
            + "set x 1\nfunccall g \"x\" out\nprint out\n";
        SugarDecompiler.Result nestedResult = SugarDecompiler.decompile(
            stripGenerated(SugarCompiler.compile(nestedSource, SugarCompiler.FuncMode.normal)));
        check(nestedResult.verified, "nested-call candidate did not recompile identically: " + nestedResult.notes);
        check(nestedResult.sugar.contains("funcdef g") && nestedResult.sugar.contains("funcdef f")
            && nestedResult.sugar.contains("funccall g") && nestedResult.sugar.contains("funccall f"),
            "nested function constructs were not recovered: " + nestedResult.sugar);

        StringBuilder tableBody = new StringBuilder();
        tableBody.append("case 0\nprint zero\nbreak\ncase 1\nprint one\nbreak\n");
        for(int i = 0; i < 8; i++) tableBody.append("case 0\ncase 1\n");
        String tableSource = "funcdef f a 25\nswitchbegin a 24\n" + tableBody + "blockend\nblockend\nfunccall f \"0\" ~\nprint r\n";
        String tableCompiled = stripGenerated(SugarCompiler.compile(tableSource, SugarCompiler.FuncMode.normal));
        check(tableCompiled.contains("op add @counter @counter"),
            "table-in-function fixture did not lower to a jump table");
        SugarDecompiler.Result tableResult = SugarDecompiler.decompile(tableCompiled);
        check(tableResult.verified, "table-in-function candidate did not recompile identically: " + tableResult.notes);
        check(tableResult.sugar.contains("funcdef f") && tableResult.sugar.contains("switchbegin"),
            "jump table inside a function was not recovered: " + tableResult.sugar);
    }

    private static String stripGenerated(String code){
        StringBuilder out = new StringBuilder();
        boolean marker = false;
        for(String line : code.replace("\r\n", "\n").split("\n", -1)){
            if(SugarCompiler.isMarkerBeginLine(line)){ marker = true; continue; }
            if(SugarCompiler.isMarkerEndLine(line)){ marker = false; continue; }
            if(marker || line.startsWith("set __ls_sugar \"") || line.startsWith("set __ls_lib \"")) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static void assertCompiled(String sugar, String expectedKeyword){
        String compiled = SugarCompiler.compile(sugar);
        String raw = stripGenerated(compiled);
        SugarDecompiler.Result result = SugarDecompiler.decompile(raw);
        check(result.verified, expectedKeyword + " candidate did not recompile identically: " + result.notes);
        check(result.sugar.contains(expectedKeyword), expectedKeyword + " was not recovered: " + result.sugar);
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
