package logicsugar.assist.data;

import arc.Core;
import arc.scene.Element;
import arc.scene.ui.layout.Table;
import logicsugar.assist.expr.ArrayRegistry;
import logicsugar.assist.expr.ExprIntrinsics;
import logicsugar.assist.expr.SetIntrinsics;
import mindustry.gen.LogicIO;
import mindustry.logic.LAssembler;
import mindustry.logic.LCanvas;
import mindustry.logic.LStatement;
import mindustry.logic.SugarCanvas;
import mindustry.logic.SugarStatements;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 无序集合模块：声明卡 {@code uset <name> <memory> <base> <capacity>}（token 不能是
 * {@code set}，那是原版 opcode）。
 *
 * <p>布局：只占用 key 区 {@code [base, base+capacity)}，开放寻址与 {@link MapModule}
 * 相同（{@code hash = abs(key) % capacity}，整表线性探测，NaN 墓碑）。卡片只是编译期
 * 元数据，lower 阶段整体跳过，产物只有原版 {@code op/read/write/jump/funccall}。</p>
 *
 * <p><b>空槽标记</b>：与哈希表相同，首次使用前必须调用 {@code uclear(s)}。判定空槽用
 * {@code op strictEqual}（见 {@link SetIntrinsics}）。</p>
 *
 * <p><b>名称校验</b>：与函数、其它 uset、以及 {@link ArrayRegistry} 里的数组/矩阵重名都报错；
 * 同一内存块上 uset 区间互相重叠报错。跨模块区间重叠不在本模块职责内。</p>
 */
public class SetModule extends DataModule{
    public static final String ID = "uset";
    public static final String TOKEN = "uset";

    /** 一个已声明的无序集合：内存块上的 key 区 [base, base+capacity)。 */
    public static final class SetInfo{
        public final String name;
        public final String memory;
        public final int base;
        public final int capacity;

        SetInfo(String name, String memory, int base, int capacity){
            this.name = name;
            this.memory = memory;
            this.base = base;
            this.capacity = capacity;
        }

        /** 占用的内存槽数（capacity）。 */
        public long span(){
            return capacity;
        }
    }

    /** 编译期上下文栈（与 {@link DataModules#collectAll}/{@code restore} 配对，支持嵌套编译）。 */
    private static final Deque<Map<String, SetInfo>> contexts = new ArrayDeque<>();
    private static boolean paletteRegistered;

    /** 当前上下文（编译期注册表优先，否则回退到编辑器画布）里的集合；未声明时为 null。 */
    public static SetInfo active(String name){
        if(name == null) return null;
        if(!contexts.isEmpty()) return contexts.peek().get(name);
        return canvasSets().get(name);
    }

    /** 当前是否处于编译期上下文（仅供测试/诊断）。 */
    public static boolean isCollecting(){
        return !contexts.isEmpty();
    }

    /** 是否存在可用的声明上下文（编译期注册表或编辑器画布）；用于区分"没有上下文"与"未声明"。 */
    public static boolean hasContext(){
        if(!contexts.isEmpty()) return true;
        try{
            SugarCanvas canvas = SugarCanvas.current();
            return canvas != null && canvas.statements != null;
        }catch(Throwable t){
            return false;
        }
    }

    @Override
    public String id(){
        return ID;
    }

    @Override
    public void registerParsers(){
        LAssembler.customParsers.put(TOKEN, SetModule::parseSet);
        if(!paletteRegistered){
            paletteRegistered = true;
            LogicIO.allStatements.add(USetStatement::new);
        }
    }

    @Override
    public void collect(List<LStatement> statements, Set<String> functionNames){
        Map<String, SetInfo> registry = new LinkedHashMap<>();
        Map<String, List<long[]>> spans = new LinkedHashMap<>();
        java.util.Set<String> names = new HashSet<>();
        ArrayRegistry arrays = ArrayRegistry.active();
        for(int i = 0; i < statements.size(); i++){
            if(!(statements.get(i) instanceof USetStatement card)) continue;
            String error = fieldError(card);
            SetInfo info = null;
            if(error == null){
                info = infoOf(card);
                if(info == null){
                    error = "invalid fields";
                }else if(names.contains(info.name)){
                    error = "duplicate name '" + info.name + "'";
                }else if(functionNames != null && functionNames.contains(info.name)){
                    error = "name '" + info.name + "' conflicts with a function of the same name";
                }else if(arrays != null && (arrays.get(info.name) != null || arrays.getMatrix(info.name) != null)){
                    error = "name '" + info.name + "' conflicts with an array or matrix of the same name";
                }else{
                    error = spanError(spans, info);
                }
            }
            if(error != null){
                throw new IllegalArgumentException("uset at statement " + i + " " + error + ".");
            }
            names.add(info.name);
            registry.put(info.name, info);
            addSpan(spans, info);
        }
        contexts.push(registry);
    }

