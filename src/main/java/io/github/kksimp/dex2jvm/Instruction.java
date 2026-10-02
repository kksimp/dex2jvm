package io.github.kksimp.dex2jvm;

/**
 * One decoded Dalvik instruction, in a shape that is uniform across all 26
 * instruction formats so consumers never re-extract bits.
 *
 * <p>Operand naming follows AOSP's {@code Instruction::VRegA/B/C/H} accessors
 * (art/libdexfile/dex/dex_instruction-inl.h), NOT the letters printed in the
 * instruction-formats doc, because the two disagree for the varargs formats.
 * Specifically for 35c/3rc/45cc/4rcc the doc calls the argument count "A" and
 * the method reference "B"; ART (and this class) keep that convention, so
 * {@link #a()} on an invoke is the ARGUMENT COUNT and not a register. Use
 * {@link #argRegisters()} for the registers.
 *
 * <p>Sign extension is already applied: {@link #literal()} is the value the
 * instruction means, widened to long. {@link #target()} is an ABSOLUTE
 * code-unit address, not the encoded relative offset.
 *
 * <p>Instances are effectively immutable once {@link InstructionDecoder} has
 * finished; the only fields it fills in after construction are the
 * result-adjacency links ({@link #resultConsumer()} / {@link #resultProducer()})
 * and the resolved switch / array-data payload data.
 */
public final class Instruction {

    /** Returned by {@link #target()} when the instruction has no target. */
    public static final int NO_TARGET = -1;
    /** Returned by {@link #index()} when the instruction carries no index. */
    public static final int NO_INDEX = -1;

    /** What kind of NOP-encoded payload this pseudo-instruction is, if any. */
    public enum PayloadKind {
        /** Not a payload: a real instruction. */
        NONE,
        /** packed-switch-payload (ident 0x0100). */
        PACKED_SWITCH,
        /** sparse-switch-payload (ident 0x0200). */
        SPARSE_SWITCH,
        /** fill-array-data-payload (ident 0x0300). */
        ARRAY_DATA
    }

    private static final int[] NO_ARGS = new int[0];

    private final int address;
    private final int opcode;
    private final Opcodes.Format format;
    private final int size;

    private final int a;
    private final int b;
    private final int c;
    private final int h;
    private final long literal;
    private final int index;
    private final int[] args;

    private final int target;
    private final PayloadKind payloadKind;

    // Payload contents. For a payload pseudo-instruction these hold the raw
    // decoded table. For the packed-switch / sparse-switch / fill-array-data
    // instruction that REFERS to a payload, InstructionDecoder copies the
    // resolved table across so consumers never have to chase the pointer.
    private int[] switchKeys;
    private int[] switchTargets;
    private int arrayElementWidth;
    private int arrayElementCount;
    private byte[] arrayData;

    private Instruction resultConsumer;
    private Instruction resultProducer;

    Instruction(int address, int opcode, Opcodes.Format format, int size,
                int a, int b, int c, int h, long literal, int index, int[] args,
                int target, PayloadKind payloadKind) {
        this.address = address;
        this.opcode = opcode;
        this.format = format;
        this.size = size;
        this.a = a;
        this.b = b;
        this.c = c;
        this.h = h;
        this.literal = literal;
        this.index = index;
        this.args = args == null ? NO_ARGS : args;
        this.target = target;
        this.payloadKind = payloadKind;
    }

    // ------------------------------------------------------------------
    // Position and identity.
    // ------------------------------------------------------------------

    /** Offset of this instruction within the method, in 16-bit code units. */
    public int address() {
        return address;
    }

    /** Length of this instruction in 16-bit code units (always >= 1). */
    public int size() {
        return size;
    }

    /** Address of the following instruction, i.e. the fallthrough address. */
    public int nextAddress() {
        return address + size;
    }

    /** The packed opcode, 0x00..0xff. */
    public int opcode() {
        return opcode;
    }

    /** AOSP mnemonic, e.g. "invoke-virtual". Payloads report "nop". */
    public String opcodeName() {
        return Opcodes.nameOf(opcode);
    }

