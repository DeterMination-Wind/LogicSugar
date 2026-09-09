package logicsugar.assist.data;

import arc.struct.Seq;
import logicsugar.assist.expr.ArrayRegistry;
import logicsugar.assist.expr.ChainIntrinsics;
import logicsugar.assist.expr.ExprCompiler;
import logicsugar.assist.expr.ExprIntrinsics;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M6 链表自测（{@link ChainModule} + {@link ChainIntrinsics}）。
 *
 * <p>覆盖契约 §5 与本模块规格：</p>
 * <ul>
 *   <li><b>表达式层</b>：11 个操作在编译期注册表上下文中展开为精确的原版链/注入函数调用，
 *       实参按 memory/base/size/head/free 绑定；未声明名字/非名字实参/错误实参个数报编译错误。</li>
 *   <li><b>语义层</b>：用一个只认识原版 op/read/write/jump/set/funccall 的小解释器执行展开链，
 *       验证 cinit 建立空闲链、cnew 的 LIFO 取节点与满链 -1、cfree 的摘链 + 挂回空闲链、
 *       clen 遍历、cget/cset/cnext/clink/cshead 的越界守卫与 NaN 哨兵。</li>
 *   <li><b>编译层</b>：声明卡不产指令；内置函数注入（normal 共享一份、未使用不进产物、
 *       不进 {@code __ls_lib} 载体）；产物逐行纯原版指令；载体往返与存储流一致。</li>
 *   <li><b>校验层</b>：重名/保留前缀/非法字面量/容量/区间重叠/与数组矩阵函数及其它结构重名
 *       都在编译期报错，编辑期 markInvalid 标红。</li>
 * </ul>
 */
public class ChainTest{
    public static void main(String[] args){
        SugarStatements.installParsers();
        Vars.logicVars = new GlobalVars();
        DataModules.clearModules();
        DataModules.register(new ChainModule());
        DataModules.registerParsers();

        loadBuiltins();
        expressionExpansions();
        builtinBodies();
        argumentErrors();
        chainBehaviour();
        declarationErrors();
        editorMarkInvalid();
        declarationCardProducesNoLine();
        builtinInjectionAndSharing();
        unusedBuiltinsStayOut();
        outputIsPureVanilla();
        roundTripAndVerification();
        editorVisibility();

        DataModules.clearModules();
        System.out.println("LogicSugar Chain self-test passed.");
    }

    // ===== 表达式层：精确展开 =====

    private static void expressionExpansions(){
        withRegistry("chain c cell1 2 4", () -> {
            checkLine("op add x __ls_chn_c_head 0", textOf(ExprCompiler.compile("x", "chead(c)")));
            checkLine("op add __ls_chn_c_head 5 0\nop add x 1 0",
                textOf(ExprCompiler.compile("x", "cshead(c, 5)")));
            // cinit/cclear 语义等价：head=-1、free=0、注入函数返回 size
            checkLine("op sub __ls_chn_c_head 0 1\n"
                    + "op add __ls_chn_c_free 0 0\n"
                    + "funccall __ls_builtin_chninit \"cell1, 2, 4\" x",
                textOf(ExprCompiler.compile("x", "cinit(c)")));
            checkLine("op sub __ls_chn_c_head 0 1\n"
                    + "op add __ls_chn_c_free 0 0\n"
                    + "funccall __ls_builtin_chninit \"cell1, 2, 4\" x",
                textOf(ExprCompiler.compile("x", "cclear(c)")));
            // cnew：先存旧空闲链头（即返回的节点下标），函数返回新空闲链头并回写 free
            checkLine("op add _1 __ls_chn_c_free 0\n"
                    + "funccall __ls_builtin_chnnew \"cell1, 2, __ls_chn_c_free\" __ls_chn_c_free\n"
                    + "op add x _1 0",
                textOf(ExprCompiler.compile("x", "cnew(c)")));
            // cget：无分支越界守卫 + read（越界地址回落到 -1 → NaN）
            checkLine("op lessThan _1 i 0\n"
                    + "op greaterThanEq _2 i 4\n"
                    + "op or _3 _1 _2\n"
                    + "op mul _4 i 2\n"
                    + "op add _4 2 _4\n"
                    + "op add _5 _4 1\n"
                    + "op mul _3 _3 _5\n"
                    + "op sub _4 _4 _3\n"
                    + "read x cell1 _4",
                textOf(ExprCompiler.compile("x", "cget(c, i)")));
            checkLine("op lessThan _1 0 0\n"
                    + "op greaterThanEq _2 0 4\n"
                    + "op or _3 _1 _2\n"
                    + "op mul _4 0 2\n"
                    + "op add _4 2 _4\n"
                    + "op add _5 _4 1\n"
                    + "op mul _3 _3 _5\n"
                    + "op sub _4 _4 _3\n"
                    + "read x cell1 _4",
                textOf(ExprCompiler.compile("x", "cget(c, 0)")));
            checkLine("funccall __ls_builtin_chnset \"cell1, 2, 4, i, 5\" x",
                textOf(ExprCompiler.compile("x", "cset(c, i, 5)")));
            checkLine("funccall __ls_builtin_chnnext \"cell1, 2, 4, i\" x",
                textOf(ExprCompiler.compile("x", "cnext(c, i)")));
            checkLine("funccall __ls_builtin_chnlink \"cell1, 2, 4, i, j\" x",
                textOf(ExprCompiler.compile("x", "clink(c, i, j)")));
            checkLine("funccall __ls_builtin_chnlen \"cell1, 2, __ls_chn_c_head\" x",
                textOf(ExprCompiler.compile("x", "clen(c)")));
            // cfree：先算有效标志，函数摘链并返回新头，调用点回写 head/free 并返回 1/0
            checkLine("op greaterThanEq _1 i 0\n"
                    + "op lessThan _2 i 4\n"
                    + "op land _3 _1 _2\n"
                    + "funccall __ls_builtin_chnfree \"cell1, 2, 4, __ls_chn_c_head, __ls_chn_c_free, i\" _4\n"
                    + "op add __ls_chn_c_head _4 0\n"
                    + "op sub _5 1 _3\n"
                    + "op mul _6 i _3\n"
                    + "op mul _7 __ls_chn_c_free _5\n"
                    + "op add __ls_chn_c_free _6 _7\n"
                    + "op add x _3 0",
                textOf(ExprCompiler.compile("x", "cfree(c, i)")));
            // 大小写不敏感（与 ExprCompiler 的数学内置一致）
            checkLine("op add x __ls_chn_c_head 0", textOf(ExprCompiler.compile("x", "CHEAD(c)")));
            // 与其它运算组合：实参先编译，再发调用
            checkLine("op add _0 i 1\nfunccall __ls_builtin_chnnext \"cell1, 2, 4, _0\" _1\nop mul x _1 2",
                textOf(ExprCompiler.compile("x", "cnext(c, i + 1) * 2")));
        });
        // 非零 base：地址与注入函数实参都按声明值展开
        withRegistry("chain c bank1 10 4", () -> {
            checkLine("op sub __ls_chn_c_head 0 1\n"
                    + "op add __ls_chn_c_free 0 0\n"
                    + "funccall __ls_builtin_chninit \"bank1, 10, 4\" x",
                textOf(ExprCompiler.compile("x", "cinit(c)")));
            checkLine("op lessThan _1 i 0\n"
                    + "op greaterThanEq _2 i 4\n"
                    + "op or _3 _1 _2\n"
                    + "op mul _4 i 2\n"
                    + "op add _4 10 _4\n"
                    + "op add _5 _4 1\n"
                    + "op mul _3 _3 _5\n"
                    + "op sub _4 _4 _3\n"
                    + "read x bank1 _4",
                textOf(ExprCompiler.compile("x", "cget(c, i)")));
            checkLine("funccall __ls_builtin_chnlen \"bank1, 10, __ls_chn_c_head\" x",
                textOf(ExprCompiler.compile("x", "clen(c)")));
        });
    }

