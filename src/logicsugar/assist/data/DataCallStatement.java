package logicsugar.assist.data;

import arc.graphics.g2d.Font;
import arc.graphics.g2d.GlyphLayout;
import arc.scene.Element;
import arc.scene.style.Drawable;
import arc.scene.ui.Button;
import arc.scene.ui.ButtonGroup;
import arc.scene.ui.Label;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import logicsugar.assist.expr.ExpressionEditor;
import mindustry.logic.LAssembler;
import mindustry.logic.LCategory;
import mindustry.logic.LStatement;
import mindustry.logic.SugarFunctions;
import mindustry.logic.SugarStatements;
import mindustry.ui.Styles;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A persistent, editable card for one data intrinsic call.  The card keeps the
 * source-level call in the Sugar carrier; {@link mindustry.logic.SugarFunctions}
 * lowers it through the same ExprCompiler path used by expression statements.
 *
 * <p>Since v5.2 one card covers a whole <em>family</em> of operations: the palette
 * registers a single card per structure (stack, queue, …) and the operation itself is
 * chosen with the in-card button, mirroring vanilla's {@code op} card.  The carrier is
 * unchanged ({@code datacall <operation> <destination> "<arguments>"}), so saves written
 * by either shape reload identically.</p>
 */
public class DataCallStatement extends SugarStatements.SugarStatement{
    public static final String TOKEN = "datacall";

    /** 卡内运算按钮的宽度区间（design 单位）：至少能放下默认短名，最多不超过卡片一行的三分之一。 */
    private static final float BUTTON_MIN_WIDTH = 118f, BUTTON_MAX_WIDTH = 150f;
    /** 弹窗条目的宽度区间（原版 op 卡用固定 60f，模组的本地化短名更长，按组内最宽项算）。 */
    private static final float ENTRY_MIN_WIDTH = 118f, ENTRY_MAX_WIDTH = 196f;
    /** 目标变量字段的宽度区间。 */
    private static final float FIELD_MIN_WIDTH = 78f, FIELD_MAX_WIDTH = 140f;
    /** 单个实参输入框的最小宽度：3 参运算要在一行里排 3 个框，不能沿用单框的 90f。 */
    private static final float ARGUMENT_FIELD_MIN_WIDTH = 52f;
    /** 字形/缩放不可用时（无头环境）退回去的固定字段宽度。 */
    private static final float FALLBACK_FIELD_WIDTH = 96f;
    /** 文字与控件边缘之间的额外余量（design 单位），避免正好卡在边界上。 */
    private static final float WIDTH_SLACK = 4f;

    public String operation = "stack_push";
    public String destination = "result";
    public String arguments = "s, 1";
    private transient DataModule.PaletteCall palette;

    /**
     * 定参框的**真值**：一格一条实参，与 {@link #arguments} 互为"界面 ↔ 载体"。
     *
     * <p>只在需要时从 {@link #arguments} 派生一次，之后每次编辑只改自己那一格、末尾把整份拼回
     * {@link #arguments}；{@link #slotCacheFrom} 记住它是从哪份实参串派生出来的，于是任何
     * <b>绕过本类</b>对 {@link #arguments} 的写入（载体重读、{@link #selectOperation}、
     * {@link #parse}、自测直接赋值）都会让缓存立刻失效、下次取用时重新派生，两条路不会各说各话。</p>
     *
     * <p>为什么必须有这份真值：早先每一键都拿 {@link #arguments} **重新拆分**，而"拼回"与"拆分"
     * 在某一格含顶层逗号时并不互逆——用户在第 0 格连打 {@code a,b,c,d}，每敲一键都把上一轮已敲的
     * 文本重新当成"多出来的段"并进最后一格，下次拼回时它又成了新的段，逐键累积增长（实测敲完
     * {@code a,b,c,d} 得到 {@code a,b,c,d, b, c, , b, c, b, , b, value}，敲 {@code a,b,c,d(} 得到
     * 46 字符），用户看到的就是"输入逗号就爆炸"。格子的边界是用户在界面上给定的，不该在下一次
     * 按键时拿载体串重新猜一遍。</p>
     */
    private transient List<String> slotCache;
    /** {@link #slotCache} 派生自哪份 {@link #arguments}；两者不相等即缓存失效。 */
    private transient String slotCacheFrom;

