package logicsugar.assist.data;

import logicsugar.LogicSugarMod;
import logicsugar.SourceNails;
import logicsugar.assist.expr.ExprIntrinsics;
import mindustry.gen.LogicIO;
import mindustry.logic.LAssembler;
import mindustry.logic.LStatement;
import mindustry.logic.SugarCompiler;
import mindustry.logic.SugarStatements;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/** Regression checks for persistent intrinsic operation cards. */
public final class DataCallTest{
    private DataCallTest(){}

    public static void main(String[] args) throws Exception{
        LogicSugarMod.registerStatements();
        String[] names = {
            "sum", "avg", "min", "max", "count", "indexof", "fill", "copy", "sortasc",
            "sortdesc", "reverse", "replace", "swap", "bsearch",
            "spush", "spop", "speek", "ssize", "sclear",
            "qpush", "qpop", "qpeek", "qsize", "qclear",
            "dpushf", "dpushb", "dpopf", "dpopb", "dpeekf", "dpeekb", "dsize", "dclear",
            "bset", "bclr", "btest", "bcount",
            "mapset", "mapget", "maphas", "mapdel", "mapsize", "mapclear",
            "uadd", "uhas", "udel", "usize", "uclear",
            "lappend", "lget", "lset", "linsert", "lremove", "lfind", "lsize",
            "hpush", "hpop", "hsize",
            "cinit", "cclear", "cnew", "cfree", "cget", "cset", "cnext", "clink",
            "cshead", "chead", "clen"
        };
        for(String name : names) check(DataModules.paletteCall(name) != null, "missing palette call " + name);
        check(names.length == 68 && DataModules.paletteCalls().size() == names.length,
            "palette must expose every intrinsic exactly once: " + DataModules.paletteCalls().keySet());
        check(DataModules.operationGroups().size() == 10,
            "expected one group per structure family, got " + DataModules.operationGroups().keySet());
        long paletteCards = LogicIO.allStatements.select(prov -> prov.get() instanceof DataCallStatement).size;
        check(paletteCards == DataModules.operationGroups().size(),
            "expected one data operation card per structure, got " + paletteCards);
        structureCardsCoverEveryOperation();
        structureCardsSitNextToTheirDeclaration();
        operationCardsShareTheirDeclarationColumn();
        operationLabelsAreTranslated();
        everyOperationBundleKeyIsReachable();
        declarationHintsUseTheOperationCardsLabels();
        argumentSlotsAreFixedAndLossless();
        operationCardsMarkInvalidArguments();
        reentrantValidationKeepsTheOuterContext();
        operationSwitchBehavior();
        check(DataModules.paletteCall("spush").category == SugarStatements.stackOps, "stack category missing");
        check(DataModules.paletteCall("qpop").category == SugarStatements.queueOps, "queue category missing");
        check(DataModules.paletteCall("dclear").category == SugarStatements.dequeOps, "deque category missing");
        check(DataModules.paletteCall("sum").category == SugarStatements.arrayAlgo, "array category missing");
        check(DataModules.paletteCall("fill").arguments.equals("buf, value"), "fill defaults must match array declaration");
        check(!DataModules.paletteCall("fill").returnsValue, "fill cards must be statement-like");
        check(DataModules.paletteCall("copy").arguments.equals("dst, src"), "copy defaults must name source and destination");
        check(!DataModules.paletteCall("sortasc").returnsValue && !DataModules.paletteCall("reverse").returnsValue,
            "in-place array transforms must not expose a result field");
        for(String operation : new String[]{"fill", "copy", "sortasc", "sortdesc", "reverse", "swap",
            "sclear", "qclear", "dclear", "mapclear", "uclear",
            // v5 API: constant results carry no information, so these are void cards too
            "bset", "bclr", "cshead"}){
            check(!DataModules.paletteCall(operation).returnsValue,
                operation + " must be represented as a void palette card");
        }
        check(DataModules.paletteCall("spop").arguments.equals("s"), "stack defaults must match stack declaration");
        check(DataModules.paletteCall("spop").returnsValue, "pop cards must expose their value");
        check(!DataModules.paletteCall("sclear").returnsValue && !DataModules.paletteCall("mapclear").returnsValue
            && !DataModules.paletteCall("uclear").returnsValue, "clear cards must not expose a result field");

        DataCallStatement fillCard = new DataCallStatement(DataModules.paletteCall("array_fill"));
        StringBuilder fillCardSource = new StringBuilder();
        fillCard.write(fillCardSource);
        check(fillCardSource.toString().equals("datacall array_fill ~ \"buf, value\""),
            "new void cards must serialize an empty destination: " + fillCardSource);
        check(new DataCallStatement(DataModules.paletteCall("array_fill")).typeName().equals("datacall.group.arrayalgo"),
            "structure-level datacall tooltip type missing");

        check(LogicIO.allStatements.count(prov -> prov.get() instanceof SugarStatements.ArrayInitStatement) == 0,
            "legacy eight-slot arrayinit must be hidden from the palette");
        LStatement legacy = LAssembler.read("arrayinit buf 1 2 ~ ~ ~ ~ ~ ~", true).first();
        check(legacy instanceof SugarStatements.ArrayInitStatement,
            "legacy arrayinit carrier must remain parseable");

        legacyCarrierCardsUseCanonicalNames();

        String sugar = "stack s cell1 0 4\n"
            + "datacall spush result \"s, 7\"\n"
            + "datacall ssize result \"s\"\n"
            + "set x result\n";
        String compiled = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null);
        String product = SugarCompiler.stripMarkers(compiled);
        check(!product.contains("datacall "), "datacall leaked into product:\n" + product);
        check(!product.contains("funccall __ls_builtin_"), "builtin call leaked into product:\n" + product);
        check(SugarCompiler.restore(compiled).equals(sugar), "datacall did not survive carrier restore");
        check(SugarCompiler.verifyRestore(compiled, sugar), "datacall carrier verification failed");

        String fillSugar = "array buf cell1 0 4\n"
            + "datacall fill ~ \"buf, 9\"\n";
        String fillCompiled = SugarCompiler.compile(fillSugar, SugarCompiler.FuncMode.normal, null, null);
        String fillProduct = SugarCompiler.stripMarkers(fillCompiled);
        check(fillProduct.contains("__ls_func___ls_builtin_arrfill_entry:"),
            "fill card did not lower through the shared array fill builtin:\n" + fillProduct);
        check(!fillProduct.contains("datacall "), "fill datacall leaked into product:\n" + fillProduct);
        check(SugarCompiler.restore(fillCompiled).equals(fillSugar), "fill card did not survive carrier restore");

        String voidSugar = "stack s cell1 0 4\n"
            + "datacall sclear ~ \"s\"\n";
        String voidCompiled = SugarCompiler.compile(voidSugar, SugarCompiler.FuncMode.normal, null, null);
        String voidProduct = SugarCompiler.stripMarkers(voidCompiled);
        check(voidProduct.contains("__ls_stk_s_top"), "void clear card did not lower safely:\n" + voidProduct);
        check(SugarCompiler.verifyRestore(voidCompiled, voidSugar), "void card carrier verification failed");

        checkCompileThrows("stack s cell1 0 4\n"
            + "datacall spush ~ \"s, 7\"\n", "requires a destination variable");

