package logicsugar.assist;

public class BoxSelectSelfTest{
    public static void main(String[] args){
        mobileDragWaitsForLongPress();
        desktopDragUsesSlopOnly();
        dragUsesSmallFixedSlop();
        movementBoundaryIsInclusive();
        System.out.println("LogicSugar BoxSelect self-test passed.");
    }

    private static void mobileDragWaitsForLongPress(){
        check(!BoxSelectDragPolicy.singleDragReady(BoxSelectDragPolicy.LONG_PRESS_NANOS - 1, 20f, 0f, true),
            "mobile drag started before long press");
        check(BoxSelectDragPolicy.singleDragReady(BoxSelectDragPolicy.LONG_PRESS_NANOS, 8f, 0f, true),
            "mobile drag did not start at the long-press boundary");
    }

    private static void desktopDragUsesSlopOnly(){
        check(BoxSelectDragPolicy.singleDragReady(0L, 8f, 0f, false),
            "desktop drag incorrectly required a long press");
        check(BoxSelectDragPolicy.singleDragReady(0L, 0f, -8f, false),
            "desktop vertical drag incorrectly required a long press");
    }

    private static void dragUsesSmallFixedSlop(){
        check(!BoxSelectDragPolicy.singleDragReady(BoxSelectDragPolicy.LONG_PRESS_NANOS, 7.9f, 0f, false),
            "drag started below the slop");
        check(BoxSelectDragPolicy.singleDragReady(BoxSelectDragPolicy.LONG_PRESS_NANOS, 0f, 8f, false),
            "vertical slop was ignored");
        check(BoxSelectDragPolicy.singleDragReady(BoxSelectDragPolicy.LONG_PRESS_NANOS, 8f, 8f, false),
            "diagonal slop was ignored");
    }

    private static void movementBoundaryIsInclusive(){
        check(!BoxSelectDragPolicy.moved(7.99f, 0f), "movement started below slop");
        check(BoxSelectDragPolicy.moved(8f, 0f), "movement boundary was treated as a click");
        check(BoxSelectDragPolicy.moved(0f, -8f), "negative movement boundary was ignored");
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
