// SuperInterfacePlan -- re-points `invoke-super <interface>` at a DIRECT
// superinterface, because the JVM only lets invokespecial name one of those and
// DEX lets it name any supertype.
//
// THE BUG THIS FIXES
// R8 emits, inside androidx.compose.runtime.internal.PersistentCompositionLocalHashMap:
//
//     invoke-super {v1, v2, v3}, Ljava/util/Map;->getOrDefault(...)
//
// The class implements PersistentCompositionLocalMap, which extends java/util/Map,
// so java/util/Map is an INDIRECT superinterface. JVMS SE21 4.9.2:
//
//     "Each invokespecial instruction must name one of the following: an
//      instance initialization method; a method in the current class or
//      interface; a method in a superclass of the current class; A METHOD IN A
//      DIRECT SUPERINTERFACE of the current class or interface; a method in
//      Object."
//
// HotSpot enforces it in ClassVerifier::verify_invoke_instructions (jdk-21+35,
// src/hotspot/share/classfile/verifier.cpp:2887), where is_same_or_direct_interface
// consults only klass->local_interfaces() -- the DIRECT ones:
//
//     } else if (opcode == Bytecodes::_invokespecial
//                && !is_same_or_direct_interface(current_class(), current_type(), ref_class_type)
//                && !ref_class_type.equals(...current_class()->super()->name())) {
//       bool have_imr_indirect = cp->tag_at(index).value() == JVM_CONSTANT_InterfaceMethodref;
//       ...
//       } else if (have_imr_indirect) {
//         verify_error(..., "Bad invokespecial instruction: "
//                           "interface method reference is in an indirect superinterface.");
//
// and again at link time in LinkResolver::linktime_resolve_special_method
// (IncompatibleClassChangeError, "is in an indirect superinterface of ..."), so
// it is not merely a verifier-time check. javac cannot produce this shape at
// all: JLS 15.12.1 makes `I.super.m()` a compile-time error unless I is a direct
// superinterface.
//
// ART has no such rule. FindSuperMethodToCall
// (art/runtime/entrypoints/entrypoint_utils-inl.h) resolves the method against
// the interface the DEX NAMES and only checks
// `referenced_class->IsAssignableFrom(referrer->GetDeclaringClass())`, i.e. any
// supertype, direct or indirect:
//
//     if (referenced_class->IsInterface()) {
//       ...
//       ArtMethod* found_method = referenced_class->FindVirtualMethodForInterfaceSuper(
//           resolved_method, linker->GetImagePointerSize());
//
// and Class::FindVirtualMethodForInterfaceSuper (art/runtime/mirror/class.cc)
// searches the NAMED interface's own declarations first, then the most-specific
// default among that interface's superinterface closure. The receiver's class is
// never consulted.
//
// THE FIX
// Name a DIRECT superinterface J of the current class from which the JVM's own
// resolution lands on the same method. JVMS 5.4.3.4 resolves an interface method
// reference against J by: J's own declaration, then a public Object method, then
// "exactly one maximally-specific superinterface method (5.4.3.3) that is not
// abstract". 5.4.3.3 eliminates a method declared in I when another
// maximally-specific one is declared in a SUBinterface of I. So resolution from J
// picks the same method ART picks from I exactly when nothing between J and I
// redeclares it -- which is what {@link #reaches} checks, one interface at a
// time. invokespecial then SELECTS the resolved method verbatim: the
// "let C be the direct superclass" branch of JVMS 6.5 requires the symbolic
// reference to name a class, not an interface.
//
// WHY THE CHECK IS SO CONSERVATIVE
// Two shapes would silently run a different method body, so both are declined:
//   - an interface between J and I that declares the same name+descriptor,
//     which 5.4.3.3 makes maximally-specific over I's (ART would still run I's);
//   - a name+descriptor that only java/lang/Object declares, where the JVM's
//     step 3 invokes Object's method and ART throws IncompatibleClassChangeError.
// And anything reachable from J that is NOT one of the app's own classes is
// declined outright, because we cannot read its declarations to rule the first
// shape out (java.util.concurrent.ConcurrentMap, for one, is a subinterface of
// java/util/Map that redeclares getOrDefault as a default).
//
// THE EXTENDED PROOF (DEX2JVM_SUPER_IFACE_EXT, default ON, 2026-09-26)
// The walk above declines whenever the path from J to I leaves the app's own
// classes, and it never considers the DIRECT SUPERCLASS at all. That left 13 of
// the corpus's 62 G8 failures (anki wi/d.removeIf -> Collection, reddit
// eb0-subclasses -> Application$ActivityLifecycleCallbacks, fossify e1/j and
// reddit fdy -> Map.getOrDefault, the j$ Chronologies -> Chronology via
// AbstractChronology, GMS ads zzvc -> zzmt via zziq) -- and every one of them is
// also a RUN-TIME IncompatibleClassChangeError the first time it executes,
// because LinkResolver::linktime_resolve_special_method applies the same rule
// at link time, independent of the verifier. So these were live crashes, not
// just verifier noise.
//
// The extended proof asks the question in its exact form instead of walking a
// path. Let DECL(X) be the set of interfaces in {X} and X's superinterface
// closure that declare name+descriptor. ART runs the maximally-specific member
// of DECL(I) (FindVirtualMethodForInterfaceSuper: I's own declaration first,
// then its iftable, most specific first). A candidate site X makes the JVM run
// the maximally-specific member of DECL(X) (JVMS 5.4.3.3 / 6.5). Because I is in
// X's closure, DECL(I) is a subset of DECL(X), so the two agree exactly when
// every interface in closure(X) OUTSIDE {I} u closure(I) is PROVABLY not a
// declarer. Interfaces inside I's own closure are shared by both sides and never
// need reading, which is what lets a framework interface such as
// ActivityLifecycleCallbacks be the target without our knowing its methods.
//
// Two kinds of candidate:
//   - a DIRECT superinterface J (InterfaceMethodref, as before);
//   - the DIRECT SUPERCLASS S (Methodref). JVMS 4.9.2 allows invokespecial to
//     name "a method in a superclass of the current class", and 6.5 then starts
//     selection at S: S's own declaration, S's superclasses, and only then the
//     maximally-specific superinterface method of S. So S additionally needs
//     every class on its chain up to and including java/lang/Object to be
//     provably NOT declaring name+descriptor -- which also rejects the Object
//     methods (equals, hashCode...) for which ART throws instead.
// For an interface J the java/lang/Object methods are still declined up front
// (JVMS 5.4.3.4 step 3; see isObjectMethod).
//
// WHAT "PROVABLY" MEANS, and why the JDK but not other classpath jars. An app
// class answers from its parsed DEX. A class from the converter's classpath
// answers its SUPERCLASS and INTERFACES from a header parse, and its METHODS
// only when the resource is served by the JDK runtime image (a jrt: URL). That
// limit is about REPRODUCIBILITY, not about correctness: a caller that caches
// converted output keyed on the classpath's class SHAPE (name, flags, super,
// interfaces) plus the JDK version would not be invalidated when a classpath
// jar's method list changed, so a conversion that depended on that list could
// go stale. The JDK's methods are pinned by the JDK version; a --patch-module
// class is served from a file: URL and so is excluded too. Anything unreadable
// is "unknown", and unknown declines -- leaving the site exactly as it was.
//
// WHY THIS IS NOT PART OF InitRepointPlan
// Same idea, but no code scan: the answer depends only on class metadata, so it
// is computed on demand per call site and memoised. The memo is a pure function
// of its key, which is what keeps 1-thread and 8-thread output byte-identical.

