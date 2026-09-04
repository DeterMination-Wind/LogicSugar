package mindustry.logic;

import java.util.Objects;
import java.util.Optional;

/**
 * A small, dependency-free predicate tree used by recovery code.
 *
 * <p>This class deliberately does not refer to any Mindustry parser, statement, or executor
 * type.  A caller can therefore build and score candidates before it touches the game API.
 * {@link EvaluationMode} is metadata about how a tree was observed; it is never inferred from
 * the text printed by this class.</p>
 *
 * <p>Loss is a minimization metric, in the same spirit as Bang's quality loss.  {@link #score()}
 * is its monotonic maximization counterpart ({@code -loss()}).  The constants are public so a
 * caller that needs to compare this model with another candidate model can document the exact
 * weights it is using without copying the implementation.</p>
 */
public final class RecoveryPredicate{
    private RecoveryPredicate(){}

    /** How a compound predicate was evaluated in the original instruction stream. */
    public enum EvaluationMode{
        /** Both sides were computed as ordinary values before the condition was tested. */
        EAGER,
        /** Control flow can skip the right-hand side. */
        SHORT_CIRCUIT,
        /** The instruction stream does not prove either evaluation strategy. */
        UNKNOWN
    }

    /**
     * Operation names understood by the standard comparison instructions.
     *
     * <p>{@link #inverse()} is intentionally empty for {@link #STRICT_EQUAL}: Mindustry has no
     * exact strict-not-equal operation in the condition instruction vocabulary.  Code that needs
     * the logical negation can keep a {@link Not} node around the strict atom instead of silently
     * changing its semantics to ordinary {@code notEqual}.</p>
     */
    public enum Comparison{
        EQUAL("equal", "==", 4, "notEqual"),
        NOT_EQUAL("notEqual", "!=", 4, "equal"),
        STRICT_EQUAL("strictEqual", "===", 4, null),
        LESS_THAN("lessThan", "<", 4, "greaterThanEq"),
        GREATER_THAN("greaterThan", ">", 4, "lessThanEq"),
        LESS_THAN_EQ("lessThanEq", "<=", 4, "greaterThan"),
        GREATER_THAN_EQ("greaterThanEq", ">=", 4, "lessThan"),
        /** Eager boolean operation, accepted for callers that model an already-built op tree. */
        EAGER_OR("or", "||", 1, null),
        /** Eager boolean operation, accepted for callers that model an already-built op tree. */
        EAGER_AND("land", "&&", 2, null);

        private final String mlogName;
        private final String symbol;
        private final int precedence;
        private final String inverse;

        Comparison(String mlogName, String symbol, int precedence, String inverse){
            this.mlogName = mlogName;
            this.symbol = symbol;
            this.precedence = precedence;
            this.inverse = inverse;
        }

        /** Canonical mlog operation name. */
        public String mlogName(){ return mlogName; }

        /** Human-readable expression symbol. */
        public String symbol(){ return symbol; }

        /** Expression precedence; larger values bind more tightly. */
        public int precedence(){ return precedence; }

        /** Exact inverse operation, or empty where the instruction vocabulary has none. */
        public Optional<Comparison> inverse(){
            return inverse == null ? Optional.empty() : Optional.of(fromMlogName(inverse));
        }

        /** Resolves a canonical mlog name or expression symbol. */
        public static Optional<Comparison> tryParse(String operation){
            if(operation == null) return Optional.empty();
            String value = operation.trim();
            for(Comparison candidate : values()){
                if(candidate.mlogName.equalsIgnoreCase(value) || candidate.symbol.equals(value)) return Optional.of(candidate);
            }
            return Optional.empty();
        }

        private static Comparison fromMlogName(String operation){
            return tryParse(operation).orElseThrow(() -> new AssertionError("unknown comparison: " + operation));
        }
    }

    /**
     * A predicate node.  Implementations are immutable and thread-safe.
     *
     * <p>{@link #applyNot()} performs exact structural negation.  For a strict-equality atom,
     * exact operation inversion is unavailable, so the result is {@link Not}{@code (atom)}.
     * This preserves the operation rather than degrading it to ordinary equality.</p>
     */
    public interface Predicate{
        /** Evaluation metadata for this node. Atomic and unknown nodes return UNKNOWN. */
        EvaluationMode mode();

