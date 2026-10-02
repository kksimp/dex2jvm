// dexsem: no-r8
// Every line this case prints is a class, method or field NAME, plus
// getSimpleName/getEnclosingMethod metadata. R8 renames all of it and strips
// InnerClasses/EnclosingMethod/Signature unless -keepattributes asks for them,
// so under the r8 mode the oracle and the actual differ for a reason that is not
// a converter bug (measured 2026-07-29: NPE from getSimpleName() returning null).
// The desugar and nodesugar modes still cover this case fully.
// Reflection and method handles. These read the ATTRIBUTES the converter
// rebuilds (Signature, InnerClasses, EnclosingMethod, annotations,
// MethodParameters), so a wrong attribute shows up here as a wrong answer
// rather than as a load failure. Ordering is normalised because
// getDeclaredMethods() has no specified order.
import java.lang.annotation.*;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;
public class Reflect {
    public static void main(String[] a) throws Throwable {
        // Class naming across every shape of class.
        p("names", n(Reflect.class) + " " + n(Nested.class) + " " + n(int[].class)
                   + " " + n(String[][].class) + " " + n(E.class) + " " + n(int.class)
                   + " " + n(void.class) + " " + n(I.class));
        Runnable anon = new Runnable() { public void run() {} };
        class Local {}
        p("anon", anon.getClass().isAnonymousClass() + "/" + anon.getClass().isLocalClass()
                  + "/" + anon.getClass().isMemberClass() + "/" + anon.getClass().isSynthetic());
        p("local", Local.class.isLocalClass() + "/" + Local.class.getSimpleName());
        p("nested", Nested.class.isMemberClass() + "/" + Nested.class.getEnclosingClass().getSimpleName());
        p("enclosing", anon.getClass().getEnclosingClass().getSimpleName() + "/"
                       + anon.getClass().getEnclosingMethod().getName());

        // Modifiers and members, sorted so the output is deterministic.
        p("modifiers", Modifier.toString(Nested.class.getModifiers()) + "|"
                       + Modifier.toString(I.class.getModifiers()));
        p("methods", members(Nested.class));
        p("fields", fields(Nested.class));
        p("ctors", ctors(Nested.class));

        // Generic signatures: these come straight from the Signature attribute.
        p("genericsuper", GImpl.class.getGenericSuperclass().toString());
        p("genericifaces", Arrays.toString(GImpl.class.getGenericInterfaces()));
        Method gm = Reflect.class.getDeclaredMethod("generic", List.class, Map.class);
        p("genericparams", Arrays.toString(gm.getGenericParameterTypes()));
        p("genericreturn", gm.getGenericReturnType().toString());
        TypeVariable<?>[] tv = Box.class.getTypeParameters();
        p("typevars", tv.length + "/" + tv[0].getName() + "/" + Arrays.toString(tv[0].getBounds()));
        p("wildcard", ((ParameterizedType) Reflect.class.getDeclaredMethod("wild", List.class)
                        .getGenericParameterTypes()[0]).getActualTypeArguments()[0].toString());

        // Runtime annotations, including array and enum members and defaults.
        Ann an = Nested.class.getAnnotation(Ann.class);
        p("annotation", an.name() + "/" + an.count() + "/" + Arrays.toString(an.tags())
                        + "/" + an.kind() + "/" + an.cls().getSimpleName());
        p("annpresent", Nested.class.isAnnotationPresent(Ann.class) + "/"
                        + Reflect.class.isAnnotationPresent(Ann.class));
        Method am = Nested.class.getDeclaredMethod("annotated", int.class);
        p("methodann", am.getAnnotation(Ann.class).name() + "/"
                       + am.getParameterAnnotations()[0].length);

        // MethodParameters (javac -parameters) survives the round trip.
        p("paramnames", Arrays.toString(Arrays.stream(am.getParameters())
                        .map(Parameter::getName).toArray()));

        // Reflective construction and field access.
        Constructor<Nested> c = Nested.class.getDeclaredConstructor(int.class);
        Nested inst = c.newInstance(21);
        Field f = Nested.class.getDeclaredField("v");
        f.setAccessible(true);
        p("reflect-new", f.getInt(inst));
        f.setInt(inst, 99);
        p("reflect-set", inst.v + "/" + Nested.class.getDeclaredMethod("twice").invoke(inst));
        p("static-field", Nested.class.getDeclaredField("S").get(null));

        // Array reflection.
        Object arr = Array.newInstance(int.class, 4);
        for (int i = 0; i < 4; i++) Array.set(arr, i, i * i);
        p("arrayrefl", Array.getLength(arr) + "/" + Array.get(arr, 3) + "/"
                       + arr.getClass().getComponentType().getName());
        p("multiarray", Array.newInstance(String.class, 2, 3).getClass().getName());

        // Enums.
        p("enum", Arrays.toString(E.class.getEnumConstants()) + "/" + E.B.getDeclaringClass().getSimpleName()
                  + "/" + E.class.isEnum() + "/" + E.B.getClass().isEnum());

        // MethodHandles: static, virtual, constructor, field, and exact vs generic invoke.
        MethodHandles.Lookup lk = MethodHandles.lookup();
        MethodHandle mhStatic = lk.findStatic(Reflect.class, "addUp", MethodType.methodType(int.class, int.class, int.class));
        MethodHandle mhVirt = lk.findVirtual(Nested.class, "twice", MethodType.methodType(int.class));
        MethodHandle mhCtor = lk.findConstructor(Nested.class, MethodType.methodType(void.class, int.class));
        MethodHandle mhGet = lk.findGetter(Nested.class, "v", int.class);
        p("mh", (int) mhStatic.invokeExact(3, 4) + "/" + (int) mhVirt.invokeExact((Nested) inst)
                + "/" + (int) mhGet.invokeExact((Nested) new Nested(5)));
        p("mh-ctor", ((Nested) mhCtor.invoke(8)).v);
        p("mh-type", mhStatic.type().toString() + "/" + mhVirt.type().toString());
        MethodHandle bound = MethodHandles.insertArguments(mhStatic, 0, 10);
        p("mh-bind", (int) bound.invokeExact(5));

        // VarHandle on an instance field.
        VarHandle vh = MethodHandles.privateLookupIn(Nested.class, lk)
                        .findVarHandle(Nested.class, "v", int.class);
        Nested vt = new Nested(1);
        vh.set(vt, 7);
        p("varhandle", vh.get(vt) + "/" + vh.compareAndSet(vt, 7, 12) + "/" + vt.v);

        // A string switch on null throws, and the message must not leak state.
        p("strswitch-null", tryIt(() -> { String s = null; switch (s) { case "a": return 1; default: return 2; } }));
        // Integer division by a variable zero, through reflection's boxing path.
        p("boxed-div", tryIt(() -> Integer.valueOf(1) / Integer.valueOf(0)));

        // Self-reflection during a nested singleton's OWN <clinit> -- the exact
        // shape of org.signal.core.util.logging.Log.tag(): "javaClass.simpleName"
        // evaluated on `this` while the object is still initializing, one level
        // deep (LogTagShape, a top-level-ish member of Reflect) and two levels
        // deep (LogTagShape.Nested, implementing an interface, matching
        // ExoPlayerPool$DataSourceTransferListener's own shape: a static final
        // INSTANCE, a private constructor, implements Listener). Every one of
        // these calls resolves through Class.getDeclaringClass0, which throws
        // IncompatibleClassChangeError the instant an outer class's own
        // InnerClasses entry does not reciprocate what the inner class claims
        // (HotSpot's Reflection::check_for_inner_class performs that check;
        // see NestIndex's header). This case cannot exercise the CROSS-DEX-FILE
        // mechanism itself -- the suite dexes every case into one combined
        // classes.dex, so a nest never crosses a dex-file boundary here; that
        // half needs a hand-built sample that genuinely splits a nest across
        // dex files. This case instead pins that a self-describing nested singleton keeps answering
        // correctly under REAL bytecode EXECUTION, not just side-by-side
        // reflective comparison against javac's own class files.
        Class<?>[] declared = LogTagShape.class.getDeclaredClasses();
        p("logtag", LogTagShape.TAG + "/" + LogTagShape.Nested.TAG + "/"
                    + LogTagShape.class.isMemberClass() + "/"
                    + LogTagShape.Nested.class.isMemberClass() + "/"
                    + LogTagShape.Nested.class.getDeclaringClass().getSimpleName() + "/"
                    + declared.length + "/" + declared[0].getSimpleName());
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Ann { String name() default "d"; int count() default 1;
                     String[] tags() default {}; E kind() default E.A; Class<?> cls() default Object.class; }

    enum E { A, B, C }
    interface I {}
    static class Box<T extends Comparable<T>> { T v; }
    static class GImpl extends Box<String> implements Comparable<GImpl>, I {
        public int compareTo(GImpl o) { return 0; }
    }

    @Ann(name = "N", count = 3, tags = { "x", "y" }, kind = E.C, cls = String.class)
    static class Nested {
        static final int S = 55;
        int v;
        Nested(int v) { this.v = v; }
        @Ann(name = "M") int annotated(int howMany) { return howMany; }
        int twice() { return v * 2; }
        private String hidden() { return "h"; }
    }

    static <T> Map<String, List<T>> generic(List<T> in, Map<String, ? extends T> m) { return null; }
    static void wild(List<? extends Number> l) {}
    static int addUp(int x, int y) { return x + y; }

    interface Listener { void onEvent(); }

    /** Kotlin `object`-shaped singleton: static final INSTANCE, private
     *  constructor, self-reflection in a field initializer that runs as part
     *  of the class's own &lt;clinit&gt;. See the "logtag" probe above. */
    static final class LogTagShape {
        static final LogTagShape INSTANCE = new LogTagShape();
        static final String TAG = INSTANCE.getClass().getSimpleName();
        private LogTagShape() {}

        /** Two levels deep, and implements an interface -- matching
         *  ExoPlayerPool$DataSourceTransferListener exactly (a nested
         *  singleton implementing androidx.media3.datasource.TransferListener). */
        static final class Nested implements Listener {
            static final Nested INSTANCE = new Nested();
            static final String TAG = INSTANCE.getClass().getSimpleName();
            private Nested() {}
            public void onEvent() {}
        }
    }

    interface Th { Object run(); }
    static String tryIt(Th t) { try { return "ok:" + t.run(); } catch (Throwable e) { return e.getClass().getName(); } }

    static String n(Class<?> c) { return c.getSimpleName() + "|" + c.getName() + "|" + c.getCanonicalName(); }
    static String members(Class<?> c) {
        List<String> l = new ArrayList<>();
        for (Method m : c.getDeclaredMethods())
            if (!m.isSynthetic()) l.add(m.getName() + Arrays.toString(m.getParameterTypes()).replace("class ", "") + ":" + m.getReturnType().getSimpleName());
        Collections.sort(l); return l.toString();
    }
    static String fields(Class<?> c) {
        List<String> l = new ArrayList<>();
        for (Field f : c.getDeclaredFields()) if (!f.isSynthetic()) l.add(f.getName() + ":" + f.getType().getSimpleName());
        Collections.sort(l); return l.toString();
    }
    static String ctors(Class<?> c) {
        List<String> l = new ArrayList<>();
        for (Constructor<?> k : c.getDeclaredConstructors()) l.add(Arrays.toString(k.getParameterTypes()).replace("class ", ""));
        Collections.sort(l); return l.toString();
    }
    static void p(String k, Object v) { System.out.println(k + "=" + v); }
}
