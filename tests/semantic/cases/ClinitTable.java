// Semantic cover for ClinitPrecompute: a static initializer replaced by the
// state it computes must leave exactly the state the original code leaves.
//
// HOW TO RUN THIS SO IT MEANS ANYTHING. The real trigger is a <clinit> over
// 65535 bytes (JVMS 4.9.1) that neither MethodOutliner nor MethodSplitter can
// rescue, and javac refuses to compile one ("code too large"). Run the suite
// with the rescue forced instead:
//
//     DEX2JVM_CLINIT_PRECOMPUTE_FORCE=1 tests/semantic/run.sh
//
// which precomputes every <clinit> the rescue accepts, in this case and in
// every other case of the suite. Without that variable this case still passes;
// it just is not testing the rescue.
//
// WHAT EACH PART IS FOR.
//   every element kind   boolean, byte, char, short, int, long, float and
//                        double arrays, with the edge values of each (sign
//                        bits, -0.0, NaN, the zero unit the encoding biases)
//   identity             ROW is shared by GRID and MIXED, GRID by MIXED: a
//                        rescue that copies instead of sharing prints false
//   reference arrays     int[][], String[] with null and non-ASCII entries,
//                        Object[] mixing arrays and strings
//   data spanning chunks HASHES and WIDE need several String constants of
//                        packed data, so a slice boundary off by one shifts
//                        every later element
//   computed state       loops, arithmetic, a switch, System.arraycopy, a
//                        static helper of the class, a synchronized block on
//                        the class itself, and fields read back while the
//                        initializer is still running
//   an interface         Consts' initializer becomes private static helpers
//                        on an interface
//   a refusal            Dynamic's initializer calls StringBuilder, so it
//                        must keep its ordinary translation
import java.util.Arrays;

public class ClinitTable {
    static final int[] ROW = {3, 1, 4, 1, 5, 9, 2, 6};
    static final int[][] GRID = {ROW, ROW, {2, 7, 1, 8}, null, {}};
    static final Object[] MIXED = {ROW, "x", GRID, null};

    static final boolean[] BOOLS = {true, false, true, true};
    static final byte[] BYTES = {-128, -1, 0, 1, 127};
    static final char[] CHARS = {'a', '\u0000', '\uffff', '\u05d0', '\u007f', '\u0080'};
    static final short[] SHORTS = {-32768, -1, 0, 1, 32767};
    static final long[] LONGS = {Long.MIN_VALUE, -1L, 0L, 1L, 0x123456789abcdefL, Long.MAX_VALUE};
    static final float[] FLOATS = {0f, -0f, 1.5f, Float.NaN, Float.MIN_VALUE, Float.NEGATIVE_INFINITY};
    static final double[] DOUBLES = {-0.0, Double.NaN, Math.PI, 1e300, Double.MIN_VALUE};
    static final String[] NAMES = {"alpha", "", null, "\u05e9\u05dc\u05d5\u05dd", "\ud83d\ude00"};

    static String title = "table";
    static char letter = 'z';
    static byte small = (byte) 200;
    static short neg = -5;
    static boolean flag = true;
    static float ratio = 2.75f;
    static double half = 0.5;
    static long big = 1L << 40;

    static int sum;
    static long cubes;
    static double mean;
    static int picked;
    static final int FIRST = ROW[0] + GRID[2][3];

    static final int[] HASHES = new int[40000];
    static final long[] WIDE = new long[12000];
    static final int[] JOINED;
    static int[] locked;

    static {
        for (int i = 0; i < 1000; i++) {
            sum += i * i;
            cubes += (long) i * i * i;
        }
        mean = sum / 7.0;
        switch (sum % 5) {
            case 0: picked = 10; break;
            case 1: picked = 20; break;
            case 2: picked = 30; break;
            case 3: picked = 40; break;
            default: picked = 50; break;
        }
        for (int i = 0; i < HASHES.length; i++) HASHES[i] = i * 0x9E3779B9;
        for (int i = 0; i < WIDE.length; i++) WIDE[i] = (long) i * 0x9E3779B97F4A7C15L;
        int[] a = part(500, 7);
        int[] b = part(300, 11);
        JOINED = new int[a.length + b.length];
        System.arraycopy(a, 0, JOINED, 0, a.length);
        System.arraycopy(b, 0, JOINED, a.length, b.length);
        synchronized (ClinitTable.class) {
            locked = new int[] {sum & 0xff, picked, FIRST};
        }
    }

    private static int[] part(int n, int mul) {
        int[] r = new int[n];
        for (int i = 0; i < n; i++) r[i] = i * mul ^ (i >>> 3);
        return r;
    }

    interface Consts {
        int[] PRIMES = {2, 3, 5, 7, 11};
        String[] WORDS = {"a", "b", null};
    }

    static class Dynamic {
        static final String NOW = new StringBuilder("dy").append(42).toString();
        static final int[] T = {1, 2};
    }

    static String str(String s) {
        return s == null ? "null" : s.length() + ":" + s.hashCode();
    }

    public static void main(String[] args) {
        System.out.println("ROW " + Arrays.toString(ROW));
        System.out.println("GRID " + Arrays.deepToString(GRID));
        System.out.println("identity " + (GRID[0] == ROW) + " " + (GRID[1] == ROW)
                + " " + (MIXED[0] == ROW) + " " + (MIXED[2] == GRID) + " " + (GRID[2] != ROW));
        System.out.println("MIXED " + MIXED.length + " " + MIXED[1] + " " + MIXED[3]);
        System.out.println("BOOLS " + Arrays.toString(BOOLS));
        System.out.println("BYTES " + Arrays.toString(BYTES));
        StringBuilder sb = new StringBuilder("CHARS");
        for (char c : CHARS) sb.append(' ').append((int) c);
        System.out.println(sb);
        System.out.println("SHORTS " + Arrays.toString(SHORTS));
        System.out.println("LONGS " + Arrays.toString(LONGS));
        sb = new StringBuilder("FLOATS");
        for (float f : FLOATS) sb.append(' ').append(Integer.toHexString(Float.floatToRawIntBits(f)));
        System.out.println(sb);
        sb = new StringBuilder("DOUBLES");
        for (double d : DOUBLES) sb.append(' ').append(Long.toHexString(Double.doubleToRawLongBits(d)));
        System.out.println(sb);
        sb = new StringBuilder("NAMES");
        for (String s : NAMES) sb.append(' ').append(str(s));
        System.out.println(sb + " interned=" + (NAMES[0] == "alpha"));
        System.out.println("scalars " + title + " " + (int) letter + " " + small + " " + neg + " "
                + flag + " " + ratio + " " + half + " " + big);
        System.out.println("computed " + sum + " " + cubes + " " + mean + " " + picked + " " + FIRST);
        System.out.println("HASHES " + HASHES.length + " " + Arrays.hashCode(HASHES)
                + " " + HASHES[1] + " " + HASHES[39999]);
        System.out.println("WIDE " + WIDE.length + " " + Arrays.hashCode(WIDE) + " " + WIDE[11999]);
        System.out.println("JOINED " + JOINED.length + " " + Arrays.hashCode(JOINED)
                + " " + JOINED[499] + " " + JOINED[500]);
        System.out.println("locked " + Arrays.toString(locked));
        System.out.println("Consts " + Arrays.toString(Consts.PRIMES) + " " + Arrays.toString(Consts.WORDS));
        System.out.println("Dynamic " + Dynamic.NOW + " " + Arrays.toString(Dynamic.T));
    }
}
