// Semantic cover for MethodSplitter: a method cut into a forward-only CHAIN of
// synthetic methods must compute exactly what it computed in one piece.
//
// HOW TO RUN THIS SO IT MEANS ANYTHING. The real trigger is a method over 65535
// bytes (JVMS 4.9.1), and javac REFUSES to compile one ("code too large"), so
// the oracle side of this gate can never reach the real threshold. Run the
// suite with the threshold lowered instead:
//
//     DEX2JVM_SPLIT_FORCE=220 tests/semantic/run.sh
//
// which makes every method over 220 bytes get chained, so this case (and every
// other case in the suite) is actually pushed through the transform. Without
// that variable this case still passes, it just is not testing anything. Set
// DEX2JVM_SPLIT_DEBUG=1 alongside it to see, per method, how many parts and
// entry points the planner chose -- a case that silently REFUSES to split is
// testing nothing either, and the refusal reasons are printed.
//
// WHAT EACH SECTION IS FOR. The chain splitter has to get eight things right,
// and each has a section here that fails loudly if it does not:
//
//   multi-entry dispatch  a forward branch that crosses a cut becomes a call
//                         into the next part with an ENTRY ID, and the part
//                         switches on it. `forwardJumps` sends control across
//                         the same cut from many different places and prints
//                         which one it came from, so a mis-numbered id shows up
//                         as the wrong value rather than as a crash.
//   liveness              only the values LIVE at an entry are handed over, and
//                         a value dropped by mistake still verifies (its slot
//                         was zero-initialized) and quietly computes zero.
//                         `carry` keeps values of every width alive across many
//                         cuts and prints all of them.
//   the parallel move     a part's arguments occupy slots 0..n-1 while the body
//                         reads the ORIGINAL register slots, which overlap.
//                         `widths` keeps many live values of every width across
//                         a cut so any ordering mistake corrupts a number.
//   exception ranges      MethodSplitter refuses a cut that a try range or its
//                         handlers straddle, and the portal's call must sit
//                         OUTSIDE every range or a handler in the earlier part
//                         would catch what the later part throws. `guarded`
//                         and `escapes` cover both directions.
//   back edges            a loop's back edge makes a cut illegal, so a method
//                         that is one big loop must not be cut inside it.
//                         `loops` is a long body with loops at both ends.
//   return values         every part returns the ORIGINAL return type, so a
//                         `return` in a hoisted block is emitted verbatim and a
//                         portal is `invokestatic; xreturn`. Every primitive
//                         width and a reference are covered, plus void.
//   `this`                every part is STATIC, so an instance method's
//                         receiver has to travel as an ordinary parameter --
//                         and the method that forced this transform
//                         (ChatMessageCell.setMessageContent) is an instance
//                         method. `mix`, `virt` and `instGuarded` read fields
//                         and make a virtual call AFTER a cut, so a dropped
//                         receiver is a NullPointerException rather than a
//                         quietly wrong number.
//   constructors          no cut may sit where local 0 is still
//                         uninitializedThis (JVMS 4.10.1.9), and no
//                         uninitialized `new` may cross one at all (JVMS
//                         4.9.2 forbids passing one, and a frame's
//                         Uninitialized_variable_info names a bytecode offset
//                         that means nothing in another part's byte stream).
//                         The two `Split(...)` constructors cover the this()
//                         and super() flavours.
//
// Output must be deterministic: no hash codes, no class names (R8 renames), no
// iteration order.
public class Split {

    static final StringBuilder LOG = new StringBuilder();
    static int voidSink;

