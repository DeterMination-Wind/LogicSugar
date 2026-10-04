package logicsugar.vars;

import arc.Core;
import arc.graphics.g2d.TextureRegion;
import mindustry.Vars;
import mindustry.ctype.Content;
import mindustry.ctype.MappableContent;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Posc;
import mindustry.gen.Unit;
import mindustry.logic.Senseable;

import static logicsugar.vars.VarsOptions.COLOR_LIMIT;

/**
 * 变量视图的公共部分：时间文本、实体描述/位置/图标、数值与对象的格式化文本，以及
 * 「一行是什么类型」的判定。上游把颜色上限放在 {@code Constants}，这里改读
 * {@link VarsOptions#COLOR_LIMIT}（同一常量）。
 *
 * <p>Ported from upstream MlogAssertions v0.11.3 ({@code cardillan.mlogassertions.data.BaseVariableValues}),
 * 去掉对上游 UI（{@code VarsDialog}）的依赖，其余行为逐字保留。</p>
 *
 * <p>v0.11.2 的两处行为变化：数据源从 {@code Building} 放宽到 {@link Senseable}（单位/队伍/
 * 内容物也能建快照），以及时间戳从「毫秒格式的时间串」改成 {@code Vars.state.tick} 的
 * 两位小数文本（{@link #tick}）——旧格式的 `h:mm:ss.mmm` 只在一天内的会话里才有意义，
 * 而 tick 与游戏时钟一一对应。</p>
 *
 * <p>两个不是线格式的显示占位符（{@code [content]} / {@code [object]}）改成读
 * LogicSugar 的 bundle；{@code "null"} 保持原样，因为它是 mlog 字面量，
 * {@link MemoryText} 导入时要靠它还原「非有限数」与「空对象」。</p>
 */
public abstract class BaseVariableValues implements VariableValues{
    /** 快照/活视图创建时的游戏 tick（{@link #time()} 的两位小数文本来源）。 */
    public final double tick = Vars.state.tick;
    /** 数据源；单位、队伍、内容物等任何 Senseable 都可以。 */
    public final Senseable entity;

    public BaseVariableValues(Senseable entity){
        this.entity = entity;
    }

    @Override
    public Senseable entity(){
        return entity;
    }

    /** 实体的显示名：建筑/单位用本地化名字，其余类型退到类名。 */
    @Override
    public String entityDesc(){
        if(entity instanceof Building b) return b.block.localizedName;
        if(entity instanceof Unit u) return u.type.localizedName;
        return entity.getClass().getSimpleName();
    }

    private String entityPos;
    @Override
    public String entityPos(){
        if(entityPos == null){
            if(entity instanceof Posc p){
                entityPos = String.format("%.0f,\u00a0%.0f", p.x() / Vars.tilesize, p.y() / Vars.tilesize);
            }else{
                entityPos = "";
            }
        }
        return entityPos;
    }

    private String buildingDescMulti;
    @Override
    public String buildingDescMulti(){
        if(buildingDescMulti == null){
            if(entity instanceof Posc p){
                buildingDescMulti = String.format("%s\n[gray](%.0f,\u00a0%.0f)", entityDesc(), p.x() / Vars.tilesize, p.y() / Vars.tilesize);
            }else{
                // 上游这里写成 buildingPos = entityDesc()（没赋值给 buildingDescMulti，返回 null）。
                // 无头/非 Posc 实体（队伍、内容物）也走同一条路径，null 会让标题栏的
                // noWrapLabel 直接炸，所以这里修正为赋值给本字段（唯一的对上游偏离）。
                buildingDescMulti = entityDesc();
            }
        }
        return buildingDescMulti;
    }

    /** 建筑/单位的图集图标（无头环境与其它 Senseable 返回 null，调用方必须判空）。 */
    @Override
    public TextureRegion icon(){
        return entity instanceof Building b ? b.block.uiIcon :
                entity instanceof Unit unit ? unit.type.uiIcon :
                        null;
    }

