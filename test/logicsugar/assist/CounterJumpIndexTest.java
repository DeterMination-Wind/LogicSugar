package logicsugar.assist;

import mindustry.logic.SugarCompiler;
import mindustry.logic.SugarFunctions;
import mindustry.logic.SugarStatements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link CounterJumpIndex} 的 main()-based 自测：无 JUnit，约定同 {@code StatementClipboardSelfTest}
 * （小 {@code check(boolean, String)} 计数失败，任何失败都以非零码退出）。
 *
 * <p>覆盖：直线程序的字面量目标、带标签程序的归一化下标、入口跳过（含 hoist 前导跳与"位置算不算"
 * 的反例）、相对 add/sub、switch 跳转表（合成与真实编译产物各一）、read 写入、越界回绕、载体行
 * 不计入指令数、{@code __ls_stmt_3:} 归属、hoisted 函数体区域为 -1、{@code ;}/注释/引号混杂源码仍
 * 能归属、provenance 的长度契约与 {@code compileRecorded} 来源通道的对接，以及坏输入只降级不抛异常。</p>
 */
public final class CounterJumpIndexTest{
    private static int checks;
    private static int failures;

    private CounterJumpIndexTest(){}

    public static void main(String[] args){
        SugarStatements.installParsers();

        straightLineLiteralTarget();
        labelRoundTripUsesNormalizedIndex();
        entrySkipWrapsToZero();
        entrySkipBeforeHoistedBodies();
        midProgramZeroIsNotEntrySkip();
        relativeAddAndSub();
        switchDispatchHasNoTarget();
        switchTableDispatchFromCompiler();
        readCounterIsDynamic();
        outOfRangeLiteralWraps();
        carriersAreNotInstructions();
        carrierNameRule();
        structuralLabelAttributesStatement();
        messySourceKeepsAttribution();
        functionRegionIsUnknown();
        provenanceIsLengthChecked();
        recordedProvenanceIsHonored();
        labelsMapThroughMainSource();
        jumpLanesSeparateOverlappingCurves();
        malformedInputDegrades();
        boundsAreSafe();

        if(failures > 0){
            System.err.println("LogicSugar CounterJumpIndex self-test FAILED: " + failures + " of " + checks + " checks failed.");
            System.exit(1);
        }
        System.out.println("LogicSugar CounterJumpIndex self-test passed (" + checks + " checks).");
    }

    private static void straightLineLiteralTarget(){
        String code = "set x 0\nset @counter 2\nop add y x 1\nprint y\nend\n";
        CounterJumpIndex index = new CounterJumpIndex(code);

        check(index.instructionCount() == 5, "straight-line program must have 5 instructions, got " + index.instructionCount());
        check(index.writes().size() == 1, "exactly one write expected, got " + index.writes().size());

        CounterJumpIndex.Write write = index.writeAt(1);
        check(write != null, "the write at instruction 1 was not found");
        if(write == null) return;
        check(write.note.equals("set-literal"), "a numeric literal is a set-literal, got " + write.note);
        check(Arrays.equals(write.targets, new int[]{2}), "target must be 2, got " + Arrays.toString(write.targets));
        check(!write.wrap, "2 is inside a 5-instruction program");
        check(write.statement == -1, "a program without structural labels cannot be attributed, got " + write.statement);
        check(write.line.equals("set @counter 2"), "unexpected instruction text: " + write.line);

        String tooltip = index.describe(write);
        check(tooltip != null && tooltip.contains("= 2"), "tooltip must mention the index, got " + tooltip);
    }

