package io.github.kksimp.dex2jvm;

/**
 * The Dalvik opcode table: name, instruction format, constant-pool index type
 * and control-flow / operand-width flags for all 256 packed opcodes.
 *
 * <p>Every row in the tables below is emitted mechanically from AOSP's
 * {@code art/libdexfile/dex/dex_instruction_list.h} (the
 * {@code DEX_INSTRUCTION_LIST(V)} macro), so the table is the same data ART
 * itself dispatches on. Fetched from
 * https://android.googlesource.com/platform/art/+/refs/heads/main/libdexfile/dex/dex_instruction_list.h
 * The generator lives outside the repo (a throwaway script); to refresh the
 * tables, re-read that header and re-emit. Do NOT hand-edit a single row:
 * one wrong format id desynchronises every instruction after it, and the
 * failure mode is a silently mistranslated method rather than a clean error.
 *
 * <p>Bit-level operand extraction is in {@link InstructionDecoder}; the
 * per-format bit layouts are cross-checked against
 * {@code art/libdexfile/dex/dex_instruction-inl.h} (the {@code VRegA_*} /
 * {@code VRegB_*} / {@code VRegC_*} accessors) and
 * https://source.android.com/docs/core/runtime/instruction-formats
 */
public final class Opcodes {

    private Opcodes() {
    }

    // ------------------------------------------------------------------
    // Instruction formats.
    // ------------------------------------------------------------------

    /**
     * A Dalvik instruction format. The enum name is the format id from the
     * Android instruction-formats doc with the letters upper-cased
     * ({@code F22C} is format "22c"); {@link #id()} gives the canonical
     * lowercase spelling used in the docs, and {@link #codeUnits()} the fixed
     * instruction length in 16-bit code units.
     *
     * <p>The leading digit of a format id IS its length in code units, which
     * is why {@code codeUnits()} is not stored per opcode. The only variable
     * length instructions are the three NOP-encoded payloads, which are not
     * formats at all (see {@link #PACKED_SWITCH_PAYLOAD}).
     */
    public enum Format {
        F10X("10x", 1),
        F12X("12x", 1),
        F11N("11n", 1),
        F11X("11x", 1),
        F10T("10t", 1),
        F20T("20t", 2),
        F22X("22x", 2),
        F21T("21t", 2),
        F21S("21s", 2),
        F21H("21h", 2),
        F21C("21c", 2),
        F23X("23x", 2),
        F22B("22b", 2),
        F22T("22t", 2),
        F22S("22s", 2),
        F22C("22c", 2),
        F32X("32x", 3),
        F30T("30t", 3),
        F31T("31t", 3),
        F31I("31i", 3),
        F31C("31c", 3),
        F35C("35c", 3),
        F3RC("3rc", 3),
        F45CC("45cc", 4),
        F4RCC("4rcc", 4),
        F51L("51l", 5);

        private final String id;
        private final int codeUnits;

        Format(String id, int codeUnits) {
            this.id = id;
            this.codeUnits = codeUnits;
        }

        /** The canonical lowercase format id, e.g. "22c". */
        public String id() {
            return id;
        }

        /** Fixed instruction length in 16-bit code units. */
        public int codeUnits() {
            return codeUnits;
        }
    }

    // ------------------------------------------------------------------
    // Constant-pool index kinds (AOSP Instruction::IndexType).
    // ------------------------------------------------------------------

    /** Opcode is unused / not a valid instruction. */
    public static final int INDEX_UNKNOWN = 0;
    /** Instruction carries no constant-pool index. */
    public static final int INDEX_NONE = 1;
    /** Index into type_ids. */
    public static final int INDEX_TYPE_REF = 2;
    /** Index into string_ids. */
    public static final int INDEX_STRING_REF = 3;
    /** Index into method_ids. */
    public static final int INDEX_METHOD_REF = 4;
    /** Index into field_ids. */
    public static final int INDEX_FIELD_REF = 5;
    /** Index into method_ids plus a proto_ids index in vH (invoke-polymorphic). */
    public static final int INDEX_METHOD_AND_PROTO_REF = 6;
    /** Index into call_site_ids (invoke-custom). */
    public static final int INDEX_CALL_SITE_REF = 7;
    /** Index into method_handles (const-method-handle). */
    public static final int INDEX_METHOD_HANDLE_REF = 8;
    /** Index into proto_ids (const-method-type). */
    public static final int INDEX_PROTO_REF = 9;

    // ------------------------------------------------------------------
    // Per-opcode flags. The first seven mirror AOSP Instruction::Flags; the
    // WIDE_* bits are distilled from that table's kVerifyReg?Wide verify
    // flags so consumers do not need a second switch to learn which operand
    // names a register PAIR (vN, vN+1).
    // ------------------------------------------------------------------

    /** Conditional or unconditional branch (AOSP kBranch). */
    public static final int FLAG_BRANCH = 0x01;
    /** Execution can continue into the following instruction (AOSP kContinue). */
    public static final int FLAG_CONTINUE = 0x02;
    /** packed-switch / sparse-switch (AOSP kSwitch). */
    public static final int FLAG_SWITCH = 0x04;
    /** May throw (AOSP kThrow). */
    public static final int FLAG_THROW = 0x08;
    /** Returns from the method (AOSP kReturn). */
    public static final int FLAG_RETURN = 0x10;
    /** Some flavour of invoke (AOSP kInvoke). */
    public static final int FLAG_INVOKE = 0x20;
    /** Branch is unconditional, i.e. a goto (AOSP kUnconditional). */
    public static final int FLAG_UNCONDITIONAL = 0x40;
    /** vA names a register pair. */
    public static final int FLAG_WIDE_A = 0x80;
    /** vB names a register pair. */
    public static final int FLAG_WIDE_B = 0x100;
    /** vC names a register pair. */
    public static final int FLAG_WIDE_C = 0x200;
    /** Opcode is not assigned in any DEX version we accept. */
    public static final int FLAG_UNUSED = 0x400;

    // ------------------------------------------------------------------
    // NOP-encoded payload signatures. These are not opcodes: they are the
    // whole first code unit of a pseudo-instruction whose low byte is NOP.
    // See AOSP Instruction::Signatures.
    // ------------------------------------------------------------------

    /** First code unit of a packed-switch-payload. */
    public static final int PACKED_SWITCH_PAYLOAD = 0x0100;
    /** First code unit of a sparse-switch-payload. */
    public static final int SPARSE_SWITCH_PAYLOAD = 0x0200;
    /** First code unit of a fill-array-data-payload. */
    public static final int ARRAY_DATA_PAYLOAD = 0x0300;

    /** Maximum register count encodable in a 35c argument list. */
    public static final int MAX_VAR_ARG_REGS = 5;

    // ------------------------------------------------------------------
    // Opcode constants.
    // ------------------------------------------------------------------

