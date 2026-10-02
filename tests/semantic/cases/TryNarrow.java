// What a handler SEES, and WHICH handler runs, when a try range interleaves
// throwing and non-throwing instructions.
//
// Since 2026-09-26 (Translator.NARROW_TRY, default ON) the converter no longer
// copies a DEX try range into the JVM exception table verbatim: it emits one
// row per run of THROWING instructions inside the range, because ART only
// routes an exception to a handler from an instruction flagged kThrow, while
// the JVM (JVMS 4.10.1.6) checks the handler frame at every covered bci. That
// is correct only if (a) every instruction that can really throw is still
// covered, (b) the JVM's first-match row order still picks the handler the DEX
// catch list picks when one range becomes several rows, and (c) the handler
// still observes the register values of the instruction that threw, not of a
// neighbour. Each method below pins one of those with a value a wrong table
// would change:
//
//   counter  a local incremented between throwing instructions; the handler
//            prints it, so a row that starts or ends one instruction off
//            reports the wrong step (or escapes the try entirely)
//   order    three catch clauses whose types overlap (Arithmetic <
//            Runtime < Throwable) over a range split into several runs; a
//            span-major table would let a later clause win
//   kinds    one throwing opcode family per step (invoke, div, aget, aput,
//            iget on null, check-cast, new-array, array-length, monitor-enter,
//            fill-array-data) so no family silently lost its coverage
//   reuse    a register holding a reference at every throwing point but
//            reused for a new object or a primitive between them -- the shape
//            that made the JVM-rule frame say `top` (mastodon, kotlinx)
//   nested / finally / loop   the usual structural combinations
//
// For the STRUCTURAL half, run tools/verify's VerifyCheck (G8, HotSpot's split
// verifier) over the converted class. Its POSITIVE CONTROL for NARROW_TRY
// (DEX2JVM_NARROW_TRY=0) is a real app's class (mastodon's
// org/joinmastodon/android/api/f), because javac + d8/r8 could not be made to
// emit the failing register reuse from a few lines of Java.
//
// Deterministic by construction: only exception SIMPLE names of JDK classes
// (never an NPE message -- JDK 14+ helpful messages name bytecode locals, and
// ours are laid out differently from javac's) and no app class names, so the
// r8 mode can rename freely.
import java.util.*;

public class TryNarrow {
    static int sink;
    // Nulls and zeros come from places R8 cannot constant-fold (an array slot,
    // the environment), and Holder is instantiated in main. Measured
    // 2026-09-26: when R8 can PROVE a receiver null it rewrites the use to
    // `throw null`, and in an early version of tests/semantic/run.sh's r8 mode it
    // then placed that `throw` OUTSIDE the enclosing try range, so the NPE
    // escaped where javac's oracle catches it. The cause was the harness, not R8
    // and not the converter: that mode passed `--lib` a directory that is not a
    // JDK home, so R8 saw no java.* class at all (hidden by `-dontwarn **`)
    // and could not tell that the RuntimeException handler catches a NullPointerException.
    // With --lib at the real JDK home the naive version passes. The case keeps
    // R8 from proving anything either way, so it is valid under both.
    static int zero = Integer.getInteger("dex2jvm.test.zero", 0);
    static final Object[] NULLS = new Object[2];

    public static void main(String[] a) {
        for (int k = 0; k <= 6; k++) p("counter" + k, counter(k));
        for (int k = 0; k <= 4; k++) p("order" + k, order(k));
        for (int k = 0; k <= 11; k++) p("kind" + k, kinds(k));
        for (int k = 0; k <= 3; k++) p("reuse" + k, reuse(k));
        for (int k = 0; k <= 3; k++) p("nested" + k, nested(k));
        for (int k = 0; k <= 2; k++) p("finally" + k, fin(k));
        p("loop", loop());
        // Instantiate Holder somewhere, or R8 proves every Holder reference
        // null and rewrites kind8/kind11 to `throw null` (see the note above).
        p("holder", new Holder().f);
        for (int k = 0; k <= 2; k++) p("wide" + k, wide(k));
        for (int k = 0; k <= 2; k++) p("rethrow" + k, rethrow(k));
    }

    static void p(String k, Object v) { System.out.println(k + "=" + v); }

    static int boom(int step, int at) {
        if (step == at) throw new IllegalStateException();
        return step;
    }

    // The handler must see the step of the instruction that threw.
    static String counter(int at) {
        int step = 0;
        int acc = 1;
        try {
            step = 1;
            acc += boom(1, at);           // invoke
            step = 2;
            acc = acc * 3 + 7;            // arithmetic that cannot throw
            step = 3;
            acc += 10 / (at == 3 ? zero : 1);   // div-int
            step = 4;
            int[] arr = new int[2];
            step = 5;
            acc += arr[at == 5 ? 9 : 1];  // aget
            step = 6;
            acc += boom(6, at);
            step = 7;
        } catch (RuntimeException e) {
            return "caught@" + step + ":" + e.getClass().getSimpleName() + ":" + acc;
        }
        return "done@" + step + ":" + acc;
    }