    /**
     * 标签行不占指令：归一化会把 {@code __ls_stmt_3:} 整行删掉，所以用户卡片的
     * {@code set @counter N} 里的 N 必须仍是归一化后的指令下标，而不是文本行号。
     */
    private static void labelRoundTripUsesNormalizedIndex(){
        String base = compiled(whileSugar());
        int label = labelInstructionIndex(base, "__ls_stmt_3");
        check(label >= 0, "the while fixture must lower with a __ls_stmt_3: label:\n" + base);
        check(textLineIndex(base, "__ls_stmt_3") > label,
            "the label line must not occupy an instruction index (line " + textLineIndex(base, "__ls_stmt_3") + " vs instruction " + label + ")");
        if(label < 0) return;

        // 第二趟：把目标写成第一趟算出的指令下标，再编译一次——归一化后必须还是同一个下标。
        String sugar = whileSugar() + "set @counter " + label + "\n";
        String code = compiled(sugar);
        check(labelInstructionIndex(code, "__ls_stmt_3") == label,
            "appending a statement after the loop must not move __ls_stmt_3");

        CounterJumpIndex index = new CounterJumpIndex(code);
        CounterJumpIndex.Write write = writeWithLine(index, "set @counter " + label);
        check(write != null, "the user's set @counter card must survive lowering:\n" + code);
        if(write == null) return;
        check(Arrays.equals(write.targets, new int[]{label}), "target must be the normalized index " + label);
        check(!write.wrap, "index " + label + " must be inside the program");
        check(index.ownerOf(label) == 3, "the target must still land on statement 3, got " + index.ownerOf(label));
        check(write.statement == 3, "the write itself belongs to statement 3, got " + write.statement);
        check(index.describe(write) == null, "a single known statement target needs no tooltip: " + index.describe(write));
    }

    private static void entrySkipWrapsToZero(){
        CounterJumpIndex index = new CounterJumpIndex(compiled(whileSugar()));
        CounterJumpIndex.Write write = writeWithLine(index, "set @counter 0");

        check(write != null, "the compiler's entry skip must be found");
        if(write == null) return;
        check(write.note.equals("entry-skip"), "the trailing zero write is the entry skip, got " + write.note);
        check(Arrays.equals(write.targets, new int[]{0}), "the UI wants targets {0}, got " + Arrays.toString(write.targets));
        check(write.wrap, "the entry skip wraps the program, so wrap must be true");
        check(write.statement == 3, "the entry skip's instruction sits in statement 3's lowering, got " + write.statement);
        check("entry skip (wraps to 0)".equals(index.describe(write)), "unexpected tooltip: " + index.describe(write));
    }

    /**
     * 位置是入口跳过形状的一部分（与 SugarDecompiler 的判定一致）：中间那句
     * {@code set @counter 0} 只是普通的字面量写入，末尾那句才是入口跳过。
     */
    private static void midProgramZeroIsNotEntrySkip(){
        CounterJumpIndex index = new CounterJumpIndex("set x 0\nset @counter 0\nprint x\nend\n");
        CounterJumpIndex.Write write = index.writeAt(1);

        check(write != null, "set @counter 0 in the middle was not detected");
        if(write == null) return;
        check(write.note.equals("set-literal"), "only the trailing zero write is the entry skip, got " + write.note);
        check(Arrays.equals(write.targets, new int[]{0}), "a mid-program reset targets 0, got " + Arrays.toString(write.targets));
        check(!write.wrap, "0 is inside the program, so nothing wraps");
        check("@counter = 0".equals(index.describe(write)), "unexpected tooltip: " + index.describe(write));
    }

    /** hoist 前导跳之后的函数体不算主程序：入口跳过在它之前，仍按"主程序末尾"识别。 */
    private static void entrySkipBeforeHoistedBodies(){
        String code = compiled(functionSugar());
        CounterJumpIndex index = new CounterJumpIndex(code);
        CounterJumpIndex.Write skip = writeWithLine(index, "set @counter 0");

        check(skip != null, "the entry skip must be found in a program with hoisted bodies");
        if(skip == null) return;
        check(skip.note.equals("entry-skip"), "the hoist prelude after it must not hide the entry skip, got " + skip.note);
        check(Arrays.equals(skip.targets, new int[]{0}) && skip.wrap, "the entry skip reports {0} and wrap");
        check(skip.instruction < index.instructionCount() - 1, "the hoisted function bodies must follow the entry skip");
    }