    private String time;
    @Override
    public String time(){
        if(time == null) time = String.format("%,.2f", tick);
        return time;
    }

    @Override
    public boolean live(){
        return !(this instanceof Snapshot);
    }

    @Override
    public boolean valid(){
        return true;
    }

    private static final String[] formats = new String[16];
    static{
        for(int i = 0; i < formats.length; i++) formats[i] = "%." + i + "g";
    }

    @Override
    public String formatted(int index, boolean hex, int significantDigits){
        if(isObj(index)){
            Object obj = obj(index);
            if(obj instanceof String str){
                return str;
            }else{
                return obj == null ? "null" :
                       obj instanceof MappableContent content ? content.name :
                       obj instanceof Content c ? Core.bundle.get("logicsugar.vars.placeholder.content", "[content]") :
                       obj instanceof Building build ? build.block.name + pos(build.x(), build.y()) + id(build.id) :
                       obj instanceof Unit unit ? unit.type.name + pos(unit.x(), unit.y()) + id(unit.id) :
                       obj instanceof Enum<?> e ? e.name() :
                       obj instanceof Team team ? team.name :
                       Core.bundle.get("logicsugar.vars.placeholder.object", "[object]");
            }
        }else{
            double num = num(index);
            if(num <= COLOR_LIMIT && num > 0){
                long color = Double.doubleToLongBits(num) & 0xFFFFFFFFL;
                String str = Integer.toHexString((int)color);
                if(str.length() < 8) str = "0".repeat(8 - str.length()) + str;
                return '%' + str + " [#" + str.substring(0, 6) + "]\ue86b";
            }else if((long)num == num){
                return hex ? "0x" + Long.toHexString((long)num).toUpperCase() : Long.toString((long)num);
            }else{
                if(significantDigits >= formats.length) return Double.toString(num).toLowerCase();
                String str = String.format(formats[significantDigits], num);
                if(str.indexOf('e') > 0) return str;
                if(str.indexOf('.') == -1) return str + ".0";  // It's NOT an integer
                int l = str.length() - 1;
                while(l > 0 && str.charAt(l) == '0') l--;
                if(str.charAt(l) == '.') l++;
                return str.substring(0, l + 1);
            }
        }
    }

    public String clipboard(int index, boolean hex){
        return isObj(index) && obj(index) instanceof String str ? str : formatted(index, hex, 16);
    }

    public static String pos(float x, float y){
        return String.format(" [gray](%.0f,\u00A0%.0f)", x / Vars.tilesize, y / Vars.tilesize);
    }

    public static String id(int id){
        return " #" + Integer.toHexString(id);
    }

    /** 一行的数据类型。注意 {@link ValueType#dead}：失效（已拆除/已死）的
     *  建筑与单位仍然是一个对象槽，只是类型退到 dead。 */
    @Override
    public ValueType type(int index){
        if(isLink(index)){
            return ValueType.link;
        }else if(isObj(index)){
            Object objval = obj(index);
            return objval == null ? ValueType.nothing :
                   objval instanceof String ? ValueType.string :
                   objval instanceof Content ? ValueType.content :
                   objval instanceof Building b ? b.dead() ? ValueType.dead : ValueType.building :
                   objval instanceof Team ? ValueType.team :
                   objval instanceof Unit u ? u.dead() ? ValueType.dead : ValueType.unit :
                   objval instanceof Enum<?> ? ValueType.enumerated :
                   ValueType.unknown;
        }else{
            double num = num(index);
            return num == 0 ? ValueType.zero :
                   num <= COLOR_LIMIT && num > 0 ? ValueType.color :
                   (long)num == num ? ValueType.integer :
                   ValueType.number;
        }
    }

    protected float[] computeTypeDistribution(){
        int size = size();

        int[] counts = new int[ValueType.values().length];
        for(int i = 0; i < size; i++){
            counts[type(i).ordinal()]++;
        }

        float[] dist = new float[counts.length];
        for(int i = 0; i < counts.length; i++){
            dist[i] = (float)((double)counts[i] / size);
        }
        return dist;
    }
}