        /** Number of predicate levels, counting this node. */
        int depth();

        /** Prints a stable expression with precedence-safe parentheses. */
        String print();

        /** Exact logical negation, using De Morgan for compound nodes. */
        Predicate applyNot();

        /** Bang-style minimization cost, including depth and mode penalties. */
        double loss();

        /** Maximization counterpart of {@link #loss()}; larger is better. */
        default double score(){ return -loss(); }
    }

    /**
     * A comparison (or otherwise named binary operation) over two already-evaluated values.
     * Values are kept as text because recovery must not depend on Mindustry's variable classes.
     */
    public static final class Atom implements Predicate{
        public final String operation;
        public final String left;
        public final String right;
        private final EvaluationMode mode;

        /** Creates an atom with unknown evaluation metadata. */
        public Atom(String operation, String left, String right){
            this(operation, left, right, EvaluationMode.UNKNOWN);
        }

        /** Creates an atom from a known comparison operation. */
        public Atom(Comparison operation, String left, String right){
            this(operation.mlogName(), left, right, EvaluationMode.UNKNOWN);
        }

        /** Creates an atom with explicit evaluation metadata. */
        public Atom(String operation, String left, String right, EvaluationMode mode){
            this.operation = normalizeOperation(operation);
            this.left = normalizeValue(left, "left");
            this.right = normalizeValue(right, "right");
            this.mode = mode == null ? EvaluationMode.UNKNOWN : mode;
        }

        /** Creates an atom from a known comparison operation and explicit mode. */
        public Atom(Comparison operation, String left, String right, EvaluationMode mode){
            this(Objects.requireNonNull(operation, "operation").mlogName(), left, right, mode);
        }

        @Override public EvaluationMode mode(){ return mode; }
        @Override public int depth(){ return 1; }
        @Override public double loss(){
            // Evaluation mode primarily describes compound control flow.  An explicit mode on an
            // atom is still accounted for, while the default UNKNOWN remains a conservative cost.
            return BASE_COST + modePenalty(mode) + DEPTH_PENALTY;
        }

        /** Resolves this atom's known operation, if it is in {@link Comparison}. */
        public Optional<Comparison> comparison(){ return Comparison.tryParse(operation); }

        /**
         * Returns the exact inverse atom when the operation has one.  Empty means callers must
         * preserve a Not node (notably for strictEqual and unknown operations).
         */
        public Optional<Atom> exactNegation(){
            Optional<Comparison> parsed = comparison();
            if(parsed.isEmpty()) return Optional.empty();
            Optional<Comparison> inverse = parsed.get().inverse();
            return inverse.map(value -> new Atom(value, left, right, mode));
        }

        /** Alias for callers that prefer an operation-oriented name. */
        public Optional<Atom> tryNegate(){ return exactNegation(); }

        @Override public Predicate applyNot(){
            return exactNegation().<Predicate>map(value -> value).orElseGet(() -> new Not(this));
        }

        /** Returns the exact inverse mlog operation name, or {@code null} if unavailable. */
        public String exactNegationOperation(){
            return comparison().flatMap(Comparison::inverse).map(Comparison::mlogName).orElse(null);
        }

        @Override public String print(){
            Optional<Comparison> parsed = comparison();
            String symbol = parsed.map(Comparison::symbol).orElse(operation);
            int precedence = parsed.map(Comparison::precedence).orElse(4);
            String leftText = valueText(left);
            String rightText = valueText(right);
            String result = leftText + " " + symbol + " " + rightText;
            // An unknown operation can still be printed, but retain a precedence-safe form.
            return precedence < 4 ? "(" + result + ")" : result;
        }

        @Override public String toString(){ return print(); }

        @Override public boolean equals(Object other){
            if(this == other) return true;
            if(!(other instanceof Atom atom)) return false;
            return operation.equals(atom.operation) && left.equals(atom.left) && right.equals(atom.right)
                && mode == atom.mode;
        }