    public static void main(String[] a) {
        p("forwardJumps.0", forwardJumps(0));
        p("forwardJumps.1", forwardJumps(1));
        p("forwardJumps.2", forwardJumps(2));
        p("forwardJumps.3", forwardJumps(3));
        p("forwardJumps.7", forwardJumps(7));

        p("carry.3", carry(3));
        p("carry.19", carry(19));

        p("widths", widths(7, 11L, 2.5f, 3.25d, "seed"));

        p("guarded.0", guarded(0));
        p("guarded.1", guarded(1));
        p("guarded.2", guarded(2));
        p("guarded.3", guarded(3));

        p("escapes.ok", escapes(0));
        p("escapes.boom", escapes(1));
        p("escapes.late", escapes(2));

        p("loops.5", loops(5));
        p("loops.12", loops(12));

        p("refs.0", refs(0));
        p("refs.1", refs(1));
        p("refs.2", refs(2));

        p("nulls.0", nulls(0));
        p("nulls.1", nulls(1));

        p("sw", sw(0) + "|" + sw(1) + "|" + sw(2) + "|" + sw(3) + "|" + sw(99));

        p("retLong", retLong(6));
        p("retDouble", retDouble(6));
        p("retFloat", retFloat(6));
        p("retBool.t", retBool(6));
        p("retBool.f", retBool(5));
        p("retRef", retRef(6));
        voidSink = 0;
        retVoid(6);
        p("retVoid", voidSink);

        // Instance methods and a constructor: the Telegram method that forced
        // this transform is an INSTANCE method, and every part is static, so
        // `this` has to travel as an ordinary parameter. A constructor is the
        // sharper case -- no cut may sit where `this` is still
        // uninitializedThis (JVMS 4.10.1.9), i.e. before the super() call.
        Split inst = new Split(9, "ctor");
        p("ctor.fields", inst.fields());
        p("inst.mix.0", inst.mix(0));
        p("inst.mix.1", inst.mix(1));
        p("inst.mix.2", inst.mix(2));
        p("inst.virt", inst.virt(4));
        p("sub.virt", new Sub(9, "sub").virt(4));
        p("inst.guarded", inst.instGuarded(1));

        p("ignoredCatch.0", ignoredCatch(0));
        p("ignoredCatch.1", ignoredCatch(1));

        p("strSwitch", strSwitch("aa") + "|" + strSwitch("bb") + "|"
                     + strSwitch("cc") + "|" + strSwitch("zz"));

        p("arrays", arrays(4));

        p("LOG", LOG.toString());
    }

    // ---------------------------------------------------------------
    // Instance state, so a split part that dropped `this` reads the wrong
    // object rather than merely the wrong number.
    // ---------------------------------------------------------------
    int a1, a2, a3;
    long b1;
    double c1;
    String d1;

    Split(int k, String tag) {
        // Everything up to here runs while local 0 is uninitializedThis.
        this(k, tag, k * 2);
    }

    Split(int k, String tag, int extra) {
        super();
        int acc = k + extra;
        acc = acc * 3 + 1;
        acc = acc ^ 0x61;
        acc = acc + 88;
        acc = acc * 2 - 7;
        acc = acc + 505;
        acc = acc * 3 + 11;
        acc = acc - 43;
        acc = acc ^ 0x1a2b;
        acc = acc + 616;
        acc = acc * 2 + 9;
        acc = acc - 71;
        acc = acc + 3030;
        acc = acc * 3 - 13;
        this.a1 = acc;
        this.a2 = acc * 2 + k;
        this.a3 = acc - extra;
        this.b1 = acc * 1000000009L + k;
        this.c1 = acc / 8.0d;
        this.d1 = tag + ":" + acc;
    }

    String fields() {
        return a1 + "," + a2 + "," + a3 + "," + b1 + "," + c1 + "," + d1;
    }

    String mix(int k) {
        int acc = a1 + k;
        acc = acc * 3 + 1;
        acc = acc ^ 0x71;
        acc = acc + 99;
        acc = acc * 2 - 11;
        acc = acc + 606;
        acc = acc * 3 + 13;
        acc = acc - 47;
        if (k == 0) acc += a2;
        if (k == 1) acc -= a3;
        acc = acc ^ 0x2b3c;
        acc = acc + 707;
        acc = acc * 2 + 5;
        acc = acc - 83;
        acc = acc + 4040;
        acc = acc * 3 - 17;
        // `this`, and the fields read THROUGH it, must survive every cut.
        return acc + "/" + a1 + "/" + a2 + "/" + a3 + "/" + d1 + "/" + k;
    }

    String tag() { return "base"; }

