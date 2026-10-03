package lab;

import arc.struct.Seq;
import logicsugar.assist.data.DataModules;
import logicsugar.assist.expr.ArrayRegistry;
import logicsugar.assist.expr.ExprCompiler;
import logicsugar.assist.expr.ExprIntrinsics;
import logicsugar.assist.expr.ExprTextImport;
import mindustry.logic.LAssembler;
import mindustry.logic.LStatement;
import mindustry.logic.LStatements;
import mindustry.logic.SugarCompiler;
import mindustry.logic.SugarDecompiler;
import mindustry.logic.SugarFunctions;
import mindustry.logic.SugarStatements;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 展示地图的代码编译管线：把 {@code demos/*.ls} 里「好读的糖码文本」编成处理器代码。
 *
 * <p>编辑器保存时的真实管线是 {@code SugarCanvas.save()} → 画布文本 → {@link SugarCompiler#compile}。
 * 画布文本不是用户写的原始文本：表达式卡会写成展开后的 {@code op}/{@code read} 行（单行卡后面
 * 跟一条 {@code # @ls-expr-card} 自描述注释），因为载体里的文本必须能被原版解析器读回并重新
 * 编出同一条指令流（{@code verifyRestore} 的安全门）。所以这里复刻同一条链路：</p>
 *
 * <pre>
 *   文本 → ExprTextImport.plan（x = a + b → 哨兵 set）
 *        → LAssembler.read → ExprTextImport.applyToStatements（哨兵 → Expr 卡）
 *        → LAssembler.write（在数组表/声明类型/函数上下文里序列化，与画布 save() 同口径）
 *        → SugarCompiler.compile（产物 = 原版 mlog + 载体）
 * </pre>
 *
 * <p>序列化必须自己装好编译期上下文（{@code ArrayRegistry} / {@code declaredKinds} /
 * user functions / {@code DataModules.collectAll}），否则 {@code buf[i]}、{@code m[1][2]}、
 * {@code s.top()} 这类糖在写文本时会解析失败或退化成裸 read/write。</p>
 */
final class LabCompiler{
    private LabCompiler(){}

    /** 一次编译的全部中间结果，供构建与自检使用。 */
    static final class Result{
        final String friendly;
        final String canvas;
        final String product;
        final String restored;
        final String openingMode;
        final boolean verified;
        final boolean strategyStable;
        final boolean libraryEmbedded;
        final int instructions;

        Result(String friendly, String canvas, String product, String restored, String openingMode,
               boolean verified, boolean strategyStable, boolean libraryEmbedded, int instructions){
            this.friendly = friendly;
            this.canvas = canvas;
            this.product = product;
            this.restored = restored;
            this.openingMode = openingMode;
            this.verified = verified;
            this.strategyStable = strategyStable;
            this.libraryEmbedded = libraryEmbedded;
            this.instructions = instructions;
        }
    }

    /** 糖码文本 → 处理器代码（含载体），并跑一遍编辑器打开时的判定。 */
    static Result compile(String friendly, boolean emit, String libraryText){
        ExprTextImport.Plan plan = ExprTextImport.plan(friendly);
        // 先查用户写的那份文本（哨兵已替换）：认不出的行原样留着，这里能把行号和原文报出来
        rejectUnparsedLines(plan.text(), "糖码文本");
        Seq<LStatement> statements = LAssembler.read(plan.text(), false);
        ExprTextImport.applyToStatements(statements, plan);
        String canvas = canvasText(statements);
        rejectUnparsedLines(canvas, "画布文本");

        SugarFunctions.SanitizedLibrary library = libraryText == null || libraryText.trim().isEmpty()
            ? null : SugarFunctions.sanitizedLibrary(normalizeLibrary(libraryText));
        SugarFunctions.LibraryIndex index = library == null ? null : library.index;
        String indexText = library == null ? null : library.text;
        SugarCompiler.AssertEmit assertEmit = emit ? SugarCompiler.AssertEmit.emit : SugarCompiler.AssertEmit.strip;

        String product = SugarCompiler.compile(canvas, SugarCompiler.FuncMode.normal, index, indexText,
            SugarCompiler.SwitchStrategy.auto, assertEmit, false);
        // 载体里的程序在不同 SwitchStrategy 设置下必须编出同一条指令流，否则用户改设置后
        // verifyRestore 失配、结构化视图会退回原版（本工具不用跳转表，因此要求完全一致）。
        String chainProduct = SugarCompiler.compile(canvas, SugarCompiler.FuncMode.normal, index, indexText,
            SugarCompiler.SwitchStrategy.chainOnly, assertEmit, false);

        String restored = SugarCompiler.restore(product);
        boolean verified = SugarCompiler.verifyRestore(product, restored);
        rejectUnparsedLines(product, "编译产物");
        SugarDecompiler.Opening opening = SugarDecompiler.openingSource(product, false, false);

        boolean libraryEmbedded = true;
        if(libraryText != null && !libraryText.trim().isEmpty()){
            SugarCompiler.EffectiveLibrary effective = SugarCompiler.effectiveLibrary(product, null, null);
            libraryEmbedded = effective.index != null && effective.index.functions.containsKey("gcd");
        }

        return new Result(friendly, canvas, product, restored, opening.mode.name(), verified,
            product.equals(chainProduct), libraryEmbedded, SugarCompiler.emittedInstructionCount(product));
    }

    /**
     * 纯原版程序（无载体，用于「从源码重建」展台）：直接给原始 mlog 文本，
     * 编辑器会走反编译推断路径。
     */
    static Result vanilla(String code){
        SugarDecompiler.Opening opening = SugarDecompiler.openingSource(code, false, false);
        SugarDecompiler.Result inferred = SugarDecompiler.decompile(code, false);
        return new Result(code, code, code, inferred.sugar, opening.mode.name(), true, true, true,
            SugarCompiler.emittedInstructionCount(code));
    }

    /**
     * 逐行拦下「文本导入认不出」的语句。
     *
     * <p>这类行不会被报错：原版 {@code LParser} 把它们落成 {@code InvalidStatement}（注册名
     * {@code noop}），产物里多一条无意义指令，编辑器里多一张红色的「无效」卡，载体里也照样存着。
     * 用户报过：{@code @counter = 0} 直接写在源码里——{@code ExprTextImport} 的赋值目标只接受
     * {@code [A-Za-z_]} 开头，{@code @} 开头的内建变量不算，于是最后一行变成「无效」卡，
     * {@code @counter} 指示线没有写入指令可画。展示地图里这种静默降级比编译报错更贵，所以直接失败。</p>
     */
    static void rejectUnparsedLines(String text, String where){
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        for(int i = 0; i < lines.length; i++){
            String line = lines[i].trim();
            if(line.isEmpty() || line.startsWith("#") || line.endsWith(":")) continue;
            Seq<LStatement> parsed;
            try{
                parsed = LAssembler.read(line, false);
            }catch(RuntimeException ignored){
                // 单行判不了的行交给整段解析去判（最典型：跳到文本里别处定义的标签，
                // 单行解析会报 Undefined jump location）。这里只负责抓「解析成功、却落成
                // InvalidStatement」的静默降级。
                continue;
            }
            for(LStatement statement : parsed){
                if(statement instanceof LStatements.InvalidStatement){
                    throw new IllegalArgumentException(where + " 第 " + (i + 1) + " 行解析不出语句（会变成「无效」卡）：`"
                        + lines[i].trim() + "`");
                }
            }
        }
    }

    /**
     * 函数库文本归一化：库里的 {@code funcdef}/{@code whilebegin} 带着「跳到哪个 blockend」的下标
     * 注释，函数库构建（{@code SugarFunctions.buildLibrary}）不像程序编译那样会重算它，
     * 手写的库文件里这个数字写错就被当成「函数损坏」整段丢掉。这里用
     * {@link SugarStatements#pairBlockEnds}（编译器自己的重配对实现）按嵌套写回正确下标。
     */
    static String normalizeLibrary(String libraryText){
        Seq<LStatement> statements = SugarFunctions.readLibrary(libraryText, false);
        if(!SugarStatements.pairBlockEnds(statements)){
            throw new IllegalArgumentException("函数库文本的 begin/blockend 无法配对，检查 demos 里的库文件");
        }
        return LAssembler.write(statements);
    }

    /** 语句列表 → 画布文本（与 LCanvas.save() 同一条路径），序列化前装好编译期上下文。 */
    static String canvasText(Seq<LStatement> statements){
        List<LStatement> list = new ArrayList<>(statements.size);
        for(LStatement statement : statements) list.add(statement);

        Set<String> functions = new HashSet<>();
        for(LStatement statement : list){
            if(statement instanceof SugarStatements.FuncDefStatement def) functions.add(def.name);
        }

        ArrayRegistry previousArrays = ArrayRegistry.enter(ArrayRegistry.compileRegistry(statements, functions));
        Map<String, String> previousKinds = ExprIntrinsics.enterDeclaredKinds(DataModules.declaredKinds(list));
        Set<String> previousFunctions = ExprIntrinsics.enterUserFunctions(functions);
        boolean previousPrivileged = ExprCompiler.enterPrivilegedSensors(false);
        DataModules.collectAll(list, functions);
        try{
            return LAssembler.write(statements);
        }finally{
            DataModules.restore();
            ExprCompiler.restorePrivilegedSensors(previousPrivileged);
            ExprIntrinsics.restoreUserFunctions(previousFunctions);
            ExprIntrinsics.restoreDeclaredKinds(previousKinds);
            ArrayRegistry.restore(previousArrays);
        }
    }

}