    @Override
    public void restore(){
        if(!contexts.isEmpty()) contexts.pop();
    }

    @Override
    public void markInvalid(List<LStatement> statements, boolean[] invalid, Set<String> functionNames){
        Map<String, List<long[]>> spans = new LinkedHashMap<>();
        java.util.Set<String> names = new HashSet<>();
        ArrayRegistry arrays = ArrayRegistry.active();
        for(int i = 0; i < statements.size(); i++){
            if(!(statements.get(i) instanceof USetStatement card)) continue;
            String error = fieldError(card);
            SetInfo info = null;
            if(error == null){
                info = infoOf(card);
                if(info == null){
                    error = "invalid fields";
                }else if(names.contains(info.name)){
                    error = "duplicate name '" + info.name + "'";
                }else if(functionNames != null && functionNames.contains(info.name)){
                    error = "name '" + info.name + "' conflicts with a function of the same name";
                }else if(arrays != null && (arrays.get(info.name) != null || arrays.getMatrix(info.name) != null)){
                    error = "name '" + info.name + "' conflicts with an array or matrix of the same name";
                }else{
                    error = spanError(spans, info);
                }
            }
            if(error != null){
                invalid[i] = true;
            }else{
                names.add(info.name);
                addSpan(spans, info);
            }
        }
    }

    @Override
    public ExprIntrinsics.Provider intrinsics(){
        return SetIntrinsics.INSTANCE;
    }

    @Override
    public List<String> builtinSugar(){
        return SetIntrinsics.builtinSugar();
    }

    /**
     * 无序集合声明卡：{@code uset <name> <memory> <base> <capacity>}。
     * 纯编译期元数据（lower 阶段跳过，不产指令）；固定 4 个参数 token，空槽写 {@code ~}。
     */
    public static class USetStatement extends DataDeclaration{
        /** 表达式中使用的集合名。 */
        public String uset = "s";
        /** 承载数据的内存块变量名（如 cell1）。 */
        public String memory = "cell1";
        /** key 区起始物理地址（非负整数字面量）。 */
        public String base = "0";
        /** 槽位数（≥1 整数字面量）；占用 [base, base+capacity)。 */
        public String capacity = "8";

        @Override
        public String token(){
            return TOKEN;
        }

        @Override
        public String name(){
            return cardLabel("uset.card", "Set");
        }

        @Override
        public void write(StringBuilder out){
            out.append(TOKEN).append(' ').append(optional(uset)).append(' ').append(optional(memory)).append(' ')
                .append(optional(base)).append(' ').append(optional(capacity));
        }

        @Override
        public void build(Table table){
            table.add(cardLabel("uset.card", "Set")).self(c -> hint(c, "uset.name"));
            field(table, uset, value -> uset = value).width(70f);
            table.add(cardLabel("map.memory", "mem")).self(c -> hint(c, "uset.memory"));
            field(table, memory, value -> memory = value).width(70f);
            table.add(cardLabel("map.base", "base")).self(c -> hint(c, "uset.base"));
            field(table, base, value -> base = value).width(45f);
            table.add(cardLabel("map.capacity", "capacity")).self(c -> hint(c, "uset.capacity"));
            field(table, capacity, value -> capacity = value).width(45f);
        }
    }

    /** {@code uset} 卡的解析器（token 固定 4 参；{@code ~} = 空）。 */
    public static LStatement parseSet(String[] tokens){
        USetStatement result = new USetStatement();
        result.uset = tokenValue(tokens, 1);
        if(result.uset.isEmpty()){
            throw new IllegalArgumentException("Invalid uset statement: missing set name");
        }
        result.memory = tokenValue(tokens, 2);
        if(result.memory.isEmpty()){
            throw new IllegalArgumentException("Invalid uset statement: missing memory cell");
        }
        result.base = tokenValue(tokens, 3);
        result.capacity = tokenValue(tokens, 4);
        return result;
    }

