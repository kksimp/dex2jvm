// InitRepointPlan -- undoes R8's constructor re-pointing so the output passes
// HotSpot's split verifier.
//
// THE BUG THIS FIXES
// R8 deletes a constructor whose body is nothing but `super(...)` and rewrites
// every call site to invoke the SUPERCLASS constructor directly:
//
//     new-instance     v0, Lcleaner$2;
//     invoke-direct    {v0, v1}, Lkotlin/jvm/internal/Lambda;-><init>(I)V
//
// ART is deliberately permissive about that. From AOSP
// art/runtime/verifier/method_verifier.cc, case Instruction::INVOKE_DIRECT:
//
//     // Note: According to JLS, constructors are never inherited. Therefore the target
//     // constructor should be defined exactly by the `this_type`, or by the direct
//     // superclass in the case of a constructor calling the superclass constructor.
//     // However, ART had this check commented out for a very long time and this has
//     // allowed bytecode optimizers such as R8 to inline constructors, often calling
//     // `j.l.Object.<init>` directly without any intermediate constructor. ...
//     // Therefore it is undesirable to reinstate this check and ART deliberately
//     // remains permissive here and diverges from the RI.
//
// The JVM is not permissive. JVMS SE21 4.9.2 (Structural Constraints):
//
//     "If an invokespecial instruction names an instance initialization method
//      and the target reference on the operand stack is a class instance created
//      by an earlier new instruction, then invokespecial must name an instance
//      initialization method from the class of that class instance."
//
//     "If the target reference on the operand stack is an uninitialized class
//      instance for the current class, then invokespecial must name an instance
//      initialization method from the current class or its direct superclass."
//
// and the type rule that enforces the first one, JVMS 4.10.1.9:
//
//     rewrittenUninitializedType(uninitialized(Address), Environment,
//                                MethodClass, MethodClass) :-
//         allInstructions(Environment, Instructions),
//         member(instruction(Address, new(MethodClass)), Instructions).
//
// i.e. the `new` at Address must have created EXACTLY the method's class. So the
// two shapes show up as two distinct HotSpot messages, both from
// ClassVerifier::verify_invoke_init: "Call to wrong <init> method" for the
// new-instance flavour and "Bad <init> method call" for the uninitializedThis
// flavour. Together they were ~80% of every split-verifier error left in the
// test corpus.
//
// THE FIX
// Put back the constructor R8 deleted. For a call site `new T` + `S.<init>(D)`,
// synthesise on T exactly what the source had --
//
//     T.<init>(D) { super(D); }
//
// -- and re-point the call site at `T.<init>(D)`. When R8 skipped SEVERAL levels
// (its favourite trick is to name java/lang/Object directly) the same thing is
// synthesised for every class between T and S, so each forwarding constructor
// calls its own DIRECT superclass, which is what JVMS 4.9.2 requires. The
// uninitializedThis flavour is the same operation one level up: re-point
// `T.<init>` -> `super(T).<init>(D)` and synthesise the chain from super(T) to S.
//
// WHY THIS IS SEMANTICS-PRESERVING
// A forwarding constructor executes nothing but the super call, so the sequence
// of constructor bodies that actually runs is identical to what ART runs. That
// holds only while every class in the chain either has NO constructor with that
// descriptor, or has one that is ITSELF a pure forwarder (R8 leaves plenty of
// those behind, and reusing them is what rescues the deeper chains). A class
// whose existing constructor does real work ends the chain and the site is left
// alone: routing through it would run initialisation ART never runs, and a class
// that fails to verify is a better outcome than one that runs different code.
// (Real and measured: androidx.concurrent.futures.AbstractResolvableFuture$Waiter
// keeps a working `Waiter()` and loses its empty `Waiter(boolean)`, so
// `new Waiter; Object.<init>()` must NOT be re-pointed at the surviving one.)
//
// WHY THE PLAN IS BUILT BEFORE THE PARALLEL FAN-OUT
// The call site and the class that needs the constructor are usually DIFFERENT
// classes, and conversion runs one class per worker thread. The plan is
// therefore computed once, up front, and is strictly read-only afterwards, so
// output stays byte-identical between 1 and 8 threads. Determinism does not rely
// on scan order either: every collection here is sorted before it is read.

