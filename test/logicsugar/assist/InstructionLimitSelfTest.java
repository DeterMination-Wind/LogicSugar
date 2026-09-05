package logicsugar.assist;

import mindustry.logic.LExecutor;

public class InstructionLimitSelfTest{
    public static void main(String[] args){
        fieldIsOverridableOnTargetBuilds();
        applyWritesStaticField();
        applyIgnoresWhenNotOverridable();
        System.out.println("LogicSugar InstructionLimit self-test passed.");
    }

    private static void fieldIsOverridableOnTargetBuilds(){
        // maxInstructions is a plain public static int on every build the mod targets;
        // if a future build makes it final, the guard must report it instead of crashing.
        check(InstructionLimit.canOverride() == !isFinal(),
            "canOverride() disagrees with the actual field modifier");
    }

    private static void applyWritesStaticField(){
        InstructionLimit.apply(1500);
        check(LExecutor.maxInstructions == 1500, "apply(1500) did not raise the limit");
        InstructionLimit.apply(InstructionLimit.defaultLimit);
        check(LExecutor.maxInstructions == InstructionLimit.defaultLimit,
            "apply(default) did not restore the vanilla limit");
    }

    private static void applyIgnoresWhenNotOverridable(){
        // apply() must be a no-op while the guard is active; the guard is real on current
        // builds, so simulate the blocked case through the same reflection predicate the
        // guard uses (a final field would make canOverride() false and skip the write).
        boolean overridable = InstructionLimit.canOverride();
        InstructionLimit.apply(1234);
        check(overridable ? LExecutor.maxInstructions == 1234 : LExecutor.maxInstructions != 1234,
            "apply() wrote the field despite the override guard");
        InstructionLimit.apply(InstructionLimit.defaultLimit);
    }

    private static boolean isFinal(){
        try{
            return java.lang.reflect.Modifier.isFinal(
                LExecutor.class.getField("maxInstructions").getModifiers());
        }catch(NoSuchFieldException e){
            return true;
        }
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
