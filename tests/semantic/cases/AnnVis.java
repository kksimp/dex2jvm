// dexsem: no-r8
// The r8 mode DESTROYS this fixture rather than exercising it: the harness's
// shared keep file has no -keepattributes, so R8 deletes the annotation USES
// themselves (measured 2026-08-19: the converted use classes carry no
// annotation attribute at all, so even a correct converter prints
// runtime-present false and the case would mismatch forever for a non-bug
// reason). The R8-STRIPPED-@Retention shape this family exists for can only be
// reproduced deterministically by building it by hand (a use compiled against a
// RUNTIME-retained type, then a retention-less build of the same type dexed
// alongside it); the d8 modes still guard the surrounding visibility behaviour.
//
// Annotation VISIBILITY across the DEX round trip -- the R8-stripped-@Retention
// family.
//
// On Android, runtime visibility is a PER-USE byte in the DEX
// (annotation_item.visibility) and ART never reads the type's @Retention; R8
// therefore strips @Retention from annotation TYPE declarations as dead
// metadata (TikTok 46.4.3: zero occurrences across 37 dex files). HotSpot
// derives visibility from the TYPE (missing @Retention = CLASS) and silently
// filters the use out of getAnnotations() -- a null, never an exception. The
// converter must emit a shape HotSpot reads the way ART reads the DEX.
//
// What each printed line pins:
//   runtime-*   a RUNTIME-retained annotation's use must survive conversion
//               and answer getAnnotation() with its element values intact.
//               Under the r8 mode the type's own @Retention is STRIPPED from
//               the dex (the TikTok shape) while the use stays runtime-visible,
//               so this line is exactly that bug: it reads NULL unless
//               the converter re-establishes runtime retention on the type.
//   build-*     a retention-less type's use is CLASS-retained: javac puts it in
//               RuntimeInvisibleAnnotations, d8 marks it VISIBILITY_BUILD, and
//               reflection must NOT see it on either side. This is the
//               don't-over-expose direction: the converter synthesizing
//               @Retention(RUNTIME) on retention-less TYPES must not leak
//               build-visibility USES into getAnnotations().
//   count-*     getAnnotations().length for both users, same two directions.
//
// Output is names-free (annotation element VALUES only) so the r8 mode's
// renaming cannot fail the case for a non-bug reason.
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public class AnnVis {
    @Retention(RetentionPolicy.RUNTIME)
    @interface Runt {
        String key();
        int n() default 7;
    }

    // No @Retention: source-default CLASS retention, the build-time-only shape.
    @interface Bld {
        String key();
    }

    @Runt(key = "alpha")
    static class UseR {}

    @Bld(key = "beta")
    static class UseB {}

    public static void main(String[] args) {
        Runt r = UseR.class.getAnnotation(Runt.class);
        System.out.println("runtime-present " + (r != null));
        System.out.println("runtime-values " + (r == null ? "-" : r.key() + "/" + r.n()));
        Bld b = UseB.class.getAnnotation(Bld.class);
        System.out.println("build-present " + (b != null));
        System.out.println("count-runtime " + count(UseR.class.getAnnotations()));
        System.out.println("count-build " + count(UseB.class.getAnnotations()));
    }

    // Counts rather than prints: annotation toString carries the TYPE NAME,
    // which the r8 mode renames.
    private static int count(Annotation[] a) {
        return a.length;
    }
}
