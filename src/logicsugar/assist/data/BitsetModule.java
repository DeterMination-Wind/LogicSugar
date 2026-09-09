package logicsugar.assist.data;

import arc.Core;
import arc.scene.Element;
import arc.scene.ui.layout.Table;
import logicsugar.assist.expr.ArrayRegistry;
import logicsugar.assist.expr.BitsetIntrinsics;
import logicsugar.assist.expr.ExprIntrinsics;
import mindustry.gen.LogicIO;
import mindustry.logic.LAssembler;
import mindustry.logic.LCanvas;
import mindustry.logic.LStatement;
import mindustry.logic.SugarCanvas;
import mindustry.logic.SugarStatements;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 位集模块（契约 §1「位集」）：声明卡 {@code bitset <name> <memory> <base> <words>} +
 * 表达式内建 {@code bset}/{@code bclr}/{@code btest}/{@code bcount}。
 *
 * <p>一个位集把内存块上 {@code words} 个连续槽位当成 {@code words × 64} 位的位向量：
 * 物理区间 {@code [base, base+words)}，逻辑位下标 {@code i} 落在 word {@code i/64} 的
 * 第 {@code i%64} 位（每 word 64 位）。声明卡只是编译期元数据（{@link DataDeclaration}
 * 在 lower 阶段整体跳过，不产出任何 mlog 行），展开见 {@link BitsetIntrinsics}：位运算
 * 全部降级为原版 {@code op}/{@code read}/{@code write}，{@code bcount} 调用注入函数
 * {@code __ls_builtin_bitcount}（normal 模式全程序共享一份）。</p>
 *
 * <p>本模块的编译期注册表在 {@link #collect} 中整表重建（严格校验，发现问题抛
 * {@link IllegalArgumentException} 中止编译），{@link #restore} 清理；编辑器路径
 * （{@link #find} 的注册表缺失回退）从当前 Sugar 画布收集声明卡，跳过不合法的卡片，
 * 字段级标红由 {@link #markInvalid} 负责。跨模块约束：只校验位集自身声明的区间与名字，
 * 与 {@code array}/{@code matrix} 的区间重叠不做校验（已知限制，见模块报告）。</p>
 */
public class BitsetModule extends DataModule{
    public static final String ID = "bitset";

    /** 编译期注册表栈：{@link #collect} 压入、{@link #restore} 弹出（与 DataModules 配对）。 */
    private static final List<Map<String, BitsetInfo>> registryStack = new ArrayList<>();
    private static Map<String, BitsetInfo> registry;

    @Override
    public String id(){
        return ID;
    }

    @Override
    public void registerParsers(){
        // 幂等：集成阶段可能重复调用（SugarAsserts 同款 containsKey 守卫）
        if(LAssembler.customParsers.containsKey(BitsetStatement.TOKEN)) return;
        LogicIO.allStatements.add(BitsetStatement::new);
        LAssembler.customParsers.put(BitsetStatement.TOKEN, BitsetModule::parseBitset);
    }

    /** {@code bitset <name> <memory> <base> <words>} 解析器（空槽 {@code ~}）。 */
    public static LStatement parseBitset(String[] tokens){
        BitsetStatement result = new BitsetStatement();
        result.name = optionalValue(tokens[1]);
        if(result.name.isEmpty()){
            throw new IllegalArgumentException("Invalid bitset statement: missing bitset name");
        }
        result.memory = optionalValue(tokens[2]);
        if(result.memory.isEmpty()){
            throw new IllegalArgumentException("Invalid bitset statement: missing memory cell");
        }
        result.base = optionalValue(tokens[3]);
        result.words = optionalValue(tokens[4]);
        return result;
    }

    @Override
    public void collect(List<LStatement> statements, Set<String> functionNames){
        List<BitsetInfo> infos = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Map<String, List<long[]>> spans = new LinkedHashMap<>();
        ArrayRegistry arrays = ArrayRegistry.active();
        for(int i = 0; i < statements.size(); i++){
            if(!(statements.get(i) instanceof BitsetStatement card)) continue;
            String error = validate(card, names, spans, functionNames, arrays);
            if(error != null) throw new IllegalArgumentException("bitset at statement " + i + " " + error + ".");
            String name = card.name.trim();
            String memory = card.memory.trim();
            long base = Long.parseLong(card.base.trim());
            long words = Long.parseLong(card.words.trim());
            names.add(name);
            spans.computeIfAbsent(memory, k -> new ArrayList<>()).add(new long[]{base, base + words});
            infos.add(new BitsetInfo(name, memory, (int)base, (int)words));
        }
        pushRegistry(infos);
    }

    @Override
    public void restore(){
        popRegistry();
    }

    @Override
    public void markInvalid(List<LStatement> statements, boolean[] invalid, Set<String> functionNames){
        Set<String> names = new HashSet<>();
        Map<String, List<long[]>> spans = new LinkedHashMap<>();
        ArrayRegistry arrays = ArrayRegistry.active();
        for(int i = 0; i < statements.size() && i < invalid.length; i++){
            if(!(statements.get(i) instanceof BitsetStatement card)) continue;
            String error = validate(card, names, spans, functionNames, arrays);
            if(error != null){
                invalid[i] = true;
                continue;
            }
            long base = Long.parseLong(card.base.trim());
            long words = Long.parseLong(card.words.trim());
            names.add(card.name.trim());
            spans.computeIfAbsent(card.memory.trim(), k -> new ArrayList<>()).add(new long[]{base, base + words});
        }
    }

    @Override
    public ExprIntrinsics.Provider intrinsics(){
        return BitsetIntrinsics.INSTANCE;
    }

    @Override
    public List<String> builtinSugar(){
        List<String> result = new ArrayList<>(2);
        result.add(BitsetIntrinsics.writeBuiltinSugar());
        result.add(BitsetIntrinsics.countBuiltinSugar());
        return result;
    }

    // ===== 注册表 =====

    private static void pushRegistry(List<BitsetInfo> infos){
        Map<String, BitsetInfo> map = new LinkedHashMap<>();
        for(BitsetInfo info : infos) map.put(info.name, info);
        registryStack.add(registry);
        registry = map;
    }

    private static void popRegistry(){
        registry = registryStack.isEmpty() ? null : registryStack.remove(registryStack.size() - 1);
    }

    /**
     * 解析一个已声明位集：编译期注册表优先；不在编译中（编辑器预览/标红）时回退到
     * 当前 Sugar 画布上的 bitset 声明卡（宽松口径，跳过不合法卡片）。找不到返回 null。
     */
    public static BitsetInfo find(String name){
        if(name == null) return null;
        Map<String, BitsetInfo> map = registry;
        if(map == null) map = canvasRegistry();
        return map.get(name);
    }

    /** 从当前画布收集 bitset 声明卡；无 UI（无头自测）或画布不可用时为空表。 */
    private static Map<String, BitsetInfo> canvasRegistry(){
        Map<String, BitsetInfo> result = new LinkedHashMap<>();
        try{
            LCanvas canvas = SugarCanvas.current();
            if(canvas == null || canvas.statements == null) return result;
            for(Element child : canvas.statements.getChildren()){
                if(!(child instanceof LCanvas.StatementElem elem)) continue;
                if(!(elem.st instanceof BitsetStatement card)) continue;
                String name = card.name == null ? "" : card.name.trim();
                String memory = card.memory == null ? "" : card.memory.trim();
                Long base = parseIntLiteral(card.base);
                Long words = parseIntLiteral(card.words);
                if(!isIdentifier(name) || name.startsWith("__ls_")) continue;
                if(memory.isEmpty() || result.containsKey(name)) continue;
                if(base == null || words == null || base < 0 || base > Integer.MAX_VALUE
                    || words < 1 || words > Integer.MAX_VALUE) continue;
                result.put(name, new BitsetInfo(name, memory, (int)(long)base, (int)(long)words));
            }
        }catch(Throwable ignored){
            // 无头自测环境（Vars.ui 未初始化等）：视同没有编辑器上下文
        }
        return result;
    }

    // ===== 校验 =====

    /** 校验一张声明卡；返回 null 表示合法。合法卡片由调用方登记进 names/spans。 */
    private static String validate(BitsetStatement card, Set<String> names, Map<String, List<long[]>> spans,
                                   Set<String> functionNames, ArrayRegistry arrays){
        String name = card.name == null ? "" : card.name.trim();
        if(name.isEmpty()) return "name must not be empty";
        if(!isIdentifier(name)) return "name '" + name + "' must match [A-Za-z_][A-Za-z0-9_]*";
        if(name.startsWith("__ls_")) return "name '" + name + "' uses the reserved '__ls_' prefix";
        if(names.contains(name)) return "duplicate name '" + name + "'";
        if(functionNames != null && functionNames.contains(name)){
            return "name '" + name + "' conflicts with a function of the same name";
        }
        if(arrays != null){
            if(arrays.get(name) != null) return "name '" + name + "' conflicts with a declared array";
            if(arrays.getMatrix(name) != null) return "name '" + name + "' conflicts with a declared matrix";
        }
        String memory = card.memory == null ? "" : card.memory.trim();
        if(memory.isEmpty()) return "bitset '" + name + "' needs a memory cell variable";
        Long base = parseIntLiteral(card.base);
        if(base == null || base < 0 || base > Integer.MAX_VALUE){
            return "bitset '" + name + "' base must be a non-negative integer literal, got '" + card.base + "'";
        }
        Long words = parseIntLiteral(card.words);
        if(words == null || words < 1 || words > Integer.MAX_VALUE){
            return "bitset '" + name + "' words must be an integer literal of at least 1, got '" + card.words + "'";
        }
        long end = base + words;
        int capacity = ArrayRegistry.memoryCapacity(memory);
        if(capacity > 0 && end > capacity){
            return "bitset '" + name + "' needs addresses up to " + (end - 1)
                + ", but memory '" + memory + "' only has " + capacity + " slots";
        }
        List<long[]> existing = spans.get(memory);
        if(existing != null){
            for(long[] span : existing){
                if(base < span[1] && span[0] < end){
                    return "bitset '" + name + "' range [" + base + ", " + end
                        + ") overlaps another bitset on '" + memory + "'";
                }
            }
        }
        return null;
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

    private static String optional(String value){
        return value == null || value.isEmpty() ? "~" : value;
    }

    private static String optionalValue(String value){
        return value == null || value.equals("~") ? "" : value;
    }

    private static String text(String key, String fallback){
        try{
            if(Core.bundle != null) return Core.bundle.get("logicsugar." + key, fallback);
        }catch(Throwable ignored){
            // 无头环境：退回英文 fallback
        }
        return fallback;
    }

    // ===== 元数据 =====

    /** 一个已声明位集：内存块上 [base, base+words) 的 words×64 位位向量。 */
    public static final class BitsetInfo{
        public final String name;
        public final String memory;
        public final int base;
        public final int words;

        BitsetInfo(String name, String memory, int base, int words){
            this.name = name;
            this.memory = memory;
            this.base = base;
            this.words = words;
        }

        /** 可寻址位数（words*64，用 long 避免溢出）。 */
        public long bitCapacity(){
            return (long)words * 64L;
        }
    }

    /**
     * 位集声明卡：{@code bitset <name> <memory> <base> <words>}，固定 5 个 token
     * （空槽写 {@code ~}）。卡片本身不产出任何 mlog 行，只是编译期元数据。
     */
    public static class BitsetStatement extends DataDeclaration{
        public static final String TOKEN = "bitset";

        /** 表达式中使用的位集名。 */
        public String name = "bits";
        /** 承载数据的位集内存块变量名（如 cell1）。 */
        public String memory = "cell1";
        /** 起始物理地址（非负整数字面量）。 */
        public String base = "0";
        /** 字数（≥1 整数字面量）；每字 64 位，占用 [base, base+words) 槽。 */
        public String words = "1";

        @Override
        public String token(){
            return TOKEN;
        }

        @Override
        public void build(Table table){
            table.add(SugarStatements.cardText("bitset.card", "Bitset")).self(c -> hint(c, "bitset.name"));
            field(table, name, value -> name = value).width(70f);
            table.add(text("array.memory", "mem")).self(c -> hint(c, "bitset.memory"));
            field(table, memory, value -> memory = value).width(70f);
            table.add(text("array.base", "base")).self(c -> hint(c, "bitset.base"));
            field(table, base, value -> base = value).width(45f);
            table.add(text("bitset.words", "words")).self(c -> hint(c, "bitset.words"));
            field(table, words, value -> words = value).width(45f);
        }

        @Override
        public String name(){
            return SugarStatements.cardText("bitset.card", "Bitset");
        }

        @Override
        public void write(StringBuilder out){
            // 固定 token 数（空槽位 "~" 占位）：LParser 复用静态 token 数组，缺尾 token 无法与残值区分
            out.append(TOKEN).append(' ').append(optional(name)).append(' ').append(optional(memory))
                .append(' ').append(optional(base)).append(' ').append(optional(words));
        }
    }
}
