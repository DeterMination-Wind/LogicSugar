package mindustry.logic;

import java.io.*;
import java.lang.reflect.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.security.CodeSource;
import java.util.*;
import java.util.stream.*;

/**
 * Regression test for the cross-classloader access trap documented in AGENTS.md.
 *
 * In game, this mod's classes load through a mod class loader while mindustry.logic classes
 * load through the app loader: same package name, different runtime packages. A class that calls
 * a package-private member of a game class (or a protected one from a non-subclass) compiles fine
 * but throws IllegalAccessError the moment that code runs — exactly what happened to the first
 * {@code addCompactOp} implementation (2026-08) and again to the built-in-variables button
 * ({@code LogicDialog.globalsDialog}, 2026-10).
 *
 * The test pins the contract twice:
 *
 * <ol>
 * <li><b>Behaviour.</b> A child-first loader defines our {@code mindustry.logic} classes while
 * {@code LStatement} stays on the parent, and the compact-op bridge is invoked the way a rendered
 * For card would. The fixed architecture (protected calls only inside the
 * {@link SugarStatements.SugarStatement} subclass) must not throw IllegalAccessError.</li>
 * <li><b>Static shape.</b> Every member reference in our compiled classes is resolved through the
 * real hierarchy and checked against the JVM's accessibility rules for a foreign loader. This is
 * the half that fails <em>before</em> a user has to click the broken button: the 2026-10 crash was
 * one field reference that no runtime test reached, and it is caught here in milliseconds.</li>
 * </ol>
 */
public final class CrossLoaderAccessTest{
    private CrossLoaderAccessTest(){}

    public static void main(String[] args) throws Exception{
        Path root = classesRoot();
        ClassLoader app = CrossLoaderAccessTest.class.getClassLoader();

        protectedBridgeSurvivesTheLoaderSplit(root, app);
        memberReferencesAreAccessibleAcrossLoaders(root, app);

        System.out.println("LogicSugar cross-loader access self-test passed.");
    }

    private static void protectedBridgeSurvivesTheLoaderSplit(Path root, ClassLoader app) throws Exception{
        URLClassLoader loader = new ChildFirstLoader(new URL[]{root.toUri().toURL()}, app);

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
    }

    /**
     * Static half: no class of ours may reference a member the JVM would refuse to link.
     *
     * <p>Every {@code Fieldref}/{@code Methodref}/{@code InterfaceMethodref} of every compiled
     * class is read straight out of the class file's constant pool and resolved the way the JVM
     * resolves it — through the referenced class's superclasses and interfaces, so
     * {@code this.buttons} is checked against the class that really declares {@code buttons} and
     * {@code this.globalsDialog} against {@code LogicDialog}. The member is legal when it is
     * public, when it is declared in one of our own classes (same loader, so same runtime package),
     * or when it is protected and the accessing class is a subclass. Everything else throws
     * IllegalAccessError in game and fails this test here.</p>
     */
    private static void memberReferencesAreAccessibleAcrossLoaders(Path root, ClassLoader loader) throws Exception{
        List<Path> files;
        try(Stream<Path> walk = Files.walk(root)){
            files = walk.filter(path -> path.toString().endsWith(".class")).sorted().collect(Collectors.toList());
        }

        List<String> violations = new ArrayList<>();
        int references = 0;
        for(Path file : files){
            Class<?> accessing = load(root, file, loader);
            if(accessing == null) continue;
            for(Reference reference : readReferences(file)){
                references++;
                String problem = accessibilityProblem(accessing, reference, root, loader);
                if(problem != null) violations.add(accessing.getName() + " -> " + problem);
            }
        }

        check(violations.isEmpty(), "cross-loader member access would throw IllegalAccessError in game: "
            + violations + " — package-private/protected game members may only be reached from inside one"
            + " of our subclasses or reflectively (Field.setAccessible), see AGENTS.md.");
        System.out.println("note: checked " + references + " member references of " + files.size()
            + " classes against the cross-loader rules");
    }