package io.github.kksimp.dex2jvm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class InitRepointPlan {

    /** A plan that re-points nothing: the lazy path before a scan, and the
     *  DEX2JVM_INIT_REPOINT=0 escape hatch. */
    static final InitRepointPlan NONE = new InitRepointPlan(Map.of(), Map.of());

    /**
     * One constructor to synthesise.
     *
     * The two descriptors differ only when the natural one was already taken by
     * a real constructor: see PAD_TYPE below.
     */
    static final class Forward {
        /** The descriptor the synthesised constructor itself declares. */
        final String ownDescriptor;
        /**
         * The descriptor it invokes on the DIRECT superclass.
         *
         * One of the two parameter lists is always a prefix of the other, since
         * both are the descriptor the DEX named plus some number of PAD_TYPEs,
         * so the real arguments line up by slot and only the padding differs.
         * Whichever way round it is, {@link #emitForwardingConstructor} passes
         * on the parameters this constructor was given and makes up the rest.
         */
        final String superDescriptor;

        Forward(String ownDescriptor, String superDescriptor) {
            this.ownDescriptor = ownDescriptor;
            this.superDescriptor = superDescriptor;
        }
    }

    /** class -> the constructors to synthesise on it, sorted by descriptor. */
    private final Map<String, List<Forward>> synthesise;
    /**
     * "target descriptor namedOwner" -> the descriptor the call site must name.
     *
     * Keyed on all three because a chain is only proved for the ONE ancestor it
     * was walked to: two call sites can name different ancestors for the same
     * (class, descriptor) and only the nearer one may be clean.
     */
    private final Map<String, String> authorised;

    private InitRepointPlan(Map<String, List<Forward>> synthesise,
                            Map<String, String> authorised) {
        this.synthesise = synthesise;
        this.authorised = authorised;
    }

    boolean isEmpty() { return authorised.isEmpty(); }

    /**
     * Constructors to synthesise on {@code internalName}, sorted so the emitted
     * method order never depends on scan order.
     */
    List<Forward> syntheticConstructors(String internalName) {
        List<Forward> m = synthesise.get(internalName);
        return m == null ? List.of() : m;
    }

    /**
     * The descriptor a call site naming {@code namedOwner.<init>(desc)} must use
     * when re-pointed at {@code target}, or null when it must be left alone.
     *
     * Usually {@code desc} itself. It differs only for the padded case, where
     * the caller must push one extra null per added parameter.
     */
    String repointDescriptor(String target, String desc, String namedOwner) {
        return authorised.get(target + ' ' + desc + ' ' + namedOwner);
    }

    /**
     * The filler parameter type used when the constructor R8 deleted cannot be
     * put back under its own descriptor, because a DIFFERENT constructor with
     * that exact descriptor survived and does real work.
     *
     * Measured, and not rare: R8 merges several exception classes into one, so
     * androidx.startup.StartupException ends up owning a `()V` that stores a
     * protobuf message, while 116 call sites still ask for a plain
     * `new StartupException` via `RuntimeException.<init>()V`. Re-pointing at
     * the surviving constructor would run initialisation ART does not run, and
     * a class file cannot hold two constructors with one descriptor.
     *
     * Adding an ignored parameter is how javac itself sidesteps exactly this
     * collision (the synthetic `Foo(Foo$1 unused)` it emits for a private
     * constructor). java/lang/Void is the natural filler: it exists everywhere,
     * cannot be instantiated, and no real API takes one.
     */
    private static final String PAD_TYPE = "Ljava/lang/Void;";

    /** Number of extra {@link #PAD_TYPE} parameters {@code own} adds to {@code base}. */
    static int padCount(String own, String base) {
        return parameterDescriptors(own).size() - parameterDescriptors(base).size();
    }

    /** {@code desc} with {@code pads} extra ignored parameters appended. */
    private static String padDescriptor(String desc, int pads) {
        int close = desc.indexOf(')');
        StringBuilder sb = new StringBuilder(desc.substring(0, close));
        for (int i = 0; i < pads; i++) sb.append(PAD_TYPE);
        return sb.append(desc, close, desc.length()).toString();
    }

    // ==================================================================
    // Building the plan
    // ==================================================================

    /**
     * Scan every method in the session and decide which forwarding constructors
     * to synthesise.
     *
     * The pairing done here is a cheap linear scan, so it is allowed to be
     * IMPRECISE in one direction only: it may propose a (class, descriptor) pair
     * no call site turns out to need, which costs one unreachable 5-byte method.
     * It may not cause a wrong re-point, because every site is re-checked
     * against the real type inference before the invokespecial is emitted (see
     * Translator.MethodTranslator.invalidRepoints).
     */
    static InitRepointPlan build(Map<String, DexClass> byName) {
        // Candidate (startClass, namedOwner, descriptor) triples. Sorted so a
        // debug dump is stable; correctness does not depend on the order.
        final Map<String, Candidate> candidates = new TreeMap<>();
        for (DexClass c : byName.values()) {
            // Released at the bottom of this iteration. transientCode below is
            // careful not to memoize a code_item for the same reason, but that
            // was only half the story: c.methods() itself memoizes the whole
            // class_data parse onto a DexClass the Session holds for its entire
            // life, so a scan of every class in the app pinned every class in
            // the app. See DexClass.releaseMembers.
            for (DexMethod m : c.methods()) {
                DexCode code = transientCode(m);
                if (code == null) continue;
                Instruction[] decoded;
                try {
                    decoded = InstructionDecoder.decode(code.insns());
                } catch (RuntimeException e) {
                    // A method we cannot even decode is one Translator will fail
                    // on too; it must not take the whole plan down with it.
                    continue;
                }
                scanMethod(decoded, m, code, (offset, uninitThis, allocated, ref) -> {
                    String start = uninitThis ? c.superclassName() : allocated;
                    if (start == null) return;
                    String owner = DexFile.internalName(ref.declaringClass());
                    String desc = ref.proto().descriptor();
                    candidates.computeIfAbsent(start + ' ' + owner + ' ' + desc,
                                    k -> new Candidate(start, owner, desc))
                              .referrers.add(c.name());
                });
            }
            c.releaseMembers();
        }

        // class -> own descriptor -> super descriptor. A TreeMap because the
        // emitted method order must not depend on discovery order.
        Map<String, TreeMap<String, String>> synth = new HashMap<>();
        Map<String, String> authorised = new HashMap<>();
        Builder b = new Builder(byName, synth);
        // Candidates are walked in sorted order so that the rare case of two of
        // them competing for one synthesised descriptor resolves identically on
        // every run.
        for (Candidate cand : candidates.values()) {
            String callDescriptor = b.plan(cand.start, cand.namedOwner, cand.desc, cand.referrers);
            if (callDescriptor == null) continue;
            authorised.put(cand.start + ' ' + cand.desc + ' ' + cand.namedOwner, callDescriptor);
        }
        // The chain walk above re-parses each ancestor's class_data looking for
        // an existing <init>, which re-memoizes it. The plan itself is nothing
        // but strings, so hand every one of those parses back. Blanket rather
        // than tracked, because releaseMembers drops a CACHE and never state:
        // over-releasing costs one re-parse, and the only caller (Session
        // .repointPlan) resolves the plan before it converts anything.
        for (DexClass c : byName.values()) {
            c.releaseMembers();
        }
        if (authorised.isEmpty()) return NONE;
        // Freeze: read from every worker thread once conversion starts.
        Map<String, List<Forward>> frozen = new HashMap<>(synth.size() * 2);
        for (Map.Entry<String, TreeMap<String, String>> e : synth.entrySet()) {
            List<Forward> out = new ArrayList<>(e.getValue().size());
            for (Map.Entry<String, String> f : e.getValue().entrySet()) {
                out.add(new Forward(f.getKey(), f.getValue()));
            }
            frozen.put(e.getKey(), Collections.unmodifiableList(out));
        }
        return new InitRepointPlan(Collections.unmodifiableMap(frozen),
                                   Collections.unmodifiableMap(authorised));
    }

    /**
     * A method's code_item, parsed and then thrown away.
     *
     * DELIBERATELY not DexMethod.code(), which MEMOIZES: this scan has to look
     * at every method in the app, and memoizing them all would pin a short[]
     * copy of the whole code section plus one object per method for the life of
     * the session. The bulk conversion path pays that anyway, but the lazy path
     * (a class loader calling Session.classBytes one class at a time) exists
     * precisely so an app's unused classes cost nothing, and one cross-class
     * pre-pass must not quietly take that away. Re-parsing the few methods that
     * are converted later is the cheaper side of the trade.
     */
    private static DexCode transientCode(DexMethod m) {
        int off = m.codeOffset();
        if (off == 0) return null;
        try {
            return new DexCode(m, off);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * One (allocated class, named ancestor, descriptor) triple to plan, plus
     * every class that names it.
     *
     * The referrers are needed because re-pointing a call site at a constructor
     * is only legal if the call site can ACCESS it (JVMS 5.4.4), and access
     * depends on who is asking. A TreeSet so the accessibility verdict -- and
     * therefore the whole plan -- cannot depend on scan order.
     */
    private static final class Candidate {
        final String start, namedOwner, desc;
        final java.util.TreeSet<String> referrers = new java.util.TreeSet<>();
        Candidate(String start, String namedOwner, String desc) {
            this.start = start;
            this.namedOwner = namedOwner;
            this.desc = desc;
        }
    }

    /** Chain walking, with the constructor-body memo it needs. Single threaded. */
    static final class Builder {
        private final Map<String, DexClass> byName;
        /** class -> own descriptor -> super descriptor, accumulated across candidates. */
        private final Map<String, TreeMap<String, String>> synthesise;
        /** "class desc" -> the class its existing <init>(desc) forwards to, or
         *  "" for "declared but not a pure forwarder", absent for "not looked at". */
        private final Map<String, String> forwardMemo = new HashMap<>();
        /**
         * "class ownDesc" -> "namedOwner desc": the chain an already-committed
         * synthesised constructor was proved to run.
         *
         * A later candidate that arrives at the same class needing the same
         * chain must REUSE it rather than trying to reserve it again, because
         * the committed one may forward under a PADDED descriptor (the class
         * above it had a real constructor in the way) and reserving would look
         * like a conflict. Measured: androidx/javascriptengine has
         * r -&gt; c -&gt; l -&gt; java/lang/Exception with a real l.&lt;init&gt;(String), so
         * `new c` commits c.&lt;init&gt;(String) -&gt; l.&lt;init&gt;(String,Void), and the
         * later `new r` and `new s` were both declined for "conflict" over a
         * constructor that already did exactly what they needed.
         */
        private final Map<String, String> chainMemo = new HashMap<>();

        Builder(Map<String, DexClass> byName, Map<String, TreeMap<String, String>> synthesise) {
            this.byName = byName;
            this.synthesise = synthesise;
        }

        /**
         * The caller of the level being walked, when that caller is a
         * constructor body we did NOT generate (an existing pure forwarder R8
         * left behind). Its super call names a fixed descriptor, so no padding
         * can be introduced at or above it.
         */
        private static final String[] FIXED_CALLER = new String[0];

        /**
         * Work out how {@code start} can reach {@code namedOwner}'s constructor
         * one direct superclass at a time, record the constructors that need
         * synthesising, and return the descriptor the CALL SITE must name.
         * Returns null when it cannot be done safely, having recorded nothing.
         *
         * A class on the way that ALREADY declares {@code <init>(desc)} is
         * reused rather than rejected, but only when that constructor does
         * nothing except pass its own arguments to another {@code <init>} with
         * the same descriptor. Then calling it runs exactly what ART runs.
         * A class whose {@code <init>(desc)} does REAL work is routed around by
         * giving the synthesised constructor an extra ignored parameter (see
         * PAD_TYPE) and telling whoever calls into that class to name the padded
         * descriptor instead. That works at any level whose caller is also ours
         * to rewrite, which is every level except one sitting above a REUSED
         * existing forwarder.
         *
         * Rejected: a chain that leaves the app (we cannot add a method to a
         * class we do not emit), a real constructor blocking the chain directly
         * above a reused forwarder (its call is already compiled and names the
         * unpadded descriptor), a namedOwner that is not an ancestor at all
         * (ART's own VerifyInvocationArgs makes that impossible, so it is a
         * corrupt-input guard), and a namedOwner of ours that declares
         * constructors but not this one, which would make the forwarding call
         * fail to link.
         */
        String plan(String start, String namedOwner, String desc, Set<String> referrers) {
            // {class, ownDesc, superDesc, provedChain}
            List<String[]> pending = new ArrayList<>(2);
            String callDescriptor = walk(start, namedOwner, desc, pending, 0, null,
                                         referrers, null);
            if (callDescriptor == null) return null;
            for (String[] f : pending) {
                synthesise.computeIfAbsent(f[0], k -> new TreeMap<>()).put(f[1], f[2]);
                // f[3], not this candidate's own namedOwner. A nested walk (the
                // one that proves the stretch above a REUSED existing forwarder)
                // terminates at a DIFFERENT ancestor, so stamping one chain
                // string across every pending entry mislabels those -- harmless
                // while the memo was only read for an exact unpadded hit, but
                // committedForChain now searches it, and a mislabelled entry
                // would be reused for a chain it does not actually run.
                chainMemo.put(f[0] + ' ' + f[1], f[3]);
            }
            return callDescriptor;
        }

        /**
         * @param caller    the pending entry for the synthesised constructor that
         *                  invokes {@code desc} on {@code start}; null when that
         *                  caller is the call site itself, FIXED_CALLER when it is
         *                  an existing constructor body
         * @param referrers the classes whose call sites name this candidate, used
         *                  to decide whether an existing constructor may be reused
         * @param namer     the class that will contain the {@code invokespecial}
         *                  naming {@code start.<init>}, or null when that is the
         *                  call sites themselves
         */
        private String walk(String start, String namedOwner, String desc,
                            List<String[]> pending, int depth, String[] caller,
                            Set<String> referrers, String namer) {
            if (depth > 16) return null;
            String callDescriptor = desc;
            // Every synthesised constructor passes its arguments straight up, so
            // the descriptor INVOKED at each level is always the one the DEX
            // named. Only the descriptor DECLARED can differ, and only where a
            // real constructor already owns the natural one.
            String[] child = caller;
            String cur = start;
            // Who will contain the invokespecial naming cur.<init>. It is the
            // call sites only for the very first level; from then on it is the
            // class one step below, whose constructor (ours or R8's) forwards up.
            String namingClass = namer;
            for (int guard = 0; guard < 256 && cur != null; guard++) {
                if (cur.equals(namedOwner)) {
                    DexClass owner = byName.get(namedOwner);
                    // A class with NO constructors at all is an R8 missing-class
                    // stub (its <clinit> throws NoClassDefFoundError); the real
                    // definition comes from the platform classpath, so its absent
                    // constructor proves nothing. Measured: wikipedia ships such
                    // a stub for android/view/autofill/AutofillManager$AutofillCallback.
                    if (owner != null && declaresAnyConstructor(owner)
                            && findConstructor(owner, desc) == null) {
                        return null;
                    }
                    return callDescriptor;
                }
                // An earlier candidate already synthesised a constructor on cur
                // and proved it runs exactly this chain. Reuse it: the rest of
                // the walk is done. It may itself live under a PADDED descriptor
                // (a class above it had a real constructor in the way), in which
                // case whoever names it has to say so.
                String reuse = committedForChain(cur, desc, namedOwner);
                if (reuse != null) {
                    if (!reuse.equals(desc)) {
                        if (child == null) callDescriptor = reuse;
                        else if (!repoint(child, reuse)) return null;
                    }
                    return callDescriptor;
                }
                DexClass c = byName.get(cur);
                if (c == null) return null;               // not ours to extend
                if (c.isInterface()) return null;         // an interface has no <init>
                String superName = c.superclassName();
                if (superName == null) return null;

                String own = desc;
                boolean blocked = false;
                DexMethod existing = findConstructor(c, desc);
                if (existing != null) {
                    // Reusing what R8 left behind is only allowed when whoever
                    // will NAME it can legally reach it. An inaccessible one is
                    // treated exactly like a real constructor owning the
                    // descriptor, i.e. routed around, because the synthesised
                    // replacement is emitted ACC_PUBLIC and always can be named.
                    String forwardsTo = canBeNamed(existing, cur, namingClass, referrers)
                            ? pureForwardTarget(c, desc) : null;
                    if (forwardsTo != null) {
                        if (!forwardsTo.equals(superName)) {
                            // Its own super call was re-pointed by R8 too. It
                            // still runs only forwardsTo's constructor, but only
                            // if everything between it and forwardsTo is a
                            // forwarder as well -- and that stretch is compiled
                            // code, so nothing in it may be padded.
                            if (walk(superName, forwardsTo, desc, pending,
                                     depth + 1, FIXED_CALLER, referrers, cur) == null) {
                                return null;
                            }
                        }
                        namingClass = cur;
                        cur = forwardsTo;
                        child = FIXED_CALLER;
                        continue;
                    }
                    blocked = true;
                } else if (declaresSynthetic(cur, desc)) {
                    // We already synthesised cur.<init>(desc) for a DIFFERENT
                    // chain (the same-chain case returned just above). It
                    // forwards to that chain's ancestor, so calling it here
                    // would run constructor bodies ART does not run at this
                    // site. Treat it exactly like a real constructor.
                    blocked = true;
                }
                if (blocked) {
                    // Route around it, which needs the level below to name the
                    // padded descriptor instead -- impossible when that level is
                    // compiled code.
                    if (child == FIXED_CALLER) return null;
                    own = freeDescriptor(c, desc, pending, namedOwner);
                    if (own == null) return null;
                }
                String[] entry = reserve(pending, cur, own, desc, namedOwner + ' ' + desc);
                if (entry == null) return null;
                if (!own.equals(desc)) {
                    if (child == null) callDescriptor = own;
                    else if (!repoint(child, own)) return null;
                }
                child = entry;
                namingClass = cur;
                cur = superName;
            }
            return null;
        }

        /**
         * Whether {@code ctor}, declared by {@code declClass}, may be named by
         * an {@code invokespecial} in {@code namingClass} -- or, when that is
         * null, by every one of {@code referrers}.
         *
         * WHY THIS GATE EXISTS. R8 re-points a call site to a SUPERCLASS
         * constructor, and the JVM then demands the call name the allocated
         * class exactly (JVMS 4.9.2), so re-pointing is forced. But the
         * constructor R8 left on the allocated class may be one the call site
         * could never have named itself, and access control is checked at
         * RESOLUTION (JVMS 5.4.3), which no verifier touches -- so a bad
         * re-point does not fail to verify, it throws IllegalAccessError the
         * first time the instruction executes. Measured 2026-07-29 on Google
         * Calculator 9.1, whose start-up failed (a blank window) because `dgo`
         * is a Serializable singleton with a PRIVATE `<init>()V` whose body is
         * nothing but `super()`. R8 rewrote three
         * unrelated classes' `new dgo` to name `java/lang/Object.<init>()V`,
         * we re-pointed all three back to the private one, and the first to run
         * died with
         *   IllegalAccessError: class cfo tried to access private method
         *   'void dgo.<init>()'
         * taking `bse`'s <clinit>, the Activity's onCreate and the whole app
         * start-up with it. ART permits the original because it never
         * re-points: it executes `Object.<init>` on the dgo, and its own access check is
         * against `java/lang/Object.<init>`, which is public.
         *
         * JVMS SE21 5.4.4 is the rule being applied:
         *   "R is private and is declared in D."  (nest clause omitted: see
         *   NestPlan -- an R8-minified app carries no dalvik EnclosingClass
         *   annotations at all, so no nest can be reconstructed and none is
         *   emitted, which makes the nest clause vacuously false here.)
         *   "R is protected ... and is declared in a class C, and D is either a
         *   subclass of C or C itself."
         *   "R is ... package private ... and is declared by a class in the same
         *   run-time package as D."
         * All the classes on a chain are ones we emit from this one session.
         * This assumes the usual arrangement, ONE class loader defining every
         * class of the converted app (as on ART, where an app's dex files share
         * one PathClassLoader); then they share a defining loader and the
         * run-time package reduces to the package name.
         */
        private boolean canBeNamed(DexMethod ctor, String declClass,
                                   String namingClass, Set<String> referrers) {
            int flags = ctor.accessFlags();
            if ((flags & DexFile.Access.ACC_PUBLIC) != 0) return true;
            if (namingClass != null) {
                // Every level past the first is named by the class one step
                // below it, i.e. always a subclass of declClass.
                return canAccess(namingClass, declClass, flags, true);
            }
            for (String r : referrers) {
                DexClass rc = byName.get(r);
                // The uninitializedThis flavour re-points a constructor's own
                // super call, so there the referrer really is a subclass.
                boolean subclass = rc != null && declClass.equals(rc.superclassName());
                if (!canAccess(r, declClass, flags, subclass)) return false;
            }
            return true;
        }

        /** JVMS 5.4.4 for a non-public member, minus the nest clause. */
        private static boolean canAccess(String from, String declClass, int flags,
                                         boolean fromIsSubclass) {
            if (from.equals(declClass)) return true;
            if ((flags & DexFile.Access.ACC_PRIVATE) != 0) return false;
            if ((flags & DexFile.Access.ACC_PROTECTED) != 0) {
                return fromIsSubclass || samePackage(from, declClass);
            }
            return samePackage(from, declClass);
        }

        /** Same run-time package: same package name, and every class here shares
         *  one class loader by construction (they all come out of this session). */
        private static boolean samePackage(String a, String b) {
            int i = a.lastIndexOf('/'), j = b.lastIndexOf('/');
            return (i < 0 ? "" : a.substring(0, i)).equals(j < 0 ? "" : b.substring(0, j));
        }

        /** A padded descriptor {@code c} does not declare and nothing else has
         *  claimed for a different super call, or null. */
        private String freeDescriptor(DexClass c, String desc, List<String[]> pending,
                                      String namedOwner) {
            for (int pads = 1; pads <= 4; pads++) {
                String own = padDescriptor(desc, pads);
                if (findConstructor(c, own) != null) continue;
                String claimed = claimedSuperDescriptor(c.name(), own, pending);
                if (claimed == null) return own;
                if (!claimed.equals(desc)) continue;
                // Claimed with the right super call. Reusable when the claim is
                // this walk's own (still pending, so no memo yet) or when it was
                // proved to run this very chain.
                String chain = chainMemo.get(c.name() + ' ' + own);
                if (chain == null || chain.equals(namedOwner + ' ' + desc)) return own;
            }
            return null;
        }

        /** The descriptor a constructor already synthesised on {@code cls}
         *  declares, when it was proved to run exactly
         *  {@code namedOwner.<init>(desc)} and nothing else, else null. */
        private String committedForChain(String cls, String desc, String namedOwner) {
            TreeMap<String, String> m = synthesise.get(cls);
            if (m == null) return null;
            String want = namedOwner + ' ' + desc;
            // TreeMap, so the answer cannot depend on discovery order.
            for (String own : m.keySet()) {
                if (want.equals(chainMemo.get(cls + ' ' + own))) return own;
            }
            return null;
        }

        /** Whether we already synthesised {@code cls.<init>(desc)} at all. */
        private boolean declaresSynthetic(String cls, String desc) {
            TreeMap<String, String> m = synthesise.get(cls);
            return m != null && m.containsKey(desc);
        }

        /**
         * Record class.&lt;init&gt;(own) -&gt; super.&lt;init&gt;(sup) and return the entry,
         * or null when something else already claimed that descriptor for a
         * different super call. Re-reserving the same triple returns the entry
         * that is already there, so the caller always gets a live handle.
         */
        private String[] reserve(List<String[]> pending, String cls, String own, String sup,
                                 String chain) {
            for (String[] f : pending) {
                if (f[0].equals(cls) && f[1].equals(own)) return f[2].equals(sup) ? f : null;
            }
            TreeMap<String, String> m = synthesise.get(cls);
            String committed = m == null ? null : m.get(own);
            // Already emitted for an earlier candidate: usable, but not ours to
            // re-point, so hand back a handle that repoint() will refuse.
            if (committed != null) return committed.equals(sup) ? FIXED_CALLER : null;
            String[] entry = new String[]{ cls, own, sup, chain };
            pending.add(entry);
            return entry;
        }

        /**
         * Change the descriptor a not-yet-committed synthesised constructor
         * invokes on its superclass, because that superclass had to declare a
         * padded one. Refuses for anything already committed or compiled.
         */
        private boolean repoint(String[] child, String newSuperDescriptor) {
            if (child == FIXED_CALLER || child.length != 4) return false;
            child[2] = newSuperDescriptor;
            return true;
        }

        private String claimedSuperDescriptor(String cls, String own, List<String[]> pending) {
            for (String[] f : pending) {
                if (f[0].equals(cls) && f[1].equals(own)) return f[2];
            }
            TreeMap<String, String> m = synthesise.get(cls);
            return m == null ? null : m.get(own);
        }

        boolean declaresAnyConstructor(DexClass c) {
            for (DexMethod m : c.methods()) if ("<init>".equals(m.name())) return true;
            return false;
        }

        /**
         * The class {@code c}'s existing {@code <init>(desc)} forwards to when
         * its whole body is {@code invoke-direct {p0..pn}, X.<init>(desc)}
         * followed by {@code return-void}, else null.
         *
         * Identical descriptor plus the parameter registers in their incoming
         * order is what proves nothing is added, dropped or reordered, so the
         * call is observationally just X's constructor.
         */
        String pureForwardTarget(DexClass c, String desc) {
            String key = c.name() + ' ' + desc;
            String memo = forwardMemo.get(key);
            if (memo != null) return memo.isEmpty() ? null : memo;
            String answer = computeForwardTarget(c, desc);
            forwardMemo.put(key, answer == null ? "" : answer);
            return answer;
        }

        private String computeForwardTarget(DexClass c, String desc) {
            DexMethod ctor = findConstructor(c, desc);
            if (ctor == null) return null;
            DexCode code = transientCode(ctor);
            if (code == null || !code.tries().isEmpty()) return null;
            Instruction[] decoded;
            try {
                decoded = InstructionDecoder.decode(code.insns());
            } catch (RuntimeException e) {
                return null;
            }
            Instruction call = null, ret = null;
            for (Instruction in : decoded) {
                if (in.isPayload()) continue;
                if (call == null) call = in;
                else if (ret == null) ret = in;
                else return null;                          // a third instruction
            }
            if (call == null || ret == null) return null;
            if (ret.opcode() != 0x0e) return null;         // return-void
            if (call.opcode() != 0x70 && call.opcode() != 0x76) return null;
            DexFile.MethodRef ref;
            try {
                ref = c.dex().methodRef(call.index());
            } catch (RuntimeException e) {
                return null;
            }
            if (ref == null || !"<init>".equals(ref.name())) return null;
            if (!desc.equals(ref.proto().descriptor())) return null;
            int[] regs = call.argRegisters();
            if (regs.length != code.insSize()) return null;
            int first = code.registersSize() - code.insSize();
            for (int i = 0; i < regs.length; i++) {
                if (regs[i] != first + i) return null;      // reordered or substituted
            }
            return DexFile.internalName(ref.declaringClass());
        }

        DexMethod findConstructor(DexClass c, String desc) {
            for (DexMethod m : c.methods()) {
                if ("<init>".equals(m.name()) && desc.equals(m.descriptor())) return m;
            }
            return null;
        }
    }

    // ==================================================================
    // The call-site scan
    // ==================================================================

    /** Receives one `invoke-direct <init>` whose named owner is not the class
     *  the JVM would allow at that point. */
    interface SiteVisitor {
        /**
         * @param offset      DEX offset of the invoke-direct
         * @param uninitThis  the receiver is the enclosing constructor's own `this`
         * @param allocated   internal name the new-instance created, null when uninitThis
         * @param ref         the constructor the DEX names
         */
        void site(int offset, boolean uninitThis, String allocated, DexFile.MethodRef ref);
    }

    /**
     * The marker a tracked register carries while it holds the enclosing
     * constructor's own uninitialized `this`.
     *
     * It shares the map with the allocated-class names so that ONE kill rule
     * covers both, which is what keeps the two states mutually exclusive as
     * registers are overwritten. A parenthesis can never appear in a value
     * produced by DexFile.internalName (it strips `L...;` off a type
     * descriptor), so the marker cannot be confused with a real class.
     */
    private static final String UNINIT_THIS = "(uninitializedThis)";

    /**
     * Find the constructor calls in one method that the JVM would reject.
     *
     * Tracking is a single linear pass that follows `new-instance` into a
     * register and through `move-object`, and forgets a register as soon as
     * anything else writes it. That is enough for R8's output, where the
     * allocation is always a few instructions above its constructor call, and
     * where it is not the caller re-checks against type inference.
     *
     * The enclosing constructor's own `this` is tracked the SAME way, from the
     * incoming parameter register onwards, and NOT by testing whether the call
     * happens to name that register. R8's register allocator routinely copies
     * `p0` down into a low register first, because `invoke-direct/range` can
     * only address a contiguous run starting at the register it names:
     *
     *     move-object v0, p0        # this
     *     move-object v2, p1 ...
     *     invoke-direct/range {v0..v5}, PropertyReference.<init>(...)
     *
     * Matching on the parameter register alone therefore missed the receiver
     * in exactly the methods R8 rewrote most aggressively (all three residual
     * `Bad <init> method call` sites in sgtpuzzles were this shape), and it was
     * also unsound the other way: a constructor that OVERWRITES its own `this`
     * register would have had the replacement value treated as uninitialized.
     */
    static void scanMethod(Instruction[] decoded, DexMethod method, DexCode code,
                           SiteVisitor visitor) {
        if (code == null) return;
        DexFile dex = method.declaringClass().dex();
        String ownerName = method.declaringClass().name();
        String superName = method.declaringClass().superclassName();
        boolean ctor = method.isConstructor() && !method.isStatic();
        // Dalvik puts the parameters in the LAST registers, so `this` is the
        // first of them (dex-format, "code_item": ins_size arguments).
        int thisRegister = ctor ? code.registersSize() - code.insSize() : -1;

        Map<Integer, String> allocated = new HashMap<>();
        if (thisRegister >= 0) allocated.put(thisRegister, UNINIT_THIS);
        for (Instruction in : decoded) {
            if (in.isPayload()) continue;
            int op = in.opcode();
            if (op == 0x70 || op == 0x76) {                 // invoke-direct[/range]
                DexFile.MethodRef ref;
                try {
                    ref = dex.methodRef(in.index());
                } catch (RuntimeException e) {
                    continue;
                }
                int[] args = in.argRegisters();
                if (ref == null || args.length == 0 || !"<init>".equals(ref.name())) continue;
                String named = DexFile.internalName(ref.declaringClass());
                String alloc = allocated.get(args[0]);
                if (UNINIT_THIS.equals(alloc)) {
                    // uninitializedThis: legal only for the current class (a
                    // this(...) delegation) or its DIRECT superclass.
                    if (!named.equals(ownerName) && !named.equals(superName)) {
                        visitor.site(in.address(), true, null, ref);
                    }
                } else if (alloc != null && !alloc.equals(named)) {
                    visitor.site(in.address(), false, alloc, ref);
                }
                continue;
            }
            if (op == 0x22) {                               // new-instance
                String d = safeType(dex, in.index());
                if (d == null) allocated.remove(in.a());
                else allocated.put(in.a(), DexFile.internalName(d));
                continue;
            }
            if (op == 0x07 || op == 0x08 || op == 0x09) {   // move-object[/from16,/16]
                String src = allocated.get(in.b());
                if (src == null) allocated.remove(in.a()); else allocated.put(in.a(), src);
                continue;
            }
            int dest = destinationRegister(op, in);
            if (dest >= 0) {
                allocated.remove(dest);
                // Only a wide write owns vA+1. Clearing it unconditionally made
                // `const/4 v3` forget the allocation in v4, which silently
                // dropped most of the sites this whole class exists to find.
                if (in.isWideA()) allocated.remove(dest + 1);
            }
        }
    }

    private static String safeType(DexFile dex, int index) {
        try {
            return dex.typeDescriptor(index);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The register an instruction writes, or -1.
     *
     * Every Dalvik instruction that defines a register puts the destination in
     * vA, so the family alone decides whether there is one. This mirrors
     * TypeInference.destinationRegister; being wrong in the "forgets too much"
     * direction only costs a missed re-point.
     */
    private static int destinationRegister(int op, Instruction in) {
        switch (TypeInference.familyOf(op)) {
            case TypeInference.F_MOVE:
            case TypeInference.F_MOVE_WIDE:
            case TypeInference.F_MOVE_RESULT:
            case TypeInference.F_CONST32:
            case TypeInference.F_CONST64:
            case TypeInference.F_CONST_STRING:
            case TypeInference.F_CONST_CLASS:
            case TypeInference.F_CHECK_CAST:
            case TypeInference.F_INSTANCE_OF:
            case TypeInference.F_ARRAY_LEN:
            case TypeInference.F_NEW_INSTANCE:
            case TypeInference.F_NEW_ARRAY:
            case TypeInference.F_CMP:
            case TypeInference.F_ARRAY_GET:
            case TypeInference.F_INSTANCE_GET:
            case TypeInference.F_STATIC_GET:
            case TypeInference.F_UNARY_OP:
            case TypeInference.F_BINARY_OP:
            case TypeInference.F_BINARY_OP_CONST:
            case TypeInference.F_CONST_METHOD_HANDLE:
            case TypeInference.F_CONST_METHOD_TYPE:
                return in.a();
            default:
                return -1;
        }
    }

    // ==================================================================
    // Emitting a forwarding constructor
    // ==================================================================

    /**
     * Write {@code T.<init>(desc) { super(desc); }} into {@code cw}.
     *
     * The body is straight line, so it needs no StackMapTable: the implicit
     * frame at offset 0 that MethodWriter.newCode installs (uninitializedThis in
     * local 0) is the only one the verifier ever needs.
     *
     * ACC_PUBLIC because the call sites we re-point live in arbitrary other
     * classes and the constructor they used to name was reachable from there;
     * ACC_SYNTHETIC because this member is not in the source and reflection
     * should be able to tell.
     */
    static void emitForwardingConstructor(ClassFileWriter cw, String superName, Forward f) {
        ClassFileWriter.MethodWriter mw = cw.addMethod(
                ClassFileWriter.ACC_PUBLIC | ClassFileWriter.ACC_SYNTHETIC,
                "<init>", f.ownDescriptor);
        CodeWriter out = mw.newCode();
        out.varOp(CodeWriter.ALOAD, 0);
        List<String> own = parameterDescriptors(f.ownDescriptor);
        List<String> sup = parameterDescriptors(f.superDescriptor);
        int slot = 1;
        // Padding only ever sits at the END of either descriptor, so position i
        // means the same thing on both sides. Where this constructor was given
        // the parameter it is passed straight on; where only the superclass
        // declares one it is a PAD_TYPE the superclass never reads, so a null
        // is as good a value as any.
        for (int i = 0; i < sup.size(); i++) {
            if (i >= own.size()) {
                out.op(CodeWriter.ACONST_NULL);
                continue;
            }
            String p = own.get(i);
            out.varOp(loadOpcode(p.charAt(0)), slot);
            slot += (p.charAt(0) == 'J' || p.charAt(0) == 'D') ? 2 : 1;
        }
        out.methodOp(CodeWriter.INVOKESPECIAL, superName, "<init>", f.superDescriptor, false);
        out.op(CodeWriter.RETURN);
    }

    /** Split a method descriptor's parameter list (JVMS 4.3.3). */
    static List<String> parameterDescriptors(String desc) {
        List<String> out = new ArrayList<>();
        int i = desc.indexOf('(') + 1;
        int end = desc.indexOf(')');
        while (i < end) {
            int start = i;
            while (i < end && desc.charAt(i) == '[') i++;
            if (i < end && desc.charAt(i) == 'L') {
                int semi = desc.indexOf(';', i);
                if (semi < 0 || semi >= end) break;
                i = semi + 1;
            } else {
                i++;
            }
            out.add(desc.substring(start, i));
        }
        return out;
    }

    private static int loadOpcode(char c) {
        switch (c) {
            case 'J': return CodeWriter.LLOAD;
            case 'F': return CodeWriter.FLOAD;
            case 'D': return CodeWriter.DLOAD;
            case 'L': case '[': return CodeWriter.ALOAD;
            default:  return CodeWriter.ILOAD;   // Z B C S I all live in an int slot
        }
    }
}