    /** A virtual call whose receiver is `this`: the part must invoke it on the
     *  object that was handed over, not on a null it zero-initialized. */
    String virt(int k) {
        int acc = a2 + k;
        acc = acc * 3 + 1;
        acc = acc ^ 0x81;
        acc = acc + 111;
        acc = acc * 2 - 13;
        acc = acc + 808;
        acc = acc * 3 + 17;
        acc = acc - 53;
        acc = acc ^ 0x3c4d;
        acc = acc + 909;
        acc = acc * 2 + 7;
        acc = acc - 97;
        acc = acc + 5050;
        acc = acc * 3 - 19;
        return tag() + "#" + acc + "#" + this.d1;
    }

    String instGuarded(int k) {
        StringBuilder sb = new StringBuilder(d1);
        int acc = a3;
        try {
            acc = acc * 2 + 1;
            if (k == 1) throw new IllegalArgumentException("iae" + a1);
            acc = acc * 3;
        } catch (IllegalArgumentException e) {
            sb.append("<").append(e.getMessage()).append(">");
            acc = a2;
        }
        acc = acc * 5 + 7;
        acc = acc ^ 0x91;
        acc = acc + 122;
        acc = acc * 2 - 3;
        acc = acc + 1010;
        acc = acc * 3 + 19;
        acc = acc - 59;
        acc = acc ^ 0x4d5e;
        acc = acc + 1111;
        return sb.append("=").append(acc).append("/").append(a1).toString();
    }

    static final class Sub extends Split {
        Sub(int k, String tag) { super(k, tag); }
        @Override String tag() { return "sub"; }
    }

    /** A catch that never mentions its exception translates to a handler stub
     *  (`pop; goto body`), which must land OUTSIDE every try range. */
    static String ignoredCatch(int k) {
        int acc = k * 7 + 1;
        try {
            acc = acc * 3 + 1;
            if (k == 0) throw new IllegalStateException("x");
            acc = acc * 2;
        } catch (IllegalStateException unused) {
            acc = 12345;
        }
        acc = acc ^ 0xa1;
        acc = acc + 133;
        acc = acc * 2 - 21;
        acc = acc + 1212;
        acc = acc * 3 + 23;
        acc = acc - 61;
        acc = acc ^ 0x5e6f;
        acc = acc + 1313;
        acc = acc * 2 + 3;
        acc = acc - 101;
        acc = acc + 6060;
        return "ic" + acc;
    }

    /** A String switch desugars to hashCode + equals + a second switch, which
     *  is a dense forward-branch cluster of exactly the kind a cut lands in. */
    static String strSwitch(String s) {
        int acc = s.length() * 13 + 1;
        acc = acc * 3 + 2;
        acc = acc ^ 0xb1;
        acc = acc + 144;
        acc = acc * 2 - 23;
        acc = acc + 1414;
        String r;
        switch (s) {
            case "aa": r = "AA"; acc += 1; break;
            case "bb": r = "BB"; acc += 2; break;
            case "cc": r = "CC"; acc += 3; break;
            default:   r = "ZZ"; acc += 4; break;
        }
        acc = acc * 3 + 29;
        acc = acc - 67;
        acc = acc ^ 0x6f70;
        acc = acc + 1515;
        acc = acc * 2 + 5;
        acc = acc - 103;
        acc = acc + 7070;
        return r + acc;
    }

    static String arrays(int n) {
        int[][] grid = new int[n][n];
        String[] names = new String[n];
        long[] longs = new long[n];
        for (int i = 0; i < n; i++) {
            names[i] = "n" + i;
            longs[i] = i * 1000003L;
            for (int j = 0; j < n; j++) grid[i][j] = i * 10 + j;
        }
        int acc = 0;
        acc += grid[0][0]; acc += grid[n - 1][n - 1];
        acc = acc * 3 + 1;
        acc = acc ^ 0xc1;
        acc = acc + 155;
        acc = acc * 2 - 25;
        acc = acc + 1616;
        acc = acc * 3 + 31;
        acc = acc - 71;
        acc = acc ^ 0x7071;
        acc = acc + 1717;
        acc = acc * 2 + 7;
        acc = acc - 107;
        acc = acc + 8080;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(names[i]).append(longs[i]).append(grid[i][i]).append(';');
        return sb.append(acc).toString();
    }

