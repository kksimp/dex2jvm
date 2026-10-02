// Control flow + exceptions: the areas where a StackMapTable/handler bug shows
// up as WRONG ANSWERS rather than as a load failure.
import java.util.*;
public class Flow {
    public static void main(String[] a) {
        // finally runs on every exit path, and a return inside finally wins.
        p("fin-normal", finNormal()); p("fin-throw", finThrow());
        p("fin-overrides", finOverrides()); p("fin-break", finBreak());
        p("nested-fin", nestedFin());

        // Catch ordering: the FIRST matching handler wins, so a broad catch
        // listed first would silently swallow the specific one.
        p("order", order(0)); p("order", order(1)); p("order", order(2));
        p("multi", multi(0)); p("multi", multi(1)); p("multi", multi(2));

        // Exceptions the JVM raises implicitly.
        p("div0", t(() -> 1 / zero()));
        p("mod0", t(() -> 1 % zero()));
        p("npe-field", t(() -> ((int[]) null).length));
        p("npe-call", t(() -> ((String) null).length()));
        p("aioobe", t(() -> { int[] x = new int[2]; return x[5]; }));
        p("nase", t(() -> new int[-1]));
        p("cce", t(() -> ((String) (Object) Integer.valueOf(1)).length()));
        p("ase", t(() -> { Object[] o = new String[1]; o[0] = 1; return 0; }));
        p("rethrow", t(Flow::rethrow));
        p("suppressed", tryWithResources());

        // tableswitch (dense) vs lookupswitch (sparse) vs string/enum switch.
        StringBuilder sb = new StringBuilder();
        for (int i = -2; i <= 6; i++) sb.append(dense(i)).append(',');
        p("dense", sb);
        sb.setLength(0);
        for (int i : new int[]{-1000, 0, 7, 5000, 999999}) sb.append(sparse(i)).append(',');
        p("sparse", sb);
        p("strswitch", strSwitch("alpha") + strSwitch("beta") + strSwitch("Aa") + strSwitch("BB") + strSwitch("zz"));
        p("enumswitch", enumSwitch(E.A) + enumSwitch(E.B) + enumSwitch(E.C));

        // Labeled break/continue out of nested loops.
        p("labeled", labeled());
        // Short-circuit must NOT evaluate the right side.
        sideEffects = 0;
        boolean r = bump(false) && bump(true);
        p("&&", r + "/" + sideEffects);
        sideEffects = 0;
        r = bump(true) || bump(true);
        p("||", r + "/" + sideEffects);
        // Loop shapes.
        p("dowhile", doWhile()); p("forever", forever());
        p("ternary", tern(-5) + tern(0) + tern(5));
        p("recurse", fib(20) + "/" + ack(2, 3));
        // synchronized must not change the answer, but it does change the
        // handler table (monitorexit gets a synthetic catch-all).
        p("sync", syncSum());
    }

    enum E { A, B, C }
    static int sideEffects;
    static boolean bump(boolean v) { sideEffects++; return v; }
    static int zero() { return 0; }

    static String finNormal() { try { return "t"; } finally { System.out.print(""); } }
    static String finThrow() { try { throw new RuntimeException("x"); }
                               catch (RuntimeException e) { return "c:" + e.getMessage(); }
                               finally { System.out.print(""); } }
    @SuppressWarnings("finally")
    static int finOverrides() { try { return 1; } finally { return 2; } }
    static int finBreak() { int n = 0;
        for (int i = 0; i < 3; i++) { try { if (i == 1) break; n += 1; } finally { n += 10; } }
        return n; }
    static String nestedFin() { StringBuilder s = new StringBuilder();
        try { try { s.append('a'); throw new IllegalStateException(); }
              finally { s.append('b'); } }
        catch (IllegalStateException e) { s.append('c'); }
        finally { s.append('d'); }
        return s.toString(); }

    static String order(int k) {
        try { if (k == 0) throw new IllegalArgumentException("iae");
              if (k == 1) throw new IllegalStateException("ise");
              throw new RuntimeException("re"); }
        catch (IllegalArgumentException e) { return "IAE"; }
        catch (IllegalStateException e) { return "ISE"; }
        catch (RuntimeException e) { return "RE"; }
    }
    static String multi(int k) {
        try { if (k == 0) throw new java.io.IOException();
              if (k == 1) throw new NumberFormatException();
              throw new Error("err"); }
        catch (java.io.IOException | NumberFormatException e) { return "M:" + e.getClass().getSimpleName(); }
        catch (Throwable e) { return "T:" + e.getClass().getSimpleName(); }
    }
    static int rethrow() {
        try { try { throw new UnsupportedOperationException("deep"); }
              catch (RuntimeException e) { throw e; } }
        catch (UnsupportedOperationException e) { throw new IllegalStateException("wrapped:" + e.getMessage()); }
    }
    static String tryWithResources() {
        class R implements AutoCloseable {
            final String n; R(String n) { this.n = n; }
            public void close() { throw new IllegalStateException("close:" + n); }
        }
        try (R r1 = new R("1"); R r2 = new R("2")) { throw new RuntimeException("body"); }
        catch (Exception e) {
            StringBuilder s = new StringBuilder(e.getMessage());
            for (Throwable t : e.getSuppressed()) s.append('|').append(t.getMessage());
            return s.toString();
        }
    }
    interface Th { Object run(); }
    static String t(Th th) {
        try { Object o = th.run(); return "ok:" + o; }
        catch (Throwable e) { return e.getClass().getName(); }
    }

    static int dense(int i) { switch (i) {
        case 0: return 100; case 1: return 101; case 2: return 102;
        case 3: return 103; case 4: return 104; default: return -1; } }
    static int sparse(int i) { switch (i) {
        case -1000: return 1; case 0: return 2; case 7: return 3;
        case 5000: return 4; default: return -1; } }
    static int strSwitch(String s) { switch (s) {
        case "alpha": return 1; case "beta": return 2;
        case "Aa": return 3;   // Aa and BB collide in String.hashCode
        case "BB": return 4; default: return 9; } }
    static int enumSwitch(E e) { switch (e) {
        case A: return 1; case B: return 2; default: return 3; } }

    static String labeled() { StringBuilder s = new StringBuilder();
        outer: for (int i = 0; i < 4; i++) { for (int j = 0; j < 4; j++) {
            if (j == 2) continue outer; if (i == 3) break outer; s.append(i).append(j); } }
        return s.toString(); }
    static int doWhile() { int n = 0, i = 0; do { n += i; i++; } while (i < 5); return n; }
    static int forever() { int n = 0; for (;;) { n++; if (n > 7) return n; } }
    static String tern(int v) { return v < 0 ? "n" : v == 0 ? "z" : "p"; }
    static long fib(int n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }
    static int ack(int m, int n) { return m == 0 ? n + 1 : n == 0 ? ack(m - 1, 1) : ack(m - 1, ack(m, n - 1)); }
    static final Object LOCK = new Object();
    static int syncSum() { int n = 0;
        synchronized (LOCK) { for (int i = 0; i < 5; i++) { synchronized (Flow.class) { n += i; } } }
        return n; }
    static void p(String k, Object v) { System.out.println(k + "=" + v); }
}