    /**
     * The instruction format. Payload pseudo-instructions report
     * {@link Opcodes.Format#F10X} because they are NOP-encoded; check
     * {@link #isPayload()} first if that matters. {@link #size()} is always
     * the true length, including for payloads.
     */
    public Opcodes.Format format() {
        return format;
    }

    // ------------------------------------------------------------------
    // Operands.
    // ------------------------------------------------------------------

    /**
     * vA. A register number for most formats. For 35c/3rc/45cc/4rcc this is
     * the ARGUMENT COUNT (matching AOSP's VRegA_35c/VRegA_3rc), not a
     * register. Zero when the format has no A operand.
     */
    public int a() {
        return a;
    }

    /**
     * vB. A register number for 12x/22x/22c/22s/22t/23x/32x/22b; the
     * constant-pool index for the 21c/31c/35c/3rc/45cc/4rcc family (also
     * available as {@link #index()}); zero when unused. Literal-bearing
     * formats put their value in {@link #literal()}, not here.
     */
    public int b() {
        return b;
    }

    /**
     * vC. A register number for 23x; the constant-pool index for 22c (also
     * available as {@link #index()}); the FIRST register of the range for
     * 3rc/4rcc; the first argument register for 35c/45cc. Zero when unused.
     */
    public int c() {
        return c;
    }

    /** vH: the proto_ids index of invoke-polymorphic (45cc/4rcc). */
    public int h() {
        return h;
    }

    /**
     * The literal operand, sign-extended to long.
     *
     * <p>For const/high16 this is the assembled int value sign-extended to
     * long, so {@code (int) literal()} is what the instruction stores. For
     * const-wide/high16 and const-wide it is the 64-bit value. For
     * const-wide/32 it is sign-extended from 32 bits (a Dalvik detail that is
     * easy to get wrong: the same 31i format is shared with const, which is
     * NOT widened).
     */
    public long literal() {
        return literal;
    }

    /**
     * The constant-pool index, or {@link #NO_INDEX}. Interpret it according
     * to {@link #indexType()}.
     */
    public int index() {
        return index;
    }

    /** One of the {@code Opcodes.INDEX_*} constants. */
    public int indexType() {
        return Opcodes.indexTypeOf(opcode);
    }

    /**
     * The argument registers of an invoke / filled-new-array, in order. Both
     * the 35c ({vC, vD, vE, vF, vG}) and 3rc ({vCCCC .. vCCCC+AA-1}) shapes
     * are expanded here so callers do not branch on the format. Empty for
     * every other instruction. The returned array is not copied: do not
     * mutate it.
     */
    public int[] argRegisters() {
        return args;
    }

    /** Number of argument registers; equals {@code argRegisters().length}. */
    public int argCount() {
        return args.length;
    }

    // ------------------------------------------------------------------
    // Control flow.
    // ------------------------------------------------------------------

    /**
     * The ABSOLUTE code-unit address this instruction points at, or
     * {@link #NO_TARGET}.
     *
     * <p>For goto/16/32 and if-* this is the branch destination. For
     * packed-switch, sparse-switch and fill-array-data it is the address of
     * the PAYLOAD, which is data, not a control-flow successor: do not build
     * a CFG edge from it. The real switch successors are
     * {@link #switchTargets()}.
     */
    public int target() {
        return target;
    }

    /** True if execution can fall through to {@link #nextAddress()}. */
    public boolean canContinue() {
        return Opcodes.canContinue(opcode);
    }

    /** True for goto/16/32 and every if-*. */
    public boolean isBranch() {
        return Opcodes.isBranch(opcode);
    }

    /** True for goto, goto/16, goto/32. */
    public boolean isUnconditionalBranch() {
        return Opcodes.isUnconditionalBranch(opcode);
    }

    /** True for packed-switch and sparse-switch. */
    public boolean isSwitch() {
        return Opcodes.isSwitch(opcode);
    }

