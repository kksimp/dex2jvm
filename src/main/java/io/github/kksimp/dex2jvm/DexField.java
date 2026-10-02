package io.github.kksimp.dex2jvm;

import java.util.List;

/**
 * One encoded_field from a class_data_item, joined with the field_id_item it
 * points at and (for static fields) its entry in the class's encoded_array of
 * initial values.
 *
 * <p>Spec: https://source.android.com/docs/core/runtime/dex-format
 * ("encoded_field", "field_id_item", "class_def_item.static_values_off").
 */
public final class DexField {

    private final DexClass declaringClass;
    private final int fieldIndex;
    private final int accessFlags;
    private final DexFile.FieldRef ref;
    private final List<DexFile.Annotation> annotations;
    private final DexFile.Value staticValue;

    DexField(DexClass declaringClass, int fieldIndex, int accessFlags,
             List<DexFile.Annotation> annotations, DexFile.Value staticValue) {
        this.declaringClass = declaringClass;
        this.fieldIndex = fieldIndex;
        this.accessFlags = accessFlags;
        this.ref = declaringClass.dex().fieldRef(fieldIndex);
        this.annotations = annotations;
        this.staticValue = staticValue;
    }

    public DexClass declaringClass() {
        return declaringClass;
    }

    /** Index into field_ids. Also the key used by annotations_directory_item. */
    public int fieldIndex() {
        return fieldIndex;
    }

    /** The underlying field_id_item. */
    public DexFile.FieldRef ref() {
        return ref;
    }

    public String name() {
        return ref == null ? null : ref.name();
    }

    /** Field type descriptor, e.g. "I" or "Ljava/lang/String;". */
    public String type() {
        return ref == null ? null : ref.type();
    }

    /** Raw DEX access flags. See {@link DexFile.Access}. */
    public int accessFlags() {
        return accessFlags;
    }

    public boolean isStatic() {
        return (accessFlags & DexFile.Access.ACC_STATIC) != 0;
    }

    public boolean isFinal() {
        return (accessFlags & DexFile.Access.ACC_FINAL) != 0;
    }

    public boolean isSynthetic() {
        return (accessFlags & DexFile.Access.ACC_SYNTHETIC) != 0;
    }

    /**
     * The compile-time constant this static field is initialized with, or null.
     *
     * <p>This is the source for the class file's ConstantValue attribute. Note
     * the JVM only honours ConstantValue for static fields of primitive or
     * String type; a static field of any other type gets its value from
     * &lt;clinit&gt;, and DEX encodes that the same way, so a non-primitive,
     * non-String value here should be dropped rather than emitted.
     *
     * <p>Sign extension has already been applied per the encoded_value rules,
     * so {@link DexFile.Value#asInt()} on a value encoded as the single byte
     * 0x9C correctly yields -100, not 156.
     */
    public DexFile.Value staticValue() {
        return staticValue;
    }

    /** Annotations declared on this field. */
    public List<DexFile.Annotation> annotations() {
        return annotations;
    }

    @Override
    public String toString() {
        return declaringClass.descriptor() + "->" + name() + ":" + type();
    }
}
