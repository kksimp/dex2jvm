// MethodSplitter -- cut a method whose translation exceeds the JVM's 65535-byte
// code_length cap into a FORWARD-ONLY CHAIN of synthetic methods, each one
// entered through a dispatch switch.
//
// THE PROBLEM, AND WHY MethodOutliner IS NOT ENOUGH
// JVMS SE21 4.9.1 caps a Code attribute at 65535 bytes; Dalvik's code_item has
// no equivalent limit, so a DEX method can be inexpressible as one JVM method.
// MethodOutliner rescues the easy shape: a method with NO try blocks that has a
// long enough STRAIGHT-LINE region to hoist into a void helper. That covers
// Telegram's EmojiData.<clinit> and BouncyCastle's FPREngine.<clinit>, and it
// covers nothing else, because its region must be branch-free, must not be a
// branch target, and must sit in a method with no exception handlers at all.
//
// The method that actually blocks Telegram is the opposite shape. Measured on
// org.telegram.messenger.web_69799:
//
//     org/telegram/ui/Cells/ChatMessageCell.setMessageContent
//         30,978 code units, 17,486 normalized instructions, 3,991 branches,
//         41 try blocks, 101,260 bytes of JVM bytecode
//
// No region of it is branch-free for more than a handful of instructions, so
// MethodOutliner refuses and the WHOLE CLASS is dropped -- and ChatMessageCell
// is the View that draws every message in a chat, so the app hits
// NoClassDefFoundError as soon as it shows a chat.
//
// THE TRANSFORM
// Cut the instruction stream at points where control flow only ever moves
// FORWARD across the cut, and turn each suffix into a static method that takes
// an entry id:
//
//     void big(a, b) {                    void big(a, b) {
//       ...block 0...                       ...block 0...
//       if (x) goto T1;         ==>         if (x) goto P1;      // portal
//       ...block 1...                       ...block 1...
//     T1: ...block 2...                     goto P2;             // fallthrough
//       ...block 3...                     P1: big$1(1, live...); return;
//     }                                   P2: big$1(0, live...); return;
//                                         }
//                                         private static synthetic
//                                         void big$1(int entry, ...live...) {
//                                           <copy params to scratch>
//                                           <zero-init register slots>
//                                           switch (entry) { 0: S0; 1: S1; }
//                                         S0: <restore live at T0>; goto L0;
//                                         S1: <restore live at T1>; goto L1;
//                                         L0/L1: ...blocks 2,3...
//                                         }
//
// A part's return type is the ORIGINAL method's return type, so a `return` in a
// hoisted block is emitted verbatim and a portal is `invokestatic; xreturn`.
// The chain is strictly forward, so at most one frame per part is on the stack
// and a loop cannot grow it.
//
// WHAT MAKES A CUT LEGAL (all checked, none assumed)
//   1. NO BACK EDGE crosses it. An edge from the suffix into the prefix would
//      have to become a call BACK into the caller, which recurses without bound
//      in a loop. This is the condition that decides the whole transform, and
//      on ChatMessageCell 6,992 of 17,486 positions satisfy it.
//   2. No try range crosses it, and every range's handlers sit on the same side
//      as the range itself. A range that spanned the cut would either lose its
//      coverage of the suffix or wrongly extend over the portal's call.
//   3. The operand stack is empty there: not between an invoke and its
//      move-result, and not inside a `new` whose uninitialized reference is
//      still on the stack (see Translator.computeDeferredNew).
//   4. Every register the analysis says is DEFINED at an entry point has a
//      parameter descriptor: no uninitializedThis, no uninitialized(new).
//   5. MONITORS stay together. JVMS 2.11.10 structured locking requires a
//      monitor-enter and its matching exit to stay in one method, so no cut may
//      sit between the FIRST monitor op and the LAST, and no forward branch out
//      of that window may cross a cut either (a cross-part branch returns from
//      the part, which would leave the frame holding a lock). This used to be a
//      blanket "method contains no monitor-enter/exit" refusal; the window is
//      strictly weaker and never worse -- when it spans the whole method the
//      search reports "no valid cut", which is what the blanket rule produced
//      anyway. See the long comment at the span construction for why an EXACT
//      enter/exit pairing is not derivable by a linear walk.
//   6. `this` is fully initialized throughout the suffix, so an <init> is only
//      split after its super() call.
//
// WHAT IS PASSED
// The values a part receives are those the analysis reports as DEFINED at the
// entry point AND that a backward liveness fixpoint says are still LIVE there.
//
// "Defined" alone would be the safer-looking choice, and it was tried first: it
// is a pure over-approximation and cannot be wrong in the dangerous direction.
// It does not fit. Measured on ChatMessageCell.setMessageContent (96 Dalvik
// registers, several scalars each), the first part's entry points define
// between them 310 and 356 argument SLOTS, against JVMS 4.3.3's ceiling of 255
// -- so every plan was refused and the class still failed. Liveness is not an
// optimization here, it is what makes the descriptor expressible.
//
// So the fixpoint has to be right, because an UNDER-approximation of liveness
// is exactly the bug class this project refuses to ship: a value silently not
// handed over still VERIFIES (the receiving slot was zero-initialized) and
// simply computes the wrong answer, with no gate able to see it. Three things
// make it sound:
//   - it is a real fixpoint, not a backward linear scan, so a loop's back edge
//     cannot make a value look dead before its own definition;
//   - EXCEPTION edges are modelled: live_out of every instruction in a
//     protected range includes live_in of every handler that covers it, which
//     is the JVM's own rule (JVMS 4.10.1.6 applies a handler to every
//     instruction in the range, throwing or not);
//   - a def of register r with scalar mask S kills only the (r, s) pairs for s
//     in S, so a differently-typed slot for the same register stays alive.
// The last two are over-approximations, which can only ADD a parameter that
// turns out to be unnecessary. If the fixpoint does not converge, the plan is
// refused rather than truncated.
//
// DEX2JVM_SPLIT_NO_LIVENESS=1 turns the trimming off, so a future maintainer
// who suspects this analysis can A/B it in one run rather than re-deriving the
// boundary. With it set, only methods whose entry points fit in 250 slots
// unaided can be split at all.
//
// TYPES ARE NEVER WIDENED, SO NO CAST IS EVER INSERTED
// A parameter is keyed by (register, scalar, DESCRIPTOR), so a register that
// holds a String at one entry and an Integer at another becomes TWO parameters
// rather than one Object parameter plus a checkcast. Callers pass null for the
// one their entry does not use. This is deliberate: a checkcast that the
// original code did not contain can throw ClassCastException where the original
// did not, and inference types are not always provable at runtime (an interface
// type in particular is never checked by the verifier). No cast, no new
// exception.
//
// WHAT THIS DOES NOT DO
// It refuses rather than guesses. The refusals are listed in `refuse` calls and
// are visible with DEX2JVM_SPLIT_DEBUG=1. A refused method fails exactly as
// it did before this class existed -- the class is dropped -- so this can only
// improve the outcome.