package io.github.kksimp.dex2jvm;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class SuperInterfacePlan {

    /** Substitutes nothing: the DEX2JVM_SUPER_IFACE=0 escape hatch and the
     *  default for callers that have no session. */
    static final SuperInterfacePlan NONE = new SuperInterfacePlan(Map.of(), null, false);

    /** No answer, so a miss costs one walk. ConcurrentHashMap forbids nulls. */
    private static final String NONE_FOUND = "";

    private final Map<String, DexClass> byName;
    private final ConcurrentHashMap<String, String> memo = new ConcurrentHashMap<>();
    /** Where classpath types are read from; null disables the extended proof. */
    private final ClassLoader loader;
    /** DEX2JVM_SUPER_IFACE_EXT: see "THE EXTENDED PROOF" above. */
    private final boolean extended;
    /** DEX2JVM_SUPER_IFACE_ADD: see mayAddDirectInterface. */
    private final boolean addDirect;
    /** DEX2JVM_PROTECTED_REPOINT: see publicRepointFor. */
    private final boolean protectedRepoint;
    /** Classpath type facts, memoised. A pure function of the name, so the
     *  memo cannot make 1-thread and 8-thread output differ. */
    private final ConcurrentHashMap<String, TypeFacts> classpathFacts = new ConcurrentHashMap<>();

    private SuperInterfacePlan(Map<String, DexClass> byName, ClassLoader loader,
                               boolean extended) {
        this.byName = byName;
        this.loader = loader;
        this.extended = extended && loader != null;
        this.addDirect = loader != null
                && !"0".equals(System.getenv("DEX2JVM_SUPER_IFACE_ADD"));
        this.protectedRepoint = loader != null
                && !"0".equals(System.getenv("DEX2JVM_PROTECTED_REPOINT"));
    }

    static SuperInterfacePlan of(Map<String, DexClass> byName) {
        return of(byName, null);
    }

    /**
     * @param loader the converter's own class loader, i.e. the same type
     *   universe ClassHierarchyOracle reads (whatever is on the converter's
     *   classpath, typically an android.jar plus the JDK). Null keeps the
     *   app-only walk.
     */
    static SuperInterfacePlan of(Map<String, DexClass> byName, ClassLoader loader) {
        if (byName == null || byName.isEmpty()) return NONE;
        boolean ext = !"0".equals(System.getenv("DEX2JVM_SUPER_IFACE_EXT"));
        SuperInterfacePlan p = new SuperInterfacePlan(byName, loader, ext);
        // DEX2JVM_SUPER_IFACE=0 still turns every interface-super rewrite off
        // (legacy walk, extended proof and the added-interface fallback), but no
        // longer the unrelated protected-receiver re-point that shares the facts.
        if ("0".equals(System.getenv("DEX2JVM_SUPER_IFACE"))) p.ifaceOn = false;
        return p;
    }

    /** False under DEX2JVM_SUPER_IFACE=0. Written once, before publication. */
    private boolean ifaceOn = true;

    /**
     * The last resort, for a site no substitution can serve: may the CURRENT
     * class list {@code named} as an additional direct superinterface?
     *
     * WHEN IT IS NEEDED. anki's wi/d extends wi/a and calls
     * `invoke-super Collection.removeIf`. wi/a itself DECLARES removeIf, so
     * naming the superclass would run wi/a's body where ART runs Collection's
     * default, and wi/d has no direct superinterface to name. No legal
     * invokespecial reaches Collection.removeIf from wi/d at all -- and
     * MethodHandles.Lookup.findSpecial goes through the same
     * LinkResolver::linktime_resolve_special_method check, so it cannot either.
     * The class file therefore has to say `implements java/util/Collection`
     * itself, after which `invokespecial Collection.removeIf` selects exactly
     * what ART's FindVirtualMethodForInterfaceSuper does: Collection's own
     * declaration first, then the maximally-specific one above it (JVMS 6.5,
     * C = the named interface).
     *
     * WHY IT IS SAFE. Only an interface the class ALREADY inherits qualifies,
     * so the subtype relation, and with it instanceof, checkcast, itable
     * layout and JVMS 5.4.6 selection (maximally-specific over the same
     * superinterface SET), is unchanged. What does change, and is the price:
     * Class.getInterfaces() gains the entry (and the class Signature gains a
     * raw one, so getGenericInterfaces() stays index-aligned for callers such
     * as Gson's $Gson$Types.getGenericSupertype that walk both arrays in step),
     * and so does the input to a Serializable class's DEFAULT serialVersionUID.
     * Both are preferred over the alternative, which is an
     * IncompatibleClassChangeError every time the method runs -- measured as a
     * link-time failure, not just a verifier one.
     *
     * Declined for the public java/lang/Object methods: with the interface
     * direct, JVMS 5.4.3.4 step 3 would call Object's, where ART throws.
     * DEX2JVM_SUPER_IFACE_ADD=0 disables it.
     */
    boolean mayAddDirectInterface(String currentClass, String named, String name,
                                  String descriptor) {
        if (!ifaceOn || !addDirect || loader == null || named == null || currentClass == null) {
            return false;
        }
        if (currentClass.equals(named) || isObjectMethod(name, descriptor)) return false;
        DexClass c = byName.get(currentClass);
        if (c == null || c.interfaceNames().contains(named)) return false;
        TypeFacts nf = facts(named);
        if (!nf.known || !nf.isInterface) return false;
        // HotSpot checks that a class may ACCESS each of its direct
        // superinterfaces when it loads the class, independent of
        // the bytecode verifier (classFileParser.cpp check_super_interface_access,
        // Reflection::verify_class_access: public, or the same RUNTIME package,
        // which is the package name AND the defining loader). Inheriting a
        // package-private interface through a supertype is legal; naming it
        // directly from another runtime package is an IllegalAccessError that
        // takes the WHOLE class down, where declining costs one call site. So
        // a non-public interface qualifies only when it is one of this app's
        // own classes (same loader) in this class's own package.
        if ((nf.access & 0x0001) == 0
                && (byName.get(named) == null || !packageOf(named).equals(packageOf(currentClass)))) {
            return false;
        }
        // `named` must already be a supertype: through a direct superinterface
        // or through any class on the superclass chain.
        for (String i : c.interfaceNames()) {
            Set<String> cl = closureWithSelf(i);
            if (cl != null && cl.contains(named)) return true;
        }
        if (c.isInterface()) return false;
        for (String k = c.superclassName(); k != null; ) {
            TypeFacts f = facts(k);
            if (!f.known) return false;
            for (String i : f.interfaces) {
                Set<String> cl = closureWithSelf(i);
                if (cl != null && cl.contains(named)) return true;
            }
            if (DexType.OBJECT_NAME.equals(k)) break;
            k = f.superName;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Protected-receiver re-pointing (DEX2JVM_PROTECTED_REPOINT). It lives
    // here only because it needs the same app + JDK type facts.
    // ------------------------------------------------------------------

    /**
     * The class to name on an {@code invokevirtual} INSTEAD of {@code refOwner},
     * or null to leave the call alone.
     *
     * THE SHAPE. R8's member rebinding can leave a call's method_id naming the
     * class that DECLARES a protected method rather than the receiver's own
     * type. Fossify's MyRecyclerViewAdapter$1.onDestroyActionMode does
     * `invoke-virtual {v0}, Ljava/lang/Object;->clone()` on a LinkedHashSet.
     * ART only checks that the caller may access Object.clone at all (it can:
     * every class is a subclass of Object) and dispatches to HashSet.clone. The
     * JVM adds the protected-receiver rule of JVMS 4.10.1.8 -- because the
     * named class java/lang/Object is a superclass of the current class and
     * declares clone protected in another package, the receiver must be
     * assignable to the CURRENT class -- and rejects it:
     *
     *   Bad access to protected data in invokevirtual
     *   Type 'java/util/LinkedHashSet' is not assignable to
     *   'org/fossify/commons/adapters/MyRecyclerViewAdapter$1'
     *
     * THE FIX. Name the receiver's own static type R when resolution from R
     * lands on a PUBLIC method. JVMS 4.10.1.8's passesProtectedCheck is keyed
     * on the SYMBOLIC class ("if MemberClassName is not the name of a superclass
     * of the current class, then the protected check passes trivially"), and a
     * public resolved method carries no 5.4.4 protected condition at link time
     * either. Dispatch cannot change: JVMS 5.4.6 selects the same override for
     * a public resolved method as for the protected one it overrides, because
     * both are inheritable and "can override" reduces to name+descriptor.
     *
     * NOT FIXABLE THIS WAY, and left alone on purpose: a protected method that
     * is protected all the way down (FullStory's fsimpl/i calling
     * View.dispatchDraw on an arbitrary View; GMS wearable zzdk calling
     * DataBufferRef.getString on a sibling). Naming a sibling class there
     * passes the verifier but fails HotSpot's link-time protected check
     * (Reflection::verify_member_access requires the named class to be related
     * to the caller), so it would trade a verify error for a runtime
     * IllegalAccessError.
     *
     * Every link of both class chains must be readable WITH methods, i.e. an
     * app class or a JDK class; see the header's reproducibility note for why a
     * non-JDK classpath class is "unknown" here.
     */
    String publicRepointFor(String currentClass, String refOwner, String name,
                            String descriptor, String receiver) {
        if (!protectedRepoint || loader == null || receiver == null || refOwner == null
                || receiver.equals(refOwner) || receiver.charAt(0) == '['
                || refOwner.charAt(0) == '[' || byName.get(currentClass) == null) {
            return null;
        }
        // Cheap filters first: this runs for EVERY invoke-virtual, and nearly all
        // of them are either not through a superclass of the caller or on a
        // receiver of the caller's own type. Only superclass links are walked
        // here, never a method table.
        if (chainContains(currentClass, refOwner) != YES) return null;  // check does not fire
        if (chainContains(receiver, currentClass) != NO) return null;   // already assignable, or unknown
        String sig = name + descriptor;
        String[] declP = findInClassChain(refOwner, sig);
        if (declP == null) return null;
        int fp = Integer.parseInt(declP[1]);
        if ((fp & 0x0004) == 0 || (fp & 0x0008) != 0) return null;   // not protected instance
        if (packageOf(declP[0]).equals(packageOf(currentClass))) return null;
        TypeFacts rf = facts(receiver);
        if (!rf.known || rf.isInterface || (rf.access & 0x0001) == 0) return null;
        if (chainContains(currentClass, receiver) != NO) return null;   // R would be checked too
        String[] declR = findInClassChain(receiver, sig);
        if (declR == null) return null;
        int fr = Integer.parseInt(declR[1]);
        if ((fr & 0x0001) == 0 || (fr & 0x0008) != 0) return null;      // must be public instance
        return receiver;
    }

    // ------------------------------------------------------------------
    // Interface-field writers (DEX2JVM_IFACE_FIELD_FLAGS, DexConverter). It
    // lives here only because this is the object that holds the session's
    // class map on every conversion path.
    // ------------------------------------------------------------------

    /** Memo for staticFieldWrittenOutsideClinit; a pure function of its key. */
    private final ConcurrentHashMap<String, Boolean> foreignStaticWrites = new ConcurrentHashMap<>();

    /**
     * True unless it is PROVEN that nothing but {@code owner}'s own
     * &lt;clinit&gt; writes the static field {@code f}, i.e. that marking it
     * final cannot turn a working putstatic into an IllegalAccessError. See
     * DexConverter.IFACE_FIELD_FLAGS.
     *
     * Only asked about a package-private or private field, whose every legal
     * writer is in {@code owner}'s package (a writer elsewhere already fails
     * access checking on ART and on HotSpot). So the scan is that package: an
     * sput-family instruction counts when its field_id carries the same name
     * and type AND names a class from which field resolution (JVMS 5.4.3.2)
     * could reach {@code owner} -- {@code owner} itself or any subtype, since
     * resolution walks superinterfaces and superclasses. Only a field_id whose
     * class is provably unrelated is skipped; an unknown supertype counts. A
     * method we cannot decode, or no session at all, is "unknown", and unknown
     * declines.
     */
    boolean staticFieldWrittenOutsideClinit(DexClass owner, DexField f) {
        if (byName.isEmpty() || byName.get(owner.name()) != owner) return true;
        String key = owner.name() + ' ' + f.name() + ' ' + f.type();
        Boolean hit = foreignStaticWrites.get(key);
        if (hit == null) {
            hit = scanStaticWrites(owner, f.name(), f.type());
            foreignStaticWrites.put(key, hit);
        }
        return hit;
    }

    private boolean scanStaticWrites(DexClass owner, String name, String type) {
        String pkg = packageOf(owner.name());
        for (DexClass k : byName.values()) {
            if (!packageOf(k.name()).equals(pkg)) continue;
            for (DexMethod m : k.methods()) {
                int off = m.codeOffset();
                if (off == 0) continue;
                Instruction[] insns;
                try {
                    // Transient, like InitRepointPlan.transientCode: a pre-pass
                    // must not pin every code_item it reads.
                    insns = InstructionDecoder.decode(new DexCode(m, off).insns());
                } catch (RuntimeException e) {
                    return true;
                }
                for (Instruction in : insns) {
                    if (in == null) continue;
                    int op = in.opcode();
                    if (op < 0x67 || op > 0x6d) continue;       // sput .. sput-short
                    DexFile.FieldRef r = k.dex().fieldRef(in.index());
                    if (r == null) return true;
                    if (!r.name().equals(name) || !r.type().equals(type)) continue;
                    if (k == owner && "<clinit>".equals(m.name())) continue;
                    if (!mayResolveThrough(r.declaringClassName(), owner.name())) continue;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * False only when field resolution starting at {@code refClass} provably
     * cannot reach a field declared in {@code owner}: JVMS 5.4.3.2 searches
     * the named class, then its superinterfaces, then its superclass chain, so
     * it reaches {@code owner} only if {@code owner} is {@code refClass} or one
     * of its supertypes. Any supertype whose shape is unknown answers true.
     */
    private boolean mayResolveThrough(String refClass, String owner) {
        if (refClass == null || refClass.equals(owner)) return true;
        Deque<String> work = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        work.push(refClass);
        while (!work.isEmpty()) {
            String t = work.pop();
            if (!seen.add(t)) continue;
            if (t.equals(owner)) return true;
            TypeFacts f = facts(t);
            if (!f.known) return true;
            for (String i : f.interfaces) work.push(i);
            if (f.superName != null) work.push(f.superName);
        }
        return false;
    }

    /** Memo for findInClassChain; a pure function of its key. */
    private final ConcurrentHashMap<String, String[]> chainDecl = new ConcurrentHashMap<>();
    private static final String[] NOT_PROVEN = new String[0];

    /** {declaring class, access flags} of the first class on {@code start}'s
     *  superclass chain declaring {@code sig}, or null if not provable. */
    private String[] findInClassChain(String start, String sig) {
        String key = start + ' ' + sig;
        String[] hit = chainDecl.get(key);
        if (hit == null) {
            String[] found = findInClassChainUncached(start, sig);
            hit = found == null ? NOT_PROVEN : found;
            chainDecl.put(key, hit);
        }
        return hit.length == 0 ? null : hit;
    }

    private String[] findInClassChainUncached(String start, String sig) {
        for (String k = start; k != null; ) {
            TypeFacts f = facts(k);
            if (!f.known || f.isInterface || f.methods == null) return null;
            Integer fl = f.methods.get(sig);
            if (fl != null) return new String[]{ k, Integer.toString(fl) };
            if (DexType.OBJECT_NAME.equals(k)) return null;
            k = f.superName;
        }
        return null;
    }

    /** YES when {@code target} is {@code start} or on its superclass chain.
     *  Reads superclass links only (the DEX header or a class-file header). */
    private int chainContains(String start, String target) {
        int guard = 0;
        for (String k = start; k != null && guard++ < 256; ) {
            if (k.equals(target)) return YES;
            if (DexType.OBJECT_NAME.equals(k)) return NO;
            DexClass c = byName.get(k);
            if (c != null) {
                if (c.isInterface()) return NO;
                k = c.superclassName();
                continue;
            }
            TypeFacts f = facts(k);
            if (!f.known) return UNKNOWN;
            if (f.isInterface) return NO;
            k = f.superName;
        }
        return UNKNOWN;
    }

    private static String packageOf(String internalName) {
        int i = internalName.lastIndexOf('/');
        return i < 0 ? "" : internalName.substring(0, i);
    }

    /** True when {@code substituteFor} may name the direct SUPERCLASS, which the
     *  caller must then emit as a Methodref (not an InterfaceMethodref). */
    boolean isClassSubstitute(String currentClass, String substitute) {
        DexClass c = byName.get(currentClass);
        return c != null && substitute != null && substitute.equals(c.superclassName())
                && !c.isInterface();
    }

    /**
     * The direct superinterface to name on the invokespecial instead of
     * {@code named}, or null to leave the call site alone.
     *
     * @param currentClass the class the invokespecial is emitted in
     * @param named        the interface the DEX method_id declares
     */
    String substituteFor(String currentClass, String named, String name, String descriptor) {
        if (!ifaceOn || byName.isEmpty() || currentClass == null || named == null) return null;
        if (currentClass.equals(named)) return null;      // already legal: same class
        String key = currentClass + ' ' + named + ' ' + name + descriptor;
        String hit = memo.get(key);
        if (hit == null) {
            // NONE_FOUND has to replace the null BEFORE it is returned, not just
            // before it is stored: the no-substitution answer is the common one,
            // and reading it straight back out threw NPE and lost the whole class.
            String found = compute(currentClass, named, name, descriptor);
            hit = found == null ? NONE_FOUND : found;
            memo.put(key, hit);
        }
        return hit.isEmpty() ? null : hit;
    }

    private String compute(String currentClass, String named, String name, String descriptor) {
        DexClass c = byName.get(currentClass);
        if (c == null) return null;
        List<String> direct = c.interfaceNames();
        if (direct.contains(named)) return null;          // already legal: direct
        if (isObjectMethod(name, descriptor)) return null;
        String found = null;
        boolean ambiguous = false;
        for (String j : direct) {
            if (!reaches(j, named, name, descriptor)) continue;
            // Two candidates can resolve to different methods (each brings its
            // own superinterface closure into 5.4.3.3), and picking one would be
            // a guess. Leaving the site alone costs one verify error; guessing
            // wrong runs the wrong code.
            if (found != null) { ambiguous = true; break; }
            found = j;
        }
        if (found != null && !ambiguous) return found;
        // The app-only walk above is kept verbatim and consulted FIRST, so a site
        // it already rescued keeps its exact bytes; the extended proof only ever
        // answers a site that would otherwise have been left illegal.
        return extended ? proveExtended(c, named, name, descriptor) : null;
    }

    // ------------------------------------------------------------------
    // The extended proof. See "THE EXTENDED PROOF" in the header.
    // ------------------------------------------------------------------

    /** Immutable facts about one type. {@code methods} null == unknown. */
    private static final class TypeFacts {
        final String superName;          // null for java/lang/Object or unknown
        final List<String> interfaces;
        final boolean isInterface;
        final int access;                // class access_flags
        final Map<String, Integer> methods;   // name+descriptor -> access_flags, or null
        final boolean known;
        TypeFacts(String superName, List<String> interfaces, boolean isInterface, int access,
                  Map<String, Integer> methods, boolean known) {
            this.superName = superName;
            this.interfaces = interfaces;
            this.isInterface = isInterface;
            this.access = access;
            this.methods = methods;
            this.known = known;
        }
    }

    private static final TypeFacts UNKNOWN_TYPE =
            new TypeFacts(null, List.of(), false, 0, null, false);

    /** Tri-state for "does T declare name+descriptor". */
    private static final int NO = 0, YES = 1, UNKNOWN = 2;

    private String proveExtended(DexClass c, String named, String name, String descriptor) {
        Set<String> shared = closureWithSelf(named);
        if (shared == null) return null;            // I's own shape is unknown
        String sig = name + descriptor;
        // Direct superinterfaces, in declaration order: the first that proves
        // wins. Every proving candidate selects the same method (that is what
        // the proof says), so the choice among them is only about determinism.
        if (!isObjectMethod(name, descriptor)) {
            for (String j : c.interfaceNames()) {
                Set<String> cl = closureWithSelf(j);
                if (cl == null || !cl.contains(named)) continue;
                if (extrasDeclare(cl, shared, sig) == NO) return j;
            }
        }
        // The direct superclass. Not for an interface: its "superclass" is
        // java/lang/Object, and invokespecial there would select Object's.
        if (c.isInterface()) return null;
        String s = c.superclassName();
        if (s == null) return null;
        Set<String> ifaces = new HashSet<>();
        // Every class on S's chain must be readable and must not declare the
        // method: JVMS 6.5 consults the class chain before any interface.
        for (String k = s; k != null; ) {
            TypeFacts f = facts(k);
            if (!f.known || f.isInterface) return null;
            int d = declares(f, sig);
            if (d != NO) return null;
            for (String i : f.interfaces) {
                Set<String> cl = closureWithSelf(i);
                if (cl == null) return null;
                ifaces.addAll(cl);
            }
            if (DexType.OBJECT_NAME.equals(k)) break;
            k = f.superName;
            if (k == null) return null;              // chain never reached Object
        }
        if (!ifaces.contains(named)) return null;   // resolution would not find I
        return extrasDeclare(ifaces, shared, sig) == NO ? s : null;
    }

    /** NO when no interface of {@code candidates} outside {@code shared}
     *  declares {@code sig}; YES or UNKNOWN otherwise. */
    private int extrasDeclare(Set<String> candidates, Set<String> shared, String sig) {
        for (String t : candidates) {
            if (shared.contains(t)) continue;
            int d = declares(facts(t), sig);
            if (d != NO) return d;
        }
        return NO;
    }

    /** {@code start} plus every superinterface reachable from it, or null when
     *  any member's shape is unknown. Sorted, for deterministic iteration. */
    private Set<String> closureWithSelf(String start) {
        Set<String> seen = new java.util.TreeSet<>();
        Deque<String> work = new ArrayDeque<>();
        work.push(start);
        while (!work.isEmpty()) {
            String k = work.pop();
            if (!seen.add(k)) continue;
            TypeFacts f = facts(k);
            if (!f.known) return null;
            for (String s : f.interfaces) work.push(s);
        }
        return seen;
    }

    private static int declares(TypeFacts f, String sig) {
        if (!f.known || f.methods == null) return UNKNOWN;
        return f.methods.containsKey(sig) ? YES : NO;
    }

    private TypeFacts facts(String name) {
        DexClass c = byName.get(name);
        if (c != null) {
            // Not memoised here: DexClass already holds the parse, and the
            // method set is only built for the handful of failing sites.
            Map<String, Integer> ms = new java.util.HashMap<>();
            for (DexMethod m : c.methods()) ms.put(m.name() + m.descriptor(), m.accessFlags());
            String sup = c.isInterface() ? null : c.superclassName();
            return new TypeFacts(sup, c.interfaceNames(), c.isInterface(), c.accessFlags(), ms,
                                 true);
        }
        TypeFacts f = classpathFacts.get(name);
        if (f != null) return f;
        f = readClasspath(name);
        classpathFacts.put(name, f);
        return f;
    }

    private TypeFacts readClasspath(String name) {
        if (loader == null || name.isEmpty() || name.charAt(0) == '[') return UNKNOWN_TYPE;
        java.net.URL url = loader.getResource(name + ".class");
        if (url == null) return UNKNOWN_TYPE;
        boolean jdk = "jrt".equals(url.getProtocol());
        try (InputStream in = url.openStream()) {
            return parse(in.readAllBytes(), jdk);
        } catch (Exception e) {
            return UNKNOWN_TYPE;
        }
    }

    /**
     * Class file parse down to the methods table (JVMS 4.1). Only Class and
     * Utf8 constants are ever resolved; everything else is skipped by its fixed
     * size, and Long/Double take two slots (JVMS 4.4.5). The methods table is
     * read only when {@code withMethods}; see the header for why.
     */
    private static TypeFacts parse(byte[] b, boolean withMethods) {
        java.nio.ByteBuffer c = java.nio.ByteBuffer.wrap(b);
        if (c.getInt() != 0xCAFEBABE) return UNKNOWN_TYPE;
        c.getShort(); c.getShort();
        int count = c.getShort() & 0xFFFF;
        int[] classNameIndex = new int[count];
        String[] utf8 = new String[count];
        for (int i = 1; i < count; i++) {
            int tag = c.get() & 0xFF;
            switch (tag) {
                case 1: {
                    int len = c.getShort() & 0xFFFF;
                    // Modified UTF-8 is length-prefixed in a class file and never
                    // contains a raw 0 byte (JVMS 4.4.7), so a NUL appended here is
                    // exactly the terminator Mutf8.decode expects of DEX strings.
                    byte[] raw = new byte[len + 1];
                    c.get(raw, 0, len);
                    utf8[i] = Mutf8.decode(raw, 0, len);
                    break;
                }
                case 7: classNameIndex[i] = c.getShort() & 0xFFFF; break;
                case 8: case 16: case 19: case 20: skip(c, 2); break;
                case 15: skip(c, 3); break;
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18:
                    skip(c, 4); break;
                case 5: case 6: skip(c, 8); i++; break;
                default: return UNKNOWN_TYPE;
            }
        }
        int access = c.getShort() & 0xFFFF;
        c.getShort();                                   // this_class
        int superIdx = c.getShort() & 0xFFFF;
        int n = c.getShort() & 0xFFFF;
        List<String> ifaces = new ArrayList<>(n);
        for (int i = 0; i < n; i++) ifaces.add(className(c.getShort() & 0xFFFF, classNameIndex, utf8));
        boolean isIface = (access & 0x0200) != 0;
        String sup = (superIdx == 0 || isIface) ? null : className(superIdx, classNameIndex, utf8);
        if (!withMethods) return new TypeFacts(sup, ifaces, isIface, access, null, true);
        int fields = c.getShort() & 0xFFFF;
        for (int i = 0; i < fields; i++) { skip(c, 6); skipAttributes(c); }
        int methods = c.getShort() & 0xFFFF;
        Map<String, Integer> ms = new java.util.HashMap<>();
        for (int i = 0; i < methods; i++) {
            int macc = c.getShort() & 0xFFFF;
            String mn = utf8[c.getShort() & 0xFFFF];
            String md = utf8[c.getShort() & 0xFFFF];
            ms.put(mn + md, macc);
            skipAttributes(c);
        }
        return new TypeFacts(sup, ifaces, isIface, access, ms, true);
    }

    private static String className(int idx, int[] classNameIndex, String[] utf8) {
        int ni = classNameIndex[idx];
        String s = ni > 0 ? utf8[ni] : null;
        if (s == null) throw new IllegalStateException("bad class constant " + idx);
        return s;
    }

    private static void skip(java.nio.ByteBuffer c, int n) { c.position(c.position() + n); }

    private static void skipAttributes(java.nio.ByteBuffer c) {
        int a = c.getShort() & 0xFFFF;
        for (int i = 0; i < a; i++) { c.getShort(); skip(c, c.getInt()); }
    }

    /**
     * True when {@code target} is reachable from {@code start} through
     * superinterfaces the app declares, none of which redeclares
     * {@code name+descriptor}.
     *
     * The walk stops AT the target without descending into it: anything above
     * the target is a superinterface of it, so 5.4.3.3 ranks it below the
     * target's own declaration and both ART and the JVM see the same candidate
     * set there.
     */
    private boolean reaches(String start, String target, String name, String descriptor) {
        if (start.equals(target)) return false;           // the caller handles this
        Deque<String> work = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        work.push(start);
        boolean hit = false;
        while (!work.isEmpty()) {
            String k = work.pop();
            if (!seen.add(k)) continue;
            if (k.equals(target)) { hit = true; continue; }
            DexClass ic = byName.get(k);
            // Not ours to read. It could be a subinterface of the target with a
            // more specific default, which would change which body runs.
            if (ic == null || !ic.isInterface()) return false;
            if (declares(ic, name, descriptor)) return false;
            for (String s : ic.interfaceNames()) work.push(s);
        }
        return hit;
    }

    private static boolean declares(DexClass c, String name, String descriptor) {
        for (DexMethod m : c.methods()) {
            if (name.equals(m.name()) && descriptor.equals(m.descriptor())) return true;
        }
        return false;
    }

    /**
     * The public instance methods of java/lang/Object, which JVMS 5.4.3.4 step 3
     * resolves BEFORE looking at superinterfaces. ART instead throws
     * IncompatibleClassChangeError for an invoke-super that lands on Object
     * ("invoke-super from interface should not resolve to Object methods"), so a
     * substitution here would turn an error into a call.
     *
     * clone and finalize are absent on purpose: step 3 requires ACC_PUBLIC and
     * both are protected.
     */
    private static boolean isObjectMethod(String name, String descriptor) {
        switch (name + descriptor) {
            case "equals(Ljava/lang/Object;)Z":
            case "hashCode()I":
            case "toString()Ljava/lang/String;":
            case "getClass()Ljava/lang/Class;":
            case "notify()V":
            case "notifyAll()V":
            case "wait()V":
            case "wait(J)V":
            case "wait(JI)V":
                return true;
            default:
                return false;
        }
    }
}
