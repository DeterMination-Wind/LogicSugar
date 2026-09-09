package logicsugar.assist.data;

import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import logicsugar.assist.expr.ArrayRegistry;
import logicsugar.assist.expr.ExprCompiler;
import logicsugar.assist.expr.ExprIntrinsics;
import logicsugar.assist.expr.FakeDataIntrinsics;
import mindustry.Vars;
import mindustry.logic.GlobalVars;
import mindustry.logic.LAssembler;
import mindustry.logic.LStatement;
import mindustry.logic.SugarCompiler;
import mindustry.logic.SugarFunctions;
import mindustry.logic.SugarStatements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * F2 数据框架自测（{@link ExprIntrinsics} + {@link DataModule}/{@link DataModules} +
 * {@link DataDeclaration}）。
 *
 * <p>用一个假模块（声明卡 + provider + 注入函数）验证框架契约：</p>
 * <ul>
 *   <li>intrinsic 调用展开（{@code fakeadd} → op 链，{@code fakedouble} → 注入函数调用）；</li>
 *   <li>成员读/写（{@code fx.v} 读、{@code fx.v = value} 写）；</li>
 *   <li>声明卡不产行（lower 跳过、产物无卡片文本）；</li>
 *   <li>内置函数注入（normal 共享一份、未使用不进产物、不进 __ls_lib 载体、paramsOf 可见）；</li>
 *   <li>用户函数优先（同名 funcdef 遮蔽 intrinsic）；</li>
 *   <li>strip/emit 行为一致、产物纯原版指令；</li>
 *   <li>DataModules collect/restore 配对（含异常路径）与 markInvalid 接线。</li>
 * </ul>
 *
 * <p>provider 实现放在 {@code logicsugar.assist.expr.FakeDataIntrinsics}（Node 是包私有类型，
 * 实现必须与被测框架同包）。</p>
 */
public class DataFrameworkSelfTest{
    public static void main(String[] args){
        SugarStatements.installParsers();
        Vars.logicVars = new GlobalVars();

        FakeModule module = new FakeModule();
        DataModules.clearModules();
        DataModules.register(module);
        module.registerParsers();

        intrinsicCalls();
        memberAccess();
        declarationCardProducesNoLine();
        builtinInjectionAndSharing();
        builtinDoesNotLeakIntoLibrary();
        unusedBuiltinStaysOut();
        userFunctionWins();
        stripAndEmitAgree();
        outputIsPureVanilla();
        moduleLifecycleAndMarkInvalid();

        DataModules.clearModules();
        System.out.println("LogicSugar DataFramework self-test passed.");
    }

    // ===== 表达式展开 =====

    private static void intrinsicCalls(){
        checkLine("op add x 2 3", textOf(ExprCompiler.compile("x", "fakeadd(2, 3)")));
        checkLine("op add _0 1 2\nop mul x _0 4",
            textOf(ExprCompiler.compile("x", "fakeadd(1, 2) * 4")));
        checkLine("funccall __ls_builtin_fakedouble \"4\" x", textOf(ExprCompiler.compile("x", "fakedouble(4)")));
        // 大小写不敏感
        checkLine("op add x 2 3", textOf(ExprCompiler.compile("x", "FakeAdd(2, 3)")));
        // 错误实参个数不是 intrinsic（落到普通 funccall，编译期报未定义函数）
        checkLine("funccall fakeadd \"1\" x", textOf(ExprCompiler.compile("x", "fakeadd(1)")));
        // 名字未被注册时保持既有未知函数行为
        check(ExprIntrinsics.isIntrinsic("fakeadd") && !ExprIntrinsics.isIntrinsic("nosuchintrinsic"),
            "isIntrinsic misclassifies names");
        check(ExprIntrinsics.isIntrinsicName("fakeadd", 2) && !ExprIntrinsics.isIntrinsicName("fakeadd", 3),
            "arity dispatch is broken");
    }

