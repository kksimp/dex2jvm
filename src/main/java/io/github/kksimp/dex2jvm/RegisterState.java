package io.github.kksimp.dex2jvm;

import java.util.Arrays;

/**
 * An immutable snapshot of the inferred type of every Dalvik register at one program point.
 *
 * <p>This is the value that flows through the {@link TypeInference} fixpoint: one instance per
 * reachable instruction offset, holding the ENTRY state of that instruction. Instances are
 * immutable and every mutator returns a new instance, because the dataflow algorithm needs to
 * hold on to the old state of a block while computing a candidate new one and compare them.
 *
 * <h2>Why immutable, and why the identity check matters</h2>
 *
 * The fixpoint loop terminates when no state changes. Detecting "no change" by deep comparison
 * would dominate the runtime on a large APK (hundreds of thousands of methods, each iterated
 * several times). Instead {@link DexType} instances are interned, so a component-wise identity
 * scan is exact, and every mutator here returns {@code this} unchanged when the write is a no-op.
 * That makes {@link #merge} return the receiver by identity whenever the merge added nothing,
 * which is the signal the worklist uses to avoid re-queueing a block. This mirrors the reference
 * implementation's {@code isSame} check (enjarify typeinference.py), which relies on the same
 * trick via persistent tree sharing.
 *
 * <h2>Wide (64-bit) register pairs</h2>
 *
 * Dalvik stores a long or double in a register PAIR: {@code const-wide v3} occupies v3 and v4.
 * Only v3 carries the value; v4 is the high half and must never be read independently. That is
 * represented by putting the value in v3 and {@link DexType#WIDE_HIGH} in v4.
 *
 * <p>{@link DexType#WIDE_HIGH} is distinct from {@link DexType#DEAD} even though neither has a
 * legal reading, because a StackMapTable must emit an explicit {@code top} entry for the second
 * slot of every long/double local (JVMS 4.7.4 gives no two-slot local a single tag; the pair is
 * encoded as the value type followed by {@code Top_variable_info}). "Second half of a wide" and
 * "never written" are different things to a verifier, so they are different things here.
 *
 * <p>The subtle part is OVERWRITE. Writing a 32-bit value to v4 above leaves v3 holding a long
 * whose high half is gone, which means v3 is no longer readable as a long. Real Dalvik verifiers
 * invalidate the low half in that case, and so does {@link #set}: writing any register also kills
 * a wide value whose high half that register was. Forgetting this is a classic converter bug,
 * because the resulting bytecode reads a corrupt long only on the path where the overwrite
 * happened.
 *
 * <h2>THE VALUE VIEW AND THE SLOT VIEW ARE DIFFERENT THINGS</h2>
 *
 * This class tracks TWO things per register, and conflating them is what produced 64 percent of
 * the remaining split-verifier failures across the test corpus (841 of 1,307 as of 2026-07-25):
 *
 * <ul>
 *   <li>{@link #live} - the value the Dalvik register holds. Its scalar component is the set of
 *       readings that are legal HERE, so it merges by INTERSECTION and can become empty. This is
 *       what drives opcode selection, and it must keep exactly that meaning.</li>
 *   <li>{@link #get} - what the JVM LOCAL SLOTS the register owns currently hold. One Dalvik
 *       register owns up to five slots, one per scalar (see {@link DexType}), and they are
 *       INDEPENDENT locals: {@code add-int v0, v1, v2} emits {@code istore} into the (v0, INT)
 *       slot and does not touch the (v0, OBJ) slot at all. This is what a StackMapTable frame
 *       describes.</li>
 * </ul>
 *
 * <p>The failure that forced the split. {@code AppCompatDelegate.syncRequestedAndStoredLocales}
 * uses v0 as an int early and as a {@code synchronized} monitor later. At the {@code return-void}
 * where the early-exit branch rejoins the monitor path, {@code mergeScalar(INT, OBJ)} is 0, so the
 * whole register went DEAD and {@code Translator.frameAt} published {@code top} for BOTH the
 * (v0, INT) slot and the (v0, OBJ) slot. But the (v0, OBJ) slot genuinely holds the monitor on one
 * path and the prologue's {@code aconst_null} on the other, so its verification type is
 * {@code java/lang/Object}, not {@code top}. The enclosing try range covers that instruction, and
 * JVMS 4.10.1.6 checks the frame of EVERY instruction in a protected range against the handler's
 * frame ({@code instructionSatisfiesHandlers} applies {@code isApplicableHandler}, which is a pure
 * {@code Offset >= Start, Offset < End} test with no throwing filter), so:
 *
 * <pre>
 *   Stack map does not match the one at exception handler 128
 *   Reason: Type top (current frame, locals[5]) is not assignable to
 *           'java/lang/Object' (stack map, locals[5])
 * </pre>
 *
 * <p>Nothing is assignable to a class except a reference (JVMS 4.10.1.2 gives {@code top} no
 * outgoing rule but reflexivity, and HotSpot's {@code VerificationType::is_assignable_from} returns
 * false once the target is a reference and the source is Bogus), so a {@code top} in the current
 * frame where the handler names a type is always fatal.
 *
 * <p><b>Why the slot view needs no per-scalar array.</b> {@code Translator.zeroInitRegisterSlots}
 * stores a definite zero or null into EVERY allocated slot before the body runs, and the code
 * writer only ever stores into slot (v, s) with the store opcode for s. So the (v, INT) slot always
 * holds an int, the (v, LONG) slot always holds a long, and so on: their verification types are
 * constants and need no tracking. The single component that varies is the reference in the
 * (v, OBJ) slot, which is what {@link #objSlot} holds.
 *
 * <p><b>Why {@link #get} is the slot view and not the value view.</b> The two consumers outside
 * this package's analysis - {@code Translator.frameAt} and {@code Translator.thisStillUninitialized}
 * - are both asking a FRAME question, and the slot view is the correct answer to both. Everything
 * inside {@link TypeInference} that is doing opcode selection asks {@link #live} instead.
 */
