package logicsugar.assist.data;

import arc.struct.Seq;
import logicsugar.assist.expr.ExprCompiler;
import mindustry.Vars;
import mindustry.logic.GlobalVars;
import mindustry.logic.LAssembler;
import mindustry.logic.LStatement;
import mindustry.logic.SugarCompiler;
import mindustry.logic.SugarStatements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 记录（record）模块自测（{@link RecordModule} + {@link logicsugar.assist.expr.RecordIntrinsics}）。
 *
 * <p>覆盖：声明卡定长往返；成员读/写与多字段；{@code unit.health} 等 sensor 成员不受影响；
 * 未声明 record 的成员访问继续走 sensor；未知字段报错；名字/字段校验（非法、保留前缀、
 * 重名、与数组/矩阵/函数冲突、空字段、超过 8 个字段）；声明卡不产指令；产物逐行纯原版
 * （stripMarkers + opcode 白名单）；载体往返（compile→restore→再 compile 一致）；
 * 编辑期标红；编译期上下文不泄漏。</p>
 */
public class RecordTest{
    public static void main(String[] args){
        SugarStatements.installParsers();
        Vars.logicVars = new GlobalVars();
        DataModules.clearModules();
        DataModules.register(new RecordModule());
        DataModules.registerParsers();

        declarationCardFormat();
        memberRead();
        memberWrite();
        sensorMembersUnaffected();
        unknownFieldErrors();
        nameAndFieldValidation();
        declarationCardProducesNoLine();
        compilerReadsInConditions();
        recordInsideFunctionBody();
        outputIsPureVanilla();
        roundTripAndVerification();
        editorMarkInvalid();
        contextLifecycle();

        DataModules.clearModules();
        System.out.println("LogicSugar Record self-test passed.");
    }

    // ===== 声明卡 =====

    private static void declarationCardFormat(){
        Seq<LStatement> statements = LAssembler.read("record p hp mp x y z w v u\n", true);
        check(statements.size == 1, "record card did not parse");
        check(statements.get(0) instanceof RecordModule.RecordStatement, "record card parsed into the wrong type");
        RecordModule.RecordStatement card = (RecordModule.RecordStatement)statements.get(0);
        check("p".equals(card.name), "record name lost: " + card.name);
        check(card.fieldList().equals(Arrays.asList("hp", "mp", "x", "y", "z", "w", "v", "u")),
            "record fields lost: " + card.fieldList());
        check("record".equals(card.typeName()), "wrong typeName: " + card.typeName());
        check(writeOf(card).equals("record p hp mp x y z w v u"), "card text drifted: " + writeOf(card));

        // ~ 槽位：跳过空槽，卡片仍写满 8 个槽（固定 10 token）
        Seq<LStatement> sparse = LAssembler.read("record p a b ~ ~ ~ ~ ~ ~\n", true);
        RecordModule.RecordStatement sparseCard = (RecordModule.RecordStatement)sparse.get(0);
        check(sparseCard.fieldList().equals(Arrays.asList("a", "b")), "sparse fields lost: " + sparseCard.fieldList());
        check(writeOf(sparseCard).equals("record p a b ~ ~ ~ ~ ~ ~"), "sparse card text drifted: " + writeOf(sparseCard));

        // 解析器把声明卡收进 LogicIO（编辑器积木列表）——registerParsers 已执行
        check(LAssembler.customParsers.containsKey("record"), "record parser not registered");
    }

    // ===== 成员读 / 写 =====

    private static void memberRead(){
        withRecords("record p f1 f2 f3 ~ ~ ~ ~ ~", () -> {
            checkLine("op add x p_f1 0", textOf(ExprCompiler.compile("x", "p.f1")));
            checkLine("op add x p_f2 0", textOf(ExprCompiler.compile("x", "p.f2")));
            checkLine("op add x p_f3 0", textOf(ExprCompiler.compile("x", "p.f3")));
            // 与其它运算组合：成员读先发一条 op add，再参与外层表达式
            checkLine("op add _0 p_f1 0\nop add _1 p_f2 0\nop add x _0 _1",
                textOf(ExprCompiler.compile("x", "p.f1 + p.f2")));
            checkLine("op add _0 p_f1 0\nop mul x _0 2", textOf(ExprCompiler.compile("x", "p.f1 * 2")));
            // 字段名大小写敏感：未知成员报错
            checkThrows(() -> ExprCompiler.compile("x", "p.F1"), "uppercase field name");
        });
        // 8 个字段全部可读
        withRecords("record p f1 f2 f3 f4 f5 f6 f7 f8", () -> {
            for(int i = 1; i <= 8; i++){
                checkLine("op add x p_f" + i + " 0", textOf(ExprCompiler.compile("x", "p.f" + i)));
            }
        });
    }