    private static void relativeAddAndSub(){
        String code = "set x 0\nop add @counter @counter 1\nop add y x 1\nprint y\nend\n";
        CounterJumpIndex index = new CounterJumpIndex(code);
        CounterJumpIndex.Write add = index.writeAt(1);

        check(add != null, "op add @counter @counter 1 was not detected");
        if(add == null) return;
        check(add.note.equals("relative"), "a numeric add is relative, got " + add.note);
        // 执行器先读后自增：本指令下标 1 → counter 已是 2 → 再加 1 = 3。
        check(Arrays.equals(add.targets, new int[]{3}), "target must be instruction + 2, got " + Arrays.toString(add.targets));
        check(!add.wrap, "3 is inside a 5-instruction program");
        String tooltip = index.describe(add);
        check(tooltip != null && tooltip.contains("\u2192"), "relative tooltips show the arrow, got " + tooltip);
        check("@counter += 1 \u2192 3".equals(tooltip), "unexpected tooltip: " + tooltip);

        CounterJumpIndex back = new CounterJumpIndex("set x 0\nop sub @counter @counter 2\nprint x\nend\n");
        CounterJumpIndex.Write sub = back.writeAt(1);
        check(sub != null && sub.note.equals("relative"), "op sub @counter @counter 2 must be relative");
        if(sub == null) return;
        // 1 + 1 - 2 = 0
        check(Arrays.equals(sub.targets, new int[]{0}), "target must be instruction + 1 - k, got " + Arrays.toString(sub.targets));
        check(!sub.wrap, "0 is inside the program");
        check("@counter -= 2 \u2192 0".equals(back.describe(sub)), "unexpected tooltip: " + back.describe(sub));

        CounterJumpIndex overflow = new CounterJumpIndex("op add @counter @counter 9\nprint x\n");
        CounterJumpIndex.Write far = overflow.writeAt(0);
        check(far != null && far.wrap, "0 + 1 + 9 = 10 is outside a 2-instruction program");
        if(far != null){
            check(Arrays.equals(far.targets, new int[]{10}), "candidates are reported as computed, got " + Arrays.toString(far.targets));
        }
    }

    private static void switchDispatchHasNoTarget(){
        CounterJumpIndex index = new CounterJumpIndex("set x 0\nop add @counter @counter x\nprint x\nend\n");
        CounterJumpIndex.Write write = index.writeAt(1);

        check(write != null, "the switch dispatch write was not detected");
        if(write == null) return;
        check(write.note.equals("switch-dispatch"), "a non-numeric add is the jump table, got " + write.note);
        check(write.targets.length == 0, "a jump table target is not statically determinable");
        check("switch jump table".equals(index.describe(write)), "unexpected tooltip: " + index.describe(write));
    }

    /** 真实的 switch 跳转表：编译器对密集 switch 发出的就是 op add @counter @counter <运行时索引>。 */
    private static void switchTableDispatchFromCompiler(){
        String code = compiled(dupSwitch());
        CounterJumpIndex index = new CounterJumpIndex(code);
        CounterJumpIndex.Write dispatch = null;

        for(CounterJumpIndex.Write write : index.writes()){
            if(write.note.equals("switch-dispatch")) dispatch = write;
        }
        check(dispatch != null, "a dense switch must lower to a jump-table dispatch:\n" + code);
        if(dispatch == null) return;
        check(dispatch.line.startsWith("op add @counter @counter "), "unexpected dispatch text: " + dispatch.line);
        check(dispatch.targets.length == 0, "a table dispatch is not statically determinable");
        check("switch jump table".equals(index.describe(dispatch)), "unexpected tooltip: " + index.describe(dispatch));
    }

    private static void readCounterIsDynamic(){
        CounterJumpIndex index = new CounterJumpIndex("read @counter cell1 0\nprint @counter\nend\n");
        CounterJumpIndex.Write write = index.writeAt(0);

        check(write != null, "read @counter was not detected");
        if(write == null) return;
        check(write.note.equals("dynamic"), "a memory read into @counter is dynamic, got " + write.note);
        check(write.targets.length == 0, "a read target is not statically determinable");
        check("target depends on a runtime value".equals(index.describe(write)), "unexpected tooltip: " + index.describe(write));

        // 其它能写目的操作数的种类同样算写入、同样不可判定。
        CounterJumpIndex other = new CounterJumpIndex("op mul @counter x 2\nend\n");
        CounterJumpIndex.Write mul = other.writeAt(0);
        check(mul != null && mul.note.equals("dynamic") && mul.targets.length == 0,
            "op mul @counter is a dynamic write: " + mul);
    }

