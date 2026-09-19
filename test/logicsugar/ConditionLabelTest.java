package logicsugar;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;
import java.util.TreeSet;

/**
 * Pins the localised label of the loop-condition field, plus the failure-value wording of the
 * data-operation tooltips.
 *
 * <p>{@code whilebegin} / {@code forbegin} take the <b>loop</b> condition: the lowering enters the
 * body when it is true ({@code jump <bodyLabel> notEqual <cond> 0}) and the bundle hint says the
 * body repeats while it holds.  The Chinese bundle used to label that field 结束条件 / 终止条件
 * ("termination condition"), which reads as an exit condition — a player following the label wrote
 * the inverted expression (the reported program drained a stack with {@code !s.size()}) and the
 * loop body never ran.  These assertions keep label, hint and lowering saying the same thing.</p>
 *
 * <p>The same file also pins the failure signal the v5 API emits: {@code failValue()} returns
 * {@code -1} for every fallible data operation, but the tooltips shipped with the STL rename
 * still described the pre-v5 values ("full stack keeps its length", "out of range writes 0").</p>
 *
 * <p>Reads the bundle files and {@code SugarStatements.java} relative to the project directory,
 * like {@code CrossLoaderAccessTest} does (no game runtime needed).</p>
 */
public final class ConditionLabelTest{
    private ConditionLabelTest(){}

    /** Condition labels must never be phrased as a termination/exit condition. */
    private static final String[] TERMINATION_WORDS = {"结束", "結束", "终止", "終止", "until", "end"};

    /** Condition keys shared by if / elif / for / while, checked for the same wording rule. */
    private static final String[] CONDITION_KEYS = {
        "logicsugar.while.condition", "logicsugar.for.condition", "logicsugar.condition"
    };

    public static void main(String[] args) throws IOException{
        File root = projectRoot();
        Properties en = load(root, "bundle.properties");
        Properties cn = load(root, "bundle_zh_CN.properties");
        Properties tw = load(root, "bundle_zh_TW.properties");

        // Locales must stay key-complete: a missing key silently falls back to another language.
        check(en.stringPropertyNames().equals(cn.stringPropertyNames()),
            "zh_CN must define exactly the English keys; missing=" + missing(en, cn) + " extra=" + missing(cn, en));
        check(en.stringPropertyNames().equals(tw.stringPropertyNames()),
            "zh_TW must define exactly the English keys; missing=" + missing(en, tw) + " extra=" + missing(tw, en));

        // Every condition label must exist and describe a loop condition, never a termination one.
        for(Properties bundle : new Properties[]{en, cn, tw}){
            for(String key : CONDITION_KEYS){
                String value = bundle.getProperty(key);
                check(value != null, "bundle is missing " + key);
                check(!value.trim().isEmpty(), key + " must not be empty");
                for(String word : TERMINATION_WORDS){
                    check(!value.contains(word),
                        key + " must describe a loop condition, got the termination wording '" + value + "'");
                }
            }
        }

        // The while card owns a dedicated key (like for.condition / if.condition) with a
        // loop-worded label in every locale.
        checkLabel(en, "logicsugar.while.condition", "while");
        checkLabel(cn, "logicsugar.while.condition", "循环条件");
        checkLabel(tw, "logicsugar.while.condition", "迴圈條件");
        checkLabel(en, "logicsugar.for.condition", "while");
        checkLabel(cn, "logicsugar.for.condition", "循环条件");
        checkLabel(tw, "logicsugar.for.condition", "迴圈條件");

        // The hints that document the actual semantics must keep saying "repeat while true".
        checkHint(en, "logicsugar.hint.while.condition", "true");
        checkHint(cn, "logicsugar.hint.while.condition", "为真");
        checkHint(tw, "logicsugar.hint.while.condition", "為真");
        checkHint(en, "logicsugar.hint.for.condition", "running while");
        checkHint(cn, "logicsugar.hint.for.condition", "继续循环");
        checkHint(tw, "logicsugar.hint.for.condition", "繼續迴圈");

        failureSignalWording(en, cn, tw);

        // The card must actually use the dedicated key, not the generic fallback.
        String statements = Files.readString(root.toPath().resolve("src/mindustry/logic/SugarStatements.java"),
            StandardCharsets.UTF_8);
        check(statements.contains("text(\"while.condition\""),
            "WhileBeginStatement must label its condition with the dedicated while.condition key");
        check(!statements.contains("text(\"condition\", \"condition\")"),
            "the generic 'condition' key must not be used as the while card label");

        System.out.println("LogicSugar condition label self-test passed.");
    }

