package mindustry.logic;

import logicsugar.LogicSugarMod;
import logicsugar.assist.expr.ArrayRegistry;
import logicsugar.assist.expr.ExprCompiler;
import mindustry.Vars;
import mindustry.gen.Building;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Multi-cell span. Headless (no link resolver, or every member unresolved) assumes
 * capacity 64 only when every member name matches {@code cellN}. A linked build uses
 * {@code memoryCapacity} from the resolver; a world-cell that happens to be named
 * {@code cellN} is not treated as 64.
 *
 * <p>Variable addressing is {@code N+3} instructions (5 when N=2). {@code idiv} is
 * {@code Math.floor}, so a negative address yields {@code q <= -1} and matches none of
 * the {@code equal q k} selects. The chain starts on numeric {@code 0}, which is the
 * null building for both {@code addr < 0} (including -64) and {@code addr >= N*C}.
 */
public final class SpanTest{
    private SpanTest(){}

    /** Two cellN members, written as an expression sum. Headless capacity is 64 each. */
    private static final String SPAN = "span big \"cell1 + cell2\"\n";

    public static void main(String[] args){
        LogicSugarMod.registerStatements();
        Vars.logicVars = new GlobalVars();

        List<String> constant0 = instructions(SPAN + "read x big 0\n");
        List<String> constant64 = instructions(SPAN + "read x big 64\n");
        List<String> variable = instructions(SPAN + "read x big i\n");
        List<String> variableWrite = instructions(SPAN + "write v big i\n");

        check(constant0.equals(List.of("read x cell1 0")), "constant index 0\n" + constant0);
        check(constant64.equals(List.of("read x cell2 0")), "constant index 64\n" + constant64);
        check(variable.size() == 5, "N=2 variable read is " + variable.size() + " instructions, expected 5\n" + variable);
        check(variableWrite.size() == 5, "N=2 variable write is " + variableWrite.size() + " instructions\n" + variableWrite);
        check(variable.equals(List.of(
            "op idiv __ls_span_q i 64",
            "op mod __ls_span_r i 64",
            "select __ls_span_b equal __ls_span_q 0 cell1 0",
            "select __ls_span_b equal __ls_span_q 1 cell2 __ls_span_b",
            "read x __ls_span_b __ls_span_r"
        )), "variable read shape\n" + variable);
        check(variableWrite.get(4).equals("write v __ls_span_b __ls_span_r"), "variable write payload\n" + variableWrite);
        for(String line : variable){
            check(!line.contains("span ") && !line.contains("funcdef") && !line.contains("funccall")
                && !line.startsWith("array ") && !line.startsWith("datacall"),
                "sugar token in variable product: " + line);
        }

        check(instructions(SPAN + "read x big -1\n").equals(List.of("read x 0 0")), "negative constant must be the null building");
        check(instructions(SPAN + "read x big -64\n").equals(List.of("read x 0 0")), "negative multiple of C must not read cell2");
        check(instructions(SPAN + "read x big 128\n").equals(List.of("read x 0 0")), "address N*C is out of range");
        check(instructions(SPAN + "read y cell1 3\n").equals(List.of("read y cell1 3")), "a real cell1 link must stay one read");

        String arraySugar = SPAN + "array buf big 0 128\nset x 1\n";
        String restored = SugarCompiler.restore(compile(arraySugar));
        check(restored.contains("span big") && restored.contains("cell1 + cell2"),
            "carrier dropped the span expression:\n" + restored);
        check(restored.contains("array buf big 0 128"), "carrier dropped the array:\n" + restored);
        ArrayRegistry registry = ArrayRegistry.compileRegistry(LAssembler.read(restored, true), Collections.emptySet());
        check(registry.span("big").logicalCapacity == 128, "span logical capacity");
        check(registry.span("big").cellCapacity == 64, "headless cellN capacity is 64");
        check(registry.span("cell1") == null, "cell1 must not itself be a span");
        ArrayRegistry previous = ArrayRegistry.enter(registry);
        try{
            check(ArrayRegistry.capacityOf("big") == 128, "capacityOf(span) during an entered registry");
            check(ArrayRegistry.capacityOf("cell1") == 64, "capacityOf(cell1) stays the cell, not N*C");
            check(textOf(ExprCompiler.compile("x", "buf[0]")).equals("read x cell1 0"), "array index 0");
            check(textOf(ExprCompiler.compile("x", "buf[64]")).equals("read x cell2 0"), "array index 64");
            List<String> indexed = linesOf(ExprCompiler.compile("x", "buf[i]"));
            check(indexed.size() == 5, "array variable index instructions: " + indexed.size() + "\n" + indexed);
            check(indexed.get(4).equals("read x __ls_span_b __ls_span_r"), "array variable index read\n" + indexed);
        }finally{
            ArrayRegistry.restore(previous);
        }
        expectFail(SPAN + "array buf big 0 129\nset x 1\n", "past N*C");

        List<String> filled = instructions(SPAN + "array buf big 64 4\narrayinit buf 7 ~ ~ ~ ~ ~ ~ ~\n");
        check(filled.equals(List.of("write 7 cell2 0")), "arrayinit past the first cell\n" + filled);

        expectFail("span big \"cell1\"\nset x 1\n", "one cell");
        expectFail("span big \"cell1 + + cell2\"\nset x 1\n", "empty term");
        expectFail("span big \"cell1 + 5\"\nset x 1\n", "not a name");
        expectFail("span big \"bank1 + bank2\"\nset x 1\n", "headless bank");
        try{
            compile(SPAN + "array buf big 0 8\ndatacall array_sum r \"buf\"\n");
            check(false, "array_sum on a span should fail");
        }catch(RuntimeException e){
            String message = String.valueOf(e.getMessage());
            check(message.contains("first cell") || message.contains("span"),
                "array_sum error should name the span, got: " + message);
        }

        String inferred = SugarDecompiler.openingSource(executable(compile(SPAN + "read x big i\n")), false, false).source;
        check(inferred == null || !inferred.contains("span "), "idiv/select was inferred back into a span card:\n" + inferred);

        withLinks(Links.of("cell1", 512, "cell2", 512), () -> {
            ArrayRegistry linked = ArrayRegistry.compileRegistry(LAssembler.read(SPAN + "set x 1\n", true), Collections.emptySet());
            check(linked.span("big").cellCapacity == 512, "linked cellN must use memoryCapacity 512, not the name guess 64");
            check(linked.span("big").logicalCapacity == 1024, "linked logical capacity");
            compile(SPAN + "array buf big 0 200\nset x 1\n");
        });
        withLinks(Links.of("cell1", 64, "cell2", 512), () -> expectFail(SPAN + "set x 1\n", "mixed"));
        withLinks(Links.privileged("cell1", "cell2"), () -> expectFail(SPAN + "set x 1\n", "privileged"));

        String condition = executable(compile(SPAN + "array buf big 0 128\nifbegin expr \"buf[i] > 0\" 3\nset x 1\nblockend\n"));
        check(condition.contains("op idiv __ls_span_q i 64"), "condition read did not divide the address\n" + condition);
        check(condition.contains("select __ls_span_b equal __ls_span_q 0 cell1 0"),
            "condition read did not null the failed cell\n" + condition);
        check(!condition.contains("lessThan i 0"), "condition read still clamps before idiv\n" + condition);

        List<String> ordered = instructions("span _cell1 \"cell1 + cell3 + cell2\"\nread x _cell1 64\n");
        check(ordered.equals(List.of("read x cell3 0")),
            "the second term is address C, got\n" + ordered);

        List<String> three = instructions("span wide \"cell1 + cell2 + cell3\"\nread x wide i\n");
        check(three.size() == 6, "N=3 variable read is " + three.size() + " instructions, expected N+3\n" + three);
        check(three.get(2).equals("select __ls_span_b equal __ls_span_q 0 cell1 0"), "N=3 null fallthrough\n" + three);
        check(three.get(4).equals("select __ls_span_b equal __ls_span_q 2 cell3 __ls_span_b"), "N=3 last cell\n" + three);

        System.out.println("spanTest passed; N=2 variable instructions=" + variable.size()
            + "; constant index 0 instructions=" + constant0.size()
            + "; constant index 64 instructions=" + constant64.size());
    }