    // ===== 注入函数源文本 =====

    private static void builtinBodies(){
        List<String> bodies = ChainIntrinsics.builtinSugar();
        check(bodies.size() == 7, "expected seven injected chain bodies, got " + bodies.size());
        checkLine("funcdef __ls_builtin_chninit mem,base,size 17\n"
            + "set __ls_ci_i 0\n"
            + "jump 10 greaterThanEq __ls_ci_i size\n"
            + "op mul __ls_ci_a __ls_ci_i 2\n"
            + "op add __ls_ci_a base __ls_ci_a\n"
            + "op add __ls_ci_n __ls_ci_a 1\n"
            + "op add __ls_ci_v __ls_ci_i 1\n"
            + "write __ls_ci_v mem __ls_ci_n\n"
            + "op add __ls_ci_i __ls_ci_i 1\n"
            + "jump 2 always x false\n"
            + "op sub __ls_ci_l size 1\n"
            + "op mul __ls_ci_a __ls_ci_l 2\n"
            + "op add __ls_ci_a base __ls_ci_a\n"
            + "op add __ls_ci_n __ls_ci_a 1\n"
            + "write -1 mem __ls_ci_n\n"
            + "op add __ls_ci_r size 0\n"
            + "return \"__ls_ci_r\"\n"
            + "blockend\n", bodies.get(0));
        checkLine("funcdef __ls_builtin_chnnew mem,base,head 10\n"
            + "jump 8 lessThan head 0\n"
            + "op mul __ls_ci_a head 2\n"
            + "op add __ls_ci_a base __ls_ci_a\n"
            + "op add __ls_ci_n __ls_ci_a 1\n"
            + "read __ls_ci_r mem __ls_ci_n\n"
            + "write -1 mem __ls_ci_n\n"
            + "jump 9 always x false\n"
            + "op sub __ls_ci_r 0 1\n"
            + "return \"__ls_ci_r\"\n"
            + "blockend\n", bodies.get(1));
        checkLine("funcdef __ls_builtin_chnfree mem,base,size,head,free,i 34\n"
            + "jump 32 lessThan i 0\n"
            + "jump 32 greaterThanEq i size\n"
            + "op mul __ls_ci_a i 2\n"
            + "op add __ls_ci_a base __ls_ci_a\n"
            + "op add __ls_ci_n __ls_ci_a 1\n"
            + "read __ls_ci_ni mem __ls_ci_n\n"
            + "op equal __ls_ci_same head i\n"
            + "op sub __ls_ci_d __ls_ci_ni head\n"
            + "op mul __ls_ci_d __ls_ci_same __ls_ci_d\n"
            + "op add __ls_ci_h head __ls_ci_d\n"
            + "jump 26 equal __ls_ci_same 1\n"
            + "set __ls_ci_j head\n"
            + "jump 26 lessThan __ls_ci_j 0\n"
            + "op mul __ls_ci_a __ls_ci_j 2\n"
            + "op add __ls_ci_a base __ls_ci_a\n"
            + "op add __ls_ci_n __ls_ci_a 1\n"
            + "read __ls_ci_nj mem __ls_ci_n\n"
            + "jump 21 equal __ls_ci_nj i\n"
            + "set __ls_ci_j __ls_ci_nj\n"
            + "jump 13 always x false\n"
            + "op mul __ls_ci_a i 2\n"
            + "op add __ls_ci_a base __ls_ci_a\n"
            + "op add __ls_ci_a __ls_ci_a 1\n"
            + "read __ls_ci_t mem __ls_ci_a\n"
            + "write __ls_ci_t mem __ls_ci_n\n"
            + "op mul __ls_ci_a i 2\n"
            + "op add __ls_ci_a base __ls_ci_a\n"
            + "op add __ls_ci_a __ls_ci_a 1\n"
            + "write free mem __ls_ci_a\n"
            + "op add __ls_ci_r __ls_ci_h 0\n"
            + "jump 33 always x false\n"
            + "op add __ls_ci_r head 0\n"
            + "return \"__ls_ci_r\"\n"
            + "blockend\n", bodies.get(2));
        checkLine("funcdef __ls_builtin_chnset mem,base,size,i,v 10\n"
            + "jump 8 lessThan i 0\n"
            + "jump 8 greaterThanEq i size\n"
            + "op mul __ls_ci_a i 2\n"
            + "op add __ls_ci_a base __ls_ci_a\n"
            + "write v mem __ls_ci_a\n"
            + "set __ls_ci_r 1\n"
            + "jump 9 always x false\n"
            + "set __ls_ci_r 0\n"
            + "return \"__ls_ci_r\"\n"
            + "blockend\n", bodies.get(3));
        checkLine("funcdef __ls_builtin_chnnext mem,base,size,i 10\n"
            + "jump 8 lessThan i 0\n"
            + "jump 8 greaterThanEq i size\n"
            + "op mul __ls_ci_a i 2\n"
            + "op add __ls_ci_a base __ls_ci_a\n"
            + "op add __ls_ci_a __ls_ci_a 1\n"
            + "read __ls_ci_r mem __ls_ci_a\n"
            + "jump 9 always x false\n"
            + "op sub __ls_ci_r 0 1\n"
            + "return \"__ls_ci_r\"\n"
            + "blockend\n", bodies.get(4));
        checkLine("funcdef __ls_builtin_chnlink mem,base,size,i,j 11\n"
            + "jump 9 lessThan i 0\n"
            + "jump 9 greaterThanEq i size\n"
            + "op mul __ls_ci_a i 2\n"
            + "op add __ls_ci_a base __ls_ci_a\n"
            + "op add __ls_ci_a __ls_ci_a 1\n"
            + "write j mem __ls_ci_a\n"
            + "set __ls_ci_r 1\n"
            + "jump 10 always x false\n"
            + "set __ls_ci_r 0\n"
            + "return \"__ls_ci_r\"\n"
            + "blockend\n", bodies.get(5));
        checkLine("funcdef __ls_builtin_chnlen mem,base,head 11\n"
            + "set __ls_ci_i head\n"
            + "set __ls_ci_r 0\n"
            + "jump 10 lessThan __ls_ci_i 0\n"
            + "op add __ls_ci_r __ls_ci_r 1\n"
            + "op mul __ls_ci_a __ls_ci_i 2\n"
            + "op add __ls_ci_a base __ls_ci_a\n"
            + "op add __ls_ci_a __ls_ci_a 1\n"
            + "read __ls_ci_i mem __ls_ci_a\n"
            + "jump 3 always x false\n"
            + "return \"__ls_ci_r\"\n"
            + "blockend\n", bodies.get(6));
    }

