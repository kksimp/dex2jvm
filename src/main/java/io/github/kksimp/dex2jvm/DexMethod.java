package io.github.kksimp.dex2jvm;

import java.util.List;

/**
 * One encoded_method from a class_data_item, joined with the method_id_item it
 * points at, its annotations, and its code_item.
 *
 * <p>Spec: https://source.android.com/docs/core/runtime/dex-format
 * ("encoded_method", "method_id_item", "code_item").
 */
public final class DexMethod {

    private final DexClass declaringClass;
    private final int methodIndex;
    private final int accessFlags;
    private final int codeOff;
    private final boolean direct;
    private final DexFile.MethodRef ref;
    private final List<DexFile.Annotation> annotations;
    private final List<List<DexFile.Annotation>> parameterAnnotations;

    // code_item parsing is deferred: on the on-demand conversion path most
    // methods of a loaded class are never touched, and insns copying is the
    // single biggest allocation in the whole parser.
    private volatile DexCode code;

    DexMethod(DexClass declaringClass, int methodIndex, int accessFlags, int codeOff,
              List<DexFile.Annotation> annotations,
              List<List<DexFile.Annotation>> parameterAnnotations,
              boolean direct) {
        this.declaringClass = declaringClass;
        this.methodIndex = methodIndex;
        this.accessFlags = accessFlags;
        this.codeOff = codeOff;
        this.direct = direct;
        this.ref = declaringClass.dex().methodRef(methodIndex);
        this.annotations = annotations;
        this.parameterAnnotations = parameterAnnotations;
    }

    public DexClass declaringClass() {
        return declaringClass;
    }

    /** Index into method_ids. Also the key used by annotations_directory_item. */
    public int methodIndex() {
        return methodIndex;
    }

    /** The underlying method_id_item. */
    public DexFile.MethodRef ref() {
        return ref;
    }

    public String name() {
        return ref == null ? null : ref.name();
    }

    /** The prototype: return type plus parameter types. */
    public DexFile.Proto proto() {
        return ref == null ? null : ref.proto();
    }

    /**
     * JVM-style method descriptor, e.g. "(Ljava/lang/String;I)V". DEX and JVM
     * descriptor syntax are identical, so this goes straight into the class
     * file's CONSTANT_NameAndType_info.
     */
    public String descriptor() {
        return ref == null ? null : ref.descriptor();
    }

    /** Raw DEX access flags. See {@link DexFile.Access}. */
    public int accessFlags() {
        return accessFlags;
    }

    public boolean isStatic() {
        return (accessFlags & DexFile.Access.ACC_STATIC) != 0;
    }

    public boolean isAbstract() {
        return (accessFlags & DexFile.Access.ACC_ABSTRACT) != 0;
    }

    public boolean isNative() {
        return (accessFlags & DexFile.Access.ACC_NATIVE) != 0;
    }

    public boolean isSynthetic() {
        return (accessFlags & DexFile.Access.ACC_SYNTHETIC) != 0;
    }

    /** True for &lt;init&gt; and &lt;clinit&gt;, per the DEX-only ACC_CONSTRUCTOR flag. */
    public boolean isConstructor() {
        return (accessFlags & DexFile.Access.ACC_CONSTRUCTOR) != 0;
    }

    /**
     * True when this method came from the direct_methods run (static, private
     * or a constructor), false for virtual_methods. This is a property of where
     * the method sits in class_data_item, not of the access flags, and it is
     * what tells you whether an in-class reference should be an invokespecial.
     */
    public boolean isDirect() {
        return direct;
    }

    /**
     * The method body, or null for abstract and native methods (code_off 0).
     *
     * <p>Parsed on first call and memoized.
     */
    public DexCode code() {
        if (codeOff == 0) {
            return null;
        }
        DexCode c = code;
        if (c != null) {
            return c;
        }
        synchronized (this) {
            c = code;
            if (c == null) {
                c = new DexCode(this, codeOff);
                code = c;
            }
            return c;
        }
    }

    /**
     * Drops the memoized code_item, so its insns short[] and its decoded debug
     * info become collectable.
     *
     * <p>A cache drop, exactly like {@link DexClass#releaseMembers()}: the next
     * {@link #code()} re-derives an equal value from the same dex bytes.
     *
     * <p>The caller that knows a method body is finished with is the one that
     * just emitted its bytecode, so DexConverter.convertOne releases each method
     * as it goes. Without it a class's peak is EVERY one of its methods' bodies
     * at once, and the line-number table is not a small thing: R8 canonicalises
     * debug_info_items and shares one across many methods, sized for the longest
     * sharer, so on TikTok 2024604030 the decoded positions averaged ~2,700 per
     * method with a body.
     */
    void releaseCode() {
        code = null;
    }

    /** File offset of this method's code_item, or 0 when it has none. */
    public int codeOffset() {
        return codeOff;
    }

    /** Annotations declared on this method. */
    public List<DexFile.Annotation> annotations() {
        return annotations;
    }

    /**
     * Per-parameter annotations, indexed by parameter position (excluding
     * "this"). May be shorter than the parameter list, or empty when the
     * method has none.
     */
    public List<List<DexFile.Annotation>> parameterAnnotations() {
        return parameterAnnotations;
    }

    @Override
    public String toString() {
        return declaringClass.descriptor() + "->" + name() + descriptor();
    }
}
