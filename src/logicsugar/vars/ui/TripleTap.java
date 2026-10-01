package logicsugar.vars.ui;

import mindustry.gen.Building;

/**
 * 三击检测：同一方块在时间窗内连点三次即触发（上游 MlogAssertions 用三击打开任意方块的
 * 属性界面，窗口可设，0 = 关闭）。
 *
 * <p>纯状态机，不碰 UI/时钟，便于无头自测：调用方传入 {@code now} 与窗口宽度。上游那份把
 * 三次点击的两个时间戳分开维护（{@code timeTapped1/2}），行为等价但分支更多；这里按
 * 「同一方块 + 窗口内连续点击」计数，触发后立刻复位，避免第四次点击又开一次界面。</p>
 */
public final class TripleTap{
    private Building last;
    private long lastMillis;
    private int count;

    /**
     * 记一次点击。
     *
     * @param build 被点击的方块（null 表示点空，会复位）
     * @param now   当前毫秒时间戳
     * @param window 三次点击的最大间隔（毫秒）；{@code <= 0} 表示关闭该功能
     * @return true 表示本次点击凑满三击，调用方应打开界面
     */
    public boolean tap(Building build, long now, int window){
        if(window <= 0 || build == null){
            reset();
            return false;
        }
        if(build != last || now - lastMillis > window || now < lastMillis){
            last = build;
            lastMillis = now;
            count = 1;
            return false;
        }

        lastMillis = now;
        if(++count < 3) return false;

        reset();
        return true;
    }

    public void reset(){
        last = null;
        lastMillis = 0;
        count = 0;
    }
}
