package mindustry.logic;

import java.util.Optional;

/** Small main-based regression test for the dependency-free RecoveryPredicate model. */
public final class RecoveryPredicateTest{
    private RecoveryPredicateTest(){}

    public static void main(String[] args){
        exactComparisonNegation();
        strictEqualityStaysStrict();
        deMorganNegation();
        precedencePrinting();
        evaluationModesAffectCost();
        System.out.println("RecoveryPredicate test passed.");
    }

    private static void exactComparisonNegation(){
        check(RecoveryPredicate.exactNegationOperation("lessThan").equals(Optional.of("greaterThanEq")),
            "lessThan did not invert exactly");
        check(RecoveryPredicate.exactNegationOperation("!=").equals(Optional.of("equal")),
            "symbolic notEqual did not invert exactly");
        RecoveryPredicate.Atom atom = new RecoveryPredicate.Atom(RecoveryPredicate.Comparison.LESS_THAN, "a", "b");
        check(atom.exactNegation().isPresent(), "exact atom negation was missing");
        check(atom.exactNegation().get().print().equals("a >= b"), "exact atom negation printed incorrectly");
    }

    private static void strictEqualityStaysStrict(){
        RecoveryPredicate.Atom strict = new RecoveryPredicate.Atom("strictEqual", "a", "b");
        check(strict.exactNegation().isEmpty(), "strictEqual unexpectedly acquired a lossy inverse");
        RecoveryPredicate.Predicate negated = strict.applyNot();
        check(negated instanceof RecoveryPredicate.Not, "strictEqual negation was silently rewritten");
        check(negated.print().equals("!(a === b)"), "strictEqual negation did not preserve its operation");
        check(negated.applyNot().equals(strict), "double negation did not restore strictEqual");
    }

    private static void deMorganNegation(){
        RecoveryPredicate.Predicate a = new RecoveryPredicate.Atom("equal", "a", "1");
        RecoveryPredicate.Predicate b = new RecoveryPredicate.Atom("greaterThan", "b", "2");
        RecoveryPredicate.Predicate expression = new RecoveryPredicate.And(a, b,
            RecoveryPredicate.EvaluationMode.SHORT_CIRCUIT);
        RecoveryPredicate.Predicate negated = expression.applyNot();
        check(negated instanceof RecoveryPredicate.Or, "AND did not become OR under negation");
        check(negated.print().equals("a != 1 || b <= 2"), "De Morgan print was not stable: " + negated.print());
        check(negated.mode() == RecoveryPredicate.EvaluationMode.SHORT_CIRCUIT,
            "negation discarded evaluation mode");
        check(negated.applyNot().equals(expression), "double De Morgan negation changed the tree");
    }

    private static void precedencePrinting(){
        RecoveryPredicate.Predicate a = new RecoveryPredicate.Atom("equal", "a", "1");
        RecoveryPredicate.Predicate b = new RecoveryPredicate.Atom("lessThan", "b", "2");
        RecoveryPredicate.Predicate c = new RecoveryPredicate.Atom("greaterThan", "c", "3");
        RecoveryPredicate.Predicate expression = new RecoveryPredicate.Or(new RecoveryPredicate.And(a, b,
            RecoveryPredicate.EvaluationMode.EAGER), c, RecoveryPredicate.EvaluationMode.EAGER);
        check(expression.print().equals("a == 1 && b < 2 || c > 3"),
            "unnecessary or missing precedence parentheses: " + expression.print());

        RecoveryPredicate.Predicate nested = new RecoveryPredicate.And(a,
            new RecoveryPredicate.Or(b, c, RecoveryPredicate.EvaluationMode.EAGER),
            RecoveryPredicate.EvaluationMode.EAGER);
        check(nested.print().equals("a == 1 && (b < 2 || c > 3)"),
            "lower-precedence right child was not parenthesized: " + nested.print());
    }

    private static void evaluationModesAffectCost(){
        RecoveryPredicate.Predicate a = new RecoveryPredicate.Atom("equal", "a", "1",
            RecoveryPredicate.EvaluationMode.EAGER);
        RecoveryPredicate.Predicate b = new RecoveryPredicate.Atom("equal", "b", "2",
            RecoveryPredicate.EvaluationMode.EAGER);
        RecoveryPredicate.Predicate eager = new RecoveryPredicate.And(a, b, RecoveryPredicate.EvaluationMode.EAGER);
        RecoveryPredicate.Predicate unknown = new RecoveryPredicate.And(a, b, RecoveryPredicate.EvaluationMode.UNKNOWN);
        RecoveryPredicate.Predicate shortCircuit = new RecoveryPredicate.And(a, b,
            RecoveryPredicate.EvaluationMode.SHORT_CIRCUIT);
        check(eager.loss() < shortCircuit.loss() && shortCircuit.loss() < unknown.loss(),
            "mode penalties are not ordered: eager=" + eager.loss() + ", short=" + shortCircuit.loss()
                + ", unknown=" + unknown.loss());
        check(eager.score() == -eager.loss(), "score is not the negative loss");
        check(eager.depth() == 2, "compound predicate depth is wrong");
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