public final class RegisterState {

    private final DexType[] regs;
    /**
     * What each register's (register, OBJ) JVM slot holds, independent of whether the register is
     * currently READABLE as an object. Never null and never {@link DexType.Ref#NONE_REF}: a slot
     * that has not been stored since the prologue holds the prologue's {@code aconst_null}, which
     * is {@link DexType.Ref#NULL_REF}.
     */
    private final DexType.Ref[] objSlot;
    private final DexType.ClassHierarchy hierarchy;

    private RegisterState(DexType[] regs, DexType.Ref[] objSlot,
                          DexType.ClassHierarchy hierarchy) {
        this.regs = regs;
        this.objSlot = objSlot;
        this.hierarchy = hierarchy;
    }

    private static DexType.Ref[] nullSlots(int registerCount) {
        DexType.Ref[] s = new DexType.Ref[registerCount];
        Arrays.fill(s, DexType.Ref.NULL_REF);
        return s;
    }

    /** A state in which every register is dead. */
    public static RegisterState empty(int registerCount, DexType.ClassHierarchy hierarchy) {
        DexType[] r = new DexType[registerCount];
        Arrays.fill(r, DexType.DEAD);
        return new RegisterState(r, nullSlots(registerCount), hierarchy);
    }

    /**
     * The entry state of a method: parameters occupy the HIGHEST-numbered registers.
     *
     * <p>Dalvik places the incoming arguments in the last {@code n} registers of the frame, so a
     * method with 8 registers and 3 argument words has them in v5, v6, v7. That is why the offset
     * below is {@code registerCount - paramWords} rather than 0. Getting this backwards produces
     * a method whose parameters are all dead and whose locals are mysteriously pre-typed, which
     * then fails at the first use rather than at the point of the mistake.
     *
     * <h2>Why the non-parameter registers start at {@link DexType#ANY_TYPE}, not DEAD</h2>
     *
     * Because {@code Translator.zeroInitRegisterSlots} makes it TRUE. That prologue stores a
     * definite zero into every allocated slot before the body runs -- {@code iconst_0} for an int
     * slot, {@code fconst_0} for a float one, {@code lconst_0} / {@code dconst_0} for the wide
     * ones and {@code aconst_null} for an object one -- and a slot is per (register, scalar), so
     * only a store of THAT scalar can ever change it. No slot is ever {@code top} at runtime.
     * {@link DexType#ANY_TYPE} (every reading legal, provably null, array state NULL) is the exact
     * model of that, and it is the IDENTITY for merge, so it can never make a live path less
     * precise: {@code ANY & X == X}, {@code mergeArray(NULL, k) == k}, {@code mergeRef(null, X)
     * == X}.
     *
     * <p>Modelling it matters at exception handlers. Under the JVMS 4.10.1.6 rule the handler's
     * frame is the meet over EVERY instruction in the protected range, including the first, where
     * a register the body assigns later has not been written yet. Seeded DEAD, that meet is
     * {@code top}, the handler frame claims {@code top}, and either the verifier rejects a later
     * use or the handler body reads a register the analysis says does not exist (which is what
     * drove Translator.translate's ART-rule retry). Seeded ANY, the meet is
     * {@code merge(zero, String) == String} and both problems disappear.
     *
     * <p>HISTORY, so this is not flip-flopped again. This seeding was tried and REVERTED earlier
     * in the same work (measured at the time as WORSE: 870 -> 1,084 verify errors on the
     * reference apps). The reason was a single interaction, not the idea: {@code DexType.mergeRef} answered {@code uninitialized(N)} for
     * {@code merge(null, uninitialized(N))}, so every slot holding a {@code new} result inside a
     * try range published {@code uninitialized(N)} in the handler frame, and JVMS 4.10.1.2 has no
     * {@code isAssignable(null, uninitialized(_))} clause -- 368 new "Type null ... is not
     * assignable to uninitialized N" errors on the five reference apps. With that merge corrected
     * to CONFLICT (see DexType.mergeRef) the family is empty and the seeding measures as a WIN:
     * 126 -> 108 verify errors across HCR 1.43 / mindustry / F-Droid / GD Lite / Flappy, 0
     * translation errors, jars +0.4%. Do not revert one without the other.
     *
     * @param spacedParamDescriptors one entry per register word, with a NULL entry immediately
     *     after each long/double to account for its high half, and the receiver descriptor first
     *     for an instance method. This matches the DEX {@code ins_size} accounting exactly.
     * @param uninitializedThis true for a constructor, so the receiver starts as
     *     {@code uninitializedThis} per JVMS 4.10.1.6 ("in other &lt;init&gt; methods, the type of
     *     this is uninitializedThis") and cannot be returned from until it is initialized.
     */
    public static RegisterState forParameters(int registerCount,
                                              String[] spacedParamDescriptors,
                                              boolean uninitializedThis,
                                              boolean isStatic,
                                              DexType.ClassHierarchy hierarchy) {
        DexType[] r = new DexType[registerCount];
        Arrays.fill(r, DexType.ANY_TYPE);   // the prologue's zero-init; see the doc comment
        // Every OBJ slot starts holding the prologue's aconst_null. A parameter register's slot is
        // then overwritten by the parameter copy, but only for the parameter's OWN scalar: an int
        // parameter leaves the (v, OBJ) slot holding null, which is exactly what NULL_REF says.
        DexType.Ref[] slots = nullSlots(registerCount);
        int offset = registerCount - spacedParamDescriptors.length;
        if (offset < 0) {
            // Malformed input: more argument words than registers. Clamp rather than throw, so a
            // single bad method does not abort conversion of the whole APK.
            offset = 0;
        }
        for (int i = 0; i < spacedParamDescriptors.length && offset + i < registerCount; i++) {
            String desc = spacedParamDescriptors[i];
            if (desc == null) {
                // High half of the preceding wide parameter.
                r[offset + i] = DexType.WIDE_HIGH;
                continue;
            }
            DexType t = DexType.fromDescriptor(desc);
            if (i == 0 && !isStatic && uninitializedThis) {
                t = t.withRef(DexType.Ref.UNINIT_THIS);
            }
            r[offset + i] = t;
            if ((t.scalar() & DexType.OBJ) != 0) {
                slots[offset + i] = DexType.objSlotRefOf(t);
            }
        }
        return new RegisterState(r, slots, hierarchy);
    }

