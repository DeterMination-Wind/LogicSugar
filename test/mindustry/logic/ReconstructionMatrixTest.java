package mindustry.logic;

import logicsugar.LogicSugarMod;
import logicsugar.assist.data.DataModules;
import logicsugar.assist.expr.ExprStatement;
import mindustry.Vars;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Reconstruction matrix: every current Sugar block/card must be recoverable from the
 * persisted carrier.  Each fixture compiles a source program, restores the carrier,
 * recompiles the restored source through the gate, and asks the decompiler for the
 * carrier path.  Control-flow fixtures additionally exercise the no-carrier inference
 * path where the decompiler supports that shape.
 */
public final class ReconstructionMatrixTest{
    private ReconstructionMatrixTest(){}

    private static final List<Fixture> fixtures = new ArrayList<>();
    private static int checks;

    public static void main(String[] args){
        Vars.logicVars = new GlobalVars();
        LogicSugarMod.registerStatements();

        addControlFixtures();
        addDeclarationFixtures();
        addOperationFixtures();
        addAssertionFixtures();

        check(fixtures.size() >= 100, "reconstruction matrix must contain at least 100 fixtures, got " + fixtures.size());
        assertEveryPaletteOperationHasFixture();

        for(Fixture fixture : fixtures){
            checkFixture(fixture);
        }
        System.out.println("LogicSugar reconstruction matrix self-test passed: "
            + fixtures.size() + " fixtures, " + checks + " gate checks.");
    }

