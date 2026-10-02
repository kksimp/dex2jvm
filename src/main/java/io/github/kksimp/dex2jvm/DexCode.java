package io.github.kksimp.dex2jvm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A method body: one code_item, its try/catch table, and its decoded
 * debug_info_item.
 *
 * <p>Spec: https://source.android.com/docs/core/runtime/dex-format
 * ("code_item", "try_item", "encoded_catch_handler_list", "debug_info_item").
 *
 * <h2>Why debug_info_item is parsed here</h2>
 * enjarify discards debug_info_item outright, which is why every stack trace
 * from code it converted reads
 * {@code at com.foo.Bar.baz(Unknown Source)}. Decoding the line-number state
 * machine costs a few hundred bytes of work per method and gives the class file
 * writer everything it needs for LineNumberTable, LocalVariableTable and
 * MethodParameters. The one translation the writer still has to do is map DEX
 * code-unit addresses to its own emitted bytecode offsets; this class reports
 * addresses in the DEX domain and never guesses at the JVM one.
 */
public final class DexCode {

    // debug_info_item state machine opcodes. Values from AOSP
    // dx/src/com/android/dx/dex/file/DebugInfoConstants.java, which matches the
    // published spec table.
    private static final int DBG_END_SEQUENCE = 0x00;
    private static final int DBG_ADVANCE_PC = 0x01;
    private static final int DBG_ADVANCE_LINE = 0x02;
    private static final int DBG_START_LOCAL = 0x03;
    private static final int DBG_START_LOCAL_EXTENDED = 0x04;
    private static final int DBG_END_LOCAL = 0x05;
    private static final int DBG_RESTART_LOCAL = 0x06;
    private static final int DBG_SET_PROLOGUE_END = 0x07;
    private static final int DBG_SET_EPILOGUE_BEGIN = 0x08;
    private static final int DBG_SET_FILE = 0x09;

    /** Smallest byte value that encodes a combined pc + line advance. */
    private static final int DBG_FIRST_SPECIAL = 0x0a;
    /** The line delta of the lowest special opcode. */
    private static final int DBG_LINE_BASE = -4;
    /** Number of distinct line deltas a special opcode can express. */
    private static final int DBG_LINE_RANGE = 15;

    private final DexMethod method;
    private final DexFile dex;
    private final int codeOff;
    private final int registersSize;
    private final int insSize;
    private final int outsSize;
    private final int debugInfoOff;
    private final int insnsFileOffset;
    private final short[] insns;
    private final List<Try> tries;

    private volatile DebugInfo debug;

    DexCode(DexMethod method, int codeOff) {
        this.method = method;
        this.dex = method.declaringClass().dex();
        this.codeOff = codeOff;

        DexFile.Reader r = dex.reader(codeOff);
        this.registersSize = r.u2();
        this.insSize = r.u2();
        this.outsSize = r.u2();
        int triesSize = r.u2();
        this.debugInfoOff = r.u4();
        int insnsSize = r.u4();
        this.insnsFileOffset = r.pos();

        byte[] raw = dex.bytes();
        short[] code = new short[insnsSize];
        int p = insnsFileOffset;
        for (int i = 0; i < insnsSize; i++, p += 2) {
            code[i] = (short) ((raw[p] & 0xff) | ((raw[p + 1] & 0xff) << 8));
        }
        this.insns = code;

        if (triesSize == 0) {
            this.tries = List.of();
        } else {
            // "padding: two bytes of padding to make tries four-byte aligned.
            // This element is only present if tries_size is non-zero and
            // insns_size is odd." The insns array itself starts four-byte
            // aligned, so an odd code-unit count leaves the cursor two bytes
            // short of alignment.
            if ((insnsSize & 1) != 0) {
                p += 2;
            }
            this.tries = readTries(p, triesSize);
        }
    }

    /** The method this body belongs to. */
    public DexMethod method() {
        return method;
    }

    /** Total registers used by the method, including the argument registers. */
    public int registersSize() {
        return registersSize;
    }

    /**
     * Number of registers occupied by incoming arguments, counting "this" for
     * an instance method and two for each J or D. The argument registers are
     * the LAST ins_size registers, so the first argument lives at
     * {@code registersSize() - insSize()}.
     */
    public int insSize() {
        return insSize;
    }