    public int size() {
        return regs.length;
    }

    /**
     * What the JVM local SLOTS this register owns currently hold. See the class comment.
     *
     * <p>The result always has every scalar bit set, so a caller iterating a (register, scalar) to
     * slot table gets a real verification type for each of its slots, and its reference component
     * is the (register, OBJ) slot's. This is the FRAME answer; {@link #live} is the value answer.
     */
    public DexType get(int reg) {
        if (reg < 0 || reg >= regs.length) {
            return DexType.DEAD;
        }
        return DexType.slotView(objSlot[reg]);
    }

    /** Alias for {@link #get} that names what it returns, for new call sites. */
    public DexType frameType(int reg) {
        return get(reg);
    }

    /** The reference held by this register's (register, OBJ) JVM slot. */
    public DexType.Ref objSlotRef(int reg) {
        if (reg < 0 || reg >= objSlot.length) {
            return DexType.Ref.NULL_REF;
        }
        return objSlot[reg];
    }

    /**
     * The VALUE the Dalvik register holds: the lattice element the fixpoint computes, whose scalar
     * component is the set of readings legal here. This is what opcode selection must consult.
     */
    public DexType live(int reg) {
        if (reg < 0 || reg >= regs.length) {
            return DexType.DEAD;
        }
        return regs[reg];
    }

