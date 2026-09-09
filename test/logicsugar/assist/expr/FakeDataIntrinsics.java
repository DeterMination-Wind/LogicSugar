package logicsugar.assist.expr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 数据框架自测用的假 provider（{@link DataFrameworkSelfTest} 的测试夹具）。
 *
 * <p>必须放在 {@code logicsugar.assist.expr} 包：{@link ExprCompiler.Node} 是包私有类型，
 * 其它包无法实现 {@link ExprIntrinsics.Provider} 的签名。</p>
 *
 * <p>能力：{@code fakeadd(a,b)} → 一条 {@code op add}；{@code fakedouble(v)} → 调用注入函数
 * {@code __ls_builtin_fakedouble}；{@code fx.v} 成员读 → {@code op add dest 7 0}，
 * {@code fx.v = value} 成员写 → {@code op add __fake_fx_v value 0}。</p>
 */
public final class FakeDataIntrinsics implements ExprIntrinsics.Provider{
    public static final FakeDataIntrinsics INSTANCE = new FakeDataIntrinsics();

    public static final String BUILTIN_NAME = "__ls_builtin_fakedouble";
    public static final String BUILTIN_SUGAR = "funcdef " + BUILTIN_NAME + " x 3\n"
        + "op mul __ls_fake_out x 2\n"
        + "return \"__ls_fake_out\"\n"
        + "blockend\n";

    private static final String[] CALL_NAMES = {"fakeadd", "fakedouble"};

    private FakeDataIntrinsics(){}

    @Override
    public String[] callNames(){
        return CALL_NAMES;
    }

    @Override
    public int arity(String name){
        if(name.equals("fakeadd")) return 2;
        if(name.equals("fakedouble")) return 1;
        return -1;
    }

    @Override
    public List<ExprCompiler.Line> expandCall(String name, List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        if(name.equals("fakeadd")){
            String a = ctx.compile(args.get(0));
            String b = ctx.compile(args.get(1));
            List<ExprCompiler.Line> lines = new ArrayList<>(1);
            lines.add(new ExprCompiler.OpLine("add", ctx.temp(), a, b));
            return lines;
        }
        if(name.equals("fakedouble")){
            String value = ctx.compile(args.get(0));
            List<ExprCompiler.Line> lines = new ArrayList<>(1);
            lines.add(new ExprCompiler.CallLine(BUILTIN_NAME, value, ctx.temp()));
            return lines;
        }
        return null;
    }

    @Override
    public boolean isMemberBase(ExprCompiler.Node base){
        return base instanceof ExprCompiler.Var var && var.name.equals("fx");
    }

    @Override
    public List<ExprCompiler.Line> readMember(ExprCompiler.Node base, String prop, ExprIntrinsics.Ctx ctx){
        if(!isMemberBase(base) || !prop.equals("v")) return null;
        List<ExprCompiler.Line> lines = new ArrayList<>(1);
        lines.add(new ExprCompiler.OpLine("add", ctx.temp(), "7", "0"));
        return lines;
    }

    @Override
    public List<ExprCompiler.Line> writeMember(ExprCompiler.Node base, String prop, ExprCompiler.Node value, ExprIntrinsics.Ctx ctx){
        if(!isMemberBase(base) || !prop.equals("v")) return null;
        String operand = ctx.compile(value);
        List<ExprCompiler.Line> lines = new ArrayList<>(1);
        lines.add(new ExprCompiler.OpLine("add", "__fake_fx_v", operand, "0"));
        return lines;
    }

    @Override
    public List<String> callees(String name, int argc){
        return name.equals("fakedouble") ? Collections.singletonList(BUILTIN_NAME) : Collections.emptyList();
    }
}
