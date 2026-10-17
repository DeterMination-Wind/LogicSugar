#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""mlog2sugar —— 把原版 mlog 重建为 LogicSugar 糖码（Sugar Code），与游戏内输出逐字节相同。

直接运行、粘贴、结束输入即可（用绝对路径，任意工作目录都能跑）：

    python "C:\Users\华硕\Documents\codex\LogicSugar\tools\mlog2sugar\mlog2sugar.py"

结束粘贴的方式（任选其一）：
  - 最后单独一行输入 EOF 或 . 并回车；
  - 按 Ctrl+Z 再回车（Windows）/ Ctrl+D（Linux、macOS）。

转换逻辑不在本文件里重写：脚本会把内嵌的 Java 内核写进系统临时目录、用 javac 编译一次，然后
用 mod 在游戏里打开/保存处理器的同一条链路完成转换（SugarDecompiler.openingSource →
openableSource → ExprTextImport → ExprHook.foldAll → ExprHook.unfoldedText，即
SugarCanvas.load() + save()）。因此输出与游戏内 LogicSugar 编辑器对同一份程序产生的文本
逐字节相同。

也可以非交互使用（下面的"本文件"指上面那条绝对路径，`--help` 会自己打印它）：

    python "<本文件>" program.mlog      # 文件 → 标准输出
    python "<本文件>" < program.mlog    # 管道（输出是纯字节，不做 CRLF 转换）
    python "<本文件>" --selftest        # 对 tools/maplab/demos 跑逐字节自检