    /** A defensive copy of the whole register file, for callers that want the raw array. */
    public DexType[] toArray() {
        return regs.clone();
    }

    /**
     * The same slot contents, with every VALUE reduced to what the slot alone proves.
     *
     * <p>Used for the exception edge out of an instruction that cannot throw. Such an instruction
     * can never transfer control to the handler, so it must not narrow what the handler body
     * believes its registers hold - but JVMS 4.10.1.6 still checks its frame against the handler's,
     * so its SLOT contents must join in.
     *
     * <p>Every component except the reference is {@link DexType#ANY_TYPE}'s, which is the identity
     * for the value merge ({@code ANY & X == X}, {@code mergeArray(NULL, k) == k}, taint false), so
     * the scalar precision the ART edge rule buys is kept intact. The REFERENCE is the slot's,
     * which WIDENS the value's - and that is the point. The frame at the handler declares the slot
     * reference, so a value view claiming something narrower would make the analysis emit code the
     * verifier rejects: an {@code aload} of a slot declared {@code java/lang/Object} pushes a
     * {@code java/lang/Object}, whatever the analysis knows about the value that reached it.
     * Measured on org/fdroid/download/Downloader before this widening was added:
     *
     * <pre>
     *   Instruction type does not match stack map
     *   Type 'java/lang/Object' (current frame, locals[24]) is not assignable to
     *   'org/fdroid/download/Downloader' (stack map, locals[24])
     * </pre>
     *
     * <p>With the widening, {@code liveRef == objSlotRef} at every handler entry where the value is
     * still readable as an object, so the two views cannot drift apart downstream either.
     */
    public RegisterState slotsOnly() {
        // Memoised because propagate() asks for it once per applicable handler and again on every
        // fixpoint pass over the same immutable state, and it allocates a whole register file.
        RegisterState c = slotsOnlyCache;
        if (c != null) {
            return c;
        }
        DexType[] r = new DexType[regs.length];
        for (int i = 0; i < r.length; i++) {
            r[i] = slotAsValue(objSlot[i]);
        }
        c = new RegisterState(r, objSlot, hierarchy);
        c.slotsOnlyCache = c;   // idempotent: the slots-only view of a slots-only view is itself
        slotsOnlyCache = c;
        return c;
    }

    /**
     * Memo only; it holds no state a caller can observe, so the class stays immutable in every
     * sense that matters. One analysis runs on one thread (DexConverter fans out per CLASS), and
     * every other field is final, so publication is safe even if that ever changes.
     */
    private RegisterState slotsOnlyCache;

    /**
     * This state with one register's OBJ slot taken from {@code other}.
     *
     * <p>Used to undo the destination half of a transfer function. HotSpot verifies exception
     * handlers against the PRE-state for the {@code *store}-into-local opcodes and against the
     * POST-state for everything else (verifier.cpp, both call sites of
     * {@code verify_exception_handler_targets}), and a Dalvik instruction's destination register is
     * written by exactly such a store. So the destination's new value is never the thing HotSpot
     * sees at this instruction, and joining it into the handler would widen the frame for nothing.
     */
    public RegisterState withSlotFrom(int reg, RegisterState other) {
        if (reg < 0 || reg >= objSlot.length || reg >= other.objSlot.length) {
            return this;
        }
        if (objSlot[reg].equals(other.objSlot[reg])) {
            return this;
        }
        DexType.Ref[] slots = objSlot.clone();
        slots[reg] = other.objSlot[reg];
        return new RegisterState(regs, slots, hierarchy);
    }

