package logicsugar.assist.data;

import logicsugar.assist.expr.ArrayBulkIntrinsics;
import logicsugar.assist.expr.ExprIntrinsics;
import mindustry.logic.LStatement;

import java.util.List;
import java.util.Set;

/**
 * 数组批量运算模块（示例模块 + 契约 §1 功能）：
 * {@code sum(buf)} {@code avg(buf)} {@code min(buf)} {@code max(buf)} {@code count(buf,v)}
 * {@code indexof(buf,v)} {@code fill(buf,v)} {@code copy(dst,src)} {@code sortasc(buf)}
 * {@code sortdesc(buf)} {@code reverse(buf)} {@code replace(buf,old,neu)} {@code swap(buf,i,j)}
 * {@code bsearch(buf,v)}。
 *
 * <p>复用 F1 的 {@code array}/{@code matrix} 声明卡，不新增卡片，因此
 * {@link #registerParsers()} 与 {@link #markInvalid} 都是空操作；数组的严格校验
 * （重名/越界/容量）仍由 {@code ArrayRegistry.compileRegistry} 负责。表达式展开见
 * {@link ArrayBulkIntrinsics}：编译期解析 memory/base/size，发射对注入函数
 * {@code __ls_builtin_arr*} 的 {@code funccall}，循环体在 normal 模式下全程序共享一份。</p>
 */
public class ArrayBulkModule extends DataModule{
    public static final String ID = "array-bulk";

    @Override
    public String id(){
        return ID;
    }

    @Override
    public void registerParsers(){
        // 复用 array/matrix 卡（F1 已注册），无自有声明卡
    }

    @Override
    public void collect(List<LStatement> statements, Set<String> functionNames){
        // 数组元数据由 ArrayRegistry.active() 提供，本模块无自有编译期注册表
    }

    @Override
    public void markInvalid(List<LStatement> statements, boolean[] invalid, Set<String> functionNames){
        // 无自有声明卡；array/matrix 的编辑期标红由 ArrayRegistry.markInvalidStatements 负责
    }

    @Override
    public ExprIntrinsics.Provider intrinsics(){
        return ArrayBulkIntrinsics.INSTANCE;
    }

    @Override
    public List<String> builtinSugar(){
        return ArrayBulkIntrinsics.builtinSugar();
    }
}
