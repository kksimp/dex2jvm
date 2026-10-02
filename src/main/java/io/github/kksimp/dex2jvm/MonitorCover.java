// MonitorCover -- how to reshape the catch-all rows of a monitor method so that
// HotSpot's JIT can prove its monitors balanced. See Translator.MONITOR_COVER
// for the flag, the measured cost, and why this is only ever tried on a method
// that needs it.
//
// THE PROBLEM, IN ONE PARAGRAPH. C1 and C2 compile a method with monitorenter
// only when GenerateOopMap proves every monitor balanced
// (ciMethod::has_balanced_monitors, jdk21u). Its exception rule
// (generateOopMap.cpp do_exception_edge) takes every bytecode that
// Bytecodes::can_trap, and `ldc`/`ldc_w`/`ldc2_w`/`checkcast` are can_trap
// whatever their operand. If such a bytecode runs while a lock is held and no
// catch_type 0 exception_table row covers it, the method is "not compilable
// (unbalanced monitors)" and stays interpreted forever. The emitter produces
// exactly those bytecodes for NON-throwing DEX instructions -- `ldc` for a
// `const` no short form encodes, above all the float view of an int const
// (intBitsToFloat(1) = 1.4E-45f has no fconst) -- and d8 legally leaves such
// instructions out of every try range, because ART routes an exception only
// from an instruction flagged kThrow (art/libdexfile/dex/dex_instruction_list.h:
// const/4, const/16, const, const/high16 and const-wide* are kContinue only;
// const-string and const-class are kThrow, so they are never the gap).
// A DEX try range also carries no notion of a lock: d8 can merge the
// non-throwing instructions AFTER a monitor-exit into the range whose catch-all
// releases that lock, and then a whole-range catch-all row
// (Translator.MONITOR_SAFE_TRY) feeds depth 0 into a handler entered at depth 1
// ("monitor stack height merge conflict", or "underflow" when that edge is
// the first to arrive).
//
// WHAT THIS COMPUTES. Per instruction, the monitor CONTEXT (the stack of
// monitor-enter DEX offsets held on entry) and, for each held lock, the
// registers that certainly still hold the locked object. Found by the walk
// GenerateOopMap does: normal edges, plus exception edges from THROWING
// instructions only, carrying the pre-state, walking the try's clauses in order
// and stopping at the catch-all, with its monitor-exit carve-out; a merge must
// agree on the context and intersects the holders. A handler's state is what
// its THROWING predecessors bring it; those edges are the program's semantics
// and are never changed. Then, for each reachable NON-throwing instruction:
//   - DROP it from its own try's whole-range catch-all row when that handler
//     is entered in a different context (the merge-conflict family);
//   - give it a RELEASE STUB when it holds a lock and no row of the right
//     context covers it: the handler javac emits for a synchronized block,
//     `aload <lock>; monitorexit; athrow` (the thrown exception is already on
//     the stack), releasing every held lock innermost first, appended after the
//     method body. The plan names, per lock level, a register that holds that
//     lock both before and after the instruction.
// That is javac's layout: its catch-all row covers the whole synchronized body
// and it is the handler that releases the lock (JLS 14.19; javac's
// Gen.visitSynchronized: genTry with a finalizer that emits monitorexit). A
// nested lock's javac handler releases one lock and rethrows into the next
// one's; a stub releases them all in the same order, which is the same end
// state.
//
// WHY A STUB AND NOT THE DEX'S OWN RELEASE HANDLER. A handler row makes the
// verifier merge the covered instruction's frame into the handler's (JVMS
// 4.10.1.6). The DEX's release handler reads what d8 put there: a coverage
// probe's array and a constant CSE'd into a register defined earlier inside the
// lock (JaCoCo instruments every exit; measured on nowinandroid, 1,231
// methods), a try-with-resources handler's resource (androidx.profileinstaller's
// writeProfileVerification, 5 corpus apps: "Type top (current frame,
// locals[15])"). A stub reads only the lock slots, so its frame is TOP
// everywhere else and `java/lang/Object` there, which every covered frame is
// assignable to, and the method body, its frames and every existing handler
// stay byte-for-byte what the previous translation produced. GenerateOopMap's
// do_monitorexit also needs the value exited to BE the lock on top of its
// monitor stack ("improper monitor pair"), which the holder sets guarantee.
//
// WHY IT CANNOT CHANGE BEHAVIOUR. Only non-throwing DEX instructions get new
// or dropped rows. On ART nothing is ever raised from them. On the JVM their
// expansions are loads, stores, arithmetic that cannot trap, and ldc of a
// numeric constant, which resolves nothing and cannot throw. So no exception
// is ever dispatched through a row this adds or removes; they exist only for
// GenerateOopMap. The one exception is an emitter `checkcast` inserted at a
// use ART had already proven, whose failure would mean the converter mistyped
// the program: with a stub it releases the lock and rethrows, which is what
// javac's layout would do, instead of leaving the lock to the unwinder.
//
// THROWING INSTRUCTIONS OUTSIDE EVERY TRY (Translator.MONITOR_COVER_THROWING).
// A THROWING instruction can also hold a lock outside every try range, because
// the code's producer left it there: R8's no-throw analysis (an iget on
// `this`, a call to a method it treats as never throwing, a const-string it
// treats as no-throw), or a bytecode instrumenter such as JaCoCo inserting
// its probe stores outside javac's range (144 of the 613 measured, all in a
// debug d8 build with no R8, so NOT a proof of anything). ART allows the shape:
// its verifier demands a catch-all only for an instruction that IS in a try
// (method_verifier.cc: "If they're not in a 'try' block when they throw,
// control transfers out of the method."). The same
// stub covers these, at their pre- and post-state lock registers alike. If one
// ever did throw, HotSpot today unwinds the frame with the lock held, and its
// interpreter then unlocks it and REPLACES the exception with an
// IllegalMonitorStateException (interp_masm_aarch64.cpp remove_activation:
// "Stack unrolling. Unlock object and install illegal_monitor_exception";
// interpreterRuntime.cpp new_illegal_monitor_state_exception: "Any current
// installed exception will be overwritten"). Under the stub the lock is
// released and the ORIGINAL exception propagates, which is what javac's layout
// does. ART propagates the original exception too, but leaves the lock held:
// its interpreter releases locks on an abrupt exit only for a method marked
// MustCountLocks (lineage-21.0 = android14 runtime/interpreter/
// interpreter_common.h DoMonitorCheckOnExit -> lock_count_data.cc
// CheckAllMonitorsReleasedOrThrow, which also replaces the exception with an
// IllegalMonitorStateException), and this shape verifies cleanly, so it is not
// marked. So the stub agrees with ART on WHICH exception the caller sees, where
// the uncovered output does not, and differs only in releasing the lock, which
// HotSpot's unwinder does today as well. That is the one observable change in
// this file. It is reached only if such an instruction really throws, which
// takes an abnormal condition (an NPE or AIOOBE on a JaCoCo probe array, an
// OutOfMemoryError, a class-initialisation error, a callee R8 misjudged): an
// improbable path, not a proven-dead one. It has its own switch,
// DEX2JVM_MONITOR_COVER_THROWING=0 (Translator.MONITOR_COVER_THROWING), so
// this behaviour can be turned off without losing the rest of MONITOR_COVER.
//
// WHAT IT REFUSES (returns null, and the method keeps its current output):
// a context conflict reached through semantic edges (a loop around a
// monitor-enter, a handler shared by code inside and outside a lock), a
// monitor-exit with nothing held or of a register not known to hold the
// innermost lock, a monitor-enter of a lock already held (GenerateOopMap
// bails on that itself), a return while holding a lock, a THROWING instruction
// holding a lock inside a try with no catch-all (a class ART rejects), and,
// with MONITOR_COVER_THROWING off, a throwing instruction holding a lock
// outside every try.
//
// WHAT IS LEFT, measured over the 62-app corpus (2026-09-26; 98 of the 8,915
// methods), and why each is a floor:
//   - 75 "context conflict": a THROWING instruction's real exception edge
//     reaches a handler at a monitor depth other than the one the handler is
//     entered at elsewhere (60 JaCoCo probe stores, aput-boolean at depth 0
//     after a monitor-exit but still in the range whose catch-all releases the
//     lock; 12 Compose InlineMarker.finallyStart calls at depth 2 inside an
//     inner release handler that the OUTER release handler covers; 2 more
//     JaCoCo stores between nested locks; 1 const-string at depth 2 whose
//     handler is entered at depth 1 elsewhere). GenerateOopMap needs one
//     depth per handler, so any table it accepts would re-route a real
//     exception; the depth-2 ones would also release out of LIFO order
//     ("improper monitor pair"). Left as the app shipped them.
//   - 22 "nested redundant lock": GenerateOopMap bails on these whatever the
//     table says.
//   - 1 reverted: a stub row whose end instruction carries no label.