    /** nop (10x) */
    public static final int NOP = 0x00;
    /** move (12x) */
    public static final int MOVE = 0x01;
    /** move/from16 (22x) */
    public static final int MOVE_FROM16 = 0x02;
    /** move/16 (32x) */
    public static final int MOVE_16 = 0x03;
    /** move-wide (12x) */
    public static final int MOVE_WIDE = 0x04;
    /** move-wide/from16 (22x) */
    public static final int MOVE_WIDE_FROM16 = 0x05;
    /** move-wide/16 (32x) */
    public static final int MOVE_WIDE_16 = 0x06;
    /** move-object (12x) */
    public static final int MOVE_OBJECT = 0x07;
    /** move-object/from16 (22x) */
    public static final int MOVE_OBJECT_FROM16 = 0x08;
    /** move-object/16 (32x) */
    public static final int MOVE_OBJECT_16 = 0x09;
    /** move-result (11x) */
    public static final int MOVE_RESULT = 0x0a;
    /** move-result-wide (11x) */
    public static final int MOVE_RESULT_WIDE = 0x0b;
    /** move-result-object (11x) */
    public static final int MOVE_RESULT_OBJECT = 0x0c;
    /** move-exception (11x) */
    public static final int MOVE_EXCEPTION = 0x0d;
    /** return-void (10x) */
    public static final int RETURN_VOID = 0x0e;
    /** return (11x) */
    public static final int RETURN = 0x0f;
    /** return-wide (11x) */
    public static final int RETURN_WIDE = 0x10;
    /** return-object (11x) */
    public static final int RETURN_OBJECT = 0x11;
    /** const/4 (11n) */
    public static final int CONST_4 = 0x12;
    /** const/16 (21s) */
    public static final int CONST_16 = 0x13;
    /** const (31i) */
    public static final int CONST = 0x14;
    /** const/high16 (21h) */
    public static final int CONST_HIGH16 = 0x15;
    /** const-wide/16 (21s) */
    public static final int CONST_WIDE_16 = 0x16;
    /** const-wide/32 (31i) */
    public static final int CONST_WIDE_32 = 0x17;
    /** const-wide (51l) */
    public static final int CONST_WIDE = 0x18;
    /** const-wide/high16 (21h) */
    public static final int CONST_WIDE_HIGH16 = 0x19;
    /** const-string (21c) */
    public static final int CONST_STRING = 0x1a;
    /** const-string/jumbo (31c) */
    public static final int CONST_STRING_JUMBO = 0x1b;
    /** const-class (21c) */
    public static final int CONST_CLASS = 0x1c;
    /** monitor-enter (11x) */
    public static final int MONITOR_ENTER = 0x1d;
    /** monitor-exit (11x) */
    public static final int MONITOR_EXIT = 0x1e;
    /** check-cast (21c) */
    public static final int CHECK_CAST = 0x1f;
    /** instance-of (22c) */
    public static final int INSTANCE_OF = 0x20;
    /** array-length (12x) */
    public static final int ARRAY_LENGTH = 0x21;
    /** new-instance (21c) */
    public static final int NEW_INSTANCE = 0x22;
    /** new-array (22c) */
    public static final int NEW_ARRAY = 0x23;
    /** filled-new-array (35c) */
    public static final int FILLED_NEW_ARRAY = 0x24;
    /** filled-new-array/range (3rc) */
    public static final int FILLED_NEW_ARRAY_RANGE = 0x25;
    /** fill-array-data (31t) */
    public static final int FILL_ARRAY_DATA = 0x26;
    /** throw (11x) */
    public static final int THROW = 0x27;
    /** goto (10t) */
    public static final int GOTO = 0x28;
    /** goto/16 (20t) */
    public static final int GOTO_16 = 0x29;
    /** goto/32 (30t) */
    public static final int GOTO_32 = 0x2a;
    /** packed-switch (31t) */
    public static final int PACKED_SWITCH = 0x2b;
    /** sparse-switch (31t) */
    public static final int SPARSE_SWITCH = 0x2c;
    /** cmpl-float (23x) */
    public static final int CMPL_FLOAT = 0x2d;
    /** cmpg-float (23x) */
    public static final int CMPG_FLOAT = 0x2e;
    /** cmpl-double (23x) */
    public static final int CMPL_DOUBLE = 0x2f;
    /** cmpg-double (23x) */
    public static final int CMPG_DOUBLE = 0x30;
    /** cmp-long (23x) */
    public static final int CMP_LONG = 0x31;
    /** if-eq (22t) */
    public static final int IF_EQ = 0x32;
    /** if-ne (22t) */
    public static final int IF_NE = 0x33;
    /** if-lt (22t) */
    public static final int IF_LT = 0x34;
    /** if-ge (22t) */
    public static final int IF_GE = 0x35;
    /** if-gt (22t) */
    public static final int IF_GT = 0x36;
    /** if-le (22t) */
    public static final int IF_LE = 0x37;
    /** if-eqz (21t) */
    public static final int IF_EQZ = 0x38;
    /** if-nez (21t) */
    public static final int IF_NEZ = 0x39;
    /** if-ltz (21t) */
    public static final int IF_LTZ = 0x3a;
    /** if-gez (21t) */
    public static final int IF_GEZ = 0x3b;
    /** if-gtz (21t) */
    public static final int IF_GTZ = 0x3c;
    /** if-lez (21t) */
    public static final int IF_LEZ = 0x3d;
    /** aget (23x) */
    public static final int AGET = 0x44;
    /** aget-wide (23x) */
    public static final int AGET_WIDE = 0x45;
    /** aget-object (23x) */
    public static final int AGET_OBJECT = 0x46;
    /** aget-boolean (23x) */
    public static final int AGET_BOOLEAN = 0x47;
    /** aget-byte (23x) */
    public static final int AGET_BYTE = 0x48;
    /** aget-char (23x) */
    public static final int AGET_CHAR = 0x49;
    /** aget-short (23x) */
    public static final int AGET_SHORT = 0x4a;
    /** aput (23x) */
    public static final int APUT = 0x4b;
    /** aput-wide (23x) */
    public static final int APUT_WIDE = 0x4c;
    /** aput-object (23x) */
    public static final int APUT_OBJECT = 0x4d;
    /** aput-boolean (23x) */
    public static final int APUT_BOOLEAN = 0x4e;
    /** aput-byte (23x) */
    public static final int APUT_BYTE = 0x4f;
    /** aput-char (23x) */
    public static final int APUT_CHAR = 0x50;
    /** aput-short (23x) */
    public static final int APUT_SHORT = 0x51;
    /** iget (22c) */
    public static final int IGET = 0x52;
    /** iget-wide (22c) */
    public static final int IGET_WIDE = 0x53;
    /** iget-object (22c) */
    public static final int IGET_OBJECT = 0x54;
    /** iget-boolean (22c) */
    public static final int IGET_BOOLEAN = 0x55;
    /** iget-byte (22c) */
    public static final int IGET_BYTE = 0x56;
    /** iget-char (22c) */
    public static final int IGET_CHAR = 0x57;
    /** iget-short (22c) */
    public static final int IGET_SHORT = 0x58;
    /** iput (22c) */
    public static final int IPUT = 0x59;
    /** iput-wide (22c) */
    public static final int IPUT_WIDE = 0x5a;
    /** iput-object (22c) */
    public static final int IPUT_OBJECT = 0x5b;
    /** iput-boolean (22c) */
    public static final int IPUT_BOOLEAN = 0x5c;
    /** iput-byte (22c) */
    public static final int IPUT_BYTE = 0x5d;
    /** iput-char (22c) */
    public static final int IPUT_CHAR = 0x5e;
    /** iput-short (22c) */
    public static final int IPUT_SHORT = 0x5f;
    /** sget (21c) */
    public static final int SGET = 0x60;
    /** sget-wide (21c) */
    public static final int SGET_WIDE = 0x61;
    /** sget-object (21c) */
    public static final int SGET_OBJECT = 0x62;
    /** sget-boolean (21c) */
    public static final int SGET_BOOLEAN = 0x63;
    /** sget-byte (21c) */
    public static final int SGET_BYTE = 0x64;
    /** sget-char (21c) */
    public static final int SGET_CHAR = 0x65;
    /** sget-short (21c) */
    public static final int SGET_SHORT = 0x66;
    /** sput (21c) */
    public static final int SPUT = 0x67;
    /** sput-wide (21c) */
    public static final int SPUT_WIDE = 0x68;
    /** sput-object (21c) */
    public static final int SPUT_OBJECT = 0x69;
    /** sput-boolean (21c) */
    public static final int SPUT_BOOLEAN = 0x6a;
    /** sput-byte (21c) */
    public static final int SPUT_BYTE = 0x6b;
    /** sput-char (21c) */
    public static final int SPUT_CHAR = 0x6c;
    /** sput-short (21c) */
    public static final int SPUT_SHORT = 0x6d;
    /** invoke-virtual (35c) */
    public static final int INVOKE_VIRTUAL = 0x6e;
    /** invoke-super (35c) */
    public static final int INVOKE_SUPER = 0x6f;
    /** invoke-direct (35c) */
    public static final int INVOKE_DIRECT = 0x70;
    /** invoke-static (35c) */
    public static final int INVOKE_STATIC = 0x71;
    /** invoke-interface (35c) */
    public static final int INVOKE_INTERFACE = 0x72;
    /** invoke-virtual/range (3rc) */
    public static final int INVOKE_VIRTUAL_RANGE = 0x74;
    /** invoke-super/range (3rc) */
    public static final int INVOKE_SUPER_RANGE = 0x75;
    /** invoke-direct/range (3rc) */
    public static final int INVOKE_DIRECT_RANGE = 0x76;
    /** invoke-static/range (3rc) */
    public static final int INVOKE_STATIC_RANGE = 0x77;
    /** invoke-interface/range (3rc) */
    public static final int INVOKE_INTERFACE_RANGE = 0x78;
    /** neg-int (12x) */
    public static final int NEG_INT = 0x7b;
    /** not-int (12x) */
    public static final int NOT_INT = 0x7c;
    /** neg-long (12x) */
    public static final int NEG_LONG = 0x7d;
    /** not-long (12x) */
    public static final int NOT_LONG = 0x7e;
    /** neg-float (12x) */
    public static final int NEG_FLOAT = 0x7f;
    /** neg-double (12x) */
    public static final int NEG_DOUBLE = 0x80;
    /** int-to-long (12x) */
    public static final int INT_TO_LONG = 0x81;
    /** int-to-float (12x) */
    public static final int INT_TO_FLOAT = 0x82;
    /** int-to-double (12x) */
    public static final int INT_TO_DOUBLE = 0x83;
    /** long-to-int (12x) */
    public static final int LONG_TO_INT = 0x84;
    /** long-to-float (12x) */
    public static final int LONG_TO_FLOAT = 0x85;
    /** long-to-double (12x) */
    public static final int LONG_TO_DOUBLE = 0x86;
    /** float-to-int (12x) */
    public static final int FLOAT_TO_INT = 0x87;
    /** float-to-long (12x) */
    public static final int FLOAT_TO_LONG = 0x88;
    /** float-to-double (12x) */
    public static final int FLOAT_TO_DOUBLE = 0x89;
    /** double-to-int (12x) */
    public static final int DOUBLE_TO_INT = 0x8a;
    /** double-to-long (12x) */
    public static final int DOUBLE_TO_LONG = 0x8b;
    /** double-to-float (12x) */
    public static final int DOUBLE_TO_FLOAT = 0x8c;
    /** int-to-byte (12x) */
    public static final int INT_TO_BYTE = 0x8d;
    /** int-to-char (12x) */
    public static final int INT_TO_CHAR = 0x8e;
    /** int-to-short (12x) */
    public static final int INT_TO_SHORT = 0x8f;
    /** add-int (23x) */
    public static final int ADD_INT = 0x90;
    /** sub-int (23x) */
    public static final int SUB_INT = 0x91;
    /** mul-int (23x) */
    public static final int MUL_INT = 0x92;
    /** div-int (23x) */
    public static final int DIV_INT = 0x93;
    /** rem-int (23x) */
    public static final int REM_INT = 0x94;
    /** and-int (23x) */
    public static final int AND_INT = 0x95;
    /** or-int (23x) */
    public static final int OR_INT = 0x96;
    /** xor-int (23x) */
    public static final int XOR_INT = 0x97;
    /** shl-int (23x) */
    public static final int SHL_INT = 0x98;
    /** shr-int (23x) */
    public static final int SHR_INT = 0x99;
    /** ushr-int (23x) */
    public static final int USHR_INT = 0x9a;
    /** add-long (23x) */
    public static final int ADD_LONG = 0x9b;
    /** sub-long (23x) */
    public static final int SUB_LONG = 0x9c;
    /** mul-long (23x) */
    public static final int MUL_LONG = 0x9d;
    /** div-long (23x) */
    public static final int DIV_LONG = 0x9e;
    /** rem-long (23x) */
    public static final int REM_LONG = 0x9f;
    /** and-long (23x) */
    public static final int AND_LONG = 0xa0;
    /** or-long (23x) */
    public static final int OR_LONG = 0xa1;
    /** xor-long (23x) */
    public static final int XOR_LONG = 0xa2;
    /** shl-long (23x) */
    public static final int SHL_LONG = 0xa3;
    /** shr-long (23x) */
    public static final int SHR_LONG = 0xa4;
    /** ushr-long (23x) */
    public static final int USHR_LONG = 0xa5;
    /** add-float (23x) */
    public static final int ADD_FLOAT = 0xa6;
    /** sub-float (23x) */
    public static final int SUB_FLOAT = 0xa7;
    /** mul-float (23x) */
    public static final int MUL_FLOAT = 0xa8;
    /** div-float (23x) */
    public static final int DIV_FLOAT = 0xa9;
    /** rem-float (23x) */
    public static final int REM_FLOAT = 0xaa;
    /** add-double (23x) */
    public static final int ADD_DOUBLE = 0xab;
    /** sub-double (23x) */
    public static final int SUB_DOUBLE = 0xac;
    /** mul-double (23x) */
    public static final int MUL_DOUBLE = 0xad;
    /** div-double (23x) */
    public static final int DIV_DOUBLE = 0xae;
    /** rem-double (23x) */
    public static final int REM_DOUBLE = 0xaf;
    /** add-int/2addr (12x) */
    public static final int ADD_INT_2ADDR = 0xb0;
    /** sub-int/2addr (12x) */
    public static final int SUB_INT_2ADDR = 0xb1;
    /** mul-int/2addr (12x) */
    public static final int MUL_INT_2ADDR = 0xb2;
    /** div-int/2addr (12x) */
    public static final int DIV_INT_2ADDR = 0xb3;
    /** rem-int/2addr (12x) */
    public static final int REM_INT_2ADDR = 0xb4;
    /** and-int/2addr (12x) */
    public static final int AND_INT_2ADDR = 0xb5;
    /** or-int/2addr (12x) */
    public static final int OR_INT_2ADDR = 0xb6;
    /** xor-int/2addr (12x) */
    public static final int XOR_INT_2ADDR = 0xb7;
    /** shl-int/2addr (12x) */
    public static final int SHL_INT_2ADDR = 0xb8;
    /** shr-int/2addr (12x) */
    public static final int SHR_INT_2ADDR = 0xb9;
    /** ushr-int/2addr (12x) */
    public static final int USHR_INT_2ADDR = 0xba;
    /** add-long/2addr (12x) */
    public static final int ADD_LONG_2ADDR = 0xbb;
    /** sub-long/2addr (12x) */
    public static final int SUB_LONG_2ADDR = 0xbc;
    /** mul-long/2addr (12x) */
    public static final int MUL_LONG_2ADDR = 0xbd;
    /** div-long/2addr (12x) */
    public static final int DIV_LONG_2ADDR = 0xbe;
    /** rem-long/2addr (12x) */
    public static final int REM_LONG_2ADDR = 0xbf;
    /** and-long/2addr (12x) */
    public static final int AND_LONG_2ADDR = 0xc0;
    /** or-long/2addr (12x) */
    public static final int OR_LONG_2ADDR = 0xc1;
    /** xor-long/2addr (12x) */
    public static final int XOR_LONG_2ADDR = 0xc2;
    /** shl-long/2addr (12x) */
    public static final int SHL_LONG_2ADDR = 0xc3;
    /** shr-long/2addr (12x) */
    public static final int SHR_LONG_2ADDR = 0xc4;
    /** ushr-long/2addr (12x) */
    public static final int USHR_LONG_2ADDR = 0xc5;
    /** add-float/2addr (12x) */
    public static final int ADD_FLOAT_2ADDR = 0xc6;
    /** sub-float/2addr (12x) */
    public static final int SUB_FLOAT_2ADDR = 0xc7;
    /** mul-float/2addr (12x) */
    public static final int MUL_FLOAT_2ADDR = 0xc8;
    /** div-float/2addr (12x) */
    public static final int DIV_FLOAT_2ADDR = 0xc9;
    /** rem-float/2addr (12x) */
    public static final int REM_FLOAT_2ADDR = 0xca;
    /** add-double/2addr (12x) */
    public static final int ADD_DOUBLE_2ADDR = 0xcb;
    /** sub-double/2addr (12x) */
    public static final int SUB_DOUBLE_2ADDR = 0xcc;
    /** mul-double/2addr (12x) */
    public static final int MUL_DOUBLE_2ADDR = 0xcd;
    /** div-double/2addr (12x) */
    public static final int DIV_DOUBLE_2ADDR = 0xce;
    /** rem-double/2addr (12x) */
    public static final int REM_DOUBLE_2ADDR = 0xcf;
    /** add-int/lit16 (22s) */
    public static final int ADD_INT_LIT16 = 0xd0;
    /** rsub-int (22s) */
    public static final int RSUB_INT = 0xd1;
    /** mul-int/lit16 (22s) */
    public static final int MUL_INT_LIT16 = 0xd2;
    /** div-int/lit16 (22s) */
    public static final int DIV_INT_LIT16 = 0xd3;
    /** rem-int/lit16 (22s) */
    public static final int REM_INT_LIT16 = 0xd4;
    /** and-int/lit16 (22s) */
    public static final int AND_INT_LIT16 = 0xd5;
    /** or-int/lit16 (22s) */
    public static final int OR_INT_LIT16 = 0xd6;
    /** xor-int/lit16 (22s) */
    public static final int XOR_INT_LIT16 = 0xd7;
    /** add-int/lit8 (22b) */
    public static final int ADD_INT_LIT8 = 0xd8;
    /** rsub-int/lit8 (22b) */
    public static final int RSUB_INT_LIT8 = 0xd9;
    /** mul-int/lit8 (22b) */
    public static final int MUL_INT_LIT8 = 0xda;
    /** div-int/lit8 (22b) */
    public static final int DIV_INT_LIT8 = 0xdb;
    /** rem-int/lit8 (22b) */
    public static final int REM_INT_LIT8 = 0xdc;
    /** and-int/lit8 (22b) */
    public static final int AND_INT_LIT8 = 0xdd;
    /** or-int/lit8 (22b) */
    public static final int OR_INT_LIT8 = 0xde;
    /** xor-int/lit8 (22b) */
    public static final int XOR_INT_LIT8 = 0xdf;
    /** shl-int/lit8 (22b) */
    public static final int SHL_INT_LIT8 = 0xe0;
    /** shr-int/lit8 (22b) */
    public static final int SHR_INT_LIT8 = 0xe1;
    /** ushr-int/lit8 (22b) */
    public static final int USHR_INT_LIT8 = 0xe2;
    /** invoke-polymorphic (45cc) */
    public static final int INVOKE_POLYMORPHIC = 0xfa;
    /** invoke-polymorphic/range (4rcc) */
    public static final int INVOKE_POLYMORPHIC_RANGE = 0xfb;
    /** invoke-custom (35c) */
    public static final int INVOKE_CUSTOM = 0xfc;
    /** invoke-custom/range (3rc) */
    public static final int INVOKE_CUSTOM_RANGE = 0xfd;
    /** const-method-handle (21c) */
    public static final int CONST_METHOD_HANDLE = 0xfe;
    /** const-method-type (21c) */
    public static final int CONST_METHOD_TYPE = 0xff;