    /** True when the two states' JVM slots hold the same things. Cheap: usually the same array. */
    public boolean sameSlotsAs(RegisterState other) {
        if (this == other || objSlot == other.objSlot) return true;
        if (objSlot.length != other.objSlot.length) return false;
        for (int i = 0; i < objSlot.length; i++) {
            if (!objSlot[i].equals(other.objSlot[i])) return false;
        }
        return true;
    }

    private static DexType slotAsValue(DexType.Ref slot) {
        if (slot == null || slot.kind == DexType.Ref.KIND_NULL) {
            return DexType.ANY_TYPE;
        }
        DexType memo = slot.slotAsValue;
        if (memo != null) {
            return memo;
        }
        slot.slotAsValue = build(slot);
        return slot.slotAsValue;
    }

    private static DexType build(DexType.Ref slot) {
        // ARRAY_NULL, not ARRAY_UNKNOWN: the array component drives opcode selection and the cast
        // TARGET, and it has no separate representation in a frame (an array's verification type is
        // just its descriptor, which rides on the reference). Widening it here would turn a
        // `checkcast [I; iaload` that the verifier accepts into an `aaload` that it does not.
        return DexType.of(DexType.ANY, DexType.ARRAY_NULL, null, slot, false, false);
    }

    /**
     * Write a 32-bit value. Also invalidates a wide value in the preceding register if this write
     * clobbers its high half; see the class comment on OVERWRITE.
     */
    public RegisterState set(int reg, DexType type) {
        return set(reg, type, true);
    }

    /**
     * Write a value that the emitter does NOT store into a JVM local, leaving the register's OBJ
     * slot holding whatever it held.
     *
     * <p>The one caller is a {@code new-instance} at a
     * {@link TypeInference.MethodInput#deferredNewSites deferred} site: the emitter produces no
     * bytecode there at all and keeps the uninitialized reference on the operand stack until the
     * paired {@code <init>}. The VALUE view must still say {@code uninitialized(offset)} so the
     * substitution fires and a use-before-init is still rejected; the SLOT view must say nothing,
     * because no local holds it.
     */
    public RegisterState setKeepingSlot(int reg, DexType type) {
        return set(reg, type, false);
    }

    private RegisterState set(int reg, DexType type, boolean writeObjSlot) {
        if (reg < 0 || reg >= regs.length) {
            return this;
        }
        // Overwriting the LOW half of a wide orphans the marker in the high half, which must be
        // cleared. The invariant this maintains is "WIDE_HIGH at r means r-1 still holds a live
        // 64-bit value", and it is load bearing: without it, `mul-double v12` leaves
        // (v12=double, v13=top), a later `move v12, v23` makes v12 an int while v13 keeps its
        // now-meaningless top marker, and any code keying off that marker mis-handles the pair.
        boolean orphansHigh = reg + 1 < regs.length && DexType.isWide(regs[reg].scalar())
                && regs[reg + 1].isWideHigh() && !DexType.isWide(type.scalar());
        // A store only touches the slots for the scalars it actually writes. `add-int v0, ...`
        // emits one istore into the (v0, INT) slot and leaves the (v0, OBJ) slot holding whatever
        // it held, which is why the slot below is updated ONLY when the value is readable as an
        // object. Clobbering it unconditionally would report the OBJ slot as dead the moment the
        // register is reused for an int, which is the bug this whole split exists to fix.
        DexType.Ref newObj = (writeObjSlot && (type.scalar() & DexType.OBJ) != 0)
                ? DexType.objSlotRefOf(type) : objSlot[reg];
        if (regs[reg] == type && !orphansHigh && newObj == objSlot[reg]) {
            return this;
        }
        DexType[] copy = regs.clone();
        copy[reg] = type;
        if (orphansHigh) {
            copy[reg + 1] = DexType.DEAD;
        }
        DexType.Ref[] slots = objSlot;
        if (newObj != objSlot[reg]) {
            slots = objSlot.clone();
            slots[reg] = newObj;
        }
        // NOTE the deliberate NON-action here. Overwriting the HIGH half (writing to r when r-1
        // holds a wide) does NOT invalidate the low half, even though Dalvik's own verifier
        // considers the pair destroyed. That is because one Dalvik register maps to one JVM local
        // PER SCALAR READING (see DexType): the long living in v8 occupies the slot for
        // (v8, LONG), and a `long-to-int v9, v8` writes the entirely separate slot for (v9, INT)
        // without touching it. Killing v8 here would mark a register untypeable that the code
        // writer can still legitimately read, turning a working conversion into a stuck one. The
        // only programs that could tell the difference are ones ART itself would reject.
        return new RegisterState(copy, slots, hierarchy);
    }