        @Override public int hashCode(){ return Objects.hash(operation, left, right, mode); }
    }

    /** Conjunction of two predicates. */
    public static final class And implements Predicate{
        public final Predicate left;
        public final Predicate right;
        public final EvaluationMode mode;

        /** Creates a conjunction whose evaluation mode is not yet proven. */
        public And(Predicate left, Predicate right){
            this(left, right, EvaluationMode.UNKNOWN);
        }

        /** Creates a conjunction with explicit eager/short-circuit evidence. */
        public And(Predicate left, Predicate right, EvaluationMode mode){
            this.left = requirePredicate(left, "left");
            this.right = requirePredicate(right, "right");
            this.mode = mode == null ? EvaluationMode.UNKNOWN : mode;
        }

        @Override public EvaluationMode mode(){ return mode; }
        @Override public int depth(){ return 1 + Math.max(left.depth(), right.depth()); }
        @Override public String print(){ return binaryPrint(left, "&&", 2, right); }
        @Override public Predicate applyNot(){
            return new Or(left.applyNot(), right.applyNot(), mode);
        }
        @Override public double loss(){
            return compoundLoss(left, right, mode, 0.5);
        }
        @Override public String toString(){ return print(); }

        @Override public boolean equals(Object other){
            if(this == other) return true;
            if(!(other instanceof And and)) return false;
            return mode == and.mode && left.equals(and.left) && right.equals(and.right);
        }

        @Override public int hashCode(){ return Objects.hash("and", left, right, mode); }
    }

    /** Disjunction of two predicates. */
    public static final class Or implements Predicate{
        public final Predicate left;
        public final Predicate right;
        public final EvaluationMode mode;

        /** Creates a disjunction whose evaluation mode is not yet proven. */
        public Or(Predicate left, Predicate right){
            this(left, right, EvaluationMode.UNKNOWN);
        }

        /** Creates a disjunction with explicit eager/short-circuit evidence. */
        public Or(Predicate left, Predicate right, EvaluationMode mode){
            this.left = requirePredicate(left, "left");
            this.right = requirePredicate(right, "right");
            this.mode = mode == null ? EvaluationMode.UNKNOWN : mode;
        }

        @Override public EvaluationMode mode(){ return mode; }
        @Override public int depth(){ return 1 + Math.max(left.depth(), right.depth()); }
        @Override public String print(){ return binaryPrint(left, "||", 1, right); }
        @Override public Predicate applyNot(){
            return new And(left.applyNot(), right.applyNot(), mode);
        }
        @Override public double loss(){
            return compoundLoss(left, right, mode, 0.5);
        }
        @Override public String toString(){ return print(); }

        @Override public boolean equals(Object other){
            if(this == other) return true;
            if(!(other instanceof Or or)) return false;
            return mode == or.mode && left.equals(or.left) && right.equals(or.right);
        }

        @Override public int hashCode(){ return Objects.hash("or", left, right, mode); }
    }

    /** Explicit logical negation.  This node is important for strictEqual. */
    public static final class Not implements Predicate{
        public final Predicate value;

        public Not(Predicate value){ this.value = requirePredicate(value, "value"); }

        @Override public EvaluationMode mode(){ return value.mode(); }
        @Override public int depth(){ return 1 + value.depth(); }
        @Override public String print(){ return "!(" + value.print() + ")"; }
        @Override public Predicate applyNot(){ return value; }
        @Override public double loss(){
            return 0.5 + value.loss() + DEPTH_PENALTY * depth() + modePenalty(mode());
        }
        @Override public String toString(){ return print(); }

        @Override public boolean equals(Object other){
            return this == other || other instanceof Not not && value.equals(not.value);
        }

        @Override public int hashCode(){ return Objects.hash("not", value); }
    }

