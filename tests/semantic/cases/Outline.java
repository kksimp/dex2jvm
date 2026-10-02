// Semantic cover for MethodOutliner: a method whose body gets hoisted into
// synthetic private static methods must compute exactly what it computed
// in one piece.
//
// HOW TO RUN THIS SO IT MEANS ANYTHING. The real trigger is a method over
// 65535 bytes (JVMS 4.9.1), and javac REFUSES to compile one ("code too
// large"), so the oracle side of this gate can never reach the real threshold.
// Run the suite with the threshold lowered instead:
//
//     DEX2JVM_OUTLINE_FORCE=400 tests/semantic/run.sh
//
// which makes every method over 400 bytes get split, so this case (and every
// other case in the suite) is actually pushed through the transform. Without
// that variable this case still passes, it just is not testing anything.
//
// WHAT EACH SECTION IS FOR. The outliner has to get four things right, and each
// one has a section here that fails loudly if it does not:
//
//   the parallel move   parameters land in slots 0..n-1 of the synthetic method
//                       while the hoisted body reads the ORIGINAL register
//                       slots, which for a static method also start at 0. The
//                       two overlap, so a naive copy clobbers a parameter
//                       before reading it. `mixedWidths` keeps many live values
//                       of every width alive across the region so any ordering
//                       mistake corrupts a printed value.
//   liveness            a value the region WRITES and something later READS may
//                       not be hoisted, because the synthetic method is void
//                       and cannot hand it back. `writtenThenRead` does exactly
//                       that, and prints the value.
//   frames              code after a hoisted region is still branchy, and its
//                       StackMapTable has to stay consistent with what the
//                       shortened method can actually prove. `tailBranches`
//                       puts loops and conditionals after the straight-line
//                       bulk, which is the shape of Telegram's EmojiData.
//   wide slots          a long/double occupies two slots, so an off-by-one in
//                       the parameter walk shows up as a garbled number.
//
// Output must be deterministic: no hash codes, no class names (R8 renames), no
// iteration order.
public class Outline {

    static final int N = 220;
    static String[] names = new String[N];
    static int[] codes = new int[N];
    static long[] longs = new long[N];
    static double[] doubles = new double[N];

    public static void main(String[] a) {
        buildTables();
        p("names.len", names.length);
        p("names.hash", join(names));
        p("codes.sum", sum(codes));
        p("longs.sum", lsum(longs));
        p("doubles.sum", dsum(doubles));

        p("mixedWidths", mixedWidths(7, 11L, 2.5f, 3.25d, "seed", new int[]{1, 2, 3}));
        p("writtenThenRead", writtenThenRead(5));
        p("tailBranches", tailBranches(9));
        p("nested", nested(4, 6L));
    }

    /**
     * A long straight-line static initializer -- the EmojiData / FPREngine
     * shape. The array references stay live across the whole run, so they must
     * arrive in every hoisted piece as parameters, while the index and the
     * temporaries are rewritten each step and must NOT.
     */
    static void buildTables() {
        String[] s = names;
        int[] c = codes;
        long[] l = longs;
        double[] d = doubles;
        // Written out rather than looped on purpose: a loop is one basic block
        // the outliner would refuse, and the point is a long straight run.
        s[0] = "a0"; c[0] = 100; l[0] = 1000L; d[0] = 0.5;
        s[1] = "a1"; c[1] = 101; l[1] = 1001L; d[1] = 1.5;
        s[2] = "a2"; c[2] = 102; l[2] = 1002L; d[2] = 2.5;
        s[3] = "a3"; c[3] = 103; l[3] = 1003L; d[3] = 3.5;
        s[4] = "a4"; c[4] = 104; l[4] = 1004L; d[4] = 4.5;
        s[5] = "a5"; c[5] = 105; l[5] = 1005L; d[5] = 5.5;
        s[6] = "a6"; c[6] = 106; l[6] = 1006L; d[6] = 6.5;
        s[7] = "a7"; c[7] = 107; l[7] = 1007L; d[7] = 7.5;
        s[8] = "a8"; c[8] = 108; l[8] = 1008L; d[8] = 8.5;
        s[9] = "a9"; c[9] = 109; l[9] = 1009L; d[9] = 9.5;
        fill(10);
        fill(60);
        fill(110);
        fill(160);
    }