    /** True for return-void / return / return-wide / return-object. */
    public boolean isReturn() {
        return Opcodes.isReturn(opcode);
    }

    /** True for the throw opcode specifically. */
    public boolean isThrow() {
        return opcode == Opcodes.THROW;
    }

    /** True if this instruction can raise an exception (AOSP kThrow). */
    public boolean canThrow() {
        return Opcodes.canThrow(opcode);
    }

    /**
     * True for invoke-virtual/super/direct/static/interface, their /range
     * forms, and invoke-polymorphic.
     *
     * <p>Deliberately FALSE for invoke-custom and invoke-custom/range: AOSP
     * does not set kInvoke on them because they resolve through a bootstrap
     * method rather than a method_id. They do still write the result register,
     * so test {@link #setsResult()} rather than this when what you care about
     * is the move-result pairing.
     */
    public boolean isInvoke() {
        return Opcodes.isInvoke(opcode);
    }

    /** True if vA names a register pair (vA, vA+1). */
    public boolean isWideA() {
        return Opcodes.isWideA(opcode);
    }

    /** True if vB names a register pair (vB, vB+1). */
    public boolean isWideB() {
        return Opcodes.isWideB(opcode);
    }

    /** True if vC names a register pair (vC, vC+1). */
    public boolean isWideC() {
        return Opcodes.isWideC(opcode);
    }

    // ------------------------------------------------------------------
    // The invoke / move-result adjacency.
    // ------------------------------------------------------------------

    /**
     * True for move-result / move-result-wide / move-result-object. These
     * read a pseudo-register that only the immediately preceding instruction
     * can have written, which is why the adjacency is modelled explicitly
     * rather than left for each consumer to rediscover.
     */
    public boolean isMoveResult() {
        return Opcodes.isMoveResult(opcode);
    }

    /** True for invoke-* and filled-new-array*: writes the result register. */
    public boolean setsResult() {
        return Opcodes.setsResult(opcode);
    }

    /**
     * If this instruction {@linkplain #setsResult() sets the result register}
     * AND is immediately followed by a move-result*, that move-result;
     * otherwise null. A null here on an invoke means the result is discarded.
     */
    public Instruction resultConsumer() {
        return resultConsumer;
    }

    /**
     * If this is a move-result* whose immediately preceding instruction sets
     * the result register, that instruction; otherwise null.
     *
     * <p>A null on a move-result means the DEX is malformed (or has been
     * obfuscated in a way ART's verifier would reject): there is no defined
     * value to move. Consumers should treat it as an error rather than
     * silently producing a garbage value.
     */
    public Instruction resultProducer() {
        return resultProducer;
    }

    void linkResult(Instruction consumer) {
        this.resultConsumer = consumer;
        consumer.resultProducer = this;
    }

    // ------------------------------------------------------------------
    // Payloads.
    // ------------------------------------------------------------------

    /** True if this is a NOP-encoded payload pseudo-instruction (data). */
    public boolean isPayload() {
        return payloadKind != PayloadKind.NONE;
    }

    /** Which payload kind this is, or {@link PayloadKind#NONE}. */
    public PayloadKind payloadKind() {
        return payloadKind;
    }

    /**
     * Switch case keys, parallel to {@link #switchTargets()}.
     *
     * <p>Available on both the packed-switch/sparse-switch instruction (the
     * decoder resolves the payload for you) and on the payload
     * pseudo-instruction itself. Null for anything else. For a packed switch
     * the keys are expanded from {@code first_key} so the two arrays are
     * always parallel and consumers do not special-case packed vs sparse.
     */
    public int[] switchKeys() {
        return switchKeys;
    }

    /**
     * Switch case targets as ABSOLUTE code-unit addresses, parallel to
     * {@link #switchKeys()}.
     *
     * <p>Note the DEX encoding stores these relative to the address of the
     * SWITCH instruction, not of the payload, so the payload alone is not
     * enough to resolve them. On the payload pseudo-instruction this array is
     * therefore the raw RELATIVE offsets; on the switch instruction it is the
     * resolved absolute addresses. Read them from the switch instruction.
     */
    public int[] switchTargets() {
        return switchTargets;
    }