package io.github.kksimp.dex2jvm;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MonitorCover {

    private MonitorCover() {}

    /** The re-plan for one method. */
    static final class Plan {
        /** Non-throwing instruction offsets to leave out of their own try's
         *  whole-range catch-all row (only meaningful when the method's
         *  catch-all rows ARE whole-range; see Translator.MONITOR_SAFE_TRY). */
        final Set<Integer> drop;
        /** Non-throwing instruction offset -> the registers holding each held
         *  lock, outermost first: cover it with a release stub for them. */
        final Map<Integer, int[]> stub;
        /** Same keys as {@link #stub}: the monitor-enter DEX offsets held there,
         *  outermost first. Instructions in different contexts must not share
         *  a stub even when their locks sit in the same registers: the stub's
         *  entry would merge two monitorenters' lock values, and
         *  GenerateOopMap's merge of two different lock refs is a plain slot
         *  ref (CellTypeState::merge), which its do_monitorexit then calls
         *  "improper monitor pair" -- measured on nowinandroid before the key
         *  carried the context: 7 of 256 rescued methods refused by HotSpot. */
        final Map<Integer, int[]> stubContext;
        Plan(Set<Integer> drop, Map<Integer, int[]> stub, Map<Integer, int[]> stubContext) {
            this.drop = drop;
            this.stub = stub;
            this.stubContext = stubContext;
        }
    }

    /** Why the last plan() on this thread returned null (diagnostics only). */
    static final ThreadLocal<String> lastRefusal = new ThreadLocal<>();

    private static final int[] EMPTY = new int[0];

    /** Monitor state on entry to one instruction: the held locks, outermost
     *  first, and per lock the registers that certainly hold it. Immutable. */
    private static final class State {
        final int[] ctx;
        final BitSet[] hold;
        State(int[] ctx, BitSet[] hold) { this.ctx = ctx; this.hold = hold; }
        int depth() { return ctx.length; }
    }

    private static final State ENTRY = new State(EMPTY, new BitSet[0]);

    /**
     * @param norm the normalized instructions the method was translated from
     * @param tries its try blocks
     * @param types the analysis of the previous translation of the same method
     *   (only its reachability is used)
     * @param wholeCatchAll whether that translation gave each catch-all one
     *   whole-range row (Translator.MONITOR_SAFE_TRY) rather than narrowed ones
     * @param coverThrowing also give a release stub to a THROWING instruction
     *   that holds a lock outside every try range (Translator.
     *   MONITOR_COVER_THROWING); otherwise such a method is refused
     */
    static Plan plan(List<TypeInference.Insn> norm, List<TypeInference.TryBlock> tries,
                     TypeInference.Result types, boolean wholeCatchAll,
                     boolean coverThrowing) {
        lastRefusal.set(null);
        int n = norm.size();
        if (n == 0) return refuse("empty");
        Map<Integer, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) index.put(norm.get(i).offset, i);

        State[] st = new State[n];
        Set<Integer> throwTargets = new HashSet<>();   // handler offsets a throwing insn reaches
        List<Integer> uncaught = new java.util.ArrayList<>();   // see coverThrowing
        ArrayDeque<Integer> work = new ArrayDeque<>();
        boolean[] queued = new boolean[n];
        st[0] = ENTRY;
        work.add(0);
        queued[0] = true;
        while (!work.isEmpty()) {
            int i = work.poll();
            queued[i] = false;
            TypeInference.Insn insn = norm.get(i);
            State s = st[i];
            int[] c = s.ctx;
            int fam = insn.family();

            if (TypeInference.canThrow(insn.opcode)) {
                // GenerateOopMap's monitorexit carve-out: while a lock is held it
                // assumes monitorexit does not throw, so no edge. (javac and d8
                // both cover a release handler's own monitor-exit with that same
                // handler; with the edge, every such handler would loop.)
                boolean carve = fam == TypeInference.F_MONITOR_EXIT && c.length > 0;
                if (!carve) {
                    boolean caughtAll = false;
                    TypeInference.TryBlock t = tryAt(tries, insn.offset);
                    if (t != null) {
                        for (TypeInference.Catch k : t.catches) {
                            Integer h = index.get(k.handlerOffset);
                            if (h == null) return refuse("handler 0x" + hex(k.handlerOffset)
                                    + " is not an instruction");
                            throwTargets.add(k.handlerOffset);
                            String m = merge(st, work, queued, h, s);
                            if (m != null) return refuse(m + " at handler 0x" + hex(k.handlerOffset));
                            if (k.exceptionType == null) { caughtAll = true; break; }
                        }
                    }
                    if (!caughtAll && c.length > 0) {
                        // Inside a try this is a class ART rejects outright
                        // (method_verifier.cc: "expected to be within a
                        // catch-all for an instruction where a monitor is
                        // held"), so only the out-of-try shape is ever covered.
                        if (t != null || !coverThrowing) {
                            return refuse("throwing opcode 0x" + hex(insn.opcode) + " at 0x"
                                    + hex(insn.offset) + " holds a lock outside every catch-all");
                        }
                        if (!uncaught.contains(i)) uncaught.add(i);
                    }
                }
            }

            State after;
            if (fam == TypeInference.F_MONITOR_ENTER) {
                // GenerateOopMap's do_monitorenter bails on a value that is
                // ALREADY a held lock ("nested redundant lock -- bailout..."),
                // whatever the exception table says, so no plan can make such a
                // method compilable; leave it as it is. R8 produces it by
                // inlining a synchronized method into a block locked on the same
                // object (measured on duolingo: 5 methods that a table re-plan
                // left refused by HotSpot before this check).
                for (int k = 0; k < c.length; k++) {
                    if (s.hold[k].get(insn.arg(0))) {
                        return refuse("nested redundant lock: monitor-enter v" + insn.arg(0)
                                + " at 0x" + hex(insn.offset) + " re-locks a held lock");
                    }
                }
                int[] nc = Arrays.copyOf(c, c.length + 1);
                nc[c.length] = insn.offset;
                BitSet[] nh = Arrays.copyOf(s.hold, s.hold.length + 1);
                BitSet mine = new BitSet();
                mine.set(insn.arg(0));
                nh[s.hold.length] = mine;
                after = new State(nc, nh);
            } else if (fam == TypeInference.F_MONITOR_EXIT) {
                if (c.length == 0) return refuse("monitor-exit with no lock held at 0x"
                        + hex(insn.offset));
                // GenerateOopMap's do_monitorexit: the value exited must be the
                // lock on top of its monitor stack ("improper monitor pair").
                if (!s.hold[c.length - 1].get(insn.arg(0))) {
                    return refuse("monitor-exit of v" + insn.arg(0) + " at 0x" + hex(insn.offset)
                            + " is not known to hold the innermost lock");
                }
                after = new State(Arrays.copyOf(c, c.length - 1),
                                  Arrays.copyOf(s.hold, s.hold.length - 1));
            } else if (fam == TypeInference.F_RETURN && c.length > 0) {
                return refuse("return holding a lock at 0x" + hex(insn.offset));
            } else {
                after = afterDefs(insn, fam, s);
            }

            int[] succ = successors(insn, fam);
            for (int target : succ) {
                Integer j = index.get(target);
                if (j == null) return refuse("branch to 0x" + hex(target) + " is not an instruction");
                String m = merge(st, work, queued, j, after);
                if (m != null) return refuse(m + " at 0x" + hex(target));
            }
            if (fallsThrough(fam)) {
                if (i + 1 >= n) return refuse("falls off the end");
                String m = merge(st, work, queued, i + 1, after);
                if (m != null) return refuse(m + " at 0x" + hex(norm.get(i + 1).offset));
            }
        }

        // The register each monitor-enter locked, for the stub's preference.
        Map<Integer, Integer> enterReg = new HashMap<>();
        for (TypeInference.Insn insn : norm) {
            if (insn.family() == TypeInference.F_MONITOR_ENTER) enterReg.put(insn.offset, insn.arg(0));
        }

        Set<Integer> drop = new HashSet<>();
        Map<Integer, int[]> stub = new HashMap<>();
        Map<Integer, int[]> stubContext = new HashMap<>();
        for (int i : uncaught) {
            TypeInference.Insn insn = norm.get(i);
            int[] regs = lockRegisters(insn, st[i], enterReg);
            if (regs == null) {
                return refuse("throwing opcode 0x" + hex(insn.opcode) + " at 0x" + hex(insn.offset)
                        + " holds a lock outside every catch-all, and no register holds it throughout");
            }
            stub.put(insn.offset, regs);
            stubContext.put(insn.offset, st[i].ctx);
        }
        for (int i = 0; i < n; i++) {
            TypeInference.Insn insn = norm.get(i);
            if (!types.isReachable(insn.offset)) continue;
            if (TypeInference.canThrow(insn.opcode)) continue;
            State s = st[i];
            // Reachable in the previous analysis only through a handler no
            // throwing instruction can enter (TypeInference gives such a handler
            // value edges from its whole range). Leave it as it was; if that is
            // still unbalanced the translator keeps the old output.
            if (s == null) continue;
            boolean covered = false;
            if (wholeCatchAll) {
                int own = ownCatchAll(tries, insn.offset);
                if (own >= 0) {
                    Integer hi = index.get(own);
                    State hs = hi == null ? null : st[hi];
                    if (hs != null && throwTargets.contains(own)) {
                        if (Arrays.equals(hs.ctx, s.ctx)) covered = true;
                        else drop.add(insn.offset);
                    } else if (s.depth() == 0 && rangeThrows(norm, types, tryAt(tries, insn.offset))) {
                        // No throwing instruction enters this handler (the ones in
                        // its range are monitor-exits under GenerateOopMap's
                        // carve-out), so only rows over NON-throwing code reach
                        // it, and a depth-0 point must not: d8's
                        // `monitor-enter; monitor-exit; const; return` for an
                        // empty synchronized block, whose range runs on past the
                        // exit, is "monitor stack underflow" at the handler's
                        // own monitor-exit (GMS DowngradeableSafeParcel, 15 corpus
                        // apps). Its throwing instructions keep their rows, so
                        // the handler stays a table target and keeps its frame.
                        drop.add(insn.offset);
                    } else {
                        // A handler whose context is unknown here: keep the row
                        // exactly as the previous translation had it.
                        covered = true;
                    }
                }
            }
            if (covered || s.depth() == 0) continue;
            int[] regs = lockRegisters(insn, s, enterReg);
            if (regs != null) {
                stub.put(insn.offset, regs);
                stubContext.put(insn.offset, s.ctx);
            }
        }
        return new Plan(drop, stub, stubContext);
    }

    /** Per held lock, outermost first, a register that holds it both on entry
     *  to {@code insn} and after it -- the lock's own monitor-enter register
     *  when that qualifies, else the lowest that does -- or null if some level
     *  has none. "After" matters because the verifier checks a handler at every
     *  bytecode of the instruction's expansion, and a stub's aload must find
     *  the lock throughout. */
    private static int[] lockRegisters(TypeInference.Insn insn, State s,
                                       Map<Integer, Integer> enterReg) {
        State after = afterDefs(insn, insn.family(), s);
        int[] regs = new int[s.depth()];
        for (int k = 0; k < regs.length; k++) {
            BitSet both = (BitSet) s.hold[k].clone();
            both.and(after.hold[k]);
            Integer pref = enterReg.get(s.ctx[k]);
            if (pref != null && both.get(pref)) {
                regs[k] = pref;
            } else {
                int r = both.nextSetBit(0);
                if (r < 0) return null;
                regs[k] = r;
            }
        }
        return regs;
    }

    /** The state after a non-monitor instruction: a register it writes no
     *  longer holds a lock, except the copy a move-object makes of one. */
    private static State afterDefs(TypeInference.Insn insn, int fam, State s) {
        if (s.depth() == 0) return s;
        int d = defRegister(fam, insn);
        if (d < 0) return s;
        boolean wide = writesWide(insn.opcode);
        int src = (insn.opcode >= 0x07 && insn.opcode <= 0x09) ? insn.arg(1) : -1;  // move-object*
        BitSet[] nh = null;
        for (int k = 0; k < s.hold.length; k++) {
            BitSet h = s.hold[k];
            BitSet b = (BitSet) h.clone();
            b.clear(d);
            if (wide) b.clear(d + 1);
            if (src >= 0 && h.get(src)) b.set(d);
            if (b.equals(h)) continue;
            if (nh == null) nh = s.hold.clone();
            nh[k] = b;
        }
        return nh == null ? s : new State(s.ctx, nh);
    }

    /** The register a non-monitor instruction writes, or -1. Same set as
     *  TypeInference.destinationRegister (args[0] for these families). */
    private static int defRegister(int fam, TypeInference.Insn insn) {
        switch (fam) {
            case TypeInference.F_MOVE: case TypeInference.F_MOVE_WIDE:
            case TypeInference.F_MOVE_RESULT: case TypeInference.F_CONST32:
            case TypeInference.F_CONST64: case TypeInference.F_CONST_STRING:
            case TypeInference.F_CONST_CLASS: case TypeInference.F_CHECK_CAST:
            case TypeInference.F_INSTANCE_OF: case TypeInference.F_ARRAY_LEN:
            case TypeInference.F_NEW_INSTANCE: case TypeInference.F_NEW_ARRAY:
            case TypeInference.F_CMP: case TypeInference.F_ARRAY_GET:
            case TypeInference.F_INSTANCE_GET: case TypeInference.F_STATIC_GET:
            case TypeInference.F_UNARY_OP: case TypeInference.F_BINARY_OP:
            case TypeInference.F_BINARY_OP_CONST:
            case TypeInference.F_CONST_METHOD_HANDLE: case TypeInference.F_CONST_METHOD_TYPE:
                return insn.args.length > 0 ? insn.arg(0) : -1;
            default:
                return -1;
        }
    }

    /** Opcodes whose destination is a register PAIR (vA, vA+1), from the DEX
     *  opcode table (source.android.com/docs/core/runtime/dalvik-bytecode):
     *  move-wide*, move-result-wide, const-wide*, aget-wide, iget-wide,
     *  sget-wide, the unary ops with a long/double result, and the long/double
     *  binary ops in both the three-register and the 2addr forms. */
    private static boolean writesWide(int op) {
        op &= 0xff;
        if (op >= 0x04 && op <= 0x06) return true;
        if (op == 0x0b || (op >= 0x16 && op <= 0x19)) return true;
        if (op == 0x45 || op == 0x53 || op == 0x61) return true;
        switch (op) {
            case 0x7d: case 0x80: case 0x81: case 0x83: case 0x86: case 0x88: case 0x89: case 0x8b:
                return true;
            default:
                break;
        }
        if (op >= 0x9b && op <= 0xa5) return true;      // add-long .. ushr-long
        if (op >= 0xab && op <= 0xaf) return true;      // add-double .. rem-double
        if (op >= 0xbb && op <= 0xc5) return true;      // .../2addr long
        return op >= 0xcb && op <= 0xcf;                // .../2addr double
    }

    /** The try block whose range contains {@code offset}, by instruction START,
     *  which is ART's catch lookup (keyed on the dex_pc of the throwing
     *  instruction) and Translator.throwingSpans' membership rule. DEX try items
     *  never overlap, so there is at most one. */
    private static TypeInference.TryBlock tryAt(List<TypeInference.TryBlock> tries, int offset) {
        for (TypeInference.TryBlock t : tries) {
            if (offset >= t.startOffset && offset < t.endOffset) return t;
        }
        return null;
    }

    /** Whether the try range holds a reachable THROWING instruction, so its
     *  catch-all keeps at least one row whatever non-throwing code is dropped
     *  (Translator.wholeRangeLessDropped never drops a throwing one). Without
     *  one the handler would lose every row and become code after an
     *  unconditional branch with no StackMapTable frame. */
    private static boolean rangeThrows(List<TypeInference.Insn> norm, TypeInference.Result types,
                                       TypeInference.TryBlock t) {
        if (t == null) return false;
        for (TypeInference.Insn in : norm) {
            if (in.offset < t.startOffset) continue;
            if (in.offset >= t.endOffset) break;
            if (types.isReachable(in.offset) && TypeInference.canThrow(in.opcode)) return true;
        }
        return false;
    }

    private static int ownCatchAll(List<TypeInference.TryBlock> tries, int offset) {
        TypeInference.TryBlock t = tryAt(tries, offset);
        if (t == null) return -1;
        for (TypeInference.Catch k : t.catches) if (k.exceptionType == null) return k.handlerOffset;
        return -1;
    }

    /** Merge {@code in} into instruction j. Returns a refusal reason on a
     *  context conflict; requeues j when its holder sets shrink. */
    private static String merge(State[] st, ArrayDeque<Integer> work, boolean[] queued,
                                int j, State in) {
        State cur = st[j];
        if (cur == null) {
            st[j] = in;
            if (!queued[j]) { queued[j] = true; work.add(j); }
            return null;
        }
        if (!Arrays.equals(cur.ctx, in.ctx)) return "context conflict";
        BitSet[] nh = null;
        for (int k = 0; k < cur.hold.length; k++) {
            BitSet a = cur.hold[k];
            BitSet b = (BitSet) a.clone();
            b.and(in.hold[k]);
            if (!b.equals(a)) {
                if (nh == null) nh = cur.hold.clone();
                nh[k] = b;
            }
        }
        if (nh != null) {
            st[j] = new State(cur.ctx, nh);
            if (!queued[j]) { queued[j] = true; work.add(j); }
        }
        return null;
    }

    private static int[] successors(TypeInference.Insn insn, int fam) {
        switch (fam) {
            case TypeInference.F_GOTO: return new int[]{insn.arg(0)};
            case TypeInference.F_IF: return new int[]{insn.arg(2)};
            case TypeInference.F_IFZ: return new int[]{insn.arg(1)};
            case TypeInference.F_SWITCH: return insn.switchTargets;
            default: return EMPTY;
        }
    }

    /** TypeInference.fallsThrough, which is private there. */
    private static boolean fallsThrough(int fam) {
        return fam != TypeInference.F_RETURN && fam != TypeInference.F_THROW
                && fam != TypeInference.F_GOTO;
    }

    private static Plan refuse(String why) {
        lastRefusal.set(why);
        return null;
    }

    private static String hex(int v) { return Integer.toHexString(v); }
}
