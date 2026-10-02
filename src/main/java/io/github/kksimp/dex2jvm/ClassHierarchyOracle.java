// ClassHierarchyOracle -- answers "what is this class's superclass" and "is it an
// interface" so DexType.mergeRef can compute a real least-upper-bound instead of
// collapsing every reference merge to java/lang/Object.
//
// WHY THIS EXISTS (measured, not speculative). Without an oracle, DexType.lubClass
// returns java/lang/Object for any merge of two different class names. That is
// SOUND -- opcode selection never reads the reference type -- but it is not
// PRECISE, and HotSpot's split verifier checks precision. Running the real
// verifier over our output (tools/verify's VerifyCheck, gate G8) across five
// apps produced 2,144 VerifyErrors, of which ~1,969 (92%) were of the shape
//
//     Bad return type ... Type 'java/lang/Object' (current frame, stack[0])
//     is not assignable to 'com/dotgears/i'
//
// i.e. a StackMapTable entry we widened to Object where the code then requires
// the specific type. A JVM running with bytecode verification enabled rejects
// such a class. It matters even for code loaded with verification disabled,
// because then HotSpot's GenerateOopMap failure path is a FATAL VM abort rather
// than a catchable VerifyError -- one subtly wrong class file kills the process
// instead of one class.
//
// WHERE THE ANSWERS COME FROM, in order:
//
//   1. The APK's own classes, straight out of the parsed DEX. Authoritative, and
//      the only source that can possibly know about the app's own hierarchy.
//   2. Whatever the converter's class loader can see (Options.hierarchyLoader,
//      or --classpath on the CLI; typically an android.jar plus the JDK), read
//      as a .class resource and parsed header-only -- ideally the types the app
//      will link against at run time. Nothing is class-LOADED here: reading
//      bytes avoids running any <clinit> and avoids perturbing class-loading
//      order.
//   3. Unknown. Answering null/false is always safe: lubClass requires a chain
//      that reaches java/lang/Object and falls back to Object otherwise, so an
//      incomplete classpath degrades to exactly the no-oracle behaviour (merge
//      to java/lang/Object) rather than
//      producing a wrong answer. This is what lets the converter keep working
//      against an incomplete android/androidx surface.
//
// THREAD SAFETY: conversion runs on a pool (DexConverter.Session.forEach), so
// every mutable structure here is a ConcurrentHashMap and entries are immutable.
// Duplicate concurrent lookups race to compute the same value, which is
// harmless -- the parse is pure.