    public DataCallStatement(){
    }

    public DataCallStatement(DataModule.PaletteCall palette){
        applyPalette(palette);
    }

    private void applyPalette(DataModule.PaletteCall value){
        palette = value;
        if(value != null){
            operation = value.name;
            destination = value.destination;
            arguments = value.arguments;
        }
    }

    /**
     * 卡内运算按钮的语义入口（与 UI 分离，便于自测）：实参重置为新运算的默认值；目标变量在
     * 两边都要求结果时保留用户写的名字（无结果运算按 v5 语义置空，序列化成 {@code ~}）。
     *
     * <p>重复选中当前运算只做名称归一化（旧载体里的 {@code spush} → {@code stack_push}），
     * 不覆盖用户已经填好的实参与目标变量。</p>
     */
    public void selectOperation(DataModule.PaletteCall value){
        if(value == null) return;
        String current = canonicalOperation();
        if(current != null && current.equalsIgnoreCase(value.name)){
            operation = value.name;
            return;
        }
        String previousDestination = destination;
        applyPalette(value);
        if(value.returnsValue && previousDestination != null && !previousDestination.trim().isEmpty()){
            destination = previousDestination;
        }
    }

    public DataModule.PaletteCall palette(){
        DataModule.PaletteCall current = DataModules.paletteCall(operation);
        return current == null ? palette : current;
    }

    /**
     * 卡片对外使用的操作名：旧名（v5.1 及更早的 carrier）统一显示成规范新名，卡片正文、
     * 悬停提示键、卡片标题与 {@link #write} 写回载体的名字都走这里，避免旧名残留。
     */
    public String canonicalOperation(){
        return DataModules.canonicalOperation(operation);
    }

    /** 本卡覆盖的结构分组键（由当前运算所属分类推导，如 {@code stackops}）。 */
    public String groupKey(){
        return DataModules.groupKey(palette());
    }

    /** 本卡可在卡内切换的运算（同一结构分组的全部运算，顺序即模块声明顺序）。 */
    public List<DataModule.PaletteCall> groupCalls(){
        return DataModules.operationGroup(groupKey());
    }

    @Override
    public void build(Table table){
        // 卡内运算按钮切换运算后整卡重排（与 SugarAsserts 的条件/操作按钮同一模式）：
        // 目标变量字段随「该运算是否返回值」出现或消失，实参回到该运算的默认形状。
        table.clearChildren();

        DataModule.PaletteCall call = palette();
        boolean returnsValue = call == null || call.returnsValue;
        if(returnsValue){
            // 目标变量字段按内容留宽：写死宽度时默认的 result 在高 UI 缩放下就被切掉最后一格
            Cell<TextField> cell = field(table, destination, value -> destination = value);
            TextField input = cell.get();
            float width = designWidth(paddedWidth(input.getStyle().font, destination, input.getStyle().background),
                FIELD_MIN_WIDTH, FIELD_MAX_WIDTH);
            cell.width(width > 0f ? width : FALLBACK_FIELD_WIDTH);
            table.add(" = ");
        }

        operationButton(table);

        table.add("(");
        argumentFields(table);
        table.add(")").self(c -> hint(c, operationHintKey("arguments")));
    }

