// MethodOutliner -- rescue a method whose translation exceeds the JVM's
// 65535-byte code_length cap by hoisting straight-line regions of it into
// synthetic private static methods.
//
// THE PROBLEM
// JVMS SE21 4.7.3 declares the Code attribute's length field as
//
//     u4 code_length;
//     u1 code[code_length];
//
// and 4.9.1 ("Static Constraints") caps it:
//
//     "The code array must not be empty, so the code_length item cannot have
//      the value 0."
//     "The value of the code_length item must be less than 65536."
//
// Dalvik has NO equivalent limit: dex-format's code_item declares
// `uint insns_size` and the spec's only ceiling is the 32-bit field itself. So
// a DEX method can be arbitrarily larger than any class file may express, and
// three real methods in the test corpus are (measured 2026-07-30):
//
//     org/telegram/messenger/EmojiData.<clinit>                   123,496 bytes
//     org/telegram/ui/Cells/ChatMessageCell.setMessageContent      101,260 bytes
//     org/bouncycastle/.../falcon/FPREngine.<clinit>                72,445 bytes
//
// Before this class, CodeWriter.assemble threw LimitExceededException and
// DexConverter's per-class catch turned that into "the whole CLASS failed to
// translate" -- so Telegram lost ChatMessageCell, the View that draws every
// message in a chat, over one oversized method out of 613.
//
// WHY OUTLINING RATHER THAN DROPPING THE METHOD
// Dropping just the method would keep the other 612 loadable, but it converts a
// visible translation error into an invisible one: the class links, and the
// first call reaches an AbstractMethodError (or, worse, a method that silently
// is not there). Outlining is what d8/R8 do when their own output overflows a
// Dalvik limit, and it is the only approach that preserves behaviour, so it is
// what this does where it can and it reports honestly where it cannot.
//
// WHAT THIS COVERS, AND WHAT IT DOES NOT
// The transform hoists a CONTIGUOUS, STRAIGHT-LINE region out of the method:
//
//     void big() {              void big() {
//       ...prologue...            ...prologue...
//       <10k instructions>        dex2jvm$outline$big$0(v4);   // <- synthetic
//       ...branchy tail...        ...branchy tail...
//     }                         }
//                               private static synthetic
//                               void dex2jvm$outline$big$0(String[] p0) {
//                                 astore <slot of v4>; <10k instructions>; return;
//                               }
//
// A region qualifies only when every one of these holds, and they are checked,
// not assumed:
//
//   1. The method has NO try/catch blocks. An outlined region that threw would
//      unwind past a handler that used to cover it, which is a behaviour change
//      rather than a layout change. (Without any handler, a throw propagates out
//      of the synthetic method and then out of the original, which is exactly
//      what it did before.)
//   2. The region contains no branch (if / goto / switch), no `return`, no
//      monitor-enter/exit, and no fill-array-data, and nothing outside it
//      branches into it. Branch-free is what makes the synthetic method need no
//      StackMapTable at all: JVMS 4.10.1 only demands a frame at a branch
//      target or a handler, and this has neither.
//   3. The operand stack is empty at both ends. Every translated Dalvik
//      instruction is stack-neutral, so this holds at any instruction boundary
//      EXCEPT inside a deferred `new` (whose uninitialized reference sits on the
//      stack until its <init>) and between an invoke and its move-result. Both
//      are excluded explicitly.
//   4. Every value the region reads on entry is a live-in PARAMETER, typed from
//      the inference's own register state, and every value the region WRITES is
//      dead at the exit. That second half is what makes a void, no-writeback
//      call sound, and it is decided by a real backward liveness fixpoint
//      (see computeLiveness), not by a heuristic.
//
// NOT COVERED HERE: a method whose oversized body is dense control flow.
// ChatMessageCell.setMessageContent is 17,486 instructions with 4,039 branches
// and 41 try blocks, and no region of it satisfies (1) or (2).
//
// That shape is MethodSplitter's, added later: it cuts the method into a
// forward-only chain of parts entered through a dispatch switch, with real
// StackMapTables and per-part exception tables. Translator.translate tries THIS
// class first, because where a straight-line region exists hoisting it is
// cheaper and leaves the method's own control flow untouched, and falls through
// to MethodSplitter when compute() below returns null. A method neither can
// take still fails exactly as it did before either existed -- the class is
// dropped -- so both can only improve the outcome, never worsen it.
//
// WHY THE BLAST RADIUS IS ZERO FOR EVERY OTHER METHOD
// Translator.translate measures the normal translation first and only builds a
// plan when it already exceeds 65535. A method that fits is never re-translated
// and never sees this code, so its bytes are unchanged by construction.