    private static void addControlFixtures(){
        addInfer("if.eq", "ifbegin x equal 1 999\nset y 1\nblockend\nprint y\n", "ifbegin x equal 1", "ifbegin");
        addInfer("if.neq", "ifbegin x notEqual 1 999\nset y 1\nblockend\nprint y\n", "ifbegin x notEqual 1", "ifbegin");
        addInfer("if.lt", "ifbegin x lessThan 1 999\nset y 1\nblockend\nprint y\n", "ifbegin x lessThan 1", "ifbegin");
        addInfer("if.lte", "ifbegin x lessThanEq 1 999\nset y 1\nblockend\nprint y\n", "ifbegin x lessThanEq 1", "ifbegin");
        addInfer("if.gt", "ifbegin x greaterThan 1 999\nset y 1\nblockend\nprint y\n", "ifbegin x greaterThan 1", "ifbegin");
        addInfer("if.gte", "ifbegin x greaterThanEq 1 999\nset y 1\nblockend\nprint y\n", "ifbegin x greaterThanEq 1", "ifbegin");
        addInfer("if.varcompare", "ifbegin x lessThan y 999\nset z 1\nblockend\nprint z\n", "ifbegin x lessThan y", "ifbegin");
        addInfer("if.nefalse", "ifbegin x notEqual false 999\nset y 1\nblockend\nprint y\n", "ifbegin x notEqual false", "ifbegin");
        addInfer("if.expr", "ifbegin expr \"x > 0\" 999\nset y 1\nblockend\nprint y\n", "ifbegin expr", "ifbegin");
        addInfer("if.exprland", "ifbegin expr \"x > 0 && ready\" 999\nset y 1\nblockend\nprint y\n", "ifbegin expr", "ifbegin");
        addInfer("if.expror", "ifbegin expr \"x > 0 || ready\" 999\nset y 1\nblockend\nprint y\n", "ifbegin expr", "ifbegin");
        addInfer("if.exprnot", "ifbegin expr \"!ready\" 999\nset y 1\nblockend\nprint y\n", "ifbegin expr", "ifbegin");
        addInfer("if.exprsc", "ifbegin exprsc \"a && b\" 999\nset hit 1\nblockend\nprint hit\n", "ifbegin exprsc", "ifbegin");
        addInfer("if.exprscor", "ifbegin exprsc \"a || b\" 999\nset hit 1\nblockend\nprint hit\n", "ifbegin exprsc", "ifbegin");
        addInfer("if.exprscnested", "ifbegin exprsc \"a && (b || c)\" 999\nset hit 1\nelse\nset hit 0\nblockend\n", "ifbegin exprsc", "ifbegin");
        addInfer("if.else", "ifbegin x notEqual false 999\nset y 1\nelse\nset y 2\nblockend\nprint y\n", "ifbegin x notEqual false", "ifbegin");
        addInfer("if.elif", "ifbegin x greaterThan 0 999\nset y 1\nelif x lessThan 0\nset y 2\nblockend\nprint y\n", "elif x lessThan 0", "ifbegin");
        addInfer("if.elifelse", "ifbegin x greaterThan 0 999\nset y 1\nelif x lessThan 0\nset y 2\nelse\nset y 3\nblockend\nprint y\n", "elif x lessThan 0", "ifbegin");
        addInfer("if.elif2", "ifbegin x greaterThan 2 999\nset y 1\nelif x equal 1\nset y 2\nelif x lessThan -5\nset y 3\nelse\nset y 4\nblockend\nprint y\n", "elif x equal 1", "ifbegin");
        addInfer("if.exprelif", "ifbegin expr \"x > 0\" 999\nset y 1\nelif expr \"x < 0\"\nset y 2\nelse\nset y 3\nblockend\nprint y\n", "elif expr", "ifbegin");
        addInfer("if.exprscelif", "ifbegin exprsc \"a && b\" 999\nset y 1\nelif exprsc \"c || d\"\nset y 2\nelse\nset y 3\nblockend\nprint y\n", "elif exprsc", "ifbegin");
        addInfer("if.nested", "ifbegin x greaterThan 0 999\nifbegin y greaterThan 0 999\nset z 1\nblockend\nblockend\nprint z\n", "ifbegin x greaterThan 0", "ifbegin");
        addInfer("if.nestedwhile", "ifbegin x greaterThan 0 999\nwhilebegin y greaterThan 0 999\nset y 0\nblockend\nblockend\nprint z\n", "whilebegin y greaterThan 0", "whilebegin");

        addInfer("while.lt", "whilebegin x lessThan 10 999\nset y 1\nblockend\nprint z\n", "whilebegin x lessThan 10", "whilebegin");
        addInfer("while.neq", "whilebegin x notEqual false 999\nset x 0\nblockend\nprint x\n", "whilebegin x notEqual false", "whilebegin");
        addInfer("while.legacy", "whilebegin x 999\nset x 0\nblockend\nprint x\n", "whilebegin x", "whilebegin");
        addInfer("while.expr", "whilebegin expr \"x < 10\" 999\nset x 0\nblockend\nprint x\n", "whilebegin expr", "whilebegin");
        addInfer("while.exprland", "whilebegin expr \"x < 10 && ready\" 999\nset x 0\nblockend\nprint x\n", "whilebegin expr", "whilebegin");
        addInfer("while.exprsc", "whilebegin exprsc \"a < b\" 999\nset x 0\nblockend\nprint x\n", "whilebegin exprsc", "whilebegin");
        addInfer("while.exprscor", "whilebegin exprsc \"a < b || ready\" 999\nset x 0\nblockend\nprint x\n", "whilebegin exprsc", "whilebegin");
        addInfer("while.continue", "whilebegin exprsc \"a < b\" 999\nset x 0\ncontinue\nblockend\nprint x\n", "continue", "whilebegin");
        addInfer("while.ifcontinue", "whilebegin x lessThan 10 999\nifbegin y equal 1 999\ncontinue\nblockend\nset x 0\nblockend\n", "continue", "whilebegin");
        addInfer("while.ifbreak", "whilebegin x lessThan 10 999\nifbegin y equal 1 999\nbreak\nblockend\nset x 0\nblockend\n", "break", "whilebegin");
        addInfer("while.break", "whilebegin x lessThan 10 999\nbreak\nblockend\n", "break", "whilebegin");
        addInfer("while.nestedifelse", "whilebegin x lessThan 10 999\nifbegin y equal 1 999\nset z 1\nelse\nset z 2\nblockend\nset x 0\nblockend\n", "else", "whilebegin");

        addInfer("for.lt", "forbegin i 0 1 lessThan 10 999\nset y i\nblockend\nprint y\n", "forbegin i 0 1 lessThan 10", "forbegin");
        addInfer("for.lte", "forbegin i 0 1 lessThanEq 10 999\nset y i\nblockend\nprint y\n", "forbegin i 0 1 lessThanEq 10", "forbegin");
        addInfer("for.gt", "forbegin i 10 -1 greaterThan 0 999\nset y i\nblockend\nprint y\n", "forbegin i 10 -1 greaterThan 0", "forbegin");
        addInfer("for.expr", "forbegin i 0 1 expr \"i < 10\" 999\nset y i\nblockend\nprint y\n", "forbegin i 0 1 expr", "forbegin");
        addInfer("for.exprland", "forbegin i 0 1 expr \"i < n && ok\" 999\nset y i\nblockend\nprint y\n", "forbegin i 0 1 expr", "forbegin");
        addInfer("for.exprsc", "forbegin i 0 1 exprsc \"i < 10\" 999\nset y i\nblockend\nprint y\n", "forbegin i 0 1 exprsc", "forbegin");
        addInfer("for.exprscland", "forbegin i 0 1 exprsc \"i < 10 && ok\" 999\nset y i\nblockend\nprint y\n", "forbegin i 0 1 exprsc", "forbegin");
        addInfer("for.continue", "forbegin i 0 1 lessThan 10 999\ncontinue\nblockend\n", "continue", "forbegin");
        addInfer("for.break", "forbegin i 0 1 lessThan 10 999\nbreak\nblockend\n", "break", "forbegin");
        addInfer("for.ifcontinue", "forbegin i 0 1 lessThan 10 999\nifbegin i equal 5 999\ncontinue\nblockend\nset y i\nblockend\n", "continue", "forbegin");
        addInfer("for.nested", "forbegin i 0 1 lessThan 3 999\nforbegin j 0 1 lessThan 3 999\nset x i\nblockend\nblockend\n", "forbegin j 0 1 lessThan 3", "forbegin");
        addInfer("for.nestedwhile", "forbegin i 0 1 lessThan 3 999\nwhilebegin j lessThan 3 999\nset j 3\nblockend\nblockend\n", "whilebegin j lessThan 3", "whilebegin");

        addInfer("switch.one", "switchbegin x 999\ncase 1\nset y 1\nbreak\nblockend\nprint y\n", "switchbegin x", "switchbegin");
        addInfer("switch.two", "switchbegin x 999\ncase 1\nset y 1\nbreak\ncase 2\nset y 2\nbreak\nblockend\nprint y\n", "case 2", "switchbegin");
        addInfer("switch.three", "switchbegin x 999\ncase 1\nset y 1\nbreak\ncase 2\nset y 2\nbreak\ncase 3\nset y 3\nbreak\nblockend\nprint y\n", "case 3", "switchbegin");
        addInfer("switch.fallthrough", "switchbegin x 999\ncase 1\nset y 1\ncase 2\nset y 2\nbreak\nblockend\nprint y\n", "case 2", "switchbegin");
        addInfer("switch.nestedif", "switchbegin x 999\ncase 1\nifbegin y equal 1 999\nset z 1\nblockend\nbreak\nblockend\nprint z\n", "ifbegin y equal 1", "switchbegin");
        addInfer("switch.while", "whilebegin x lessThan 10 999\nswitchbegin x 999\ncase 1\nbreak\nblockend\nset x 0\nblockend\n", "switchbegin x", "switchbegin");

        addInfer("func.returnconst", "funcdef f ~ 999\nreturn \"1\"\nblockend\nset x 0\nfunccall f \"\" out\nprint out\n", "funcdef f", "funcdef");
        addInfer("func.returnparam", "funcdef f a 999\nreturn \"a + 1\"\nblockend\nset x 3\nfunccall f \"x\" out\nprint out\n", "return \"a + 1\"", "funcdef");
        addInfer("func.returnbinary", "funcdef f a,b 999\nreturn \"a * b + 1\"\nblockend\nset x 3\nfunccall f \"x, 2\" out\nprint out\n", "return \"a * b + 1\"", "funcdef");
        addInfer("func.void", "funcdef f a 999\nset y a\nblockend\nfunccall f \"1\" ~\nprint y\n", "funcdef f", "funcdef");
        addInfer("func.noparams", "funcdef f ~ 999\nset y 1\nblockend\nfunccall f \"\" ~\nprint y\n", "funcdef f", "funcdef");
        addInfer("func.if", "funcdef f a 999\nifbegin a greaterThan 0 999\nreturn \"a\"\nblockend\nreturn \"0\"\nblockend\nset x 1\nfunccall f \"x\" out\nprint out\n", "ifbegin a greaterThan 0", "funcdef");
        addInfer("func.while", "funcdef f a 999\nwhilebegin a lessThan 3 999\nset a 3\nblockend\nreturn \"a\"\nblockend\nfunccall f \"0\" out\nprint out\n", "whilebegin a lessThan 3", "funcdef");
        addInfer("func.for", "funcdef f a 999\nforbegin i 0 1 lessThan 3 999\nset x i\nblockend\nblockend\nfunccall f \"0\" ~\nprint x\n", "forbegin i 0 1 lessThan 3", "funcdef");
        addInfer("func.switch", "funcdef f a 999\nswitchbegin a 999\ncase 1\nreturn \"1\"\ncase 2\nreturn \"2\"\nblockend\nreturn \"0\"\nblockend\nfunccall f \"1\" out\nprint out\n", "switchbegin a", "funcdef");
        addInfer("func.break", "funcdef f a 999\nwhilebegin a lessThan 3 999\nifbegin a equal 1 999\nbreak\nblockend\nset a 3\nblockend\nreturn \"a\"\nblockend\nfunccall f \"0\" out\nprint out\n", "break", "funcdef");
        addInfer("func.continue", "funcdef f a 999\nforbegin i 0 1 lessThan 3 999\ncontinue\nblockend\nreturn \"a\"\nblockend\nfunccall f \"0\" out\nprint out\n", "continue", "funcdef");
        addInfer("func.callresult", "funcdef f a 999\nreturn \"a + 1\"\nblockend\nset x 3\nfunccall f \"x\" out\nprint out\n", "funccall f", "funcdef");
        addInfer("func.nestedcall", "funcdef f a 999\nreturn \"a + 1\"\nblockend\nfuncdef g b 999\nreturn \"f(b) + 1\"\nblockend\nset x 1\nfunccall g \"x\" out\nprint out\n", "funccall g", "funcdef");

        addInfer("collapsed.if", "ifbeginc x equal 1 999\nset y 1\nblockend\nprint y\n", "ifbeginc x equal 1", "ifbegin");
        addInfer("collapsed.while", "whilebeginc x lessThan 10 999\nset x 10\nblockend\nprint x\n", "whilebeginc x lessThan 10", "whilebegin");
        addInfer("collapsed.for", "forbeginc i 0 1 lessThan 3 999\nset y i\nblockend\nprint y\n", "forbeginc i 0 1 lessThan 3", "forbegin");
        addInfer("collapsed.switch", "switchbeginc x 999\ncase 1\nset y 1\nbreak\nblockend\nprint y\n", "switchbeginc x", "switchbegin");
        addInfer("collapsed.func", "funcdefc f a 999\nreturn \"a + 1\"\nblockend\nset x 1\nfunccall f \"x\" out\nprint out\n", "funcdefc f", "funcdef");

        // Opt-in no-carrier inference coverage for the shapes the decompiler can prove.
        // The carrier fixtures above are mandatory for every card; these lock the other half
        // of the reconstruction contract.  Data-declaration cards intentionally have no
        // inference fixture: they exist only in the carrier and must never be invented.
        addInferred("infer.if.native", "ifbegin x greaterThan 0 2\nset y 1\nblockend\nprint y\n", "ifbegin x greaterThan 0", "ifbegin");
        addInferred("infer.if.else", "ifbegin x greaterThan 0 6\nset y 1\nelif x lessThan 0\nset y 2\nelse\nset y 3\nblockend\nprint y\n", "elif x lessThan 0", "elif");
        addInferred("infer.if.exprsc", "ifbegin exprsc \"a && b\" 4\nset hit 1\nelse\nset hit 0\nblockend\n", "ifbegin exprsc", "exprsc");
        addInferred("infer.while.native", "whilebegin x greaterThan 0 2\nset x 0\nblockend\nprint x\n", "whilebegin x greaterThan 0", "whilebegin");
        addInferred("infer.while.exprsc", "whilebegin exprsc \"a < b\" 2\nset x 0\nblockend\nprint x\n", "whilebegin exprsc", "whilebegin");
        addInferred("infer.while.continue", "whilebegin exprsc \"a < b\" 3\nset x 0\ncontinue\nblockend\nprint x\n", "continue", "whilebegin");
        addInferred("infer.for.native", "forbegin i 0 1 lessThan 3 2\nset x i\nblockend\nprint x\n", "forbegin i 0 1 lessThan 3", "forbegin");
        addInferred("infer.for.exprsc", "forbegin i 0 1 exprsc \"i < 3\" 2\nprint i\nblockend\nprint x\n", "forbegin i 0 1 exprsc", "forbegin");
        addInferred("infer.switch", "switchbegin x 7\ncase 1\nset y 1\nbreak\ncase 2\nset y 2\nbreak\nblockend\nprint y\n", "switchbegin x", "switchbegin");
        addInferred("infer.nested", "whilebegin x greaterThan 0 5\nifbegin y equal 1 3\nset z 1\nblockend\nset x 0\nblockend\nprint z\n", "whilebegin x greaterThan 0", "whilebegin");
        addInferred("infer.func.return", "funcdef f a 2\nreturn \"a + 1\"\nblockend\nset x 3\nfunccall f \"x\" out\nprint out\n", "funcdef f", "funcdef");
        addInferred("infer.func.void", "funcdef f ~ 2\nset flag 1\nblockend\nfunccall f \"\" ~\nprint flag\n", "funcdef f", "funcdef");
        addInferred("infer.func.nested", "funcdef f a 2\nreturn \"a + 1\"\nblockend\nfuncdef g b 5\nreturn \"f(b) + 1\"\nblockend\nset x 1\nfunccall g \"x\" out\nprint out\n", "funcdef g", "funcdef");
        addInferred("infer.loop.break", "whilebegin x lessThan 10 4\nifbegin y equal 1 3\nbreak\nblockend\nset x 0\nblockend\n", "whilebegin x lessThan 10", "whilebegin");
    }