    // ---------------------------------------------------------------
    // Many forward branches over one long region: each one crosses the
    // same cut from a different place, so the dispatch ids must be distinct
    // and each must land on the right block.
    // ---------------------------------------------------------------
    static int forwardJumps(int k) {
        int a = k * 3 + 1;
        int b = a * 5 + 2;
        int c = b - k;
        int d = c * 2;
        int e = d + a;
        int f = e ^ b;
        int g = f + c * 3;
        int h = g - d;
        if (k == 0) { a += 1000; b += 2000; c += 3000; }
        if (k == 1) { a += 10; b += 20; c += 30; d += 40; }
        if (k == 2) { e += 7; f += 8; g += 9; h += 10; }
        int t0 = a + b + c + d + e + f + g + h;
        int t1 = t0 * 2 + a;
        int t2 = t1 - b;
        int t3 = t2 + c * 4;
        int t4 = t3 ^ d;
        int t5 = t4 + e;
        int t6 = t5 * 3 - f;
        int t7 = t6 + g;
        int t8 = t7 - h;
        int t9 = t8 + t0;
        if (k == 3) return t9 + 1;
        if (k == 4) return t9 + 2;
        if (k == 5) return t9 + 3;
        int u0 = t9 * 2;
        int u1 = u0 + t1;
        int u2 = u1 - t2;
        int u3 = u2 + t3;
        int u4 = u3 ^ t4;
        int u5 = u4 + t5;
        int u6 = u5 - t6;
        int u7 = u6 + t7;
        int u8 = u7 * 2 - t8;
        int u9 = u8 + t9;
        if (k == 6) return u9;
        int v0 = u9 + a;
        int v1 = v0 - b;
        int v2 = v1 + c;
        int v3 = v2 ^ d;
        int v4 = v3 + e;
        int v5 = v4 - f;
        int v6 = v5 + g;
        int v7 = v6 * 2 - h;
        int v8 = v7 + t0;
        int v9 = v8 - u0;
        return v9 + t5 + u5;
    }

    // ---------------------------------------------------------------
    // Values of every width stay live across a long body, so any of them
    // dropped at a cut prints as zero/null instead of its real value.
    // ---------------------------------------------------------------
    static String carry(int k) {
        int i0 = k + 1;
        long l0 = k * 1000000007L + 3;
        float f0 = k * 1.5f + 0.25f;
        double d0 = k * 2.25d + 0.125d;
        String s0 = "s" + k;
        int[] arr = new int[] { k, k + 1, k + 2, k + 3 };
        long l1 = l0 * 31 + 7;
        double d1 = d0 * 3.5d - 1.5d;
        int i1 = i0 * 17;
        float f1 = f0 * 2.0f;

        int acc = 0;
        acc += i0; acc += i1; acc += arr[0]; acc += arr[1];
        acc = acc * 3 + 11;
        acc = acc ^ (int) (l0 & 0xff);
        acc = acc + (int) d0;
        acc = acc * 2 - (int) f0;
        acc = acc + s0.length();
        acc = acc * 5 + 17;
        acc = acc - (int) (l1 % 97);
        acc = acc + (int) (d1 * 4);
        acc = acc ^ 0x5a5a;
        acc = acc + arr[2] * 3;
        acc = acc - arr[3];
        acc = acc * 7 + 3;
        acc = acc + i0 * i1;
        acc = acc ^ (int) f1;
        acc = acc + 12345;
        acc = acc * 2 - 999;
        acc = acc + (int) (l0 >> 8);
        acc = acc - (int) (l1 >> 16);
        acc = acc + (int) (d0 * 100);
        acc = acc + (int) (d1 * 10);
        acc = acc * 3 + 1;

        // Everything declared above must still be correct here.
        return acc + "/" + i0 + "/" + i1 + "/" + l0 + "/" + l1 + "/"
             + f0 + "/" + f1 + "/" + d0 + "/" + d1 + "/" + s0 + "/"
             + arr[0] + arr[1] + arr[2] + arr[3];
    }