    /**
     * 定参输入框的各槽初值；**返回 {@code null} 表示本卡应退回单框**（唯一情形：运算名未知）。
     *
     * <p>判定逻辑从 {@link #build} 里提出来，是为了让它可以无头自测：{@code build} 需要 GL
     * （Table/Label 都建不起来），但"这张卡要不要用定参框、每个槽初始显示什么"是纯数据问题，
     * 而且正是最容易出错的地方（旧载体缺参、多余参、括号嵌套）。</p>
     *
     * <p>拆分**只按字段数锚定**，与当前串可不可解析无关——这是刻意的，因为本方法同时服务两条路径：
     * 建卡（{@link #build}）与逐键写回（{@link #setArgument}）。写回发生在用户**正在打字**的那一刻，
     * 刚敲下一个 {@code (} 或一个 {@code "} 时串必然不配平，可那一格的内容与其它格的边界并没有歧义
     * （拆分只按顶层逗号切、从不要求整串配平）。**必须用 {@link SugarFunctions#splitArgsLenient}
     * 而不是 {@link SugarFunctions#splitArgs}**：严格版把未闭合的 {@code (} 读成"我后面全是嵌套"，
     * 于是 {@code f(, value} 会被读成整整一段、第二格的内容在下一次写回时**丢掉**。早先这里拿
     * {@link SugarFunctions#balancedArgs} 与"拆分可逆"两条严格判据把中间态一律否掉，后果分两层：
     * 写回侧是从敲下 {@code (} 那一刻起**每一次按键都被静默丢弃**（框里显示 {@code f(a, b)}、
     * 载体里还是旧的 {@code map, f(}）；建卡侧是**重开就塌成单框、且不可逆**——用户只是没写完就关了
     * 对话框，再打开就失去了分格编辑，得先把内容改配平才能拿回来。两条路径共用这一份实现，
     * 就不会再出现"一边放宽、另一边没跟上"的偏差。</p>
     *
     * <p><b>而且只在这里拆一次</b>：结果缓存进 {@link #slotCache}，之后每次编辑都直接改那一格
     * （见 {@link #setArgument}），不拿 {@link #arguments} 重新拆。理由是"拼回"与"拆分"
     * <b>不是互逆的</b>——某一格含顶层逗号时（{@code s} 与 {@code a,b} 拼成 {@code "s, a,b"}，
     * 再拆回来是 {@code [s, a, b]} 三段），逐键重拆会把用户已经敲进去的文字在格与格之间反复搬家
     * 并累加，串长按敲键次数增长。格子边界是界面给的，不是载体串给的。</p>
     *
     * <p>各情形的落法：① 未知运算（损坏载体/未来版本写入的名字）取不到参数名，只能退回单框；
     * ② 实参数**少于**参数量补空槽（清空末几个框就是这条路径），用户一眼看得出少填了哪个；
     * ③ 实参数**多于**参数量把多出来的段并进最后一格，内容不丢、位置也不搬家（拼接用的空白可能被
     * 规范化，编译结果不变）。</p>
     *
     * @return 长度恒等于 {@link DataModules#paletteParams} 的槽位列表（可安全修改，与缓存无关）；
     *         或 {@code null} 表示单框
     */
    public List<String> argumentSlots(){
        List<String> params = DataModules.paletteParams(canonicalOperation());
        if(params.isEmpty()) return null;
        int count = params.size();
        // 缓存只在"派生的源头没变、槽位数也没变"时才可信：换运算、载体重新赋值都会让它失效
        if(slotCache != null && slotCache.size() == count && slotCacheFrom != null
            && slotCacheFrom.equals(arguments)){
            return new ArrayList<>(slotCache);
        }

        // 用宽松拆分：用户敲到一半的 `(` / `"` 不配平，严格拆分会把其后的每一个逗号都当成嵌套内容
        // 而返回整整一段，于是第一格吞下全文、其余格空掉（= "变成一个输入框"）。见 splitArgsLenient。
        List<String> parts = SugarFunctions.splitArgsLenient(arguments);
        while(parts.size() < count) parts.add("");
        List<String> slots;
        if(parts.size() == count){
            slots = parts;
        }else{
            slots = new ArrayList<>(parts.subList(0, count - 1));
            StringBuilder overflow = new StringBuilder();
            for(int i = count - 1; i < parts.size(); i++){
                if(overflow.length() > 0) overflow.append(", ");
                overflow.append(parts.get(i));
            }
            slots.add(overflow.toString());
        }
        slotCache = slots;
        slotCacheFrom = arguments;
        return new ArrayList<>(slots);
    }