    /** Fifty more entries, so the emitted body is comfortably past any
     *  lowered threshold a gate run might set. */
    static void fill(int base) {
        String[] s = names;
        int[] c = codes;
        long[] l = longs;
        double[] d = doubles;
        for (int i = 0; i < 50; i++) {
            int k = base + i;
            s[k] = "e" + k;
            c[k] = k * 3 + 1;
            l[k] = ((long) k << 20) ^ 0x5555L;
            d[k] = k / 8.0;
        }
    }

    /**
     * Every operand width live at once, so a parameter walk that miscounts a
     * long or a double corrupts a printed value rather than passing quietly.
     */
    static String mixedWidths(int i, long j, float f, double d, String s, int[] arr) {
        int i2 = i * 2;
        long j2 = j * 3;
        float f2 = f + 0.5f;
        double d2 = d * 2;
        String s2 = s + "!";
        int t0 = arr[0], t1 = arr[1], t2 = arr[2];
        // A long straight run that reads all of the above afterwards, forcing
        // them to be live-in parameters of whatever gets hoisted.
        int acc = 0;
        acc += t0; acc += t1; acc += t2;
        acc += i; acc += i2;
        acc ^= (int) j; acc ^= (int) j2;
        acc += (int) f; acc += (int) f2;
        acc += (int) d; acc += (int) d2;
        acc += s.length(); acc += s2.length();
        acc = acc * 31 + 7; acc = acc * 31 + 8; acc = acc * 31 + 9;
        acc = acc * 31 + 10; acc = acc * 31 + 11; acc = acc * 31 + 12;
        return i + "/" + j + "/" + f + "/" + d + "/" + s2 + "/" + acc
             + "/" + i2 + "/" + j2 + "/" + f2 + "/" + d2;
    }

    /** A value produced in the middle of a straight run and read after it: the
     *  region that writes it may not be hoisted, because a void synthetic
     *  method cannot return it. */
    static long writtenThenRead(int seed) {
        long carried = seed * 1000L;
        int a = seed + 1, b = seed + 2, c = seed + 3, d = seed + 4;
        carried += a; carried += b; carried += c; carried += d;
        carried *= 3; carried += 17; carried ^= 0xFF00;
        long later = carried * 2 + a + b + c + d;
        return later + carried;
    }

    /** Straight-line bulk followed by branches, the EmojiData shape: the
     *  shortened method still needs a correct StackMapTable for the tail. */
    static int tailBranches(int n) {
        int x = n, y = n * 2, z = n * 3;
        x += 1; x += 2; x += 3; x += 4; x += 5;
        y ^= 7; y ^= 8; y ^= 9; y ^= 10;
        z *= 2; z += 11; z -= 3; z *= 2;
        String tag = "t" + n;
        int out = 0;
        for (int i = 0; i < 5; i++) {
            if ((i & 1) == 0) out += x + i; else out -= y - i;
            if (i == 3) out ^= z;
        }
        switch (n % 3) {
            case 0: out += 100; break;
            case 1: out += 200; break;
            default: out += 300; break;
        }
        return out + tag.length() + x + y + z;
    }

    /** Two straight runs separated by a call, so more than one region is a
     *  candidate in a single method. */
    static String nested(int i, long j) {
        int a = i, b = i + 1, c = i + 2;
        a *= 2; a += 3; a ^= 5; a += 7; a *= 2; a -= 1;
        b *= 3; b += 4; b ^= 6; b += 8; b *= 3; b -= 2;
        String mid = mixedWidths(a, j, b, c, "n", new int[]{a, b, c});
        int d = a + b + c;
        d *= 2; d += 3; d ^= 5; d += 7; d *= 2; d -= 1;
        return a + ":" + b + ":" + c + ":" + d + ":" + mid.length();
    }

    static String join(String[] s) {
        long h = 17;
        for (String v : s) h = h * 31 + (v == null ? 0 : v.hashCode());
        return Long.toString(h);
    }
    static int sum(int[] v) { int t = 0; for (int x : v) t += x; return t; }
    static long lsum(long[] v) { long t = 0; for (long x : v) t += x; return t; }
    static String dsum(double[] v) { double t = 0; for (double x : v) t += x; return Double.toString(t); }

    static void p(String k, Object v) { System.out.println(k + " = " + v); }
    static void p(String k, int v) { System.out.println(k + " = " + v); }
    static void p(String k, long v) { System.out.println(k + " = " + v); }
}