    private static void addDeclarationFixtures(){
        addCarrier("decl.array", "array buf cell1 0 8\nset x 1\n", "array buf cell1 0 8");
        addCarrier("decl.matrix", "matrix m cell1 0 2 2\nset x 1\n", "matrix m cell1 0 2 2");
        addCarrier("decl.arrayinit", "array buf cell1 0 8\narrayinit buf 1 2 3 ~ ~ ~ ~ ~\n", "arrayinit buf 1 2 3");
        addCarrier("decl.record", "record p hp team ~ ~ ~ ~ ~ ~\nset x 1\n", "record p hp team");
        addCarrier("decl.stack", "stack s cell1 0 4\nset x 1\n", "stack s cell1 0 4");
        addCarrier("decl.queue", "queue q cell1 0 4\nset x 1\n", "queue q cell1 0 4");
        addCarrier("decl.deque", "deque d cell1 0 4\nset x 1\n", "deque d cell1 0 4");
        addCarrier("decl.bitset", "bitset bits cell1 0 2\nset x 1\n", "bitset bits cell1 0 2");
        addCarrier("decl.map", "map m cell1 0 4\nset x 1\n", "map m cell1 0 4");
        addCarrier("decl.uset", "uset u cell1 0 4\nset x 1\n", "uset u cell1 0 4");
        addCarrier("decl.list", "list l cell1 0 8\nset x 1\n", "list l cell1 0 8");
        addCarrier("decl.heap", "heap h cell1 0 8\nset x 1\n", "heap h cell1 0 8");
        addCarrier("decl.chain", "chain c cell1 0 8\nset x 1\n", "chain c cell1 0 8");
        // 单行表达式卡的自描述标记（ExprStatement.cardMarkerPrefix）：注释不改变产物，
        // 但随载体保存下来，重开时由 ExprTextImport 把上一行还原成表达式卡。
        // 没有标记时单行卡与普通 set/op 积木在文本里无法区分，保存一次就会退化成积木。
        addCarrier("decl.exprcard", "stack s cell1 0 4\nset result 0\n"
            + ExprStatement.cardMarkerPrefix + "result \"0\"\n", ExprStatement.cardMarkerPrefix);
    }