    // ------------------------------------------------------------------
    // Tables (generated, index = opcode).
    // ------------------------------------------------------------------

    private static final String[] NAMES = {
        "nop", "move", "move/from16", "move/16",
        "move-wide", "move-wide/from16", "move-wide/16", "move-object",
        "move-object/from16", "move-object/16", "move-result", "move-result-wide",
        "move-result-object", "move-exception", "return-void", "return",
        "return-wide", "return-object", "const/4", "const/16",
        "const", "const/high16", "const-wide/16", "const-wide/32",
        "const-wide", "const-wide/high16", "const-string", "const-string/jumbo",
        "const-class", "monitor-enter", "monitor-exit", "check-cast",
        "instance-of", "array-length", "new-instance", "new-array",
        "filled-new-array", "filled-new-array/range", "fill-array-data", "throw",
        "goto", "goto/16", "goto/32", "packed-switch",
        "sparse-switch", "cmpl-float", "cmpg-float", "cmpl-double",
        "cmpg-double", "cmp-long", "if-eq", "if-ne",
        "if-lt", "if-ge", "if-gt", "if-le",
        "if-eqz", "if-nez", "if-ltz", "if-gez",
        "if-gtz", "if-lez", "unused-3e", "unused-3f",
        "unused-40", "unused-41", "unused-42", "unused-43",
        "aget", "aget-wide", "aget-object", "aget-boolean",
        "aget-byte", "aget-char", "aget-short", "aput",
        "aput-wide", "aput-object", "aput-boolean", "aput-byte",
        "aput-char", "aput-short", "iget", "iget-wide",
        "iget-object", "iget-boolean", "iget-byte", "iget-char",
        "iget-short", "iput", "iput-wide", "iput-object",
        "iput-boolean", "iput-byte", "iput-char", "iput-short",
        "sget", "sget-wide", "sget-object", "sget-boolean",
        "sget-byte", "sget-char", "sget-short", "sput",
        "sput-wide", "sput-object", "sput-boolean", "sput-byte",
        "sput-char", "sput-short", "invoke-virtual", "invoke-super",
        "invoke-direct", "invoke-static", "invoke-interface", "unused-73",
        "invoke-virtual/range", "invoke-super/range", "invoke-direct/range", "invoke-static/range",
        "invoke-interface/range", "unused-79", "unused-7a", "neg-int",
        "not-int", "neg-long", "not-long", "neg-float",
        "neg-double", "int-to-long", "int-to-float", "int-to-double",
        "long-to-int", "long-to-float", "long-to-double", "float-to-int",
        "float-to-long", "float-to-double", "double-to-int", "double-to-long",
        "double-to-float", "int-to-byte", "int-to-char", "int-to-short",
        "add-int", "sub-int", "mul-int", "div-int",
        "rem-int", "and-int", "or-int", "xor-int",
        "shl-int", "shr-int", "ushr-int", "add-long",
        "sub-long", "mul-long", "div-long", "rem-long",
        "and-long", "or-long", "xor-long", "shl-long",
        "shr-long", "ushr-long", "add-float", "sub-float",
        "mul-float", "div-float", "rem-float", "add-double",
        "sub-double", "mul-double", "div-double", "rem-double",
        "add-int/2addr", "sub-int/2addr", "mul-int/2addr", "div-int/2addr",
        "rem-int/2addr", "and-int/2addr", "or-int/2addr", "xor-int/2addr",
        "shl-int/2addr", "shr-int/2addr", "ushr-int/2addr", "add-long/2addr",
        "sub-long/2addr", "mul-long/2addr", "div-long/2addr", "rem-long/2addr",
        "and-long/2addr", "or-long/2addr", "xor-long/2addr", "shl-long/2addr",
        "shr-long/2addr", "ushr-long/2addr", "add-float/2addr", "sub-float/2addr",
        "mul-float/2addr", "div-float/2addr", "rem-float/2addr", "add-double/2addr",
        "sub-double/2addr", "mul-double/2addr", "div-double/2addr", "rem-double/2addr",
        "add-int/lit16", "rsub-int", "mul-int/lit16", "div-int/lit16",
        "rem-int/lit16", "and-int/lit16", "or-int/lit16", "xor-int/lit16",
        "add-int/lit8", "rsub-int/lit8", "mul-int/lit8", "div-int/lit8",
        "rem-int/lit8", "and-int/lit8", "or-int/lit8", "xor-int/lit8",
        "shl-int/lit8", "shr-int/lit8", "ushr-int/lit8", "unused-e3",
        "unused-e4", "unused-e5", "unused-e6", "unused-e7",
        "unused-e8", "unused-e9", "unused-ea", "unused-eb",
        "unused-ec", "unused-ed", "unused-ee", "unused-ef",
        "unused-f0", "unused-f1", "unused-f2", "unused-f3",
        "unused-f4", "unused-f5", "unused-f6", "unused-f7",
        "unused-f8", "unused-f9", "invoke-polymorphic", "invoke-polymorphic/range",
        "invoke-custom", "invoke-custom/range", "const-method-handle", "const-method-type",
    };

