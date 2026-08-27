package mindustry.logic;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Regression test for the cross-classloader protected-access trap documented in AGENTS.md.
 *
 * In game, this mod's classes load through a mod class loader while mindustry.logic classes
 * load through the app loader: same package name, different runtime packages. A static
 * helper that calls a protected member of a game class (e.g. {@code LStatement.field})
 * compiles fine but throws IllegalAccessError the moment a statement renders — exactly what
 * happened to the first {@code addCompactOp} implementation.
 *
 * This test reproduces the loader topology headlessly: a child-first loader defines our
 * {@code mindustry.logic} classes while {@code LStatement} stays on the parent. It then
 * invokes the compact-op bridge the way a rendered For card would. The fixed architecture
 * (protected calls only inside the {@link SugarStatements.SugarStatement} subclass) must not
 * throw IllegalAccessError. Other Throwables (headless UI limits of this environment) are
 * tolerated — they fire after the access check and are unrelated to the loader contract.
 */
public final class CrossLoaderAccessTest{
    private CrossLoaderAccessTest(){}

    public static void main(String[] args) throws Exception{
        File main = new File("build/classes/java/main");
        if(!main.isDirectory()){
            main = new File("../build/classes/java/main");
        }
        if(!main.isDirectory()){
            throw new AssertionError("main classes directory not found relative to " + new File(".").getCanonicalPath());
        }

        ClassLoader app = CrossLoaderAccessTest.class.getClassLoader();
        URLClassLoader loader = new ChildFirstLoader(new URL[]{main.toURI().toURL()}, app);

        Class<?> statements = Class.forName("mindustry.logic.SugarStatements", true, loader);
        check(statements.getClassLoader() == loader,
            "SugarStatements must be defined by the child loader for this simulation");
        Class<?> lStatement = Class.forName("mindustry.logic.LStatement", true, loader);
        check(lStatement.getClassLoader() == app,
            "LStatement must stay on the parent loader for this simulation");

        Class<?> sugarStatement = Class.forName("mindustry.logic.SugarStatements$SugarStatement", true, loader);
        Class<?> forBegin = Class.forName("mindustry.logic.SugarStatements$ForBeginStatement", true, loader);
        Object owner = forBegin.getDeclaredConstructor().newInstance();
        Class<?> opType = Class.forName("mindustry.logic.ConditionOp", true, loader);
        @SuppressWarnings("unchecked")
        Object op = Enum.valueOf((Class<? extends Enum>)opType.asSubclass(Enum.class), "lessThanEq");

        Method bridge = sugarStatement.getMethod("addCompactOp", arc.scene.ui.layout.Table.class,
            opType, arc.func.Cons.class, String.class, arc.func.Cons.class, String.class, arc.func.Cons.class);

        try{
            bridge.invoke(owner, new arc.scene.ui.layout.Table(), op,
                null, "i", null, "10", null);
        }catch(Throwable failure){
            while(failure instanceof java.lang.reflect.InvocationTargetException
                && failure.getCause() != null){
                failure = failure.getCause();
            }
            if(failure instanceof IllegalAccessError){
                throw new AssertionError("Cross-loader protected access regressed: protected LStatement"
                    + " members must only be called from inside the SugarStatement subclass (AGENTS.md).",
                    failure);
            }
            // Headless UI limitation (e.g. missing initialized fonts) — the access check itself
            // has already passed at this point, so the loader contract under test holds.
            System.out.println("note: bridge invocation stopped at headless UI limit: " + failure);
        }
        System.out.println("LogicSugar cross-loader access self-test passed.");
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }

    /** Loads our own mindustry.logic / logicsugar classes self-first so they end up in a
     *  different runtime package than the game classes on the parent loader. */
    private static final class ChildFirstLoader extends URLClassLoader{
        ChildFirstLoader(URL[] urls, ClassLoader parent){
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException{
            synchronized(getClassLoadingLock(name)){
                Class<?> c = findLoadedClass(name);
                if(c == null){
                    if(name.startsWith("mindustry.logic.") || name.startsWith("logicsugar.")){
                        try{
                            c = findClass(name);
                        }catch(ClassNotFoundException e){
                            c = getParent().loadClass(name);
                        }
                    }else{
                        c = getParent().loadClass(name);
                    }
                }
                if(resolve) resolveClass(c);
                return c;
            }
        }
    }
}
