// Cross-class access to PRIVATE members -- a failure mode NO other gate can see,
// because access control is checked at RESOLUTION (JVMS 5.4.3), not at
// verification. G6/G7/G8 all pass a class that throws IllegalAccessError the
// instant one of its instructions first executes, and switching verification off
// would not mask it either -- so the symptom is a loud IllegalAccessError (often
// wrapped in an ExceptionInInitializerError) at class initialisation rather than
// silent corruption.
//
// WHAT IT PINS, and both halves are real converter bugs that shipped:
//
// 1. NESTMATES. A class and the classes nested in it form a nest, and nestmates
//    may touch each other's private members directly (JVMS 5.4.4). DEX carries no
//    nest attributes, so a rebuilt class file loses the nest and every such
//    access becomes IllegalAccessError. NestPlan reconstructs
//    NestHost/NestMembers from the dalvik EnclosingClass/EnclosingMethod
//    annotations, and ClassFileWriter.versionFloor() raises the class file to
//    major 55 for any class carrying one -- below 55 HotSpot silently IGNORES
//    both attributes (classFileParser.cpp gates them on
//    `_major_version >= JAVA_11_VERSION`), so emitting them at 52 is inert.
//    Holder below exercises every direction: outer reads inner's private, inner
//    reads outer's private, sibling reads sibling's private, for a field, an
//    instance method, a static method and a CONSTRUCTOR.
//
// 2. R8's CONSTRUCTOR RE-POINTING onto a PRIVATE constructor. R8 deletes a
//    constructor whose body is only `super()` and rewrites `new T; T.<init>()`
//    to name a SUPERCLASS constructor, usually java/lang/Object directly. ART
//    permits that (art/runtime/verifier/method_verifier.cc INVOKE_DIRECT is
//    deliberately permissive); the JVM demands the call name the allocated class
//    exactly (JVMS 4.9.2 and the 4.10.1.9 rewrittenUninitializedType rule), so
//    InitRepointPlan must point it back. R8 ALSO strips InnerClasses /
//    EnclosingMethod by default, so by the time we see the DEX there is no nest
//    left to reconstruct -- and if the constructor R8 left behind is PRIVATE,
//    pointing back at it is illegal for every caller outside that class.
//    InitRepointPlan used to do exactly that.
//
//    Measured 2026-07-29 on Google Calculator 9.1, which failed at startup:
//        IllegalAccessError: class cfo tried to access private method
//        'void dgo.<init>()'
//    out of CalculatorApplication.onCreate. `dgo` is a Serializable singleton
//    whose private no-arg constructor's body is nothing but super(). Trivial
//    below is that shape, allocated from a class it is nested in -- legal Java,
//    and legal DEX after R8 flattens it.
//
// This half needs the R8 mode: d8 alone never re-points a constructor. Under
// plain d8 the case still guards half 1.
//
// STATUS OF HALF 2, measured 2026-07-29 -- READ BEFORE TRUSTING IT.
// The Memoize shape below does NOT yet trip half 2. R8 does strip the nest
// annotations as intended (0 of 10 emitted classes carry InnerClass /
// EnclosingClass, exactly as in Calculator), but instead of constructor-inlining
// `new Sentinel()` it PUBLICIZES `Sentinel.<init>()V` (measured flags 0x10001 =
// ACC_PUBLIC|ACC_CONSTRUCTOR), so no cross-class private reference survives and
// the case passes even on the buggy converter. R8 chooses between
// publicize-and-keep and inline-and-re-point on whole-program grounds
// (DefaultInliningOracle.canInlineInstanceInitializer plus
// InternalOptions.canInitNewInstanceUsingSuperclassConstructor, which needs DEX
// output and minApi >= 21), and a ten-class program is not enough to push it the
// other way.
// So the REGRESSION GUARD for half 2 is not this case -- it is
// tools/verify's PrivAudit, which tests/semantic/run.sh runs over every emitted
// jar, and which over real apps measured Calculator 3 -> 0 sites and
// Duolingo 5 -> 0 across the fix. Keep this case for half 1 and as the documented
// shape; if you later find R8 flags that make it re-point, delete this note.
//
// Everything printed is deterministic: no identity hashes, no map iteration order.
public class Access {
    public static void main(String[] a) {
        p("outer-reads-inner", Holder.readInnerPrivates());
        p("inner-reads-outer", new Holder.Inner().readOuterPrivates());
        p("sibling", Holder.crossSibling());
        p("private-ctor-nested", Holder.buildDeep());
        p("trivial-private-ctor", Holder.buildTrivial().tag());
        p("trivial-distinct", String.valueOf(Holder.buildTrivial() != Holder.buildTrivial()));
        p("singleton", Singleton.get().tag() + "/" + (Singleton.get() == Singleton.get()));
        p("peer", Peer.viaOuter());
        // The Calculator shape: sibling nested classes, one allocating the
        // other's private trivial constructor. Called twice so the memo's two
        // branches both run.
        Memoize.Supplier<String> s = Memoize.of("mv");
        p("memoize", s.get() + "/" + s.get());
        p("memoize2", Memoize.of("z").get());
    }

