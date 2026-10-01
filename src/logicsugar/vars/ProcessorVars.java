package logicsugar.vars;

import arc.Core;
import arc.func.Cons;
import arc.func.Prov;
import mindustry.logic.LExecutor;
import mindustry.logic.LVar;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;

import java.util.Arrays;
import java.util.Comparator;

/**
 * 一块处理器的变量视图：最前面几行是 LogicSugar 自己合成的 {@code Text buffer}、
 * {@code Time waited}、{@code @counter}、{@code @unit}、{@code @ipt}（特权处理器还有
 * {@code @queryResult}），后面是程序的普通变量（{@code executor.vars[1..]}，跳过
 * {@code vars[0]}）。
 *
 * <p>Ported from upstream MlogAssertions v0.11.1 ({@code cardillan.mlogassertions.data.ProcessorVars}),
 * 去掉对上游 UI 的依赖。两处偏差：合成行的名字（会显示在表里）改读 LogicSugar 的 bundle；
 * 排序用的 {@code mindcodeOrder} 保持原样。隐藏临时变量（{@code *tmp*}）与链接变量的
 * 过滤开关来自 {@link VarsOptions}，由 {@link #setView} 接收。</p>
 */
public class ProcessorVars extends BaseVariableValues{
    public final LExecutor executor;
    public final LVar[] data;
    public final LVar[] view;
    public final int start;
    public int length;

    LVar id;

    public ProcessorVars(LogicBuild build){
        super(build);
        this.executor = build.executor;
        this.data = new LVar[executor.vars.length + 4 + (executor.privileged ? 1 : 0)];
        length = 0;

        // 合成行的名字就是表里的行标签，所以走 bundle；快照与活视图在同一会话里取到
        // 同一份文本，ProcessorSnapshot.writeTo 的名字比对不受影响。
        store(objvar(Core.bundle.get("logicsugar.vars.var.textbuffer", "Text buffer"), () -> cachedTextBuffer()));
        store(numvar(Core.bundle.get("logicsugar.vars.var.timewaited", "Time waited"), () -> (double)timeWaited()));
        store(executor.counter);
        store(executor.unit);
        store(executor.ipt);
        if(executor.privileged){
            store(executor.queryResult);
        }

        start = length;

        for(int i = 1; i < executor.vars.length; i++){
            store(executor.vars[i]);
            if(executor.vars[i].name.equals("*id")) id = executor.vars[i];
        }

        view = Arrays.copyOf(data, length);
    }

    protected void store(LVar var){
        if(var != null) data[length++] = var;
    }

    static char[] bufferM = new char[150];
    static char[] buffer = new char[150];
    String rawId, formattedId, formattedIdMulti;

    /** 把 {@code *id} 变量的文本压成单行（长名字在中间用空格回退、换行变成 '/'），供表头显示。 */
    private void updateDesc(String text){
        boolean copying = false;
        int beg = 0, l = 0;
        int stop = Math.min(text.length(), bufferM.length);
        for(int i = 0; i < stop; i++){
            char ch = text.charAt(i);
            bufferM[l] = ch;
            buffer[l] = ch;
            l++;

            if(ch == ' ' && !copying){
                l = beg;
                copying = true;
            }else if(ch == '\n'){
                buffer[l - 1] = '/';
                copying = false;
                beg = l;
            }
        }

        rawId = text;
        formattedId = new String(buffer, 0, l);
        formattedIdMulti = new String(bufferM, 0, l) + " [gray](" + buildingPos() + ")";
    }

    @Override
    public BlockDataType dataType(){
        return BlockDataType.processor;
    }

    @Override
    public String buildingDesc(){
        if(id == null || !(id.obj() instanceof String text)) return super.buildingDesc();
        if(rawId != text) updateDesc(text);
        return formattedId;
    }

    @Override
    public String buildingDescMulti(){
        if(id == null || !(id.obj() instanceof String text)) return super.buildingDescMulti();

        if(rawId != text) updateDesc(text);
        return formattedIdMulti;
    }

    @Override
    public int size(){
        return length;
    }

    @Override
    public String label(int index, boolean hex){
        return " " + view[index].name + " ";
    }

    @Override
    public boolean isObj(int index){
        return view[index].isobj;
    }

    @Override
    public boolean isLink(int index){
        return view[index].constant && view[index].name.charAt(0) != '@';
    }

