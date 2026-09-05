package logicsugar.assist;

public class ProcessorStatusSelfTest{
    public static void main(String[] args){
        waitThresholdIsInclusive();
        waitThresholdZeroDisables();
        budgetPreservesFractionsAtHighFps();
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

    private static void budgetPreservesFractionsAtHighFps(){
        // delta is clamped from below at 1.5, so every frame delivers at least
        // 1.5 * perTick scans regardless of FPS (the floor dominates on normal frames)
        double budget = 0;
        long delivered = 0;
        for(int frame = 0; frame < 240; frame++){
            budget = ProcessorStatus.advanceBudget(budget, 1.0 / 240, 50);
            long updates = (long)budget;
            budget -= updates;
            delivered += updates;
        }
        check(delivered == 240L * 75, "per-frame scan floor deviated from 1.5 * perTick: " + delivered);
        check(budget == 0, "budget leaked a remainder after whole runs: " + budget);

        // a fractional per-frame increment must not lose scans to truncation:
        // 1.5 * 5 = 7.5 per frame, so two frames deliver exactly 15 (7 then 8)
        budget = 0;
        long first = (long)(budget = ProcessorStatus.advanceBudget(budget, 1.5, 5));
        budget -= first;
        long second = (long)(budget = ProcessorStatus.advanceBudget(budget, 1.5, 5));
        budget -= second;
        check(first + second == 15 && budget == 0,
            "fractional scan budget lost updates: " + first + "+" + second + ", remainder " + budget);
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
