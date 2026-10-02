// Integer/float edge cases where a converter that "looks right" is wrong.
// Everything printed must be deterministic across JVMs.
public class Arith {
    static int si; static long sl; static float sf; static double sd;

    public static void main(String[] a) {
        // Overflow and the two division cases the JLS calls out specially.
        p("int-overflow", Integer.MAX_VALUE + 1);
        p("int-underflow", Integer.MIN_VALUE - 1);
        p("MIN/-1", Integer.MIN_VALUE / -1);      // JLS 15.17.2: stays MIN_VALUE
        p("MIN%-1", Integer.MIN_VALUE % -1);      // 0, and must not trap
        p("longMIN/-1", Long.MIN_VALUE / -1L);
        p("neg%", (-7) % 3); p("%neg", 7 % (-3));
        p("negdiv", (-7) / 3); p("divneg", 7 / (-3));

        // Shift distance is masked (JLS 15.19): & 31 for int, & 63 for long.
        int x = 1;
        p("shl32", x << 32); p("shl33", x << 33); p("shl-1", x << -1);
        p("shr-1", -8 >> 1); p("ushr-1", -8 >>> 1); p("ushr32", -8 >>> 32);
        long L = 1L;
        p("lshl64", L << 64); p("lshl65", L << 65);
        p("lushr", -8L >>> 4);

        // Narrowing conversions.
        int big = 0x1234ABCD;
        p("i2b", (byte) big); p("i2c", (int) (char) big); p("i2s", (short) big);
        p("i2b-neg", (byte) -1); p("i2c-neg", (int) (char) -1);
        p("l2i", (int) 0x1_0000_0001L);

        // float/double specials: NaN comparisons are all false, -0.0 == 0.0,
        // and NaN->int is 0 while huge->int saturates (JLS 5.1.3).
        double nan = 0.0 / 0.0, inf = 1.0 / 0.0, ninf = -1.0 / 0.0;
        p("nan==nan", nan == nan); p("nan!=nan", nan != nan);
        p("nan<1", nan < 1.0); p("nan>1", nan > 1.0);
        p("Double.compare(nan,nan)", Double.compare(nan, nan));
        p("negzero==zero", -0.0 == 0.0);
        p("Double.compare(-0,0)", Double.compare(-0.0, 0.0));
        p("nan2int", (int) nan); p("inf2int", (int) inf); p("ninf2int", (int) ninf);
        p("nan2long", (long) nan); p("inf2long", (long) inf);
        p("1e20f2int", (int) 1e20f); p("-1e20f2int", (int) -1e20f);
        p("inf-inf", inf - inf); p("0*inf", 0.0 * inf);
        p("bits(nan)", Double.doubleToRawLongBits(nan));
        p("fbits(-0f)", Float.floatToRawIntBits(-0.0f));

        // dcmpg/dcmpl and fcmpg/fcmpl differ ONLY on NaN; both must be emitted.
        p("cmpg", nan >= 1.0); p("cmpl", nan <= 1.0);
        float fn = 0.0f / 0.0f;
        p("fcmpg", fn >= 1.0f); p("fcmpl", fn <= 1.0f);

        // Wide register pairs: overwriting the high half must invalidate the low.
        long w = 0x1122334455667788L;
        double d = Double.longBitsToDouble(w);
        p("wide-roundtrip", Double.doubleToRawLongBits(d) == w);
        p("l2d2l", (long) (double) 123456789012345L);
        p("l2f", (float) 123456789012345L);
        p("d2f", (float) 1.0000000001);

        // Static field defaults and compound assignment on fields.
        p("statics", si + "/" + sl + "/" + sf + "/" + sd);
        si += 5; si *= 3; si -= 1; si /= 2; si %= 4; si <<= 2; si ^= 9;
        p("compound", si);

        // Increment forms.
        int i = 5;
        p("i++ + ++i", (i++) + (++i));
        p("i", i);
        byte b = 120; b += 10;                 // compound assign narrows implicitly
        p("byte-wrap", b);
        char c = 'A'; c += 2;
        p("char-add", c);
    }
    static void p(String k, Object v) { System.out.println(k + "=" + v); }
    static void p(String k, boolean v) { System.out.println(k + "=" + v); }
    static void p(String k, int v) { System.out.println(k + "=" + v); }
    static void p(String k, long v) { System.out.println(k + "=" + v); }
    static void p(String k, float v) { System.out.println(k + "=" + v); }
    static void p(String k, double v) { System.out.println(k + "=" + v); }
}
