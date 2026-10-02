package io.github.kksimp.dex2jvm;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes a method's {@code insns} array (16-bit code units, as stored in a
 * DEX {@code code_item}) into a stream of {@link Instruction}s.
 *
 * <p>Bit layouts follow AOSP {@code art/libdexfile/dex/dex_instruction-inl.h}
 * ({@code VRegA_*} / {@code VRegB_*} / {@code VRegC_*} / {@code VRegH_*} /
 * {@code GetVarArgs}) and the format table at
 * https://source.android.com/docs/core/runtime/instruction-formats
 * Payload sizes follow {@code Instruction::SizeInCodeUnitsComplexOpcode} in
 * {@code dex_instruction.cc}.
 *
 * <p>Three things this decoder does that a naive "switch on format" does not,
 * each of which is a classic source of wrong output:
 * <ul>
 * <li>The three NOP-encoded payloads (packed-switch, sparse-switch,
 * fill-array-data) are decoded as variable-length pseudo-instructions and
 * their contents are copied onto the instruction that REFERS to them, with
 * switch targets resolved to absolute addresses. The DEX encodes those
 * targets relative to the switch instruction, not to the payload.</li>
 * <li>{@code const-wide/32} is sign-extended to 64 bits even though it shares
 * format 31i with {@code const}, which is not. Missing this silently
 * corrupts negative long constants.</li>
 * <li>Each {@code move-result*} is linked to the instruction that produced
 * its value ({@link Instruction#resultProducer()}), and each invoke to its
 * consumer, so consumers never have to re-derive the adjacency.</li>
 * </ul>
 */
public final class InstructionDecoder {

    private InstructionDecoder() {
    }

    /** Thrown when the code array cannot be decoded as valid Dalvik bytecode. */
    public static final class MalformedCodeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public MalformedCodeException(String message) {
            super(message);
        }
    }

    /**
     * Decodes the whole code array, in address order.
     *
     * <p>The result includes the payload pseudo-instructions: they occupy
     * code units and their addresses must stay in the map, but they are data,
     * not code. {@link ControlFlowGraph} keeps them out of basic blocks.
     *
     * @param insns the method's code units; element {@code i} is the code unit
     *              at code-unit address {@code i}
     * @return the decoded instructions, ordered by address
     * @throws MalformedCodeException if an instruction runs past the end of
     *                                the array, an unassigned opcode is hit,
     *                                or a payload reference is bad
     */
    public static Instruction[] decode(short[] insns) {
        List<Instruction> out = new ArrayList<>(Math.max(16, insns.length / 2));
        int pos = 0;
        while (pos < insns.length) {
            Instruction insn = decodeAt(insns, pos);
            out.add(insn);
            pos += insn.size();
        }
        Instruction[] list = out.toArray(new Instruction[0]);
        Instruction[] byAddress = addressIndex(list, insns.length);
        attachPayloads(list, byAddress);
        linkResults(list);
        return list;
    }

    /**
     * Builds a sparse address-to-instruction index: element {@code i} is the
     * instruction starting at code-unit address {@code i}, or null if {@code i}
     * is in the middle of an instruction.
     *
     * <p>A branch target is valid if and only if its slot here is non-null;
     * that is the cheapest way to reject a target that lands mid-instruction.
     */
    public static Instruction[] addressIndex(Instruction[] list, int codeUnitCount) {
        Instruction[] byAddress = new Instruction[codeUnitCount];
        for (Instruction insn : list) {
            byAddress[insn.address()] = insn;
        }
        return byAddress;
    }

    // ------------------------------------------------------------------
    // Single-instruction decode.
    // ------------------------------------------------------------------

    private static Instruction decodeAt(short[] insns, int pos) {
        int unit = u16(insns, pos, pos);
        int opcode = unit & 0xff;

        if (opcode == Opcodes.NOP) {
            // A NOP whose high byte is non-zero is a payload signature, not a
            // nop. AOSP: Instruction::SizeInCodeUnitsComplexOpcode.
            switch (unit) {
                case Opcodes.PACKED_SWITCH_PAYLOAD:
                    return decodePackedSwitchPayload(insns, pos);
                case Opcodes.SPARSE_SWITCH_PAYLOAD:
                    return decodeSparseSwitchPayload(insns, pos);
                case Opcodes.ARRAY_DATA_PAYLOAD:
                    return decodeArrayDataPayload(insns, pos);
                default:
                    break; // 0x0000 (a real nop) and anything unrecognised.
            }
        }

        if (Opcodes.isUnused(opcode)) {
            throw new MalformedCodeException(String.format(
                    "unassigned opcode 0x%02x at code-unit address 0x%04x", opcode, pos));
        }

        Opcodes.Format format = Opcodes.formatOf(opcode);
        int size = format.codeUnits();
        if (pos + size > insns.length) {
            throw new MalformedCodeException(String.format(
                    "instruction %s at 0x%04x needs %d code units, only %d remain",
                    Opcodes.nameOf(opcode), pos, size, insns.length - pos));
        }

        int a = 0;
        int b = 0;
        int c = 0;
        int h = 0;
        long literal = 0;
        int index = Instruction.NO_INDEX;
        int[] args = null;
        int target = Instruction.NO_TARGET;

        // Nibble / byte accessors on the first code unit, named as in AOSP:
        // InstA = (unit >> 8) & 0xf, InstB = unit >> 12, InstAA = unit >> 8.
        int instA = (unit >>> 8) & 0x0f;
        int instB = (unit >>> 12) & 0x0f;
        int instAA = (unit >>> 8) & 0xff;

        switch (format) {
            case F10X: // op
                break;

            case F12X: // op vA, vB
                a = instA;
                b = instB;
                break;

            case F11N: // op vA, #+B  (B is a signed nibble)
                a = instA;
                literal = signExtend(instB, 4);
                break;

            case F11X: // op vAA
                a = instAA;
                break;

            case F10T: // op +AA  (signed byte, relative to this instruction)
                a = instAA;
                target = pos + (byte) instAA;
                break;

            case F20T: // op +AAAA
                target = pos + (short) u16(insns, pos + 1, pos);
                break;

            case F22X: // op vAA, vBBBB
                a = instAA;
                b = u16(insns, pos + 1, pos);
                break;

            case F21T: // op vAA, +BBBB
                a = instAA;
                target = pos + (short) u16(insns, pos + 1, pos);
                break;

            case F21S: // op vAA, #+BBBB
                a = instAA;
                literal = (short) u16(insns, pos + 1, pos);
                break;

            case F21H: // op vAA, #+BBBB0000 [00000000]
                a = instAA;
                // The 16 bits land in the HIGH half of an int (const/high16)
                // or the high quarter of a long (const-wide/high16). For
                // const/high16 we sign-extend the assembled int so that
                // (int) literal() is exactly what the instruction stores.
                if (opcode == Opcodes.CONST_HIGH16) {
                    literal = (u16(insns, pos + 1, pos) << 16);
                } else {
                    literal = ((long) u16(insns, pos + 1, pos)) << 48;
                }
                break;

            case F21C: // op vAA, thing@BBBB
                a = instAA;
                b = u16(insns, pos + 1, pos);
                index = b;
                break;

            case F23X: // op vAA, vBB, vCC
                a = instAA;
                b = u16(insns, pos + 1, pos) & 0xff;
                c = u16(insns, pos + 1, pos) >>> 8;
                break;

            case F22B: // op vAA, vBB, #+CC  (CC is a signed byte)
                a = instAA;
                b = u16(insns, pos + 1, pos) & 0xff;
                literal = (byte) (u16(insns, pos + 1, pos) >>> 8);
                break;

            case F22T: // op vA, vB, +CCCC
                a = instA;
                b = instB;
                target = pos + (short) u16(insns, pos + 1, pos);
                break;

            case F22S: // op vA, vB, #+CCCC
                a = instA;
                b = instB;
                literal = (short) u16(insns, pos + 1, pos);
                break;

            case F22C: // op vA, vB, thing@CCCC
                a = instA;
                b = instB;
                c = u16(insns, pos + 1, pos);
                index = c;
                break;

            case F32X: // op vAAAA, vBBBB
                a = u16(insns, pos + 1, pos);
                b = u16(insns, pos + 2, pos);
                break;

            case F30T: // op +AAAAAAAA
                target = pos + u32(insns, pos + 1, pos);
                break;

            case F31T: // op vAA, +BBBBBBBB
                // packed-switch / sparse-switch / fill-array-data. The target
                // is a PAYLOAD address, i.e. data, not a control-flow edge.
                a = instAA;
                target = pos + u32(insns, pos + 1, pos);
                break;

            case F31I: // op vAA, #+BBBBBBBB
                a = instAA;
                // const-wide/32 shares this format with const but widens to a
                // long, so its 32-bit operand must be sign-extended. const
                // keeps 32 bits; sign-extending it into the long field is
                // harmless because (int) literal() recovers the same value.
                literal = u32(insns, pos + 1, pos);
                break;

            case F31C: // op vAA, string@BBBBBBBB
                a = instAA;
                index = u32(insns, pos + 1, pos);
                b = index;
                break;

            case F35C: // op {vC, vD, vE, vF, vG}, thing@BBBB   (A: count, G in the A nibble)
            case F45CC: { // op {vC .. vG}, meth@BBBB, proto@HHHH
                int count = instB;
                if (count > Opcodes.MAX_VAR_ARG_REGS) {
                    throw new MalformedCodeException(String.format(
                            "%s at 0x%04x declares %d argument registers (max %d)",
                            Opcodes.nameOf(opcode), pos, count, Opcodes.MAX_VAR_ARG_REGS));
                }
                index = u16(insns, pos + 1, pos);
                b = index;
                int regList = u16(insns, pos + 2, pos);
                args = new int[count];
                // AOSP GetVarArgs: {C, D, E, F} are the nibbles of the third
                // code unit and G is the A nibble of the first. Falls through
                // from the high case down, so a short list uses the low
                // nibbles.
                if (count > 0) {
                    args[0] = regList & 0x0f;
                }
                if (count > 1) {
                    args[1] = (regList >>> 4) & 0x0f;
                }
                if (count > 2) {
                    args[2] = (regList >>> 8) & 0x0f;
                }
                if (count > 3) {
                    args[3] = (regList >>> 12) & 0x0f;
                }
                if (count > 4) {
                    args[4] = instA;
                }
                a = count;
                c = regList & 0x0f;
                if (format == Opcodes.Format.F45CC) {
                    h = u16(insns, pos + 3, pos);
                }
                break;
            }

            case F3RC: // op {vCCCC .. vCCCC+AA-1}, thing@BBBB
            case F4RCC: { // ... , proto@HHHH
                int count = instAA;
                index = u16(insns, pos + 1, pos);
                b = index;
                c = u16(insns, pos + 2, pos);
                args = new int[count];
                for (int i = 0; i < count; i++) {
                    args[i] = c + i;
                }
                a = count;
                if (format == Opcodes.Format.F4RCC) {
                    h = u16(insns, pos + 3, pos);
                }
                break;
            }

            case F51L: // op vAA, #+BBBBBBBBBBBBBBBB
                a = instAA;
                literal = (u32(insns, pos + 1, pos) & 0xffffffffL)
                        | ((long) u32(insns, pos + 3, pos) << 32);
                break;

            default:
                throw new MalformedCodeException("unhandled format " + format
                        + " for opcode " + Opcodes.nameOf(opcode));
        }

        return new Instruction(pos, opcode, format, size, a, b, c, h, literal, index, args,
                target, Instruction.PayloadKind.NONE);
    }

    // ------------------------------------------------------------------
    // Payloads.
    // ------------------------------------------------------------------

    private static Instruction decodePackedSwitchPayload(short[] insns, int pos) {
        // ident:u2 size:u2 first_key:s4 targets:s4[size]
        int count = u16(insns, pos + 1, pos);
        int size = 4 + count * 2;
        requireRoom(insns, pos, size, "packed-switch-payload");
        int firstKey = u32(insns, pos + 2, pos);
        int[] keys = new int[count];
        int[] targets = new int[count];
        for (int i = 0; i < count; i++) {
            keys[i] = firstKey + i;
            targets[i] = u32(insns, pos + 4 + i * 2, pos);
        }
        Instruction insn = payload(pos, size, Instruction.PayloadKind.PACKED_SWITCH);
        insn.setSwitchData(keys, targets);
        return insn;
    }

    private static Instruction decodeSparseSwitchPayload(short[] insns, int pos) {
        // ident:u2 size:u2 keys:s4[size] targets:s4[size]
        int count = u16(insns, pos + 1, pos);
        int size = 2 + count * 4;
        requireRoom(insns, pos, size, "sparse-switch-payload");
        int[] keys = new int[count];
        int[] targets = new int[count];
        for (int i = 0; i < count; i++) {
            keys[i] = u32(insns, pos + 2 + i * 2, pos);
            targets[i] = u32(insns, pos + 2 + count * 2 + i * 2, pos);
        }
        Instruction insn = payload(pos, size, Instruction.PayloadKind.SPARSE_SWITCH);
        insn.setSwitchData(keys, targets);
        return insn;
    }

    private static Instruction decodeArrayDataPayload(short[] insns, int pos) {
        // ident:u2 element_width:u2 size:u4 data:ubyte[element_width*size]
        int width = u16(insns, pos + 1, pos);
        long count = (u16(insns, pos + 2, pos) & 0xffffL)
                | ((long) u16(insns, pos + 3, pos) << 16);
        // The "+1) / 2" rounds a run of odd length up to a whole code unit.
        // AOSP: Instruction::SizeInCodeUnitsComplexOpcode.
        long size = 4 + (((long) width * count) + 1) / 2;
        if (size > Integer.MAX_VALUE || size < 4) {
            throw new MalformedCodeException(String.format(
                    "fill-array-data-payload at 0x%04x has implausible size %d", pos, size));
        }
        requireRoom(insns, pos, (int) size, "fill-array-data-payload");
        long byteLen = (long) width * count;
        byte[] data = new byte[(int) byteLen];
        for (int i = 0; i < byteLen; i++) {
            int unit = u16(insns, pos + 4 + (i >>> 1), pos);
            data[i] = (byte) ((i & 1) == 0 ? (unit & 0xff) : (unit >>> 8));
        }
        Instruction insn = payload(pos, (int) size, Instruction.PayloadKind.ARRAY_DATA);
        insn.setArrayData(width, (int) count, data);
        return insn;
    }

    private static Instruction payload(int pos, int size, Instruction.PayloadKind kind) {
        return new Instruction(pos, Opcodes.NOP, Opcodes.Format.F10X, size,
                0, 0, 0, 0, 0L, Instruction.NO_INDEX, null, Instruction.NO_TARGET, kind);
    }

    /**
     * Copies each payload's contents onto the instruction that refers to it,
     * resolving switch targets from payload-relative offsets to absolute
     * addresses. Doing it here means every consumer sees a switch as a plain
     * (keys, targets) pair.
     */
    private static void attachPayloads(Instruction[] list, Instruction[] byAddress) {
        for (Instruction insn : list) {
            int opcode = insn.opcode();
            boolean wantsSwitch = opcode == Opcodes.PACKED_SWITCH
                    || opcode == Opcodes.SPARSE_SWITCH;
            boolean wantsArray = opcode == Opcodes.FILL_ARRAY_DATA;
            if (!wantsSwitch && !wantsArray) {
                continue;
            }
            int at = insn.target();
            if (at < 0 || at >= byAddress.length || byAddress[at] == null) {
                throw new MalformedCodeException(String.format(
                        "%s at 0x%04x points at 0x%04x, which is not an instruction boundary",
                        insn.opcodeName(), insn.address(), at));
            }
            Instruction data = byAddress[at];
            if (wantsSwitch) {
                Instruction.PayloadKind want = opcode == Opcodes.PACKED_SWITCH
                        ? Instruction.PayloadKind.PACKED_SWITCH
                        : Instruction.PayloadKind.SPARSE_SWITCH;
                if (data.payloadKind() != want) {
                    throw new MalformedCodeException(String.format(
                            "%s at 0x%04x points at 0x%04x which is %s, not %s",
                            insn.opcodeName(), insn.address(), at, data.payloadKind(), want));
                }
                int[] relative = data.switchTargets();
                int[] absolute = new int[relative.length];
                for (int i = 0; i < relative.length; i++) {
                    // Relative to the address of the SWITCH, not the payload.
                    absolute[i] = insn.address() + relative[i];
                }
                insn.setSwitchData(data.switchKeys(), absolute);
            } else {
                if (data.payloadKind() != Instruction.PayloadKind.ARRAY_DATA) {
                    throw new MalformedCodeException(String.format(
                            "fill-array-data at 0x%04x points at 0x%04x which is %s",
                            insn.address(), at, data.payloadKind()));
                }
                insn.setArrayData(data.arrayElementWidth(), data.arrayElementCount(),
                        data.arrayData());
            }
        }
    }

    /**
     * Links every {@code move-result*} to the instruction immediately before
     * it, when that instruction is one that sets the result register.
     *
     * <p>Dalvik has no explicit result register operand: an invoke leaves its
     * value in a hidden slot that only an immediately following move-result
     * may read. Modelling that adjacency here (rather than leaving each
     * consumer to look backwards) is what keeps a downstream pass from
     * accidentally reordering, splitting or dropping the pair.
     */
    private static void linkResults(Instruction[] list) {
        for (int i = 1; i < list.length; i++) {
            Instruction insn = list[i];
            if (!insn.isMoveResult()) {
                continue;
            }
            Instruction prev = list[i - 1];
            if (prev.setsResult()) {
                prev.linkResult(insn);
            }
        }
    }

    // ------------------------------------------------------------------
    // Raw reads.
    // ------------------------------------------------------------------

    private static void requireRoom(short[] insns, int pos, int size, String what) {
        if (pos + size > insns.length || size < 0) {
            throw new MalformedCodeException(String.format(
                    "%s at 0x%04x needs %d code units, only %d remain",
                    what, pos, size, insns.length - pos));
        }
    }

    /** Reads code unit {@code at} as an unsigned 16-bit value. */
    private static int u16(short[] insns, int at, int insnPos) {
        if (at < 0 || at >= insns.length) {
            throw new MalformedCodeException(String.format(
                    "instruction at 0x%04x reads code unit 0x%04x, past the end (0x%04x units)",
                    insnPos, at, insns.length));
        }
        return insns[at] & 0xffff;
    }

    /** Reads two code units at {@code at} as a little-endian signed 32-bit value. */
    private static int u32(short[] insns, int at, int insnPos) {
        return u16(insns, at, insnPos) | (u16(insns, at + 1, insnPos) << 16);
    }

    private static long signExtend(int value, int bits) {
        int shift = 32 - bits;
        return (value << shift) >> shift;
    }
}