    private static final Format[] FORMATS = {
        Format.F10X, Format.F12X, Format.F22X, Format.F32X, Format.F12X, Format.F22X,
        Format.F32X, Format.F12X, Format.F22X, Format.F32X, Format.F11X, Format.F11X,
        Format.F11X, Format.F11X, Format.F10X, Format.F11X, Format.F11X, Format.F11X,
        Format.F11N, Format.F21S, Format.F31I, Format.F21H, Format.F21S, Format.F31I,
        Format.F51L, Format.F21H, Format.F21C, Format.F31C, Format.F21C, Format.F11X,
        Format.F11X, Format.F21C, Format.F22C, Format.F12X, Format.F21C, Format.F22C,
        Format.F35C, Format.F3RC, Format.F31T, Format.F11X, Format.F10T, Format.F20T,
        Format.F30T, Format.F31T, Format.F31T, Format.F23X, Format.F23X, Format.F23X,
        Format.F23X, Format.F23X, Format.F22T, Format.F22T, Format.F22T, Format.F22T,
        Format.F22T, Format.F22T, Format.F21T, Format.F21T, Format.F21T, Format.F21T,
        Format.F21T, Format.F21T, Format.F10X, Format.F10X, Format.F10X, Format.F10X,
        Format.F10X, Format.F10X, Format.F23X, Format.F23X, Format.F23X, Format.F23X,
        Format.F23X, Format.F23X, Format.F23X, Format.F23X, Format.F23X, Format.F23X,
        Format.F23X, Format.F23X, Format.F23X, Format.F23X, Format.F22C, Format.F22C,
        Format.F22C, Format.F22C, Format.F22C, Format.F22C, Format.F22C, Format.F22C,
        Format.F22C, Format.F22C, Format.F22C, Format.F22C, Format.F22C, Format.F22C,
        Format.F21C, Format.F21C, Format.F21C, Format.F21C, Format.F21C, Format.F21C,
        Format.F21C, Format.F21C, Format.F21C, Format.F21C, Format.F21C, Format.F21C,
        Format.F21C, Format.F21C, Format.F35C, Format.F35C, Format.F35C, Format.F35C,
        Format.F35C, Format.F10X, Format.F3RC, Format.F3RC, Format.F3RC, Format.F3RC,
        Format.F3RC, Format.F10X, Format.F10X, Format.F12X, Format.F12X, Format.F12X,
        Format.F12X, Format.F12X, Format.F12X, Format.F12X, Format.F12X, Format.F12X,
        Format.F12X, Format.F12X, Format.F12X, Format.F12X, Format.F12X, Format.F12X,
        Format.F12X, Format.F12X, Format.F12X, Format.F12X, Format.F12X, Format.F12X,
        Format.F23X, Format.F23X, Format.F23X, Format.F23X, Format.F23X, Format.F23X,
        Format.F23X, Format.F23X, Format.F23X, Format.F23X, Format.F23X, Format.F23X,
        Format.F23X, Format.F23X, Format.F23X, Format.F23X, Format.F23X, Format.F23X,
        Format.F23X, Format.F23X, Format.F23X, Format.F23X, Format.F23X, Format.F23X,
        Format.F23X, Format.F23X, Format.F23X, Format.F23X, Format.F23X, Format.F23X,
        Format.F23X, Format.F23X, Format.F12X, Format.F12X, Format.F12X, Format.F12X,
        Format.F12X, Format.F12X, Format.F12X, Format.F12X, Format.F12X, Format.F12X,
        Format.F12X, Format.F12X, Format.F12X, Format.F12X, Format.F12X, Format.F12X,
        Format.F12X, Format.F12X, Format.F12X, Format.F12X, Format.F12X, Format.F12X,
        Format.F12X, Format.F12X, Format.F12X, Format.F12X, Format.F12X, Format.F12X,
        Format.F12X, Format.F12X, Format.F12X, Format.F12X, Format.F22S, Format.F22S,
        Format.F22S, Format.F22S, Format.F22S, Format.F22S, Format.F22S, Format.F22S,
        Format.F22B, Format.F22B, Format.F22B, Format.F22B, Format.F22B, Format.F22B,
        Format.F22B, Format.F22B, Format.F22B, Format.F22B, Format.F22B, Format.F10X,
        Format.F10X, Format.F10X, Format.F10X, Format.F10X, Format.F10X, Format.F10X,
        Format.F10X, Format.F10X, Format.F10X, Format.F10X, Format.F10X, Format.F10X,
        Format.F10X, Format.F10X, Format.F10X, Format.F10X, Format.F10X, Format.F10X,
        Format.F10X, Format.F10X, Format.F10X, Format.F10X, Format.F45CC, Format.F4RCC,
        Format.F35C, Format.F3RC, Format.F21C, Format.F21C,
    };

