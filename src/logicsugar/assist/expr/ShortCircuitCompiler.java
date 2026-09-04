package logicsugar.assist.expr;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Lowers a boolean predicate to vanilla mlog conditional jumps.
 *
 * <p>This class is deliberately independent from the structured-statement compiler.  It has no
 * dependency on Mindustry classes (and in particular does not access protected game members), so a
 * caller can use it while building a program or while testing a recovered predicate.  The
 * predicate tree is immutable; only the label allocator is stateful.</p>
 *
 * <p>An atom is a normal vanilla {@code jump} comparison.  Its operands must already be vanilla
 * values (variables, constants, or quoted strings); value-expression compilation belongs to the
 * caller.  Compound nodes are emitted in control-flow order: the right side of an {@code And} is
 * emitted only after the left side branches to its continuation, and the right side of an
 * {@code Or} is emitted only after the left side fails.  No eager boolean temporary is produced.</p>
 */
public final class ShortCircuitCompiler{
    private ShortCircuitCompiler(){}

    /** A boolean predicate that can be lowered to two control-flow destinations. */
    public interface Predicate{
        /** Exact logical negation.  Strict equality remains wrapped in {@link Not}. */
        Predicate negate();

        /** Alias useful at call sites that use RecoveryPredicate's terminology. */
        default Predicate applyNot(){ return negate(); }
    }

    /** The comparison operations accepted by vanilla {@code jump}. */
    public enum Comparison{
        EQUAL("equal", "==", "notEqual"),
        NOT_EQUAL("notEqual", "!=", "equal"),
        STRICT_EQUAL("strictEqual", "===", null),
        LESS_THAN("lessThan", "<", "greaterThanEq"),
        GREATER_THAN("greaterThan", ">", "lessThanEq"),
        LESS_THAN_EQ("lessThanEq", "<=", "greaterThan"),
        GREATER_THAN_EQ("greaterThanEq", ">=", "lessThan");

        private final String operation;
        private final String symbol;
        private final String inverse;

        Comparison(String operation, String symbol, String inverse){
            this.operation = operation;
            this.symbol = symbol;
            this.inverse = inverse;
        }

        /** Canonical mlog operation name. */
        public String operation(){ return operation; }

        /** Source-language spelling. */
        public String symbol(){ return symbol; }

        /** Exact inverse, or empty when vanilla has no exact inverse. */
        public Optional<Comparison> inverse(){
            return inverse == null ? Optional.empty() : Optional.of(fromOperation(inverse));
        }

        /** Resolves a canonical operation or its symbolic spelling. */
        public static Optional<Comparison> tryParse(String value){
            if(value == null) return Optional.empty();
            String candidate = value.trim();
            for(Comparison comparison : values()){
                if(comparison.operation.equalsIgnoreCase(candidate) || comparison.symbol.equals(candidate)){
                    return Optional.of(comparison);
                }
            }
            return Optional.empty();
        }

        private static Comparison fromOperation(String value){
            return tryParse(value).orElseThrow(() -> new AssertionError("unknown comparison: " + value));
        }
    }

    /** An immutable atomic comparison over two already-evaluated vanilla values. */
    public static final class Atom implements Predicate{
        public final Comparison comparison;
        public final String operation;
        public final String left;
        public final String right;

        public Atom(Comparison comparison, String left, String right){
            this.comparison = Objects.requireNonNull(comparison, "comparison");
            this.operation = comparison.operation();
            this.left = value(left, "left");
            this.right = value(right, "right");
        }

        /** Accepts either a vanilla operation name ({@code equal}) or source symbol ({@code ==}). */
        public Atom(String operation, String left, String right){
            this(Comparison.tryParse(operation).orElseThrow(() ->
                new IllegalArgumentException("unsupported jump comparison: " + operation)), left, right);
        }

        public Comparison comparison(){ return comparison; }

        /**
         * Negates exactly where vanilla provides an inverse.  strictEqual intentionally has no
         * inverse: it becomes an explicit {@link Not} node instead of an imprecise notEqual.
         */
        @Override public Predicate negate(){
            return comparison.inverse()
                .<Predicate>map(value -> new Atom(value, left, right))
                .orElseGet(() -> new Not(this));
        }

        @Override public String toString(){
            return left + " " + comparison.symbol() + " " + right;
        }

