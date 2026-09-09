package logicsugar.assist.data;

import arc.Core;
import arc.scene.Element;
import arc.scene.ui.layout.Table;
import logicsugar.assist.expr.ArrayRegistry;
import logicsugar.assist.expr.ExprIntrinsics;
import logicsugar.assist.expr.RecordIntrinsics;
import mindustry.gen.LogicIO;
import mindustry.logic.LAssembler;
import mindustry.logic.LCanvas;
import mindustry.logic.LStatement;
import mindustry.logic.SugarCanvas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 记录（record）模块：{@code record <name> <f1>…<f8>} 声明卡 + 编译期注册表 + 成员访问展开。
 *
 * <p>记录是纯编译期抽象——mlog 没有结构体，声明卡本身不产出任何指令（lower 整体跳过）。
 * 字段存储为普通变量 {@code <name>_<field>}（用户可见、可调试，{@link logicsugar.assist.VarDisplayFilter}
 * 不会隐藏），成员读 {@code p.f1} 降级为 {@code op add <tmp> p_f1 0}，成员写
 * {@code p.f1 = expr}（表达式语句 dest 形如 {@code p.f1}）降级为
 * {@code op add p_f1 <value> 0}，产物始终是纯原版指令。</p>
 *
 * <p><b>sensor 兼容</b>：成员展开只在「基底是已声明 record 的变量名」时生效
 * （{@link #active()} 的注册表命中 {@link ExprCompiler.Var} 名字）。未声明为 record 的
 * 成员访问原样走 {@code ExprCompiler.resolveMember} 的 sensor 路径，因此
 * {@code unit.health}、{@code unit.x} 等既有表达式不受影响；反过来，已声明为 record 的
 * 变量名出现未知成员时按笔误报错（不再退回 sensor）。</p>
 *
 * <p>编译期上下文与 {@link ArrayRegistry} 同模式：{@link #collect} 建立程序级注册表并
 * {@link #enter}，{@link #restore} 与 collect 配对弹出（{@link DataModules} 驱动）；
 * 没有编译上下文时（编辑器预览/条件表达式校验）回退到当前画布探测
 * （{@link #canvasRegistry()}，无头环境返回 null）。</p>
 */
public class RecordModule extends DataModule{
    public static final String ID = "record";
    /** 声明卡语法 token。 */
    public static final String TOKEN = "record";
    /** 字段槽位数（卡片固定 10 token：token + 名字 + 8 个字段槽）。 */
    public static final int SLOTS = 8;
    /** 一个 record 允许的最大字段数。 */
    public static final int MAX_FIELDS = 8;

    private static final String RESERVED_PREFIX = "__ls_";

    private static boolean parsersInstalled;
    private static RecordRegistry current;

    /** 与 {@link #collect} 配对的先前上下文栈（允许 null；同一模块实例可能被嵌套编译复用）。 */
    private final List<RecordRegistry> previousStack = new ArrayList<>();

    @Override
    public String id(){
        return ID;
    }

    @Override
    public void registerParsers(){
        if(parsersInstalled) return;
        parsersInstalled = true;
        LAssembler.customParsers.put(TOKEN, RecordModule::parseRecord);
        LogicIO.allStatements.add(RecordStatement::new);
    }

    @Override
    public void collect(List<LStatement> statements, Set<String> functionNames){
        // 先整表严格校验，全部通过后才进入上下文：校验失败不留下任何静态状态
        previousStack.add(enter(compileRegistry(statements, functionNames)));
    }

    @Override
    public void restore(){
        if(previousStack.isEmpty()) return;
        RecordModule.restore(previousStack.remove(previousStack.size() - 1));
    }

    @Override
    public void markInvalid(List<LStatement> statements, boolean[] invalid, Set<String> functionNames){
        markInvalidStatements(statements, invalid, functionNames);
    }

    @Override
    public ExprIntrinsics.Provider intrinsics(){
        return RecordIntrinsics.INSTANCE;
    }

    // ===== 声明卡 =====

    /**
     * 记录声明卡：{@code record <name> <f1> … <f8>}（固定 10 token，空槽写 {@code ~}）。
     * 纯编译期元数据，{@code write} 只用于存档往返；lower 阶段由
     * {@code SugarFunctions.lower} 整体跳过。
     */
    public static class RecordStatement extends DataDeclaration{
        /** 记录名（表达式里的基底变量名）。 */
        public String name = "";
        /** 8 个字段槽位（空串 = 未使用，写盘时输出 {@code ~}）。 */
        public final List<String> fields = new ArrayList<>(Collections.nCopies(SLOTS, ""));

        /** 已声明字段（去空、去空白、按声明顺序）。 */
        public List<String> fieldList(){
            List<String> result = new ArrayList<>(SLOTS);
            for(String field : fields){
                if(field == null) continue;
                String trimmed = field.trim();
                if(!trimmed.isEmpty()) result.add(trimmed);
            }
            return result;
        }

        @Override
        public void build(Table table){
            table.add(text("logicsugar.record.card", "Record"));
            field(table, name, value -> name = value).width(70f);
            for(int i = 0; i < SLOTS; i++){
                final int index = i;
                if(i % 4 == 0) table.row();
                table.add(String.valueOf(i)).padLeft(i % 4 == 0 ? 4 : 2).color(table.color);
                field(table, fields.get(index), value -> fields.set(index, value)).width(60f).pad(2f);
            }
        }

        @Override
        public String name(){
            return text("logicsugar.record.card", "Record");
        }

        @Override
        public String token(){
            return TOKEN;
        }

        @Override
        public void write(StringBuilder out){
            // 固定 token 数（空槽位 "~" 占位）：LParser 复用静态 token 数组，缺尾 token 无法与残值区分
            out.append(TOKEN).append(' ').append(optional(name));
            for(int i = 0; i < SLOTS; i++){
                out.append(' ').append(optional(i < fields.size() ? fields.get(i) : ""));
            }
        }
    }

    /** 解析 {@code record} 声明卡；语义校验（名字/字段/重名）推迟到 {@link #compileRegistry}。 */
    public static LStatement parseRecord(String[] tokens){
        RecordStatement result = new RecordStatement();
        result.name = tokens.length > 1 ? optionalValue(tokens[1]) : "";
        for(int i = 0; i < SLOTS; i++){
            int index = 2 + i;
            // 自定义解析器拿不到本行 token 数量（LParser 复用静态数组），超出本行的槽位
            // 读到的可能是残留值；卡片写盘时总是补满 8 个槽位，正常存档的往返始终精确。
            result.fields.set(i, index < tokens.length ? optionalValue(tokens[index]) : "");
        }
        return result;
    }

    // ===== 编译期注册表 =====

    /** 一个已声明记录：名字 + 有序字段表 + 字段→存储变量名映射。 */
    public static final class RecordInfo{
        public final String name;
        public final List<String> fields;
        private final Map<String, String> variables;

        RecordInfo(String name, List<String> fields){
            this.name = name;
            this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
            Map<String, String> map = new LinkedHashMap<>();
            for(String field : this.fields){
                map.put(field, name + "_" + field);
            }
            this.variables = Collections.unmodifiableMap(map);
        }

        /** @return 该成员名对应的字段名（大小写敏感），未知成员返回 null。 */
        public String findField(String prop){
            return prop != null && variables.containsKey(prop) ? prop : null;
        }

        public boolean hasField(String field){
            return variables.containsKey(field);
        }

        /** 字段的存储变量名 {@code <name>_<field>}；未知字段返回 null。 */
        public String variable(String field){
            return variables.get(field);
        }
    }

    /** 程序级记录注册表（按声明名索引，同时维护存储变量→记录名）。 */
    public static final class RecordRegistry{
        private final Map<String, RecordInfo> byName = new LinkedHashMap<>();
        private final Map<String, String> variables = new LinkedHashMap<>();

        public RecordInfo get(String name){
            return name == null ? null : byName.get(name);
        }

        public boolean isEmpty(){
            return byName.isEmpty();
        }

        public List<RecordInfo> all(){
            return Collections.unmodifiableList(new ArrayList<>(byName.values()));
        }

        /** 存储变量名（{@code name_field}）属于哪个记录；未占用返回 null。 */
        public String ownerOfVariable(String variable){
            return variable == null ? null : variables.get(variable);
        }

        public boolean hasVariable(String variable){
            return variable != null && variables.containsKey(variable);
        }

        void put(RecordInfo info){
            byName.put(info.name, info);
            for(String field : info.fields){
                variables.put(info.variable(field), info.name);
            }
        }
    }

    /**
     * 从语句列表严格收集全部 {@link RecordStatement}，任何问题都抛出
     * {@link IllegalArgumentException}（消息走 {@link ExprIntrinsics#text} 的 l10n 键 + 英文
     * fallback）。校验项：名字合法/非 {@code __ls_} 前缀/不与其它 record 重名/不与
     * 数组、矩阵、函数重名；字段名合法且同一 record 内唯一；至少 1 个、最多 8 个字段；
     * 字段存储变量 {@code <name>_<field>} 不与数组/矩阵/其它记录的名字或存储变量冲突。
     *
     * @param functionNames 本地 funcdef + 库函数名的并集，record 名不得与之冲突
     */
    public static RecordRegistry compileRegistry(List<LStatement> statements, Set<String> functionNames){
        RecordRegistry registry = new RecordRegistry();
        ArrayRegistry arrays = ArrayRegistry.active();
        Set<String> recordNames = new LinkedHashSet<>();
        Map<String, String> storage = new LinkedHashMap<>();
        int index = 0;
        for(LStatement statement : statements){
            if(statement instanceof RecordStatement card){
                String name = card.name == null ? "" : card.name.trim();
                if(name.isEmpty()){
                    throw error(index, ExprIntrinsics.text("la.err.record_name_empty",
                        "record name must not be empty"));
                }
                if(!isIdentifier(name)){
                    throw error(index, ExprIntrinsics.text("la.err.record_name_invalid",
                        "record name {0} must match [A-Za-z_][A-Za-z0-9_]*", name));
                }
                if(name.startsWith(RESERVED_PREFIX)){
                    throw error(index, ExprIntrinsics.text("la.err.record_name_reserved",
                        "record name {0} uses the reserved __ls_ prefix", name));
                }
                if(recordNames.contains(name)){
                    throw error(index, ExprIntrinsics.text("la.err.record_name_duplicate",
                        "duplicate record name {0}", name));
                }
                if(functionNames != null && functionNames.contains(name)){
                    throw error(index, ExprIntrinsics.text("la.err.record_name_conflict_function",
                        "record name {0} conflicts with a function of the same name", name));
                }
                if(arrays != null && (arrays.get(name) != null || arrays.getMatrix(name) != null)){
                    throw error(index, ExprIntrinsics.text("la.err.record_name_conflict_array",
                        "record name {0} conflicts with an array or matrix of the same name", name));
                }
                if(storage.containsKey(name)){
                    throw error(index, ExprIntrinsics.text("la.err.record_name_conflict_field",
                        "record name {0} conflicts with field variable {0} of record {1}",
                        name, storage.get(name)));
                }

                List<String> fields = new ArrayList<>(SLOTS);
                for(String raw : card.fields){
                    String field = raw == null ? "" : raw.trim();
                    if(field.isEmpty()) continue;
                    if(!isIdentifier(field)){
                        throw error(index, ExprIntrinsics.text("la.err.record_field_invalid",
                            "field name {0} of record {1} must match [A-Za-z_][A-Za-z0-9_]*",
                            field, name));
                    }
                    if(fields.contains(field)){
                        throw error(index, ExprIntrinsics.text("la.err.record_field_duplicate",
                            "duplicate field {0} in record {1}", field, name));
                    }
                    fields.add(field);
                }
                if(fields.isEmpty()){
                    throw error(index, ExprIntrinsics.text("la.err.record_no_fields",
                        "record {0} must declare at least one field", name));
                }
                if(fields.size() > MAX_FIELDS){
                    throw error(index, ExprIntrinsics.text("la.err.record_too_many_fields",
                        "record {0} declares {1} fields; at most 8 are supported", name, fields.size()));
                }

                RecordInfo info = new RecordInfo(name, fields);
                for(String field : fields){
                    String variable = info.variable(field);
                    if(arrays != null && (arrays.get(variable) != null || arrays.getMatrix(variable) != null)){
                        throw error(index, ExprIntrinsics.text("la.err.record_field_conflict_array",
                            "field variable {0} of record {1} conflicts with an array or matrix of the same name",
                            variable, name));
                    }
                    if(recordNames.contains(variable)){
                        throw error(index, ExprIntrinsics.text("la.err.record_field_conflict_record",
                            "field variable {0} of record {1} conflicts with record {0}",
                            variable, name));
                    }
                    if(storage.containsKey(variable)){
                        throw error(index, ExprIntrinsics.text("la.err.record_field_conflict_duplicate",
                            "field variable {0} is declared by both record {1} and record {2}",
                            variable, storage.get(variable), name));
                    }
                }

                recordNames.add(name);
                registry.put(info);
                for(String field : fields){
                    storage.put(info.variable(field), name);
                }
            }
            index++;
        }
        return registry;
    }

    // ===== 编辑期标红 =====

    /** 编辑期字段级校验：有问题的声明卡标红（{@code invalid[i] = true}），不抛错。 */
    public static void markInvalidStatements(List<LStatement> statements, boolean[] invalid, Set<String> functionNames){
        ArrayRegistry arrays = null;
        try{
            arrays = ArrayRegistry.active();
        }catch(Throwable ignored){
            // 无编辑器/无头环境：跳过数组冲突检查
        }
        Set<String> recordNames = new LinkedHashSet<>();
        Set<String> storage = new LinkedHashSet<>();
        int index = 0;
        for(LStatement statement : statements){
            if(statement instanceof RecordStatement card){
                String name = card.name == null ? "" : card.name.trim();
                List<String> fields = card.fieldList();
                boolean bad = name.isEmpty() || !isIdentifier(name) || name.startsWith(RESERVED_PREFIX)
                    || recordNames.contains(name) || storage.contains(name)
                    || (functionNames != null && functionNames.contains(name))
                    || (arrays != null && (arrays.get(name) != null || arrays.getMatrix(name) != null))
                    || fields.isEmpty() || fields.size() > MAX_FIELDS;
                if(!bad){
                    Set<String> seen = new HashSet<>();
                    for(String field : fields){
                        if(!isIdentifier(field) || !seen.add(field)){
                            bad = true;
                            break;
                        }
                        String variable = name + "_" + field;
                        if(storage.contains(variable) || recordNames.contains(variable)
                            || (arrays != null && (arrays.get(variable) != null || arrays.getMatrix(variable) != null))){
                            bad = true;
                            break;
                        }
                    }
                }
                if(bad){
                    invalid[index] = true;
                }else{
                    recordNames.add(name);
                    for(String field : fields){
                        storage.add(name + "_" + field);
                    }
                }
            }
            index++;
        }
    }

    // ===== 静态编译期上下文 =====

    /** 进入编译期上下文，返回先前的注册表供 {@link #restore} 恢复（须成对调用）。 */
    public static RecordRegistry enter(RecordRegistry registry){
        RecordRegistry previous = current;
        current = registry;
        return previous;
    }

    /** 恢复 {@link #enter} 返回的先前上下文。 */
    public static void restore(RecordRegistry previous){
        current = previous;
    }

    /** 成员访问解析所用的注册表：编译期上下文优先，否则回退到当前画布。 */
    public static RecordRegistry active(){
        RecordRegistry context = current;
        if(context != null) return context;
        return canvasRegistry();
    }

    /** 当前打开的 Sugar 画布上的 record 声明（跳过不合法/重复的卡片）；画布不可用时为 null。 */
    public static RecordRegistry canvasRegistry(){
        try{
            SugarCanvas canvas = SugarCanvas.current();
            if(canvas == null || canvas.statements == null) return null;
            RecordRegistry registry = new RecordRegistry();
            for(Element child : canvas.statements.getChildren()){
                if(!(child instanceof LCanvas.StatementElem elem)) continue;
                if(!(elem.st instanceof RecordStatement card)) continue;
                String name = card.name == null ? "" : card.name.trim();
                if(!isIdentifier(name) || name.startsWith(RESERVED_PREFIX) || registry.get(name) != null) continue;
                List<String> fields = card.fieldList();
                if(fields.isEmpty() || fields.size() > MAX_FIELDS) continue;
                Set<String> seen = new HashSet<>();
                boolean bad = false;
                for(String field : fields){
                    if(!isIdentifier(field) || !seen.add(field)){
                        bad = true;
                        break;
                    }
                    String variable = name + "_" + field;
                    if(registry.hasVariable(variable) || registry.get(variable) != null){
                        bad = true;
                        break;
                    }
                }
                if(bad) continue;
                registry.put(new RecordInfo(name, fields));
            }
            return registry;
        }catch(Throwable t){
            // 无头自测环境（Vars.ui 未初始化等）：视同没有编辑器上下文
            return null;
        }
    }

    // ===== 工具 =====

    private static IllegalArgumentException error(int index, String detail){
        return new IllegalArgumentException("record at statement " + index + " " + detail + ".");
    }

    static boolean isIdentifier(String name){
        if(name == null || name.isEmpty()) return false;
        char first = name.charAt(0);
        if(!(Character.isLetter(first) || first == '_')) return false;
        for(int i = 1; i < name.length(); i++){
            char c = name.charAt(i);
            if(!(Character.isLetterOrDigit(c) || c == '_')) return false;
        }
        return true;
    }

    private static String optional(String value){
        return value == null || value.isEmpty() ? "~" : value;
    }

    private static String optionalValue(String token){
        return token == null || token.equals("~") ? "" : token.trim();
    }

    private static String text(String key, String fallback){
        try{
            return Core.bundle.get(key, fallback);
        }catch(Throwable ignored){
            return fallback;
        }
    }
}
