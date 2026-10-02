// AnnotationWriter -- DEX encoded annotations -> the class file's annotation
// attribute bodies (JVMS 4.7.16 / 4.7.18 / 4.7.22).
//
// WHAT THIS IS FOR
// enjarify throws every annotation away. That is invisible until an app uses a
// reflection-driven library: Retrofit reads @GET/@Path off its interface
// methods, Gson and Moshi read @SerializedName/@Json off fields, and Kotlin's
// own runtime reads @kotlin.Metadata off the class. With the annotations gone
// those libraries do not fail loudly, they silently see an empty method or a
// field with no name override, which is far harder to diagnose than a crash.
//
// THE ONE THING THAT IS EASY TO GET WRONG
// An element_value's const_value_index does NOT point at the kind of constant
// the reader ends up with. Verified against javac 21's own output (javap -v of
// a class carrying every element kind):
//
//   tag 's' (String)  -> CONSTANT_Utf8   , NOT CONSTANT_String
//   tag 'c' (Class)   -> CONSTANT_Utf8   , NOT CONSTANT_Class; the text is a
//                        RETURN DESCRIPTOR, so int.class is "I" and
//                        String[].class is "[Ljava/lang/String;"
//   tag 'e' (enum)    -> two CONSTANT_Utf8: the enum type DESCRIPTOR and the
//                        constant's simple name
//   numeric tags      -> CONSTANT_Integer/Long/Float/Double, with byte, short,
//                        char and boolean all riding CONSTANT_Integer
//   annotation type_index -> CONSTANT_Utf8 of the descriptor ("Lcom/Foo;")
//
// Using CONSTANT_String for 's' produces a class file that loads fine and then
// throws inside the annotation parser at first reflective access, which is why
// this was pinned empirically rather than from the rendered spec.

package io.github.kksimp.dex2jvm;

import java.util.List;

public final class AnnotationWriter {

    private AnnotationWriter() {}

    /**
     * Body of a RuntimeVisibleAnnotations / RuntimeInvisibleAnnotations
     * attribute (JVMS 4.7.16), or null when nothing survived translation.
     */
    public static byte[] annotationsBody(ConstantPool pool, List<DexFile.Annotation> anns) {
        if (anns == null || anns.isEmpty()) return null;
        int kept = 0;
        ConstantPool.ByteVector items = new ConstantPool.ByteVector(64);
        for (DexFile.Annotation a : anns) {
            if (!representable(a)) continue;   // see dropUnrepresentable below
            writeAnnotation(pool, items, a);
            kept++;
        }
        if (kept == 0) return null;
        ConstantPool.ByteVector out = new ConstantPool.ByteVector(items.length() + 2);
        out.putU2(kept);
        out.putBytes(items.toByteArray());
        return out.toByteArray();
    }

    /**
     * Body of a RuntimeVisibleParameterAnnotations attribute (JVMS 4.7.18).
     *
     * num_parameters is a u1 and the entries are positional, so a parameter
     * with no annotations still needs its empty entry; dropping it would shift
     * every later parameter's annotations onto the wrong argument.
     */
    public static byte[] parameterAnnotationsBody(ConstantPool pool,
                                                  List<List<DexFile.Annotation>> params) {
        if (params == null || params.isEmpty() || params.size() > 255) return null;
        boolean any = false;
        ConstantPool.ByteVector out = new ConstantPool.ByteVector(64);
        out.putU1(params.size());
        for (List<DexFile.Annotation> one : params) {
            int kept = 0;
            ConstantPool.ByteVector items = new ConstantPool.ByteVector(32);
            if (one != null) {
                for (DexFile.Annotation a : one) {
                    if (!representable(a)) continue;
                    writeAnnotation(pool, items, a);
                    kept++;
                }
            }
            out.putU2(kept);
            out.putBytes(items.toByteArray());
            if (kept > 0) any = true;
        }
        return any ? out.toByteArray() : null;
    }

    /** Body of an AnnotationDefault attribute (JVMS 4.7.22): one element_value. */
    public static byte[] annotationDefaultBody(ConstantPool pool, DexFile.Value v) {
        if (v == null || !representable(v)) return null;
        ConstantPool.ByteVector out = new ConstantPool.ByteVector(16);
        writeElementValue(pool, out, v);
        return out.toByteArray();
    }

    // ==================================================================
    // Serialization
    // ==================================================================

    private static void writeAnnotation(ConstantPool pool, ConstantPool.ByteVector out,
                                        DexFile.Annotation a) {
        out.putU2(pool.utf8(a.type()));
        List<DexFile.AnnotationElement> els = a.elements();
        out.putU2(els.size());
        for (DexFile.AnnotationElement e : els) {
            out.putU2(pool.utf8(e.name()));
            writeElementValue(pool, out, e.value());
        }
    }