    /** Size of the outgoing argument area, i.e. the widest call this method makes. */
    public int outsSize() {
        return outsSize;
    }

    /** Number of 16-bit code units in {@link #insns()}. */
    public int insnsSize() {
        return insns.length;
    }

    /**
     * The raw Dalvik instruction stream as 16-bit code units.
     *
     * <p>Returned by reference, not copied: on a large dex this array IS most of
     * the parser's memory traffic and copying it on every access would dominate
     * conversion time. Treat it as read-only. Switch and fill-array-data
     * payloads live inside this same array at their branch targets.
     *
     * <p>The elements are SIGNED shorts holding unsigned 16-bit code units, so
     * read them as {@code insns()[i] & 0xffff}. Reading one without the mask
     * silently breaks every opcode above 0x7f and every high branch offset.
     */
    public short[] insns() {
        return insns;
    }

    /** File offset of the insns array, for diagnostics. */
    public int insnsFileOffset() {
        return insnsFileOffset;
    }

    /** File offset of this code_item. */
    public int codeOffset() {
        return codeOff;
    }

    /** The try blocks, in ascending start-address order. */
    public List<Try> tries() {
        return tries;
    }

    /** True when this method carries a debug_info_item. */
    public boolean hasDebugInfo() {
        return debugInfoOff != 0;
    }

    /** File offset of the debug_info_item, or 0. */
    public int debugInfoOffset() {
        return debugInfoOff;
    }

    /**
     * The source line for the instruction at the given 16-bit code-unit offset,
     * or -1 when unknown.
     *
     * <p>Semantics match a DWARF-style line program: the answer is the line of
     * the last position entry at or before {@code codeUnitOffset}. Offsets
     * before the first entry return -1 rather than guessing.
     */
    public int lineForOffset(int codeUnitOffset) {
        int[] ps = debug().positions;
        if (ps.length == 0) {
            return -1;
        }
        int lo = 0;
        int hi = (ps.length >> 1) - 1;
        int best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (ps[mid << 1] <= codeUnitOffset) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best < 0 ? -1 : ps[(best << 1) + 1];
    }

    // ---- the line table, without materialising it ---------------------------
    //
    // The decoded line table is BY FAR the biggest allocation the converter
    // makes, and it is entirely garbage. Measured on TikTok 2024604030 (411,385
    // classes, 1,765,552 method bodies, 76,349,513 code units): one conversion
    // decodes 3,756,501,410 position entries -- ~49 per code unit, because R8
    // canonicalises debug_info_items and shares one across many structurally
    // similar methods, so the same table is decoded again for every sharer. As
    // one 24-byte object plus a 4-byte array slot each, that is ~100 GB of
    // allocation churn for a single app. Stored as interleaved (address, line)
    // ints it is ~30 GB, and the objects never exist at all.
    //
    // positions() still hands back the List<Position> view the public API
    // documents; the converter's own hot path (Translator.emitLineNumbers)
    // reads the raw table instead, so it allocates nothing per entry. The
    // largest single table in that same measurement was 4,086 entries, so this
    // is about churn, not about peak.

    /**
     * The line-number table as interleaved {@code (address, line)} ints, in
     * ascending address order: entry {@code i} is {@code [2i]} and
     * {@code [2i+1]}, and there are {@code length / 2} entries.
     *
     * <p>The live array, NOT a copy, because handing out a copy would reinstate
     * the allocation this exists to remove. It is memoized inside DebugInfo and
     * read from several worker threads at once, so a caller must treat it as
     * immutable. Same contract as {@link #insns()}.
     *
     * <p>See {@link #positions()} for the object view.
     */
    int[] positionTable() {
        return debug().positions;
    }