    private static String compile(String sugar){
        return SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
    }

    /** Addressing and payload lines. Entry skip and carriers are not part of the count. */
    private static List<String> instructions(String sugar){
        List<String> lines = new ArrayList<>();
        for(String line : executable(compile(sugar)).split("\n", -1)){
            String trimmed = line.trim();
            if(trimmed.isEmpty() || trimmed.endsWith(":") || trimmed.startsWith("#")) continue;
            if(trimmed.startsWith("set __ls_sugar") || trimmed.startsWith("set __ls_lib")) continue;
            if(trimmed.equals("set @counter 0")) continue;
            lines.add(trimmed);
        }
        return lines;
    }

    private static String executable(String compiled){
        String stripped = SugarCompiler.stripMarkers(compiled);
        StringBuilder out = new StringBuilder();
        for(String line : stripped.replace("\r\n", "\n").split("\n", -1)){
            if(line.startsWith("set __ls_sugar") || line.startsWith("set __ls_lib")) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String textOf(List<ExprCompiler.Line> lines){
        return String.join("\n", linesOf(lines));
    }

    private static List<String> linesOf(List<ExprCompiler.Line> lines){
        List<String> text = new ArrayList<>();
        for(ExprCompiler.Line line : lines) text.add(line.toText());
        return text;
    }

    private static void expectFail(String sugar, String what){
        try{
            compile(sugar);
        }catch(RuntimeException e){
            check(e.getMessage() != null && !e.getMessage().isEmpty(), what + " failed without a message");
            return;
        }
        check(false, "compile should have failed (" + what + ")");
    }

    private static void withLinks(ArrayRegistry.LinkResolver resolver, Runnable body){
        ArrayRegistry.LinkResolver previous = ArrayRegistry.enterLinkResolver(resolver);
        try{
            body.run();
        }finally{
            ArrayRegistry.restoreLinkResolver(previous);
        }
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }

    /** Capacities by name. Unlisted names are unresolved (-1). */
    private static final class Links implements ArrayRegistry.LinkResolver{
        final Map<String, Integer> capacities = new HashMap<>();
        final java.util.Set<String> privileged = new HashSet<>();
        boolean processorPrivileged = true;

        static Links of(String a, int aCap, String b, int bCap){
            Links links = new Links();
            links.capacities.put(a, aCap);
            links.capacities.put(b, bCap);
            return links;
        }

        static Links privileged(String a, String b){
            Links links = of(a, 512, b, 512);
            links.privileged.add(a);
            links.privileged.add(b);
            links.processorPrivileged = false;
            return links;
        }

        @Override public Building linkedBuilding(String memory){ return null; }

        @Override public int capacity(String memory){
            Integer value = capacities.get(memory);
            return value == null ? -1 : value;
        }

        @Override public boolean privilegedMemory(String memory){
            return privileged.contains(memory);
        }

        @Override public boolean processorPrivileged(){
            return processorPrivileged;
        }
    }
}
