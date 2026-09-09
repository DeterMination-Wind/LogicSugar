package logicsugar.assist.data;

import arc.Core;
import arc.scene.Element;
import arc.scene.ui.layout.Table;
import logicsugar.assist.expr.ArrayRegistry;
import logicsugar.assist.expr.ExprIntrinsics;
import logicsugar.assist.expr.MapIntrinsics;
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
 * 哈希表模块：声明卡 {@code map <name> <memory> <base> <capacity>} + 开放寻址布局。
 *
 * <p>布局（契约 §4）：key 区 {@code [base, base+capacity)}、value 区
 * {@code [base+capacity, base+2*capacity)}。哈希 {@code abs(key) % capacity}，线性探测
 * （整表环形扫描，见 {@link MapIntrinsics}）。卡片只是编译期元数据，lower 阶段整体跳过，
 * 产物只有原版 {@code op/read/write/jump/funccall}。</p>
 *
 * <p><b>空槽标记</b>：{@code op div <t> 0 0} 生成的 NaN 写入 key 槽；判定"槽为空"用
 * {@code op strictEqual <t> <key> <nan>}（当前 BE 运行时里 NaN 读回是 null 对象，
 * strictEqual 对 null 对象相等、对数字与对象不等，见 MapIntrinsics 注释）。
 * 因此哈希表首次使用前必须调用 {@code mapclear(m)}：未初始化的内存槽读回数字 0，
 * 会被当作"已占用且 key = 0"。</p>
 *
 * <p><b>名称校验</b>：与函数、其它 map、以及 {@link ArrayRegistry} 里的数组/矩阵重名都报错；
 * 同一内存块上 map 区间互相重叠报错。跨模块（array/matrix/其它数据结构）的区间重叠不在
 * 本模块职责内（契约 §7 已知限制）。</p>
 */
public class MapModule extends DataModule{
    public static final String ID = "map";
    public static final String TOKEN = "map";

    /** 一个已声明的哈希表：内存块上的两段区间（key 区 + value 区）。 */
    public static final class MapInfo{
        public final String name;
        public final String memory;
        public final int base;
        public final int capacity;

        MapInfo(String name, String memory, int base, int capacity){
            this.name = name;
            this.memory = memory;
            this.base = base;
            this.capacity = capacity;
        }

        /** 占用的内存槽数（2 * capacity）。 */
        public long span(){
            return 2L * capacity;
        }

        /** 第 i 个槽的 key 物理地址。 */
        public int keyAddress(int index){
            return base + index;
        }

        /** 第 i 个槽的 value 物理地址。 */
        public int valueAddress(int index){
            return base + capacity + index;
        }
    }

    /** 编译期上下文栈（与 {@link DataModules#collectAll}/{@code restore} 配对，支持嵌套编译）。 */
    private static final Deque<Map<String, MapInfo>> contexts = new ArrayDeque<>();
    private static boolean paletteRegistered;