    private static void memberWrite(){
        withRecords("record p f1 f2 ~ ~ ~ ~ ~ ~", () -> {
            // 成员写：op add <name>_<field> <value> 0
            checkLine("op add p_f1 5 0", textOf(ExprCompiler.compile("p.f1", "5")));
            checkLine("op add p_f1 x 0", textOf(ExprCompiler.compile("p.f1", "x")));
            // 右侧是表达式：先编译 value，再写入字段变量
            checkLine("op add _0 a 1\nop add p_f1 _0 0", textOf(ExprCompiler.compile("p.f1", "a + 1")));
            // 右侧引用另一个字段（临时变量按 TempStack 规则复用）
            checkLine("op add _0 p_f1 0\nop mul _0 _0 2\nop add p_f2 _0 0",
                textOf(ExprCompiler.compile("p.f2", "p.f1 * 2")));
            checkThrows(() -> ExprCompiler.compile("p.nosuch", "1"), "write to an unknown field");
        });
        // 8 个字段全部可写
        withRecords("record p f1 f2 f3 f4 f5 f6 f7 f8", () -> {
            for(int i = 1; i <= 8; i++){
                checkLine("op add p_f" + i + " 7 0", textOf(ExprCompiler.compile("p.f" + i, "7")));
            }
        });
    }

    // ===== sensor 兼容 =====

    private static void sensorMembersUnaffected(){
        // 没有任何 record 声明：p.x 走原版 sensor 成员
        checkLine("sensor x p @x", textOf(ExprCompiler.compile("x", "p.x")));
        checkLine("sensor x unit @health", textOf(ExprCompiler.compile("x", "unit.health")));

        withRecords("record p f1 f2 ~ ~ ~ ~ ~ ~", () -> {
            // 声明了 record p：其它名字的 sensor 成员不受影响
            checkLine("sensor x unit @health", textOf(ExprCompiler.compile("x", "unit.health")));
            checkLine("sensor x q @health", textOf(ExprCompiler.compile("x", "q.health")));
            // 名字大小写不同 → 不是 record 变量，继续走 sensor
            checkLine("sensor x P @health", textOf(ExprCompiler.compile("x", "P.health")));
            // 同一个表达式里混用 sensor 成员与 record 字段
            checkLine("sensor _0 unit @health\nop add _1 p_f1 0\nop add x _0 _1",
                textOf(ExprCompiler.compile("x", "unit.health + p.f1")));
            // 已声明 record 的未知成员按笔误报错，不静默退回 sensor
            checkThrows(() -> ExprCompiler.compile("x", "p.health"), "record variable with a sensor-like unknown member");
        });
    }

    private static void unknownFieldErrors(){
        checkThrows(() -> ExprCompiler.compile("x", "p.nosuch"), "undeclared base with unknown member");
        withRecords("record p f1 ~ ~ ~ ~ ~ ~ ~", () -> {
            checkErrorContains(() -> ExprCompiler.compile("x", "p.nosuch"), "has no field", "unknown field read");
            checkErrorContains(() -> ExprCompiler.compile("p.nosuch", "1"), "has no field", "unknown field write");
        });
    }

    // ===== 声明校验 =====