package io.github.kksimp.dex2jvm;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ClassHierarchyOracle implements DexType.ClassHierarchy {

    /** Immutable answer for one class name. */
    private static final class Entry {
        final String superName;      // null == unknown, or java/lang/Object itself
        final boolean isInterface;
        Entry(String superName, boolean isInterface) {
            this.superName = superName;
            this.isInterface = isInterface;
        }
    }

    /** Memoised "we looked and found nothing", so a miss costs one lookup. */
    private static final Entry UNKNOWN = new Entry(null, false);
    private static final Entry OBJECT = new Entry(null, false);

    private final Map<String, DexClass> appClasses;
    private final ClassLoader loader;
    private final ConcurrentHashMap<String, Entry> memo = new ConcurrentHashMap<>();

    ClassHierarchyOracle(Map<String, DexClass> appClasses, ClassLoader loader) {
        this.appClasses = appClasses;
        this.loader = loader != null ? loader : ClassHierarchyOracle.class.getClassLoader();
    }

    @Override public String superclassOf(String internalName) {
        if (internalName == null) return null;
        // Every array type's direct superclass is Object (JVMS 4.10.1.2), and
        // lubClass short-circuits arrays anyway; answering keeps the chain walk
        // from stalling if it ever reaches one.
        if (internalName.charAt(0) == '[') return DexType.OBJECT_NAME;
        return lookup(internalName).superName;
    }

    @Override public boolean isInterface(String internalName) {
        if (internalName == null || internalName.charAt(0) == '[') return false;
        return lookup(internalName).isInterface;
    }

    private Entry lookup(String name) {
        Entry e = memo.get(name);
        if (e != null) return e;
        e = compute(name);
        memo.put(name, e);
        return e;
    }

    private Entry compute(String name) {
        if (DexType.OBJECT_NAME.equals(name)) return OBJECT;

        DexClass c = appClasses.get(name);
        if (c != null) {
            // A DEX interface records java/lang/Object as its superclass, which is
            // also what the class file must say, so no special case is needed.
            return new Entry(c.superclassName(), c.isInterface());
        }

        byte[] bytes = readResource(name);
        if (bytes == null) return UNKNOWN;
        try {
            return parseHeader(bytes);
        } catch (RuntimeException ex) {
            // A class file we cannot parse is one we must not guess about.
            return UNKNOWN;
        }
    }

    private byte[] readResource(String internalName) {
        try (InputStream in = loader.getResourceAsStream(internalName + ".class")) {
            return in == null ? null : in.readAllBytes();
        } catch (Exception ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Header-only class file parse
    // ------------------------------------------------------------------

    /**
     * Read access_flags / this_class / super_class without loading the class.
     *
     * Reaching super_class means walking the constant pool, because entries are
     * variable length. Only the two Class entries are ever resolved; everything
     * else is skipped by its fixed size. Long and Double take TWO pool slots
     * (JVMS 4.4.5), which is the classic off-by-one in this loop.
     */
    private static Entry parseHeader(byte[] b) {
        Cursor c = new Cursor(b);
        if (c.u4() != 0xCAFEBABEL) return UNKNOWN;
        c.u2(); // minor
        c.u2(); // major
        int count = c.u2();
        int[] classNameIndex = new int[count];   // cp index -> its name_index, 0 if not a Class
        int[] utf8Start = new int[count];        // cp index -> offset of its bytes, 0 if not Utf8
        int[] utf8Len = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = c.u1();
            switch (tag) {
                case 1: { // Utf8
                    int len = c.u2();
                    utf8Start[i] = c.pos;
                    utf8Len[i] = len;
                    c.skip(len);
                    break;
                }
                case 7:   // Class
                    classNameIndex[i] = c.u2();
                    break;
                case 8: case 16: case 19: case 20:            // String/MethodType/Module/Package
                    c.skip(2);
                    break;
                case 15:                                      // MethodHandle
                    c.skip(3);
                    break;
                case 3: case 4: case 9: case 10: case 11:     // Integer/Float/*ref
                case 12: case 17: case 18:                    // NameAndType/Dynamic/InvokeDynamic
                    c.skip(4);
                    break;
                case 5: case 6:                               // Long/Double
                    c.skip(8);
                    i++;                                      // occupies two slots
                    break;
                default:
                    // Unknown tag: the pool is desynchronised, so stop guessing.
                    return UNKNOWN;
            }
        }
        int accessFlags = c.u2();
        c.u2();                       // this_class
        int superIdx = c.u2();

        boolean isInterface = (accessFlags & 0x0200) != 0;   // ACC_INTERFACE
        String superName = null;
        if (superIdx != 0 && superIdx < count) {
            int nameIdx = classNameIndex[superIdx];
            if (nameIdx > 0 && nameIdx < count && utf8Len[nameIdx] > 0) {
                superName = new String(b, utf8Start[nameIdx], utf8Len[nameIdx],
                                       java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return new Entry(superName, isInterface);
    }

    private static final class Cursor {
        final byte[] b;
        int pos;
        Cursor(byte[] b) { this.b = b; }
        int u1() { return b[pos++] & 0xFF; }
        int u2() { return (u1() << 8) | u1(); }
        long u4() { return ((long) u2() << 16) | u2(); }
        void skip(int n) {
            pos += n;
            if (pos > b.length) throw new IllegalStateException("truncated class file");
        }
    }
}
