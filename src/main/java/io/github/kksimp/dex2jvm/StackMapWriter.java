package io.github.kksimp.dex2jvm;

import java.util.ArrayList;
import java.util.List;

/**
 * Serializer for the StackMapTable attribute (JVMS SE21 section 4.7.4).
 *
 * WHY THIS EXISTS (the class file version decision, documented here because
 * this is the file it hinges on):
 *
 *   We emit class file major version 52 (Java SE 8). Version 49 would let us
 *   skip stack maps entirely, because verification by type checking only
 *   applies to "a class file whose version number is 50.0 or above"
 *   (JVMS 4.10.1), but 49 predates invokedynamic (51) and static/default
 *   interface methods (52), and R8-built APKs are full of both. Upstream
 *   enjarify writes version 49 (jvm/writeclass.py, the version bytes), so its
 *   output cannot carry the static/default interface methods that R8-built
 *   apps emit: before 52, JVMS 4.6 requires every interface method other
 *   than &lt;clinit&gt; to be public abstract, and HotSpot rejects anything else
 *   with a ClassFormatError when it checks the class.
 *
 *   Version 50 would let us lean on the failover clause, JVMS 4.10.1: "If, and
 *   only if, a class file's version number equals 50.0, then if the type
 *   checking fails, a Java Virtual Machine implementation may choose to attempt
 *   to perform verification by type inference." But 50 also predates
 *   invokedynamic, so it is not an option for lambda-bearing apps either.
 *
 *   So the version is 52, verification by type checking is mandatory, and there
 *   is no failover. That leaves exactly two ways to load our output:
 *
 *     (a) Emit real stack maps. Correct under a fully enabled verifier.
 *     (b) Emit no StackMapTable and disable verification. JVMS 4.7.4: "In a
 *         class file whose version number is 50.0 or above, if a method's Code
 *         attribute does not have a StackMapTable attribute, it has an implicit
 *         stack map attribute. This implicit stack map attribute is equivalent
 *         to a StackMapTable attribute with number_of_entries equal to zero."
 *         An empty map verifies fine for straight-line code and fails on the
 *         first branch target, which is why a frameless method with any branch
 *         is rejected by a JVM running with bytecode verification enabled.
 *
 *   We implement (a) and keep (b) working as the fallback: CodeWriter omits
 *   the attribute entirely when no frames are supplied, which reproduces the
 *   frameless output byte for byte; supply frames and the same class file
 *   becomes verifier-clean. Path (a) is the one that matters because (b)
 *   depends on turning the verifier off: that is VM-wide (it also stops
 *   verifying every other class in the VM, which hides bugs there too, not
 *   just in the converted code), and the launcher option that does it was
 *   deprecated in JDK 13 and is on a removal path.
 *
 * FRAME SHAPE CONTRACT (what a type-inference producer must hand us):
 *
 *   locals[] and stack[] are in ENTRY form, not SLOT form. A long or a double
 *   is ONE entry here even though it occupies TWO local variable slots. JVMS
 *   4.7.4 spells this out for Long_variable_info and Double_variable_info: the
 *   item "indicates that the first of two locations has the verification type
 *   long", and the second location is implied, never encoded. Getting this
 *   wrong is the single most common StackMapTable bug, so if your inference
 *   pass tracks per-slot state, run it through compressSlots() first.
 *
 *   Trailing `top` entries in locals[] are trimmed automatically. HotSpot pads
 *   a frame's locals out to max_locals with top, so trimming is
 *   representation-only and it is what javac emits.
 *
 * BRIDGING FROM TypeInference (this package's DEX-side analysis):
 *
 *   TypeInference.Result.entryState(offset) yields a RegisterState, which is a
 *   DexType per DEX register. Translate one register's DexType to a VType by
 *   looking at DexType.ref().kind first and falling back to DexType.scalar():
 *
 *     ref kind KIND_UNINIT_THIS  -> UNINITIALIZED_THIS
 *     ref kind KIND_UNINIT       -> uninitialized(label marking the JVM `new`
 *                                   that corresponds to Ref.newOffset; the
 *                                   translator must keep that DEX-offset to
 *                                   Label map, since the attribute stores the
 *                                   `new` instruction's BYTECODE offset)
 *     ref kind KIND_NULL         -> NULL
 *     ref kind KIND_CLASS        -> object(Ref.name), which is already an
 *                                   internal name or an array descriptor
 *     ref kind KIND_NONE/CONFLICT-> TOP
 *
 *   and when the ref part is not in play, DexType.scalar() is a BITSET of the
 *   still-possible kinds (INT, FLOAT, OBJ, LONG, DOUBLE), because a DEX
 *   register is untyped until an instruction pins it. A frame entry must name
 *   ONE verification type, so:
 *
 *     exactly INT    -> INTEGER      exactly LONG   -> LONG
 *     exactly FLOAT  -> FLOAT        exactly DOUBLE -> DOUBLE
 *     more than one bit set, or NONE -> TOP
 *
 *   TOP is always safe: it is the top of the verifier's type lattice, so
 *   declaring it can only lose precision, never claim something false. It does
 *   mean the register cannot be READ after that point without being redefined,
 *   which is exactly what an unresolvable DEX register means anyway.
 *
 *   Registers map to JVM local slots in whatever way the translator chose; the
 *   only rule this class cares about is that a wide value's second slot is not
 *   given an entry of its own (use compressSlots()).
 *
 * Frames must be added in strictly ascending bytecode offset order.
 *
 * Not thread safe. One instance per method.
 */