    private static void addOperationFixtures(){
        String array = "array buf cell1 0 8\n";
        addCarrier("op.array.sum", array + "datacall array_sum r \"buf\"\n", "datacall array_sum r");
        addCarrier("op.array.avg", array + "datacall array_avg r \"buf\"\n", "datacall array_avg r");
        addCarrier("op.array.min", array + "datacall array_min r \"buf\"\n", "datacall array_min r");
        addCarrier("op.array.max", array + "datacall array_max r \"buf\"\n", "datacall array_max r");
        addCarrier("op.array.count", array + "datacall array_count r \"buf, 1\"\n", "datacall array_count r");
        addCarrier("op.array.indexof", array + "datacall array_find r \"buf, 1\"\n", "datacall array_find r");
        addCarrier("op.array.fill", array + "datacall array_fill ~ \"buf, 0\"\n", "datacall array_fill ~");
        addCarrier("op.array.copy", "array dst cell1 0 4\narray src cell1 8 4\ndatacall array_copy ~ \"dst, src\"\n", "datacall array_copy ~");
        addCarrier("op.array.sortasc", array + "datacall array_sort ~ \"buf\"\n", "datacall array_sort ~");
        addCarrier("op.array.sortdesc", array + "datacall array_sort_desc ~ \"buf\"\n", "datacall array_sort_desc ~");
        addCarrier("op.array.reverse", array + "datacall array_reverse ~ \"buf\"\n", "datacall array_reverse ~");
        addCarrier("op.array.replace", array + "datacall array_replace r \"buf, 1, 2\"\n", "datacall array_replace r");
        addCarrier("op.array.swap", array + "datacall array_swap ~ \"buf, 0, 1\"\n", "datacall array_swap ~");
        addCarrier("op.array.bsearch", array + "datacall array_lower_bound r \"buf, 1\"\n", "datacall array_lower_bound r");

        String stack = "stack s cell1 0 4\n";
        addCarrier("op.stack.push", stack + "datacall stack_push r \"s, 1\"\n", "datacall stack_push r");
        addCarrier("op.stack.pop", stack + "datacall stack_pop r \"s\"\n", "datacall stack_pop r");
        addCarrier("op.stack.peek", stack + "datacall stack_top r \"s\"\n", "datacall stack_top r");
        addCarrier("op.stack.size", stack + "datacall stack_size r \"s\"\n", "datacall stack_size r");
        addCarrier("op.stack.clear", stack + "datacall stack_clear ~ \"s\"\n", "datacall stack_clear ~");

        String queue = "queue q cell1 0 4\n";
        addCarrier("op.queue.push", queue + "datacall queue_push r \"q, 1\"\n", "datacall queue_push r");
        addCarrier("op.queue.pop", queue + "datacall queue_pop r \"q\"\n", "datacall queue_pop r");
        addCarrier("op.queue.peek", queue + "datacall queue_front r \"q\"\n", "datacall queue_front r");
        addCarrier("op.queue.size", queue + "datacall queue_size r \"q\"\n", "datacall queue_size r");
        addCarrier("op.queue.clear", queue + "datacall queue_clear ~ \"q\"\n", "datacall queue_clear ~");

        String deque = "deque d cell1 0 4\n";
        addCarrier("op.deque.pushf", deque + "datacall deque_push_front r \"d, 1\"\n", "datacall deque_push_front r");
        addCarrier("op.deque.pushb", deque + "datacall deque_push_back r \"d, 2\"\n", "datacall deque_push_back r");
        addCarrier("op.deque.popf", deque + "datacall deque_pop_front r \"d\"\n", "datacall deque_pop_front r");
        addCarrier("op.deque.popb", deque + "datacall deque_pop_back r \"d\"\n", "datacall deque_pop_back r");
        addCarrier("op.deque.peekf", deque + "datacall deque_front r \"d\"\n", "datacall deque_front r");
        addCarrier("op.deque.peekb", deque + "datacall deque_back r \"d\"\n", "datacall deque_back r");
        addCarrier("op.deque.size", deque + "datacall deque_size r \"d\"\n", "datacall deque_size r");
        addCarrier("op.deque.clear", deque + "datacall deque_clear ~ \"d\"\n", "datacall deque_clear ~");

        String bitset = "bitset bits cell1 0 2\n";
        addCarrier("op.bitset.set", bitset + "datacall bitset_set r \"bits, 1\"\n", "datacall bitset_set r");
        addCarrier("op.bitset.clr", bitset + "datacall bitset_reset r \"bits, 1\"\n", "datacall bitset_reset r");
        addCarrier("op.bitset.test", bitset + "datacall bitset_test r \"bits, 1\"\n", "datacall bitset_test r");
        addCarrier("op.bitset.count", bitset + "datacall bitset_count r \"bits\"\n", "datacall bitset_count r");

        String map = "map m cell1 0 4\n";
        addCarrier("op.map.set", map + "datacall map_set r \"m, 1, 2\"\n", "datacall map_set r");
        addCarrier("op.map.get", map + "datacall map_get r \"m, 1\"\n", "datacall map_get r");
        addCarrier("op.map.has", map + "datacall map_contains r \"m, 1\"\n", "datacall map_contains r");
        addCarrier("op.map.del", map + "datacall map_erase r \"m, 1\"\n", "datacall map_erase r");
        addCarrier("op.map.size", map + "datacall map_size r \"m\"\n", "datacall map_size r");
        addCarrier("op.map.clear", map + "datacall map_clear ~ \"m\"\n", "datacall map_clear ~");

        String uset = "uset u cell1 0 4\n";
        addCarrier("op.uset.add", uset + "datacall set_add r \"u, 1\"\n", "datacall set_add r");
        addCarrier("op.uset.has", uset + "datacall set_contains r \"u, 1\"\n", "datacall set_contains r");
        addCarrier("op.uset.del", uset + "datacall set_remove r \"u, 1\"\n", "datacall set_remove r");
        addCarrier("op.uset.size", uset + "datacall set_size r \"u\"\n", "datacall set_size r");
        addCarrier("op.uset.clear", uset + "datacall set_clear ~ \"u\"\n", "datacall set_clear ~");

        String list = "list l cell1 0 8\n";
        addCarrier("op.list.append", list + "datacall vector_push_back r \"l, 1\"\n", "datacall vector_push_back r");
        addCarrier("op.list.get", list + "datacall vector_at r \"l, 0\"\n", "datacall vector_at r");
        addCarrier("op.list.set", list + "datacall vector_set r \"l, 0, 2\"\n", "datacall vector_set r");
        addCarrier("op.list.insert", list + "datacall vector_insert r \"l, 0, 2\"\n", "datacall vector_insert r");
        addCarrier("op.list.remove", list + "datacall vector_erase r \"l, 0\"\n", "datacall vector_erase r");
        addCarrier("op.list.find", list + "datacall vector_find r \"l, 2\"\n", "datacall vector_find r");
        addCarrier("op.list.size", list + "datacall vector_size r \"l\"\n", "datacall vector_size r");

        String heap = "heap h cell1 0 8\n";
        addCarrier("op.heap.push", heap + "datacall heap_push r \"h, 1\"\n", "datacall heap_push r");
        addCarrier("op.heap.pop", heap + "datacall heap_pop r \"h\"\n", "datacall heap_pop r");
        addCarrier("op.heap.size", heap + "datacall heap_size r \"h\"\n", "datacall heap_size r");

        String chain = "chain c cell1 0 8\n";
        addCarrier("op.chain.init", chain + "datacall chain_init r \"c\"\n", "datacall chain_init r");
        addCarrier("op.chain.clear", chain + "datacall chain_clear r \"c\"\n", "datacall chain_clear r");
        addCarrier("op.chain.new", chain + "datacall chain_alloc r \"c\"\n", "datacall chain_alloc r");
        addCarrier("op.chain.free", chain + "datacall chain_free r \"c, 0\"\n", "datacall chain_free r");
        addCarrier("op.chain.get", chain + "datacall chain_get r \"c, 0\"\n", "datacall chain_get r");
        addCarrier("op.chain.set", chain + "datacall chain_set r \"c, 0, 5\"\n", "datacall chain_set r");
        addCarrier("op.chain.next", chain + "datacall chain_next r \"c, 0\"\n", "datacall chain_next r");
        addCarrier("op.chain.link", chain + "datacall chain_link r \"c, 0, 1\"\n", "datacall chain_link r");
        addCarrier("op.chain.sethead", chain + "datacall chain_set_head ~ \"c, 0\"\n", "datacall chain_set_head ~");
        addCarrier("op.chain.head", chain + "datacall chain_head r \"c\"\n", "datacall chain_head r");
        addCarrier("op.chain.len", chain + "datacall chain_len r \"c\"\n", "datacall chain_len r");
    }