    private static String fieldError(USetStatement card){
        String name = trim(card.uset);
        if(name.isEmpty()) return "name must not be empty";
        if(!isIdentifier(name)) return "name '" + name + "' must match [A-Za-z_][A-Za-z0-9_]*";
        if(name.startsWith("__ls_")) return "name '" + name + "' uses the reserved '__ls_' prefix";
        String memory = trim(card.memory);
        if(memory.isEmpty()) return "needs a memory cell variable";
        Long base = parseIntLiteral(card.base);
        if(base == null || base < 0 || base > Integer.MAX_VALUE){
            return "base must be a non-negative integer literal (variables are not supported yet), got '" + card.base + "'";
        }
        Long capacity = parseIntLiteral(card.capacity);
        if(capacity == null || capacity < 1 || capacity > Integer.MAX_VALUE){
            return "capacity must be an integer literal of at least 1 (variables are not supported yet), got '" + card.capacity + "'";
        }
        long end = base + capacity;
        if(end > Integer.MAX_VALUE) return "base + capacity is too large (" + end + ")";
        int limit = ArrayRegistry.memoryCapacity(memory);
        if(limit > 0 && end > limit){
            return "needs addresses up to " + (end - 1) + ", but memory '" + memory + "' only has " + limit + " slots";
        }
        return null;
    }

    private static SetInfo infoOf(USetStatement card){
        String name = trim(card.uset);
        String memory = trim(card.memory);
        Long base = parseIntLiteral(card.base);
        Long capacity = parseIntLiteral(card.capacity);
        if(name.isEmpty() || !isIdentifier(name) || name.startsWith("__ls_")) return null;
        if(memory.isEmpty() || base == null || capacity == null) return null;
        if(base < 0 || base > Integer.MAX_VALUE || capacity < 1 || capacity > Integer.MAX_VALUE) return null;
        long end = base + capacity;
        if(end > Integer.MAX_VALUE) return null;
        int limit = ArrayRegistry.memoryCapacity(memory);
        if(limit > 0 && end > limit) return null;
        return new SetInfo(name, memory, (int)(long)base, (int)(long)capacity);
    }

    private static String spanError(Map<String, List<long[]>> spans, SetInfo info){
        List<long[]> existing = spans.get(info.memory);
        if(existing == null) return null;
        long start = info.base;
        long end = info.base + info.span();
        for(long[] span : existing){
            if(start < span[1] && span[0] < end){
                return "range [" + start + ", " + end + ") overlaps another uset on '" + info.memory + "'";
            }
        }
        return null;
    }

    private static void addSpan(Map<String, List<long[]>> spans, SetInfo info){
        spans.computeIfAbsent(info.memory, k -> new ArrayList<>())
            .add(new long[]{info.base, info.base + info.span()});
    }

    private static Map<String, SetInfo> canvasSets(){
        try{
            SugarCanvas canvas = SugarCanvas.current();
            if(canvas == null || canvas.statements == null) return Collections.emptyMap();
            Map<String, SetInfo> result = new LinkedHashMap<>();
            for(Element child : canvas.statements.getChildren()){
                if(!(child instanceof LCanvas.StatementElem elem)) continue;
                if(!(elem.st instanceof USetStatement card)) continue;
                String name = trim(card.uset);
                if(result.containsKey(name)) continue;
                SetInfo info = infoOf(card);
                if(info != null) result.put(info.name, info);
            }
            return result;
        }catch(Throwable t){
            return Collections.emptyMap();
        }
    }

    private static String cardLabel(String key, String fallback){
        try{
            if(SugarStatements.cardsLocalized() && Core.bundle != null){
                return Core.bundle.get("logicsugar." + key, fallback);
            }
        }catch(Throwable ignored){
        }
        return fallback;
    }

    private static String tokenValue(String[] tokens, int index){
        if(tokens == null || index >= tokens.length) return "";
        String value = tokens[index];
        if(value == null || value.equals("~")) return "";
        return value.trim();
    }

    private static String optional(String value){
        return value == null || value.isEmpty() ? "~" : value;
    }

    private static String trim(String value){
        return value == null ? "" : value.trim();
    }

    private static boolean isIdentifier(String name){
        if(name.isEmpty()) return false;
        char first = name.charAt(0);
        if(!(Character.isLetter(first) || first == '_')) return false;
        for(int i = 1; i < name.length(); i++){
            char c = name.charAt(i);
            if(!(Character.isLetterOrDigit(c) || c == '_')) return false;
        }
        return true;
    }

    private static Long parseIntLiteral(String token){
        if(token == null) return null;
        String t = token.trim();
        if(t.isEmpty()) return null;
        String digits = t.startsWith("-") ? t.substring(1) : t;
        if(digits.isEmpty()) return null;
        for(int i = 0; i < digits.length(); i++){
            char c = digits.charAt(i);
            if(c < '0' || c > '9') return null;
        }
        try{
            return Long.parseLong(t);
        }catch(NumberFormatException e){
            return null;
        }
    }
}