    /** The compiled main classes, which is also the loader the mod uses for our own classes. */
    private static Path classesRoot(){
        Path main = Paths.get("build/classes/java/main");
        if(!Files.isDirectory(main)) main = Paths.get("../build/classes/java/main");
        if(!Files.isDirectory(main)){
            throw new AssertionError("main classes directory not found relative to "
                + Paths.get(".").toAbsolutePath().normalize());
        }
        return main;
    }

    private static Class<?> load(Path root, Path file, ClassLoader loader){
        String relative = root.relativize(file).toString().replace(File.separatorChar, '/');
        String name = relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
        try{
            return Class.forName(name, false, loader);
        }catch(Throwable failure){
            System.out.println("note: reference scan skipped " + name + " (" + failure + ")");
            return null;
        }
    }

    /** @return a description of why the JVM would reject the reference, or null when it is legal */
    private static String accessibilityProblem(Class<?> accessing, Reference reference, Path root, ClassLoader loader){
        Class<?> owner;
        try{
            owner = Class.forName(reference.owner.replace('/', '.'), false, loader);
        }catch(Throwable failure){
            // A type this build does not compile against (fork-only APIs are reached reflectively)
            // — there is nothing to check.
            return null;
        }

        Member member = reference.field
            ? findField(owner, reference.name)
            : findMethod(owner, reference.name, reference.descriptor, loader);
        if(member == null){
            // Unresolvable here (a member the game jar in this environment does not have) would be
            // a NoSuchField/MethodError, not an access error; out of scope for this test.
            return null;
        }

        int modifiers = member.getModifiers();
        if(Modifier.isPublic(modifiers)) return null;

        Class<?> declaring = member.getDeclaringClass();
        if(isOurs(declaring, root)) return null; // same loader as the accessing class
        if(Modifier.isProtected(modifiers) && declaring.isAssignableFrom(accessing)) return null;

        String kind = Modifier.isPrivate(modifiers) ? "private"
            : Modifier.isProtected(modifiers) ? "protected, but the accessing class is not a subclass"
            : "package-private";
        return (reference.field ? "field " : "method ") + declaring.getName() + "." + reference.name
            + " is " + kind;
    }

    private static boolean isOurs(Class<?> type, Path root){
        try{
            CodeSource source = type.getProtectionDomain().getCodeSource();
            if(source == null || source.getLocation() == null) return false;
            return Paths.get(source.getLocation().toURI()).toAbsolutePath().normalize()
                .equals(root.toAbsolutePath().normalize());
        }catch(Throwable ignored){
            return false;
        }
    }

    private static Member findField(Class<?> owner, String name){
        for(Class<?> type = owner; type != null; type = type.getSuperclass()){
            try{
                return type.getDeclaredField(name);
            }catch(NoSuchFieldException | NoClassDefFoundError ignored){
            }
        }
        return null;
    }

    private static Member findMethod(Class<?> owner, String name, String descriptor, ClassLoader loader){
        Class<?>[] parameters;
        try{
            parameters = parameterTypes(descriptor, loader);
        }catch(Throwable ignored){
            return null;
        }
        for(Class<?> type = owner; type != null; type = type.getSuperclass()){
            Member found = declaredMethod(type, name, parameters);
            if(found != null) return found;
        }
        Deque<Class<?>> queue = new ArrayDeque<>(Arrays.asList(owner.getInterfaces()));
        while(!queue.isEmpty()){
            Class<?> type = queue.poll();
            Member found = declaredMethod(type, name, parameters);
            if(found != null) return found;
            queue.addAll(Arrays.asList(type.getInterfaces()));
        }
        return null;
    }

    private static Member declaredMethod(Class<?> type, String name, Class<?>[] parameters){
        try{
            return name.equals("<init>") ? type.getDeclaredConstructor(parameters) : type.getDeclaredMethod(name, parameters);
        }catch(NoSuchMethodException | NoClassDefFoundError ignored){
            return null;
        }
    }