    // ===== 错误路径 =====

    private static void argumentErrors(){
        withRegistry("chain c cell1 0 4", () -> {
            checkThrowsExpr(() -> ExprCompiler.compile("x", "chead(nosuch)"), "undeclared chain");
            checkThrowsExpr(() -> ExprCompiler.compile("x", "cnew(nosuch)"), "undeclared chain (cnew)");
            checkThrowsExpr(() -> ExprCompiler.compile("x", "cget(c[0], 1)"), "subscript argument");
            checkThrowsExpr(() -> ExprCompiler.compile("x", "clen(1 + 2)"), "expression argument");
        });
        // 没有 chain 声明上下文时给出明确错误（编辑器无画布/纯表达式预览）
        checkThrowsExpr(() -> ExprCompiler.compile("x", "chead(c)"), "no declaration context");
    }

    // ===== 语义层：解释执行展开链 =====

    private static void chainBehaviour(){
        withRegistry("chain c cell1 0 4", () -> {
            double[] memory = new double[64];
            Map<String, Double> vars = new HashMap<>();

            // 未初始化：head/free 读作 0，cnew 会把 0 号节点当空闲（规格要求先 cinit）。
            // 此时 next 槽也是 0，clen 会陷入 0→0 自环——正是必须显式初始化的原因，
            // 因此这里只断言状态变量读值，不调用遍历类操作。
            exec("chead(c)", vars, memory);
            check(num(vars, "x") == 0, "uninitialized head must read as 0 (mlog semantics)");
            exec("cnext(c, 0)", vars, memory);
            check(num(vars, "x") == 0, "uninitialized next must read as 0");

            // cinit：head=-1、free=0、空闲链 0→1→2→3→-1，返回 size
            exec("cinit(c)", vars, memory);
            check(num(vars, "x") == 4, "cinit must return size");
            check(num(vars, "__ls_chn_c_head") == -1, "cinit must clear the head");
            check(num(vars, "__ls_chn_c_free") == 0, "cinit must point free at node 0");
            check(memory[1] == 1 && memory[3] == 2 && memory[5] == 3 && memory[7] == -1,
                "free chain must be 0->1->2->3->-1, got next slots "
                    + memory[1] + "," + memory[3] + "," + memory[5] + "," + memory[7]);
            exec("chead(c)", vars, memory);
            check(num(vars, "x") == -1, "empty chain head must be -1");
            exec("clen(c)", vars, memory);
            check(num(vars, "x") == 0, "empty chain length must be 0");

            // cnew 三次：LIFO 取节点 0/1/2，每个节点 next 置 -1
            exec("cnew(c)", vars, memory);
            check(num(vars, "x") == 0, "first cnew must return node 0");
            check(num(vars, "__ls_chn_c_free") == 1, "free must advance to node 1");
            exec("cnew(c)", vars, memory);
            check(num(vars, "x") == 1, "second cnew must return node 1");
            exec("cnew(c)", vars, memory);
            check(num(vars, "x") == 2, "third cnew must return node 2");
            check(num(vars, "__ls_chn_c_free") == 3, "free must advance to node 3");
            check(memory[1] == -1 && memory[3] == -1 && memory[5] == -1,
                "cnew must reset the allocated nodes' next to -1");
            exec("cnext(c, 0)", vars, memory);
            check(num(vars, "x") == -1, "freshly allocated node must be a tail");

            // clink + cshead 串成 0→1→2→-1
            exec("clink(c, 0, 1)", vars, memory);
            check(num(vars, "x") == 1, "clink must succeed for a valid node");
            exec("clink(c, 1, 2)", vars, memory);
            check(num(vars, "x") == 1, "clink must succeed for a valid node");
            exec("cshead(c, 0)", vars, memory);
            check(num(vars, "x") == 1, "cshead must always return 1");
            exec("chead(c)", vars, memory);
            check(num(vars, "x") == 0, "head must be node 0");
            exec("clen(c)", vars, memory);
            check(num(vars, "x") == 3, "chain 0->1->2->-1 must have length 3");

            // cset/cget：value 槽读写
            exec("cset(c, 0, 11)", vars, memory);
            check(num(vars, "x") == 1, "cset must return 1 for a valid node");
            exec("cset(c, 2, 7 * 3)", vars, memory);
            check(num(vars, "x") == 1, "cset must accept expression values");
            exec("cget(c, 0)", vars, memory);
            check(num(vars, "x") == 11, "cget must read the stored value");
            exec("cget(c, 2)", vars, memory);
            check(num(vars, "x") == 21, "cget must read the second stored value");
            exec("cget(c, 4)", vars, memory);
            check(Double.isNaN(raw(vars, "x")), "out-of-range cget must return NaN");
            exec("cset(c, 4, 9)", vars, memory);
            check(num(vars, "x") == 0, "out-of-range cset must return 0 and write nothing");
            exec("cset(c, -1, 9)", vars, memory);
            check(num(vars, "x") == 0, "negative cset index must return 0");

            // cnext/clink/cshead 的越界守卫
            exec("cnext(c, 0)", vars, memory);
            check(num(vars, "x") == 1, "cnext must follow the link");
            exec("cnext(c, 2)", vars, memory);
            check(num(vars, "x") == -1, "tail next must be -1");
            exec("cnext(c, 4)", vars, memory);
            check(num(vars, "x") == -1, "out-of-range cnext must return -1");
            exec("cnext(c, -1)", vars, memory);
            check(num(vars, "x") == -1, "negative cnext index must return -1");
            exec("clink(c, 4, 0)", vars, memory);
            check(num(vars, "x") == 0, "out-of-range clink must return 0");
            exec("clink(c, 2, -1)", vars, memory);
            check(num(vars, "x") == 1, "clink to -1 (tail) must succeed");
            exec("cshead(c, -1)", vars, memory);
            check(num(vars, "x") == 1, "cshead(-1) must be accepted");
            exec("cshead(c, 0)", vars, memory);

            // cfree 中间节点：摘链 0→2→-1，节点 1 挂回空闲链头
            exec("cfree(c, 1)", vars, memory);
            check(num(vars, "x") == 1, "cfree must return 1 for a valid node");
            check(num(vars, "__ls_chn_c_free") == 1, "freed node must become the free head");
            check(memory[3] == 3, "freed node's next must be the old free head");
            check(memory[1] == 2, "predecessor must skip the freed node");
            exec("clen(c)", vars, memory);
            check(num(vars, "x") == 2, "chain 0->2->-1 must have length 2");
            exec("cnext(c, 0)", vars, memory);
            check(num(vars, "x") == 2, "node 0 must now point at node 2");

            // cfree 头节点：head 直接后移
            exec("cfree(c, 0)", vars, memory);
            check(num(vars, "x") == 1, "cfree must return 1 for the head node");
            check(num(vars, "__ls_chn_c_head") == 2, "freeing the head must advance the head");
            check(num(vars, "__ls_chn_c_free") == 0, "freed head must become the free head");
            check(memory[1] == 1, "freed head's next must be the old free head");
            exec("clen(c)", vars, memory);
            check(num(vars, "x") == 1, "chain 2->-1 must have length 1");

            // cfree 尾节点（此时也是头）：链变空
            exec("cfree(c, 2)", vars, memory);
            check(num(vars, "x") == 1, "cfree must return 1 for the last node");
            check(num(vars, "__ls_chn_c_head") == -1, "freeing the last node must empty the chain");
            check(num(vars, "__ls_chn_c_free") == 2, "last freed node must become the free head");
            check(memory[5] == 0, "last freed node's next must be the old free head");
            exec("clen(c)", vars, memory);
            check(num(vars, "x") == 0, "empty chain length must be 0");
            exec("chead(c)", vars, memory);
            check(num(vars, "x") == -1, "empty chain head must be -1");

            // 越界 cfree：返回 0 且不改状态
            exec("cfree(c, 4)", vars, memory);
            check(num(vars, "x") == 0, "out-of-range cfree must return 0");
            check(num(vars, "__ls_chn_c_free") == 2 && num(vars, "__ls_chn_c_head") == -1,
                "out-of-range cfree must not touch the state");

            // 空闲链取空后 cnew 返回 -1
            exec("cnew(c)", vars, memory);
            check(num(vars, "x") == 2, "free list LIFO order broken (expected node 2)");
            exec("cnew(c)", vars, memory);
            check(num(vars, "x") == 0, "free list LIFO order broken (expected node 0)");
            exec("cnew(c)", vars, memory);
            check(num(vars, "x") == 1, "free list LIFO order broken (expected node 1)");
            exec("cnew(c)", vars, memory);
            check(num(vars, "x") == 3, "free list LIFO order broken (expected node 3)");
            check(num(vars, "__ls_chn_c_free") == -1, "free list must be exhausted");
            exec("cnew(c)", vars, memory);
            check(num(vars, "x") == -1, "cnew on a full chain must return -1");
            check(num(vars, "__ls_chn_c_free") == -1, "full cnew must keep free at -1");

            // cclear 重置：head=-1、空闲链重建，返回 size
            exec("cclear(c)", vars, memory);
            check(num(vars, "x") == 4, "cclear must return size");
            check(num(vars, "__ls_chn_c_head") == -1 && num(vars, "__ls_chn_c_free") == 0,
                "cclear must reset head/free");
            check(memory[1] == 1 && memory[3] == 2 && memory[5] == 3 && memory[7] == -1,
                "cclear must rebuild the free chain");
            exec("clen(c)", vars, memory);
            check(num(vars, "x") == 0, "chain must be empty after cclear");
        });
    }