    /** Write a 64-bit value into the pair (reg, reg+1). */
    public RegisterState setWide(int reg, DexType type) {
        return set(reg, type).set(reg + 1, DexType.WIDE_HIGH);
    }

    /**
     * Write a value inferred from a type descriptor, choosing the narrow or wide form as the
     * descriptor requires. This is the common path for field reads, method results and
     * parameters, where the descriptor is authoritative.
     */
    public RegisterState setFromDescriptor(int reg, String descriptor) {
        DexType t = DexType.fromDescriptor(descriptor);
        if (DexType.isWide(t.scalar())) {
            return setWide(reg, t);
        }
        return set(reg, t);
    }

    /**
     * Copy a register, preserving every lattice component, as Dalvik's {@code move} does.
     *
     * <p>The VALUE view is what is copied, because the code writer emits one load/store pair per
     * live reading of the source and touches no other slot. So a {@code move} of an int-only value
     * leaves the destination's OBJ slot alone, which {@link #set} already handles.
     */
    public RegisterState move(int dest, int src, boolean wide) {
        RegisterState s = set(dest, live(src));
        if (wide) {
            s = s.set(dest + 1, live(src + 1));
        }
        return s;
    }

    /**
     * Replace every register holding the given uninitialized reference with {@code replacement}.
     *
     * <p>Two JVMS rules need this, and both are easy to miss because they only bite on inputs
     * that a straight-line test will not produce:
     *
     * <ul>
     *   <li>{@code invokespecial <init>} initializes EVERY copy of the reference at once, not
     *       just the receiver register. JVMS 4.10.1.9 (invokespecial) applies {@code substitute}
     *       to both the operand stack and the locals. Dalvik code routinely holds two copies,
     *       because {@code new-instance v0} followed by {@code move v1, v0} then
     *       {@code invoke-direct {v0}} must leave v1 initialized too. Initializing only v0 leaves
     *       v1 permanently uninitialized, and every later merge involving v1 collapses to
     *       CONFLICT.</li>
     *   <li>{@code new-instance} itself must first wipe any STALE value carrying the same
     *       allocation offset. JVMS 4.10.1.9 (new) reads
     *       {@code substitute(NewItem, top, Locals, NewLocals)}: the incoming locals have
     *       {@code uninitialized(Offset)} replaced by {@code top} before the fresh one is
     *       created. This exists for loops. On the second trip through a loop containing
     *       {@code new-instance v0, Foo}, the register still holds {@code uninitialized(O)} from
     *       the previous iteration, and without the wipe it would merge with the fresh
     *       {@code uninitialized(O)} and look like the SAME object. Wiping first keeps each
     *       iteration's allocation distinct and is what makes the loop reach a fixpoint.</li>
     * </ul>
     */
    public RegisterState substituteRef(DexType.Ref from, DexType replacement) {
        DexType[] copy = null;
        boolean[] valueMatched = null;
        for (int i = 0; i < regs.length; i++) {
            if (regs[i].ref().equals(from)) {
                if (copy == null) {
                    copy = regs.clone();
                    valueMatched = new boolean[regs.length];
                }
                copy[i] = replacement;
                valueMatched[i] = true;
            }
        }
        // The SLOTS carry the same substitution, and they carry it independently: a register whose
        // value has since been reused for an int still has the uninitialized reference sitting in
        // its OBJ slot, and both JVMS rules quoted above are written against Locals, not against
        // "the locals that are still readable". The replacement ref is the initialized class for
        // the <init> case; for the new-instance wipe the replacement is DEAD, which is a value with
        // no OBJ reading, and JVMS 4.10.1.9's `new` rule spells that case out as
        // substitute(NewItem, top, Locals, NewLocals) -- CONFLICT is how this lattice spells top.
        boolean initializing = (replacement.scalar() & DexType.OBJ) != 0;
        DexType.Ref to = initializing
                ? DexType.objSlotRefOf(replacement) : DexType.Ref.CONFLICT;
        DexType.Ref[] slots = null;
        for (int i = 0; i < objSlot.length; i++) {
            // Two ways a register's slot takes the substitution, and the second one is what makes a
            // DEFERRED new-instance work. Normally the slot held `from` too, because the emitter
            // stored the uninitialized reference there. At a deferred site it did not: the emitter
            // kept the value on the stack and its `astore slot(vX, OBJ)` at THIS instruction is the
            // first thing ever written to that slot. So when the substitution is an INITIALIZATION
            // (the replacement is a real reference, i.e. the <init> case), every register whose
            // VALUE held `from` takes it as well.
            //
            // The new-instance stale-allocation wipe deliberately does NOT get that treatment: its
            // replacement is DEAD, JVMS 4.10.1.9 spells it `substitute(NewItem, top, Locals,
            // NewLocals)` -- a rule about LOCALS -- and at a deferred site no local holds the value,
            // so clobbering the slot to top there would throw away a perfectly good frame entry.
            boolean matched = objSlot[i].equals(from)
                    || (initializing && valueMatched != null && valueMatched[i]);
            if (matched && !objSlot[i].equals(to)) {
                if (slots == null) {
                    slots = objSlot.clone();
                }
                slots[i] = to;
            }
        }
        if (copy == null && slots == null) {
            return this;
        }
        return new RegisterState(copy == null ? regs : copy,
                                 slots == null ? objSlot : slots, hierarchy);
    }