    private static final int[] INDEX_TYPES = {
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_STRING_REF, INDEX_STRING_REF,
        INDEX_TYPE_REF, INDEX_NONE, INDEX_NONE, INDEX_TYPE_REF,
        INDEX_TYPE_REF, INDEX_NONE, INDEX_TYPE_REF, INDEX_TYPE_REF,
        INDEX_TYPE_REF, INDEX_TYPE_REF, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_UNKNOWN, INDEX_UNKNOWN,
        INDEX_UNKNOWN, INDEX_UNKNOWN, INDEX_UNKNOWN, INDEX_UNKNOWN,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_FIELD_REF, INDEX_FIELD_REF,
        INDEX_FIELD_REF, INDEX_FIELD_REF, INDEX_FIELD_REF, INDEX_FIELD_REF,
        INDEX_FIELD_REF, INDEX_FIELD_REF, INDEX_FIELD_REF, INDEX_FIELD_REF,
        INDEX_FIELD_REF, INDEX_FIELD_REF, INDEX_FIELD_REF, INDEX_FIELD_REF,
        INDEX_FIELD_REF, INDEX_FIELD_REF, INDEX_FIELD_REF, INDEX_FIELD_REF,
        INDEX_FIELD_REF, INDEX_FIELD_REF, INDEX_FIELD_REF, INDEX_FIELD_REF,
        INDEX_FIELD_REF, INDEX_FIELD_REF, INDEX_FIELD_REF, INDEX_FIELD_REF,
        INDEX_FIELD_REF, INDEX_FIELD_REF, INDEX_METHOD_REF, INDEX_METHOD_REF,
        INDEX_METHOD_REF, INDEX_METHOD_REF, INDEX_METHOD_REF, INDEX_UNKNOWN,
        INDEX_METHOD_REF, INDEX_METHOD_REF, INDEX_METHOD_REF, INDEX_METHOD_REF,
        INDEX_METHOD_REF, INDEX_UNKNOWN, INDEX_UNKNOWN, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_NONE,
        INDEX_NONE, INDEX_NONE, INDEX_NONE, INDEX_UNKNOWN,
        INDEX_UNKNOWN, INDEX_UNKNOWN, INDEX_UNKNOWN, INDEX_UNKNOWN,
        INDEX_UNKNOWN, INDEX_UNKNOWN, INDEX_UNKNOWN, INDEX_UNKNOWN,
        INDEX_UNKNOWN, INDEX_UNKNOWN, INDEX_UNKNOWN, INDEX_UNKNOWN,
        INDEX_UNKNOWN, INDEX_UNKNOWN, INDEX_UNKNOWN, INDEX_UNKNOWN,
        INDEX_UNKNOWN, INDEX_UNKNOWN, INDEX_UNKNOWN, INDEX_UNKNOWN,
        INDEX_UNKNOWN, INDEX_UNKNOWN, INDEX_METHOD_AND_PROTO_REF, INDEX_METHOD_AND_PROTO_REF,
        INDEX_CALL_SITE_REF, INDEX_CALL_SITE_REF, INDEX_METHOD_HANDLE_REF, INDEX_PROTO_REF,
    };