package io.github.kksimp.dex2jvm;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MethodOutliner {

    private MethodOutliner() {}

    /** JVMS 4.9.1: "The value of the code_length item must be less than 65536." */
    static final int CODE_LIMIT = 65535;

    /**
     * Bytes of ORIGINAL bytecode to put in one synthetic method.
     *
     * Well under the limit on purpose: the synthetic body also carries the
     * parameter-unpacking prologue, and the branch-widening fixpoint in
     * CodeWriter.assemble can only make a body longer than the measuring pass
     * saw. Overshooting costs one more synthetic method; undershooting costs the
     * whole class.
     */
    private static final int CHUNK_TARGET = 40000;

    /** A region smaller than this is not worth a call. */
    private static final int MIN_CHUNK = 2048;

    /**
     * Test knob: pretend the limit is this many bytes, so ordinary methods get
     * outlined and the transform can be exercised by a gate.
     *
     * It exists because the semantic gate's oracle is javac, and javac REFUSES
     * to compile a method over 65535 bytes ("code too large") -- so there is no
     * way to build a reference run for a method big enough to trigger the real
     * threshold. Lowering the threshold instead lets
     * `DEX2JVM_OUTLINE_FORCE=400 tests/semantic/run.sh` push every case in
     * the suite through the outliner and diff the result against javac, which is
     * the only evidence available that a hoisted region still computes the same
     * thing. Same spirit as DEX2JVM_THREADS, which exists so the parallel and
     * sequential paths can be A/B'd for byte identity.
     *
     * Unset normally, when the only trigger is a real overflow.
     */
    private static final int FORCE = forceLimit();

    private static int forceLimit() {
        String v = System.getenv("DEX2JVM_OUTLINE_FORCE");
        if (v == null || v.isEmpty()) return -1;
        try {
            int n = Integer.parseInt(v.trim());
            return n > 0 ? n : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** True when the test knob is on, which makes DexConverter arm outlining for
     *  every class rather than only for one that already overflowed. */
    static boolean forced() { return FORCE > 0; }

    /** The code_length a method must exceed before it is worth splitting. */
    static int codeLimit() { return FORCE > 0 ? FORCE : CODE_LIMIT; }

    private static int chunkTarget() { return FORCE > 0 ? Math.max(24, FORCE * 2 / 3) : CHUNK_TARGET; }

    private static int minChunk() { return FORCE > 0 ? Math.max(8, FORCE / 8) : MIN_CHUNK; }

    /**
     * Argument-slot ceiling for a synthetic method.
     *
     * JVMS 4.3.3: "The number of method parameters is limited to 255 by the
     * definition of a method descriptor, where the limit includes one unit for
     * `this` in the case of instance ... method invocations." Staying well under
     * it leaves room for the descriptor to be built without a second check.
     */
    private static final int MAX_PARAM_SLOTS = 200;

    /** Bound on the liveness fixpoint, so a pathological CFG cannot spin. */
    private static final int MAX_LIVENESS_PASSES = 200;

    /**
     * Names the reason a plan was refused.
     *
     * A refusal is INVISIBLE in the output -- the class simply fails to
     * translate, exactly as it did before this class existed -- so without this
     * the only way to tell "no region qualified" from "the region was rejected
     * for one specific reason" is a debugger. Off by default; costs nothing.
     */
    private static final boolean DEBUG = System.getenv("DEX2JVM_OUTLINE_DEBUG") != null;

    private static Plan refuse(String why, Object... args) {
        if (DEBUG) System.err.println("[dex-outline] refused: " + String.format(why, args));
        return null;
    }

    // ==================================================================
    // The plan
    // ==================================================================

    /** One value the synthetic method receives, and the slot it must land in. */
    static final class Param {
        /** Dalvik register this stands for. */
        final int register;
        /** DexType scalar, which is what makes (register, scalar) a JVM slot. */
        final int scalar;
        /** The JVM local slot the ORIGINAL body reads it from. */
        final int slot;
        /** Field descriptor, e.g. "I" or "[Ljava/lang/String;". */
        final String descriptor;

        Param(int register, int scalar, int slot, String descriptor) {
            this.register = register;
            this.scalar = scalar;
            this.slot = slot;
            this.descriptor = descriptor;
        }
    }

    /** One region to hoist: [startOffset, endOffset) in DEX code units. */
    static final class Chunk {
        final int startOffset;
        final int endOffset;
        final String name;
        final String descriptor;
        final List<Param> params;

        Chunk(int startOffset, int endOffset, String name, String descriptor,
              List<Param> params) {
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.name = name;
            this.descriptor = descriptor;
            this.params = params;
        }
    }

    /** The regions to hoist out of one method, in ascending offset order. */
    static final class Plan {
        private final Map<Integer, Chunk> byStart = new HashMap<>();
        private final List<Chunk> chunks;

        Plan(List<Chunk> chunks) {
            this.chunks = chunks;
            for (Chunk c : chunks) byStart.put(c.startOffset, c);
        }

        List<Chunk> chunks() { return chunks; }

        /** The chunk that begins at this DEX offset, or null. */
        Chunk startingAt(int dexOffset) { return byStart.get(dexOffset); }
    }

    // ==================================================================
    // Planning
    // ==================================================================

    /**
     * Decide which regions of one oversized method to hoist, or return null when
     * none qualifies.
     *
     * @param norm       the normalized instruction stream, in offset order
     * @param types      the inference result the same stream was emitted from
     * @param locals     the (register, scalar) -> JVM slot assignment in force
     * @param hasTries   whether the method has any try/catch block
     * @param deferredNew  new-instance DEX offset -> the &lt;init&gt; that consumes
     *                     it, for the sites whose allocation stays on the stack
     * @param bytecodeOf DEX offset -> bytecode offset, measured from the
     *                   translation that overflowed; -1 for an offset that
     *                   emitted nothing
     * @param totalBytes the measured code_length that overflowed
     * @param taken      method names already declared on the class, so a
     *                   synthetic name cannot collide
     * @param methodTag  a name fragment identifying the method being split
     */
    static Plan compute(List<TypeInference.Insn> norm,
                        TypeInference.Result types,
                        Translator.Locals locals,
                        boolean hasTries,
                        Map<Integer, Integer> deferredNew,
                        java.util.function.IntUnaryOperator bytecodeOf,
                        int totalBytes,
                        Set<String> taken,
                        String methodTag) {
        // (1) A handler that used to cover the region would no longer cover it.
        if (hasTries) return refuse("method has try blocks");
        if (norm.size() < 4) return refuse("only %d instructions", norm.size());

        final int n = norm.size();
        final int[] offsets = new int[n];
        for (int i = 0; i < n; i++) offsets[i] = norm.get(i).offset;

        // Slot index space for liveness: one bit per (register, scalar) pair
        // that actually got a JVM local.
        final Map<Long, Integer> bitOf = new HashMap<>();
        final List<Translator.Locals.Entry> entries = locals.entries();
        for (int i = 0; i < entries.size(); i++) {
            Translator.Locals.Entry e = entries.get(i);
            bitOf.put(key(e.register, e.scalar), i);
        }
        if (entries.isEmpty()) return refuse("no local slots allocated");

        BitSet[] liveIn = computeLiveness(norm, types, bitOf, entries.size());
        if (liveIn == null) return refuse("liveness did not converge");

        Set<Integer> branchTargets = branchTargets(norm);
        // Two DIFFERENT questions, and conflating them is why a first attempt
        // found nothing to hoist in FPREngine: `mayContain` asks whether an
        // instruction may sit INSIDE a region, `mayBound` whether a region may
        // START or END in front of it. A deferred `new` keeps an uninitialized
        // reference on the operand stack for a few instructions, which rules out
        // a boundary there but is perfectly fine to hoist WHOLE -- and FPREngine
        // does one every five instructions, so treating it as un-hoistable
        // capped every region at four instructions.
        boolean[] mayContain = new boolean[n];
        boolean[] mayBound = new boolean[n];
        for (int i = 0; i < n; i++) {
            mayContain[i] = mayContain(norm.get(i), types, offsets[i], branchTargets);
            mayBound[i] = mayContain[i]
                    && TypeInference.familyOf(norm.get(i).opcode) != TypeInference.F_MOVE_RESULT
                    && !insideDeferredNew(offsets[i], deferredNew);
        }

        int statContain = 0, statStartTried = 0, statExitOk = 0, statParamsOk = 0;
        for (boolean b : mayContain) if (b) statContain++;
        List<Chunk> chunks = new ArrayList<>();
        Set<String> used = new HashSet<>(taken);
        long savedBytes = 0;
        int i = 0;
        while (i < n) {
            if (!mayBound[i] || bytecodeOf.applyAsInt(offsets[i]) < 0) { i++; continue; }
            int start = bytecodeOf.applyAsInt(offsets[i]);
            // Grow the region while it stays straight-line, remembering the last
            // position that is a LEGAL exit. The two are different questions: an
            // instruction can be safe to include and still be an illegal place
            // to stop, because a register the region wrote is live past it.
            BitSet writes = new BitSet(entries.size());
            int best = -1;
            int j = i;
            final int chunkTarget = chunkTarget(), minChunk = minChunk();
            while (j + 1 < n && mayContain[j]) {
                addDefs(writes, types, offsets[j], bitOf);
                j++;
                int here = bytecodeOf.applyAsInt(offsets[j]);
                if (here < 0) break;
                if (here - start > chunkTarget) break;
                if (here - start < minChunk) continue;
                if (!mayBound[j]) continue;
                // (4) Nothing the region wrote may still be needed afterwards:
                // a void call cannot hand it back.
                if (writes.intersects(liveIn[j])) continue;
                best = j;
            }
            statStartTried++;
            if (best < 0) { i++; continue; }
            statExitOk++;

            List<Param> params = parametersAt(offsets[i], types, entries, liveIn[i]);
            if (params == null) { i++; continue; }
            statParamsOk++;

            String name = uniqueName(used, methodTag, chunks.size());
            chunks.add(new Chunk(offsets[i], offsets[best], name,
                                 descriptorOf(params), params));
            used.add(name);
            savedBytes += bytecodeOf.applyAsInt(offsets[best]) - start;
            savedBytes -= callCost(params);
            i = best;
        }

        if (chunks.isEmpty()) {
            return refuse("no region qualified (contain=%d/%d, starts=%d, exits=%d, params=%d)",
                          statContain, n, statStartTried, statExitOk, statParamsOk);
        }
        // Only claim the rescue if what is left actually fits. Reporting the
        // original failure is better than emitting a method that still overflows
        // plus a pile of synthetic ones nothing calls.
        if (totalBytes - savedBytes > codeLimit()) {
            return refuse("%d chunks saved only %d of %d bytes", chunks.size(), savedBytes, totalBytes);
        }
        if (DEBUG) {
            System.err.println("[dex-outline] " + chunks.size() + " chunk(s), "
                + savedBytes + " of " + totalBytes + " bytes hoisted");
        }
        return new Plan(chunks);
    }

    /** Bytes the call site costs the ORIGINAL method: one load per parameter
     *  (worst case 4 bytes with a wide index) plus a 3-byte invokestatic. */
    private static int callCost(List<Param> params) {
        return params.size() * 4 + 3;
    }

    private static String uniqueName(Set<String> used, String tag, int index) {
        String base = Options.syntheticPrefix + "$outline$" + tag + "$" + index;
        String name = base;
        for (int k = 1; used.contains(name); k++) name = base + "_" + k;
        return name;
    }

    private static String descriptorOf(List<Param> params) {
        StringBuilder sb = new StringBuilder("(");
        for (Param p : params) sb.append(p.descriptor);
        return sb.append(")V").toString();
    }

    // ==================================================================
    // What may sit inside a region
    // ==================================================================

    /**
     * Whether the instruction at {@code offset} may be part of a hoisted region.
     *
     * Excluded, and why:
     *   branch / switch  -- would need a StackMapTable in the synthetic method,
     *                       and its target may be outside the region entirely
     *   return           -- would return from the SYNTHETIC method, after which
     *                       the original would carry on rather than return
     *   monitor-enter/exit -- JVMS 2.11.10 structured locking: the matching pair
     *                       must stay in one method
     *   fill-array-data  -- references a payload by code offset
     *   unreachable      -- emitBody emits nothing for it, so hoisting it would
     *                       move a label rather than any code
     *   inside a deferred new -- an uninitialized reference is live ON THE STACK
     *                       across the site, so the stack is not empty here
     *   a branch target  -- something outside would branch into the region
     *
     * `throw` is deliberately allowed: with no try blocks in the method (checked
     * by compute), an exception raised inside the synthetic method propagates
     * out of it and then out of the original, which is what it did before.
     */
    private static boolean mayContain(TypeInference.Insn in, TypeInference.Result types,
                                      int offset, Set<Integer> branchTargets) {
        if (!types.isReachable(offset)) return false;
        if (branchTargets.contains(offset)) return false;
        switch (TypeInference.familyOf(in.opcode)) {
            case TypeInference.F_IF:
            case TypeInference.F_IFZ:
            case TypeInference.F_GOTO:
            case TypeInference.F_SWITCH:
            case TypeInference.F_RETURN:
                return false;
            default:
                break;
        }
        int op = in.opcode & 0xFF;
        if (op == 0x26) return false;                 // fill-array-data
        return op != 0x1d && op != 0x1e;              // monitor-enter / monitor-exit
    }

    /** True when an uninitialized reference is live on the operand stack across
     *  this offset, which makes the stack non-empty here. */
    private static boolean insideDeferredNew(int offset, Map<Integer, Integer> deferredNew) {
        if (deferredNew == null || deferredNew.isEmpty()) return false;
        for (Map.Entry<Integer, Integer> e : deferredNew.entrySet()) {
            if (offset > e.getKey() && offset <= e.getValue()) return true;
        }
        return false;
    }

    /** Every offset reached by an edge that is not simple fall-through. */
    private static Set<Integer> branchTargets(List<TypeInference.Insn> norm) {
        Set<Integer> out = new HashSet<>();
        for (TypeInference.Insn in : norm) {
            for (int s : successors(in)) {
                if (s != in.nextOffset) out.add(s);
            }
        }
        return out;
    }

    /**
     * Normal (non-exception) successors of one instruction.
     *
     * Mirrors Translator.MethodTranslator.normalSuccessors; both are pure
     * functions of the instruction, and this class must not depend on a
     * MethodTranslator instance because it runs before the second one is built.
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

    // ==================================================================
    // Liveness
    // ==================================================================

    /**
     * Backward liveness over (register, scalar) pairs, one BitSet per
     * instruction giving what is live BEFORE it.
     *
     * This is the classic fixpoint, live_in(n) = use(n) union (live_out(n) minus
     * def(n)), live_out(n) = union of live_in over successors. It has to be a
     * real fixpoint rather than a linear backward scan because a loop's back
     * edge makes a value live before its own definition, and getting that wrong
     * in the OPTIMISTIC direction would drop a parameter the region needs.
     *
     * Two deliberate over-approximations, both safe (they can only add a
     * parameter that turns out to be unnecessary, never omit one that is
     * needed):
     *   - a def of register r with scalar mask S kills only the (r, s) pairs
     *     for s in S, leaving any differently-typed slot for the same register
     *     alive;
     *   - exception edges are not modelled, which is sound only because
     *     compute() refuses any method that has try blocks at all.
     */
    private static BitSet[] computeLiveness(List<TypeInference.Insn> norm,
                                            TypeInference.Result types,
                                            Map<Long, Integer> bitOf, int bits) {
        int n = norm.size();
        Map<Integer, Integer> indexOf = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) indexOf.put(norm.get(i).offset, i);

        BitSet[] live = new BitSet[n];
        for (int i = 0; i < n; i++) live[i] = new BitSet(bits);

        BitSet scratch = new BitSet(bits);
        for (int pass = 0; pass < MAX_LIVENESS_PASSES; pass++) {
            boolean changed = false;
            for (int i = n - 1; i >= 0; i--) {
                TypeInference.Insn in = norm.get(i);
                scratch.clear();
                for (int s : successors(in)) {
                    Integer si = indexOf.get(s);
                    if (si != null) scratch.or(live[si]);
                }
                TypeInference.InsnTypes it = types.typesFor(in.offset);
                if (it != null) {
                    for (TypeInference.Def d : it.defs) {
                        for (int scalar : DexType.scalarBits(d.scalars)) {
                            Integer b = bitOf.get(key(d.register, scalar));
                            if (b != null) scratch.clear(b);
                        }
                    }
                    for (TypeInference.Use u : it.uses) {
                        Integer b = bitOf.get(key(u.register, u.scalar));
                        if (b != null) scratch.set(b);
                    }
                }
                if (!scratch.equals(live[i])) {
                    live[i].clear();
                    live[i].or(scratch);
                    changed = true;
                }
            }
            if (!changed) return live;
        }
        // Did not converge: refusing is the honest answer, because a truncated
        // liveness result is exactly the kind that omits a needed parameter.
        return null;
    }

    /** Add every (register, scalar) the instruction at {@code offset} defines. */
    private static void addDefs(BitSet writes, TypeInference.Result types, int offset,
                                Map<Long, Integer> bitOf) {
        TypeInference.InsnTypes it = types.typesFor(offset);
        if (it == null) return;
        for (TypeInference.Def d : it.defs) {
            for (int scalar : DexType.scalarBits(d.scalars)) {
                Integer b = bitOf.get(key(d.register, scalar));
                if (b != null) writes.set(b);
            }
        }
    }

    // ==================================================================
    // Parameters
    // ==================================================================

    /**
     * The live-in values at {@code offset}, as a parameter list, or null when the
     * region cannot start here.
     *
     * Refused when a live value has no expressible parameter type. That is the
     * uninitialized cases above all: JVMS 4.9.2 forbids passing an uninitialized
     * class instance as an argument, so a region whose entry state holds one
     * simply cannot be hoisted. `null`-typed and top-typed registers are refused
     * for the same practical reason -- there is no descriptor that keeps the
     * body verifiable.
     */
    private static List<Param> parametersAt(int offset, TypeInference.Result types,
                                            List<Translator.Locals.Entry> entries,
                                            BitSet live) {
        RegisterState st = types.entryState(offset);
        if (st == null) return null;
        List<Param> params = new ArrayList<>();
        int slots = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (!live.get(i)) continue;
            Translator.Locals.Entry e = entries.get(i);
            DexType t = st.get(e.register);
            if (t == null || t.isDead()) continue;      // not defined here, so not live-in
            if ((t.scalar() & e.scalar) == 0) continue; // a differently typed slot
            String desc = descriptorFor(e.scalar, t);
            if (desc == null) return null;
            params.add(new Param(e.register, e.scalar, e.slot, desc));
            slots += (e.scalar == DexType.LONG || e.scalar == DexType.DOUBLE) ? 2 : 1;
            if (slots > MAX_PARAM_SLOTS) return null;
        }
        return params;
    }

    /**
     * Field descriptor for one register slot, or null when there is none.
     *
     * The reference case reads the inference's own class name, which is the same
     * string Translator.vtypeFor hands to a StackMapTable Object_variable_info,
     * so a hoisted body sees exactly the type it would have seen in place. An
     * array type is already in descriptor form ("[Ljava/lang/String;"); a plain
     * class is in internal form and needs the L...; wrapper (JVMS 4.3.2).
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

    /** Same packing Translator.Locals uses, so the two agree on identity. */
    private static long key(int register, int scalar) {
        return ((long) register << 8) | (scalar & 0xff);
    }
}