    private static void outOfRangeLiteralWraps(){
        CounterJumpIndex index = new CounterJumpIndex("set x 0\nset @counter 99\nend\n");
        CounterJumpIndex.Write write = index.writeAt(1);

        check(write != null, "set @counter 99 was not detected");
        if(write == null) return;
        check(Arrays.equals(write.targets, new int[]{99}), "the literal is reported as written, got " + Arrays.toString(write.targets));
        check(write.wrap, "99 is outside a 3-instruction program");
        String tooltip = index.describe(write);
        check(tooltip != null && tooltip.contains("wraps to 0"), "the tooltip must mention the wrap, got " + tooltip);
    }

    private static void carriersAreNotInstructions(){
        String code = compiled(whileSugar());
        CounterJumpIndex index = new CounterJumpIndex(code);
        int carriers = carrierLineCount(code);

        check(code.contains("set __ls_sugar \""), "the fixture must carry a persistence carrier");
        check(carriers == 1, "the fixture must carry exactly one carrier line, got " + carriers);
        check(index.instructionCount() == new CounterJumpIndex(withoutCarrierLines(code)).instructionCount(),
            "carrier lines changed the instruction count");
        check(index.instructionCount() == SugarCompiler.emittedInstructionCount(code) - carriers,
            "instructionCount must be the executable lines minus the carriers: " + index.instructionCount()
                + " vs " + SugarCompiler.emittedInstructionCount(code) + " - " + carriers);
        check(index.instructionCount() < SugarCompiler.emittedInstructionCount(code),
            "carriers must not be counted as instructions");
    }

    /**
     * 载体行判定的边界：{@code __ls_sugar}/{@code __ls_lib} 与纯数字分片都算载体，
     * 用户变量 {@code __ls_sugarx} 不算（它照常占一条指令）。
     */
    private static void carrierNameRule(){
        CounterJumpIndex sharded = new CounterJumpIndex("set __ls_sugar_1 \"AAA\"\nset __ls_lib_2 \"BBB\"\nset x 1\n");
        check(sharded.instructionCount() == 1, "sharded carriers must not be instructions, got " + sharded.instructionCount());

        CounterJumpIndex plain = new CounterJumpIndex("set __ls_sugar \"AAA\"\nset __ls_lib \"BBB\"\nset x 1\n");
        check(plain.instructionCount() == 1, "single carriers must not be instructions, got " + plain.instructionCount());

        CounterJumpIndex user = new CounterJumpIndex("set __ls_sugarx 1\nset x 2\n");
        check(user.instructionCount() == 2, "a user variable starting with __ls_sugar must count, got " + user.instructionCount());

        CounterJumpIndex notSet = new CounterJumpIndex("print __ls_sugar\nset x 2\n");
        check(notSet.instructionCount() == 2, "only a set statement can be a carrier, got " + notSet.instructionCount());
    }

    private static void structuralLabelAttributesStatement(){
        String code = compiled(whileSugar());
        CounterJumpIndex index = new CounterJumpIndex(code);
        int label = labelInstructionIndex(code, "__ls_stmt_3");

        check(label >= 0, "the fixture must contain __ls_stmt_3:");
        check(index.ownerOf(label) == 3, "the label must attribute its instruction to statement 3, got " + index.ownerOf(label));
        check(index.ownerOf(0) == 0, "the leading __ls_stmt_0: label owns instruction 0, got " + index.ownerOf(0));
    }

