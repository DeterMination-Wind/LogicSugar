package logicsugar.assist;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 跳转线的轨道分配（区间着色）。
 *
 * <p>原版 {@code LCanvas.JumpCurve} 的横向距离不是固定值，而是
 * {@code Scl.scl(40) + Scl.scl(10) * predHeight}：互相重叠的跳转线被分到不同轨道，才不会挤在
 * 同一条竖线上互相穿插。轨道号由 {@code LCanvas.StatementsTable.setJumpHeights} /
 * {@code getJumpHeight} 算出 —— 那是一段递归的区间着色，本类把它原样搬出来（含"代表元"合并：
 * 区间完全相同的线共用一层，它们画出来本来就重合），这样两侧镜像的跳转线（原版右侧的
 * {@code jump} 线、{@code @counter} 指示线在左侧）用的是同一套规则，而且这段纯逻辑可以无头测试
 * （几何本身没法测，"谁和谁不能同轨"可以）。</p>
 *
 * <p>规则复述（镜像原版）：按区间起点排序，逐条挑一条既没被"仍然覆盖当前位置的线"占用、又位于
 * 自己内部嵌套线之上的轨道；嵌套线因此比它外面的线更贴近积木。</p>
 */
public final class JumpLanes{
    private JumpLanes(){}

    /**
     * 为一组线分配轨道。
     *
     * @param begin   每条线覆盖的语句区间起点（含）
     * @param end     同一条线的区间终点（含）；三个数组等长
     * @param flipped 目标在源卡片下方（原版同名字段；只决定合并时按区间的哪一端归类）
     * @return 与输入等长的轨道号数组，0 表示最贴近积木；区间重叠的线保证不同轨
     */
    public static int[] assign(int[] begin, int[] end, boolean[] flipped){
        int count = begin.length;
        int[] lanes = new int[count];
        if(count == 0) return lanes;

        // 代表元合并：区间相同的线共用一层，只有"按方向看更长"的那条参与计算。
        int[] representative = new int[count];
        Arrays.fill(representative, -1);
        Map<Integer, Integer> reprBefore = new HashMap<>(); // 非 flipped：按区间终点归类
        Map<Integer, Integer> reprAfter = new HashMap<>();  // flipped：按区间起点归类
        List<Integer> order = new ArrayList<>();
        for(int i = 0; i < count; i++){
            if(flipped[i]){
                Integer previous = reprAfter.get(begin[i]);
                if(previous != null && end[previous] >= end[i]){
                    representative[i] = previous;
                    continue;
                }
                reprAfter.put(begin[i], i);
            }else{
                Integer previous = reprBefore.get(end[i]);
                if(previous != null && begin[previous] <= begin[i]){
                    representative[i] = previous;
                    continue;
                }
                reprBefore.put(end[i], i);
            }
            order.add(i);
        }
        order.sort(Comparator.comparingInt((Integer i) -> begin[i]).thenComparingInt(i -> i));

        boolean[] fixed = new boolean[count];
        List<Integer> occupiers = new ArrayList<>();
        BitSet occupied = new BitSet();
        for(int i = 0; i < order.size(); i++){
            int current = order.get(i);
            dropExpired(occupiers, occupied, lanes, end, begin[current]);
            int lane = laneHeight(order, begin, end, lanes, fixed, occupiers, occupied, i);
            lanes[current] = lane;
            occupiers.add(current);
            occupied.set(lane);
        }

        // 代表元之外的线沿用代表元的层号（原版同样如此）。
        for(int i = 0; i < count; i++){
            if(representative[i] >= 0) lanes[i] = lanes[representative[i]];
        }
        return lanes;
    }

    /** 原版 {@code JumpCurve.getJumpHeight}：自内向外找一条既空闲、又在内部嵌套线之上的轨道。 */
    private static int laneHeight(List<Integer> order, int[] begin, int[] end, int[] lanes, boolean[] fixed,
                                  List<Integer> occupiers, BitSet occupied, int index){
        int jmp = order.get(index);
        if(fixed[jmp]) return lanes[jmp];

        List<Integer> tmpOccupiers = new ArrayList<>(occupiers);
        BitSet tmpOccupied = (BitSet)occupied.clone();

        int max = -1;
        for(int i = index + 1; i < order.size(); i++){
            int nested = order.get(i);
            if(end[nested] > end[jmp]) continue;
            dropExpired(tmpOccupiers, tmpOccupied, lanes, end, begin[nested]);
            int lane = laneHeight(order, begin, end, lanes, fixed, tmpOccupiers, tmpOccupied, i);
            tmpOccupiers.add(nested);
            tmpOccupied.set(lane);
            max = Math.max(max, lane);
        }

        lanes[jmp] = occupied.nextClearBit(max + 1);
        fixed[jmp] = true;
        return lanes[jmp];
    }

    /** 丢掉已经结束（{@code end <= from}）的占用者并释放它的轨道号。 */
    private static void dropExpired(List<Integer> occupiers, BitSet occupied, int[] lanes, int[] end, int from){
        for(int i = occupiers.size() - 1; i >= 0; i--){
            int entry = occupiers.get(i);
            if(end[entry] > from) continue;
            occupied.clear(lanes[entry]);
            occupiers.remove(i);
        }
    }
}