        @Override public boolean equals(Object other){
            if(this == other) return true;
            if(!(other instanceof Atom atom)) return false;
            return comparison == atom.comparison && left.equals(atom.left) && right.equals(atom.right);
        }

        @Override public int hashCode(){
            return Objects.hash(comparison, left, right);
        }
    }

    /** Immutable short-circuit conjunction. */
    public static final class And implements Predicate{
        public final Predicate left;
        public final Predicate right;

        public And(Predicate left, Predicate right){
            this.left = require(left, "left");
            this.right = require(right, "right");
        }

        @Override public Predicate negate(){
            return new Or(left.negate(), right.negate());
        }

        @Override public String toString(){ return "(" + left + " && " + right + ")"; }

        @Override public boolean equals(Object other){
            if(this == other) return true;
            return other instanceof And and && left.equals(and.left) && right.equals(and.right);
        }

        @Override public int hashCode(){ return Objects.hash("and", left, right); }
    }

    /** Immutable short-circuit disjunction. */
    public static final class Or implements Predicate{
        public final Predicate left;
        public final Predicate right;

        public Or(Predicate left, Predicate right){
            this.left = require(left, "left");
            this.right = require(right, "right");
        }

        @Override public Predicate negate(){
            return new And(left.negate(), right.negate());
        }

        @Override public String toString(){ return "(" + left + " || " + right + ")"; }

        @Override public boolean equals(Object other){
            if(this == other) return true;
            return other instanceof Or or && left.equals(or.left) && right.equals(or.right);
        }

        @Override public int hashCode(){ return Objects.hash("or", left, right); }
    }

    /** Explicit logical negation.  It is retained for strictEqual and unknown future atoms. */
    public static final class Not implements Predicate{
        public final Predicate value;

        public Not(Predicate value){ this.value = require(value, "value"); }

        @Override public Predicate negate(){ return value; }

        @Override public String toString(){ return "!(" + value + ")"; }

        @Override public boolean equals(Object other){
            return this == other || other instanceof Not not && value.equals(not.value);
        }

        @Override public int hashCode(){ return Objects.hash("not", value); }
    }

    /**
     * Allocates labels used for internal predicate continuations.  Allocated labels must be unique
     * within the surrounding program and must be valid mlog label tokens.
     */
    @FunctionalInterface
    public interface LabelAllocator{
        String allocate(String hint);

        /** Convenience alias for code that calls allocators "next". */
        default String next(String hint){ return allocate(hint); }
    }

    /** Deterministic allocator that puts a caller-supplied prefix on every internal label. */
    public static final class PrefixLabelAllocator implements LabelAllocator{
        private final String prefix;
        private int sequence;

        public PrefixLabelAllocator(String prefix){
            Objects.requireNonNull(prefix, "prefix");
            String value = prefix.trim();
            if(!value.isEmpty() && !value.matches("[A-Za-z_][A-Za-z0-9_.]*")){
                throw new IllegalArgumentException("prefix must be a valid mlog label prefix: " + prefix);
            }
            this.prefix = value;
        }

        @Override public String allocate(String hint){
            String safeHint = labelToken(hint, "hint");
            return prefix + safeHint + "_" + sequence++;
        }

        public String prefix(){ return prefix; }
    }

    /** Creates a deterministic allocator for labels such as {@code prefix_and_0}. */
    public static LabelAllocator labels(String prefix){ return new PrefixLabelAllocator(prefix); }

    /** Parses {@code ==}, {@code !=}, {@code ===}, relational comparisons, {@code &&}, {@code ||}, and {@code !}. */
    public static Predicate parse(String source){
        if(source == null || source.trim().isEmpty()) throw new ParseException("predicate is empty");
        return new Parser(source).parse();
    }

    /** Returns an empty optional for malformed or unsupported predicate text. */
    public static Optional<Predicate> tryParse(String source){
        try{
            return Optional.of(parse(source));
        }catch(RuntimeException ignored){
            return Optional.empty();
        }
    }

    /** Convenience lowering entry point using a custom internal-label prefix. */
    public static String lower(String source, String trueLabel, String falseLabel, String labelPrefix){
        return lower(parse(source), trueLabel, falseLabel, new PrefixLabelAllocator(labelPrefix));
    }

    /** Convenience lowering entry point for an already-built immutable predicate. */
    public static String lower(Predicate predicate, String trueLabel, String falseLabel,
                               LabelAllocator allocator){
        StringBuilder out = new StringBuilder();
        emitPredicate(predicate, trueLabel, falseLabel, out, allocator);
        return out.toString();
    }