    /**
     * 实参输入区：**每个参数一个输入框**，参数名作灰色占位提示，逗号由卡片自己写。
     *
     * <p>运算的参数量是固定的——68 个有卡运算全部定参（1/2/3 参 = 34/27/7，无变参），
     * 参数名逐位就是 palette 默认串的每一段（见 {@link DataModules#paletteParams}）——所以
     * 让用户在一格里数逗号写 {@code "s, value"} 没有任何好处。几个框仍旧写回同一个
     * {@link #arguments} 字符串（逗号拼回），**载体格式与可执行流都不变**，旧存档照常读。</p>
     */
    private void argumentFields(Table table){
        List<String> slots = argumentSlots();
        if(slots == null){
            // 唯一退到单框的情形：运算名未知（损坏载体 / 未来版本写入的名字），取不到参数名。
            // 已有内容零丢失——原文原样交给单框，用户仍可整串编辑。
            table.add(new ExpressionEditor(arguments, "data, value", value -> arguments = value))
                .growX().minWidth(90f);
            return;
        }

        // 参数名只取决于运算（不取决于当前实参），所以与 slots 一一对应、长度必相等
        List<String> params = DataModules.paletteParams(canonicalOperation());
        for(int i = 0; i < slots.size(); i++){
            if(i > 0) table.add(",");
            final int index = i;
            table.add(new ExpressionEditor(slots.get(i), argumentLabel(params.get(i)),
                value -> setArgument(index, value)))
                .growX().minWidth(ARGUMENT_FIELD_MIN_WIDTH);
        }
    }

    /**
     * 把第 {@code index} 个实参槽设为 {@code value}，并重拼 {@link #arguments}。
     *
     * <p>这是定参输入框唯一的写回路径（{@link #build} 里每个框都调它），提到 public 是为了
     * 让自测能覆盖"编辑某一格"的语义——UI 需要 GL 建不起来，而"只动那一格、其余原样、
     * 逗号怎么拼"正是最容易出错的地方。槽位与建卡共用 {@link #argumentSlots()}：**同一份实现**
     * 是刻意的，早先那边用严格判据、这边用宽松判据，于是用户刚敲下 {@code (} 后的每一次按键
     * 都被静默丢弃（见 {@link #argumentSlots()} 的说明）；共用之后两边不可能再对中间态持不同口径。
     * 运算未知（取不到参数名）或下标越界时不做任何事。</p>
     *
     * <p>写回后把这份槽位留在 {@link #slotCache} 里，是"不逐键重拆"的落地点：本次写进最后一格的
     * 文字（可能含顶层逗号）在下一次按键时**不会**被重新当成分隔符搬走。见 {@link #slotCache}。</p>
     */
    public void setArgument(int index, String value){
        List<String> slots = argumentSlots();
        if(slots == null || index < 0 || index >= slots.size()) return;
        slots.set(index, value);
        arguments = joinArguments(slots);
        // 与刚拼出来的载体串对齐：下次取槽直接命中缓存，不再从串上重新猜格子边界
        slotCache = slots;
        slotCacheFrom = arguments;
    }

    /**
     * 按 {@code ", "} 拼回 {@link #arguments}，**丢掉尾部空槽**。
     *
     * <p>新卡片的默认实参串（如 {@code "s, value"}）本来就是逐位的参数名，所以正常编辑路径上
     * 每个槽都有内容；只有当用户把末几个槽清空时才会走到这里——那时不该存下尾逗号，
     * 否则载体里出现 {@code "s, "} 这种半截形态。中间的空槽必须保留逗号：位置是有意义的，
     * {@code array_swap(buf, , j)} 与 {@code array_swap(buf, j)} 不是一回事，前者应当如实
     * 报参数错误而不是被悄悄左移。</p>
     */
    private static String joinArguments(List<String> slots){
        int end = slots.size();
        while(end > 0 && slots.get(end - 1).trim().isEmpty()) end--;
        StringBuilder out = new StringBuilder();
        for(int i = 0; i < end; i++){
            if(i > 0) out.append(", ");
            out.append(slots.get(i).trim());
        }
        return out.toString();
    }

