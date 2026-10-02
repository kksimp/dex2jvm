package io.github.kksimp.dex2jvm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The inferred JVM-level type of a single Dalvik register at a single program point.
 *
 * <p>WHY THIS CLASS EXISTS AT ALL. Dalvik registers are untyped and aggressively reused: the
 * same physical register v3 can hold an int on one path, a float on another, and a reference on
 * a third. The JVM is the opposite: every opcode is typed ({@code iload} vs {@code fload} vs
 * {@code aload}) and the local variable read must match what was written. So a DEX to JVM
 * converter cannot emit a single instruction without first answering "what is in v3 here?".
 * Getting that answer wrong does not fail loudly; it silently emits an {@code iload} where an
 * {@code fload} belonged and the app computes garbage. That silent failure mode is the entire
 * reason for this file.
 *
 * <h2>The type is FOUR independent lattices, not one</h2>
 *
 * A register's inferred type is a tuple of four components, each with its OWN lattice and its
 * OWN merge direction. Conflating them is the most common way to break this code, so they are
 * documented separately below. Read the merge directions carefully: two of them widen and two
 * of them narrow, and that is correct.
 *
 * <ol>
 *   <li>{@link #scalar()} - a BITSET of the JVM scalar types this value may legally be read as.
 *       Merge is intersection (a NARROWING meet). Drives opcode selection. Mandatory.</li>
 *   <li>{@link #arrayKind()} / {@link #arrayDescriptor()} - the array descriptor, when the value
 *       is known to be an array. Merge WIDENS toward "unknown". Drives {@code aaload} vs
 *       {@code baload} vs {@code iaload} element opcode selection.</li>
 *   <li>{@link #ref()} - the reference type (null / a class / uninitialized). Merge is a
 *       least-upper-bound that WIDENS toward {@code java/lang/Object}. Needed only for
 *       StackMapTable emission and checkcast placement, never for opcode selection.</li>
 *   <li>{@link #tainted()} - whether ART narrowed this value via an implicit cast. Merge is OR
 *       (WIDENS). Drives defensive {@code checkcast} emission.</li>
 * </ol>
 *
 * <h2>The scalar bitset, and why merge is an AND</h2>
 *
 * <pre>
 *   INT    = 1        FLOAT = 2       OBJ = 4       LONG = 8       DOUBLE = 16
 *
 *   ZERO    = INT|FLOAT|OBJ  (7)   the literal `const 0`: it is a valid int, a valid float
 *                                   bit pattern, AND a valid null reference, all at once
 *   CONST32 = INT|FLOAT      (3)   a non-zero 32-bit literal: int or float, never a reference
 *   CONST64 = LONG|DOUBLE    (24)  a 64-bit literal: long or double
 *   ANY     = ZERO|CONST64   (31)  "could be anything" - the IDENTITY for merge
 *   NONE    = 0                    "no consistent reading exists" - ABSORBING for merge
 * </pre>
 *
 * The bitset is the SET OF JVM TYPES THIS VALUE MAY BE READ AS, not "the type it is". At a
 * control flow merge, a subsequent read must be legal on every incoming path, so the answer is
 * the INTERSECTION of the incoming sets. Hence merge is a bitwise AND and the lattice is a meet
 * semilattice whose top is {@link #ANY} (31, every reading legal) and whose bottom is
 * {@link #NONE} (0, no reading legal, i.e. the register is dead or inconsistent here).
 *
 * <p>This inverts the intuition that a constant named ANY should be the "bad" value and NONE the
 * "good" one. It is the other way around. {@link #ANY} is deliberately produced for provably
 * unreachable code (see the array-of-null case in TypeInference) precisely because ANDing with
 * 31 is a no-op and therefore cannot pollute a reachable path.
 *
 * <p>Termination: bits are only ever cleared by merge, and there are five of them, so each
 * register can descend at most five times. The fixpoint always converges.
 *
 * <h2>The consequence for the code writer: ONE Dalvik register is UP TO FIVE JVM locals</h2>
 *
 * This is the single most important fact for whoever emits bytecode from this analysis, and
 * missing it produces exactly the silent miscompile described above. Because a Dalvik register
 * can be simultaneously readable as an int and as a float, the converter must NOT try to pick
 * one JVM local slot per Dalvik register. It maps the PAIR (dalvikRegister, scalarBit) to a JVM
 * local slot, so v3 may occupy separate slots for its int reading and its float reading. A
 * {@code move} whose source has two bits set therefore emits TWO load/store pairs, one per bit.
 * A later register-coalescing pass can collapse slots that are never both live; correctness does
 * not depend on it. The reference implementation (enjarify jvm/writeir.py visitMove) does the
 * same thing, and {@link #scalarBits(int)} exists to make iterating the set bits easy.
 *
 * <h2>The reference lattice is deliberately weak, and that is safe</h2>
 *
 * {@link Ref} widens to {@code java/lang/Object} whenever two different classes merge and no
 * {@link ClassHierarchy} oracle is supplied. That is not a shortcut; it is what the JVM verifier
 * itself does for interfaces. JVMS 4.10.1.2 defines assignability so that ANY class is assignable
 * to ANY interface type without checking the hierarchy (the verifier is deliberately unsound for
 * interfaces and defers the real check to the runtime {@code checkcast}/{@code invokeinterface}).
 * So collapsing an unknown merge to Object can never cause a wrong opcode, because opcode
 * selection reads {@link #scalar()} and never reads {@link #ref()}.
 *
 * <p>This matters in practice: an APK's DEX is often converted against an incomplete android and
 * androidx classpath, so the full inheritance hierarchy is frequently NOT available at
 * conversion time. Requiring exact reference LUBs would make the converter fail on every app
 * that references a class the classpath does not provide. Supplying a {@link ClassHierarchy}
 * is therefore optional and purely a precision improvement.
 *
 * <p>Instances are immutable, interned (see {@link #of}), and safe to compare with {@code ==}.
 * The fixpoint loop relies on that identity comparison to detect that nothing changed.
 */
public final class DexType {

    // ------------------------------------------------------------------
    // Scalar bitset
    // ------------------------------------------------------------------

    /** No legal reading. Bottom of the scalar meet lattice. Register is dead or inconsistent. */
    public static final int NONE = 0;
    public static final int INT = 1 << 0;
    public static final int FLOAT = 1 << 1;
    public static final int OBJ = 1 << 2;
    public static final int LONG = 1 << 3;
    public static final int DOUBLE = 1 << 4;

    /** `const 0`: simultaneously a valid int, float and null reference. */
    public static final int ZERO = INT | FLOAT | OBJ;
    /** A non-zero 32-bit literal: readable as int or float, never as a reference. */
    public static final int CONST32 = INT | FLOAT;
    /** A 64-bit literal: readable as long or double. */
    public static final int CONST64 = LONG | DOUBLE;
    /** Every reading legal. Top of the meet lattice, and the identity for {@link #mergeScalar}. */
    public static final int ANY = ZERO | CONST64;

    /** True if the scalar set denotes a 64-bit value, which occupies a register PAIR in Dalvik. */
    public static boolean isWide(int scalarSet) {
        return (scalarSet & CONST64) != 0;
    }

    /**
     * Merge of two scalar sets: intersection. See the class comment for why this narrows rather
     * than widens.
     */
    public static int mergeScalar(int a, int b) {
        return a & b;
    }

    /**
     * The scalar set implied by a JVM/DEX type descriptor. Dalvik and the JVM verifier both
     * collapse boolean, byte, char and short to int (JVMS 4.10.1.2 lists only int, float, long,
     * double and reference as verification types for values), so Z/B/C/S all map to {@link #INT}.
     */
    public static int scalarFromDescriptor(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) {
            return NONE;
        }
        switch (descriptor.charAt(0)) {
            case 'Z': case 'B': case 'C': case 'S': case 'I': return INT;
            case 'F': return FLOAT;
            case 'J': return LONG;
            case 'D': return DOUBLE;
            case 'L': case '[': return OBJ;
            case 'V': return NONE;
            default: return NONE;
        }
    }

    /**
     * The individual bits set in a scalar set, in a stable order (int, long, float, double, obj).
     * The code writer iterates this to emit one JVM local access per live reading. The order
     * matches the JVM's own ILFDA opcode ordering closely enough to keep generated code readable.
     */
    public static int[] scalarBits(int scalarSet) {
        int n = Integer.bitCount(scalarSet & ANY);
        int[] out = new int[n];
        int i = 0;
        if ((scalarSet & INT) != 0) out[i++] = INT;
        if ((scalarSet & LONG) != 0) out[i++] = LONG;
        if ((scalarSet & FLOAT) != 0) out[i++] = FLOAT;
        if ((scalarSet & DOUBLE) != 0) out[i++] = DOUBLE;
        if ((scalarSet & OBJ) != 0) out[i++] = OBJ;
        return out;
    }

    /**
     * Pick ONE scalar reading out of a set, preferring {@code preferred} when it is legal.
     * Used where the JVM forces a single choice, for example {@code if-eq} on two registers that
     * are both {@link #ZERO}: either {@code if_icmpeq} or {@code if_acmpeq} is correct, so the
     * caller states a preference and gets a legal answer or {@link #NONE}.
     */
    public static int pickScalar(int scalarSet, int preferred) {
        if ((scalarSet & preferred) != 0) {
            return preferred;
        }
        int[] bits = scalarBits(scalarSet);
        return bits.length == 0 ? NONE : bits[0];
    }

    public static String scalarToString(int scalarSet) {
        if (scalarSet == NONE) return "none";
        if (scalarSet == ANY) return "any";
        if (scalarSet == ZERO) return "zero";
        if (scalarSet == CONST32) return "const32";
        if (scalarSet == CONST64) return "const64";
        StringBuilder sb = new StringBuilder();
        if ((scalarSet & INT) != 0) sb.append("I");
        if ((scalarSet & FLOAT) != 0) sb.append("F");
        if ((scalarSet & OBJ) != 0) sb.append("A");
        if ((scalarSet & LONG) != 0) sb.append("J");
        if ((scalarSet & DOUBLE) != 0) sb.append("D");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Array descriptor lattice
    // ------------------------------------------------------------------

    /**
     * The value is definitely null, so it is compatible with EVERY array type. Bottom of the
     * array lattice and the identity for {@link #mergeArray}.
     */
    public static final int ARRAY_NULL = 0;
    /** The exact array descriptor is known, and it is a primitive array such as {@code [[C}. */
    public static final int ARRAY_EXACT = 1;
    /**
     * Either not an array at all, or an array of references whose element type we deliberately do
     * not track. Top of the array lattice.
     *
     * <p>The name matters. enjarify calls this state INVALID, which reads like an error but is
     * not: it is the NORMAL state for every {@code Object[]}, and the element accessor maps it to
     * {@code aaload}/{@code aastore}. Anyone debugging a miscompile who sees "INVALID" and
     * assumes something went wrong will chase the wrong thing, so it is named UNKNOWN here.
     */
    public static final int ARRAY_UNKNOWN = 2;

    /**
     * Merge of two array states. Unlike {@link #mergeScalar} this WIDENS: the result is the
     * weakest claim true on both paths. Null is the identity because a null reference is a legal
     * value of every array type (JVMS 4.10.1.2: null is assignable to any class or array type),
     * so merging null with {@code [I} still leaves us able to emit {@code iaload}.
     */
    public static int mergeArray(int kindA, String descA, int kindB, String descB, int[] outKind) {
        if (kindA == ARRAY_NULL) { outKind[0] = kindB; return 0; }
        if (kindB == ARRAY_NULL) { outKind[0] = kindA; return 0; }
        if (kindA == ARRAY_EXACT && kindB == ARRAY_EXACT && Objects.equals(descA, descB)) {
            outKind[0] = ARRAY_EXACT;
            return 0;
        }
        outKind[0] = ARRAY_UNKNOWN;
        return 0;
    }

    /**
     * Intersect two array states. This is the dual of {@link #mergeArray} and is used by
     * {@code check-cast}, which supplies new information rather than joining two paths. If the
     * two claims are incompatible the value can only ever be null at this point (the cast would
     * throw otherwise), which is exactly {@link #ARRAY_NULL}.
     */
    public static int narrowArrayKind(int kindA, String descA, int kindB, String descB) {
        if (kindA == ARRAY_UNKNOWN) return kindB;
        if (kindB == ARRAY_UNKNOWN) return kindA;
        if (kindA == ARRAY_EXACT && kindB == ARRAY_EXACT) {
            return Objects.equals(descA, descB) ? ARRAY_EXACT : ARRAY_NULL;
        }
        return ARRAY_NULL;
    }

    /**
     * The array state implied by a descriptor. Only PRIMITIVE arrays are tracked exactly; an
     * array of references becomes {@link #ARRAY_UNKNOWN} because the element opcode
     * ({@code aaload}) is the same regardless of element class, so the extra precision would buy
     * nothing and would cost a merge that can never converge on a deep hierarchy.
     */
    public static int arrayKindFromDescriptor(String descriptor) {
        if (descriptor == null || !descriptor.startsWith("[") || descriptor.endsWith(";")) {
            return ARRAY_UNKNOWN;
        }
        return ARRAY_EXACT;
    }

    // ------------------------------------------------------------------
    // Reference lattice
    // ------------------------------------------------------------------

    /** Optional oracle for exact reference least-upper-bounds. All methods may return null. */
    public interface ClassHierarchy {
        /** Internal name (e.g. {@code java/lang/String}) of the superclass, or null if unknown. */
        String superclassOf(String internalName);
        /** True if the named type is an interface. Unknown types should answer false. */
        boolean isInterface(String internalName);
    }

    public static final String OBJECT_NAME = "java/lang/Object";

    /**
     * A reference type in the JVMS 4.10.1.2 verification type system, restricted to the cases a
     * DEX to JVM converter can actually observe.
     */
    public static final class Ref {
        /** Not a reference on at least one incoming path. Absorbing. */
        public static final int KIND_NONE = 0;
        /** The null type. Assignable to every class and array type (JVMS 4.10.1.2). */
        public static final int KIND_NULL = 1;
        /** An initialized reference of the named class, or an array descriptor. */
        public static final int KIND_CLASS = 2;
        /** Result of {@code new-instance} at {@link #newOffset}, before its {@code <init>} ran. */
        public static final int KIND_UNINIT = 3;
        /** The receiver of a constructor before it chains to a super/this {@code <init>}. */
        public static final int KIND_UNINIT_THIS = 4;
        /**
         * An initialized value merged with an uninitialized one, or two different
         * {@code new-instance} sites merged. JVMS forbids USING such a value, but producing it is
         * not by itself fatal: R8 emits dead paths where this legitimately happens, so this is
         * recorded rather than thrown, and only becomes a diagnostic if the value is read.
         */
        public static final int KIND_CONFLICT = 5;

        public final int kind;
        /** Internal class name or array descriptor for {@link #KIND_CLASS}, else null. */
        public final String name;
        /** DEX offset of the originating {@code new-instance} for {@link #KIND_UNINIT}, else -1. */
        public final int newOffset;

        private final int hash;

        /**
         * Memo for {@link DexType#slotView}, which {@link RegisterState#get} calls on every frame
         * slot and every reference use. Without it that is a {@link #POOL} hash lookup on the
         * hottest path in the converter. Benign race: two threads compute the same interned
         * instance, so whichever wins is the same object.
         */
        private DexType slotView;
        /** Memo for {@code RegisterState.slotAsValue}, on the same hot path. */
        DexType slotAsValue;

        private Ref(int kind, String name, int newOffset) {
            this.kind = kind;
            this.name = name;
            this.newOffset = newOffset;
            this.hash = kind * 31 * 31 + (name == null ? 0 : name.hashCode()) * 31 + newOffset;
        }

        public static final Ref NONE_REF = new Ref(KIND_NONE, null, -1);
        public static final Ref NULL_REF = new Ref(KIND_NULL, null, -1);
        public static final Ref OBJECT = new Ref(KIND_CLASS, OBJECT_NAME, -1);
        public static final Ref UNINIT_THIS = new Ref(KIND_UNINIT_THIS, null, -1);
        public static final Ref CONFLICT = new Ref(KIND_CONFLICT, null, -1);

        private static final Map<Ref, Ref> POOL = new ConcurrentHashMap<>();

        private static Ref intern(Ref r) {
            Ref prior = POOL.putIfAbsent(r, r);
            return prior != null ? prior : r;
        }

        public static Ref ofClass(String internalName) {
            if (internalName == null || OBJECT_NAME.equals(internalName)) {
                return OBJECT;
            }
            return intern(new Ref(KIND_CLASS, internalName, -1));
        }

        public static Ref uninitialized(int newInstanceOffset) {
            return intern(new Ref(KIND_UNINIT, null, newInstanceOffset));
        }

        public boolean isUninitialized() {
            return kind == KIND_UNINIT || kind == KIND_UNINIT_THIS;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Ref)) return false;
            Ref r = (Ref) o;
            return kind == r.kind && newOffset == r.newOffset && Objects.equals(name, r.name);
        }

        @Override public int hashCode() { return hash; }

        @Override public String toString() {
            switch (kind) {
                case KIND_NONE: return "-";
                case KIND_NULL: return "null";
                case KIND_CLASS: return name;
                case KIND_UNINIT: return "uninit@" + newOffset;
                case KIND_UNINIT_THIS: return "uninitThis";
                default: return "conflict";
            }
        }
    }

    /**
     * Least upper bound of two reference types at a control flow merge.
     *
     * <p>The rules, and the JVMS clause each one implements:
     * <ul>
     *   <li>null joined with anything yields the anything. JVMS 4.10.1.2 makes the null type
     *       assignable to every class and array type, so no information is lost.</li>
     *   <li>Two different classes yield their common superclass when {@code hierarchy} can tell
     *       us, and {@code java/lang/Object} otherwise. Object is always a sound answer: JVMS
     *       4.10.1.2's assignability rule for interfaces already treats an arbitrary class as
     *       assignable to any interface, so the verification type system cannot distinguish a
     *       precise interface LUB from Object anyway. Two different interfaces, or an interface
     *       joined with a class, therefore always yield Object.</li>
     *   <li>Two {@code new-instance} results join only if they came from the SAME instruction.
     *       Different offsets, or an uninitialized value joined with an initialized one, yield
     *       {@link Ref#CONFLICT}: JVMS makes using such a value a verification error because the
     *       verifier cannot know whether {@code <init>} has run.</li>
     * </ul>
     */
    public static Ref mergeRef(Ref a, Ref b, ClassHierarchy hierarchy) {
        if (a == b || a.equals(b)) {
            return a;
        }
        if (a.kind == Ref.KIND_NONE || b.kind == Ref.KIND_NONE) {
            return Ref.NONE_REF;
        }
        if (a.kind == Ref.KIND_CONFLICT || b.kind == Ref.KIND_CONFLICT) {
            return Ref.CONFLICT;
        }
        // The uninitialized test comes BEFORE the null shortcuts, and the order is
        // load bearing. JVMS 4.10.1.2 gives null exactly three assignability
        // clauses -- isAssignable(null, class(_,_)), isAssignable(null,
        // arrayOf(_)), and isAssignable(null, X) via java/lang/Object -- and none
        // of them reaches uninitialized(Offset) or uninitializedThis. So "null
        // joined with anything yields the anything" is true for class and array
        // types and FALSE for an uninitialized one: there is no representable
        // upper bound of null and uninitialized(N) short of top.
        //
        // Answering uninitialized(N) there put that type in the frame and the
        // verifier rejected the null path into it:
        //
        //   com/ironsource/sdk/service/Gibberish -- Stack map does not match the
        //   one at exception handler N. Type null (current frame, locals[3]) is
        //   not assignable to uninitialized 29 (stack map, locals[3])
        //
        // CONFLICT renders as top (Translator.vtypeFor), which every incoming
        // type including null satisfies. An identical pair of uninitialized refs
        // was already returned by the equality test at the top of this method, so
        // this only fires on a genuine mismatch.
        if (a.isUninitialized() || b.isUninitialized()) {
            return Ref.CONFLICT;
        }
        if (a.kind == Ref.KIND_NULL) return b;
        if (b.kind == Ref.KIND_NULL) return a;
        return Ref.ofClass(lubClass(a.name, b.name, hierarchy));
    }

    /** Common supertype of two internal class names, or {@code java/lang/Object} if unknown. */
    private static String lubClass(String a, String b, ClassHierarchy hierarchy) {
        if (a == null || b == null) return OBJECT_NAME;
        if (a.equals(b)) return a;
        if (hierarchy == null) return OBJECT_NAME;
        // Arrays and interfaces both collapse to Object. Arrays of different element types share
        // only Object, Cloneable and Serializable, and the latter two are interfaces which the
        // verifier already equates with Object (see mergeRef's doc).
        if (a.startsWith("[") || b.startsWith("[")) return OBJECT_NAME;
        if (hierarchy.isInterface(a) || hierarchy.isInterface(b)) return OBJECT_NAME;

        Set<String> chainA = new HashSet<>();
        String cur = a;
        int guard = 0;
        while (cur != null && guard++ < 256 && chainA.add(cur)) {
            cur = hierarchy.superclassOf(cur);
        }
        if (!chainA.contains(OBJECT_NAME)) {
            // The oracle could not reach Object, so the chain is incomplete and any answer other
            // than Object would be a guess.
            return OBJECT_NAME;
        }
        cur = b;
        guard = 0;
        Set<String> seenB = new HashSet<>();
        while (cur != null && guard++ < 256 && seenB.add(cur)) {
            if (chainA.contains(cur)) {
                return cur;
            }
            cur = hierarchy.superclassOf(cur);
        }
        return OBJECT_NAME;
    }

    /** The reference type implied by a descriptor, or {@link Ref#NONE_REF} for primitives. */
    public static Ref refFromDescriptor(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) {
            return Ref.NONE_REF;
        }
        char c = descriptor.charAt(0);
        if (c == '[') {
            return Ref.ofClass(descriptor);
        }
        if (c == 'L' && descriptor.endsWith(";")) {
            return Ref.ofClass(descriptor.substring(1, descriptor.length() - 1));
        }
        return Ref.NONE_REF;
    }

    // ------------------------------------------------------------------
    // The combined value
    // ------------------------------------------------------------------

    private final int scalar;
    private final int arrayKind;
    private final String arrayDescriptor;
    private final Ref ref;
    private final boolean tainted;
    private final boolean wideHigh;
    private final int hash;

    private DexType(int scalar, int arrayKind, String arrayDescriptor, Ref ref,
                    boolean tainted, boolean wideHigh) {
        this.scalar = scalar;
        this.arrayKind = arrayKind;
        this.arrayDescriptor = arrayDescriptor;
        this.ref = ref;
        this.tainted = tainted;
        this.wideHigh = wideHigh;
        int h = scalar;
        h = h * 31 + arrayKind;
        h = h * 31 + (arrayDescriptor == null ? 0 : arrayDescriptor.hashCode());
        h = h * 31 + ref.hashCode();
        h = h * 31 + (tainted ? 1 : 0);
        h = h * 31 + (wideHigh ? 1 : 0);
        this.hash = h;
    }

    private static final Map<DexType, DexType> POOL = new ConcurrentHashMap<>();

    /**
     * Canonical instance for the given components. Interning is what makes {@code ==} a valid
     * comparison, which in turn is what lets {@link RegisterState} detect "nothing changed" in
     * O(registers) reference comparisons instead of deep equality. The pool is bounded by the
     * number of distinct type tuples an APK produces, which is small.
     */
    public static DexType of(int scalar, int arrayKind, String arrayDescriptor, Ref ref,
                             boolean tainted, boolean wideHigh) {
        if (arrayKind != ARRAY_EXACT) {
            arrayDescriptor = null;
        }
        if (ref == null) {
            ref = Ref.NONE_REF;
        }
        // A value with no legal reading carries no useful sub-state; normalising here keeps the
        // pool small and makes the "dead register" case a single identity. Both DEAD and
        // WIDE_HIGH are built with the private constructor and so are NOT in the pool; returning
        // them explicitly is what keeps == a valid comparison for them. Without this, a merge
        // that produced a wide-high would mint a second, equal-but-distinct instance and the
        // fixpoint's identity check would never report convergence.
        if (scalar == NONE) {
            return wideHigh ? WIDE_HIGH : DEAD;
        }
        DexType candidate = new DexType(scalar, arrayKind, arrayDescriptor, ref, tainted, wideHigh);
        DexType prior = POOL.putIfAbsent(candidate, candidate);
        return prior != null ? prior : candidate;
    }

    /** No legal reading: the register is uninitialized, out of scope, or type-inconsistent. */
    public static final DexType DEAD =
            new DexType(NONE, ARRAY_UNKNOWN, null, Ref.NONE_REF, false, false);

    /**
     * The high half of a 64-bit register pair. Like {@link #DEAD} it has no legal reading of its
     * own, but it is tracked distinctly because a StackMapTable must emit an explicit {@code top}
     * entry after every long/double local, and "second half of a wide" and "never written" are
     * different things to a verifier.
     */
    public static final DexType WIDE_HIGH =
            new DexType(NONE, ARRAY_UNKNOWN, null, Ref.NONE_REF, false, true);

    public static final DexType INT_TYPE = of(INT, ARRAY_UNKNOWN, null, Ref.NONE_REF, false, false);
    public static final DexType FLOAT_TYPE = of(FLOAT, ARRAY_UNKNOWN, null, Ref.NONE_REF, false, false);
    public static final DexType LONG_TYPE = of(LONG, ARRAY_UNKNOWN, null, Ref.NONE_REF, false, false);
    public static final DexType DOUBLE_TYPE = of(DOUBLE, ARRAY_UNKNOWN, null, Ref.NONE_REF, false, false);
    public static final DexType CONST32_TYPE = of(CONST32, ARRAY_UNKNOWN, null, Ref.NONE_REF, false, false);
    public static final DexType CONST64_TYPE = of(CONST64, ARRAY_UNKNOWN, null, Ref.NONE_REF, false, false);
    /** `const 0`: int zero, float zero, and null all at once. Note the array state is NULL. */
    public static final DexType ZERO_TYPE = of(ZERO, ARRAY_NULL, null, Ref.NULL_REF, false, false);
    /** Unreachable-code filler. ANDs away to nothing, so it can never pollute a live path. */
    public static final DexType ANY_TYPE = of(ANY, ARRAY_NULL, null, Ref.NULL_REF, false, false);
    public static final DexType OBJECT_TYPE = of(OBJ, ARRAY_UNKNOWN, null, Ref.OBJECT, false, false);

    /**
     * The type of the JVM LOCAL SLOTS a Dalvik register owns, as opposed to the type of the VALUE
     * the register holds. See {@link RegisterState} for why those are different things.
     *
     * <p>Every scalar bit is set, because {@code Translator.zeroInitRegisterSlots} stores a
     * definite value of the matching kind into every allocated slot before the body runs and only
     * a store of THAT kind can ever overwrite it: the (v, INT) slot always holds an int, the
     * (v, LONG) slot always holds a long, and so on. So a slot is never {@code top} at run time,
     * whatever the value lattice says about the register itself. The only component that varies is
     * the reference in the (v, OBJ) slot, which is the argument.
     *
     * <p>The array component is deliberately {@link #ARRAY_UNKNOWN} rather than
     * {@link #ARRAY_NULL}: a slot view is only ever consulted for its {@link #ref}, and claiming
     * ARRAY_NULL would make {@link #isDefinitelyNull} answer true for a slot that holds a real
     * array.
     */
    public static DexType slotView(Ref objRef) {
        Ref r = objRef == null ? Ref.NULL_REF : objRef;
        DexType cached = r.slotView;
        if (cached != null) {
            return cached;
        }
        DexType v = of(ANY, ARRAY_UNKNOWN, null, r, false, false);
        r.slotView = v;
        return v;
    }

    /**
     * The reference a store of this value leaves in the register's OBJ slot.
     *
     * <p>Only meaningful when the value's scalar set contains {@link #OBJ}, because otherwise the
     * code writer emits no {@code astore} and the slot keeps whatever it held. A value that is
     * readable as an object but carries no reference information yields {@code java/lang/Object},
     * which is the weakest claim that is still TRUE; answering {@link Ref#NULL_REF} there would
     * tell the verifier the slot is null when it holds a real object, and answering
     * {@link Ref#NONE_REF} would render as {@code top} and lose the slot.
     */
    public static Ref objSlotRefOf(DexType value) {
        Ref r = value.ref();
        if (r == null || r.kind == Ref.KIND_NONE) {
            return Ref.OBJECT;
        }
        return r;
    }

    /** A plain reference of unknown class, which is what most instructions produce. */
    public static DexType object() {
        return OBJECT_TYPE;
    }

    public static DexType objectOfClass(String internalName) {
        return of(OBJ, ARRAY_UNKNOWN, null, Ref.ofClass(internalName), false, false);
    }

    /**
     * The type a value of the given descriptor has. Callers must additionally place
     * {@link #WIDE_HIGH} in the following register when {@link #isWide} is true of the result.
     */
    public static DexType fromDescriptor(String descriptor) {
        int sc = scalarFromDescriptor(descriptor);
        if (sc == NONE) {
            return DEAD;
        }
        int ak = arrayKindFromDescriptor(descriptor);
        return of(sc, ak, ak == ARRAY_EXACT ? descriptor : null,
                refFromDescriptor(descriptor), false, false);
    }

    public int scalar() { return scalar; }
    public int arrayKind() { return arrayKind; }
    public String arrayDescriptor() { return arrayDescriptor; }
    public Ref ref() { return ref; }
    /** True when ART narrowed this value with an implicit cast, so uses need a checkcast. */
    public boolean tainted() { return tainted; }
    public boolean isWideHigh() { return wideHigh; }
    public boolean isDead() { return scalar == NONE; }
    /** True when the value is provably null, so a use can emit {@code aconst_null} instead. */
    public boolean isDefinitelyNull() { return arrayKind == ARRAY_NULL && (scalar & OBJ) != 0; }

    public DexType withTaint(boolean t) {
        if (t == tainted) return this;
        return of(scalar, arrayKind, arrayDescriptor, ref, t, wideHigh);
    }

    public DexType withRef(Ref r) {
        if (r == ref) return this;
        return of(scalar, arrayKind, arrayDescriptor, r, tainted, wideHigh);
    }

    public DexType withArray(int kind, String descriptor) {
        return of(scalar, kind, descriptor, ref, tainted, wideHigh);
    }

    /**
     * Intersect this value's array state with the type named by a cast, returning a value whose
     * array component is narrowed and whose other components are untouched.
     *
     * <p>The descriptor bookkeeping here is the whole point of having a helper. When the cast is
     * to an OBJECT array (say {@code [Ljava/lang/Object;}) the cast contributes
     * {@link #ARRAY_UNKNOWN}, so the intersection keeps the descriptor the value ALREADY had (say
     * {@code [[B}). Writing the cast's own descriptor into the result there is wrong and produces
     * a value that claims to be an Object array when it is really a byte-array array, which then
     * selects {@code aaload} where {@code baload} belonged.
     */
    public DexType narrowArrayTo(String castDescriptor) {
        int castKind = arrayKindFromDescriptor(castDescriptor);
        String castDesc = castKind == ARRAY_EXACT ? castDescriptor : null;
        int kind = narrowArrayKind(arrayKind, arrayDescriptor, castKind, castDesc);
        String desc = null;
        if (kind == ARRAY_EXACT) {
            desc = castKind == ARRAY_EXACT ? castDesc : arrayDescriptor;
        }
        return withArray(kind, desc);
    }

    /**
     * The (scalar, arrayState) of an element of this array. Only meaningful when the value is an
     * array. {@link #ARRAY_UNKNOWN} yields an object element, which is the {@code aaload} case.
     */
    public DexType arrayElement() {
        if (arrayKind != ARRAY_EXACT) {
            // arrayKind deliberately tracks only PRIMITIVE arrays exactly (see
            // arrayKindFromDescriptor: widening the array LATTICE would make
            // narrowArrayKind treat a legal (Foo[]) cast of an Object[] as
            // "provably null"). The REFERENCE side has no such problem -- it
            // already carries the full descriptor -- so recover the element
            // class from there instead.
            //
            // This is what lets an aaload from [Lcom/dotgears/m; be typed as
            // com/dotgears/m rather than java/lang/Object. Object is sound for
            // opcode selection, which is why it stood for so long, but it is
            // what HotSpot's split verifier rejects at the eventual areturn:
            // "Bad return type ... Type 'java/lang/Object' (current frame,
            // stack[0]) is not assignable to 'com/dotgears/m'".
            if (ref.kind == Ref.KIND_CLASS && ref.name != null
                    && ref.name.length() > 1 && ref.name.charAt(0) == '[') {
                DexType e = fromDescriptor(ref.name.substring(1));
                // A primitive element would already have been ARRAY_EXACT, so
                // only a reference element can be recovered here.
                if (e.scalar() == OBJ) {
                    return e;
                }
            }
            return OBJECT_TYPE;
        }
        String elem = arrayDescriptor.substring(1);
        return fromDescriptor(elem);
    }

    /** The one-character element descriptor for opcode selection, or null for {@code aaload}. */
    public String arrayElementDescriptor() {
        if (arrayKind != ARRAY_EXACT) {
            return null;
        }
        return arrayDescriptor.substring(1);
    }

    /**
     * Merge two types at a control flow join. Each component uses its own lattice; see the class
     * comment. Returns {@code this} unchanged when the merge is a no-op, which is what the
     * fixpoint loop uses to decide it has converged.
     */
    public DexType merge(DexType other, ClassHierarchy hierarchy) {
        if (this == other) {
            return this;
        }
        int sc = mergeScalar(scalar, other.scalar);
        boolean wh = wideHigh && other.wideHigh;
        if (sc == NONE && !wh) {
            return DEAD;
        }
        int[] outKind = new int[1];
        mergeArray(arrayKind, arrayDescriptor, other.arrayKind, other.arrayDescriptor, outKind);
        int ak = outKind[0];
        String ad = ak == ARRAY_EXACT
                ? (arrayKind == ARRAY_EXACT ? arrayDescriptor : other.arrayDescriptor)
                : null;
        Ref r = mergeRef(ref, other.ref, hierarchy);
        boolean t = tainted || other.tainted;
        DexType merged = of(sc, ak, ad, r, t, wh);
        return merged == this ? this : merged;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DexType)) return false;
        DexType d = (DexType) o;
        return scalar == d.scalar && arrayKind == d.arrayKind && tainted == d.tainted
                && wideHigh == d.wideHigh && ref.equals(d.ref)
                && Objects.equals(arrayDescriptor, d.arrayDescriptor);
    }

    @Override public int hashCode() { return hash; }

    @Override public String toString() {
        if (wideHigh) return "top";
        if (scalar == NONE) return "dead";
        StringBuilder sb = new StringBuilder(scalarToString(scalar));
        if (arrayKind == ARRAY_EXACT) {
            sb.append('{').append(arrayDescriptor).append('}');
        } else if (arrayKind == ARRAY_NULL && (scalar & OBJ) != 0) {
            sb.append("{null}");
        }
        if ((scalar & OBJ) != 0 && ref.kind != Ref.KIND_NONE && ref != Ref.OBJECT) {
            sb.append('<').append(ref).append('>');
        }
        if (tainted) sb.append('!');
        return sb.toString();
    }

    /** Diagnostic helper: a compact rendering of a whole register file. */
    public static String describe(DexType[] regs) {
        StringBuilder sb = new StringBuilder("[");
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < regs.length; i++) {
            DexType t = regs[i];
            if (t == null || t == DEAD) continue;
            parts.add("v" + i + "=" + t);
        }
        sb.append(String.join(" ", parts));
        return sb.append(']').toString();
    }
}