    private static void exec(String expr, Map<String, Double> vars, double[] memory){
        run(ExprCompiler.compile("x", expr), vars, memory);
    }

    /**
     * 极简 mlog 解释器：执行本模块展开会产生的 op/read/funccall 行。语义对齐原版：
     * 越界读 → NaN，越界写 → 空操作，null/NaN 参与运算按 0（{@code LVar.num()}）。
     * 注入函数体逐行解释（jump/op/set/read/write/return），跳转目标按函数文本下标解析。
     */
    private static void run(List<ExprCompiler.Line> lines, Map<String, Double> vars, double[] memory){
        for(ExprCompiler.Line line : lines){
            if(line instanceof ExprCompiler.ReadLine read){
                int address = (int)num(vars, read.b);
                vars.put(read.dest, address >= 0 && address < memory.length ? memory[address] : Double.NaN);
            }else if(line instanceof ExprCompiler.CallLine call){
                runBuiltin(call, vars, memory);
            }else if(line instanceof ExprCompiler.OpLine op){
                vars.put(op.dest, apply(op.op, num(vars, op.a), num(vars, op.b)));
            }else{
                throw new AssertionError("unexpected line in a chain expansion: " + line.toText());
            }
        }
    }

    /** 执行注入函数体（{@code __ls_builtin_chn*}）。 */
    private static void runBuiltin(ExprCompiler.CallLine call, Map<String, Double> vars, double[] memory){
        Builtin builtin = builtins.get(call.name);
        check(builtin != null, "unknown injected function in a chain expansion: " + call.name);
        Map<String, Double> local = new HashMap<>();
        String[] args = call.args.split(",");
        check(args.length == builtin.params.size(), "arity mismatch for " + call.name);
        for(int i = 0; i < args.length; i++){
            local.put(builtin.params.get(i), num(vars, args[i].trim()));
        }
        int pc = 0, guard = 0;
        while(pc >= 0 && pc < builtin.body.size()){
            if(++guard > 100000) throw new AssertionError("builtin did not terminate: " + call.name);
            String[] tokens = builtin.body.get(pc).split("\\s+");
            switch(tokens[0]){
                case "jump": {
                    if(condition(tokens[2], num(local, tokens[3]), num(local, tokens[4]))){
                        pc = Integer.parseInt(tokens[1]) - 1; // 文本下标 1 = body[0]
                        continue;
                    }
                    break;
                }
                case "op":
                    local.put(tokens[2], apply(tokens[1], num(local, tokens[3]), num(local, tokens[4])));
                    break;
                case "set":
                    local.put(tokens[1], num(local, tokens[2]));
                    break;
                case "read": {
                    int address = (int)num(local, tokens[3]);
                    local.put(tokens[1], address >= 0 && address < memory.length ? memory[address] : Double.NaN);
                    break;
                }
                case "write": {
                    int address = (int)num(local, tokens[3]);
                    if(address >= 0 && address < memory.length){
                        memory[address] = num(local, tokens[1]);
                    }
                    break;
                }
                case "return":
                    vars.put(call.dest, local.get(tokens[1].replace("\"", "")));
                    return;
                default:
                    throw new AssertionError("unexpected statement in a builtin body: " + builtin.body.get(pc));
            }
            pc++;
        }
        throw new AssertionError("builtin did not return: " + call.name);
    }

