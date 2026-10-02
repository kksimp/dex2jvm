// Semantic cover for splitting a method that holds a MONITOR.
//
// WHY THIS CASE EXISTS. MethodSplitter used to refuse outright -- "method uses
// monitor-enter/exit" -- so a synchronized method over the JVM's 65535-byte
// code_length cap (JVMS 4.9.1) was never split, the class was dropped, and
// nothing here needed testing. That refusal is what stubbed Instagram's
// `X/9Pu.A09` (75,928 bytes, the whole opcode switch of Bloks' Lispy
// interpreter). It is now replaced by two narrower rules, and THOSE need cover:
//
//   monitor window    a single cut-forbidden span from the FIRST monitor op to
//                     the LAST, so every enter and every exit lands in one
//                     part. Sufficient for structured locking (JVMS 2.11.10)
//                     without pairing anything -- which matters because an
//                     exact pairing is not derivable by a linear walk: a
//                     synchronized block emits a monitor-exit on the normal
//                     path AND a second one in a catch-all handler that
//                     rethrows, so a depth counter goes NEGATIVE at the
//                     handler.
//   forward escape    a forward branch OUT of that window into a later part
//                     would leave the method (a part is a synthetic static
//                     method, so a cross-part branch is a return plus a
//                     re-dispatch) while a monitor is held. Each such edge adds
//                     its own cut-forbidden span.
//
// HOW TO RUN THIS SO IT MEANS ANYTHING:
//
//     DEX2JVM_SPLIT_FORCE=400 tests/semantic/run.sh Monitors
//
// javac refuses to compile a method over 65535 bytes, so the oracle can never
// reach the real threshold; FORCE lowers it so every method here is actually
// pushed through the transform. Add DEX2JVM_SPLIT_DEBUG=1 to watch the
// planner: you want to see `parts of` lines, not `refused:` lines.
//
// ⚠ 400, not the 220 that `Split.java` documents, and the number is not
// arbitrary. A part's budget is `codeLimit() - (4*locals + 64)`, so at 220 the
// budget is about 100 bytes -- too tight for a monitor window plus its
// surrounding block, and 6 of these methods refuse. Measured across the suite:
//
//     FORCE=300  7 split, 5 refused        FORCE=500  6 split, 0 refused
//     FORCE=400  7 split, 3 refused  <--   FORCE=600  4 split, 0 refused
//
// 400 maximises the methods that actually go through the transform. Above it
// the methods stop exceeding the limit at all and coverage silently DROPS,
// which is the trap: a higher forced limit looks stricter and tests less.
//
// ★★★ A MATCH HERE IS VACUOUS UNLESS THE METHODS ACTUALLY SPLIT. Read this
// before trusting a green row. DEX2JVM_SPLIT_FORCE lowers the limit the
// SPLITTER plans against; it does NOT lower the real 65535 serialization limit.
// So a method the planner REFUSES is not stubbed -- it emits normally, runs
// correctly, and the case passes having tested nothing. Confirm with:
//
//     DEX2JVM_SPLIT_FORCE=400 DEX2JVM_SPLIT_DEBUG=1 tests/semantic/run.sh Monitors 2>&1 | grep 'parts of'
//
// At FORCE=400 expect a `parts of` line for `breakOut`, `carry`, `loopLock`,
// `nested`, `plain` and `throwInside` -- six monitor-holding methods -- plus
// `main`. `returnInside`, `twoRegions` and `topLevelWindow` refuse, which is
// correct and documented below. A run with no `parts of` lines is a blind pass.
// (The first version of this file was exactly that: every method refused, both
// builds reported MATCH, and the case measured nothing.)
//
// ★★ THE SHAPE THAT DECIDES IT, and it is a durable fact about d8 worth knowing
// on its own: **a top-level `synchronized` block puts its catch-all handler --
// and therefore the LAST monitor-exit -- at the END of the method**, so the
// min..max window stretches from the block to the final instruction and no cut
// can sit anywhere after it. Put the same block inside a LOOP and d8 cannot
// sink the handler past the loop, so the window stays narrow. Measured on a
// two-method probe, identical bodies:
//
//     top-level:  monitor window [13,53] of 55 instructions  -> REFUSED
//     in a loop:  monitor window [17,28] of 63 instructions  -> 4 parts
//
// That is also why the method this fix was built for works: `X/9Pu.A09` is a
// 12,000-instruction opcode switch whose synchronized region sits inside the
// interpreter's dispatch loop, giving a window of 31 instructions.
//
// So the sections below keep every synchronized region TINY and inside a loop,
// padded with long straight-line arithmetic that offers plenty of legal cut
// points. `topLevelWindow` is kept as the counter-shape, and refusing it is the
// CORRECT outcome -- the conservative window has a cost and this is it.
//
// A second consequence of FORCE not stubbing: there is no positive control
// available from this file alone. The control is the `parts of` count above --
// on a build with the OLD blanket monitor refusal it is ZERO for every
// monitor-holding section here, and on a fixed build it is six.
//
// ★ THE ORACLE THAT MAKES IT MORE THAN A VALUE DIFF. Every section calls
// Thread.holdsLock(), which asks the VM directly whether this frame holds the
// monitor. A value diff can only catch a dropped or mis-numbered live value; a
// holdsLock diff catches the failure mode unique to this change -- a lock that
// leaked across a part boundary, or one released early.
//
// Single-threaded on purpose. This gate is about CODEGEN, not concurrency: a
// second thread would make the output nondeterministic and the diff useless.
public class Monitors {

