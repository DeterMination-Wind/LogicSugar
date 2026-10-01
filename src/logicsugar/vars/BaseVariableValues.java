package logicsugar.vars;

import arc.Core;
import mindustry.Vars;
import mindustry.ctype.Content;
import mindustry.ctype.MappableContent;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Unit;

import static logicsugar.vars.VarsOptions.COLOR_LIMIT;

/**
 * 变量视图的公共部分：时间戳/时间文本、建筑描述、数值与对象的格式化文本，以及
 * 「一行是什么类型」的判定。上游把颜色上限放在 {@code Constants}，这里改读
 * {@link VarsOptions#COLOR_LIMIT}（同一常量）。
 *
 * <p>Ported from upstream MlogAssertions v0.11.1 ({@code cardillan.mlogassertions.data.BaseVariableValues}),
 * 去掉对上游 UI（{@code VarsDialog}）的依赖，其余行为逐字保留。</p>
 *
 * <p>两个不是线格式的显示占位符（{@code [content]} / {@code [object]}）改成读
 * LogicSugar 的 bundle；{@code "null"} 保持原样，因为它是 mlog 字面量，
 * {@link MemoryText} 导入时要靠它还原「非有限数」与「空对象」。</p>
 */
public abstract class BaseVariableValues implements VariableValues{
    public final long timestamp = (long)(Vars.state.tick / 60.0 * 1000.0);
    public final Building build;

    public BaseVariableValues(Building build){
        this.build = build;
    }

    @Override
    public long timestamp(){
        return timestamp;
    }

    @Override
    public Building building(){
        return build;
    }

    @Override
    public String buildingDesc(){
        return build.block.localizedName;
    }

    private String buildingPos;
    @Override
    public String buildingPos(){
        if(buildingPos == null){
            buildingPos = String.format("%.0f,\u00a0%.0f", build.x() / Vars.tilesize, build.y() / Vars.tilesize);
        }
        return buildingPos;
    }

    private String buildingDescMulti;
    @Override
    public String buildingDescMulti(){
        if(buildingDescMulti == null){
            buildingDescMulti = String.format("%s\n[gray](%.0f,\u00a0%.0f)", build.block.localizedName, build.x() / Vars.tilesize, build.y() / Vars.tilesize);
        }
        return buildingDescMulti;
    }

    private String time;
    @Override
    public String time(){
        if(time == null){
            if(timestamp > 86_400_000){
                int days = (int)(timestamp / 86_400_000);
                long millis = timestamp % 86_400_000;
                time = String.format("%dd %d:%02d:%02d.%03d", days, millis / 3_600_000, millis / 60_000 % 60, millis / 1000 % 60, millis % 1000);
            }else{
                time = String.format("%d:%02d:%02d.%03d", timestamp / 3_600_000, timestamp / 60_000 % 60, timestamp / 1000 % 60, timestamp % 1000);
            }
        }
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
