package logicsugar.assist.expr;

import arc.scene.Element;
import arc.struct.Seq;
import mindustry.logic.LCanvas;
import mindustry.logic.LStatement;
import mindustry.logic.SugarCanvas;
import mindustry.logic.SugarFunctions;
import mindustry.logic.SugarStatements.ArrayStatement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数组注册表：{@code array} 声明卡（{@link ArrayStatement}）的编译期元数据。
 *
 * <p>数组是纯 sugar 抽象——mlog 没有数组，卡片本身不产出任何指令（lower 时被剥离）。
 * 表达式下标 {@code buf[i]} / {@code buf[i] = x} 在编译时查此表，把逻辑下标换算成
 * 内存块（memory cell）的物理地址，产出原版 {@code read}/{@code write} 指令：
 * 物理地址 = base + 逻辑下标，[base, base+size) 为该数组的专属区间。</p>
 *
 * <p>两个构建口径：
 * <ul>
 *   <li><b>严格</b>（{@link #compileRegistry}，编译路径）：任何字段问题（名字、字面量、
 *       重名、与函数重名、同内存块区间重叠）都抛出编译错误；</li>
 *   <li><b>宽松</b>（{@link #canvasRegistry}，编辑器路径）：从当前画布收集声明卡，
 *       跳过不合法的卡片（编辑期标红由 {@link #markInvalidStatements} 负责，不阻塞预览）。</li>
 * </ul></p>
 *
 * <p>上下文传递采用 {@link mindustry.logic.SugarCompiler#currentAssertEmit()} 式的静态
 * 编译期上下文：编译路径在 lowering 期间 enter/restore，编辑器路径没有上下文时回退到
 * 当前画布（{@link #active()}）。注册表为空或缺失时表达式下标退化为"仅语法校验"——
 * 按普通变量名发射 read/write，不做名字与越界检查（纯原版 mlog 与函数库预览不受影响）。</p>
 */
public final class ArrayRegistry{
    private ArrayRegistry(){}

    /** 一个已声明数组：内存块上的命名区间 [base, base+size)。 */
    public static final class ArrayInfo{
        public final String name;
        public final String memory;
        public final int base;
        public final int size;

        ArrayInfo(String name, String memory, int base, int size){
            this.name = name;
            this.memory = memory;
            this.base = base;
            this.size = size;
        }

        /** 逻辑下标是否落在 [0, size)。 */
        public boolean inRange(long index){
            return index >= 0 && index < size;
        }

        /** 逻辑下标对应的物理地址（base + index）。 */
        public long addressOf(long index){
            return base + index;
        }
    }

    private final Map<String, ArrayInfo> byName = new LinkedHashMap<>();

    /** @return 按声明名查找的数组，未声明时为 null。 */
    public ArrayInfo get(String name){
        return name == null ? null : byName.get(name);
    }

    public boolean isEmpty(){
        return byName.isEmpty();
    }

    /** 声明在同一内存块上的全部数组（区间互不重叠，严格构建保证）。 */
    public List<ArrayInfo> byMemory(String memory){
        List<ArrayInfo> result = new ArrayList<>();
        if(memory != null){
            for(ArrayInfo info : byName.values()){
                if(memory.equals(info.memory)) result.add(info);
            }
        }
        return result;
    }

    // ===== 严格构建（编译路径） =====

    /**
     * 从语句列表收集全部 {@link ArrayStatement} 并严格校验，任何问题都抛出
     * {@link IllegalArgumentException}（格式对齐 {@code SugarFunctions.error}）。
     * 卡片允许出现在程序任意位置（包括函数体内），注册表始终是程序级的。
     *
     * @param functionNames 本地 funcdef + 库函数名的并集，数组名不得与之冲突
     */
    public static ArrayRegistry compileRegistry(Seq<LStatement> statements, Set<String> functionNames){
        ArrayRegistry registry = new ArrayRegistry();
        // 同一内存块上已声明的区间（用于重叠校验）
        Map<String, List<long[]>> spans = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for(int i = 0; i < statements.size; i++){
            if(!(statements.get(i) instanceof ArrayStatement card)) continue;
            String name = card.array == null ? "" : card.array.trim();
            if(name.isEmpty()) throw error(i, "array name must not be empty");
            if(!isIdentifier(name)) throw error(i, "array name '" + name + "' must match [A-Za-z_][A-Za-z0-9_]*");
            if(name.startsWith("__ls_")) throw error(i, "array name '" + name + "' uses the reserved '__ls_' prefix");
            if(names.contains(name)) throw error(i, "duplicate array name '" + name + "'");
            if(functionNames != null && functionNames.contains(name)){
                throw error(i, "array name '" + name + "' conflicts with a function of the same name");
            }
            String memory = card.memory == null ? "" : card.memory.trim();
            if(memory.isEmpty()) throw error(i, "array '" + name + "' needs a memory cell variable");
            Long base = parseIntLiteral(card.base);
            if(base == null || base < 0 || base > Integer.MAX_VALUE){
                throw error(i, "array '" + name + "' base must be a non-negative integer literal (variables are not supported yet), got '" + card.base + "'");
            }
            Long size = parseIntLiteral(card.size);
            if(size == null || size < 1 || size > Integer.MAX_VALUE){
                throw error(i, "array '" + name + "' size must be an integer literal of at least 1 (variables are not supported yet), got '" + card.size + "'");
            }
            List<long[]> existing = spans.computeIfAbsent(memory, k -> new ArrayList<>());
            for(long[] span : existing){
                if(base < span[1] && span[0] < base + size){
                    throw error(i, "array '" + name + "' range [" + base + ", " + (base + size)
                        + ") overlaps another array on '" + memory + "'");
                }
            }
            existing.add(new long[]{base, base + size});
            names.add(name);
            registry.byName.put(name, new ArrayInfo(name, memory, (int)(long)base, (int)(long)size));
        }
        return registry;
    }

    private static IllegalArgumentException error(int index, String detail){
        return new IllegalArgumentException("array at statement " + index + " " + detail + ".");
    }

    // ===== 宽松构建（编辑器路径） =====

    /** 收集当前打开的 Sugar 画布上的数组声明卡（跳过不合法的卡片），画布不可用时为 null。 */
    public static ArrayRegistry canvasRegistry(){
        try{
            return canvasRegistry(SugarCanvas.current());
        }catch(Throwable t){
            // 无头自测环境（Vars.ui 未初始化等）：视同没有编辑器上下文
            return null;
        }
    }

    /** 收集指定画布上的数组声明卡（跳过不合法的卡片），画布不可用时为 null。
     *  折叠/展开（ExprHook）传入画布本体，避免依赖全局当前画布。 */
    public static ArrayRegistry canvasRegistry(LCanvas canvas){
        try{
            if(canvas == null || canvas.statements == null) return null;
            ArrayRegistry registry = new ArrayRegistry();
            for(Element child : canvas.statements.getChildren()){
                if(child instanceof LCanvas.StatementElem elem && elem.st instanceof ArrayStatement card){
                    String name = card.array == null ? "" : card.array.trim();
                    String memory = card.memory == null ? "" : card.memory.trim();
                    Long base = parseIntLiteral(card.base);
                    Long size = parseIntLiteral(card.size);
                    // 宽松口径：名字合法、未被占用、内存块非空、区间字面量合法才登记；
                    // 其余问题（重叠、与函数重名等）留给编译期严格校验与编辑期标红
                    if(!isIdentifier(name) || name.startsWith("__ls_")) continue;
                    if(memory.isEmpty() || registry.byName.containsKey(name)) continue;
                    if(base == null || size == null || base < 0 || base > Integer.MAX_VALUE
                        || size < 1 || size > Integer.MAX_VALUE) continue;
                    registry.byName.put(name, new ArrayInfo(name, memory, (int)(long)base, (int)(long)size));
                }
            }
            return registry;
        }catch(Throwable t){
            // 无头自测环境（Vars.ui 未初始化等）：视同没有编辑器上下文
            return null;
        }
    }

    /** 空注册表哨兵：显式表示"没有数组上下文"。enter 后 {@link #active()} 不再回退到
     *  画布探测，下标表达式的折叠按注册表缺失处理（仅语法校验）。 */
    public static ArrayRegistry empty(){
        return new ArrayRegistry();
    }

    // ===== 编辑期标红 =====

    /**
     * 编辑期字段级校验：把有问题的数组卡标红（{@code invalid[i] = true}）。
     * 只做字段层面的检查（名字/字面量/重名/与函数重名/区间重叠），不抛错——
     * 编译期的严格校验仍会拦截保存。
     */
    public static void markInvalidStatements(Seq<LStatement> statements, boolean[] invalid, Set<String> functionNames){
        Set<String> names = new HashSet<>();
        Map<String, List<long[]>> spans = new LinkedHashMap<>();
        for(int i = 0; i < statements.size; i++){
            if(!(statements.get(i) instanceof ArrayStatement card)) continue;
            String name = card.array == null ? "" : card.array.trim();
            String memory = card.memory == null ? "" : card.memory.trim();
            Long base = parseIntLiteral(card.base);
            Long size = parseIntLiteral(card.size);
            boolean bad = name.isEmpty() || !isIdentifier(name) || name.startsWith("__ls_")
                || memory.isEmpty()
                || base == null || base < 0 || base > Integer.MAX_VALUE
                || size == null || size < 1 || size > Integer.MAX_VALUE
                || names.contains(name)
                || (functionNames != null && functionNames.contains(name));
            if(!bad){
                List<long[]> existing = spans.computeIfAbsent(memory, k -> new ArrayList<>());
                for(long[] span : existing){
                    if(base < span[1] && span[0] < base + size){ bad = true; break; }
                }
                if(!bad) existing.add(new long[]{base, base + size});
            }
            if(bad){
                invalid[i] = true;
            }else{
                names.add(name);
            }
        }
    }

    // ===== 静态编译期上下文 =====

    private static ArrayRegistry current;

    /** 进入编译期上下文，返回先前的注册表供 {@link #restore} 恢复（须 try/finally 配对）。 */
    public static ArrayRegistry enter(ArrayRegistry registry){
        ArrayRegistry previous = current;
        current = registry;
        return previous;
    }

    /** 恢复 {@link #enter} 返回的先前上下文。 */
    public static void restore(ArrayRegistry previous){
        current = previous;
    }

    /** 表达式下标折叠所用的注册表：编译期上下文优先，否则回退到当前画布。 */
    public static ArrayRegistry active(){
        ArrayRegistry context = current;
        if(context != null) return context;
        return canvasRegistry();
    }

    // ===== 工具 =====

    static boolean isIdentifier(String name){
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
    static Long parseIntLiteral(String token){
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
