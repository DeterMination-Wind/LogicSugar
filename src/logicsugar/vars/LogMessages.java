package logicsugar.vars;

import arc.Core;
import arc.Events;
import arc.struct.ObjectMap;
import arc.struct.Queue;
import arc.util.Log;
import arc.util.Nullable;
import mindustry.game.EventType;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;

/**
 * 处理器自带的内存日志（上游 v0.12.0 的 {@code data.LogMessages}）。
 *
 * <p>{@code log} 指令不再写游戏日志文件，而是把消息存进**创建它的处理器**：每个处理器一份
 * 定长队列，按帧记录级别、分类与文本，由 {@code LogDialog} 展示。写游戏日志文件的能力
 * 保留在 {@code systemlog} 指令上——上游把旧的 {@code log} 重命名成它，否则新 {@code log}
 * 会与存量程序的含义冲突。</p>
 *
 * <p>只存在于客户端内存，不改变保存产物；关图（{@code ResetEvent}）或拆掉处理器时丢弃。
 * 因此和快照一样不受「多人必须兼容原版」的单机门禁约束：{@code log} 指令本身仍只在
 * {@code emit} 调试构建里落进程序文本，普通保存只有载体里的卡片。</p>
 *
 * <p>与上游的差异只有两处，都是集成约定：设置键用本 mod 的 {@code logicsugar.logMessageLimit}
 * / {@code logicsugar.logClearOnRestart}（不是 {@code mlogdevtools-*}）；存储方法接受任意
 * 键与指令数组，使 {@code varsTest} 能在没有活 {@code LogicBuild} 的无头环境里验证
 * 「重新编译即清空 / 上限裁剪 / 关闭即不记录」这套状态机。</p>
 */
public final class LogMessages{
    /** 设置键：滑块存的是 {@link #LIMITS} 的下标。 */
    public static final String settingLimit = "logicsugar.logMessageLimit";
    /** 设置键：处理器重启/重新编译时是否清空日志。 */
    public static final String settingClearOnRestart = "logicsugar.logClearOnRestart";

    /** 可选条数上限（滑块档位），与上游一致。0 = 完全关闭。 */
    public static final int[] LIMITS = {0, 20, 50, 100, 200, 500, 1000, 2000, 5000};
    /** 默认档位下标：100 条（上游默认值）。 */
    public static final int defaultLimitIndex = 3;

    /** 每个处理器保留的消息条数；0 = 关闭日志记录。 */
    public static int maxMessages = LIMITS[defaultLimitIndex];
    /** 重新编译（{@code executor.instructions} 换了一份数组）时是否清空旧消息。 */
    public static boolean clearOnRestart = true;

    private static final ObjectMap<Object, LoggingData> loggingData = new ObjectMap<>();
    private static boolean initialized;

    private LogMessages(){
    }

    /** 注册关图/拆方块清理（幂等）。由 {@code LogicSugarMod.installVars()} 调用。 */
    public static void init(){
        if(initialized) return;
        initialized = true;

        Events.on(EventType.ResetEvent.class, e -> loggingData.clear());
        Events.on(EventType.BlockBuildEndEvent.class, e -> {
            // 上游 v0.12.0：任何被拆掉的方块都清（快照表可能因 connected/global 快照
            // 收藏过非处理器方块，日志表则只可能是处理器，但共用同一守卫没有坏处）
            if(e.breaking && e.tile.build != null) loggingData.remove(e.tile.build);
        });
    }

    /** 从设置读回上限与「重启清空」开关（设置页未打开时也要生效）。 */
    public static void applySettings(){
        if(Core.settings == null) return;
        int index = Core.settings.getInt(settingLimit, defaultLimitIndex);
        setLimit(LIMITS[Math.max(0, Math.min(LIMITS.length - 1, index))]);
        clearOnRestart = Core.settings.getBool(settingClearOnRestart, true);
    }

    /** 改上限并立即裁剪已有队列；0 直接清空全部数据。 */
    public static void setLimit(int limit){
        maxMessages = Math.max(0, limit);
        if(maxMessages == 0){
            loggingData.clear();
        }else{
            loggingData.each((key, data) -> {
                while(data.messages.size > maxMessages) data.messages.removeFirst();
            });
        }
    }

    /** 记录一条消息。{@code category} 为空串时展示侧按「无分类」处理。 */
    public static void addMessage(LogicBuild build, Log.LogLevel level, @Nullable String category, String message){
        addMessageTo(build, instructionsOf(build), level, category, message);
    }

    /** 清空某个处理器的消息（日志对话框的「清空」）。 */
    public static void clear(LogicBuild build){
        clearTo(build, instructionsOf(build));
    }

    /** 当前消息快照；没有记录时返回空数组。 */
    public static LogMessage[] getMessages(LogicBuild build){
        return messagesTo(build, instructionsOf(build));
    }

    static void clearTo(Object key, Object instructions){
        Queue<LogMessage> messages = messagesOf(key, instructions, false);
        if(messages != null) messages.clear();
    }

    static LogMessage[] messagesTo(Object key, Object instructions){
        Queue<LogMessage> messages = messagesOf(key, instructions, false);
        return messages == null ? new LogMessage[0] : messages.toArray(LogMessage.class);
    }

    // ===== 无头可测的核心（键与指令数组用 Object 表示） =====

    static void addMessageTo(Object key, Object instructions, Log.LogLevel level, @Nullable String category, String message){
        if(maxMessages <= 0) return;

        Queue<LogMessage> messages = messagesOf(key, instructions, true);
        messages.add(new LogMessage(frameId(), level, category == null ? "" : category, message));
        while(messages.size > maxMessages) messages.removeFirst();
    }

    static @Nullable Queue<LogMessage> messagesOf(Object key, Object instructions, boolean create){
        LoggingData data = loggingData.get(key);
        // 上游口径：没有数据、或者指令数组换了一份（重新编译）且开着「重启清空」时重建。
        // 关掉设置时旧消息跨重新编译保留。
        if(data == null || (clearOnRestart && data.instructions != instructions)){
            if(!create){
                if(data != null) loggingData.remove(key);
                return null;
            }
            data = new LoggingData(new Queue<>(), instructions);
            loggingData.put(key, data);
        }
        return data.messages;
    }

    static Object instructionsOf(LogicBuild build){
        return build == null || build.executor == null ? null : build.executor.instructions;
    }

    /** 帧号：无头自检里 {@code Core.graphics} 不存在，返回 0。 */
    static long frameId(){
        try{
            return Core.graphics == null ? 0L : Core.graphics.getFrameId();
        }catch(Throwable t){
            return 0L;
        }
    }

    /** 一条日志消息。字段只读：展示层按插入顺序读，重排只发生在复制/导出时。 */
    public static class LogMessage{
        public final long frameId;
        public final Log.LogLevel level;
        public final String category;
        public final String message;

        LogMessage(long frameId, Log.LogLevel level, String category, String message){
            this.frameId = frameId;
            this.level = level;
            this.category = category;
            this.message = message;
        }
    }

    private static class LoggingData{
        final Queue<LogMessage> messages;
        /** 记录创建时处理器的指令数组，用来识别重新编译。 */
        final Object instructions;

        LoggingData(Queue<LogMessage> messages, Object instructions){
            this.messages = messages;
            this.instructions = instructions;
        }
    }
}