    private static final class Builtin{
        final List<String> params = new ArrayList<>();
        final List<String> body = new ArrayList<>();
    }

    private static final Map<String, Builtin> builtins = new LinkedHashMap<>();

    private static void loadBuiltins(){
        for(String text : ChainIntrinsics.builtinSugar()){
            String[] lines = text.trim().split("\n");
            String[] header = lines[0].split("\\s+");
            Builtin builtin = new Builtin();
            for(String param : header[2].split(",")){
                builtin.params.add(param.trim());
            }
            // 去掉 funcdef 头与末尾 blockend
            for(int i = 1; i < lines.length - 1; i++){
                builtin.body.add(lines[i].trim());
            }
            builtins.put(header[1], builtin);
        }
    }

    /** 与 Mindustry LogicOp 一致的运算语义（double 存储，位运算走 long 转换）。 */
    private static double apply(String op, double a, double b){
        return switch(op){
            case "add" -> a + b;
            case "sub" -> a - b;
            case "mul" -> a * b;
            case "div" -> a / b;
            case "idiv" -> Math.floor(a / b);
            case "mod" -> a % b;
            case "min" -> Math.min(a, b);
            case "max" -> Math.max(a, b);
            case "shl" -> (double)((long)a << (long)b);
            case "shr" -> (double)((long)a >> (long)b);
            case "ushr" -> (double)((long)a >>> (long)b);
            case "and" -> (double)((long)a & (long)b);
            case "or" -> (double)((long)a | (long)b);
            case "xor" -> (double)((long)a ^ (long)b);
            case "not" -> (double)(~(long)a);
            case "equal" -> Math.abs(a - b) < 0.000001 ? 1 : 0;
            case "notEqual" -> Math.abs(a - b) < 0.000001 ? 0 : 1;
            case "land" -> a != 0 && b != 0 ? 1 : 0;
            case "lessThan" -> a < b ? 1 : 0;
            case "lessThanEq" -> a <= b ? 1 : 0;
            case "greaterThan" -> a > b ? 1 : 0;
            case "greaterThanEq" -> a >= b ? 1 : 0;
            default -> throw new AssertionError("unsupported op: " + op);
        };
    }

    private static boolean condition(String op, double a, double b){
        switch(op){
            case "always": return true;
            case "greaterThanEq": return a >= b;
            case "greaterThan": return a > b;
            case "lessThanEq": return a <= b;
            case "lessThan": return a < b;
            case "equal": return Math.abs(a - b) < 0.000001;
            case "notEqual": return Math.abs(a - b) >= 0.000001;
            default: throw new AssertionError("unexpected jump condition: " + op);
        }
    }

