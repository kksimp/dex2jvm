// invokedynamic: lambdas, method references, and string concat. Needs
// --no-desugaring at d8 time or none of this survives as invoke-custom.
import java.util.*;
import java.util.function.*;
public class Indy {
    public static void main(String[] a) {
        // Non-capturing, capturing, and instance-capturing lambdas.
        Supplier<String> s0 = () -> "nocap";
        int cap = 7;
        IntUnaryOperator s1 = x -> x + cap;
        Indy self = new Indy();
        Supplier<String> s2 = () -> self.inst();
        p("lambda", s0.get() + "/" + s1.applyAsInt(1) + "/" + s2.get());

        // Method references: static, bound, unbound, constructor, array ctor.
        Function<String, Integer> f1 = Integer::parseInt;
        Function<String, Integer> f2 = String::length;
        Supplier<ArrayList<String>> f3 = ArrayList::new;
        IntFunction<int[]> f4 = int[]::new;
        BiFunction<String, String, Boolean> f5 = String::startsWith;
        p("mrefs", f1.apply("42") + "/" + f2.apply("abcd") + "/" + f3.get().size()
                   + "/" + f4.apply(3).length + "/" + f5.apply("hello", "he"));

        // String concat is also indy (makeConcatWithConstants) on modern javac.
        int i = 5; long l = 6L; double d = 1.5; char c = 'z'; Object o = null;
        p("concat", "a" + i + "b" + l + "c" + d + "d" + c + "e" + o + "f" + true);

        // Streams exercise a lot of indy at once; keep the order deterministic.
        List<String> src = Arrays.asList("bb", "a", "ccc", "dd");
        p("stream", src.stream().filter(x -> x.length() > 1).map(String::toUpperCase)
                       .sorted().reduce("", (x, y) -> x + y));
        p("mapToInt", src.stream().mapToInt(String::length).sum());
        p("comparator", src.stream().sorted(Comparator.comparingInt(String::length)
                           .thenComparing(Comparator.naturalOrder())).collect(java.util.stream.Collectors.toList()).toString());

        // A lambda in a static initialiser, and one that throws.
        p("staticlambda", HOLDER.get());
        try { ((Runnable) () -> { throw new IllegalStateException("fromLambda"); }).run(); }
        catch (IllegalStateException e) { p("lambdathrow", e.getMessage()); }

        // Nested/recursive lambdas and closures over mutable holders.
        int[] box = { 0 };
        Runnable inc = () -> box[0]++;
        for (int k = 0; k < 4; k++) inc.run();
        p("closure", box[0]);
        Function<Integer, Function<Integer, Integer>> add = x -> y -> x + y;
        p("curried", add.apply(3).apply(4));
    }
    static final Supplier<String> HOLDER = () -> "held";
    String inst() { return "inst"; }
    static void p(String k, Object v) { System.out.println(k + "=" + v); }
}