    /**
     * Assertion/debug cards are part of the current block set.  They serialize as
     * pseudo-instructions and are stripped by default; the carrier still has to restore
     * every one of them under the same verify gate.
     */
    private static void addAssertionFixtures(){
        addCarrier("assert.bounds", "assertBounds integer 2 0 lessThanEq index lessThanEq 10 \"msg\"\nset x 1\n", "assertBounds integer");
        addCarrier("assert.equals", "assertequals 0 i \"should be 0\"\nset x 1\n", "assertequals 0 i");
        addCarrier("assert.flush", "assertflush position\nset x 1\n", "assertflush position");
        addCarrier("assert.prints", "assertprints position \"frog\" \"bad output\"\nset x 1\n", "assertprints position");
        addCarrier("assert.type", "asserttype @unit unit \"should be a unit\"\nset x 1\n", "asserttype @unit unit");
        addCarrier("assert.error", "error \"Runtime error at #[[1]\" @counter null null null null null null null null\nset x 1\n", "error \"Runtime error at");
        addCarrier("assert.log", "log info \"Logging a message at #[[1]\" @counter null null null null null null null null\nset x 1\n", "log info");
        addCarrier("assert.breakpoint", "breakpoint always x false\nset x 1\n", "breakpoint always x false");
    }

    /**
     * Fails the matrix when a new intrinsic operation card is registered without a
     * reconstruction fixture.  This is the cheap half of the mandatory-reconstruction rule:
     * lowering a new {@code datacall} operation is not done until it has a carrier fixture.
     */
    private static void assertEveryPaletteOperationHasFixture(){
        Pattern pattern = Pattern.compile("datacall\\s+(\\S+)");
        Set<String> covered = new HashSet<>();
        for(Fixture fixture : fixtures){
            Matcher matcher = pattern.matcher(fixture.sugar);
            while(matcher.find()){
                covered.add(matcher.group(1).toLowerCase(Locale.ROOT));
            }
        }
        for(String operation : DataModules.paletteCalls().keySet()){
            check(covered.contains(operation),
                "no reconstruction fixture for registered data operation '" + operation + "'");
        }
    }



