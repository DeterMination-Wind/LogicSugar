package logicsugar.assist.expr;

import logicsugar.assist.data.RecordModule;

import java.util.ArrayList;
import java.util.List;

/**
 * 记录（record）成员访问的表达式扩展：{@code p.f1} 读、{@code p.f1 = expr} 写。
 *
 * <p>成员展开只在「基底是 {@link ExprCompiler.Var} 且其名字是 {@link RecordModule}
 * 注册表里的已声明 record」时生效（{@link #isMemberBase}）。因此：</p>
 * <ul>
 *   <li>{@code unit.health}、{@code unit.x} 等既有 sensor 成员访问不受影响——名字不是
 *       record 时 provider 返回 null，{@link ExprCompiler#compileNode} 继续走
 *       {@code resolveMember} 的 sensor 路径；</li>
 *   <li>已声明 record 的未知成员按笔误报编译错误（不再退回 sensor）；</li>
 *   <li>降级产物只有一条原版指令：读 {@code op add <tmp> <name>_<field> 0}，
 *       写 {@code op add <name>_<field> <value> 0}。</li>
 * </ul>
 *
 * <p>实现必须留在 {@code logicsugar.assist.expr} 包：{@code Node}/{@code Var}/{@code Line}
 * 是 {@link ExprCompiler} 的包私有类型。</p>
 */
public final class RecordIntrinsics implements ExprIntrinsics.Provider{
    public static final RecordIntrinsics INSTANCE = new RecordIntrinsics();

    /** 记录没有表达式函数，只有成员访问。 */
    private static final String[] CALL_NAMES = new String[0];

    private RecordIntrinsics(){}

    @Override
    public String[] callNames(){
        return CALL_NAMES;
    }

    @Override
    public List<ExprCompiler.Line> expandCall(String name, List<ExprCompiler.Node> args, ExprIntrinsics.Ctx ctx){
        return null;
    }

    @Override
    public boolean isMemberBase(ExprCompiler.Node base){
        return info(base) != null;
    }

    @Override
    public List<ExprCompiler.Line> readMember(ExprCompiler.Node base, String prop, ExprIntrinsics.Ctx ctx){
        RecordModule.RecordInfo info = requireField(base, prop, ctx);
        if(info == null) return null;
        List<ExprCompiler.Line> lines = new ArrayList<>(1);
        // 普通变量读取的等价单条原版指令（dest 是临时变量，外层表达式据此接线）
        lines.add(new ExprCompiler.OpLine("add", ctx.temp(), info.variable(prop), "0"));
        return lines;
    }

    @Override
    public List<ExprCompiler.Line> writeMember(ExprCompiler.Node base, String prop, ExprCompiler.Node value,
                                                ExprIntrinsics.Ctx ctx){
        RecordModule.RecordInfo info = requireField(base, prop, ctx);
        if(info == null) return null;
        // value 的编译行由 ctx.compile 追加到调用方的 ops 列表（writeMember 的返回值接在其后）
        String operand = ctx.compile(value);
        List<ExprCompiler.Line> lines = new ArrayList<>(1);
        lines.add(new ExprCompiler.OpLine("add", info.variable(prop), operand, "0"));
        return lines;
    }

    /** 基底对应的已声明 record；不是 record 变量时为 null（调用方退回 sensor 路径）。 */
    private static RecordModule.RecordInfo info(ExprCompiler.Node base){
        if(!(base instanceof ExprCompiler.Var var)) return null;
        RecordModule.RecordRegistry registry = RecordModule.active();
        return registry == null ? null : registry.get(var.name);
    }

    /** 解析成员字段，未知字段抛编译错误；基底不是 record 时返回 null（退回 sensor）。 */
    private static RecordModule.RecordInfo requireField(ExprCompiler.Node base, String prop, ExprIntrinsics.Ctx ctx){
        RecordModule.RecordInfo info = info(base);
        if(info == null) return null;
        if(!info.hasField(prop)){
            throw ctx.error(ExprIntrinsics.text("la.err.record_unknown_field",
                "record {0} has no field {1}", info.name, prop));
        }
        return info;
    }
}