    static void p(String k, Object v) { System.out.println(k + " = " + v); }
}

/**
 * Nest exercise plus the Calculator shape.
 *
 * Trivial is the one that matters for half 2: a PRIVATE no-arg constructor whose
 * body is only the implicit super() call, allocated from the ENCLOSING class.
 * That is legal Java via the nest; R8 then (a) drops the nest attributes and
 * (b) deletes the do-nothing constructor and re-points `new Trivial()` at
 * java/lang/Object.<init>()V -- leaving the converter to re-point it back at a
 * private constructor whose only legal caller no longer exists.
 */
class Holder {
    private int outerField = 7;
    private static String outerStatic = "os";
    private String outerMethod() { return "om"; }
    private static String outerStaticMethod() { return "osm"; }

    static String readInnerPrivates() {
        Inner i = new Inner();
        return i.innerField + "/" + i.innerMethod() + "/" + Inner.innerStatic()
             + "/" + new Deep(3).deepField;
    }

    /** One nested class touching a SIBLING nested class's privates. */
    static String crossSibling() { return Inner.readSibling(); }

    /** A private constructor that does REAL work: must stay reachable, and must
     *  NOT be routed around (that would run initialisation ART never runs). */
    static String buildDeep() { return new Deep(11).show(); }

    /** The Calculator shape: private constructor, body is only super(). */
    static Trivial buildTrivial() { return new Trivial(); }

    static class Inner {
        private int innerField = 5;
        private String innerMethod() { return "im"; }
        private static String innerStatic() { return "is"; }
        String readOuterPrivates() {
            Holder h = new Holder();
            return h.outerField + "/" + h.outerMethod() + "/" + outerStatic
                 + "/" + outerStaticMethod();
        }
        static String readSibling() { return new Deep(2).deepField + "/" + Deep.deepStatic(); }
    }

    static class Deep {
        private final int deepField;
        private Deep(int v) { this.deepField = v; }
        private static String deepStatic() { return "ds"; }
        private String show() { return "deep" + deepField; }
    }

    /** No fields, no body: nothing for R8 to keep, so the constructor goes. */
    static class Trivial {
        private Trivial() {}
        String tag() { return "trivial"; }
    }
}

/**
 * The measured Calculator shape, reproduced structurally.
 *
 * Guava's `Suppliers` is the original: `Suppliers.NonSerializableMemoizingSupplier`
 * holds a `private final Object lock = new Object()`-style sentinel allocated from
 * a SIBLING nested class whose constructor is private and does nothing. R8
 * flattens both siblings to top-level names in the default package and strips
 * InnerClasses/EnclosingMethod, so by the time the DEX reaches us the two classes
 * are unrelated -- no nest can be reconstructed, and the cross-class re-point onto
 * the private constructor becomes an IllegalAccessError.
 *
 * In Calculator these came out as `cfo` (this one) and `dgo` (the sentinel).
 */
final class Memoize {

    interface Supplier<T> { T get(); }

    /** The sentinel. Private no-arg constructor whose body is ONLY super(), which
     *  is exactly what R8 deletes and re-points to java/lang/Object.<init>()V.
     *  Serializable + readResolve keeps R8 from collapsing it to nothing. */
    static final class Sentinel implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        static final Sentinel INSTANCE = new Sentinel();
        private Sentinel() {}
        private Object readResolve() { return INSTANCE; }
        String name() { return "sentinel"; }
    }

    /** The SIBLING that allocates Sentinel. Holds real mutable state so R8 cannot
     *  inline it away, and reads the sentinel on both branches so neither the
     *  field nor the allocation is dead. */
    static final class Memo<T> implements Supplier<T> {
        private final Sentinel lock = new Sentinel();
        private volatile boolean done;
        private T value;
        private final T seed;
        Memo(T seed) { this.seed = seed; }
        @Override public T get() {
            if (!done) {
                synchronized (lock) {
                    if (!done) { value = seed; done = true; return tag(value); }
                }
            }
            return tag(value);
        }
        @SuppressWarnings("unchecked")
        private T tag(T v) { return (T) (v + ":" + lock.name()); }
    }

    static <T> Supplier<T> of(T seed) { return new Memo<>(seed); }
}

/**
 * A Serializable singleton with a private no-arg constructor -- the exact idiom
 * `dgo` came from, kept as a second instance of the shape whose allocation stays
 * INSIDE the class (so the same-class case must not be regressed into a pad).
 */
final class Singleton implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    private static final Singleton INSTANCE = new Singleton();
    private Singleton() {}
    static Singleton get() { return INSTANCE; }
    String tag() { return "singleton"; }
    private Object readResolve() { return INSTANCE; }
}

/** A separate top-level class reaching Holder only through package-private API:
 *  the control case that must keep working unchanged. */
final class Peer {
    static String viaOuter() { return Holder.readInnerPrivates() + "|" + Holder.crossSibling(); }
}
