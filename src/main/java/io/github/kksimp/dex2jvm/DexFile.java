package io.github.kksimp.dex2jvm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed Android DEX container: the header, the six id tables, the class
 * definitions, and everything hanging off them (class_data_item, code_item,
 * try/catch tables, debug_info_item, encoded_array, annotations).
 *
 * <p>This is the front half of the DEX to JVM-bytecode converter. This class
 * owns the CONTAINER only: it turns bytes into a typed, immutable model. It
 * knows nothing about Dalvik instruction semantics, type inference, or class
 * file emission.
 *
 * <h2>Format reference</h2>
 * https://source.android.com/docs/core/runtime/dex-format is the spec. Where
 * the published prose is ambiguous, the tie-breaker used here is AOSP's own
 * Java reader/writer under {@code dx/src/com/android/dex/} and
 * {@code dx/src/com/android/dx/dex/file/}, since that is the code that produced
 * the files we are reading. One concrete case: the rendered spec page lists
 * annotations_directory_item's counts in the order
 * "annotated_methods_size, annotated_fields_size"; the real on-disk order is
 * {@code class_annotations_off, fields_size, methods_size, parameters_size},
 * per {@code AnnotationsDirectoryItem.writeTo0()}. We follow the writer.
 *
 * <h2>Contract for downstream stages (keep this stable)</h2>
 * <pre>
 *   DexFile dex = DexFile.parse(bytes);        // one .dex; multidex = N of these
 *   for (DexClass c : dex.classes()) {
 *       c.name();                              // "com/example/Foo" (internal)
 *       c.descriptor();                        // "Lcom/example/Foo;"
 *       c.superclassName();                    // internal name, null for Object
 *       c.interfaceNames();                    // internal names
 *       c.sourceFile();                        // "Foo.java" or null
 *       c.accessFlags();
 *       for (DexField f : c.fields()) {
 *           f.name(); f.type(); f.accessFlags();
 *           f.staticValue();                   // Value or null -> ConstantValue
 *       }
 *       for (DexMethod m : c.methods()) {
 *           m.name(); m.descriptor();          // "(Ljava/lang/String;I)V"
 *           m.accessFlags();
 *           DexCode code = m.code();           // null for abstract/native
 *           code.registersSize(); code.insSize(); code.outsSize();
 *           code.insns();                      // raw short[] code units
 *           code.tries();                      // try/catch table
 *           code.lineForOffset(codeUnitOffset);// source line, -1 if unknown
 *           code.positions();                  // full line table
 *       }
 *   }
 *   // pool lookups, for resolving instruction operands:
 *   dex.string(idx); dex.typeDescriptor(idx); dex.typeName(idx);
 *   dex.proto(idx); dex.fieldRef(idx); dex.methodRef(idx);
 *   dex.callSite(idx); dex.methodHandle(idx);
 * </pre>
 *
 * <h2>Type strings</h2>
 * DEX type descriptors and JVM field descriptors are the same syntax
 * ("Lcom/foo/Bar;", "[I", "J", "V"), so descriptors pass straight through to
 * the class file writer. What differs is the CONSTANT_Class form, which wants
 * an "internal name": "com/foo/Bar" for an object type but the full descriptor
 * "[I" for an array. {@link #internalName(String)} applies that rule; the
 * {@code ...Name()} accessors on the model apply it for you.
 *
 * <h2>Line numbers</h2>
 * debug_info_item is parsed, not discarded. Enjarify throws it away, which is
 * why stack traces from enjarify output carry no file or line. See
 * {@link DexCode#positions()}, {@link DexCode#lineForOffset(int)},
 * {@link DexCode#locals()} and {@link DexCode#parameterNames()}.
 *
 * <h2>Immutability and threading</h2>
 * The model is observationally immutable: no setters, no I/O, every accessor
 * returns either a primitive, a String, an unmodifiable List, or another model
 * object. Two deliberate exceptions, both documented at their accessor:
 * {@link DexCode#insns()} hands back the live {@code short[]} (copying it per
 * call would dominate conversion time on a 10 MB dex), and parsing of class
 * members, code and debug info is lazy and memoized behind volatile fields, so
 * a DexFile is safe to share across threads but does allocate on first touch.
 */
public final class DexFile {

    /** The DEX "absent index" sentinel, 0xffffffff, which reads back as -1. */
    public static final int NO_INDEX = -1;

    // ---- encoded_value type tags ------------------------------------------
    // Spec: "encoded_value encoding". The tag byte packs value_type in the low
    // 5 bits and value_arg in the high 3.
    public static final int VALUE_BYTE = 0x00;
    public static final int VALUE_SHORT = 0x02;
    public static final int VALUE_CHAR = 0x03;
    public static final int VALUE_INT = 0x04;
    public static final int VALUE_LONG = 0x06;
    public static final int VALUE_FLOAT = 0x10;
    public static final int VALUE_DOUBLE = 0x11;
    public static final int VALUE_METHOD_TYPE = 0x15;
    public static final int VALUE_METHOD_HANDLE = 0x16;
    public static final int VALUE_STRING = 0x17;
    public static final int VALUE_TYPE = 0x18;
    public static final int VALUE_FIELD = 0x19;
    public static final int VALUE_METHOD = 0x1a;
    public static final int VALUE_ENUM = 0x1b;
    public static final int VALUE_ARRAY = 0x1c;
    public static final int VALUE_ANNOTATION = 0x1d;
    public static final int VALUE_NULL = 0x1e;
    public static final int VALUE_BOOLEAN = 0x1f;

    // ---- annotation visibility (spec: "visibility values") ----------------
    public static final int VISIBILITY_BUILD = 0x00;
    public static final int VISIBILITY_RUNTIME = 0x01;
    public static final int VISIBILITY_SYSTEM = 0x02;

    // ---- method_handle_item types (spec: "method handle type codes") ------
    public static final int METHOD_HANDLE_STATIC_PUT = 0x00;
    public static final int METHOD_HANDLE_STATIC_GET = 0x01;
    public static final int METHOD_HANDLE_INSTANCE_PUT = 0x02;
    public static final int METHOD_HANDLE_INSTANCE_GET = 0x03;
    public static final int METHOD_HANDLE_INVOKE_STATIC = 0x04;
    public static final int METHOD_HANDLE_INVOKE_INSTANCE = 0x05;
    public static final int METHOD_HANDLE_INVOKE_CONSTRUCTOR = 0x06;
    public static final int METHOD_HANDLE_INVOKE_DIRECT = 0x07;
    public static final int METHOD_HANDLE_INVOKE_INTERFACE = 0x08;

    // ---- map_item type codes we care about --------------------------------
    // The header has no size/off pair for call_site_ids or method_handles
    // (they postdate the fixed 0x70-byte header_item), so the ONLY way to find
    // them is the map_list. Spec: "TYPE_CALL_SITE_ID_ITEM 0x0007",
    // "TYPE_METHOD_HANDLE_ITEM 0x0008"; cross-checked against
    // dx/src/com/android/dex/TableOfContents.java.
    private static final int TYPE_CALL_SITE_ID_ITEM = 0x0007;
    private static final int TYPE_METHOD_HANDLE_ITEM = 0x0008;

    /**
     * Access flag bit values, shared by classes, fields and methods.
     * Spec: "access_flags definitions".
     */
    public static final class Access {
        public static final int ACC_PUBLIC = 0x00001;
        public static final int ACC_PRIVATE = 0x00002;
        public static final int ACC_PROTECTED = 0x00004;
        public static final int ACC_STATIC = 0x00008;
        public static final int ACC_FINAL = 0x00010;
        public static final int ACC_SYNCHRONIZED = 0x00020;
        public static final int ACC_SUPER = 0x00020;
        public static final int ACC_VOLATILE = 0x00040;
        public static final int ACC_BRIDGE = 0x00040;
        public static final int ACC_TRANSIENT = 0x00080;
        public static final int ACC_VARARGS = 0x00080;
        public static final int ACC_NATIVE = 0x00100;
        public static final int ACC_INTERFACE = 0x00200;
        public static final int ACC_ABSTRACT = 0x00400;
        public static final int ACC_STRICT = 0x00800;
        public static final int ACC_SYNTHETIC = 0x01000;
        public static final int ACC_ANNOTATION = 0x02000;
        public static final int ACC_ENUM = 0x04000;

        /**
         * DEX-only flags with no JVM class file equivalent. ACC_CONSTRUCTOR
         * (0x10000) marks &lt;init&gt;/&lt;clinit&gt;; ACC_DECLARED_SYNCHRONIZED
         * (0x20000) is how DEX records "the source said synchronized" for a
         * non-native method, because DEX reserves 0x20 on methods for native
         * synchronization. A class file writer must mask both off, and if
         * ACC_DECLARED_SYNCHRONIZED is set it should set ACC_SYNCHRONIZED
         * (0x20) instead.
         */
        public static final int ACC_CONSTRUCTOR = 0x10000;
        public static final int ACC_DECLARED_SYNCHRONIZED = 0x20000;
        public static final int DEX_ONLY_FLAGS = ACC_CONSTRUCTOR | ACC_DECLARED_SYNCHRONIZED;

        private Access() {}
    }

    /** Thrown when the input is not a DEX we can read. */
    public static final class FormatException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public FormatException(String message) {
            super(message);
        }
    }

    private final byte[] data;
    private final int version;

    private final int stringIdsSize;
    private final int stringIdsOff;
    private final int typeIdsSize;
    private final int typeIdsOff;
    private final int protoIdsSize;
    private final int protoIdsOff;
    private final int fieldIdsSize;
    private final int fieldIdsOff;
    private final int methodIdsSize;
    private final int methodIdsOff;
    private final int classDefsSize;
    private final int classDefsOff;
    private final int callSiteIdsSize;
    private final int callSiteIdsOff;
    private final int methodHandlesSize;
    private final int methodHandlesOff;

    // Memoization caches. Every cached type has all-final fields, so the
    // benign data race on a slot cannot publish a half-built object (JLS 17.5
    // final field semantics). Worst case two threads decode the same string
    // and one result is discarded.
    private final String[] stringCache;
    private final Proto[] protoCache;
    private final FieldRef[] fieldRefCache;
    private final MethodRef[] methodRefCache;
    private final MethodHandleRef[] methodHandleCache;
    private final CallSite[] callSiteCache;

    private final List<DexClass> classes;

    /** Parses a complete .dex image. The array is retained, not copied. */
    public static DexFile parse(byte[] data) {
        return new DexFile(data);
    }

    private DexFile(byte[] data) {
        if (data == null || data.length < 0x70) {
            throw new FormatException("not a dex file: too short");
        }
        this.data = data;
        this.version = readMagic(data);

        // header_item, fixed layout, all little-endian. Offsets are from the
        // spec table: header_size at 0x24, endian_tag at 0x28, then the
        // size/off pairs starting at 0x38.
        int headerSize = u4(0x24);
        if (headerSize != 0x70) {
            // Name the FORMAT, not the number. A bare "unexpected header_size
            // 0x78" is the DEX container format (version 041) and nothing else:
            // ART's DexFile::Header::GetExpectedHeaderSize is
            // "version < 41 ? sizeof(Header) : sizeof(HeaderV41)", and HeaderV41
            // adds container_size + header_offset to the classic 0x70 header.
            // In that format ONE file holds several logical dex files back to
            // back, all offsets are relative to the physical file rather than
            // to the header, and data_off/data_size go unused -- so a reader
            // that starts at byte 0 and stops at file_size sees only the first
            // one. Supporting it means handing the whole container plus a
            // per-dex header offset down to the parser, which is a change in
            // the CALLER (DexConverter.open takes one byte[] per dex today).
            // Not built blind: AOSP's dex-format page says version 041 support
            // "is experimental in the Android 16 release for testing the
            // container format. However, version 041 shouldn't be used for
            // production code", so no shipping apk has one to verify against.
            if (headerSize == 0x78 && version >= 41) {
                throw new FormatException(
                        "DEX container format (version 0" + version + ", header_size 0x78)"
                        + " is not supported yet; it packs several logical dex files into one"
                        + " file with offsets relative to the container");
            }
            throw new FormatException("unexpected header_size 0x"
                    + Integer.toHexString(headerSize) + " for dex version 0" + version
                    + " (a classic dex header is 0x70)");
        }
        int endianTag = u4(0x28);
        if (endianTag != 0x12345678) {
            // 0x78563412 is the spec's byte-swapped form. No production
            // toolchain emits it, and supporting it would mean byte-swapping
            // every read, so refuse loudly rather than silently misparse.
            throw new FormatException("unsupported endian_tag 0x" + Integer.toHexString(endianTag));
        }
        int mapOff = u4(0x34);
        this.stringIdsSize = u4(0x38);
        this.stringIdsOff = u4(0x3c);
        this.typeIdsSize = u4(0x40);
        this.typeIdsOff = u4(0x44);
        this.protoIdsSize = u4(0x48);
        this.protoIdsOff = u4(0x4c);
        this.fieldIdsSize = u4(0x50);
        this.fieldIdsOff = u4(0x54);
        this.methodIdsSize = u4(0x58);
        this.methodIdsOff = u4(0x5c);
        this.classDefsSize = u4(0x60);
        this.classDefsOff = u4(0x64);

        int csSize = 0;
        int csOff = 0;
        int mhSize = 0;
        int mhOff = 0;
        if (mapOff > 0 && mapOff + 4 <= data.length) {
            int n = u4(mapOff);
            for (int i = 0; i < n; i++) {
                int p = mapOff + 4 + i * 12;
                if (p + 12 > data.length) {
                    break;
                }
                int type = u2(p);
                int size = u4(p + 4);
                int off = u4(p + 8);
                if (type == TYPE_CALL_SITE_ID_ITEM) {
                    csSize = size;
                    csOff = off;
                } else if (type == TYPE_METHOD_HANDLE_ITEM) {
                    mhSize = size;
                    mhOff = off;
                }
            }
        }
        this.callSiteIdsSize = csSize;
        this.callSiteIdsOff = csOff;
        this.methodHandlesSize = mhSize;
        this.methodHandlesOff = mhOff;

        this.stringCache = new String[Math.max(stringIdsSize, 0)];
        this.protoCache = new Proto[Math.max(protoIdsSize, 0)];
        this.fieldRefCache = new FieldRef[Math.max(fieldIdsSize, 0)];
        this.methodRefCache = new MethodRef[Math.max(methodIdsSize, 0)];
        this.methodHandleCache = new MethodHandleRef[Math.max(methodHandlesSize, 0)];
        this.callSiteCache = new CallSite[Math.max(callSiteIdsSize, 0)];

        // class_def_item is a fixed 32 bytes, so building every shell up front
        // is cheap even at 30k classes. The expensive parts (class_data_item,
        // code, debug info) stay lazy inside DexClass.
        DexClass[] cs = new DexClass[classDefsSize];
        for (int i = 0; i < classDefsSize; i++) {
            cs[i] = new DexClass(this, classDefsOff + i * 32);
        }
        this.classes = List.of(cs);
    }

    private static int readMagic(byte[] d) {
        if (d[0] == 'c' && d[1] == 'd' && d[2] == 'e' && d[3] == 'x') {
            // CompactDex is ART's internal dex2oat output. It never appears
            // inside an APK, and its layout differs enough that a partial
            // parse would be worse than a clear failure.
            throw new FormatException("CompactDex (cdex) is not supported; expected a standard .dex");
        }
        boolean dex = d[0] == 'd' && d[1] == 'e' && d[2] == 'x' && d[3] == '\n';
        if (!dex || d[7] != 0) {
            throw new FormatException(String.format(
                    "bad dex magic: %02x %02x %02x %02x %02x %02x %02x %02x",
                    d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7]));
        }
        int v = 0;
        for (int i = 4; i < 7; i++) {
            int c = d[i] - '0';
            if (c < 0 || c > 9) {
                throw new FormatException("bad dex version digits");
            }
            v = v * 10 + c;
        }
        return v;
    }

    /** DEX format version from the magic, e.g. 35, 37, 38, 39 or 40. */
    public int version() {
        return version;
    }

    /** Every class defined by this dex, in class_defs order. */
    public List<DexClass> classes() {
        return classes;
    }

    private volatile Map<String, DexClass> classIndex;

    /**
     * Class by internal name ("com/example/Foo"), or null if this dex does not
     * define it. Indexed lazily because most callers never ask.
     *
     * Scoped to ONE dex on purpose: every string/type/method index is an
     * index into THIS file's own id tables, so the same class has unrelated
     * indices in different classes*.dex files, and a caller spanning a
     * multidex APK has to do its own first-wins merge, which DexConverter.Session
     * already does.
     */
    public DexClass classByName(String internalName) {
        Map<String, DexClass> idx = classIndex;
        if (idx == null) {
            synchronized (this) {
                idx = classIndex;
                if (idx == null) {
                    idx = new HashMap<>(classes.size() * 2);
                    for (DexClass c : classes) idx.putIfAbsent(c.name(), c);
                    classIndex = idx;
                }
            }
        }
        return idx.get(internalName);
    }

    private volatile Map<String, List<DexClass>> nestIndex;

    /**
     * Classes that name {@code outerInternalName} as their enclosing class,
     * in class_defs order.
     *
     * This is the INVERSE of the EnclosingClass / EnclosingMethod system
     * annotations, and it is the only way to answer "what does this class
     * enclose" completely: MemberClasses lists the declared members but NOT
     * the anonymous and local classes, and HotSpot rejects a class file whose
     * outer class has no entry for an anonymous class it encloses
     * ("Sample and Sample$1 disagree on InnerClasses attribute" from
     * Class.getDeclaringClass).
     *
     * <p><b>Per-dex, like every other index here -- and that is NOT enough on
     * its own.</b> This class's own doc used to claim "in practice a splitter
     * keeps a nest together, precisely because this relationship has to
     * survive". That is disproven: measured on Signal 172401,
     * {@code org/signal/video/exo/ExoPlayerPool} is defined in
     * {@code classes5.dex} while its own nested
     * {@code ExoPlayerPool$DataSourceTransferListener} is defined in
     * {@code classes8.dex}. A per-dex answer to "what does ExoPlayerPool
     * enclose" never sees the nested class in the other file, so the outer's
     * emitted InnerClasses silently omitted it. DexConverter and NestPlan
     * build a SESSION-wide index instead ({@link NestIndex}, over the same merged,
     * first-definition-wins class map ClassHierarchyOracle already uses for
     * the identical cross-dex reason). This method stays correct for what it
     * documents -- one dex file's own class_defs -- but nothing in the
     * shipped converter should call it for anything InnerClasses/NestHost/
     * NestMembers related; use NestIndex instead.
     */
    public List<DexClass> enclosedClasses(String outerInternalName) {
        Map<String, List<DexClass>> idx = nestIndex;
        if (idx == null) {
            synchronized (this) {
                idx = nestIndex;
                if (idx == null) {
                    idx = new HashMap<>();
                    for (DexClass c : classes) {
                        String outer = enclosingOf(c);
                        if (outer != null) {
                            idx.computeIfAbsent(outer, k -> new ArrayList<>()).add(c);
                        }
                    }
                    nestIndex = idx;
                }
            }
        }
        List<DexClass> got = idx.get(outerInternalName);
        return got == null ? Collections.emptyList() : got;
    }

    /**
     * The internal name of the class enclosing {@code c}, or null if none.
     *
     * Package-private (not private) so {@link NestIndex} can build the same
     * relationship SESSION-wide instead of per-dex -- this method itself only
     * ever reads {@code c}'s own annotations, so it needs no dex-boundary
     * awareness and is safe to share verbatim.
     */
    static String enclosingOf(DexClass c) {
        for (Annotation a : c.annotations()) {
            if (a.visibility() != VISIBILITY_SYSTEM || a.type() == null) continue;
            if ("Ldalvik/annotation/EnclosingClass;".equals(a.type())) {
                Value v = a.element("value");
                String d = v == null ? null : v.asTypeDescriptor();
                if (d != null) return internalName(d);
            } else if ("Ldalvik/annotation/EnclosingMethod;".equals(a.type())) {
                Value v = a.element("value");
                MethodRef m = v == null ? null : v.asMethod();
                if (m != null) return m.declaringClassName();
            }
        }
        return null;
    }

    // ---- id table accessors ------------------------------------------------

    public int stringCount() {
        return stringIdsSize;
    }

    /**
     * string_ids[index], MUTF-8 decoded.
     *
     * @return null for {@link #NO_INDEX} or any out-of-range index, so callers
     *         can pass a raw uleb128p1 result straight through
     */
    public String string(int index) {
        if (index < 0 || index >= stringIdsSize) {
            return null;
        }
        String s = stringCache[index];
        if (s != null) {
            return s;
        }
        // string_id_item is just an offset to a string_data_item, which is a
        // uleb128 utf16_size followed by NUL-terminated MUTF-8.
        int off = u4(stringIdsOff + index * 4);
        Reader r = new Reader(data, off);
        int utf16Size = r.uleb128();
        s = Mutf8.decode(data, r.pos(), utf16Size);
        stringCache[index] = s;
        return s;
    }

    public int typeCount() {
        return typeIdsSize;
    }

    /** type_ids[index] as a descriptor: "Lcom/foo/Bar;", "[I", "J". */
    public String typeDescriptor(int index) {
        if (index < 0 || index >= typeIdsSize) {
            return null;
        }
        return string(u4(typeIdsOff + index * 4));
    }

    /** type_ids[index] as a JVM internal name. See {@link #internalName}. */
    public String typeName(int index) {
        return internalName(typeDescriptor(index));
    }

    /**
     * Converts a type descriptor to the JVM "internal name" form used by
     * CONSTANT_Class_info: "Lcom/foo/Bar;" becomes "com/foo/Bar", while array
     * descriptors like "[Lcom/foo/Bar;" and primitives are returned unchanged
     * (the JVM spec keeps arrays in descriptor form there).
     */
    public static String internalName(String descriptor) {
        if (descriptor == null) {
            return null;
        }
        int n = descriptor.length();
        if (n > 2 && descriptor.charAt(0) == 'L' && descriptor.charAt(n - 1) == ';') {
            return descriptor.substring(1, n - 1);
        }
        return descriptor;
    }

    public int protoCount() {
        return protoIdsSize;
    }

    /** proto_ids[index]: shorty, return type and parameter types. */
    public Proto proto(int index) {
        if (index < 0 || index >= protoIdsSize) {
            return null;
        }
        Proto p = protoCache[index];
        if (p != null) {
            return p;
        }
        // proto_id_item: shorty_idx u4, return_type_idx u4, parameters_off u4
        int base = protoIdsOff + index * 12;
        p = new Proto(string(u4(base)), typeDescriptor(u4(base + 4)), typeDescriptors(u4(base + 8)));
        protoCache[index] = p;
        return p;
    }

    public int fieldRefCount() {
        return fieldIdsSize;
    }

    /** field_ids[index]: the declaring class, type and name of a field. */
    public FieldRef fieldRef(int index) {
        if (index < 0 || index >= fieldIdsSize) {
            return null;
        }
        FieldRef f = fieldRefCache[index];
        if (f != null) {
            return f;
        }
        // field_id_item: class_idx u2, type_idx u2, name_idx u4
        int base = fieldIdsOff + index * 8;
        f = new FieldRef(typeDescriptor(u2(base)), typeDescriptor(u2(base + 2)), string(u4(base + 4)));
        fieldRefCache[index] = f;
        return f;
    }

    public int methodRefCount() {
        return methodIdsSize;
    }

    /** method_ids[index]: the declaring class, proto and name of a method. */
    public MethodRef methodRef(int index) {
        if (index < 0 || index >= methodIdsSize) {
            return null;
        }
        MethodRef m = methodRefCache[index];
        if (m != null) {
            return m;
        }
        // method_id_item: class_idx u2, proto_idx u2, name_idx u4
        int base = methodIdsOff + index * 8;
        m = new MethodRef(typeDescriptor(u2(base)), proto(u2(base + 2)), string(u4(base + 4)));
        methodRefCache[index] = m;
        return m;
    }

    /** Number of method_handle_items, 0 on dex versions below 038. */
    public int methodHandleCount() {
        return methodHandlesSize;
    }

    /** method_handles[index], the operand of a const-method-handle. */
    public MethodHandleRef methodHandle(int index) {
        if (index < 0 || index >= methodHandlesSize) {
            return null;
        }
        MethodHandleRef h = methodHandleCache[index];
        if (h != null) {
            return h;
        }
        // method_handle_item: type u2, unused u2, field_or_method_id u2, unused u2
        int base = methodHandlesOff + index * 8;
        h = new MethodHandleRef(this, u2(base), u2(base + 4));
        methodHandleCache[index] = h;
        return h;
    }

    /** Number of call_site_ids, 0 on dex versions below 038. */
    public int callSiteCount() {
        return callSiteIdsSize;
    }

    /**
     * call_site_ids[index]. A call site is an encoded_array whose first three
     * entries are the bootstrap method handle, the method name and the method
     * type, followed by the bootstrap arguments. Spec: "call_site_item".
     */
    public CallSite callSite(int index) {
        if (index < 0 || index >= callSiteIdsSize) {
            return null;
        }
        CallSite c = callSiteCache[index];
        if (c != null) {
            return c;
        }
        int off = u4(callSiteIdsOff + index * 4);
        c = new CallSite(readEncodedArray(off));
        callSiteCache[index] = c;
        return c;
    }

    // ---- shared value types ------------------------------------------------

    /** A method prototype: return type plus parameter types. */
    public static final class Proto {
        private final String shorty;
        private final String returnType;
        private final List<String> parameterTypes;
        private final String descriptor;

        Proto(String shorty, String returnType, List<String> parameterTypes) {
            this.shorty = shorty;
            this.returnType = returnType;
            this.parameterTypes = parameterTypes;
            StringBuilder sb = new StringBuilder(16);
            sb.append('(');
            for (String p : parameterTypes) {
                sb.append(p);
            }
            sb.append(')').append(returnType);
            this.descriptor = sb.toString();
        }

        /** The shorty descriptor, e.g. "VLI" (return type first). */
        public String shorty() {
            return shorty;
        }

        /** Return type descriptor, e.g. "V" or "Ljava/lang/String;". */
        public String returnType() {
            return returnType;
        }

        /** Parameter type descriptors, in declaration order. */
        public List<String> parameterTypes() {
            return parameterTypes;
        }

        /**
         * The JVM-style method descriptor "(params)ret". DEX and JVM descriptor
         * syntax are identical, so this string is directly usable in a
         * CONSTANT_NameAndType_info.
         */
        public String descriptor() {
            return descriptor;
        }

        /**
         * Number of JVM local slots the parameters occupy, counting J and D as
         * two. Excludes "this".
         */
        public int parameterSlots() {
            int n = 0;
            for (String p : parameterTypes) {
                char c = p.charAt(0);
                n += (c == 'J' || c == 'D') ? 2 : 1;
            }
            return n;
        }

        @Override
        public String toString() {
            return descriptor;
        }
    }

    /** A field_id_item: a symbolic reference to a field. */
    public static final class FieldRef {
        private final String declaringClass;
        private final String type;
        private final String name;

        FieldRef(String declaringClass, String type, String name) {
            this.declaringClass = declaringClass;
            this.type = type;
            this.name = name;
        }

        /** Declaring class as a descriptor, e.g. "Lcom/foo/Bar;". */
        public String declaringClass() {
            return declaringClass;
        }

        /** Declaring class as a JVM internal name, e.g. "com/foo/Bar". */
        public String declaringClassName() {
            return internalName(declaringClass);
        }

        /** Field type descriptor. */
        public String type() {
            return type;
        }

        public String name() {
            return name;
        }

        @Override
        public String toString() {
            return declaringClass + "->" + name + ":" + type;
        }
    }

    /** A method_id_item: a symbolic reference to a method. */
    public static final class MethodRef {
        private final String declaringClass;
        private final Proto proto;
        private final String name;

        MethodRef(String declaringClass, Proto proto, String name) {
            this.declaringClass = declaringClass;
            this.proto = proto;
            this.name = name;
        }

        /**
         * Declaring class as a descriptor. Note this can be an ARRAY
         * descriptor such as "[I" (for example {@code int[].clone()}), which is
         * why the class file writer must go through
         * {@link #declaringClassName()} rather than stripping "L...;" itself.
         */
        public String declaringClass() {
            return declaringClass;
        }

        /** Declaring class as a JVM internal name. */
        public String declaringClassName() {
            return internalName(declaringClass);
        }

        public Proto proto() {
            return proto;
        }

        /** The JVM-style method descriptor "(params)ret". */
        public String descriptor() {
            return proto == null ? null : proto.descriptor();
        }

        public String name() {
            return name;
        }

        @Override
        public String toString() {
            return declaringClass + "->" + name + descriptor();
        }
    }

    /** A method_handle_item, the operand of const-method-handle / invoke-custom. */
    public static final class MethodHandleRef {
        private final int type;
        private final int fieldOrMethodIndex;
        private final FieldRef field;
        private final MethodRef method;

        MethodHandleRef(DexFile dex, int type, int fieldOrMethodIndex) {
            this.type = type;
            this.fieldOrMethodIndex = fieldOrMethodIndex;
            // Types 0x00..0x03 are the field accessors; 0x04..0x08 are the
            // invocation kinds. Which id table the index refers to depends on
            // that, so resolve once here rather than making every caller
            // rediscover the rule.
            boolean isField = type <= METHOD_HANDLE_INSTANCE_GET;
            this.field = isField ? dex.fieldRef(fieldOrMethodIndex) : null;
            this.method = isField ? null : dex.methodRef(fieldOrMethodIndex);
        }

        /** One of the METHOD_HANDLE_* constants. */
        public int type() {
            return type;
        }

        public int fieldOrMethodIndex() {
            return fieldOrMethodIndex;
        }

        /** The referenced field, or null when this handle targets a method. */
        public FieldRef field() {
            return field;
        }

        /** The referenced method, or null when this handle targets a field. */
        public MethodRef method() {
            return method;
        }

        @Override
        public String toString() {
            return "MethodHandle(type=" + type + ", " + (field != null ? field : method) + ")";
        }
    }

    /** A call_site_item: the bootstrap arguments for an invoke-custom. */
    public static final class CallSite {
        private final List<Value> values;

        CallSite(List<Value> values) {
            this.values = values;
        }

        /** All entries, bootstrap handle first. */
        public List<Value> values() {
            return values;
        }

        /** Entry 0: the bootstrap method handle. */
        public MethodHandleRef bootstrapMethod() {
            return values.isEmpty() ? null : values.get(0).asMethodHandle();
        }

        /** Entry 1: the name passed to the bootstrap method. */
        public String methodName() {
            return values.size() < 2 ? null : values.get(1).asString();
        }

        /** Entry 2: the method type passed to the bootstrap method. */
        public Proto methodType() {
            return values.size() < 3 ? null : values.get(2).asProto();
        }

        /** Entries 3 and beyond: the extra static bootstrap arguments. */
        public List<Value> bootstrapArguments() {
            return values.size() <= 3 ? List.of() : values.subList(3, values.size());
        }

        @Override
        public String toString() {
            return "CallSite" + values;
        }
    }

    /** A single annotation with its element name/value pairs. */
    public static final class Annotation {
        private final int visibility;
        private final String type;
        private final List<AnnotationElement> elements;

        Annotation(int visibility, String type, List<AnnotationElement> elements) {
            this.visibility = visibility;
            this.type = type;
            this.elements = elements;
        }

        /**
         * One of VISIBILITY_BUILD / RUNTIME / SYSTEM, or -1 for a nested
         * annotation (the encoded_annotation inside a value carries no
         * visibility byte of its own). Only RUNTIME annotations belong in
         * RuntimeVisibleAnnotations.
         */
        public int visibility() {
            return visibility;
        }

        /** Annotation type descriptor, e.g. "Lcom/squareup/moshi/Json;". */
        public String type() {
            return type;
        }

        public List<AnnotationElement> elements() {
            return elements;
        }

        /** Convenience lookup by element name, or null. */
        public Value element(String name) {
            for (AnnotationElement e : elements) {
                // e.name() can be null if the dex carried an out-of-range
                // string index, so compare in that order.
                if (name.equals(e.name())) {
                    return e.value();
                }
            }
            return null;
        }

        @Override
        public String toString() {
            return "@" + type + elements;
        }
    }

    /** One name/value pair inside an annotation. */
    public static final class AnnotationElement {
        private final String name;
        private final Value value;

        AnnotationElement(String name, Value value) {
            this.name = name;
            this.value = value;
        }

        public String name() {
            return name;
        }

        public Value value() {
            return value;
        }

        @Override
        public String toString() {
            return name + "=" + value;
        }
    }

    /**
     * A decoded encoded_value: a static field initializer, an annotation
     * element, or a call site argument.
     *
     * <p>Numeric payloads are already widened per spec: BYTE/SHORT/INT/LONG are
     * sign-extended from their ENCODED width, CHAR is zero-extended, and
     * FLOAT/DOUBLE are shifted left so the stored bytes land in the
     * high-order end (DEX stores only the significant high bytes of an IEEE754
     * pattern). Getting that wrong is not theoretical: leaving INT
     * un-extended turns AppCompat's MODE_NIGHT_UNSPECIFIED (-100, one
     * encoded byte 0x9C) into 156, and every AppCompatActivity throws.
     */
    public static final class Value {
        private final int tag;
        private final long bits;
        private final Object ref;

        Value(int tag, long bits, Object ref) {
            this.tag = tag;
            this.bits = bits;
            this.ref = ref;
        }

        /** One of the VALUE_* constants. */
        public int tag() {
            return tag;
        }

        public boolean isNull() {
            return tag == VALUE_NULL;
        }

        /**
         * The widened integral payload for BYTE, SHORT, CHAR, INT, LONG and
         * BOOLEAN. Also returns the raw IEEE754 bit pattern for FLOAT/DOUBLE.
         */
        public long asLong() {
            return bits;
        }

        public int asInt() {
            return (int) bits;
        }

        public boolean asBoolean() {
            return bits != 0;
        }

        public float asFloat() {
            return Float.intBitsToFloat((int) bits);
        }

        public double asDouble() {
            return Double.longBitsToDouble(bits);
        }

        /** The string payload for VALUE_STRING, else null. */
        public String asString() {
            return tag == VALUE_STRING ? (String) ref : null;
        }

        /** The type descriptor for VALUE_TYPE, else null. */
        public String asTypeDescriptor() {
            return tag == VALUE_TYPE ? (String) ref : null;
        }

        /** The field for VALUE_FIELD or VALUE_ENUM, else null. */
        public FieldRef asField() {
            return ref instanceof FieldRef ? (FieldRef) ref : null;
        }

        /** The method for VALUE_METHOD, else null. */
        public MethodRef asMethod() {
            return ref instanceof MethodRef ? (MethodRef) ref : null;
        }

        /** The prototype for VALUE_METHOD_TYPE, else null. */
        public Proto asProto() {
            return ref instanceof Proto ? (Proto) ref : null;
        }

        /** The handle for VALUE_METHOD_HANDLE, else null. */
        public MethodHandleRef asMethodHandle() {
            return ref instanceof MethodHandleRef ? (MethodHandleRef) ref : null;
        }

        /** The elements for VALUE_ARRAY, else null. */
        @SuppressWarnings("unchecked")
        public List<Value> asArray() {
            return tag == VALUE_ARRAY ? (List<Value>) ref : null;
        }

        /** The nested annotation for VALUE_ANNOTATION, else null. */
        public Annotation asAnnotation() {
            return ref instanceof Annotation ? (Annotation) ref : null;
        }

        @Override
        public String toString() {
            switch (tag) {
                case VALUE_NULL: return "null";
                case VALUE_BOOLEAN: return String.valueOf(bits != 0);
                case VALUE_FLOAT: return asFloat() + "f";
                case VALUE_DOUBLE: return String.valueOf(asDouble());
                case VALUE_CHAR: return "'" + (char) bits + "'";
                case VALUE_STRING: return "\"" + ref + "\"";
                default: return ref != null ? String.valueOf(ref) : String.valueOf(bits);
            }
        }
    }

    // ---- internal parsing helpers (package private) ------------------------

    int u1(int off) {
        return data[off] & 0xff;
    }

    int u2(int off) {
        return (data[off] & 0xff) | ((data[off + 1] & 0xff) << 8);
    }

    int u4(int off) {
        return (data[off] & 0xff)
                | ((data[off + 1] & 0xff) << 8)
                | ((data[off + 2] & 0xff) << 16)
                | ((data[off + 3] & 0xff) << 24);
    }

    byte[] bytes() {
        return data;
    }

    Reader reader(int off) {
        return new Reader(data, off);
    }

    /** type_list at {@code off} as raw type indices; empty for off == 0. */
    int[] typeListIndices(int off) {
        if (off == 0) {
            return EMPTY_INTS;
        }
        int size = u4(off);
        int[] out = new int[size];
        for (int i = 0; i < size; i++) {
            out[i] = u2(off + 4 + i * 2);
        }
        return out;
    }

    /** type_list at {@code off} as descriptors; empty list for off == 0. */
    List<String> typeDescriptors(int off) {
        if (off == 0) {
            return List.of();
        }
        int size = u4(off);
        String[] out = new String[size];
        for (int i = 0; i < size; i++) {
            out[i] = typeDescriptor(u2(off + 4 + i * 2));
        }
        // Arrays.asList rather than List.of: a malformed or truncated dex can
        // carry an out-of-range type index, which resolves to null, and List.of
        // rejects null elements with an NPE that would abort the whole APK.
        return Collections.unmodifiableList(Arrays.asList(out));
    }

    private static final int[] EMPTY_INTS = new int[0];

    /** encoded_array_item at {@code off}: a uleb128 count then that many values. */
    List<Value> readEncodedArray(int off) {
        if (off == 0) {
            return List.of();
        }
        Reader r = reader(off);
        return readValueList(r, r.uleb128());
    }

    private List<Value> readValueList(Reader r, int size) {
        if (size <= 0) {
            return List.of();
        }
        Value[] out = new Value[size];
        for (int i = 0; i < size; i++) {
            out[i] = readValue(r);
        }
        return List.of(out);
    }

    /**
     * Reads one encoded_value at the reader's cursor.
     *
     * <p>The leading byte packs {@code value_type = tag & 0x1f} and
     * {@code value_arg = tag >> 5}. For the sized types value_arg is
     * "size - 1", so the payload is 1..8 bytes stored little-endian, holding
     * only as many bytes as the value needs.
     */
    Value readValue(Reader r) {
        int tagByte = r.u1();
        int type = tagByte & 0x1f;
        int arg = (tagByte >> 5) & 0x07;

        switch (type) {
            case VALUE_NULL:
                return new Value(VALUE_NULL, 0, null);
            case VALUE_BOOLEAN:
                // The only type whose payload lives entirely in value_arg.
                return new Value(VALUE_BOOLEAN, arg & 1, null);
            case VALUE_ARRAY:
                return new Value(VALUE_ARRAY, 0, readValueList(r, r.uleb128()));
            case VALUE_ANNOTATION:
                return new Value(VALUE_ANNOTATION, 0, readEncodedAnnotation(r, -1));
            default:
                break;
        }

        int size = arg + 1;
        long raw = 0;
        for (int i = 0; i < size; i++) {
            raw |= ((long) r.u1()) << (i * 8);
        }

        switch (type) {
            case VALUE_BYTE:
            case VALUE_SHORT:
            case VALUE_INT:
                return new Value(type, signExtend(raw, size), null);
            case VALUE_LONG:
                return new Value(type, signExtend(raw, size), null);
            case VALUE_CHAR:
                // Zero-extended: a char is unsigned.
                return new Value(type, raw, null);
            case VALUE_FLOAT:
                // "zero-extended to the right": the encoded bytes are the
                // HIGH-order bytes of the 32-bit pattern, so 1.0f (0x3f800000)
                // encodes as the two bytes 80 3f, not 00 00 80 3f.
                return new Value(type, (raw << (32 - size * 8)) & 0xffffffffL, null);
            case VALUE_DOUBLE:
                return new Value(type, raw << (64 - size * 8), null);
            case VALUE_STRING:
                return new Value(type, raw, string((int) raw));
            case VALUE_TYPE:
                return new Value(type, raw, typeDescriptor((int) raw));
            case VALUE_FIELD:
            case VALUE_ENUM:
                return new Value(type, raw, fieldRef((int) raw));
            case VALUE_METHOD:
                return new Value(type, raw, methodRef((int) raw));
            case VALUE_METHOD_TYPE:
                return new Value(type, raw, proto((int) raw));
            case VALUE_METHOD_HANDLE:
                return new Value(type, raw, methodHandle((int) raw));
            default:
                // Unknown tag. We already consumed value_arg + 1 bytes, which
                // is the encoding every sized type uses, so the stream stays
                // in sync and one odd value does not poison the rest.
                return new Value(type, raw, null);
        }
    }

    private static long signExtend(long value, int sizeBytes) {
        int shift = 64 - sizeBytes * 8;
        return (value << shift) >> shift;
    }

    /** encoded_annotation: type_idx uleb128, size uleb128, then name/value pairs. */
    Annotation readEncodedAnnotation(Reader r, int visibility) {
        String type = typeDescriptor(r.uleb128());
        int size = r.uleb128();
        if (size == 0) {
            return new Annotation(visibility, type, List.of());
        }
        AnnotationElement[] elements = new AnnotationElement[size];
        for (int i = 0; i < size; i++) {
            String name = string(r.uleb128());
            elements[i] = new AnnotationElement(name, readValue(r));
        }
        return new Annotation(visibility, type, List.of(elements));
    }

    /** annotation_set_item at {@code off}; empty list for off == 0. */
    List<Annotation> readAnnotationSet(int off) {
        if (off == 0) {
            return List.of();
        }
        int size = u4(off);
        if (size == 0) {
            return List.of();
        }
        Annotation[] out = new Annotation[size];
        for (int i = 0; i < size; i++) {
            // Each entry points at an annotation_item: a visibility byte
            // followed by an encoded_annotation.
            Reader r = reader(u4(off + 4 + i * 4));
            int visibility = r.u1();
            out[i] = readEncodedAnnotation(r, visibility);
        }
        return List.of(out);
    }

    /** annotation_set_ref_list at {@code off}: one annotation set per parameter. */
    List<List<Annotation>> readAnnotationSetRefList(int off) {
        if (off == 0) {
            return List.of();
        }
        int size = u4(off);
        List<List<Annotation>> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(readAnnotationSet(u4(off + 4 + i * 4)));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * annotations_directory_item at {@code off}.
     *
     * <p>Layout, per dx's AnnotationsDirectoryItem.writeTo0(): class_annotations_off
     * u4, fields_size u4, methods_size u4, parameters_size u4, then
     * field_annotation[fields_size] {field_idx u4, annotations_off u4}, then
     * method_annotation[methods_size], then parameter_annotation[parameters_size]
     * (whose annotations_off points at an annotation_set_ref_list).
     */
    /**
     * Just the class_annotations of an annotations_directory_item, WITHOUT
     * reading the per-field / per-method / per-parameter tables that follow it.
     *
     * <p>class_annotations_off is the FIRST u4 of the directory and points at a
     * self-contained annotation_set_item, so answering "what annotations are on
     * this class" needs one indirection and nothing else. The full
     * {@link #readAnnotationsDirectory} exists to hand each DexField/DexMethod
     * its own annotations at construction, which only the class_data parse
     * needs; a caller that wants the class-level answer must not be made to pay
     * for that. See DexClass.annotations(): the session-wide NestIndex pass asks
     * every class in the app this one question, and routing it through the
     * member parse is what pinned 411,385 parsed class_data items (+706 MB,
     * measured on TikTok 2024604030) for the life of a bulk conversion.
     */
    List<Annotation> readClassAnnotations(int off) {
        return off == 0 ? List.of() : readAnnotationSet(u4(off));
    }

    AnnotationsDirectory readAnnotationsDirectory(int off) {
        if (off == 0) {
            return AnnotationsDirectory.EMPTY;
        }
        int classOff = u4(off);
        int fieldsSize = u4(off + 4);
        int methodsSize = u4(off + 8);
        int paramsSize = u4(off + 12);
        int p = off + 16;

        Map<Integer, List<Annotation>> fields = new HashMap<>(fieldsSize * 2);
        for (int i = 0; i < fieldsSize; i++, p += 8) {
            fields.put(u4(p), readAnnotationSet(u4(p + 4)));
        }
        Map<Integer, List<Annotation>> methods = new HashMap<>(methodsSize * 2);
        for (int i = 0; i < methodsSize; i++, p += 8) {
            methods.put(u4(p), readAnnotationSet(u4(p + 4)));
        }
        Map<Integer, List<List<Annotation>>> params = new HashMap<>(paramsSize * 2);
        for (int i = 0; i < paramsSize; i++, p += 8) {
            params.put(u4(p), readAnnotationSetRefList(u4(p + 4)));
        }
        return new AnnotationsDirectory(readAnnotationSet(classOff), fields, methods, params);
    }

    /** Parsed annotations_directory_item, keyed by field_idx / method_idx. */
    static final class AnnotationsDirectory {
        static final AnnotationsDirectory EMPTY = new AnnotationsDirectory(
                List.of(), Map.of(), Map.of(), Map.of());

        final List<Annotation> classAnnotations;
        private final Map<Integer, List<Annotation>> fieldAnnotations;
        private final Map<Integer, List<Annotation>> methodAnnotations;
        private final Map<Integer, List<List<Annotation>>> parameterAnnotations;

        AnnotationsDirectory(List<Annotation> classAnnotations,
                             Map<Integer, List<Annotation>> fieldAnnotations,
                             Map<Integer, List<Annotation>> methodAnnotations,
                             Map<Integer, List<List<Annotation>>> parameterAnnotations) {
            this.classAnnotations = classAnnotations;
            this.fieldAnnotations = fieldAnnotations;
            this.methodAnnotations = methodAnnotations;
            this.parameterAnnotations = parameterAnnotations;
        }

        List<Annotation> forField(int fieldIdx) {
            List<Annotation> a = fieldAnnotations.get(fieldIdx);
            return a == null ? List.of() : a;
        }

        List<Annotation> forMethod(int methodIdx) {
            List<Annotation> a = methodAnnotations.get(methodIdx);
            return a == null ? List.of() : a;
        }

        List<List<Annotation>> forParameters(int methodIdx) {
            List<List<Annotation>> a = parameterAnnotations.get(methodIdx);
            return a == null ? List.of() : a;
        }
    }

    /**
     * A little-endian cursor over the dex image with LEB128 support.
     *
     * <p>Not thread safe by design: each parse creates its own cursor over the
     * shared, never-mutated byte array.
     */
    static final class Reader {
        private final byte[] d;
        private int p;

        Reader(byte[] d, int pos) {
            this.d = d;
            this.p = pos;
        }

        int pos() {
            return p;
        }

        void pos(int newPos) {
            this.p = newPos;
        }

        void skip(int n) {
            p += n;
        }

        boolean hasMore(int limit) {
            return p < limit;
        }

        int u1() {
            return d[p++] & 0xff;
        }

        int u2() {
            int v = (d[p] & 0xff) | ((d[p + 1] & 0xff) << 8);
            p += 2;
            return v;
        }

        int u4() {
            int v = (d[p] & 0xff)
                    | ((d[p + 1] & 0xff) << 8)
                    | ((d[p + 2] & 0xff) << 16)
                    | ((d[p + 3] & 0xff) << 24);
            p += 4;
            return v;
        }

        /** Unsigned LEB128, 1..5 bytes, seven payload bits per byte. */
        int uleb128() {
            int result = 0;
            int shift = 0;
            while (true) {
                int b = d[p++] & 0xff;
                result |= (b & 0x7f) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
                if (shift > 28) {
                    // Malformed continuation. Stop rather than shifting off the
                    // end of the int and looping forever on garbage input.
                    return result;
                }
            }
        }

        /**
         * uleb128p1: the encoded value is the real value plus one, so 0 means
         * "absent" and decodes to {@link DexFile#NO_INDEX}. Used for every
         * optional index in debug_info_item.
         */
        int uleb128p1() {
            return uleb128() - 1;
        }

        /** Signed LEB128, sign-extended from the last continuation bit. */
        int sleb128() {
            int result = 0;
            int shift = 0;
            int b;
            do {
                b = d[p++] & 0xff;
                result |= (b & 0x7f) << shift;
                shift += 7;
            } while ((b & 0x80) != 0 && shift <= 28);
            if (shift < 32 && (b & 0x40) != 0) {
                result |= -(1 << shift);
            }
            return result;
        }
    }
}