    static String widths(int i, long l, float f, double d, String s) {
        int i2 = i * 3;
        long l2 = l * 7 + i;
        float f2 = f * 2.5f;
        double d2 = d * 1.5d;
        String s2 = s + "-" + i;
        long l3 = l2 ^ 0x1234_5678L;
        double d3 = d2 + 0.0625d;
        int i3 = i2 + (int) (l3 & 0x3f);
        float f3 = f2 + 0.5f;
        String s3 = s2 + "|" + i3;
        int pad = 0;
        pad += i; pad += i2; pad += i3;
        pad = pad * 3 + 1;
        pad = pad ^ 0x33;
        pad = pad + (int) l; pad = pad + (int) l2; pad = pad + (int) l3;
        pad = pad * 2 - 7;
        pad = pad + (int) f; pad = pad + (int) f2; pad = pad + (int) f3;
        pad = pad + (int) d; pad = pad + (int) d2; pad = pad + (int) d3;
        pad = pad * 5 + 13;
        pad = pad + s.length() + s2.length() + s3.length();
        pad = pad * 3 - 21;
        pad = pad ^ 0x7f7f;
        pad = pad + 4242;
        return pad + ":" + i + "," + i2 + "," + i3 + ";" + l + "," + l2 + "," + l3
             + ";" + f + "," + f2 + "," + f3 + ";" + d + "," + d2 + "," + d3
             + ";" + s + "," + s2 + "," + s3;
    }

    // ---------------------------------------------------------------
    // try/catch: a range and its handlers must end up in the same part, and
    // a portal's call must not sit inside a range.
    // ---------------------------------------------------------------
    static String guarded(int k) {
        StringBuilder sb = new StringBuilder();
        int acc = k;
        try {
            acc = acc * 2 + 1;
            if (k == 0) throw new IllegalStateException("zero");
            acc = acc * 3 + 2;
            sb.append("a").append(acc);
        } catch (IllegalStateException e) {
            sb.append("A").append(e.getMessage());
            acc = -1;
        }
        acc = acc * 5 + 7;
        acc = acc ^ 0x11;
        acc = acc + 100;
        acc = acc * 2 - 3;
        acc = acc + 55;
        acc = acc * 3 + 9;
        acc = acc - 17;
        sb.append("/").append(acc);
        try {
            int[] a = new int[k];
            acc += a.length;
            acc += a[k - 1];
            sb.append("b").append(acc);
        } catch (RuntimeException e) {
            sb.append("B");
            acc += 3;
        }
        acc = acc * 7 + 1;
        acc = acc ^ 0x2222;
        acc = acc + 12;
        acc = acc * 2 + 5;
        acc = acc - 33;
        acc = acc + 404;
        acc = acc * 3 - 11;
        sb.append("/").append(acc);
        try {
            String s = (k == 2) ? null : ("t" + k);
            sb.append("c").append(s.length());
            acc += s.length();
        } catch (NullPointerException e) {
            sb.append("C");
            acc += 9;
        } finally {
            sb.append("F");
        }
        acc = acc * 11 + 3;
        acc = acc ^ 0x0f0f;
        acc = acc + 7;
        acc = acc * 2 - 19;
        acc = acc + 88;
        return sb.append("=").append(acc).toString();
    }

    /** An exception raised in a LATER part must propagate out of the chain to
     *  the original caller, not be caught by a handler of an earlier part. */
    static String escapes(int k) {
        int acc = k * 3;
        try {
            acc = acc + 1;
            acc = acc * 2;
            acc = acc + 3;
            acc = acc * 4;
            acc = acc + 5;
            acc = acc * 6;
            acc = acc + 7;
            acc = acc * 8;
        } catch (RuntimeException e) {
            return "early:" + e.getMessage();
        }
        acc = acc + 11;
        acc = acc * 3;
        acc = acc - 5;
        acc = acc ^ 0x99;
        acc = acc + 21;
        acc = acc * 2;
        acc = acc - 13;
        acc = acc + 400;
        acc = acc * 3 + 1;
        acc = acc ^ 0x1f1f;
        acc = acc + 77;
        acc = acc * 2 - 9;
        acc = acc + 5150;
        try {
            if (k == 1) throw new ArithmeticException("boom" + acc);
            if (k == 2) acc = acc / (k - 2);
            acc = acc + 1;
        } catch (ArithmeticException e) {
            return "late:" + e.getMessage();
        }
        return "ok:" + acc;
    }