    /**
     * Card tooltips must document the failure signal the v5 API actually emits.
     *
     * <p>{@code SugarCompiler.failValue()} reports {@code -1} for every fallible data operation
     * (push / append / insert / set / erase / free), while the query operations stay 0/1. The
     * hints shipped with the STL rename still described the pre-v5 values ("full stack keeps its
     * length", "out of range writes 0"), i.e. the tooltip contradicted the compiled program, so
     * the tuples are pinned here for the live (new-name) keys of all three locales.</p>
     */
    private static void failureSignalWording(Properties en, Properties cn, Properties tw){
        String[] operations = {
            "stack_push", "queue_push", "deque_push_front", "deque_push_back",
            "vector_push_back", "vector_set", "vector_insert", "heap_push",
            "map_erase", "set_remove", "chain_free", "chain_set", "chain_link"
        };
        for(Properties bundle : new Properties[]{en, cn, tw}){
            for(String operation : operations){
                for(String prefix : new String[]{"hint", "lst"}){
                    String key = "logicsugar." + prefix + ".datacall." + operation;
                    String value = bundle.getProperty(key);
                    check(value != null, "bundle is missing the hint " + key);
                    check(value.contains("-1"),
                        key + " must document the v5 failure value -1, got '" + value + "'");
                }
            }
            // Queries stay 0/1: mentioning -1 there would send players down the wrong branch.
            for(String operation : new String[]{"map_contains", "set_contains", "bitset_test"}){
                String key = "logicsugar.hint.datacall." + operation;
                String value = bundle.getProperty(key);
                check(value != null, "bundle is missing the hint " + key);
                check(!value.contains("-1"),
                    key + " is a query and must stay 0/1, got '" + value + "'");
            }
        }
    }

    private static void checkLabel(Properties bundle, String key, String expected){
        String value = bundle.getProperty(key);
        check(expected.equals(value), key + " expected '" + expected + "' but was '" + value + "'");
    }

    private static void checkHint(Properties bundle, String key, String needle){
        String value = bundle.getProperty(key);
        check(value != null, "bundle is missing the hint " + key);
        check(value.contains(needle), key + " must still document the continue-while-true semantics, got '" + value + "'");
    }

    private static Properties load(File root, String name) throws IOException{
        Properties properties = new Properties();
        try(Reader reader = new InputStreamReader(
            Files.newInputStream(root.toPath().resolve("assets/bundles").resolve(name)), StandardCharsets.UTF_8)){
            properties.load(reader);
        }
        return properties;
    }

    private static String missing(Properties reference, Properties candidate){
        TreeSet<String> keys = new TreeSet<>(reference.stringPropertyNames());
        keys.removeAll(candidate.stringPropertyNames());
        return keys.toString();
    }

    /** Project directory that contains {@code assets/bundles}; gradle runs from it, tests may run from a subdirectory. */
    private static File projectRoot(){
        for(String candidate : new String[]{".", ".."}){
            File root = new File(candidate);
            if(new File(root, "assets/bundles/bundle.properties").isFile()
                && new File(root, "src/mindustry/logic/SugarStatements.java").isFile()){
                return root;
            }
        }
        throw new AssertionError("LogicSugar project directory not found from " + new File(".").getAbsolutePath());
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