    @Override
    public Object obj(int index){
        return view[index].obj();
    }

    @Override
    public double num(int index){
        return view[index].num();
    }

    String lastTextBuffer;
    private String cachedTextBuffer(){
        String str = executor.textBuffer.toString();
        return str.equals(lastTextBuffer) ? lastTextBuffer : (lastTextBuffer = str);
    }

    @Override
    public String textBuffer(){
        return executor.textBuffer.toString();
    }

    protected float timeWaited(){
        int counter = (int)executor.counter.numval;
        return counter >= 0 && counter < executor.instructions.length && executor.instructions[counter] instanceof LExecutor.WaitI w ? w.curTime : 0;
    }

    @Override
    public void clear(){
        // 处理器变量由程序自己写，这里不能清
    }

    @Override
    public void setView(boolean sorted, boolean filtered, boolean hideLinks){
        length = 0;
        for(int i = 0; i < data.length; i++){
            if(data[i] == null) continue;
            if(filtered && isTemp(data[i].name)) continue;
            if(hideLinks && data[i].constant && data[i].name.charAt(0) != '@') continue;
            view[length++] = data[i];
        }

        if(sorted){
            Arrays.sort(view, start, length, mindcodeOrder);
        }
    }

    private enum VariableClass{
        builtin, uncategorized, global, main, local, compiler, temporary;
    }

    private static VariableClass variableClass(String name){
        if(name.isEmpty()) return VariableClass.uncategorized;
        return switch(name.charAt(0)){
            case '@' -> VariableClass.builtin;
            case '.' -> VariableClass.global;
            case ':' -> name.indexOf(':', 1) < 0 ? VariableClass.main : VariableClass.local;
            case '*' -> isTemp(name) ? VariableClass.temporary : VariableClass.compiler;
            case '_' -> VariableClass.temporary;
            default -> VariableClass.uncategorized;
        };
    }

    private static boolean isTemp(String name){
        return name.startsWith("*tmp");
    }

    /** Mindcode/Mindustry 变量名的自然序：先按类别，再按数字段数值、前导零个数、其余字符。 */
    private static final Comparator<LVar> mindcodeOrder = (la, lb) -> {
        String a = la.name, b = lb.name;

        if(a.isEmpty() || b.isEmpty()) return Integer.compare(a.length(), b.length());

        VariableClass va = variableClass(a), vb = variableClass(b);
        if(va != vb) return va.compareTo(vb);

        int ia = 0;
        int ib = 0;

        while(ia < a.length() && ib < b.length()){
            char ca = a.charAt(ia);
            char cb = b.charAt(ib);

            if(Character.isDigit(ca) && Character.isDigit(cb)){
                while(ia < a.length() && a.charAt(ia) == '0') ia++;
                while(ib < b.length() && b.charAt(ib) == '0') ib++;

                int sa = ia, na = ia;
                int sb = ib, nb = ib;

                while(ia < a.length() && Character.isDigit(a.charAt(ia))) ia++;
                while(ib < b.length() && Character.isDigit(b.charAt(ib))) ib++;

                if(ia - na != ib - nb){
                    return Integer.compare(ia - na, ib - nb);
                }

                while(na < ia){
                    int comparison = Character.compare(a.charAt(na), b.charAt(nb));
                    if(comparison != 0){
                        return comparison;
                    }
                    na++;
                    nb++;
                }

                if(sa - na != sb - nb){
                    return Integer.compare(sa - na, sb - nb);
                }
            }else{
                int comparison = Character.compare(ca, cb);
                if(comparison != 0){
                    return comparison;
                }
                ia++;
                ib++;
            }
        }

        return Integer.compare(a.length(), b.length());
    };

    @Override
    public void eachObject(Cons<Object> getter){
        for(int index = 0; index < data.length; index++){
            if(data[index].isobj) getter.get(data[index].obj());
        }
    }

    private LVar objvar(String name, Prov<Object> prov){
        LVar result = new LVar(name){
            {
                isobj = true;
            }
            @Override
            public Object obj(){
                return objval = prov.get();
            }
        };
        result.obj();
        return result;
    }

    private LVar numvar(String name, Prov<Double> prov){
        LVar result = new LVar(name){
            {
                isobj = false;
            }

            @Override
            public double num(){
                return numval = prov.get();
            }
        };
        result.num();
        return result;
    }
}
