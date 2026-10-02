package io.github.kksimp.dex2jvm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A maximal run of instructions with a single entry point, as produced by
 * {@link ControlFlowGraph}.
 *
 * <p>Normal edges (fallthrough, goto, if, switch) and exception edges are kept
 * apart: {@link #successors()} is normal control flow only, and
 * {@link #exceptionSuccessors()} lists the catch-handler blocks reachable from
 * anywhere inside this block. Merging them would lose the distinction a type
 * checker needs, since an exception edge carries the register state from
 * BEFORE a covered instruction rather than after the block.
 *
 * <p>Payload pseudo-instructions (packed-switch, sparse-switch,
 * fill-array-data) are data, not code. They live in their own blocks, flagged
 * {@link #isData()}, which are excluded from {@link ControlFlowGraph#blocks()}.
 */
public final class BasicBlock {

    private final int id;
    private final List<Instruction> instructions;
    private final boolean data;
    private boolean catchHandler;

    private final List<BasicBlock> successors = new ArrayList<>(2);
    private final List<BasicBlock> predecessors = new ArrayList<>(2);
    private final List<BasicBlock> exceptionSuccessors = new ArrayList<>(0);
    private final List<BasicBlock> exceptionPredecessors = new ArrayList<>(0);

    // Unmodifiable views are live over the lists above, so they are built once
    // rather than per accessor call. A dataflow fixpoint walks these edges
    // millions of times per APK; wrapping on each call would be pure garbage.
    private final List<BasicBlock> successorsView =
            Collections.unmodifiableList(successors);
    private final List<BasicBlock> predecessorsView =
            Collections.unmodifiableList(predecessors);
    private final List<BasicBlock> exceptionSuccessorsView =
            Collections.unmodifiableList(exceptionSuccessors);
    private final List<BasicBlock> exceptionPredecessorsView =
            Collections.unmodifiableList(exceptionPredecessors);

    BasicBlock(int id, List<Instruction> instructions, boolean data) {
        this.id = id;
        this.instructions = Collections.unmodifiableList(instructions);
        this.data = data;
    }

    /** Dense block id, unique within the owning graph. */
    public int id() {
        return id;
    }

    /** Code-unit address of the first instruction. */
    public int startAddress() {
        return instructions.get(0).address();
    }

    /** Code-unit address one past the last instruction (exclusive). */
    public int endAddress() {
        return instructions.get(instructions.size() - 1).nextAddress();
    }

    /** The instructions, in address order. Never empty. Unmodifiable. */
    public List<Instruction> instructions() {
        return instructions;
    }

    /** The first instruction. */
    public Instruction first() {
        return instructions.get(0);
    }

    /** The last instruction: the one that decides the block's successors. */
    public Instruction last() {
        return instructions.get(instructions.size() - 1);
    }

    /** Number of instructions in the block. */
    public int size() {
        return instructions.size();
    }

    /**
     * True if this block holds data rather than code: a payload
     * pseudo-instruction, or an unreachable alignment {@code nop} emitted to
     * put a following payload on a 4-byte boundary. Data blocks have no edges
     * and are not in {@link ControlFlowGraph#blocks()}.
     */
    public boolean isData() {
        return data;
    }

    /** True if this block is the entry point of a catch handler. */
    public boolean isCatchHandler() {
        return catchHandler;
    }

    /**
     * Normal (non-exception) successors: the fallthrough block, branch
     * targets, and every switch case target. Unmodifiable.
     */
    public List<BasicBlock> successors() {
        return successorsView;
    }

    /** Blocks with a normal edge into this one. Unmodifiable. */
    public List<BasicBlock> predecessors() {
        return predecessorsView;
    }

    /**
     * Catch-handler blocks reachable from anywhere inside this block: the
     * union over every instruction here of
     * {@link ControlFlowGraph#handlersFor(int)}.
     *
     * <p>This is the block-granularity view, which is conservative but
     * imprecise: it does not say WHICH instruction can reach a handler, and a
     * handler's incoming register state is the merge over the state before
     * each covered instruction, not the state at the end of this block. A
     * type checker must use {@link ControlFlowGraph#handlersFor(int)}
     * per instruction instead.
     */
    public List<BasicBlock> exceptionSuccessors() {
        return exceptionSuccessorsView;
    }

    /** Blocks that can throw into this handler block. Unmodifiable. */
    public List<BasicBlock> exceptionPredecessors() {
        return exceptionPredecessorsView;
    }

    /** True if control can fall out of the last instruction into the next block. */
    public boolean fallsThrough() {
        return last().canContinue();
    }

    /** Total number of incoming edges, normal plus exception. */
    public int predecessorCount() {
        return predecessors.size() + exceptionPredecessors.size();
    }

    void markCatchHandler() {
        this.catchHandler = true;
    }

    void addSuccessor(BasicBlock target) {
        if (!successors.contains(target)) {
            successors.add(target);
            target.predecessors.add(this);
        }
    }

    void addExceptionSuccessor(BasicBlock handler) {
        if (!exceptionSuccessors.contains(handler)) {
            exceptionSuccessors.add(handler);
            handler.exceptionPredecessors.add(this);
        }
    }

    @Override
    public String toString() {
        return String.format("B%d[%04x..%04x)%s%s", id, startAddress(), endAddress(),
                data ? " data" : "", catchHandler ? " handler" : "");
    }
}