    private static void memberAccess(){
        checkLine("op add x 7 0", textOf(ExprCompiler.compile("x", "fx.v")));
        checkLine("op add __fake_fx_v 5 0", textOf(ExprCompiler.compile("fx.v", "5")));
        checkLine("op add _0 a 1\nop add __fake_fx_v _0 0",
            textOf(ExprCompiler.compile("fx.v", "a + 1")));
        // 未处理的成员名退回既有 sensor 解析（未知属性 → 编译错误）
        checkThrows(() -> ExprCompiler.compile("x", "fx.zzz"), "unknown member on an intrinsic base");
    }

    // ===== 声明卡 =====

    private static void declarationCardProducesNoLine(){
        String sugar = "fakestruct fx\nset x 1\n";
        String mlog = stripCompile(sugar);
        check(!mlog.contains("fakestruct"), "declaration card leaked into the product:\n" + mlog);
        check(mlog.contains("set x 1"), "regular instruction lost:\n" + mlog);
        check(SugarCompiler.restore(SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip)).equals(sugar),
            "declaration card did not survive the carrier round trip");
    }

    // ===== 内置函数注入 =====

    private static void builtinInjectionAndSharing(){
        String sugar = "fakestruct fx\n"
            + "ifbegin expr \"fakedouble(4) > 0\" 3\n"
            + "set x 1\n"
            + "blockend\n"
            + "ifbegin expr \"fakedouble(9) > 0\" 6\n"
            + "set y 2\n"
            + "blockend\n";
        String mlog = stripCompile(sugar);
        check(countOf(mlog, "__ls_func___ls_builtin_fakedouble_entry:") == 1,
            "builtin body must be hoisted exactly once:\n" + mlog);
        check(countOf(mlog, "jump __ls_func___ls_builtin_fakedouble_entry always x false") == 2,
            "each call site must jump to the shared entry:\n" + mlog);
        check(mlog.contains("set __ls_func___ls_builtin_fakedouble_x 4"), "argument binding missing:\n" + mlog);
        check(mlog.contains("op mul __ls_fake_out __ls_func___ls_builtin_fakedouble_x 2"),
            "builtin body was not mangled/bound correctly:\n" + mlog);
        check(!mlog.contains("fakestruct") && !mlog.contains("funccall ") && !mlog.contains("ifbegin "),
            "sugar residue in the product:\n" + mlog);
    }

    private static void builtinDoesNotLeakIntoLibrary(){
        String lib = "funcdef userfn x 3\n"
            + "op add __ls_dummy x 1\n"
            + "return \"__ls_dummy\"\n"
            + "blockend\n";
        SugarFunctions.SanitizedLibrary sanitized = SugarFunctions.sanitizedLibrary(lib);
        String sugar = "fakestruct fx\n"
            + "funccall userfn 1 r\n"
            + "ifbegin expr \"fakedouble(4) > 0\" 4\n"
            + "set x 1\n"
            + "blockend\n";
        String compiled = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, sanitized.index, sanitized.text,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        String embedded = SugarCompiler.libraryFromCode(compiled);
        check(embedded != null && embedded.contains("userfn"), "used user library function missing: " + embedded);
        check(embedded != null && !embedded.contains("__ls_builtin"), "builtin leaked into the library carrier: " + embedded);
        check(SugarCompiler.stripMarkers(compiled).contains("__ls_func___ls_builtin_fakedouble_entry:"),
            "builtin body missing from the product");
        check(DataModules.builtinFunctionNames().contains(FakeDataIntrinsics.BUILTIN_NAME),
            "builtin names are not exposed to the editor");
        check(SugarFunctions.paramsOf(FakeDataIntrinsics.BUILTIN_NAME, null) != null
            && SugarFunctions.paramsOf(FakeDataIntrinsics.BUILTIN_NAME, null).equals(Collections.singletonList("x")),
            "builtin params are not visible to paramsOf");
    }

    private static void unusedBuiltinStaysOut(){
        String mlog = stripCompile("fakestruct fx\nset x 1\n");
        check(!mlog.contains("__ls_builtin") && !mlog.contains("__ls_fake_out"),
            "unused builtin leaked into the product:\n" + mlog);
    }

    // ===== 用户函数优先 =====

    private static void userFunctionWins(){
        String sugar = "funcdef fakeadd a,b 3\n"
            + "op add __ls_user a b\n"
            + "return \"__ls_user\"\n"
            + "blockend\n"
            + "ifbegin expr \"fakeadd(1, 2) > 0\" 6\n"
            + "set x 1\n"
            + "blockend\n";
        String mlog = stripCompile(sugar);
        check(mlog.contains("__ls_func_fakeadd_entry:"), "user function was not hoisted:\n" + mlog);
        check(!mlog.contains("op add __ls_cond_4 1 2"), "intrinsic shadowed the user function:\n" + mlog);
        // 本地 funcdef 存在时，编辑器校验也把该名字当用户函数
        check(SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip).contains("__ls_func_fakeadd_entry:"),
            "user function priority lost");
    }

    // ===== strip / emit 与纯原版产物 =====

    private static void stripAndEmitAgree(){
        String sugar = "fakestruct fx\n"
            + "ifbegin expr \"fakedouble(4) > 0\" 3\n"
            + "set x 1\n"
            + "blockend\n";
        String strip = stripCompile(sugar, SugarCompiler.AssertEmit.strip);
        String emit = stripCompile(sugar, SugarCompiler.AssertEmit.emit);
        for(String mlog : new String[]{strip, emit}){
            check(mlog.contains("__ls_func___ls_builtin_fakedouble_entry:"),
                "builtin body missing from a lowering mode:\n" + mlog);
            check(!mlog.contains("fakestruct") && !mlog.contains("funccall ") && !mlog.contains("ifbegin "),
                "sugar residue in a lowering mode:\n" + mlog);
        }
        check(!emit.contains("assertBounds"), "no array subscript should emit a bounds assert:\n" + emit);
    }

    private static void outputIsPureVanilla(){
        String sugar = "fakestruct fx\n"
            + "ifbegin expr \"fakedouble(fakeadd(1, 2)) > 0\" 3\n"
            + "set x 1\n"
            + "blockend\n";
        String mlog = stripCompile(sugar, SugarCompiler.AssertEmit.strip);
        for(String line : mlog.replace("\r\n", "\n").split("\n", -1)){
            String t = line.trim();
            if(t.isEmpty() || t.endsWith(":") || t.startsWith("#")) continue;
            int space = t.indexOf(' ');
            String opcode = space < 0 ? t : t.substring(0, space);
            check(vanillaOpcodes.contains(opcode), "non-vanilla instruction in product: " + t);
        }
    }

    // ===== 模块生命周期与编辑期接线 =====

    private static void moduleLifecycleAndMarkInvalid(){
        FakeModule module = new FakeModule();
        DataModules.clearModules();
        DataModules.register(module);
        module.registerParsers();
        check(module.collected == 0 && module.restored == 0, "fresh module must start clean");
        check(DataModules.isCollecting() == false, "no compile context should be active outside collectAll");

        String good = "fakestruct fx\nifbegin expr \"fakeadd(1, 2) > 0\" 3\nset x 1\nblockend\n";
        stripCompile(good);
        check(module.collected == 1 && module.restored == 1,
            "collect/restore must pair once per compile (collected=" + module.collected + ", restored=" + module.restored + ")");
        check(!DataModules.isCollecting(), "restore must clear the compile context");
        // 编译期静态上下文不得泄漏：数组注册表回退到画布探测（无头环境为 null），
        // 用户函数遮蔽集合恢复为空
        check(ArrayRegistry.active() == null, "compile leaked the array registry context");
        check(ExprIntrinsics.isIntrinsicName("fakeadd", 2), "user function shadow context leaked out of the compile");

        // 异常路径（lower 阶段才失败）也必须 restore
        try{
            stripCompile("fakestruct fx\nifbegin expr \"fakeadd(1) > 0\" 3\nset x 1\nblockend\n");
            check(false, "wrong-arity intrinsic should fail the compile");
        }catch(RuntimeException expected){
            // expected
        }
        check(module.collected == 2 && module.restored == 2,
            "restore must run on the throwing path (collected=" + module.collected + ", restored=" + module.restored + ")");

        // 编辑期标红接线：markInvalid 被调用，且 intrinsic 名字在条件表达式里不标红
        Seq<LStatement> statements = LAssembler.read(good, true);
        boolean[] invalid = SugarCompiler.invalidStatements(statements);
        check(module.marked >= 1, "DataModules.markInvalid was not wired into invalidStatements");
        check(!invalid[1], "intrinsic name in a condition expression was marked invalid");
        check(!invalid[0], "fake declaration card was marked invalid");

        // 未定义函数仍然标红
        Seq<LStatement> bad = LAssembler.read("fakestruct fx\nfunccall nosuch ~\n", true);
        boolean[] badInvalid = SugarCompiler.invalidStatements(bad);
        check(badInvalid[1], "undefined function call was not marked invalid");
    }

    // ===== 假模块 =====

    /** 测试用假声明卡：只承载一个名字，lower 不产行。 */
    public static class FakeDeclStatement extends DataDeclaration{
        public static final String TOKEN = "fakestruct";
        public String name = "fx";

        @Override public String token(){ return TOKEN; }
        @Override public void build(Table table){}
        @Override public void write(StringBuilder out){ out.append(TOKEN).append(' ').append(name); }
    }

    /** 测试用假模块：无自有注册表，只提供 provider 与注入函数。 */
    public static class FakeModule extends DataModule{
        int collected, restored, marked;

        @Override public String id(){ return "fake-module"; }

        @Override public void registerParsers(){
            LAssembler.customParsers.put(FakeDeclStatement.TOKEN, tokens -> {
                FakeDeclStatement statement = new FakeDeclStatement();
                statement.name = tokens.length > 1 && tokens[1] != null ? tokens[1] : "fx";
                return statement;
            });
        }

        @Override public void collect(List<LStatement> statements, Set<String> functionNames){
            collected++;
        }

        @Override public void restore(){
            restored++;
        }

        @Override public void markInvalid(List<LStatement> statements, boolean[] invalid, Set<String> functionNames){
            marked++;
        }

        @Override public ExprIntrinsics.Provider intrinsics(){
            return FakeDataIntrinsics.INSTANCE;
        }

        @Override public List<String> builtinSugar(){
            return Collections.singletonList(FakeDataIntrinsics.BUILTIN_SUGAR);
        }
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

    private static String stripCompile(String sugar){
        return stripCompile(sugar, SugarCompiler.AssertEmit.strip);
    }

    private static String stripCompile(String sugar, SugarCompiler.AssertEmit emit){
        return SugarCompiler.stripMarkers(SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null,
            SugarCompiler.SwitchStrategy.auto, emit));
    }

    private static String textOf(List<ExprCompiler.Line> ops){
        StringBuilder out = new StringBuilder();
        for(int i = 0; i < ops.size(); i++){
            if(i > 0) out.append('\n');
            out.append(ops.get(i).toText());
        }
        return out.toString();
    }

    private static int countOf(String text, String needle){
        int count = 0, at = 0;
        while((at = text.indexOf(needle, at)) >= 0){
            count++;
            at += needle.length();
        }
        return count;
    }

    private static void checkThrows(Runnable body, String what){
        try{
            body.run();
        }catch(ExprCompiler.ParseException e){
            return; // expected
        }
        check(false, "expression should have failed (" + what + ")");
    }

    private static void checkLine(String expected, String actual){
        check(expected.equals(actual), "mlog mismatch\n  expected: " + expected + "\n  actual:   " + actual);
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
