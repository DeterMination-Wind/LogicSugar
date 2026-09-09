package logicsugar.assist.data;

import mindustry.logic.SugarStatements;

/**
 * 所有数据声明卡的基类：声明卡只是编译期元数据，lower 阶段整体跳过（不产出任何 mlog 行），
 * 因此产物始终是纯原版指令，联机（含自建服）与单机行为一致。
 *
 * <p>继承 {@link SugarStatements.SugarStatement}：共享「build(LAssembler) 返回 NoopI」与
 * {@link SugarStatements#dataStructures} 分类，并让 {@code SugarCompiler.containsSugar} 把
 * 「只有声明卡的程序」也当作 sugar 程序编译（否则纯声明卡程序会被原样返回，卡片文本泄漏到产物）。</p>
 *
 * <p>子类必须实现 {@code write(StringBuilder)} 与 {@code build(Table)}；文本格式必须是
 * 固定 token 数的原版 sugar 行（空槽写 {@code ~}），并配一个
 * {@code LAssembler.customParsers} 解析器（由所属 {@link DataModule#registerParsers()} 注册）。</p>
 */
public abstract class DataDeclaration extends SugarStatements.SugarStatement{

    /** 声明卡的语法 token（如 {@code record}/{@code stack}），用于错误消息与调试。 */
    public abstract String token();

    @Override
    public String typeName(){
        return token();
    }

    @Override
    public mindustry.logic.LCategory category(){
        return SugarStatements.dataStructures;
    }
}