    static final Object A = new Object();
    static final Object B = new Object();

    public static void main(String[] args) {
        p("plain.1", plain(1));
        p("plain.7", plain(7));

        p("nested.2", nested(2));

        p("breakOut.3", breakOut(3));
        p("breakOut.99", breakOut(99));

        p("returnInside.0", returnInside(0));
        p("returnInside.1", returnInside(1));
        p("returnInside.2", returnInside(2));

        p("throwInside.ok", throwInside(0));
        p("throwInside.boom", throwInside(1));

        p("twoRegions.4", twoRegions(4));

        p("loopLock.3", loopLock(3));

        p("carry", carry(7, 13L, 1.5d, "tail"));

        p("wholeMethod.3", wholeMethod(3));

        p("topLevelWindow.5", topLevelWindow(5));

        p("heldAfter.A", Thread.holdsLock(A));
        p("heldAfter.B", Thread.holdsLock(B));
    }

    /** The baseline shape: long splittable run, TINY monitor region, long
     *  splittable run. `h0`/`h1`/`h2` sample the lock before, inside and after,
     *  and all three are carried across the cuts that follow. */
    static String plain(int seed) {
        int a = seed;
        a = a * 31 + 1;  a = a * 17 + 2;  a = a ^ 3;      a = a * 5 + 4;
        a = a * 31 + 5;  a = a * 17 + 6;  a = a ^ 7;      a = a * 5 + 8;
        a = a * 31 + 9;  a = a * 17 + 10; a = a ^ 11;     a = a * 5 + 12;
        a = a * 31 + 13; a = a * 17 + 14; a = a ^ 15;     a = a * 5 + 16;
        a = a * 31 + 17; a = a * 17 + 18; a = a ^ 19;     a = a * 5 + 20;
        boolean h0 = Thread.holdsLock(A);
        boolean h1 = false;
        for (int i = 0; i < 2; i++) { synchronized (A) { h1 = Thread.holdsLock(A); a += 21 + i; } }
        boolean h2 = Thread.holdsLock(A);
        a = a * 31 + 22; a = a * 17 + 23; a = a ^ 24;     a = a * 5 + 25;
        a = a * 31 + 26; a = a * 17 + 27; a = a ^ 28;     a = a * 5 + 29;
        a = a * 31 + 30; a = a * 17 + 31; a = a ^ 32;     a = a * 5 + 33;
        a = a * 31 + 34; a = a * 17 + 35; a = a ^ 36;     a = a * 5 + 37;
        a = a * 31 + 38; a = a * 17 + 39; a = a ^ 40;     a = a * 5 + 41;
        return a + ":" + h0 + h1 + h2;
    }