    /**
     * The full line-number table, in ascending address order. This is the input
     * for a LineNumberTable attribute: each entry says "code unit N came from
     * source line L".
     *
     * <p>Reported verbatim, which means a writer MUST filter it. Two things
     * seen in real R8 output that a LineNumberTable cannot represent:
     *
     * <ol>
     *   <li>Addresses at or past {@link #insnsSize()}. R8's residual debug info
     *       uses a "line number equals pc" table that it CANONICALIZES and
     *       shares across many methods, so one 72-entry table gets pointed at
     *       by methods whose code is only 31 code units long. Measured on
     *       io.anuke.mindustry_1099.apk: 2,523,007 of 3,960,100 decoded
     *       positions are out of range.</li>
     *   <li>Addresses that land inside a packed-switch, sparse-switch or
     *       fill-array-data payload, which is data rather than an instruction
     *       and therefore has no bytecode offset to attach a line to.</li>
     * </ol>
     *
     * <p>Both cases are exactly what baksmali drops. Since deciding "is this
     * address an instruction" requires decoding the instruction stream, which
     * is not this layer's job, the rule for the writer is simply: emit an entry
     * only for an address it has an emitted-bytecode offset for. Positions can
     * also legitimately repeat at one address (R8 uses that to encode inlined
     * frames); keeping only the first is the usual choice.
     *
     * <p>ALLOCATES one Position per entry, every call: the table is stored flat
     * (see {@link #positionTable()}) precisely because materialising it is what
     * cost ~100 GB of churn on a large app. This is the readable view for a
     * diagnostic or a test; anything that runs once per method should read the
     * flat table.
     */
    public List<Position> positions() {
        int[] ps = debug().positions;
        if (ps.length == 0) {
            return List.of();
        }
        Position[] out = new Position[ps.length >> 1];
        for (int i = 0; i < out.length; i++) {
            out[i] = new Position(ps[i << 1], ps[(i << 1) + 1]);
        }
        return Collections.unmodifiableList(Arrays.asList(out));
    }

    /** The initial line number from debug_info_item.line_start, or -1. */
    public int lineStart() {
        return debug().lineStart;
    }

    /**
     * Local variable live ranges recovered from the debug info, including the
     * implicit "this" and the named parameters. This is the input for a
     * LocalVariableTable / LocalVariableTypeTable attribute.
     */
    public List<Local> locals() {
        return debug().locals;
    }

    /**
     * Declared parameter names, excluding "this", in declaration order. Entries
     * are null where the DEX recorded no name. Empty when there is no debug
     * info. This is the input for a MethodParameters attribute.
     */
    public List<String> parameterNames() {
        return debug().parameterNames;
    }

    /**
     * The file name set by a DBG_SET_FILE opcode, or null when the method never
     * overrides its class's source file. Only the first override is reported;
     * per-position file tracking would have nowhere to go in a class file,
     * which has a single SourceFile attribute.
     */
    public String sourceFileOverride() {
        return debug().sourceFileOverride;
    }

    @Override
    public String toString() {
        return "code[" + method + " regs=" + registersSize + " ins=" + insSize
                + " outs=" + outsSize + " units=" + insns.length + "]";
    }

    // ---- try / catch -------------------------------------------------------

    private List<Try> readTries(int triesOff, int triesSize) {
        // The handler list sits immediately after the try_item array, and each
        // try_item's handler_off is measured from the START of that list (from
        // the uleb128 count, not past it).
        int handlersOff = triesOff + triesSize * 8;
        Map<Integer, List<Handler>> byOffset = new HashMap<>();
        DexFile.Reader hr = dex.reader(handlersOff);
        int handlerCount = hr.uleb128();
        for (int i = 0; i < handlerCount; i++) {
            int rel = hr.pos() - handlersOff;
            byOffset.put(rel, readHandler(hr));
        }

        Try[] out = new Try[triesSize];
        for (int i = 0; i < triesSize; i++) {
            int p = triesOff + i * 8;
            int startAddr = dex.u4(p);
            int insnCount = dex.u2(p + 4);
            int handlerOff = dex.u2(p + 6);
            List<Handler> handlers = byOffset.get(handlerOff);
            if (handlers == null) {
                // The enumerated list should cover every referenced offset, but
                // a repacked dex can leave an unreferenced gap. Parsing at the
                // offset directly is always correct, so fall back rather than
                // dropping the try block and silently losing an exception edge.
                handlers = readHandler(dex.reader(handlersOff + handlerOff));
                byOffset.put(handlerOff, handlers);
            }
            out[i] = new Try(startAddr, insnCount, handlers);
        }
        return List.of(out);
    }