依赖：JDK 17+（javac/java 在 PATH 上或设置 JAVA_HOME）、一个 Mindustry v160.1 桌面 jar
（默认 <仓库>/../Mindustry-master/desktop/build/libs/Mindustry.jar，可用 --mindustry-jar 或
环境变量 LOGICSUGAR_MINDUSTRY_JAR 覆盖）以及本仓库编译好的 mod 类
（build/classes/java/main，缺失或过期时脚本自动跑一次 gradlew classes）。
"""

from __future__ import annotations

import argparse
import hashlib
import locale
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).resolve()
# 帮助信息、报错提示统一用本文件的绝对路径，从任意工作目录调用都指向同一个入口
PROG = str(SCRIPT)
MAIN_CLASS = "mlog2sugar.Mlog2Sugar"
SEP = ";" if os.name == "nt" else ":"
# 结束粘贴的哨兵行（整行比较；这两者在 mlog 里都不是合法语句）
END_MARKERS = ("eof", ".")


def find_root() -> Path:
    """仓库根目录：环境变量 > 从脚本往上找 SugarDecompiler.java > 固定相对位置。"""
    override = os.environ.get("LOGICSUGAR_HOME")
    if override:
        return Path(override).resolve()
    for parent in [SCRIPT.parent, *SCRIPT.parents]:
        if (parent / "src" / "mindustry" / "logic" / "SugarDecompiler.java").is_file():
            return parent
    return SCRIPT.parents[2] if len(SCRIPT.parents) > 2 else SCRIPT.parent


ROOT = find_root()
DEFAULT_MOD_CLASSES = ROOT / "build" / "classes" / "java" / "main"
DEFAULT_DEMOS = ROOT / "tools" / "maplab" / "demos"


# =====================================================================================
# 内嵌的 Java 内核（本文件唯一的“另一半”；运行时会写进临时目录并编译）
# =====================================================================================

JAVA_SOURCE = r'''package mlog2sugar;

import arc.struct.Seq;
import logicsugar.LogicSugarMod;
import logicsugar.assist.data.DataModules;
import logicsugar.assist.expr.ArrayRegistry;
import logicsugar.assist.expr.ExprCompiler;
import logicsugar.assist.expr.ExprHook;
import logicsugar.assist.expr.ExprIntrinsics;
import logicsugar.assist.expr.ExprStatement;
import logicsugar.assist.expr.ExprTextImport;
import mindustry.Vars;
import mindustry.logic.GlobalVars;
import mindustry.logic.LAssembler;
import mindustry.logic.LExecutor;
import mindustry.logic.LStatement;
import mindustry.logic.LStatements;
import mindustry.logic.SugarCompiler;
import mindustry.logic.SugarDecompiler;
import mindustry.logic.SugarFunctions;
import mindustry.logic.SugarStatements;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mlog → Sugar Code 的命令行转换器（{@code tools/mlog2sugar/mlog2sugar.py} 的 Java 内核）。
 *
 * <p>本类不重新实现任何转换逻辑，它按<b>游戏里编辑器打开处理器时的同一条链路</b>调用 mod 现有
 * 的入口，所以输出与游戏内的结果逐字节相同：</p>
 *
 * <pre>
 *   SugarDecompiler.openingSource   载体恢复 / 反编译推断 / 原样（与 SugarLogicDialog.show 同一个判定）
 *   SugarDecompiler.openableSource  打开前预检与「读不了就退回存档」的同一条兜底
 *   ExprTextImport.attachCardMarkers + plan + applyToStatements   SugarCanvas.load 的文本导入
 *   foldAll                          SugarCanvas.load 末尾的 ExprHook.foldAll（op 链 → 表达式卡）
 *   ExprHook.unfoldedText            SugarCanvas.save 的展开态文本（纯读取，与画布 save() 同一实现）
 * </pre>
 *
 * <p>画布本身是 UI，无头环境搭不出来；{@link #savedText(Seq, boolean)} 补上画布会提供的三份
 * 上下文（宽松数组注册表 {@code ArrayRegistry.lenientRegistry}、声明的结构种类、用户函数表 +
 * {@code DataModules.collectAll}），这三份正是 {@code SugarCanvas.save()} 里
 * {@code ArrayRegistry.canvasRegistry(canvas)} 与各模块 {@code active()} 的画布回退在该起的作用。
 * 折叠判定全部用生产代码那一份（{@code ExprHook.isChainLine} / {@code collectChain} /
 * {@code foldableChain} / {@code foldPlan}），只补画布专有的 jump 区间检查与语句表元素增删。</p>
 *
 * <p>用法（由 Python 脚本驱动，也可以直接跑 class）：</p>
 *
 * <pre>
 *   java -cp &lt;harness&gt;:&lt;game.jar&gt;:&lt;LogicSugar/build/classes/java/main&gt; mlog2sugar.Mlog2Sugar [选项] &lt; in.mlog &gt; out.ls
 *
 *   --privileged     按世界处理器/编辑器特权解析（默认按普通处理器，与 SugarLogicDialog.show 的 executor != null 一致）
 *   --assert-emit    按「调试断言构建」展开表达式卡（默认 strip；对应 logicsugar.assertEmit 设置，仅单机）
 *   --stage &lt;canvas|source&gt;  输出画布文本（默认，= save()）或打开时的糖码原文（= editable）
 *   --verbose        把判定模式等信息打到 stderr
 *   --selftest &lt;demos 目录&gt;  对 tools/maplab 的 *.ls 跑「打开+保存 == 重开+保存」逐字节自检
 * </pre>
 */
public final class Mlog2Sugar{
    private Mlog2Sugar(){}

    /** 一次转换的结果：文本 + 打开判定（模式 / 是否走了兜底）。 */
    static final class Render{
        final String text;
        final String mode;
        final boolean fallback;

        Render(String text, String mode, boolean fallback){
            this.text = text;
            this.mode = mode;
            this.fallback = fallback;
        }
    }

    public static void main(String[] args){
        boolean privileged = false;
        boolean emitAsserts = false;
        boolean verbose = false;
        boolean sourceStage = false;
        Path demos = null;
        try{
            for(int i = 0; i < args.length; i++){
                String arg = args[i];
                switch(arg){
                    case "--privileged" -> privileged = true;
                    case "--assert-emit" -> emitAsserts = true;
                    case "--verbose" -> verbose = true;
                    case "--stage" -> {
                        String value = arg(args, ++i);
                        if(value.equals("source")) sourceStage = true;
                        else if(!value.equals("canvas")) throw new IllegalArgumentException("--stage expects canvas|source, got " + value);
                    }
                    case "--selftest" -> demos = Path.of(arg(args, ++i));
                    default -> throw new IllegalArgumentException("unknown argument: " + arg);
                }
            }

            install();

            if(demos != null){
                System.exit(selftest(demos) ? 0 : 1);
            }

            String code = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            Render render = render(code, privileged, emitAsserts, sourceStage);
            if(verbose){
                System.err.println("[mlog2sugar] mode=" + render.mode
                    + (render.fallback ? " (fell back to the stored program)" : "")
                    + " stage=" + (sourceStage ? "source" : "canvas")
                    + " chars=" + render.text.length());
            }
            System.out.write(render.text.getBytes(StandardCharsets.UTF_8));
            System.out.flush();
        }catch(Throwable throwable){
            System.err.println("[mlog2sugar] " + throwable);
            if(verbose) throwable.printStackTrace();
            System.exit(1);
        }
    }

    private static String arg(String[] args, int index){
        if(index >= args.length) throw new IllegalArgumentException("missing argument value");
        return args[index];
    }

    /**
     * 换行归一化（与 {@code SugarDecompiler.normalizeLineEndings} 同口径）。
     *
     * <p>游戏里 {@code build.code} 永远是 LF，编辑器不会遇到 {@code \r}；但命令行读到的文件可能是
     * CRLF，而 {@code LParser} 不认行尾的 {@code \r}（会把最后一个 token 变成 {@code 999\r} 直接报错）。
     * 归一化不等于放宽解析：它只是把文件读成游戏里那份文本。</p>
     */
    private static String normalize(String text){
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** 无头初始化：与 {@code ReconstructionMatrixTest} 同一套（注册全部卡片/解析器 + 逻辑全局变量表）。 */
    private static boolean installed;
    private static synchronized void install(){
        if(installed) return;
        installed = true;
        // 表达式编译里 @pi/@this 这类内建量的宿主；无头环境下默认为 null，测试同样是显式建一份。
        if(Vars.logicVars == null) Vars.logicVars = new GlobalVars();
        // 单一注册点：Sugar 卡片 + 断言卡片 + 数据子系统的声明/操作卡片解析器。
        LogicSugarMod.registerStatements();
    }

    // ===== 编辑器链路 =====================================================================

    /**
     * 复刻「打开处理器」的完整链路：{@link SugarDecompiler#openingSource} 决定打开什么，
     * {@link SugarDecompiler#openableSource} 做打开前预检（读不了时退回存档代码），最后按
     * {@code SugarCanvas.load()} + {@code save()} 得到画布文本。
     *
     * @param sourceStage true = 只输出打开时的糖码原文（{@code editable}），不做画布文本往返
     */
    static Render render(String code, boolean privileged, boolean emitAsserts, boolean sourceStage){
        String input = normalize(code == null ? "" : code);
        SugarDecompiler.Opening opening = SugarDecompiler.openingSource(input, privileged, false);
        SugarDecompiler.OpenDecision decision = SugarDecompiler.openableSource(opening.source, input, privileged, false);
        if(decision.source == null){
            throw new IllegalStateException("the logic editor cannot open this program: " + decision.failure);
        }
        String mode = decision.fallback ? opening.mode + "/fallback" : opening.mode.name();
        String text = sourceStage ? decision.source : canvasText(decision.source, privileged, emitAsserts);
        return new Render(text, mode, decision.fallback);
    }

    /**
     * 文本 → 语句表 → 展开态文本，等价于 {@code SugarCanvas.load(text)} 之后再 {@code save()}。
     *
     * <p>{@link #savedText(Seq, boolean)} 拿到的语句表下标就是画布语句下标（文本导入的哨兵与
     * 标记都是一对一替换，语句条数不变），所以 jump/begin 的目标下标换算与画布侧同口径。</p>
     */
    static String canvasText(String source, boolean privileged, boolean emitAsserts){
        // SugarCanvas.load 的两步：先把注释标记块里的 # @ls-expr-card 提到它展开成的那条语句下面，
        // 再把认不出的赋值行一对一换成哨兵 set（跳转下标不动），解析后还原成表达式卡。
        String normalized = normalize(source);
        String hoisted = ExprTextImport.attachCardMarkers(normalized, normalized);
        ExprTextImport.Plan plan = ExprTextImport.plan(hoisted);
        // LCanvas.load：解析 + 按原版指令上限截断（糖码行数可以多于指令数，跳过上限的行为必须一致）。
        Seq<LStatement> statements = LAssembler.read(plan.text(), privileged);
        statements.truncate(LExecutor.maxInstructions);
        ExprTextImport.applyToStatements(statements, plan);
        // SugarCanvas.save() 的第一步是 structure.refresh()：画布按嵌套重配对 begin/blockend 的
        // 下标注释。手写糖码里的占位/过期下标在这一步被修正（与 restore 的 rewriteStaleBlockDests
        // 同一处 recomputeBlockDests）；结构本身不完整（无法配对）时保持原样，编辑器会把它们标红。
        SugarStatements.pairBlockEnds(statements);
        return savedText(statements, emitAsserts);
    }

    /**
     * {@code SugarCanvas.load} 末尾的折叠 + {@code save()} 的展开态文本，外加画布会提供的三份上下文。
     *
     * <p>画布侧 {@code ExprHook.unfoldedText(LCanvas)} 会 {@code ArrayRegistry.enter(canvasRegistry)}；
     * 各数据模块的 {@code active()} 在同一时刻回退到「扫当前画布」。无头环境没有画布，这里用
     * {@link ArrayRegistry#lenientRegistry}（画布口径的同一处实现）与
     * {@code DataModules.collectAll} 补上，说明见类注释。</p>
     */
    static String savedText(Seq<LStatement> statements, boolean emitAsserts){
        List<LStatement> list = new ArrayList<>(statements.size);
        for(LStatement statement : statements) list.add(statement);

        Set<String> functions = new HashSet<>();
        for(LStatement statement : list){
            if(statement instanceof SugarStatements.FuncDefStatement def) functions.add(def.name);
        }

        ArrayRegistry previousArrays = ArrayRegistry.enter(ArrayRegistry.lenientRegistry(statements));
        Map<String, String> previousKinds = ExprIntrinsics.enterDeclaredKinds(DataModules.declaredKinds(list));
        Set<String> previousFunctions = ExprIntrinsics.enterUserFunctions(functions);
        boolean previousPrivileged = ExprCompiler.enterPrivilegedSensors(false);
        DataModules.collectAll(list, functions);
        try{
            foldAll(list);
            // 画布 save() 的 emitAsserts 来自设置（单机调试构建）；这里由 --assert-emit 显式给出。
            return ExprHook.unfoldedText(list, emitAsserts);
        }finally{
            DataModules.restore();
            ExprCompiler.restorePrivilegedSensors(previousPrivileged);
            ExprIntrinsics.restoreUserFunctions(previousFunctions);
            ExprIntrinsics.restoreDeclaredKinds(previousKinds);
            ArrayRegistry.restore(previousArrays);
        }
    }

    /**
     * {@code ExprHook.foldAll} 的无画布版：逐条语句试折叠，折回的链换成一张 {@link ExprStatement}。
     *
     * <p>与画布版的差别只有元素增删（这里是列表下标）与画布专有的 jump 安全检查；判定本身全部
     * 走生产代码（{@code ExprHook} 的那四处 + {@link ExprHook#adjustStatementIndex} 的同一套下标
     * 位移规则）。折叠后多行卡片的下标口径与画布一致：链 N 条语句换成 1 条，链首位置不变，链后的
     * jump/begin 目标整体前移，随后 {@code ExprHook.unfoldedText} 再把画布下标换算成文本下标。</p>
     */
    private static void foldAll(List<LStatement> statements){
        int index = 0;
        while(index < statements.size()){
            LStatement first = statements.get(index);
            if(!ExprHook.isChainLine(first)){
                index++;
                continue;
            }
            ExprHook.Chain chain = ExprHook.collectChain(statements, index);
            if(chain.ops.isEmpty()){
                index = chain.end;
                continue;
            }
            // 单行链不折（数组声明不是下标访问的证据，单行卡靠 # @ls-expr-card 标记还原）。
            if(!ExprHook.foldableChain(chain)){
                index++;
                continue;
            }
            int length = chain.length();
            // 画布专有：有 jump 指向链中间时放弃折叠（别人手写的 jump 会改变语义）。
            if(hasJumpInRange(statements, index + 1, index + length - 1)){
                index = chain.end;
                continue;
            }
            ExprHook.Plan plan = ExprHook.foldPlan(statements, chain);
            if(plan == null){
                // 链外读取 / 重建失败 / 安全门拒绝：跳过整条链，避免从链中间误折。
                index = chain.end;
                continue;
            }
            ExprStatement card = new ExprStatement();
            card.dest = plan.dest() == null ? "result" : plan.dest();
            card.expr = plan.expr();
            // 与 foldAll 一致：编译失败的卡片 write() 回退输出折进去的原链。
            card.lastOps = chain.ops;
            for(int k = 0; k < length; k++) statements.remove(index);
            for(LStatement statement : statements){
                ExprHook.adjustStatementIndex(statement, index, 1 - length);
            }
            statements.add(index, card);
            index++;
        }
    }

    /** {@code ExprHook.hasJumpInRange} 的语句表版本：jump 的 {@code destIndex} 落在 [lo, hi] 内。 */
    private static boolean hasJumpInRange(List<LStatement> statements, int lo, int hi){
        if(lo > hi) return false;
        for(LStatement statement : statements){
            if(statement instanceof LStatements.JumpStatement jump
                && jump.destIndex >= lo && jump.destIndex <= hi){
                return true;
            }
        }
        return false;
    }

    // ===== 自检 ===========================================================================

    /**
     * 对 {@code tools/maplab/demos/*.ls} 跑完整往返并逐字节比对：
     *
     * <pre>
     *   friendly(.ls) --画布文本--> canvas --compile--> product --打开+保存--> text --重编/重开+保存--> text
     * </pre>
     *
     * <p>断言两条性质：</p>
     * <ol>
     *   <li>产物能被自己的糖码验证（{@code verifyRestore}）；</li>
     *   <li>{@code text} 是编辑器往返（打开 + 保存）的不动点：把 {@code text} 编成产物再打开、
     *       再保存，得到的仍是同一段文本。这是用户在游戏里“打开、复制/保存”看到的那份文本。</li>
     * </ol>
     *
     * <p>不断言 {@code text == 打开时的载体文本}：demos 里的 {@code 999} 占位 begin 下标会被
     * {@code restore} 按嵌套重配对（好的一组）；而载体文本本身也可能不是不动点——例如 bitset
     * 下标卡的展开链在画布折叠后重新展开时会做常量折叠（生产代码既定行为，本工具与游戏同一条
     * 链路），所以第一遍保存就可能规范化一次，之后稳定。这两条性质才是“与游戏内输出一致”的可验证形式。</p>
     */
    static boolean selftest(Path demosDir) throws IOException{
        List<Path> demos = new ArrayList<>();
        try(var files = Files.list(demosDir)){
            files.filter(path -> path.getFileName().toString().endsWith(".ls")).sorted().forEach(demos::add);
        }
        if(demos.isEmpty()) throw new IllegalArgumentException("no *.ls demos in " + demosDir);

        SugarFunctions.LibraryIndex library = null;
        String libraryText = null;
        Path libraryPath = demosDir.resolve("library.txt");
        if(Files.isRegularFile(libraryPath)){
            SugarFunctions.SanitizedLibrary sanitized =
                SugarFunctions.sanitizedLibrary(normalizeLibrary(Files.readString(libraryPath, StandardCharsets.UTF_8)));
            if(!sanitized.index.functions.isEmpty()){
                library = sanitized.index;
                libraryText = sanitized.text;
            }
        }

        int passed = 0;
        for(Path demo : demos){
            String name = demo.getFileName().toString();
            try{
                String friendly = Files.readString(demo, StandardCharsets.UTF_8);
                String canvas = canvasText(friendly, false, false);
                String product = compile(canvas, library, libraryText);
                if(!SugarCompiler.verifyRestore(product, canvas)){
                    throw new IllegalStateException("verifyRestore rejected the product compiled from its own canvas text");
                }
                Render first = render(product, false, false, false);
                String next = compile(first.text, library, libraryText);
                String reopened = SugarDecompiler.openingSource(next, false, false).source;
                if(!first.text.equals(reopened)){
                    throw new IllegalStateException("the produced text is not what the editor reopens with "
                        + firstDifference(first.text, reopened));
                }
                Render second = render(next, false, false, false);
                if(!first.text.equals(second.text)){
                    throw new IllegalStateException("the produced text is not a save-cycle fixed point "
                        + firstDifference(first.text, second.text));
                }
                passed++;
                System.out.println("  ok   " + name + "  (" + first.mode + ", " + first.text.length() + " chars)");
            }catch(Throwable throwable){
                System.out.println("  FAIL " + name + ": " + throwable);
            }
        }
        System.out.println("mlog2sugar selftest: " + passed + "/" + demos.size()
            + " demos save-cycle byte-identical (open+save text == reopen+save text)");
        return passed == demos.size();
    }

    /** 自检用的固定编译形状：normal / auto / strip / 非特权（与工具默认一致）。 */
    private static String compile(String sugar, SugarFunctions.LibraryIndex library, String libraryText){
        return SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, library, libraryText,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip, false);
    }

    /** 函数库文本归一化：重配对 begin/blockend 的下标注释（与 tools/maplab 同一处实现口径）。 */
    private static String normalizeLibrary(String text){
        Seq<LStatement> statements = SugarFunctions.readLibrary(text, false);
        if(!SugarStatements.pairBlockEnds(statements)){
            throw new IllegalArgumentException("function library begin/blockend cannot be paired");
        }
        return LAssembler.write(statements);
    }

    /** 第一处不同（含少量上下文），自检失败时用来说明漂在哪一行。 */
    private static String firstDifference(String expected, String actual){
        int length = Math.min(expected.length(), actual.length());
        int index = 0;
        while(index < length && expected.charAt(index) == actual.charAt(index)) index++;
        return "at char " + index
            + ": expected `" + snippet(expected, index) + "` got `" + snippet(actual, index) + "`"
            + " (lengths " + expected.length() + " vs " + actual.length() + ")";
    }

    private static String snippet(String text, int index){
        if(index >= text.length()) return "<end>";
        String part = text.substring(index, Math.min(text.length(), index + 40));
        return part.replace("\n", "\\n");
    }
}
'''


# =====================================================================================
# 启动：JDK / 游戏 jar / mod 类 / 内核编译
# =====================================================================================

def die(message: str):
    print("mlog2sugar: " + message, file=sys.stderr)
    raise SystemExit(1)


def forward(data: bytes) -> None:
    if data:
        sys.stderr.buffer.write(data)
        sys.stderr.buffer.flush()


def tool(name: str) -> str:
    """JDK 可执行文件：优先 JAVA_HOME/bin，其次 PATH。"""
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / (name + (".exe" if os.name == "nt" else ""))
        if candidate.is_file():
            return str(candidate)
    found = shutil.which(name)
    if found is None:
        die("找不到 " + name + "，请安装 JDK 17+ 或设置 JAVA_HOME")
    return found


def run(cmd: list[str], cwd: Path | None = None, capture: bool = True):
    if not capture:
        return subprocess.run(cmd, cwd=cwd)
    return subprocess.run(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def newest_mtime(directory: Path) -> float:
    newest = 0.0
    for path in directory.rglob("*.java"):
        newest = max(newest, path.stat().st_mtime)
    return newest


def ensure_mod_classes(classes: Path, allow_build: bool) -> None:
    """确保 mod 类是最新的；必要时跑一次 gradlew classes。"""
    marker = classes / "mindustry" / "logic" / "SugarDecompiler.class"
    sources = ROOT / "src"
    if marker.is_file() and (not sources.is_dir() or marker.stat().st_mtime >= newest_mtime(sources)):
        return
    if not allow_build:
        die(f"{classes} 不存在或已过期（--no-build 阻止了自动构建）")
    gradle = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not gradle.is_file():
        die(f"找不到 {gradle}；请先在仓库根目录运行 gradlew classes")
    print("mlog2sugar: 运行 gradlew classes（mod 类缺失或已过期）", file=sys.stderr)
    if run([str(gradle), "classes"], cwd=ROOT, capture=False).returncode != 0:
        die("gradlew classes 失败")


def find_mindustry_jar(explicit: str | None) -> Path:
    candidates: list[Path] = []
    if explicit:
        candidates.append(Path(explicit))
    for variable in ("LOGICSUGAR_MINDUSTRY_JAR", "MINDUSTRY_JAR"):
        value = os.environ.get(variable)
        if value:
            candidates.append(Path(value))
    candidates.append(ROOT.parent / "Mindustry-master" / "desktop" / "build" / "libs" / "Mindustry.jar")
    for base in (os.environ.get("ProgramFiles(x86)"), os.environ.get("ProgramFiles"), os.environ.get("ProgramW6432")):
        if base:
            candidates.append(Path(base) / "Steam" / "steamapps" / "common" / "Mindustry" / "jre" / "desktop.jar")
    for candidate in candidates:
        if candidate.is_file():
            return candidate.resolve()
    die(
        "找不到可用的游戏 jar：用 --mindustry-jar 指定，或设置 LOGICSUGAR_MINDUSTRY_JAR，"
        f"或准备 {ROOT.parent / 'Mindustry-master/desktop/build/libs/Mindustry.jar'}"
    )


def core_dir() -> Path:
    """内嵌内核的编译缓存目录（按源码内容哈希分目录，随本文件更新自动重编）。"""
    digest = hashlib.sha1(JAVA_SOURCE.encode("utf-8")).hexdigest()[:12]
    base = os.environ.get("MLOG2SUGAR_CACHE") or os.path.join(tempfile.gettempdir(), "mlog2sugar")
    return Path(base) / digest


def ensure_core(jar: Path, classes: Path) -> Path:
    directory = core_dir()
    target = directory / "mlog2sugar" / "Mlog2Sugar.class"
    if target.is_file():
        return directory
    directory.mkdir(parents=True, exist_ok=True)
    source = directory / "Mlog2Sugar.java"
    source.write_text(JAVA_SOURCE, encoding="utf-8")
    result = run([
        tool("javac"), "-encoding", "UTF-8", "--release", "17",
        "-cp", str(jar) + SEP + str(classes),
        "-d", str(directory), str(source),
    ])
    if result.returncode != 0:
        forward(result.stderr)
        die("编译内嵌的 Java 内核失败： " + str(source))
    if not target.is_file():
        die("Java 内核编译后没有产出 " + str(target))
    return directory


def java_command(core: Path, jar: Path, classes: Path) -> list[str]:
    return [
        tool("java"), "-Dfile.encoding=UTF-8",
        "-cp", str(core) + SEP + str(jar) + SEP + str(classes),
        MAIN_CLASS,
    ]


# =====================================================================================
# 输入 / 输出
# =====================================================================================

def read_console() -> str:
    """交互式粘贴：提示打法，支持 EOF/./Ctrl+Z（Ctrl+D）结束。"""
    print(
        "[mlog2sugar] 粘贴原版 mlog，结束后最后单独一行输入 EOF 或 . 并回车"
        "（Windows 也可 Ctrl+Z 回车，Linux/macOS 用 Ctrl+D）",
        file=sys.stderr,
    )
    lines: list[str] = []
    while True:
        try:
            line = input()
        except EOFError:
            break
        if line.strip().lower() in END_MARKERS:
            break
        lines.append(line)
    return "\n".join(lines) + ("\n" if lines else "")


def decode_text(data: bytes) -> str:
    """文件/管道的字节 → 文本：UTF-8（去 BOM）优先，失败退回本机编码。"""
    if data.startswith(b"\xef\xbb\xbf"):
        data = data[3:]
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return data.decode(locale.getpreferredencoding(False) or "utf-8", "replace")


def write_text(text: str) -> None:
    """终端按控制台编码显示，重定向/管道输出确定性的 UTF-8 字节。"""
    if sys.stdout.isatty():
        sys.stdout.write(text)
        sys.stdout.flush()
    else:
        sys.stdout.buffer.write(text.encode("utf-8"))
        sys.stdout.buffer.flush()


def pause_before_exit(interactive: bool) -> None:
    """双击运行（Windows 控制台）时留住窗口。"""
    if not interactive or os.name != "nt":
        return
    print("[mlog2sugar] 按任意键退出…", file=sys.stderr)
    import msvcrt

    msvcrt.getch()


# =====================================================================================
# 主流程
# =====================================================================================

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog=PROG,
        description="原版 mlog → LogicSugar 糖码（与游戏内 LogicSugar 编辑器的输出逐字节相同）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "不带参数运行即进入粘贴模式：\n"
            f'  python "{PROG}"\n'
            "  粘贴 mlog，最后单独一行输入 EOF 回车（或 Ctrl+Z 回车 / Ctrl+D）\n\n"
            "其他用法：\n"
            f'  python "{PROG}" program.mlog      # 文件 → 标准输出\n'
            f'  python "{PROG}" < program.mlog    # 管道\n'
            f'  python "{PROG}" --selftest        # tools/maplab 全部演示的逐字节自检\n'
        ),
    )
    parser.add_argument("input", nargs="?", default=None, help="要转换的 mlog 文件；省略则交互粘贴或读标准输入")
    parser.add_argument("-o", "--output", default="-", help="输出文件，默认写标准输出（-）")
    parser.add_argument("--privileged", action="store_true",
                        help="按世界处理器/编辑器特权解析（默认按普通处理器，与游戏内普通处理器编辑器一致）")
    parser.add_argument("--assert-emit", action="store_true",
                        help="按调试断言构建展开（对应 logicsugar.assertEmit=emit，仅单机；默认 strip）")
    parser.add_argument("--stage", choices=("canvas", "source"), default="canvas",
                        help="canvas = 画布 save() 文本（默认，游戏导出/保存的那份）；source = 打开时的糖码原文")
    parser.add_argument("--mindustry-jar", help="游戏 jar 路径（默认 ../Mindustry-master/desktop/build/libs/Mindustry.jar）")
    parser.add_argument("--classes", help="覆盖 mod 类目录（默认 build/classes/java/main）")
    parser.add_argument("--no-build", action="store_true", help="mod 类缺失或过期时不自动跑 gradlew classes")
    parser.add_argument("-v", "--verbose", action="store_true", help="把打开判定模式等信息打到 stderr")
    parser.add_argument("--selftest", action="store_true", help="对 tools/maplab/demos/*.ls 跑自检（打开+保存 == 重开+保存）")
    parser.add_argument("--demos", default=str(DEFAULT_DEMOS), help="自检用的演示目录")
    args = parser.parse_args(argv)

    classes = Path(args.classes).resolve() if args.classes else DEFAULT_MOD_CLASSES
    ensure_mod_classes(classes, allow_build=not args.no_build)
    jar = find_mindustry_jar(args.mindustry_jar)
    core = ensure_core(jar, classes)
    command = java_command(core, jar, classes)

    if args.selftest:
        return run(command + ["--selftest", str(Path(args.demos))], capture=False).returncode

    if args.verbose:
        command.append("--verbose")
    if args.privileged:
        command.append("--privileged")
    if args.assert_emit:
        command.append("--assert-emit")
    if args.stage != "canvas":
        command += ["--stage", args.stage]

    interactive = False
    if args.input:
        text = decode_text(Path(args.input).read_bytes())
    elif sys.stdin.isatty():
        text = read_console()
        interactive = True
    else:
        text = decode_text(sys.stdin.buffer.read())

    result = subprocess.run(command, input=text.encode("utf-8"), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    forward(result.stderr)
    if result.returncode != 0:
        return result.returncode

    if args.output == "-":
        write_text(result.stdout.decode("utf-8"))
    else:
        Path(args.output).write_bytes(result.stdout)

    if interactive:
        output = result.stdout.decode("utf-8")
        print(f"[mlog2sugar] 已输出 {len(output)} 个字符（{output.count(chr(10))} 行）", file=sys.stderr)
        pause_before_exit(True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