    /** 操作数求值：变量优先，否则数字字面量；NaN/Inf 按原版 {@code LVar.num()} 视作 0。 */
    private static double num(Map<String, Double> vars, String operand){
        Double value = vars.get(operand);
        if(value == null){
            try{
                value = Double.parseDouble(operand);
            }catch(NumberFormatException e){
                return 0;
            }
        }
        return Double.isNaN(value) || Double.isInfinite(value) ? 0 : value;
    }

    /** 原始值（不做 NaN → 0 归一），用于断言「返回 NaN」。 */
    private static double raw(Map<String, Double> vars, String operand){
        Double value = vars.get(operand);
        if(value == null){
            try{
                return Double.parseDouble(operand);
            }catch(NumberFormatException e){
                return 0;
            }
        }
        return value;
    }

    // ===== 声明校验 =====

    private static void declarationErrors(){
        checkThrows("chain c cell1 0 4\nchain c cell1 8 4", "duplicate chain name");
        checkThrows("chain __ls_bad cell1 0 4", "reserved prefix");
        checkThrows("chain 1bad cell1 0 4", "invalid identifier");
        checkThrows("chain c cell1 ~ 4", "non-integer base");
        checkThrows("chain c cell1 -1 4", "negative base");
        checkThrows("chain c cell1 0 0", "size below 1");
        checkThrows("chain c cell1 0 abc", "non-integer size");
        checkThrows("chain c cell1 0 1.5", "non-integer size literal");
        checkThrows("chain c cell1 60 8", "cell capacity exceeded (60 + 2*8)");
        checkThrows("chain c cell1 0 33", "cell capacity exceeded (2*33)");
        checkThrows("chain c bank1 510 4", "bank capacity exceeded (510 + 2*4)");
        checkThrows("chain a cell1 0 4\nchain b cell1 4 4", "overlapping ranges on one memory block");
        checkReadThrows("chain c ~ 0 4", "missing memory cell");
        checkReadThrows("chain ~ cell1 0 4", "missing chain name");
        checkThrowsWithArrays("chain buf cell1 8 4", "array buf cell1 0 4", "conflict with an array name");
        checkThrowsWithArrays("chain m cell1 8 4", "matrix m cell1 0 2 2", "conflict with a matrix name");
        checkThrowsWithFunctions("chain f cell1 0 4", Collections.singleton("f"), "conflict with a function name");

        // 与其它数据结构声明卡重名（反射读取同批卡片的名字）
        List<LStatement> mixed = new ArrayList<>();
        ContainerModule.StackDeclStatement stack = new ContainerModule.StackDeclStatement();
        stack.name = "c";
        stack.memory = "cell2";
        stack.base = "0";
        stack.size = "4";
        mixed.add(stack);
        mixed.addAll(statements("chain c cell1 0 4"));
        try{
            ChainModule.compileRegistry(mixed, null);
            check(false, "chain name clashing with another data structure must be rejected");
        }catch(IllegalArgumentException expected){
            // expected
        }

        // 同内存块相邻区间、不同内存块同地址都合法
        ChainModule.Registry adjacent = ChainModule.compileRegistry(
            statements("chain a cell1 0 4\nchain b cell1 8 4"), null);
        check(adjacent.size() == 2, "adjacent ranges on one memory block must be accepted");
        ChainModule.Registry distinct = ChainModule.compileRegistry(
            statements("chain a cell1 0 4\nchain b cell2 0 4"), null);
        check(distinct.size() == 2, "same addresses on distinct memories must be accepted");
        // 容量边界：cell 的最后一个合法地址
        ChainModule.Registry edge = ChainModule.compileRegistry(statements("chain c cell1 62 1"), null);
        check(edge.size() == 1, "chain at the capacity edge must be accepted");
    }

    // ===== 编辑期标红 =====

    private static void editorMarkInvalid(){
        Seq<LStatement> duplicate = LAssembler.read("chain c cell1 0 4\nchain c cell1 8 4\n", true);
        boolean[] invalid = SugarCompiler.invalidStatements(duplicate);
        check(!invalid[0] && invalid[1], "duplicate declaration must be marked invalid in the editor");

        Seq<LStatement> overflow = LAssembler.read("chain c cell1 60 8\n", true);
        boolean[] overflowInvalid = SugarCompiler.invalidStatements(overflow);
        check(overflowInvalid[0], "capacity overflow must be marked invalid");

        Seq<LStatement> clash = LAssembler.read("array c cell1 0 4\nchain c cell2 0 4\n", true);
        boolean[] clashInvalid = SugarCompiler.invalidStatements(clash);
        check(clashInvalid[1], "chain name clashing with an array must be marked invalid");

        // 合法声明 + 表达式在编辑期不标红（无画布时用显式注册表上下文模拟编辑器展开）
        Seq<LStatement> good = LAssembler.read(
            "chain c cell1 0 4\nifbegin expr \"clen(c) >= 0\" 3\nset x 1\nblockend\n", true);
        ChainModule.Registry previous = ChainModule.enter(
            ChainModule.compileRegistry(statements("chain c cell1 0 4"), null));
        boolean[] goodInvalid;
        try{
            goodInvalid = SugarCompiler.invalidStatements(good);
        }finally{
            ChainModule.leave(previous);
        }
        check(!goodInvalid[0], "valid chain declaration was marked invalid");
        check(!goodInvalid[1], "chain expression was marked invalid");
        check(!goodInvalid[2], "regular instruction was marked invalid");
    }

    // ===== 声明卡不产指令 / 载体往返 =====