    private static final int[] FLAGS = {
        /* 0x00 nop                      */ FLAG_CONTINUE,
        /* 0x01 move                     */ FLAG_CONTINUE,
        /* 0x02 move/from16              */ FLAG_CONTINUE,
        /* 0x03 move/16                  */ FLAG_CONTINUE,
        /* 0x04 move-wide                */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B,
        /* 0x05 move-wide/from16         */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B,
        /* 0x06 move-wide/16             */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B,
        /* 0x07 move-object              */ FLAG_CONTINUE,
        /* 0x08 move-object/from16       */ FLAG_CONTINUE,
        /* 0x09 move-object/16           */ FLAG_CONTINUE,
        /* 0x0a move-result              */ FLAG_CONTINUE,
        /* 0x0b move-result-wide         */ FLAG_CONTINUE | FLAG_WIDE_A,
        /* 0x0c move-result-object       */ FLAG_CONTINUE,
        /* 0x0d move-exception           */ FLAG_CONTINUE,
        /* 0x0e return-void              */ FLAG_RETURN,
        /* 0x0f return                   */ FLAG_RETURN,
        /* 0x10 return-wide              */ FLAG_RETURN | FLAG_WIDE_A,
        /* 0x11 return-object            */ FLAG_RETURN,
        /* 0x12 const/4                  */ FLAG_CONTINUE,
        /* 0x13 const/16                 */ FLAG_CONTINUE,
        /* 0x14 const                    */ FLAG_CONTINUE,
        /* 0x15 const/high16             */ FLAG_CONTINUE,
        /* 0x16 const-wide/16            */ FLAG_CONTINUE | FLAG_WIDE_A,
        /* 0x17 const-wide/32            */ FLAG_CONTINUE | FLAG_WIDE_A,
        /* 0x18 const-wide               */ FLAG_CONTINUE | FLAG_WIDE_A,
        /* 0x19 const-wide/high16        */ FLAG_CONTINUE | FLAG_WIDE_A,
        /* 0x1a const-string             */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x1b const-string/jumbo       */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x1c const-class              */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x1d monitor-enter            */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x1e monitor-exit             */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x1f check-cast               */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x20 instance-of              */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x21 array-length             */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x22 new-instance             */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x23 new-array                */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x24 filled-new-array         */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x25 filled-new-array/range   */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x26 fill-array-data          */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x27 throw                    */ FLAG_THROW,
        /* 0x28 goto                     */ FLAG_BRANCH | FLAG_UNCONDITIONAL,
        /* 0x29 goto/16                  */ FLAG_BRANCH | FLAG_UNCONDITIONAL,
        /* 0x2a goto/32                  */ FLAG_BRANCH | FLAG_UNCONDITIONAL,
        /* 0x2b packed-switch            */ FLAG_CONTINUE | FLAG_SWITCH,
        /* 0x2c sparse-switch            */ FLAG_CONTINUE | FLAG_SWITCH,
        /* 0x2d cmpl-float               */ FLAG_CONTINUE,
        /* 0x2e cmpg-float               */ FLAG_CONTINUE,
        /* 0x2f cmpl-double              */ FLAG_CONTINUE | FLAG_WIDE_B | FLAG_WIDE_C,
        /* 0x30 cmpg-double              */ FLAG_CONTINUE | FLAG_WIDE_B | FLAG_WIDE_C,
        /* 0x31 cmp-long                 */ FLAG_CONTINUE | FLAG_WIDE_B | FLAG_WIDE_C,
        /* 0x32 if-eq                    */ FLAG_CONTINUE | FLAG_BRANCH,
        /* 0x33 if-ne                    */ FLAG_CONTINUE | FLAG_BRANCH,
        /* 0x34 if-lt                    */ FLAG_CONTINUE | FLAG_BRANCH,
        /* 0x35 if-ge                    */ FLAG_CONTINUE | FLAG_BRANCH,
        /* 0x36 if-gt                    */ FLAG_CONTINUE | FLAG_BRANCH,
        /* 0x37 if-le                    */ FLAG_CONTINUE | FLAG_BRANCH,
        /* 0x38 if-eqz                   */ FLAG_CONTINUE | FLAG_BRANCH,
        /* 0x39 if-nez                   */ FLAG_CONTINUE | FLAG_BRANCH,
        /* 0x3a if-ltz                   */ FLAG_CONTINUE | FLAG_BRANCH,
        /* 0x3b if-gez                   */ FLAG_CONTINUE | FLAG_BRANCH,
        /* 0x3c if-gtz                   */ FLAG_CONTINUE | FLAG_BRANCH,
        /* 0x3d if-lez                   */ FLAG_CONTINUE | FLAG_BRANCH,
        /* 0x3e unused-3e                */ FLAG_UNUSED,
        /* 0x3f unused-3f                */ FLAG_UNUSED,
        /* 0x40 unused-40                */ FLAG_UNUSED,
        /* 0x41 unused-41                */ FLAG_UNUSED,
        /* 0x42 unused-42                */ FLAG_UNUSED,
        /* 0x43 unused-43                */ FLAG_UNUSED,
        /* 0x44 aget                     */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x45 aget-wide                */ FLAG_CONTINUE | FLAG_THROW | FLAG_WIDE_A,
        /* 0x46 aget-object              */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x47 aget-boolean             */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x48 aget-byte                */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x49 aget-char                */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x4a aget-short               */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x4b aput                     */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x4c aput-wide                */ FLAG_CONTINUE | FLAG_THROW | FLAG_WIDE_A,
        /* 0x4d aput-object              */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x4e aput-boolean             */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x4f aput-byte                */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x50 aput-char                */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x51 aput-short               */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x52 iget                     */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x53 iget-wide                */ FLAG_CONTINUE | FLAG_THROW | FLAG_WIDE_A,
        /* 0x54 iget-object              */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x55 iget-boolean             */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x56 iget-byte                */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x57 iget-char                */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x58 iget-short               */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x59 iput                     */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x5a iput-wide                */ FLAG_CONTINUE | FLAG_THROW | FLAG_WIDE_A,
        /* 0x5b iput-object              */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x5c iput-boolean             */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x5d iput-byte                */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x5e iput-char                */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x5f iput-short               */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x60 sget                     */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x61 sget-wide                */ FLAG_CONTINUE | FLAG_THROW | FLAG_WIDE_A,
        /* 0x62 sget-object              */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x63 sget-boolean             */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x64 sget-byte                */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x65 sget-char                */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x66 sget-short               */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x67 sput                     */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x68 sput-wide                */ FLAG_CONTINUE | FLAG_THROW | FLAG_WIDE_A,
        /* 0x69 sput-object              */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x6a sput-boolean             */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x6b sput-byte                */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x6c sput-char                */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x6d sput-short               */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x6e invoke-virtual           */ FLAG_CONTINUE | FLAG_THROW | FLAG_INVOKE,
        /* 0x6f invoke-super             */ FLAG_CONTINUE | FLAG_THROW | FLAG_INVOKE,
        /* 0x70 invoke-direct            */ FLAG_CONTINUE | FLAG_THROW | FLAG_INVOKE,
        /* 0x71 invoke-static            */ FLAG_CONTINUE | FLAG_THROW | FLAG_INVOKE,
        /* 0x72 invoke-interface         */ FLAG_CONTINUE | FLAG_THROW | FLAG_INVOKE,
        /* 0x73 unused-73                */ FLAG_UNUSED,
        /* 0x74 invoke-virtual/range     */ FLAG_CONTINUE | FLAG_THROW | FLAG_INVOKE,
        /* 0x75 invoke-super/range       */ FLAG_CONTINUE | FLAG_THROW | FLAG_INVOKE,
        /* 0x76 invoke-direct/range      */ FLAG_CONTINUE | FLAG_THROW | FLAG_INVOKE,
        /* 0x77 invoke-static/range      */ FLAG_CONTINUE | FLAG_THROW | FLAG_INVOKE,
        /* 0x78 invoke-interface/range   */ FLAG_CONTINUE | FLAG_THROW | FLAG_INVOKE,
        /* 0x79 unused-79                */ FLAG_UNUSED,
        /* 0x7a unused-7a                */ FLAG_UNUSED,
        /* 0x7b neg-int                  */ FLAG_CONTINUE,
        /* 0x7c not-int                  */ FLAG_CONTINUE,
        /* 0x7d neg-long                 */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B,
        /* 0x7e not-long                 */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B,
        /* 0x7f neg-float                */ FLAG_CONTINUE,
        /* 0x80 neg-double               */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B,
        /* 0x81 int-to-long              */ FLAG_CONTINUE | FLAG_WIDE_A,
        /* 0x82 int-to-float             */ FLAG_CONTINUE,
        /* 0x83 int-to-double            */ FLAG_CONTINUE | FLAG_WIDE_A,
        /* 0x84 long-to-int              */ FLAG_CONTINUE | FLAG_WIDE_B,
        /* 0x85 long-to-float            */ FLAG_CONTINUE | FLAG_WIDE_B,
        /* 0x86 long-to-double           */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B,
        /* 0x87 float-to-int             */ FLAG_CONTINUE,
        /* 0x88 float-to-long            */ FLAG_CONTINUE | FLAG_WIDE_A,
        /* 0x89 float-to-double          */ FLAG_CONTINUE | FLAG_WIDE_A,
        /* 0x8a double-to-int            */ FLAG_CONTINUE | FLAG_WIDE_B,
        /* 0x8b double-to-long           */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B,
        /* 0x8c double-to-float          */ FLAG_CONTINUE | FLAG_WIDE_B,
        /* 0x8d int-to-byte              */ FLAG_CONTINUE,
        /* 0x8e int-to-char              */ FLAG_CONTINUE,
        /* 0x8f int-to-short             */ FLAG_CONTINUE,
        /* 0x90 add-int                  */ FLAG_CONTINUE,
        /* 0x91 sub-int                  */ FLAG_CONTINUE,
        /* 0x92 mul-int                  */ FLAG_CONTINUE,
        /* 0x93 div-int                  */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x94 rem-int                  */ FLAG_CONTINUE | FLAG_THROW,
        /* 0x95 and-int                  */ FLAG_CONTINUE,
        /* 0x96 or-int                   */ FLAG_CONTINUE,
        /* 0x97 xor-int                  */ FLAG_CONTINUE,
        /* 0x98 shl-int                  */ FLAG_CONTINUE,
        /* 0x99 shr-int                  */ FLAG_CONTINUE,
        /* 0x9a ushr-int                 */ FLAG_CONTINUE,
        /* 0x9b add-long                 */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B | FLAG_WIDE_C,
        /* 0x9c sub-long                 */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B | FLAG_WIDE_C,
        /* 0x9d mul-long                 */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B | FLAG_WIDE_C,
        /* 0x9e div-long                 */ FLAG_CONTINUE | FLAG_THROW | FLAG_WIDE_A | FLAG_WIDE_B | FLAG_WIDE_C,
        /* 0x9f rem-long                 */ FLAG_CONTINUE | FLAG_THROW | FLAG_WIDE_A | FLAG_WIDE_B | FLAG_WIDE_C,
        /* 0xa0 and-long                 */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B | FLAG_WIDE_C,
        /* 0xa1 or-long                  */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B | FLAG_WIDE_C,
        /* 0xa2 xor-long                 */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B | FLAG_WIDE_C,
        /* 0xa3 shl-long                 */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B,
        /* 0xa4 shr-long                 */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B,
        /* 0xa5 ushr-long                */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B,
        /* 0xa6 add-float                */ FLAG_CONTINUE,
        /* 0xa7 sub-float                */ FLAG_CONTINUE,
        /* 0xa8 mul-float                */ FLAG_CONTINUE,
        /* 0xa9 div-float                */ FLAG_CONTINUE,
        /* 0xaa rem-float                */ FLAG_CONTINUE,
        /* 0xab add-double               */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B | FLAG_WIDE_C,
        /* 0xac sub-double               */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B | FLAG_WIDE_C,
        /* 0xad mul-double               */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B | FLAG_WIDE_C,
        /* 0xae div-double               */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B | FLAG_WIDE_C,
        /* 0xaf rem-double               */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B | FLAG_WIDE_C,
        /* 0xb0 add-int/2addr            */ FLAG_CONTINUE,
        /* 0xb1 sub-int/2addr            */ FLAG_CONTINUE,
        /* 0xb2 mul-int/2addr            */ FLAG_CONTINUE,
        /* 0xb3 div-int/2addr            */ FLAG_CONTINUE | FLAG_THROW,
        /* 0xb4 rem-int/2addr            */ FLAG_CONTINUE | FLAG_THROW,
        /* 0xb5 and-int/2addr            */ FLAG_CONTINUE,
        /* 0xb6 or-int/2addr             */ FLAG_CONTINUE,
        /* 0xb7 xor-int/2addr            */ FLAG_CONTINUE,
        /* 0xb8 shl-int/2addr            */ FLAG_CONTINUE,
        /* 0xb9 shr-int/2addr            */ FLAG_CONTINUE,
        /* 0xba ushr-int/2addr           */ FLAG_CONTINUE,
        /* 0xbb add-long/2addr           */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B,
        /* 0xbc sub-long/2addr           */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B,
        /* 0xbd mul-long/2addr           */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B,
        /* 0xbe div-long/2addr           */ FLAG_CONTINUE | FLAG_THROW | FLAG_WIDE_A | FLAG_WIDE_B,
        /* 0xbf rem-long/2addr           */ FLAG_CONTINUE | FLAG_THROW | FLAG_WIDE_A | FLAG_WIDE_B,
        /* 0xc0 and-long/2addr           */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B,
        /* 0xc1 or-long/2addr            */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B,
        /* 0xc2 xor-long/2addr           */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B,
        /* 0xc3 shl-long/2addr           */ FLAG_CONTINUE | FLAG_WIDE_A,
        /* 0xc4 shr-long/2addr           */ FLAG_CONTINUE | FLAG_WIDE_A,
        /* 0xc5 ushr-long/2addr          */ FLAG_CONTINUE | FLAG_WIDE_A,
        /* 0xc6 add-float/2addr          */ FLAG_CONTINUE,
        /* 0xc7 sub-float/2addr          */ FLAG_CONTINUE,
        /* 0xc8 mul-float/2addr          */ FLAG_CONTINUE,
        /* 0xc9 div-float/2addr          */ FLAG_CONTINUE,
        /* 0xca rem-float/2addr          */ FLAG_CONTINUE,
        /* 0xcb add-double/2addr         */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B,
        /* 0xcc sub-double/2addr         */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B,
        /* 0xcd mul-double/2addr         */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B,
        /* 0xce div-double/2addr         */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B,
        /* 0xcf rem-double/2addr         */ FLAG_CONTINUE | FLAG_WIDE_A | FLAG_WIDE_B,
        /* 0xd0 add-int/lit16            */ FLAG_CONTINUE,
        /* 0xd1 rsub-int                 */ FLAG_CONTINUE,
        /* 0xd2 mul-int/lit16            */ FLAG_CONTINUE,
        /* 0xd3 div-int/lit16            */ FLAG_CONTINUE | FLAG_THROW,
        /* 0xd4 rem-int/lit16            */ FLAG_CONTINUE | FLAG_THROW,
        /* 0xd5 and-int/lit16            */ FLAG_CONTINUE,
        /* 0xd6 or-int/lit16             */ FLAG_CONTINUE,
        /* 0xd7 xor-int/lit16            */ FLAG_CONTINUE,
        /* 0xd8 add-int/lit8             */ FLAG_CONTINUE,
        /* 0xd9 rsub-int/lit8            */ FLAG_CONTINUE,
        /* 0xda mul-int/lit8             */ FLAG_CONTINUE,
        /* 0xdb div-int/lit8             */ FLAG_CONTINUE | FLAG_THROW,
        /* 0xdc rem-int/lit8             */ FLAG_CONTINUE | FLAG_THROW,
        /* 0xdd and-int/lit8             */ FLAG_CONTINUE,
        /* 0xde or-int/lit8              */ FLAG_CONTINUE,
        /* 0xdf xor-int/lit8             */ FLAG_CONTINUE,
        /* 0xe0 shl-int/lit8             */ FLAG_CONTINUE,
        /* 0xe1 shr-int/lit8             */ FLAG_CONTINUE,
        /* 0xe2 ushr-int/lit8            */ FLAG_CONTINUE,
        /* 0xe3 unused-e3                */ FLAG_UNUSED,
        /* 0xe4 unused-e4                */ FLAG_UNUSED,
        /* 0xe5 unused-e5                */ FLAG_UNUSED,
        /* 0xe6 unused-e6                */ FLAG_UNUSED,
        /* 0xe7 unused-e7                */ FLAG_UNUSED,
        /* 0xe8 unused-e8                */ FLAG_UNUSED,
        /* 0xe9 unused-e9                */ FLAG_UNUSED,
        /* 0xea unused-ea                */ FLAG_UNUSED,
        /* 0xeb unused-eb                */ FLAG_UNUSED,
        /* 0xec unused-ec                */ FLAG_UNUSED,
        /* 0xed unused-ed                */ FLAG_UNUSED,
        /* 0xee unused-ee                */ FLAG_UNUSED,
        /* 0xef unused-ef                */ FLAG_UNUSED,
        /* 0xf0 unused-f0                */ FLAG_UNUSED,
        /* 0xf1 unused-f1                */ FLAG_UNUSED,
        /* 0xf2 unused-f2                */ FLAG_UNUSED,
        /* 0xf3 unused-f3                */ FLAG_UNUSED,
        /* 0xf4 unused-f4                */ FLAG_UNUSED,
        /* 0xf5 unused-f5                */ FLAG_UNUSED,
        /* 0xf6 unused-f6                */ FLAG_UNUSED,
        /* 0xf7 unused-f7                */ FLAG_UNUSED,
        /* 0xf8 unused-f8                */ FLAG_UNUSED,
        /* 0xf9 unused-f9                */ FLAG_UNUSED,
        /* 0xfa invoke-polymorphic       */ FLAG_CONTINUE | FLAG_THROW | FLAG_INVOKE,
        /* 0xfb invoke-polymorphic/range */ FLAG_CONTINUE | FLAG_THROW | FLAG_INVOKE,
        /* 0xfc invoke-custom            */ FLAG_CONTINUE | FLAG_THROW,
        /* 0xfd invoke-custom/range      */ FLAG_CONTINUE | FLAG_THROW,
        /* 0xfe const-method-handle      */ FLAG_CONTINUE | FLAG_THROW,
        /* 0xff const-method-type        */ FLAG_CONTINUE | FLAG_THROW,
    };