    private static void addCarrier(String name, String sugar, String expected){
        fixtures.add(new Fixture(name, sugar, expected, false, null));
    }

    private static void addInfer(String name, String sugar, String expected, String inferExpected){
        // Carrier coverage is mandatory for every shape.  Inference is intentionally opt-in
        // through addInferred() below: the decompiler only rewrites patterns it can prove.
        fixtures.add(new Fixture(name, sugar, expected, false, inferExpected));
    }

    private static void addInferred(String name, String sugar, String expected, String inferExpected){
        fixtures.add(new Fixture(name, sugar, expected, true, inferExpected));
    }

    private static void checkFixture(Fixture fixture){
        String compiled;
        try{
            compiled = compile(fixture.sugar);
        }catch(Throwable t){
            throw new AssertionError("[" + fixture.name + "] compile failed: " + t.getMessage(), t);
        }
        check(SugarCompiler.isSugarProgram(compiled), "[" + fixture.name + "] no carrier was written");
        String restored = SugarCompiler.restore(compiled);
        check(restored != null && restored.contains(fixture.expected),
            "[" + fixture.name + "] carrier restore lost expected text. Expected: " + fixture.expected
                + "\nRestored:\n" + restored);
        check(SugarCompiler.verifyRestore(compiled, restored),
            "[" + fixture.name + "] verifyRestore rejected the restored carrier source");

        // Entry skip: every sugar program's product must keep the compiler's own skip line inside
        // main, and restore() must drop it again. Both halves matter and neither is visible from the
        // carrier alone:
        //   - without the skip in the product, execution starting at counter 0 runs off the end of
        //     main into the carrier that follows it, re-executing the carrier every tick;
        //   - if restore() handed the skip back, the editor would show a line the user never wrote.
        // The skip is not necessarily the *last* statement of main: hoisted function bodies (and the
        // jump that skips over them) are appended after it, so this checks for presence in main
        // rather than position. Presence is still enough for the property that matters - the skip
        // resets the counter, so nothing after main can ever be reached. Only products that carry
        // sugar get the line at all; a program with no sugar cards returns early (see
        // SugarCompiler.compile), and the carrier check above already proved this fixture has one.
        String mainOnly = stripCarrierAndMarkers(compiled);
        check(containsEntrySkipLine(mainOnly),
            "[" + fixture.name + "] the product's main lost the entry skip line, so the carrier that "
                + "follows it would be re-executed every tick.\nMain only:\n" + mainOnly);
        check(!containsEntrySkipLine(restored),
            "[" + fixture.name + "] restore() must drop the compiler's own entry skip line.\nRestored:\n" + restored);

        SugarDecompiler.Result carrier = SugarDecompiler.decompile(compiled, true);
        check(carrier.verified && "carrier".equals(carrier.matchedMode),
            "[" + fixture.name + "] decompiler did not take the carrier path: mode=" + carrier.matchedMode
                + " notes=" + carrier.notes);
        check(carrier.sugar.contains(fixture.expected),
            "[" + fixture.name + "] carrier decompile lost expected text. Expected: " + fixture.expected
                + "\nRecovered:\n" + carrier.sugar);

        if(fixture.infer){
            String stripped = stripCarrierAndMarkers(compiled);
            SugarDecompiler.Result inferred = SugarDecompiler.decompile(stripped);
            check(inferred.verified,
                "[" + fixture.name + "] stripped-carrier inference failed verification: " + inferred.notes);
            check(inferred.sugar.contains(fixture.inferExpected),
                "[" + fixture.name + "] inference lost expected text: " + fixture.inferExpected
                    + "\nRecovered:\n" + inferred.sugar);
            checks += 2;
        }
        checks += 6;
    }