    /**
     * 真实源码里的 {@code ;} 多语句行、注释行、空行和带 {@code ;}/# 的字符串都会让"文本行号"和
     * "语句下标"脱钩：标签回填必须仍然对得上（对不上时本类会整体放弃归属，测试就会红）。
     */
    private static void messySourceKeepsAttribution(){
        String code = compiled("""
            # a comment line
            set a 1
            set b 2; set c 3

            print "a ; b # c"
            ifbegin a equal 1 6
            print yes
            blockend
            whilebegin a lessThan 3 9
            op add a a 1
            blockend
            """);
        CounterJumpIndex index = new CounterJumpIndex(code);
        // 语句：0 set a、1 set b、2 set c（同一文本行）、3 print、4 ifbegin、5 print yes、
        // 6 blockend、7 whilebegin、8 op add、9 blockend、10 入口跳过 —— if 的出口是 7，while 的出口是 10。
        int exit = labelInstructionIndex(code, "__ls_stmt_7");
        int loopExit = labelInstructionIndex(code, "__ls_stmt_10");

        check(exit >= 0, "the if exit label must survive the messy source:\n" + code);
        check(loopExit >= 0, "the while exit label must survive the messy source:\n" + code);
        check(index.ownerOf(exit) == 7, "the if exit label must own statement 7, got " + index.ownerOf(exit));
        check(index.ownerOf(loopExit) == 10, "the while exit label must own statement 10, got " + index.ownerOf(loopExit));
        check(writeWithLine(index, "set @counter 0") != null, "the entry skip must still be found");
    }

    private static void functionRegionIsUnknown(){
        String sugar = functionSugar();
        String code = compiled(sugar);
        CounterJumpIndex index = new CounterJumpIndex(code);
        int entry = labelInstructionIndex(code, "__ls_func_func_entry");

        check(entry >= 0, "the function fixture must hoist __ls_func_func_entry:\n" + code);
        check(index.ownerOf(entry) == -1,
            "a target inside the hoisted function body must be unknown, not a wrong card: " + index.ownerOf(entry));

        CounterJumpIndex.Write trampoline = writeWithLine(index, "set @counter __ls_func_func_ret");
        check(trampoline != null, "the function return trampoline must be found:\n" + code);
        if(trampoline == null) return;
        check(trampoline.note.equals("function-return"), "the trampoline is a function return, got " + trampoline.note);
        check(trampoline.targets.length == 0, "a runtime return address is not statically determinable");
        check("function return trampoline".equals(index.describe(trampoline)), "unexpected tooltip: " + index.describe(trampoline));
    }

    private static void provenanceIsLengthChecked(){
        String code = compiled(whileSugar());
        int label = labelInstructionIndex(code, "__ls_stmt_3");
        CounterJumpIndex plain = new CounterJumpIndex(code);
        CounterJumpIndex nullProvenance = new CounterJumpIndex(code, null);
        CounterJumpIndex emptyProvenance = new CounterJumpIndex(code, new int[0]);

        check(sameOwners(plain, nullProvenance), "null provenance must behave like the label fallback");
        check(sameOwners(plain, emptyProvenance), "an empty provenance array must be ignored (length mismatch)");

        int[] wrongLength = new int[plain.instructionCount() + 3];
        Arrays.fill(wrongLength, 9);
        CounterJumpIndex ignored = new CounterJumpIndex(code, wrongLength);
        check(sameOwners(plain, ignored), "a length mismatch must be ignored, never mis-indexed");

        int[] partial = new int[plain.instructionCount()];
        Arrays.fill(partial, -1);
        partial[0] = 5;
        CounterJumpIndex filled = new CounterJumpIndex(code, partial);
        check(filled.ownerOf(0) == 5, "an exact-length provenance array is authoritative, got " + filled.ownerOf(0));
        check(label >= 0 && filled.ownerOf(label) == 3,
            "slots left at -1 are still filled by the label fallback, got " + filled.ownerOf(label));
        check(filled.ownerOf(plain.instructionCount()) == -1, "out-of-range instructions have no owner");
    }

