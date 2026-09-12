package logicsugar.assist.expr;

public final class V160SensorAccessSelfTest{
    public static void main(String[] args){
        boolean previous = ExprCompiler.enterPrivilegedSensors(false);
        try{
            if(ExprCompiler.resolveMember("cameraX") != null){
                throw new AssertionError("cameraX must not resolve for ordinary processors");
            }
            ExprCompiler.enterPrivilegedSensors(true);
            if(!"cameraX".equals(ExprCompiler.resolveMember("@CameraX"))){
                throw new AssertionError("cameraX must resolve for privileged processors");
            }
        }finally{
            ExprCompiler.restorePrivilegedSensors(previous);
        }
        System.out.println("V160SensorAccessSelfTest passed");
    }
}
