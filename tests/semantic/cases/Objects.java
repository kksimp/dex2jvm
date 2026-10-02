// Dispatch, initialisation order, arrays and generics. A converter bug here
// produces a plausible-looking class that computes the wrong thing.
import java.util.*;
public class Objects {
    public static void main(String[] a) {
        // Virtual dispatch through an abstract base, plus super calls.
        Base[] bs = { new Base(), new Mid(), new Leaf() };
        StringBuilder s = new StringBuilder();
        for (Base b : bs) s.append(b.name()).append(':').append(b.calc(3)).append(' ');
        p("dispatch", s);
        p("supercall", new Leaf().viaSuper(4));

        // Interface default + static + private methods, and diamond resolution.
        p("default", new Impl().greet() + "/" + I1.stat() + "/" + new Both().greet());

        // Initialisation ORDER: static init, instance init blocks, field
        // initialisers, then the constructor body.
        Order.trace.setLength(0);
        new Order(7);
        p("initorder", Order.trace);

        // Bridge methods: a generic override gets a synthetic bridge, and
        // calling through the raw type must reach the real one.
        Box<String> box = new StrBox();
        box.put("x");
        p("bridge", box.get() + "/" + ((Box) box).get());

        // Arrays: covariance, jagged shapes, defaults, clone, multi-dim.
        int[][] jag = new int[3][];
        for (int i = 0; i < 3; i++) { jag[i] = new int[i + 1]; for (int j = 0; j <= i; j++) jag[i][j] = i * 10 + j; }
        p("jagged", Arrays.deepToString(jag));
        int[][][] cube = new int[2][3][4];
        cube[1][2][3] = 42;
        p("cube", cube.length + "/" + cube[0].length + "/" + cube[0][0].length + "/" + cube[1][2][3]);
        p("defaults", Arrays.deepToString(new int[2][3]) + Arrays.toString(new boolean[2])
                      + (int) (new char[2])[0] + Arrays.toString(new String[2]) + Arrays.toString(new double[2]));
        int[] src = { 5, 4, 3, 2, 1 };
        int[] cl = src.clone(); Arrays.sort(cl);
        p("clone-sort", Arrays.toString(src) + Arrays.toString(cl));
        Object[] objs = new String[] { "a", "b" };
        p("covariant", objs.getClass().getName() + "/" + objs[1]);
        p("fillarray", Arrays.toString(new int[]{1,2,3,4,5}) + Arrays.toString(new long[]{1L,2L})
                       + Arrays.toString(new byte[]{1,2}) + Arrays.toString(new String[]{"p","q"}));
        System.arraycopy(src, 1, cl, 0, 3);
        p("arraycopy", Arrays.toString(cl));

        // instanceof / checkcast chains including arrays and interfaces.
        Object o = new Leaf();
        p("instanceof", (o instanceof Base) + "" + (o instanceof Mid) + (o instanceof I1)
                        + (objs instanceof Object[]) + (((Object) objs) instanceof String[])
                        + (((Object) src) instanceof int[]) + (o instanceof Comparable));

        // Boxing: the Integer cache makes == identity observable in [-128,127].
        // The two 128s come from two separate Integer.getInteger calls (an
        // unset property, so each returns its own Integer.valueOf(128)): R8,
        // once it can see java.lang.Integer (a real JDK on --lib), treats
        // valueOf as pure and merges two valueOf(128) of one constant -- even
        // through a volatile field it can prove is never written -- into one
        // object. That is a legal optimisation of the INPUT, not ours to catch.
        Integer i1 = 127, i2 = 127;
        Integer i3 = Integer.getInteger("dex2jvm.test.unset", 128);
        Integer i4 = Integer.getInteger("dex2jvm.test.unset", 128);
        p("intcache", (i1 == i2) + "/" + (i3 == i4) + "/" + i3.equals(i4));
        p("boxing", sum(Arrays.asList(1, 2, 3)) + "/" + unbox(Integer.valueOf(9)));
        p("charbool", (Character.valueOf('a') == Character.valueOf('a')) + "/"
                      + (Boolean.valueOf(true) == Boolean.valueOf(true)));

        // Varargs, including the zero-arg and pass-an-array forms.
        p("varargs", va() + "/" + va(1) + "/" + va(1, 2, 3) + "/" + va(new int[]{4,5}));
        p("objvarargs", ova("a", 1, null) + "/" + ova());

        // Inner / anonymous / local classes and captured locals.
        int captured = 11;
        Runnable r = new Runnable() { public void run() { System.out.println("anon=" + (captured * 2)); } };
        r.run();
        class Local { int f() { return captured + 1; } }
        p("local", new Local().f());
        p("inner", new Objects().new Inner(3).v());
        p("staticnested", new Nested(4).v());

        // Strings: deterministic ops only.
        String str = "Hello, é世界";
        p("string", str.length() + "/" + str.hashCode() + "/" + str.toUpperCase(Locale.ROOT)
                    + "/" + str.substring(7) + "/" + str.indexOf('世'));
        p("concat", concatLoop(5));
        p("chars", (int) str.charAt(7) + "/" + Integer.toHexString(str.codePointAt(7)));
        p("sbuilder", new StringBuilder("ab").insert(1, 'X').reverse().append(1).append(2L).append(1.5).toString());

        // Collections with deterministic iteration order.
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("b", 2); m.put("a", 1); m.put("c", 3);
        p("linked", m.toString());
        p("tree", new TreeMap<>(m).toString());
        List<Integer> li = new ArrayList<>(Arrays.asList(3, 1, 2));
        Collections.sort(li);
        p("sorted", li.toString());
    }

    static class Base { String name() { return "B"; } int calc(int x) { return x; } }
    static class Mid extends Base { String name() { return "M"; } int calc(int x) { return super.calc(x) * 2; } }
    static class Leaf extends Mid implements I1, Comparable<Leaf> {
        String name() { return "L"; } int calc(int x) { return super.calc(x) + 1; }
        int viaSuper(int x) { return super.calc(x) * 100; }
        public int compareTo(Leaf o) { return 0; }
    }
    interface I1 { static String stat() { return "S"; } default String greet() { return "I1" + helper(); }
                   private String helper() { return "!"; } }
    interface I2 { default String greet() { return "I2"; } }
    static class Impl implements I1 {}
    static class Both implements I1, I2 { public String greet() { return I1.super.greet() + I2.super.greet(); } }

    static class Order {
        static final StringBuilder trace = new StringBuilder();
        static { trace.append("S1"); }
        int f = init("F1");
        { trace.append("B1"); }
        int g = init("F2");
        static { trace.append("S2"); }
        Order(int v) { trace.append("C").append(v); }
        static int init(String t) { trace.append(t); return 0; }
    }

    static class Box<T> { T v; void put(T t) { v = t; } T get() { return v; } }
    static class StrBox extends Box<String> { String get() { return "s:" + super.get(); } }

    class Inner { final int n; Inner(int n) { this.n = n; } int v() { return n * 7; } }
    static class Nested { final int n; Nested(int n) { this.n = n; } int v() { return n * 9; } }

    static int sum(List<Integer> l) { int n = 0; for (int i : l) n += i; return n; }
    static int unbox(Integer i) { return i; }
    static int va(int... xs) { int n = xs.length * 100; for (int x : xs) n += x; return n; }
    static String ova(Object... xs) { return xs.length + Arrays.toString(xs); }
    static String concatLoop(int n) { String s = ""; for (int i = 0; i < n; i++) s += i + "-"; return s; }
    static void p(String k, Object v) { System.out.println(k + "=" + v); }
}