        // A dedicated operation card must choose its intrinsic even if ordinary expressions
        // would let a same-named local function shadow it.  Test both lowering modes because
        // normal mode needs builtin reachability/hoisting and inline expands at the call site.
        String shadowSugar = "stack s cell1 0 4\n"
            + "funcdef spush a,b 4\n"
            + "set hijacked 1\n"
            + "return \"99\"\n"
            + "blockend\n"
            + "datacall spush pushed \"s, 7\"\n";
        for(SugarCompiler.FuncMode mode : SugarCompiler.FuncMode.values()){
            String shadowCompiled = SugarCompiler.compile(shadowSugar, mode, null, null);
            String shadowProduct = SugarCompiler.stripMarkers(shadowCompiled);
            check(shadowProduct.contains("__ls_builtin_stkpush"),
                "datacall spush did not force the stack intrinsic in " + mode + ":\n" + shadowProduct);
            check(!shadowProduct.contains("set hijacked 1"),
                "same-named user function hijacked datacall spush in " + mode + ":\n" + shadowProduct);
            check(SugarCompiler.verifyRestore(shadowCompiled, shadowSugar),
                "shadowed datacall did not survive carrier verification in " + mode);
        }

        // Only the fixed root is forced. A user function nested in a card argument must
        // still be analyzed, retained and lowered normally.
        String nestedSugar = "stack s cell1 0 4\n"
            + "funcdef uservalue x 4\n"
            + "set nestedHit 1\n"
            + "return \"x\"\n"
            + "blockend\n"
            + "datacall spush pushed \"s, uservalue(7)\"\n";
        for(SugarCompiler.FuncMode mode : SugarCompiler.FuncMode.values()){
            String nestedProduct = SugarCompiler.stripMarkers(SugarCompiler.compile(nestedSugar, mode, null, null));
            check(nestedProduct.contains("set nestedHit 1"),
                "nested user function was not retained in " + mode + ":\n" + nestedProduct);
            check(nestedProduct.contains("__ls_builtin_stkpush"),
                "root stack intrinsic was lost in " + mode + ":\n" + nestedProduct);
        }