    /** Convenience lowering entry point using a custom internal-label prefix. */
    public static String lower(Predicate predicate, String trueLabel, String falseLabel,
                               String labelPrefix){
        return lower(predicate, trueLabel, falseLabel, new PrefixLabelAllocator(labelPrefix));
    }

    /**
     * Emits a predicate in short-circuit order.  The caller owns {@code trueLabel} and
     * {@code falseLabel}; this method only emits internal labels obtained from {@code allocator}.
     */
    public static void emitPredicate(Predicate predicate, String trueLabel, String falseLabel,
                                      StringBuilder out, LabelAllocator allocator){
        emitPredicate(predicate, trueLabel, falseLabel, (Appendable)out, allocator);
    }

    /** Appendable variant for writers and other text sinks. */
    public static void emitPredicate(Predicate predicate, String trueLabel, String falseLabel,
                                      Appendable out, LabelAllocator allocator){
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(allocator, "allocator");
        String yes = labelToken(trueLabel, "trueLabel");
        String no = labelToken(falseLabel, "falseLabel");
        emit(predicate, yes, no, out, allocator);
    }

    /** Runtime exception used by {@link #parse(String)}. */
    public static final class ParseException extends IllegalArgumentException{
        public ParseException(String message){ super(message); }
    }

    private static void emit(Predicate predicate, String trueLabel, String falseLabel,
                             Appendable out, LabelAllocator allocator){
        if(predicate instanceof Atom atom){
            append(out, "jump " + trueLabel + " " + atom.operation + " " + atom.left + " " + atom.right + '\n');
            append(out, "jump " + falseLabel + " always x false\n");
            return;
        }
        if(predicate instanceof Not not){
            // Swapping destinations is structural negation.  In particular, a strictEqual atom
            // remains a strictEqual jump instead of being changed to the lossy notEqual op.
            emit(not.value, falseLabel, trueLabel, out, allocator);
            return;
        }
        if(predicate instanceof And and){
            String right = allocate(allocator, "and");
            emit(and.left, right, falseLabel, out, allocator);
            append(out, right + ":\n");
            emit(and.right, trueLabel, falseLabel, out, allocator);
            return;
        }
        if(predicate instanceof Or or){
            String right = allocate(allocator, "or");
            emit(or.left, trueLabel, right, out, allocator);
            append(out, right + ":\n");
            emit(or.right, trueLabel, falseLabel, out, allocator);
            return;
        }
        throw new IllegalArgumentException("unsupported predicate implementation: "
            + predicate.getClass().getName());
    }

    private static String allocate(LabelAllocator allocator, String hint){
        return labelToken(allocator.allocate(hint), "allocated label");
    }

    private static void append(Appendable out, String text){
        try{
            out.append(text);
        }catch(IOException e){
            throw new IllegalStateException("could not write lowered predicate", e);
        }
    }

    private static String value(String value, String name){
        Objects.requireNonNull(value, name);
        String result = value.trim();
        if(result.isEmpty() || result.indexOf('\n') >= 0 || result.indexOf('\r') >= 0
            || result.indexOf(' ') >= 0 || result.indexOf('\t') >= 0){
            throw new IllegalArgumentException(name + " must be one vanilla value token");
        }
        if(!isVanillaValue(result)){
            throw new IllegalArgumentException(name + " is not a vanilla value token: " + value);
        }
        return result;
    }

    private static boolean isVanillaValue(String value){
        if(value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) return true;
        return value.matches("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)")
            || value.matches("[@A-Za-z_][@A-Za-z0-9_.]*");
    }

    private static String labelToken(String value, String name){
        Objects.requireNonNull(value, name);
        String result = value.trim();
        if(!result.matches("[A-Za-z_][A-Za-z0-9_.]*")){
            throw new IllegalArgumentException(name + " must be a valid mlog label token: " + value);
        }
        return result;
    }

    private static Predicate require(Predicate value, String name){
        return Objects.requireNonNull(value, name);
    }

    private enum TokenType{ VALUE, OP, NOT, AND, OR, LEFT, RIGHT, END }

    private static final class Token{
        final TokenType type;
        final String text;
        final int position;

        Token(TokenType type, String text, int position){
            this.type = type;
            this.text = text;
            this.position = position;
        }
    }

