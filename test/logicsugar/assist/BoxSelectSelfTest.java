package logicsugar.assist;

import logicsugar.SourceNails;

public class BoxSelectSelfTest{
    /** Immediate-drag distance used by the mobile call site (before Scl.scl); the policy takes it
     *  as a parameter, so the boundary cases do not need a live game context to be pinned. */
    private static final float IMMEDIATE = BoxSelectDragPolicy.IMMEDIATE_SLOP;

    public static void main(String[] args) throws Exception{
        mobileSwipeStartsDragWithoutLongPress();
        mobileSmallMovementStillNeedsLongPress();
        mobileLongPressStillNudgesSmallDistances();
        desktopDragUsesSlopOnly();
        dragUsesSmallFixedSlop();
        movementBoundaryIsInclusive();
        mobileEntryPassesScaledImmediateThreshold();
        System.out.println("LogicSugar BoxSelect self-test passed.");
    }

    /** Fast swipe on mobile: the finger travelled past the immediate threshold, so the drag starts
     *  at once even though the long press has not elapsed. */
    private static void mobileSwipeStartsDragWithoutLongPress(){
        check(BoxSelectDragPolicy.singleDragReady(0L, IMMEDIATE, 0f, true, IMMEDIATE),
            "mobile swipe did not start a drag before the long press elapsed");
        check(BoxSelectDragPolicy.singleDragReady(0L, 0f, -IMMEDIATE, true, IMMEDIATE),
            "mobile vertical swipe did not start a drag before the long press elapsed");
    }

    /** Just below the immediate threshold nothing happens yet: a tap that wobbles is not a drag. */
    private static void mobileSmallMovementStillNeedsLongPress(){
        check(!BoxSelectDragPolicy.singleDragReady(0L, IMMEDIATE - 0.1f, 0f, true, IMMEDIATE),
            "mobile drag started below the immediate threshold without a long press");
        check(!BoxSelectDragPolicy.singleDragReady(1L, 8f, 0f, true, IMMEDIATE),
            "mobile drag started on slop movement without a long press");
    }

    /** The long press is the precision channel: it still opens the drag at the small slop, and it
     *  still waits for the full press before that. */
    private static void mobileLongPressStillNudgesSmallDistances(){
        check(BoxSelectDragPolicy.singleDragReady(BoxSelectDragPolicy.LONG_PRESS_NANOS, 8f, 0f, true, IMMEDIATE),
            "mobile drag did not start at the long-press boundary");
        check(!BoxSelectDragPolicy.singleDragReady(BoxSelectDragPolicy.LONG_PRESS_NANOS - 1, 8f, 0f, true, IMMEDIATE),
            "mobile drag started before the long press for a small movement");
        check(BoxSelectDragPolicy.singleDragReady(BoxSelectDragPolicy.LONG_PRESS_NANOS - 1, IMMEDIATE, 0f, true, IMMEDIATE),
            "the swipe channel must not be gated by the long press");
    }

    private static void desktopDragUsesSlopOnly(){
        check(BoxSelectDragPolicy.singleDragReady(0L, 8f, 0f, false, BoxSelectDragPolicy.SLOP),
            "desktop drag incorrectly required a long press");
        check(BoxSelectDragPolicy.singleDragReady(0L, 0f, -8f, false, BoxSelectDragPolicy.SLOP),
            "desktop vertical drag incorrectly required a long press");
    }

    private static void dragUsesSmallFixedSlop(){
        check(!BoxSelectDragPolicy.singleDragReady(BoxSelectDragPolicy.LONG_PRESS_NANOS, 7.9f, 0f, false, BoxSelectDragPolicy.SLOP),
            "drag started below the slop");
        check(BoxSelectDragPolicy.singleDragReady(BoxSelectDragPolicy.LONG_PRESS_NANOS, 0f, 8f, false, BoxSelectDragPolicy.SLOP),
            "vertical slop was ignored");
        check(BoxSelectDragPolicy.singleDragReady(BoxSelectDragPolicy.LONG_PRESS_NANOS, 8f, 8f, false, BoxSelectDragPolicy.SLOP),
            "diagonal slop was ignored");
    }

    private static void movementBoundaryIsInclusive(){
        check(!BoxSelectDragPolicy.moved(7.99f, 0f), "movement started below slop");
        check(BoxSelectDragPolicy.moved(8f, 0f), "movement boundary was treated as a click");
        check(BoxSelectDragPolicy.moved(0f, -8f), "negative movement boundary was ignored");
    }

    /**
     * The policy is only half the fix: the call site has to hand the scaled threshold to it on
     * mobile and the small slop on desktop. A test that only exercised the pure function would
     * stay green if the wiring passed {@code SLOP} on both platforms, i.e. if the reported bug
     * came back - which is exactly how the "requires a long press on phone" complaint appeared.
     */
    private static void mobileEntryPassesScaledImmediateThreshold() throws Exception{
        String body = SourceNails.methodBody(SourceNails.readSource("src/logicsugar/assist/BoxSelect.java"),
            "private static float singleDragImmediateSlop(){");
        check(body.contains("Vars.mobile ? Scl.scl(BoxSelectDragPolicy.IMMEDIATE_SLOP) : BoxSelectDragPolicy.SLOP"),
            "the immediate-drag threshold must be Scl.scl(IMMEDIATE_SLOP) on mobile and SLOP on desktop");

        String threshold = SourceNails.methodBody(SourceNails.readSource("src/logicsugar/assist/BoxSelect.java"),
            "private static boolean singleDragThresholdReached(float mx, float my){");
        check(threshold.contains("singleDragReady(elapsed, dx, dy, Vars.mobile, singleDragImmediateSlop())"),
            "the drag state machine must consult the policy with the platform threshold");
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