    /** fill-array-data element width in bytes (1, 2, 4 or 8). */
    public int arrayElementWidth() {
        return arrayElementWidth;
    }

    /** fill-array-data element count. */
    public int arrayElementCount() {
        return arrayElementCount;
    }

    /**
     * fill-array-data raw element bytes, little-endian,
     * {@code arrayElementWidth() * arrayElementCount()} bytes long. Not
     * copied: do not mutate.
     */
    public byte[] arrayData() {
        return arrayData;
    }

    void setSwitchData(int[] keys, int[] targets) {
        this.switchKeys = keys;
        this.switchTargets = targets;
    }

    void setArrayData(int elementWidth, int elementCount, byte[] data) {
        this.arrayElementWidth = elementWidth;
        this.arrayElementCount = elementCount;
        this.arrayData = data;
    }

    // ------------------------------------------------------------------
    // Debug.
    // ------------------------------------------------------------------

    /**
     * A smali-flavoured one-line dump. Constant-pool indices are printed as
     * bare numbers because this class has no access to the pool; it is a
     * debugging aid, not a disassembler.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%04x: ", address));
        if (isPayload()) {
            switch (payloadKind) {
                case PACKED_SWITCH:
                    sb.append("packed-switch-payload ").append(switchTargets.length)
                            .append(" cases, first_key=").append(switchKeys[0]);
                    break;
                case SPARSE_SWITCH:
                    sb.append("sparse-switch-payload ").append(switchTargets.length)
                            .append(" cases");
                    break;
                case ARRAY_DATA:
                    sb.append("array-data-payload width=").append(arrayElementWidth)
                            .append(" count=").append(arrayElementCount);
                    break;
                default:
                    break;
            }
            return sb.toString();
        }
        sb.append(opcodeName());
        switch (format) {
            case F10X:
                break;
            case F11X:
            case F11N:
                sb.append(" v").append(a);
                if (format == Opcodes.Format.F11N) {
                    sb.append(", #").append(literal);
                }
                break;
            case F12X:
                sb.append(" v").append(a).append(", v").append(b);
                break;
            case F10T:
            case F20T:
            case F30T:
                sb.append(" ").append(String.format("%04x", target));
                break;
            case F22X:
            case F32X:
                sb.append(" v").append(a).append(", v").append(b);
                break;
            case F21T:
                sb.append(" v").append(a).append(", ").append(String.format("%04x", target));
                break;
            case F21S:
            case F21H:
                sb.append(" v").append(a).append(", #").append(literal);
                break;
            case F21C:
            case F31C:
                sb.append(" v").append(a).append(", @").append(index);
                break;
            case F23X:
                sb.append(" v").append(a).append(", v").append(b).append(", v").append(c);
                break;
            case F22B:
                sb.append(" v").append(a).append(", v").append(b).append(", #").append(literal);
                break;
            case F22T:
                sb.append(" v").append(a).append(", v").append(b)
                        .append(", ").append(String.format("%04x", target));
                break;
            case F22S:
                sb.append(" v").append(a).append(", v").append(b).append(", #").append(literal);
                break;
            case F22C:
                sb.append(" v").append(a).append(", v").append(b).append(", @").append(index);
                break;
            case F31I:
                sb.append(" v").append(a).append(", #").append(literal);
                break;
            case F31T:
                sb.append(" v").append(a).append(", ").append(String.format("%04x", target));
                break;
            case F51L:
                sb.append(" v").append(a).append(", #").append(literal);
                break;
            case F35C:
            case F3RC:
            case F45CC:
            case F4RCC:
                sb.append(" {");
                for (int i = 0; i < args.length; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append('v').append(args[i]);
                }
                sb.append("}, @").append(index);
                if (format == Opcodes.Format.F45CC || format == Opcodes.Format.F4RCC) {
                    sb.append(", proto@").append(h);
                }
                break;
            default:
                break;
        }
        return sb.toString();
    }
}
