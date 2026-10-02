package io.github.kksimp.dex2jvm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import io.github.kksimp.dex2jvm.InstructionDecoder.MalformedCodeException;

/**
 * The control-flow graph of one Dalvik method: the decoded instruction stream,
 * the basic blocks over it, and the exception edges implied by the try/catch
 * table.
 *
 * <p><b>Exception edges are modelled at instruction granularity.</b> A DEX try
 * range covers a span of instructions, and ANY of them can transfer control to
 * the handler, so the handler's incoming register state is the merge of the
 * state before each covered instruction, not the state at the end of some
 * block. {@link #handlersFor(int)} is therefore the authoritative view and the
 * one a type checker must use; {@link BasicBlock#exceptionSuccessors()} is the
 * coarser block-level union, useful for reachability and traversal.
 *
 * <p>Use {@link #build(DexCode)} for a parsed method. Everything below that
 * entry point works on a raw code array plus a {@link TryCatchBlock} table and
 * touches nothing else in the DEX object model, so the decoder and the graph
 * stay unit-testable on hand-written bytecode.
 */
public final class ControlFlowGraph {

    /**
     * One {@code try_item} plus its resolved
     * {@code encoded_catch_handler}: the range of code units it covers and the
     * ordered list of catch clauses.
     *
     * <p>Order matters and is the DEX order: the typed clauses first, in the
     * order the compiler wrote them, then the catch-all (from
     * {@code catch_all_addr}) last if present. A catch-all carries a null type,
     * matching {@link DexCode.Handler#type()}.
     */
    public static final class TryCatchBlock {

        private final int startAddress;
        private final int insnCount;
        private final String[] catchTypes;
        private final int[] catchAddresses;

        /**
         * @param startAddress   {@code try_item.start_addr}, in code units
         * @param insnCount      {@code try_item.insn_count}, in code units
         * @param catchTypes     one entry per catch clause: the exception type
         *                       descriptor, or null for the catch-all
         * @param catchAddresses the handler addresses, parallel to
         *                       {@code catchTypes}
         */
        public TryCatchBlock(int startAddress, int insnCount,
                             String[] catchTypes, int[] catchAddresses) {
            if (catchTypes.length != catchAddresses.length) {
                throw new IllegalArgumentException("catch type/address arrays differ in length");
            }
            this.startAddress = startAddress;
            this.insnCount = insnCount;
            this.catchTypes = catchTypes;
            this.catchAddresses = catchAddresses;
        }

        /** First covered code unit. */
        public int startAddress() {
            return startAddress;
        }

        /** Number of code units covered. */
        public int insnCount() {
            return insnCount;
        }

        /** One past the last covered code unit. */
        public int endAddress() {
            return startAddress + insnCount;
        }

        /** Number of catch clauses. */
        public int handlerCount() {
            return catchAddresses.length;
        }

        /** Exception type descriptor of clause {@code i}, or null for catch-all. */
        public String catchType(int i) {
            return catchTypes[i];
        }

        /** Handler address of clause {@code i}, in code units. */
        public int catchAddress(int i) {
            return catchAddresses[i];
        }
    }

    /** One catch clause that applies to a given instruction. */
    public static final class CatchHandler {

        private final String type;
        private final int address;
        private BasicBlock block;

        CatchHandler(String type, int address) {
            this.type = type;
            this.address = address;
        }

        /** Exception type descriptor, or null for a catch-all. */
        public String type() {
            return type;
        }

        /** True if this clause catches everything (Throwable / finally). */
        public boolean isCatchAll() {
            return type == null;
        }

        /** Handler entry address in code units. */
        public int address() {
            return address;
        }

        /** The block the handler starts at. Always a {@code isCatchHandler()} block. */
        public BasicBlock block() {
            return block;
        }

        @Override
        public String toString() {
            return (isCatchAll() ? "catch-all" : "catch " + type)
                    + " -> " + String.format("%04x", address);
        }
    }

    private static final TryCatchBlock[] NO_TRIES = new TryCatchBlock[0];

    private final int registersSize;
    private final int codeUnitCount;
    private final Instruction[] instructions;
    private final Instruction[] byAddress;
    private final List<CatchHandler>[] handlersByAddress;
    private final List<BasicBlock> blocks;
    private final List<BasicBlock> dataBlocks;
    private final BasicBlock[] blockStartingAt;
    private final BasicBlock entry;
    private final int splitResultPairs;

    private List<BasicBlock> reversePostOrder;
    private List<BasicBlock> unreachable;

    // ------------------------------------------------------------------
    // Construction.
    // ------------------------------------------------------------------

