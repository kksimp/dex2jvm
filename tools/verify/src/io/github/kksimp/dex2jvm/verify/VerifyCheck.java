// VerifyCheck -- gate G8: run HotSpot's REAL split verifier over every class in a
// converted jar and count what it rejects.
//
// G6 (BranchTargetCheck) and G7 (StackDepthCheck) are offline structural checks.
// This one asks the JVM itself. Each class is loaded through a URLClassLoader,
// which makes it a "remote" class, so HotSpot verifies it at link time
// (BytecodeVerificationRemote is on by default). Linking is forced with
// Class.getDeclaredMethods(), which does NOT run <clinit>, so no app code runs.
//
// Every class lands in exactly one bucket:
//
//   OK       linked and verified
//   VERIFY   java.lang.VerifyError -- the split verifier rejected the bytecode or
//            its StackMapTable. This is the number to drive to zero.
//   FORMAT   java.lang.ClassFormatError -- the class file itself is malformed
//   MISSING  NoClassDefFoundError -- a supertype or signature type is not on the
//            classpath, so the class could not be linked and was NEVER verified.
//            Pass the app's library surface (an android.jar) with --classpath to
//            shrink this bucket; a large MISSING count means G8 saw less than
//            the class count suggests.
//   OTHER    any other LinkageError (IncompatibleClassChangeError,
//            IllegalAccessError, ...): a disagreement between the class and the
//            classpath it was linked against, not a verifier verdict
//
//   java -cp dex2jvm-verify.jar io.github.kksimp.dex2jvm.verify.VerifyCheck \
//        [--classpath <android.jar>] [--show N] <out.jar>
//
// The last line is "RESULT: PASS" when VERIFY and FORMAT are both 0.

package io.github.kksimp.dex2jvm.verify;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class VerifyCheck {
    private VerifyCheck() {}

    public static void main(String[] args) throws Exception {
        String classpath = "";
        int show = 20;
        String jar = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--classpath": classpath = args[++i]; break;
                case "--show":      show = Integer.parseInt(args[++i]); break;
                default:            jar = args[i];
            }
        }
        if (jar == null) {
            System.err.println("usage: VerifyCheck [--classpath <path>] [--show N] <out.jar>");
            System.exit(2);
        }

        List<URL> urls = new ArrayList<>();
        urls.add(new File(jar).toURI().toURL());
        for (String p : classpath.split(File.pathSeparator)) {
            if (!p.isEmpty()) urls.add(new File(p).toURI().toURL());
        }

        List<String> names = new ArrayList<>();
        try (ZipFile z = new ZipFile(jar)) {
            for (ZipEntry e : Collections.list(z.entries())) {
                String n = e.getName();
                if (n.endsWith(".class") && !n.startsWith("META-INF/")) {
                    names.add(n.substring(0, n.length() - 6).replace('/', '.'));
                }
            }
        }
        Collections.sort(names);

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String k : new String[] {"OK", "VERIFY", "FORMAT", "MISSING", "OTHER"}) counts.put(k, 0);
        List<String> verifyMsgs = new ArrayList<>();
        List<String> otherMsgs = new ArrayList<>();

        try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]),
                ClassLoader.getPlatformClassLoader())) {
            for (String name : names) {
                String bucket;
                try {
                    Class<?> c = Class.forName(name, false, loader);
                    c.getDeclaredMethods();
                    bucket = "OK";
                } catch (VerifyError e) {
                    bucket = "VERIFY";
                    verifyMsgs.add(name + ": " + oneLine(e.getMessage()));
                } catch (ClassFormatError e) {
                    bucket = "FORMAT";
                    verifyMsgs.add(name + " [format]: " + oneLine(e.getMessage()));
                } catch (NoClassDefFoundError | ClassNotFoundException e) {
                    bucket = "MISSING";
                } catch (LinkageError e) {
                    bucket = "OTHER";
                    otherMsgs.add(name + ": " + e.getClass().getSimpleName() + ": "
                            + oneLine(e.getMessage()));
                } catch (Throwable t) {
                    bucket = "OTHER";
                    otherMsgs.add(name + ": " + t);
                }
                counts.merge(bucket, 1, Integer::sum);
            }
        }

        for (int i = 0; i < Math.min(show, verifyMsgs.size()); i++) {
            System.out.println("VERIFY " + verifyMsgs.get(i));
        }
        for (int i = 0; i < Math.min(show, otherMsgs.size()); i++) {
            System.out.println("OTHER  " + otherMsgs.get(i));
        }
        StringBuilder sb = new StringBuilder("SUMMARY classes=" + names.size());
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            sb.append(' ').append(e.getKey().toLowerCase()).append('=').append(e.getValue());
        }
        System.out.println(sb);
        boolean pass = counts.get("VERIFY") == 0 && counts.get("FORMAT") == 0;
        System.out.println("RESULT: " + (pass ? "PASS" : "FAIL"));
        System.exit(pass ? 0 : 1);
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replaceAll("\\s+", " ");
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
