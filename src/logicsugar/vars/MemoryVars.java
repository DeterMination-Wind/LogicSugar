package logicsugar.vars;

import arc.func.Cons;
import arc.util.Log;
import mindustry.world.blocks.logic.MemoryBlock.MemoryBuild;

import java.lang.reflect.Field;
import java.util.Arrays;

/**
 * 一块内存单元（{@code cellN}/{@code bankN}）的变量视图：每个槽位要么持一个数
 * （{@code numberMemory}），要么持一个对象（{@code objectMemory}，用盘外的 sentinel
 * 表示「这是数值槽」），与 mlog 的 {@code read}/{@code write} 语义一致。
 *
 * <p>Ported from upstream MlogAssertions v0.11.1 ({@code cardillan.mlogassertions.data.MemoryVars}).
 * 两个数组与 sentinel 是 v160 {@code MemoryBuild} 的私有字段，只能反射读取。</p>
 *
 * <p><b>老 fork 兼容</b>：只有 v160 才有对象内存（{@code objectMemory}/{@code numberMemory}）；
 * MindustryX 等基于更早内核的 fork 仍是单个 {@code public double[] memory}。探测不到 v160 字段时
 * 退到那个数组：{@code numberMemory} 直接指向它（因此 live 视图的写入/清空真的落到方块上），
 * 对象侧用一张全 sentinel 的假数组补齐，于是每个槽位都是数值槽（与老内核的语义一致）。
 * 两个模型都探测不到时视图退化为 0 行而不是报错，这是上游的既定行为。
 * {@link #init()} 必须在游戏启动后调用一次（由集成阶段调用）。</p>
 *
 * <p>跨加载器注意：这里的反射访问的是游戏加载器里的 {@code MemoryBuild}，mod 类加载器
 * 看不到它的包内成员，因此必须走 {@code setAccessible(true)}，且只能放在方法里
 * （不能放进静态初始化块，见 AGENTS.md 的跨加载器规则）。</p>
 */
public class MemoryVars extends BaseVariableValues{
    // Share cell labels across all instances
    public static String[] decLabels = new String[0];
    public static String[] hexLabels = new String[0];

    // Memory block private fields
    private static Object sentinel;
    private static Field objectField;
    private static Field numberField;
    /** v160 之前的内存模型（单一 {@code double[] memory}）；非 null 时表示跑在老 fork 上。 */
    private static Field legacyField;

    public final Object[] objectMemory;
    public final double[] numberMemory;
    public final int length;

    public static void init(){
        // 老 fork 没有 sentinel 字段：用本类自己的哨兵，配合下面那张全哨兵的假对象数组。
        sentinel = new Object();
        try{
            Field sentinelField = MemoryBuild.class.getDeclaredField("sentinel");
            sentinelField.setAccessible(true);
            sentinel = sentinelField.get(null);
            objectField = MemoryBuild.class.getDeclaredField("objectMemory");
            objectField.setAccessible(true);
            numberField = MemoryBuild.class.getDeclaredField("numberMemory");
            numberField.setAccessible(true);
            return;
        }catch(ReflectiveOperationException ignored){
            // v160 之前的模型，继续往下探
        }

        try{
            legacyField = MemoryBuild.class.getDeclaredField("memory");
            legacyField.setAccessible(true);
        }catch(ReflectiveOperationException e){
            Log.warn("LogicSugar: cannot access the memory block data fields, the memory view stays empty: @", e);
        }
    }

    public MemoryVars(MemoryBuild build){
        this(build, true);
    }

    protected MemoryVars(MemoryBuild build, boolean live){
        super(build);

        Object[] objectMemory;
        double[] numberMemory;
        if(legacyField != null){
            // 见类注释：老内存模型只有一个 double[]，numberMemory 指向它本人
            numberMemory = get(build, legacyField, new double[0]);
            objectMemory = new Object[numberMemory.length];
            Arrays.fill(objectMemory, sentinel);
        }else{
            objectMemory = get(build, objectField, new Object[0]);
            numberMemory = get(build, numberField, new double[0]);
        }

        length = Math.min(objectMemory.length, numberMemory.length);
        this.objectMemory = live ? objectMemory : Arrays.copyOf(objectMemory, length);
        this.numberMemory = live ? numberMemory : Arrays.copyOf(numberMemory, length);

        if(length > decLabels.length){
            decLabels = new String[length];
            hexLabels = new String[length];
            for(int i = 0; i < length; i++){
                decLabels[i] = " " + i + " ";
                hexLabels[i] = " " + Integer.toHexString(i).toUpperCase() + " ";
            }
        }
    }

    private static <T> T get(Object instance, Field field, T defaultValue){
        if(field == null || sentinel == null) return defaultValue;

        try{
            //noinspection unchecked
            return (T)field.get(instance);
        }catch(IllegalAccessException e){
            Log.err("LogicSugar: failed to access MemoryBuild data fields", e);
            return defaultValue;
        }
    }

    @Override
    public BlockDataType dataType(){
        return BlockDataType.memory;
    }

    @Override
    public int size(){
        return length;
    }

    @Override
    public String label(int index, boolean hex){
        return hex ? hexLabels[index] : decLabels[index];
    }

    @Override
    public boolean isObj(int index){
        return objectMemory[index] != sentinel;
    }

    @Override
    public boolean isLink(int index){
        return false;
    }

    @Override
    public Object obj(int index){
        return objectMemory[index];
    }

    @Override
    public double num(int index){
        return numberMemory[index];
    }

    @Override
    public String textBuffer(){
        return "";
    }

    @Override
    public void clear(){
        if(length > 0){
            Arrays.fill(objectMemory, sentinel);
            Arrays.fill(numberMemory, 0);
        }
    }

    @Override
    public void set(int index, double value){
        if(index < 0 || index >= length || sentinel == null) return;

        objectMemory[index] = sentinel;
        numberMemory[index] = value;
    }

    @Override
    public void set(int index, Object value){
        if(index < 0 || index >= length || sentinel == null) return;

        objectMemory[index] = value;
    }

    @Override
    public void setView(boolean sorted, boolean filtered, boolean hideLinks){
        // 内存槽位顺序固定，没有可排序/过滤的内容
    }

    @Override
    public void eachObject(Cons<Object> getter){
        for(int index = 0; index < size(); index++){
            if(objectMemory[index] != sentinel) getter.get(objectMemory[index]);
        }
    }
}
