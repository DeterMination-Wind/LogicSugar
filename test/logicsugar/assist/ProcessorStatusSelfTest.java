package logicsugar.assist;

public class ProcessorStatusSelfTest{
    public static void main(String[] args){
        waitThresholdIsInclusive();
        waitThresholdZeroDisables();
        budgetScalesWithFrameTimeAndCapsOnLowFps();
        updatesPerTickMapping();
        legacyScanValueMigration();
        System.out.println("LogicSugar ProcessorStatus self-test passed.");
    }

    private static void waitThresholdIsInclusive(){
        ProcessorStatus.minWaitMillis = 1000;
        check(!ProcessorStatus.isLongWait(0.999), "wait below the threshold was indicated");
        check(ProcessorStatus.isLongWait(1.0), "wait exactly at the threshold was not indicated (>= is load-bearing)");
        check(ProcessorStatus.isLongWait(5.0), "long wait was not indicated");
    }

    private static void waitThresholdZeroDisables(){
        ProcessorStatus.minWaitMillis = 0;
        check(!ProcessorStatus.isLongWait(60.0), "wait indication was not disabled by threshold 0");
        ProcessorStatus.minWaitMillis = 1000;
    }

    private static void budgetScalesWithFrameTimeAndCapsOnLowFps(){
        // 60 FPS: delta * 60 == 1, so one frame delivers exactly perTick scans
        check(ProcessorStatus.advanceBudget(0, 1.0 / 60, 50) == 50,
            "60 FPS frame did not deliver exactly perTick scans: " + ProcessorStatus.advanceBudget(0, 1.0 / 60, 50));

        // 240 FPS: the per-frame budget is fractional (12.5 at perTick=50) and must not
        // lose scans to truncation; 240 frames deliver 3000 in a 12/13 alternation
        double budget = 0;
        long delivered = 0;
        for(int frame = 0; frame < 240; frame++){
            budget = ProcessorStatus.advanceBudget(budget, 1.0 / 240, 50);
            long updates = (long)budget;
            budget -= updates;
            delivered += updates;
        }
        check(delivered == 3000L, "fractional scan budget deviated from 3000 over 240 frames: " + delivered);
        check(budget == 0, "budget leaked a remainder after whole runs: " + budget);

        // low FPS: the per-frame advance is capped at 5x perTick so a stutter cannot
        // force a full-map scan burst on top of the lag
        check(ProcessorStatus.advanceBudget(0, 0.5, 10) == 50,
            "low FPS frame was not capped at 5x perTick: " + ProcessorStatus.advanceBudget(0, 0.5, 10));
    }

    private static void updatesPerTickMapping(){
        check(ProcessorStatus.updatesPerTick(0) == 1, "index 0 must map to 1");
        check(ProcessorStatus.updatesPerTick(4) == 50, "default index 4 must map to 50");
        check(ProcessorStatus.updatesPerTick(ProcessorStatus.UPDATES_PER_TICK.length - 1) == 5000,
            "last step must map to 5000");
        // out-of-range indices defensively fall back to the closest lower step
        check(ProcessorStatus.updatesPerTick(50) == 50, "index 50 must fall back to the 50 step");
        check(ProcessorStatus.updatesPerTick(15) == 10, "index 15 must fall back to the 10 step");
        check(ProcessorStatus.updatesPerTick(-1) == 1, "negative index must fall back to the smallest step");
    }

    /** Pre-v0.8.2 builds stored the raw per-frame scan count under the slider key; the
     *  migration must map those to the closest step, not read them as indices. */
    private static void legacyScanValueMigration(){
        check(ProcessorStatus.closestStep(5) == 1, "raw 5 must migrate to step 1 (5/frame)");
        check(ProcessorStatus.closestStep(10) == 2, "raw 10 must migrate to step 2 (10/frame)");
        check(ProcessorStatus.closestStep(50) == 4, "raw 50 must migrate to step 4 (50/frame)");
        check(ProcessorStatus.closestStep(200) == 6, "raw 200 must migrate to the closest step (250/frame)");
        check(ProcessorStatus.closestStep(5000) == ProcessorStatus.UPDATES_PER_TICK.length - 1,
            "raw 5000 must migrate to the last step");
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
