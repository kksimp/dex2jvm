// NestIndex -- session-wide (multidex-transparent) answer to "what classes
// does X directly enclose" and "resolve this internal name to a DexClass",
// built once per DexConverter.Session from the merged, first-definition-wins
// byName map.
//
// THE BUG THIS FIXES
// DexFile.enclosedClasses() / DexFile.classByName() only ever see ONE
// physical .dex file, because dex type/annotation indices are per-dex.
// R8's multidex splitter does NOT keep a nest together -- measured on Signal
// 172401:
// org/signal/video/exo/ExoPlayerPool is defined in classes5.dex, and its own
// nested org/signal/video/exo/ExoPlayerPool$DataSourceTransferListener is
// defined in classes8.dex. DexConverter.applyInnerClasses asked
// ExoPlayerPool's OWN dex file "what do you enclose" and got back only the 4
// siblings that happened to share classes5.dex (Companion, OwnershipInfo,
// PoolState, PoolStats) -- DataSourceTransferListener was invisible, so
// ExoPlayerPool's emitted InnerClasses attribute never named it, even though
// DataSourceTransferListener's OWN InnerClasses attribute correctly named
// ExoPlayerPool as its outer class (that half is read straight off the
// class's own EnclosingClass annotation -- always local, see
// DexConverter.applyInnerClasses's first branch).
//
// HotSpot's Reflection::check_for_inner_class (src/hotspot/share/runtime/
// reflection.cpp) requires BOTH halves to agree before Class.getDeclaringClass0
// (and therefore getSimpleName/getEnclosingClass/isMemberClass) will answer:
// the nested class's own InnerClasses entry names its outer class, and the
// OUTER's InnerClasses must then carry a matching entry (same inner_class_info
// and outer_class_info). JVMS 4.7.6 requires that too ("If a class or
// interface has members that are classes or interfaces, its constant_pool
// table (and hence its InnerClasses attribute) must refer to each such
// member"), though the JVM itself does not check it at load time -- only
// reflection does. A one-sided reciprocity throws
// IncompatibleClassChangeError the first time reflection touches the nested
// class, which is unconditional in idioms like Kotlin's
// `Log.tag(javaClass) = javaClass.simpleName`.
//
// THE FIX
// Build the outer->nested and name->class indices ONCE, over the SESSION's
// merged byName map (every dex file the app ships, first-definition-wins --
// exactly Main.run(List<byte[]>, Path)'s "every classes*.dex in one session"
// contract, and the same map ClassHierarchyOracle already reads superclass
// answers from for the identical cross-dex reason -- see its own header: "a
// session sees every class in the set... Converting each buffer in its own
// session lost exactly that"). A pair that never crosses a dex-file boundary
// gets byte-identical output to the old per-dex answer; only a
// boundary-crossing pair changes, from silently incomplete to correct.
//
// NestPlan (NestHost/NestMembers, JVMS 4.7.28/4.7.29) had the SAME per-dex gap
// in both directions -- downward (what does this class enclose, transitively)
// and upward (walk to the outermost enclosing class). Its own header used to
// argue the gap was safe because "in practice a splitter keeps a nest
// together, precisely because [the InnerClasses] relationship has to
// survive". Signal 172401 disproves that in practice, so NestPlan now takes
// this same index instead of reading DexFile.enclosedClasses/classByName
// directly. (NestPlan's degrade-not-break argument, JVMS 5.4.4, still holds
// for whatever gap remains -- e.g. a class whose outer lives in a dex file
// outside THIS session entirely -- so this is a strict improvement, not a
// new correctness requirement.)

package io.github.kksimp.dex2jvm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class NestIndex {

    /** No session backing this index: every answer is "found nothing". Not
     *  reached by the shipped converter (every public entry point opens a
     *  Session), kept only as the safe default for a bare-DexClass caller,
     *  matching the InitRepointPlan.NONE / SuperInterfacePlan.NONE shape. */
    static final NestIndex NONE = new NestIndex(Map.of(), Map.of());

    private final Map<String, DexClass> byName;
    private final Map<String, List<DexClass>> enclosedBy;

    private NestIndex(Map<String, DexClass> byName, Map<String, List<DexClass>> enclosedBy) {
        this.byName = byName;
        this.enclosedBy = enclosedBy;
    }

    /**
     * Builds the index once from a session's merged class map.
     *
     * {@code byName} must be the SAME map DexConverter.Session builds (first
     * definition wins across dex files, in dex-file-then-class_defs order) so
     * this index sees exactly the classes the session will actually convert
     * -- not, for instance, a shadowed duplicate definition from a later dex
     * file nobody will emit. Built eagerly (not lazily): every worker thread
     * only ever READS this index once conversion starts, which is what keeps
     * output byte-identical regardless of thread count, the same guarantee
     * ClassHierarchyOracle and SuperInterfacePlan already rely on.
     */
    static NestIndex of(Map<String, DexClass> byName) {
        if (byName == null || byName.isEmpty()) return NONE;
        Map<String, List<DexClass>> idx = new HashMap<>();
        for (DexClass c : byName.values()) {
            String outer = DexFile.enclosingOf(c);
            if (outer != null) {
                idx.computeIfAbsent(outer, k -> new ArrayList<>()).add(c);
            }
        }
        return new NestIndex(byName, idx);
    }

    /**
     * Classes that name {@code outerInternalName} as their enclosing class,
     * across every dex file in the session, in byName iteration order (each
     * dex file's own class_defs order, dex files in session/argument order).
     *
     * The session-wide replacement for DexFile.enclosedClasses -- see the
     * file header for why the per-dex version is not enough on its own.
     */
    List<DexClass> enclosedClasses(String outerInternalName) {
        List<DexClass> got = enclosedBy.get(outerInternalName);
        return got == null ? List.of() : got;
    }

    /**
     * Resolves an internal name to the DexClass the session will actually
     * convert, across every dex file, or null when this session does not
     * define it (a framework/platform type, or a name nothing in the app
     * declares).
     *
     * The session-wide replacement for DexFile.classByName, needed by
     * NestPlan.outermostEnclosing to walk an enclosing chain that may cross a
     * dex-file boundary partway up.
     */
    DexClass classByName(String internalName) {
        return byName.get(internalName);
    }
}
