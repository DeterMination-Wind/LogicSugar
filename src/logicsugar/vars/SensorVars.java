package logicsugar.vars;

import arc.func.Cons;
import mindustry.Vars;
import mindustry.ctype.Content;
import mindustry.ctype.MappableContent;
import mindustry.gen.Building;
import mindustry.logic.LAccess;
import mindustry.logic.LVar;
import mindustry.logic.Senseable;

/**
 * 任意 Senseable 的「传感器」视图（{@code VarsDialog} 里的 Properties）：行是
 * {@code LAccess.all} 的全部传感项，能装物品/液体时再加上每种物品与液体。
 *
 * <p>Ported from upstream MlogAssertions v0.11.3 ({@code cardillan.mlogassertions.data.SensorVars}),
 * verbatim：v0.11.2 把构造参数从建筑放宽到 {@link Senseable}（单位、队伍、内容物都能看
 * Properties），{@code hasItems()} 保留上游的写法——`entity instanceof Building &amp;&amp; … ||
 * entity instanceof Senseable` 对任何 Senseable 都为真，所以单位视图也会列出全部物品/液体
 * 行（读数恒为 0）。这里刻意与上游保持一致而不是「修好」，否则同一份快照在两边的行号会错位。
 * 标签与内容表是静态缓存的，读取 {@code Vars.content}，所以本类只能在游戏内容加载完成后使用
 * （无界面自检里不要碰它）。</p>
 */
public class SensorVars extends BaseVariableValues{
    protected static String[] accessLabels = new String[LAccess.all.length];
    protected static String[] itemLabels = new String[Vars.content.items().size];
    protected static String[] liquidLabels = new String[Vars.content.liquids().size];
    protected static MappableContent[] items = new MappableContent[Vars.content.items().size];
    protected static MappableContent[] liquids = new MappableContent[Vars.content.liquids().size];

    static{
        for(int i = 0; i < accessLabels.length; i++){
            accessLabels[i] = " @" + LAccess.all[i].name() + " ";
        }

        for(int i = 0; i < itemLabels.length; i++){
            items[i] = Vars.content.items().get(i);
            itemLabels[i] = " @" + items[i].name + " ";
        }
        for(int i = 0; i < liquidLabels.length; i++){
            liquids[i] = Vars.content.liquids().get(i);
            liquidLabels[i] = " @" + liquids[i].name + " ";
        }
    }

    public final int length;
    public final Content[] contents;
    public final String[] labels;

    public SensorVars(Senseable entity){
        super(entity);

        length = accessLabels.length
                + (hasItems() ? itemLabels.length : 0)
                + (hasLiquids() ? liquidLabels.length : 0);

        contents = new Content[length - accessLabels.length];
        labels = new String[length];

        System.arraycopy(accessLabels, 0, labels, 0, accessLabels.length);
        int index = 0;
        if(hasItems()){
            System.arraycopy(items, 0, contents, index, items.length);
            System.arraycopy(itemLabels, 0, labels, accessLabels.length + index, itemLabels.length);
            index += itemLabels.length;
        }
        if(hasLiquids()){
            System.arraycopy(liquids, 0, contents, index, liquids.length);
            System.arraycopy(liquidLabels, 0, labels, accessLabels.length + index, liquidLabels.length);
        }
    }

    /** 上游语义（见类注释）：对任何 Senseable 都为真。 */
    private boolean hasItems(){
        return entity instanceof Building && ((Building)entity).block.hasItems || entity instanceof Senseable;
    }

    private boolean hasLiquids(){
        return entity instanceof Building && ((Building)entity).block.hasLiquids;
    }

    @Override
    public BlockDataType dataType(){
        return BlockDataType.properties;
    }

    @Override
    public int size(){
        return length;
    }

    @Override
    public String label(int index, boolean hex){
        return labels[index];
    }

    @Override
    public boolean isObj(int index){
        return index >= accessLabels.length ? LVar.invalid(entity.sense(contents[index - accessLabels.length]))
                : entity.senseObject(LAccess.all[index]) != Senseable.noSensed || LVar.invalid(entity.sense(LAccess.all[index]));
    }

    @Override
    public boolean isLink(int index){
        return false;
    }

    @Override
    public Object obj(int index){
        if(index >= accessLabels.length){
            return null;
        }else{
            Object result = entity.senseObject(LAccess.all[index]);
            return result == Senseable.noSensed ? null : result;
        }
    }

    @Override
    public double num(int index){
        double value = index >= accessLabels.length ? entity.sense(contents[index - accessLabels.length]) : entity.sense(LAccess.all[index]);
        return Double.isNaN(value) ? 0 : value;
    }

    @Override
    public String textBuffer(){
        return "";
    }

    @Override
    public void clear(){
        // 传感器读数由实体自己决定，这里不能清
    }

    @Override
    public void setView(boolean sorted, boolean filtered, boolean hideLinks){
    }

    @Override
    public void eachObject(Cons<Object> getter){
        for(int index = 0; index < LAccess.all.length; index++){
            Object value = obj(index);
            if(value != null) getter.get(value);
        }
    }
}
