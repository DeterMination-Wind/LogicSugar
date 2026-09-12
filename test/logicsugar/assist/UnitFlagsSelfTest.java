package logicsugar.assist;

public class UnitFlagsSelfTest{
    public static void main(String[] args){
        zeroAndNonFiniteAreHidden();
        nonzeroFiniteIsShown();
        wholeNumbersHaveNoTrailingDecimal();
        fractionalAndLargeValuesKeepPrecision();
        labelSitsOnTopOfHitbox();
        System.out.println("LogicSugar UnitFlags self-test passed.");
    }

    private static void zeroAndNonFiniteAreHidden(){
        check(!UnitFlags.shouldDraw(0d), "default flag 0 was drawn");
        check(!UnitFlags.shouldDraw(-0d), "negative zero was drawn");
        check(!UnitFlags.shouldDraw(Double.NaN), "NaN flag was drawn");
        check(!UnitFlags.shouldDraw(Double.POSITIVE_INFINITY), "Inf flag was drawn");
        check(!UnitFlags.shouldDraw(Double.NEGATIVE_INFINITY), "-Inf flag was drawn");
    }

    private static void nonzeroFiniteIsShown(){
        check(UnitFlags.shouldDraw(1d), "integer flag 1 was hidden");
        check(UnitFlags.shouldDraw(-3d), "negative flag was hidden");
        check(UnitFlags.shouldDraw(0.5d), "fractional flag was hidden");
        check(UnitFlags.shouldDraw(1e12d), "large flag was hidden");
    }

    private static void wholeNumbersHaveNoTrailingDecimal(){
        check(UnitFlags.formatFlag(7d).equals("7"), "7.0 was not formatted as 7: " + UnitFlags.formatFlag(7d));
        check(UnitFlags.formatFlag(-2d).equals("-2"), "whole negative was not an integer string");
        check(UnitFlags.formatFlag(1e12d).equals("1000000000000"),
            "large whole flag lost its integer form: " + UnitFlags.formatFlag(1e12d));
    }

    private static void fractionalAndLargeValuesKeepPrecision(){
        check(UnitFlags.formatFlag(0.5d).equals("0.5"), "0.5 was reformatted: " + UnitFlags.formatFlag(0.5d));
        check(UnitFlags.formatFlag(0.1d).equals(Double.toString(0.1d)),
            "0.1 lost full double text: " + UnitFlags.formatFlag(0.1d));
        // beyond 2^53 the value is no longer an exact long; keep Double.toString
        double huge = 1e20d;
        check(UnitFlags.formatFlag(huge).equals(Double.toString(huge)),
            "non-integer-range magnitude was forced through long: " + UnitFlags.formatFlag(huge));
    }

    private static void labelSitsOnTopOfHitbox(){
        check(UnitFlags.labelY(10f, 8f) == 10f + 4f + UnitFlags.yPad,
            "label was not placed on the top edge of the hitbox");
        check(UnitFlags.labelY(0f, 0f) == UnitFlags.yPad, "zero-size unit was not padded above origin");
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