    /** Turns a JVM method descriptor's argument list into classes (the return type is not needed). */
    private static Class<?>[] parameterTypes(String descriptor, ClassLoader loader) throws ClassNotFoundException{
        if(!descriptor.startsWith("(")) return new Class<?>[0];
        int end = descriptor.indexOf(')');
        if(end < 0) return new Class<?>[0];

        List<Class<?>> types = new ArrayList<>();
        int index = 1;
        while(index < end){
            int start = index;
            while(descriptor.charAt(index) == '[') index++;
            switch(descriptor.charAt(index)){
                case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z' -> index++;
                default -> index = descriptor.indexOf(';', index) + 1;
            }
            types.add(Class.forName(descriptor.substring(start, index).replace('/', '.'), false, loader));
        }
        return types.toArray(new Class<?>[0]);
    }

    /** One constant-pool member reference: the class it was written against plus name/descriptor. */
    private static final class Reference{
        final String owner, name, descriptor;
        final boolean field;

        Reference(String owner, String name, String descriptor, boolean field){
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
            this.field = field;
        }
    }

    /**
     * Reads the member references out of a class file's constant pool.
     *
     * <p>Written by hand on purpose: the test must run on the plain JDK classpath, without a
     * bytecode library, and only needs the constant pool table. The referenced class is the one
     * javac wrote into the entry — the JVM resolves the member from there through the hierarchy,
     * which is what {@link #accessibilityProblem} reproduces.</p>
     *
     * <p>The pool is read in two passes: constant pool entries may reference entries that appear
     * <em>later</em> in the table (a Class entry pointing at a Utf8 that javac appended after it),
     * so resolving names while parsing would silently drop most of them.</p>
     */
    private static List<Reference> readReferences(Path file) throws IOException{
        try(DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))){
            if(in.readInt() != 0xCAFEBABE) throw new IOException("not a class file: " + file);
            in.readUnsignedShort(); // minor version
            in.readUnsignedShort(); // major version

            int count = in.readUnsignedShort();
            String[] utf8 = new String[count];
            int[] classNames = new int[count];        // Class -> Utf8 name
            int[] nameTypeNames = new int[count];     // NameAndType -> Utf8 name
            int[] nameTypeDescriptors = new int[count];
            int[] tags = new int[count];              // Fieldref/Methodref/InterfaceMethodref only
            int[] ownerClasses = new int[count];      // reference -> Class
            int[] nameTypes = new int[count];         // reference -> NameAndType

            for(int i = 1; i < count; i++){
                int tag = in.readUnsignedByte();
                switch(tag){
                    case 1 -> utf8[i] = in.readUTF();
                    case 7 -> classNames[i] = in.readUnsignedShort();
                    case 8, 16, 19, 20 -> in.skipBytes(2);
                    case 15 -> in.skipBytes(3);
                    case 3, 4 -> in.skipBytes(4);
                    case 5, 6 -> {
                        in.skipBytes(8);
                        i++; // long/double take two constant pool slots
                    }
                    case 9, 10, 11 -> {
                        tags[i] = tag;
                        ownerClasses[i] = in.readUnsignedShort();
                        nameTypes[i] = in.readUnsignedShort();
                    }
                    case 12 -> {
                        nameTypeNames[i] = in.readUnsignedShort();
                        nameTypeDescriptors[i] = in.readUnsignedShort();
                    }
                    case 17, 18 -> in.skipBytes(4);
                    default -> throw new IOException("unknown constant pool tag " + tag + " in " + file);
                }
            }

            List<Reference> references = new ArrayList<>();
            for(int i = 1; i < count; i++){
                if(tags[i] == 0) continue;
                String owner = utf8[classNames[ownerClasses[i]]];
                String name = utf8[nameTypeNames[nameTypes[i]]];
                if(owner == null || name == null) continue;
                references.add(new Reference(owner, name, utf8[nameTypeDescriptors[nameTypes[i]]], tags[i] == 9));
            }
            return references;
        }
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