public final class StackMapWriter {

    // verification_type_info tags (JVMS 4.7.4). Note that Double is 3 and Long
    // is 4, which is not the order the union is declared in.
    public static final int ITEM_Top               = 0;
    public static final int ITEM_Integer           = 1;
    public static final int ITEM_Float             = 2;
    public static final int ITEM_Double            = 3;
    public static final int ITEM_Long              = 4;
    public static final int ITEM_Null              = 5;
    public static final int ITEM_UninitializedThis = 6;
    public static final int ITEM_Object            = 7;
    public static final int ITEM_Uninitialized     = 8;

    /** One verification_type_info (JVMS 4.7.4). Value type: compare with equals(). */
    public static final class VType {
        public final int tag;
        /** Internal class name for ITEM_Object, else null. */
        public final String className;
        /** The `new` instruction that produced an ITEM_Uninitialized value, else null. */
        public final CodeWriter.Label newInsn;
        /** Set only on a VType recovered by roundTripMismatch, where the u2 that
         *  followed the tag is known but the name or Label behind it is not.
         *  -1 on every VType a producer builds. */
        final int rawOperand;

        private VType(int tag, String className, CodeWriter.Label newInsn) {
            this(tag, className, newInsn, -1);
        }

        private VType(int tag, String className, CodeWriter.Label newInsn, int rawOperand) {
            this.tag = tag;
            this.className = className;
            this.newInsn = newInsn;
            this.rawOperand = rawOperand;
        }

        public static final VType TOP                = new VType(ITEM_Top, null, null);
        public static final VType INTEGER            = new VType(ITEM_Integer, null, null);
        public static final VType FLOAT              = new VType(ITEM_Float, null, null);
        public static final VType DOUBLE             = new VType(ITEM_Double, null, null);
        public static final VType LONG               = new VType(ITEM_Long, null, null);
        public static final VType NULL               = new VType(ITEM_Null, null, null);
        public static final VType UNINITIALIZED_THIS = new VType(ITEM_UninitializedThis, null, null);

        /** ITEM_Object. Takes an internal name ("java/lang/String") or, for an
         *  array, a descriptor ("[I"), matching CONSTANT_Class_info's rule. */
        public static VType object(String internalNameOrArrayDesc) {
            return new VType(ITEM_Object, internalNameOrArrayDesc, null);
        }

        /** ITEM_Uninitialized. `newInsn` must be the label marking the `new`
         *  instruction that allocated the value; its offset is what gets
         *  written, so the label has to be resolved before serialization. */
        public static VType uninitialized(CodeWriter.Label newInsn) {
            return new VType(ITEM_Uninitialized, null, newInsn);
        }

        /** Maps a JVM field descriptor to the verification type of the value it
         *  holds. byte/char/short/boolean all verify as int (JVMS 4.10.1.2:
         *  "the verification type int is used for byte, char, short, boolean"). */
        public static VType forDescriptor(String desc) {
            char c = desc.charAt(0);
            switch (c) {
                case 'B': case 'C': case 'I': case 'S': case 'Z': return INTEGER;
                case 'F': return FLOAT;
                case 'J': return LONG;
                case 'D': return DOUBLE;
                case 'V': throw new IllegalArgumentException("void has no verification type");
                case '[': return object(desc);
                case 'L': return object(desc.substring(1, desc.length() - 1));
                default: throw new IllegalArgumentException("bad descriptor: " + desc);
            }
        }

