package logicsugar.assist;

/** Pure gesture thresholds shared by the block drag state machine and its self-test. */
final class BoxSelectDragPolicy{
    static final float SLOP = 8f;
    static final long LONG_PRESS_NANOS = 430_000_000L;
    /** Touch movement that starts a drag without waiting for the long press. Above the slop
     *  (a real swipe, not hand tremor), below a single card's height so one card of travel is
     *  enough. The caller scales it with {@code Scl.scl} on mobile. */
    static final float IMMEDIATE_SLOP = 16f;

    private BoxSelectDragPolicy(){
    }

    /**
     * Whether a press on one statement may take over as a drag.
     *
     * <p>Two channels, in this order: a movement of at least {@code immediateSlop} starts the
     * drag right away on any platform (on mobile that is a deliberate swipe); a smaller movement
     * still needs {@link #LONG_PRESS_NANOS} when {@code requireLongPress} is set, then only
     * {@link #SLOP} - the long press is the precision channel for nudging a block a few pixels.</p>
     *
     * @param immediateSlop caller-supplied distance that skips the wait; {@link #SLOP} on
     *        desktop keeps the historical slop-only behaviour exactly
     */
    static boolean singleDragReady(long elapsedNanos, float dx, float dy, boolean requireLongPress, float immediateSlop){
        float distance = dx * dx + dy * dy;
        if(distance >= immediateSlop * immediateSlop) return true;
        if(requireLongPress && elapsedNanos < LONG_PRESS_NANOS) return false;
        return distance >= SLOP * SLOP;
    }

    static boolean moved(float dx, float dy){
        return dx * dx + dy * dy >= SLOP * SLOP;
    }
}