    /** Two DIFFERENT monitors nested. The window must span both, and releasing
     *  them in the wrong order shows as a holdsLock disagreement. */
    static String nested(int seed) {
        int a = seed;
        a = a * 31 + 1;  a = a * 17 + 2;  a = a ^ 3;      a = a * 5 + 4;
        a = a * 31 + 5;  a = a * 17 + 6;  a = a ^ 7;      a = a * 5 + 8;
        a = a * 31 + 9;  a = a * 17 + 10; a = a ^ 11;     a = a * 5 + 12;
        a = a * 31 + 13; a = a * 17 + 14; a = a ^ 15;     a = a * 5 + 16;
        boolean ha = false, hb = false, hbOut = true;
        for (int i = 0; i < 2; i++) {
            synchronized (A) { ha = Thread.holdsLock(A); synchronized (B) { hb = Thread.holdsLock(B); a += 17 + i; } hbOut = Thread.holdsLock(B); }
        }
        boolean haOut = Thread.holdsLock(A);
        a = a * 31 + 18; a = a * 17 + 19; a = a ^ 20;     a = a * 5 + 21;
        a = a * 31 + 22; a = a * 17 + 23; a = a ^ 24;     a = a * 5 + 25;
        a = a * 31 + 26; a = a * 17 + 27; a = a ^ 28;     a = a * 5 + 29;
        a = a * 31 + 30; a = a * 17 + 31; a = a ^ 32;     a = a * 5 + 33;
        return a + ":" + ha + hb + hbOut + haOut;
    }

    /** `break` out of a loop from INSIDE the region. javac emits monitor-exit
     *  before the branch, so this is well-formed -- it is here to prove the
     *  forward-escape spans do not BREAK the well-formed case while guarding
     *  the restructured one. */
    static String breakOut(int k) {
        int a = k;
        a = a * 31 + 1;  a = a * 17 + 2;  a = a ^ 3;      a = a * 5 + 4;
        a = a * 31 + 5;  a = a * 17 + 6;  a = a ^ 7;      a = a * 5 + 8;
        a = a * 31 + 9;  a = a * 17 + 10; a = a ^ 11;     a = a * 5 + 12;
        int hit = -1;
        for (int i = 0; i < 6; i++) {
            synchronized (A) { if (i == k % 7) { hit = i; break; } a += i; }
        }
        boolean h = Thread.holdsLock(A);
        a = a * 31 + 13; a = a * 17 + 14; a = a ^ 15;     a = a * 5 + 16;
        a = a * 31 + 17; a = a * 17 + 18; a = a ^ 19;     a = a * 5 + 20;
        a = a * 31 + 21; a = a * 17 + 22; a = a ^ 23;     a = a * 5 + 24;
        a = a * 31 + 25; a = a * 17 + 26; a = a ^ 27;     a = a * 5 + 28;
        return a + ":" + hit + ":" + h;
    }

    /** `return` from INSIDE the region, at three different depths, with a long
     *  splittable run after each so the returns land in different parts. */
    static String returnInside(int k) {
        int a = k;
        a = a * 31 + 1;  a = a * 17 + 2;  a = a ^ 3;      a = a * 5 + 4;
        a = a * 31 + 5;  a = a * 17 + 6;  a = a ^ 7;      a = a * 5 + 8;
        for (int i = 0; i < 2; i++) { synchronized (A) { if (k == 0) return "early:" + a + ":" + Thread.holdsLock(A); a += 9 + i; } }
        a = a * 31 + 10; a = a * 17 + 11; a = a ^ 12;     a = a * 5 + 13;
        a = a * 31 + 14; a = a * 17 + 15; a = a ^ 16;     a = a * 5 + 17;
        for (int i = 0; i < 2; i++) { synchronized (B) { if (k == 1) return "mid:" + a + ":" + Thread.holdsLock(B); a += 18 + i; } }
        a = a * 31 + 19; a = a * 17 + 20; a = a ^ 21;     a = a * 5 + 22;
        a = a * 31 + 23; a = a * 17 + 24; a = a ^ 25;     a = a * 5 + 26;
        a = a * 31 + 27; a = a * 17 + 28; a = a ^ 29;     a = a * 5 + 30;
        return "end:" + a + ":" + Thread.holdsLock(A) + Thread.holdsLock(B);
    }

