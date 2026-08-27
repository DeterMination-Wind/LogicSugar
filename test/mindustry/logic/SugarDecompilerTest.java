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
        nestedRoundTrip();
        metadataAndLineEndings();
        malformedInputIsPreserved();
        strictEqualityDoesNotCrash();
        expressionControlFlowIsPreserved();
        functionControlFlowIsPreserved();
        tildeExpressionEscapingSurvivesRecovery();
        quotedEscapeIsSelfInverse();
        deletedCarrierDegradesToInference();
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

    private static String stripGenerated(String code){
        StringBuilder out = new StringBuilder();
        boolean marker = false;
        for(String line : code.replace("\r\n", "\n").split("\n", -1)){
            if(line.equals("# @logic-sugar-v1 begin")){ marker = true; continue; }
            if(line.equals("# @logic-sugar-v1 end")){ marker = false; continue; }
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