    private List<Handler> readHandler(DexFile.Reader r) {
        // encoded_catch_handler.size is SIGNED: a non-positive count means the
        // typed handlers are followed by a catch-all address.
        int size = r.sleb128();
        int n = Math.abs(size);
        List<Handler> out = new ArrayList<>(n + 1);
        for (int i = 0; i < n; i++) {
            int typeIdx = r.uleb128();
            int addr = r.uleb128();
            out.add(new Handler(dex.typeDescriptor(typeIdx), addr));
        }
        if (size <= 0) {
            // Appended last so handler order matches Java's "first match wins"
            // semantics: a catch-all only applies once the typed catches miss.
            out.add(new Handler(null, r.uleb128()));
        }
        return Collections.unmodifiableList(out);
    }

    /** One try_item: a code range plus the handlers that guard it. */
    public static final class Try {
        private final int startAddress;
        private final int instructionCount;
        private final List<Handler> handlers;

        Try(int startAddress, int instructionCount, List<Handler> handlers) {
            this.startAddress = startAddress;
            this.instructionCount = instructionCount;
            this.handlers = handlers;
        }

        /** First guarded code unit. */
        public int startAddress() {
            return startAddress;
        }

        /** Number of guarded code units. */
        public int instructionCount() {
            return instructionCount;
        }

        /** One past the last guarded code unit, i.e. the exclusive end. */
        public int endAddress() {
            return startAddress + instructionCount;
        }

        /** Handlers in match order; a catch-all, if present, is last. */
        public List<Handler> handlers() {
            return handlers;
        }

        @Override
        public String toString() {
            return "try[" + startAddress + "," + endAddress() + ") " + handlers;
        }
    }

    /** One catch clause: an exception type (or catch-all) and its entry point. */
    public static final class Handler {
        private final String type;
        private final int address;

        Handler(String type, int address) {
            this.type = type;
            this.address = address;
        }

        /**
         * Exception type descriptor, or null for a catch-all. A class file
         * writer emits catch_type 0 for the null case, which is also how
         * finally blocks and synchronized unwinding are expressed.
         */
        public String type() {
            return type;
        }

        /** Type as a JVM internal name, or null for a catch-all. */
        public String typeName() {
            return DexFile.internalName(type);
        }

        /** Code unit offset of the handler's first instruction. */
        public int address() {
            return address;
        }

        @Override
        public String toString() {
            return (type == null ? "*" : type) + "->" + address;
        }
    }

    // ---- debug_info_item ---------------------------------------------------

    /** Shared empty seed for the parser's growable position buffer. */
    private static final int[] EMPTY_POSITIONS = new int[0];

    /**
     * One entry in the line-number table.
     *
     * <p>A VIEW, built on demand by {@link #positions()}. The table itself is
     * stored flat -- see {@link #positionTable()} for why.
     */
    public static final class Position {
        private final int address;
        private final int line;

        Position(int address, int line) {
            this.address = address;
            this.line = line;
        }

        /** Code unit offset this line starts at. */
        public int address() {
            return address;
        }

        /** Source line number. */
        public int line() {
            return line;
        }

        @Override
        public String toString() {
            return address + ":" + line;
        }
    }

    /** A local variable's name, type and live range. */
    public static final class Local {
        private final int register;
        private final String name;
        private final String type;
        private final String signature;
        private final int startAddress;
        private final int endAddress;

        Local(int register, String name, String type, String signature,
              int startAddress, int endAddress) {
            this.register = register;
            this.name = name;
            this.type = type;
            this.signature = signature;
            this.startAddress = startAddress;
            this.endAddress = endAddress;
        }

        /** Dalvik register holding the variable. */
        public int register() {
            return register;
        }

        public String name() {
            return name;
        }

        /** Type descriptor, or null when the DEX omitted it. */
        public String type() {
            return type;
        }

        /** Generic signature, or null. Feeds LocalVariableTypeTable. */
        public String signature() {
            return signature;
        }

        /** First code unit the variable is live at. */
        public int startAddress() {
            return startAddress;
        }

        /** One past the last code unit the variable is live at. */
        public int endAddress() {
            return endAddress;
        }

        @Override
        public String toString() {
            return "v" + register + " " + name + ":" + type
                    + " [" + startAddress + "," + endAddress + ")";
        }
    }

    private DebugInfo debug() {
        DebugInfo d = debug;
        if (d != null) {
            return d;
        }
        synchronized (this) {
            d = debug;
            if (d == null) {
                d = debugInfoOff == 0 ? DebugInfo.EMPTY : parseDebugInfo();
                debug = d;
            }
            return d;
        }
    }