    private static void nameAndFieldValidation(){
        // 名字：空 / 非法标识符 / 保留前缀 / 重名
        checkCompileThrows("record ~ f1 ~ ~ ~ ~ ~ ~ ~\nset x 1\n", "empty record name");
        checkErrorContains(() -> compile("record ~ f1 ~ ~ ~ ~ ~ ~ ~\nset x 1\n"),
            "record name must not be empty", "empty record name");
        checkCompileThrows("record 1abc f1 ~ ~ ~ ~ ~ ~ ~\nset x 1\n", "illegal record name");
        checkCompileThrows("record a-b f1 ~ ~ ~ ~ ~ ~ ~\nset x 1\n", "illegal record name with '-'");
        checkCompileThrows("record __ls_x f1 ~ ~ ~ ~ ~ ~ ~\nset x 1\n", "reserved record name");
        checkCompileThrows("record p a ~ ~ ~ ~ ~ ~ ~\nrecord p b ~ ~ ~ ~ ~ ~ ~\nset x 1\n", "duplicate record name");

        // 名字：与数组 / 矩阵 / 函数冲突
        checkCompileThrows("array p cell1 0 8\nrecord p a ~ ~ ~ ~ ~ ~ ~\nset x 1\n", "record name conflicts with an array");
        checkCompileThrows("matrix p cell1 0 2 2\nrecord p a ~ ~ ~ ~ ~ ~ ~\nset x 1\n", "record name conflicts with a matrix");
        checkCompileThrows("funcdef p x 3\nop add __ls_t x 1\nreturn \"__ls_t\"\nblockend\n"
            + "record p a ~ ~ ~ ~ ~ ~ ~\nset x 1\n", "record name conflicts with a function");

        // 字段：非法名 / 同一 record 内重名 / 空字段
        checkCompileThrows("record p a-b ~ ~ ~ ~ ~ ~ ~\nset x 1\n", "illegal field name");
        checkCompileThrows("record p a a ~ ~ ~ ~ ~ ~\nset x 1\n", "duplicate field name");
        checkErrorContains(() -> compile("record p a a ~ ~ ~ ~ ~ ~\nset x 1\n"), "duplicate field", "duplicate field");
        checkCompileThrows("record p ~ ~ ~ ~ ~ ~ ~ ~\nset x 1\n", "record with no fields");
        checkErrorContains(() -> compile("record p ~ ~ ~ ~ ~ ~ ~ ~\nset x 1\n"),
            "at least one field", "record with no fields");

        // 字段存储变量 <name>_<field> 的冲突
        checkCompileThrows("array p_a cell1 0 8\nrecord p a ~ ~ ~ ~ ~ ~ ~\nset x 1\n",
            "field variable conflicts with an array");
        checkCompileThrows("record p a ~ ~ ~ ~ ~ ~ ~\nrecord p_a b ~ ~ ~ ~ ~ ~ ~\nset x 1\n",
            "field variable conflicts with a record name");
        checkCompileThrows("record a b_c ~ ~ ~ ~ ~ ~ ~\nrecord a_b c ~ ~ ~ ~ ~ ~ ~\nset x 1\n",
            "two records declaring the same field variable");

        // 超过 8 个字段：卡片只有 8 个槽位，用注册表 API 直接验证上限
        RecordModule.RecordStatement nine = new RecordModule.RecordStatement();
        nine.name = "p";
        nine.fields.clear();
        for(int i = 0; i < 9; i++){
            nine.fields.add("f" + i);
        }
        List<LStatement> statements = new ArrayList<>();
        statements.add(nine);
        checkErrorContains(() -> RecordModule.compileRegistry(statements, null), "at most 8", "nine fields");

        // 合法声明（含 ~ 槽位）不报错
        compile("record p a b ~ ~ ~ ~ ~ ~\nset x 1\n");
    }

    // ===== 产物 =====

    private static void declarationCardProducesNoLine(){
        String sugar = "record p f1 f2 ~ ~ ~ ~ ~ ~\nset x 1\n";
        String mlog = stripCompile(sugar);
        check(!mlog.contains("record"), "declaration card leaked into the product:\n" + mlog);
        check(mlog.contains("set x 1"), "regular instruction lost:\n" + mlog);
        // 只有声明卡的程序同样不产卡片文本
        String only = stripCompile("record p f1 f2 ~ ~ ~ ~ ~ ~\n");
        check(!only.contains("record"), "only-card program leaked the card:\n" + only);
    }