    /**
     * 真实调用方的路径：{@code SugarCompiler.compileRecorded} 的来源通道按同一条编号传入时，
     * 归属以它为准确认（负值——含 {@code syntheticOrigin} 的 -2——仍然交给标签回填，绝不外泄）。
     */
    private static void recordedProvenanceIsHonored(){
        SugarCompiler.CompileProvenance provenance = SugarCompiler.compileRecorded(whileSugar(),
            SugarCompiler.FuncMode.normal, null, null, SugarCompiler.currentStrategy(), SugarCompiler.AssertEmit.strip, true);
        check(provenance != null, "compileRecorded must return a provenance channel for a sugar program");
        if(provenance == null) return;

        CounterJumpIndex index = new CounterJumpIndex(provenance.code, provenance.origins);
        check(provenance.instructions() == index.instructionCount(),
            "the caller's channel and instructionCount() must share one numbering: "
                + provenance.instructions() + " vs " + index.instructionCount());

        // 循环体语句 1 的指令：以来源通道为准。
        int body = -1;
        for(int i = 0; i < provenance.instructions(); i++){
            if(provenance.originOf(i) == 1) body = i;
        }
        check(body >= 0, "the provenance channel must attribute an instruction to statement 1");
        if(body >= 0){
            check(index.ownerOf(body) == 1, "an exact-length channel is authoritative, got " + index.ownerOf(body));
        }

        // 编译器自发射的指令（入口跳过是 syntheticOrigin）：不得把 -2 这种哨兵值漏给界面。
        for(int i = 0; i < provenance.instructions(); i++){
            if(provenance.originOf(i) == SugarFunctions.syntheticOrigin){
                check(index.ownerOf(i) != SugarFunctions.syntheticOrigin,
                    "a synthetic origin must never leak out of ownerOf at instruction " + i);
                break;
            }
        }

        int label = labelInstructionIndex(provenance.code, "__ls_stmt_3");
        check(label >= 0 && index.ownerOf(label) == 3,
            "negative channel slots fall back to the structural label, got " + index.ownerOf(label));
    }

    /**
     * 轨道分配（{@link JumpLanes}）：重叠的线必须不同轨，否则所有 {@code @counter} 指示线会挤在
     * 同一条竖线上互相穿插（2026-09-25 报告）。规则镜像原版 {@code StatementsTable.setJumpHeights}，
     * 所以这里钉的是"哪些线不许同轨"，而不是具体层号。
     */
    private static void jumpLanesSeparateOverlappingCurves(){
        // ① 互不相交：都贴最内层
        int[] lanes = JumpLanes.assign(new int[]{0, 10}, new int[]{2, 12}, new boolean[]{false, false});
        check(lanes[0] == 0 && lanes[1] == 0,
            "disjoint spans must share the innermost rail, got " + Arrays.toString(lanes));

        // ② 首尾相接不算重叠（端点相同 = 上一条正好结束）：原版用 end > begin 判断，同样同轨
        lanes = JumpLanes.assign(new int[]{0, 5}, new int[]{5, 9}, new boolean[]{false, false});
        check(lanes[0] == 0 && lanes[1] == 0,
            "touching spans may share a rail, got " + Arrays.toString(lanes));

        // ③ 真正重叠：必须分开
        lanes = JumpLanes.assign(new int[]{0, 3}, new int[]{6, 9}, new boolean[]{false, false});
        check(lanes[0] != lanes[1],
            "overlapping spans must not share a rail, got " + Arrays.toString(lanes));

        // ④ 三条互相重叠：需要三条不同的轨道
        lanes = JumpLanes.assign(new int[]{0, 1, 2}, new int[]{9, 8, 7}, new boolean[]{false, false, true});
        check(lanes[0] != lanes[1] && lanes[1] != lanes[2] && lanes[0] != lanes[2],
            "three mutually overlapping spans need three rails, got " + Arrays.toString(lanes));

        // ⑤ 嵌套：内层贴积木、外层让开（原版语义）
        lanes = JumpLanes.assign(new int[]{0, 2}, new int[]{10, 4}, new boolean[]{false, false});
        check(lanes[1] < lanes[0],
            "a nested span must stay closer to the cards than the span containing it, got "
                + Arrays.toString(lanes));

        // ⑥ 区间完全相同的线共用一层（原版的代表元合并）
        lanes = JumpLanes.assign(new int[]{0, 0}, new int[]{6, 6}, new boolean[]{false, false});
        check(lanes[0] == lanes[1],
            "identical spans may share a rail, got " + Arrays.toString(lanes));

        // ⑦ 空输入不炸
        check(JumpLanes.assign(new int[0], new int[0], new boolean[0]).length == 0,
            "the empty case must return an empty result");
    }

