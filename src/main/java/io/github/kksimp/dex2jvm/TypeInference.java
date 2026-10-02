package io.github.kksimp.dex2jvm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Infers a JVM-typed view of Dalvik's untyped registers, for every instruction of a method.
 *
 * <p>This is the piece that makes a DEX to JVM converter correct rather than merely plausible.
 * See {@link DexType} for the lattice design and for the rule that ONE Dalvik register maps to UP
 * TO FIVE JVM local slots; that rule is the main thing a code writer must not get wrong.
 *
 * <h2>The algorithm</h2>
 *
 * A standard forward dataflow fixpoint over the DEX instruction stream. {@link RegisterState} is
 * the lattice element, one per instruction ENTRY point. Starting from the parameter state, each
 * instruction's transfer function produces a successor state which is merged into every successor
 * offset; any offset whose state actually changed is re-queued. Termination is guaranteed because
 * every lattice component has finite height and merges move in one direction only (see
 * {@link DexType}); the pass cap below is a safety net for malformed input, not the real bound.
 *
 * <p>We iterate the instruction list in address order rather than using a true worklist priority
 * queue. That is what the reference implementation does (enjarify typeinference.py) and it
 * converges in very few passes on real code because DEX is mostly forward-branching.
 *
 * <h2>Three things that are easy to get wrong</h2>
 *
 * <ol>
 *   <li><b>Exception edges carry the PRE-state, not the post-state.</b> An exception can be
 *       thrown part-way through an instruction, so the handler must see the registers as they
 *       were BEFORE the instruction ran. Merging the post-state instead lets the handler believe
 *       a destination register already holds the instruction's result, which is false on exactly
 *       the path the handler exists to service. JVMS 4.10.1.6 encodes this by deriving the
 *       handler frame from an {@code ExcStackFrame} captured before the instruction's effect.</li>
 *   <li><b>{@code new-instance} must wipe stale allocations of the same offset first.</b> JVMS
 *       4.10.1.9 (rule for {@code new}) reads {@code substitute(NewItem, top, Locals, NewLocals)}
 *       before pushing the fresh value. Without it a loop that allocates in its body makes the
 *       previous iteration's object merge with the current one and look like the same object.
 *       See {@link RegisterState#substituteRef}.</li>
 *   <li><b>{@code <init>} initializes EVERY copy of the reference, not just the receiver.</b>
 *       Also {@link RegisterState#substituteRef}.</li>
 * </ol>
 *
 * <h2>Integration note (why the input types are concrete classes)</h2>
 *
 * {@link Insn}, {@link MethodInput}, {@link TryBlock} and {@link Catch} are plain data carriers
 * declared here rather than interfaces onto another agent's model, so that this analysis cannot
 * break when a neighbouring class changes shape. Whoever owns the DEX parser builds these
 * directly, or writes a small adapter. Only {@link Resolver} and {@link DexType.ClassHierarchy}
 * are interfaces, because they genuinely need to call back into the constant pool and the class
 * graph.
 *
 * <p>The numeric argument convention in {@link Insn#args} matches enjarify's
 * {@code dalvikformats.decode} exactly, including that branch offsets are already converted to
 * ABSOLUTE instruction offsets and that literals are already sign extended. That convention is
 * documented per opcode family on {@link Insn}.
 */
public final class TypeInference {

    private TypeInference() {}

    // ==================================================================
    // Opcode families. Ranges mirror the DEX opcode table; see
    // https://source.android.com/docs/core/runtime/dex-format
    // ==================================================================

    static final int F_NOP = 0;
    static final int F_MOVE = 1;
    static final int F_MOVE_WIDE = 2;
    static final int F_MOVE_RESULT = 3;
    static final int F_RETURN = 4;
    static final int F_CONST32 = 5;
    static final int F_CONST64 = 6;
    static final int F_CONST_STRING = 7;
    static final int F_CONST_CLASS = 8;
    static final int F_MONITOR_ENTER = 9;
    static final int F_MONITOR_EXIT = 10;
    static final int F_CHECK_CAST = 11;
    static final int F_INSTANCE_OF = 12;
    static final int F_ARRAY_LEN = 13;
    static final int F_NEW_INSTANCE = 14;
    static final int F_NEW_ARRAY = 15;
    static final int F_FILLED_NEW_ARRAY = 16;
    static final int F_FILL_ARRAY_DATA = 17;
    static final int F_THROW = 18;
    static final int F_GOTO = 19;
    static final int F_SWITCH = 20;
    static final int F_CMP = 21;
    static final int F_IF = 22;
    static final int F_IFZ = 23;
    static final int F_ARRAY_GET = 24;
    static final int F_ARRAY_PUT = 25;
    static final int F_INSTANCE_GET = 26;
    static final int F_INSTANCE_PUT = 27;
    static final int F_STATIC_GET = 28;
    static final int F_STATIC_PUT = 29;
    static final int F_INVOKE_VIRTUAL = 30;
    static final int F_INVOKE_SUPER = 31;
    static final int F_INVOKE_DIRECT = 32;
    static final int F_INVOKE_STATIC = 33;
    static final int F_INVOKE_INTERFACE = 34;
    static final int F_UNARY_OP = 35;
    static final int F_BINARY_OP = 36;
    static final int F_BINARY_OP_CONST = 37;
    /** invoke-custom / invoke-custom-range (0xfc, 0xfd) -> invokedynamic. */
    static final int F_INVOKE_CUSTOM = 38;
    /** invoke-polymorphic / -range (0xfa, 0xfb): a signature-polymorphic
     *  MethodHandle/VarHandle call, whose real signature is the SEPARATE proto
     *  operand rather than the referenced method's own descriptor. */
    static final int F_INVOKE_POLYMORPHIC = 39;
    static final int F_CONST_METHOD_HANDLE = 40;
    static final int F_CONST_METHOD_TYPE = 41;

    private static final int[] FAMILY = new int[256];
    static {
        // Each entry fills forward until the next declared opcode, matching the DEX table layout.
        int[][] ranges = {
            {0x00, F_NOP}, {0x01, F_MOVE}, {0x04, F_MOVE_WIDE}, {0x07, F_MOVE},
            {0x0a, F_MOVE_RESULT}, {0x0e, F_RETURN}, {0x12, F_CONST32}, {0x16, F_CONST64},
            {0x1a, F_CONST_STRING}, {0x1c, F_CONST_CLASS}, {0x1d, F_MONITOR_ENTER},
            {0x1e, F_MONITOR_EXIT}, {0x1f, F_CHECK_CAST}, {0x20, F_INSTANCE_OF},
            {0x21, F_ARRAY_LEN}, {0x22, F_NEW_INSTANCE}, {0x23, F_NEW_ARRAY},
            {0x24, F_FILLED_NEW_ARRAY}, {0x26, F_FILL_ARRAY_DATA}, {0x27, F_THROW},
            {0x28, F_GOTO}, {0x2b, F_SWITCH}, {0x2d, F_CMP}, {0x32, F_IF}, {0x38, F_IFZ},
            {0x3e, F_NOP}, {0x44, F_ARRAY_GET}, {0x4b, F_ARRAY_PUT}, {0x52, F_INSTANCE_GET},
            {0x59, F_INSTANCE_PUT}, {0x60, F_STATIC_GET}, {0x67, F_STATIC_PUT},
            {0x6e, F_INVOKE_VIRTUAL}, {0x6f, F_INVOKE_SUPER}, {0x70, F_INVOKE_DIRECT},
            {0x71, F_INVOKE_STATIC}, {0x72, F_INVOKE_INTERFACE}, {0x73, F_NOP},
            {0x74, F_INVOKE_VIRTUAL}, {0x75, F_INVOKE_SUPER}, {0x76, F_INVOKE_DIRECT},
            {0x77, F_INVOKE_STATIC}, {0x78, F_INVOKE_INTERFACE}, {0x79, F_NOP},
            {0x7b, F_UNARY_OP}, {0x90, F_BINARY_OP}, {0xd0, F_BINARY_OP_CONST}, {0xe3, F_NOP},
            // 0xfa..0xff are the invokedynamic family. enjarify's table stops
            // at 0xe3 and treats these as 1-unit NOPs, which DESYNCHRONISES the
            // instruction stream because they are 3 or 4 units wide.
            {0xfa, F_INVOKE_POLYMORPHIC}, {0xfc, F_INVOKE_CUSTOM},
            {0xfe, F_CONST_METHOD_HANDLE}, {0xff, F_CONST_METHOD_TYPE},
        };
        for (int i = 0; i < ranges.length; i++) {
            int end = (i + 1 < ranges.length) ? ranges[i + 1][0] : 256;
            for (int op = ranges[i][0]; op < end; op++) {
                FAMILY[op] = ranges[i][1];
            }
        }
    }

    public static int familyOf(int opcode) {
        return FAMILY[opcode & 0xFF];
    }

    /**
     * Instructions Dalvik considers capable of throwing, hence the ones that need an edge to any
     * covering exception handler.
     *
     * <p>Using too FEW edges here is unsound: the handler would be told a register holds an int
     * when the path that actually threw had a float there, and the converter would then emit an
     * {@code iload} for a float. Using too many merely loses precision (extra registers become
     * dead in the handler), so when in doubt this list should grow, not shrink. It matches
     * enjarify's full {@code THROW_TYPES}, NOT the {@code PRUNED_THROW_TYPES} its type inference
     * uses: the pruned set drops const-string, const-class and instance-of on the theory that
     * linkage errors can be ignored, but output run against a classpath missing some referenced
     * classes sees exactly those instructions raise NoClassDefFoundError, so they keep their
     * edges here.
     *
     * <p>Package-visible because Translator.throwingSpans must use THIS predicate: under
     * NARROW_TRY the exception table covers exactly the instructions this returns true for, and
     * a table wider than the edge set would make the verifier check handler frames against
     * program points the analysis never merged in. (One exception, and it is matched on both
     * sides: a monitor method's catch-all rows keep the whole range, and
     * MethodInput.catchAllWideEdges gives exactly those rows' extra instructions their edges.)
     * It equals AOSP's kThrow set
     * (art/libdexfile/dex/dex_instruction_list.h, 84 opcodes) and {@link Opcodes#canThrow};
     * both were diffed opcode by opcode on 2026-09-26 with no difference.
     */
    static boolean canThrow(int opcode) {
        switch (FAMILY[opcode & 0xFF]) {
            case F_INVOKE_VIRTUAL: case F_INVOKE_SUPER: case F_INVOKE_DIRECT:
            case F_INVOKE_STATIC: case F_INVOKE_INTERFACE:
            case F_CONST_STRING: case F_CONST_CLASS: case F_MONITOR_ENTER: case F_MONITOR_EXIT:
            case F_CHECK_CAST: case F_INSTANCE_OF: case F_ARRAY_LEN: case F_NEW_ARRAY:
            case F_NEW_INSTANCE: case F_FILLED_NEW_ARRAY: case F_FILL_ARRAY_DATA: case F_THROW:
            case F_ARRAY_GET: case F_ARRAY_PUT: case F_INSTANCE_GET: case F_INSTANCE_PUT:
            case F_STATIC_GET: case F_STATIC_PUT:
            case F_INVOKE_CUSTOM: case F_INVOKE_POLYMORPHIC:
            case F_CONST_METHOD_HANDLE: case F_CONST_METHOD_TYPE:
                return true;
            case F_BINARY_OP: {
                // Only INTEGER division and remainder can throw (ArithmeticException on a zero
                // divisor). Float and double div/rem produce NaN or an infinity and never throw,
                // and no other arithmetic op can fail, so giving them handler edges would only
                // blur the handler's entry types. Indices within the 32-op table: 3 = div-int,
                // 4 = rem-int, 14 = div-long, 15 = rem-long.
                int i = ((opcode & 0xFF) - 0x90) % 32;
                return i == 3 || i == 4 || i == 14 || i == 15;
            }
            case F_BINARY_OP_CONST: {
                // div-int/lit16, rem-int/lit16, div-int/lit8, rem-int/lit8.
                int op = opcode & 0xFF;
                return op == 0xd3 || op == 0xd4 || op == 0xdb || op == 0xdc;
            }
            default:
                return false;
        }
    }

    // Result scalar for each unary op, indexed by opcode - 0x7b. Source scalars are in UNARY_SRC.
    private static final int[] UNARY_DST = {
        DexType.INT, DexType.INT, DexType.LONG, DexType.LONG, DexType.FLOAT, DexType.DOUBLE,
        DexType.LONG, DexType.FLOAT, DexType.DOUBLE,
        DexType.INT, DexType.FLOAT, DexType.DOUBLE,
        DexType.INT, DexType.LONG, DexType.DOUBLE,
        DexType.INT, DexType.LONG, DexType.FLOAT,
        DexType.INT, DexType.INT, DexType.INT,
    };
    private static final int[] UNARY_SRC = {
        DexType.INT, DexType.INT, DexType.LONG, DexType.LONG, DexType.FLOAT, DexType.DOUBLE,
        DexType.INT, DexType.INT, DexType.INT,
        DexType.LONG, DexType.LONG, DexType.LONG,
        DexType.FLOAT, DexType.FLOAT, DexType.FLOAT,
        DexType.DOUBLE, DexType.DOUBLE, DexType.DOUBLE,
        DexType.INT, DexType.INT, DexType.INT,
    };

    /**
     * Result and operand scalars for the 32 binary ops, in DEX table order:
     * 11 int ops, 11 long ops, 5 float ops, 5 double ops. The 0xb0..0xcf "/2addr" block repeats
     * the same 32 in the same order, so the index is {@code (op - 0x90) % 32}.
     *
     * <p>The shift ops are the reason operand types are tracked separately from the result: a
     * {@code shl-long} produces a long but its shift amount is an INT, so emitting {@code lload}
     * for the second operand would be wrong.
     */
    private static final int[] BINARY_DST = {
        // add sub mul div rem and or xor shl shr ushr (int)
        DexType.INT, DexType.INT, DexType.INT, DexType.INT, DexType.INT, DexType.INT,
        DexType.INT, DexType.INT, DexType.INT, DexType.INT, DexType.INT,
        // long
        DexType.LONG, DexType.LONG, DexType.LONG, DexType.LONG, DexType.LONG, DexType.LONG,
        DexType.LONG, DexType.LONG, DexType.LONG, DexType.LONG, DexType.LONG,
        // float
        DexType.FLOAT, DexType.FLOAT, DexType.FLOAT, DexType.FLOAT, DexType.FLOAT,
        // double
        DexType.DOUBLE, DexType.DOUBLE, DexType.DOUBLE, DexType.DOUBLE, DexType.DOUBLE,
    };
    private static final int[] BINARY_SRC2 = {
        DexType.INT, DexType.INT, DexType.INT, DexType.INT, DexType.INT, DexType.INT,
        DexType.INT, DexType.INT, DexType.INT, DexType.INT, DexType.INT,
        DexType.LONG, DexType.LONG, DexType.LONG, DexType.LONG, DexType.LONG, DexType.LONG,
        DexType.LONG, DexType.LONG,
        // shl-long, shr-long, ushr-long take an INT shift distance
        DexType.INT, DexType.INT, DexType.INT,
        DexType.FLOAT, DexType.FLOAT, DexType.FLOAT, DexType.FLOAT, DexType.FLOAT,
        DexType.DOUBLE, DexType.DOUBLE, DexType.DOUBLE, DexType.DOUBLE, DexType.DOUBLE,
    };

    // cmpl-float, cmpg-float, cmpl-double, cmpg-double, cmp-long
    private static final int[] CMP_SRC = {
        DexType.FLOAT, DexType.FLOAT, DexType.DOUBLE, DexType.DOUBLE, DexType.LONG,
    };

    // ==================================================================
    // Input contracts
    // ==================================================================

    /** Constant pool lookups. Any method may return null for an index this DEX cannot resolve. */
    public interface Resolver {
        /** Type descriptor for a type index, e.g. {@code Ljava/lang/String;} or {@code [I}. */
        String typeDescriptor(int typeIndex);
        /** Field TYPE descriptor for a field index. */
        String fieldDescriptor(int fieldIndex);
        /** Internal name of the class declaring a field index, e.g. {@code com/x/Y}. */
        String fieldOwner(int fieldIndex);
        /** Return type descriptor for a method index, {@code V} for void. */
        String methodReturnDescriptor(int methodIndex);
        /**
         * Parameter descriptors for a method index, one entry per REGISTER WORD, with a null
         * entry after each long/double for its high half, and the receiver descriptor first when
         * {@code isStatic} is false. This is the same "spaced" convention as
         * {@link MethodInput#spacedParamDescriptors}.
         */
        String[] methodSpacedParamDescriptors(int methodIndex, boolean isStatic);
        /** Method name, needed only to recognise {@code <init>}. */
        String methodName(int methodIndex);
        /** Internal name of the class declaring a method index. */
        String methodOwner(int methodIndex);
        /**
         * Spaced parameter descriptors for a proto_ids index, no receiver.
         * invoke-polymorphic's real call signature is this proto, NOT the
         * referenced method's descriptor: MethodHandle.invokeExact is declared
         * ([Ljava/lang/Object;)Ljava/lang/Object; and every call site has its
         * own shape.
         */
        String[] protoSpacedParamDescriptors(int protoIndex);
        /** Spaced parameter descriptors of a call site's method type, no receiver. */
        String[] callSiteSpacedParamDescriptors(int callSiteIndex);
    }

    /** One catch clause. A null {@link #exceptionType} means catch-all (Dalvik's catch_all). */
    public static final class Catch {
        public final String exceptionType;
        public final int handlerOffset;

        public Catch(String exceptionType, int handlerOffset) {
            this.exceptionType = exceptionType;
            this.handlerOffset = handlerOffset;
        }
    }

    /** A try range, half open on the end as JVMS 4.10.1.6 isApplicableHandler specifies. */
    public static final class TryBlock {
        public final int startOffset;
        public final int endOffset;
        public final List<Catch> catches;

        public TryBlock(int startOffset, int endOffset, List<Catch> catches) {
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.catches = catches == null ? Collections.emptyList() : catches;
        }
    }

    /**
     * One decoded DEX instruction, in the enjarify {@code dalvikformats} normalisation.
     *
     * <p>{@link #args} conventions by family (all register numbers, indices and offsets as
     * {@code long} so that {@code const-wide} literals fit):
     * <pre>
     *   MOVE / MOVE_WIDE      [dest, src]
     *   MOVE_RESULT           [dest]                       plus {@link #moveResultDescriptor}
     *   RETURN                [] for return-void, else [src]
     *   CONST32 / CONST64     [dest, value]                value already sign extended / shifted
     *   CONST_STRING          [dest, stringIndex]
     *   CONST_CLASS           [dest, typeIndex]
     *   MONITOR_ENTER/EXIT    [reg]
     *   CHECK_CAST            [reg, typeIndex]
     *   INSTANCE_OF           [dest, src, typeIndex]
     *   ARRAY_LEN             [dest, arrayReg]
     *   NEW_INSTANCE          [dest, typeIndex]
     *   NEW_ARRAY             [dest, sizeReg, typeIndex]
     *   FILLED_NEW_ARRAY      [typeIndex]                  registers in {@link #registerList}
     *   FILL_ARRAY_DATA       [arrayReg, payloadOffset]
     *   THROW                 [reg]
     *   GOTO                  [absoluteTarget]
     *   SWITCH                [reg, payloadOffset]         targets in {@link #switchTargets}
     *   CMP                   [dest, src1, src2]
     *   IF                    [srcA, srcB, absoluteTarget]
     *   IFZ                   [src, absoluteTarget]
     *   ARRAY_GET             [dest, arrayReg, indexReg]
     *   ARRAY_PUT             [srcReg, arrayReg, indexReg]
     *   INSTANCE_GET          [dest, objReg, fieldIndex]
     *   INSTANCE_PUT          [srcReg, objReg, fieldIndex]
     *   STATIC_GET            [dest, fieldIndex]
     *   STATIC_PUT            [srcReg, fieldIndex]
     *   INVOKE_*              [methodIndex]                registers in {@link #registerList}
     *   UNARY_OP              [dest, src]
     *   BINARY_OP             [dest, src1, src2]  or, for the 0xb0..0xcf /2addr block, [destAndSrc1, src2]
     *   BINARY_OP_CONST       [dest, src, literal]
     * </pre>
     */
    public static final class Insn {
        /** Offset of this instruction, in 16-bit code units, as DEX branch targets measure. */
        public final int offset;
        /** Offset of the following instruction. Also the fallthrough successor. */
        public final int nextOffset;
        /** Raw DEX opcode byte. */
        public final int opcode;
        public final long[] args;
        /** Register list for invoke-kind and filled-new-array, else empty. */
        public final int[] registerList;
        /** Absolute targets of a packed/sparse switch, else empty. */
        public final int[] switchTargets;
        /**
         * For {@code move-result*}, the descriptor of the value being moved: the preceding
         * invoke's return type, the filled-new-array type, or {@code Ljava/lang/Throwable;} (or
         * the caught type) for {@code move-exception}. Null when the instruction is not a
         * move-result, or when no preceding producer was found.
         */
        public final String moveResultDescriptor;
        /**
         * ART narrows types through {@code instance-of} followed by a branch, in a way plain
         * Java casts cannot express. When the parser recognises that pattern it reports the type
         * index here and the affected registers in {@link #implicitCastRegisters}; the analysis
         * then marks those registers tainted so the code writer emits a defensive
         * {@code checkcast} at each use. -1 when absent, which is the safe default: the only
         * cost is a cast we did not need to make, or a ClassCastException surfacing later than
         * ART would have raised it.
         */
        public final int implicitCastTypeIndex;
        public final int[] implicitCastRegisters;

        public Insn(int offset, int nextOffset, int opcode, long[] args, int[] registerList,
                    int[] switchTargets, String moveResultDescriptor,
                    int implicitCastTypeIndex, int[] implicitCastRegisters) {
            this.offset = offset;
            this.nextOffset = nextOffset;
            this.opcode = opcode;
            this.args = args == null ? new long[0] : args;
            this.registerList = registerList == null ? new int[0] : registerList;
            this.switchTargets = switchTargets == null ? new int[0] : switchTargets;
            this.moveResultDescriptor = moveResultDescriptor;
            this.implicitCastTypeIndex = implicitCastTypeIndex;
            this.implicitCastRegisters =
                    implicitCastRegisters == null ? new int[0] : implicitCastRegisters;
        }

        /** Convenience constructor for the common case with no lists and no implicit cast. */
        public Insn(int offset, int nextOffset, int opcode, long[] args) {
            this(offset, nextOffset, opcode, args, null, null, null, -1, null);
        }

        public int family() { return FAMILY[opcode & 0xFF]; }
        public int arg(int i) { return (int) args[i]; }

        @Override public String toString() {
            return String.format("%04x: op=%02x %s", offset, opcode, Arrays.toString(args));
        }
    }

    /** Everything the analysis needs about the method under inference. */
    public static final class MethodInput {
        public final int registerCount;
        public final boolean isStatic;
        /** True for {@code <init>}, which makes the receiver start as uninitializedThis. */
        public final boolean isConstructor;
        public final String declaringClassInternalName;
        /** See {@link Resolver#methodSpacedParamDescriptors} for the "spaced" convention. */
        public final String[] spacedParamDescriptors;
        public final List<Insn> instructions;
        public final List<TryBlock> tryBlocks;
        /**
         * Give every instruction in a protected range an edge to its handlers
         * (the JVM rule) rather than only the throwing ones (the ART rule).
         * See computeHandlerTargets. Translator drops this to false and retries
         * the rare method where the JVM rule leaves a register undefined at
         * handler entry that the handler body then reads.
         *
         * Under Translator.NARROW_TRY (the default since 2026-09-26) it is false
         * for EVERY method, because the exception table then covers only the
         * throwing instructions and the ART rule is the JVM's rule too.
         */
        public boolean wideHandlerEdges = true;
        /**
         * With {@link #wideHandlerEdges} false: still give EVERY instruction in a
         * protected range an edge to the range's CATCH-ALL handler (the JVM rule
         * for that one clause), while typed catches keep the ART rule.
         *
         * Set by Translator for a method that holds a monitor, where the
         * catch-all row keeps the whole DEX range for HotSpot's JIT: its
         * monitor-pairing analysis (GenerateOopMap::do_exception_edge) needs a
         * catch_type 0 row over every bytecode that CAN trap while a lock is
         * held, and the `ldc`/`checkcast` the emitter produces for
         * non-throwing DEX instructions are such bytecodes. See
         * Translator.MONITOR_SAFE_TRY. The edges it adds are slot-only, exactly
         * as under the wide rule, because nothing actually throws from them.
         */
        public boolean catchAllWideEdges = false;
        /**
         * DEX offsets of {@code new-instance} instructions whose allocation the EMITTER defers to
         * the paired {@code invoke-direct <init>}, javac style:
         * {@code new C; dup; <args>; invokespecial C.<init>; astore slot(vX, OBJ)}.
         *
         * <p>The analysis must not write the uninitialized reference into the register's OBJ SLOT
         * for these sites, because no local holds it between the two instructions and a frame that
         * claims one is simply wrong. The VALUE view still tracks {@code uninitialized(offset)}
         * exactly as before, so the {@code <init>} substitution still fires and a genuine
         * use-before-init is still caught.
         *
         * <p>WHY THIS EXISTS. Keeping an uninitialized reference in a JVM local is what makes a
         * handler frame unsatisfiable. JVMS 4.10.1.2 gives {@code uninitialized(_)} exactly one
         * outgoing assignability chain -- {@code isAssignable(uninitialized, X) :-
         * isAssignable(reference, X)}, which reaches {@code oneWord} and then {@code top} and never
         * a class -- so once one instruction in a protected range holds {@code uninitialized(N)} in
         * a slot and another holds the initialized class there, the only type the handler frame can
         * name is {@code top}, and any handler body that reads the register then fails with "Type
         * top (current frame, locals[N]) is not assignable to reference type". 138 of the 233
         * remaining corpus-wide split-verifier errors are that one shape. javac never produces it
         * because it keeps the uninitialized value on the OPERAND STACK, which
         * {@code instructionSatisfiesHandler} discards ({@code TrueExcStackFrame = frame(Locals,
         * [ExceptionClass], Flags)}); this flag lets the emitter do the same.
         *
         * <p>Populated by the emitter side, which owns the guard conditions (a single consuming
         * {@code <init>}, no intervening read or copy of the register, no branch target in between,
         * same try-range coverage, no live earlier value clobbered) and falls back to the old shape
         * whenever one fails. Both shapes therefore occur in the same method and both are handled.
         */
        public java.util.Set<Integer> deferredNewSites = java.util.Set.of();
        public final Resolver resolver;
        /** Optional; null means every unequal reference merge widens to java/lang/Object. */
        public final DexType.ClassHierarchy hierarchy;
        /**
         * The method's declared return descriptor, or null if unknown.
         *
         * Load-bearing, not decorative: DEX has ONE opcode (0x0f) for every
         * 32-bit non-object return and one (0x10) for every 64-bit return, so
         * the opcode cannot say int from float or long from double. Only the
         * descriptor can. Getting it from the opcode alone made a
         * float-returning method load its register's int reading and freturn
         * it ("float_type is not assignable from integer_type").
         */
        public final String returnDescriptor;

        public MethodInput(int registerCount, boolean isStatic, boolean isConstructor,
                           String declaringClassInternalName, String[] spacedParamDescriptors,
                           List<Insn> instructions, List<TryBlock> tryBlocks,
                           Resolver resolver, DexType.ClassHierarchy hierarchy) {
            this(registerCount, isStatic, isConstructor, declaringClassInternalName,
                 spacedParamDescriptors, instructions, tryBlocks, resolver, hierarchy, null);
        }

        public MethodInput(int registerCount, boolean isStatic, boolean isConstructor,
                           String declaringClassInternalName, String[] spacedParamDescriptors,
                           List<Insn> instructions, List<TryBlock> tryBlocks,
                           Resolver resolver, DexType.ClassHierarchy hierarchy,
                           String returnDescriptor) {
            this.returnDescriptor = returnDescriptor;
            this.registerCount = registerCount;
            this.isStatic = isStatic;
            this.isConstructor = isConstructor;
            this.declaringClassInternalName = declaringClassInternalName;
            this.spacedParamDescriptors =
                    spacedParamDescriptors == null ? new String[0] : spacedParamDescriptors;
            this.instructions = instructions == null ? Collections.emptyList() : instructions;
            this.tryBlocks = tryBlocks == null ? Collections.emptyList() : tryBlocks;
            this.resolver = resolver;
            this.hierarchy = hierarchy;
        }
    }

    // ==================================================================
    // Output
    // ==================================================================

    /** A register read by an instruction, already resolved to ONE JVM scalar type. */
    public static final class Use {
        public final int register;
        /** A single {@link DexType} scalar bit, or {@link DexType#NONE} if no legal reading. */
        public final int scalar;
        public final DexType type;
        /**
         * When true the value is provably null, so the code writer should push a constant
         * ({@code aconst_null} or a zero) instead of loading the local. This matters because the
         * local may never have been written on this path.
         */
        public final boolean definitelyNull;
        /** Internal name or array descriptor to checkcast to before use, or null. */
        public final String checkCastTo;

        Use(int register, int scalar, DexType type, String checkCastTo) {
            this.register = register;
            this.scalar = scalar;
            this.type = type;
            this.definitelyNull = type.isDefinitelyNull() && scalar == DexType.OBJ;
            this.checkCastTo = checkCastTo;
        }

        @Override public String toString() {
            return "v" + register + ":" + DexType.scalarToString(scalar);
        }
    }

    /** A register written by an instruction. */
    public static final class Def {
        public final int register;
        public final DexType type;
        /**
         * The scalar readings the code writer must materialise. Because one Dalvik register maps
         * to one JVM local PER SCALAR (see {@link DexType}), a def whose type has several bits
         * set requires one store per bit. Iterate with {@link DexType#scalarBits}.
         */
        public final int scalars;
        public final boolean wide;

        Def(int register, DexType type) {
            this.register = register;
            this.type = type;
            this.scalars = type.scalar();
            this.wide = DexType.isWide(type.scalar());
        }

        @Override public String toString() {
            return "v" + register + "<-" + type;
        }
    }

    /** Everything the code writer needs for one instruction. */
    public static final class InsnTypes {
        public final int offset;
        /** Register types on entry. Never null for a reachable instruction. */
        public final RegisterState entry;
        /** Register types on fallthrough exit, or null when the instruction does not fall through. */
        public final RegisterState exit;
        public final Use[] uses;
        public final Def[] defs;

        InsnTypes(int offset, RegisterState entry, RegisterState exit, Use[] uses, Def[] defs) {
            this.offset = offset;
            this.entry = entry;
            this.exit = exit;
            this.uses = uses;
            this.defs = defs;
        }
    }

    /** A construct the analysis could not type precisely. Reported, never thrown. */
    public static final class Note {
        public final int offset;
        public final String kind;
        public final String detail;

        Note(int offset, String kind, String detail) {
            this.offset = offset;
            this.kind = kind;
            this.detail = detail;
        }

        @Override public String toString() {
            return String.format("%04x %s: %s", offset, kind, detail);
        }
    }

    /** The analysis result. Offsets are DEX code-unit offsets, matching {@link Insn#offset}. */
    public static final class Result {
        private final Map<Integer, RegisterState> entryStates;
        private final Map<Integer, InsnTypes> insnTypes;
        private final Map<Integer, int[]> handlerTargets;
        private final List<Note> notes;
        private final boolean converged;
        private final int passes;
        private final int registerCount;

        Result(Map<Integer, RegisterState> entryStates, Map<Integer, InsnTypes> insnTypes,
               Map<Integer, int[]> handlerTargets, List<Note> notes, boolean converged,
               int passes, int registerCount) {
            this.entryStates = entryStates;
            this.insnTypes = insnTypes;
            this.handlerTargets = handlerTargets;
            this.notes = notes;
            this.converged = converged;
            this.passes = passes;
            this.registerCount = registerCount;
        }

        /** Entry state at an offset, or null when the instruction is unreachable. */
        public RegisterState entryState(int offset) { return entryStates.get(offset); }

        /**
         * True when the instruction is reachable. UNREACHABLE INSTRUCTIONS MUST NOT BE EMITTED
         * with inferred types, because no type was inferred for them; emit an unconditional
         * throw or skip the block.
         */
        public boolean isReachable(int offset) { return entryStates.containsKey(offset); }

        public DexType typeAt(int offset, int register) {
            RegisterState s = entryStates.get(offset);
            return s == null ? DexType.DEAD : s.live(register);
        }

        /** Per-instruction uses and defs, or null when unreachable. */
        public InsnTypes typesFor(int offset) { return insnTypes.get(offset); }

        /** Offsets of exception handlers reachable from this instruction. Never null. */
        public int[] handlersFor(int offset) {
            int[] h = handlerTargets.get(offset);
            return h == null ? EMPTY_INT : h;
        }

        /** False when the pass cap was hit, which means the reported types are NOT a fixpoint. */
        public boolean converged() { return converged; }
        public int passes() { return passes; }
        public int registerCount() { return registerCount; }
        /** Constructs that could not be typed precisely. Empty is the normal case. */
        public List<Note> notes() { return notes; }
        public int reachableInstructionCount() { return entryStates.size(); }
    }

    private static final int[] EMPTY_INT = new int[0];

    /** Diagnostic only: name the methods that fell back to the ART exception-edge
     *  rule, which is where the residual handler-frame mismatches concentrate. */
    private static final boolean NARROW_EDGE_DEBUG =
            System.getenv("DEX2JVM_NARROW_DEBUG") != null;

    // ==================================================================
    // Driver
    // ==================================================================

    public static Result analyze(MethodInput m) {
        if (NARROW_EDGE_DEBUG && !m.wideHandlerEdges && !Translator.NARROW_TRY) {
            // The ART-rule retry (Translator.translate's catch block). Such a
            // method keeps handler frames that the JVM rule would have widened
            // to top, so it is the expected source of "Stack map does not match
            // the one at exception handler N". Under NARROW_TRY every method
            // takes the ART rule on purpose and there is no retry to name.
            System.err.println("[dex-narrow] " + m.declaringClassInternalName);
        }
        List<Insn> code = m.instructions;
        List<Note> notes = new ArrayList<>();
        Map<Integer, RegisterState> states = new HashMap<>();
        Map<Integer, int[]> handlerTargets = new HashMap<>();

        if (code.isEmpty()) {
            return new Result(states, new HashMap<>(), handlerTargets, notes, true, 0,
                    m.registerCount);
        }

        Map<Integer, Insn> byOffset = new HashMap<>();
        for (Insn insn : code) {
            byOffset.put(insn.offset, insn);
        }
        computeHandlerTargets(m, code, handlerTargets);
        java.util.Set<Integer> valueEdgeAlways = handlersWithNoThrowingPredecessor(code,
                handlerTargets);

        RegisterState entry = RegisterState.forParameters(
                m.registerCount, m.spacedParamDescriptors,
                m.isConstructor, m.isStatic, m.hierarchy);

        int firstOffset = code.get(0).offset;
        states.put(firstOffset, entry);

        BitSet dirty = new BitSet();
        Map<Integer, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < code.size(); i++) {
            indexOf.put(code.get(i).offset, i);
        }
        dirty.set(0);

        // Safety net only. The real bound is the lattice height times the register count; see the
        // class comment. Hitting this cap means the input is malformed or a transfer function is
        // not monotone, and the caller is told via Result.converged().
        int passCap = 64 + 16 * Math.max(1, m.registerCount);
        int passes = 0;
        boolean converged = false;

        while (!dirty.isEmpty()) {
            if (++passes > passCap) {
                notes.add(new Note(-1, "no-fixpoint",
                        "pass cap " + passCap + " exceeded with " + dirty.cardinality()
                                + " offsets still dirty"));
                break;
            }
            for (int i = 0; i < code.size(); i++) {
                if (!dirty.get(i)) {
                    continue;
                }
                dirty.clear(i);
                Insn insn = code.get(i);
                RegisterState cur = states.get(insn.offset);
                if (cur == null) {
                    continue;
                }
                propagate(m, insn, cur, states, handlerTargets, valueEdgeAlways, indexOf, dirty,
                        notes);
            }
            if (dirty.isEmpty()) {
                converged = true;
            }
        }

        Map<Integer, InsnTypes> insnTypes = new HashMap<>();
        for (Insn insn : code) {
            RegisterState st = states.get(insn.offset);
            if (st == null) {
                continue;
            }
            RegisterState exit = fallsThrough(insn.family())
                    ? transfer(m, insn, st, notes, false)
                    : null;
            insnTypes.put(insn.offset,
                    new InsnTypes(insn.offset, st, exit, computeUses(m, insn, st),
                            computeDefs(m, insn, st, notes)));
        }

        return new Result(states, insnTypes, handlerTargets, notes, converged, passes,
                m.registerCount);
    }

    /** Convenience entry point for callers that only want a yes/no plus the numbers. */
    public static Result analyzeQuietly(MethodInput m) {
        try {
            return analyze(m);
        } catch (RuntimeException e) {
            List<Note> notes = new ArrayList<>();
            notes.add(new Note(-1, "exception", e.getClass().getSimpleName() + ": " + e.getMessage()));
            return new Result(new HashMap<>(), new HashMap<>(), new HashMap<>(), notes, false, 0,
                    m.registerCount);
        }
    }

    private static void computeHandlerTargets(MethodInput m, List<Insn> code,
                                              Map<Integer, int[]> out) {
        if (m.tryBlocks.isEmpty()) {
            return;
        }
        boolean wide = m.wideHandlerEdges
                && !"0".equals(System.getenv("DEX2JVM_WIDE_HANDLERS"));
        for (Insn insn : code) {
            // Under the ART rule a non-throwing instruction still reaches the
            // catch-all handler when catchAllWideEdges says the table covers it
            // there; see that field. Every other clause stays throwing-only.
            boolean allClauses = wide || canThrow(insn.opcode);
            if (!allClauses && !m.catchAllWideEdges) continue;
            // NOT gated on canThrow. Dalvik and the JVM disagree here, and the
            // JVM's rule is the one the output has to satisfy.
            //
            // ART routes an exception edge only from instructions that can
            // actually throw. JVMS 4.10.1.6 instead requires, for EVERY
            // instruction in a protected range, that the handler's frame be
            // assignable from that instruction's frame
            // (instructionSatisfiesHandlers is applied per instruction in the
            // range, with no throwing-instruction filter). So a register first
            // assigned half way through the range is `top` at the top of the
            // range as far as the JVM is concerned, and a handler frame that
            // names a type there is rejected:
            //
            //   Stack map does not match the one at exception handler N
            //   Reason: Type top (current frame, locals[5]) is not assignable
            //           to 'java/io/InputStream'
            //
            // Widening this set is safe in the direction that matters: per the
            // canThrow doc, too FEW edges is unsound (the handler is told a
            // register holds an int when the throwing path had a float), while
            // too many only costs precision. Here the lost precision IS the
            // fix -- it is exactly the `top` the verifier expects.
            List<Integer> targets = null;
            for (TryBlock t : m.tryBlocks) {
                // Overlap test on the instruction's whole byte range rather than just its start,
                // because a try range can begin or end in the middle of a multi-unit instruction
                // in hand written or obfuscated DEX.
                if (t.startOffset >= insn.nextOffset || t.endOffset <= insn.offset) {
                    continue;
                }
                for (Catch c : t.catches) {
                    // EVERY catch of an overlapping try, with no early exit once
                    // a catch-all or a `catch Throwable` has been seen.
                    //
                    // Stopping there is what a RUNTIME dispatcher does -- ART
                    // tries the catches in order and a Throwable clause matches
                    // everything, so the ones behind it can never fire -- but the
                    // verifier is not a dispatcher. HotSpot's
                    // ClassVerifier::verify_exception_handler_targets
                    // (src/hotspot/share/classfile/verifier.cpp) walks the WHOLE
                    // exception table and checks every row whose [start_pc,end_pc)
                    // covers the bci, with no early exit on catch_type_index == 0:
                    //
                    //   for(int i = 0; i < exlength; i++) { ...
                    //     if(bci >= start_pc && bci < end_pc) { ...
                    //       if (!matches) { verify_error(ctx,
                    //          "Stack map does not match the one at "
                    //          "exception handler %d", handler_pc); return; } } }
                    //
                    // Translator.emitHandlers writes a row for every catch, so
                    // dropping the edge here left those rows' frames computed
                    // from a SMALLER set of predecessors than the verifier uses:
                    //
                    //   com/applovin/impl/sdk/b/e.a(...) -- try [0x4,0xb3) has
                    //   `catch Throwable -> 0xc1` followed by `catch_all -> 0xbf`.
                    //   0xbf then took its frame only from the second try
                    //   [0xc4,0xde), where the register holds the caught
                    //   Throwable, so the frame claimed locals[10]='Throwable'
                    //   while bci 69 of the first range holds a 'java/lang/Class'
                    //   there -- "Type 'java/lang/Class' is not assignable to
                    //   'java/lang/Throwable' (stack map, locals[10])".
                    //
                    // Too many edges only costs precision; too few is a wrong
                    // frame. See the block comment above.
                    if (!allClauses && c.exceptionType != null) continue;
                    if (targets == null) {
                        targets = new ArrayList<>();
                    }
                    if (!targets.contains(c.handlerOffset)) {
                        targets.add(c.handlerOffset);
                    }
                }
            }
            if (targets != null) {
                int[] arr = new int[targets.size()];
                for (int i = 0; i < arr.length; i++) {
                    arr[i] = targets.get(i);
                }
                out.put(insn.offset, arr);
            }
        }
    }

    /**
     * Handlers no instruction in their protected range can actually throw into.
     *
     * <p>Such a handler is unreachable at run time, but the class file still carries an exception
     * table row for it and the verifier still demands a frame at its target. So it gets the
     * ordinary treatment: every in-range instruction contributes to its VALUE state as well as its
     * slot state. Without this a hand-written or obfuscated try range containing only
     * non-throwing instructions would leave the handler with no incoming value edge at all, hence
     * no entry state, hence no frame -- "Expecting a stackmap frame at branch target".
     */
    private static java.util.Set<Integer> handlersWithNoThrowingPredecessor(
            List<Insn> code, Map<Integer, int[]> handlerTargets) {
        if (handlerTargets.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        java.util.Set<Integer> all = new java.util.HashSet<>();
        java.util.Set<Integer> reachedByThrow = new java.util.HashSet<>();
        for (Insn insn : code) {
            int[] hs = handlerTargets.get(insn.offset);
            if (hs == null) continue;
            boolean throwing = canThrow(insn.opcode);
            for (int h : hs) {
                all.add(h);
                if (throwing) reachedByThrow.add(h);
            }
        }
        all.removeAll(reachedByThrow);
        return all;
    }

    /**
     * The receiver register of an {@code invoke-direct <init>} that completes a DEFERRED
     * allocation, or -1.
     *
     * <p>No extra plumbing is needed to pair the two instructions: {@code Ref.newOffset} already
     * carries the DEX offset of the {@code new-instance} that produced the value, so membership in
     * {@link MethodInput#deferredNewSites} is one lookup away from the receiver's own type.
     */
    private static int deferredInitReceiver(MethodInput m, Insn insn, RegisterState cur) {
        if (insn.family() != F_INVOKE_DIRECT || m.deferredNewSites.isEmpty()
                || insn.registerList.length == 0
                || !"<init>".equals(safeMethodName(m, insn.arg(0)))) {
            return -1;
        }
        int receiver = insn.registerList[0];
        DexType.Ref r = cur.live(receiver).ref();
        return (r.kind == DexType.Ref.KIND_UNINIT && m.deferredNewSites.contains(r.newOffset))
                ? receiver : -1;
    }

    private static void propagate(MethodInput m, Insn insn, RegisterState cur,
                                  Map<Integer, RegisterState> states,
                                  Map<Integer, int[]> handlerTargets,
                                  java.util.Set<Integer> valueEdgeAlways,
                                  Map<Integer, Integer> indexOf, BitSet dirty,
                                  List<Note> notes) {
        int family = insn.family();
        RegisterState after = transfer(m, insn, cur, notes, true);

        // Exception edges carry the PRE-state. See the class comment; this is a correctness rule,
        // not an optimisation.
        //
        // THE TWO EDGE RULES ARE DIFFERENT AND BOTH ARE RIGHT. ART routes a handler edge only from
        // an instruction that can throw, and for the VALUE view that is simply the truth: nothing
        // else can transfer control to the handler, so nothing else can say anything about what
        // the handler body will find in a register. JVMS 4.10.1.6 instead applies
        // instructionSatisfiesHandlers to every instruction whose offset lies in the range --
        //
        //   isApplicableHandler(Offset, handler(Start, End, _Target, _ClassName)) :-
        //       Offset >= Start,
        //       Offset < End.
        //
        // -- with no throwing filter, and HotSpot implements exactly that
        // (verifier.cpp verify_exception_handler_targets: `if (bci >= start_pc && bci < end_pc)`).
        // So for the SLOT view, which is what a frame declares, every in-range instruction counts.
        //
        // Applying the JVM rule to BOTH is what the tree did until now, and it is what produced
        // "TranslationException: no local for vN scalar none": a `return-void` in the range whose
        // register meet is empty made the register dead at the handler, and the handler body then
        // could not read the monitor it is there to release. Translator caught that and retried the
        // whole method under ART's rule, which fixed the value view but ALSO narrowed the slot
        // view, so the handler frame was computed from fewer predecessors than HotSpot uses and
        // failed as "Type 'T' (current frame, locals[N]) is not assignable to 'T' (stack map)".
        // Splitting the rules removes both symptoms at once.
        int[] handlers = handlerTargets.get(insn.offset);
        if (handlers != null) {
            boolean throwing = canThrow(insn.opcode);
            // HOTSPOT CHECKS THE HANDLER AGAINST THE POST-STATE, NOT THE PRE-STATE, AND THE SPEC
            // SAYS OTHERWISE. JVMS 4.10.1.6 derives ExceptionStackFrame from the incoming frame
            // ("The type state after an instruction completes abruptly is the same as the incoming
            // type state, except that the operand stack is empty"), but verifier.cpp runs the check
            // at the BOTTOM of its per-bytecode loop, after the opcode has already been applied to
            // current_frame, and says why:
            //
            //   // Don't do this check if it has already been done (for
            //   // ([a,d,f,i,l]store* opcodes).  This check cannot be done earlier because
            //   // opcodes, such as invokespecial, may set the this_uninit flag.
            //   if (!verified_exc_handlers && bci >= ex_min && bci < ex_max) {
            //     verify_exception_handler_targets(bci, this_uninit, &current_frame, ...);
            //
            // Only the *store-into-local opcodes get the pre-state, from the earlier call site.
            //
            // That matters here because ONE Dalvik instruction becomes SEVERAL JVM instructions, so
            // the post-state is a program point inside the protected range even when the DEX
            // instruction is the last one in it. The visible case is `invoke-direct <init>`: JVMS
            // 4.10.1.9 substitutes uninitialized(N) with the class in the locals, HotSpot performs
            // that substitution and only then checks the handlers, and our handler frame -- built
            // from the pre-state alone -- still claimed the uninitialized type:
            //
            //   org/fdroid/fdroid/data/DBHelper.prePopulateDb @614: astore
            //   Type 'org/fdroid/database/InitialRepository' (current frame, locals[4]) is not
            //   assignable to uninitialized 327 (stack map, locals[4])
            //
            // Joining BOTH states covers every intermediate one too, because each slot in an
            // intermediate frame holds either its old or its new value. Slots only: control cannot
            // actually arrive at the handler carrying post-state VALUES, so widening the value view
            // with them would only cost precision in the handler body.
            //
            // The DESTINATION register is excluded, because that is the half HotSpot reads from the
            // pre-state: its slot is written by the astore this instruction ends with, and an
            // astore takes the earlier call site. Excluding it also makes this a no-op for every
            // ordinary def, so only the two instructions that retype OTHER registers pay -- the
            // <init> substitution and new-instance's stale-allocation wipe (JVMS 4.10.1.9).
            RegisterState postAll = after;
            int destArg = destinationRegister(family);
            if (destArg >= 0 && insn.args.length > destArg) {
                postAll = postAll.withSlotFrom(insn.arg(destArg), cur);
            }
            int deferredReceiver = deferredInitReceiver(m, insn, cur);
            if (deferredReceiver >= 0) {
                // Same carve-out, for the other instruction that writes a slot with a trailing
                // astore. A deferred <init> emits `new C; dup; <args>; invokespecial; astore
                // slot(vX, OBJ)`, so within its expansion the slot still holds its OLD value at
                // every bci HotSpot checks: the astore is a store-into-local, which takes the
                // earlier, PRE-state call site. Merging the initialized type here would widen the
                // handler frame for a program point that does not exist.
                postAll = postAll.withSlotFrom(deferredReceiver, cur);
            }
            RegisterState post = postAll.sameSlotsAs(cur) ? null : postAll.slotsOnly();
            RegisterState curSlots = null;
            for (int h : handlers) {
                boolean valueEdge = throwing || valueEdgeAlways.contains(h);
                if (!valueEdge && curSlots == null) {
                    curSlots = cur.slotsOnly();
                }
                mergeInto(h, valueEdge ? cur : curSlots, states, indexOf, dirty);
                if (post != null) {
                    mergeInto(h, post, states, indexOf, dirty);
                }
            }
        }

        // ART's implicit casts narrow a register only on ONE side of the branch. For if-nez the
        // narrowed value flows down the TAKEN edge (the value was non-null, so instance-of held);
        // for if-eqz it flows down the fallthrough. Applying it to both sides would emit a
        // checkcast on a path where the type was never established.
        RegisterState taken = after;
        RegisterState fall = after;
        if (insn.implicitCastTypeIndex >= 0 && insn.implicitCastRegisters.length > 0) {
            String desc = safeTypeDescriptor(m, insn.implicitCastTypeIndex);
            RegisterState narrowed = after;
            for (int reg : insn.implicitCastRegisters) {
                DexType n = narrowed.live(reg).narrowArrayTo(desc);
                narrowed = narrowed.taint(reg, n.arrayKind(), n.arrayDescriptor());
            }
            if (insn.opcode == 0x39) { // if-nez
                taken = narrowed;
            } else {
                fall = narrowed;
            }
        }

        switch (family) {
            case F_GOTO:
                mergeInto(insn.arg(0), taken, states, indexOf, dirty);
                return;
            case F_IF:
                mergeInto(insn.arg(2), taken, states, indexOf, dirty);
                break;
            case F_IFZ:
                mergeInto(insn.arg(1), taken, states, indexOf, dirty);
                break;
            case F_SWITCH:
                for (int t : insn.switchTargets) {
                    mergeInto(t, after, states, indexOf, dirty);
                }
                break;
            default:
                break;
        }

        if (fallsThrough(family)) {
            mergeInto(insn.nextOffset, fall, states, indexOf, dirty);
        }
    }

    private static boolean fallsThrough(int family) {
        return family != F_RETURN && family != F_THROW && family != F_GOTO;
    }

    private static void mergeInto(int offset, RegisterState incoming,
                                  Map<Integer, RegisterState> states,
                                  Map<Integer, Integer> indexOf, BitSet dirty) {
        Integer idx = indexOf.get(offset);
        if (idx == null) {
            // A branch into the middle of an instruction or past the end. Real DEX does not do
            // this; obfuscated DEX occasionally does. Dropping the edge is the only safe option,
            // and it cannot make a reachable instruction lose a type it would otherwise have.
            return;
        }
        RegisterState old = states.get(offset);
        if (old == null) {
            states.put(offset, incoming);
            dirty.set(idx);
            return;
        }
        RegisterState merged = old.merge(incoming);
        // Identity, not equality: RegisterState.merge contracts to return the receiver when the
        // merge added nothing. Comparing with equals here would re-queue forever.
        if (merged != old) {
            states.put(offset, merged);
            dirty.set(idx);
        }
    }

    // ==================================================================
    // Transfer function
    // ==================================================================

    private static RegisterState transfer(MethodInput m, Insn insn, RegisterState cur,
                                          List<Note> notes, boolean recordNotes) {
        int family = insn.family();
        Resolver r = m.resolver;
        switch (family) {
            case F_MOVE:
                return cur.move(insn.arg(0), insn.arg(1), false);
            case F_MOVE_WIDE:
                return cur.move(insn.arg(0), insn.arg(1), true);
            case F_MOVE_RESULT: {
                String desc = insn.moveResultDescriptor;
                if (desc == null) {
                    if (recordNotes) {
                        notes.add(new Note(insn.offset, "move-result-no-producer",
                                "no preceding invoke or filled-new-array supplied a descriptor"));
                    }
                    return cur.set(insn.arg(0), DexType.DEAD);
                }
                return cur.setFromDescriptor(insn.arg(0), desc);
            }
            case F_CONST32: {
                long val = insn.args.length > 1 ? (insn.args[1] & 0xFFFFFFFFL) : 0L;
                // A zero literal is simultaneously int 0, float 0.0f and null. Keeping all three
                // readings alive is what lets the same register later feed either an iadd or an
                // aload without a cast, and it is the single most load bearing case in the whole
                // lattice: collapsing it to int here silently breaks every `if (x == null)` that
                // Dalvik compiled as a comparison against a const-zero register.
                return cur.set(insn.arg(0), val == 0 ? DexType.ZERO_TYPE : DexType.CONST32_TYPE);
            }
            case F_CONST64:
                return cur.setWide(insn.arg(0), DexType.CONST64_TYPE);
            case F_CONST_STRING:
                return cur.set(insn.arg(0), DexType.objectOfClass("java/lang/String"));
            case F_CONST_CLASS:
                return cur.set(insn.arg(0), DexType.objectOfClass("java/lang/Class"));
            case F_CONST_METHOD_HANDLE:
                return cur.set(insn.arg(0),
                        DexType.objectOfClass("java/lang/invoke/MethodHandle"));
            case F_CONST_METHOD_TYPE:
                return cur.set(insn.arg(0),
                        DexType.objectOfClass("java/lang/invoke/MethodType"));
            case F_INSTANCE_OF:
            case F_ARRAY_LEN:
            case F_CMP:
            case F_BINARY_OP_CONST:
                return cur.set(insn.arg(0), DexType.INT_TYPE);
            case F_CHECK_CAST: {
                String desc = safeTypeDescriptor(m, insn.arg(1));
                // A cast SUPPLIES information rather than joining two paths, so the array state is
                // intersected, not merged. narrowArrayTo also keeps the right DESCRIPTOR: casting
                // a [[B to Object[] must not relabel it as an Object array. Taint clears because
                // an explicit checkcast is exactly the proof ART's implicit narrowing lacked.
                DexType narrowed = cur.live(insn.arg(0)).narrowArrayTo(desc);
                DexType t = DexType.of(DexType.OBJ, narrowed.arrayKind(),
                        narrowed.arrayDescriptor(),
                        DexType.refFromDescriptor(desc), false, false);
                return cur.set(insn.arg(0), t);
            }
            case F_NEW_ARRAY: {
                String desc = safeTypeDescriptor(m, insn.arg(2));
                int kind = DexType.arrayKindFromDescriptor(desc);
                return cur.set(insn.arg(0), DexType.of(DexType.OBJ, kind,
                        kind == DexType.ARRAY_EXACT ? desc : null,
                        DexType.refFromDescriptor(desc), false, false));
            }
            case F_NEW_INSTANCE: {
                String desc = safeTypeDescriptor(m, insn.arg(1));
                DexType.Ref fresh = DexType.Ref.uninitialized(insn.offset);
                // JVMS 4.10.1.9 (new): wipe any stale value carrying this same allocation offset
                // before creating the fresh one, so a loop body's allocations stay distinct.
                RegisterState s = cur.substituteRef(fresh, DexType.DEAD);
                String name = desc != null && desc.startsWith("L") && desc.endsWith(";")
                        ? desc.substring(1, desc.length() - 1) : desc;
                DexType t = DexType.of(DexType.OBJ, DexType.ARRAY_UNKNOWN, null, fresh, false, false);
                if (name == null && recordNotes) {
                    notes.add(new Note(insn.offset, "new-instance-unresolved-type",
                            "type index " + insn.arg(1)));
                }
                // At a deferred site the emitter produces NO bytecode here and keeps the
                // uninitialized reference on the operand stack until the paired <init>, so the
                // register's OBJ slot is untouched. Claiming uninitialized(offset) for it would put
                // a type in the frame that no local holds, which is exactly the thing deferring is
                // for. The VALUE still takes it, so the substitution below still fires.
                return m.deferredNewSites.contains(insn.offset)
                        ? s.setKeepingSlot(insn.arg(0), t)
                        : s.set(insn.arg(0), t);
            }
            case F_ARRAY_GET: {
                DexType arr = cur.live(insn.arg(1));
                if (arr.arrayKind() == DexType.ARRAY_NULL) {
                    // Provably a null array, so this instruction always throws and everything
                    // after it on this path is unreachable. ANY_TYPE is the identity for the
                    // scalar meet, so it cannot pollute a genuinely reachable merge.
                    return cur.set(insn.arg(0), DexType.ANY_TYPE);
                }
                DexType elem = arr.arrayElement();
                // DELIBERATE, MEASURED DIVERGENCE from the reference implementation. enjarify
                // uses a narrow assign here, so after `aget-wide v6, ...` it leaves v7 claiming
                // whatever it held before, even though Dalvik's aget-wide defines the register
                // PAIR. That is an inconsistency in the reference rather than a decision: every
                // other wide-producing instruction there (const-wide, move-result-wide, field
                // reads, wide unary/binary results) does mark the high half.
                //
                // This is the ONLY behavioural difference between this implementation and the
                // reference. Verified by building both and diffing: with this branch removed the
                // two agree on 100 percent of register slots across F-Droid, unciv, ShatteredPD,
                // bouncy, flappy, OpenGD and RVTest; with it in place the difference is confined
                // to high-half registers and causes ZERO untypeable uses across all of them. Keep
                // the stricter behaviour because a StackMapTable writer keys off the high-half
                // marker, and a stale one there describes a local that no longer exists.
                if (DexType.isWide(elem.scalar())) {
                    return cur.setWide(insn.arg(0), elem);
                }
                return cur.set(insn.arg(0), elem);
            }
            case F_INSTANCE_GET: {
                String desc = safeFieldDescriptor(m, insn.arg(2));
                if (desc == null) {
                    if (recordNotes) {
                        notes.add(new Note(insn.offset, "unresolved-field", "index " + insn.arg(2)));
                    }
                    return cur.set(insn.arg(0), DexType.DEAD);
                }
                return cur.setFromDescriptor(insn.arg(0), desc);
            }
            case F_STATIC_GET: {
                String desc = safeFieldDescriptor(m, insn.arg(1));
                if (desc == null) {
                    if (recordNotes) {
                        notes.add(new Note(insn.offset, "unresolved-field", "index " + insn.arg(1)));
                    }
                    return cur.set(insn.arg(0), DexType.DEAD);
                }
                return cur.setFromDescriptor(insn.arg(0), desc);
            }
            case F_UNARY_OP: {
                int st = UNARY_DST[(insn.opcode & 0xFF) - 0x7b];
                DexType t = scalarType(st);
                return DexType.isWide(st) ? cur.setWide(insn.arg(0), t) : cur.set(insn.arg(0), t);
            }
            case F_BINARY_OP: {
                int st = BINARY_DST[((insn.opcode & 0xFF) - 0x90) % 32];
                DexType t = scalarType(st);
                return DexType.isWide(st) ? cur.setWide(insn.arg(0), t) : cur.set(insn.arg(0), t);
            }
            case F_INVOKE_DIRECT: {
                // A constructor call initializes EVERY register holding that same uninitialized
                // reference, not only the receiver. See RegisterState.substituteRef.
                if (r != null && insn.registerList.length > 0
                        && "<init>".equals(safeMethodName(m, insn.arg(0)))) {
                    int receiver = insn.registerList[0];
                    DexType.Ref ref = cur.live(receiver).ref();
                    if (ref.isUninitialized()) {
                        // For uninitializedThis the resulting type is the class being CONSTRUCTED,
                        // not the class whose <init> was invoked. A constructor chaining to
                        // super() names the superclass as the callee owner, so using the owner
                        // there would retype `this` as its own superclass and lose every field
                        // and method the subclass adds. JVMS 4.10.1.6 starts `this` as
                        // uninitializedThis for the declaring class, and that is what it becomes.
                        String owner = ref.kind == DexType.Ref.KIND_UNINIT_THIS
                                ? m.declaringClassInternalName
                                : safeMethodOwner(m, insn.arg(0));
                        DexType initialized = DexType.objectOfClass(
                                owner != null ? owner : DexType.OBJECT_NAME);
                        return cur.substituteRef(ref, initialized);
                    }
                }
                return cur;
            }
            default:
                // Nop, return, throw, goto, switch, if, ifz, monitor, filled-new-array,
                // fill-array-data, array-put, instance-put, static-put and the invokes all leave
                // every register unchanged. Invoke results arrive via the following move-result.
                return cur;
        }
    }

    private static DexType scalarType(int scalar) {
        switch (scalar) {
            case DexType.INT: return DexType.INT_TYPE;
            case DexType.FLOAT: return DexType.FLOAT_TYPE;
            case DexType.LONG: return DexType.LONG_TYPE;
            case DexType.DOUBLE: return DexType.DOUBLE_TYPE;
            default: return DexType.DEAD;
        }
    }

    // ==================================================================
    // Use / def extraction
    // ==================================================================

    private static Use[] computeUses(MethodInput m, Insn insn, RegisterState st) {
        int family = insn.family();
        List<Use> uses = new ArrayList<>(4);
        switch (family) {
            case F_MOVE:
            case F_MOVE_WIDE:
                // A move copies EVERY live reading, so the code writer emits one load/store pair
                // per set bit rather than picking a single type. Reported as one Use per bit.
                for (int bit : DexType.scalarBits(st.live(insn.arg(1)).scalar())) {
                    uses.add(new Use(insn.arg(1), bit, st.live(insn.arg(1)), null));
                }
                break;
            case F_RETURN:
                if (insn.args.length > 0) {
                    // The declared return type is a consumer like any other, so it gets the same
                    // treatment as an invoke operand (see needsNarrowingCast): if the reference we
                    // are about to areturn is not PROVABLY assignable to it, re-establish the type
                    // the DEX already proved with a checkcast. This used to pass null and was the
                    // last reference use with no cast target, which is why widening a merge showed
                    // up as
                    //   Bad return type
                    //   Type 'T' (current frame, stack[0]) is not assignable to 'T' (from method
                    //   signature)
                    // rather than being absorbed the way an invoke argument is.
                    uses.add(pick(m.hierarchy, st, insn.arg(0),
                            preferredForReturn(insn.opcode, m.returnDescriptor),
                            castTargetForDescriptor(m.returnDescriptor)));
                }
                break;
            case F_MONITOR_ENTER:
            case F_MONITOR_EXIT:
                uses.add(pick(m.hierarchy, st, insn.arg(0), DexType.OBJ, null));
                break;
            case F_THROW:
                uses.add(pick(m.hierarchy, st, insn.arg(0), DexType.OBJ, "java/lang/Throwable"));
                break;
            case F_CHECK_CAST:
                uses.add(pick(m.hierarchy, st, insn.arg(0), DexType.OBJ, null));
                break;
            case F_INSTANCE_OF:
                uses.add(pick(m.hierarchy, st, insn.arg(1), DexType.OBJ, null));
                break;
            case F_ARRAY_LEN:
                uses.add(pick(m.hierarchy, st, insn.arg(1), DexType.OBJ,
                        arrayCastTarget(st.live(insn.arg(1)))));
                break;
            case F_NEW_ARRAY:
                uses.add(pick(m.hierarchy, st, insn.arg(1), DexType.INT, null));
                break;
            case F_FILL_ARRAY_DATA:
                uses.add(pick(m.hierarchy, st, insn.arg(0), DexType.OBJ,
                        arrayCastTarget(st.live(insn.arg(0)))));
                break;
            case F_SWITCH:
                uses.add(pick(m.hierarchy, st, insn.arg(0), DexType.INT, null));
                break;
            case F_CMP: {
                int sc = CMP_SRC[(insn.opcode & 0xFF) - 0x2d];
                uses.add(pick(m.hierarchy, st, insn.arg(1), sc, null));
                uses.add(pick(m.hierarchy, st, insn.arg(2), sc, null));
                break;
            }
            case F_IF: {
                // Both operands must be compared as the SAME JVM type, so the choice is driven by
                // the intersection of what each side can be read as. Two registers that are both
                // `const 0` are legal as either if_icmpeq or if_acmpeq; preferring int keeps the
                // common integer comparison cheap.
                int both = st.live(insn.arg(0)).scalar() & st.live(insn.arg(1)).scalar();
                int sc = (both & DexType.INT) != 0 ? DexType.INT : DexType.OBJ;
                uses.add(pick(m.hierarchy, st, insn.arg(0), sc, null));
                uses.add(pick(m.hierarchy, st, insn.arg(1), sc, null));
                break;
            }
            case F_IFZ: {
                int sc = (st.live(insn.arg(0)).scalar() & DexType.INT) != 0
                        ? DexType.INT : DexType.OBJ;
                uses.add(pick(m.hierarchy, st, insn.arg(0), sc, null));
                break;
            }
            case F_ARRAY_GET: {
                DexType arr = st.live(insn.arg(1));
                uses.add(pick(m.hierarchy, st, insn.arg(1), DexType.OBJ, arrayCastTarget(arr)));
                uses.add(pick(m.hierarchy, st, insn.arg(2), DexType.INT, null));
                break;
            }
            case F_ARRAY_PUT: {
                DexType arr = st.live(insn.arg(1));
                uses.add(pick(m.hierarchy, st, insn.arg(1), DexType.OBJ, arrayCastTarget(arr)));
                uses.add(pick(m.hierarchy, st, insn.arg(2), DexType.INT, null));
                // elem.scalar() is always a single bit here (it comes from a descriptor), which
                // is what pick() expects as its preference.
                DexType elem = arr.arrayElement();
                uses.add(pick(m.hierarchy, st, insn.arg(0), elem.scalar(), null));
                break;
            }
            case F_INSTANCE_GET:
                uses.add(pick(m.hierarchy, st, insn.arg(1), DexType.OBJ,
                        safeFieldOwner(m, insn.arg(2))));
                break;
            case F_INSTANCE_PUT: {
                uses.add(pick(m.hierarchy, st, insn.arg(1), DexType.OBJ,
                        safeFieldOwner(m, insn.arg(2))));
                String desc = safeFieldDescriptor(m, insn.arg(2));
                uses.add(pick(m.hierarchy, st, insn.arg(0), DexType.scalarFromDescriptor(desc),
                        castTargetForDescriptor(desc)));
                break;
            }
            case F_STATIC_PUT: {
                String desc = safeFieldDescriptor(m, insn.arg(1));
                uses.add(pick(m.hierarchy, st, insn.arg(0), DexType.scalarFromDescriptor(desc),
                        castTargetForDescriptor(desc)));
                break;
            }
            case F_UNARY_OP:
                uses.add(pick(m.hierarchy, st, insn.arg(1),
                        UNARY_SRC[(insn.opcode & 0xFF) - 0x7b], null));
                break;
            case F_BINARY_OP: {
                int idx = ((insn.opcode & 0xFF) - 0x90) % 32;
                boolean twoAddr = (insn.opcode & 0xFF) >= 0xb0;
                int src1 = twoAddr ? insn.arg(0) : insn.arg(1);
                int src2 = twoAddr ? insn.arg(1) : insn.arg(2);
                uses.add(pick(m.hierarchy, st, src1, BINARY_DST[idx], null));
                uses.add(pick(m.hierarchy, st, src2, BINARY_SRC2[idx], null));
                break;
            }
            case F_BINARY_OP_CONST:
                uses.add(pick(m.hierarchy, st, insn.arg(1), DexType.INT, null));
                break;
            case F_FILLED_NEW_ARRAY: {
                String desc = safeTypeDescriptor(m, insn.arg(0));
                int kind = DexType.arrayKindFromDescriptor(desc);
                DexType arrType = DexType.of(DexType.OBJ, kind,
                        kind == DexType.ARRAY_EXACT ? desc : null,
                        DexType.refFromDescriptor(desc), false, false);
                DexType elem = arrType.arrayElement();
                for (int reg : insn.registerList) {
                    uses.add(pick(m.hierarchy, st, reg, elem.scalar(), null));
                }
                break;
            }
            case F_INVOKE_VIRTUAL:
            case F_INVOKE_SUPER:
            case F_INVOKE_DIRECT:
            case F_INVOKE_STATIC:
            case F_INVOKE_INTERFACE: {
                boolean isStatic = family == F_INVOKE_STATIC;
                String[] params = safeParams(m, insn.arg(0), isStatic);
                if (params == null) {
                    break;
                }
                int n = Math.min(params.length, insn.registerList.length);
                for (int i = 0; i < n; i++) {
                    String desc = params[i];
                    if (desc == null) {
                        // High half of a wide argument: not loaded independently.
                        continue;
                    }
                    uses.add(pick(m.hierarchy, st, insn.registerList[i],
                            DexType.scalarFromDescriptor(desc), castTargetForDescriptor(desc)));
                }
                break;
            }
            case F_INVOKE_CUSTOM: {
                // The call site's method type IS the invokedynamic descriptor,
                // and there is no receiver: an indy call site is always static
                // from the caller's point of view.
                addArgumentUses(m.hierarchy, uses, st, insn,
                        safeCallSiteParams(m, (int) insn.arg(0)));
                break;
            }
            case F_INVOKE_POLYMORPHIC: {
                // arg(1) is the PROTO operand, which carries the call site's
                // real signature; the referenced method (MethodHandle.invoke,
                // invokeExact, VarHandle.get, ...) is signature-polymorphic and
                // its own descriptor says nothing about the arguments. The
                // receiver is still the first register.
                String[] proto = safeProtoParams(m, (int) insn.arg(1));
                if (proto == null) break;
                String[] params = new String[proto.length + 1];
                // The receiver is the REFERENCED METHOD'S OWNER, not always
                // MethodHandle. VarHandle.get/set/compareAndSet are equally
                // signature-polymorphic, and hardcoding MethodHandle here made
                // the consumer type wrong for every VarHandle call. That was
                // invisible until the narrowing-cast rule became
                // hierarchy-aware, at which point it started emitting
                // `checkcast java/lang/invoke/MethodHandle` onto a VarHandle:
                //
                //   ClassCastException: class
                //   java.lang.invoke.VarHandleInts$FieldInstanceReadWrite cannot
                //   be cast to class java.lang.invoke.MethodHandle
                //
                // Found by the semantic suite (tests/semantic, Reflect case); no
                // structural gate can see it, because the class file is well formed
                // and only the RUNTIME type is wrong.
                String polyOwner = safeMethodOwner(m, (int) insn.arg(0));
                params[0] = polyOwner != null
                        ? "L" + polyOwner + ";"
                        : "Ljava/lang/invoke/MethodHandle;";
                System.arraycopy(proto, 0, params, 1, proto.length);
                addArgumentUses(m.hierarchy, uses, st, insn, params);
                break;
            }
            default:
                break;
        }
        return uses.toArray(new Use[0]);
    }

    private static void addArgumentUses(DexType.ClassHierarchy hierarchy, List<Use> uses,
                                        RegisterState st, Insn insn, String[] params) {
        if (params == null) return;
        int n = Math.min(params.length, insn.registerList.length);
        for (int i = 0; i < n; i++) {
            String desc = params[i];
            if (desc == null) continue;   // high half of a wide argument
            uses.add(pick(hierarchy, st, insn.registerList[i],
                    DexType.scalarFromDescriptor(desc), castTargetForDescriptor(desc)));
        }
    }

    private static String[] safeProtoParams(MethodInput m, int protoIndex) {
        try {
            return m.resolver == null ? null : m.resolver.protoSpacedParamDescriptors(protoIndex);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String[] safeCallSiteParams(MethodInput m, int callSiteIndex) {
        try {
            return m.resolver == null ? null
                    : m.resolver.callSiteSpacedParamDescriptors(callSiteIndex);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The register an instruction writes, determined SYNTACTICALLY from its opcode, or -1.
     *
     * <p>This must not be derived by diffing the before and after states. A {@code const/4 v0, 0}
     * whose destination already held the same type produces no state change at all, yet it very
     * much does define v0 and the code writer must emit the store. Diffing would silently drop
     * exactly the redundant-looking assignments that keep a loop's register live.
     */
    private static int destinationRegister(int family) {
        switch (family) {
            case F_MOVE: case F_MOVE_WIDE: case F_MOVE_RESULT: case F_CONST32: case F_CONST64:
            case F_CONST_STRING: case F_CONST_CLASS: case F_CHECK_CAST: case F_INSTANCE_OF:
            case F_ARRAY_LEN: case F_NEW_INSTANCE: case F_NEW_ARRAY: case F_CMP:
            case F_ARRAY_GET: case F_INSTANCE_GET: case F_STATIC_GET: case F_UNARY_OP:
            case F_BINARY_OP: case F_BINARY_OP_CONST:
            case F_CONST_METHOD_HANDLE: case F_CONST_METHOD_TYPE:
                return 0; // index into args
            default:
                return -1;
        }
    }

    private static Def[] computeDefs(MethodInput m, Insn insn, RegisterState st, List<Note> notes) {
        RegisterState after = transfer(m, insn, st, notes, false);
        List<Def> defs = new ArrayList<>(2);
        boolean[] reported = new boolean[Math.max(1, st.size())];

        int destArg = destinationRegister(insn.family());
        if (destArg >= 0 && insn.args.length > destArg) {
            int reg = insn.arg(destArg);
            if (reg >= 0 && reg < after.size()) {
                defs.add(new Def(reg, after.live(reg)));
                reported[reg] = true;
            }
        }
        // Registers re-typed without being a syntactic destination: the <init> substitution that
        // initializes every copy of a reference, and the new-instance wipe of a stale allocation.
        int n = Math.min(st.size(), after.size());
        for (int i = 0; i < n; i++) {
            if (!reported[i] && st.live(i) != after.live(i) && !after.live(i).isWideHigh()) {
                defs.add(new Def(i, after.live(i)));
            }
        }
        return defs.toArray(new Def[0]);
    }

    private static Use pick(DexType.ClassHierarchy hierarchy, RegisterState st, int reg,
                            int preferred, String castTarget) {
        DexType t = st.live(reg);
        int sc = DexType.pickScalar(t.scalar(), preferred);
        // The checkcast decision is made against what the VERIFIER will see on the stack, which is
        // the declared type of the SLOT this load reads, not the value type the analysis inferred.
        // At every ordinary program point the two agree: the value keeps its OBJ reading only if
        // every incoming path stored it into the OBJ slot, so the two joins run over the same
        // edges. They diverge at an exception handler, where the value view joins only the
        // instructions that can THROW (the only ones that can transfer control there) while the
        // slot view joins every instruction in the protected range, as JVMS 4.10.1.6 requires. The
        // slot type is then the weaker of the two, and it is the one the verifier checks.
        //
        // A provably null value is exempt because the code writer pushes aconst_null rather than
        // loading the slot, so the slot's declared type never reaches the stack.
        boolean definitelyNull = t.isDefinitelyNull() && sc == DexType.OBJ;
        DexType seen = definitelyNull ? t : st.get(reg);
        String cast = (t.tainted() || needsNarrowingCast(hierarchy, seen, sc, castTarget))
                ? castTarget : null;
        return new Use(reg, sc, t, cast);
    }

    /**
     * Whether a reference use needs a {@code checkcast} because the type WE
     * inferred is weaker than the one the consumer declares.
     *
     * Reference merges here have no {@link DexType.ClassHierarchy} to consult,
     * so two unequal classes joining at a control flow merge collapse to
     * {@code java/lang/Object} (JVMS 4.10.1.2 makes that always sound). Sound,
     * but not sufficient: DEX is happy to store a String and something else in
     * one register and then call a String method on it, and once our frame
     * SAYS Object the verifier rejects the call --
     *
     *   Type 'java/lang/Object' (current frame, stack[0]) is not assignable
     *   to 'java/lang/String'
     *
     * (15 of 18 verify failures on GD Lite). ART does not care because Dalvik
     * registers are untyped; the JVM does, and a checkcast is exactly how a
     * real compiler bridges the gap. It is a no-op at runtime when the value
     * already has the type, and it throws the same ClassCastException the JVM
     * would have thrown anyway when it does not.
     *
     * Deliberately narrow: only when we actually LOST precision. Casting on
     * every reference use would be correct too, but it would add three bytes
     * to nearly every invoke.
     */
    private static boolean needsNarrowingCast(DexType.ClassHierarchy hierarchy, DexType t,
                                              int scalar, String castTarget) {
        if (castTarget == null || scalar != DexType.OBJ) return false;
        if (DexType.OBJECT_NAME.equals(castTarget)) return false;   // nothing is narrower
        DexType.Ref r = t.ref();
        if (r == null) return false;
        switch (r.kind) {
            case DexType.Ref.KIND_NULL:
                return false;      // null is assignable to every reference type
            case DexType.Ref.KIND_UNINIT:
            case DexType.Ref.KIND_UNINIT_THIS:
                return false;      // casting an uninitialized reference is illegal
            case DexType.Ref.KIND_CLASS:
                // A named class is not automatically precise ENOUGH. What the
                // verifier checks is assignability to the required type, and a
                // merge widens toward a common supertype long before it reaches
                // java/lang/Object:
                //
                //   com/squareup/picasso/BitmapHunter.run @312: putfield
                //   Type 'java/lang/Throwable' (current frame, stack[1]) is not
                //   assignable to 'java/lang/Exception'
                //
                // (two catch clauses reusing one Dalvik register for their
                // exception, merged to Throwable, stored into an Exception
                // field). Testing only for java/lang/Object missed every such
                // case. Ask the oracle instead, and keep the old Object-only
                // rule when there is no oracle, since without one nothing is
                // provable and casting on every use would be pure bloat.
                if (DexType.OBJECT_NAME.equals(r.name)) return true;
                return hierarchy != null && !assignableTo(hierarchy, r.name, castTarget);
            default:
                // NONE / CONFLICT: nothing provable, so let the cast assert it.
                return true;
        }
    }

    /**
     * JVMS 4.10.1.2 assignability, not real Java subtyping, and the difference
     * matters twice.
     *
     * <p>Every class is assignable to every INTERFACE type: the spec's
     * isJavaAssignable(class(_, _), class(To, L)) clause succeeds whenever To is
     * an interface, and the real check is deferred to invokeinterface at run
     * time. So an interface target answers true and generates no cast, which is
     * what keeps this refinement from becoming bloat.
     *
     * <p>ARRAYS are COVARIANT and getting that wrong is actively destructive
     * here, not merely imprecise. JVMS 4.10.1.2 reads
     * {@code isJavaAssignable(arrayOf(X), arrayOf(Y)) :- isJavaAssignable(X, Y)}
     * plus {@code isJavaAssignable(arrayOf(_), class('java/lang/Object', BL))},
     * so {@code [Lcom/Foo;} IS assignable to {@code [Ljava/lang/Object;}.
     * Answering "no" there emits a checkcast to the WIDER array type, and unlike
     * a redundant cast to a narrower type that one throws information away: the
     * value on the stack becomes {@code [Ljava/lang/Object;} and the following
     * {@code aaload} yields Object. Measured, with exact equality as the array
     * rule: arc/flabel/FParser$InternalToken.fromName got
     * {@code getstatic all:[Larc/flabel/FParser$InternalToken; ; checkcast
     * [Ljava/lang/Object; ; arraylength} and 4,124 classes across the five
     * reference apps stopped verifying (up from 270).
     *
     * <p>A PRIMITIVE array is assignable only to an identical one, to Object,
     * and to Cloneable/Serializable, which falls out of recursing on the element
     * with no primitive widening.
     *
     * <p>When the chain runs out without reaching {@code to} the answer is "not
     * provable", which is treated as "not assignable". That is the safe
     * direction for a CLASS target: a redundant checkcast to the required type
     * always succeeds at run time, a missing one is a class that will not
     * verify.
     */
    private static boolean assignableTo(DexType.ClassHierarchy hierarchy, String from, String to) {
        if (from == null || to == null) return true;
        if (from.equals(to) || DexType.OBJECT_NAME.equals(to)) return true;
        if (to.charAt(0) == '[') {
            // Only an array is assignable to an array, and then element-wise.
            if (from.charAt(0) != '[') return false;
            return elementAssignable(hierarchy, from.substring(1), to.substring(1));
        }
        if (from.charAt(0) == '[') {
            // Array to non-array: Object was handled above, so only Cloneable
            // and Serializable remain, and both are interfaces.
            return hierarchy.isInterface(to);
        }
        if (hierarchy.isInterface(to)) return true;
        String cur = from;
        for (int guard = 0; cur != null && guard < 256; guard++) {
            if (cur.equals(to)) return true;
            cur = hierarchy.superclassOf(cur);
        }
        return false;
    }

    /**
     * Assignability between two ARRAY ELEMENT descriptors, e.g. the "Lcom/Foo;"
     * and "Ljava/lang/Object;" of {@code [Lcom/Foo;} and {@code [Ljava/lang/Object;}.
     *
     * <p>Descriptors, not internal names, because the two are ambiguous exactly
     * where it matters: R8 emits one-letter class names, so the element of
     * {@code [LZ;} and the element of {@code [Z} both read as "Z" once the L and
     * the semicolon are stripped, and one is a class while the other is boolean.
     * A primitive element admits no widening at all (JVMS 4.10.1.2 has no
     * isJavaAssignable clause between two different primitives), so mistaking a
     * class for one would suppress a legal assignment.
     */
    private static boolean elementAssignable(DexType.ClassHierarchy hierarchy,
                                             String from, String to) {
        if (from.equals(to)) return true;
        char f = from.charAt(0), t = to.charAt(0);
        if (f != 'L' && f != '[') return false;   // primitive element: identical only
        if (t != 'L' && t != '[') return false;
        if (t == '[') {
            return f == '[' && elementAssignable(hierarchy, from.substring(1), to.substring(1));
        }
        String toName = to.substring(1, to.length() - 1);
        String fromName = (f == 'L') ? from.substring(1, from.length() - 1) : from;
        return assignableTo(hierarchy, fromName, toName);
    }

    /**
     * The reading the returned register must be produced under.
     *
     * The declared descriptor wins whenever we have it: 0x0f `return` covers
     * Z/B/C/S/I AND F, and 0x10 `return-wide` covers J AND D, so the opcode
     * alone cannot tell an int return from a float one. A register holding a
     * DEX constant is legally int, float and null at once, and picking the
     * wrong reading is a verify error rather than a wrong answer.
     */
    private static int preferredForReturn(int opcode, String returnDescriptor) {
        int declared = DexType.scalarFromDescriptor(returnDescriptor);
        if (declared != DexType.NONE) return declared;
        // 0x0f return, 0x10 return-wide, 0x11 return-object
        switch (opcode & 0xFF) {
            case 0x10: return DexType.LONG;
            case 0x11: return DexType.OBJ;
            default: return DexType.INT;
        }
    }

    /**
     * The class to checkcast an array reference to before using it, when the value is tainted.
     * An unknown array kind means some object array, and Object[] is the weakest cast that still
     * satisfies the JVM's array instructions.
     */
    private static String arrayCastTarget(DexType t) {
        if (t.arrayKind() == DexType.ARRAY_EXACT) {
            return t.arrayDescriptor();
        }
        return "[Ljava/lang/Object;";
    }

    private static String castTargetForDescriptor(String desc) {
        if (desc == null) {
            return null;
        }
        if (desc.startsWith("L") && desc.endsWith(";")) {
            return desc.substring(1, desc.length() - 1);
        }
        if (desc.startsWith("[")) {
            return desc;
        }
        return null;
    }

    // Resolver access is wrapped because a DEX can reference an index this dex does not define,
    // and one unresolvable index must degrade that instruction rather than abort the method.
    private static String safeTypeDescriptor(MethodInput m, int idx) {
        try {
            return m.resolver == null ? null : m.resolver.typeDescriptor(idx);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String safeFieldDescriptor(MethodInput m, int idx) {
        try {
            return m.resolver == null ? null : m.resolver.fieldDescriptor(idx);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String safeFieldOwner(MethodInput m, int idx) {
        try {
            return m.resolver == null ? null : m.resolver.fieldOwner(idx);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String safeMethodName(MethodInput m, int idx) {
        try {
            return m.resolver == null ? null : m.resolver.methodName(idx);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String safeMethodOwner(MethodInput m, int idx) {
        try {
            return m.resolver == null ? null : m.resolver.methodOwner(idx);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String[] safeParams(MethodInput m, int idx, boolean isStatic) {
        try {
            return m.resolver == null ? null
                    : m.resolver.methodSpacedParamDescriptors(idx, isStatic);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
