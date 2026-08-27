package logicsugar.assist;

/** Pure gesture thresholds shared by the block drag state machine and its self-test. */
final class BoxSelectDragPolicy{
    static final float SLOP = 8f;
    static final long LONG_PRESS_NANOS = 430_000_000L;

    private BoxSelectDragPolicy(){
    }

    static boolean singleDragReady(long elapsedNanos, float dx, float dy, boolean requireLongPress){
        if(requireLongPress && elapsedNanos < LONG_PRESS_NANOS) return false;
        return dx * dx + dy * dy >= SLOP * SLOP;
    }

    static boolean moved(float dx, float dy){
        return dx * dx + dy * dy >= SLOP * SLOP;
    }
}