    private static void malformedInputDegrades(){
        CounterJumpIndex none = new CounterJumpIndex(null);
        check(none.instructionCount() == 0 && none.writes().isEmpty(), "null code must give an empty index");
        check(new CounterJumpIndex("").instructionCount() == 0, "empty code must give an empty index");
        check(new CounterJumpIndex("   \n\n\t\n").instructionCount() == 0, "blank code must give an empty index");

        // 未定义标签让 LParser 抛错：退化成按行计数，既不抛异常也不谎报写入。
        CounterJumpIndex broken = new CounterJumpIndex("jump nowhere always x false\n");
        check(broken.instructionCount() == 1, "a parse failure must degrade to line counting, got " + broken.instructionCount());
        check(broken.writes().isEmpty(), "a failed parse must not invent writes");

        // 认不出的语句会变成 noop，但仍然占一条指令：后面的下标不会因此错位。
        CounterJumpIndex prose = new CounterJumpIndex("hello world this is prose\nset @counter 1\n");
        check(prose.instructionCount() == 2, "unreadable statements still occupy one instruction, got " + prose.instructionCount());
        CounterJumpIndex.Write write = prose.writeAt(1);
        check(write != null, "the write after a noop was not found");
        if(write == null) return;
        check(Arrays.equals(write.targets, new int[]{1}), "the write after a noop keeps its index: " + Arrays.toString(write.targets));
        check(!write.wrap, "target 1 is inside a 2-instruction program");
    }

    private static void boundsAreSafe(){
        CounterJumpIndex index = new CounterJumpIndex("set x 0\nset @counter 1\n");

        check(index.ownerOf(-1) == -1, "a negative instruction has no owner");
        check(index.ownerOf(index.instructionCount()) == -1, "an instruction past the end has no owner");
        check(index.writeAt(-1) == null && index.writeAt(99) == null, "out-of-range writeAt must be null");
        check(index.writes() != null, "writes() is never null");
        check(index.describe(null) == null, "a null write has no tooltip");
        check(index.writes().size() == 1, "one write expected, got " + index.writes().size());
    }

    /**
     * 带 funcdef 的程序里 __ls_stmt_&lt;N&gt; 的 N 是 lowering 的可见主程序下标，必须经
     * mainToCanvas 换算回画布下标；provenance 通道是权威来源，这个映射只服务没有 provenance
     * 或槽位为负的调用方。
     */
    private static void labelsMapThroughMainSource(){
        String sugar = "funcdef f a 5\nreturn \"a + 1\"\nblockend\n"
            + "whilebegin x lessThan 3 4\nset x x 1\nblockend\nfunccall f \"1\" out\n";
        SugarCompiler.CompileProvenance p = SugarCompiler.compileRecorded(sugar,
            SugarCompiler.FuncMode.normal, null, null, SugarCompiler.currentStrategy(),
            SugarCompiler.AssertEmit.strip, true);
        check(p != null && p.mainToCanvas != null, "compileRecorded must expose the main-to-canvas map");
        if(p == null || p.mainToCanvas == null) return;
        check(Arrays.equals(p.mainToCanvas, new int[]{3, 4, 5, 6, 7}),
            "visible main statements must map to canvas 3/4/5/6/7, got " + Arrays.toString(p.mainToCanvas));
        int at = labelInstructionIndex(p.code, "__ls_stmt_3");
        check(at >= 0, "the while exit label __ls_stmt_3 must exist:\n" + p.code);
        if(at < 0) return;
        CounterJumpIndex withMap = new CounterJumpIndex(p.code, null, p.mainToCanvas);
        CounterJumpIndex withoutMap = new CounterJumpIndex(p.code, null);
        check(withMap.ownerOf(at) == 6,
            "with the map the label must resolve to canvas statement 6 (funccall), got " + withMap.ownerOf(at));
        check(withoutMap.ownerOf(at) == 3,
            "without the map the raw lowering index is used, got " + withoutMap.ownerOf(at));
    }