        /** True for the two types that occupy two local variable slots. */
        public boolean isWide() { return tag == ITEM_Long || tag == ITEM_Double; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof VType)) return false;
            VType t = (VType) o;
            if (tag != t.tag) return false;
            if (tag == ITEM_Object) return className.equals(t.className);
            if (tag == ITEM_Uninitialized) return newInsn == t.newInsn;
            return true;
        }

        @Override public int hashCode() {
            int h = tag;
            if (className != null) h = h * 31 + className.hashCode();
            if (newInsn != null) h = h * 31 + System.identityHashCode(newInsn);
            return h;
        }

        @Override public String toString() {
            switch (tag) {
                case ITEM_Top: return "top";
                case ITEM_Integer: return "int";
                case ITEM_Float: return "float";
                case ITEM_Double: return "double";
                case ITEM_Long: return "long";
                case ITEM_Null: return "null";
                case ITEM_UninitializedThis: return "uninitializedThis";
                case ITEM_Object: return className;
                default: return "uninitialized(new@" + newInsn + ")";
            }
        }
    }

    /** A complete verifier state: the local variable array and the operand
     *  stack, both in entry form (see the class comment). */
    public static final class Frame {
        public final VType[] locals;
        public final VType[] stack;

        public Frame(VType[] locals, VType[] stack) {
            this.locals = trimTrailingTop(locals);
            this.stack = (stack == null) ? new VType[0] : stack;
        }

        @Override public String toString() {
            return "locals=" + java.util.Arrays.toString(locals)
                    + " stack=" + java.util.Arrays.toString(stack);
        }
    }

    /**
     * Supplies the verifier state at a bytecode offset. This is the seam a type
     * inference pass plugs into.
     *
     * CodeWriter calls frameAt() exactly once per offset that needs a frame,
     * after all labels are resolved, in ascending order. The set of offsets is
     * computed from the emitted control flow, so an implementation only has to
     * answer, never to decide where frames go. Returning null for an offset
     * means "no state available"; CodeWriter then drops the whole StackMapTable
     * for that method rather than emitting a partial one, since a table missing
     * a required frame fails verification just as loudly as no table at all.
     */
    public interface FrameProvider {
        Frame frameAt(int bytecodeOffset);
    }

    private final ConstantPool pool;
    private final List<int[]> offsets = new ArrayList<>();   // parallel to frames, holds {offset}
    private final List<Frame> frames = new ArrayList<>();
    private Frame initial;

    public StackMapWriter(ConstantPool pool) {
        this.pool = pool;
    }

    /**
     * The implicit frame at offset 0, derived from the method descriptor. JVMS
     * 4.7.4: "The first stack map frame of a method is implicit, and computed
     * from the method descriptor by the type checker." It is never written, but
     * the first written frame's shape is a delta against it, so we need it.
     */
    public void setInitialFrame(Frame f) { this.initial = f; }

    /** Frames must be added in strictly ascending bytecode offset order. */
    public void addFrame(int bytecodeOffset, Frame f) {
        if (!offsets.isEmpty() && bytecodeOffset <= offsets.get(offsets.size() - 1)[0]) {
            throw new IllegalArgumentException(
                    "stack map frames must be in ascending offset order (got "
                            + bytecodeOffset + " after " + offsets.get(offsets.size() - 1)[0] + ")");
        }
        offsets.add(new int[] { bytecodeOffset });
        frames.add(f);
    }

    public int size() { return frames.size(); }

    // ---- helpers for producers ------------------------------------------

    /**
     * Converts a per-SLOT type array (the natural output of a register-based
     * inference pass, where a long occupies slots n and n+1) into the per-ENTRY
     * form the attribute needs. The second slot of a long/double is expected to
     * be TOP or LONG/DOUBLE repeated; either is dropped.
     */
    public static VType[] compressSlots(VType[] slots) {
        List<VType> out = new ArrayList<>(slots.length);
        for (int i = 0; i < slots.length; i++) {
            VType t = slots[i];
            out.add(t);
            if (t.isWide()) i++;   // skip the implied second slot
        }
        return out.toArray(new VType[0]);
    }

    /** Splits a method descriptor's parameter list into verification types, in
     *  entry form. "(IJLjava/lang/String;)V" yields [int, long, String]. */
    public static VType[] parameterTypes(String methodDescriptor) {
        List<VType> out = new ArrayList<>();
        int i = 1;   // skip '('
        while (methodDescriptor.charAt(i) != ')') {
            int start = i;
            while (methodDescriptor.charAt(i) == '[') i++;
            if (methodDescriptor.charAt(i) == 'L') {
                i = methodDescriptor.indexOf(';', i) + 1;
            } else {
                i++;
            }
            out.add(VType.forDescriptor(methodDescriptor.substring(start, i)));
        }
        return out.toArray(new VType[0]);
    }

    /**
     * Builds the implicit frame at offset 0 for a method, per JVMS 4.10.1.6
     * (methodInitialStackFrame): locals are `this` (for an instance method)
     * followed by the parameter types, and the stack is empty.
     *
     * For a constructor the `this` entry is uninitializedThis, not the class
     * type, with the one exception the spec calls out: in Object.<init> itself
     * `this` is already java/lang/Object.
     */
    public static Frame initialFrame(String ownerInternalName, String methodName,
                                     String descriptor, boolean isStatic) {
        VType[] params = parameterTypes(descriptor);
        if (isStatic) return new Frame(params, new VType[0]);

        VType self;
        if ("<init>".equals(methodName) && !"java/lang/Object".equals(ownerInternalName)) {
            self = VType.UNINITIALIZED_THIS;
        } else {
            self = VType.object(ownerInternalName);
        }
        VType[] locals = new VType[params.length + 1];
        locals[0] = self;
        System.arraycopy(params, 0, locals, 1, params.length);
        return new Frame(locals, new VType[0]);
    }

    /** Number of local variable SLOTS a frame's locals occupy, which is what
     *  max_locals has to cover. */
    public static int slotCount(VType[] entries) {
        int n = 0;
        for (VType t : entries) n += t.isWide() ? 2 : 1;
        return n;
    }

    private static VType[] trimTrailingTop(VType[] locals) {
        if (locals == null) return new VType[0];
        int n = locals.length;
        while (n > 0 && locals[n - 1].tag == ITEM_Top) n--;
        if (n == locals.length) return locals;
        VType[] out = new VType[n];
        System.arraycopy(locals, 0, out, 0, n);
        return out;
    }

    // ---- serialization ---------------------------------------------------

    private void writeType(ConstantPool.ByteVector out, VType t) {
        out.putU1(t.tag);
        if (t.tag == ITEM_Object) {
            out.putU2(pool.classRef(t.className));
        } else if (t.tag == ITEM_Uninitialized) {
            out.putU2(t.newInsn.offset());
        }
    }

    private static boolean sameLocals(VType[] a, VType[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (!a[i].equals(b[i])) return false;
        return true;
    }

    private static boolean isPrefix(VType[] shortOne, VType[] longOne) {
        for (int i = 0; i < shortOne.length; i++) {
            if (!shortOne[i].equals(longOne[i])) return false;
        }
        return true;
    }

    private void writeFull(ConstantPool.ByteVector out, int delta, Frame f) {
        out.putU1(255);
        out.putU2(delta);
        out.putU2(f.locals.length);
        for (VType t : f.locals) writeType(out, t);
        out.putU2(f.stack.length);
        for (VType t : f.stack) writeType(out, t);
    }

    /**
     * Produces the attribute BODY (everything after attribute_length), i.e.
     * number_of_entries followed by the frames. The caller wraps it with the
     * name index and length.
     *
     * Offset encoding, JVMS 4.7.4: "The bytecode offset at which a stack map
     * frame applies is calculated by taking the value offset_delta specified in
     * the frame ... and adding offset_delta + 1 to the bytecode offset of the
     * previous frame, unless the previous frame is the initial frame of the
     * method. In that case, the bytecode offset at which the stack map frame
     * applies is the value offset_delta specified in the frame." The `- 1` on
     * every frame after the first is that rule; dropping it shifts every frame
     * by one byte and yields "Expecting a stackmap frame at branch target N".
     */
    public byte[] toAttributeBody() {
        ConstantPool.ByteVector out = new ConstantPool.ByteVector(16 + frames.size() * 8);
        out.putU2(frames.size());

        Frame prev = (initial != null) ? initial : new Frame(new VType[0], new VType[0]);
        int prevOffset = -1;

        for (int i = 0; i < frames.size(); i++) {
            int offset = offsets.get(i)[0];
            Frame f = frames.get(i);
            int delta = (prevOffset < 0) ? offset : offset - prevOffset - 1;
            if (delta < 0) {
                throw new IllegalStateException("negative stack map offset delta at " + offset);
            }

            if (f.stack.length == 0) {
                if (sameLocals(f.locals, prev.locals)) {
                    if (delta <= 63) {
                        out.putU1(delta);                       // same_frame, 0..63
                    } else {
                        out.putU1(251);                         // same_frame_extended
                        out.putU2(delta);
                    }
                } else if (f.locals.length < prev.locals.length
                        && prev.locals.length - f.locals.length <= 3
                        && isPrefix(f.locals, prev.locals)) {
                    int k = prev.locals.length - f.locals.length;
                    out.putU1(251 - k);                         // chop_frame, 248..250
                    out.putU2(delta);
                } else if (f.locals.length > prev.locals.length
                        && f.locals.length - prev.locals.length <= 3
                        && isPrefix(prev.locals, f.locals)) {
                    int k = f.locals.length - prev.locals.length;
                    out.putU1(251 + k);                         // append_frame, 252..254
                    out.putU2(delta);
                    for (int j = prev.locals.length; j < f.locals.length; j++) {
                        writeType(out, f.locals[j]);
                    }
                } else {
                    writeFull(out, delta, f);
                }
            } else if (f.stack.length == 1 && sameLocals(f.locals, prev.locals)) {
                if (delta <= 63) {
                    out.putU1(64 + delta);                      // same_locals_1_stack_item
                } else {
                    out.putU1(247);                             // ..._extended
                    out.putU2(delta);
                }
                writeType(out, f.stack[0]);
            } else {
                writeFull(out, delta, f);
            }

            prev = f;
            prevOffset = offset;
        }
        return out.toByteArray();
    }

    // ---- round-trip self check -------------------------------------------

    /**
     * Decodes a body produced by toAttributeBody and checks that it reads back
     * as exactly the frames that were handed in.
     *
     * WHY. Every frame type above is a DELTA against the previous frame, so a
     * single wrong choice (a chop where the locals are not really a prefix, an
     * append that miscounts, an offset delta off by one) does not corrupt one
     * frame, it silently re-interprets every frame after it. The class file
     * still parses; the damage only appears as a verifier complaint about some
     * unrelated instruction, or not at all when verification is off. Re-reading
     * what we just wrote is the only check that separates "the analysis handed
     * us a bad frame" from "we encoded a good frame badly", which is otherwise
     * a matter of inference from a verifier message.
     *
     * Types are compared in ENCODED form -- tag plus the u2 that follows for
     * ITEM_Object and ITEM_Uninitialized -- rather than by name, because
     * ConstantPool.classRef is idempotent, so re-asking for the expected type's
     * index yields the very index that was written and no pool reader is
     * needed.
     *
     * @return null when the body round trips exactly, else a description of the
     *         first discrepancy
     */
    public String roundTripMismatch(byte[] body) {
        int[] p = { 0 };
        int count;
        try {
            count = readU2(body, p);
        } catch (RuntimeException e) {
            return "body too short for number_of_entries";
        }
        if (count != frames.size()) {
            return "number_of_entries " + count + " != " + frames.size() + " frames added";
        }
        Frame prev = (initial != null) ? initial : new Frame(new VType[0], new VType[0]);
        int prevOffset = -1;
        try {
            for (int i = 0; i < count; i++) {
                int tag = body[p[0]++] & 0xFF;
                int delta;
                VType[] locals;
                VType[] stack;
                if (tag <= 63) {                       // same_frame
                    delta = tag;
                    locals = prev.locals;
                    stack = new VType[0];
                } else if (tag <= 127) {               // same_locals_1_stack_item
                    delta = tag - 64;
                    locals = prev.locals;
                    stack = new VType[] { readType(body, p) };
                } else if (tag < 247) {
                    return "frame " + i + " uses reserved tag " + tag + " (JVMS 4.7.4)";
                } else if (tag == 247) {               // ..._extended
                    delta = readU2(body, p);
                    stack = new VType[] { readType(body, p) };
                    locals = prev.locals;
                } else if (tag <= 250) {               // chop_frame
                    int k = 251 - tag;
                    delta = readU2(body, p);
                    if (k > prev.locals.length) return "frame " + i + " chops " + k + " of "
                            + prev.locals.length + " locals";
                    locals = java.util.Arrays.copyOf(prev.locals, prev.locals.length - k);
                    stack = new VType[0];
                } else if (tag == 251) {               // same_frame_extended
                    delta = readU2(body, p);
                    locals = prev.locals;
                    stack = new VType[0];
                } else if (tag <= 254) {               // append_frame
                    int k = tag - 251;
                    delta = readU2(body, p);
                    locals = java.util.Arrays.copyOf(prev.locals, prev.locals.length + k);
                    for (int j = 0; j < k; j++) locals[prev.locals.length + j] = readType(body, p);
                    stack = new VType[0];
                } else {                               // full_frame
                    delta = readU2(body, p);
                    locals = new VType[readU2(body, p)];
                    for (int j = 0; j < locals.length; j++) locals[j] = readType(body, p);
                    stack = new VType[readU2(body, p)];
                    for (int j = 0; j < stack.length; j++) stack[j] = readType(body, p);
                }
                // JVMS 4.7.4: offset_delta for the first explicit frame IS the
                // offset; every later one adds offset_delta + 1 to the previous.
                int offset = (prevOffset < 0) ? delta : prevOffset + delta + 1;
                int want = offsets.get(i)[0];
                if (offset != want) {
                    return "frame " + i + " decodes at offset " + offset + ", expected " + want;
                }
                Frame expect = frames.get(i);
                String bad = sameTypes("locals", i, expect.locals, locals);
                if (bad != null) return bad;
                bad = sameTypes("stack", i, expect.stack, stack);
                if (bad != null) return bad;
                prev = new Frame(locals, stack);
                prevOffset = offset;
            }
        } catch (RuntimeException e) {
            return "decode failed: " + e;
        }
        if (p[0] != body.length) {
            return "decoded " + p[0] + " of " + body.length + " bytes";
        }
        return null;
    }

    private String sameTypes(String what, int frame, VType[] expect, VType[] got) {
        // The writer trims trailing Top from locals (representation only,
        // HotSpot pads back out to max_locals), so the decoded side is compared
        // against the same trimmed form the writer actually held.
        VType[] a = "locals".equals(what) ? trimTrailingTop(expect) : expect;
        VType[] b = "locals".equals(what) ? trimTrailingTop(got) : got;
        if (a.length != b.length) {
            return "frame " + frame + " " + what + " length " + b.length + ", expected " + a.length;
        }
        for (int i = 0; i < a.length; i++) {
            if (encoded(a[i]) != encoded(b[i])) {
                return "frame " + frame + " " + what + "[" + i + "] is " + b[i]
                        + ", expected " + a[i];
            }
        }
        return null;
    }

    /** tag in the high bits plus the u2 operand, so two VTypes compare equal
     *  exactly when they serialize to the same bytes. */
    private long encoded(VType t) {
        long operand = 0;
        if (t.rawOperand >= 0) {
            operand = t.rawOperand;                       // decoded side
        } else if (t.tag == ITEM_Object) {
            operand = pool.classRef(t.className);         // idempotent lookup
        } else if (t.tag == ITEM_Uninitialized) {
            operand = t.newInsn.offset();
        }
        return ((long) t.tag << 32) | operand;
    }

    private static int readU2(byte[] b, int[] p) {
        int v = ((b[p[0]] & 0xFF) << 8) | (b[p[0] + 1] & 0xFF);
        p[0] += 2;
        return v;
    }

    /** Rebuilds a VType from its encoded form. Object and Uninitialized keep
     *  their raw u2 so encoded() can compare them without a pool reader. */
    private VType readType(byte[] b, int[] p) {
        int tag = b[p[0]++] & 0xFF;
        if (tag == ITEM_Object) return new VType(ITEM_Object, null, null, readU2(b, p));
        if (tag == ITEM_Uninitialized) return new VType(ITEM_Uninitialized, null, null, readU2(b, p));
        switch (tag) {
            case ITEM_Top: return VType.TOP;
            case ITEM_Integer: return VType.INTEGER;
            case ITEM_Float: return VType.FLOAT;
            case ITEM_Double: return VType.DOUBLE;
            case ITEM_Long: return VType.LONG;
            case ITEM_Null: return VType.NULL;
            case ITEM_UninitializedThis: return VType.UNINITIALIZED_THIS;
            default: throw new IllegalStateException("bad verification_type_info tag " + tag);
        }
    }
}