    private static final class DebugInfo {
        static final DebugInfo EMPTY =
                new DebugInfo(-1, new int[0], List.of(), List.of(), null);

        final int lineStart;
        /** Interleaved (address, line); see {@link DexCode#positionTable()}. */
        final int[] positions;
        final List<Local> locals;
        final List<String> parameterNames;
        final String sourceFileOverride;

        DebugInfo(int lineStart, int[] positions, List<Local> locals,
                  List<String> parameterNames, String sourceFileOverride) {
            this.lineStart = lineStart;
            this.positions = positions;
            this.locals = locals;
            this.parameterNames = parameterNames;
            this.sourceFileOverride = sourceFileOverride;
        }
    }

    /**
     * Runs the debug_info_item state machine.
     *
     * <p>Layout: {@code line_start uleb128}, {@code parameters_size uleb128},
     * {@code parameter_names uleb128p1[]}, then a byte stream of opcodes. Two
     * registers, address (code units) and line, both advanced by the opcodes. A
     * position entry is emitted ONLY by a special opcode; DBG_ADVANCE_PC and
     * DBG_ADVANCE_LINE move the registers silently, which is how the encoder
     * expresses deltas too large for one special opcode.
     */
    private DebugInfo parseDebugInfo() {
        byte[] raw = dex.bytes();
        DexFile.Reader r = dex.reader(debugInfoOff);
        int lineStart = r.uleb128();
        int paramsSize = r.uleb128();

        String[] paramNames = new String[paramsSize];
        for (int i = 0; i < paramsSize; i++) {
            // uleb128p1: 0 encodes "no name", which decodes to NO_INDEX and
            // then to a null string.
            paramNames[i] = dex.string(r.uleb128p1());
        }

        // Interleaved (address, line), grown by doubling exactly as the
        // ArrayList<Position> this replaces did. Flat because this loop runs
        // 3.76 BILLION times over one large app -- see positionTable().
        int[] positions = EMPTY_POSITIONS;
        int positionInts = 0;
        List<Local> locals = new ArrayList<>();
        // live[reg] is the currently open declaration; ended[reg] remembers the
        // last one so DBG_RESTART_LOCAL can reopen it by name.
        OpenLocal[] live = new OpenLocal[Math.max(registersSize, 1)];
        OpenLocal[] ended = new OpenLocal[live.length];

        seedParameters(paramNames, live, ended);

        int address = 0;
        int line = lineStart;
        String sourceFileOverride = null;

        loop:
        while (r.pos() < raw.length) {
            int opcode = r.u1();
            switch (opcode) {
                case DBG_END_SEQUENCE:
                    break loop;

                case DBG_ADVANCE_PC:
                    address += r.uleb128();
                    break;

                case DBG_ADVANCE_LINE:
                    // Signed: line numbers move backwards after inlining and
                    // after a loop's back edge.
                    line += r.sleb128();
                    break;

                case DBG_START_LOCAL: {
                    int reg = r.uleb128();
                    String name = dex.string(r.uleb128p1());
                    String type = dex.typeDescriptor(r.uleb128p1());
                    startLocal(locals, live, ended, reg, name, type, null, address);
                    break;
                }

                case DBG_START_LOCAL_EXTENDED: {
                    int reg = r.uleb128();
                    String name = dex.string(r.uleb128p1());
                    String type = dex.typeDescriptor(r.uleb128p1());
                    String sig = dex.string(r.uleb128p1());
                    startLocal(locals, live, ended, reg, name, type, sig, address);
                    break;
                }

                case DBG_END_LOCAL: {
                    int reg = r.uleb128();
                    if (reg >= 0 && reg < live.length && live[reg] != null) {
                        closeLocal(locals, live, ended, reg, address);
                    }
                    break;
                }

                case DBG_RESTART_LOCAL: {
                    int reg = r.uleb128();
                    // Reopens the register's most recent declaration; the name
                    // and type are not repeated in the stream.
                    if (reg >= 0 && reg < live.length && live[reg] == null && ended[reg] != null) {
                        OpenLocal prev = ended[reg];
                        live[reg] = new OpenLocal(prev.name, prev.type, prev.signature, address);
                    }
                    break;
                }

                case DBG_SET_PROLOGUE_END:
                case DBG_SET_EPILOGUE_BEGIN:
                    // Breakpoint hints only. Nothing in a class file carries
                    // them, so they are consumed and dropped.
                    break;

                case DBG_SET_FILE: {
                    String f = dex.string(r.uleb128p1());
                    if (sourceFileOverride == null) {
                        sourceFileOverride = f;
                    }
                    break;
                }

                default: {
                    if (opcode < DBG_FIRST_SPECIAL) {
                        // Unknown low opcode: the stream is desynchronized and
                        // we cannot know its operand width, so stop here rather
                        // than emit garbage positions.
                        break loop;
                    }
                    int adjusted = opcode - DBG_FIRST_SPECIAL;
                    address += adjusted / DBG_LINE_RANGE;
                    line += DBG_LINE_BASE + (adjusted % DBG_LINE_RANGE);
                    if (positionInts == positions.length) {
                        positions = Arrays.copyOf(positions,
                                positions.length == 0 ? 32 : positions.length * 2);
                    }
                    positions[positionInts++] = address;
                    positions[positionInts++] = line;
                    break;
                }
            }
        }

        // Anything still open runs to the end of the method.
        for (int reg = 0; reg < live.length; reg++) {
            if (live[reg] != null) {
                closeLocal(locals, live, ended, reg, insns.length);
            }
        }

        return new DebugInfo(
                lineStart,
                positionInts == positions.length ? positions
                                                 : Arrays.copyOf(positions, positionInts),
                Collections.unmodifiableList(locals),
                Collections.unmodifiableList(Arrays.asList(paramNames)),
                sourceFileOverride);
    }