    // ===== fixtures & helpers ==============================================================

    /** 一段带结构标签与入口跳过的真实编译产物（normal 模式、无函数库）。 */
    private static String compiled(String sugar){
        return SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null);
    }

    /** 语句 0 = whilebegin，1 = 循环体，2 = blockend；退出标签是 __ls_stmt_3，3 = 入口跳过。 */
    private static String whileSugar(){
        return "whilebegin x lessThan 3 2\nset x x 1\nblockend\n";
    }

    /** normal 模式把函数体 hoist 到 main 之后，用 jump __ls_end 跨过去。 */
    private static String functionSugar(){
        return "funcdef func x,y,z 2\nreturn \"x+y+z\"\nblockend\nfunccall func \"1, 2, 3\" x\n";
    }

    /** 值只有 0/1 的密集 switch：代价模型必然选跳转表（chain 13，带容差收口的表是 12）。 */
    private static String dupSwitch(){
        StringBuilder body = new StringBuilder();
        for(int i = 0; i < 6; i++){
            body.append("case 0\nprint zero\n");
            body.append("case 1\nprint one\n");
        }
        int dest = 1 + body.toString().split("\n", -1).length - 1;
        return "switchbegin x " + dest + "\n" + body + "blockend\nend\n";
    }

    private static CounterJumpIndex.Write writeWithLine(CounterJumpIndex index, String line){
        for(CounterJumpIndex.Write write : index.writes()){
            if(write.line.equals(line)) return write;
        }
        return null;
    }

    private static boolean sameOwners(CounterJumpIndex a, CounterJumpIndex b){
        if(a.instructionCount() != b.instructionCount()) return false;
        for(int i = 0; i < a.instructionCount(); i++){
            if(a.ownerOf(i) != b.ownerOf(i)) return false;
        }
        return true;
    }

    /** {@code label:} 之后的指令在归一化流里的下标；没有这个标签时 -1。 */
    private static int labelInstructionIndex(String code, String label){
        int instruction = 0;
        for(String line : lines(code)){
            String trimmed = line.trim();
            if(trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if(trimmed.equals(label + ":")) return instruction;
            if(isLabelLine(trimmed) || isCarrierLine(trimmed)) continue;
            instruction++;
        }
        return -1;
    }

    /** {@code label:} 所在的物理行号（0 基），用来证明行号与指令下标不是一回事。 */
    private static int textLineIndex(String code, String label){
        List<String> lines = lines(code);
        for(int i = 0; i < lines.size(); i++){
            if(lines.get(i).trim().equals(label + ":")) return i;
        }
        return -1;
    }

    private static List<String> lines(String code){
        List<String> result = new ArrayList<>();
        for(String line : code.replace("\r\n", "\n").split("\n", -1)) result.add(line);
        return result;
    }

    /** 只有一个 token 且以 {@code :} 结尾的行是标签，不占指令。 */
    private static boolean isLabelLine(String trimmed){
        return trimmed.endsWith(":") && !trimmed.contains(" ") && !trimmed.contains("\t");
    }

    private static boolean isCarrierLine(String trimmed){
        String[] tokens = trimmed.split("\\s+");
        if(tokens.length < 2 || !tokens[0].equals("set")) return false;
        String name = tokens[1];
        return name.equals("__ls_sugar") || name.equals("__ls_lib")
            || name.startsWith("__ls_sugar_") || name.startsWith("__ls_lib_");
    }

    private static int carrierLineCount(String code){
        int count = 0;
        for(String line : lines(code)){
            if(isCarrierLine(line.trim())) count++;
        }
        return count;
    }

    private static String withoutCarrierLines(String code){
        StringBuilder out = new StringBuilder();
        for(String line : lines(code)){
            if(isCarrierLine(line.trim())) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static void check(boolean condition, String message){
        checks++;
        if(!condition){
            failures++;
            System.err.println("FAIL: " + message);
        }
    }
}