    /**
     * 卡内运算按钮（对照原版 {@code op} 卡的 {@code opButton}）：按钮显示当前运算的本地化短名，
     * 点开是本结构的运算网格。
     */
    private void operationButton(Table table){
        List<DataModule.PaletteCall> calls = groupCalls();
        if(calls.isEmpty()){
            // 未知运算（损坏载体、或未来版本写入的名字）：退化为旧行为，直接显示运算名文本
            table.add(canonicalOperation()).self(c -> hint(c, operationHintKey("operation")));
            return;
        }

        String label = operationLabel(canonicalOperation());
        // 按钮宽度按标签实测宽度给，不写死：本地化短名比原版的运算符长得多（「查看顶部」这类 4 字
        // 短名在 200% 缩放下会被省略成「查看顶…」）。
        float measured = designWidth(paddedWidth(Styles.logict.font, label, Styles.logict.up),
            BUTTON_MIN_WIDTH, BUTTON_MAX_WIDTH);
        final float width = measured <= 0f ? BUTTON_MIN_WIDTH : measured;
        table.button(b -> {
            b.add(label).self(c -> singleLine(c, width));
            b.clicked(() -> showOperationSelect(b, table));
        }, Styles.logict, () -> {
        }).size(width, 40f).pad(4f).color(table.color)
            .self(c -> hint(c, operationHintKey("operation")));
    }

    /**
     * 运算选择弹窗：网格列出本结构的全部运算（原版 {@code showSelect} 的网格外观，
     * 条目用本模组的本地化短名与原有逐运算悬停说明）。
     *
     * <p>{@code showSelect} 本身按 {@code toString()} 查 vanilla bundle，装不下模组的
     * 运算名，所以用 {@code showSelectTable} 自绘网格。列宽取组内最宽的短名（不再写死 118f：
     * 那会让长一点的短名互相压在一起）。</p>
     */
    private void showOperationSelect(Button button, Table card){
        final String current = canonicalOperation();
        float widest = 0f;
        for(DataModule.PaletteCall call : groupCalls()){
            widest = Math.max(widest, paddedWidth(Styles.logicTogglet.font, operationLabel(call.name), Styles.logicTogglet.up));
        }
        float cellWidth = designWidth(widest, ENTRY_MIN_WIDTH, ENTRY_MAX_WIDTH);
        if(cellWidth <= 0f) cellWidth = ENTRY_MIN_WIDTH;
        final float entryWidth = cellWidth;

        showSelectTable(button, (t, hide) -> {
            t.defaults().size(entryWidth, 38f);
            ButtonGroup<Button> group = new ButtonGroup<>();
            int i = 0;
            for(DataModule.PaletteCall call : groupCalls()){
                String name = call.name;
                boolean selected = name.equalsIgnoreCase(current);
                t.button(operationLabel(name), Styles.logicTogglet, () -> {
                    hide.run();
                    if(!selected){
                        selectOperation(call);
                        build(card);
                    }
                }).self(c -> {
                    singleLine(c, entryWidth);
                    hint(c, operationLabelKey(name));
                }).checked(selected).group(group);

                if(++i % 3 == 0) t.row();
            }
        });
    }

    /**
     * 文本 + 样式背景左右内边距的**像素**宽度；字体未加载（无头环境）时返回 0。
     *
     * <p>背景的 ninepatch 内边距不随 UI 缩放变化，而字形会随缩放变大——这正是同一张卡在
     * 100% 缩放正常、200% 缩放下「result」被切掉最后一格的原因，所以必须按真实背景内边距留宽。</p>
     */
    private static float paddedWidth(Font font, String text, Drawable background){
        if(font == null || text == null || text.isEmpty()) return 0f;
        try{
            GlyphLayout layout = new GlyphLayout();
            layout.setText(font, text);
            if(layout.width <= 0f) return 0f;
            float padding = background == null ? 0f : background.getLeftWidth() + background.getRightWidth();
            return layout.width + padding;
        }catch(Throwable ignored){
            return 0f;
        }
    }

    /**
     * 像素宽度 → {@code Cell} 要的 design 单位，夹在 [min, max]。
     *
     * <p>{@code Cell.width()}（以及 pad/size）内部会 {@code Scl.scl()} 一次，而 {@code GlyphLayout}
     * 与背景内边距都是像素，所以这里先除回 design——原版 UI 也是这个写法
     * （见 {@code UI.java} 的 {@code getPrefHeight() / Scl.scl()}）。漏掉这一步在 200% 缩放下
     * 会得到双倍宽度的控件。</p>
     *
     * @return 目标宽度；字形或缩放不可用时返回 0，调用方退回固定宽度
     */
    private static float designWidth(float pixelWidth, float min, float max){
        if(pixelWidth <= 0f) return 0f;
        try{
            float scale = Scl.scl(1f);
            return clamp(pixelWidth / (scale <= 0f ? 1f : scale) + WIDTH_SLACK, min, max);
        }catch(Throwable ignored){
            return 0f;
        }
    }