    private static void compilerReadsInConditions(){
        String sugar = "record p f1 f2 ~ ~ ~ ~ ~ ~\n"
            + "ifbegin expr \"p.f1 > p.f2\" 3\n"
            + "set x 1\n"
            + "blockend\n";
        String mlog = stripCompile(sugar);
        check(mlog.contains("op add __ls_cond_1 p_f1 0"), "record read missing in the lowered condition:\n" + mlog);
        check(mlog.contains("op add __ls_cond_11 p_f2 0"), "second record read missing in the lowered condition:\n" + mlog);
        check(mlog.contains("op greaterThan __ls_cond_1 __ls_cond_1 __ls_cond_11"),
            "condition comparison missing:\n" + mlog);
        check(!mlog.contains("ifbegin") && !mlog.contains("record "), "sugar residue in the product:\n" + mlog);
    }

    /** 记录字段在函数体内的条件表达式里同样展开（函数前缀的临时变量命名空间）。 */
    private static void recordInsideFunctionBody(){
        String sugar = "record p f1 f2 ~ ~ ~ ~ ~ ~\n"
            + "funcdef readp x 6\n"
            + "ifbegin expr \"p.f1 > x\" 4\n"
            + "set __ls_r x\n"
            + "blockend\n"
            + "return \"__ls_r\"\n"
            + "blockend\n"
            + "funccall readp 1 r\n";
        String mlog = stripCompile(sugar);
        check(mlog.contains("p_f1"), "record read inside a function body missing:\n" + mlog);
        check(!mlog.contains("record ") && !mlog.contains("ifbegin"), "sugar residue in the function-body product:\n" + mlog);
    }

    private static void outputIsPureVanilla(){
        String sugar = "record p f1 f2 ~ ~ ~ ~ ~ ~\n"
            + "ifbegin expr \"p.f1 > p.f2\" 3\n"
            + "set x 1\n"
            + "blockend\n"
            + "ifbegin expr \"unit.health > 0\" 6\n"
            + "set y 2\n"
            + "blockend\n";
        String mlog = stripCompile(sugar);
        for(String line : mlog.replace("\r\n", "\n").split("\n", -1)){
            String t = line.trim();
            if(t.isEmpty() || t.endsWith(":") || t.startsWith("#")) continue;
            int space = t.indexOf(' ');
            String opcode = space < 0 ? t : t.substring(0, space);
            check(vanillaOpcodes.contains(opcode), "non-vanilla instruction in product: " + t);
        }
    }

    private static void roundTripAndVerification(){
        String sugar = "record p f1 f2 ~ ~ ~ ~ ~ ~\n"
            + "ifbegin expr \"p.f1 > p.f2\" 3\n"
            + "set x 1\n"
            + "blockend\n";
        String compiled = compile(sugar);
        check(SugarCompiler.restore(compiled).equals(sugar),
            "carrier did not preserve the record source:\n" + SugarCompiler.restore(compiled));
        check(SugarCompiler.verifyRestore(compiled, sugar), "verifyRestore rejected a record program");
        String recompiled = compile(SugarCompiler.restore(compiled));
        check(SugarCompiler.matchesStoredStream(recompiled, compiled),
            "recompiled record program drifted from the stored stream");
    }

    // ===== 编辑期与上下文 =====

    private static void editorMarkInvalid(){
        List<LStatement> statements = toList(LAssembler.read(
            "record p a a ~ ~ ~ ~ ~ ~\nrecord q b ~ ~ ~ ~ ~ ~ ~\nset x 1\n", true));
        boolean[] invalid = new boolean[statements.size()];
        RecordModule.markInvalidStatements(statements, invalid, null);
        check(invalid[0], "record with a duplicate field must be marked invalid");
        check(!invalid[1], "valid record card was marked invalid");
        check(!invalid[2], "non-record statement was marked invalid");

        // 接线：SugarCompiler.invalidStatements → DataModules.markInvalid → 本模块
        Seq<LStatement> good = LAssembler.read("record p a b ~ ~ ~ ~ ~ ~\nset x 1\n", true);
        boolean[] goodInvalid = SugarCompiler.invalidStatements(good);
        check(!goodInvalid[0], "valid record card marked invalid by the editor");
        Seq<LStatement> bad = LAssembler.read("record p ~ ~ ~ ~ ~ ~ ~ ~\nset x 1\n", true);
        boolean[] badInvalid = SugarCompiler.invalidStatements(bad);
        check(badInvalid[0], "field-less record card was not marked invalid by the editor");
    }