    // ---------------------------------------------------------------
    // Back edges: a cut inside a loop is illegal, so the planner must place
    // its cuts outside them and the answer must not change.
    // ---------------------------------------------------------------
    static long loops(int n) {
        long acc = 1;
        for (int i = 0; i < n; i++) {
            acc = acc * 31 + i;
            if ((i & 1) == 0) acc ^= i * 7L;
        }
        int pad = (int) (acc & 0xffff);
        pad = pad * 3 + 1;
        pad = pad ^ 0x4444;
        pad = pad + 17;
        pad = pad * 2 - 5;
        pad = pad + 999;
        pad = pad * 3 + 7;
        pad = pad - 41;
        pad = pad ^ 0x2020;
        pad = pad + 3131;
        pad = pad * 2 + 11;
        pad = pad - 77;
        pad = pad + 6060;
        acc = acc + pad;
        for (int i = n; i > 0; i--) {
            acc = acc + i * 13L;
            for (int j = 0; j < 3; j++) acc = acc ^ (i + j);
        }
        int pad2 = (int) (acc % 100003L);
        pad2 = pad2 * 5 + 3;
        pad2 = pad2 ^ 0x1717;
        pad2 = pad2 + 29;
        pad2 = pad2 * 2 - 15;
        pad2 = pad2 + 808;
        pad2 = pad2 * 3 + 5;
        pad2 = pad2 - 61;
        return acc * 3 + pad2;
    }

    // ---------------------------------------------------------------
    // One variable holding DIFFERENT reference types on different paths that
    // join after a cut. Each type has to be handed over as its own parameter,
    // because widening them to Object and casting back could throw where the
    // original did not.
    // ---------------------------------------------------------------
    static String refs(int k) {
        Object o;
        CharSequence cs;
        if (k == 0) { o = "str" + k; cs = "cs0"; }
        else if (k == 1) { o = Integer.valueOf(k * 7); cs = new StringBuilder("cs1"); }
        else { o = new int[] { k, k + 1 }; cs = "cs2"; }
        int acc = k * 3 + 1;
        acc = acc * 5 + 2;
        acc = acc ^ 0x21;
        acc = acc + 44;
        acc = acc * 2 - 7;
        acc = acc + 303;
        acc = acc * 3 + 13;
        acc = acc - 29;
        acc = acc ^ 0x1234;
        acc = acc + 777;
        acc = acc * 2 + 3;
        acc = acc - 91;
        acc = acc + 5000;
        acc = acc * 3 - 11;
        String tag;
        if (o instanceof String) tag = "S" + ((String) o).length();
        else if (o instanceof Integer) tag = "I" + ((Integer) o).intValue();
        else tag = "A" + ((int[]) o).length;
        return tag + "/" + cs.length() + "/" + cs.charAt(2) + "/" + acc;
    }

    /** A local that is definitely null at the cut has no descriptor at all, so
     *  it is restored with aconst_null rather than passed. */
    static String nulls(int k) {
        String s = null;
        int acc = k + 1;
        acc = acc * 3 + 2;
        acc = acc ^ 0x31;
        acc = acc + 66;
        acc = acc * 2 - 9;
        acc = acc + 202;
        acc = acc * 3 + 5;
        acc = acc - 37;
        acc = acc ^ 0x4321;
        acc = acc + 888;
        acc = acc * 2 + 7;
        acc = acc - 53;
        acc = acc + 4000;
        acc = acc * 3 - 15;
        if (k == 1) s = "late";
        return (s == null ? "null" : s) + ":" + acc;
    }

    static String sw(int k) {
        int acc = k * 11 + 1;
        acc = acc * 3 + 2;
        acc = acc ^ 0x41;
        acc = acc + 55;
        acc = acc * 2 - 11;
        acc = acc + 111;
        acc = acc * 3 + 6;
        acc = acc - 23;
        String r;
        switch (k) {
            case 0: r = "zero"; acc += 1; break;
            case 1: r = "one"; acc += 2; break;
            case 2: r = "two"; acc += 3; break;
            case 3: r = "three"; acc += 4; break;
            default: r = "many"; acc += 5; break;
        }
        acc = acc * 7 + 3;
        acc = acc ^ 0x1111;
        acc = acc + 22;
        acc = acc * 2 - 13;
        acc = acc + 333;
        acc = acc * 3 + 8;
        acc = acc - 47;
        acc = acc + 9000;
        return r + acc;
    }