    /** An exception raised INSIDE the region. The catch-all handler d8 emits is
     *  what releases the monitor, and it sits OUTSIDE the body's linear range --
     *  the exact reason the window is min..max rather than a paired walk. The
     *  `holdsLock` in the catch asserts the handler really ran. */
    static String throwInside(int mode) {
        int a = mode;
        a = a * 31 + 1;  a = a * 17 + 2;  a = a ^ 3;      a = a * 5 + 4;
        a = a * 31 + 5;  a = a * 17 + 6;  a = a ^ 7;      a = a * 5 + 8;
        a = a * 31 + 9;  a = a * 17 + 10; a = a ^ 11;     a = a * 5 + 12;
        String tag;
        try {
            for (int i = 0; i < 2; i++) { synchronized (A) { if (mode == 1) throw new IllegalStateException("x" + a); a += 13 + i; } }
            tag = "clean";
        } catch (IllegalStateException e) {
            tag = "ise:" + e.getMessage() + ":" + Thread.holdsLock(A);
        }
        a = a * 31 + 14; a = a * 17 + 15; a = a ^ 16;     a = a * 5 + 17;
        a = a * 31 + 18; a = a * 17 + 19; a = a ^ 20;     a = a * 5 + 21;
        a = a * 31 + 22; a = a * 17 + 23; a = a ^ 24;     a = a * 5 + 25;
        return tag + "|" + a + "|" + Thread.holdsLock(A);
    }

    /** Two SEPARATE regions with a long splittable gap between them. The window
     *  spans the gap even though no lock is held there, so this measures the
     *  cost of the conservative rule -- and proves it still computes the right
     *  answer. This is the method most likely to REFUSE at a small FORCE; a
     *  refusal is a correct outcome, a wrong answer is not. */
    static String twoRegions(int seed) {
        int a = seed;
        a = a * 31 + 1;  a = a * 17 + 2;  a = a ^ 3;      a = a * 5 + 4;
        a = a * 31 + 5;  a = a * 17 + 6;  a = a ^ 7;      a = a * 5 + 8;
        boolean ha = false, hb = false;
        for (int i = 0; i < 2; i++) { synchronized (A) { ha = Thread.holdsLock(A); a += 9 + i; } }
        a = a * 31 + 10; a = a * 17 + 11; a = a ^ 12;     a = a * 5 + 13;
        a = a * 31 + 14; a = a * 17 + 15; a = a ^ 16;     a = a * 5 + 17;
        a = a * 31 + 18; a = a * 17 + 19; a = a ^ 20;     a = a * 5 + 21;
        for (int i = 0; i < 2; i++) { synchronized (B) { hb = Thread.holdsLock(B); a += 22 + i; } }
        a = a * 31 + 23; a = a * 17 + 24; a = a ^ 25;     a = a * 5 + 26;
        a = a * 31 + 27; a = a * 17 + 28; a = a ^ 29;     a = a * 5 + 30;
        return a + ":" + ha + hb + Thread.holdsLock(A) + Thread.holdsLock(B);
    }