    private static final class Parser{
        final List<Token> tokens;
        int position;

        Parser(String source){ this.tokens = tokenize(source); }

        Predicate parse(){
            Predicate result = parseOr();
            Token token = peek();
            if(token.type != TokenType.END) fail("unexpected token '" + token.text + "'", token);
            return result;
        }

        private Predicate parseOr(){
            Predicate result = parseAnd();
            while(match(TokenType.OR)) result = new Or(result, parseAnd());
            return result;
        }

        private Predicate parseAnd(){
            Predicate result = parseNot();
            while(match(TokenType.AND)) result = new And(result, parseNot());
            return result;
        }

        private Predicate parseNot(){
            if(match(TokenType.NOT)) return new Not(parseNot());
            return parsePrimary();
        }

        private Predicate parsePrimary(){
            if(match(TokenType.LEFT)){
                Predicate result = parseOr();
                expect(TokenType.RIGHT, "expected ')'");
                return result;
            }
            return parseAtom();
        }

        private Predicate parseAtom(){
            Token left = expect(TokenType.VALUE, "expected a comparison operand");
            if(peek().type != TokenType.OP){
                return new Atom(Comparison.NOT_EQUAL, left.text, "0");
            }
            Comparison operation = Comparison.tryParse(next().text).orElseThrow(() ->
                new ParseException("unsupported comparison at position " + peek().position));
            Token right = expect(TokenType.VALUE, "expected the right comparison operand");
            return new Atom(operation, left.text, right.text);
        }

        private boolean match(TokenType type){
            if(peek().type != type) return false;
            position++;
            return true;
        }

        private Token expect(TokenType type, String message){
            Token token = peek();
            if(token.type != type) fail(message, token);
            position++;
            return token;
        }

        private Token next(){
            Token token = peek();
            position++;
            return token;
        }

        private Token peek(){ return tokens.get(position); }

        private static void fail(String message, Token token){
            throw new ParseException(message + " at position " + token.position);
        }
    }

    private static List<Token> tokenize(String source){
        List<Token> result = new ArrayList<>();
        int index = 0;
        while(index < source.length()){
            char c = source.charAt(index);
            if(Character.isWhitespace(c)){
                index++;
                continue;
            }
            if(c == '('){ result.add(new Token(TokenType.LEFT, "(", index++)); continue; }
            if(c == ')'){ result.add(new Token(TokenType.RIGHT, ")", index++)); continue; }
            if(source.startsWith("&&", index)){
                result.add(new Token(TokenType.AND, "&&", index));
                index += 2;
                continue;
            }
            if(source.startsWith("||", index)){
                result.add(new Token(TokenType.OR, "||", index));
                index += 2;
                continue;
            }
            String comparison = comparisonAt(source, index);
            if(comparison != null){
                result.add(new Token(TokenType.OP, comparison, index));
                index += comparison.length();
                continue;
            }
            if(c == '!'){
                result.add(new Token(TokenType.NOT, "!", index++));
                continue;
            }
            if(c == '"'){
                int start = index++;
                boolean escaped = false;
                while(index < source.length()){
                    char value = source.charAt(index++);
                    if(value == '"' && !escaped) break;
                    escaped = value == '\\' && !escaped;
                    if(value != '\\') escaped = false;
                }
                if(index > source.length() || source.charAt(index - 1) != '"'){
                    throw new ParseException("unterminated string at position " + start);
                }
                result.add(new Token(TokenType.VALUE, source.substring(start, index), start));
                continue;
            }
            int start = index;
            while(index < source.length() && !isValueDelimiter(source.charAt(index))) index++;
            if(start == index) throw new ParseException("unexpected character '" + c + "' at position " + index);
            result.add(new Token(TokenType.VALUE, source.substring(start, index), start));
        }
        result.add(new Token(TokenType.END, "", source.length()));
        return result;
    }

    private static String comparisonAt(String source, int index){
        String[] operators = {"===", "!=", "==", ">=", "<=", ">", "<"};
        for(String operator : operators){
            if(source.startsWith(operator, index)) return operator;
        }
        return null;
    }

    private static boolean isValueDelimiter(char c){
        return Character.isWhitespace(c) || c == '(' || c == ')' || c == '&' || c == '|'
            || c == '!' || c == '<' || c == '>' || c == '=' || c == '"';
    }
}