    private static void declarationCardProducesNoLine(){
        String sugar = "chain c cell1 0 4\nchain d cell2 0 2\nset x 1\n";
        String mlog = stripCompile(sugar);
        // 载体行 `set __ls_sugar "<base64>"` 会保留，但它只承载源码文本；
        // 检查的是没有任何声明卡文本与隐藏状态变量进入降级产物。
        check(!mlog.contains("__ls_chn_"), "hidden chain state leaked into the product:\n" + mlog);
        for(String line : mlog.replace("\r\n", "\n").split("\n", -1)){
            String text = line.trim();
            check(!text.startsWith("chain "), "declaration card leaked into the product: " + text);
        }
        check(mlog.contains("set x 1"), "regular instruction lost:\n" + mlog);
        // 编辑器复制卡片：write → copy（重新解析）必须保持文本一致
        for(LStatement card : statements("chain c cell1 0 4\nchain d cell2 4 2\n")){
            StringBuilder text = new StringBuilder();
            card.write(text);
            LStatement copy = card.copy();
            check(copy != null, "declaration card copy failed: " + text);
            StringBuilder copied = new StringBuilder();
            copy.write(copied);
            check(text.toString().equals(copied.toString()),
                "declaration card copy changed its text: " + text + " vs " + copied);
        }
        check(SugarCompiler.restore(SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip)).equals(sugar),
            "declaration cards did not survive the carrier round trip");
    }

    // ===== 编译层：注入函数 =====

    private static void builtinInjectionAndSharing(){
        String sugar = "chain c cell1 0 4\n"
            + "ifbegin expr \"cnew(c) >= 0\" 3\nset x 1\nblockend\n"
            + "ifbegin expr \"cnew(c) >= 0\" 6\nset y 2\nblockend\n";
        String mlog = stripCompile(sugar);
        check(countOf(mlog, "__ls_func___ls_builtin_chnnew_entry:") == 1,
            "cnew body must be hoisted exactly once (shared subroutine):\n" + mlog);
        check(countOf(mlog, "jump __ls_func___ls_builtin_chnnew_entry always x false") == 2,
            "each cnew call site must jump to the shared entry:\n" + mlog);
        check(mlog.contains("set __ls_func___ls_builtin_chnnew_mem cell1"), "memory binding missing:\n" + mlog);
        check(mlog.contains("set __ls_func___ls_builtin_chnnew_base 0"), "base binding missing:\n" + mlog);
        check(mlog.contains("set __ls_func___ls_builtin_chnnew_head __ls_chn_c_free"),
            "head binding missing:\n" + mlog);
        check(mlog.contains("write -1 __ls_func___ls_builtin_chnnew_mem __ls_ci_n"),
            "cnew body write missing/mis-mangled:\n" + mlog);
        check(mlog.contains("set __ls_chn_c_free __ls_func___ls_builtin_chnnew_result"),
            "cnew result must be written back to the free variable:\n" + mlog);
        check(!mlog.contains("funccall ") && !mlog.contains("ifbegin ") && !mlog.contains("chain "),
            "sugar residue in the product:\n" + mlog);

        // cfree：摘链 + 回写 head，结果写回表达式目标
        String free = "chain c cell1 0 4\n"
            + "ifbegin expr \"cfree(c, 0) > 0\" 3\nset a 1\nblockend\n";
        String freeMlog = stripCompile(free);
        check(countOf(freeMlog, "__ls_func___ls_builtin_chnfree_entry:") == 1,
            "cfree body must be hoisted exactly once:\n" + freeMlog);
        check(freeMlog.contains("set __ls_func___ls_builtin_chnfree_head __ls_chn_c_head"),
            "cfree head binding missing:\n" + freeMlog);
        check(freeMlog.contains("set __ls_func___ls_builtin_chnfree_free __ls_chn_c_free"),
            "cfree free binding missing:\n" + freeMlog);
        check(freeMlog.contains("__ls_func___ls_builtin_chnfree_result"),
            "cfree function result missing from the product:\n" + freeMlog);
        // 条件命名空间下返回的新头先落到 __ls_cond_* 临时变量，再直线写回 head
        check(freeMlog.contains("op add __ls_chn_c_head __ls_cond"),
            "cfree result must be wired back into the head variable:\n" + freeMlog);
        check(freeMlog.contains("op add __ls_chn_c_free "), "cfree must update the free variable:\n" + freeMlog);

        // cinit：注入函数返回 size，head/free 由调用点直线写入
        String init = "chain c cell1 0 4\n"
            + "ifbegin expr \"cinit(c) > 0\" 3\nset b 1\nblockend\n";
        String initMlog = stripCompile(init);
        check(countOf(initMlog, "__ls_func___ls_builtin_chninit_entry:") == 1,
            "cinit body must be hoisted exactly once:\n" + initMlog);
        check(initMlog.contains("set __ls_func___ls_builtin_chninit_size 4"),
            "cinit size binding missing:\n" + initMlog);
        check(initMlog.contains("write __ls_ci_v __ls_func___ls_builtin_chninit_mem"),
            "cinit body write missing:\n" + initMlog);
    }

    private static void unusedBuiltinsStayOut(){
        String mlog = stripCompile("chain c cell1 0 4\n"
            + "ifbegin expr \"chead(c) >= -1\" 3\nset x 1\nblockend\n"
            + "ifbegin expr \"cget(c, 0) >= 0\" 6\nset y 2\nblockend\n");
        check(!mlog.contains("__ls_builtin"), "unused builtin leaked into the product:\n" + mlog);
        check(!mlog.contains("__ls_ci_"), "unused builtin body leaked into the product:\n" + mlog);
        check(mlog.contains("read "), "cget read missing from the product:\n" + mlog);
    }

    private static void outputIsPureVanilla(){
        String sugar = "chain c cell1 0 4\n"
            + "ifbegin expr \"cinit(c) > 0\" 3\nset a 1\nblockend\n"
            + "ifbegin expr \"cclear(c) > 0\" 6\nset b 2\nblockend\n"
            + "ifbegin expr \"cnew(c) >= -1\" 9\nset c 3\nblockend\n"
            + "ifbegin expr \"cfree(c, 0) > 0\" 12\nset d 4\nblockend\n"
            + "ifbegin expr \"cget(c, 0) >= 0\" 15\nset e 5\nblockend\n"
            + "ifbegin expr \"cset(c, 0, 1) > 0\" 18\nset f 6\nblockend\n"
            + "ifbegin expr \"cnext(c, 0) >= -1\" 21\nset g 7\nblockend\n"
            + "ifbegin expr \"clink(c, 0, -1) > 0\" 24\nset h 8\nblockend\n"
            + "ifbegin expr \"cshead(c, 0) > 0\" 27\nset i 9\nblockend\n"
            + "ifbegin expr \"chead(c) >= -1\" 30\nset j 10\nblockend\n"
            + "ifbegin expr \"clen(c) >= 0\" 33\nset k 11\nblockend\n";
        String mlog = stripCompile(sugar);
        for(String line : mlog.replace("\r\n", "\n").split("\n", -1)){
            String text = line.trim();
            if(text.isEmpty() || text.endsWith(":") || text.startsWith("#")) continue;
            int space = text.indexOf(' ');
            String opcode = space < 0 ? text : text.substring(0, space);
            check(vanillaOpcodes.contains(opcode), "non-vanilla instruction in product: " + text);
        }
        check(mlog.contains("__ls_ci_h"), "cfree builtin body missing from the product:\n" + mlog);
        check(mlog.contains("__ls_ci_l"), "cinit builtin body missing from the product:\n" + mlog);
    }

    private static void roundTripAndVerification(){
        String sugar = "chain c cell1 0 4\n"
            + "ifbegin expr \"cinit(c) > 0\" 3\nset x 1\nblockend\n"
            + "ifbegin expr \"cnew(c) >= 0\" 6\nset y 2\nblockend\n"
            + "ifbegin expr \"clen(c) >= 0\" 9\nset z 3\nblockend\n";
        String compiled = SugarCompiler.compile(sugar, SugarCompiler.FuncMode.normal, null, null,
            SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        check(SugarCompiler.restore(compiled).equals(sugar),
            "carrier did not preserve the chain source:\n" + SugarCompiler.restore(compiled));
        check(SugarCompiler.verifyRestore(compiled, sugar), "verifyRestore rejected a chain program");
        String recompiled = SugarCompiler.compile(SugarCompiler.restore(compiled), SugarCompiler.FuncMode.normal,
            null, null, SugarCompiler.SwitchStrategy.auto, SugarCompiler.AssertEmit.strip);
        check(SugarCompiler.matchesStoredStream(recompiled, compiled),
            "recompiled chain program drifted from the stored stream");
    }

    private static void editorVisibility(){
        check(DataModules.builtinFunctionNames().contains(ChainIntrinsics.BUILTIN_INIT),
            "cinit builtin name is not exposed");
        check(DataModules.builtinFunctionNames().contains(ChainIntrinsics.BUILTIN_FREE),
            "cfree builtin name is not exposed");
        check(SugarFunctions.paramsOf(ChainIntrinsics.BUILTIN_FREE, null) != null
            && SugarFunctions.paramsOf(ChainIntrinsics.BUILTIN_FREE, null)
                .equals(Arrays.asList("mem", "base", "size", "head", "free", "i")),
            "cfree builtin params are not visible to paramsOf");
        check(ExprIntrinsics.isIntrinsicName("cnew", 1) && !ExprIntrinsics.isIntrinsicName("cnew", 2),
            "cnew arity dispatch is broken");
        check(ExprIntrinsics.isIntrinsicName("cget", 2) && ExprIntrinsics.isIntrinsicName("cset", 3)
            && ExprIntrinsics.isIntrinsicName("clink", 3), "chain intrinsic arities are not registered");
        check(ExprIntrinsics.isIntrinsicName("cinit", 1) && ExprIntrinsics.isIntrinsicName("clen", 1)
            && ExprIntrinsics.isIntrinsicName("chead", 1) && ExprIntrinsics.isIntrinsicName("cnext", 2),
            "chain intrinsic names are not registered");
        check(!ExprIntrinsics.isIntrinsicName("clen", 2), "wrong arity must not be treated as an intrinsic");
        check(ChainModule.stateVar("c", ChainModule.FIELD_HEAD).equals("__ls_chn_c_head")
            && ChainModule.stateVar("c", ChainModule.FIELD_FREE).equals("__ls_chn_c_free"),
            "state variable naming mismatch");
        check(ChainIntrinsics.builtinSugar().size() == 7, "expected seven builtin function bodies");
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

    private static void withRegistry(String declarations, Runnable body){
        ChainModule.Registry registry = ChainModule.compileRegistry(statements(declarations), null);
        ChainModule.Registry previous = ChainModule.enter(registry);
        try{
            body.run();
        }finally{
            ChainModule.leave(previous);
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

    private static int countOf(String text, String needle){
        int count = 0, at = 0;
        while((at = text.indexOf(needle, at)) >= 0){
            count++;
            at += needle.length();
        }
        return count;
    }

    private static void checkThrows(String declarations, String what){
        checkThrowsFns(declarations, null, what);
    }

    private static void checkThrowsWithArrays(String declarations, String arrayDeclarations, String what){
        ArrayRegistry previous = ArrayRegistry.enter(
            ArrayRegistry.compileRegistry(LAssembler.read(arrayDeclarations, true), null));
        try{
            checkThrowsFns(declarations, null, what);
        }finally{
            ArrayRegistry.restore(previous);
        }
    }

    private static void checkThrowsWithFunctions(String declarations, Set<String> functions, String what){
        checkThrowsFns(declarations, functions, what);
    }

    private static void checkThrowsFns(String declarations, Set<String> functions, String what){
        try{
            ChainModule.compileRegistry(statements(declarations), functions);
        }catch(IllegalArgumentException e){
            return; // expected
        }
        check(false, "declaration should have failed (" + what + "): " + declarations);
    }

    private static void checkReadThrows(String declarations, String what){
        try{
            statements(declarations);
        }catch(RuntimeException expected){
            return;
        }
        check(false, "parse should have failed (" + what + "): " + declarations);
    }

    private static void checkThrowsExpr(Runnable body, String what){
        try{
            body.run();
        }catch(ExprCompiler.ParseException e){
            return; // expected
        }
        check(false, "expression should have failed (" + what + ")");
    }

    private static List<LStatement> statements(String sugar){
        Seq<LStatement> seq = LAssembler.read(sugar, true);
        List<LStatement> list = new ArrayList<>(seq.size);
        for(LStatement statement : seq) list.add(statement);
        return list;
    }

    private static void checkLine(String expected, String actual){
        check(expected.equals(actual), "mlog mismatch\n  expected:\n" + expected + "\n  actual:\n" + actual);
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