    private static String compile(String sugar){
        return SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
    }

    /** Whether any line of {@code text} is exactly the compiler's own entry skip statement. */
    private static boolean containsEntrySkipLine(String text){
        for(String line : text.replace("\r\n", "\n").split("\n", -1)){
            if(line.trim().equals(SugarCompiler.entrySkipLine)) return true;
        }
        return false;
    }

    private static String stripCarrierAndMarkers(String code){
        StringBuilder out = new StringBuilder();
        boolean marker = false;
        for(String line : code.replace("\r\n", "\n").split("\n", -1)){
            if(SugarCompiler.isMarkerBeginLine(line)){ marker = true; continue; }
            if(SugarCompiler.isMarkerEndLine(line)){ marker = false; continue; }
            if(marker || line.startsWith("set __ls_sugar \"") || line.startsWith("set __ls_lib \"")
                || line.startsWith("set __ls_sugar_") || line.startsWith("set __ls_lib_")) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }

    private static final class Fixture{
        final String name;
        final String sugar;
        final String expected;
        final boolean infer;
        final String inferExpected;

        Fixture(String name, String sugar, String expected, boolean infer, String inferExpected){
            this.name = name;
            this.sugar = sugar;
            this.expected = expected;
            this.infer = infer;
            this.inferExpected = inferExpected;
        }
    }
}
