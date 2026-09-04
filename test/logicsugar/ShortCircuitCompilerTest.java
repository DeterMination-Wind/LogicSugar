package logicsugar;

import logicsugar.assist.expr.ShortCircuitCompiler;

/** Main-based tests for the short-circuit predicate parser and lowering. */
public final class ShortCircuitCompilerTest{
    private ShortCircuitCompilerTest(){}

    public static void main(String[] args){
        String and = ShortCircuitCompiler.lower("a && b", "TRUE", "FALSE", "__test_");
        check(and.contains("jump __test_and_0 notEqual a 0") && and.contains("jump FALSE always x false")
                && and.contains("jump TRUE notEqual b 0"),
            "AND did not lower to conditional jumps: " + and);
        check(and.indexOf("a 0") < and.indexOf("b 0"), "AND operand order changed: " + and);

        String or = ShortCircuitCompiler.lower("a || b", "TRUE", "FALSE", "__test_");
        check(or.contains("jump TRUE notEqual a 0") && or.contains("jump TRUE notEqual b 0"),
            "OR did not preserve its true target: " + or);
        check(or.contains("__test_or_0:"), "OR continuation label missing: " + or);

        String nested = ShortCircuitCompiler.lower("a && (b || c)", "TRUE", "FALSE", "__test_");
        check(nested.indexOf("a 0") < nested.indexOf("b 0")
                && nested.indexOf("b 0") < nested.indexOf("c 0"),
            "nested short-circuit order changed: " + nested);

        ShortCircuitCompiler.Predicate strict = ShortCircuitCompiler.parse("a === b");
        String strictNegated = ShortCircuitCompiler.lower(strict.negate(), "TRUE", "FALSE", "__test_");
        check(strictNegated.contains("strictEqual"), "strict equality negation was made lossy: " + strictNegated);

        check(ShortCircuitCompiler.tryParse("a &&").isEmpty(), "malformed predicate was accepted");
        System.out.println("ShortCircuitCompiler test passed.");
    }

    private static void check(boolean value, String message){
        if(!value) throw new AssertionError(message);
    }
}