    private static void contextLifecycle(){
        // 编译结束后上下文必须清理（active() 回退到画布探测，无头环境为 null）
        stripCompile("record p f1 f2 ~ ~ ~ ~ ~ ~\nset x 1\n");
        check(RecordModule.active() == null, "compile leaked the record registry context");

        // enter/restore 可嵌套且成对恢复
        RecordModule.RecordRegistry outer = RecordModule.compileRegistry(
            toList(LAssembler.read("record p a ~ ~ ~ ~ ~ ~ ~\n", true)), null);
        RecordModule.RecordRegistry inner = RecordModule.compileRegistry(
            toList(LAssembler.read("record q b ~ ~ ~ ~ ~ ~ ~\n", true)), null);
        RecordModule.RecordRegistry previous = RecordModule.enter(outer);
        try{
            check(RecordModule.active() == outer, "enter did not install the registry");
            RecordModule.RecordRegistry previousInner = RecordModule.enter(inner);
            check(RecordModule.active() == inner, "nested enter did not install the registry");
            RecordModule.restore(previousInner);
            check(RecordModule.active() == outer, "nested restore did not pop the registry");
        }finally{
            RecordModule.restore(previous);
        }
        check(RecordModule.active() == null, "record context leaked after restore");
    }

    // ===== helpers =====

    private static final Set<String> vanillaOpcodes = new HashSet<>(Arrays.asList(
        "noop", "read", "write", "draw", "print", "printchar", "format", "drawflush", "printflush",
        "getlink", "control", "radar", "sensor", "set", "op", "select", "wait", "stop", "lookup",
        "packcolor", "unpackcolor", "end", "jump", "ubind", "ucontrol", "uradar", "ulocate",
        "query", "getblock", "setblock", "spawn", "bullet", "status", "weathersense", "weatherset",
        "spawnwave", "setrule", "message", "cutscene", "effect", "explosion", "setrate", "fetch",
        "sync", "clientdata", "getflag", "setflag", "setprop", "playsound", "playmusic",
        "setmarker", "makemarker", "localeprint"
    ));

    private static String compile(String sugar){
        return SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
    }

    private static String stripCompile(String sugar){
        return SugarCompiler.stripMarkers(compile(sugar));
    }

    private static String writeOf(RecordModule.RecordStatement card){
        StringBuilder builder = new StringBuilder();
        card.write(builder);
        return builder.toString();
    }

    private static List<LStatement> toList(Seq<LStatement> statements){
        List<LStatement> result = new ArrayList<>(statements.size);
        for(LStatement statement : statements){
            result.add(statement);
        }
        return result;
    }

    /** 在给定的 record 声明上下文中执行表达式层断言（模拟编译期注册表）。 */
    private static void withRecords(String declarations, Runnable body){
        RecordModule.RecordRegistry registry = RecordModule.compileRegistry(
            toList(LAssembler.read(declarations + "\n", true)), null);
        RecordModule.RecordRegistry previous = RecordModule.enter(registry);
        try{
            body.run();
        }finally{
            RecordModule.restore(previous);
        }
    }

    private static String textOf(List<ExprCompiler.Line> ops){
        StringBuilder out = new StringBuilder();
        for(int i = 0; i < ops.size(); i++){
            if(i > 0) out.append('\n');
            out.append(ops.get(i).toText());
        }
        return out.toString();
    }

    private static void checkCompileThrows(String sugar, String what){
        checkThrows(() -> compile(sugar), what);
    }

    private static void checkThrows(Runnable body, String what){
        try{
            body.run();
        }catch(ExprCompiler.ParseException e){
            return; // expected
        }catch(IllegalArgumentException e){
            return; // expected (registry validation)
        }
        check(false, "should have failed (" + what + ")");
    }

    private static void checkErrorContains(Runnable body, String needle, String what){
        try{
            body.run();
        }catch(RuntimeException e){
            String message = e.getMessage();
            check(message != null && message.contains(needle),
                "error message for " + what + " is missing '" + needle + "': " + message);
            return;
        }
        check(false, "should have failed (" + what + ")");
    }

    private static void checkLine(String expected, String actual){
        check(expected.equals(actual), "mlog mismatch\n  expected: " + expected + "\n  actual:   " + actual);
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