package io.github.kksimp.dex2jvm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class MethodSplitter {

    private MethodSplitter() {}

    /**
     * Test knob: pretend the code limit is this many bytes, so ordinary methods
     * get split and the transform can be exercised by the semantic gate.
     *
     * Same reason MethodOutliner has one. javac refuses to compile a method
     * over 65535 bytes ("code too large"), so there is no way to build a javac
     * reference run for a method big enough to trigger the real threshold;
     * lowering the threshold instead lets
     * {@code DEX2JVM_SPLIT_FORCE=200 tests/semantic/run.sh} push every case
     * in the suite through the splitter and diff the result against javac.
     *
     * Unset normally, when the only trigger is a real overflow.
     */
    private static final int FORCE = intEnv("DEX2JVM_SPLIT_FORCE");

    private static final boolean DEBUG = System.getenv("DEX2JVM_SPLIT_DEBUG") != null;

    private static int intEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) return -1;
        try {
            int n = Integer.parseInt(v.trim());
            return n > 0 ? n : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static boolean debug() { return DEBUG; }

    /** True when the test knob is on, which makes DexConverter arm splitting for
     *  every class rather than only for one that already overflowed, and makes
     *  Translator prefer the splitter over MethodOutliner. */
    static boolean forced() { return FORCE > 0; }

    /** The code_length a method must exceed before it is worth splitting. */
    static int codeLimit() { return FORCE > 0 ? FORCE : 65535; }

    /**
     * Bytes of ORIGINAL bytecode to aim for in one part, on the first attempt.
     *
     * Well under the limit because a part also carries its own zero-init
     * prologue, parameter unpacking, entry stubs and portals, and because
     * CodeWriter's branch-widening fixpoint can only make a body LONGER than
     * the measuring pass saw. Overshooting costs one more part; undershooting
     * costs the whole class, so Translator.translate retries -- and it shrinks
     * the target from the OVERFLOWING LENGTH it was told about rather than
     * walking a fixed ladder, because the fixed overhead per part does not
     * scale with the budget and a ladder either overshoots it or wastes
     * attempts. See shrink().
     */
    static final int FIRST_TARGET = 40000;

    /** Smallest budget worth trying: below this the fixed prologue dominates
     *  and another attempt cannot help. */
    static final int MIN_TARGET = 24;

    static int initialTarget() {
        return Math.min(FIRST_TARGET, Math.max(MIN_TARGET, codeLimit() * 2 / 3));
    }

    /**
     * The next budget to try after a part came out {@code emitted} bytes long
     * against a limit it broke.
     *
     * Scaled by how far over it went, with a safety factor, so one measurement
     * lands close instead of halving blindly. Always strictly smaller, so the
     * retry loop terminates.
     */
    static int shrink(int target, int emitted) {
        int want = codeLimit() * 4 / 5;
        long scaled = (long) target * want / Math.max(1, emitted);
        int next = (int) Math.min(Integer.MAX_VALUE, scaled);
        return Math.min(target - 1, Math.max(MIN_TARGET, next));
    }

    /**
     * Argument-slot ceiling for a part.
     *
     * JVMS 4.3.3 caps a method descriptor at 255 argument slots including the
     * receiver, a long or double contributing two. A part is STATIC, so there
     * is no receiver and all 255 units are available to parameters.
     *
     * `slot` below starts at 1 because slot 0 is the entry id, and the id's own
     * `I` is therefore already counted -- so after the loop `slot` IS the total
     * parameter unit count and 255 is exactly the legal bound, not an
     * approximation of it.
     *
     * ⚠ This was 250, described as leaving "room without a second check". The
     * second check it was reserving for is the receiver, which a static method
     * does not have, so the five slots bought nothing and cost real methods:
     * measured on Instagram, one part came in at EXACTLY 255 and the whole
     * method was refused for it. Being conservative is free only when nothing
     * lands in the margin.
     */
    private static final int MAX_PARAM_SLOTS = 255;

    /** Bound on the liveness fixpoint, so a pathological CFG cannot spin. */
    private static final int MAX_LIVENESS_PASSES = 400;

    /** Escape hatch: pass everything DEFINED rather than everything LIVE. See
     *  the header -- this is here so a suspected liveness bug can be A/B'd. */
    private static final boolean NO_LIVENESS =
            System.getenv("DEX2JVM_SPLIT_NO_LIVENESS") != null;

    /**
     * DEX2JVM_CLINIT_SPILL (default ON; =0 restores the argument-slot
     * refusal, byte for byte).
     *
     * A part of a split &lt;clinit&gt; whose live set does not fit JVMS 4.3.3's 255
     * argument slots receives its values through private static synthetic
     * FIELDS of the same class instead of through parameters. See "THE
     * ARGUMENT-SLOT WALL AND THE &lt;clinit&gt; SPILL" below.
     */
    static final boolean CLINIT_SPILL =
            !"0".equals(System.getenv("DEX2JVM_CLINIT_SPILL"));

    // THE ARGUMENT-SLOT WALL AND THE <clinit> SPILL
    //
    // A part's values arrive as parameters, and JVMS 4.3.3 caps a descriptor at
    // 255 argument units. That is a FLOOR for one common shape, not a near-miss:
    // an R8-built enum <clinit> keeps every constant it creates in its own
    // register until the final filled-new-array / System.arraycopy that builds
    // $VALUES, so the live set at a cut grows with the number of constants
    // already made. Measured on Instagram's X/11D.<clinit> (1,234 constants,
    // 1,250 registers, 81,036 bytes): shrinking the budget drives the demand
    // 910 -> 731 -> ... -> 268 and then stops moving, and the method was
    // stubbed -- which for a <clinit> means the class never initialises, so
    // every one of its 63 referencing classes sees ExceptionInInitializerError
    // and then NoClassDefFoundError.
    //
    // For a <clinit> ONLY, the values can go through static fields instead:
    //   - it runs at most once per class, on one thread, under the class
    //     initialization lock (JVMS 5.5), and a part is private and only ever
    //     entered through the one portal that just wrote its fields, so there is
    //     no reentrancy and no second writer. An ordinary method has neither
    //     guarantee (recursion, other threads), which is why it keeps the
    //     parameter path and its refusal;
    //   - a field is typed with exactly the parameter's descriptor, so
    //     putstatic/getstatic move the value with NO cast and NO runtime check
    //     (putstatic has none; a T[] spill would have aastore's, a Object[] spill
    //     a checkcast, and the header's no-cast rule forbids both for the reason
    //     it gives). Primitive descriptors here are only I/F/J/D, so the
    //     putstatic narrowing JVMS 6.5 applies to a boolean field cannot occur;
    //   - a reference field is nulled by the entry that reads it, so nothing the
    //     original code would have let die is kept alive by the class.
    // The fields are private static synthetic, like the part methods that
    // already sit on a split class; reflection sees both, as it already did the
    // parts. An interface is excluded: JVMS 4.5 requires its fields to be public
    // static final, and a final field cannot be re-written by the next portal.
    //
    // The spill is a LAST RESORT. Translator.translate first runs its whole
    // retry ladder with the spill off, so any method a parameter-only plan can
    // place -- at any budget -- comes out byte for byte as before. Only when that
    // ladder fails and one of its refusals was this slot ceiling does an
    // eligible <clinit> get a second ladder with `spillAllowed`; within that
    // plan only a part OVER the ceiling is spilled, and every part that fits
    // keeps its parameters.

    /** The LAST valid cut whose distance from `startBytes` is within `bound`,
     *  or -1. Factored out so the widened second attempt cannot drift from the
     *  first: both passes must apply identical validity rules and differ only
     *  in the byte bound. */
    private static int scanForCut(int startIdx, int startBytes, int bound, int n,
                                  boolean[] valid, int[] off,
                                  java.util.function.IntUnaryOperator bytecodeOf) {
        int pick = -1;
        for (int p = startIdx + 1; p < n; p++) {
            if (!valid[p]) continue;
            int b = bytecodeOf.applyAsInt(off[p]);
            if (b < 0) continue;
            if (b - startBytes > bound) break;
            pick = p;
        }
        return pick;
    }

    /** The FIRST valid cut PAST `over` bytes from `startBytes` and still within
     *  `bound`, or -1.
     *
     *  This is the rescue scan, and it deliberately takes the NEAREST legal cut
     *  rather than the furthest. scanForCut maximises a part, which is right
     *  when the part is known to fit; here the part is already past the budget
     *  that was chosen to leave room for CodeWriter's branch-widening fixpoint,
     *  so the goal flips to overshooting as LITTLE as possible. Taking the
     *  furthest cut under the ceiling would leave no margin at all, and a part
     *  that then overflows costs the whole method -- the retry that catches it
     *  has widening disabled, so it will simply refuse. Nearest-first keeps the
     *  part near the target and pays at most one extra part for it. */
    private static int scanFirstCutBeyond(int startIdx, int startBytes, int over, int bound,
                                          int n, boolean[] valid, int[] off,
                                          java.util.function.IntUnaryOperator bytecodeOf) {
        for (int p = startIdx + 1; p < n; p++) {
            if (!valid[p]) continue;
            int b = bytecodeOf.applyAsInt(off[p]);
            if (b < 0) continue;
            int d = b - startBytes;
            if (d > bound) break;
            if (d > over) return p;
        }
        return -1;
    }

    /** The method `compute` is currently planning, so a refusal can NAME it.
     *
     *  Without this the refusals are correlated to methods POSITIONALLY -- by
     *  reading whichever `STUBBED` line follows -- which is fragile and silently
     *  wrong the moment output interleaves. And it DOES interleave: conversion
     *  runs on min(cores,8) threads, so this is ThreadLocal for the same reason
     *  LAST_ENTRY_FAILURE is. */
    private static final ThreadLocal<String> CURRENT_METHOD = new ThreadLocal<>();

    /** Set when the refusal depends on WHERE THE CUTS LANDED, so a different
     *  budget would produce a different plan and is worth trying.
     *
     *  The caller's ladder shrinks the target and retries, and its own comment
     *  used to read "refused; a smaller budget cannot help". That is right for a
     *  STRUCTURAL refusal ("no valid cut within N bytes" only gets worse as the
     *  budget falls; "more than 64 parts" likewise) and WRONG for a refusal that
     *  is a property of the chosen boundaries -- the argument-slot ceiling above
     *  all, since a part's descriptor is the union of what is live at ITS entry
     *  points, and moving the cut moves that set. Treating the two alike threw
     *  away methods that a second plan would have accepted.
     *
     *  ThreadLocal for the same reason as the other two: conversion is
     *  parallel. */
    private static final ThreadLocal<Boolean> RETRY_WORTHWHILE = new ThreadLocal<>();

    /** True when the last refusal on this thread might be cured by a different
     *  budget. Only meaningful immediately after compute() returned null. */
    static boolean lastRefusalWasBudgetDependent() {
        return Boolean.TRUE.equals(RETRY_WORTHWHILE.get());
    }

    /** How many argument slots the worst part of the last refused plan needed,
     *  or 0 when the refusal was not the slot ceiling.
     *
     *  The ladder uses this to tell "nearly fits" from "chasing an asymptote".
     *  MEASURED on Instagram's `X/11D.<clinit>`, one line per attempt:
     *  910, 731, 590, 475, 384, 310, 271, 270, 268 -- a 20% budget cut buying
     *  1 slot by the end. The live set at the tightest boundary is a property
     *  of the METHOD, and below some width no budget reaches it, so continuing
     *  costs a full re-plan per attempt and cannot win. */
    private static final ThreadLocal<Integer> LAST_SLOT_DEMAND = new ThreadLocal<>();

    static int lastSlotDemand() {
        Integer v = LAST_SLOT_DEMAND.get();
        return v == null ? 0 : v;
    }

    private static Plan refuse(String why, Object... args) {
        if (DEBUG) {
            String m = CURRENT_METHOD.get();
            System.err.println("[dex-split] refused" + (m == null ? "" : " " + m) + ": "
                               + String.format(why, args));
        }
        return null;
    }

    /** A refusal a different budget might cure. See RETRY_WORTHWHILE. */
    private static Plan refuseRetryable(String why, Object... args) {
        RETRY_WORTHWHILE.set(Boolean.TRUE);
        return refuse(why, args);
    }

    // ==================================================================
    // Plan shapes
    // ==================================================================

    /** One value a part receives. Keyed by (register, scalar, descriptor): the
     *  same register with two different reference types becomes two parameters,
     *  which is what keeps every type exact and every cast unnecessary. */
    static final class Param {
        final int register;
        final int scalar;
        /** JVM local slot the body reads it from, i.e. Locals' assignment. */
        final int slot;
        final String descriptor;
        /** Slot inside the SCRATCH copy of the argument block, filled in when
         *  the part's descriptor is laid out. */
        int scratchSlot = -1;
        /** For a param of a SPILLED part: the private static field it travels
         *  through (typed {@link #descriptor}), else null. */
        String spillField;

        Param(int register, int scalar, int slot, String descriptor) {
            this.register = register;
            this.scalar = scalar;
            this.slot = slot;
            this.descriptor = descriptor;
        }

        int width() {
            return (scalar == DexType.LONG || scalar == DexType.DOUBLE) ? 2 : 1;
        }

        String key() { return register + "/" + scalar + "/" + descriptor; }
    }

    /** One place control may enter a part from an earlier part. */
    static final class Entry {
        final int dexOffset;
        /** Dispatch key, dense from 0 within its part. */
        final int id;
        /** Parameters this entry restores. A subset of its part's list. */
        final List<Param> params = new ArrayList<>();
        /** Slots whose value at this entry is the null type, which has no
         *  descriptor and is restored with aconst_null instead. */
        final List<int[]> nullSlots = new ArrayList<>();   // {slot}

        Entry(int dexOffset, int id) { this.dexOffset = dexOffset; this.id = id; }
    }

    /** One method in the chain. Part 0 is the original method itself. */
    static final class Part {
        final int index;
        /** First DEX offset in this part. 0 for part 0. */
        final int startOffset;
        /** Name of the synthetic method, null for part 0. */
        final String name;
        /** Descriptor of the synthetic method, null for part 0. */
        String descriptor;
        /** Parameters after the leading entry-id int, in argument order. */
        final List<Param> params = new ArrayList<>();
        final List<Entry> entries = new ArrayList<>();
        /** Argument slots including the leading entry id. */
        int argSlots;
        /** True when {@link #params} travel through static fields rather than
         *  arguments; the descriptor is then just the entry id. See CLINIT_SPILL. */
        boolean spilled;

        Part(int index, int startOffset, String name) {
            this.index = index;
            this.startOffset = startOffset;
            this.name = name;
        }
    }

    static final class Plan {
        private final List<Part> parts;
        /** Ascending start offsets, parallel to parts. */
        private final int[] starts;
        private final Map<Integer, Entry> entryByOffset = new HashMap<>();
        /** {name, descriptor} of every spill field, in creation order. Empty
         *  unless a part is spilled. */
        private final List<String[]> spillFields;

        Plan(List<Part> parts) { this(parts, java.util.Collections.emptyList()); }

        Plan(List<Part> parts, List<String[]> spillFields) {
            this.parts = parts;
            this.spillFields = spillFields;
            this.starts = new int[parts.size()];
            for (int i = 0; i < parts.size(); i++) {
                starts[i] = parts.get(i).startOffset;
                for (Entry e : parts.get(i).entries) entryByOffset.put(e.dexOffset, e);
            }
        }

        List<Part> parts() { return parts; }

        /** The static fields a spilled part's values travel through, as
         *  {name, descriptor}, for the translator to declare on the class. */
        List<String[]> spillFields() { return spillFields; }

        /** Which part a DEX offset belongs to. Parts partition the offset space
         *  into ascending half-open ranges, so this is a plain bucket search and
         *  answers for an offset that is not an instruction start too (a try
         *  range end that landed in a data payload, say). */
        int partIndexOf(int dexOffset) {
            int lo = 0, hi = starts.length - 1, ans = 0;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (starts[mid] <= dexOffset) { ans = mid; lo = mid + 1; } else hi = mid - 1;
            }
            return ans;
        }

        Part partOf(int dexOffset) { return parts.get(partIndexOf(dexOffset)); }

        /** The entry record for a DEX offset that a portal targets, or null. */
        Entry entryFor(int dexOffset) { return entryByOffset.get(dexOffset); }
    }

    // ==================================================================
    // Planning
    // ==================================================================

    /**
     * @param norm        normalized instruction stream, in offset order
     * @param types       the inference the same stream was emitted from
     * @param locals      the (register, scalar) -&gt; JVM slot assignment in force
     * @param tries       the method's try blocks
     * @param deferredNew new-instance DEX offset -&gt; the &lt;init&gt; that consumes it
     * @param thisUninit  DEX offsets at which JVM local 0 is uninitializedThis
     * @param bytecodeOf  DEX offset -&gt; bytecode offset from the overflowing
     *                    translation; -1 for an offset that emitted nothing
     * @param totalBytes  the measured code_length that overflowed
     * @param targetBytes bytes of original bytecode to aim for per part
     * @param returnDescriptor the ORIGINAL method's return descriptor, which
     *                    every part shares so a hoisted `return` is emitted
     *                    verbatim and a portal is `invokestatic; xreturn`
     * @param taken       method names already on the class, so a synthetic name
     *                    cannot collide
     * @param allowWiden  may a part grow past {@code targetBytes} (up to the
     *                    JVM ceiling) when no legal cut sits inside it. TRUE
     *                    only on the caller's FIRST attempt: once a part has
     *                    actually overflowed, the caller shrinks the target,
     *                    and widening back to the ceiling would silently undo
     *                    that and make the retry ladder a no-op.
     * @param methodTag   name fragment identifying the method being split
     * @param spillAllowed may a part over the argument-slot ceiling take its
     *                    values through static fields (see CLINIT_SPILL). The
     *                    caller sets it only for a class's &lt;clinit&gt;, never for
     *                    an interface's.
     * @param takenFields field names already on the class, so a spill field
     *                    cannot collide
     */
    static Plan compute(List<TypeInference.Insn> norm,
                        TypeInference.Result types,
                        Translator.Locals locals,
                        List<TypeInference.TryBlock> tries,
                        Map<Integer, Integer> deferredNew,
                        Set<Integer> thisUninit,
                        java.util.function.IntUnaryOperator bytecodeOf,
                        int totalBytes,
                        int targetBytes,
                        boolean allowWiden,
                        String returnDescriptor,
                        Set<String> taken,
                        String methodTag,
                        boolean spillAllowed,
                        Set<String> takenFields) {
        CURRENT_METHOD.set(methodTag);
        RETRY_WORTHWHILE.set(Boolean.FALSE);
        LAST_SLOT_DEMAND.set(0);
        final int n = norm.size();
        if (n < 8) return refuse("only %d instructions", n);
        if (locals.entries().isEmpty()) return refuse("no local slots allocated");

        final int[] off = new int[n];
        final Map<Integer, Integer> indexOf = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) { off[i] = norm.get(i).offset; indexOf.put(off[i], i); }
        // An edge's target need not be an instruction START in `norm`: Normalizer
        // drops data payloads, and a fall-through across one lands on the next
        // EMITTED instruction. Resolving by CEILING rather than exact match is
        // what makes that edge visible to the back-edge test and to the entry
        // discovery; an exact lookup silently ignored it, which would have let a
        // part be entered at a point no dispatch id names.
        final Resolver resolve = new Resolver(off);

        // (1) sufMin[p] = the lowest index any instruction at or after p can
        // reach. p is back-edge-free exactly when sufMin[p] >= p.
        int[] minTo = new int[n];
        for (int i = 0; i < n; i++) {
            minTo[i] = i;
            for (int s : successors(norm.get(i))) {
                int si = resolve.indexAtOrAfter(s);
                if (si >= 0) minTo[i] = Math.min(minTo[i], si);
            }
        }
        int[] sufMin = new int[n + 1];
        sufMin[n] = Integer.MAX_VALUE;
        for (int i = n - 1; i >= 0; i--) sufMin[i] = Math.min(sufMin[i + 1], minTo[i]);

        // (2) Every try block's covered range AND its handlers, as an index span
        // that must not be straddled.
        List<int[]> spans = new ArrayList<>();
        for (TypeInference.TryBlock t : tries) {
            int lo = Integer.MAX_VALUE, hi = -1;
            for (int i = 0; i < n; i++) {
                if (off[i] >= t.startOffset && off[i] < t.endOffset) {
                    lo = Math.min(lo, i); hi = Math.max(hi, i);
                }
            }
            for (TypeInference.Catch c : t.catches) {
                Integer x = indexOf.get(c.handlerOffset);
                if (x == null) continue;
                lo = Math.min(lo, x); hi = Math.max(hi, x);
            }
            if (hi >= 0) spans.add(new int[]{ lo, hi });
        }

        // (5) MONITORS. JVMS 2.11.10 structured locking is defined PER METHOD, so
        // a monitor-enter and its matching exit(s) must land in the SAME
        // synthetic part; a cut between them produces a part that locks without
        // unlocking (and one that unlocks without locking), and the verifier
        // rejects both.
        //
        // This used to be a BLANKET refusal -- "method uses monitor-enter/exit"
        // -- on the stated grounds that proving a monitor region does not span a
        // cut is a separate dataflow. That is true of an EXACT pairing, and it is
        // not needed: a single span from the first monitor op to the last forces
        // every enter and every exit into one part, which is sufficient for
        // structured locking without pairing anything.
        //
        // Deliberately conservative, and here is why an exact pairing is the
        // wrong thing to attempt. A `synchronized` block compiles to a normal
        // monitor-exit on the fall-through path AND a second monitor-exit in a
        // catch-all handler that rethrows, so the exits are not linearly nested
        // with their enter -- a depth counter walking the instruction list goes
        // negative at the handler and any pairing built on it is wrong. The
        // handler also sits outside the body's linear range. Taking the whole
        // min..max monitor window sidesteps that entirely.
        //
        // The cost is only ever a narrower choice of cut: if the window spans the
        // method, the greedy search below reports "no valid cut", which is
        // exactly the outcome the blanket refusal produced anyway. So this is
        // strictly better than before and never worse.
        //
        // ⚠ MEASURED: this is what stubbed `X/9Pu.A09` (75,928 bytes), the entire
        // opcode switch of Instagram's Bloks Lispy interpreter. Every script the
        // interpreter evaluates goes through that switch, so a stub there means
        // each one is parsed and then thrown away; the failure is easy to miss
        // because the app routes it to its own error reporter, not to
        // android.util.Log.
        {
            int mLo = Integer.MAX_VALUE, mHi = -1;
            for (int i = 0; i < n; i++) {
                int op = norm.get(i).opcode & 0xFF;
                if (op == 0x1d || op == 0x1e) {   // monitor-enter / monitor-exit
                    mLo = Math.min(mLo, i);
                    mHi = Math.max(mHi, i);
                }
            }
            if (mHi >= 0) {
                spans.add(new int[]{ mLo, mHi });
                if (DEBUG) System.err.println("[dex-split] monitor window [" + mLo
                    + "," + mHi + "] of " + n + " instructions is cut-forbidden");

                // One span is not sufficient on its own. Keeping the window in
                // ONE part stops a cut from splitting a lock from its unlock,
                // but a FORWARD branch out of the window into a LATER part would
                // still leave the method (the part is a synthetic static method,
                // so a cross-part branch is a return plus a re-dispatch) while a
                // monitor is held. HotSpot unlocks what a popped frame still
                // holds and raises IllegalMonitorStateException, so that is a
                // real failure, not a theoretical one.
                //
                // Backward edges need no handling: rule (1) already invalidates
                // every cut a back edge crosses, anywhere in the method.
                //
                // Forbidding a cut anywhere between such a branch and its target
                // is enough, and it is what an exact monitor-depth pairing would
                // have bought at much greater cost. On well-formed input this
                // adds nothing -- d8 emits monitor-exit BEFORE the branch that
                // leaves a synchronized block -- so it is a guard against
                // restructured (R8-optimised) code, priced at one extra pass.
                for (int i = mLo; i <= mHi; i++) {
                    for (int s : successors(norm.get(i))) {
                        int si = resolve.indexAtOrAfter(s);
                        if (si > i) spans.add(new int[]{ i, si });
                    }
                }
            }
        }

        // (7) UNINITIALIZED REFERENCES may not be live across a part boundary.
        //
        // This is a HARD constraint, not a conservatism. A StackMapTable's
        // Uninitialized_variable_info names the BYTECODE OFFSET of its `new`
        // (JVMS 4.7.4); if the `new` is in one part and the frame in another,
        // that offset points into a different method's byte stream and means
        // nothing. JVMS 4.9.2 separately forbids passing an uninitialized class
        // instance as an argument, so it cannot be handed over as a parameter
        // either. There is no way to express the hand-over; the only fix is to
        // not create the boundary.
        //
        // ⚠ Rule (3)'s `deferredNew` does NOT cover this, and the reason is
        // worth writing down because the two look like the same check.
        // Translator.computeDeferredNew records a new-instance only while its
        // window is simple: its forward walk BREAKS on a branch target, on
        // anything touching the register, and on anything diverting control. So
        // it describes the DEFERRED `new`/`dup` pattern, where the JVM `new` is
        // emitted late at the <init> site and nothing uninitialized ever lives
        // in a local. When the window contains a branch target the translator
        // must emit the `new` EAGERLY and keep the uninitialized reference in a
        // REGISTER across that target -- a different mechanism, correctly not in
        // the map, and exactly the case that reaches here.
        //
        // ⚠ MEASURED: this is what stubbed Instagram's `X/11D.<clinit>` and
        // `X/cIH.<clinit>`. Both are referenced widely (`X/11D` by 63 classes),
        // and a stubbed <clinit> is worse than a stubbed ordinary method -- the
        // class is kept but never initialises, so the first touch is
        // ExceptionInInitializerError and every one after is
        // NoClassDefFoundError. The regions are TINY: new-instance @0x3af4 with
        // the entry at 0x3af9, and @0x6343 with the entry at 0x634b. Before
        // this rule the greedy happily cut there and buildEntry then refused the
        // WHOLE METHOD; now the cut simply moves.
        //
        // Why a span over states is sufficient, given an entry is a branch
        // target rather than a cut: `entryState` is the MERGE over all
        // predecessors, so if the state at an index says a register holds
        // uninit@N, every path there has it live and N dominates the index.
        // Any branch into the region therefore starts inside the region too,
        // and the cut between them would fall strictly inside the span.
        {
            java.util.Set<Integer> regs = new java.util.LinkedHashSet<>();
            for (Translator.Locals.Entry le : locals.entries()) regs.add(le.register);
            Map<Integer, int[]> uninit = new LinkedHashMap<>();   // newOffset -> {loIdx, hiIdx}
            for (int i = 0; i < n; i++) {
                RegisterState st = types.entryState(off[i]);
                if (st == null) continue;
                for (int reg : regs) {
                    DexType t = st.get(reg);
                    if (t == null || t.isDead()) continue;
                    if ((t.scalar() & DexType.OBJ) == 0) continue;
                    DexType.Ref r = t.ref();
                    // KIND_UNINIT_THIS carries no newOffset and is already
                    // handled by rule (6)'s lastUninit.
                    if (r == null || r.kind != DexType.Ref.KIND_UNINIT) continue;
                    Integer ni = indexOf.get(r.newOffset);
                    int lo = ni == null ? i : ni;
                    int[] cur = uninit.get(r.newOffset);
                    if (cur == null) uninit.put(r.newOffset, new int[]{ lo, i });
                    else { cur[0] = Math.min(cur[0], lo); cur[1] = Math.max(cur[1], i); }
                }
            }
            int widest = 0;
            for (int[] s : uninit.values()) {
                spans.add(s);
                widest = Math.max(widest, s[1] - s[0]);
            }
            if (DEBUG && !uninit.isEmpty()) {
                System.err.println("[dex-split] " + uninit.size()
                    + " uninitialized-ref window(s) are cut-forbidden, widest "
                    + widest + " of " + n + " instructions");
            }
        }

        // (6) The highest index at which `this` may still be uninitialized. No
        // cut may sit at or before it.
        int lastUninit = -1;
        if (thisUninit != null) {
            for (int u : thisUninit) {
                Integer x = indexOf.get(u);
                if (x != null) lastUninit = Math.max(lastUninit, x);
            }
        }

        boolean[] valid = new boolean[n];
        for (int p = 1; p < n; p++) valid[p] = validCut(p, norm, types, off, sufMin, spans,
                                                        deferredNew, lastUninit, bytecodeOf);

        // A part carries a FIXED overhead the body's byte count knows nothing
        // about: the zero-init of every register slot, the copy of the
        // arguments into scratch, one entry stub per entry point, and one
        // portal per cross-part branch in the PREVIOUS part. The zero-init is
        // the part that is knowable here and it is the biggest of them at ~4
        // bytes per local slot, so the budget is reduced by it up front rather
        // than discovered by a failed emission. The rest is what
        // Translator.translate's retry is for.
        int overhead = 4 * locals.entries().size() + 64;
        final int hardCeil = Math.max(MIN_TARGET, codeLimit() - overhead);
        targetBytes = Math.min(targetBytes, hardCeil);

        // Greedy: from each part start take the LAST valid cut still inside the
        // byte target, so parts are as large as they may be.
        List<Integer> cuts = new ArrayList<>();
        int startIdx = 0;
        while (true) {
            int startBytes = bytecodeOf.applyAsInt(off[startIdx]);
            if (startBytes < 0) return refuse("part start 0x%x emitted nothing", off[startIdx]);
            if (totalBytes - startBytes <= targetBytes) break;   // the tail fits
            int pick = scanForCut(startIdx, startBytes, targetBytes, n, valid, off, bytecodeOf);
            if (pick < 0 && allowWiden && targetBytes < hardCeil) {
                // WIDEN ONCE, to the real JVM ceiling, before giving up.
                //
                // FIRST_TARGET (40000) is a HEURISTIC start, chosen to leave room
                // for CodeWriter's branch-widening fixpoint, and the existing
                // retry ladder only ever shrinks it -- which is right for an
                // overflow but exactly backwards for THIS failure. Valid cuts are
                // sparse in a loop-heavy method (a cut may not sit where a back
                // edge crosses it, rule 1), so "no cut within 40000 bytes" often
                // means the nearest legal cut is simply further away, not that
                // none exists. Refusing there throws away a method the ceiling
                // would have allowed.
                //
                // Widening is safe because the part is still bounded by
                // codeLimit() - overhead, and if the emitted body nevertheless
                // overflows, Translator.translate's shrink-retry is the existing
                // net for precisely that. Overshooting costs one more part;
                // refusing costs the method -- this file's own header makes that
                // trade explicit.
                //
                // ⚠ ONLY on the caller's first attempt (`allowWiden`). Widening
                // on a RETRY would jump straight back to the ceiling the retry
                // exists to come down from, so the ladder would re-plan the same
                // too-big part eight times and then give up. The two mechanisms
                // answer opposite failures: widen means "no legal cut is near",
                // shrink means "the part I emitted was too big".
                //
                // ⚠ MEASURED on Instagram's `X/9Pu.A09` (75,928 bytes), the whole
                // opcode switch of Bloks' Lispy interpreter: with the monitor
                // rule relaxed it found ONE cut and then reported "no valid cut
                // within 40000 bytes of index 2737" with ~10k bytes still to
                // shed.
                pick = scanFirstCutBeyond(startIdx, startBytes, targetBytes, hardCeil,
                                          n, valid, off, bytecodeOf);
                if (pick >= 0 && DEBUG) {
                    System.err.println("[dex-split] widened target " + targetBytes
                        + " -> " + hardCeil + " to reach a valid cut at index " + pick
                        + " (" + (bytecodeOf.applyAsInt(off[pick]) - startBytes) + " bytes)");
                }
            }
            if (pick < 0) return refuse("no valid cut within %d bytes of index %d",
                                       allowWiden ? hardCeil : targetBytes, startIdx);
            cuts.add(pick);
            startIdx = pick;
            if (cuts.size() > 64) return refuse("more than 64 parts");
        }
        if (cuts.isEmpty()) return refuse("nothing to cut (total %d, target %d)", totalBytes, targetBytes);

        // ---- liveness, which is what keeps the argument lists expressible
        Map<Long, Integer> bitOf = new HashMap<>();
        List<Translator.Locals.Entry> lentries = locals.entries();
        for (int i = 0; i < lentries.size(); i++) {
            Translator.Locals.Entry e = lentries.get(i);
            bitOf.put(slotKey(e.register, e.scalar), i);
        }
        java.util.BitSet[] liveIn = NO_LIVENESS ? null
                : computeLiveness(norm, types, tries, resolve, bitOf, lentries.size());
        if (liveIn == null && !NO_LIVENESS) return refuse("liveness did not converge");

        // ---- build the parts
        Set<String> used = new HashSet<>(taken);
        List<Part> parts = new ArrayList<>();
        parts.add(new Part(0, off[0], null));
        for (int k = 0; k < cuts.size(); k++) {
            String name = uniqueName(used, methodTag, k);
            used.add(name);
            parts.add(new Part(k + 1, off[cuts.get(k)], name));
        }
        int[] partOfIndex = new int[n];
        {
            int pi = 0;
            for (int i = 0; i < n; i++) {
                if (pi + 1 < parts.size() && i >= cuts.get(pi)) pi++;
                partOfIndex[i] = pi;
            }
        }

        // ---- entry points: every successor that lands in a LATER part
        // TreeSet so the dispatch ids are a deterministic function of the code.
        List<TreeSet<Integer>> entryOffsets = new ArrayList<>();
        for (int k = 0; k < parts.size(); k++) entryOffsets.add(new TreeSet<>());
        for (int i = 0; i < n; i++) {
            if (!types.isReachable(off[i])) continue;
            for (int s : successors(norm.get(i))) {
                int si = resolve.indexAtOrAfter(s);
                if (si < 0) continue;
                // The entry is recorded at the offset control actually REACHES,
                // which is the resolved instruction, not the raw edge target.
                if (partOfIndex[si] > partOfIndex[i]) entryOffsets.get(partOfIndex[si]).add(off[si]);
            }
        }

        // Spill fields are shared across parts by Param key: a portal writes the
        // fields of the one part it calls immediately before the call, and that
        // part's entry stub reads them first thing, so two parts reusing one
        // field never overlap in time. LinkedHashMap so the names, and the order
        // they are declared on the class in, are a function of the code.
        Map<String, String> spillNameByKey = new LinkedHashMap<>();
        List<String[]> spillFields = new ArrayList<>();
        Set<String> usedFields = new HashSet<>(takenFields);
        for (int k = 1; k < parts.size(); k++) {
            Part part = parts.get(k);
            Map<String, Param> union = new LinkedHashMap<>();
            int id = 0;
            for (int t : entryOffsets.get(k)) {
                Entry e = new Entry(t, id++);
                Integer ti = indexOf.get(t);
                if (ti == null) return refuseRetryable("entry 0x%x is not an instruction", t);
                if (!buildEntry(e, types, locals, norm, indexOf, deferredNew, union,
                                liveIn == null ? null : liveIn[ti], bitOf)) {
                    String why = LAST_ENTRY_FAILURE.get();
                    return refuseRetryable("entry 0x%x of part %d cannot be typed: %s", t, k,
                                           why == null ? "unknown" : why);
                }
                part.entries.add(e);
            }
            // Deterministic argument order, independent of discovery order.
            List<Param> ps = new ArrayList<>(union.values());
            ps.sort(Comparator.<Param>comparingInt(p -> p.slot)
                              .thenComparingInt(p -> p.scalar)
                              .thenComparing(p -> p.descriptor));
            int slot = 1;   // slot 0 is the entry id
            StringBuilder desc = new StringBuilder("(I");
            for (Param p : ps) {
                p.scratchSlot = slot;      // provisional; rebased in the emitter
                slot += p.width();
                desc.append(p.descriptor);
            }
            if (slot > MAX_PARAM_SLOTS && spillAllowed && CLINIT_SPILL) {
                // The live set is wider than any descriptor can be: hand it over
                // through fields instead. See THE ARGUMENT-SLOT WALL above.
                for (Param p : ps) {
                    p.scratchSlot = -1;
                    String name = spillNameByKey.get(p.key());
                    if (name == null) {
                        name = Options.syntheticPrefix + "$spill$" + spillNameByKey.size();
                        for (int d = 1; usedFields.contains(name); d++) {
                            name = Options.syntheticPrefix + "$spill$" + spillNameByKey.size() + "_" + d;
                        }
                        usedFields.add(name);
                        spillNameByKey.put(p.key(), name);
                        spillFields.add(new String[]{ name, p.descriptor });
                    }
                    p.spillField = name;
                }
                part.spilled = true;
                part.params.addAll(ps);
                part.argSlots = 1;
                part.descriptor = "(I)" + returnDescriptor;
                if (DEBUG) {
                    System.err.println("[dex-split] " + methodTag + ": part " + k
                        + " needs " + slot + " argument slots; spilled " + ps.size()
                        + " values to static fields");
                }
                continue;
            }
            if (slot > MAX_PARAM_SLOTS) {
                LAST_SLOT_DEMAND.set(slot);
                return refuseRetryable("part %d needs %d argument slots", k, slot);
            }
            part.params.addAll(ps);
            part.argSlots = slot;
            part.descriptor = desc.append(')').append(returnDescriptor).toString();
        }

        if (DEBUG) {
            StringBuilder sb = new StringBuilder("[dex-split] " + methodTag + ": "
                + parts.size() + " parts of " + totalBytes + " bytes;");
            for (Part p : parts) {
                sb.append(" [").append(Integer.toHexString(p.startOffset)).append(' ')
                  .append(p.entries.size()).append("e/").append(p.params.size())
                  .append(p.spilled ? "p spilled]" : "p]");
            }
            System.err.println(sb);
        }
        return new Plan(parts, spillFields);
    }

    /** Maps an edge target to the index of the instruction that receives control. */
    private static final class Resolver {
        private final int[] off;
        Resolver(int[] off) { this.off = off; }
        int indexAtOrAfter(int dexOffset) {
            int lo = 0, hi = off.length - 1, ans = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (off[mid] >= dexOffset) { ans = mid; hi = mid - 1; } else lo = mid + 1;
            }
            return ans;
        }
    }

    /** Whether index {@code p} may begin a new part. */
    private static boolean validCut(int p, List<TypeInference.Insn> norm,
                                    TypeInference.Result types, int[] off, int[] sufMin,
                                    List<int[]> spans, Map<Integer, Integer> deferredNew,
                                    int lastUninit, java.util.function.IntUnaryOperator bytecodeOf) {
        if (sufMin[p] < p) return false;                        // (1) a back edge crosses
        if (p <= lastUninit) return false;                      // (6) this still uninitialized
        if (!types.isReachable(off[p])) return false;
        if (bytecodeOf.applyAsInt(off[p]) < 0) return false;    // emitted nothing
        // (3) the operand stack must be empty here
        if (TypeInference.familyOf(norm.get(p).opcode) == TypeInference.F_MOVE_RESULT) return false;
        if (insideDeferredNew(off[p], deferredNew)) return false;
        for (int[] s : spans) if (s[0] < p && s[1] >= p) return false;   // (2)
        return true;
    }

    /**
     * Fill in one entry's restore list and merge its parameters into the part's
     * union, or answer false when a value there cannot be expressed.
     *
     * Everything the analysis reports as DEFINED is restored -- see the header
     * for why this is deliberately not a liveness result.
     */
    /** Why the last buildEntry call failed. Diagnostic only: five different
     *  conditions used to return a bare `false` and the refusal printed one
     *  undifferentiated "cannot be typed", which says nothing about whether the
     *  cause is fixable. Costs a field; saves re-deriving it by bisection.
     *
     *  ⚠ ThreadLocal, NOT a plain static. DexConverter converts classes on
     *  min(cores,8) threads, so a shared field would let one thread's reason be
     *  printed beside another thread's method -- a wrong reason next to the
     *  right method is worse than no reason at all, and it would be
     *  nondeterministic, which is the hardest kind of instrument bug to spot. */
    private static final ThreadLocal<String> LAST_ENTRY_FAILURE = new ThreadLocal<>();

    private static boolean entryFail(String why) { LAST_ENTRY_FAILURE.set(why); return false; }

    private static boolean buildEntry(Entry e, TypeInference.Result types,
                                      Translator.Locals locals,
                                      List<TypeInference.Insn> norm,
                                      Map<Integer, Integer> indexOf,
                                      Map<Integer, Integer> deferredNew,
                                      Map<String, Param> union,
                                      java.util.BitSet live,
                                      Map<Long, Integer> bitOf) {
        Integer idx = indexOf.get(e.dexOffset);
        if (idx == null) return entryFail("not an instruction start");
        // An entry is a branch target or a part boundary, so the stack is empty
        // there for the same reasons a cut needs -- but a branch target is not
        // covered by validCut, so check it here too.
        if (TypeInference.familyOf(norm.get(idx).opcode) == TypeInference.F_MOVE_RESULT) {
            return entryFail("lands on a move-result (stack not empty)");
        }
        if (insideDeferredNew(e.dexOffset, deferredNew)) return entryFail("inside a deferred new");

        RegisterState st = types.entryState(e.dexOffset);
        if (st == null) return entryFail("no inferred entry state");
        List<Translator.Locals.Entry> lentries = locals.entries();

        // An UNINITIALIZED value may not cross, and this test runs BEFORE the
        // liveness filter on purpose.
        //
        // JVMS 4.9.2 forbids passing an uninitialized class instance as an
        // argument, so it could never be handed over -- but the reason the
        // check cannot be folded into the loop below is subtler: a StackMapTable
        // Uninitialized_variable_info names the BYTECODE OFFSET of its `new`
        // (JVMS 4.7.4), and Translator.frameFor publishes a frame entry for
        // every register the state defines, whether or not this entry restores
        // it. A dead-but-uninitialized register therefore still puts an
        // Uninitialized_variable_info in the new part's frame, pointing at a
        // `new` that lives in a different method's byte stream. Filtering by
        // liveness first hid exactly that: measured on the R8 build of
        // tests/semantic/cases/Split.java, an entry whose uninitialized(0x5) was
        // dead passed planning and then failed at SERIALIZATION time, where
        // there is no longer anywhere to fall back to.
        for (Translator.Locals.Entry le : lentries) {
            DexType t = st.get(le.register);
            if (t == null || t.isDead()) continue;
            if ((t.scalar() & DexType.OBJ) == 0) continue;
            DexType.Ref r = t.ref();
            if (r != null && (r.kind == DexType.Ref.KIND_UNINIT
                           || r.kind == DexType.Ref.KIND_UNINIT_THIS)) {
                return entryFail("register v" + le.register + " holds an uninitialized ref ("
                    + (r.kind == DexType.Ref.KIND_UNINIT
                        ? String.format("new-instance @0x%x", r.newOffset) : "uninitializedThis")
                    + ")");
            }
        }

        for (int li = 0; li < lentries.size(); li++) {
            Translator.Locals.Entry le = lentries.get(li);
            if (live != null && !live.get(li)) continue;   // never read before rewritten
            DexType t = st.get(le.register);
            if (t == null || t.isDead()) continue;
            if ((t.scalar() & le.scalar) == 0) continue;
            if (le.scalar == DexType.OBJ && isNullType(t)) {
                e.nullSlots.add(new int[]{ le.slot });
                continue;
            }
            String desc = descriptorFor(le.scalar, t);
            if (desc == null) {                     // uninitialized, or unnameable
                DexType.Ref r = t.ref();
                return entryFail("register v" + le.register + " scalar=" + le.scalar
                    + " has no descriptor (ref kind="
                    + (r == null ? "none" : String.valueOf(r.kind))
                    + " name=" + (r == null ? "-" : String.valueOf(r.name)) + ")");
            }
            Param p = new Param(le.register, le.scalar, le.slot, desc);
            Param existing = union.get(p.key());
            if (existing == null) { union.put(p.key(), p); existing = p; }
            e.params.add(existing);
        }
        return true;
    }

    /**
     * Backward liveness over (register, scalar) pairs, one BitSet per
     * instruction giving what is live BEFORE it. Null when it did not converge.
     *
     * live_in(n) = use(n) union (live_out(n) minus def(n)), and live_out(n) is
     * the union of live_in over the NORMAL successors plus every handler that
     * covers n. The uses and defs come from the same InsnTypes the emitter reads
     * to decide which local slot to touch, so the set of registers this walks is
     * exactly the set the emitted bytecode can read.
     */
    private static java.util.BitSet[] computeLiveness(List<TypeInference.Insn> norm,
                                                      TypeInference.Result types,
                                                      List<TypeInference.TryBlock> tries,
                                                      Resolver resolve,
                                                      Map<Long, Integer> bitOf, int bits) {
        int n = norm.size();
        java.util.BitSet[] live = new java.util.BitSet[n];
        for (int i = 0; i < n; i++) live[i] = new java.util.BitSet(bits);

        // Handler edges, precomputed per instruction index: which handler
        // instruction indices this one can reach by throwing.
        List<int[]> handlersFor = new ArrayList<>(n);
        for (int i = 0; i < n; i++) handlersFor.add(null);
        for (TypeInference.TryBlock t : tries) {
            List<Integer> hs = new ArrayList<>();
            for (TypeInference.Catch c : t.catches) {
                int hi = resolve.indexAtOrAfter(c.handlerOffset);
                if (hi >= 0) hs.add(hi);
            }
            if (hs.isEmpty()) continue;
            int[] arr = new int[hs.size()];
            for (int k = 0; k < arr.length; k++) arr[k] = hs.get(k);
            for (int i = 0; i < n; i++) {
                int o = norm.get(i).offset;
                if (o < t.startOffset || o >= t.endOffset) continue;
                int[] had = handlersFor.get(i);
                if (had == null) { handlersFor.set(i, arr); continue; }
                int[] both = new int[had.length + arr.length];
                System.arraycopy(had, 0, both, 0, had.length);
                System.arraycopy(arr, 0, both, had.length, arr.length);
                handlersFor.set(i, both);
            }
        }

        java.util.BitSet scratch = new java.util.BitSet(bits);
        for (int pass = 0; pass < MAX_LIVENESS_PASSES; pass++) {
            boolean changed = false;
            for (int i = n - 1; i >= 0; i--) {
                TypeInference.Insn in = norm.get(i);
                scratch.clear();
                for (int s : successors(in)) {
                    int si = resolve.indexAtOrAfter(s);
                    if (si >= 0) scratch.or(live[si]);
                }
                TypeInference.InsnTypes it = types.typesFor(in.offset);
                if (it != null) {
                    for (TypeInference.Def d : it.defs) {
                        for (int scalar : DexType.scalarBits(d.scalars)) {
                            Integer b = bitOf.get(slotKey(d.register, scalar));
                            if (b != null) scratch.clear(b);
                        }
                    }
                    for (TypeInference.Use u : it.uses) {
                        Integer b = bitOf.get(slotKey(u.register, u.scalar));
                        if (b != null) scratch.set(b);
                    }
                }
                // AFTER the kill, because a handler is entered from BEFORE this
                // instruction completes: a register this instruction defines is
                // not yet defined on the exception edge, so what the handler
                // reads has to come from further back.
                int[] hs = handlersFor.get(i);
                if (hs != null) for (int hi : hs) scratch.or(live[hi]);
                if (!scratch.equals(live[i])) {
                    live[i].clear();
                    live[i].or(scratch);
                    changed = true;
                }
            }
            if (!changed) return live;
        }
        return null;
    }

    /** Same packing Translator.Locals uses, so the two agree on identity. */
    private static long slotKey(int register, int scalar) {
        return ((long) register << 8) | (scalar & 0xff);
    }

    private static boolean isNullType(DexType t) {
        DexType.Ref r = t.ref();
        return r == null || r.kind == DexType.Ref.KIND_NULL;
    }

    /**
     * Field descriptor for one register slot, or null when there is none.
     *
     * Mirrors MethodOutliner.descriptorFor: the reference case reads the same
     * class name Translator.vtypeFor hands to a StackMapTable
     * Object_variable_info, so a restored value has exactly the type the frame
     * at that program point declares and needs no cast.
     */
    private static String descriptorFor(int scalar, DexType t) {
        switch (scalar) {
            case DexType.INT:    return "I";
            case DexType.FLOAT:  return "F";
            case DexType.LONG:   return "J";
            case DexType.DOUBLE: return "D";
            case DexType.OBJ: {
                DexType.Ref r = t.ref();
                if (r == null || r.kind != DexType.Ref.KIND_CLASS) return null;
                String name = r.name;
                if (name == null || name.isEmpty()) return null;
                return name.charAt(0) == '[' ? name : "L" + name + ";";
            }
            default: return null;
        }
    }

    private static boolean insideDeferredNew(int offset, Map<Integer, Integer> deferredNew) {
        if (deferredNew == null || deferredNew.isEmpty()) return false;
        for (Map.Entry<Integer, Integer> e : deferredNew.entrySet()) {
            if (offset > e.getKey() && offset <= e.getValue()) return true;
        }
        return false;
    }

    private static String uniqueName(Set<String> used, String tag, int index) {
        String base = Options.syntheticPrefix + "$part$" + tag + "$" + index;
        String name = base;
        for (int k = 1; used.contains(name); k++) name = base + "_" + k;
        return name;
    }

    /**
     * Normal (non-exception) successors of one instruction.
     *
     * Mirrors Translator.MethodTranslator.normalSuccessors and
     * MethodOutliner.successors; all three are pure functions of the
     * instruction, and this class runs before a second MethodTranslator exists.
     */
    private static int[] successors(TypeInference.Insn in) {
        switch (TypeInference.familyOf(in.opcode)) {
            case TypeInference.F_RETURN:
            case TypeInference.F_THROW:
                return new int[0];
            case TypeInference.F_GOTO:
                return new int[]{ (int) in.args[0] };
            case TypeInference.F_IF:
                return new int[]{ (int) in.args[2], in.nextOffset };
            case TypeInference.F_IFZ:
                return new int[]{ (int) in.args[1], in.nextOffset };
            case TypeInference.F_SWITCH: {
                int[] out = new int[in.switchTargets.length + 1];
                System.arraycopy(in.switchTargets, 0, out, 0, in.switchTargets.length);
                out[out.length - 1] = in.nextOffset;
                return out;
            }
            default:
                return new int[]{ in.nextOffset };
        }
    }
}