    /**
     * Decodes a parsed method body and builds its graph. This is the normal
     * entry point.
     *
     * @param code a method's {@code code_item}, i.e. {@code DexMethod.code()};
     *             must not be null (abstract and native methods have none)
     */
    public static ControlFlowGraph build(DexCode code) {
        List<DexCode.Try> tries = code.tries();
        TryCatchBlock[] table = new TryCatchBlock[tries.size()];
        for (int t = 0; t < table.length; t++) {
            DexCode.Try item = tries.get(t);
            List<DexCode.Handler> handlers = item.handlers();
            String[] types = new String[handlers.size()];
            int[] addresses = new int[handlers.size()];
            for (int h = 0; h < handlers.size(); h++) {
                types[h] = handlers.get(h).type();
                addresses[h] = handlers.get(h).address();
            }
            table[t] = new TryCatchBlock(item.startAddress(), item.instructionCount(),
                    types, addresses);
        }
        return build(code.insns(), code.registersSize(), table);
    }

    /**
     * Decodes {@code insns} and builds the graph.
     *
     * @param insns         the method's code units, i.e. {@code DexCode.insns()}
     * @param registersSize {@code DexCode.registersSize()}; carried through for
     *                      consumers, not used here
     * @param tries         the try/catch table, or null/empty if the method has none
     * @throws MalformedCodeException if the bytecode cannot be decoded or a
     *                                branch target does not land on an
     *                                instruction boundary
     */
    public static ControlFlowGraph build(short[] insns, int registersSize, TryCatchBlock[] tries) {
        return new ControlFlowGraph(InstructionDecoder.decode(insns), insns.length,
                registersSize, tries);
    }