    // ---------------------------------------------------------------
    // Every return width, so a portal's `invokestatic; xreturn` is exercised
    // for each one.
    // ---------------------------------------------------------------
    static long retLong(int k) {
        long acc = k;
        acc = acc * 3 + 1; acc = acc ^ 0x51; acc = acc + 77; acc = acc * 2 - 5;
        acc = acc + 1001; acc = acc * 3 + 9; acc = acc - 31; acc = acc ^ 0x2468;
        acc = acc + 555; acc = acc * 2 + 11; acc = acc - 67; acc = acc + 7000;
        if (k > 4) return acc * 2;
        acc = acc * 5 + 3; acc = acc ^ 0x1357; acc = acc + 41; acc = acc * 2 - 17;
        return acc;
    }

    static double retDouble(int k) {
        double acc = k;
        acc = acc * 3 + 1; acc = acc / 2; acc = acc + 77; acc = acc * 2 - 5;
        acc = acc + 1001; acc = acc * 3 + 9; acc = acc - 31; acc = acc / 4;
        acc = acc + 555; acc = acc * 2 + 11; acc = acc - 67; acc = acc + 7000;
        if (k > 4) return acc * 2;
        acc = acc * 5 + 3; acc = acc / 8; acc = acc + 41; acc = acc * 2 - 17;
        return acc;
    }

    static float retFloat(int k) {
        float acc = k;
        acc = acc * 3 + 1; acc = acc / 2; acc = acc + 77; acc = acc * 2 - 5;
        acc = acc + 1001; acc = acc * 3 + 9; acc = acc - 31; acc = acc / 4;
        acc = acc + 555; acc = acc * 2 + 11; acc = acc - 67; acc = acc + 7000;
        if (k > 4) return acc * 2;
        acc = acc * 5 + 3; acc = acc / 8; acc = acc + 41; acc = acc * 2 - 17;
        return acc;
    }

    static boolean retBool(int k) {
        int acc = k;
        acc = acc * 3 + 1; acc = acc ^ 0x51; acc = acc + 77; acc = acc * 2 - 5;
        acc = acc + 1001; acc = acc * 3 + 9; acc = acc - 31; acc = acc ^ 0x2468;
        acc = acc + 555; acc = acc * 2 + 11; acc = acc - 67; acc = acc + 7000;
        if (k > 5) return (acc & 1) == 0;
        acc = acc * 5 + 3; acc = acc ^ 0x1357; acc = acc + 41; acc = acc * 2 - 17;
        return (acc & 1) == 1;
    }

    static String retRef(int k) {
        int acc = k;
        acc = acc * 3 + 1; acc = acc ^ 0x51; acc = acc + 77; acc = acc * 2 - 5;
        acc = acc + 1001; acc = acc * 3 + 9; acc = acc - 31; acc = acc ^ 0x2468;
        acc = acc + 555; acc = acc * 2 + 11; acc = acc - 67; acc = acc + 7000;
        if (k > 4) return "big" + acc;
        acc = acc * 5 + 3; acc = acc ^ 0x1357; acc = acc + 41; acc = acc * 2 - 17;
        return "small" + acc;
    }

    static void retVoid(int k) {
        int acc = k;
        acc = acc * 3 + 1; acc = acc ^ 0x51; acc = acc + 77; acc = acc * 2 - 5;
        acc = acc + 1001; acc = acc * 3 + 9; acc = acc - 31; acc = acc ^ 0x2468;
        acc = acc + 555; acc = acc * 2 + 11; acc = acc - 67; acc = acc + 7000;
        if (k > 4) { voidSink = acc * 2; return; }
        acc = acc * 5 + 3; acc = acc ^ 0x1357; acc = acc + 41; acc = acc * 2 - 17;
        voidSink = acc;
    }

    static void p(String k, Object v) {
        System.out.println(k + " = " + v);
    }

    static void p(String k, long v) {
        System.out.println(k + " = " + v);
    }
}
