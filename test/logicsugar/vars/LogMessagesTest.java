package logicsugar.vars;

import arc.util.Log.LogLevel;
import logicsugar.vars.LogMessages.LogMessage;

/**
 * {@link LogMessages} 的无界面自检：处理器自带日志的存储规则（上游 v0.12.0）。
 *
 * <p>真正的 {@code LogMessage} 由 {@code log} 指令写、由 {@code LogDialog} 读；两者都需要活的
 * {@code LogicBuild}/{@code Core.scene}，无头环境构造不出来。所以这里只验证数据层的状态机：
 * 上限裁剪（含把上限调小/调到 0）、重新编译（指令数组换了一份）时清空、关闭「重启清空」时
 * 保留旧消息、分类与级别字段。生产代码把这些规则放在接受任意键的包内方法上，测试直接用普通
 * {@code Object} 当处理器与指令数组，不伪造游戏对象。</p>
 */
public final class LogMessagesTest{
    private LogMessagesTest(){
    }

    public static void main(String[] args){
        limitTrimsOldestMessages();
        zeroLimitRecordsNothing();
        loweringTheLimitTrimsLiveQueues();
        recompileClearsOnlyWhenEnabled();
        clearKeepsTheStoreUsable();
        categoryAndLevelAreStored();

        System.out.println("LogicSugar log messages self-test passed.");
    }

    /** 满上限后每加一条丢最旧的一条，顺序是插入顺序。 */
    private static void limitTrimsOldestMessages(){
        Object build = new Object();
        Object instructions = new Object();
        LogMessages.setLimit(3);

        for(int i = 1; i <= 5; i++){
            LogMessages.addMessageTo(build, instructions, LogLevel.info, "", "m" + i);
        }

        LogMessage[] kept = messages(build, instructions);
        checkEquals(3, kept.length, "上限 3 时只保留最近 3 条");
        checkEquals("m3", kept[0].message, "最旧的一条是第 3 条");
        checkEquals("m5", kept[kept.length - 1].message, "最新的一条是第 5 条");
    }

    /** 0 = 关闭：不记录，也不保留旧数据。 */
    private static void zeroLimitRecordsNothing(){
        Object build = new Object();
        Object instructions = new Object();
        LogMessages.setLimit(4);
        LogMessages.addMessageTo(build, instructions, LogLevel.info, "", "before");

        LogMessages.setLimit(0);
        LogMessages.addMessageTo(build, instructions, LogLevel.info, "", "after");

        checkEquals(0, messages(build, instructions).length, "上限 0 时 setLimit 清空旧数据，之后也不再记录");
    }

    /** 运行中把上限调小，已有队列立即裁剪（不等下一条消息）。 */
    private static void loweringTheLimitTrimsLiveQueues(){
        Object build = new Object();
        Object instructions = new Object();
        LogMessages.setLimit(5);
        for(int i = 1; i <= 5; i++){
            LogMessages.addMessageTo(build, instructions, LogLevel.info, "", "m" + i);
        }

        LogMessages.setLimit(2);

        LogMessage[] kept = messages(build, instructions);
        checkEquals(2, kept.length, "调小上限后立即裁剪到新上限");
        checkEquals("m4", kept[0].message, "裁剪保留的是最近的条目");
        checkEquals("m5", kept[1].message, "裁剪保留的是最近的条目");
    }

    /** 指令数组换了一份（代码重新编译）时，默认清空；关掉「重启清空」则跨编译保留。 */
    private static void recompileClearsOnlyWhenEnabled(){
        Object build = new Object();
        Object oldInstructions = new Object();
        Object newInstructions = new Object();
        LogMessages.setLimit(10);
        LogMessages.clearOnRestart = true;

        LogMessages.addMessageTo(build, oldInstructions, LogLevel.info, "", "old");
        LogMessages.addMessageTo(build, newInstructions, LogLevel.info, "", "new");

        LogMessage[] restarted = messages(build, newInstructions);
        checkEquals(1, restarted.length, "重新编译后旧消息必须被丢弃");
        checkEquals("new", restarted[0].message, "只留下重新编译后的消息");

        LogMessages.clearOnRestart = false;
        Object thirdInstructions = new Object();
        LogMessages.addMessageTo(build, thirdInstructions, LogLevel.info, "", "kept");
        LogMessage[] kept = messages(build, thirdInstructions);
        checkEquals(2, kept.length, "关闭「重启清空」后旧消息跨编译保留");
        checkEquals("new", kept[0].message, "旧消息仍在");
        checkEquals("kept", kept[1].message, "新消息追加在后面");
        LogMessages.clearOnRestart = true;
    }

    /** clear 只清消息，不破坏后续记录。 */
    private static void clearKeepsTheStoreUsable(){
        Object build = new Object();
        Object instructions = new Object();
        LogMessages.setLimit(10);
        LogMessages.addMessageTo(build, instructions, LogLevel.warn, "c", "first");

        LogMessages.clearTo(build, instructions);
        checkEquals(0, messages(build, instructions).length, "clear 后没有消息");

        LogMessages.addMessageTo(build, instructions, LogLevel.debug, "c", "second");
        LogMessage[] after = messages(build, instructions);
        checkEquals(1, after.length, "clear 后仍能继续记录");
        checkEquals("second", after[0].message, "继续记录的是新消息");
    }

    /** 级别、分类与文本原样保存；null 分类归一为空串。 */
    private static void categoryAndLevelAreStored(){
        Object build = new Object();
        Object instructions = new Object();
        LogMessages.setLimit(10);

        LogMessages.addMessageTo(build, instructions, LogLevel.err, "phase", "boom");
        LogMessages.addMessageTo(build, instructions, LogLevel.info, null, "plain");

        LogMessage[] kept = messages(build, instructions);
        checkEquals(2, kept.length, "两条消息都要在");
        checkEquals(LogLevel.err, kept[0].level, "级别原样保存");
        checkEquals("phase", kept[0].category, "分类原样保存");
        checkEquals("boom", kept[0].message, "文本原样保存");
        checkEquals("", kept[1].category, "null 分类归一为空串（对话框按无分类展示）");
    }

    private static LogMessage[] messages(Object build, Object instructions){
        return LogMessages.messagesTo(build, instructions);
    }

    private static void checkEquals(Object expected, Object actual, String message){
        if(expected == null ? actual != null : !expected.equals(actual)){
            throw new AssertionError(message + " (expected " + expected + ", got " + actual + ")");
        }
    }
}
