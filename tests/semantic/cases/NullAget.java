// An array LOAD from a register that is provably null.
//
// ART's verifier types the result of `aget vA, vNull, vI` as Zero, which is
// legal as every 32-bit reading at once, so the dex a compiler or obfuscator
// emits may read that one def as an int in one place and as a float in
// another (measured: Moat analytics inside Hill Climb Racing 1.43,
// com/moat/analytics/mobile/cha/g). The emitter must pick ONE xaload opcode
// for the load, and before Translator.NULL_AGET it `dup`ed that value into
// every slot the def owns, so an iaload feeding an fstore failed the split
// verifier ("Type integer is not assignable to float"). The fix drops the
// loaded value and stores a typed zero per slot; the load still throws
// NullPointerException first, so no store below it ever runs.
//
// This case pins the BEHAVIOUR the fix must keep: every element kind, read
// from a null array, throws NullPointerException from the load itself, before
// any later instruction in the same try can run, and the handler sees the
// state from before the load. For the STRUCTURAL half, run tools/verify's
// VerifyCheck (G8, HotSpot's split verifier) over the converted class: it must
// pass, and with DEX2JVM_NULL_AGET=0 (the pre-fix emission) it must fail --
// that run is the positive control.
//
// The arrays come from constants, not from a parameter, so that d8 (debug
// mode, no constant propagation) emits the aget on a register that the
// analysis PROVES null. Output names no classes and prints no NPE message
// (JDK 14+ helpful messages describe bytecode locals, which differ by design).
public class NullAget {
    static int sink;

    public static void main(String[] a) {
        p("int", intLoad());
        p("float", floatLoad());
        p("mixed", mixed(3));
        p("long", longLoad());
        p("double", doubleLoad());
        p("byte", byteLoad());
        p("char", charLoad());
        p("short", shortLoad());
        p("bool", boolLoad());
        p("obj", objLoad());
        p("loop", loop());
    }

    static void p(String k, Object v) { System.out.println(k + "=" + v); }

    static String intLoad() {
        int[] arr = null;
        int step = 0;
        try {
            step = 1;
            int v = arr[0];
            step = 2;
            sink = v;
        } catch (NullPointerException e) {
            return "npe@" + step;
        }
        return "no-npe";
    }

    static String floatLoad() {
        float[] arr = null;
        int step = 0;
        try {
            step = 1;
            float v = arr[1];
            step = 2;
            sink = (int) (v * 2f);
        } catch (NullPointerException e) {
            return "npe@" + step;
        }
        return "no-npe";
    }

    // One loaded value used both as an int and, through a float array store,
    // as a float -- the Moat shape, as close as Java source gets to it.
    static String mixed(int i) {
        float[] fa = null;
        float[] out = new float[4];
        int step = 0;
        try {
            step = 1;
            float v = fa[i];
            step = 2;
            out[0] = v;
            sink = (int) v + i;
        } catch (NullPointerException e) {
            return "npe@" + step + ":" + out[0];
        }
        return "no-npe";
    }

    static String longLoad() {
        long[] arr = null;
        try {
            long v = arr[0];
            return "no-npe" + v;
        } catch (NullPointerException e) {
            return "npe";
        }
    }

    static String doubleLoad() {
        double[] arr = null;
        try {
            double v = arr[2];
            return "no-npe" + v;
        } catch (NullPointerException e) {
            return "npe";
        }
    }

    static String byteLoad() {
        byte[] arr = null;
        try { return "no-npe" + arr[0]; } catch (NullPointerException e) { return "npe"; }
    }

    static String charLoad() {
        char[] arr = null;
        try { return "no-npe" + arr[0]; } catch (NullPointerException e) { return "npe"; }
    }

    static String shortLoad() {
        short[] arr = null;
        try { return "no-npe" + arr[0]; } catch (NullPointerException e) { return "npe"; }
    }

    static String boolLoad() {
        boolean[] arr = null;
        try { return "no-npe" + arr[0]; } catch (NullPointerException e) { return "npe"; }
    }

    static String objLoad() {
        String[] arr = null;
        try { return "no-npe" + arr[0].length(); } catch (NullPointerException e) { return "npe"; }
    }

    static String loop() {
        int npes = 0;
        for (int i = 0; i < 5; i++) {
            int[] arr = (i % 2 == 0) ? null : new int[] { i };
            try {
                sink += arr[0];
            } catch (NullPointerException e) {
                npes++;
            }
        }
        return npes + "/" + sink;
    }
}