    /** Lock taken and released inside a LOOP body, so the same monitor ops run
     *  many times and any imbalance compounds instead of cancelling. */
    static String loopLock(int n) {
        int a = n;
        a = a * 31 + 1;  a = a * 17 + 2;  a = a ^ 3;      a = a * 5 + 4;
        a = a * 31 + 5;  a = a * 17 + 6;  a = a ^ 7;      a = a * 5 + 8;
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) {
            synchronized (A) { s.append(Thread.holdsLock(A) ? '1' : '0'); a += i; }
            s.append(Thread.holdsLock(A) ? '1' : '0');
        }
        a = a * 31 + 9;  a = a * 17 + 10; a = a ^ 11;     a = a * 5 + 12;
        a = a * 31 + 13; a = a * 17 + 14; a = a ^ 15;     a = a * 5 + 16;
        a = a * 31 + 17; a = a * 17 + 18; a = a ^ 19;     a = a * 5 + 20;
        return s + ":" + a;
    }

    /** Values of every width must survive the hand-over into the part that owns
     *  the monitor window. A value dropped by mistake still VERIFIES (its slot
     *  was zero-initialized) and quietly reads zero, so each one is printed. */
    static String carry(int i0, long l0, double d0, String s0) {
        int a = i0;
        long b = l0;
        double c = d0;
        String t = s0;
        a = a * 31 + 1;  b = b * 17 + 2;  c = c * 1.5 + 3;  t = t + "1";
        a = a * 31 + 4;  b = b * 17 + 5;  c = c * 1.5 + 6;  t = t + "2";
        a = a * 31 + 7;  b = b * 17 + 8;  c = c * 1.5 + 9;  t = t + "3";
        boolean h = false;
        for (int i = 0; i < 2; i++) { synchronized (A) { h = Thread.holdsLock(A); a += 10 + i; } }
        a = a * 31 + 11; b = b * 17 + 12; c = c * 1.5 + 13; t = t + "4";
        a = a * 31 + 14; b = b * 17 + 15; c = c * 1.5 + 16; t = t + "5";
        a = a * 31 + 17; b = b * 17 + 18; c = c * 1.5 + 19; t = t + "6";
        return a + "/" + b + "/" + c + "/" + t + "/" + h + Thread.holdsLock(A);
    }

    /** A synchronized METHOD -- the monitor is implicit in ACC_SYNCHRONIZED, so
     *  there are no monitor-enter/exit instructions and the window is EMPTY. The
     *  lock lives on the OUTER frame, and a part is a separate static method
     *  that does not inherit it -- but Thread.holdsLock asks about the THREAD,
     *  and the outer frame is still on the stack while the part runs, so it must
     *  still report true. Included so that difference is measured, not assumed. */
    static synchronized String wholeMethod(int seed) {
        int a = seed;
        boolean h0 = Thread.holdsLock(Monitors.class);
        a = a * 31 + 1;  a = a * 17 + 2;  a = a ^ 3;      a = a * 5 + 4;
        a = a * 31 + 5;  a = a * 17 + 6;  a = a ^ 7;      a = a * 5 + 8;
        a = a * 31 + 9;  a = a * 17 + 10; a = a ^ 11;     a = a * 5 + 12;
        boolean h1 = Thread.holdsLock(Monitors.class);
        a = a * 31 + 13; a = a * 17 + 14; a = a ^ 15;     a = a * 5 + 16;
        a = a * 31 + 17; a = a * 17 + 18; a = a ^ 19;     a = a * 5 + 20;
        a = a * 31 + 21; a = a * 17 + 22; a = a ^ 23;     a = a * 5 + 24;
        boolean h2 = Thread.holdsLock(Monitors.class);
        return a + ":" + h0 + h1 + h2;
    }

    /** THE COUNTER-SHAPE, and refusing it is the CORRECT outcome. A top-level
     *  synchronized block -- not inside any loop -- lets d8 sink its catch-all
     *  handler, and therefore the LAST monitor-exit, to the END of the method.
     *  The min..max window then reaches within a couple of instructions of the
     *  final one and no cut can sit after it, so the planner reports "no valid
     *  cut". That is the price of the conservative window, measured rather than
     *  assumed, and it is still strictly better than the blanket refusal it
     *  replaced: this method used to be unsplittable too.
     *
     *  It is here so the cost stays visible. If a future change narrows the
     *  window (an exact enter/exit pairing, or splitting the window at the
     *  handler), THIS is the method that should start producing `parts of`. */
    static String topLevelWindow(int seed) {
        int a = seed;
        a = a * 31 + 1;  a = a * 17 + 2;  a = a ^ 3;      a = a * 5 + 4;
        a = a * 31 + 5;  a = a * 17 + 6;  a = a ^ 7;      a = a * 5 + 8;
        a = a * 31 + 9;  a = a * 17 + 10; a = a ^ 11;     a = a * 5 + 12;
        boolean h;
        synchronized (A) { h = Thread.holdsLock(A); a += 13; }
        a = a * 31 + 14; a = a * 17 + 15; a = a ^ 16;     a = a * 5 + 17;
        a = a * 31 + 18; a = a * 17 + 19; a = a ^ 20;     a = a * 5 + 21;
        a = a * 31 + 22; a = a * 17 + 23; a = a ^ 24;     a = a * 5 + 25;
        return a + ":" + h + Thread.holdsLock(A);
    }

    static void p(String k, Object v) { System.out.println(k + "=" + v); }
}