    private static float clamp(float value, float min, float max){
        return value < min ? min : Math.min(value, max);
    }

    /**
     * 单元格里的标签收成单行省略号。
     *
     * <p>arc 的标签不会自己裁切：译文偏长（或关掉卡片本地化后是 {@code vector_push_back}
     * 这类 STL 名）时文字会越过按钮边界压到旁边的条目上，看上去像"字叠在一起/显示不全"。
     * 把标签格的最小宽度归零，标签才会缩到按钮宽度并按 {@code ellipsis} 截断——与加号菜单
     * 的 {@code SugarLogicDialog.configurePaletteButton} 同一套处理。</p>
     */
    private static void singleLine(Cell<?> cell, float maxWidth){
        Element target = cell.get();
        if(target instanceof TextButton button){
            button.getLabel().setWrap(false);
            button.getLabel().setEllipsis(true);
            button.getLabelCell().minWidth(0f);
            button.getLabelCell().maxWidth(maxWidth);
        }else if(target instanceof Label label){
            label.setWrap(false);
            label.setEllipsis(true);
            cell.minWidth(0f).maxWidth(maxWidth);
        }
    }

    private static String cardText(String key, String fallback){
        return SugarStatements.cardText(key, fallback);
    }

    /** 运算短名（如 {@code stack_push} → 压入）的 bundle 键：{@code logicsugar.datacall.<op>}。 */
    public static String operationLabelKey(String operation){
        return "datacall." + (operation == null ? "" : operation.toLowerCase(Locale.ROOT));
    }

    /**
     * 卡片正文用的运算短名：按 {@code logicsugar.datacall.<op>} 取本地化名（v5.2 改名后
     * 短名与 STL 名分属两套键，用 {@code logicsugar.<op>} 会查空，卡片只能显示英文 STL 名）。
     * 未翻译时回退到运算名本身。完整的函数签名仍在悬停提示（{@code logicsugar.hint.datacall.<op>}）里。
     */
    private static String operationLabel(String operation){
        if(operation == null) return "";
        return cardText(operationLabelKey(operation), operation);
    }

    /** 实参名的 bundle 键：{@code logicsugar.datacall.arg.<name>}。 */
    public static String argumentLabelKey(String parameter){
        return "datacall.arg." + (parameter == null ? "" : parameter.toLowerCase(Locale.ROOT));
    }

    /**
     * 实参输入框的灰色占位：参数名的本地化名（{@code value} → 「值」，{@code s} → 「栈」）。
     *
     * <p>名字域只有 19 个词，且默认实参串本身就是这些名字，所以用户看到的永远是「这个槽该填
     * 什么」而不是位置编号。未翻译时回退参数名本身——那时卡片上就是英文 STL 风格的参数名，
     * 与关掉本地化时运算名显示英文 STL 名是同一套约定。</p>
     */
    private static String argumentLabel(String parameter){
        if(parameter == null) return "";
        return cardText(argumentLabelKey(parameter), parameter);
    }

    /** 分组标签（加号菜单里的卡片名）：复用既有的 {@code logicsugar.category.<组>} 文案。 */
    private String groupLabel(String groupKey){
        return cardText("category." + groupKey, categoryFallback(groupKey));
    }

    /** 关闭卡片本地化时的英文分组名（{@code name()} 的 fallback）。 */
    private static String categoryFallback(String groupKey){
        switch(groupKey){
            case "stackops": return "Stack ops";
            case "queueops": return "Queue ops";
            case "dequeops": return "Deque ops";
            case "listops": return "List ops";
            case "heapops": return "Heap ops";
            case "mapops": return "Map ops";
            case "setops": return "Set ops";
            case "chainops": return "Chain ops";
            case "bitsetops": return "Bitset ops";
            case "arrayalgo": return "Array ops";
            default: return groupKey;
        }
    }

    @Override
    public String name(){
        // 一张卡代表一个结构：加号菜单显示结构名（各组运算在卡内按钮里选），
        // 因此 fallback 也必须是结构名而不是某个具体运算。
        String group = groupKey();
        if(group == null) return cardText(canonicalOperation(), canonicalOperation());
        return groupLabel(group);
    }