    /** Base cost carried by each recovered predicate expression. */
    public static final double BASE_COST = 0.5;
    /** Small cost for nesting, preventing arbitrarily deep equivalent trees from winning. */
    public static final double DEPTH_PENALTY = 0.15;
    /** Penalty for an unknown mode; unknown evidence must not beat proven candidates by accident. */
    public static final double UNKNOWN_MODE_PENALTY = 1.5;
    /** Conservative penalty for a short-circuit pattern until the caller proves its CFG edges. */
    public static final double SHORT_CIRCUIT_PENALTY = 0.5;
    /** No additional penalty for a proven eager operation. */
    public static final double EAGER_MODE_PENALTY = 0.0;
    /** Penalty when nested compound modes contradict their containing mode. */
    public static final double MODE_MISMATCH_PENALTY = 4.0;

    /** Creates an atom without requiring callers to spell the nested class name. */
    public static Atom atom(String operation, String left, String right){
        return new Atom(operation, left, right);
    }

    public static And and(Predicate left, Predicate right, EvaluationMode mode){
        return new And(left, right, mode);
    }

    public static Or or(Predicate left, Predicate right, EvaluationMode mode){
        return new Or(left, right, mode);
    }

    public static Not not(Predicate value){ return new Not(value); }

    /**
     * Parses the small boolean language used by the short-circuit lowering.  It intentionally
     * accepts only identifiers/literals as comparison operands; callers can keep richer leaves
     * as vanilla code instead of changing their evaluation order.
     */
    public static Optional<Predicate> parseShortCircuit(String text){
        if(text == null || text.trim().isEmpty()) return Optional.empty();
        try{
            return Optional.of(parseBoolean(text.trim()));
        }catch(IllegalArgumentException ignored){
            return Optional.empty();
        }
    }

    private static Predicate parseBoolean(String source){
        String value = stripOuterParentheses(source.trim());
        if(value.startsWith("!") && !value.startsWith("!=") && value.length() > 1){
            return new Not(parseBoolean(value.substring(1)));
        }
        int split = findTopLevel(value, "||");
        if(split >= 0){
            return new Or(parseBoolean(value.substring(0, split)),
                parseBoolean(value.substring(split + 2)), EvaluationMode.SHORT_CIRCUIT);
        }
        split = findTopLevel(value, "&&");
        if(split >= 0){
            return new And(parseBoolean(value.substring(0, split)),
                parseBoolean(value.substring(split + 2)), EvaluationMode.SHORT_CIRCUIT);
        }

        String[] symbols = {"===", "!=", "==", ">=", "<=", ">", "<"};
        for(String symbol : symbols){
            split = findTopLevel(value, symbol);
            if(split < 0) continue;
            String left = value.substring(0, split).trim();
            String right = value.substring(split + symbol.length()).trim();
            if(!validValue(left) || !validValue(right)) throw new IllegalArgumentException("invalid comparison");
            String operation = switch(symbol){
                case "===" -> "strictEqual";
                case "!=" -> "notEqual";
                case "==" -> "equal";
                case ">=" -> "greaterThanEq";
                case "<=" -> "lessThanEq";
                case ">" -> "greaterThan";
                default -> "lessThan";
            };
            return new Atom(operation, left, right);
        }
        if(validValue(value)) return new Atom("notEqual", value, "0");
        throw new IllegalArgumentException("unsupported boolean leaf");
    }

    private static String stripOuterParentheses(String value){
        while(isWrapped(value)) value = value.substring(1, value.length() - 1).trim();
        return value;
    }

    private static boolean isWrapped(String value){
        if(value.length() < 2 || value.charAt(0) != '(' || value.charAt(value.length() - 1) != ')') return false;
        int depth = 0;
        for(int i = 0; i < value.length(); i++){
            char c = value.charAt(i);
            if(c == '(') depth++;
            else if(c == ')' && --depth == 0 && i != value.length() - 1) return false;
            if(depth < 0) return false;
        }
        return depth == 0;
    }

    private static int findTopLevel(String value, String operator){
        int depth = 0;
        for(int i = 0; i + operator.length() <= value.length(); i++){
            char c = value.charAt(i);
            if(c == '('){ depth++; continue; }
            if(c == ')'){ depth--; if(depth < 0) return -1; continue; }
            if(depth == 0 && value.startsWith(operator, i)) return i;
        }
        return -1;
    }

    private static boolean validValue(String value){
        return value.matches("(?:[@A-Za-z_][@A-Za-z0-9_.]*|[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))");
    }