    // ------------------------------------------------------------------
    // Accessors.
    // ------------------------------------------------------------------

    /** The AOSP mnemonic for {@code opcode}, e.g. "invoke-virtual/range". */
    public static String nameOf(int opcode) {
        return NAMES[opcode & 0xff];
    }

    /** The instruction format for {@code opcode}. */
    public static Format formatOf(int opcode) {
        return FORMATS[opcode & 0xff];
    }

    /** Fixed instruction length of {@code opcode} in 16-bit code units. */
    public static int codeUnitsOf(int opcode) {
        return FORMATS[opcode & 0xff].codeUnits();
    }

    /** One of the {@code INDEX_*} constants. */
    public static int indexTypeOf(int opcode) {
        return INDEX_TYPES[opcode & 0xff];
    }

    /** The {@code FLAG_*} bitset for {@code opcode}. */
    public static int flagsOf(int opcode) {
        return FLAGS[opcode & 0xff];
    }

    private static boolean has(int opcode, int bit) {
        return (FLAGS[opcode & 0xff] & bit) != 0;
    }

    /** True if {@code opcode} is not assigned by any DEX version. */
    public static boolean isUnused(int opcode) {
        return has(opcode, FLAG_UNUSED);
    }

    /** True if execution can fall through into the following instruction. */
    public static boolean canContinue(int opcode) {
        return has(opcode, FLAG_CONTINUE);
    }