    /** As {@link #build(short[], int, TryCatchBlock[])} for an already-decoded method. */
    public static ControlFlowGraph build(Instruction[] decoded, int codeUnitCount,
                                         int registersSize, TryCatchBlock[] tries) {
        return new ControlFlowGraph(decoded, codeUnitCount, registersSize, tries);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ControlFlowGraph(Instruction[] instructions, int codeUnitCount,
                             int registersSize, TryCatchBlock[] tries) {
        if (instructions.length == 0) {
            throw new MalformedCodeException("method has no instructions");
        }
        this.instructions = instructions;
        this.codeUnitCount = codeUnitCount;
        this.registersSize = registersSize;
        this.byAddress = InstructionDecoder.addressIndex(instructions, codeUnitCount);
        this.handlersByAddress = new List[codeUnitCount];

        TryCatchBlock[] tryTable = tries == null ? NO_TRIES : tries;
        CatchHandler[][] perTry = buildHandlerObjects(tryTable);
        spreadHandlersOverRanges(tryTable, perTry);

        // Freeze each handler list into an unmodifiable view once, so
        // handlersFor() on the dataflow hot path does not allocate a wrapper
        // per instruction per pass.
        for (int i = 0; i < handlersByAddress.length; i++) {
            if (handlersByAddress[i] != null) {
                handlersByAddress[i] = Collections.unmodifiableList(handlersByAddress[i]);
            }
        }

        boolean[] data = classifyData();
        boolean[] leaders = computeLeaders(tryTable, data);
        this.blocks = new ArrayList<>();
        this.dataBlocks = new ArrayList<>();
        this.blockStartingAt = new BasicBlock[codeUnitCount];
        buildBlocks(leaders, data);

        BasicBlock first = blockStartingAt[0];
        if (first == null || first.isData()) {
            throw new MalformedCodeException("method does not start with an instruction");
        }
        this.entry = first;

        for (CatchHandler[] handlers : perTry) {
            for (CatchHandler handler : handlers) {
                handler.block = blockStartingAt[handler.address()];
                handler.block.markCatchHandler();
            }
        }

        wireNormalEdges();
        wireExceptionEdges();
        this.splitResultPairs = countSplitResultPairs();
    }

    private CatchHandler[][] buildHandlerObjects(TryCatchBlock[] tries) {
        CatchHandler[][] perTry = new CatchHandler[tries.length][];
        for (int t = 0; t < tries.length; t++) {
            TryCatchBlock item = tries[t];
            CatchHandler[] handlers = new CatchHandler[item.handlerCount()];
            for (int h = 0; h < handlers.length; h++) {
                int address = item.catchAddress(h);
                requireBoundary(address, "catch handler of try at "
                        + String.format("%04x", item.startAddress()));
                handlers[h] = new CatchHandler(item.catchType(h), address);
            }
            perTry[t] = handlers;
        }
        return perTry;
    }

    /**
     * Attaches every try's handler list to EVERY instruction the try covers.
     *
     * <p>This is the part that is easy to get wrong and expensive to debug: a
     * handler is a successor of every instruction in the range, not only of
     * the last one, because the exception can be raised anywhere inside. The
     * coverage test is an overlap test rather than {@code start <= addr < end}
     * so that a range whose bound falls inside an instruction still covers it,
     * matching the reference converter.
     */
    private void spreadHandlersOverRanges(TryCatchBlock[] tries, CatchHandler[][] perTry) {
        for (int t = 0; t < tries.length; t++) {
            TryCatchBlock item = tries[t];
            int start = item.startAddress();
            int end = item.endAddress();
            for (int i = firstOverlapping(start); i < instructions.length; i++) {
                Instruction insn = instructions[i];
                if (insn.address() >= end) {
                    break;
                }
                if (insn.isPayload()) {
                    continue;
                }
                List<CatchHandler> list = handlersByAddress[insn.address()];
                if (list == null) {
                    list = new ArrayList<>(perTry[t].length);
                    handlersByAddress[insn.address()] = list;
                }
                appendPruned(list, perTry[t]);
            }
        }
    }

    /** Index of the first instruction whose extent overlaps {@code start}. */
    private int firstOverlapping(int start) {
        int lo = 0;
        int hi = instructions.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (instructions[mid].nextAddress() > start) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    /**
     * Appends {@code handlers} to {@code list}, dropping clauses that can never
     * fire: a second clause for a type already listed, and anything after a
     * catch-all. Valid DEX gives an address at most one try_item, so this only
     * matters for hand-written or obfuscated overlapping ranges.
     */
    private static void appendPruned(List<CatchHandler> list, CatchHandler[] handlers) {
        if (!list.isEmpty() && list.get(list.size() - 1).isCatchAll()) {
            return;
        }
        for (CatchHandler handler : handlers) {
            boolean duplicate = false;
            for (CatchHandler existing : list) {
                if (java.util.Objects.equals(existing.type(), handler.type())) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                list.add(handler);
            }
            if (handler.isCatchAll()) {
                return;
            }
        }
    }

    /**
     * Marks which instructions are data rather than code: the payload
     * pseudo-instructions, and the alignment nops in front of them.
     *
     * <p>A payload must start on a 4-byte boundary, so when the preceding
     * instruction ends on an odd code unit the compiler emits a one-unit
     * {@code nop} as padding. That nop is unreachable (whatever precedes it
     * always returns, throws or gotos) but it is a real, fall-through-capable
     * opcode, so treating it as code would produce a block that "falls into"
     * a payload. F-Droid alone has 198 methods shaped that way, so this is the
     * normal case, not a corner case.
     */
    private boolean[] classifyData() {
        boolean[] data = new boolean[instructions.length];
        for (int i = instructions.length - 1; i >= 0; i--) {
            Instruction insn = instructions[i];
            if (insn.isPayload()) {
                data[i] = true;
            } else if (insn.opcode() == Opcodes.NOP && i + 1 < instructions.length
                    && data[i + 1]) {
                data[i] = true;
            }
        }
        return data;
    }

    /**
     * Marks the instructions that start a basic block: the method entry, every
     * branch / switch target, the instruction after any instruction that does
     * not simply fall through, every catch-handler entry, and the boundaries
     * of each payload.
     *
     * <p>Try-range boundaries are deliberately NOT leaders. Compilers routinely
     * end a try range between an invoke and its move-result, and splitting
     * there would separate a pair that must stay together. Precision is
     * recovered by keeping exception edges per instruction; see
     * {@link #handlersFor(int)}.
     */
    private boolean[] computeLeaders(TryCatchBlock[] tries, boolean[] data) {
        boolean[] leaders = new boolean[instructions.length];
        leaders[0] = true;
        for (int i = 0; i < instructions.length; i++) {
            Instruction insn = instructions[i];
            if (data[i]) {
                // Data: its own block, and whatever follows starts a new one.
                leaders[i] = true;
                if (i + 1 < instructions.length) {
                    leaders[i + 1] = true;
                }
                continue;
            }
            boolean endsBlock = false;
            if (insn.isBranch()) {
                markLeader(leaders, insn.target(), insn);
                endsBlock = true;
            }
            if (insn.isSwitch()) {
                for (int target : insn.switchTargets()) {
                    markLeader(leaders, target, insn);
                }
                endsBlock = true;
            }
            if (!insn.canContinue()) {
                endsBlock = true;
            }
            if (endsBlock && i + 1 < instructions.length) {
                leaders[i + 1] = true;
            }
        }
        for (TryCatchBlock item : tries) {
            for (int h = 0; h < item.handlerCount(); h++) {
                markLeader(leaders, item.catchAddress(h), null);
            }
        }
        return leaders;
    }

    private void markLeader(boolean[] leaders, int address, Instruction from) {
        Instruction target = boundaryAt(address);
        if (target == null) {
            throw new MalformedCodeException(String.format(
                    "%s targets 0x%04x, which is not an instruction boundary",
                    from == null ? "catch handler" : from.opcodeName() + " at "
                            + String.format("0x%04x", from.address()), address));
        }
        leaders[indexOf(target)] = true;
    }

    private void buildBlocks(boolean[] leaders, boolean[] data) {
        List<Instruction> current = new ArrayList<>();
        int nextId = 0;
        int startIndex = 0;
        for (int i = 0; i < instructions.length; i++) {
            if (leaders[i] && !current.isEmpty()) {
                nextId = flush(current, nextId, data[startIndex]);
                startIndex = i;
            }
            current.add(instructions[i]);
        }
        if (!current.isEmpty()) {
            flush(current, nextId, data[startIndex]);
        }
    }

    private int flush(List<Instruction> current, int nextId, boolean data) {
        BasicBlock block = new BasicBlock(nextId, new ArrayList<>(current), data);
        (data ? dataBlocks : blocks).add(block);
        blockStartingAt[block.startAddress()] = block;
        current.clear();
        return nextId + 1;
    }

    private void wireNormalEdges() {
        for (int i = 0; i < blocks.size(); i++) {
            BasicBlock block = blocks.get(i);
            Instruction last = block.last();
            if (last.isBranch()) {
                block.addSuccessor(requireCodeBlock(last.target(), last));
            }
            if (last.isSwitch()) {
                for (int target : last.switchTargets()) {
                    block.addSuccessor(requireCodeBlock(target, last));
                }
            }
            if (last.canContinue()) {
                block.addSuccessor(requireCodeBlock(last.nextAddress(), last));
            }
        }
    }

    private BasicBlock requireCodeBlock(int address, Instruction from) {
        BasicBlock block = address >= 0 && address < codeUnitCount
                ? blockStartingAt[address] : null;
        if (block == null) {
            throw new MalformedCodeException(String.format(
                    "%s at 0x%04x transfers control to 0x%04x, which starts no block",
                    from.opcodeName(), from.address(), address));
        }
        if (block.isData()) {
            throw new MalformedCodeException(String.format(
                    "%s at 0x%04x transfers control into a payload at 0x%04x",
                    from.opcodeName(), from.address(), address));
        }
        return block;
    }

    private void wireExceptionEdges() {
        for (BasicBlock block : blocks) {
            for (Instruction insn : block.instructions()) {
                List<CatchHandler> handlers = handlersByAddress[insn.address()];
                if (handlers == null) {
                    continue;
                }
                for (CatchHandler handler : handlers) {
                    block.addExceptionSuccessor(handler.block());
                }
            }
        }
    }

    /**
     * Counts move-result instructions that ended up in a different block from
     * the instruction that produced their value. Valid DEX never does this
     * (ART's verifier rejects branching to a move-result), so a non-zero count
     * means either an obfuscated input or a bug in the leader computation.
     */
    private int countSplitResultPairs() {
        int count = 0;
        for (BasicBlock block : blocks) {
            Instruction first = block.first();
            if (first.isMoveResult() && first.resultProducer() != null) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    // Queries.
    // ------------------------------------------------------------------

    /** {@code DexCode.registersSize()}, carried through from the caller. */
    public int registersSize() {
        return registersSize;
    }

    /** Length of the method's code array in 16-bit code units. */
    public int codeUnitCount() {
        return codeUnitCount;
    }

    /**
     * Every decoded instruction in address order, INCLUDING the payload
     * pseudo-instructions. Not copied: do not mutate.
     */
    public Instruction[] instructions() {
        return instructions;
    }

    /**
     * The instruction starting at {@code address}, or null if the address is
     * out of range or lands inside an instruction. This doubles as the
     * "is this a legal branch target" test.
     */
    public Instruction instructionAt(int address) {
        return boundaryAt(address);
    }

    /** Code basic blocks in address order. Excludes payload data blocks. */
    public List<BasicBlock> blocks() {
        return Collections.unmodifiableList(blocks);
    }

    /** The payload pseudo-instruction blocks, in address order. */
    public List<BasicBlock> dataBlocks() {
        return Collections.unmodifiableList(dataBlocks);
    }

    /** The block containing the method's first instruction. */
    public BasicBlock entry() {
        return entry;
    }

    /** The block starting exactly at {@code address}, or null. */
    public BasicBlock blockAt(int address) {
        return address >= 0 && address < codeUnitCount ? blockStartingAt[address] : null;
    }

    /**
     * The catch clauses that apply to the instruction at {@code address}, in
     * the order they must be tried, or an empty list.
     *
     * <p>This is the precise, per-instruction exception edge set. A type
     * checker should, for each covered instruction, merge the register state
     * BEFORE that instruction into each listed handler.
     */
    public List<CatchHandler> handlersFor(int address) {
        List<CatchHandler> list = address >= 0 && address < codeUnitCount
                ? handlersByAddress[address] : null;
        return list == null ? Collections.emptyList() : list;
    }

    /** True if any instruction in the method is covered by a try range. */
    public boolean hasExceptionHandlers() {
        for (BasicBlock block : blocks) {
            if (!block.exceptionSuccessors().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Code blocks in reverse postorder over normal AND exception edges: a
     * dataflow worklist seeded in this order converges in far fewer passes
     * than address order. Unreachable blocks are not included.
     */
    public List<BasicBlock> reversePostOrder() {
        if (reversePostOrder == null) {
            computeOrder();
        }
        return reversePostOrder;
    }

    /**
     * Code blocks not reachable from {@link #entry()} along any edge. Dead
     * code is legal in DEX (obfuscators emit it), so this is informational,
     * not an error.
     */
    public List<BasicBlock> unreachableBlocks() {
        if (unreachable == null) {
            computeOrder();
        }
        return unreachable;
    }

    /**
     * Number of move-result instructions separated from their producer by a
     * block boundary. Always 0 for DEX that ART would verify.
     */
    public int splitResultPairs() {
        return splitResultPairs;
    }

    private void computeOrder() {
        boolean[] visited = new boolean[blocks.size() + dataBlocks.size()];
        List<BasicBlock> postOrder = new ArrayList<>(blocks.size());
        // Iterative DFS: methods with thousands of blocks would blow a
        // recursive one, and obfuscated code routinely has them.
        Deque<BasicBlock> stack = new ArrayDeque<>();
        Deque<Integer> cursor = new ArrayDeque<>();
        stack.push(entry);
        cursor.push(0);
        visited[entry.id()] = true;
        while (!stack.isEmpty()) {
            BasicBlock block = stack.peek();
            int at = cursor.pop();
            List<BasicBlock> normal = block.successors();
            List<BasicBlock> handlers = block.exceptionSuccessors();
            BasicBlock next = null;
            while (at < normal.size() + handlers.size()) {
                BasicBlock candidate = at < normal.size()
                        ? normal.get(at) : handlers.get(at - normal.size());
                at++;
                if (!visited[candidate.id()]) {
                    next = candidate;
                    break;
                }
            }
            cursor.push(at);
            if (next != null) {
                visited[next.id()] = true;
                stack.push(next);
                cursor.push(0);
            } else {
                stack.pop();
                cursor.pop();
                postOrder.add(block);
            }
        }
        List<BasicBlock> rpo = new ArrayList<>(postOrder.size());
        for (int i = postOrder.size() - 1; i >= 0; i--) {
            rpo.add(postOrder.get(i));
        }
        List<BasicBlock> dead = new ArrayList<>();
        for (BasicBlock block : blocks) {
            if (!visited[block.id()]) {
                dead.add(block);
            }
        }
        this.reversePostOrder = Collections.unmodifiableList(rpo);
        this.unreachable = Collections.unmodifiableList(dead);
    }

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

    private Instruction boundaryAt(int address) {
        return address >= 0 && address < codeUnitCount ? byAddress[address] : null;
    }

    private void requireBoundary(int address, String what) {
        if (boundaryAt(address) == null) {
            throw new MalformedCodeException(String.format(
                    "%s points at 0x%04x, which is not an instruction boundary", what, address));
        }
    }

    private int indexOf(Instruction insn) {
        int lo = 0;
        int hi = instructions.length - 1;
        int want = insn.address();
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int at = instructions[mid].address();
            if (at == want) {
                return mid;
            }
            if (at < want) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        throw new IllegalStateException("instruction not in stream: " + insn);
    }

    @Override
    public String toString() {
        return "ControlFlowGraph[" + blocks.size() + " blocks, "
                + instructions.length + " instructions]";
    }
}