    /** 当前上下文（编译期注册表优先，否则回退到编辑器画布）里的哈希表；未声明时为 null。 */
    public static MapInfo active(String name){
        if(name == null) return null;
        if(!contexts.isEmpty()) return contexts.peek().get(name);
        return canvasMaps().get(name);
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

    // ===== DataModule =====

    @Override
    public String id(){
        return ID;
    }

    @Override
    public void registerParsers(){
        LAssembler.customParsers.put(TOKEN, MapModule::parseMap);
        if(!paletteRegistered){
            paletteRegistered = true;
            LogicIO.allStatements.add(MapStatement::new);
        }
    }

    @Override
    public void collect(List<LStatement> statements, Set<String> functionNames){
        Map<String, MapInfo> registry = new LinkedHashMap<>();
        Map<String, List<long[]>> spans = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        ArrayRegistry arrays = ArrayRegistry.active();
        for(int i = 0; i < statements.size(); i++){
            if(!(statements.get(i) instanceof MapStatement card)) continue;
            String error = fieldError(card);
            MapInfo info = null;
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
                throw new IllegalArgumentException("map at statement " + i + " " + error + ".");
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
        Set<String> names = new HashSet<>();
        ArrayRegistry arrays = ArrayRegistry.active();
        for(int i = 0; i < statements.size(); i++){
            if(!(statements.get(i) instanceof MapStatement card)) continue;
            String error = fieldError(card);
            MapInfo info = null;
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
        return MapIntrinsics.INSTANCE;
    }

    @Override
    public List<String> builtinSugar(){
        return MapIntrinsics.builtinSugar();
    }

    // ===== 声明卡 =====

    /**
     * 哈希表声明卡：{@code map <name> <memory> <base> <capacity>}。
     * 纯编译期元数据（lower 阶段跳过，不产指令）；固定 4 个参数 token，空槽写 {@code ~}。
     */
    public static class MapStatement extends DataDeclaration{
        /** 表达式中使用的哈希表名。 */
        public String map = "map";
        /** 承载数据的内存块变量名（如 cell1）。 */
        public String memory = "cell1";
        /** key 区起始物理地址（非负整数字面量）。 */
        public String base = "0";
        /** 槽位数（≥1 整数字面量）；占用 [base, base+2*capacity)。 */
        public String capacity = "8";

        @Override
        public String token(){
            return TOKEN;
        }

        @Override
        public String name(){
            return cardLabel("map.card", "Map");
        }

        @Override
        public void write(StringBuilder out){
            // 固定 token 数（空槽位 "~" 占位）：LParser 复用静态 token 数组，缺尾 token 无法与残值区分
            out.append(TOKEN).append(' ').append(optional(map)).append(' ').append(optional(memory)).append(' ')
                .append(optional(base)).append(' ').append(optional(capacity));
        }

        @Override
        public void build(Table table){
            table.add(cardLabel("map.card", "Map")).self(c -> hint(c, "map.name"));
            field(table, map, value -> map = value).width(70f);
            table.add(cardLabel("map.memory", "mem")).self(c -> hint(c, "map.memory"));
            field(table, memory, value -> memory = value).width(70f);
            table.add(cardLabel("map.base", "base")).self(c -> hint(c, "map.base"));
            field(table, base, value -> base = value).width(45f);
            table.add(cardLabel("map.capacity", "capacity")).self(c -> hint(c, "map.capacity"));
            field(table, capacity, value -> capacity = value).width(45f);
        }
    }

    /** {@code map} 卡的解析器（token 固定 4 参；{@code ~} = 空）。 */
    public static LStatement parseMap(String[] tokens){
        MapStatement result = new MapStatement();
        result.map = tokenValue(tokens, 1);
        if(result.map.isEmpty()){
            throw new IllegalArgumentException("Invalid map statement: missing map name");
        }
        result.memory = tokenValue(tokens, 2);
        if(result.memory.isEmpty()){
            throw new IllegalArgumentException("Invalid map statement: missing memory cell");
        }
        result.base = tokenValue(tokens, 3);
        result.capacity = tokenValue(tokens, 4);
        return result;
    }

    // ===== 校验 =====

    /** 字段级校验（名字/内存/字面量/容量）；返回错误描述，合法时返回 null。 */
    private static String fieldError(MapStatement card){
        String name = trim(card.map);
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
        long end = base + 2L * capacity;
        if(end > Integer.MAX_VALUE) return "base + 2*capacity is too large (" + end + ")";
        int limit = ArrayRegistry.memoryCapacity(memory);
        if(limit > 0 && end > limit){
            return "needs addresses up to " + (end - 1) + ", but memory '" + memory + "' only has " + limit + " slots";
        }
        return null;
    }

    /** 字段全部合法时的元数据；否则 null（{@link #fieldError} 已给出原因）。 */
    private static MapInfo infoOf(MapStatement card){
        String name = trim(card.map);
        String memory = trim(card.memory);
        Long base = parseIntLiteral(card.base);
        Long capacity = parseIntLiteral(card.capacity);
        if(name.isEmpty() || !isIdentifier(name) || name.startsWith("__ls_")) return null;
        if(memory.isEmpty() || base == null || capacity == null) return null;
        if(base < 0 || base > Integer.MAX_VALUE || capacity < 1 || capacity > Integer.MAX_VALUE) return null;
        long end = base + 2L * capacity;
        if(end > Integer.MAX_VALUE) return null;
        int limit = ArrayRegistry.memoryCapacity(memory);
        if(limit > 0 && end > limit) return null;
        return new MapInfo(name, memory, (int)(long)base, (int)(long)capacity);
    }

    /** 同一内存块上的 map 区间重叠检查（跨模块重叠不在此列）。 */
    private static String spanError(Map<String, List<long[]>> spans, MapInfo info){
        List<long[]> existing = spans.get(info.memory);
        if(existing == null) return null;
        long start = info.base;
        long end = info.base + info.span();
        for(long[] span : existing){
            if(start < span[1] && span[0] < end){
                return "range [" + start + ", " + end + ") overlaps another map on '" + info.memory + "'";
            }
        }
        return null;
    }

    private static void addSpan(Map<String, List<long[]>> spans, MapInfo info){
        spans.computeIfAbsent(info.memory, k -> new ArrayList<>())
            .add(new long[]{info.base, info.base + info.span()});
    }

    // ===== 编辑器画布回退（宽松口径） =====

    /** 收集当前 Sugar 画布上的 map 声明卡（跳过不合法的卡片），画布不可用时为空表。 */
    private static Map<String, MapInfo> canvasMaps(){
        try{
            SugarCanvas canvas = SugarCanvas.current();
            if(canvas == null || canvas.statements == null) return Collections.emptyMap();
            Map<String, MapInfo> result = new LinkedHashMap<>();
            for(Element child : canvas.statements.getChildren()){
                if(!(child instanceof LCanvas.StatementElem elem)) continue;
                if(!(elem.st instanceof MapStatement card)) continue;
                String name = trim(card.map);
                if(result.containsKey(name)) continue;
                MapInfo info = infoOf(card);
                if(info != null) result.put(info.name, info);
            }
            return result;
        }catch(Throwable t){
            // 无头自测环境（Vars.ui 未初始化等）：视同没有编辑器上下文
            return Collections.emptyMap();
        }
    }

    // ===== 工具 =====

    private static String cardLabel(String key, String fallback){
        try{
            if(SugarStatements.cardsLocalized() && Core.bundle != null){
                return Core.bundle.get("logicsugar." + key, fallback);
            }
        }catch(Throwable ignored){
            // 无头环境（Core.settings/bundle 未初始化）：退回英文 fallback
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

    /** 解析纯十进制整数字面量（允许前导 '-'），失败（含溢出）返回 null。 */
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
