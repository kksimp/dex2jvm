package io.github.kksimp.dex2jvm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One class_def_item plus the class_data_item, static values and annotations
 * that hang off it.
 *
 * <p>Only the 32-byte class_def_item is read when the DexFile is parsed. The
 * member tables are parsed on first access and memoized, because the on-demand
 * conversion path only ever touches a handful of classes out of the tens of
 * thousands in a modern APK, and parsing every code_item up front would throw
 * that saving away.
 *
 * <p>That memo suits the on-demand path and is exactly wrong for the BULK path,
 * which touches every class and holds every DexClass for the whole session, so
 * a memo it never releases is a memo it never stops paying for. The bulk passes
 * therefore call {@link #releaseMembers()} when they are done with a class --
 * see that method for the measurement. Note that {@link #annotations()} answers
 * without a member parse at all, which is what keeps the session-wide NestIndex
 * pass cheap.
 *
 * <p>Spec: https://source.android.com/docs/core/runtime/dex-format
 * ("class_def_item", "class_data_item").
 */
public final class DexClass {

    private final DexFile dex;
    private final int typeIndex;
    private final int accessFlags;
    private final int superclassIndex;
    private final int interfacesOff;
    private final int sourceFileIndex;
    private final int annotationsOff;
    private final int classDataOff;
    private final int staticValuesOff;

    private final String descriptor;

    // Non-null once the member tables have been parsed. Volatile plus the
    // double-checked read in members() is what makes lazy parsing safe to share
    // across threads; the Members object itself is fully built before publish.
    private volatile Members members;

    DexClass(DexFile dex, int off) {
        this.dex = dex;
        // class_def_item: class_idx, access_flags, superclass_idx,
        // interfaces_off, source_file_idx, annotations_off, class_data_off,
        // static_values_off. Eight u4s, 32 bytes.
        this.typeIndex = dex.u4(off);
        this.accessFlags = dex.u4(off + 4);
        this.superclassIndex = dex.u4(off + 8);
        this.interfacesOff = dex.u4(off + 12);
        this.sourceFileIndex = dex.u4(off + 16);
        this.annotationsOff = dex.u4(off + 20);
        this.classDataOff = dex.u4(off + 24);
        this.staticValuesOff = dex.u4(off + 28);
        this.descriptor = dex.typeDescriptor(typeIndex);
    }

    /** The dex this class was defined in. */
    public DexFile dex() {
        return dex;
    }

    /** Type descriptor, e.g. "Lcom/example/Foo;". */
    public String descriptor() {
        return descriptor;
    }

    /** JVM internal name, e.g. "com/example/Foo". This is the class file name. */
    public String name() {
        return DexFile.internalName(descriptor);
    }

    /** Index into type_ids. */
    public int typeIndex() {
        return typeIndex;
    }

    /** Raw DEX access flags. See {@link DexFile.Access}. */
    public int accessFlags() {
        return accessFlags;
    }

    public boolean isInterface() {
        return (accessFlags & DexFile.Access.ACC_INTERFACE) != 0;
    }

    public boolean isAnnotation() {
        return (accessFlags & DexFile.Access.ACC_ANNOTATION) != 0;
    }

    public boolean isEnum() {
        return (accessFlags & DexFile.Access.ACC_ENUM) != 0;
    }

    /**
     * Superclass descriptor, or null when superclass_idx is NO_INDEX. Only
     * java/lang/Object and interfaces legitimately have no superclass; a class
     * file writer must still emit java/lang/Object as the super for an
     * interface.
     */
    public String superclassDescriptor() {
        return superclassIndex == DexFile.NO_INDEX ? null : dex.typeDescriptor(superclassIndex);
    }

    /** Superclass as a JVM internal name, or null. */
    public String superclassName() {
        return DexFile.internalName(superclassDescriptor());
    }

    /** Directly implemented interfaces, as descriptors. */
    public List<String> interfaceDescriptors() {
        return dex.typeDescriptors(interfacesOff);
    }

    /** Directly implemented interfaces, as JVM internal names. */
    public List<String> interfaceNames() {
        List<String> descs = interfaceDescriptors();
        if (descs.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(descs.size());
        for (String d : descs) {
            out.add(DexFile.internalName(d));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * The source file name from class_def_item, e.g. "Foo.java", or null when
     * source_file_idx is NO_INDEX (which is what R8 emits once it strips debug
     * info). This is the value for the class file's SourceFile attribute.
     */
    public String sourceFile() {
        return sourceFileIndex == DexFile.NO_INDEX ? null : dex.string(sourceFileIndex);
    }

    /**
     * Class-level annotations.
     *
     * <p>Deliberately does NOT go through {@link #members()}: class_annotations
     * lives in the annotations_directory_item, which is a different structure
     * from class_data_item, so this question is answerable without parsing a
     * single field, method or code_item. It used to read
     * {@code members().annotationsDirectory.classAnnotations}, which meant the
     * session-wide NestIndex pass -- one {@code DexFile.enclosingOf} call per
     * class, and it only ever looks for EnclosingClass/EnclosingMethod --
     * materialised and MEMOIZED the whole member table of every class in the
     * app. Measured on TikTok 2024604030 (411,385 classes): +706 MB of
     * permanently reachable parse, in the Session CONSTRUCTOR, before any class
     * was converted.
     *
     * <p>An existing memo is still preferred when there is one, so the
     * on-demand path (which parses members anyway) does not pay a second read.
     */
    public List<DexFile.Annotation> annotations() {
        Members m = members;
        return m != null ? m.annotationsDirectory.classAnnotations
                         : dex.readClassAnnotations(annotationsOff);
    }

    /** Static fields, in declaration order. */
    public List<DexField> staticFields() {
        return members().staticFields;
    }

    /** Instance fields, in declaration order. */
    public List<DexField> instanceFields() {
        return members().instanceFields;
    }

    /** Static fields followed by instance fields. */
    public List<DexField> fields() {
        return members().allFields;
    }

    /** Direct methods: static, private and constructors. */
    public List<DexMethod> directMethods() {
        return members().directMethods;
    }

    /** Virtual methods: everything dispatched through the vtable. */
    public List<DexMethod> virtualMethods() {
        return members().virtualMethods;
    }

    /** Direct methods followed by virtual methods. */
    public List<DexMethod> methods() {
        return members().allMethods;
    }

    /** True when the class_def carries no class_data_item at all. */
    public boolean hasClassData() {
        return classDataOff != 0;
    }

    @Override
    public String toString() {
        return descriptor;
    }

    DexFile.AnnotationsDirectory annotationsDirectory() {
        return members().annotationsDirectory;
    }

    /**
     * Drops the memoized class_data parse, so the DexFields, DexMethods and any
     * DexCode they memoized become collectable.
     *
     * <p>This hands back a CACHE, never state: {@link #members()} re-derives an
     * equal value from the same immutable dex bytes on the next call, so a
     * release can only ever cost time. That is what makes it safe for a
     * whole-session pass to release a class it did not itself pin, and safe to
     * race with another thread's {@code members()} (two equal Members objects
     * may transiently exist; nothing in the converter keys on their identity).
     *
     * <p>WHY IT EXISTS. The memo's own justification -- "the on-demand
     * conversion path only ever touches a handful of classes out of the tens of
     * thousands in a modern APK" -- is exactly inverted for the BULK path,
     * which touches every class, holds every DexClass in
     * DexConverter.Session.byName for the whole session, and therefore never
     * releases anything it parses. DexConverter.Session.forEach promises
     * "streaming straight into the jar keeps the whole conversion flat in
     * memory regardless of app size"; the OUTPUT side honoured that and the
     * INPUT side did not. Measured on TikTok 2024604030 (37 dex, 316 MB, 411,385
     * classes): OutOfMemoryError at -Xmx6g, because every converted method's
     * DexCode -- its insns short[] AND its decoded line-number table -- stayed
     * reachable through the class it was parsed from.
     */
    void releaseMembers() {
        members = null;
    }

    private Members members() {
        Members m = members;
        if (m != null) {
            return m;
        }
        synchronized (this) {
            m = members;
            if (m == null) {
                m = parseMembers();
                members = m;
            }
            return m;
        }
    }

    private Members parseMembers() {
        DexFile.AnnotationsDirectory dir = dex.readAnnotationsDirectory(annotationsOff);
        if (classDataOff == 0) {
            // Legal and common: a class_def with no members at all (marker
            // interfaces, and every class whose body R8 emptied out).
            return new Members(List.of(), List.of(), List.of(), List.of(), dir);
        }

        // static_values_off points at an encoded_array whose entries line up
        // one-for-one with the FIRST N static fields, in class_data order. A
        // shorter array is legal and means the trailing statics default to
        // zero/null. Read it BEFORE the fields so each DexField can be handed
        // its initializer at construction and stay fully final.
        List<DexFile.Value> staticValues = dex.readEncodedArray(staticValuesOff);

        DexFile.Reader r = dex.reader(classDataOff);
        int staticFieldsSize = r.uleb128();
        int instanceFieldsSize = r.uleb128();
        int directMethodsSize = r.uleb128();
        int virtualMethodsSize = r.uleb128();

        List<DexField> statics = readFields(r, staticFieldsSize, dir, staticValues);
        List<DexField> instances = readFields(r, instanceFieldsSize, dir, List.of());
        List<DexMethod> directs = readMethods(r, directMethodsSize, dir, true);
        List<DexMethod> virtuals = readMethods(r, virtualMethodsSize, dir, false);

        return new Members(
                Collections.unmodifiableList(statics),
                Collections.unmodifiableList(instances),
                Collections.unmodifiableList(directs),
                Collections.unmodifiableList(virtuals),
                dir);
    }

    private List<DexField> readFields(DexFile.Reader r, int count,
                                      DexFile.AnnotationsDirectory dir,
                                      List<DexFile.Value> staticValues) {
        if (count == 0) {
            return new ArrayList<>(0);
        }
        List<DexField> out = new ArrayList<>(count);
        // encoded_field indices are DELTA encoded within each run (static,
        // then instance), each run restarting from zero.
        int fieldIndex = 0;
        for (int i = 0; i < count; i++) {
            fieldIndex += r.uleb128();
            int access = r.uleb128();
            DexFile.Value init = i < staticValues.size() ? staticValues.get(i) : null;
            out.add(new DexField(this, fieldIndex, access, dir.forField(fieldIndex), init));
        }
        return out;
    }

    private List<DexMethod> readMethods(DexFile.Reader r, int count,
                                        DexFile.AnnotationsDirectory dir, boolean direct) {
        if (count == 0) {
            return new ArrayList<>(0);
        }
        List<DexMethod> out = new ArrayList<>(count);
        // Same delta encoding as fields, restarting at zero for the virtual run.
        int methodIndex = 0;
        for (int i = 0; i < count; i++) {
            methodIndex += r.uleb128();
            int access = r.uleb128();
            int codeOff = r.uleb128();
            out.add(new DexMethod(this, methodIndex, access, codeOff,
                    dir.forMethod(methodIndex), dir.forParameters(methodIndex), direct));
        }
        return out;
    }

    /** Everything parsed out of class_data_item, published as one unit. */
    private static final class Members {
        final List<DexField> staticFields;
        final List<DexField> instanceFields;
        final List<DexField> allFields;
        final List<DexMethod> directMethods;
        final List<DexMethod> virtualMethods;
        final List<DexMethod> allMethods;
        final DexFile.AnnotationsDirectory annotationsDirectory;

        Members(List<DexField> staticFields, List<DexField> instanceFields,
                List<DexMethod> directMethods, List<DexMethod> virtualMethods,
                DexFile.AnnotationsDirectory annotationsDirectory) {
            this.staticFields = staticFields;
            this.instanceFields = instanceFields;
            this.directMethods = directMethods;
            this.virtualMethods = virtualMethods;
            this.annotationsDirectory = annotationsDirectory;
            this.allFields = concat(staticFields, instanceFields);
            this.allMethods = concat(directMethods, virtualMethods);
        }

        private static <T> List<T> concat(List<T> a, List<T> b) {
            if (a.isEmpty()) {
                return b;
            }
            if (b.isEmpty()) {
                return a;
            }
            List<T> out = new ArrayList<>(a.size() + b.size());
            out.addAll(a);
            out.addAll(b);
            return Collections.unmodifiableList(out);
        }
    }
}