    /** True for goto/16/32 and every if-*. */
    public static boolean isBranch(int opcode) {
        return has(opcode, FLAG_BRANCH);
    }

    /** True for goto, goto/16, goto/32 only. */
    public static boolean isUnconditionalBranch(int opcode) {
        return has(opcode, FLAG_UNCONDITIONAL);
    }

    /** True for packed-switch and sparse-switch. */
    public static boolean isSwitch(int opcode) {
        return has(opcode, FLAG_SWITCH);
    }

    /** True for return-void / return / return-wide / return-object. */
    public static boolean isReturn(int opcode) {
        return has(opcode, FLAG_RETURN);
    }

    /** True if the instruction may raise an exception. */
    public static boolean canThrow(int opcode) {
        return has(opcode, FLAG_THROW);
    }

    /**
     * True for every invoke-* flavour EXCEPT invoke-custom / invoke-custom
     * /range, which AOSP does not mark kInvoke because they resolve through a
     * bootstrap method. Not true for filled-new-array. See
     * {@link #setsResult(int)} for the "writes the result register" test.
     */
    public static boolean isInvoke(int opcode) {
        return has(opcode, FLAG_INVOKE);
    }

    /** True if vA names a register pair (vA, vA+1). */
    public static boolean isWideA(int opcode) {
        return has(opcode, FLAG_WIDE_A);
    }

    /** True if vB names a register pair (vB, vB+1). */
    public static boolean isWideB(int opcode) {
        return has(opcode, FLAG_WIDE_B);
    }

    /** True if vC names a register pair (vC, vC+1). */
    public static boolean isWideC(int opcode) {
        return has(opcode, FLAG_WIDE_C);
    }

    /**
     * True for move-result / move-result-wide / move-result-object. Such an
     * instruction reads a pseudo-register written by the IMMEDIATELY
     * preceding invoke-* or filled-new-array; see
     * {@link Instruction#resultProducer()}.
     */
    public static boolean isMoveResult(int opcode) {
        return opcode == MOVE_RESULT || opcode == MOVE_RESULT_WIDE
                || opcode == MOVE_RESULT_OBJECT;
    }

    /**
     * True for every instruction that writes the result pseudo-register:
     * all invoke-* flavours plus filled-new-array and filled-new-array/range.
     * A following move-result* reads it.
     */
    public static boolean setsResult(int opcode) {
        return isInvoke(opcode) || opcode == FILLED_NEW_ARRAY
                || opcode == FILLED_NEW_ARRAY_RANGE
                || opcode == INVOKE_CUSTOM || opcode == INVOKE_CUSTOM_RANGE;
    }

    /**
     * True if the instruction terminates a basic block by itself: it either
     * cannot fall through (return / throw / goto) or transfers control
     * conditionally (if-* / *-switch).
     */
    public static boolean isTerminator(int opcode) {
        int f = FLAGS[opcode & 0xff];
        if ((f & (FLAG_BRANCH | FLAG_SWITCH | FLAG_RETURN)) != 0) {
            return true;
        }
        // throw is kThrow with no kContinue; everything else with no
        // kContinue is an unused opcode.
        return (f & FLAG_CONTINUE) == 0;
    }
}