    @Override public String typeName(){
        String group = groupKey();
        // 加号菜单按 typeName() 取提示键（logicsugar.lst.<typeName>）：一张卡对应整个结构，
        // 因此提示描述的是这个结构能做的运算；未知运算仍退回逐运算键。
        if(group != null) return TOKEN + ".group." + group.toLowerCase(Locale.ROOT);
        String op = canonicalOperation();
        if(op == null || op.isEmpty()) return TOKEN;
        return TOKEN + "." + op.toLowerCase(Locale.ROOT);
    }

    /**
     * 加号菜单搜索的额外关键词：结构与卡内每个运算的名字/本地化短名。
     *
     * <p>一张卡覆盖多个运算后，只按卡片名（结构名）匹配会让「搜 vector_push_back」
     * 找不到列表卡，所以把组内全部运算的词条交给搜索框。</p>
     */
    @Override
    public String searchTerms(){
        StringBuilder out = new StringBuilder();
        for(DataModule.PaletteCall call : groupCalls()){
            if(call.name == null) continue;
            if(out.length() > 0) out.append(' ');
            // STL 名与本地化短名都要能搜到：搜 vector_push_back 或「追加」都应命中列表卡
            out.append(call.name.toLowerCase(Locale.ROOT)).append(' ').append(operationLabel(call.name));
        }
        return out.length() == 0 ? null : out.toString();
    }

    @Override
    public LCategory category(){
        // 与声明卡同栏：数组/矩阵的声明卡在「数组算法」，所以数组运算卡也去那里；其余结构的
        // 声明卡在「数据结构」，运算卡跟着留下。规则与理由见 DataModules.paletteColumn。
        return DataModules.paletteColumn(groupKey());
    }

    @Override
    public void write(StringBuilder out){
        out.append(TOKEN).append(' ').append(optional(canonicalOperation())).append(' ')
            .append(optional(destination)).append(' ').append('"')
            .append(SugarStatements.escapeQuoted(arguments == null ? "" : arguments))
            .append('"');
    }

    private static String optional(String value){
        return value == null || value.isEmpty() ? "~" : value;
    }

    private String operationHintKey(String field){
        String op = canonicalOperation();
        op = op == null ? "" : op.toLowerCase(Locale.ROOT);
        return DataModules.paletteCall(op) == null ? "datacall." + field : "datacall." + op;
    }

    @Override
    public LStatement copy(){
        DataCallStatement result = new DataCallStatement();
        result.operation = operation;
        result.destination = destination;
        result.arguments = arguments;
        // 槽位跟着走：撤销/重做与剪贴板都走 copy()，粘贴回来的卡片必须还是用户离开时那副分格样子
        // （某一格含顶层逗号时，只有这份布局能让它原样重现——从串上重拆必被重新分配）
        result.slotCache = slotCache == null ? null : new ArrayList<>(slotCache);
        result.slotCacheFrom = slotCacheFrom;
        result.palette = palette;
        return result;
    }

    public static LStatement parse(String[] tokens){
        DataCallStatement result = new DataCallStatement();
        // 旧名（v5.1 及更早的载体）在解析时归一化成规范新名：卡片正文、悬停提示键与下一次
        // 保存写出的载体都只出现新名，可执行流不变（见 DataModules.canonicalOperation）。
        result.operation = DataModules.canonicalOperation(optionalValue(tokens, 1));
        result.destination = optionalValue(tokens, 2);
        result.arguments = unquote(tokens, 3);
        DataModule.PaletteCall palette = DataModules.paletteCall(result.operation);
        result.palette = palette;
        if(result.operation.isEmpty()) throw new IllegalArgumentException("Invalid datacall: missing operation");
        return result;
    }

    private static String optionalValue(String[] tokens, int index){
        if(tokens == null || index >= tokens.length || tokens[index] == null || "~".equals(tokens[index])) return "";
        return tokens[index];
    }

    private static String unquote(String[] tokens, int index){
        String value = optionalValue(tokens, index);
        if(value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"'){
            value = value.substring(1, value.length() - 1);
        }
        return SugarStatements.unescapeQuoted(value);
    }
}
