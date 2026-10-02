// NestPlan -- reconstructs the NestHost / NestMembers attributes (JVMS SE21
// 4.7.28 and 4.7.29) that DEX does not carry.
//
// WHY THIS EXISTS
// Since Java 11, a class and the classes nested inside it form a NEST, and
// nestmates may touch each other's private members directly (JVMS 5.4.4: "R is
// private and is declared by a class or interface C that belongs to the same
// nest as D"). javac expresses the nest with two attributes: each nested class
// gets NestHost naming the outermost class, and the outermost class gets
// NestMembers listing all of them. Before nestmates the same access went
// through package-private synthetic accessor methods instead.
//
// DEX has no nest attributes, so rebuilding a class file from DEX loses the
// nest entirely and every such access becomes IllegalAccessError at first
// execution. Measured on tests/semantic/cases/Edge.java under
// d8 --no-desugaring:
//
//   IllegalAccessError: class Edge$Op tried to access private method
//     'void Edge$Op$1.<init>(java.lang.String, int)'
//
// (an enum with constant-specific class bodies compiles to anonymous subclasses
// whose constructors are private, and the enum's own <clinit> instantiates
// them). Normal d8 desugaring generates synthetic accessors instead, so this
// only bites a --no-desugaring build; the fix is correctness completeness
// rather than a shipping-APK emergency. Widening the private flags instead
// would "work" and is NOT acceptable: it changes what
// Method.getModifiers/getDeclaredMethods report and what a security manager or
// a reflection-driven library sees.
//
// THE HOST MUST BE THE OUTERMOST CLASS, NOT THE IMMEDIATE ONE
// Verified against javac 21 (javap -v on a class with a nested enum carrying
// constant-specific bodies): Outer$Op$1 carries "NestHost: class Outer", not
// "NestHost: class Outer$Op", and Outer carries NestMembers listing Outer$I,
// Outer$Op, Outer$Op$1, Outer$Op$2, Outer$Inner, Outer$Member, Outer$1Local and
// Outer$1 -- i.e. the transitive closure, including anonymous and local
// classes. The immediate-enclosing-class reading is not merely different, it is
// impossible: JVMS 4.7.28 says "A class may not have both a NestHost attribute
// and a NestMembers attribute", so an intermediate class like Outer$Op cannot
// be both a member of Outer's nest and the host of its own.
//
// WHY THIS IS SAFE TO GET WRONG (it degrades, it does not break)
// JVMS 5.4.4 makes the whole thing self-validating. A class M with a NestHost
// naming H falls back to being its own nest host -- exactly today's behaviour
// -- whenever H is in a different run-time package, H has no NestMembers, or
// H's NestMembers does not name M. So an incomplete nest costs precision, never
// correctness. And nestmate access is purely ADDITIVE: it can only make an
// access legal that was not, never the reverse. The one thing that would be
// unsafe is claiming a host that a DIFFERENT class also claims and that lists
// this class, which cannot happen here because the host is derived from this
// class's own enclosing chain.
//
// MULTIDEX
// DexFile.enclosedClasses/classByName are per-dex by construction (dex indices
// are per-dex). A nest split across dex files therefore used to reconstruct
// only the part visible in one dex file, degrading to self-hosting per the
// rule above -- this file used to claim "in practice a splitter keeps a nest
// together, because InnerClasses already depends on the same relationship".
// That is disproven: measured on Signal 172401,
// org/signal/video/exo/ExoPlayerPool (classes5.dex) encloses
// ExoPlayerPool$DataSourceTransferListener (classes8.dex), a genuine cross-dex
// nest. So this file now takes a NestIndex (built session-wide from every
// classes*.dex the app ships, see NestIndex.java) instead of reading
// DexFile.enclosedClasses/classByName directly, exactly the fix
// DexConverter.applyInnerClasses needed for the same reason. The degrade-not-
// break argument above still holds for whatever gap remains (e.g. an outer
// class this SESSION never saw at all), so this is a strict improvement, not
// a new correctness requirement.