    // First-match order across a range the table narrows into several rows.
    static String order(int k) {
        String tag = "none";
        try {
            tag = "a";
            if (k == 0) sink = 1 / zero;                    // Arithmetic
            tag = "b";
            int x = sink + 3;                               // no throw
            tag = "c";
            if (k == 1) throw new IllegalArgumentException();  // Runtime
            tag = "d";
            x = x * 2;
            if (k == 2) throw new AssertionError();         // Throwable only
            tag = "e";
            if (k == 3) { int[] q = new int[1]; sink = q[x]; }  // AIOOBE is Runtime
            tag = "f";
            if (k == 4) sink = 7 % zero;                    // Arithmetic again
            tag = "g";
        } catch (ArithmeticException e) {
            return "arith@" + tag;
        } catch (RuntimeException e) {
            return "runtime@" + tag + ":" + e.getClass().getSimpleName();
        } catch (Throwable t) {
            return "throwable@" + tag + ":" + t.getClass().getSimpleName();
        }
        return "none@" + tag;
    }

    static final Object LOCK = new Object();

    // One throwing opcode family per case.
    static String kinds(int k) {
        int step = 100 + k;
        try {
            switch (k) {
                case 0: sink = String.valueOf(k).length(); step = -1; break;
                case 1: sink = 5 / zero; break;
                case 2: { int[] a = new int[1]; sink = a[3]; break; }
                case 3: { int[] a = new int[1]; a[4] = 1; break; }
                case 4: { Object[] a = new String[1]; a[0] = Integer.valueOf(1); break; }
                case 5: { int[] a = (int[]) NULLS[0]; sink = a.length; break; }
                case 6: { Object o = "s"; sink = ((Integer) o).intValue(); break; }
                case 7: { sink = new int[-k].length; break; }
                case 8: { Holder h = (Holder) NULLS[1]; sink = h.f; break; }
                case 9: { synchronized (NULLS[k & 1]) { sink = 1; } break; }
                case 10: { int[] a = { 1, 2, 3, 4, 5, 6 }; sink = a[k - 10]; step = -2; break; }
                case 11: { Holder h = (Holder) NULLS[0]; h.f = 3; break; }
                default: break;
            }
        } catch (RuntimeException e) {
            return step + ":" + e.getClass().getSimpleName();
        }
        return step + ":ok:" + sink;
    }

    static final class Holder { int f; }

    // A local that is a reference at every throwing instruction but whose
    // register an optimiser may reuse in between.
    static String reuse(int k) {
        Object keep = "first";
        StringBuilder sb = null;
        try {
            keep = "second";
            sb = new StringBuilder(String.valueOf(boom(0, k == 0 ? 0 : -1)));
            int prim = sb.length() * 7;
            keep = sb;
            sb = new StringBuilder("x" + prim);
            boom(1, k == 1 ? 1 : -1);
            keep = new ArrayList<>(Arrays.asList(1, 2, 3));
            sb.append(((List<?>) keep).size());
            boom(2, k == 2 ? 2 : -1);
            keep = sb.toString();
        } catch (IllegalStateException e) {
            return "caught:" + keep + ":" + (sb == null ? "null" : sb.toString());
        }
        return "done:" + keep;
    }

    static String nested(int k) {
        StringBuilder log = new StringBuilder();
        try {
            log.append('a');
            try {
                log.append('b');
                if (k == 0) throw new IllegalStateException();
                log.append('c');
                if (k == 1) sink = 1 / zero;
                log.append('d');
            } catch (IllegalStateException e) {
                log.append("[inner]");
                if (k == 0) sink = 2 / zero;
            }
            log.append('e');
            if (k == 2) throw new UnsupportedOperationException();
            log.append('f');
        } catch (ArithmeticException e) {
            log.append("[outer-arith]");
        } catch (RuntimeException e) {
            log.append("[outer-rt]");
        }
        return log.toString();
    }

    static String fin(int k) {
        StringBuilder log = new StringBuilder();
        try {
            try {
                log.append('1');
                if (k == 0) throw new IllegalStateException();
                log.append('2');
                if (k == 1) return log.append("ret").toString();
                log.append('3');
            } finally {
                log.append("[fin]");
            }
        } catch (IllegalStateException e) {
            log.append("[caught]");
        }
        return log.toString();
    }

    static String loop() {
        int caught = 0, sum = 0;
        for (int i = 0; i < 12; i++) {
            try {
                sum += i;
                sum += 100 / (i % 3);      // throws on every third i
                sum += 1;
                if (i % 4 == 1) throw new IllegalStateException();
                sum += 2;
            } catch (ArithmeticException e) {
                caught += 1;
            } catch (IllegalStateException e) {
                caught += 100;
            }
        }
        return caught + "/" + sum;
    }

    // Wide values live across the throwing points and are read by the handler.
    static String wide(int k) {
        long l = 1L;
        double d = 0.5;
        try {
            l += 10;
            d *= 4;
            boom(0, k == 0 ? 0 : -1);
            l = l * 3;
            d = d + 0.25;
            sink = (int) (l / (k == 1 ? zero : 1));
            l += 1000;
        } catch (RuntimeException e) {
            return "caught:" + l + ":" + d;
        }
        return "done:" + l + ":" + d;
    }

    static String rethrow(int k) {
        try {
            return inner(k);
        } catch (RuntimeException e) {
            return "outer:" + e.getClass().getSimpleName() + ":" + e.getMessage();
        }
    }

    static String inner(int k) {
        int n = 0;
        try {
            n = 1;
            if (k == 0) throw new IllegalStateException("first");
            n = 2;
            if (k == 1) throw new IllegalArgumentException("second");
            n = 3;
        } catch (IllegalStateException e) {
            throw new UnsupportedOperationException("wrapped" + n);
        }
        return "inner:" + n;
    }
}