    /** Returns an exact operation inverse, or empty for strictEqual/unknown operations. */
    public static Optional<String> exactNegationOperation(String operation){
        return Comparison.tryParse(operation).flatMap(Comparison::inverse).map(Comparison::mlogName);
    }

    private static final class Rendered{
        final String text;
        final int precedence;
        final String operator;

        Rendered(String text, int precedence, String operator){
            this.text = text;
            this.precedence = precedence;
            this.operator = operator;
        }
    }

    private static String binaryPrint(Predicate left, String operator, int precedence, Predicate right){
        Rendered leftText = render(left);
        Rendered rightText = render(right);
        return parenthesize(leftText, precedence, operator, false)
            + " " + operator + " "
            + parenthesize(rightText, precedence, operator, true);
    }

    private static Rendered render(Predicate value){
        if(value instanceof Atom atom){
            Optional<Comparison> comparison = atom.comparison();
            int precedence = comparison.map(Comparison::precedence).orElse(4);
            String symbol = comparison.map(Comparison::symbol).orElse(atom.operation);
            return new Rendered(value.print(), precedence, symbol);
        }
        if(value instanceof And) return new Rendered(value.print(), 2, "&&");
        if(value instanceof Or) return new Rendered(value.print(), 1, "||");
        // Not is a prefix expression and is already parenthesized internally.
        return new Rendered(value.print(), 3, "!");
    }

    private static String parenthesize(Rendered child, int parentPrecedence, String parentOperator,
                                       boolean rightHandSide){
        if(child.precedence < parentPrecedence) return "(" + child.text + ")";
        if(child.precedence > parentPrecedence) return child.text;
        // Same-precedence different operators need grouping. Same logical operators are
        // associative, so omit redundant parentheses for stable compact output.
        if(child.operator != null && !child.operator.equals(parentOperator)) return "(" + child.text + ")";
        return child.text;
    }

    private static double compoundLoss(Predicate left, Predicate right, EvaluationMode mode, double base){
        double loss = BASE_COST + base + left.loss() + right.loss();
        int depth = 1 + Math.max(left.depth(), right.depth());
        loss += DEPTH_PENALTY * depth + modePenalty(mode);
        loss += modeMismatchPenalty(left, mode) + modeMismatchPenalty(right, mode);
        return loss;
    }

    private static double modeMismatchPenalty(Predicate value, EvaluationMode expected){
        if(!(value instanceof And) && !(value instanceof Or)) return 0.0;
        EvaluationMode actual = value.mode();
        return actual != EvaluationMode.UNKNOWN && expected != EvaluationMode.UNKNOWN && actual != expected
            ? MODE_MISMATCH_PENALTY : 0.0;
    }

    private static double modePenalty(EvaluationMode mode){
        if(mode == EvaluationMode.SHORT_CIRCUIT) return SHORT_CIRCUIT_PENALTY;
        if(mode == EvaluationMode.UNKNOWN) return UNKNOWN_MODE_PENALTY;
        return EAGER_MODE_PENALTY;
    }

    private static Predicate requirePredicate(Predicate value, String name){
        return Objects.requireNonNull(value, name);
    }

    private static String normalizeOperation(String operation){
        Objects.requireNonNull(operation, "operation");
        String value = operation.trim();
        if(value.isEmpty() || value.indexOf(' ') >= 0 || value.indexOf('\t') >= 0){
            throw new IllegalArgumentException("operation must be one token: " + operation);
        }
        Optional<Comparison> comparison = Comparison.tryParse(value);
        return comparison.map(Comparison::mlogName).orElse(value);
    }

    private static String normalizeValue(String value, String name){
        Objects.requireNonNull(value, name);
        String result = value.trim();
        if(result.isEmpty()) throw new IllegalArgumentException(name + " must not be empty");
        return result;
    }

    private static String valueText(String value){
        if(value.matches("[@A-Za-z_][@A-Za-z0-9_]*")
            || value.matches("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)")
            || value.startsWith("\"") && value.endsWith("\"")
            || value.startsWith("(") && value.endsWith(")")) return value;
        return "(" + value + ")";
    }
}