package io.github.kksimp.dex2jvm;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class NestPlan {

    private NestPlan() {}

    /**
     * An enclosing chain longer than this is a malformed or hostile dex rather
     * than real source. Source nesting is bounded by javac's own recursion
     * limits and by the 65535-byte name limit long before it gets here, so the
     * cap only exists so a cycle in the annotations cannot spin.
     */
    private static final int MAX_DEPTH = 64;

    static final String A_ENCLOSING_CLASS  = "Ldalvik/annotation/EnclosingClass;";
    static final String A_ENCLOSING_METHOD = "Ldalvik/annotation/EnclosingMethod;";

    /**
     * Applies whichever of the two attributes this class needs, or neither.
     *
     * A class is either a nest MEMBER (it has an enclosing class, so it gets
     * NestHost naming the outermost one) or a potential nest HOST (it has none,
     * so it gets NestMembers listing everything it transitively encloses), and
     * JVMS 4.7.28 forbids both. A top-level class that encloses nothing gets
     * neither, which is the overwhelmingly common case and costs no bytes.
     */
    public static void apply(ClassFileWriter cw, DexClass cls, NestIndex nestIndex) {
        String host = outermostEnclosing(cls, nestIndex);
        if (host != null) {
            cw.setNestHost(host);
            return;
        }
        List<String> members = transitivelyEnclosed(cls, nestIndex);
        if (!members.isEmpty()) cw.setNestMembers(members);
    }

    /**
     * The outermost class enclosing {@code cls}, or null when {@code cls} is
     * itself outermost.
     *
     * The walk stops at the first link it cannot follow -- a class enclosed by
     * something not defined ANYWHERE in this session (not merely absent from
     * cls's own dex file, now that {@code nestIndex} spans every dex file the
     * app ships) -- and returns that name. That is the best answer available,
     * and per JVMS 5.4.4 a wrong one simply degrades to self-hosting rather
     * than mis-granting access.
     */
    static String outermostEnclosing(DexClass cls, NestIndex nestIndex) {
        String outer = enclosingOf(cls);
        if (outer == null) return null;
        // Names already seen, so an EnclosingClass cycle terminates. Seeded
        // with the starting class because the shortest possible cycle is a
        // class that names itself.
        Set<String> seen = new LinkedHashSet<>();
        seen.add(cls.name());
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            if (!seen.add(outer)) return null;   // cycle: emit nothing
            DexClass parent = nestIndex.classByName(outer);
            if (parent == null) return outer;    // not in this session; stop here
            String next = enclosingOf(parent);
            if (next == null) return outer;      // parent is outermost
            outer = next;
        }
        return null;
    }

    /**
     * Every class {@code cls} encloses, at any depth, in nestIndex order.
     *
     * NestIndex.enclosedClasses answers one level, so this is a breadth-first
     * closure over it. It has to be transitive: javac lists Outer$Op$1 in
     * Outer's NestMembers even though Outer$Op$1 is enclosed by Outer$Op, and
     * omitting it would leave that class self-hosted (JVMS 5.4.4) with no
     * diagnostic.
     */
    static List<String> transitivelyEnclosed(DexClass cls, NestIndex nestIndex) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        seen.add(cls.name());
        List<DexClass> frontier = new ArrayList<>(nestIndex.enclosedClasses(cls.name()));
        for (int depth = 0; depth < MAX_DEPTH && !frontier.isEmpty(); depth++) {
            List<DexClass> next = new ArrayList<>();
            for (DexClass c : frontier) {
                if (!seen.add(c.name())) continue;
                out.add(c.name());
                next.addAll(nestIndex.enclosedClasses(c.name()));
            }
            frontier = next;
        }
        return out;
    }

    /**
     * The internal name of the class directly enclosing {@code c}, or null.
     *
     * DEX splits this across two system annotations and a class carries exactly
     * one of them: EnclosingClass for a member class, EnclosingMethod for a
     * local or anonymous one (dx AnnotationUtils). Both name the enclosing
     * CLASS, which is all this needs, so they are read the same way.
     *
     * This duplicates DexFile.enclosingOf, which is private and drives the
     * inverse index used by enclosedClasses. Kept as a separate copy rather
     * than widening that one, so the nest work stays inside its own file.
     */
    static String enclosingOf(DexClass c) {
        for (DexFile.Annotation a : c.annotations()) {
            if (a.visibility() != DexFile.VISIBILITY_SYSTEM || a.type() == null) continue;
            if (A_ENCLOSING_CLASS.equals(a.type())) {
                DexFile.Value v = a.element("value");
                String d = v == null ? null : v.asTypeDescriptor();
                if (d != null) return DexFile.internalName(d);
            } else if (A_ENCLOSING_METHOD.equals(a.type())) {
                DexFile.Value v = a.element("value");
                DexFile.MethodRef m = v == null ? null : v.asMethod();
                if (m != null) return m.declaringClassName();
            }
        }
        return null;
    }
}