    private static void writeElementValue(ConstantPool pool, ConstantPool.ByteVector out,
                                          DexFile.Value v) {
        switch (v.tag()) {
            case DexFile.VALUE_BYTE:    tagged(out, 'B', pool.integer(v.asInt())); break;
            case DexFile.VALUE_SHORT:   tagged(out, 'S', pool.integer(v.asInt())); break;
            case DexFile.VALUE_CHAR:    tagged(out, 'C', pool.integer(v.asInt())); break;
            case DexFile.VALUE_INT:     tagged(out, 'I', pool.integer(v.asInt())); break;
            case DexFile.VALUE_LONG:    tagged(out, 'J', pool.longConst(v.asLong())); break;
            // asLong() IS the raw IEEE754 pattern DEX stored. Going through
            // asFloat()/asDouble() would round-trip through a float, and raw
            // bits are the only form that keeps -0.0 distinct from 0.0 and
            // preserves a NaN payload.
            case DexFile.VALUE_FLOAT:   tagged(out, 'F', pool.floatBits((int) v.asLong())); break;
            case DexFile.VALUE_DOUBLE:  tagged(out, 'D', pool.doubleBits(v.asLong())); break;
            case DexFile.VALUE_BOOLEAN: tagged(out, 'Z', pool.integer(v.asBoolean() ? 1 : 0)); break;
            case DexFile.VALUE_STRING:  tagged(out, 's', pool.utf8(v.asString())); break;
            case DexFile.VALUE_TYPE:    tagged(out, 'c', pool.utf8(v.asTypeDescriptor())); break;
            case DexFile.VALUE_ENUM: {
                DexFile.FieldRef f = v.asField();
                out.putU1('e');
                out.putU2(pool.utf8(f.declaringClass()));   // descriptor, not internal name
                out.putU2(pool.utf8(f.name()));
                break;
            }
            case DexFile.VALUE_ARRAY: {
                List<DexFile.Value> items = v.asArray();
                out.putU1('[');
                out.putU2(items.size());
                for (DexFile.Value x : items) writeElementValue(pool, out, x);
                break;
            }
            case DexFile.VALUE_ANNOTATION:
                out.putU1('@');
                writeAnnotation(pool, out, v.asAnnotation());
                break;
            default:
                // representable() gates this; reaching here is a bug, and an
                // unwritten element_value would desynchronise the whole
                // attribute rather than lose one value.
                throw new IllegalStateException("unrepresentable element_value tag 0x"
                        + Integer.toHexString(v.tag()));
        }
    }

    private static void tagged(ConstantPool.ByteVector out, char tag, int cpIndex) {
        out.putU1(tag);
        out.putU2(cpIndex);
    }

    // ==================================================================
    // dropUnrepresentable
    // ==================================================================

    /**
     * Whether every value in this annotation has an element_value form.
     *
     * JVMS 4.7.16.1 defines thirteen tags and DEX's encoded_value has more:
     * VALUE_NULL, VALUE_FIELD, VALUE_METHOD, VALUE_METHOD_TYPE and
     * VALUE_METHOD_HANDLE have no annotation encoding at all. They cannot come
     * from Java source (an annotation member may not be declared with those
     * types), so in practice this only fires on a system annotation, which is
     * translated into a real attribute elsewhere and never reaches here.
     *
     * An annotation with a missing member is worse than an absent one: the
     * reflective reader throws IncompleteAnnotationException at first access,
     * far from the cause. So the whole annotation is dropped, not the element.
     */
    private static boolean representable(DexFile.Annotation a) {
        if (a.type() == null) return false;
        for (DexFile.AnnotationElement e : a.elements()) {
            if (e.name() == null || e.value() == null) return false;
            if (!representable(e.value())) return false;
        }
        return true;
    }

    private static boolean representable(DexFile.Value v) {
        switch (v.tag()) {
            case DexFile.VALUE_BYTE:
            case DexFile.VALUE_SHORT:
            case DexFile.VALUE_CHAR:
            case DexFile.VALUE_INT:
            case DexFile.VALUE_LONG:
            case DexFile.VALUE_FLOAT:
            case DexFile.VALUE_DOUBLE:
            case DexFile.VALUE_BOOLEAN:
                return true;
            case DexFile.VALUE_STRING:  return v.asString() != null;
            case DexFile.VALUE_TYPE:    return v.asTypeDescriptor() != null;
            case DexFile.VALUE_ENUM:
                return v.asField() != null && v.asField().declaringClass() != null
                        && v.asField().name() != null;
            case DexFile.VALUE_ARRAY: {
                List<DexFile.Value> items = v.asArray();
                if (items == null || items.size() > 0xffff) return false;
                for (DexFile.Value x : items) if (x == null || !representable(x)) return false;
                return true;
            }
            case DexFile.VALUE_ANNOTATION:
                return v.asAnnotation() != null && representable(v.asAnnotation());
            default:
                return false;
        }
    }
}