    /**
     * Apply a taint marker and an array-type narrowing to one register, as ART's implicit casts
     * do. See {@link TypeInference} for why implicit casts need a defensive checkcast.
     */
    public RegisterState taint(int reg, int narrowedArrayKind, String narrowedArrayDescriptor) {
        DexType t = live(reg);
        if (t.isDead()) {
            return this;
        }
        return set(reg, t.withArray(narrowedArrayKind, narrowedArrayDescriptor).withTaint(true));
    }

    /**
     * Merge another state into this one at a control flow join.
     *
     * <p>Returns {@code this} by IDENTITY when the merge changes nothing. The fixpoint loop tests
     * for that identity to decide whether to re-queue successors, so this contract is load
     * bearing: returning an equal-but-distinct instance would make the analysis loop forever.
     */
    public RegisterState merge(RegisterState other) {
        if (this == other) {
            return this;
        }
        DexType[] copy = null;
        DexType.Ref[] slots = null;
        int n = Math.min(regs.length, other.regs.length);
        for (int i = 0; i < n; i++) {
            DexType merged = regs[i].merge(other.regs[i], hierarchy);
            if (merged != regs[i]) {
                if (copy == null) {
                    copy = regs.clone();
                }
                copy[i] = merged;
            }
            // The SLOT join is separate from the value meet and survives it. Two paths that leave
            // an int and an object in the same register meet to "no legal reading" as a VALUE, but
            // their (register, OBJ) slots each still hold a reference, and the frame has to say
            // which one. mergeRef is the JVMS 4.10.1.2 least upper bound, so this is exactly the
            // type every predecessor is assignable to.
            //
            // Refs are interned, so the identity test is exact and it skips the least-upper-bound
            // walk on the overwhelmingly common "this register did not change" case.
            if (objSlot[i] == other.objSlot[i]) {
                continue;
            }
            DexType.Ref mergedSlot =
                    DexType.mergeRef(objSlot[i], other.objSlot[i], hierarchy);
            if (!mergedSlot.equals(objSlot[i])) {
                if (slots == null) {
                    slots = objSlot.clone();
                }
                slots[i] = mergedSlot;
            }
        }
        if (copy == null && slots == null) {
            return this;
        }
        return new RegisterState(copy == null ? regs : copy,
                                 slots == null ? objSlot : slots, hierarchy);
    }

    /** True when every register is identical by reference. Used only for assertions and tests. */
    public boolean sameAs(RegisterState other) {
        if (this == other) return true;
        if (other == null || regs.length != other.regs.length) return false;
        for (int i = 0; i < regs.length; i++) {
            if (regs[i] != other.regs[i]) return false;
            if (!objSlot[i].equals(other.objSlot[i])) return false;
        }
        return true;
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder(DexType.describe(regs));
        sb.append(" slots[");
        for (int i = 0; i < objSlot.length; i++) {
            if (objSlot[i] == DexType.Ref.NULL_REF) continue;
            sb.append(" v").append(i).append(".A=").append(objSlot[i]);
        }
        return sb.append(" ]").toString();
    }
}