        // Bounds assertions emitted from a card in a function must use that function call's
        // private temp namespace; a bare _0 would be clobbered by surrounding expressions.
        String dynamicSugar = "array buf cell1 0 4\n"
            + "stack s cell1 4 4\n"
            + "funcdef consume i 5\n"
            + "datacall spush pushed \"s, buf[i + 1]\"\n"
            + "return \"pushed\"\n"
            + "blockend\n"
            + "funccall consume 1 result\n";
        for(SugarCompiler.FuncMode mode : SugarCompiler.FuncMode.values()){
            String dynamicCompiled = SugarCompiler.compile(dynamicSugar, mode, null, null,
                SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.emit);
            String dynamicProduct = SugarCompiler.stripMarkers(dynamicCompiled);
            check(dynamicProduct.contains("assertBounds integer ~ 0 lessThanEq __ls_"),
                "datacall bounds assert was not namespaced in " + mode + ":\n" + dynamicProduct);
            check(!dynamicProduct.contains("lessThanEq _0 lessThanEq"),
                "datacall bounds assert kept a bare temporary in " + mode + ":\n" + dynamicProduct);
            check(SugarCompiler.verifyRestore(dynamicCompiled, dynamicSugar),
                "dynamic datacall carrier verification failed in " + mode);
        }
        apiVersionVerification();
        System.out.println("DataCallTest passed");
    }

    /**
     * v5.2 改名：加号菜单只提供新名（{@link DataModules#paletteCalls()}），旧载体重开的卡片
     * 也必须在解析时归一化成新名——否则旧存档里的积木仍然显示 {@code spush(...)}、悬停提示
     * 也按旧名取键（旧名那套键已随死键清理删除，只会退回英文原文），并且把旧名写回下一次保存的载体。
     */
    private static void legacyCarrierCardsUseCanonicalNames(){
        LStatement parsed = LAssembler.read("datacall spush pushed \"s, 7\"", true).first();
        check(parsed instanceof DataCallStatement, "legacy datacall line did not parse into a card");
        DataCallStatement card = (DataCallStatement)parsed;
        check(card.operation.equals("stack_push"),
            "legacy operation name was not canonicalized: " + card.operation);
        check(card.typeName().equals("datacall.group.stackops"),
            "legacy card tooltip key must resolve to the structure card: " + card.typeName());
        check(card.groupKey().equals("stackops"),
            "legacy card did not resolve its structure group: " + card.groupKey());
        check(card.groupCalls().size() == 5,
            "the stack card must offer all five stack operations: " + card.groupCalls().size());
        check(!card.name().equals("spush"), "legacy card title still resolves through the old key: " + card.name());

        StringBuilder written = new StringBuilder();
        card.write(written);
        check(written.toString().equals("datacall stack_push pushed \"s, 7\""),
            "a reopened legacy card must be written back with the canonical name: " + written);

        // 未知名不得被归一化改写：编译期仍要报准确的 unknown data intrinsic
        LStatement unknown = LAssembler.read("datacall nosuchop r \"a\"", true).first();
        check(unknown instanceof DataCallStatement, "unknown datacall line did not parse into a card");
        check(((DataCallStatement)unknown).operation.equals("nosuchop"),
            "an unknown operation name must survive parsing unchanged");

        // 旧名载体：解析成规范卡后，可执行流与旧名完全一致，载体校验仍然通过
        String legacySugar = "stack s cell1 0 4\n"
            + "datacall spush pushed \"s, 7\"\n";
        for(SugarCompiler.FuncMode mode : SugarCompiler.FuncMode.values()){
            String compiled = SugarCompiler.compile(legacySugar, mode, null, null);
            String restored = SugarCompiler.restore(compiled);
            check(restored.equals(legacySugar),
                "the carrier must keep the source text verbatim in " + mode + ":\n" + restored);
            check(SugarCompiler.verifyRestore(compiled, restored),
                "a legacy-named card must still pass the restore gate in " + mode);
            LStatement reread = LAssembler.read(restored, true).get(1);
            check(reread instanceof DataCallStatement reopened && reopened.operation.equals("stack_push"),
                "reopening a legacy carrier must yield the canonical operation in " + mode);
        }
    }

    /**
     * v5.2 UX（对照原版 {@code op} 卡）：加号菜单每个结构只注册一张卡，卡内按钮负责切换该结构的
     * 运算。这里钉住：① 每个运算恰好属于一个结构分组；② 分组卡的默认运算=组内首项且载体能原样
     * 读回；③ 卡片与该结构的声明卡同栏、组内运算顺序与搜索词覆盖整组——菜单不必再铺 68 个积木。
     */
    private static void structureCardsCoverEveryOperation(){
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        for(java.util.Map.Entry<String, java.util.List<DataModule.PaletteCall>> entry : DataModules.operationGroups().entrySet()){
            String group = entry.getKey();
            java.util.List<DataModule.PaletteCall> calls = entry.getValue();
            check(!calls.isEmpty(), "structure group " + group + " is empty");

            for(DataModule.PaletteCall call : calls){
                check(group.equals(DataModules.groupKey(call)),
                    "operation " + call.name + " leaked into another group");
                check(seen.put(call.name, 1) == null,
                    "operation " + call.name + " appears in two structure groups");
            }

            DataCallStatement card = new DataCallStatement(calls.get(0));
            check(card.groupKey().equals(group), "card group mismatch for " + group);
            // 与声明卡同栏：数组/矩阵的声明卡在「数组算法」，其余结构的声明卡都在「数据结构」。
            check(card.category() == (SugarStatements.arrayAlgo.name.equals(group)
                    ? SugarStatements.arrayAlgo : SugarStatements.dataStructures),
                "operation cards must sit in their structure's declaration column: " + group
                    + " -> " + card.category().name);

            String[] expectedNames = new String[calls.size()];
            for(int i = 0; i < calls.size(); i++) expectedNames[i] = calls.get(i).name;
            String[] actualNames = new String[card.groupCalls().size()];
            for(int i = 0; i < actualNames.length; i++) actualNames[i] = card.groupCalls().get(i).name;
            check(java.util.Arrays.equals(expectedNames, actualNames),
                "card must offer exactly its own structure's operations in order: " + group
                    + " -> " + java.util.Arrays.toString(actualNames));

            String terms = card.searchTerms();
            for(DataModule.PaletteCall call : calls){
                check(terms != null && terms.contains(call.name),
                    "the palette search must still find " + call.name + " through the structure card");
            }

            StringBuilder carrier = new StringBuilder();
            card.write(carrier);
            LStatement reread = LAssembler.read(carrier.toString(), true).first();
            check(reread instanceof DataCallStatement reopened
                    && reopened.canonicalOperation().equals(calls.get(0).name)
                    && reopened.groupKey().equals(group),
                "structure card did not round-trip through its carrier: " + carrier);
        }
        check(seen.size() == 68, "every intrinsic must belong to exactly one structure: " + seen.size());
    }

    /**
     * 卡内运算按钮的语义（UI 部分需要 GL，无法无头构建，所以状态迁移单独测）：切换运算重置实参、
     * 目标变量只跨越「两边都有结果」的切换时保留、无结果运算清空目标，重复选中不覆盖用户输入。
     */
    private static void operationSwitchBehavior(){
        DataCallStatement card = new DataCallStatement(DataModules.paletteCall("stack_push"));
        card.destination = "myVar";
        card.arguments = "custom, 1";

        card.selectOperation(DataModules.paletteCall("stack_top"));
        check(card.operation.equals("stack_top"), "the card must switch to the picked operation: " + card.operation);
        check(card.arguments.equals("s"),
            "switching must reset the arguments to the new operation's defaults: " + card.arguments);
        check(card.destination.equals("myVar"),
            "a custom destination must survive a switch between value-returning operations: " + card.destination);
        StringBuilder carrier = new StringBuilder();
        card.write(carrier);
        check(carrier.toString().equals("datacall stack_top myVar \"s\""),
            "the switched card must serialize the new operation: " + carrier);

        card.selectOperation(DataModules.paletteCall("stack_clear"));
        check(card.destination.isEmpty(), "a result-less operation must clear the destination: " + card.destination);
        carrier.setLength(0);
        card.write(carrier);
        check(carrier.toString().equals("datacall stack_clear ~ \"s\""),
            "a result-less operation must serialize an empty destination: " + carrier);

        card.selectOperation(DataModules.paletteCall("stack_pop"));
        check(card.destination.equals("result"),
            "switching back to a value-returning operation must restore the default destination: " + card.destination);

        card.arguments = "userValue";
        card.selectOperation(DataModules.paletteCall("stack_pop"));
        check(card.arguments.equals("userValue"),
            "re-picking the current operation must not reset what the user typed: " + card.arguments);

        // 旧名卡片：选中同一个运算只做归一化，不重置实参/目标
        DataCallStatement legacy = (DataCallStatement)LAssembler.read("datacall spush kept \"s, 7\"", true).first();
        legacy.selectOperation(DataModules.paletteCall("stack_push"));
        check(legacy.operation.equals("stack_push"), "re-picking via the palette must normalize the name: " + legacy.operation);
        check(legacy.arguments.equals("s, 7") && legacy.destination.equals("kept"),
            "normalizing a legacy name must not touch the card's fields: " + legacy.arguments + " / " + legacy.destination);
    }

    /**
     * 加号菜单按 {@code LogicIO.allStatements} 的顺序铺卡片：每张运算卡必须紧跟它所属结构的
     * 声明卡。声明卡由各模块先注册、运算卡统一在最后注册，所以不做重排时菜单里「栈」在第二行、
     * 「栈操作」在列表末尾——同一结构被拆成两段，选运算要先跨行找卡片。
     */
    private static void structureCardsSitNextToTheirDeclaration(){
        java.util.List<LStatement> palette = new java.util.ArrayList<>();
        for(arc.func.Prov<LStatement> provider : LogicIO.allStatements){
            palette.add(provider.get());
        }

        java.util.Map<String, String> declarationGroups = new java.util.HashMap<>();
        declarationGroups.put("stack", "stackops");
        declarationGroups.put("queue", "queueops");
        declarationGroups.put("deque", "dequeops");
        declarationGroups.put("bitset", "bitsetops");
        declarationGroups.put("map", "mapops");
        declarationGroups.put("uset", "setops");
        declarationGroups.put("list", "listops");
        declarationGroups.put("heap", "heapops");
        declarationGroups.put("chain", "chainops");
        check(declarationGroups.size() == DataModules.operationGroups().size() - 1,
            "every structure except the array operations must own a declaration card in the palette");

        java.util.Set<String> cards = new java.util.HashSet<>();
        int anchored = 0;
        for(int i = 0; i < palette.size(); i++){
            LStatement statement = palette.get(i);
            if(statement instanceof DataCallStatement card){
                check(cards.add(card.groupKey()),
                    "the palette must hold exactly one operation card per structure: " + card.groupKey());
                continue;
            }
            if(!(statement instanceof DataDeclaration declaration)) continue;
            String group = declarationGroups.get(declaration.token());
            if(group == null) continue; // record 等没有运算卡的声明卡
            check(i + 1 < palette.size() && palette.get(i + 1) instanceof DataCallStatement next
                    && group.equals(next.groupKey()),
                "the " + declaration.token() + " declaration must be followed by its " + group
                    + " card, got " + (i + 1 < palette.size() ? palette.get(i + 1).getClass().getSimpleName() : "end of palette"));
            anchored++;
        }
        check(anchored == declarationGroups.size(),
            "every structure declaration must be anchored to its operation card, got " + anchored);
        check(cards.size() == DataModules.operationGroups().size(),
            "every structure group must own exactly one palette card, got " + cards);
    }

    /**
     * 运算卡必须和它那个结构的**声明卡**同栏。
     *
     * <p>栏取真实声明卡的 {@code category()}，而不是复述 {@link DataModules#paletteColumn}——
     * 否则测试只是把实现抄一遍。数组/矩阵是唯一声明卡不在「数据结构」栏的结构（它们的声明卡在
     * {@code SugarStatements} 里、由 {@code LogicSugarMod.registerStatements} 紧挨着注册），
     * 所以数组运算卡必须去「数组算法」；其余 9 个结构的声明卡在「数据结构」，运算卡跟着留下。</p>
     */
    private static void operationCardsShareTheirDeclarationColumn(){
        java.util.Map<String, mindustry.logic.LCategory> declared = new java.util.LinkedHashMap<>();
        declared.put("stackops", new ContainerModule.StackDeclStatement().category());
        declared.put("queueops", new ContainerModule.QueueDeclStatement().category());
        declared.put("dequeops", new ContainerModule.DequeDeclStatement().category());
        declared.put("bitsetops", new BitsetModule.BitsetStatement().category());
        declared.put("mapops", new MapModule.MapStatement().category());
        declared.put("setops", new SetModule.USetStatement().category());
        declared.put("listops", new ListHeapModule.ListDeclStatement().category());
        declared.put("heapops", new ListHeapModule.HeapDeclStatement().category());
        declared.put("chainops", new ChainModule.ChainDeclStatement().category());
        declared.put("arrayalgo", new SugarStatements.ArrayStatement().category());

        check(declared.keySet().equals(DataModules.operationGroups().keySet()),
            "every operation group must have a declaration card to compare against: "
                + declared.keySet() + " vs " + DataModules.operationGroups().keySet());

        for(java.util.Map.Entry<String, mindustry.logic.LCategory> entry : declared.entrySet()){
            check(DataModules.paletteColumn(entry.getKey()) == entry.getValue(),
                "the " + entry.getKey() + " card must sit in its declaration's column ("
                    + entry.getValue().name + "), got " + DataModules.paletteColumn(entry.getKey()).name);
        }

        check(new SugarStatements.MatrixStatement().category() == declared.get("arrayalgo"),
            "the matrix declaration must share the array column");

        int outside = 0;
        for(mindustry.logic.LCategory column : declared.values()){
            if(column != SugarStatements.dataStructures) outside++;
        }
        check(outside == 1,
            "the arrays must be the only structure whose declarations leave the data-structure column");
    }

    /**
     * 运算短名的 bundle 键是 {@code logicsugar.datacall.<op>}（v5.2 改名后 STL 名与短名各有一套键）。
     * 卡内运算按钮、弹窗条目与搜索词都用这个键取词：写成 {@code logicsugar.<op>} 会查空，卡片就只剩
     * 英文 STL 名（"翻译丢失"），所以这里把键和三份译文一起钉住。
     */
    private static void operationLabelsAreTranslated() throws IOException{
        File root = SourceNails.root();
        check(DataCallStatement.operationLabelKey("stack_push").equals("datacall.stack_push"),
            "the operation label must be looked up under the localised datacall key: "
                + DataCallStatement.operationLabelKey("stack_push"));
        for(String name : new String[]{"bundle.properties", "bundle_zh_CN.properties", "bundle_zh_TW.properties"}){
            Properties bundle = load(root, name);
            for(String operation : DataModules.paletteCalls().keySet()){
                String labelKey = "logicsugar." + DataCallStatement.operationLabelKey(operation);
                String value = bundle.getProperty(labelKey);
                check(value != null, name + " is missing the operation label " + labelKey);
                check(!value.trim().isEmpty(), labelKey + " must not be empty");
                check(!value.equals(operation),
                    labelKey + " must be the localised short name, not the STL name '" + value + "'");
            }
        }

        // 卡片正文（按钮）与弹窗条目都必须走这个键；直接 cardText(<op>) 会查 logicsugar.<op>（不存在）
        // 而退回英文 STL 名——这正是本次修掉的显示问题。
        String source = SourceNails.readSource("src/logicsugar/assist/data/DataCallStatement.java");
        check(source.contains("cardText(operationLabelKey("),
            "the card must resolve operation labels through operationLabelKey()");
        check(source.contains("operationLabel(canonicalOperation())"),
            "the in-card operation button must show the localised operation name");
        check(source.contains("t.button(operationLabel(name)"),
            "the operation picker entries must show the localised operation names");

        // 宽度单位陷阱：GlyphLayout 与背景内边距都是像素，而 Cell.width() 会再乘一次 Scl。
        // 漏掉这一步在 200% UI 缩放下会得到双倍宽度的控件（卡片虚胖、弹窗顶出画布）。
        check(source.contains("Scl.scl(1f)"),
            "measured text width must be converted from pixels back to design units before Cell.width()");
        check(!source.contains("+ BUTTON_PADDING"),
            "widget widths must come from measured text metrics, not a hardcoded padding constant");
    }

    /**
     * 运算卡引用的 bundle 键必须都有代码路径能取到，反过来可达的键也必须都写出来。
     *
     * <p>加号菜单改成「一个结构一张卡」之后，逐运算的 {@code logicsugar.lst.datacall.<op>} 全部
     * 变成不可达 —— {@link DataCallStatement#typeName()} 只返回 {@code datacall.group.<组>}，
     * 所以调色板按钮的描述改由 {@code logicsugar.lst.datacall.group.<组>} 提供；同理，旧短名
     * （{@code spush}/{@code sum}/…）在卡片解析时就被归一化成规范名，{@code datacall.<旧名>} 与
     * {@code hint.datacall.<旧名>} 也不再被取用。</p>
     *
     * <p>死键不只是占地方：它们让「译文丢了会静默退回英文」这类排查失效 —— 看到键还在就以为
     * 文案生效，实际取的是另一个键。这里把可达集合算出来与三份 bundle 双向对齐，任何一侧对不上
     * 都在这里变红，而不是等玩家发现某张卡显示英文。</p>
     */
    private static void everyOperationBundleKeyIsReachable() throws IOException{
        Set<String> reachable = new LinkedHashSet<>();
        // 卡内「运算」按钮/「实参」输入框自身的提示（不是逐运算的）
        reachable.add("logicsugar.datacall.operation");
        reachable.add("logicsugar.datacall.arguments");
        reachable.add("logicsugar.hint.datacall.operation");
        reachable.add("logicsugar.hint.datacall.arguments");
        for(DataModule.PaletteCall call : DataModules.paletteCalls().values()){
            String operation = call.name.toLowerCase(Locale.ROOT);
            // 卡片正文与卡内运算按钮：logicsugar.datacall.<规范名>
            reachable.add("logicsugar.datacall." + operation);
            // 卡内运算选择器每一条的悬停提示：logicsugar.hint.datacall.<规范名>
            reachable.add("logicsugar.hint.datacall." + operation);
            // 调色板按钮描述：一张卡覆盖整组，所以只有分组键
            reachable.add("logicsugar.lst.datacall.group."
                + DataModules.groupKey(call).toLowerCase(Locale.ROOT));
            // 实参输入框的灰色占位：logicsugar.datacall.arg.<参数名>
            for(String parameter : DataModules.paletteParams(call.name)){
                reachable.add("logicsugar.datacall.arg." + parameter.toLowerCase(Locale.ROOT));
            }
        }

        for(String name : new String[]{"bundle.properties", "bundle_zh_CN.properties", "bundle_zh_TW.properties"}){
            Properties bundle = load(SourceNails.root(), name);

            List<String> dead = new ArrayList<>();
            for(String key : bundle.stringPropertyNames()){
                if(!isOperationCardKey(key)) continue;
                if(!reachable.contains(key)) dead.add(key);
            }
            Collections.sort(dead);
            check(dead.isEmpty(), name + " has " + dead.size()
                + " operation key(s) that no code path can reach (delete them): " + preview(dead));

            List<String> missing = new ArrayList<>();
            for(String key : reachable){
                if(!bundle.containsKey(key)) missing.add(key);
            }
            Collections.sort(missing);
            check(missing.isEmpty(), name + " is missing " + missing.size()
                + " reachable operation key(s) (the UI would silently fall back to English): " + preview(missing));
        }
    }

    /** 运算卡自己那一族键（不含声明卡、设置页等其它 {@code logicsugar.*} 键）。 */
    private static boolean isOperationCardKey(String key){
        return key.startsWith("logicsugar.datacall.")
            || key.startsWith("logicsugar.hint.datacall.")
            || key.startsWith("logicsugar.lst.datacall.");
    }

    private static String preview(List<String> keys){
        return keys.subList(0, Math.min(keys.size(), 8)) + (keys.size() > 8 ? " ..." : "");
    }

    /**
     * 声明的两处文案都用**运算卡上的短名**称呼运算，不罗列 STL 标识符，也不留旧短名。
     *
     * <p>这条线起于 5073458，那时运算还叫 {@code spush}/{@code qpush}/…；c7511b9 把运算改成 STL
     * 风格并给运算卡加了本地化短名（{@code logicsugar.datacall.stack_push = 压入}），却没回头改
     * 面向用户的文案 —— 于是同一张卡上，悬停看到一串英文旧标识符，悬停运算按钮看到「压入」，说的
     * 其实是同一批运算。第一轮（{@code relabel_declaration_name_hints.py}）只修了卡内「名字」字段
     * 的提示 {@code logicsugar.hint.<stem>.name}，2026-09-20 的反馈说明调色板按钮自身的描述
     * {@code logicsugar.lst.<stem>}（{@code SugarLogicDialog} 按 {@code typeName()} 取键）还漏着，
     * 所以这里两处一起钉：</p>
     *
     * <ul>
     *   <li>两处出现的每个运算 == 该结构运算卡上的短名（新增/改名运算漏改文案直接变红）；</li>
     *   <li>按钮描述还必须引用组卡自己的名字 {@code logicsugar.category.<group>}，因为「运算在哪张
     *       卡上」正是用户困惑的来源 —— 改名组卡时两处一起红，而不是悄悄指向一个不存在的卡。</li>
     * </ul>
     */
    private static void declarationHintsUseTheOperationCardsLabels() throws IOException{
        // 分组键 → 声明卡提示的键名。setops 的声明 token 是 uset（不是 set），所以不能靠去掉
        // "ops" 后缀推导。arrayalgo 不在此表：它的声明卡提示（hint.array.name / hint.matrix.name）
        // 说的是下标写法，本来就不罗列运算。
        java.util.Map<String, String> stems = new java.util.LinkedHashMap<>();
        stems.put("stackops", "stack");
        stems.put("queueops", "queue");
        stems.put("dequeops", "deque");
        stems.put("bitsetops", "bitset");
        stems.put("mapops", "map");
        stems.put("setops", "uset");
        stems.put("listops", "list");
        stems.put("heapops", "heap");
        stems.put("chainops", "chain");

        check(DataModules.operationGroups().size() == stems.size() + 1
                && DataModules.operationGroups().containsKey(SugarStatements.arrayAlgo.name),
            "exactly the array group must lack a declaration-name hint: "
                + DataModules.operationGroups().keySet());
        for(String group : DataModules.operationGroups().keySet()){
            if(group.equals(SugarStatements.arrayAlgo.name)) continue;
            check(stems.containsKey(group), "no declaration-hint stem registered for group " + group);
        }

        for(String name : new String[]{"bundle.properties", "bundle_zh_CN.properties", "bundle_zh_TW.properties"}){
            Properties bundle = load(SourceNails.root(), name);
            for(java.util.Map.Entry<String, String> entry : stems.entrySet()){
                // 同一批运算在界面上有两处文案，两处都必须与运算卡一致：
                //   logicsugar.hint.<stem>.name —— 声明卡「名字」字段的悬停提示
                //   logicsugar.lst.<stem>       —— 调色板按钮本身的悬停描述
                String hintKey = "logicsugar.hint." + entry.getValue() + ".name";
                String listKey = "logicsugar.lst." + entry.getValue();
                for(String textKey : new String[]{hintKey, listKey}){
                    String text = bundle.getProperty(textKey);
                    check(text != null, name + " is missing " + textKey);
                    for(DataModule.PaletteCall call : DataModules.operationGroup(entry.getKey())){
                        String labelKey = "logicsugar." + DataCallStatement.operationLabelKey(call.name);
                        String label = bundle.getProperty(labelKey);
                        check(label != null, name + " is missing the operation label " + labelKey);
                        check(text.contains(label),
                            textKey + " must name " + call.name + " the way its card does ('" + label + "'): " + text);
                        check(!text.contains(call.name),
                            textKey + " must not enumerate the STL identifier '" + call.name + "': " + text);
                    }
                }

                // 按钮描述还要把用户指到真正放着这些运算的那张卡上。卡上的组名就是
                // logicsugar.category.<group>，两处引用同一个字符串，改组卡名时会一起变红，
                // 而不是留下一个指向不存在卡片的死文案。
                String groupLabel = bundle.getProperty("logicsugar.category." + entry.getKey());
                check(groupLabel != null, name + " is missing logicsugar.category." + entry.getKey());
                check(bundle.getProperty(listKey).contains(groupLabel),
                    listKey + " must point at the '" + groupLabel + "' card: " + bundle.getProperty(listKey));
            }
        }

        // 旧短名（spush/qpush/… 这一套，只在解析旧载体时才需要）不该再出现在面向用户的提示里。
        // provider.callNames() 同时列出规范名与别名，靠 paletteCall 归一后的名字来区分两者。
        java.util.List<Properties> languages = new java.util.ArrayList<>();
        for(String name : new String[]{"bundle.properties", "bundle_zh_CN.properties", "bundle_zh_TW.properties"}){
            languages.add(load(SourceNails.root(), name));
        }
        for(DataModule module : DataModules.activeModules()){
            ExprIntrinsics.Provider provider = module.intrinsics();
            if(provider == null || provider.callNames() == null) continue;
            for(String name : provider.callNames()){
                DataModule.PaletteCall call = DataModules.paletteCall(name);
                if(call == null || name.equalsIgnoreCase(call.name)) continue;
                for(Properties bundle : languages){
                    for(String stem : stems.values()){
                        for(String textKey : new String[]{
                            "logicsugar.hint." + stem + ".name", "logicsugar.lst." + stem}){
                            String text = bundle.getProperty(textKey);
                            check(text == null || !text.contains(name),
                                "the legacy short name '" + name + "' must not appear in the user-facing hint "
                                    + textKey + ": " + text);
                        }
                    }
                }
            }
        }
    }

    /**
     * 定参输入框：槽数由运算的参数量决定，参数名逐位取自默认实参串，编辑只动那一格。
     *
     * <p>钉住五件事：① 每个有卡运算的槽数 == 编译期 {@code Provider.arity()}；② 参数名不出那
     * 19 个词，且三语言都有占位译文；③ 拆分/拼回对带嵌套括号的实参无损；④ 旧载体（实参数偏少 /
     * 偏多 / 运算名未知）分别补空槽、退回单框，都不静默改内容；⑤ 来回编辑不改变载体。</p>
     */
    private static void argumentSlotsAreFixedAndLossless() throws IOException{
        // ① 槽数必须等于编译期 arity：UI 认为这个运算收几个参数，编译期就必须收几个。
        //    provider 只在 collect 上下文里可见，所以借用一次空收集（用完立刻配对 restore）。
        DataModules.collectAll(new java.util.ArrayList<>(), new java.util.LinkedHashSet<>());
        try{
            for(DataModule module : DataModules.activeModules()){
                ExprIntrinsics.Provider provider = module.intrinsics();
                if(provider == null || provider.callNames() == null) continue;
                for(String name : provider.callNames()){
                    if(name == null || DataModules.paletteCall(name) == null) continue; // 既无卡片也无别名
                    int arity = provider.arity(name);
                    java.util.List<String> params = DataModules.paletteParams(name);
                    check(params.size() == arity,
                        name + " offers " + params.size() + " argument slots but its arity is " + arity);
                }
            }
        }finally{
            DataModules.restore();
        }

        // 参数量分布：全部定参、最多三参（有变参或出现四参时这条会红，提醒重审输入框布局）
        java.util.Map<Integer, Integer> histogram = new java.util.TreeMap<>();
        for(String name : DataModules.paletteCalls().keySet()){
            histogram.merge(DataModules.paletteParams(name).size(), 1, Integer::sum);
        }
        check(histogram.equals(java.util.Map.of(1, 34, 2, 27, 3, 7)),
            "every operation must have a fixed arity of at most three parameters: " + histogram);

        // ② 参数名域就这 19 个词：新增运算带来新参数名时这条会失败，提示补三份 bundle 与本清单
        java.util.Set<String> vocabulary = new java.util.LinkedHashSet<>();
        for(String name : DataModules.paletteCalls().keySet()){
            vocabulary.addAll(DataModules.paletteParams(name));
        }
        java.util.Set<String> expectedVocabulary = new java.util.LinkedHashSet<>(java.util.Arrays.asList(
            "buf", "dst", "s", "q", "d", "bits", "map", "l", "h", "c",
            "value", "src", "oldValue", "newValue", "i", "j", "index", "key", "next"));
        check(vocabulary.equals(expectedVocabulary),
            "the shared argument-name vocabulary changed to " + vocabulary
                + "; add the new names to logicsugar.datacall.arg.* in all three bundles and to this list");

        File rootForBundles = SourceNails.root();
        for(String bundleName : new String[]{
            "bundle.properties", "bundle_zh_CN.properties", "bundle_zh_TW.properties"}){
            Properties bundle = load(rootForBundles, bundleName);
            for(String argument : vocabulary){
                String key = "logicsugar." + DataCallStatement.argumentLabelKey(argument);
                String value = bundle.getProperty(key);
                check(value != null && !value.trim().isEmpty(),
                    bundleName + " is missing the argument placeholder " + key);
            }
        }

        // ③ 新卡片：默认实参串逐位就是参数名（所以新建的卡片开箱即用，逗号由卡片写）
        DataCallStatement push = new DataCallStatement(DataModules.paletteCall("stack_push"));
        java.util.List<String> pushSlots = push.argumentSlots();
        check(pushSlots != null && pushSlots.size() == 2
                && pushSlots.get(0).equals("s") && pushSlots.get(1).equals("value"),
            "a fresh stack_push card must show one slot per parameter: " + pushSlots);

        DataCallStatement swap = new DataCallStatement(DataModules.paletteCall("array_swap"));
        java.util.List<String> swapSlots = swap.argumentSlots();
        check(swapSlots != null && swapSlots.size() == 3,
            "the widest operation must offer exactly three slots: " + swapSlots);

        // 编辑某一格只改那一格，其余原样
        for(int edited = 0; edited < 3; edited++){
            DataCallStatement card = new DataCallStatement(DataModules.paletteCall("array_swap"));
            card.setArgument(edited, "edited");
            java.util.List<String> after = card.argumentSlots();
            check(after != null && after.size() == 3, "edited card lost its slots: " + after);
            for(int i = 0; i < 3; i++){
                check(after.get(i).equals(i == edited ? "edited" : swapSlots.get(i)),
                    "editing slot " + edited + " changed slot " + i + ": " + after);
            }
        }

        // ④ 实参里的嵌套调用：括号内的逗号不是槽分隔符
        DataCallStatement nested = new DataCallStatement(DataModules.paletteCall("array_fill"));
        nested.arguments = "buf, f(a, b)";
        java.util.List<String> nestedSlots = nested.argumentSlots();
        check(nestedSlots != null && nestedSlots.size() == 2 && nestedSlots.get(1).equals("f(a, b)"),
            "a nested call must stay inside one slot: " + nestedSlots);
        nested.setArgument(0, "arr");
        check(nested.arguments.equals("arr, f(a, b)"),
            "editing a slot must preserve a nested argument verbatim: " + nested.arguments);

        // ⑤ 旧载体：实参数偏少 → 补空槽（用户一眼看出少填了哪个）
        DataCallStatement shortList = new DataCallStatement(DataModules.paletteCall("stack_push"));
        shortList.arguments = "s";
        java.util.List<String> padded = shortList.argumentSlots();
        check(padded != null && padded.size() == 2 && padded.get(1).isEmpty(),
            "a carrier with fewer arguments than parameters must pad empty slots: " + padded);

        // 实参数偏多 → 退回单框，绝不定参截断（那会静默丢掉第 N+1 项起的内容）
        shortList.arguments = "s, 1, 2";
        check(shortList.argumentSlots() == null,
            "more arguments than parameters must fall back to the single field rather than drop them");

        // 未知运算（损坏载体 / 未来版本写入的名字）→ 没有参数名可用，同样退回单框
        DataCallStatement unknown = (DataCallStatement)LAssembler.read("datacall nosuchop r \"a\"", true).first();
        check(unknown.argumentSlots() == null,
            "an unknown operation has no parameter names, so it must keep the single field");

        // 拼回规则：尾部空槽丢掉（不留尾逗号），中间空槽保留逗号（位置有意义）
        DataCallStatement tail = new DataCallStatement(DataModules.paletteCall("stack_push"));
        tail.setArgument(1, "");
        check(tail.arguments.equals("s"),
            "clearing the last slot must not leave a trailing comma: " + tail.arguments);
        java.util.List<String> reopenedTail = tail.argumentSlots();
        check(reopenedTail != null && reopenedTail.size() == 2 && reopenedTail.get(1).isEmpty(),
            "a cleared trailing slot must reopen as an empty slot, not vanish: " + reopenedTail);

        DataCallStatement middle = new DataCallStatement(DataModules.paletteCall("array_swap"));
        middle.setArgument(1, "");
        check(middle.arguments.equals("buf, , j"),
            "clearing a middle slot must keep the comma so positions stay meaningful: " + middle.arguments);
        java.util.List<String> reopenedMiddle = middle.argumentSlots();
        check(reopenedMiddle != null && reopenedMiddle.size() == 3
                && reopenedMiddle.get(0).equals("buf") && reopenedMiddle.get(1).isEmpty()
                && reopenedMiddle.get(2).equals("j"),
            "a cleared middle slot must survive the round trip: " + reopenedMiddle);

        // ⑥ 无操作编辑不得让载体漂移：打开卡片、原值写回每一格、保存，字符必须逐字不变
        for(DataModule.PaletteCall call : DataModules.paletteCalls().values()){
            DataCallStatement card = new DataCallStatement(call);
            String before = card.arguments;
            java.util.List<String> slots = card.argumentSlots();
            check(slots != null, call.name + " must open in the fixed-argument shape");
            for(int i = 0; i < slots.size(); i++) card.setArgument(i, slots.get(i));
            check(card.arguments.equals(before),
                call.name + " argument text drifted on a no-op edit: " + before + " -> " + card.arguments);
        }

        // 载体形状不变（存档兼容）：仍是 datacall <op> <dest> "<逗号串>"
        StringBuilder carrier = new StringBuilder();
        DataCallStatement written = new DataCallStatement(DataModules.paletteCall("array_swap"));
        written.destination = "r";
        written.setArgument(1, "0");
        written.setArgument(2, "3");
        written.write(carrier);
        check(carrier.toString().equals("datacall array_swap r \"buf, 0, 3\""),
            "the carrier shape must stay unchanged: " + carrier);

        // 源码钉子：build() 必须真的走这套判定与写回（否则上面的逻辑没人调用）
        String source = SourceNails.readSource("src/logicsugar/assist/data/DataCallStatement.java");
        check(source.contains("argumentSlots()"),
            "the card must decide its argument shape through argumentSlots()");
        check(source.contains("table.add(\",\")"),
            "the card must supply the commas between argument slots");
        check(source.contains("value -> setArgument(index, value)"),
            "every slot must write back through setArgument()");
    }

    /**
     * 运算卡的编辑期标红：实参写错（引用不存在的结构、参数个数不符、表达式非法、缺少目标变量）
     * 必须在编辑器里立刻变红，而不是等到保存或编译。
     *
     * <p>最强的钉子是"编辑期与编译期逐例对齐"：标红集合的有无必须与
     * {@code SugarCompiler.compile} 的成败一致。判断本来就出自同一个
     * {@code ExprCompiler.compileForcedIntrinsic}，所以这条断言也在拦住将来有人给编辑器另写
     * 一份参数校验规则——那必然会与编译器漂移。</p>
     */
    private static void operationCardsMarkInvalidArguments() throws IOException{
        // ① 每个分组的默认卡 + 与默认首参同名的声明：整组必须干净。新拖出来的卡片一露面就是
        //    红的没人受得了，这条钉住默认实参串与声明名的对应关系（array_copy 需要两个数组）。
        java.util.Map<String, String> declarations = new java.util.LinkedHashMap<>();
        declarations.put("arrayalgo", "array buf cell1 0 8\narray dst cell1 24 8\narray src cell1 32 8");
        declarations.put("stackops", "stack s cell1 0 8");
        declarations.put("queueops", "queue q cell1 0 8");
        declarations.put("dequeops", "deque d cell1 0 8");
        declarations.put("bitsetops", "bitset bits cell1 0 4");
        declarations.put("mapops", "map map cell1 0 4");
        declarations.put("setops", "uset s cell1 0 8");
        declarations.put("listops", "list l cell1 0 8");
        declarations.put("heapops", "heap h cell1 0 8");
        declarations.put("chainops", "chain c cell1 0 8");

        java.util.Map<String, java.util.List<String>> byGroup = new java.util.LinkedHashMap<>();
        for(String name : DataModules.paletteCalls().keySet()){
            DataModule.PaletteCall call = DataModules.paletteCall(name);
            byGroup.computeIfAbsent(DataModules.groupKey(call), key -> new java.util.ArrayList<>()).add(name);
        }
        check(byGroup.size() == declarations.size(),
            "every operation group needs a declaration here, got " + byGroup.keySet());
        int defaultCards = 0;
        for(java.util.Map.Entry<String, java.util.List<String>> group : byGroup.entrySet()){
            String header = declarations.get(group.getKey());
            check(header != null, "no declaration known for group " + group.getKey());
            StringBuilder sugar = new StringBuilder(header).append('\n');
            for(String name : group.getValue()){
                DataCallStatement card = new DataCallStatement(DataModules.paletteCall(name));
                sugar.append(carrier(card)).append('\n');
                defaultCards++;
            }
            java.util.List<Integer> red = invalidOperationLines(sugar.toString());
            check(red.isEmpty(), "a freshly created " + group.getKey()
                + " card must open clean, but lines " + red + " are red:\n" + sugar);
        }
        check(defaultCards == DataModules.paletteCalls().size(),
            "every operation must be covered by a default card, got " + defaultCards);

        // ② 编辑期与编译期逐例对齐：{期望, 说明, 卡片文本}。两边必须同进退——编辑期标红当且
        //    仅当编译期失败。
        String declarationsFor = "stack s cell1 0 8\narray a cell1 16 8\nmap m cell1 32 4\n";
        String[][] cases = {
            {"clean", "legal push", "datacall stack_push r \"s, 1\""},
            {"clean", "legal pop", "datacall stack_pop r \"s\""},
            {"clean", "legal array swap", "datacall array_swap r \"a, 0, 3\""},
            {"clean", "nested expression", "datacall stack_push r \"s, count + 1\""},
            {"clean", "nested call", "datacall stack_push r \"s, max(1, 2)\""},
            {"clean", "free variable as value", "datacall stack_push r \"s, unsetvar\""},
            {"red", "unknown operation", "datacall nosuchop r \"a\""},
            {"red", "undeclared container", "datacall stack_push r \"ss, 1\""},
            {"red", "undeclared array", "datacall array_swap r \"b, 0, 3\""},
            {"red", "non-identifier receiver", "datacall stack_push r \"1, 1\""},
            {"red", "too few arguments", "datacall array_swap r \"a, 0\""},
            {"red", "too many arguments", "datacall array_swap r \"a, 0, 3, 9\""},
            {"red", "empty arguments", "datacall stack_push r \"\""},
            {"red", "empty middle slot", "datacall array_swap r \"a, , 3\""},
            {"red", "illegal expression", "datacall stack_push r \"s, a1.1\""},
            {"red", "dangling operator", "datacall stack_push r \"s, 1 +\""},
            {"red", "missing destination on value operation", "datacall stack_push ~ \"s, 1\""},
        };
        for(String[] item : cases){
            String sugar = declarationsFor + item[2] + "\n";
            boolean red = !invalidOperationLines(sugar).isEmpty();
            boolean compiled = compiles(sugar);
            check(red != compiled, item[1] + ": the editor says "
                + (red ? "invalid" : "valid") + " but the compiler says "
                + (compiled ? "valid" : "invalid") + " for: " + item[2]);
            check(red == item[0].equals("red"), item[1] + " must be " + item[0]
                + ", got " + (red ? "red" : "clean") + " for: " + item[2]);
        }

        // ③ 声明卡自身非法（区间重叠）时安静放弃第二层：不误标运算卡，也绝不泄漏上下文
        String brokenDeclarations = "stack t cell1 0 8\nstack u cell1 4 8\ndatacall stack_push r \"s, 1\"\n";
        check(invalidOperationLines(brokenDeclarations).isEmpty(),
            "a broken declaration must not drag an unrelated operation card into the red");
        check(!DataModules.isCollecting(),
            "the validation context must be released when a declaration fails to collect");
        // 失败之后下一次仍然要能正常工作（上下文没被上一次弄脏）
        check(invalidOperationLines(declarationsFor + "datacall stack_push r \"s, 1\"\n").isEmpty(),
            "a failed validation pass must not poison the next one");

        // ④ 形状层单独钉一遍：不依赖任何注册表就能判定的错
        DataCallStatement unknown = new DataCallStatement();
        unknown.operation = "nosuchop";
        unknown.destination = "r";
        unknown.arguments = "a";
        check(!invalidOperationLines(carrier(unknown)).isEmpty(),
            "an unknown operation must be red even without any declaration to consult");

        DataCallStatement noDestination = new DataCallStatement(DataModules.paletteCall("stack_pop"));
        noDestination.destination = "";
        check(!invalidOperationLines(declarationsFor + carrier(noDestination) + "\n").isEmpty(),
            "a value-returning operation must be red when its destination is empty");
        DataCallStatement voidCard = new DataCallStatement(DataModules.paletteCall("stack_clear"));
        voidCard.destination = "";
        check(invalidOperationLines(declarationsFor + carrier(voidCard) + "\n").isEmpty(),
            "a void operation must stay clean with an empty destination");

        // ⑤ 没有运算卡时零开销：不建立试编译上下文
        check(!DataModules.isCollecting() && invalidOperationLines("stack s cell1 0 8\nset x 1\n").isEmpty(),
            "a program without operation cards must not enter a validation context");

        // ⑥ 接线钉子：编译路径必须真的调用它，且编辑器用的是编译期同一条路径
        String modules = SourceNails.readSource("src/logicsugar/assist/data/DataModules.java");
        check(modules.contains("ExprCompiler.compileForcedIntrinsic(destination, operation,"),
            "the editor pass must reuse the compiler's own intrinsic lowering");
        String compiler = SourceNails.readSource("src/mindustry/logic/SugarCompiler.java");
        check(compiler.contains("DataModules.markInvalidCalls(statementList, invalid, arrayReservedNames)"),
            "the editor's invalid-statement pass must invoke the operation-card check");

        // ⑦ 端到端：编辑器真正调用的那一个入口（编辑器每帧重建结构时跑的就是它）也必须把
        //    运算卡标红——上面几条走的是内部入口，这条证明它确实接在这一条链上。
        arc.struct.Seq<LStatement> parsed =
            LAssembler.read(declarationsFor + "datacall stack_push r \"ss, 1\"\n", true);
        boolean[] invalid = SugarCompiler.invalidStatements(parsed);
        check(!invalid[0] && !invalid[1] && !invalid[2],
            "the legal declarations must stay clean through the editor entry point");
        check(invalid[3], "the editor entry point must flag an operation card with an undeclared receiver");

        arc.struct.Seq<LStatement> clean =
            LAssembler.read(declarationsFor + "datacall stack_push r \"s, 1\"\n", true);
        boolean[] cleanInvalid = SugarCompiler.invalidStatements(clean);
        for(int i = 0; i < cleanInvalid.length; i++){
            check(!cleanInvalid[i], "a fully legal program must be entirely clean, line " + i + " is red");
        }
    }

    /** 载体文本（与存档、加号菜单写入的形状一致）。 */
    private static String carrier(DataCallStatement card){
        StringBuilder out = new StringBuilder();
        card.write(out);
        return out.toString();
    }

    /** 跑一次运算卡标红，返回被标红的行号（不含声明卡）。 */
    private static java.util.List<Integer> invalidOperationLines(String sugar){
        java.util.List<LStatement> statements = new java.util.ArrayList<>();
        for(LStatement statement : LAssembler.read(sugar, true)) statements.add(statement);
        boolean[] invalid = new boolean[statements.size()];
        DataModules.markInvalidCalls(statements, invalid, new java.util.LinkedHashSet<>());
        java.util.List<Integer> red = new java.util.ArrayList<>();
        for(int i = 0; i < invalid.length; i++) if(invalid[i]) red.add(i);
        return red;
    }

    /** 与编辑器标红无关的参照：这段文本编译得过吗。 */
    private static boolean compiles(String sugar){
        try{
            SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null);
            return true;
        }catch(Throwable ignored){
            return false;
        }
    }

    private static Properties load(File root, String name) throws IOException{
        Properties properties = new Properties();
        try(Reader reader = new InputStreamReader(
            Files.newInputStream(root.toPath().resolve("assets/bundles").resolve(name)), StandardCharsets.UTF_8)){
            properties.load(reader);
        }
        return properties;
    }

    /**
     * The v5 API changed failure values (0 to -1). Save written before it must still pass the
     * restore gate: the gate re-lowers them with the legacy API and compares streams.
     */
    private static void apiVersionVerification(){
        String sugar = "map m cell1 0 4\n"
            + "datacall mapclear ~ \"m\"\n"
            + "datacall mapdel r \"m, 1\"\n";
        String v2 = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null);

        SugarCompiler.Api previous = SugarCompiler.enterApi(SugarCompiler.Api.v1);
        String v1;
        try{
            v1 = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null);
        }finally{
            SugarCompiler.leaveApi(previous);
        }
        String v1Save = v1.replace("# @logic-sugar-v2", "# @logic-sugar-v1");

        check(SugarCompiler.storedFormat(v2) == 2 && SugarCompiler.storedFormat(v1Save) == 1,
            "marker versions must differ between the v2 and v1 lowering");
        check(!SugarCompiler.matchesStoredStream(v2, v1Save),
            "v1 and v2 failure values must not compare equal without the legacy pass");
        check(SugarCompiler.verifyRestore(v1Save, sugar),
            "a pre-v5 save must verify through the legacy lowering API:\n" + v1Save);
        check(SugarCompiler.verifyRestore(v2, sugar), "a v2 save must verify through the v2 API");

        // Tampered v1 save: the legacy pass must still reject a stream that does not reproduce.
        String tampered = v1Save.replace("set r ", "set r 1\nset r ");
        check(!SugarCompiler.verifyRestore(tampered, sugar),
            "a tampered v1 save must not be accepted");
    }

    /**
     * 重入配对：{@code enterCallValidation} 重入时交出的必须是**不拥有**外层上下文的一份句柄，
     * 调用方因此不能替外层 {@code leaveCallValidation()}——那会把外层的上下文拆掉，让外层后续的
     * {@code callCompiles} 在无上下文状态下跑。这与"静态上下文泄漏"是同一条红线的两侧。
     *
     * <p>当前 {@code markInvalidCalls} 的调用链（{@code SugarCompiler}）不可重入，所以这是潜伏
     * 缺陷；这个用例用反射把外层上下文摆出来，让它保持潜伏，而不是某天被新的调用链踩到。</p>
     *
     * <p>程序里<b>必须</b>有运算卡：{@code markInvalidCalls} 在没有运算卡时会在
     * {@code if(pending.isEmpty()) return;} 处直接返回，根本走不到 {@code enterCallValidation}——
     * 那样这个用例在"重入配对已坏"的实现上照样通过，等于没测。末张卡引用未声明的结构，它只能由
     * 第二层试编译标红，所以 {@code invalid[2]} 就是"这次标红真的复用了外层上下文"的证据。</p>
     */
    private static void reentrantValidationKeepsTheOuterContext() throws Exception{
        java.util.List<LStatement> statements = new java.util.ArrayList<>();
        for(LStatement statement : LAssembler.read("stack s cell1 0 8\n"
            + "datacall stack_push r \"s, 7\"\ndatacall stack_push r \"nope, 7\"\n", true)) statements.add(statement);

        Method enter = DataModules.class.getDeclaredMethod("enterCallValidation",
            java.util.List.class, java.util.Set.class);
        enter.setAccessible(true);
        Method leave = DataModules.class.getDeclaredMethod("leaveCallValidation");
        leave.setAccessible(true);
        Field context = DataModules.class.getDeclaredField("callValidation");
        context.setAccessible(true);

        Object outer = enter.invoke(null, statements, new java.util.LinkedHashSet<>());
        check(outer != null, "test setup: the outer validation context was not established");
        check(DataModules.isCollecting(), "test setup: the outer context is not collecting");

        // 外层还在时跑一次编辑器标红：它必须复用外层上下文，而不是另建一份、也不是拆掉外层的。
        boolean[] invalid = new boolean[statements.size()];
        DataModules.markInvalidCalls(statements, invalid, new java.util.LinkedHashSet<>());
        check(!invalid[1], "test setup: the valid operation card was marked invalid");
        check(invalid[2], "test setup: the trial-compile layer never ran, so this case proves nothing");
        check(context.get(null) == outer, "a reentrant validation pass tore down the outer context");
        check(DataModules.isCollecting(), "a reentrant validation pass ended the outer collection");

        // 只有主人收尾才算真的收尾。
        leave.invoke(null);
        check(context.get(null) == null, "leaving the owner did not clear the context");
        check(!DataModules.isCollecting(), "leaving the owner left the collection flag set");
    }

    private static void checkCompileThrows(String sugar, String expected){
        try{
            SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null);
            throw new AssertionError("expected compile failure containing '" + expected + "'");
        }catch(IllegalArgumentException exception){
            check(exception.getMessage().contains(expected),
                "unexpected compile failure: " + exception.getMessage());
        }
    }

    private static void check(boolean value, String message){
        if(!value) throw new AssertionError(message);
    }
}