    /**
     * Opens the implicit locals every method starts with: "this" for an
     * instance method, then each named parameter.
     *
     * <p>The argument registers are the last {@code ins_size} of the frame, so
     * they begin at {@code registersSize - insSize}. AOSP's decoder derives the
     * same base by subtracting the prototype's word count plus the "this" slot;
     * using ins_size directly is equivalent and does not depend on the
     * prototype agreeing with the frame.
     */
    private void seedParameters(String[] paramNames, OpenLocal[] live, OpenLocal[] ended) {
        int base = registersSize - insSize;
        if (base < 0) {
            return;
        }
        int reg = base;
        if (!method.isStatic()) {
            if (reg < live.length) {
                live[reg] = new OpenLocal("this", method.declaringClass().descriptor(), null, 0);
            }
            reg++;
        }
        DexFile.Proto proto = method.proto();
        if (proto == null) {
            return;
        }
        List<String> types = proto.parameterTypes();
        for (int i = 0; i < types.size() && reg < live.length; i++) {
            String type = types.get(i);
            String name = i < paramNames.length ? paramNames[i] : null;
            if (name != null) {
                live[reg] = new OpenLocal(name, type, null, 0);
            }
            // Wide types occupy two registers; the second is not addressable
            // as a separate local.
            char c = type == null || type.isEmpty() ? 'L' : type.charAt(0);
            reg += (c == 'J' || c == 'D') ? 2 : 1;
        }
    }

    private void startLocal(List<Local> out, OpenLocal[] live, OpenLocal[] ended,
                            int reg, String name, String type, String signature, int address) {
        if (reg < 0 || reg >= live.length) {
            return;
        }
        if (live[reg] != null) {
            // A new declaration in a live register implicitly ends the old one.
            // Well-formed dx output always emits DBG_END_LOCAL first, but
            // obfuscators do not always bother.
            closeLocal(out, live, ended, reg, address);
        }
        live[reg] = new OpenLocal(name, type, signature, address);
    }

    private void closeLocal(List<Local> out, OpenLocal[] live, OpenLocal[] ended,
                            int reg, int address) {
        OpenLocal open = live[reg];
        live[reg] = null;
        ended[reg] = open;
        if (open.name != null && address > open.start) {
            // A zero-length range is legal in the stream but useless in a
            // LocalVariableTable, and an unnamed local has nothing to record.
            out.add(new Local(reg, open.name, open.type, open.signature, open.start, address));
        }
    }

    /** A local declaration that has been opened but not yet closed. */
    private static final class OpenLocal {
        final String name;
        final String type;
        final String signature;
        final int start;

        OpenLocal(String name, String type, String signature, int start) {
            this.name = name;
            this.type = type;
            this.signature = signature;
            this.start = start;
        }
    }
}
