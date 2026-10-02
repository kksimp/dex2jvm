// Options -- process-wide converter settings that change what is EMITTED.
//
// Every field is read during conversion, so set them BEFORE DexConverter.open()
// and do not change them while a session is converting. The CLI (Main) sets
// them from its flags; an embedding application sets them directly.
//
// Each field can also be seeded from a system property or an environment
// variable, so a build that wraps the converter can configure it without code:
//
//   field              system property              environment variable
//   syntheticPrefix    dex2jvm.syntheticPrefix      DEX2JVM_SYNTHETIC_PREFIX
//   unsafeRedirect     dex2jvm.redirectUnsafe       DEX2JVM_REDIRECT_UNSAFE
//   exitRedirect       dex2jvm.redirectExit         DEX2JVM_REDIRECT_EXIT
//   threads            dex2jvm.threads              DEX2JVM_THREADS
//
// The system property wins over the environment variable.

package io.github.kksimp.dex2jvm;

public final class Options {
    private Options() {}

    /**
     * Prefix for the synthetic members the converter invents (outlined and
     * split method parts, spill fields, the message of an oversize-method
     * stub). The JVM never sees it as anything but a name; it only has to be
     * something an app's own code will not already use.
     */
    public static volatile String syntheticPrefix = setting("dex2jvm.syntheticPrefix",
            "DEX2JVM_SYNTHETIC_PREFIX", "dex2jvm");

    /**
     * When non-null, the internal name of a class that receives every routed
     * {@code sun.misc.Unsafe} call (see UnsafeRepoint), as a static method
     * taking the Unsafe receiver as its first parameter. Null (the default)
     * leaves Unsafe calls exactly as the DEX wrote them.
     *
     * <p>This exists for hosts that run Android code on a JVM whose Unsafe
     * semantics differ from ART's (field offsets in particular). A plain
     * conversion has no reason to set it.
     */
    public static volatile String unsafeRedirect = setting("dex2jvm.redirectUnsafe",
            "DEX2JVM_REDIRECT_UNSAFE", null);

    /**
     * When non-null, the internal name of a class that receives
     * {@code System.exit(int)} as {@code systemExit(I)V}, and
     * {@code Runtime.exit/halt(int)} as {@code runtimeExit/runtimeHalt
     * (Ljava/lang/Runtime;I)V}. Null (the default) leaves them alone.
     *
     * <p>For hosts where "the app exits" must not mean "the JVM exits".
     */
    public static volatile String exitRedirect = setting("dex2jvm.redirectExit",
            "DEX2JVM_REDIRECT_EXIT", null);

    /**
     * Worker threads for a bulk conversion; 0 means min(cores, 8). Output is
     * byte-identical at every thread count.
     */
    public static volatile int threads = parseThreads(setting("dex2jvm.threads",
            "DEX2JVM_THREADS", null));

    /**
     * Where the class-hierarchy oracle reads framework and library classes
     * from (header only, nothing is loaded). Null means the converter's own
     * class loader. Point it at an android.jar so merges of framework types
     * produce precise stack-map frames instead of java/lang/Object.
     */
    public static volatile ClassLoader hierarchyLoader = null;

    private static String setting(String property, String env, String dflt) {
        String v = System.getProperty(property);
        if (v == null || v.isEmpty()) v = System.getenv(env);
        return (v == null || v.isEmpty()) ? dflt : v;
    }

    private static int parseThreads(String v) {
        if (v == null) return 0;
        try {
            return Math.max(0, Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
